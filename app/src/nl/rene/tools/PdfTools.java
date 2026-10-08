package nl.rene.tools;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.text.Layout;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PDF-gereedschap: plaatjes naar één PDF, een PDF splitsen of pagina's eruit halen, en tekst of een e-mail als PDF.
 * Alles gebeurt op de telefoon. Werkbestanden staan in de cache-map; de gemaakte PDF's sla je zelf op of deel je.
 */
final class PdfTools {

    private PdfTools() { }

    static final long MAX_IN = 120L * 1024 * 1024;
    static final int MAX_IMAGES = 100;

    static File dir(Context c, String n) { File d = new File(c.getCacheDir(), n); d.mkdirs(); return d; }
    static File imgDir(Context c) { return dir(c, "pdf-img"); }
    static File outDir(Context c) { return dir(c, "pdf-out"); }
    static File inFile(Context c) { return new File(dir(c, "pdf-in"), "invoer.pdf"); }

    // ---------- algemeen ----------

    static String displayName(Context c, Uri u) {
        try (android.database.Cursor cur = c.getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getString(0);
        } catch (Exception ignored) { }
        String s = u.getLastPathSegment();
        return s == null ? "document" : s;
    }

    /**
     * Mag deze app dit bestand namens een andere app lezen? Alleen content://-adressen; niet van de eigen
     * (privé) providers van Rene's Tools (behalve gemaakte PDF's), en niet van providers die een toestemming
     * vragen die deze app heeft (bijv. contacten of sms): anders kan een andere app via "Delen" of "Openen"
     * gegevens laten ophalen die hij zelf niet mag lezen.
     */
    static void checkUri(Context c, Uri u) throws Exception {
        if (u == null || !"content".equals(u.getScheme()) || u.getAuthority() == null) throw new Exception("Dit bestand kan niet geopend worden");
        String auth = u.getAuthority();
        if (PdfProvider.AUTH.equals(auth)) return;
        android.content.pm.ProviderInfo pi = c.getPackageManager().resolveContentProvider(auth.contains("@") ? auth.substring(auth.indexOf('@') + 1) : auth, 0);
        if (pi == null) return; // onbekend: openen lukt dan vanzelf niet
        if (c.getPackageName().equals(pi.packageName)) throw new Exception("Dit bestand kan niet geopend worden");
        String perm = pi.readPermission; // (android:permission telt hier ook mee)
        if (perm != null && c.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED && !"media".equals(pi.authority))
            throw new Exception("Dit bestand kan niet geopend worden");
    }

    /**
     * Kwam dit van een andere app mét leesrecht (FLAG_GRANT_READ_URI_PERMISSION)? Android staat dat alleen toe als
     * de afzender het bestand zelf mag lezen, dus zo kan een app ons niet laten lezen wat hij zelf niet mag
     * (bijv. via de mediabibliotheek of de backup-map). Onze eigen gemaakte PDF's zijn altijd goed.
     */
    static boolean grantedBySender(android.content.Intent i, List<Uri> uris) {
        boolean flag = i != null && (i.getFlags() & android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0;
        for (Uri u : uris) if (u != null && !PdfProvider.AUTH.equals(u.getAuthority()) && !flag) return false;
        return true;
    }

    static void copy(Context c, Uri u, File to, long max) throws Exception {
        checkUri(c, u);
        try (InputStream in = c.getContentResolver().openInputStream(u); OutputStream o = new FileOutputStream(to)) {
            if (in == null) throw new Exception("Bestand niet te openen");
            byte[] b = new byte[1 << 16]; long n = 0; int r;
            while ((r = in.read(b)) > 0) { n += r; if (n > max) throw new Exception("Bestand is te groot"); o.write(b, 0, r); }
        } catch (Exception e) { to.delete(); throw e; }
    }

    static byte[] readAll(File f, long max) throws Exception {
        if (f.length() > max) throw new Exception("Bestand is te groot");
        byte[] b = new byte[(int) f.length()];
        try (InputStream in = new FileInputStream(f)) { int off = 0, r; while (off < b.length && (r = in.read(b, off, b.length - off)) > 0) off += r; }
        return b;
    }

    /** Veilige bestandsnaam voor een PDF (zonder .pdf), maximaal 60 tekens. */
    static String base(String name) {
        String n = name == null ? "" : name.replaceAll("(?i)\\.(pdf|jpe?g|png|webp|heic|eml|txt|html?)$", "");
        n = n.replaceAll("[^\\p{L}\\p{N} ._()-]+", "_").replaceAll("\\s+", " ").trim();
        if (n.length() > 60) n = n.substring(0, 60).trim();
        return n.isEmpty() ? "document" : n;
    }

    /** Uniek uitvoerbestand in de uitvoermap. */
    static File outFile(Context c, String baseName) {
        File d = outDir(c), f = new File(d, baseName + ".pdf");
        for (int i = 2; f.exists(); i++) f = new File(d, baseName + " (" + i + ").pdf");
        return f;
    }

    static String outputs(Context c) {
        JSONArray a = new JSONArray();
        File[] fs = outDir(c).listFiles();
        if (fs != null) {
            java.util.Arrays.sort(fs, (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
            for (File f : fs) {
                if (!f.getName().endsWith(".pdf")) continue;
                if (System.currentTimeMillis() - f.lastModified() > 7L * 86_400_000L) { f.delete(); continue; } // na een week opruimen
                try { a.put(new JSONObject().put("name", f.getName()).put("size", f.length()).put("t", f.lastModified())); } catch (Exception ignored) { }
            }
        }
        return a.toString();
    }

    static File output(Context c, String name) throws Exception {
        File d = outDir(c).getCanonicalFile(), f = new File(d, name).getCanonicalFile();
        if (!d.equals(f.getParentFile()) || !f.isFile()) throw new Exception("Bestand niet gevonden");
        return f;
    }

    static void clear(File d) { File[] fs = d.listFiles(); if (fs != null) for (File f : fs) f.delete(); }

    // ---------- plaatjes ----------

    static final List<File> images = new ArrayList<>();
    /** Eigen slot voor de plaatjeslijst: het splitsen (lang bezig) mag het scherm niet laten wachten. */
    static final Object IMG = new Object();
    /** Voorbeeld en maten per plaatje (worden maar één keer berekend). */
    static final java.util.Map<File, JSONObject> meta = new java.util.HashMap<>();

    static int addImage(Context c, Uri u) throws Exception {
        synchronized (IMG) { if (images.size() >= MAX_IMAGES) throw new Exception("Maximaal " + MAX_IMAGES + " plaatjes"); }
        File f = new File(imgDir(c), System.nanoTime() + ".img");
        copy(c, u, f, 40L * 1024 * 1024);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) { f.delete(); throw new Exception(displayName(c, u) + " is geen plaatje"); }
        synchronized (IMG) {
            if (images.size() >= MAX_IMAGES) { f.delete(); throw new Exception("Maximaal " + MAX_IMAGES + " plaatjes"); }
            images.add(f);
            return images.size();
        }
    }

    static void addImageFile(File f) { synchronized (IMG) { if (images.size() < MAX_IMAGES) images.add(f); else f.delete(); } }

    static String imagesJson(Context c) {
        List<File> snap;
        synchronized (IMG) { snap = new ArrayList<>(images); meta.keySet().retainAll(snap); }
        JSONArray a = new JSONArray();
        for (int i = 0; i < snap.size(); i++) {
            File f = snap.get(i);
            JSONObject m;
            synchronized (IMG) { m = meta.get(f); }
            try {
                if (m == null) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(f.getPath(), o);
                    int rot = rotation(f);
                    boolean swap = rot == 90 || rot == 270;
                    m = new JSONObject().put("w", swap ? o.outHeight : o.outWidth).put("h", swap ? o.outWidth : o.outHeight).put("thumb", thumb(f, 160));
                    synchronized (IMG) { if (images.contains(f)) meta.put(f, m); }
                }
                a.put(new JSONObject(m.toString()).put("i", i));
            } catch (Throwable e) { /* overslaan */ }
        }
        return a.toString();
    }

    static void moveImage(int i, int to) {
        synchronized (IMG) {
            if (i < 0 || i >= images.size() || to < 0 || to >= images.size()) return;
            images.add(to, images.remove(i));
        }
    }

    static void removeImage(int i) { synchronized (IMG) { if (i >= 0 && i < images.size()) { File f = images.remove(i); meta.remove(f); f.delete(); } } }

    static void clearImages(Context c) { synchronized (IMG) { images.clear(); meta.clear(); clear(imgDir(c)); } }

    /** Draaiing volgens EXIF (foto's van de camera staan vaak "op hun kant" opgeslagen). */
    static int rotation(File f) {
        try {
            android.media.ExifInterface e = new android.media.ExifInterface(f.getPath());
            switch (e.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                case 6: return 90; case 3: return 180; case 8: return 270; default: return 0;
            }
        } catch (Exception e) { return 0; }
    }

    static boolean isJpeg(File f) {
        try (InputStream in = new FileInputStream(f)) { return in.read() == 0xFF && in.read() == 0xD8; } catch (Exception e) { return false; }
    }

    /** Klein voorbeeld als data-URL. */
    static String thumb(File f, int max) throws Exception {
        Bitmap b = decode(f, max);
        if (b == null) return "";
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 70, o);
        b.recycle();
        return "data:image/jpeg;base64," + android.util.Base64.encodeToString(o.toByteArray(), android.util.Base64.NO_WRAP);
    }

    /** Plaatje inlezen, verkleind tot hooguit max pixels (langste zijde), rechtgezet volgens EXIF, op een witte achtergrond. */
    static Bitmap decode(File f, int max) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int s = 1;
        while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= max) s *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = s;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) return null;
        float scale = Math.min(1f, (float) max / Math.max(b.getWidth(), b.getHeight()));
        int rot = rotation(f);
        Matrix m = new Matrix();
        m.postScale(scale, scale);
        if (rot != 0) m.postRotate(rot);
        if (scale < 1f || rot != 0) {
            Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (r != b) b.recycle();
            b = r;
        }
        if (b.hasAlpha()) { // doorzichtig (png): op wit
            Bitmap w = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(w);
            cv.drawColor(Color.WHITE);
            cv.drawBitmap(b, 0, 0, null);
            b.recycle();
            b = w;
        }
        return b;
    }

    /** Alle plaatjes naar één PDF. a4 = op A4 met marge; anders elke pagina zo groot als het plaatje. */
    static File imagesToPdf(Context c, boolean a4, String name) throws Exception {
        List<File> snap;
        synchronized (IMG) { snap = new ArrayList<>(images); }
        if (snap.isEmpty()) throw new Exception("Kies eerst plaatjes");
        List<PdfWriter.Page> pages = new ArrayList<>();
        for (File f : snap) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            byte[] jpeg; int w, h;
            if (isJpeg(f) && rotation(f) == 0 && Math.max(o.outWidth, o.outHeight) <= 4000 && f.length() < 8_000_000 && !cmyk(f)) {
                jpeg = readAll(f, 8_000_000); w = o.outWidth; h = o.outHeight; // origineel: geen kwaliteitsverlies
            } else {
                Bitmap b = decode(f, 2480); // A4 bij 300 dpi
                if (b == null) throw new Exception("Een plaatje is niet te lezen");
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                b.compress(Bitmap.CompressFormat.JPEG, 88, bo);
                w = b.getWidth(); h = b.getHeight();
                b.recycle();
                jpeg = bo.toByteArray();
            }
            pages.add(PdfWriter.layout(jpeg, w, h, false, a4, 28));
        }
        File out = outFile(c, base(name == null || name.trim().isEmpty() ? "Plaatjes " + stamp() : name));
        try (OutputStream o = new java.io.BufferedOutputStream(new FileOutputStream(out))) { PdfWriter.write(o, pages, out.getName().replace(".pdf", "")); }
        catch (Exception e) { out.delete(); throw e; }
        // alleen de gebruikte plaatjes weghalen (er kunnen er intussen nieuwe bij zijn gekomen)
        synchronized (IMG) { for (File f : snap) { images.remove(f); meta.remove(f); f.delete(); } }
        return out;
    }

    /** CMYK-JPEG's (sommige scanners) laten we door Android opnieuw maken: PDF-lezers tonen ze anders verkeerd. */
    static boolean cmyk(File f) {
        try (InputStream in = new java.io.BufferedInputStream(new FileInputStream(f))) {
            byte[] b = new byte[65536]; int n = in.read(b);
            for (int i = 0; i + 9 < n; i++) if ((b[i] & 0xff) == 0xFF && ((b[i + 1] & 0xff) == 0xC0 || (b[i + 1] & 0xff) == 0xC2)) return (b[i + 9] & 0xff) == 4;
        } catch (Exception ignored) { }
        return false;
    }

    static String stamp() { return new java.text.SimpleDateFormat("d-M-yyyy HH.mm", Locale.US).format(new java.util.Date()); }

    // ---------- splitsen ----------

    static volatile String inName = "document";
    static volatile int inPages = 0;
    static volatile boolean inLossless = true;
    /** Slot voor de te splitsen PDF (los van de plaatjes). */
    static final java.util.concurrent.locks.ReentrantLock IN = new java.util.concurrent.locks.ReentrantLock();
    /** Groter dan dit: niet zelf ontleden (geheugen), maar Android de pagina's laten tekenen. */
    static final long LOSSLESS_MAX = 80L * 1024 * 1024;

    /** Een PDF kiezen om te splitsen: {name, pages, lossless} of {error}. */
    static String openPdf(Context c, Uri u) {
        IN.lock();
        try { return openPdfLocked(c, u); } finally { IN.unlock(); }
    }

    private static String openPdfLocked(Context c, Uri u) {
        try {
            File f = inFile(c);
            copy(c, u, f, MAX_IN);
            inName = base(displayName(c, u));
            return inspect(c, f);
        } catch (Throwable e) { return err(e); }
    }

    static String openPdfFile(Context c, File src, String name) {
        IN.lock();
        try { return openPdfFileLocked(c, src, name); } finally { IN.unlock(); }
    }

    private static String openPdfFileLocked(Context c, File src, String name) {
        try {
            File f = inFile(c);
            if (!src.renameTo(f)) { try (InputStream in = new FileInputStream(src); OutputStream o = new FileOutputStream(f)) { byte[] b = new byte[1 << 16]; int r; while ((r = in.read(b)) > 0) o.write(b, 0, r); } src.delete(); }
            inName = base(name);
            return inspect(c, f);
        } catch (Throwable e) { return err(e); }
    }

    static String inspect(Context c, File f) throws Exception {
        inPages = 0; inLossless = true;
        try {
            if (f.length() > LOSSLESS_MAX) throw new Exception("groot");
            PdfSplit s = new PdfSplit(readAll(f, MAX_IN));
            inPages = s.pageCount();
        } catch (PdfSplit.Encrypted e) {
            f.delete();
            throw e;
        } catch (OutOfMemoryError | Exception e) {
            // Niet zelf te lezen: Android kan de pagina's wel tekenen (dan worden ze als plaatje overgenomen)
            inLossless = false;
            try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY); PdfRenderer r = new PdfRenderer(pfd)) { inPages = r.getPageCount(); }
            catch (Exception e2) { f.delete(); throw new Exception("Deze PDF is niet te lezen"); }
        }
        return new JSONObject().put("name", inName).put("pages", inPages).put("lossless", inLossless).toString();
    }

    /** Voorbeeld van een pagina (0-gebaseerd) als data-URL. */
    static String pageThumb(Context c, int i, int width) {
        // Wordt vanuit het scherm aangeroepen: niet wachten als er net gesplitst of geopend wordt ("wait" = later nog eens)
        if (!IN.tryLock()) return "wait";
        try { return pageThumbLocked(c, i, width); } finally { IN.unlock(); }
    }

    private static String pageThumbLocked(Context c, int i, int width) {
        File f = inFile(c);
        if (!f.isFile()) return "";
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY); PdfRenderer r = new PdfRenderer(pfd)) {
            if (i < 0 || i >= r.getPageCount()) return "";
            try (PdfRenderer.Page p = r.openPage(i)) {
                int w = Math.max(40, Math.min(width, 400)), h = Math.max(1, Math.min(w * 4, Math.round(w * (float) p.getHeight() / Math.max(1, p.getWidth()))));
                Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                b.eraseColor(Color.WHITE);
                p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                b.compress(Bitmap.CompressFormat.JPEG, 70, o);
                b.recycle();
                return "data:image/jpeg;base64," + android.util.Base64.encodeToString(o.toByteArray(), android.util.Base64.NO_WRAP);
            }
        } catch (Throwable e) { return ""; }
    }

    /**
     * Splitsen. groups: elke groep wordt één nieuwe PDF (pagina's 0-gebaseerd).
     * Geeft de namen van de gemaakte bestanden.
     */
    static JSONArray split(Context c, List<List<Integer>> groups) throws Exception {
        IN.lock();
        try { return splitLocked(c, groups); } finally { IN.unlock(); }
    }

    private static JSONArray splitLocked(Context c, List<List<Integer>> groups) throws Exception {
        File f = inFile(c);
        if (!f.isFile()) throw new Exception("Kies eerst een PDF");
        JSONArray made = new JSONArray();
        PdfSplit s = inLossless ? new PdfSplit(readAll(f, MAX_IN)) : null;
        for (List<Integer> g : groups) {
            if (g.isEmpty()) continue;
            String label = g.size() == 1 ? "p" + (g.get(0) + 1) : "p" + range(g);
            File out = outFile(c, base(inName + " " + label));
            try (OutputStream o = new java.io.BufferedOutputStream(new FileOutputStream(out))) {
                if (s != null) s.extract(g, o);
                else raster(f, g, o);
            } catch (Exception e) { out.delete(); throw e; }
            made.put(out.getName());
        }
        return made;
    }

    /** "1-3,5" voor een bestandsnaam. */
    static String range(List<Integer> g) {
        StringBuilder b = new StringBuilder();
        int i = 0;
        while (i < g.size()) {
            int a = g.get(i), e = a;
            while (i + 1 < g.size() && g.get(i + 1) == e + 1) { e++; i++; }
            if (b.length() > 0) b.append(',');
            b.append(a + 1);
            if (e > a) b.append('-').append(e + 1);
            i++;
        }
        return b.toString();
    }

    /** Terugval: pagina's door Android laten tekenen en als plaatje (150 dpi) in een nieuwe PDF zetten. */
    static void raster(File f, List<Integer> g, OutputStream out) throws Exception {
        List<PdfWriter.Page> pages = new ArrayList<>();
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY); PdfRenderer r = new PdfRenderer(pfd)) {
            for (int i : g) {
                if (i < 0 || i >= r.getPageCount()) continue;
                try (PdfRenderer.Page p = r.openPage(i)) {
                    float sc = Math.min(150f / 72f, 2480f / Math.max(p.getWidth(), p.getHeight()));
                    int w = Math.max(1, Math.round(p.getWidth() * sc)), h = Math.max(1, Math.round(p.getHeight() * sc));
                    Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    b.eraseColor(Color.WHITE);
                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    b.compress(Bitmap.CompressFormat.JPEG, 85, bo);
                    b.recycle();
                    pages.add(new PdfWriter.Page(bo.toByteArray(), w, h, false, p.getWidth(), p.getHeight(), 0, 0, p.getWidth(), p.getHeight()));
                }
            }
        }
        PdfWriter.write(out, pages, "");
    }

    // ---------- tekst en e-mail ----------

    /**
     * Tekst (of opgemaakte tekst uit HTML) als PDF op A4. head = regels bovenaan (bijv. Van/Aan/Datum), mag leeg.
     */
    static File textToPdf(Context c, String title, String head, CharSequence body) throws Exception {
        final int W = 595, H = 842, M = 50;
        PdfDocument doc = new PdfDocument();
        try {
            TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            tp.setTextSize(11f); tp.setColor(Color.BLACK);
            TextPaint hp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            hp.setTextSize(16f); hp.setColor(Color.BLACK); hp.setTypeface(Typeface.DEFAULT_BOLD);
            TextPaint gp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            gp.setTextSize(9f); gp.setColor(0xFF555555);
            int tw = W - 2 * M;
            StaticLayout tl = title == null || title.isEmpty() ? null : StaticLayout.Builder.obtain(title, 0, title.length(), hp, tw).build();
            StaticLayout hl = head == null || head.isEmpty() ? null : StaticLayout.Builder.obtain(head, 0, head.length(), gp, tw).build();
            CharSequence txt = body == null || body.length() == 0 ? " " : body;
            StaticLayout bl = StaticLayout.Builder.obtain(txt, 0, txt.length(), tp, tw).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(2f, 1f).build();
            int pageNo = 0, line = 0, lines = bl.getLineCount();
            do {
                pageNo++;
                PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder(W, H, pageNo).create());
                Canvas cv = page.getCanvas();
                float y = M;
                if (pageNo == 1) {
                    if (tl != null) { cv.save(); cv.translate(M, y); tl.draw(cv); cv.restore(); y += tl.getHeight() + 6; }
                    if (hl != null) { cv.save(); cv.translate(M, y); hl.draw(cv); cv.restore(); y += hl.getHeight() + 6; }
                    if (tl != null || hl != null) { Paint lp = new Paint(); lp.setColor(0xFFCCCCCC); cv.drawLine(M, y, W - M, y, lp); y += 10; }
                }
                float avail = H - M - 20 - y;
                int first = line, top = bl.getLineTop(first);
                while (line < lines && bl.getLineBottom(line) - top <= avail) line++;
                if (line == first) line++; // een te hoge regel (bijv. groot plaatje): toch verder
                int bottom = bl.getLineBottom(line - 1);
                cv.save();
                cv.translate(M, y - top);
                cv.clipRect(0, top, tw, bottom);
                bl.draw(cv);
                cv.restore();
                Paint np = new Paint(Paint.ANTI_ALIAS_FLAG); np.setTextSize(8f); np.setColor(0xFF888888);
                cv.drawText(String.valueOf(pageNo), W / 2f, H - M / 2f, np);
                doc.finishPage(page);
            } while (line < lines && pageNo < 2000);
            File out = outFile(c, base(title == null || title.trim().isEmpty() ? "Tekst " + stamp() : title));
            try (OutputStream o = new java.io.BufferedOutputStream(new FileOutputStream(out))) { doc.writeTo(o); }
            catch (Exception e) { out.delete(); throw e; }
            return out;
        } finally { doc.close(); }
    }

    /** HTML naar opgemaakte tekst (zonder plaatjes, stijlen of scripts). */
    @SuppressWarnings("deprecation")
    static CharSequence fromHtml(String html) {
        String h = stripBlocks(html).replaceAll("(?i)<img[^>]*>", "");
        Spanned s = android.os.Build.VERSION.SDK_INT >= 24 ? android.text.Html.fromHtml(h, android.text.Html.FROM_HTML_MODE_COMPACT) : android.text.Html.fromHtml(h);
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder(s);
        for (int i = b.length() - 1; i >= 0; i--) if (b.charAt(i) == '￼') b.delete(i, i + 1);
        // lange reeksen lege regels inkorten (van achteren naar voren, dan blijven de plekken kloppen)
        for (int i = b.length() - 1; i >= 2; i--) if (b.charAt(i) == '\n' && b.charAt(i - 1) == '\n' && b.charAt(i - 2) == '\n') b.delete(i, i + 1);
        return b;
    }

    /** style/script/head/title-blokken weghalen, in één keer door de tekst (een regex kan bij kapotte HTML heel traag worden). */
    static String stripBlocks(String html) {
        // Alleen A-Z naar kleine letters: dan blijft de lengte gelijk (toLowerCase maakt van "İ" twee tekens)
        char[] lc = html.toCharArray();
        for (int k = 0; k < lc.length; k++) if (lc[k] >= 'A' && lc[k] <= 'Z') lc[k] = (char) (lc[k] + 32);
        String low = new String(lc);
        StringBuilder out = new StringBuilder(html.length());
        int i = 0;
        while (i < html.length()) {
            int lt = low.indexOf('<', i);
            if (lt < 0) { out.append(html, i, html.length()); break; }
            String tag = null;
            for (String t : new String[]{"style", "script", "head", "title"})
                if (low.startsWith(t, lt + 1) && lt + 1 + t.length() < low.length() && !Character.isLetterOrDigit(low.charAt(lt + 1 + t.length()))) { tag = t; break; }
            if (tag == null) { out.append(html, i, lt + 1); i = lt + 1; continue; }
            out.append(html, i, lt);
            int end = low.indexOf("</" + tag, lt);
            if (end < 0) break; // niet afgesloten: de rest weglaten
            int gt = low.indexOf('>', end);
            i = gt < 0 ? html.length() : gt + 1;
        }
        return out.toString();
    }

    /** E-mail (.eml) als PDF: kopregels bovenaan, dan de tekst. */
    static File mailToPdf(Context c, byte[] raw) throws Exception {
        MailParse.Mail m = MailParse.parse(raw);
        StringBuilder head = new StringBuilder();
        for (String[] k : new String[][]{{"from", "Van"}, {"to", "Aan"}, {"cc", "Cc"}, {"date", "Datum"}}) {
            String v = m.h(k[0]);
            if (!v.isEmpty()) head.append(k[1]).append(": ").append(v).append('\n');
        }
        if (!m.attachments.isEmpty()) head.append("Bijlagen (niet in deze PDF): ").append(android.text.TextUtils.join(", ", m.attachments)).append('\n');
        CharSequence body = !m.html.isEmpty() ? fromHtml(m.html) : m.text;
        String subject = m.h("subject");
        return textToPdf(c, subject.isEmpty() ? "E-mail" : subject, head.toString().trim(), body);
    }

    static String err(Throwable e) {
        String m = e instanceof OutOfMemoryError ? "Bestand is te groot voor deze telefoon" : e.getMessage() == null ? "Er ging iets mis" : e.getMessage();
        return "{\"error\":" + JSONObject.quote(m) + "}";
    }
}
