package nl.rene.tools;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.security.keystore.UserNotAuthenticatedException;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Bankzaken: afschriften analyseren, volledig op deze telefoon.
 *
 * Beveiliging (zie ook het scherm "Zo blijven je gegevens veilig"):
 * - Alles staat versleuteld (AES-256-GCM, BankCrypt) in getFilesDir()/bank. De sleutel wordt gemaakt in de beveiligde
 *   hardware van de telefoon (Android Keystore) en kan daar niet uit.
 * - De sleutel werkt alleen tot 15 minuten na je vingerafdruk of pincode (door de hardware afgedwongen, niet alleen door
 *   de app). Daarnaast gaat Bankzaken na 5 minuten niets doen, of zodra de app uit beeld gaat, op slot.
 * - Standaard buiten backups; alleen na eigen toestemming in een versleutelde appbackup. Nooit in de Android-backup,
 *   zoeken, de assistent of Statistieken. De app maakt geen verbinding naar buiten voor Bankzaken (de interface mag dat sowieso
 *   niet: connect-src 'none'); de AI draait op de telefoon zelf.
 * - Alles wissen = ook de sleutel weg: wat er eventueel nog op de opslag zou staan, is dan voorgoed onleesbaar.
 */
final class Bank {

    private Bank() { }

    static final String ALIAS = "renes_bankzaken";
    static final int KEY_SECONDS = 15 * 60;
    static final long IDLE_MS = 5 * 60_000L, MAX_MS = KEY_SECONDS * 1000L - 30_000L;

    static final Object LOCK = new Object();
    /** Open tot dit moment (5 min na het laatste gebruik), en hooguit tot maxUntil (de sleutel verloopt dan). */
    static volatile long openUntil = 0, maxUntil = 0;
    /** Elke ontgrendeling heeft een eigen generatie; een los venster moet zelf ontgrendeld zijn. */
    static volatile long generation = 0;
    static volatile boolean restoring = false;
    /** De AI is gebruikt met bankgegevens: bij op slot gaan het taalmodel stoppen (dan staat er niets meer in zijn geheugen). */
    static volatile boolean aiUsed = false;
    /** Bestanden kiezen loopt (de app gaat dan even uit beeld): niet op slot doen. */
    static volatile boolean picking = false;

    static boolean isOpen() {
        long n = System.currentTimeMillis();
        boolean open = n < openUntil && n < maxUntil;
        if (!open && (aiUsed || openUntil != 0)) close(); // vanzelf verlopen: ook het taalmodel en wachtende afschriften opruimen
        return open;
    }
    /** Bestanden kiezen telt hooguit 2 minuten als "binnen de app" (daarna gewoon op slot als de app uit beeld gaat). */
    static volatile long pickingAt = 0;
    static boolean kiest() { return picking && System.currentTimeMillis() - pickingAt < 120_000L; }
    /** Versie van de opgeslagen gegevens: een opslag vanuit het scherm met een oudere versie wordt geweigerd (geen import kwijt). */
    static volatile long rev = 0;
    /** De sleutel bestaat nog, maar is door de telefoon ongeldig gemaakt (schermvergrendeling gewijzigd). */
    static volatile boolean ongeldig = false;
    static long remaining() { long n = System.currentTimeMillis(); return Math.max(0, Math.min(openUntil, maxUntil) - n); }

    static synchronized long open() { long n = System.currentTimeMillis(); generation++; maxUntil = n + MAX_MS; openUntil = n + IDLE_MS; return generation; }
    static synchronized boolean session(long grant) { return grant == generation && isOpen(); }
    static void touch() { if (isOpen()) openUntil = System.currentTimeMillis() + IDLE_MS; }

    static synchronized void close() {
        generation++;
        openUntil = 0; maxUntil = 0;
        synchronized (WACHT) { if (System.currentTimeMillis() > wachtTot) WACHT.clear(); }
        if (aiUsed) {
            aiUsed = false;
            try { LocalAi.cancel(); } catch (Throwable ignored) { }
            try { LocalAi.closeChat(); } catch (Throwable ignored) { }
        }
    }

    /** Ontgrendelantwoord hoort bij één venster; een vertraagd antwoord mag niet verborgen openen. */
    static final class Unlock {
        private long serial, until;
        private boolean foreground, pending;
        synchronized long begin() { if (!foreground) return -1; pending = true; until = 0; return ++serial; }
        synchronized boolean authenticated(long request, long now) { if (request != serial || !pending) return false; pending = false; until = now + 5000; return true; }
        synchronized boolean ready(long request, long now) { return foreground && request == serial && until > 0 && now <= until; }
        synchronized boolean claim(long request, long now) { if (!ready(request, now)) return false; until = 0; return true; }
        synchronized void resume() { foreground = true; }
        synchronized void pause(boolean systemAuth) { foreground = false; if (!pending || !systemAuth) cancel(); }
        synchronized void cancel() { serial++; pending = false; until = 0; }
    }

    static File dir(Context c) { File d = new File(c.getFilesDir(), "bank"); d.mkdirs(); return d; }
    static File data(Context c) { return new File(dir(c), "gegevens.bin"); }
    /** Alleen technische gegevens (geen bedragen, namen of rekeningen): hoe de sleutel beveiligd is. */
    static File meta(Context c) { return new File(dir(c), "sleutel.json"); }

    static boolean hasData(Context c) { return data(c).exists(); }

    static JSONObject metaJson(Context c) {
        try (FileInputStream in = new FileInputStream(meta(c))) {
            byte[] b = new byte[(int) Math.min(4096, meta(c).length())];
            int n = in.read(b);
            return new JSONObject(new String(b, 0, Math.max(0, n), StandardCharsets.UTF_8));
        } catch (Exception e) { return new JSONObject(); }
    }

    static KeyStore ks() throws Exception { KeyStore k = KeyStore.getInstance("AndroidKeyStore"); k.load(null); return k; }

    static boolean hasKey() {
        try { return ks().containsAlias(ALIAS); } catch (Throwable t) { return false; }
    }

    static SecretKey existingKey() throws Exception {
        KeyStore k = ks();
        if (!k.containsAlias(ALIAS)) return null;
        return ((KeyStore.SecretKeyEntry) k.getEntry(ALIAS, null)).getSecretKey();
    }

    /** Nieuwe sleutel, direct na een geslaagde vingerafdruk/pincode. Eerst met verplichte ontgrendeling (hardware);
     *  werkt dat op deze telefoon niet, dan zonder, en dat staat dan eerlijk bij "Beveiliging". */
    static SecretKey createKey(Context c) throws Exception {
        for (int poging = 0; poging < 2; poging++) try {
            SecretKey k = generate(true);
            BankCrypt.decrypt(BankCrypt.encrypt(new byte[]{1}, k), k); // werkt hij echt na deze ontgrendeling?
            writeMeta(c, true);
            return k;
        } catch (Throwable t) { try { ks().deleteEntry(ALIAS); } catch (Throwable ignored) { } }
        {
            try { ks().deleteEntry(ALIAS); } catch (Throwable ignored) { }
            App.log(c, "BANK", "sleutel zonder verplichte ontgrendeling");
            SecretKey k = generate(false);
            writeMeta(c, false);
            return k;
        }
    }

    private static SecretKey generate(boolean auth) throws Exception {
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true);
        if (auth) {
            b.setUserAuthenticationRequired(true);
            b.setInvalidatedByBiometricEnrollment(false); // een vingerafdruk erbij maakt de gegevens niet onleesbaar
            if (Build.VERSION.SDK_INT >= 30) b.setUserAuthenticationParameters(KEY_SECONDS, KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL);
            else b.setUserAuthenticationValidityDurationSeconds(KEY_SECONDS);
        }
        g.init(b.build());
        return g.generateKey();
    }

    static void writeMeta(Context c, boolean authBound) {
        try (FileOutputStream o = new FileOutputStream(meta(c))) {
            o.write(new JSONObject().put("v", 1).put("ontgrendeling", authBound).put("t", System.currentTimeMillis()).toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    /** De sleutel; maakt hem als hij er nog niet is (alleen als er nog geen gegevens zijn). */
    static SecretKey key(Context c) throws Exception {
        synchronized (LOCK) {
            SecretKey k = existingKey();
            // Nog geen gegevens en de sleutel was niet aan de ontgrendeling gekoppeld: opnieuw proberen (net ontgrendeld)
            if (k != null && !hasData(c) && !metaJson(c).optBoolean("ontgrendeling", false)) { try { ks().deleteEntry(ALIAS); } catch (Throwable ignored) { } k = null; }
            if (k != null) return k;
            if (hasData(c)) throw new Exception(LOST);
            return createKey(c);
        }
    }

    static final String LOST = "De sleutel van Bankzaken bestaat niet meer (bijvoorbeeld omdat de schermvergrendeling van je telefoon uit is gezet). "
            + "Je bankgegevens zijn daardoor niet meer te openen. Wis ze en importeer je afschriften opnieuw.";
    static final String AGAIN = "Ontgrendel Bankzaken opnieuw (de sleutel werkt maar 15 minuten na je vingerafdruk of pincode)";

    static String why(Throwable t) {
        if (t instanceof UserNotAuthenticatedException) return AGAIN;
        if (t instanceof KeyPermanentlyInvalidatedException) return LOST;
        Throwable c = t.getCause();
        if (c instanceof UserNotAuthenticatedException) return AGAIN;
        if (c instanceof KeyPermanentlyInvalidatedException) return LOST;
        String m = t.getMessage();
        return m == null || m.isEmpty() ? "Bankgegevens openen lukt niet" : m;
    }

    /** De ontsleutelde gegevens als JSON-tekst; "" als er nog niets is. */
    static String load(Context c) throws Exception {
        synchronized (LOCK) {
            if (!isOpen()) throw new Exception("Bankzaken is op slot");
            if (!hasData(c)) return "";
            File f = data(c);
            if (f.length() > 200L * 1024 * 1024) throw new Exception("Bankgegevens zijn te groot");
            byte[] blob = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) { int off = 0, r; while (off < blob.length && (r = in.read(blob, off, blob.length - off)) > 0) off += r; }
            SecretKey k = existingKey();
            if (k == null) throw new Exception(LOST);
            String s;
            try { s = new String(BankCrypt.decrypt(blob, k), StandardCharsets.UTF_8); }
            catch (Throwable t) { if (why(t).equals(LOST)) ongeldig = true; throw t; }
            rev = new JSONObject(s).optLong("rev", 0);
            return s;
        }
    }

    static final String VEROUDERD = "Er is intussen iets geïmporteerd; het scherm is bijgewerkt. Doe je laatste wijziging nog eens.";

    /** Opslaan vanuit het scherm: alleen als het op de laatste versie voortbouwt (anders zou een import verloren gaan). */
    static void saveFromUi(Context c, JSONObject o) throws Exception {
        synchronized (LOCK) {
            if (hasData(c) && o.optLong("rev", 0) != rev) throw new Exception(VEROUDERD);
            save(c, o);
        }
    }

    /** Opslaan met een nieuwe versie (rev + 1). */
    static void save(Context c, JSONObject o) throws Exception {
        synchronized (LOCK) {
            o.put("rev", rev + 1);
            save(c, o.toString());
            rev = rev + 1;
        }
    }

    /** Alleen na de eigen systeemontgrendeling, en alleen voor een door de gebruiker toegepaste backup. */
    static void restoreBackup(Context c, JSONObject data) throws Exception {
        synchronized (LOCK) {
            if (!isOpen() || !ProtectedRestore.has(c, "bank")) throw new Exception("Bankzaken is op slot");
            String receipt = ProtectedRestore.receipt(c, "bank");
            if (hasData(c)) {
                if (receipt.equals(new JSONObject(load(c)).optString("_backupRestore"))) return;
                throw new Exception("De klaargezette gegevens zijn niet te lezen");
            }
            ks().deleteEntry(ALIAS); createKey(c);
            save(c, data.put("_backupRestore", receipt));
        }
    }

    /** Geen geopende schermsessie nodig: de afzonderlijke backup-toestemming én hardware-ontgrendeling zijn vereist. */
    static byte[] backup(Context c) throws Exception {
        synchronized (LOCK) {
            if (!BackupRisk.enabled(c, "bank") || Secure.CAPTURE.get() == null) throw new Exception("Bankzaken is op slot");
            SecretKey key = existingKey();
            if (key == null) throw new Exception(LOST);
            try (java.io.InputStream in = new java.io.FileInputStream(data(c))) {
                return BankCrypt.decrypt(Facturen.lees(in, BankCrypt.MAX_PLAIN + 1024 * 1024), key);
            }
        }
    }

    /** Versleuteld opslaan: eerst volledig schrijven en teruglezen, pas dan het oude bestand vervangen. */
    private static void save(Context c, String json) throws Exception {
        synchronized (LOCK) {
            if (!isOpen()) throw new Exception("Bankzaken is op slot");
            byte[] plain = json.getBytes(StandardCharsets.UTF_8);
            SecretKey k = key(c);
            byte[] blob = BankCrypt.encrypt(plain, k);
            if (!java.util.Arrays.equals(plain, BankCrypt.decrypt(blob, k))) throw new Exception("Opslaan kon niet worden gecontroleerd");
            File tmp = new File(dir(c), "gegevens.schrijven");
            try {
                try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(blob); o.flush(); o.getFD().sync(); }
                if (!tmp.renameTo(data(c))) throw new Exception("Opslaan lukt niet; de vorige gegevens blijven bewaard");
            } finally { tmp.delete(); }
            java.util.Arrays.fill(plain, (byte) 0);
        }
    }

    /** Alles weg: gegevens, technische gegevens en de sleutel zelf. */
    static void wipe(Context c) {
        synchronized (LOCK) {
            File[] fs = dir(c).listFiles();
            if (fs != null) for (File f : fs) {
                // Eerst overschrijven met nullen (op flashgeheugen niet gegarandeerd; daarom gaat de sleutel ook weg)
                try (FileOutputStream o = new FileOutputStream(f)) { byte[] z = new byte[64 * 1024]; long left = f.length(); while (left > 0) { int n = (int) Math.min(z.length, left); o.write(z, 0, n); left -= n; } o.getFD().sync(); } catch (Exception ignored) { }
                f.delete();
            }
            try { ks().deleteEntry(ALIAS); } catch (Throwable ignored) { }
            synchronized (WACHT) { WACHT.clear(); }
            laatsteKoppeling = ""; rev = 0; ongeldig = false;
            close();
        }
    }

    /** Toestand voor het scherm, zonder iets te ontsleutelen. */
    static JSONObject state(Context c) throws Exception {
        JSONObject m = metaJson(c);
        boolean key = hasKey();
        return new JSONObject().put("open", isOpen()).put("left", remaining()).put("data", hasData(c))
                .put("sleutel", key).put("kwijt", hasData(c) && (!key || ongeldig))
                .put("ontgrendeling", m.optBoolean("ontgrendeling", false))
                .put("hardware", key && hardware())
                .put("slot", Lock.deviceSecure(c));
    }

    /** Zit de sleutel echt in de beveiligde hardware? */
    static boolean hardware() {
        try {
            SecretKey k = existingKey();
            if (k == null) return false;
            javax.crypto.SecretKeyFactory f = javax.crypto.SecretKeyFactory.getInstance(k.getAlgorithm(), "AndroidKeyStore");
            android.security.keystore.KeyInfo i = (android.security.keystore.KeyInfo) f.getKeySpec(k, android.security.keystore.KeyInfo.class);
            if (Build.VERSION.SDK_INT >= 31) return i.getSecurityLevel() != KeyProperties.SECURITY_LEVEL_SOFTWARE;
            return insideHw(i);
        } catch (Throwable t) { return false; }
    }

    @SuppressWarnings("deprecation")
    private static boolean insideHw(android.security.keystore.KeyInfo i) { return i.isInsideSecureHardware(); }

    // ---------- importeren (bestanden kiezen of automatisch bij de bank) ----------

    /** Een afschrift in het geheugen (nooit onversleuteld op de opslag). data = null als het lezen al mislukte. */
    static final class Bestand {
        final String naam; final byte[] data;
        Bestand(String naam, byte[] data) { this.naam = naam; this.data = data; }
    }

    /** Opgehaald bij de bank, maar Bankzaken ging intussen op slot: wacht (alleen in het geheugen) tot je weer ontgrendelt. */
    static final java.util.List<Bestand> WACHT = new java.util.ArrayList<>();
    /** Uitkomst van de laatste automatische import, voor het scherm (eenmalig opgehaald). */
    static volatile String laatsteKoppeling = "";
    /** Automatisch importeren loopt (eigen scherm met de website van de bank): niet op slot gaan. */
    static volatile boolean linking = false;
    /** Tot wanneer een opgehaald afschrift in het geheugen mag wachten (daarna weg). */
    static volatile long wachtTot = 0;

    /** Inlezen, samenvoegen met wat er is en versleuteld opslaan. fouten (mag null) = per bestand al een leesfout. */
    static JSONObject importeer(Context c, java.util.List<Bestand> lijst, java.util.List<String> fouten) throws Exception {
        synchronized (LOCK) {
            if (!isOpen()) throw new Exception("Bankzaken ging op slot; ontgrendel en probeer opnieuw");
            String cur = load(c);
            JSONObject store = cur.isEmpty() ? new JSONObject().put("v", 1) : new JSONObject(cur);
            org.json.JSONArray files = new org.json.JSONArray();
            int ok = 0;
            for (int i = 0; i < lijst.size(); i++) {
                Bestand b = lijst.get(i);
                JSONObject f = new JSONObject().put("naam", b.naam);
                String fout = fouten == null ? null : fouten.get(i);
                if (fout != null || b.data == null) { f.put("ok", false).put("fout", fout == null ? "Lezen lukt niet" : fout); files.put(f); continue; }
                try {
                    BankImport.Result r = BankImport.parse(b.data, b.naam);
                    f.put("ok", true).put("sum", BankImport.merge(store, r, System.currentTimeMillis()));
                    ok++;
                } catch (Throwable t) { f.put("ok", false).put("fout", t instanceof OutOfMemoryError ? "Bestand is te groot" : t.getMessage() == null ? "Inlezen lukt niet" : t.getMessage()); }
                files.put(f);
            }
            if (ok > 0) save(c, store);
            App.log(c, "BANK", "import");
            return new JSONObject().put("bestanden", files).put("ok", ok);
        }
    }

    /** Na ontgrendelen: wat er nog wachtte (automatisch opgehaald) alsnog importeren. Geeft null als er niets wachtte. */
    static JSONObject wachtendeImporteren(Context c) {
        java.util.List<Bestand> l;
        synchronized (WACHT) {
            if (WACHT.isEmpty()) return null;
            if (System.currentTimeMillis() > wachtTot) { WACHT.clear(); return null; } // te lang gewacht: weg uit het geheugen
            l = new java.util.ArrayList<>(WACHT);
        }
        try {
            JSONObject r = importeer(c, l, null);
            synchronized (WACHT) { WACHT.removeAll(l); }
            return r;
        } catch (Throwable t) {
            try { return new JSONObject().put("error", why(t)); } catch (Exception e) { return null; }
        }
    }
}
