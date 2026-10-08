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
 * Een geopende PDF voor de viewer. Android's PdfRenderer kan maar één pagina tegelijk open hebben en is niet
 * thread-veilig, dus alles hier is synchronized en wordt (op de paginamaten na) vanaf de tekenthread gebruikt.
 */
final class PdfDoc implements AutoCloseable {
    final File file;
    final String name;
    final int count;
    /** Paginamaten in punten (1/72 inch). */
    final float[] w, h;
    private final ParcelFileDescriptor pfd;
    private final PdfRenderer r;
    private boolean closed;

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

    private static void closeQuietly(ParcelFileDescriptor p) { try { p.close(); } catch (Exception ignored) { } }

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
        if (closed || !canText() || i < 0 || i >= count) return out;
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
        if (closed || !canText() || i < 0 || i >= count || q == null || q.isEmpty()) return out;
        try (PdfRenderer.Page pg = r.openPage(i)) {
            for (android.graphics.pdf.models.PageMatchBounds m : pg.searchText(q)) out.add(new Match(i, m.getBounds()));
        } catch (RuntimeException ignored) { }
        return out;
    }

    /** De tekst van pagina i (Android 15 en nieuwer), of "" als er geen tekst in staat (bijv. een scan). */
    synchronized String text(int i) {
        if (closed || !canText() || i < 0 || i >= count) return "";
        StringBuilder b = new StringBuilder();
        try (PdfRenderer.Page pg = r.openPage(i)) {
            for (android.graphics.pdf.content.PdfPageTextContent t : pg.getTextContents()) { if (b.length() > 0) b.append('\n'); b.append(t.getText()); }
        } catch (RuntimeException ignored) { }
        return b.toString().trim();
    }

    synchronized boolean isClosed() { return closed; }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try { r.close(); } catch (Exception ignored) { }
        closeQuietly(pfd);
    }
}
