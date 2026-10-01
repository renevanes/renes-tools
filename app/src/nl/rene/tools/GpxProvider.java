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
 * Kleine eigen ContentProvider om een GPX-bestand uit de app te delen, zonder AndroidX.
 * Serveert alleen .gpx-bestanden uit de routemap, alleen-lezen.
 */
public class GpxProvider extends ContentProvider {

    @Override public boolean onCreate() { return true; }

    private File resolve(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("..") || !name.endsWith(".gpx")) return null;
        File f = new File(Tracks.dir(getContext()), name);
        return f.isFile() ? f : null;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        File f = resolve(uri);
        if (f == null) throw new java.io.FileNotFoundException("Onbekend bestand");
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f = resolve(uri);
        if (f == null) return null;
        String[] cols = {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        c.addRow(new Object[]{f.getName(), f.length()});
        return c;
    }

    @Override public String getType(Uri uri) { return "application/gpx+xml"; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
