package nl.rene.tools;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;

/** Geeft een gemaakte of geopende PDF (alleen lezen) aan de app waarmee je hem deelt, opent of afdrukt. */
public final class PdfProvider extends ContentProvider {
    static final String AUTH = "nl.rene.tools.pdf";

    /** Een gemaakte PDF (map pdf-out) of een PDF die in de viewer open staat (map pdf-view/&lt;id&gt;/). */
    static Uri uri(File f) {
        Uri.Builder b = new Uri.Builder().scheme("content").authority(AUTH);
        File p = f.getParentFile();
        if (p != null && p.getParentFile() != null && PdfViewActivity.DIR.equals(p.getParentFile().getName())) b.appendPath("v").appendPath(p.getName());
        return b.appendPath(f.getName()).build();
    }

    /** Het bestand achter een uri van deze provider, of een fout. */
    static File file(android.content.Context c, Uri uri) throws Exception {
        if (!AUTH.equals(uri.getAuthority())) throw new Exception("niet gevonden");
        java.util.List<String> seg = uri.getPathSegments();
        if (seg.size() == 1) return PdfTools.output(c, seg.get(0));
        if (seg.size() == 3 && "v".equals(seg.get(0))) {
            File d = new File(PdfTools.dir(c, PdfViewActivity.DIR), seg.get(1)).getCanonicalFile(), f = new File(d, seg.get(2)).getCanonicalFile();
            if (!PdfTools.dir(c, PdfViewActivity.DIR).getCanonicalFile().equals(d.getParentFile()) || !d.equals(f.getParentFile()) || !f.isFile() || !f.getName().endsWith(".pdf")) throw new Exception("niet gevonden");
            return f;
        }
        throw new Exception("niet gevonden");
    }

    @Override public boolean onCreate() { return true; }

    private File resolve(Uri uri) throws Exception { return file(getContext(), uri); }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        try { if (!"r".equals(mode)) throw new Exception("alleen lezen"); return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY); }
        catch (Exception e) { throw new java.io.FileNotFoundException("PDF niet gevonden"); }
    }

    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) {
        try { File f = resolve(uri); MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}); c.addRow(new Object[]{f.getName(), f.length()}); return c; }
        catch (Exception e) { return null; }
    }

    @Override public String getType(Uri uri) { return "application/pdf"; }
    @Override public Uri insert(Uri uri, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
