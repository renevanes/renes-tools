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
        check(d.aantal() == NieuwsDiagnose.MAX, "hooguit " + NieuwsDiagnose.MAX + " regels");
        check(d.tekst("").contains("oudere regels weggelaten"), "zegt dat er regels weg zijn");
        long t0 = System.nanoTime();
        NieuwsDiagnose.verberg(new String(new char[100000]).replace('\0', 'a'));
        check(System.nanoTime() - t0 < 2_000_000_000L, "lange tekst zonder cijfers blijft snel");
        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
    }
}
