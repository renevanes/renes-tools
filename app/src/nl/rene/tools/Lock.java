package nl.rene.tools;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.SystemClock;

/**
 * App-slot: de app vergrendelt na een ingestelde tijd buiten beeld en wordt ontgrendeld met de
 * schermvergrendeling van de telefoon zelf (vingerafdruk, gezicht of pincode/patroon), of - als je die
 * instelt - met een eigen app-code (alleen een PBKDF2-hash wordt bewaard). Met app-code kan een verkeerde
 * code de neutrale versie openen (NeutralActivity) in plaats van een foutmelding.
 */
final class Lock {

    private Lock() { }

    static final int REQ_CONFIRM = 90; // niet 30: dat is REQ_PDF_SAVE (dan kwam "PDF opslaan" hier terecht)

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("lock", Context.MODE_PRIVATE); }

    static boolean enabled(Context c) { return prefs(c).getBoolean("on", false); }

    /** Tijd buiten beeld voordat de app weer op slot gaat (ms). */
    static long timeout(Context c) {
        SharedPreferences p = prefs(c);
        // Sinds 1.36 standaard "meteen": ook bij snel terugkomen in de app opnieuw de vingerafdruk (eenmalig omgezet).
        if (!p.getBoolean("t2", false)) p.edit().putBoolean("t2", true).putLong("timeout", 0L).apply();
        return p.getLong("timeout", 0L);
    }

    static boolean deviceSecure(Context c) {
        KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
        return km != null && (Build.VERSION.SDK_INT >= 23 ? km.isDeviceSecure() : km.isKeyguardSecure());
    }

    // Toestand (per proces)
    static volatile boolean unlocked = false;
    static volatile long hiddenAt = 0;       // elapsedRealtime toen de app uit beeld ging
    static volatile boolean authBusy = false; // tijdens de systeemvraag niet opnieuw vergrendelen

    /** Zonder schermvergrendeling én zonder app-code kan niet ontgrendeld worden: dan nooit op slot (anders zit je vast). */
    static boolean active(Context c) { return enabled(c) && (deviceSecure(c) || hasCode(c)); }

    // ---------- eigen app-code ----------

    static boolean hasCode(Context c) { return prefs(c).contains("codeHash"); }

    /** Bij een verkeerde code de neutrale versie tonen (anders een foutmelding). */
    static boolean decoy(Context c) { return hasCode(c) && prefs(c).getBoolean("decoy", false); }

    /** Vingerafdruk/gezicht opent ook de echte app (naast de code). */
    static boolean bioWithCode(Context c) { return prefs(c).getBoolean("codeBio", false); }

    static boolean validCode(String code) { return code != null && code.matches("[0-9]{4,12}"); }

    private static String alg() { return Build.VERSION.SDK_INT >= 26 ? "PBKDF2WithHmacSHA256" : "PBKDF2WithHmacSHA1"; }

    static byte[] hash(String code, byte[] salt, String alg, int rounds) throws Exception {
        javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(code.toCharArray(), salt, rounds, 256);
        try { return javax.crypto.SecretKeyFactory.getInstance(alg).generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }

    static void setCode(Context c, String code) throws Exception {
        if (!validCode(code)) throw new Exception("Gebruik 4 tot 12 cijfers");
        byte[] salt = new byte[16];
        new java.security.SecureRandom().nextBytes(salt);
        String alg = alg();
        int rounds = 120_000;
        byte[] h = hash(code, salt, alg, rounds);
        prefs(c).edit().putString("codeHash", android.util.Base64.encodeToString(h, android.util.Base64.NO_WRAP))
                .putString("codeSalt", android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP))
                .putString("codeAlg", alg).putInt("codeRounds", rounds).putInt("fails", 0).putLong("waitUntil", 0).apply();
    }

    static void clearCode(Context c) {
        setBio(c, false);
        prefs(c).edit().remove("codeHash").remove("codeSalt").remove("codeAlg").remove("codeRounds").remove("fails").remove("waitUntil").putBoolean("decoy", false).apply();
    }

    static boolean checkCode(Context c, String code) {
        if (!hasCode(c) || code == null || code.length() > 12) return false;
        try {
            SharedPreferences p = prefs(c);
            byte[] salt = android.util.Base64.decode(p.getString("codeSalt", ""), android.util.Base64.NO_WRAP);
            byte[] want = android.util.Base64.decode(p.getString("codeHash", ""), android.util.Base64.NO_WRAP);
            byte[] got = hash(code, salt, p.getString("codeAlg", alg()), p.getInt("codeRounds", 120_000));
            return java.security.MessageDigest.isEqual(want, got);
        } catch (Exception e) { return false; }
    }

    /** Resultaat van een poging: OK, WRONG (verkeerd), WAIT (te vaak fout: even wachten, ook de juiste code telt dan niet). */
    static final int OK = 0, WRONG = 1, WAIT = 2;

    /**
     * Een code proberen. Na 5 keer fout op rij telt een tijdje niets meer (30 s, daarna steeds langer, tot 1 uur),
     * ook de juiste code niet - zo is raden niet te doen, ook niet via de neutrale versie.
     */
    static synchronized int tryCode(Context c, String code) {
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis();
        if (waitSeconds(c) > 0) return WAIT;
        if (checkCode(c, code)) { p.edit().putInt("fails", 0).putLong("waitUntil", 0).putLong("waitElapsed", 0).putBoolean("waitServed", true).commit(); renewBio(c); return OK; }
        int fails = p.getInt("fails", 0) + 1;
        long wait = waitFor(fails);
        // Wachttijd op twee klokken: de gewone (overleeft herstarten) en de tijd sinds opstarten (klok verzetten helpt niet)
        p.edit().putInt("fails", fails).putLong("waitUntil", wait > 0 ? now + wait : 0)
                .putLong("waitElapsed", wait > 0 ? SystemClock.elapsedRealtime() + wait : 0).putInt("waitBoot", bootCount(c))
                .putBoolean("waitServed", wait == 0).putLong("lastTry", now).commit(); // meteen opslaan, ook als de app direct daarna dicht gaat
        return WRONG;
    }

    /** Wachttijd na zoveel keer fout op rij: vanaf 5 keer 30 s, steeds dubbel, hooguit een uur. */
    static long waitFor(int fails) { return fails >= 5 ? Math.min(3_600_000L, 30_000L << Math.min(7, fails - 5)) : 0; }

    private static int bootCount(Context c) {
        try { return android.provider.Settings.Global.getInt(c.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT); } catch (Exception e) { return -1; }
    }

    /**
     * Hoeveel seconden er nog gewacht moet worden (0 = mag). Telt op de tijd sinds opstarten; na herstarten begint
     * een nog niet uitgezeten wachttijd opnieuw (herstarten en de klok verzetten helpt dus niet). Nooit langer dan
     * de wachttijd zelf (een verkeerde datum na een lege accu sluit je niet dagen buiten).
     */
    static synchronized long waitSeconds(Context c) {
        SharedPreferences p = prefs(c);
        int fails = p.getInt("fails", 0);
        long cap = waitFor(fails);
        if (cap == 0 || p.getBoolean("waitServed", true)) return 0;
        long now = System.currentTimeMillis(), el = SystemClock.elapsedRealtime(), left;
        int boot = bootCount(c);
        if (boot >= 0 && p.getInt("waitBoot", -2) == boot) left = p.getLong("waitElapsed", 0) - el;
        else if (boot >= 0) {
            // Herstart sinds de laatste poging: de wachttijd opnieuw, op deze opstart
            p.edit().putInt("waitBoot", boot).putLong("waitElapsed", el + cap).putLong("waitUntil", now + cap).apply();
            left = cap;
        } else {
            left = p.getLong("waitUntil", 0) - now; // geen opstartteller: dan de gewone klok
            if (now < p.getLong("lastTry", 0) - 60_000L) left = Math.max(left, 30_000L); // klok teruggezet
        }
        left = Math.min(left, cap);
        if (left <= 0) { p.edit().putBoolean("waitServed", true).apply(); return 0; }
        return (left + 999) / 1000;
    }

    // ---------- vingerafdruk naast de app-code ----------
    // Gekoppeld aan een sleutel die Android ongeldig maakt zodra er een nieuwe vingerafdruk (of gezicht) bijkomt:
    // wie de pincode van de telefoon kent en zijn eigen vingerafdruk toevoegt, komt er zo niet in.

    static final String BIO_ALIAS = "rt-lockbio";

    static final String MSG_NEW_FINGER_CODE = "Er is een nieuwe vingerafdruk toegevoegd; ontgrendel één keer met je app-code";
    static final String MSG_NEW_FINGER_PIN = "Er is een nieuwe vingerafdruk toegevoegd; ontgrendel één keer met je pincode";

    /**
     * Aan- of uitzetten vanuit de instellingen. Geeft "" of een melding. Zolang de app op slot is kan dat niet:
     * de brug kan zo nooit zelf een extra manier van ontgrendelen toevoegen of weghalen.
     */
    static String enableBio(Context c, boolean on) {
        if (locked(c)) return "Ontgrendel eerst de app";
        return setBio(c, on);
    }

    /** Maakt (aan) of verwijdert (uit) de sleutel; er moet al een sterke vingerafdruk/gezicht zijn ingesteld. */
    private static String setBio(Context c, boolean on) {
        try {
            deleteKey(BIO_ALIAS);
            prefs(c).edit().remove("bioRenew").apply();
            if (on) {
                if (Build.VERSION.SDK_INT < 29) return "Kan pas vanaf Android 10";
                if (!strongBio(c)) return "Stel eerst een vingerafdruk in op je telefoon";
                makeKey(BIO_ALIAS);
            }
            prefs(c).edit().putBoolean("codeBio", on).apply();
            return "";
        } catch (Throwable e) { return "Vingerafdruk instellen lukt niet: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()); }
    }

    /** Is er een sterke vingerafdruk/gezicht ingesteld (Android 10+)? */
    private static boolean strongBio(Context c) {
        if (Build.VERSION.SDK_INT < 29) return false;
        android.hardware.biometrics.BiometricManager bm = c.getSystemService(android.hardware.biometrics.BiometricManager.class);
        if (bm == null) return false;
        int ok = Build.VERSION.SDK_INT >= 30 ? bm.canAuthenticate(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG) : bm.canAuthenticate();
        return ok == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS;
    }

    /** Na de juiste app-code: de vingerafdruksleutel opnieuw maken als een nieuwe vingerafdruk hem ongeldig maakte. */
    private static void renewBio(Context c) {
        if (!prefs(c).getBoolean("bioRenew", false)) return;
        if (!setBio(c, true).isEmpty()) prefs(c).edit().putBoolean("codeBio", false).remove("bioRenew").apply();
    }

    /** Alleen een sterke vingerafdruk/gezicht met de gekoppelde sleutel (geen pincode van de telefoon). */
    static void promptBio(Activity a, Result r) {
        if (authBusy) return;
        if (Build.VERSION.SDK_INT < 29) { r.done(false, "Gebruik je app-code"); return; }
        if (prefs(a).getBoolean("bioRenew", false)) { r.done(false, MSG_NEW_FINGER_CODE); return; }
        final javax.crypto.Cipher ci;
        try {
            javax.crypto.SecretKey k = key(BIO_ALIAS);
            if (k == null) { r.done(false, "Vingerafdruk staat uit; gebruik je app-code"); return; }
            ci = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(javax.crypto.Cipher.ENCRYPT_MODE, k);
        } catch (android.security.keystore.KeyPermanentlyInvalidatedException e) {
            // Er kwam een vingerafdruk bij: sleutel weg; na één keer de juiste app-code komt er vanzelf een nieuwe
            try { deleteKey(BIO_ALIAS); } catch (Throwable ignored) { }
            prefs(a).edit().putBoolean("bioRenew", true).apply();
            r.done(false, MSG_NEW_FINGER_CODE);
            return;
        } catch (Throwable e) { r.done(false, "Vingerafdruk lukt niet; gebruik je app-code"); return; }
        authBusy = true;
        try {
            BiometricPrompt.Builder b = new BiometricPrompt.Builder(a).setTitle("Tools ontgrendelen").setSubtitle("Vingerafdruk")
                    .setNegativeButton("App-code", a.getMainExecutor(), (d, w) -> { authBusy = false; r.done(false, null); });
            if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG);
            b.build().authenticate(new BiometricPrompt.CryptoObject(ci), new CancellationSignal(), a.getMainExecutor(), new BioCallback(r));
        } catch (Throwable e) {
            authBusy = false;
            r.done(false, "Vingerafdruk lukt niet; gebruik je app-code");
        }
    }

    /** Pas geslaagd als de sleutel echt bruikbaar is geworden (bewijst dat het een ingeschreven, sterke vingerafdruk was). */
    static final class BioCallback extends BiometricPrompt.AuthenticationCallback {
        private final Result r;
        BioCallback(Result r) { this.r = r; }
        @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult res) {
            authBusy = false;
            if (proves(res == null ? null : res.getCryptoObject())) r.done(true, null);
            else r.done(false, "Vingerafdruk niet bevestigd; gebruik je app-code");
        }
        @Override public void onAuthenticationError(int code, CharSequence msg) { authBusy = false; r.done(false, code == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED || code == BiometricPrompt.BIOMETRIC_ERROR_CANCELED ? null : msg == null ? null : msg.toString()); }
    }

    // ---------- sleutels in de AndroidKeyStore ----------
    // Ontgrendelen telt pas als een sleutel die alleen na echt ontgrendelen werkt, een vaste uitdaging kan versleutelen.

    /** Android 11+: werkt bij elk gebruik alleen na een sterke vingerafdruk/gezicht of de pincode van de telefoon. */
    static final String DEV_ALIAS = "rt-lockdev";
    /** Android 7–10 (en als terugvaloptie): werkt tot CONFIRM_SECONDS na het bevestigingsscherm van de telefoon. */
    static final String CONFIRM_ALIAS = "rt-lockconfirm";
    static final int CONFIRM_SECONDS = 15;

    private static java.security.KeyStore keyStore() throws Exception {
        java.security.KeyStore ks = java.security.KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        return ks;
    }

    private static javax.crypto.SecretKey key(String alias) throws Exception {
        return (javax.crypto.SecretKey) keyStore().getKey(alias, null);
    }

    private static void deleteKey(String alias) throws Exception {
        java.security.KeyStore ks = keyStore();
        if (ks.containsAlias(alias)) ks.deleteEntry(alias);
    }

    /**
     * Nieuwe AES-sleutel die alleen na ontgrendelen werkt. BIO_ALIAS en DEV_ALIAS vragen bij elk gebruik en worden
     * ongeldig zodra er een vingerafdruk bijkomt; CONFIRM_ALIAS werkt kort na het ontgrendelen.
     */
    private static javax.crypto.SecretKey makeKey(String alias) throws Exception {
        deleteKey(alias);
        javax.crypto.KeyGenerator g = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        android.security.keystore.KeyGenParameterSpec.Builder spec = new android.security.keystore.KeyGenParameterSpec.Builder(alias,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true);
        int strong = android.security.keystore.KeyProperties.AUTH_BIOMETRIC_STRONG;
        int cred = android.security.keystore.KeyProperties.AUTH_DEVICE_CREDENTIAL;
        if (CONFIRM_ALIAS.equals(alias)) {
            if (Build.VERSION.SDK_INT >= 30) spec.setUserAuthenticationParameters(CONFIRM_SECONDS, strong | cred);
            else spec.setUserAuthenticationValidityDurationSeconds(CONFIRM_SECONDS);
        } else {
            spec.setInvalidatedByBiometricEnrollment(true);
            if (Build.VERSION.SDK_INT >= 30) spec.setUserAuthenticationParameters(0, DEV_ALIAS.equals(alias) ? strong | cred : strong);
        }
        g.init(spec.build());
        return g.generateKey();
    }

    /** Vaste uitdaging die na ontgrendelen versleuteld moet kunnen worden. */
    static final byte[] CHALLENGE = "Tools app-slot: ontgrendeld".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private static boolean proves(BiometricPrompt.CryptoObject co) { return co != null && proves(co.getCipher()); }

    /**
     * Bewijst deze versleutelaar dat er echt ontgrendeld is? Alleen als hij de uitdaging kan versleutelen (een
     * AndroidKeyStore-sleutel met gebruikersbevestiging weigert dat anders) en er versleutelde tekst met GCM-tag uitkomt.
     */
    static boolean proves(javax.crypto.Cipher ci) {
        if (ci == null) return false;
        try {
            byte[] out = ci.doFinal(CHALLENGE);
            return out != null && out.length >= CHALLENGE.length + 12;
        } catch (Throwable e) { return false; }
    }

    /** Hoe de schermvergrendeling gevraagd wordt: systeemvraag met gekoppelde sleutel (Android 11+) of het bevestigingsscherm. */
    static final int VIA_PROMPT = 0, VIA_CONFIRM = 1;

    static int via(int sdk, boolean cryptoFailed) { return sdk >= 30 && !cryptoFailed ? VIA_PROMPT : VIA_CONFIRM; }

    /** De systeemvraag met sleutel werkte op deze telefoon niet: de rest van deze keer het bevestigingsscherm (nooit buitensluiten). */
    static volatile boolean cryptoFailed = false;

    static boolean locked(Context c) { return active(c) && !unlocked; }

    /** Zou de app nu op slot gaan als hij in beeld kwam (ook als de tijd buiten beeld verstreken is)? Verandert niets. */
    static boolean wouldLock(Context c) {
        if (!active(c)) return false;
        if (!unlocked) return true;
        return hiddenAt > 0 && SystemClock.elapsedRealtime() - hiddenAt >= timeout(c);
    }

    /** De app opent zelf een scherm (mappenkiezer, instellingen, delen ...): dat telt niet als "weg". */
    static volatile boolean internalNav = false;

    /** App gaat uit beeld. */
    static void onHidden() { if (!authBusy && !internalNav && unlocked) hiddenAt = SystemClock.elapsedRealtime(); }

    /** App komt weer in beeld: vergrendelen als hij te lang weg was. Geeft true als hij nu op slot is. */
    static boolean onShown(Context c) {
        internalNav = false;
        if (!active(c)) return false;
        if (authBusy) return !unlocked;
        if (unlocked && hiddenAt > 0 && SystemClock.elapsedRealtime() - hiddenAt >= timeout(c)) unlocked = false;
        hiddenAt = 0;
        return !unlocked;
    }


    interface Result { void done(boolean ok, String msg); }

    /** Systeemvraag met gekoppelde sleutel (Android 11+): pas gelukt als de sleutel daarna echt de uitdaging versleutelt. */
    static final class Callback extends BiometricPrompt.AuthenticationCallback {
        private final Result r;
        Callback(Result r) { this.r = r; }
        @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult res) {
            authBusy = false;
            BiometricPrompt.CryptoObject co = res == null ? null : res.getCryptoObject();
            if (proves(co == null ? null : co.getCipher())) { r.done(true, null); return; }
            // Systeem zegt "gelukt" maar de sleutel niet: niet open; volgende keer het bevestigingsscherm van de telefoon
            try { deleteKey(DEV_ALIAS); } catch (Throwable ignored) { }
            cryptoFailed = true;
            r.done(false, "Ontgrendelen niet bevestigd; probeer het nog eens");
        }
        @Override public void onAuthenticationError(int code, CharSequence msg) {
            authBusy = false;
            // Werkt de systeemvraag hier niet (geen sensor, sleutelfout ...)? Dan volgende keer het bevestigingsscherm.
            if (code != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED && code != BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                    && code != BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT) cryptoFailed = true;
            r.done(false, msg == null ? null : msg.toString());
        }
    }

    /** Vraagt de schermvergrendeling van de telefoon (vingerafdruk/gezicht of pincode/patroon). */
    static void prompt(Activity a, String title, Result r) {
        if (authBusy) return; // er staat al een vraag open
        if (!deviceSecure(a)) { r.done(false, "Stel eerst een schermvergrendeling in op je telefoon (pincode, patroon of vingerafdruk)"); return; }
        if (via(Build.VERSION.SDK_INT, cryptoFailed) == VIA_PROMPT && promptCrypto(a, title, r)) return;
        cryptoFailed = false; // één keer het bevestigingsscherm, daarna weer de systeemvraag proberen
        promptConfirm(a, title, r);
    }

    /** Android 11+: BiometricPrompt met CryptoObject. Geeft false als dat hier niet kan (dan het bevestigingsscherm). */
    private static boolean promptCrypto(Activity a, String title, Result r) {
        if (Build.VERSION.SDK_INT < 30) return false;
        int how = android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
                | android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL;
        String note = null;
        javax.crypto.Cipher ci;
        try {
            try { ci = devCipher(); }
            catch (android.security.keystore.KeyPermanentlyInvalidatedException e) {
                // Er kwam een vingerafdruk bij: nieuwe sleutel, en deze ene keer alleen de pincode van de telefoon
                deleteKey(DEV_ALIAS);
                ci = devCipher();
                note = MSG_NEW_FINGER_PIN;
                how = android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL;
            }
        } catch (Throwable e) { return false; }
        authBusy = true;
        try {
            BiometricPrompt.Builder b = new BiometricPrompt.Builder(a).setTitle(title).setSubtitle("Tools").setAllowedAuthenticators(how);
            if (note != null) b.setDescription(note);
            b.build().authenticate(new BiometricPrompt.CryptoObject(ci), new CancellationSignal(), a.getMainExecutor(), new Callback(r));
            return true;
        } catch (Throwable e) {
            authBusy = false;
            return false;
        }
    }

    private static javax.crypto.Cipher devCipher() throws Exception {
        javax.crypto.SecretKey k = key(DEV_ALIAS);
        if (k == null) k = makeKey(DEV_ALIAS);
        javax.crypto.Cipher ci = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        ci.init(javax.crypto.Cipher.ENCRYPT_MODE, k);
        return ci;
    }

    /** Het bevestigingsscherm van de telefoon (Android 7–10, of als de systeemvraag niet werkt); daarna de sleutel controleren. */
    private static void promptConfirm(Activity a, String title, Result r) {
        KeyguardManager km = (KeyguardManager) a.getSystemService(Context.KEYGUARD_SERVICE);
        Intent i = km == null ? null : km.createConfirmDeviceCredentialIntent(title, "Tools");
        if (i == null) { r.done(false, "Geen schermvergrendeling ingesteld"); return; }
        boolean withKey;
        try { makeKey(CONFIRM_ALIAS); withKey = true; } // elke keer een verse sleutel
        catch (Throwable e) { withKey = false; }        // sleutelopslag werkt hier niet: dan geldt het antwoord van de telefoon
        pendingKey = withKey;
        authBusy = true;
        pending = r;
        try { a.startActivityForResult(i, REQ_CONFIRM); }
        catch (Throwable e) { authBusy = false; pending = null; r.done(false, "Ontgrendelen lukt niet"); }
    }

    static Result pending;
    private static volatile boolean pendingKey = false;

    /** Resultaat van het bevestigingsscherm: alleen gelukt als de korte sleutel nu ook echt werkt. */
    static void onConfirmResult(boolean ok) {
        authBusy = false;
        Result r = pending;
        pending = null;
        if (r == null) return; // geen vraag van ons open: niets ontgrendelen
        if (ok && pendingKey) ok = confirmVerified();
        pendingKey = false;
        r.done(ok, ok ? null : "Niet ontgrendeld");
    }

    /**
     * Werkt de korte sleutel (is er zojuist echt ontgrendeld)? "Niet ontgrendeld" van de sleutelopslag telt als nee;
     * een sleutelopslag die op deze telefoon zelf stuk is, sluit je niet buiten (dan geldt het antwoord van Android).
     */
    private static boolean confirmVerified() {
        javax.crypto.Cipher ci;
        try {
            javax.crypto.SecretKey k = key(CONFIRM_ALIAS);
            if (k == null) return false;
            ci = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(javax.crypto.Cipher.ENCRYPT_MODE, k);
        } catch (android.security.keystore.UserNotAuthenticatedException e) {
            return false;
        } catch (android.security.keystore.KeyPermanentlyInvalidatedException e) {
            return false; // schermvergrendeling net gewijzigd: de volgende poging maakt een nieuwe sleutel
        } catch (Throwable e) {
            return true;
        }
        return proves(ci);
    }
}
