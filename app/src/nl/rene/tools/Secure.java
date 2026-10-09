package nl.rene.tools;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Versleutelde backups, opruimen van oude backups en ruimtegebruik van de backup-map.
 *
 * Het wachtwoord zelf wordt nergens bewaard. Alleen de daaruit afgeleide sleutel staat op de telefoon,
 * zelf weer versleuteld met een sleutel uit de Android-sleutelopslag (die de telefoon nooit verlaat),
 * zodat de nachtelijke backup zonder wachtwoord kan draaien.
 */
final class Secure {

    private Secure() { }

    static final String DIR = "Versleuteld";
    static final String ALIAS = "rt-backup";

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("secure", Context.MODE_PRIVATE); }

    static boolean on(Context c) { return prefs(c).contains("wrapped"); }

    // ---------- sleutel ----------

    static SecretKey deviceKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return (SecretKey) ks.getKey(ALIAS, null);
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return g.generateKey();
    }

    static void setPassword(Context c, String pw) throws Exception {
        if (pw == null || pw.length() < 8) throw new Exception("Kies een wachtwoord van minstens 8 tekens");
        char[] p = pw.toCharArray();
        Vault.Key k;
        try { k = Vault.create(p); } finally { java.util.Arrays.fill(p, '\0'); }
        Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
        ci.init(Cipher.ENCRYPT_MODE, deviceKey());
        byte[] wrapped = ci.doFinal(k.key);
        prefs(c).edit()
                .putString("wrapped", b64(ci.getIV()) + ":" + b64(wrapped))
                .putString("salt", b64(k.salt)).putInt("iter", k.iter).putInt("kdf", k.kdf)
                .putLong("since", System.currentTimeMillis()).apply();
    }

    static void off(Context c) { prefs(c).edit().remove("wrapped").remove("salt").remove("iter").remove("kdf").remove("since").apply(); }

    /** Is de bewaarde sleutel nog bruikbaar? (Niet na terugzetten van app-gegevens op een andere telefoon.) */
    static boolean broken(Context c) {
        if (!on(c)) return false;
        try { Vault.Key k = key(c); java.util.Arrays.fill(k.key, (byte) 0); return false; } catch (Exception e) { return true; }
    }

    /** De bewaarde sleutel; fout als de sleutelopslag hem niet meer kent (bijv. na terugzetten op een andere telefoon). */
    static Vault.Key key(Context c) throws Exception {
        String w = prefs(c).getString("wrapped", null);
        if (w == null) return null;
        try {
            String[] p = w.split(":");
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.DECRYPT_MODE, deviceKey(), new GCMParameterSpec(128, unb64(p[0])));
            return new Vault.Key(ci.doFinal(unb64(p[1])), unb64(prefs(c).getString("salt", "")), prefs(c).getInt("iter", Vault.ITER), prefs(c).getInt("kdf", 1));
        } catch (Exception e) {
            throw new Exception("De versleutelsleutel is niet meer bruikbaar. Stel het wachtwoord opnieuw in (Alles back-uppen → Versleutelen).");
        }
    }

    static String b64(byte[] b) { return Base64.encodeToString(b, Base64.NO_WRAP); }
    static byte[] unb64(String s) { return Base64.decode(s, Base64.NO_WRAP); }

    // ---------- archief maken ----------

    static final ThreadLocal<Zip> CAPTURE = new ThreadLocal<>();

    /** Zip-archief dat versleuteld in de backup-map wordt geschreven. Eén bestand tegelijk open. */
    static final class Zip {
        final ZipOutputStream z;
        final Set<String> names = new HashSet<>();
        /** Bestanden die niet af kwamen (fout halverwege): die biedt terugzetten niet aan. */
        final java.util.List<String> bad = new ArrayList<>();
        String lastPath;
        final WaBackup.Dest dest;
        final Uri doc;
        final String name;
        final OutputStream raw;
        final WaBackup.DestDir dir;
        int files = 0;

        Zip(WaBackup.Dest d, WaBackup.DestDir dr, Uri u, String n, OutputStream r, Vault.Key k) throws IOException {
            dest = d; dir = dr; doc = u; name = n; raw = r;
            z = new ZipOutputStream(new Vault.Out(raw, k));
            z.setLevel(6);
        }

        Writer writer(String dir, String file) throws IOException {
            return new java.io.BufferedWriter(new OutputStreamWriter(stream(dir, file), StandardCharsets.UTF_8), 1 << 16);
        }

        /** Binair bestand in het archief (bijv. kluisdocumenten). */
        OutputStream stream(String dir, String file) throws IOException {
            String path = (dir == null || dir.isEmpty() ? "" : dir + "/") + file;
            String p = path;
            int dot = path.lastIndexOf('.');
            for (int i = 2; !names.add(p.toLowerCase(Locale.ROOT)); i++)
                p = dot > path.lastIndexOf('/') ? path.substring(0, dot) + " (" + i + ")" + path.substring(dot) : path + " (" + i + ")";
            ZipEntry e = new ZipEntry(p);
            lastPath = p;
            e.setTime(System.currentTimeMillis());
            z.putNextEntry(e);
            files++;
            return new EntryStream(z);
        }
    }

    /** Sluiten = alleen het bestand in het archief afsluiten, niet het archief zelf. */
    static final class EntryStream extends FilterOutputStream {
        private final ZipOutputStream z;
        private boolean closed = false;
        EntryStream(ZipOutputStream o) { super(o); z = o; }
        @Override public void write(byte[] b, int off, int len) throws IOException { z.write(b, off, len); }
        @Override public void close() throws IOException { if (!closed) { closed = true; flush(); z.closeEntry(); } }
    }

    static Zip begin(Context c, Vault.Key k) throws Exception {
        WaBackup.Dest dest = new WaBackup.Dest(c.getContentResolver(), WaBackup.destUri(c));
        WaBackup.DestDir dir = dest.dir(DIR, true);
        writeDecryptor(c, dest, dir);
        // Achtergebleven halve archieven van een afgebroken backup opruimen (die tellen nooit als backup).
        for (Map.Entry<String, WaBackup.Child> e : new ArrayList<>(dir.kids.entrySet())) {
            if (e.getValue().dir || !e.getKey().endsWith(".rtb.part")) continue;
            // Pas na twee dagen: een recente .part kan een compleet archief zijn waarvan alleen het hernoemen mislukte
            if (e.getValue().mod > 0 && System.currentTimeMillis() - e.getValue().mod < 2L * 86_400_000L) continue;
            try { DocumentsContract.deleteDocument(dest.cr, DocumentsContract.buildDocumentUriUsingTree(dest.tree, e.getValue().docId)); dir.kids.remove(e.getKey()); }
            catch (Exception ignored) { }
        }
        String name = "backup-" + new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(new Date()) + ".rtb";
        // Eerst onder een tijdelijke naam schrijven; pas als het archief compleet is krijgt het de echte naam.
        Uri u = DocumentsContract.createDocument(dest.cr, dir.uri, "application/octet-stream", name + ".part");
        if (u == null) throw new Exception("Archief maken lukt niet");
        OutputStream o = null;
        try {
            o = dest.cr.openOutputStream(u, "w");
            if (o == null) throw new Exception("Archief schrijven lukt niet");
            return new Zip(dest, dir, u, name, new java.io.BufferedOutputStream(o, 1 << 16), k);
        } catch (Exception e) {
            if (o != null) try { o.close(); } catch (Exception ignored) { }
            try { DocumentsContract.deleteDocument(dest.cr, u); } catch (Exception ignored) { }
            throw e;
        }
    }

    /** Slot: inhoudsopgave erbij en afsluiten. Bij een fout wordt het halve archief verwijderd. */
    /** Geeft de uiteindelijke naam van het archief. */
    static String finish(Zip zip, JSONObject res) throws Exception {
        try {
            if (!zip.bad.isEmpty()) try (Writer w = zip.writer("", "onvolledig.txt")) { for (String b : zip.bad) w.write(b + "\n"); }
            try (Writer w = zip.writer("", "inhoud.txt")) {
                w.write("Versleutelde backup van Rene's Tools\r\nGemaakt: " + new SimpleDateFormat("d MMM yyyy HH:mm", new Locale("nl", "NL")).format(new Date())
                        + "\r\nVersie app: " + Version.NAME + "\r\n\r\n");
                for (String p : AllBackup.PARTS) {
                    JSONObject r = res.optJSONObject(p);
                    if (r != null) w.write(p + ": " + (r.optBoolean("ok") ? r.optInt("count") + " stuks" : "mislukt - " + r.optString("msg")) + "\r\n");
                }
            }
            zip.z.close();
        } catch (Exception e) {
            abort(zip);
            throw e;
        }
        // Compleet. Bestaat er al een archief met deze naam (zelfde minuut), dan een eigen naam met seconden:
        // nooit een bestaand, compleet archief weggooien.
        String name = zip.name;
        if (zip.dir.kids.containsKey(name))
            name = "backup-" + new SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(new Date()) + ".rtb";
        Uri done = null;
        try { done = DocumentsContract.renameDocument(zip.dest.cr, zip.doc, name); } catch (Exception ignored) { }
        if (done == null) {
            // Hernoemen kan hier niet: kopiëren naar een bestand met de echte naam, daarna het tijdelijke weg
            Uri u = DocumentsContract.createDocument(zip.dest.cr, zip.dir.uri, "application/octet-stream", name);
            if (u == null) throw new Exception("Archief hernoemen lukt niet (het staat als " + zip.name + ".part)");
            try (InputStream in = zip.dest.cr.openInputStream(zip.doc); OutputStream o = zip.dest.cr.openOutputStream(u, "w")) {
                if (in == null || o == null) throw new Exception("kopiëren lukt niet");
                byte[] b = new byte[1 << 16]; int r; while ((r = in.read(b)) > 0) o.write(b, 0, r);
            } catch (Exception e) {
                try { DocumentsContract.deleteDocument(zip.dest.cr, u); } catch (Exception ignored) { }
                throw new Exception("Archief opslaan lukt niet (het staat als " + zip.name + ".part): " + e.getMessage());
            }
            try { DocumentsContract.deleteDocument(zip.dest.cr, zip.doc); } catch (Exception ignored) { }
        }
        return name;
    }

    /** Afbreken: zonder slotblok sluiten (dus nooit een geldig archief) en het tijdelijke bestand weghalen. */
    static void abort(Zip zip) {
        try { zip.raw.close(); } catch (Exception ignored) { }
        try { DocumentsContract.deleteDocument(zip.dest.cr, zip.doc); } catch (Exception ignored) { }
    }

    /** Zet ontsleutelen.html naast de archieven (voor op de computer, zonder app). */
    static void writeDecryptor(Context c, WaBackup.Dest dest, WaBackup.DestDir dir) {
        String n = "ontsleutelen.html";
        int have = prefs(c).getInt("decryptor", 0);
        if (dir.kids.containsKey(n) && have == Version.CODE) return;
        try (InputStream in = c.getAssets().open(n); Sms.Out w = Sms.open(dest, dir, n, "text/html")) {
            w.write(new String(SelfTest.readAll(in), StandardCharsets.UTF_8));
            w.done(); // pas nu vervangt het nieuwe bestand het oude
        } catch (Exception e) { App.log(c, "SECURE", "ontsleutelen.html: " + e.getMessage()); return; }
        prefs(c).edit().putInt("decryptor", Version.CODE).apply(); // pas na gelukt bewaren
    }

    // ---------- opruimen ----------

    static final String[] ROTATE_DIRS = {DIR, Sms.DIR, Calls.DIR, Contacts.DIR, NotificationHistory.DIR};

    /** Verwijdert oude backups volgens Rotate; geeft {count, bytes}. dry = alleen tellen. */
    static JSONObject rotate(Context c, boolean dry) throws Exception {
        Uri tree = WaBackup.destUri(c);
        if (tree == null) throw new Exception("Kies eerst een backup-map");
        WaBackup.Dest dest = new WaBackup.Dest(c.getContentResolver(), tree);
        int n = 0;
        long bytes = 0;
        for (String d : ROTATE_DIRS) {
            WaBackup.DestDir dir = dest.dir(d, false);
            if (dir == null) continue;
            List<String> files = new ArrayList<>();
            for (Map.Entry<String, WaBackup.Child> e : dir.kids.entrySet()) if (!e.getValue().dir) files.add(e.getKey());
            for (String name : Rotate.toDelete(files, System.currentTimeMillis())) {
                WaBackup.Child ch = dir.kids.get(name);
                if (!dry) {
                    try { DocumentsContract.deleteDocument(dest.cr, DocumentsContract.buildDocumentUriUsingTree(dest.tree, ch.docId)); }
                    catch (Exception e) { App.log(c, "SECURE", "opruimen " + name + ": " + e.getMessage()); continue; }
                }
                n++;
                bytes += Math.max(0, ch.size);
            }
        }
        return new JSONObject().put("count", n).put("bytes", bytes);
    }

    // ---------- ruimtegebruik ----------

    /** Grootte per map in de backup-map (met submappen). */
    static JSONObject space(Context c) throws Exception {
        Uri tree = WaBackup.destUri(c);
        if (tree == null) throw new Exception("Kies eerst een backup-map");
        WaBackup.Dest dest = new WaBackup.Dest(c.getContentResolver(), tree);
        WaBackup.DestDir root = dest.root();
        JSONArray dirs = new JSONArray();
        long total = 0, loose = 0;
        int looseN = 0;
        for (Map.Entry<String, WaBackup.Child> e : root.kids.entrySet()) {
            WaBackup.Child ch = e.getValue();
            if (!ch.dir) { loose += Math.max(0, ch.size); looseN++; continue; }
            long[] s = new long[2];
            sum(dest, ch.docId, s, 0);
            dirs.put(new JSONObject().put("name", e.getKey()).put("bytes", s[0]).put("files", s[1]));
            total += s[0];
        }
        if (looseN > 0) dirs.put(new JSONObject().put("name", "(losse bestanden)").put("bytes", loose).put("files", looseN));
        total += loose;
        JSONObject o = new JSONObject().put("dirs", dirs).put("total", total).put("t", System.currentTimeMillis());
        prefs(c).edit().putString("space", o.toString()).apply();
        return o;
    }

    static void sum(WaBackup.Dest dest, String docId, long[] s, int depth) {
        if (depth > 12) return;
        Uri q = DocumentsContract.buildChildDocumentsUriUsingTree(dest.tree, docId);
        List<String> sub = new ArrayList<>();
        try (Cursor cur = dest.cr.query(q, new String[]{Document.COLUMN_DOCUMENT_ID, Document.COLUMN_SIZE, Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (cur == null) return;
            while (cur.moveToNext()) {
                if (Document.MIME_TYPE_DIR.equals(cur.getString(2))) sub.add(cur.getString(0));
                else { s[0] += cur.isNull(1) ? 0 : Math.max(0, cur.getLong(1)); s[1]++; }
            }
        } catch (Exception ignored) { }
        for (String d : sub) sum(dest, d, s, depth + 1);
    }

    // ---------- archief openen (terugzetten) ----------

    static volatile Uri openUri;
    static volatile byte[] openHeader;
    static volatile Vault.Key openKey;
    static volatile String openName = "";

    static synchronized void forget() {
        Vault.Key k = openKey;
        if (k != null) java.util.Arrays.fill(k.key, (byte) 0);
        openUri = null; openHeader = null; openKey = null;
    }

    static InputStream stream(Context c) throws Exception {
        Uri u = openUri;
        Vault.Key k = openKey;
        if (u == null || k == null) throw new Exception("Open de backup opnieuw");
        InputStream raw = c.getContentResolver().openInputStream(u);
        if (raw == null) throw new Exception("Bestand niet te openen");
        raw = new java.io.BufferedInputStream(raw, 1 << 16);
        byte[] h = Vault.readHeader(raw);
        return new Vault.In(raw, h, k);
    }

    /** Opent een gekozen .rtb: met de bewaarde sleutel als die past, anders met het opgegeven wachtwoord. */
    static synchronized JSONObject open(Context c, Uri u, String pw) throws Exception {
        if (u == null) throw new Exception("Kies eerst een backupbestand");
        byte[] h;
        try (InputStream in = c.getContentResolver().openInputStream(u)) {
            if (in == null) throw new Exception("Bestand niet te openen");
            h = Vault.readHeader(in);
        }
        Vault.Key k = null;
        try { Vault.Key mine = key(c); if (Vault.matches(mine, h)) k = mine; } catch (Exception ignored) { }
        if (k == null) {
            if (pw == null || pw.isEmpty()) return new JSONObject().put("needPw", true);
            char[] p = pw.toCharArray();
            try { k = Vault.keyFor(h, p); }
            catch (java.security.NoSuchAlgorithmException e) { throw new Exception("Dit archief kan op deze Android-versie niet geopend worden; gebruik ontsleutelen.html op de computer"); }
            finally { java.util.Arrays.fill(p, '\0'); }
        }
        openUri = u; openHeader = h; openKey = k;
        openName = displayName(c, u);
        JSONArray files = new JSONArray();
        String contacts = null, notes = null, launcher = null, settings = null, transcripts = null, music = null;
        long total = 0;
        try (ZipInputStream z = new ZipInputStream(stream(c))) {
            ZipEntry e;
            byte[] buf = new byte[1 << 16];
            Set<String> bad = new HashSet<>();
            while ((e = z.getNextEntry()) != null) {
                long size = 0;
                int r;
                boolean list = "onvolledig.txt".equals(e.getName());
                ByteArrayOutputStream lb = list ? new ByteArrayOutputStream() : null;
                while ((r = z.read(buf)) > 0) { size += r; if (lb != null && lb.size() < 65536) lb.write(buf, 0, r); }
                if (lb != null) for (String l : lb.toString("UTF-8").split("\n")) if (!l.trim().isEmpty()) bad.add(l.trim());
                total += size;
                String n = e.getName();
                files.put(new JSONObject().put("n", n).put("s", size));
                if (n.startsWith(Contacts.DIR + "/contacten-") && n.endsWith(".vcf")) contacts = n;
                if (n.equals(Notes.DIR + "/notities.json")) notes = n;
                if (n.equals(LauncherBackup.DIR + "/" + LauncherBackup.FILE)) launcher = n;
                if (n.equals(SettingsBackup.DIR + "/" + SettingsBackup.FILE)) settings = n;
                if (n.equals(Transcribe.DIR + "/" + SettingsBackup.TX_FILE)) transcripts = n;
                if (n.equals("Muziek/herkende-nummers.json")) music = n;
            }
            // Wat bij het maken halverwege misging, niet aanbieden om terug te zetten
            if (bad.contains(contacts)) contacts = null;
            if (bad.contains(notes)) notes = null;
            if (bad.contains(launcher)) launcher = null;
            if (bad.contains(settings)) settings = null;
            if (bad.contains(transcripts)) transcripts = null;
            if (bad.contains(music)) music = null;
        } catch (Exception e) {
            forget();
            throw e;
        }
        JSONObject o = new JSONObject().put("name", openName).put("files", files).put("bytes", total);
        if (contacts != null) o.put("contacts", contacts);
        if (notes != null) o.put("notes", notes);
        if (launcher != null) o.put("launcher", launcher);
        if (settings != null) o.put("settings", settings);
        if (transcripts != null) o.put("transcripts", transcripts);
        if (music != null) o.put("music", music);
        return o;
    }

    /**
     * Proef-terugzetten voor de backup-controle: het archief los openen met de bewaarde sleutel (zonder het archief
     * te raken dat je zelf geopend hebt), alle bestanden doorlopen en notities.json echt inlezen.
     * Geeft "bestanden|notities" (notities = -1 als die er niet in zit); gooit bij een verkeerde sleutel of een kapot archief.
     */
    static String verify(Context c, Uri u) throws Exception {
        byte[] h;
        try (InputStream in = c.getContentResolver().openInputStream(u)) {
            if (in == null) throw new Exception("Bestand niet te openen");
            h = Vault.readHeader(in);
        }
        Vault.Key k = key(c);
        if (k == null) throw new Exception("er is geen sleutel bewaard op deze telefoon");
        if (!Vault.matches(k, h)) throw new Exception("past niet bij de bewaarde sleutel");
        int files = 0, notes = -1;
        try {
            InputStream raw0 = c.getContentResolver().openInputStream(u);
            if (raw0 == null) throw new Exception("Bestand niet te openen");
            InputStream raw = new java.io.BufferedInputStream(raw0, 1 << 16);
            byte[] hh;
            try { hh = Vault.readHeader(raw); } catch (Exception he) { try { raw.close(); } catch (Exception ignored) { } throw he; }
            try (ZipInputStream z = new ZipInputStream(new Vault.In(raw, hh, k))) {
                ZipEntry e;
                byte[] buf = new byte[1 << 16];
                while ((e = z.getNextEntry()) != null) {
                    files++;
                    if (e.getName().equals(Notes.DIR + "/notities.json")) {
                        ByteArrayOutputStream b = new ByteArrayOutputStream();
                        int r;
                        while ((r = z.read(buf)) > 0) { b.write(buf, 0, r); if (b.size() > 20_000_000) throw new Exception("notities.json is te groot"); }
                        notes = new JSONArray(new String(b.toByteArray(), StandardCharsets.UTF_8)).length();
                    } else while (z.read(buf) > 0) { /* alleen doorlezen: controleert de versleuteling */ }
                }
            }
        } finally { java.util.Arrays.fill(k.key, (byte) 0); }
        return files + "|" + notes;
    }

    static String displayName(Context c, Uri u) {
        try (Cursor cur = c.getContentResolver().query(u, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } catch (Exception ignored) { }
        return "backup.rtb";
    }

    /** Eén bestand uit het geopende archief als tekst. */
    static String readEntry(Context c, String name) throws Exception {
        try (ZipInputStream z = new ZipInputStream(stream(c))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (!e.getName().equals(name)) continue;
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[1 << 16];
                int r;
                while ((r = z.read(buf)) > 0) { b.write(buf, 0, r); if (b.size() > 30_000_000) throw new Exception("Bestand is te groot"); }
                return new String(b.toByteArray(), StandardCharsets.UTF_8);
            }
        }
        throw new Exception("Niet gevonden in de backup: " + name);
    }

    /** Pakt het geopende archief uit naar backup-map/Uitgepakt/&lt;naam&gt;/. Geeft het aantal bestanden. */
    static int unpack(Context c) throws Exception {
        Uri tree = WaBackup.destUri(c);
        if (tree == null) throw new Exception("Kies eerst een backup-map");
        WaBackup.Dest dest = new WaBackup.Dest(c.getContentResolver(), tree);
        String base = "Uitgepakt/" + WaBackup.safeName(openName.replaceAll("(?i)\\.rtb$", ""));
        int n = 0;
        try (ZipInputStream z = new ZipInputStream(stream(c))) {
            ZipEntry e;
            byte[] buf = new byte[1 << 16];
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (name.contains("..") || name.startsWith("/")) continue;
                int slash = name.lastIndexOf('/');
                StringBuilder rel = new StringBuilder(base);
                if (slash > 0) for (String part : name.substring(0, slash).split("/")) rel.append('/').append(WaBackup.safeName(part));
                WaBackup.DestDir dir = dest.dir(rel.toString(), true);
                String file = WaBackup.safeName(name.substring(slash + 1));
                WaBackup.Child old = dir.kids.get(file);
                if (old != null && !old.dir) DocumentsContract.deleteDocument(dest.cr, DocumentsContract.buildDocumentUriUsingTree(dest.tree, old.docId));
                Uri u = DocumentsContract.createDocument(dest.cr, dir.uri, WaBackup.mime(file), file);
                if (u == null) throw new Exception("Bestand maken lukt niet: " + file);
                try (OutputStream o = dest.cr.openOutputStream(u, "w")) {
                    if (o == null) throw new Exception("Schrijven lukt niet: " + file);
                    int r;
                    while ((r = z.read(buf)) > 0) o.write(buf, 0, r);
                }
                dir.kids.put(file, new WaBackup.Child(DocumentsContract.getDocumentId(u), 0, 0, false));
                n++;
            }
        }
        return n;
    }
}
