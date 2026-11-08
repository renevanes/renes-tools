package nl.rene.tools;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Toolgegevens: alles van de tools dat niet al een eigen onderdeel van Alles back-uppen heeft, als één leesbaar
 * bestand in de backup-map (Toolgegevens/toolgegevens.json). Mijn auto (parkeren, ritten), Facturen (leveranciers,
 * dossier, aanleveren), Apparaten, Toiletten (eigen laag), versies van contacten, Spotify, Gebruik, gespreksopnames en
 * de instellingen van alle tools (regio en taal, lokale AI, Slaappraat, uitschrijven, losse apps …).
 *
 * Volgt {@link AppData}: alles gaat standaard mee, ook van een nieuwe tool (instellingen en zijn map in getFilesDir()). Nooit mee: geheimen
 * (wachtwoorden, sleutels, tokens, de app-code, VPN), wat alleen versleuteld mag (kluis, foto's van Gebruik), de privé-
 * onderdelen met een eigen vinkje (assistent, Slaappraat-nachten, Check-in), Bankzaken en stamgegevens.
 * Terugzetten voegt alleen toe wat er nog niet is: bestaande instellingen en bestanden worden nooit overschreven.
 */
final class ToolData {

    private ToolData() { }

    static final String DIR = "Toolgegevens", FILE = "toolgegevens.json", FORMAT = "renes-tools-tooldata";
    static final int VERSION = 1;

    /** Instellingenbestanden die nooit leesbaar mee mogen (geheimen, of zonder geheimen onbruikbaar). */
    static final Set<String> PREFS_NEVER = new HashSet<>(Arrays.asList(
            "lock",       // de app-code (hash en zout) en het slot: opnieuw instellen
            "vpn",        // tunnels zijn zonder hun (versleutelde) sleutels onbruikbaar
            "webstore", "allbackup", "radio", "alarm", "auto", "redialplan", "reminders", "notifcenter", "mockloc",
            "podcast", "player", "podcast_dl", // hebben al een eigen plek in Instellingen/instellingen.json
            "pinned_shortcuts", // vastgezette snelkoppelingen moet je na een herinstallatie opnieuw plaatsen
            "checkin",          // privé: gaat alleen mee met het eigen vinkje Check-in
            "txdur", "txmeta"   // hoort bij Uitgeschreven gesprekken (namen en nummers van bellers): eigen onderdeel
    ));

    /**
     * Losse sleutels die nooit leesbaar mee mogen: alles wat naar een sleutel, wachtwoord of token klinkt (ook de
     * WhatsApp-sleutel waKey), plus persoonlijke tekst voor de assistent (die is privé, eigen vinkje).
     */
    static boolean secretKey(String file, String key) {
        String k = key.toLowerCase(java.util.Locale.ROOT);
        if (k.contains("token") || k.contains("secret") || k.contains("passw") || k.contains("wachtw") || k.contains("hash")
                || k.contains("salt") || k.contains("cred") || k.contains("refresh") || k.contains("key") || k.contains("sleutel")
                || k.contains("code")) return true;
        if ("localai".equals(file) && (k.startsWith("cfg_about") || k.startsWith("cfg_instr") || "chat".equals(k))) return true;
        if ("music".equals(file) && "token".equals(key)) return true;        // AudD-sleutel
        if ("spotify".equals(file) && ("access_token".equals(key) || "refresh".equals(key))) return true;
        return false;
    }

    /** Mappen in getFilesDir() die al een eigen onderdeel hebben, of alleen met toestemming mee gaan. */
    static final Set<String> FILES_OWN = new HashSet<>(Arrays.asList(
            "notes.json", "notes.json.bak", // Notities
            "music.json",                    // Herkende muziek
            "transcripts",                   // Uitgeschreven gesprekken
            "tracks",                        // Routes (GPX)
            "podcasts",                      // abonnementen en voortgang in Instellingen; de rest is opnieuw op te halen
            "ai", "sleep", "checkin",        // privé: eigen onderdeel met eigen toestemming
            "call-recordings",               // opnames van gesprekken (ook de stem van anderen): staan al in Gespreksopnames/
            "contacts"                       // versies van contacten: Contacten backup/ heeft de lijst als vCard
    ));

    /** Grenzen op wat er in het bestand komt (met base64 en tekens): ruim onder de 30 MB die terugzetten leest. */
    static final long MAX_FILE = 2L * 1024 * 1024, MAX_TOTAL = 8L * 1024 * 1024;

    /** Gaat dit instellingenbestand mee? */
    static boolean keepPrefsFile(String f) { return AppData.keepPrefsFile(f) && !PREFS_NEVER.contains(f); }

    /** Toestand van deze installatie (gevraagde toestemmingen, tellers, tijdstippen van de laatste keer): niet terugzetten. */
    static final Set<String> INSTALL = new HashSet<>(Arrays.asList(
            "contacts.suspectFp", "contacts.suspectSince", "contacts.lastCheck", "sms.lastExport", "sms.lastCount", "calls.lastExport", "calls.lastCount",
            "gebruik.watermark", "gebruik.failRun", "gebruik.lastCollect", "gebruik.lastShot", "gebruik.pruneAt", "gebruik.photoFail",
            "care.seen", "care.seenBattery", "care.last", "care.checkAt", "care.batteryAt", "care.optNotified",
            "spotify.lastAt", "spotify.lastFailAt", "spotify.lastMsg", "spotify.folderErr", "launcher.weather", "launcher.last",
            "music.used", "wabackup.lastOk", "wabackup.history"));

    static boolean installKey(String f, String k) { return INSTALL.contains(f + "." + k) || k.endsWith("Asked") || k.endsWith("asked"); }

    /** Gaat deze instelling mee? */
    static boolean keepPref(String f, String k) { return keepPrefsFile(f) && AppData.keepPref(f, k) && !secretKey(f, k) && !installKey(f, k); }

    /** Gaat dit bestand (pad binnen getFilesDir()) mee? */
    static boolean keepFile(String rel) {
        String r = AppData.safeRel(rel);
        if (r == null || AppData.fileRule(r) != null) return false;
        int slash = r.indexOf('/');
        String top = slash < 0 ? r : r.substring(0, slash);
        if (FILES_OWN.contains(top)) return false;
        if (r.startsWith("facturen/pdf/") || r.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) return false; // de PDF's staan al in Facturen/
        String low = r.toLowerCase(java.util.Locale.ROOT);
        if (low.matches(".*(geheim|secret|token|wachtwoord|password).*")) return false;
        // Mappen die nog niet bekeken zijn (een nieuwe tool): ook paden met sleutel, key, pin of cred niet leesbaar.
        return AppData.FILE_KNOWN.contains(top) || !low.matches(".*(passwd|sleutel|credential|apikey|api_key|private).*|.*(^|[/_.-])(key|keys|pin|cred)([/_.-].*|$)");
    }

    /** Een naam in JSON die naar een geheim klinkt ("refresh_token": …, "password": …). */
    /** Een naam die naar een geheim klinkt, gevolgd door : of = ("refresh_token": …, password=…, apiKey: …). */
    static final java.util.regex.Pattern SECRET_JSON = java.util.regex.Pattern.compile(
            "(token|secret|geheim|password|passwd|wachtwoord|sleutel|api_?key|private_?key|credential|refresh|(^|[^a-z])(key|pin|cred))[a-z0-9_.-]{0,40}[\"']?\\s*[:=]",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Een bestand van een map die nog niet bekeken is (niet in AppData.FILE_KNOWN, dus van een nieuwe tool) gaat alleen
     * leesbaar mee als het tekst is (text == null: iets anders) zonder iets wat naar een geheim klinkt. In Alle appgegevens
     * (versleuteld) gaat hij altijd mee.
     */
    static boolean readableOk(String rel, String text) {
        int slash = rel.indexOf('/');
        String top = slash < 0 ? rel : rel.substring(0, slash);
        return AppData.FILE_KNOWN.contains(top) || (text != null && !SECRET_JSON.matcher(text).find());
    }

    /** Lager = eerder mee: eigen invoer (eigen plekken, dossier, apparaten, auto) voor logboeken en versies. */
    static int priority(String rel) {
        if (rel.startsWith("toiletten/") || rel.startsWith("facturen/") || rel.startsWith("apparaten/")) return 0;
        if (rel.endsWith(".jsonl") || rel.contains("/versies/") || rel.contains("/pl/")) return 2;
        return 1;
    }

    static boolean text(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        for (String e : new String[]{".json", ".jsonl", ".txt", ".csv", ".vcf", ".xml", ".gpx", ".trk", ".md", ".html"}) if (n.endsWith(e)) return true;
        return false;
    }

    // ---------- maken ----------

    /** Schrijft Toolgegevens/toolgegevens.json; geeft het aantal instellingen + bestanden, -1 als er niets is. */
    static int export(Context c) throws Exception {
        Uri tree = WaBackup.destUri(c);
        if (tree == null) throw new Exception("Kies eerst een backup-map");
        JSONObject prefs = new JSONObject();
        int n = 0;
        for (String f : AppBackup.prefsOnDevice(c)) {
            if (!keepPrefsFile(f)) continue;
            SharedPreferences p = c.getSharedPreferences(f, Context.MODE_PRIVATE);
            JSONObject o = new JSONObject();
            for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
                if (!keepPref(f, e.getKey())) continue;
                JSONObject v = AppBackup.typed(e.getValue());
                if (v != null) { o.put(e.getKey(), v); n++; }
            }
            if (o.length() > 0) prefs.put(f, o);
        }
        List<String> files = new ArrayList<>();
        File root = c.getFilesDir().getCanonicalFile();
        File[] tops = root.listFiles();
        if (tops != null) { Arrays.sort(tops); for (File t : tops) collect(root, t, t.getName(), 0, files); }
        // Wat Rene zelf invoerde eerst, en kleine bestanden voor grote: past niet alles, dan vallen grote logboeken af
        Collections.sort(files, (x, y) -> {
            int px = priority(x), py = priority(y);
            if (px != py) return px - py;
            return Long.compare(new File(root, x).length(), new File(root, y).length());
        });
        WaBackup.Dest dest = new WaBackup.Dest(c.getContentResolver(), tree);
        WaBackup.DestDir dir = dest.dir(DIR, true);
        JSONArray skipped = new JSONArray();
        long total = 0;
        try (Sms.Out w = Sms.open(dest, dir, FILE, "application/json")) {
            w.write("{\"format\":" + JSONObject.quote(FORMAT) + ",\"version\":" + VERSION + ",\"exportedAt\":" + System.currentTimeMillis()
                    + ",\"app\":" + JSONObject.quote(Version.NAME) + ",\"prefs\":" + prefs.toString() + ",\"files\":[");
            boolean first = true;
            for (String rel : files) {
                File f = new File(root, rel);
                long len = f.length();
                if (len > MAX_FILE) { skipped.put(rel); continue; }
                byte[] b;
                try { b = read(f, MAX_FILE); } catch (Exception e) { skipped.put(rel); continue; }
                boolean asText = text(rel) && utf8(b);
                if (!readableOk(rel, asText ? new String(b, StandardCharsets.UTF_8) : null)) { skipped.put(rel); continue; }
                long cost = asText ? b.length + b.length / 8 : (b.length + 2) / 3 * 4; // ongeveer wat het in het bestand kost
                if (total + cost > MAX_TOTAL) { skipped.put(rel); continue; }
                total += cost;
                JSONObject o = new JSONObject().put("path", rel).put("t", f.lastModified());
                if (asText) o.put("text", new String(b, StandardCharsets.UTF_8));
                else o.put("b64", AppData.b64(b));
                w.write((first ? "" : ",") + o.toString());
                first = false;
                n++;
            }
            w.write("],\"skipped\":" + skipped.toString() + "}");
            w.done();
        }
        if (skipped.length() > 0) App.log(c, "BACKUP", "toolgegevens: " + skipped.length() + " bestanden niet mee (te groot, of mogelijk een geheim)");
        return n == 0 ? -1 : n;
    }

    static void collect(File root, File f, String rel, int depth, List<String> out) throws Exception {
        if (depth > AppData.MAX_DEPTH || out.size() > 20000) return;
        if (!f.getCanonicalFile().getPath().startsWith(root.getPath() + File.separator)) return; // geen koppelingen naar buiten
        if (f.isDirectory()) {
            if (depth == 0 && !keepFile(rel)) return;
            File[] kids = f.listFiles();
            if (kids == null) return;
            Arrays.sort(kids);
            for (File k : kids) collect(root, k, rel + "/" + k.getName(), depth + 1, out);
        } else if (f.isFile() && keepFile(rel)) out.add(rel);
    }

    static byte[] read(File f, long max) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) { b.write(buf, 0, r); if (b.size() > max) throw new Exception("te groot"); }
            return b.toByteArray();
        }
    }

    static boolean utf8(byte[] b) {
        try {
            java.nio.charset.CharsetDecoder d = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            d.decode(java.nio.ByteBuffer.wrap(b));
            return true;
        } catch (Exception e) { return false; }
    }

    // ---------- terugzetten ----------

    /** Wat er toegevoegd zou worden: alleen ontbrekende instellingen en bestanden. */
    static final class Plan {
        int total, fresh;
        final JSONObject prefs = new JSONObject(); // bestand → {sleutel: {t, v}}
        final List<JSONObject> files = new ArrayList<>();
        final JSONArray sample = new JSONArray();
        final Set<String> tools = new java.util.TreeSet<>();
    }

    static JSONObject parse(String text) throws Exception {
        JSONObject o;
        try { o = new JSONObject(text.trim()); } catch (Exception e) { throw new Exception("Dit is geen backup van toolgegevens (toolgegevens.json)"); }
        if (!FORMAT.equals(o.optString("format"))) throw new Exception("Dit is geen backup van toolgegevens (toolgegevens.json)");
        if (o.optInt("version", 0) > VERSION) throw new Exception("Deze backup komt uit een nieuwere versie van Tools; werk de app eerst bij");
        return o;
    }

    /** Herkenbare naam voor het overzicht (welke tools krijgen iets terug). */
    static String toolName(String key) {
        switch (key) {
            case "car": return Taal.T("Mijn auto");
            case "facturen": return Taal.T("Facturen");
            case "apparaten": return Taal.T("Apparaten");
            case "toiletten": return Taal.T("Toiletten en douches");
            case "contacts": return Taal.T("Contacten (versies)");
            case "spotify": return Taal.T("Spotify");
            case "gebruik": return Taal.T("Gebruik");
            case "call-recordings": case "call_recordings": return Taal.T("Gespreksopnames");
            case "regio": return Taal.T("Regio en taal");
            case "localai": case "models": return Taal.T("Lokale AI");
            case "sleeptalk": return Taal.T("Slaappraat");
            case "transcribe": case "txdur": case "txmeta": return Taal.T("Gesprekken uitschrijven");
            case "solo": return Taal.T("Losse apps");
            case "voorlezen": return Taal.T("Lokaal voorlezen");
            case "launcher": case "workspace": case "pinned_shortcuts": case "shortcut-icons": return Taal.T("Startscherm");
            default: return Taal.T("Overige instellingen");
        }
    }

    /**
     * Plan opstellen. has(file, key) = staat deze instelling er al; exists(rel) = staat dit bestand er al.
     * Zuiver (zonder Android), zodat het te testen is.
     */
    static Plan plan(JSONObject o, PrefsHas has, FileHas exists) throws Exception {
        Plan pl = new Plan();
        JSONObject prefs = o.optJSONObject("prefs");
        int prefsFiles = 0;
        if (prefs != null) for (Iterator<String> fi = prefs.keys(); fi.hasNext() && prefsFiles < AppData.MAX_PREFS_FILES; ) {
            String f = fi.next();
            JSONObject in = prefs.optJSONObject(f);
            if (in == null || !keepPrefsFile(f)) continue;
            prefsFiles++;
            JSONObject add = new JSONObject();
            for (Iterator<String> ki = in.keys(); ki.hasNext(); ) {
                String k = ki.next();
                JSONObject v = in.optJSONObject(k);
                if (v == null || !keepPref(f, k) || !validTyped(v)) continue;
                pl.total++;
                if (has.has(f, k)) continue;
                add.put(k, v); pl.fresh++;
            }
            if (add.length() > 0) { pl.prefs.put(f, add); pl.tools.add(toolName(f)); }
        }
        JSONArray files = o.optJSONArray("files");
        Set<String> seen = new HashSet<>();
        if (files != null) for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.optJSONObject(i);
            if (f == null) continue;
            String rel = AppData.safeRel(f.optString("path"));
            if (rel == null || !keepFile(rel) || !seen.add(rel)) continue;
            if (!f.has("text") && !f.has("b64")) continue;
            pl.total++;
            if (exists.exists(rel)) continue;
            pl.files.add(f); pl.fresh++;
            int slash = rel.indexOf('/');
            pl.tools.add(toolName(slash < 0 ? rel : rel.substring(0, slash)));
        }
        for (String t : pl.tools) if (pl.sample.length() < 8) pl.sample.put(t);
        return pl;
    }

    interface PrefsHas { boolean has(String file, String key); }
    interface FileHas { boolean exists(String rel); }

    static boolean validTyped(JSONObject v) {
        String t = v.optString("t");
        Object x = v.opt("v");
        switch (t) {
            case "b": return x instanceof Boolean;
            case "i": case "l": case "f": return x instanceof Number;
            case "s": return x instanceof String;
            case "S": return x instanceof JSONArray;
            default: return false;
        }
    }

    static Plan plan(Context c, JSONObject o) throws Exception {
        File root = c.getFilesDir();
        return plan(o, (f, k) -> c.getSharedPreferences(f, Context.MODE_PRIVATE).contains(k), rel -> new File(root, rel).exists());
    }

    static JSONObject preview(Context c, String text) throws Exception {
        Plan p = plan(c, parse(text));
        return new JSONObject().put("kind", "tooldata").put("total", p.total).put("fresh", p.fresh).put("dup", p.total - p.fresh).put("sample", p.sample);
    }

    /** Voegt toe wat ontbreekt. Geeft het aantal toegevoegde instellingen en bestanden. */
    static int apply(Context c, String text) throws Exception {
        Plan pl = plan(c, parse(text));
        int n = 0;
        for (Iterator<String> fi = pl.prefs.keys(); fi.hasNext(); ) {
            String f = fi.next();
            JSONObject add = pl.prefs.getJSONObject(f);
            SharedPreferences.Editor e = c.getSharedPreferences(f, Context.MODE_PRIVATE).edit();
            for (Iterator<String> ki = add.keys(); ki.hasNext(); ) {
                String k = ki.next();
                JSONObject v = add.getJSONObject(k);
                if (!PlayPolicy.keepPreference(c.getPackageName(), f, k)) continue; // zoals bij Alle appgegevens
                switch (v.optString("t")) {
                    case "b": e.putBoolean(k, v.optBoolean("v")); break;
                    case "i": e.putInt(k, v.optInt("v")); break;
                    case "l": e.putLong(k, v.optLong("v")); break;
                    case "f": e.putFloat(k, (float) v.optDouble("v")); break;
                    case "s": e.putString(k, v.optString("v")); break;
                    case "S": {
                        Set<String> s = new HashSet<>();
                        JSONArray a = v.optJSONArray("v");
                        for (int i = 0; a != null && i < a.length(); i++) s.add(a.optString(i));
                        e.putStringSet(k, s); break;
                    }
                    default: continue;
                }
                n++;
            }
            e.commit();
        }
        File root = c.getFilesDir().getCanonicalFile();
        for (JSONObject f : pl.files) {
            String rel = AppData.safeRel(f.optString("path"));
            if (rel == null) continue;
            File out = new File(root, rel);
            if (!out.getCanonicalFile().getPath().startsWith(root.getPath() + File.separator) || out.exists()) continue;
            byte[] b;
            try { b = f.has("text") ? f.optString("text").getBytes(StandardCharsets.UTF_8) : AppData.unb64(f.optString("b64")); }
            catch (Exception e) { continue; }
            File parent = out.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) continue;
            File tmp = new File(out.getPath() + ".tmp");
            try (OutputStream o = new FileOutputStream(tmp)) { o.write(b); }
            if (!tmp.renameTo(out)) { tmp.delete(); continue; }
            long t = f.optLong("t");
            if (t > 0) out.setLastModified(t);
            n++;
        }
        // Wat de tools bij opstarten plannen, opnieuw inplannen
        try { Herstel.rearmFile(c).createNewFile(); Herstel.afterRestart(c); } catch (Throwable ignored) { }
        return n;
    }

    /** Welke mappen en instellingen er mee gaan (voor de uitleg en de test). */
    static List<String> prefsFiles() {
        List<String> l = new ArrayList<>();
        for (String f : AppData.PREFS_KNOWN) if (keepPrefsFile(f)) l.add(f);
        Collections.sort(l);
        return l;
    }
}
