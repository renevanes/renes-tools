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

        check(Lock.MSG_SECURITY_CHANGED.contains("beveiliging") && Lock.MSG_SECURITY_CHANGED.contains("pincode"), "melding bij gewijzigde beveiliging");
        check(Lock.MSG_NEW_FINGER_CODE.contains("nieuwe vingerafdruk") && Lock.MSG_NEW_FINGER_CODE.contains("app-code"), "melding bij nieuwe vingerafdruk (app-code)");

        // Noodklep: Android zegt "gelukt", de sleutel zegt nee
        check(!Lock.confirmAccepted(false, true, false, 5), "Android zegt nee: nooit open, ook niet met noodklep");
        check(Lock.confirmAccepted(true, true, true, 0), "Android en sleutel zeggen ja: open");
        check(Lock.confirmAccepted(true, false, false, 0), "sleutelopslag werkt niet: het antwoord van Android geldt");
        check(!Lock.confirmAccepted(true, true, false, 0), "eerste keer oneens: niet open");
        check(!Lock.confirmAccepted(true, true, false, 1), "tweede keer oneens: niet open");
        check(Lock.confirmAccepted(true, true, false, Lock.VALVE), "na twee keer oneens op rij: bevestigingsscherm telt (niet buitensluiten)");
        int n = 0;
        n = Lock.nextMismatches(true, true, false, n);   // systeemvraag: oneens
        check(n == 1, "teller +1 na oneens");
        n = Lock.nextMismatches(false, true, false, n);  // afgebroken: telt niet mee
        check(n == 1, "afbreken verandert de teller niet");
        n = Lock.nextMismatches(true, true, false, n);   // bevestigingsscherm: oneens
        check(n == 2 && Lock.confirmAccepted(true, true, false, n), "crypto + bevestigingsscherm samen: derde poging gaat open");
        n = Lock.nextMismatches(true, true, true, n);
        check(n == 0, "door de sleutel bevestigd: teller weer 0");
        check(Lock.nextMismatches(true, false, false, 3) == 3, "zonder sleutelcontrole verandert de teller niet");

        // Een open vraag waarvan het antwoord verloren ging
        check(!Lock.busyStale(false, 0, 1_000_000), "geen vraag open: niets oud");
        check(!Lock.busyStale(true, 1_000_000, 1_000_000 + 30_000), "vraag van 30 s: nog open");
        check(Lock.busyStale(true, 1_000_000, 1_000_000 + Lock.BUSY_STALE_MS), "vraag van 90 s: vergeten");
        System.out.println(fails == 0 ? "Alle slot-tests geslaagd" : fails + " slot-tests mislukt");
        System.exit(fails == 0 ? 0 : 1);
    }
}
