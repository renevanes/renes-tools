package nl.rene.tools;

/**
 * App-slot: ontgrendelen telt alleen als de versleutelaar de uitdaging echt kan versleutelen, en de keuze tussen
 * systeemvraag met sleutel (Android 11+) en het bevestigingsscherm (Android 7–10, of als terugvaloptie).
 * (De AndroidKeyStore zelf bestaat niet buiten Android; hier een gewone AES-sleutel als stand-in.)
 */
public class LockCryptoTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }
    public static void main(String[] a) throws Exception {
        check(!Lock.proves((javax.crypto.Cipher) null), "geen versleutelaar: niet ontgrendeld");
        javax.crypto.Cipher fresh = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        check(!Lock.proves(fresh), "versleutelaar zonder sleutel: niet ontgrendeld");
        javax.crypto.KeyGenerator g = javax.crypto.KeyGenerator.getInstance("AES");
        g.init(256);
        javax.crypto.Cipher ok = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        ok.init(javax.crypto.Cipher.ENCRYPT_MODE, g.generateKey());
        check(Lock.proves(ok), "bruikbare sleutel: ontgrendeld");
        javax.crypto.Cipher dec = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        dec.init(javax.crypto.Cipher.DECRYPT_MODE, g.generateKey(), new javax.crypto.spec.GCMParameterSpec(128, new byte[12]));
        check(!Lock.proves(dec), "ontsleutelen zonder geldige tag: niet ontgrendeld");

        check(Lock.via(36, false) == Lock.VIA_PROMPT, "Android 16: systeemvraag met sleutel");
        check(Lock.via(30, false) == Lock.VIA_PROMPT, "Android 11: systeemvraag met sleutel");
        check(Lock.via(29, false) == Lock.VIA_CONFIRM, "Android 10: bevestigingsscherm");
        check(Lock.via(24, false) == Lock.VIA_CONFIRM, "Android 7: bevestigingsscherm");
        check(Lock.via(34, true) == Lock.VIA_CONFIRM, "systeemvraag werkte niet: terugval op het bevestigingsscherm");

        check(Lock.MSG_NEW_FINGER_PIN.contains("nieuwe vingerafdruk") && Lock.MSG_NEW_FINGER_PIN.contains("pincode"), "melding bij nieuwe vingerafdruk");
        System.out.println(fails == 0 ? "Alle slot-tests geslaagd" : fails + " slot-tests mislukt");
        System.exit(fails == 0 ? 0 : 1);
    }
}
