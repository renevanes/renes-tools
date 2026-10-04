package nl.rene.tools;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Alles back-uppen: sms, oproepen, contacten, notities, gesprekken en herkende muziek in één keer
 * naar de backup-map, met per onderdeel de uitkomst. Draait op verzoek of elke nacht (AllBackupJob).
 * WhatsApp heeft een eigen nachtelijke backup en wordt alleen bij handmatig starten meegenomen.
 */
final class AllBackup {

    private AllBackup() { }

    static final String[] PARTS = {"sms", "calls", "contacts", "notes", "transcripts", "music"};

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("allbackup", Context.MODE_PRIVATE); }

    /** Er kan maar één backup tegelijk lopen. */
    static final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(false);
    static volatile boolean busy = false;
    static volatile boolean stop = false;    // Android beëindigt de nachtelijke taak: netjes stoppen
    static volatile String current = "";

    /** Probeert een backup te starten; false als er al een loopt. */
    static boolean tryBegin() {
        if (!running.compareAndSet(false, true)) return false;
        busy = true;
        stop = false;
        return true;
    }

    static boolean has(Context c, String p) { return c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    static boolean enabledPart(Context c, String part) { return prefs(c).getBoolean("part_" + part, true); }

    /** Voert de backup uit (na tryBegin). Geeft per onderdeel {ok, count, msg}; slaat de uitkomst op. */
    static JSONObject run(Context c, boolean auto) {
        JSONObject res = new JSONObject();
        try {
            if (WaBackup.destUri(c) == null) {
                put(res, "all", false, 0, "Kies eerst een backup-map (Instellingen)");
                return res;
            }
            for (String part : PARTS) {
                if (stop) { try { res.put("stopped", true); } catch (Exception ignored) { } break; }
                if (!enabledPart(c, part)) continue;
                current = part;
                try {
                    int n = runPart(c, part);
                    put(res, part, true, n, n < 0 ? "Niets te bewaren" : null);
                } catch (Throwable e) {
                    String m = e instanceof OutOfMemoryError ? "onvoldoende geheugen" : e.getMessage() != null ? e.getMessage() : "mislukt";
                    put(res, part, false, 0, m);
                    App.log(c, "BACKUP", part + ": " + m);
                }
            }
        } finally {
            current = "";
            try {
                res.put("t", System.currentTimeMillis()).put("auto", auto);
                if (!res.optBoolean("stopped")) prefs(c).edit().putString("last", res.toString()).apply();
            } catch (Exception ignored) { }
            busy = false;
            running.set(false);
        }
        return res;
    }

    private static void put(JSONObject r, String part, boolean ok, int n, String msg) {
        try {
            JSONObject o = new JSONObject().put("ok", ok).put("count", Math.max(0, n)).put("t", System.currentTimeMillis());
            if (msg != null) o.put("msg", msg);
            r.put(part, o);
        } catch (Exception ignored) { }
    }

    /** Eén onderdeel; -1 = niets te bewaren (geen fout). */
    static int runPart(Context c, String part) throws Exception {
        switch (part) {
            case "sms":
                if (!has(c, Manifest.permission.READ_SMS)) throw new Exception("Geen toestemming voor sms");
                if (Sms.countTotal(c) == 0) return -1; // geen sms'jes: geen fout
                return Sms.export(c, (d, t) -> { });
            case "calls":
                if (!has(c, Manifest.permission.READ_CALL_LOG)) throw new Exception("Geen toestemming voor de oproepgeschiedenis");
                return Calls.export(c, "", (d, t) -> { });
            case "contacts":
                if (!has(c, Manifest.permission.READ_CONTACTS)) throw new Exception("Geen toestemming voor contacten");
                return Contacts.export(c, 0);
            case "notes":
                return Notes.export(c);
            case "transcripts":
                try { return Transcribe.export(c, ""); }
                catch (Exception e) { if (e.getMessage() != null && e.getMessage().startsWith("Nog geen")) return -1; throw e; }
            case "music":
                return exportMusic(c);
            default:
                return -1;
        }
    }

    /** Herkende nummers als JSON (terug te lezen) en als leesbare lijst. */
    static int exportMusic(Context c) throws Exception {
        JSONArray h = Music.history(c);
        if (h.length() == 0) return -1;
        WaBackup.Dest dest = new WaBackup.Dest(c.getContentResolver(), WaBackup.destUri(c));
        WaBackup.DestDir dir = dest.dir("Muziek", true);
        try (Writer w = Sms.open(dest, dir, "herkende-nummers.json", "application/json")) { w.write(h.toString()); }
        SimpleDateFormat df = new SimpleDateFormat("d MMM yyyy HH:mm", new Locale("nl", "NL"));
        try (Writer w = Sms.open(dest, dir, "herkende-nummers.txt", "text/plain")) {
            w.write("Herkende nummers - Rene's Tools\r\n\r\n");
            for (int i = 0; i < h.length(); i++) {
                JSONObject r = h.getJSONObject(i);
                w.write(df.format(new Date(r.optLong("t"))) + "  " + r.optString("artist") + " - " + r.optString("title")
                        + (r.optString("album").isEmpty() ? "" : "  (" + r.optString("album") + ")") + "\r\n");
            }
        }
        return h.length();
    }

    static String stateJson(Context c, boolean full) {
        try {
            JSONObject o = new JSONObject().put("busy", busy).put("current", current);
            if (!full) {
                String last = prefs(c).getString("last", null);
                if (last != null) o.put("last", new JSONObject(last));
                return o.toString();
            }
            o
                    .put("auto", prefs(c).getBoolean("auto", false)).put("charging", prefs(c).getBoolean("charging", true))
                    .put("dest", WaBackup.destUri(c) != null).put("destName", WaBackup.destName(c))
                    .put("next", prefs(c).getLong("next", 0));
            JSONObject parts = new JSONObject();
            for (String p : PARTS) parts.put(p, enabledPart(c, p));
            o.put("parts", parts);
            String last = prefs(c).getString("last", null);
            if (last != null) o.put("last", new JSONObject(last));
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }
}
