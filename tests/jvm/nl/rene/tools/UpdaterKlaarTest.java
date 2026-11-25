package nl.rene.tools;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Een gedownloade nieuwe app blijft bewaard tot hij geïnstalleerd is (Updater.klaar): wordt de installatie afgebroken,
 * dan installeert Installeren de bewaarde download zonder opnieuw te downloaden. Alleen een beschrijving die bij een
 * nieuwere app hoort en precies bij het bestand past telt; het bestand gaat nooit mee in een backup.
 */
public class UpdaterKlaarTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    public static void main(String[] a) throws Exception {
        String sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        JSONObject m = new JSONObject().put("versionCode", 300).put("versionName", "1.300").put("nativeLevel", 230).put("sha256", sha).put("bytes", 5000L);
        check(Updater.klaarGeldig(m, 5000, 219), "nieuwere app, grootte klopt: klaar om te installeren");
        check(!Updater.klaarGeldig(m, 5000, 230) && !Updater.klaarGeldig(m, 5000, 231), "al geïnstalleerd (of nieuwer): niet meer klaar");
        check(!Updater.klaarGeldig(m, 4999, 219) && !Updater.klaarGeldig(m, 0, 219), "grootte klopt niet: niet gebruiken");
        check(!Updater.klaarGeldig(new JSONObject(m.toString()).put("sha256", "abc"), 5000, 219), "geen geldig controlegetal: niet gebruiken");
        check(!Updater.klaarGeldig(new JSONObject(m.toString()).put("versionName", "<b>1</b>"), 5000, 219), "vreemde versienaam: niet gebruiken");
        check(!Updater.klaarGeldig(new JSONObject(m.toString()).put("bytes", Updater.MAX_APK + 1), Updater.MAX_APK + 1, 219), "te groot: niet gebruiken");
        check(!Updater.klaarGeldig(null, 5000, 219), "geen beschrijving: niet gebruiken");

        // Controlegetal: dezelfde schrijfwijze als bij het downloaden
        check(Updater.hex(java.security.MessageDigest.getInstance("SHA-256").digest("abc".getBytes(StandardCharsets.US_ASCII)))
                .equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), "SHA-256 in kleine letters");

        // Nooit in een backup (ook niet die van Android) en nooit uit een backup terug: de bewaarde download staat in de
        // map die Android niet meeneemt, en niet in getFilesDir() (waar onze eigen backups kijken)
        String bron = new String(Files.readAllBytes(new File("app/src/nl/rene/tools/Updater.java").toPath()), StandardCharsets.UTF_8);
        check(bron.contains("static File klaarDir(Context c) { return new File(c.getNoBackupFilesDir(), KLAAR_DIR); }"), "download in getNoBackupFilesDir");
        check(!AppData.FILE_KNOWN.contains(Updater.KLAAR_DIR) && !AppData.FILE_SKIP.containsKey(Updater.KLAAR_DIR), "niet in de lijsten van getFilesDir()");
        // Zonder verbinding alleen bij echt geen antwoord (IOException), niet bij een fout antwoord
        check(bron.contains("catch (java.io.IOException offline)"), "offline alleen bij geen verbinding");

        if (fails > 0) { System.out.println(fails + " fout(en)"); System.exit(1); }
        System.out.println("UpdaterKlaarTest ok");
    }
}
