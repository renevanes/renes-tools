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

    static final String[] PARTS = {"sms", "calls", "notifications", "contacts", "notes", "transcripts", "music"};

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

    static boolean enabledPart(Context c, String part) { return prefs(c).getBoolean("part_" + part, !part.equals("notifications")); }

    /** Voert de backup uit (na tryBegin). Geeft per onderdeel {ok, count, msg}; slaat de uitkomst op. */
    static JSONObject run(Context c, boolean auto) {
        JSONObject res = new JSONObject();
        Secure.Zip zip = null;
        try {
            if (WaBackup.destUri(c) == null) {
                put(res, "all", false, 0, "Kies eerst een backup-map (Instellingen)");
                return res;
            }
            if (Secure.on(c)) {
                try {
                    zip = Secure.begin(c, Secure.key(c));
                } catch (Exception e) {
                    put(res, "all", false, 0, e.getMessage() != null ? e.getMessage() : "Versleuteld archief maken lukt niet");
                    App.log(c, "BACKUP", "archief: " + e.getMessage());
                    return res;
                }
                Secure.CAPTURE.set(zip);
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
            Secure.CAPTURE.remove();
            if (zip != null) {
                boolean any = false;
                for (String p : PARTS) { JSONObject r = res.optJSONObject(p); if (r != null && r.optBoolean("ok") && r.optInt("count") > 0) any = true; }
                if (!any || stop) Secure.abort(zip);
                else {
                    try { Secure.finish(zip, res); res.put("archive", zip.name); }
                    catch (Exception e) { put(res, "all", false, 0, "Archief afsluiten mislukt: " + e.getMessage()); App.log(c, "BACKUP", "archief afsluiten: " + e); }
                }
                zip = null;
            }
            boolean allOk = !res.has("all");
            for (String p : PARTS) { JSONObject r = res.optJSONObject(p); if (r != null && !r.optBoolean("ok")) allOk = false; }
            // Alleen opruimen na een volledig gelukte backup, anders kan een onvolledige backup de goede verdringen.
            if (!stop && allOk && prefs(c).getBoolean("rotate", false)) {
                try { JSONObject r = Secure.rotate(c, false); if (r.optInt("count") > 0) res.put("rotated", r); }
                catch (Exception e) { App.log(c, "BACKUP", "opruimen: " + e.getMessage()); }
            }
        } finally {
            Secure.CAPTURE.remove();
            if (zip != null) Secure.abort(zip);
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
            case "notifications":
                return NotificationHistory.export(c, true); // loopt er al een handmatige export: even wachten
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
                    .put("next", prefs(c).getLong("next", 0))
                    .put("rotate", prefs(c).getBoolean("rotate", false)).put("encrypted", Secure.on(c)).put("encBroken", Secure.broken(c))
                    .put("encSince", Secure.prefs(c).getLong("since", 0));
            String space = Secure.prefs(c).getString("space", null);
            if (space != null) o.put("space", new JSONObject(space));
            JSONObject parts = new JSONObject();
            for (String p : PARTS) parts.put(p, enabledPart(c, p));
            o.put("parts", parts);
            String last = prefs(c).getString("last", null);
            if (last != null) o.put("last", new JSONObject(last));
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }
}
