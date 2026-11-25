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

        // Controlegetal van een bestand (zoals bij het installeren opnieuw gecontroleerd)
        File f = File.createTempFile("klaar", ".apk");
        try {
            Files.write(f.toPath(), "abc".getBytes(StandardCharsets.US_ASCII));
            check(Updater.sha256(f).equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), "SHA-256 van een bestand");
            check(Updater.sha256(f).equals(Updater.sha256("abc".getBytes(StandardCharsets.US_ASCII))), "zelfde als van de bytes");
        } finally { f.delete(); }

        // Nooit in een backup en nooit uit een backup terugzetten (dan zou een backup een app kunnen klaarzetten)
        check(AppData.FILE_SKIP.containsKey(Updater.KLAAR_DIR), "map van de bewaarde download staat in FILE_SKIP");

        if (fails > 0) { System.out.println(fails + " fout(en)"); System.exit(1); }
        System.out.println("UpdaterKlaarTest ok");
    }
}
