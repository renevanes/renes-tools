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
import java.util.HashSet;
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
 * Dubbel opslaan wordt vermeden: staat precies dezelfde inhoud al bij een ander onderdeel in hetzelfde archief
 * (kluisdocumenten in Kluis/, Slaappraat-geluid in Slaappraat/), dan komt er alleen een verwijzing (R-regel) in de
 * inhoudsopgave. Die onderdelen blijven zelf gewoon terug te zetten en uit te pakken zoals altijd.
 *
 * Terugzetten gaat in stappen, zodat er nooit een half teruggezette app overblijft:
 *  1. stage(): het archief helemaal lezen en controleren (inhoudsopgave, groottes, SHA-256) en alles klaarzetten in
 *     een eigen map buiten de app-gegevens (no_backup/herstel-klaar). Er verandert nog niets. Geheimen staan daar
 *     nooit leesbaar: instellingen versleuteld met de sleutel van deze installatie, kluis en handtekening met de
 *     kluissleutel.
 *  2. apply(): de huidige mappen opzij (no_backup/herstel-oud), de klaargezette op hun plek, dan de instellingen.
 *     Elke stap staat in een logboek ({@link HerstelLog}); valt de app halverwege om, dan zet recover() bij de
 *     volgende start alles terug (vóór het verplaatsen klaar was) of maakt het af (daarna, ook met de backup-sleutel).
 */
final class AppBackup {

    private AppBackup() { }

    static final long MAX_ENTRY = 8L << 30;          // 8 GB per bestand
    static final long MAX_TOTAL = 64L << 30;         // 64 GB in totaal
    static final int MAX_SMALL = 48 * 1024 * 1024;   // instellingen, geheimen, kluisindex: in het geheugen
    static final long MIN_FREE = 300L * 1024 * 1024; // zoveel ruimte moet er vrij blijven
    static final long MIN_REF = 4096;                // kleinere bestanden: gewoon opnemen (verwijzen loont niet)

    /** Hoeveel de appgegevens zelf in de laatste backup innamen (zonder verwijzingen), voor de uitleg bij opruimen. */
    static volatile long lastBytes = 0;

    // ===================================================================================================
    // Maken (in de versleutelde Alles back-uppen)
    // ===================================================================================================

    /** Schrijft alles in het lopende versleutelde archief. Geeft het aantal bestanden; -1 = geen versleuteld archief. */
    static int export(Context c) throws Exception {
        Secure.Zip zip = Secure.CAPTURE.get();
        if (zip == null) return -1; // nooit leesbaar naar de backup-map
        lastBytes = 0;
        AppData.Manifest m = new AppData.Manifest();
        m.app = Version.NAME;
        m.appCode = String.valueOf(Version.CODE);
        m.time = System.currentTimeMillis();

        // Instellingen (en de waarden die met de sleutel van de telefoon versleuteld zijn, apart en ontsleuteld)
        JSONObject secrets = new JSONObject();
        JSONObject prefs = prefsSnapshot(c, secrets, m);
        putBytes(zip, m, AppData.PREFS, prefs.toString().getBytes(StandardCharsets.UTF_8));
        putBytes(zip, m, AppData.SECRETS, secrets.toString().getBytes(StandardCharsets.UTF_8));

        // Kluis: ontsleuteld (de sleutel van de kluis blijft op de telefoon); staat het document al in Kluis/: verwijzen
        exportKluis(c, zip, m);
        // PDF-handtekening (ook met de kluissleutel versleuteld)
        try {
            JSONObject sig = PdfSignature.load(c);
            if (sig != null) putBytes(zip, m, AppData.SIGNATURE, sig.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { m.skipped.add(new String[]{"handtekening", "niet te lezen"}); }

        // Bestanden
        File root = c.getFilesDir();
        File[] tops = root.listFiles();
        Walk w = new Walk(c, zip, m, root.getCanonicalFile(), AllBackup.sleepAudio(c));
        if (tops != null) {
            java.util.Arrays.sort(tops);
            for (File t : tops) {
                String rule = AppData.fileRule(t.getName());
                if (rule != null) { m.skipped.add(new String[]{t.getName(), rule}); continue; }
                walk(w, t, t.getName(), 0);
            }
        }
        if (w.sleepAudioSkipped > 0)
            m.skipped.add(new String[]{"sleep-geluid", w.sleepAudioSkipped + " stukjes geluid van Slaappraat: Slaappraat met geluid staat uit"});

        // Meldingsgeschiedenis (rij voor rij): alleen als je die hebt aangevinkt (zoals bij Alles back-uppen zelf)
        stopCheck();
        if (AllBackup.enabledPart(c, "notifications")) putHistory(c, zip, m);
        else m.skipped.add(new String[]{"meldingen", "Meldingsgeschiedenis staat uit bij Wat gaat er mee"});

        // Welke modellen er waren (worden opnieuw gedownload) en welke toestemmingen (voor het lijstje achteraf)
        putBytes(zip, m, AppData.MODELS, Herstel.modelsJson(c).toString().getBytes(StandardCharsets.UTF_8));
        putBytes(zip, m, AppData.STATE, Herstel.stateJson(c).toString().getBytes(StandardCharsets.UTF_8));

        // Inhoudsopgave als laatste
        try (OutputStream o = zip.stream(AppData.DIR, "inhoud.txt")) { o.write(AppData.build(m).getBytes(StandardCharsets.UTF_8)); }
        lastBytes = m.bytes();
        return m.entries.size() + m.refs.size();
    }

    /** Android stopt de nachtelijke taak: tussen twee bestanden netjes stoppen (het archief wordt dan …_deels.rtb). */
    static void stopCheck() throws Exception { if (AllBackup.stop) throw new Exception(AllBackup.STOPPED); }

    /** Tijdelijke ontsleutelde kluisbestanden in de cache van een afgebroken backup: weg. */
    static void clearTemp(Context c) {
        File[] fs = c.getCacheDir().listFiles();
        if (fs != null) for (File f : fs) if (f.getName().startsWith("kluis-") && f.getName().endsWith(".tmp")) f.delete();
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
            if ("vpn".equals(f) && o.has("tunnels")) o.put("tunnels", typed(vpnOut(o.getJSONObject("tunnels").optString("v", "[]"), secrets, m)));
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
    static String vpnOut(String tunnels, JSONObject secrets, AppData.Manifest m) throws Exception {
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

    /** Wat walk() nodig heeft. */
    static final class Walk {
        final Context c; final Secure.Zip zip; final AppData.Manifest m; final File root; final boolean sleepAudio;
        int sleepAudioSkipped = 0;
        Walk(Context c, Secure.Zip zip, AppData.Manifest m, File root, boolean sleepAudio) { this.c = c; this.zip = zip; this.m = m; this.root = root; this.sleepAudio = sleepAudio; }
    }

    static void walk(Walk w, File f, String rel, int depth) throws Exception {
        stopCheck();
        AppData.Manifest m = w.m;
        if (depth > AppData.MAX_DEPTH) { m.skipped.add(new String[]{rel, "te diep"}); return; }
        // Geen snelkoppelingen naar buiten de app-map volgen
        File cf = f.getCanonicalFile();
        if (!cf.getPath().startsWith(w.root.getPath() + File.separator)) { m.skipped.add(new String[]{rel, "buiten de app-map"}); return; }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids == null) return;
            java.util.Arrays.sort(kids);
            for (File k : kids) {
                String r = rel + "/" + k.getName();
                String rule = AppData.fileRule(r);
                if (rule != null) { if (!"tijdelijk bestand".equals(rule)) m.skipped.add(new String[]{r, rule}); continue; }
                walk(w, k, r, depth + 1);
            }
            return;
        }
        if (!f.isFile() || AppData.fileRule(rel) != null) return;
        // Slaappraat met geluid staat uit: dan ook hier zonder geluid (zoals bij het onderdeel Slaappraat)
        if (!w.sleepAudio && rel.startsWith("sleep/") && AppData.audio(rel)) { w.sleepAudioSkipped++; return; }
        String path = AppData.FILES + "/" + rel;
        long mtime = f.lastModified();
        if (ref(w.zip, m, f, path, mtime)) return;
        int slash = path.lastIndexOf('/');
        OutputStream raw = null;
        String written = null;
        try (InputStream in = new FileInputStream(f)) {
            raw = w.zip.stream(path.substring(0, slash), path.substring(slash + 1), AppData.compressed(rel) ? 1 : 6);
            written = w.zip.lastPath;
            AppData.DigestOut d = new AppData.DigestOut(raw);
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) d.write(b, 0, n);
            d.close();
            raw = null;
            if (!path.equals(written)) { m.broken.add(written); m.skipped.add(new String[]{rel, "dubbele naam"}); return; }
            m.entries.add(new AppData.Entry(path, d.n, d.sha(), mtime));
        } catch (java.io.FileNotFoundException e) {
            if (raw != null) { try { raw.close(); } catch (Exception ignored) { } if (written != null) m.broken.add(written); }
            // intussen weggehaald: geen fout
        } catch (IOException e) {
            if (raw != null) try { raw.close(); } catch (Exception ignored) { }
            if (written == null) throw e; // het archief zelf: dan ook niet verder
            m.broken.add(written);
            w.zip.bad.add(written);
            m.skipped.add(new String[]{rel, "niet te lezen"});
        }
    }

    /**
     * Staat precies deze inhoud al bij een ander onderdeel in dit archief (bijv. Slaappraat/ of Kluis/)? Dan alleen een
     * verwijzing in de inhoudsopgave. Alleen bij een gelijke grootte wordt het bestand eerst gelezen voor de SHA-256.
     */
    static boolean ref(Secure.Zip zip, AppData.Manifest m, File f, String target, long mtime) {
        try {
            long size = f.length();
            if (size < MIN_REF || !zip.hasSize(size)) return false;
            java.security.MessageDigest md = AppData.sha256();
            long n = 0;
            try (InputStream in = new FileInputStream(f)) {
                byte[] b = new byte[1 << 16];
                int r;
                while ((r = in.read(b)) > 0) { md.update(b, 0, r); n += r; }
            }
            if (n != size) return false;
            AppData.Entry e = zip.same(size, AppData.hex(md.digest()));
            if (e == null) return false;
            m.refs.add(new AppData.Ref(e.path, target, e.size, e.sha, mtime));
            return true;
        } catch (Exception e) { return false; }
    }

    static void putBytes(Secure.Zip zip, AppData.Manifest m, String path, byte[] b) throws Exception {
        int slash = path.lastIndexOf('/');
        try (AppData.DigestOut d = new AppData.DigestOut(zip.stream(path.substring(0, slash), path.substring(slash + 1)))) {
            d.write(b, 0, b.length);
            d.flush();
            if (!path.equals(zip.lastPath)) throw new IOException("Dubbele naam in het archief");
            d.close();
            m.entries.add(new AppData.Entry(path, d.n, d.sha()));
        }
    }

    static void putHistory(Context c, Secure.Zip zip, AppData.Manifest m) throws Exception {
        try (AppData.DigestOut d = new AppData.DigestOut(zip.stream(AppData.DIR, "meldingen.jsonl"))) {
            Writer w = new java.io.BufferedWriter(new OutputStreamWriter(d, StandardCharsets.UTF_8), 1 << 16);
            boolean ok = true;
            try { NotificationHistory.dumpRows(c, w); }
            catch (Exception e) { ok = false; }
            w.flush();
            d.close();
            // Niet te lezen: dan als onvolledig (bij terugzetten blijft de geschiedenis die er staat)
            if (ok) m.entries.add(new AppData.Entry(AppData.HISTORY, d.n, d.sha()));
            else { m.broken.add(AppData.HISTORY); m.skipped.add(new String[]{"meldingen", "Meldingsgeschiedenis niet te lezen"}); }
        }
    }

    static void exportKluis(Context c, Secure.Zip zip, AppData.Manifest m) throws Exception {
        JSONArray l;
        try { l = Kluis.list(c); }
        catch (Exception e) { m.skipped.add(new String[]{"kluis", "kluis niet te lezen"}); return; }
        if (l.length() == 0) return;
        JSONArray ok = new JSONArray();
        for (int i = 0; i < l.length(); i++) {
            stopCheck();
            JSONObject d = l.getJSONObject(i);
            String id = d.optString("id");
            if (!id.matches("[a-z0-9]{6,24}")) continue;
            File f = new File(Kluis.dir(c), id + ".bin");
            File tmp = new File(c.getCacheDir(), "kluis-app.tmp");
            String written = null;
            String path = AppData.KLUIS_DIR + "/" + id;
            // Eerst helemaal ontsleutelen en controleren, pas dan in het archief (nooit een half document)
            try {
                try (InputStream in = new java.io.BufferedInputStream(new FileInputStream(f), 1 << 16); OutputStream o = new FileOutputStream(tmp)) { Kluis.decrypt(in, o); }
                if (!ref(zip, m, tmp, path, d.optLong("t"))) {
                    try (InputStream in = new FileInputStream(tmp); AppData.DigestOut o = new AppData.DigestOut(zip.stream(AppData.KLUIS_DIR, id, AppData.compressed(d.optString("name")) ? 1 : 6))) {
                        written = zip.lastPath;
                        byte[] b = new byte[1 << 16]; int r; while ((r = in.read(b)) > 0) o.write(b, 0, r);
                        o.close();
                        m.entries.add(new AppData.Entry(path, o.n, o.sha()));
                        written = null;
                    }
                }
                ok.put(d);
            } catch (Exception e) {
                if (written != null) { m.broken.add(written); zip.bad.add(written); }
                if (AllBackup.stop) throw e;
                m.skipped.add(new String[]{"kluis:" + id, "kluisdocument niet te lezen"});
                App.log(c, "BACKUP", "appgegevens kluis " + id + ": " + e.getClass().getSimpleName());
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

    static final int KLUIS_REPLACE = 0, KLUIS_KEEP = 1, KLUIS_MERGE = 2;

    /** Klaargezet en gecontroleerd (tussen stage() en apply()). */
    static final class Staged {
        AppData.Manifest manifest;
        Vault.Key key;
        String name;
        int files, kluis, broken;
        boolean history, signature;
        int kluisMode = KLUIS_REPLACE;
        /** Wat niet in deze backup zit (of niet af kwam): voor de samenvatting. */
        final List<String> missing = new ArrayList<>();
    }

    static volatile Staged staged;
    /** Loopt er nu klaarzetten of terugzetten (dan niet tussendoor "herstellen na omvallen")? */
    static volatile boolean working = false;

    /** Telt wat er gelezen is (voor de voortgang). */
    static final class CountIn extends FilterInputStream {
        long n;
        CountIn(InputStream in) { super(in); }
        @Override public int read() throws IOException { int r = super.read(); if (r >= 0) n++; return r; }
        @Override public int read(byte[] b, int o, int l) throws IOException { int r = in.read(b, o, l); if (r > 0) n += r; return r; }
    }

    /** Voortgang; stap 1 = de hele backup lezen, stap 2 = verwijzingen ophalen (kluis, Slaappraat) uit het begin. */
    interface Progress { void at(long done, long total, int step); }

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

    static synchronized void cancel(Context c) {
        Staged s = staged;
        staged = null;
        if (s != null && s.key != null) java.util.Arrays.fill(s.key.key, (byte) 0);
        if (!journal(c).exists()) wipe(stageDir(c));
    }

    /** Toestand tijdens het klaarzetten. */
    static final class Ctx {
        final Context c; final File st, stFiles, stKluis; final String stFilesPath; final Staged s;
        long all = 0, lastFree = 0;
        final Map<String, File> stagedFiles = new HashMap<>();   // naam in het archief → klaargezet bestand
        final Map<String, String> kinds = new HashMap<>();       // naam → "f" of "kluis"
        byte[] prefs, secrets, kluisIndex, signature, models, state;
        Ctx(Context c, File st, Staged s) throws IOException {
            this.c = c; this.st = st; this.s = s;
            stFiles = new File(st, "files"); stKluis = new File(st, "kluis");
            stFiles.mkdirs(); stKluis.mkdirs();
            stFilesPath = stFiles.getCanonicalPath() + File.separator;
        }
        void count(long n) throws Exception {
            all += n;
            if (all > MAX_TOTAL) throw new Exception("De backup is te groot");
            if (all - lastFree > 32L * 1024 * 1024) { lastFree = all; checkFree(c); }
        }
        void wipeSecrets() {
            for (byte[] b : new byte[][]{prefs, secrets, kluisIndex, signature}) if (b != null) java.util.Arrays.fill(b, (byte) 0);
        }
    }

    /** Eén bestand uit het archief klaarzetten als target t (en meetellen). Geeft {grootte, sha}. */
    static AppData.Entry stageEntry(Ctx x, InputStream z, String name, String t, byte[] buf) throws Exception {
        long en;
        String esha;
        if (t.startsWith("f:")) {
            File out = new File(x.stFiles, t.substring(2));
            if (!out.getCanonicalPath().startsWith(x.stFilesPath)) throw new Exception("Onveilige naam in de backup: " + AppData.cut(name));
            File dir = out.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new Exception("Klaarzetten lukt niet");
            try (AppData.DigestOut d = new AppData.DigestOut(new java.io.BufferedOutputStream(new FileOutputStream(out), 1 << 16))) {
                int r;
                while ((r = z.read(buf)) > 0) {
                    d.write(buf, 0, r);
                    if (d.n > MAX_ENTRY) throw new Exception("Bestand in de backup is te groot: " + AppData.cut(name));
                    x.count(r);
                }
                d.flush();
                en = d.n; esha = d.sha();
            }
            x.stagedFiles.put(name, out);
            x.kinds.put(name, "f");
            x.s.files++;
        } else if (t.startsWith("kluis:")) {
            // Meteen weer versleutelen met de kluissleutel van deze installatie: nooit ontsleuteld op de telefoon
            checkFree(x.c);
            File out = new File(x.stKluis, t.substring(6) + ".bin");
            DigestIn din = new DigestIn(z);
            try (OutputStream o = new java.io.BufferedOutputStream(new FileOutputStream(out), 1 << 16)) { Kluis.encrypt(din, o); }
            drain(din, buf);
            en = din.n; esha = AppData.hex(din.md.digest());
            x.count(en);
            x.stagedFiles.put(name, out);
            x.kinds.put(name, "kluis");
            x.s.kluis++;
        } else if ("history".equals(t)) {
            checkFree(x.c);
            try (AppData.DigestOut d = new AppData.DigestOut(new java.io.BufferedOutputStream(new FileOutputStream(new File(x.st, "meldingen.jsonl")), 1 << 16))) {
                int r;
                while ((r = z.read(buf)) > 0) { d.write(buf, 0, r); if (d.n > 512L * 1024 * 1024) throw new Exception("Meldingsgeschiedenis is te groot"); x.count(r); }
                d.flush();
                en = d.n; esha = d.sha();
            }
            x.stagedFiles.put(name, new File(x.st, "meldingen.jsonl"));
            x.kinds.put(name, "history");
            x.s.history = true;
        } else {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            AppData.DigestOut d = new AppData.DigestOut(b);
            int r;
            while ((r = z.read(buf)) > 0) { d.write(buf, 0, r); if (d.n > MAX_SMALL) throw new Exception("Te groot in de backup: " + AppData.cut(name)); }
            en = d.n; esha = d.sha();
            byte[] v = b.toByteArray();
            if ("prefs".equals(t)) x.prefs = v;
            else if ("secrets".equals(t)) x.secrets = v;
            else if ("kluis-index".equals(t)) x.kluisIndex = v;
            else if ("signature".equals(t)) x.signature = v;
            else if ("models".equals(t)) x.models = v;
            else if ("state".equals(t)) x.state = v;
        }
        return new AppData.Entry(name, en, esha);
    }

    static ZipInputStream openZip(Context c, Uri u, Vault.Key k, CountIn[] counter, Vault.In[] vault) throws Exception {
        InputStream raw0 = c.getContentResolver().openInputStream(u);
        if (raw0 == null) throw new Exception("Bestand niet te openen");
        CountIn count = new CountIn(new java.io.BufferedInputStream(raw0, 1 << 16));
        try {
            Vault.In vin = new Vault.In(count, Vault.readHeader(count), k);
            counter[0] = count; vault[0] = vin;
            return new ZipInputStream(vin);
        } catch (Exception e) { try { count.close(); } catch (Exception ignored) { } throw e; }
    }

    /** Leest en controleert het hele archief en zet alles klaar. Verandert niets aan de app. */
    static synchronized Staged stage(Context c, Uri u, Vault.Key k, long total, Progress pr) throws Exception {
        working = true;
        try { return stageLocked(c, u, k, total, pr); } finally { working = false; }
    }

    private static Staged stageLocked(Context c, Uri u, Vault.Key k, long total, Progress pr) throws Exception {
        cancel(c);
        if (journal(c).exists()) throw new Exception("Er is nog een eerder terugzetten bezig; start de app opnieuw");
        File st = stageDir(c);
        wipe(st);
        if (!st.mkdirs()) throw new Exception("Klaarzetten lukt niet");
        Staged s = new Staged();
        Ctx x = new Ctx(c, st, s);
        Map<String, AppData.Entry> seen = new HashMap<>(), others = new HashMap<>();
        String manifest = null;
        boolean ok = false;
        CountIn[] counter = new CountIn[1];
        Vault.In[] vault = new Vault.In[1];
        try {
            // ---- 1. alles lezen: appgegevens klaarzetten, van de andere onderdelen alleen grootte en SHA-256 ----
            try (ZipInputStream z = openZip(c, u, k, counter, vault)) {
                ZipEntry e;
                byte[] buf = new byte[1 << 16];
                while ((e = z.getNextEntry()) != null) {
                    String name = e.getName();
                    if (pr != null) pr.at(counter[0].n, total, 1);
                    if (e.isDirectory()) { drain(z, buf); continue; }
                    if (!name.startsWith(AppData.DIR + "/")) { others.put(name, hashDrain(z, name, buf)); continue; }
                    if (name.equals(AppData.MANIFEST)) { manifest = new String(small(z, buf, 16_000_000), StandardCharsets.UTF_8); continue; }
                    if (AppData.safeRel(name) == null) throw new Exception("Onveilige naam in de backup");
                    String t = AppData.target(name);
                    if (t == null) { drain(z, buf); continue; } // van een nieuwere versie, of niet meer bekend: overslaan
                    AppData.Entry en = stageEntry(x, z, name, t, buf);
                    if (seen.containsKey(name)) throw new Exception("Dubbele naam in de backup: " + AppData.cut(name));
                    seen.put(name, en);
                }
                drain(vault[0], buf); // ook het slot van het archief: pas dan is alles echt gecontroleerd (afgekapt = fout)
            }
            if (manifest == null && !seen.isEmpty()) throw new Exception("De appgegevens in deze backup zijn onvolledig (afgebroken backup); kies een andere backup");
            if (manifest == null) throw new NoAppData();
            AppData.Manifest m = AppData.parse(manifest);
            String bad = AppData.verify(m, seen, others);
            if (bad != null) throw new Exception(bad);

            // ---- halve bestanden (bij het maken niet af gekomen) niet terugzetten en niet meetellen ----
            for (String b : m.broken) {
                File f = x.stagedFiles.remove(b);
                if (f == null) continue;
                f.delete();
                String kind = x.kinds.get(b);
                if ("f".equals(kind)) s.files--;
                else if ("kluis".equals(kind)) s.kluis--;
                else if ("history".equals(kind)) s.history = false;
                s.broken++;
            }

            // ---- 2. verwijzingen: die inhoud staat bij een ander onderdeel (vóór de appgegevens in het archief) ----
            Map<String, List<AppData.Ref>> want = new HashMap<>();
            for (AppData.Ref r : m.refs) {
                String rt = AppData.target(r.target);
                if (rt == null || !(rt.startsWith("f:") || rt.startsWith("kluis:"))) continue; // alleen bestanden en kluisdocumenten
                List<AppData.Ref> l = want.get(r.source);
                if (l == null) { l = new ArrayList<>(); want.put(r.source, l); }
                l.add(r);
            }
            if (!want.isEmpty()) {
                try (ZipInputStream z = openZip(c, u, k, counter, vault)) {
                    ZipEntry e;
                    byte[] buf = new byte[1 << 16];
                    while (!want.isEmpty() && (e = z.getNextEntry()) != null) {
                        if (pr != null) pr.at(counter[0].n, total, 2);
                        List<AppData.Ref> l = want.remove(e.getName());
                        if (l == null) { drain(z, buf); continue; }
                        AppData.Ref first = l.get(0);
                        AppData.Entry got = stageEntry(x, z, first.target, AppData.target(first.target), buf);
                        if (got.size != first.size || !got.sha.equals(first.sha)) throw new Exception("Beschadigd in de backup: " + AppData.cut(first.source));
                        File src = x.stagedFiles.get(first.target);
                        for (int i = 1; i < l.size(); i++) copyAs(x, src, l.get(i));
                    }
                }
                if (!want.isEmpty()) throw new Exception("Ontbreekt in de backup: " + AppData.cut(want.keySet().iterator().next()));
            }

            // Bestanden weer met hun oude tijd (opnames en routes staan op volgorde van tijd)
            for (AppData.Entry e2 : m.entries) setTime(x, e2.path, e2.mtime);
            for (AppData.Ref r : m.refs) setTime(x, r.target, r.mtime);

            if (x.prefs == null) throw new Exception("Instellingen ontbreken in de backup");
            // Alles wat terug gaat, nu al inlezen en controleren (pas daarna mag er iets veranderen).
            // De instellingen (met o.a. de WhatsApp- en AudD-sleutel) staan alleen versleuteld klaar.
            JSONObject ready;
            try { ready = readyPrefs(x.prefs, x.secrets); }
            catch (org.json.JSONException je) { throw new Exception("De instellingen in deze backup zijn niet te lezen"); }
            Contacts.writeText(new File(st, "instellingen-klaar.bin"), AppData.seal(Secure.deviceKey(), ready.toString()), false);
            if (x.kluisIndex != null) {
                JSONArray idx;
                try { idx = cleanKluisIndex(new String(x.kluisIndex, StandardCharsets.UTF_8), x.stKluis); }
                catch (org.json.JSONException je) { throw new Exception("De kluis in deze backup is niet te lezen"); }
                Kluis.writeEncrypted(new File(x.stKluis, "index.bin"), idx.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (x.signature != null) {
                try {
                    byte[] sig = PdfSignature.normalize(new String(x.signature, StandardCharsets.UTF_8)).toString().getBytes(StandardCharsets.UTF_8);
                    Kluis.writeEncrypted(new File(st, "handtekening.bin"), sig); // met de kluissleutel, zoals PdfSignature zelf
                    s.signature = true;
                } catch (Exception ex) { App.log(c, "HERSTEL", "handtekening overgeslagen"); }
            }
            if (x.models != null) Contacts.writeText(new File(st, "modellen.json"), new String(x.models, StandardCharsets.UTF_8), false);
            if (x.state != null) Contacts.writeText(new File(st, "toestand.json"), new String(x.state, StandardCharsets.UTF_8), false);

            // Kluis: niet te lezen bij het maken → de huidige houden; deels → samenvoegen met de huidige
            boolean kluisAll = false, kluisSome = hasKluisBroken(m);
            for (String[] sk : m.skipped) {
                if ("kluis".equals(sk[0])) kluisAll = true;
                if (sk[0].startsWith("kluis:")) kluisSome = true;
            }
            s.kluisMode = kluisAll ? KLUIS_KEEP : kluisSome ? KLUIS_MERGE : KLUIS_REPLACE;
            missing(s, m);
            s.manifest = m;
            s.key = k;
            if (pr != null) pr.at(total, total, 0);
            ok = true;
            staged = s;
            return s;
        } finally {
            x.wipeSecrets();
            if (!ok) wipe(st);
        }
    }

    static boolean hasKluisBroken(AppData.Manifest m) {
        for (String b : m.broken) if (b.startsWith(AppData.KLUIS_DIR + "/")) return true;
        return false;
    }

    /** Wat er niet (helemaal) in de backup zit, in gewone woorden. */
    static void missing(Staged s, AppData.Manifest m) {
        Set<String> seen = new HashSet<>();
        for (String[] sk : m.skipped) {
            String k = sk[0], text;
            if ("kluis".equals(k)) text = "de kluis (niet te lezen bij het maken): je huidige kluis blijft";
            else if (k.startsWith("kluis:")) text = "een deel van de kluis (niet te lezen bij het maken): je huidige documenten blijven erbij";
            else if ("meldingen".equals(k)) text = "de meldingsgeschiedenis (" + sk[1].toLowerCase(java.util.Locale.ROOT) + "): de huidige blijft";
            else if ("sleep-geluid".equals(k)) text = "het geluid van Slaappraat (Slaappraat met geluid stond uit)";
            else if ("spotify".equals(k)) text = "de Spotify-koppeling (opnieuw koppelen)";
            else if (k.startsWith("vpn:")) text = "de sleutel van een VPN-tunnel (opnieuw instellen)";
            else if ("handtekening".equals(k)) text = "de bewaarde PDF-handtekening";
            else if ("niet te lezen".equals(sk[1]) || "dubbele naam".equals(sk[1])) text = "losse bestanden die bij het maken niet te lezen waren";
            else continue; // modellen, zoekindex e.d.: bewust, staat in de uitleg
            if (seen.add(text)) s.missing.add(text);
        }
        if (s.broken > 0 && seen.add("broken")) s.missing.add(s.broken + (s.broken == 1 ? " bestand dat" : " bestanden die") + " bij het maken niet af kwam");
    }

    static void setTime(Ctx x, String path, long mtime) {
        if (mtime <= 0) return;
        File f = x.stagedFiles.get(path);
        String t = AppData.target(path);
        if (f != null && t != null && t.startsWith("f:")) f.setLastModified(mtime);
    }

    /** Dezelfde inhoud nog een keer (twee bestanden in de app met precies dezelfde inhoud). */
    static void copyAs(Ctx x, File src, AppData.Ref r) throws Exception {
        String t = AppData.target(r.target);
        if (src == null || t == null) return;
        File out;
        if (t.startsWith("f:")) {
            out = new File(x.stFiles, t.substring(2));
            if (!out.getCanonicalPath().startsWith(x.stFilesPath)) throw new Exception("Onveilige naam in de backup");
            x.s.files++;
        } else if (t.startsWith("kluis:")) { out = new File(x.stKluis, t.substring(6) + ".bin"); x.s.kluis++; }
        else return;
        File dir = out.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new Exception("Klaarzetten lukt niet");
        try (InputStream in = new FileInputStream(src); OutputStream o = new FileOutputStream(out)) {
            byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) > 0) { o.write(b, 0, n); x.count(n); }
        }
        x.stagedFiles.put(r.target, out);
    }

    static AppData.Entry hashDrain(InputStream in, String name, byte[] buf) throws IOException {
        java.security.MessageDigest md = AppData.sha256();
        long n = 0;
        int r;
        while ((r = in.read(buf)) > 0) { md.update(buf, 0, r); n += r; }
        return new AppData.Entry(name, n, AppData.hex(md.digest()));
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
    static JSONObject readyPrefs(byte[] prefs, byte[] secretBytes) throws Exception {
        JSONObject root = new JSONObject(new String(prefs, StandardCharsets.UTF_8));
        if (!(AppData.FORMAT + "-prefs").equals(root.optString("format"))) throw new Exception("De instellingen in deze backup zijn niet te lezen");
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

    /** De backup-sleutel ingepakt met de sleutel van deze installatie, zodat ook afmaken na omvallen hem nog heeft. */
    static void writeKeyCopy(File st, Vault.Key k) throws Exception {
        Contacts.writeText(new File(st, "sleutel.txt"), AppData.seal(Secure.deviceKey(), AppData.b64(k.key)) + "\n" + AppData.b64(k.salt) + "\n" + k.iter + "\n" + k.kdf, false);
    }

    static Vault.Key readKeyCopy(File st) {
        try {
            File f = new File(st, "sleutel.txt");
            if (!f.isFile()) return null;
            String[] p = Contacts.readText(f, false).split("\n");
            return new Vault.Key(AppData.unb64(AppData.open(Secure.deviceKey(), p[0])), AppData.unb64(p[1]), Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim()));
        } catch (Exception e) { return null; }
    }

    /**
     * Zet het klaargezette terug: alles vervangen. Gooit als er niets veranderd is (fout vóór het verplaatsen).
     * Geeft "" als alles klaar is, of een melding als het na het verplaatsen misging: dan maakt recover() het bij de
     * volgende start af (de app moet dus opnieuw starten).
     */
    static synchronized String apply(Context c) throws Exception {
        working = true;
        try { return applyLocked(c); } finally { working = false; }
    }

    private static String applyLocked(Context c) throws Exception {
        Staged s = staged;
        if (s == null || !stageDir(c).isDirectory()) throw new Exception("Open de backup opnieuw");
        File st = stageDir(c), old = oldDir(c), files = c.getFilesDir(), jf = journal(c);
        wipe(old);
        if (!old.mkdirs()) throw new Exception("Terugzetten lukt niet (geen ruimte?)");
        writeKeyCopy(st, s.key);
        try {
            HerstelLog.line(jf, "begin");
            // 0. Wat bewust niet in de backup zit maar bij deze installatie hoort (lopende route, gedownloade
            //    afleveringen, zoekindex): mee naar de klaargezette mappen, zodat het blijft
            for (String p : AppData.PATH_SKIP.keySet()) {
                File cur = new File(files, p), dst = new File(st, "files/" + p);
                if (cur.isFile() && !dst.exists()) HerstelLog.move(jf, cur, dst);
            }
            // 1. Mappen en bestanden: de huidige opzij, de klaargezette op hun plek
            for (String r : AppData.FILE_ROOTS) {
                File cur = new File(files, r);
                if (cur.exists()) HerstelLog.move(jf, cur, new File(old, "files/" + r));
                File in = new File(st, "files/" + r);
                if (in.exists()) HerstelLog.move(jf, in, cur);
            }
            // 2. Kluis: vervangen, houden (niet te lezen bij het maken) of samenvoegen (deels)
            File kl = new File(files, "kluis"), stKl = new File(st, "kluis");
            if (s.kluisMode == KLUIS_MERGE) mergeKluis(c, jf, kl, stKl);
            if (s.kluisMode != KLUIS_KEEP) {
                if (kl.exists()) HerstelLog.move(jf, kl, new File(old, "files/kluis"));
                if (stKl.exists()) HerstelLog.move(jf, stKl, kl);
            }
            HerstelLog.line(jf, "files");
        } catch (Exception e) {
            String log = readOr(jf);
            HerstelLog.rollback(log);
            jf.delete();
            wipe(old);
            cancel(c);
            App.log(c, "HERSTEL", "terugzetten teruggedraaid: " + AppData.safeMsg(e, "fout"));
            throw new Exception("Terugzetten mislukt; er is niets veranderd. Open de backup opnieuw.");
        }
        try { finish(c, s.key); return ""; }
        catch (Exception e) {
            App.log(c, "HERSTEL", "afmaken: " + AppData.safeMsg(e, "fout"));
            return "Bijna klaar: de app maakt het terugzetten af bij het opnieuw starten";
        }
    }

    /** Huidige kluisdocumenten die niet in de backup zitten, erbij (zelfde kluissleutel, dus gewoon verplaatsen). */
    static void mergeKluis(Context c, File jf, File kl, File stKl) throws Exception {
        JSONArray cur, back;
        try { cur = Kluis.list(c); } catch (Exception e) { return; } // huidige kluis niet te lezen: dan alleen wat terugkomt
        try { back = readKluisIndex(new File(stKl, "index.bin")); } catch (Exception e) { back = new JSONArray(); }
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < back.length(); i++) ids.add(back.getJSONObject(i).optString("id"));
        for (int i = 0; i < cur.length(); i++) {
            JSONObject d = cur.getJSONObject(i);
            String id = d.optString("id");
            if (!id.matches("[a-z0-9]{6,24}") || ids.contains(id)) continue;
            File bin = new File(kl, id + ".bin");
            if (!bin.isFile()) continue;
            HerstelLog.move(jf, bin, new File(stKl, id + ".bin"));
            back.put(d);
            ids.add(id);
        }
        Kluis.writeEncrypted(new File(stKl, "index.bin"), back.toString().getBytes(StandardCharsets.UTF_8));
    }

    static JSONArray readKluisIndex(File f) throws Exception {
        if (!f.isFile()) return new JSONArray();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (InputStream in = new java.io.BufferedInputStream(new FileInputStream(f))) { Kluis.decrypt(in, b); }
        return new JSONArray(new String(b.toByteArray(), StandardCharsets.UTF_8));
    }

    /** Na het verplaatsen: instellingen, geschiedenis, handtekening, en de sleutel. Ook bij afmaken na omvallen. */
    static void finish(Context c, Vault.Key key) throws Exception {
        File st = stageDir(c), jf = journal(c);
        File rp = new File(st, "instellingen-klaar.bin");
        if (rp.isFile()) {
            JSONObject ready;
            try { ready = new JSONObject(AppData.open(Secure.deviceKey(), Contacts.readText(rp, false).trim())); }
            catch (Exception e) { throw new Exception("De klaargezette instellingen zijn niet te lezen"); }
            restoredFlags(c, ready.optBoolean("_bio"));
            writePrefs(c, ready);
            HerstelLog.line(jf, "prefs");
            rp.delete();
        }
        File h = new File(st, "meldingen.jsonl");
        if (h.isFile()) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(h), StandardCharsets.UTF_8))) { NotificationHistory.replaceRows(c, r); }
            catch (Exception e) { App.log(c, "HERSTEL", "meldingsgeschiedenis: " + AppData.safeMsg(e, "niet te lezen")); }
        }
        // Handtekening: staat al met de kluissleutel versleuteld klaar, precies zoals PdfSignature hem bewaart
        File sig = new File(st, "handtekening.bin");
        if (sig.isFile()) {
            File dst = PdfSignature.file(c).getBaseFile();
            new File(dst.getPath() + ".bak").delete();
            dst.delete();
            if (!sig.renameTo(dst)) App.log(c, "HERSTEL", "handtekening terugzetten lukt niet");
        }
        if (key == null) key = readKeyCopy(st);
        boolean keySet = false;
        if (key != null) {
            try { Secure.setKey(c, key); keySet = true; } catch (Exception e) { App.log(c, "HERSTEL", "sleutel: " + AppData.safeMsg(e, "fout")); }
        }
        Herstel.restored(c, readBio(c), keySet, readOr(new File(st, "modellen.json")), readOr(new File(st, "toestand.json")));
        HerstelLog.line(jf, "ok");
        jf.delete();
        wipe(oldDir(c));
        wipe(st);
        Staged s = staged;
        staged = null;
        if (s != null && s.key != null) java.util.Arrays.fill(s.key.key, (byte) 0);
        if (key != null) java.util.Arrays.fill(key.key, (byte) 0);
    }

    /** Of de vingerafdruk bij de app-code aan stond (voor het lijstje), ook als finish() later wordt afgemaakt. */
    static void restoredFlags(Context c, boolean bio) {
        try { Contacts.writeText(new File(stageDir(c), "vlaggen.txt"), bio ? "bio" : "", false); } catch (Exception ignored) { }
    }

    static boolean readBio(Context c) { String s = readOr(new File(stageDir(c), "vlaggen.txt")); return s != null && s.contains("bio"); }

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
                        Set<String> set = new HashSet<>();
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

    /**
     * Bij het starten van de app (na ontgrendelen): is een vorig terugzetten halverwege gestopt (app omgevallen,
     * telefoon uit)? Vóór de bestanden klaar waren: alles terug zoals het was. Daarna: afmaken met wat klaarstaat.
     * Zonder logboek: een achtergebleven klaargezette map (app gestopt tussen controleren en terugzetten) weg.
     */
    /** Snel, voor bij het openen van het scherm: alleen als er iets achtergebleven is en er nu niets loopt. */
    static void recoverIfNeeded(Context c) {
        if (working) return;
        if (!journal(c).exists() && (staged != null || !stageDir(c).exists()) && !oldDir(c).exists()) return;
        recover(c);
    }

    static synchronized void recover(Context c) {
        try {
            clearTemp(c);
            File jf = journal(c);
            String log = readOr(jf);
            switch (HerstelLog.decide(log)) {
                case HerstelLog.NOTHING:
                    if (staged == null) { wipe(stageDir(c)); wipe(oldDir(c)); }
                    return;
                case HerstelLog.CLEANUP:
                    jf.delete(); wipe(oldDir(c)); wipe(stageDir(c));
                    return;
                case HerstelLog.ROLLBACK:
                    App.log(c, "HERSTEL", "onderbroken terugzetten: teruggedraaid");
                    HerstelLog.rollback(log);
                    jf.delete(); wipe(oldDir(c)); wipe(stageDir(c)); staged = null;
                    return;
                default:
                    App.log(c, "HERSTEL", "onderbroken terugzetten: afgemaakt");
                    finish(c, null); // de sleutel komt uit de ingepakte kopie in de klaargezette map
            }
        } catch (Throwable t) { App.log(c, "HERSTEL", "herstellen na onderbreking: " + AppData.safeMsg(t, "fout")); }
    }

    // ---------- wat er al op de telefoon staat ----------

    /** Instellingen die alleen iets bevatten als je de app echt gebruikt hebt: {bestand, sleutel (null = elke)}. */
    static final String[][] MEANINGFUL = {
            {"vpn", "tunnels"}, {"spotify", "refresh"}, {"lock", "codeHash"}, {"lock", "on"}, {"radio", "favs"}, {"webstore", "all"},
            {"launcher", "cfg"}, {"auto", "rules"}, {"reminders", null}, {"mockloc", "places"}, {"car", "park"}, {"car", "log"},
            {"wabackup", "waKey"}, {"music", "token"}, {"alarm", "station"}, {"workspace", "ws"}, {"solo", "tools"},
            {"pinned_shortcuts", null}, {"notepins", null}, {"transcribe", "folder"}, {"localai", "chat"},
    };

    /**
     * Staat er al iets van jezelf in Tools op deze telefoon (dan eerst "Alles vervangen?")? Ruim gemeten: elke map met
     * gegevens, de kluis, de handtekening en instellingen die alleen bij echt gebruik iets bevatten. De backup-map telt
     * niet mee (die kies je juist om terug te zetten). Kan het niet worden nagekeken: dan "ja".
     */
    static boolean hasLocalData(Context c) {
        try {
            for (String r : AppData.FILE_ROOTS) if (hasFiles(new File(c.getFilesDir(), r), r, 0)) return true;
            File[] kl = new File(c.getFilesDir(), "kluis").listFiles();
            if (kl != null && kl.length > 0) return true;
            if (PdfSignature.file(c).getBaseFile().exists()) return true;
            for (String[] k : MEANINGFUL) {
                Map<String, ?> all = c.getSharedPreferences(k[0], Context.MODE_PRIVATE).getAll();
                if (k[1] == null ? !all.isEmpty() : meaningful(all.get(k[1]))) return true;
            }
            return false;
        } catch (Throwable t) { return true; }
    }

    static boolean meaningful(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) { String t = ((String) v).trim(); return !t.isEmpty() && !"[]".equals(t) && !"{}".equals(t) && !"null".equals(t); }
        if (v instanceof Set) return !((Set<?>) v).isEmpty();
        return true;
    }

    /** Een echt bestand (geen leeg lijstje, geen tijdelijk of bewust overgeslagen bestand) in deze map? */
    static boolean hasFiles(File f, String rel, int depth) {
        if (depth > 8 || !f.exists()) return false;
        if (f.isFile()) return f.length() > 2 && AppData.fileRule(rel) == null;
        File[] k = f.listFiles();
        if (k != null) for (File x : k) if (hasFiles(x, rel + "/" + x.getName(), depth + 1)) return true;
        return false;
    }

    /** Voor de samenvatting vóór terugzetten. */
    static JSONObject summary(Staged s) throws Exception {
        JSONArray miss = new JSONArray();
        for (String m : s.missing) miss.put(m);
        return new JSONObject().put("app", s.manifest.app).put("t", s.manifest.time).put("files", s.files).put("kluis", s.kluis)
                .put("bytes", s.manifest.bytes() + s.manifest.refBytes()).put("name", s.name == null ? "" : s.name).put("history", s.history)
                .put("missing", miss);
    }

    static String readOr(File f) { try { return f.isFile() ? Contacts.readText(f, false) : null; } catch (Exception e) { return null; } }
}
