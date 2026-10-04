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
 * schermvergrendeling van de telefoon zelf (vingerafdruk, gezicht of pincode/patroon). Er wordt
 * dus geen eigen pincode opgeslagen en "pincode vergeten" bestaat niet.
 */
final class Lock {

    private Lock() { }

    static final int REQ_CONFIRM = 30;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("lock", Context.MODE_PRIVATE); }

    static boolean enabled(Context c) { return prefs(c).getBoolean("on", false); }

    /** Tijd buiten beeld voordat de app weer op slot gaat (ms). */
    static long timeout(Context c) { return prefs(c).getLong("timeout", 60_000L); }

    static boolean deviceSecure(Context c) {
        KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
        return km != null && (Build.VERSION.SDK_INT >= 23 ? km.isDeviceSecure() : km.isKeyguardSecure());
    }

    // Toestand (per proces)
    static volatile boolean unlocked = false;
    static volatile long hiddenAt = 0;       // elapsedRealtime toen de app uit beeld ging
    static volatile boolean authBusy = false; // tijdens de systeemvraag niet opnieuw vergrendelen

    /** Zonder schermvergrendeling op de telefoon kan niet ontgrendeld worden: dan nooit op slot (anders zit je vast). */
    static boolean active(Context c) { return enabled(c) && deviceSecure(c); }

    static boolean locked(Context c) { return active(c) && !unlocked; }

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
