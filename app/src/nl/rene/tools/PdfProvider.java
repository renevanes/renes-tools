package nl.rene.tools;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;

/**
 * Geeft een gemaakte of geopende PDF (alleen lezen) aan de app waarmee je hem deelt, opent of afdrukt. Ook een bestand dat
 * Bron klaarzette voor een tool in de app zelf (pad b/&lt;token&gt;/&lt;naam&gt;, elke soort, alleen zolang het token geldt): een
 * kluisdocument wordt dan pas tijdens het lezen ontsleuteld, door een pijp (nooit leesbaar op schijf).
 */
public final class PdfProvider extends ContentProvider {
    static final String AUTH = "nl.rene.tools.pdf";

    /** Een gemaakte PDF (map pdf-out) of een PDF die in de viewer open staat (map pdf-view/&lt;id&gt;/). */
    static Uri uri(File f) {
        Uri.Builder b = new Uri.Builder().scheme("content").authority(AUTH);
        File p = f.getParentFile();
        if (p != null && Kluis.VIEW_DIR.equals(p.getName())) return b.appendPath("k").appendPath(f.getName()).build(); // tijdelijk geopend kluisdocument
        if (p != null && p.getParentFile() != null && PdfViewActivity.DIR.equals(p.getParentFile().getName())) b.appendPath("v").appendPath(p.getName());
        return b.appendPath(f.getName()).build();
    }

    /** Het bestand achter een uri van deze provider, of een fout. */
    static File file(android.content.Context c, Uri uri) throws Exception {
        if (!AUTH.equals(uri.getAuthority())) throw new Exception("niet gevonden");
        java.util.List<String> seg = uri.getPathSegments();
        if (seg.size() == 1) return PdfTools.output(c, seg.get(0));
        if (seg.size() == 2 && "k".equals(seg.get(0))) {
            // Een kluisdocument dat de app net ontsleutelde om te bekijken; alleen zolang de kluis open is (daarna is de map leeg)
            if (!Kluis.isOpen()) { Kluis.clearViews(c); throw new Exception("niet gevonden"); }
            File d = Kluis.viewDir(c).getCanonicalFile(), f = new File(d, seg.get(1)).getCanonicalFile();
            if (!d.equals(f.getParentFile()) || !f.isFile() || !f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) throw new Exception("niet gevonden");
            return f;
        }
        if (seg.size() == 3 && "v".equals(seg.get(0))) {
            File d = new File(PdfTools.dir(c, PdfViewActivity.DIR), seg.get(1)).getCanonicalFile(), f = new File(d, seg.get(2)).getCanonicalFile();
            if (!PdfTools.dir(c, PdfViewActivity.DIR).getCanonicalFile().equals(d.getParentFile()) || !d.equals(f.getParentFile()) || !f.isFile() || !f.getName().endsWith(".pdf")) throw new Exception("niet gevonden");
            return f;
        }
        throw new Exception("niet gevonden");
    }

    /** Uri voor een bestand dat Bron klaarzette. */
    static Uri bronUri(Bron.Bestand b) { return new Uri.Builder().scheme("content").authority(AUTH).appendPath("b").appendPath(b.token).appendPath(b.naam).build(); }
    private static Bron.Bestand bron(Uri uri) {
        java.util.List<String> seg = uri.getPathSegments();
        return AUTH.equals(uri.getAuthority()) && seg.size() == 3 && "b".equals(seg.get(0)) ? Bron.zoek(seg.get(1), seg.get(2), android.os.SystemClock.elapsedRealtime()) : null;
    }
    private static boolean bronPad(Uri uri) { java.util.List<String> seg = uri.getPathSegments(); return !seg.isEmpty() && "b".equals(seg.get(0)); }

    /** Een kluisdocument ontsleutelen in een pijp (de lezer krijgt het ene uiteinde); een fout sluit de pijp met die fout. */
    private ParcelFileDescriptor pijp(Bron.Bestand b) throws java.io.IOException {
        final ParcelFileDescriptor[] p = ParcelFileDescriptor.createReliablePipe();
        final android.content.Context c = getContext();
        new Thread(() -> {
            try (java.io.InputStream in = new java.io.BufferedInputStream(new java.io.FileInputStream(Kluis.blobFile(c, b.id, b.versie)), 1 << 16);
                 java.io.OutputStream o = new ParcelFileDescriptor.AutoCloseOutputStream(p[1])) { Kluis.decrypt(in, o); }
            catch (Throwable e) { try { p[1].closeWithError("niet te lezen"); } catch (Throwable ignored) { } }
        }, "bron-kluis").start();
        return p[0];
    }

    @Override public boolean onCreate() { return true; }

    private File resolve(Uri uri) throws Exception { return file(getContext(), uri); }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        try {
            if (!"r".equals(mode)) throw new Exception("alleen lezen");
            if (bronPad(uri)) {
                Bron.Bestand b = bron(uri);
                if (b == null) throw new Exception("niet gevonden");
                if (b.bestand == null) return pijp(b);
                if (!b.bestand.isFile()) throw new Exception("niet gevonden");
                return ParcelFileDescriptor.open(b.bestand, ParcelFileDescriptor.MODE_READ_ONLY);
            }
            return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
        }
        catch (Exception e) { throw new java.io.FileNotFoundException("PDF niet gevonden"); }
    }

    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) {
        try {
            MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            if (bronPad(uri)) { Bron.Bestand b = bron(uri); if (b == null) return null; c.addRow(new Object[]{b.naam, b.grootte >= 0 ? b.grootte : null}); return c; }
            File f = resolve(uri); c.addRow(new Object[]{f.getName(), f.length()}); return c;
        }
        catch (Exception e) { return null; }
    }

    @Override public String getType(Uri uri) {
        if (bronPad(uri)) { Bron.Bestand b = bron(uri); return b == null ? null : b.mime; }
        return "application/pdf";
    }
    @Override public Uri insert(Uri uri, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
