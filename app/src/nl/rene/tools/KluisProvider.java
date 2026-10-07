package nl.rene.tools;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;

/** Geeft één net geopend kluisdocument (tijdelijk ontsleuteld) aan de app waarmee je het bekijkt. Alleen lezen. */
public final class KluisProvider extends ContentProvider {
    static final String AUTH = "nl.rene.tools.kluis";

    @Override public boolean onCreate() { return true; }

    private File resolve(Uri uri) throws Exception {
        if (!Kluis.isOpen()) { Kluis.clearViews(getContext()); throw new Exception("kluis op slot"); }
        if (!AUTH.equals(uri.getAuthority()) || uri.getPathSegments().size() != 1) throw new Exception("niet gevonden");
        File dir = Kluis.viewDir(getContext()).getCanonicalFile();
        File f = new File(dir, uri.getLastPathSegment()).getCanonicalFile();
        if (!dir.equals(f.getParentFile()) || !f.isFile()) throw new Exception("niet gevonden");
        return f;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        try { if (!"r".equals(mode)) throw new Exception("alleen lezen"); return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY); }
        catch (Exception e) { throw new java.io.FileNotFoundException("Document niet beschikbaar"); }
    }

    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) {
        try { File f = resolve(uri); MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}); c.addRow(new Object[]{f.getName(), f.length()}); return c; }
        catch (Exception e) { return null; }
    }

    @Override public String getType(Uri uri) {
        String n = uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment();
        int dot = n.lastIndexOf('.');
        String t = dot < 0 ? null : android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(n.substring(dot + 1).toLowerCase(java.util.Locale.ROOT));
        return t == null ? "application/octet-stream" : t;
    }
    @Override public Uri insert(Uri uri, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
