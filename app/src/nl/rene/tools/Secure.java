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

    /**
     * Na terugzetten op een nieuwe installatie: de sleutel van het teruggezette archief (afgeleid van het wachtwoord)
     * weer bewaren, ingepakt met de sleutelopslag van deze installatie. Zo lopen de nachtelijke versleutelde backups
     * meteen door met hetzelfde wachtwoord, en opent de app de oude archieven zonder het wachtwoord te vragen.
     */
    static void setKey(Context c, Vault.Key k) throws Exception {
        if (k == null || k.key == null || k.key.length != 32) throw new Exception("Geen bruikbare sleutel");
        Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
        ci.init(Cipher.ENCRYPT_MODE, deviceKey());
        byte[] wrapped = ci.doFinal(k.key);
        if (!prefs(c).edit()
                .putString("wrapped", b64(ci.getIV()) + ":" + b64(wrapped))
                .putString("salt", b64(k.salt)).putInt("iter", k.iter).putInt("kdf", k.kdf)
                .putLong("since", System.currentTimeMillis()).commit()) throw new Exception("Bewaren van de sleutel lukt niet");
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
        /** Grote bestanden apart en één keer (Blokken); null = alles in het archief zelf (zoals vroeger, en in tests). */
        Blokken.Schrijver blok;
        /** Voor grote bestanden die nog geschreven worden (in de cache van de app). */
        java.io.File tmpDir;
        /** Apart opslaan lukte niet (map, verbinding): de rest van deze ronde gaan grote bestanden in het archief zelf. */
        volatile Exception blokFout;
        /** Hoeveel inhoud er in totaal door dit archief ging (ook de grote bestanden), en hoeveel er het archief zelf in ging. */
        long ruw = 0;
        final Blokken.Teller uit;
        /** Wat er al compleet in dit archief staat (naam → grootte en SHA-256), om dezelfde inhoud niet twee keer op te nemen. */
        final Map<String, AppData.Entry> done = new java.util.HashMap<>();
        final Map<Long, List<AppData.Entry>> bySize = new java.util.HashMap<>();

        void record(String path, long size, String sha) {
            AppData.Entry e = new AppData.Entry(path, size, sha);
            done.put(path, e);
            List<AppData.Entry> l = bySize.get(size);
            if (l == null) { l = new ArrayList<>(); bySize.put(size, l); }
            l.add(e);
        }

        /** Staat er al een ander onderdeel (niet de appgegevens zelf) met deze grootte in het archief? */
        boolean hasSize(long size) { return bySize.containsKey(size); }

        /** Een eerder, compleet bestand van een ander onderdeel met precies deze inhoud; anders null. */
        AppData.Entry same(long size, String sha) {
            List<AppData.Entry> l = bySize.get(size);
            if (l != null) for (AppData.Entry e : l)
                if (e.sha.equals(sha) && !bad.contains(e.path) && !e.path.startsWith(AppData.DIR + "/")) return e;
            return null;
        }

        Zip(WaBackup.Dest d, WaBackup.DestDir dr, Uri u, String n, OutputStream r, Vault.Key k) throws IOException {
            dest = d; dir = dr; doc = u; name = n; raw = r;
            uit = new Blokken.Teller(raw);
            z = new ZipOutputStream(new Vault.Out(uit, k));
            z.setLevel(6);
        }

        /** Bytes die al naar het archief gingen (versleuteld); de laatste paar kB kunnen nog in de buffers zitten. */
        long archiefBytes() { return uit.n; }

        Writer writer(String dir, String file) throws IOException {
            // Tekst: altijd weer gewoon inpakken (level 6), ook na een licht ingepakt geluidsbestand
            return new java.io.BufferedWriter(new OutputStreamWriter(stream(dir, file, 6), StandardCharsets.UTF_8), 1 << 16);
        }

        /** Binair bestand in het archief (bijv. kluisdocumenten). */
        OutputStream stream(String dir, String file) throws IOException { return stream(dir, file, 6); }

        /** Zelfde, met een eigen mate van inpakken (1 = licht, voor al samengeperst geluid; 6 = gewoon). */
        OutputStream stream(String dir, String file, int level) throws IOException {
            String path = unique(dir, file);
            files++;
            // Met grote bestanden apart: eerst opvangen; bij sluiten klein → in het archief, groot → apart (of al aanwezig)
            if (blok != null) return new Spool(this, path, level);
            ZipEntry e = new ZipEntry(path);
            e.setTime(System.currentTimeMillis());
            z.setLevel(level); // geldt vanaf dit bestand; het volgende stream() of writer() zet het weer (writer: 6)
            z.putNextEntry(e);
            return new EntryStream(this, lastPath);
        }

        /** Een klein (opgevangen) bestand nu in het archief. */
        void inline(String path, byte[] b, int len, int level, String sha) throws IOException {
            ZipEntry e = new ZipEntry(path);
            e.setTime(System.currentTimeMillis());
            z.setLevel(level);
            z.putNextEntry(e);
            z.write(b, 0, len);
            z.closeEntry();
            record(path, len, sha);
        }

        /** Een groot bestand toch in het archief zelf (uit het tijdelijke bestand). */
        void inlineFile(String path, java.io.File f, long len, int level, String sha) throws IOException {
            ZipEntry e = new ZipEntry(path);
            e.setTime(System.currentTimeMillis());
            z.setLevel(level);
            z.putNextEntry(e);
            long done = 0;
            try (InputStream in = new java.io.BufferedInputStream(new java.io.FileInputStream(f), 1 << 16)) {
                byte[] b = new byte[1 << 16];
                int r;
                while ((r = in.read(b)) > 0) { z.write(b, 0, r); done += r; }
            }
            z.closeEntry();
            if (done != len) throw new IOException("Tijdelijk bestand onvolledig");
            record(path, len, sha);
        }

        /** De lijst met grote bestanden als laatste (vóór inhoud.txt), onverpakt; niets als er geen grote bestanden zijn. */
        void lijst() throws Exception {
            if (blok == null || blok.items.isEmpty()) return;
            byte[] b = Blokken.json(blok.ids.kid, blok.items).getBytes(StandardCharsets.UTF_8);
            if (!names.add(Blokken.LIST.toLowerCase(Locale.ROOT))) throw new IOException("Dubbele naam in het archief");
            putStored(Blokken.LIST, b);
        }

        private void putStored(String path, byte[] b) throws IOException {
            ZipEntry e = new ZipEntry(path);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(b, 0, b.length);
            e.setMethod(ZipEntry.STORED);
            e.setSize(b.length);
            e.setCompressedSize(b.length);
            e.setCrc(crc.getValue());
            e.setTime(System.currentTimeMillis());
            z.putNextEntry(e);
            z.write(b, 0, b.length);
            z.closeEntry();
        }

        /** Al samengeperst bestand (geluid): ongecomprimeerd in het archief, dat scheelt rekenwerk en tijd. */
        void stored(String dir, String file, byte[] b) throws IOException {
            if (blok != null && b.length >= Blokken.MIN) { try (OutputStream o = stream(dir, file, 1)) { o.write(b, 0, b.length); } return; }
            ZipEntry e = new ZipEntry(unique(dir, file));
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(b, 0, b.length);
            e.setMethod(ZipEntry.STORED);
            e.setSize(b.length);
            e.setCompressedSize(b.length);
            e.setCrc(crc.getValue());
            e.setTime(System.currentTimeMillis());
            z.putNextEntry(e);
            files++;
            z.write(b, 0, b.length);
            z.closeEntry();
            ruw += b.length;
            java.security.MessageDigest md = AppData.sha256();
            md.update(b, 0, b.length);
            record(lastPath, b.length, AppData.hex(md.digest()));
        }

        private String unique(String dir, String file) {
            String path = (dir == null || dir.isEmpty() ? "" : dir + "/") + file;
            String p = path;
            int dot = path.lastIndexOf('.');
            for (int i = 2; !names.add(p.toLowerCase(Locale.ROOT)); i++)
                p = dot > path.lastIndexOf('/') ? path.substring(0, dot) + " (" + i + ")" + path.substring(dot) : path + " (" + i + ")";
            lastPath = p;
            return p;
        }
    }

    /**
     * Opvangen met grote bestanden apart: tot Blokken.MIN in het geheugen, daarboven in een tijdelijk bestand. Sluiten =
     * klein: als gewoon bestand in het archief; groot: naar Versleuteld/Grote bestanden (of al aanwezig: hergebruiken).
     */
    static final class Spool extends OutputStream {
        private final Zip zip; private final String path; private final int level;
        private final java.security.MessageDigest md = AppData.sha256();
        private long n = 0;
        private ByteArrayOutputStream mem = new ByteArrayOutputStream();
        private java.io.File f;
        private OutputStream fo;
        private boolean closed = false;
        Spool(Zip zip, String path, int level) { this.zip = zip; this.path = path; this.level = level; }

        @Override public void write(int b) throws IOException { write(new byte[]{(byte) b}, 0, 1); }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            if (closed) throw new IOException("Al gesloten");
            md.update(b, off, len);
            n += len;
            if (fo != null) { fo.write(b, off, len); return; }
            mem.write(b, off, len);
            if (mem.size() >= Blokken.MIN) {
                if (zip.tmpDir != null) zip.tmpDir.mkdirs();
                f = java.io.File.createTempFile("blok-", ".tmp", zip.tmpDir);
                fo = new java.io.BufferedOutputStream(new java.io.FileOutputStream(f), 1 << 16);
                mem.writeTo(fo);
                mem = null;
            }
        }

        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            zip.ruw += n;
            String sha = AppData.hex(md.digest());
            try {
                if (fo == null) { byte[] b = mem.toByteArray(); zip.inline(path, b, b.length, level, sha); return; }
                fo.close();
                fo = null;
                // Apart, behalve wat altijd in het archief hoort; lukte apart opslaan eerder deze ronde niet, dan niet steeds
                // opnieuw proberen: dan gaat het in het archief zelf (groter, maar compleet)
                if (Blokken.apart(path) && zip.blokFout == null) {
                    try { zip.blok.bewaar(path, f, n, sha); zip.record(path, n, sha); return; }
                    catch (Exception e) { zip.blokFout = e; }
                }
                zip.inlineFile(path, f, n, level, sha);
            } finally {
                if (fo != null) try { fo.close(); } catch (IOException ignored) { }
                if (f != null) f.delete();
            }
        }
    }

    /** Sluiten = alleen het bestand in het archief afsluiten, niet het archief zelf. Telt grootte en SHA-256 mee. */
    static final class EntryStream extends FilterOutputStream {
        private final ZipOutputStream z;
        private final Zip zip;
        private final String path;
        private final java.security.MessageDigest md = AppData.sha256();
        private long n = 0;
        private boolean closed = false;
        EntryStream(Zip zip, String path) { super(zip.z); this.z = zip.z; this.zip = zip; this.path = path; }
        @Override public void write(int b) throws IOException { z.write(b); md.update((byte) b); n++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { z.write(b, off, len); md.update(b, off, len); n += len; }
        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            flush();
            z.closeEntry();
            zip.ruw += n;
            zip.record(path, n, AppData.hex(md.digest()));
        }
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
        // Grote bestanden apart: eerst het merkteken (sinds-…), en pas daarna de naam van het archief (zie Blokken.SINDS)
        BlokOpslag blokken = null;
        if (AllBackup.blokken(c)) {
            blokken = new BlokOpslag(WaBackup.Dest.echt(c.getContentResolver(), dest.tree));
            // Lukt het merkteken niet (map niet bereikbaar), dan deze keer alles in het archief zelf: zonder merkteken
            // weet opruimen later niet welke archieven ouder zijn
            try { blokken.merkteken(); } catch (Exception e) { App.log(c, "BACKUP", "grote bestanden: " + AppData.safeMsg(e, "merkteken mislukt")); blokken = null; }
        }
        String name = "backup-" + new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(new Date()) + ".rtb";
        // Eerst onder een tijdelijke naam schrijven; pas als het archief compleet is krijgt het de echte naam.
        Uri u = WaBackup.create(dest, dir.uri, "application/octet-stream", name + ".part");
        if (u == null) throw new Exception("Archief maken lukt niet");
        OutputStream o = null;
        try {
            o = dest.cr.openOutputStream(u, "w");
            if (o == null) throw new Exception("Archief schrijven lukt niet");
            Zip zip = new Zip(dest, dir, u, name, new java.io.BufferedOutputStream(o, 1 << 16), k);
            // Grote bestanden één keer, apart in Versleuteld/Grote bestanden (in de echte map, niet in het archief)
            if (blokken != null) {
                zip.tmpDir = AppBackup.grootTmp(c);
                zip.blok = new Blokken.Schrijver(blokken, k);
                zip.blok.bekend.putAll(BlokOpslag.bekend(c));
            }
            return zip;
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
            zip.lijst(); // welke grote bestanden apart staan (Versleuteld/Grote bestanden)
            try (Writer w = zip.writer("", "inhoud.txt")) {
                w.write("Versleutelde backup van Tools\r\nGemaakt: " + new SimpleDateFormat("d MMM yyyy HH:mm", new Locale("nl", "NL")).format(new Date())
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
        // Gestopt halverwege: een eigen naam (…_deels.rtb), zodat opruimen hem nooit als volledige backup telt
        String suffix = res.optBoolean("stopped") ? Rotate.PARTIAL + ".rtb" : ".rtb";
        String name = zip.name.replaceAll("\\.rtb$", "") + suffix;
        if (zip.dir.kids.containsKey(name))
            name = "backup-" + new SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(new Date()) + suffix;
        Uri done = null;
        try { done = WaBackup.rename(zip.dest, zip.doc, name); } catch (Exception ignored) { }
        if (done == null) {
            // Hernoemen kan hier niet: kopiëren naar een bestand met de echte naam, daarna het tijdelijke weg
            Uri u = WaBackup.create(zip.dest, zip.dir.uri, "application/octet-stream", name);
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
        dropPartials(zip, name);
        return name;
    }

    /** Onvolledige archieven (…_deels.rtb) die een nieuwer archief hebben of te oud zijn: weg, ook zonder "opruimen". */
    static void dropPartials(Zip zip, String justMade) {
        try {
            List<String> names = new ArrayList<>(zip.dir.kids.keySet());
            names.add(justMade);
            for (String n : Rotate.stalePartials(names, System.currentTimeMillis())) {
                WaBackup.Child ch = zip.dir.kids.get(n);
                if (ch == null || ch.dir || n.equals(justMade)) continue;
                try { DocumentsContract.deleteDocument(zip.dest.cr, DocumentsContract.buildDocumentUriUsingTree(zip.dest.tree, ch.docId)); zip.dir.kids.remove(n); }
                catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
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
        int n = 0, keep = AllBackup.keep(c);
        long bytes = 0;
        for (String d : ROTATE_DIRS) {
            WaBackup.DestDir dir = dest.dir(d, false);
            if (dir == null) continue;
            List<String> files = new ArrayList<>();
            for (Map.Entry<String, WaBackup.Child> e : dir.kids.entrySet()) if (!e.getValue().dir) files.add(e.getKey());
            for (String name : Rotate.toDelete(files, System.currentTimeMillis(), keep, DIR.equals(d) ? appBlijft(c, dest, dir) : null)) {
                WaBackup.Child ch = dir.kids.get(name);
                if (!dry) {
                    try { DocumentsContract.deleteDocument(dest.cr, DocumentsContract.buildDocumentUriUsingTree(dest.tree, ch.docId)); }
                    catch (Exception e) { App.log(c, "SECURE", "opruimen mislukt: " + AppData.safeMsg(e, e.getClass().getSimpleName())); continue; }
                }
                n++;
                bytes += Math.max(0, ch.size);
            }
        }
        return new JSONObject().put("count", n).put("bytes", bytes);
    }

    /** De nieuwste backup met alle appgegevens (Versleuteld/appgegevens.txt, en wat deze telefoon het laatst maakte):
     *  die blijft altijd staan, ook als er daarna backups zonder appgegevens kwamen. */
    static List<String> appBlijft(Context c, WaBackup.Dest dest, WaBackup.DestDir dir) {
        List<String> uit = new ArrayList<>();
        try {
            WaBackup.Child l = dir.kids.get(Herstel.APP_LIST);
            if (l != null && !l.dir) {
                List<String> met = AppData.appNames(Herstel.readCapped(c, DocumentsContract.buildDocumentUriUsingTree(dest.tree, l.docId)));
                for (String n : AppData.archives(new ArrayList<>(dir.kids.keySet()))) if (met.contains(n)) { uit.add(n); break; }
            }
        } catch (Throwable e) { /* dan alleen de naam uit de instellingen */ }
        String laatst = AllBackup.prefs(c).getString("appLastName", "");
        if (!laatst.isEmpty()) uit.add(laatst);
        return uit;
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
            JSONObject d = new JSONObject().put("name", e.getKey()).put("bytes", s[0]).put("files", s[1]);
            // Versleuteld uitgesplitst: backups, grote bestanden en oude volle backups (van vóór de grote bestanden)
            if (DIR.equals(e.getKey())) try { d.put("delen", versleuteldDelen(c, dest, ch)); } catch (Throwable ignored) { }
            dirs.put(d);
            total += s[0];
        }
        if (looseN > 0) dirs.put(new JSONObject().put("name", "(losse bestanden)").put("bytes", loose).put("files", looseN));
        total += loose;
        JSONObject o = new JSONObject().put("dirs", dirs).put("total", total).put("t", System.currentTimeMillis());
        prefs(c).edit().putString("space", o.toString()).apply();
        return o;
    }

    /**
     * De map Versleuteld in delen: backups, oude volle backups (wat "Oude, volledige backups weghalen" zou weghalen),
     * de map Grote bestanden en de rest. Elk {n, bytes}.
     */
    static JSONObject versleuteldDelen(Context c, WaBackup.Dest dest, WaBackup.Child ch) throws Exception {
        WaBackup.DestDir v = new WaBackup.DestDir();
        v.uri = DocumentsContract.buildDocumentUriUsingTree(dest.tree, ch.docId);
        dest.list(v);
        long[] nieuw = new long[2], oud = new long[2], groot = new long[2], rest = new long[2];
        for (Map.Entry<String, WaBackup.Child> e : v.kids.entrySet()) {
            WaBackup.Child k = e.getValue();
            String n = e.getKey();
            if (k.dir) {
                long[] s = new long[2];
                sum(dest, k.docId, s, 1);
                long[] doel = Blokken.DIR.equals(n) ? groot : rest;
                doel[0] += s[0]; doel[1] += s[1];
                continue;
            }
            long[] doel = n.endsWith(".rtb") && Rotate.parse(n) != null ? nieuw : rest;
            doel[0] += Math.max(0, k.size); doel[1]++;
        }
        // Oude, volledige backups: precies wat de knop "Oude, volledige backups weghalen" zou weghalen (zelfde regels)
        try {
            JSONObject w = BlokOpslag.oudeArchieven(c, true);
            long n = w.optInt("count"), b = w.optLong("bytes");
            if (n > 0 && n <= nieuw[1]) { oud[1] = n; oud[0] = b; nieuw[1] -= n; nieuw[0] = Math.max(0, nieuw[0] - b); }
        } catch (Throwable ignored) { }
        JSONObject o = new JSONObject();
        o.put("backups", new JSONObject().put("n", nieuw[1]).put("bytes", nieuw[0]));
        o.put("oud", new JSONObject().put("n", oud[1]).put("bytes", oud[0]));
        o.put("groot", new JSONObject().put("n", groot[1]).put("bytes", groot[0]));
        o.put("rest", new JSONObject().put("n", rest[1]).put("bytes", rest[0]));
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

    /** Het geopende archief lezen, met de grote bestanden uit Versleuteld/Grote bestanden erbij (pas opgehaald als nodig). */
    static Blokken.Lezer lezer(Context c) throws Exception {
        Vault.Key k = openKey;
        if (k == null) throw new Exception("Open de backup opnieuw");
        return new Blokken.Lezer(new ZipInputStream(stream(c)), null, Blokken.bron(BlokOpslag.voor(c), k));
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
        String contacts = null, notes = null, launcher = null, settings = null, transcripts = null, music = null, ai = null, sleep = null, checkin = null;
        long total = 0;
        try (Blokken.Lezer z = lezer(c)) {
            byte[] buf = new byte[1 << 16];
            Set<String> bad = new HashSet<>();
            while (z.volgende()) {
                long size = 0;
                int r;
                boolean list = "onvolledig.txt".equals(z.naam());
                if (z.extern()) size = z.item().s; // groot bestand apart: niet ophalen, alleen tellen
                else {
                    InputStream in = z.inhoud();
                    ByteArrayOutputStream lb = list ? new ByteArrayOutputStream() : null;
                    while ((r = in.read(buf)) > 0) { size += r; if (lb != null && lb.size() < 65536) lb.write(buf, 0, r); }
                    if (lb != null) for (String l : lb.toString("UTF-8").split("\n")) if (!l.trim().isEmpty()) bad.add(l.trim());
                }
                total += size;
                String n = z.naam();
                files.put(new JSONObject().put("n", n).put("s", size));
                if (n.startsWith(Contacts.DIR + "/contacten-") && n.endsWith(".vcf")) contacts = n;
                if (n.equals(Notes.DIR + "/notities.json")) notes = n;
                if (n.equals(LauncherBackup.DIR + "/" + LauncherBackup.FILE)) launcher = n;
                if (n.equals(SettingsBackup.DIR + "/" + SettingsBackup.FILE)) settings = n;
                if (n.equals(Transcribe.DIR + "/" + SettingsBackup.TX_FILE)) transcripts = n;
                if (n.equals("Muziek/herkende-nummers.json")) music = n;
                if (n.equals(AiSleepBackup.AI_DIR + "/" + AiSleepBackup.AI_FILE)) ai = n;
                if (n.equals(AiSleepBackup.SLEEP_DIR + "/" + AiSleepBackup.SLEEP_FILE)) sleep = n;
                if (n.equals(CheckinStore.DIR + "/" + CheckinStore.FILE)) checkin = n;
            }
            // Wat bij het maken halverwege misging, niet aanbieden om terug te zetten
            if (bad.contains(contacts)) contacts = null;
            if (bad.contains(notes)) notes = null;
            if (bad.contains(launcher)) launcher = null;
            if (bad.contains(settings)) settings = null;
            if (bad.contains(transcripts)) transcripts = null;
            if (bad.contains(music)) music = null;
            if (bad.contains(ai)) ai = null;
            if (bad.contains(sleep)) sleep = null;
            if (bad.contains(checkin)) checkin = null;
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
        if (ai != null) o.put("ai", ai);
        if (sleep != null) o.put("sleep", sleep);
        if (checkin != null) o.put("checkin", checkin);
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
            Vault.In vin = new Vault.In(raw, hh, k);
            BlokOpslag o = BlokOpslag.voor(c);
            try (Blokken.Lezer z = new Blokken.Lezer(new ZipInputStream(vin), vin, Blokken.bron(o, k))) {
                byte[] buf = new byte[1 << 16];
                Map<String, Long> er = null;
                while (z.volgende()) {
                    files++;
                    // Groot bestand apart: staat het er (met de verwachte grootte)? Helemaal lezen doet de nachtelijke controle.
                    if (z.extern()) {
                        if (er == null) er = o == null ? new java.util.HashMap<String, Long>() : o.lijst();
                        Long have = er.get(z.item().b);
                        if (have == null) throw new Exception("groot bestand ontbreekt in " + Blokken.DIR);
                        if (have >= 0 && z.item().e > 0 && have != z.item().e) throw new Exception("groot bestand heeft een andere grootte");
                        if (!z.naam().equals(Notes.DIR + "/notities.json")) continue; // de notities ook als groot bestand echt inlezen
                    }
                    InputStream in = z.inhoud();
                    if (z.naam().equals(Notes.DIR + "/notities.json")) {
                        ByteArrayOutputStream b = new ByteArrayOutputStream();
                        int r;
                        while ((r = in.read(buf)) > 0) { b.write(buf, 0, r); if (b.size() > 20_000_000) throw new Exception("notities.json is te groot"); }
                        notes = new JSONArray(new String(b.toByteArray(), StandardCharsets.UTF_8)).length();
                    } else while (in.read(buf) > 0) { /* alleen doorlezen: controleert de versleuteling */ }
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
        try (Blokken.Lezer z = lezer(c)) {
            while (z.volgende()) {
                if (!z.naam().equals(name)) continue;
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[1 << 16];
                int r;
                InputStream in = z.inhoud();
                while ((r = in.read(buf)) > 0) { b.write(buf, 0, r); if (b.size() > 30_000_000) throw new Exception("Bestand is te groot"); }
                return new String(b.toByteArray(), StandardCharsets.UTF_8);
            }
        }
        throw new Exception("Niet gevonden in de backup: " + name);
    }

    /** Bij het laatste uitpakken: zoveel grote bestanden ontbraken of waren beschadigd. */
    static volatile int ontbrak = 0;

    /** Pakt het geopende archief uit naar backup-map/Uitgepakt/&lt;naam&gt;/. Geeft het aantal bestanden (zie ook ontbrak). */
    static int unpack(Context c) throws Exception {
        ontbrak = 0;
        Uri tree = WaBackup.destUri(c);
        if (tree == null) throw new Exception("Kies eerst een backup-map");
        WaBackup.Dest dest = new WaBackup.Dest(c.getContentResolver(), tree);
        String base = "Uitgepakt/" + WaBackup.safeName(openName.replaceAll("(?i)\\.rtb$", ""));
        int n = 0;
        try (Blokken.Lezer z = lezer(c)) {
            byte[] buf = new byte[1 << 16];
            while (z.volgende()) {
                String name = z.naam();
                if (name.contains("..") || name.startsWith("/")) continue;
                // Geheimen (VPN-sleutels, wachtwoorden, koppelingen) nooit leesbaar in de backup-map: alleen in het archief
                if (name.startsWith(AppData.SECRET_DIR + "/") || name.equals(AppData.PREFS)) continue;
                int slash = name.lastIndexOf('/');
                StringBuilder rel = new StringBuilder(base);
                if (slash > 0) for (String part : name.substring(0, slash).split("/")) rel.append('/').append(WaBackup.safeName(part));
                WaBackup.DestDir dir = dest.dir(rel.toString(), true);
                String file = WaBackup.safeName(name.substring(slash + 1));
                // Eerst openen: ontbreekt een groot bestand, dan blijft een eerder uitgepakte versie staan
                InputStream in;
                try { in = z.inhoud(); } catch (Blokken.LeesFout e) { ontbrak++; continue; }
                WaBackup.Child old = dir.kids.get(file);
                if (old != null && !old.dir) DocumentsContract.deleteDocument(dest.cr, DocumentsContract.buildDocumentUriUsingTree(dest.tree, old.docId));
                Uri u = WaBackup.create(dest, dir.uri, WaBackup.mime(file), file);
                if (u == null) throw new Exception("Bestand maken lukt niet: " + file);
                try (OutputStream o = dest.cr.openOutputStream(u, "w")) {
                    if (o == null) throw new Exception("Schrijven lukt niet: " + file);
                    int r;
                    while ((r = in.read(buf)) > 0) o.write(buf, 0, r);
                } catch (Blokken.LeesFout e) {
                    // Een groot bestand ontbreekt of is beschadigd: dat ene bestand niet, de rest wel
                    try { DocumentsContract.deleteDocument(dest.cr, u); } catch (Exception ignored) { }
                    ontbrak++;
                    continue;
                }
                dir.kids.put(file, new WaBackup.Child(DocumentsContract.getDocumentId(u), 0, 0, false));
                n++;
            }
        }
        return n;
    }
}
