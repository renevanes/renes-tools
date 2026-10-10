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

    static final int REQ_CONFIRM = 30;

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
        enableBio(c, false);
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
        if (checkCode(c, code)) { p.edit().putInt("fails", 0).putLong("waitUntil", 0).putLong("waitElapsed", 0).putBoolean("waitServed", true).commit(); return OK; }
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

    /** Aanzetten: maakt de sleutel (er moet al een sterke vingerafdruk/gezicht zijn ingesteld). Geeft "" of een melding. */
    static String enableBio(Context c, boolean on) {
        try {
            java.security.KeyStore ks = java.security.KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(BIO_ALIAS)) ks.deleteEntry(BIO_ALIAS);
            if (on) {
                if (Build.VERSION.SDK_INT < 29) return "Kan pas vanaf Android 10";
                android.hardware.biometrics.BiometricManager bm = c.getSystemService(android.hardware.biometrics.BiometricManager.class);
                int ok = Build.VERSION.SDK_INT >= 30 ? bm.canAuthenticate(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG) : bm.canAuthenticate();
                if (ok != android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS) return "Stel eerst een vingerafdruk in op je telefoon";
                javax.crypto.KeyGenerator g = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                android.security.keystore.KeyGenParameterSpec.Builder spec = new android.security.keystore.KeyGenParameterSpec.Builder(BIO_ALIAS,
                        android.security.keystore.KeyProperties.PURPOSE_ENCRYPT | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(true);
                if (Build.VERSION.SDK_INT >= 30) spec.setUserAuthenticationParameters(0, android.security.keystore.KeyProperties.AUTH_BIOMETRIC_STRONG);
                g.init(spec.build());
                g.generateKey();
            }
            prefs(c).edit().putBoolean("codeBio", on).apply();
            return "";
        } catch (Exception e) { return "Vingerafdruk instellen lukt niet: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()); }
    }

    /** Alleen een sterke vingerafdruk/gezicht met de gekoppelde sleutel (geen pincode van de telefoon). */
    static void promptBio(Activity a, Result r) {
        if (authBusy) return;
        if (Build.VERSION.SDK_INT < 29) { r.done(false, "Gebruik je app-code"); return; }
        final javax.crypto.Cipher ci;
        try {
            java.security.KeyStore ks = java.security.KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            javax.crypto.SecretKey k = (javax.crypto.SecretKey) ks.getKey(BIO_ALIAS, null);
            if (k == null) { r.done(false, "Vingerafdruk staat uit; gebruik je app-code"); return; }
            ci = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(javax.crypto.Cipher.ENCRYPT_MODE, k);
        } catch (android.security.keystore.KeyPermanentlyInvalidatedException e) {
            enableBio(a, false); // er kwam een vingerafdruk bij: uit, tot je hem in de instellingen weer aanzet
            r.done(false, "Er is een vingerafdruk of gezicht bijgekomen op deze telefoon. Gebruik je app-code (en zet vingerafdruk daarna weer aan in Instellingen).");
            return;
        } catch (Exception e) { r.done(false, "Vingerafdruk lukt niet; gebruik je app-code"); return; }
        authBusy = true;
        try {
            BiometricPrompt.Builder b = new BiometricPrompt.Builder(a).setTitle("Rene's Tools ontgrendelen").setSubtitle("Vingerafdruk")
                    .setNegativeButton("App-code", a.getMainExecutor(), (d, w) -> { authBusy = false; r.done(false, null); });
            if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG);
            b.build().authenticate(new BiometricPrompt.CryptoObject(ci), new CancellationSignal(), a.getMainExecutor(), new BioCallback(r));
        } catch (Exception e) {
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
            try {
                BiometricPrompt.CryptoObject co = res.getCryptoObject();
                if (co == null || co.getCipher() == null) throw new Exception();
                co.getCipher().doFinal(new byte[16]);
                r.done(true, null);
            } catch (Exception e) { r.done(false, "Vingerafdruk niet bevestigd; gebruik je app-code"); }
        }
        @Override public void onAuthenticationError(int code, CharSequence msg) { authBusy = false; r.done(false, code == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED || code == BiometricPrompt.BIOMETRIC_ERROR_CANCELED ? null : msg == null ? null : msg.toString()); }
    }

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

    static final class Callback extends BiometricPrompt.AuthenticationCallback {
        private final Result r;
        Callback(Result r) { this.r = r; }
        @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult res) { authBusy = false; r.done(true, null); }
        @Override public void onAuthenticationError(int code, CharSequence msg) { authBusy = false; r.done(false, msg == null ? null : msg.toString()); }
    }

    /** Vraagt de schermvergrendeling van de telefoon. */
    static void prompt(Activity a, String title, Result r) {
        if (authBusy) return; // er staat al een vraag open
        if (!deviceSecure(a)) { r.done(false, "Stel eerst een schermvergrendeling in op je telefoon (pincode, patroon of vingerafdruk)"); return; }
        authBusy = true;
        if (Build.VERSION.SDK_INT >= 29) {
            BiometricPrompt.Builder b = new BiometricPrompt.Builder(a).setTitle(title).setSubtitle("Rene's Tools");
            if (Build.VERSION.SDK_INT >= 30)
                b.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK
                        | android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL);
            else b.setDeviceCredentialAllowed(true);
            try {
                b.build().authenticate(new CancellationSignal(), a.getMainExecutor(), new Callback(r));
            } catch (Exception e) {
                authBusy = false;
                r.done(false, "Ontgrendelen lukt niet: " + e.getMessage());
            }
        } else {
            // Android 7–9: het bevestigingsscherm van de telefoon
            KeyguardManager km = (KeyguardManager) a.getSystemService(Context.KEYGUARD_SERVICE);
            Intent i = km.createConfirmDeviceCredentialIntent(title, "Rene's Tools");
            if (i == null) { authBusy = false; r.done(false, "Geen schermvergrendeling ingesteld"); return; }
            pending = r;
            a.startActivityForResult(i, REQ_CONFIRM);
        }
    }

    static Result pending;

    /** Resultaat van het bevestigingsscherm (Android 7–9). */
    static void onConfirmResult(boolean ok) {
        authBusy = false;
        Result r = pending;
        pending = null;
        if (r != null) r.done(ok, ok ? null : "Niet ontgrendeld");
    }
}
