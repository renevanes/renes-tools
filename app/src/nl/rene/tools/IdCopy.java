package nl.rene.tools;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/** ID-bronnen blijven in privé-cache. Uitvoer bevat uitsluitend opnieuw getekende, afgeschermde pixels. */
final class IdCopy {
    static final int MAX_PAGES = 4, EDGE = 2400;
    private final List<Source> pages = new ArrayList<>();
    private File dir;
    private String revision = "";
    private boolean closed;
    private static boolean CLEANED;
    private static final java.util.Set<File> PENDING = new java.util.HashSet<>();

    static final class Source {
        final File file; final int page;
        Source(File file, int page) { this.file = file; this.page = page; }
    }

    synchronized boolean clear() {
        closed = true; revision = ""; pages.clear();
        retryPending();
        if (dir == null) return true;
        File previous = dir;
        boolean removed;
        try { removed = erase(previous); } catch (Throwable ignored) { removed = false; }
        if (removed) { dir = null; return true; }
        synchronized (PENDING) { PENDING.add(previous); }
        return false;
    }

    private static boolean erase(File d) {
        File[] files = d.listFiles(); boolean ok = true;
        if (files != null) for (File f : files) if (!f.delete() && f.exists()) ok = false;
        return ok && (d.delete() || !d.exists());
    }

    private static void retryPending() {
        synchronized (PENDING) { java.util.Iterator<File> it = PENDING.iterator(); while (it.hasNext()) { File d = it.next(); try { if (erase(d)) it.remove(); } catch (Throwable ignored) { } } }
    }

    static synchronized void cleanStale(Context c) {
        if (CLEANED || !App.unlocked(c)) return;
        CLEANED = true;
        File[] dirs = c.getCacheDir().listFiles();
        if (dirs != null) for (File d : dirs) if (d.isDirectory() && d.getName().startsWith("id-copy-")) { if (!erase(d)) synchronized (PENDING) { PENDING.add(d); } }
        File stage=new File(c.getCacheDir(),"master-export");if(stage.isDirectory()&&!erase(stage))synchronized(PENDING){PENDING.add(stage);}
        File[] outputs = new File(c.getCacheDir(), "pdf-out").listFiles();
        if (outputs != null) for (File f : outputs) if (f.getName().startsWith("idcopy-") && f.getName().endsWith(".tmp") && !f.delete())
            synchronized (PENDING) { PENDING.add(f); }
    }

    /** Zonder balk; ook gebruikt door tests/telefoon/idcopy/IdCopyDeviceTest (via reflectie). */
    JSONObject add(Context c, List<Uri> uris) throws Exception { return add(c, uris, Voortgang.GEEN); }
    synchronized JSONObject add(Context c, List<Uri> uris, Voortgang v) throws Exception {
        if (closed) throw new Exception(Taal.T("Kies de ID-kopie opnieuw"));
        cleanStale(c); retryPending();
        if (uris == null || uris.isEmpty()) return new JSONObject().put("cancelled", true);
        if (uris.size() > MAX_PAGES) throw new Exception(Taal.T("Kies maximaal vier pagina's"));
        if (dir == null) { dir = new File(c.getCacheDir(), "id-copy-" + java.util.UUID.randomUUID()); if (!dir.mkdirs()) throw new Exception(Taal.T("Werkbestand maken lukt niet")); }
        List<Source> added = new ArrayList<>(); List<File> copied = new ArrayList<>();
        try {
            for (int k = 0; k < uris.size(); k++) {
                Uri u = uris.get(k); v.bestand(k); v.stap("lezen", 0, 0);
                File f = new File(dir, java.util.UUID.randomUUID().toString()); copied.add(f);
                PdfTools.copy(c, u, f, 24L * 1024 * 1024); v.stap("verwerken", 0, 0);
                if (isPdf(f)) {
                    try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY); PdfRenderer r = new PdfRenderer(fd)) {
                        if (pages.size() + added.size() + r.getPageCount() > MAX_PAGES) throw new Exception(Taal.T("Kies maximaal vier pagina's"));
                        for (int i = 0; i < r.getPageCount(); i++) added.add(new Source(f, i));
                    }
                } else {
                    BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true; BitmapFactory.decodeFile(f.getPath(), o);
                    if (o.outWidth <= 0 || o.outHeight <= 0) throw new Exception(Taal.T("Kies een leesbare foto of PDF"));
                    added.add(new Source(f, -1));
                }
                if (pages.size() + added.size() > MAX_PAGES) throw new Exception(Taal.T("Kies maximaal vier pagina's"));
            }
        } catch (Throwable e) {
            for (File f : copied) f.delete();
            if (e instanceof Exception) throw (Exception) e;
            throw new Exception(Taal.T("Dit bestand is te groot of niet leesbaar"));
        }
        pages.addAll(added); revision = java.util.UUID.randomUUID().toString();
        return new JSONObject().put("revision", revision).put("pages", pages.size());
    }

    /** Kaartfoto's (JPEG) uit Stamgegevens als pagina's; de bytes worden daarna gewist. Zelfde werkmap en grenzen als kiezen. */
    synchronized JSONObject addImages(Context c, List<byte[]> images) throws Exception {
        if (closed) throw new Exception(Taal.T("Kies de ID-kopie opnieuw"));
        cleanStale(c); retryPending();
        if (images == null || images.isEmpty()) return new JSONObject().put("cancelled", true);
        if (pages.size() + images.size() > MAX_PAGES) throw new Exception(Taal.T("Kies maximaal vier pagina's"));
        if (dir == null) { dir = new File(c.getCacheDir(), "id-copy-" + java.util.UUID.randomUUID()); if (!dir.mkdirs()) throw new Exception(Taal.T("Werkbestand maken lukt niet")); }
        List<Source> added = new ArrayList<>();
        try {
            for (byte[] b : images) {
                File f = new File(dir, java.util.UUID.randomUUID().toString());
                try (FileOutputStream o = new FileOutputStream(f)) { o.write(b); }
                BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true; BitmapFactory.decodeFile(f.getPath(), o);
                if (o.outWidth <= 0 || o.outHeight <= 0) { f.delete(); throw new Exception(Taal.T("Kies een leesbare foto of PDF")); }
                added.add(new Source(f, -1));
            }
        } catch (Throwable e) {
            for (Source s : added) s.file.delete();
            if (e instanceof Exception) throw (Exception) e;
            throw new Exception(Taal.T("Dit bestand is te groot of niet leesbaar"));
        } finally { for (byte[] b : images) java.util.Arrays.fill(b, (byte) 0); }
        pages.addAll(added); revision = java.util.UUID.randomUUID().toString();
        return new JSONObject().put("revision", revision).put("pages", pages.size());
    }

    private static boolean isPdf(File f) throws Exception {
        byte[] header = new byte[5];
        try (FileInputStream in = new FileInputStream(f)) { return in.read(header) == 5 && "%PDF-".equals(new String(header, java.nio.charset.StandardCharsets.US_ASCII)); }
    }

    private void check(String token) throws Exception {
        if (closed || revision.isEmpty() || !revision.equals(token) || pages.isEmpty()) throw new Exception(Taal.T("Kies de ID-kopie opnieuw"));
    }

    private Bitmap render(int index, int turn, int edge) throws Exception {
        if (index < 0 || index >= pages.size() || turn < 0 || turn > 3) throw new Exception(Taal.T("Ongeldige pagina"));
        Source source = pages.get(index); Bitmap image;
        if (source.page < 0) image = PdfTools.decode(source.file, edge);
        else try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(source.file, ParcelFileDescriptor.MODE_READ_ONLY); PdfRenderer r = new PdfRenderer(fd); PdfRenderer.Page p = r.openPage(source.page)) {
            float scale = (float) edge / Math.max(p.getWidth(), p.getHeight());
            image = Bitmap.createBitmap(Math.max(1, Math.round(p.getWidth() * scale)), Math.max(1, Math.round(p.getHeight() * scale)), Bitmap.Config.ARGB_8888);
            image.eraseColor(Color.WHITE); p.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
        }
        if (image == null) throw new Exception(Taal.T("Kies een leesbare foto of PDF"));
        if (turn != 0) { Matrix m = new Matrix(); m.postRotate(turn * 90); Bitmap rotated = Bitmap.createBitmap(image, 0, 0, image.getWidth(), image.getHeight(), m, true); if (rotated != image) image.recycle(); image = rotated; }
        // Ook transparante plaatjes krijgen een vaste achtergrond. Werk altijd op eigen schrijfbare pixels.
        Bitmap flat = Bitmap.createBitmap(image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);
        flat.eraseColor(Color.WHITE); new Canvas(flat).drawBitmap(image, 0, 0, null); image.recycle(); return flat;
    }

    synchronized JSONObject preview(String token, int page, int turn) throws Exception {
        check(token); Bitmap b = render(page, turn, 1200);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, 88, out);
            return new JSONObject().put("revision", token).put("page", page).put("turn", turn).put("w", b.getWidth()).put("h", b.getHeight())
                    .put("image", "data:image/jpeg;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP));
        } finally { b.recycle(); }
    }

    synchronized File save(Context c, String token, String json) throws Exception {
        return save(c,token,json,false);
    }
    synchronized File save(Context c,String token,String json,boolean staged)throws Exception {
        check(token);
        if (json == null || json.length() > 40000) throw new Exception(Taal.T("Ongeldige afscherming"));
        JSONObject plan = new JSONObject(json); JSONArray entries = plan.getJSONArray("pages");
        String watermark = plan.optString("watermark").trim();
        if (entries.length() != pages.size() || watermark.length() < 12 || watermark.length() > 300 || watermark.split("\n", -1).length > 4)
            throw new Exception(Taal.T("Vul ontvanger, doel en datum in"));
        int masks = 0;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject p = entries.getJSONObject(i);
            if (!p.optBoolean("checked")) throw new Exception(Taal.T("Controleer elke pagina voordat je de kopie maakt"));
            int turn = p.getInt("turn"); if (turn < 0 || turn > 3) throw new Exception(Taal.T("Ongeldige pagina"));
            JSONArray rects = p.getJSONArray("rects"); if (rects.length() > 60) throw new Exception(Taal.T("Maximaal 60 vlakken per pagina"));
            for (int n = 0; n < rects.length(); n++) { JSONArray r = rects.getJSONArray(n); if (r.length() != 4) throw new Exception(Taal.T("Ongeldig afschermvlak")); IdCopyPixels.bounds(r.getDouble(0), r.getDouble(1), r.getDouble(2), r.getDouble(3), 100, 100); }
            masks += rects.length();
        }
        if (masks == 0) throw new Exception(Taal.T("Scherm eerst de gegevens af die je niet wilt delen"));
        List<PdfWriter.Page> output = new ArrayList<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject p = entries.getJSONObject(i); Bitmap b = render(i, p.getInt("turn"), EDGE);
            try {
                int w = b.getWidth(), h = b.getHeight(); int[] pixels = new int[w * h]; b.getPixels(pixels, 0, w, 0, 0, w, h);
                JSONArray rects = p.getJSONArray("rects");
                for (int n = 0; n < rects.length(); n++) { JSONArray r = rects.getJSONArray(n); IdCopyPixels.blacken(pixels, w, h, r.getDouble(0), r.getDouble(1), r.getDouble(2), r.getDouble(3)); }
                b.setPixels(pixels, 0, w, 0, 0, w, h); java.util.Arrays.fill(pixels, 0);
                stamp(b, watermark);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, 94, bytes);
                output.add(PdfWriter.layout(bytes.toByteArray(), w, h, false, false, 0));
            } finally { b.recycle(); }
        }
        File temporary = File.createTempFile("idcopy-", ".tmp", staged ? MasterExport.directory(c) : PdfTools.outDir(c));
        File out = null;
        try {
            try (FileOutputStream stream = new FileOutputStream(temporary)) { PdfWriter.write(stream, output, ""); }
            if (!clear()) throw new Exception(Taal.T("Werkbestand wissen lukt niet. Sluit dit scherm en probeer opnieuw."));
            if(staged){File ready=temporary;temporary=null;return ready;}
            // Exclusieve reservering; alleen de geheel geschreven, afgeschermde PDF wordt gepubliceerd.
            do { out = new File(PdfTools.outDir(c), "ID-kopie afgeschermd-" + java.util.UUID.randomUUID().toString().substring(0, 8) + ".pdf"); } while (!out.createNewFile());
            if (!temporary.renameTo(out)) throw new Exception(Taal.T("Kopie opslaan lukt niet"));
            return out;
        } catch (Throwable e) {
            if (out != null) out.delete();
            throw e;
        } finally { if(temporary!=null)temporary.delete(); }
    }

    private static void stamp(Bitmap b, String text) {
        Canvas canvas = new Canvas(b); Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.argb(205, 160, 25, 25)); paint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
        String[] lines = text.split("\n"); float size = Math.min(b.getWidth(), b.getHeight()) * .045f; paint.setTextSize(size);
        float longest = 1; for (String line : lines) longest = Math.max(longest, paint.measureText(line));
        paint.setTextSize(Math.min(size, size * b.getWidth() * .9f / longest)); paint.setTextAlign(Paint.Align.CENTER);
        canvas.save(); canvas.rotate(-25, b.getWidth() / 2f, b.getHeight() / 2f);
        for (int i = 0; i < lines.length; i++) canvas.drawText(lines[i], b.getWidth() / 2f, b.getHeight() / 2f + (i - (lines.length - 1) / 2f) * paint.getTextSize() * 1.35f, paint);
        canvas.restore();
    }
}
