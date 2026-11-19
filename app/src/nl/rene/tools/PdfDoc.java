package nl.rene.tools;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Een geopend document voor de viewer van de app: een PDF of een afbeelding (JPEG, PNG, WebP, GIF, HEIC; één pagina).
 * Android's PdfRenderer kan maar één pagina tegelijk open hebben en is niet thread-veilig, dus alles hier is
 * synchronized en wordt (op de paginamaten na) vanaf de tekenthread gebruikt.
 */
final class PdfDoc implements AutoCloseable {
    final File file;
    final String name;
    final int count;
    /** Paginamaten in punten (1/72 inch). */
    final float[] w, h;
    private final ParcelFileDescriptor pfd;
    private final PdfRenderer r;
    /** Bij een afbeelding: het beeld zelf (rechtop gezet, hooguit MAX_BEELD pixels breed of hoog); anders null. */
    private Bitmap beeld;
    private boolean closed;
    static final int MAX_BEELD = 4096;

    /** Een link op een pagina: een webadres (uri) of een andere pagina (page ≥ 0). Vlakken in punten. */
    static final class Link {
        final List<RectF> bounds; final String uri; final int page;
        Link(List<RectF> b, String u, int p) { bounds = b; uri = u; page = p; }
    }

    /** Een zoekresultaat: pagina en vlakken in punten. */
    static final class Match {
        final int page; final List<RectF> bounds;
        Match(int p, List<RectF> b) { page = p; bounds = b; }
    }

    /** Wachtwoord nodig (of fout wachtwoord). */
    static final class NeedsPassword extends IOException {
        NeedsPassword() { super("Deze PDF is beveiligd met een wachtwoord"); }
    }

    static boolean canPassword() { return Build.VERSION.SDK_INT >= 35; }
    static boolean canText() { return Build.VERSION.SDK_INT >= 35; }

    PdfDoc(File f, String name, String password) throws IOException {
        file = f; this.name = name;
        if (soort(f) != null) { // een afbeelding: één pagina
            beeld = laadBeeld(f);
            pfd = null; r = null; count = 1;
            w = new float[]{beeld.getWidth()}; h = new float[]{beeld.getHeight()};
            return;
        }
        ParcelFileDescriptor p = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        PdfRenderer rr;
        try {
            if (password != null && canPassword()) rr = new PdfRenderer(p, new android.graphics.pdf.LoadParams.Builder().setPassword(password).build());
            else rr = new PdfRenderer(p);
        } catch (SecurityException e) {
            closeQuietly(p);
            throw new NeedsPassword();
        } catch (IOException | RuntimeException e) {
            closeQuietly(p);
            throw new IOException("Deze PDF is niet te openen", e);
        }
        pfd = p; r = rr;
        count = r.getPageCount();
        if (count <= 0) { close(); throw new IOException("Deze PDF heeft geen pagina's"); }
        w = new float[count]; h = new float[count];
        for (int i = 0; i < count; i++) {
            try (PdfRenderer.Page pg = r.openPage(i)) { w[i] = Math.max(1, pg.getWidth()); h[i] = Math.max(1, pg.getHeight()); }
            catch (RuntimeException e) { w[i] = 595; h[i] = 842; }
        }
    }

    private static void closeQuietly(ParcelFileDescriptor p) { try { if (p != null) p.close(); } catch (Exception ignored) { } }

    /** Een afbeelding (geen PDF): geen tekst, links, zoeken of afdrukken. */
    boolean isBeeld() { return r == null; }

    /** Het soort afbeelding aan de eerste bytes ("jpg", "png", "webp", "gif", "heic"), of null (bijv. een PDF). */
    static String soort(byte[] k, int n) {
        if (k == null || n < 12) return null;
        if ((k[0] & 0xff) == 0xff && (k[1] & 0xff) == 0xd8 && (k[2] & 0xff) == 0xff) return "jpg";
        if ((k[0] & 0xff) == 0x89 && k[1] == 'P' && k[2] == 'N' && k[3] == 'G') return "png";
        if (k[0] == 'R' && k[1] == 'I' && k[2] == 'F' && k[3] == 'F' && k[8] == 'W' && k[9] == 'E' && k[10] == 'B' && k[11] == 'P') return "webp";
        if (k[0] == 'G' && k[1] == 'I' && k[2] == 'F' && k[3] == '8') return "gif";
        if (k[4] == 'f' && k[5] == 't' && k[6] == 'y' && k[7] == 'p') {
            String b = new String(k, 8, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (b.equals("heic") || b.equals("heix") || b.equals("mif1") || b.equals("msf1") || b.equals("avif")) return "heic";
        }
        return null;
    }

    static String soort(File f) {
        byte[] k = new byte[16]; int n;
        try (java.io.InputStream in = new java.io.FileInputStream(f)) { n = in.read(k); } catch (Exception e) { return null; }
        return soort(k, n);
    }

    /** Het mime-type bij een bestand van de viewer (PDF of afbeelding). */
    static String mime(File f) {
        String s = soort(f);
        return s == null ? "application/pdf" : "jpg".equals(s) ? "image/jpeg" : "heic".equals(s) ? "image/heic" : "image/" + s;
    }

    /** Leest een afbeelding verkleind tot hooguit MAX_BEELD pixels en zet hem rechtop (EXIF). */
    private static Bitmap laadBeeld(File f) throws IOException {
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) throw new IOException("Deze afbeelding is niet te openen");
        int s = 1;
        while (Math.max(o.outWidth, o.outHeight) / s > MAX_BEELD) s *= 2;
        o = new android.graphics.BitmapFactory.Options();
        o.inSampleSize = s;
        Bitmap b;
        try { b = android.graphics.BitmapFactory.decodeFile(f.getPath(), o); }
        catch (OutOfMemoryError e) { throw new IOException("Deze afbeelding is te groot om te openen"); }
        if (b == null) throw new IOException("Deze afbeelding is niet te openen");
        int draai = 0;
        try {
            int st = new android.media.ExifInterface(f.getPath()).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL);
            draai = st == android.media.ExifInterface.ORIENTATION_ROTATE_90 ? 90 : st == android.media.ExifInterface.ORIENTATION_ROTATE_180 ? 180 : st == android.media.ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
        } catch (Exception ignored) { }
        if (draai == 0) return b;
        try {
            Matrix m = new Matrix(); m.postRotate(draai);
            Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (r != b) b.recycle();
            return r;
        } catch (OutOfMemoryError e) { return b; }
    }

    /**
     * Tekent (een deel van) pagina i in een nieuwe bitmap van bw × bh pixels. De matrix zet paginapunten om naar
     * bitmappixels. Geeft null als het document al dicht is of de pagina niet te tekenen is.
     */
    synchronized Bitmap render(int i, int bw, int bh, Matrix m) {
        if (closed || i < 0 || i >= count || bw <= 0 || bh <= 0) return null;
        Bitmap b;
        try { b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888); }
        catch (OutOfMemoryError e) { return null; }
        b.eraseColor(Color.WHITE);
        if (beeld != null) { // een afbeelding: dezelfde omrekening als bij een pagina (punten = pixels van het beeld)
            new android.graphics.Canvas(b).drawBitmap(beeld, m, new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
            return b;
        }
        try (PdfRenderer.Page pg = r.openPage(i)) {
            if (Build.VERSION.SDK_INT >= 35) {
                // Ook opmerkingen en markeringen tonen (Android 15 en nieuwer)
                pg.render(b, null, m, new android.graphics.pdf.RenderParams.Builder(android.graphics.pdf.RenderParams.RENDER_MODE_FOR_DISPLAY)
                        .setRenderFlags(android.graphics.pdf.RenderParams.FLAG_RENDER_TEXT_ANNOTATIONS | android.graphics.pdf.RenderParams.FLAG_RENDER_HIGHLIGHT_ANNOTATIONS).build());
            } else pg.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return b;
        } catch (RuntimeException e) {
            b.recycle();
            return null;
        }
    }

    /** Links op pagina i (alleen Android 15 en nieuwer; anders een lege lijst). */
    synchronized List<Link> links(int i) {
        List<Link> out = new ArrayList<>();
        if (closed || r == null || !canText() || i < 0 || i >= count) return out;
        try (PdfRenderer.Page pg = r.openPage(i)) {
            for (android.graphics.pdf.content.PdfPageLinkContent l : pg.getLinkContents())
                if (l.getUri() != null) out.add(new Link(l.getBounds(), l.getUri().toString(), -1));
            for (android.graphics.pdf.content.PdfPageGotoLinkContent l : pg.getGotoLinks())
                if (l.getDestination() != null) out.add(new Link(l.getBounds(), null, l.getDestination().getPageNumber()));
        } catch (RuntimeException ignored) { }
        return out;
    }

    /** Zoekt tekst op pagina i (Android 15 en nieuwer). */
    synchronized List<Match> search(int i, String q) {
        List<Match> out = new ArrayList<>();
        if (closed || r == null || !canText() || i < 0 || i >= count || q == null || q.isEmpty()) return out;
        try (PdfRenderer.Page pg = r.openPage(i)) {
            for (android.graphics.pdf.models.PageMatchBounds m : pg.searchText(q)) out.add(new Match(i, m.getBounds()));
        } catch (RuntimeException ignored) { }
        return out;
    }

    /** De tekst van pagina i (Android 15 en nieuwer), of "" als er geen tekst in staat (bijv. een scan). */
    synchronized String text(int i) {
        if (closed || r == null || !canText() || i < 0 || i >= count) return "";
        StringBuilder b = new StringBuilder();
        try (PdfRenderer.Page pg = r.openPage(i)) {
            for (android.graphics.pdf.content.PdfPageTextContent t : pg.getTextContents()) { if (b.length() > 0) b.append('\n'); b.append(t.getText()); }
        } catch (RuntimeException ignored) { }
        return b.toString().trim();
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try { if (r != null) r.close(); } catch (Exception ignored) { }
        closeQuietly(pfd);
        if (beeld != null) { beeld.recycle(); beeld = null; }
    }
}
