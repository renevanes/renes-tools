package nl.rene.tools;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;

/** Leest alleen expliciet gedeelde, afgeronde opnames uit de private opnamemap. */
public final class RecordingProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private File resolve(Uri uri) throws Exception {
        if (!"nl.rene.tools.recordings".equals(uri.getAuthority()) || uri.getPathSegments().size() != 1) throw new Exception("Opname niet gevonden");
        return CallRecordings.resolve(getContext(), uri.getLastPathSegment());
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        try { if (!"r".equals(mode)) throw new Exception("Alleen lezen"); return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY); }
        catch (Exception e) { throw new java.io.FileNotFoundException("Opname niet gevonden of niet leesbaar"); }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        try {
            File file = resolve(uri);
            MatrixCursor rows = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            rows.addRow(new Object[]{file.getName(), file.length()}); return rows;
        } catch (Exception e) { return null; }
    }
    @Override public String getType(Uri uri) { return "audio/mp4"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
