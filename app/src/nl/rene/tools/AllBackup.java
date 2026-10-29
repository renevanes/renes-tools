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
 * Alles back-uppen: sms, oproepen, contacten, notities, gesprekken, herkende muziek, routes en instellingen in één keer
 * (en als je dat aanvinkt: meldingen, het geheugen en de gesprekken van de assistent, Slaappraat en Check-in)
 * naar de backup-map, met per onderdeel de uitkomst. Draait op verzoek of elke nacht (AllBackupJob).
 * WhatsApp heeft een eigen nachtelijke backup en wordt alleen bij handmatig starten meegenomen.
 */
final class AllBackup {

    private AllBackup() { }

    static final String[] PARTS = {"sms", "calls", "notifications", "contacts", "notes", "launcher", "transcripts", "music", "settings", "routes", "ai", "sleep", "checkin", "kluis"};

    /** Privé: alleen versleuteld mee, of onversleuteld na uitdrukkelijke toestemming (plain_…). */
    static final String[] PRIVATE = {"ai", "sleep", "checkin"};

    static boolean priv(String part) { for (String p : PRIVATE) if (p.equals(part)) return true; return false; }

    /** Gevoelig: staan standaard uit en gaan alleen mee als je ze aanvinkt (zonder versleutelen als leesbaar bestand). */
    static boolean optIn(String part) { return "notifications".equals(part) || priv(part); }

    /** Slaappraat met geluid (WAV) of alleen de tekst (standaard). */
    static boolean sleepAudio(Context c) { return prefs(c).getBoolean("sleep_audio", false); }

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

    static final String STOPPED = "Gestopt: Android beëindigde de backup (volgende keer opnieuw)";

    static boolean has(Context c, String p) { return c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    static boolean enabledPart(Context c, String part) { return prefs(c).getBoolean("part_" + part, !optIn(part)); }

    /**
     * Toestemming om assistent of Slaappraat onversleuteld mee te nemen (alleen na de vraag "Onversleuteld meenemen?").
     * Staat in secure.xml: niet in de Android-backup en niet in de instellingen-backup.
     */
    static boolean plainOk(Context c, String part) { return Secure.prefs(c).getBoolean("plain_" + part, false); }
    static void setPlain(Context c, String part, boolean on) { Secure.prefs(c).edit().putBoolean("plain_" + part, on).apply(); }

    /** Gaat dit onderdeel echt mee? (Privé zonder versleutelen alleen met toestemming.) */
    static boolean effectivePart(Context c, String part) {
        if (!enabledPart(c, part)) return false;
        return !priv(part) || Secure.on(c) || plainOk(c, part);
    }

    /** Voert de backup uit (na tryBegin). Geeft per onderdeel {ok, count, msg}; slaat de uitkomst op. */
    static JSONObject run(Context c, boolean auto) {
        JSONObject res = new JSONObject();
        HomeWidgets.refresh(c, HomeWidgets.Overview.class); // "Backup is bezig…"
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
                if (!enabledPart(c, part)) continue;
                // De kluis gaat alleen mee in een versleutelde backup. Zonder versleutelen geen fout (anders zou het
                // automatisch opruimen nooit meer lopen), wel een duidelijke melding bij het resultaat.
                if ("kluis".equals(part) && !Secure.on(c)) {
                    try { if (Kluis.list(c).length() > 0) put(res, part, true, -1, "Niet mee: de kluis gaat alleen in een versleutelde backup (zet Versleutelen aan)"); }
                    catch (Exception ignored) { }
                    continue;
                }
                // Android beëindigt de taak: de rest niet meer, maar wel zichtbaar als mislukt; wat klaar is blijft bewaard
                if (stop) { try { res.put("stopped", true); } catch (Exception ignored) { } put(res, part, false, 0, STOPPED); continue; }
                // Privé zonder versleutelen alleen met uitdrukkelijke toestemming (die staat niet in een backup; na een
                // nieuwe installatie of teruggezette instellingen gaat het dus nooit stilletjes leesbaar mee)
                if (priv(part) && !Secure.on(c) && !plainOk(c, part)) {
                    put(res, part, true, -1, "Niet mee: privé, zet Versleutelen aan (of vink het opnieuw aan)");
                    continue;
                }
                current = part;
                try {
                    int n = runPart(c, part);
                    // Geheugen, gesprekken en Slaappraat zonder versleutelen: in de uitkomst laten zien dat het leesbaar is
                    boolean plain = n > 0 && zip == null && priv(part);
                    put(res, part, true, n, n < 0 ? "Niets te bewaren" : plain ? n + " (niet versleuteld)" : null);
                    // Onthouden dat deze telefoon zelf een leesbare kopie schreef (alleen die mag later weg)
                    if (plain) Secure.prefs(c).edit().putBoolean("plain_written_" + part, true).apply();
                } catch (Throwable e) {
                    String orig = e instanceof OutOfMemoryError ? "onvoldoende geheugen" : e.getMessage() != null ? e.getMessage() : "mislukt";
                    String m = stop ? STOPPED : orig;
                    if (stop) try { res.put("stopped", true); } catch (Exception ignored) { }
                    put(res, part, false, 0, m);
                    App.log(c, "BACKUP", part + ": " + m + (stop ? " (" + orig + ")" : ""));
                }
            }
            Secure.CAPTURE.remove();
            if (zip != null) {
                boolean any = false;
                for (String p : PARTS) { JSONObject r = res.optJSONObject(p); if (r != null && r.optBoolean("ok") && r.optInt("count") > 0) any = true; }
                // Ook na stoppen het archief afmaken met wat klaar is (de onderdelen daarna staan als mislukt)
                if (!any) Secure.abort(zip);
                else {
                    boolean done = false;
                    try { res.put("archive", Secure.finish(zip, res)); done = true; }
                    catch (Exception e) { put(res, "all", false, 0, "Archief afsluiten mislukt: " + e.getMessage()); App.log(c, "BACKUP", "archief afsluiten: " + e); }
                    // Staat het privé-deel nu versleuteld in het archief, dan de oude leesbare kopie uit de backup-map weg
                    // (ook als er niets te bewaren was: dan hoort er ook geen oude leesbare kopie te blijven staan)
                    if (done) for (String p : PRIVATE) {
                        JSONObject r = res.optJSONObject(p);
                        // Alleen een leesbare kopie die deze telefoon zelf schreef (niet die van een vorige telefoon in dezelfde map)
                        if (r != null && r.optBoolean("ok") && Secure.prefs(c).getBoolean("plain_written_" + p, false)) AiSleepBackup.removePlain(c, zip.dest.tree, p);
                    }
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
                prefs(c).edit().putString("last", res.toString()).apply(); // ook na stoppen: dan is te zien wat niet meer lukte
                HomeWidgets.refresh(c, HomeWidgets.Overview.class);
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
                int nt;
                try { nt = Transcribe.export(c, ""); }
                catch (Exception e) { if (e.getMessage() != null && e.getMessage().startsWith("Nog geen")) return -1; throw e; }
                SettingsBackup.exportTranscripts(c); // volledig, om terug te kunnen zetten
                return nt;
            case "settings":
                return SettingsBackup.export(c);
            case "routes":
                return SettingsBackup.exportRoutes(c);
            case "kluis":
                return Kluis.export(c); // alleen in een versleutelde backup
            case "music":
                return exportMusic(c);
            case "launcher":
                return LauncherBackup.export(c);
            case "ai":
                return AiSleepBackup.exportAi(c); // geheugen en gesprekken; nooit modellen of de zoekindex
            case "sleep":
                return AiSleepBackup.exportSleep(c, sleepAudio(c));
            case "checkin":
                return CheckinStore.export(c); // vragen en antwoorden
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
        try (Sms.Out w = Sms.open(dest, dir, "herkende-nummers.json", "application/json")) { w.write(h.toString()); w.done(); }
        SimpleDateFormat df = new SimpleDateFormat("d MMM yyyy HH:mm", new Locale("nl", "NL"));
        try (Sms.Out w = Sms.open(dest, dir, "herkende-nummers.txt", "text/plain")) {
            w.write("Herkende nummers - Tools\r\n\r\n");
            for (int i = 0; i < h.length(); i++) {
                JSONObject r = h.getJSONObject(i);
                w.write(df.format(new Date(r.optLong("t"))) + "  " + r.optString("artist") + " - " + r.optString("title")
                        + (r.optString("album").isEmpty() ? "" : "  (" + r.optString("album") + ")") + "\r\n");
            }
            w.done(); // pas nu vervangt het nieuwe bestand het oude
        }
        return h.length();
    }

    static String stateJson(Context c, boolean full) {
        try {
            JSONObject o = new JSONObject().put("busy", busy).put("current", current);
            if (!full) {
                o.put("encrypted", Secure.on(c));
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
            for (String p : PARTS) parts.put(p, effectivePart(c, p));
            JSONObject extra = AiSleepBackup.sizes(c);
            CheckinStore.sizes(c, extra);
            o.put("parts", parts).put("sleepAudio", sleepAudio(c)).put("extra", extra);
            String last = prefs(c).getString("last", null);
            if (last != null) o.put("last", new JSONObject(last));
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }
}
