package nl.rene.tools;

/** Logboek van het inlogvenster van Nieuws: adressen zonder inlogcodes, geen e-mailadressen of sleutels, begrensd. */
public class NieuwsDiagnoseTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    public static void main(String[] a) {
        check(NieuwsDiagnose.schoon("https://www.aireport.nl/login?email=rene%40x.nl&token=abc#x").equals("https://www.aireport.nl/login?…#…"), "vraag en anker weg");
        check(NieuwsDiagnose.schoon("https://user:pw@www.aireport.nl/p/een").equals("https://www.aireport.nl/p/een"), "gebruiker en wachtwoord in het adres weg");
        check(NieuwsDiagnose.schoon("https://app.beehiiv.com/magic/a1B2c3D4e5F6g7H8i9J0k1L2m3/verify").equals("https://app.beehiiv.com/magic/…/verify"), "sleutel in het pad weg");
        check(NieuwsDiagnose.schoon("https://www.aireport.nl/p/hoe-gebruik-je-ai").equals("https://www.aireport.nl/p/hoe-gebruik-je-ai"), "gewoon pad blijft");
        check(NieuwsDiagnose.schoon("data:image/png;base64,AAAA").equals("data:…"), "data: zonder inhoud");
        check(NieuwsDiagnose.schoon("blob:https://www.aireport.nl/5e1c-uuid?x").startsWith("blob:https://www.aireport.nl/"), "blob: met site");
        check(NieuwsDiagnose.schoon(null).isEmpty(), "niets blijft niets");
        check(NieuwsDiagnose.schoon("niet een adres met spaties?code=1").equals("niet een adres met spaties?…"), "onleesbaar adres: vraag toch weg");
        check(!NieuwsDiagnose.verberg("Fout bij rene.van.es@example.nl inloggen").contains("rene"), "e-mailadres weg");
        check(NieuwsDiagnose.verberg("TypeError: x is undefined").equals("TypeError: x is undefined"), "gewone foutmelding blijft");

        NieuwsDiagnose d = new NieuwsDiagnose(1000);
        d.log(2500, "laad", "GET https://www.aireport.nl/");
        String t = d.tekst("kop");
        check(t.startsWith("kop\n"), "kop bovenaan");
        check(t.contains("1.5s") && t.contains("laad") && t.contains("aireport.nl"), "regel met tijd en soort: " + t.trim());
        d.log(3000, "js", "melding met\nnieuwe regel " + new String(new char[1000]).replace('\0', 'x'));
        String laatste = d.tekst("").trim();
        laatste = laatste.substring(laatste.lastIndexOf('\n') + 1);
        check(laatste.length() < NieuwsDiagnose.MAX_REGEL + 40 && laatste.contains("nieuwe regel"), "regel ingekort en op één regel");
        for (int i = 0; i < NieuwsDiagnose.MAX + 50; i++) d.log(4000 + i, "laad", "r" + i);
        check(d.aantal() == NieuwsDiagnose.MAX + NieuwsDiagnose.BEGIN, "hooguit " + NieuwsDiagnose.MAX + " regels");
        check(d.tekst("").contains("regels weggelaten"), "zegt dat er regels weg zijn");
        long t0 = System.nanoTime();
        NieuwsDiagnose.verberg(new String(new char[100000]).replace('\0', 'a'));
        check(System.nanoTime() - t0 < 2_000_000_000L, "lange tekst zonder cijfers blijft snel");
        // adressen in vrije tekst (foutmeldingen van Chromium) zonder inlogcode
        String fout = NieuwsDiagnose.verberg("Failed to load https://api.beehiiv.com/v2/otp?code=123456&email=r: net::ERR_FAILED");
        check(!fout.contains("123456") && fout.contains("https://api.beehiiv.com/v2/otp?…") && fout.contains("ERR_FAILED"), "adres in een foutmelding geschoond: " + fout);
        check(!NieuwsDiagnose.schoon("https://x.nl/unsubscribe/rene%40example.nl").contains("rene"), "e-mailadres met %40 weg");
        check(!NieuwsDiagnose.schoon("https://user:p!w@x.nl/a b?c=1").contains("p!w") && !NieuwsDiagnose.schoon("https://user:p!w@x.nl/a b").contains("user"), "wachtwoord in een onleesbaar adres weg");
        check(NieuwsDiagnose.schoon("https://x.nl/verify/abcdefghijklmnopqrstuvwxyzABCDEF").equals("https://x.nl/verify/…"), "sleutel zonder cijfer (hoofd- en kleine letters) weg");
        check(NieuwsDiagnose.schoon("https://www.aireport.nl/p/de-beste-ai-tools-voor-je-administratie").endsWith("administratie"), "lange titel in kleine letters blijft");
        NieuwsDiagnose e = new NieuwsDiagnose(0);
        e.log(1, "js", new String(new char[1190]).replace('\0', 'x') + " rene.van.es@example.nl");
        check(!e.tekst("").contains("rene.van"), "e-mailadres op de knip half weg is toch weg");
        long t1 = System.nanoTime();
        String groot = "https://x.nl/" + new String(new char[2_000_000]).replace('\0', 'a');
        NieuwsDiagnose.schoon(groot);
        NieuwsDiagnose.verberg(groot);
        check(System.nanoTime() - t1 < 3_000_000_000L, "adres van 2 MB blijft snel");
        // fouten en het begin blijven, gelijke regels worden geteld
        NieuwsDiagnose f = new NieuwsDiagnose(0);
        f.log(1, "start", "eerste");
        f.log(2, "HTTP", "403 Forbidden POST https://www.aireport.nl/api");
        for (int i = 0; i < NieuwsDiagnose.MAX + 100; i++) f.log(3 + i, "laad", "r" + i);
        f.log(9000, "laad", "zelfde");
        f.log(9001, "laad", "zelfde");
        f.log(9002, "laad", "zelfde");
        String ft = f.tekst("");
        check(ft.contains("eerste") && ft.contains("== Fouten") && ft.indexOf("403 Forbidden") < ft.indexOf("== Alles"), "begin en fouten blijven bewaard");
        check(ft.contains("zelfde (×3)") && ft.indexOf("zelfde") == ft.lastIndexOf("zelfde"), "gelijke regels geteld");
        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
    }
}
