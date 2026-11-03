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
 * - Gaat nergens mee: niet in de Android-backup, niet in Alles back-uppen of "Alle appgegevens", niet in zoeken of de
 *   assistent, niet in Statistieken. De app maakt geen verbinding naar buiten voor Bankzaken (de interface mag dat sowieso
 *   niet: connect-src 'none'); de AI draait op de telefoon zelf.
 * - Alles wissen = ook de sleutel weg: wat er eventueel nog op de opslag zou staan, is dan voorgoed onleesbaar.
 */
final class Bank {

    private Bank() { }

    static final String ALIAS = "renes_bankzaken";
    static final int KEY_SECONDS = 15 * 60;
    static final long IDLE_MS = 5 * 60_000L, MAX_MS = KEY_SECONDS * 1000L - 30_000L;

    private static final Object LOCK = new Object();
    /** Open tot dit moment (5 min na het laatste gebruik), en hooguit tot maxUntil (de sleutel verloopt dan). */
    static volatile long openUntil = 0, maxUntil = 0;
    /** De AI is gebruikt met bankgegevens: bij op slot gaan het taalmodel stoppen (dan staat er niets meer in zijn geheugen). */
    static volatile boolean aiUsed = false;
    /** Bestanden kiezen loopt (de app gaat dan even uit beeld): niet op slot doen. */
    static volatile boolean picking = false;

    static boolean isOpen() { long n = System.currentTimeMillis(); return n < openUntil && n < maxUntil; }
    static long remaining() { long n = System.currentTimeMillis(); return Math.max(0, Math.min(openUntil, maxUntil) - n); }

    static void open() { long n = System.currentTimeMillis(); maxUntil = n + MAX_MS; openUntil = n + IDLE_MS; }
    static void touch() { if (isOpen()) openUntil = System.currentTimeMillis() + IDLE_MS; }

    static void close() {
        openUntil = 0; maxUntil = 0;
        if (aiUsed) {
            aiUsed = false;
            try { LocalAi.cancel(); } catch (Throwable ignored) { }
            try { LocalAi.closeChat(); } catch (Throwable ignored) { }
        }
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
        try {
            SecretKey k = generate(true);
            BankCrypt.decrypt(BankCrypt.encrypt(new byte[]{1}, k), k); // werkt hij echt na deze ontgrendeling?
            writeMeta(c, true);
            return k;
        } catch (Throwable t) {
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
            return new String(BankCrypt.decrypt(blob, k), StandardCharsets.UTF_8);
        }
    }

    /** Versleuteld opslaan: eerst volledig schrijven en teruglezen, pas dan het oude bestand vervangen. */
    static void save(Context c, String json) throws Exception {
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
            close();
        }
    }

    /** Toestand voor het scherm, zonder iets te ontsleutelen. */
    static JSONObject state(Context c) throws Exception {
        JSONObject m = metaJson(c);
        boolean key = hasKey();
        return new JSONObject().put("open", isOpen()).put("left", remaining()).put("data", hasData(c))
                .put("sleutel", key).put("kwijt", hasData(c) && !key)
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
}
