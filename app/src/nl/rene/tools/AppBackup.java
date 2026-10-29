package nl.rene.tools;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.StatFs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Alle appgegevens: een getrouwe kopie van de gegevens van de app in de versleutelde backup (onderdeel "app"), en
 * het terugzetten daarvan na een herinstallatie (alles vervangen). Wat er wel en niet meegaat staat in {@link AppData}.
 *
 * Terugzetten gaat in stappen, zodat er nooit een half teruggezette app overblijft:
 *  1. stage(): het archief helemaal lezen en controleren (inhoudsopgave, groottes, SHA-256) en alles klaarzetten in
 *     een eigen map buiten de app-gegevens (no_backup/herstel-klaar). Er verandert nog niets.
 *  2. apply(): de huidige mappen opzij (no_backup/herstel-oud), de klaargezette op hun plek, dan de instellingen.
 *     Elke stap staat in een logboek (herstel-logboek.txt). Valt de app halverwege om, dan zet recover() bij de
 *     volgende start alles terug (vóór het verplaatsen klaar was) of maakt het af (daarna).
 */
final class AppBackup {

    private AppBackup() { }

    static final long MAX_ENTRY = 8L << 30;          // 8 GB per bestand
    static final long MAX_TOTAL = 64L << 30;         // 64 GB in totaal
    static final int MAX_SMALL = 48 * 1024 * 1024;   // instellingen, geheimen, kluisindex: in het geheugen
    static final long MIN_FREE = 300L * 1024 * 1024; // zoveel ruimte moet er vrij blijven

    // ===================================================================================================
    // Maken (in de versleutelde Alles back-uppen)
    // ===================================================================================================

    /** Schrijft alles in het lopende versleutelde archief. Geeft het aantal bestanden; -1 = geen versleuteld archief. */
    static int export(Context c) throws Exception {
        Secure.Zip zip = Secure.CAPTURE.get();
        if (zip == null) return -1; // nooit leesbaar naar de backup-map
        AppData.Manifest m = new AppData.Manifest();
        m.app = Version.NAME;
        m.appCode = String.valueOf(Version.CODE);
        m.time = System.currentTimeMillis();

        // Instellingen (en de waarden die met de sleutel van de telefoon versleuteld zijn, apart en ontsleuteld)
        JSONObject secrets = new JSONObject();
        JSONObject prefs = prefsSnapshot(c, secrets, m);
        putBytes(zip, m, AppData.PREFS, prefs.toString().getBytes(StandardCharsets.UTF_8));
        putBytes(zip, m, AppData.SECRETS, secrets.toString().getBytes(StandardCharsets.UTF_8));

        // Kluis: ontsleuteld (de sleutel van de kluis blijft op de telefoon); index en documenten
        exportKluis(c, zip, m);
        // PDF-handtekening (ook met de kluissleutel versleuteld)
        try {
            JSONObject sig = PdfSignature.load(c);
            if (sig != null) putBytes(zip, m, AppData.SIGNATURE, sig.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { m.skipped.add(new String[]{"handtekening", "niet te lezen: " + e.getMessage()}); }

        // Bestanden
        File root = c.getFilesDir();
        File[] tops = root.listFiles();
        if (tops != null) {
            java.util.Arrays.sort(tops);
            for (File t : tops) {
                String rule = AppData.fileRule(t.getName());
                if (rule != null) { m.skipped.add(new String[]{t.getName(), rule}); continue; }
                walk(c, zip, m, root.getCanonicalFile(), t, t.getName(), 0);
            }
        }

        // Meldingsgeschiedenis (rij voor rij)
        putHistory(c, zip, m);

        // Welke modellen er waren (worden opnieuw gedownload) en welke toestemmingen (voor het lijstje achteraf)
        putBytes(zip, m, AppData.MODELS, Herstel.modelsJson(c).toString().getBytes(StandardCharsets.UTF_8));
        putBytes(zip, m, AppData.STATE, Herstel.stateJson(c).toString().getBytes(StandardCharsets.UTF_8));

        // Inhoudsopgave als laatste
        try (OutputStream o = zip.stream(AppData.DIR, "inhoud.txt")) { o.write(AppData.build(m).getBytes(StandardCharsets.UTF_8)); }
        return m.entries.size();
    }

    /** Alle instellingen die meegaan, met hun soort: {bestand: {sleutel: {t, v}}}. */
    static JSONObject prefsSnapshot(Context c, JSONObject secrets, AppData.Manifest m) throws Exception {
        JSONObject files = new JSONObject();
        for (String f : AppData.PREFS_KEEP) {
            SharedPreferences p = c.getSharedPreferences(f, Context.MODE_PRIVATE);
            JSONObject o = new JSONObject();
            for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
                if (!AppData.keepPref(f, e.getKey())) continue;
                JSONObject v = typed(e.getValue());
                if (v != null) o.put(e.getKey(), v);
            }
            if ("vpn".equals(f) && o.has("tunnels")) o.put("tunnels", typed(vpnOut(c, o.getJSONObject("tunnels").optString("v", "[]"), secrets, m)));
            if (o.length() > 0) files.put(f, o);
        }
        // Spotify-koppeling (ververs-sleutel, versleuteld met de sleutel van de telefoon)
        String r = Spotify.prefs(c).getString("refresh", null);
        if (r != null) {
            try { secrets.put("spotify", new JSONObject().put("refresh", AppData.open(Secure.deviceKey(), r))); }
            catch (Exception e) { m.skipped.add(new String[]{"spotify", "koppeling niet te lezen: opnieuw koppelen"}); }
        }
        return new JSONObject().put("format", AppData.FORMAT + "-prefs").put("version", 1).put("files", files);
    }

    /** VPN-tunnels zonder de versleutelde delen; die gaan ontsleuteld naar secrets.vpn[id]. */
    static String vpnOut(Context c, String tunnels, JSONObject secrets, AppData.Manifest m) throws Exception {
        JSONArray a = new JSONArray(tunnels), out = new JSONArray();
        JSONObject vs = new JSONObject();
        for (int i = 0; i < a.length(); i++) {
            JSONObject t = a.optJSONObject(i);
            if (t == null) continue;
            JSONObject s = new JSONObject();
            for (String k : new String[]{"conf", "cred"}) {
                if (!t.has(k)) continue;
                try { s.put(k, AppData.open(Secure.deviceKey(), t.getString(k))); }
                catch (Exception e) { m.skipped.add(new String[]{"vpn:" + t.optString("id"), "niet te lezen: opnieuw instellen"}); }
                t.remove(k);
            }
            if (s.length() > 0) vs.put(t.optString("id"), s);
            out.put(t);
        }
        if (vs.length() > 0) secrets.put("vpn", vs);
        return out.toString();
    }

    static JSONObject typed(Object v) throws Exception {
        if (v instanceof Boolean) return new JSONObject().put("t", "b").put("v", v);
        if (v instanceof Integer) return new JSONObject().put("t", "i").put("v", v);
        if (v instanceof Long) return new JSONObject().put("t", "l").put("v", v);
        if (v instanceof Float) return new JSONObject().put("t", "f").put("v", ((Float) v).doubleValue());
        if (v instanceof String) return new JSONObject().put("t", "s").put("v", v);
        if (v instanceof Set) {
            JSONArray a = new JSONArray();
            for (Object x : (Set<?>) v) if (x instanceof String) a.put(x);
            return new JSONObject().put("t", "S").put("v", a);
        }
        return null;
    }

    static void walk(Context c, Secure.Zip zip, AppData.Manifest m, File root, File f, String rel, int depth) throws Exception {
        if (depth > AppData.MAX_DEPTH) { m.skipped.add(new String[]{rel, "te diep"}); return; }
        // Geen snelkoppelingen naar buiten de app-map volgen
        File cf = f.getCanonicalFile();
        if (!cf.getPath().startsWith(root.getPath() + File.separator)) { m.skipped.add(new String[]{rel, "buiten de app-map"}); return; }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids == null) return;
            java.util.Arrays.sort(kids);
            for (File k : kids) {
                String r = rel + "/" + k.getName();
                String rule = AppData.fileRule(r);
                if (rule != null) { if (!"tijdelijk bestand".equals(rule)) m.skipped.add(new String[]{r, rule}); continue; }
                walk(c, zip, m, root, k, r, depth + 1);
            }
            return;
        }
        if (!f.isFile() || AppData.fileRule(rel) != null) return;
        String path = AppData.FILES + "/" + rel;
        int slash = path.lastIndexOf('/');
        OutputStream raw = null;
        String written = null;
        AppData.DigestOut d = null;
        try (InputStream in = new FileInputStream(f)) {
            raw = zip.stream(path.substring(0, slash), path.substring(slash + 1), AppData.compressed(rel) ? 1 : 6);
            written = zip.lastPath;
            d = new AppData.DigestOut(raw);
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) d.write(b, 0, n);
            d.close();
            raw = null;
            if (!path.equals(written)) { m.broken.add(written); m.skipped.add(new String[]{rel, "dubbele naam"}); return; }
            m.entries.add(new AppData.Entry(path, d.n, d.sha(), f.lastModified()));
        } catch (java.io.FileNotFoundException e) {
            if (raw != null) { try { raw.close(); } catch (Exception ignored) { } if (written != null) m.broken.add(written); }
            // intussen weggehaald: geen fout
        } catch (IOException e) {
            if (raw != null) try { raw.close(); } catch (Exception ignored) { }
            if (written == null) throw e; // het archief zelf: dan ook niet verder
            m.broken.add(written);
            zip.bad.add(written);
            m.skipped.add(new String[]{rel, "niet te lezen: " + e.getMessage()});
        }
    }

    static void putBytes(Secure.Zip zip, AppData.Manifest m, String path, byte[] b) throws Exception {
        int slash = path.lastIndexOf('/');
        try (AppData.DigestOut d = new AppData.DigestOut(zip.stream(path.substring(0, slash), path.substring(slash + 1)))) {
            d.write(b, 0, b.length);
            d.flush();
            if (!path.equals(zip.lastPath)) throw new IOException("Dubbele naam in het archief: " + path);
            d.close();
            m.entries.add(new AppData.Entry(path, d.n, d.sha()));
        }
    }

    static void putHistory(Context c, Secure.Zip zip, AppData.Manifest m) throws Exception {
        try (AppData.DigestOut d = new AppData.DigestOut(zip.stream(AppData.DIR, "meldingen.jsonl"))) {
            Writer w = new java.io.BufferedWriter(new OutputStreamWriter(d, StandardCharsets.UTF_8), 1 << 16);
            try { NotificationHistory.dumpRows(c, w); }
            catch (Exception e) { m.skipped.add(new String[]{"meldingen", "niet te lezen: " + e.getMessage()}); }
            w.flush();
            d.close();
            m.entries.add(new AppData.Entry(AppData.HISTORY, d.n, d.sha()));
        }
    }

    static void exportKluis(Context c, Secure.Zip zip, AppData.Manifest m) throws Exception {
        JSONArray l;
        try { l = Kluis.list(c); }
        catch (Exception e) { m.skipped.add(new String[]{"kluis", "niet te lezen: " + e.getMessage()}); return; }
        if (l.length() == 0) return;
        JSONArray ok = new JSONArray();
        for (int i = 0; i < l.length(); i++) {
            JSONObject d = l.getJSONObject(i);
            String id = d.optString("id");
            if (!id.matches("[a-z0-9]{6,24}")) continue;
            File f = new File(Kluis.dir(c), id + ".bin");
            File tmp = new File(c.getCacheDir(), "kluis-app.tmp");
            String written = null;
            // Eerst helemaal ontsleutelen en controleren, pas dan in het archief (nooit een half document)
            try {
                try (InputStream in = new java.io.BufferedInputStream(new FileInputStream(f), 1 << 16); OutputStream o = new FileOutputStream(tmp)) { Kluis.decrypt(in, o); }
                String path = AppData.KLUIS_DIR + "/" + id;
                try (InputStream in = new FileInputStream(tmp); AppData.DigestOut o = new AppData.DigestOut(zip.stream(AppData.KLUIS_DIR, id, AppData.compressed(d.optString("name")) ? 1 : 6))) {
                    written = zip.lastPath;
                    byte[] b = new byte[1 << 16]; int r; while ((r = in.read(b)) > 0) o.write(b, 0, r);
                    o.close();
                    m.entries.add(new AppData.Entry(path, o.n, o.sha()));
                    written = null;
                }
                ok.put(d);
            } catch (Exception e) {
                if (written != null) { m.broken.add(written); zip.bad.add(written); }
                m.skipped.add(new String[]{"kluis:" + id, "niet te lezen: " + e.getMessage()});
                App.log(c, "BACKUP", "appgegevens kluis " + id + ": " + e.getMessage());
            } finally { tmp.delete(); }
        }
        putBytes(zip, m, AppData.KLUIS_INDEX, ok.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ===================================================================================================
    // Terugzetten
    // ===================================================================================================

    static File stageDir(Context c) { return new File(c.getNoBackupFilesDir(), "herstel-klaar"); }
    static File oldDir(Context c) { return new File(c.getNoBackupFilesDir(), "herstel-oud"); }
    static File journal(Context c) { return new File(c.getNoBackupFilesDir(), "herstel-logboek.txt"); }

    /** Klaargezet en gecontroleerd (tussen stage() en apply()). */
    static final class Staged {
        AppData.Manifest manifest;
        Vault.Key key;
        String name;
        int files, kluis;
        boolean history, signature;
    }

    static volatile Staged staged;

    /** Telt wat er gelezen is (voor de voortgang). */
    static final class CountIn extends FilterInputStream {
        long n;
        CountIn(InputStream in) { super(in); }
        @Override public int read() throws IOException { int r = super.read(); if (r >= 0) n++; return r; }
        @Override public int read(byte[] b, int o, int l) throws IOException { int r = in.read(b, o, l); if (r > 0) n += r; return r; }
    }

    interface Progress { void at(long done, long total); }

    /** Archief zonder appgegevens (gemaakt vóór deze functie, of zonder versleutelen). */
    static final class NoAppData extends Exception { NoAppData() { super("Deze backup bevat nog geen appgegevens"); } }

    /** Map met alles erin weg (een snelkoppeling zelf weg, nooit wat erachter zit). */
    static void wipe(File f) {
        if (f == null || !f.exists()) return;
        File[] k = f.isDirectory() && !isLink(f) ? f.listFiles() : null;
        if (k != null) for (File x : k) wipe(x);
        f.delete();
    }

    static boolean isLink(File f) {
        try { return android.system.OsConstants.S_ISLNK(android.system.Os.lstat(f.getPath()).st_mode); }
        catch (Throwable e) { return true; }
    }

    static void cancel(Context c) {
        Staged s = staged;
        staged = null;
        if (s != null && s.key != null) java.util.Arrays.fill(s.key.key, (byte) 0);
        if (!journal(c).exists()) wipe(stageDir(c));
    }

    /** Leest en controleert het hele archief en zet alles klaar. Verandert niets aan de app. */
    static synchronized Staged stage(Context c, Uri u, Vault.Key k, long total, Progress pr) throws Exception {
        cancel(c);
        if (journal(c).exists()) throw new Exception("Er is nog een eerder terugzetten bezig; start de app opnieuw");
        File st = stageDir(c);
        wipe(st);
        if (!st.mkdirs()) throw new Exception("Klaarzetten lukt niet");
        File stFiles = new File(st, "files"), stKluis = new File(st, "kluis");
        stFiles.mkdirs(); stKluis.mkdirs();
        String stFilesPath = stFiles.getCanonicalPath() + File.separator;
        Map<String, AppData.Entry> seen = new HashMap<>();
        String manifest = null;
        byte[] prefs = null, secrets = null, kluisIndex = null, signature = null, models = null, state = null;
        Staged s = new Staged();
        boolean ok = false;
        long all = 0, lastFree = 0;
        InputStream raw0 = c.getContentResolver().openInputStream(u);
        if (raw0 == null) throw new Exception("Bestand niet te openen");
        CountIn count = new CountIn(new java.io.BufferedInputStream(raw0, 1 << 16));
        Vault.In vin = new Vault.In(count, Vault.readHeader(count), k);
        Map<String, Long> mtimes = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(vin)) {
            ZipEntry e;
            byte[] buf = new byte[1 << 16];
            while ((e = z.getNextEntry()) != null) {
                String name = e.getName();
                if (pr != null) pr.at(count.n, total);
                if (e.isDirectory() || !name.startsWith(AppData.DIR + "/")) { drain(z, buf); continue; } // andere onderdelen: alleen doorlezen
                if (name.equals(AppData.MANIFEST)) { manifest = new String(small(z, buf, 16_000_000), StandardCharsets.UTF_8); continue; }
                String t = AppData.target(name);
                if (AppData.safeRel(name) == null) throw new Exception("Onveilige naam in de backup: " + AppData.cut(name));
                if (t == null) { drain(z, buf); continue; } // van een nieuwere versie, of niet meer bekend: overslaan
                long en;
                String esha;
                if (t.startsWith("f:")) {
                    File out = new File(stFiles, t.substring(2));
                    if (!out.getCanonicalPath().startsWith(stFilesPath)) throw new Exception("Onveilige naam in de backup: " + AppData.cut(name));
                    File dir = out.getParentFile();
                    if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new Exception("Klaarzetten lukt niet");
                    try (AppData.DigestOut d = new AppData.DigestOut(new java.io.BufferedOutputStream(new FileOutputStream(out), 1 << 16))) {
                        int r;
                        while ((r = z.read(buf)) > 0) {
                            d.write(buf, 0, r);
                            if (d.n > MAX_ENTRY) throw new Exception("Bestand in de backup is te groot: " + AppData.cut(name));
                            all += r;
                            if (all - lastFree > 32L * 1024 * 1024) { lastFree = all; checkFree(c); if (pr != null) pr.at(count.n, total); }
                        }
                        d.flush();
                        en = d.n; esha = d.sha();
                    }
                    mtimes.put(name, 0L);
                    s.files++;
                } else if (t.startsWith("kluis:")) {
                    // Meteen weer versleutelen met de kluissleutel van deze installatie: nooit ontsleuteld op de telefoon
                    String id = t.substring(6);
                    File out = new File(stKluis, id + ".bin");
                    DigestIn din = new DigestIn(z);
                    try (OutputStream o = new java.io.BufferedOutputStream(new FileOutputStream(out), 1 << 16)) { Kluis.encrypt(din, o); }
                    drain(din, buf); // (Kluis.encrypt leest tot het einde; voor de zekerheid)
                    en = din.n; esha = AppData.hex(din.md.digest());
                    all += en;
                    s.kluis++;
                } else if ("history".equals(t)) {
                    try (AppData.DigestOut d = new AppData.DigestOut(new java.io.BufferedOutputStream(new FileOutputStream(new File(st, "meldingen.jsonl")), 1 << 16))) {
                        int r;
                        while ((r = z.read(buf)) > 0) { d.write(buf, 0, r); if (d.n > 512L * 1024 * 1024) throw new Exception("Meldingsgeschiedenis is te groot"); }
                        d.flush();
                        en = d.n; esha = d.sha();
                    }
                    all += en;
                    s.history = true;
                } else {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    AppData.DigestOut d = new AppData.DigestOut(b);
                    int r;
                    while ((r = z.read(buf)) > 0) { d.write(buf, 0, r); if (d.n > MAX_SMALL) throw new Exception("Te groot in de backup: " + AppData.cut(name)); }
                    en = d.n; esha = d.sha();
                    byte[] v = b.toByteArray();
                    if ("prefs".equals(t)) prefs = v;
                    else if ("secrets".equals(t)) secrets = v;
                    else if ("kluis-index".equals(t)) kluisIndex = v;
                    else if ("signature".equals(t)) signature = v;
                    else if ("models".equals(t)) models = v;
                    else if ("state".equals(t)) state = v;
                }
                if (seen.containsKey(name)) throw new Exception("Dubbele naam in de backup: " + AppData.cut(name));
                seen.put(name, new AppData.Entry(name, en, esha));
                if (all > MAX_TOTAL) throw new Exception("De backup is te groot");
            }
            drain(vin, buf); // ook het slot van het archief: pas dan is alles echt gecontroleerd (afgekapt = fout)
            if (manifest == null && !seen.isEmpty()) throw new Exception("De appgegevens in deze backup zijn onvolledig (afgebroken backup); kies een andere backup");
            if (manifest == null) throw new NoAppData();
            AppData.Manifest m = AppData.parse(manifest);
            String bad = AppData.verify(m, seen);
            if (bad != null) throw new Exception(bad);
            // Bestanden weer met hun oude tijd (opnames en routes staan op volgorde van tijd)
            for (AppData.Entry e2 : m.entries) {
                if (e2.mtime <= 0 || !mtimes.containsKey(e2.path)) continue;
                String t2 = AppData.target(e2.path);
                if (t2 != null && t2.startsWith("f:")) new File(stFiles, t2.substring(2)).setLastModified(e2.mtime);
            }
            if (prefs == null) throw new Exception("Instellingen ontbreken in de backup");
            // Alles wat terug gaat, nu al inlezen en controleren (pas daarna mag er iets veranderen)
            JSONObject ready = readyPrefs(c, prefs, secrets);
            Contacts.writeText(new File(st, "instellingen-klaar.json"), ready.toString(), false);
            if (kluisIndex != null) {
                JSONArray idx = cleanKluisIndex(new String(kluisIndex, StandardCharsets.UTF_8), stKluis);
                Kluis.writeEncrypted(new File(stKluis, "index.bin"), idx.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (signature != null) {
                try { Contacts.writeText(new File(st, "handtekening.json"), PdfSignature.normalize(new String(signature, StandardCharsets.UTF_8)).toString(), false); s.signature = true; }
                catch (Exception ex) { App.log(c, "HERSTEL", "handtekening overgeslagen: " + ex.getMessage()); }
            }
            if (models != null) Contacts.writeText(new File(st, "modellen.json"), new String(models, StandardCharsets.UTF_8), false);
            if (state != null) Contacts.writeText(new File(st, "toestand.json"), new String(state, StandardCharsets.UTF_8), false);
            s.manifest = m;
            s.key = k;
            if (pr != null) pr.at(total, total);
            ok = true;
            staged = s;
            return s;
        } finally {
            if (prefs != null) java.util.Arrays.fill(prefs, (byte) 0);
            if (secrets != null) java.util.Arrays.fill(secrets, (byte) 0);
            if (!ok) wipe(st);
        }
    }

    static void drain(InputStream in, byte[] buf) throws IOException { while (in.read(buf) > 0) { /* doorlezen: controleert de versleuteling */ } }

    static byte[] small(InputStream in, byte[] buf, int max) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int r;
        while ((r = in.read(buf)) > 0) { b.write(buf, 0, r); if (b.size() > max) throw new Exception("Te groot in de backup"); }
        return b.toByteArray();
    }

    static void checkFree(Context c) throws Exception {
        long free;
        try { free = new StatFs(c.getNoBackupFilesDir().getPath()).getAvailableBytes(); } catch (Throwable t) { return; }
        if (free < MIN_FREE) throw new Exception("Te weinig ruimte op de telefoon om terug te zetten");
    }

    /** Leest door (zonder sluiten) en telt bytes en SHA-256 mee: voor het opnieuw versleutelen van kluisdocumenten. */
    static final class DigestIn extends FilterInputStream {
        final java.security.MessageDigest md = AppData.sha256();
        long n;
        DigestIn(InputStream in) { super(in); }
        @Override public int read() throws IOException { int r = in.read(); if (r >= 0) { md.update((byte) r); n++; } return r; }
        @Override public int read(byte[] b, int o, int l) throws IOException { int r = in.read(b, o, l); if (r > 0) { md.update(b, o, r); n += r; } return r; }
        @Override public void close() { /* het archief blijft open */ }
    }

    /** Kluisindex uit de backup: alleen bekende velden, en alleen documenten die er ook echt in zaten. */
    static JSONArray cleanKluisIndex(String text, File stKluis) throws Exception {
        JSONArray in = new JSONArray(text), out = new JSONArray();
        for (int i = 0; i < in.length(); i++) {
            JSONObject d = in.optJSONObject(i);
            if (d == null) continue;
            String id = d.optString("id");
            if (!id.matches("[a-z0-9]{6,24}") || !new File(stKluis, id + ".bin").isFile()) continue;
            JSONObject o = new JSONObject().put("id", id).put("name", cut(d.optString("name", "document"), 120))
                    .put("mime", cut(d.optString("mime", "application/octet-stream"), 120)).put("size", Math.max(0, d.optLong("size")))
                    .put("t", d.optLong("t", System.currentTimeMillis())).put("category", DocumentMeta.category(d.optString("category", "")));
            if (d.has("note")) o.put("note", DocumentMeta.note(d.optString("note")));
            if (d.optBoolean("favorite")) o.put("favorite", true);
            out.put(o);
        }
        return out;
    }

    static String cut(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }

    /**
     * Instellingen uit de backup omzetten naar wat er straks precies wordt weggeschreven: alleen bekende bestanden en
     * sleutels met een bekend soort, en de geheimen alvast versleuteld met de sleutel van deze installatie.
     */
    static JSONObject readyPrefs(Context c, byte[] prefs, byte[] secretBytes) throws Exception {
        JSONObject root = new JSONObject(new String(prefs, StandardCharsets.UTF_8));
        if (!(AppData.FORMAT + "-prefs").equals(root.optString("format"))) throw new Exception("Instellingen in de backup zijn niet te lezen");
        JSONObject in = root.optJSONObject("files");
        JSONObject secrets = secretBytes == null ? new JSONObject() : new JSONObject(new String(secretBytes, StandardCharsets.UTF_8));
        JSONObject out = new JSONObject();
        if (in == null) return out;
        for (Iterator<String> it = in.keys(); it.hasNext(); ) {
            String f = it.next();
            if (!AppData.keepPrefsFile(f)) continue;
            JSONObject src = in.optJSONObject(f);
            if (src == null) continue;
            JSONObject dst = new JSONObject();
            for (Iterator<String> ks = src.keys(); ks.hasNext(); ) {
                String key = ks.next();
                if (!AppData.keepPref(f, key)) continue;
                JSONObject v = src.optJSONObject(key);
                if (v == null || !valid(v)) continue;
                dst.put(key, v);
            }
            out.put(f, dst);
        }
        // VPN: de sleutels en wachtwoorden weer versleutelen met de sleutel van deze installatie
        JSONObject vpn = out.optJSONObject("vpn");
        if (vpn != null && vpn.optJSONObject("tunnels") != null) {
            JSONArray a = new JSONArray(vpn.getJSONObject("tunnels").optString("v", "[]"));
            JSONObject vs = secrets.optJSONObject("vpn");
            JSONArray clean = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                JSONObject t = a.optJSONObject(i);
                if (t == null || !t.optString("id").matches("[A-Za-z0-9_-]{1,64}")) continue;
                JSONObject s = vs == null ? null : vs.optJSONObject(t.optString("id"));
                if (s != null) for (String k : new String[]{"conf", "cred"}) if (s.has(k)) t.put(k, AppData.seal(Secure.deviceKey(), s.getString(k)));
                clean.put(t);
            }
            vpn.put("tunnels", typed(clean.toString()));
        }
        JSONObject sp = secrets.optJSONObject("spotify");
        if (sp != null && !sp.optString("refresh").isEmpty()) {
            JSONObject f = out.optJSONObject("spotify");
            if (f == null) { f = new JSONObject(); out.put("spotify", f); }
            f.put("refresh", typed(AppData.seal(Secure.deviceKey(), sp.getString("refresh"))));
        }
        // App-slot: de vingerafdruksleutel is weg met de oude installatie; met de app-code kom je er wel in
        JSONObject lock = out.optJSONObject("lock");
        if (lock != null && lock.optJSONObject("codeBio") != null && lock.getJSONObject("codeBio").optBoolean("v")) {
            lock.put("codeBio", typed(Boolean.FALSE));
            out.put("_bio", true);
        }
        // Interface-instellingen: bij de volgende start in de WebView zetten (en vervangen wat er staat)
        JSONObject ws = out.optJSONObject("webstore");
        if (ws != null && ws.optJSONObject("all") != null) {
            ws.put("restoreAll", ws.getJSONObject("all")).put("pending", typed(Boolean.TRUE)).put("replace", typed(Boolean.TRUE));
        }
        return out;
    }

    static boolean valid(JSONObject v) {
        String t = v.optString("t");
        Object x = v.opt("v");
        switch (t) {
            case "b": return x instanceof Boolean;
            case "i": case "l": case "f": return x instanceof Number;
            case "s": return x instanceof String && ((String) x).length() <= 16_000_000;
            case "S": return x instanceof JSONArray && ((JSONArray) x).length() <= 10_000;
            default: return false;
        }
    }

    // ---------- toepassen ----------

    /**
     * Zet het klaargezette terug: alles vervangen. Gooit als er niets veranderd is (fout vóór het verplaatsen).
     * Geeft "" als alles klaar is, of een melding als het na het verplaatsen misging: dan maakt recover() het bij de
     * volgende start af (de app moet dus opnieuw starten).
     */
    static synchronized String apply(Context c) throws Exception {
        Staged s = staged;
        if (s == null || !stageDir(c).isDirectory()) throw new Exception("Open de backup opnieuw");
        File st = stageDir(c), old = oldDir(c), files = c.getFilesDir();
        wipe(old);
        if (!old.mkdirs()) throw new Exception("Terugzetten lukt niet (geen ruimte?)");
        Journal j = new Journal(journal(c));
        try {
            j.line("begin");
            // 1. Mappen en bestanden: de huidige opzij, de klaargezette op hun plek
            List<String> roots = new ArrayList<>(AppData.FILE_ROOTS);
            for (String r : roots) {
                File cur = new File(files, r);
                if (cur.exists()) j.move(cur, new File(old, "files/" + r));
                File in = new File(st, "files/" + r);
                if (in.exists()) j.move(in, cur);
            }
            File kl = new File(files, "kluis");
            if (kl.exists()) j.move(kl, new File(old, "files/kluis"));
            if (new File(st, "kluis").exists()) j.move(new File(st, "kluis"), kl);
            j.line("files");
        } catch (Exception e) {
            j.close();
            rollback(c);
            throw new Exception("Terugzetten mislukt; er is niets veranderd (" + e.getMessage() + ")");
        }
        j.close();
        try { finish(c, s.key); return ""; }
        catch (Exception e) {
            App.log(c, "HERSTEL", "afmaken: " + e.getMessage());
            return "Bijna klaar: de app maakt het terugzetten af bij het opnieuw starten";
        }
    }

    /** Na het verplaatsen: instellingen, geschiedenis, handtekening, en de sleutel. Ook bij afmaken na omvallen. */
    static void finish(Context c, Vault.Key key) throws Exception {
        File st = stageDir(c);
        JSONObject ready = new JSONObject(Contacts.readText(new File(st, "instellingen-klaar.json"), false));
        writePrefs(c, ready);
        Journal j = new Journal(journal(c));
        j.line("prefs");
        j.close();
        File h = new File(st, "meldingen.jsonl");
        if (h.isFile()) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(h), StandardCharsets.UTF_8))) { NotificationHistory.replaceRows(c, r); }
            catch (Exception e) { App.log(c, "HERSTEL", "meldingsgeschiedenis: " + e.getMessage()); }
        }
        File sig = new File(st, "handtekening.json");
        if (sig.isFile()) try { PdfSignature.save(c, Contacts.readText(sig, false)); } catch (Exception e) { App.log(c, "HERSTEL", "handtekening: " + e.getMessage()); }
        if (key != null) Secure.setKey(c, key);
        Herstel.restored(c, ready.optBoolean("_bio"), key != null, readOr(new File(st, "modellen.json")), readOr(new File(st, "toestand.json")));
        j = new Journal(journal(c));
        j.line("ok");
        j.close();
        journal(c).delete();
        wipe(oldDir(c));
        wipe(st);
        Staged s = staged;
        staged = null;
        if (s != null && s.key != null) java.util.Arrays.fill(s.key.key, (byte) 0);
    }

    /** Alle instellingen die meegaan vervangen door die uit de backup (wat bij deze installatie hoort blijft staan). */
    static void writePrefs(Context c, JSONObject ready) throws Exception {
        for (String f : AppData.PREFS_KEEP) {
            SharedPreferences p = c.getSharedPreferences(f, Context.MODE_PRIVATE);
            SharedPreferences.Editor e = p.edit();
            for (String k : p.getAll().keySet()) if (AppData.keepPref(f, k) || ("spotify".equals(f) && "refresh".equals(k))) e.remove(k);
            JSONObject in = ready.optJSONObject(f);
            if (in != null) for (Iterator<String> it = in.keys(); it.hasNext(); ) {
                String k = it.next();
                JSONObject v = in.optJSONObject(k);
                if (v == null || !valid(v)) continue;
                boolean special = "webstore".equals(f) && ("restoreAll".equals(k) || "pending".equals(k) || "replace".equals(k));
                if (!special && !AppData.keepPref(f, k) && !("spotify".equals(f) && "refresh".equals(k))) continue;
                Object x = v.opt("v");
                switch (v.optString("t")) {
                    case "b": e.putBoolean(k, (Boolean) x); break;
                    case "i": e.putInt(k, ((Number) x).intValue()); break;
                    case "l": e.putLong(k, ((Number) x).longValue()); break;
                    case "f": e.putFloat(k, ((Number) x).floatValue()); break;
                    case "s": e.putString(k, String.valueOf(x)); break;
                    case "S": {
                        java.util.Set<String> set = new java.util.HashSet<>();
                        JSONArray a = (JSONArray) x;
                        for (int i = 0; i < a.length(); i++) set.add(a.optString(i));
                        e.putStringSet(k, set);
                        break;
                    }
                    default: break;
                }
            }
            if (!e.commit()) throw new Exception("Instellingen bewaren lukt niet (" + f + ")");
        }
    }

    /** Logboek van het terugzetten: elke verplaatsing staat erin vóór hij gebeurt. */
    static final class Journal {
        private final FileOutputStream out;
        Journal(File f) throws IOException { out = new FileOutputStream(f, true); }
        void line(String s) throws IOException { out.write((s + "\n").getBytes(StandardCharsets.UTF_8)); out.flush(); try { out.getFD().sync(); } catch (Exception ignored) { } }
        void move(File from, File to) throws Exception {
            File p = to.getParentFile();
            if (p != null && !p.isDirectory() && !p.mkdirs()) throw new IOException("map maken lukt niet");
            line("M\t" + from.getPath() + "\t" + to.getPath());
            if (!from.renameTo(to)) throw new IOException("verplaatsen lukt niet: " + from.getName());
        }
        void close() { try { out.close(); } catch (Exception ignored) { } }
    }

    /** Alles wat verplaatst is terug op zijn plek (in omgekeerde volgorde). */
    static void rollback(Context c) {
        File jf = journal(c);
        try {
            List<String[]> moves = new ArrayList<>();
            for (String l : Contacts.readText(jf, false).split("\n")) {
                String[] p = l.split("\t");
                if (p.length == 3 && "M".equals(p[0])) moves.add(p);
            }
            for (int i = moves.size() - 1; i >= 0; i--) {
                File from = new File(moves.get(i)[1]), to = new File(moves.get(i)[2]);
                if (to.exists() && !from.exists()) to.renameTo(from);
            }
        } catch (Throwable t) { App.log(c, "HERSTEL", "terugdraaien: " + t); }
        jf.delete();
        wipe(oldDir(c));
        wipe(stageDir(c));
        staged = null;
    }

    /**
     * Bij het starten van de app: is een vorig terugzetten halverwege gestopt (app omgevallen, telefoon uit)?
     * Vóór de bestanden klaar waren: alles terug zoals het was. Daarna: afmaken met wat klaarstaat.
     */
    static synchronized void recover(Context c) {
        try {
            File jf = journal(c);
            if (!jf.isFile()) return;
            String log = Contacts.readText(jf, false);
            if (log.contains("\nok\n") || log.startsWith("ok\n")) { jf.delete(); wipe(oldDir(c)); wipe(stageDir(c)); return; }
            if (!log.contains("\nfiles\n")) { App.log(c, "HERSTEL", "onderbroken terugzetten: teruggedraaid"); rollback(c); return; }
            App.log(c, "HERSTEL", "onderbroken terugzetten: afgemaakt");
            finish(c, null); // de sleutel is niet meer bekend: het lijstje vraagt dan het wachtwoord opnieuw in te stellen
        } catch (Throwable t) { App.log(c, "HERSTEL", "herstellen na onderbreking: " + t); }
    }

    // ---------- wat er al op de telefoon staat ----------

    /** Staat er al iets van jezelf op deze telefoon (dan waarschuwen dat het overschreven wordt)? */
    static boolean hasLocalData(Context c) {
        try { String n = Notes.load(c); if (n != null && n.trim().length() > 2 && new JSONArray(n).length() > 0) return true; } catch (Exception ignored) { }
        try { if (Auto.rules(c).length() > 0) return true; } catch (Exception ignored) { }
        try { if (Kluis.list(c).length() > 0) return true; } catch (Exception ignored) { }
        for (String r : new String[]{"call-recordings", "tracks", "transcripts", "sleep", "checkin"}) {
            File[] k = new File(c.getFilesDir(), r).listFiles();
            if (k != null && k.length > 0) return true;
        }
        return false;
    }

    /** Voor de samenvatting vóór terugzetten. */
    static JSONObject summary(Staged s) throws Exception {
        return new JSONObject().put("app", s.manifest.app).put("t", s.manifest.time).put("files", s.files).put("kluis", s.kluis)
                .put("bytes", s.manifest.bytes()).put("name", s.name == null ? "" : s.name).put("history", s.history);
    }

    static String readOr(File f) { try { return f.isFile() ? Contacts.readText(f, false) : null; } catch (Exception e) { return null; } }
}
