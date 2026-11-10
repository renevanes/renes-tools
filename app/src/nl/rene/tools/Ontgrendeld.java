package nl.rene.tools;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.security.keystore.UserNotAuthenticatedException;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Extra ontgrendelen uit bij Bankzaken en Stamgegevens: hun sleutel werkt (door de hardware afgedwongen) alleen tot
 * een vast aantal seconden na het ontgrendelen van de telefoon met pincode, patroon, wachtwoord of een sterke
 * vingerafdruk. Hoe lang dat nog is, zegt Android niet. Proefsleutels die korter werken geven een ondergrens: werkt de
 * proef van 60 seconden, dan was de laatste ontgrendeling hooguit 60 seconden geleden en werkt de echte sleutel nog
 * minstens (seconden − 60). De tool opent dan zonder te vragen en gaat op slot vóór zijn sleutel ophoudt; is er geen
 * zekere tijd, dan vraagt hij het wel. De proefsleutels versleutelen niets.
 */
final class Ontgrendeld {

    private Ontgrendeld() { }

    static final int[] PROEF = {60, 180, 300, 600};
    /** Zo veel eerder dan de sleutel gaat de sessie dicht. */
    static final int MARGE = 30;

    interface Proef { boolean werkt(int seconden); }

    /** Zo veel ms werkt een sleutel van {@code seconden} nog zeker; 0 = niet zeker (dan zelf vragen). */
    static long zeker(int seconden, Proef proef) {
        for (int p : PROEF) {
            if (p + MARGE >= seconden) break;
            if (proef.werkt(p)) return (seconden - p - MARGE) * 1000L;
        }
        return 0;
    }

    static long zeker(String naam, int seconden) { return zeker(seconden, p -> werkt(alias(naam, p), p)); }

    static String alias(String naam, int p) { return naam + "_proef_" + p; }

    private static boolean werkt(String alias, int seconden) {
        for (int poging = 0; poging < 2; poging++) {
            try {
                KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
                ks.load(null);
                SecretKey k = ks.containsAlias(alias) ? ((KeyStore.SecretKeyEntry) ks.getEntry(alias, null)).getSecretKey() : maak(alias, seconden);
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, k);
                c.doFinal(new byte[]{1});
                return true;
            } catch (UserNotAuthenticatedException e) {
                return false;
            } catch (KeyPermanentlyInvalidatedException e) {
                weg(alias); // de schermvergrendeling is gewijzigd: een nieuwe proef
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private static SecretKey maak(String alias, int seconden) throws Exception {
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(false);
        if (Build.VERSION.SDK_INT >= 30) b.setUserAuthenticationParameters(seconden, KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL);
        else b.setUserAuthenticationValidityDurationSeconds(seconden);
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(b.build());
        return g.generateKey();
    }

    private static void weg(String alias) {
        try { KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null); if (ks.containsAlias(alias)) ks.deleteEntry(alias); } catch (Throwable ignored) { }
    }

    /** Bij alles wissen: ook de proefsleutels weg. */
    static void wis(String naam) { for (int p : PROEF) weg(alias(naam, p)); }
}
