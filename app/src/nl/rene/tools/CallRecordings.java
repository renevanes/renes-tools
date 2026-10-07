package nl.rene.tools;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

final class CallRecordings {
    private CallRecordings() { }
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("call_recordings", Context.MODE_PRIVATE); }
    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "call-recordings");
        if (!d.isDirectory() && !d.mkdirs()) throw new IllegalStateException("Opnamemap maken lukt niet");
        return d;
    }
    static File resolve(Context c, String name) throws Exception { return RecordingFiles.resolve(dir(c), name); }
    static boolean hasRecordings(Context c) {
        File[] files = dir(c).listFiles((d, name) -> RecordingFiles.validName(name));
        return files != null && files.length > 0;
    }
    static String state(Context c) {
        try {
            File[] files = dir(c).listFiles((d, name) -> RecordingFiles.validName(name));
            if (files == null) throw new Exception("Opnames lezen lukt niet");
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            JSONArray rows = new JSONArray();
            for (File f : files) {
                if (rows.length() == 500) break;
                rows.put(new JSONObject().put("id", f.getName()).put("date", Long.parseLong(f.getName().substring(7, 20)))
                        .put("size", f.length()).put("duration", Transcribe.durationMs(c, f)));
            }
            boolean busy = CallRecorderService.busy;
            String message = prefs(c).getString("message", "");
            if (!busy && prefs(c).getBoolean("active", false)) message = "De vorige opname is onderbroken. Een niet-afgeronde opname is niet opgeslagen.";
            return new JSONObject().put("busy", busy).put("recording", CallRecorderService.recording).put("elapsed", CallRecorderService.elapsed())
                    .put("silent", CallRecorderService.silent()).put("message", message).put("rows", rows).put("total", files.length)
                    .put("dest", WaBackup.destUri(c) != null).put("mic", c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                    .put("notifications", android.os.Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                    .put("autoEnabled", AutoCallRecording.enabled(c)).put("autoReady", AutoCallRecording.ready(c)).put("automatic", CallRecorderService.autoSession != 0).put("autoStop", CallRecorderService.watchingCall).put("phone", c.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED).toString();
        } catch (Exception e) { return "{\"error\":\"Opnames laden lukt niet\"}"; }
    }
    static int export(Context c, String name) throws Exception {
        File f = resolve(c, name);
        Uri tree = WaBackup.destUri(c);
        if (tree == null) throw new Exception("Kies eerst een backup-map");
        WaBackup.Dest dest = new WaBackup.Dest(c.getContentResolver(), tree);
        WaBackup.DestDir folder = dest.dir("Gespreksopnames", true);
        Uri temp = DocumentsContract.createDocument(dest.cr, folder.uri, "audio/mp4", name + ".part");
        if (temp == null) throw new Exception("Backupbestand maken lukt niet");
        boolean done = false;
        try {
            try (InputStream in = new FileInputStream(f); OutputStream out = dest.cr.openOutputStream(temp, "w")) {
                if (out == null) throw new Exception("Backupbestand openen lukt niet");
                byte[] buffer = new byte[32768]; int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
            if (DocumentsContract.renameDocument(dest.cr, temp, name) == null) throw new Exception("Backupbestand afronden lukt niet");
            done = true;
            return 1;
        } finally { if (!done) try { DocumentsContract.deleteDocument(dest.cr, temp); } catch (Exception ignored) { } }
    }
}
