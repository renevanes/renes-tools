package nl.rene.tools;

/** Nieuwe app vanzelf downloaden: wanneer het mag, en vaste redenen voor het logboek (Updater). */
public class UpdaterVanzelfTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    public static void main(String[] a) {
        check(Updater.magVanzelf(true, true, false, false), "aan, alleen wifi, op wifi: downloaden");
        check(!Updater.magVanzelf(true, true, true, false), "aan, alleen wifi, op mobiele data: wachten");
        check(Updater.magVanzelf(true, false, true, false), "aan, ook mobiele data: downloaden");
        check(!Updater.magVanzelf(false, false, false, false), "uit: nooit");
        check(!Updater.magVanzelf(true, false, false, true), "Play-versie: nooit (updates via Google Play)");

        check(Updater.vanzelfReden(null).equals("klaar"), "geen probleem: klaar");
        check(Updater.vanzelfReden("De update is niet ondertekend; niet geïnstalleerd").equals("handtekening"), "handtekening");
        check(Updater.vanzelfReden("Controlegetal van de download klopt niet").equals("controle"), "controlegetal");
        check(Updater.vanzelfReden("Onverwachte versienaam; niet geïnstalleerd").equals("controle"), "versienaam");
        check(Updater.vanzelfReden("Update mislukt: de download kon niet bewaard worden").equals("opslaan"), "opslaan");
        check(Updater.vanzelfReden("Update mislukt: geen ruimte voor de download").equals("opslaan"), "geen ruimte");
        check(Updater.vanzelfReden("Update mislukt: Unable to resolve host raw.githubusercontent.com").equals("download"), "netwerk: alleen het woord, geen adres");
        for (String p : new String[]{"Update mislukt: https://example.org/x", "x@y.nl", "iets onbekends"})
            check(Updater.vanzelfReden(p).matches("[a-z]+"), "altijd een vast woord: " + Updater.vanzelfReden(p));

        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
        System.out.println("alles goed");
    }
}
