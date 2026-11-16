package nl.rene.tools;

import java.util.List;

public class KluisTekstTest {
    static int fails = 0;
    static void check(boolean ok, String name) { if (!ok) { fails++; System.out.println("FAIL: " + name); } else System.out.println("ok: " + name); }

    public static void main(String[] args) {
        check(KluisTekst.netjes("  Polis\n\nnummer\t 12 34 ").equals("Polis nummer 12 34"), "witruimte samengenomen");
        StringBuilder lang = new StringBuilder(); for (int i = 0; i < 30000; i++) lang.append("woord woord ");
        check(KluisTekst.netjes(lang.toString()).length() == KluisTekst.MAX_TEKST, "begrensd");
        check(KluisTekst.plat("Café Ënergie ÀB ß").equals("cafe energie ab ß") && KluisTekst.plat("Geïnd").length() == 5, "plat: zonder accenten, even lang");
        check(KluisTekst.woorden("ab").isEmpty() && KluisTekst.woorden("  ").isEmpty() && KluisTekst.woorden(null).isEmpty(), "te korte vraag zoekt niet");
        List<String> w = KluisTekst.woorden("Polis  CAFÉ polis");
        check(w.size() == 2 && w.get(0).equals("polis") && w.get(1).equals("cafe"), "woorden: plat, zonder dubbele");
        String t = "Dit is de eerste regel van een lang document over de opstalverzekering van het huis. Het polisnummer is 12345 en de premie bedraagt 20 euro per maand. Daarna volgt nog veel meer tekst over voorwaarden en uitsluitingen.";
        String s = KluisTekst.stuk(t, KluisTekst.woorden("POLISNUMMER"));
        check(s != null && s.contains("polisnummer is 12345") && s.startsWith("… ") && s.endsWith(" …") && s.length() < 180, "stukje rond de treffer: " + s);
        check(KluisTekst.stuk(t, KluisTekst.woorden("premie huis")) != null, "alle woorden komen voor, waar dan ook");
        check(KluisTekst.stuk(t, KluisTekst.woorden("premie auto")) == null, "een woord ontbreekt: geen treffer");
        check(KluisTekst.stuk("Factuur café René", KluisTekst.woorden("rene cafe")).equals("Factuur café René"), "accenten tellen niet; korte tekst helemaal");
        check(KluisTekst.stuk("", KluisTekst.woorden("iets")) == null && KluisTekst.stuk(t, KluisTekst.woorden("x")) == null, "leeg of te kort: niets");
        check(KluisTekst.soort("application/pdf", "a").equals("pdf") && KluisTekst.soort("application/octet-stream", "Polis.PDF").equals("pdf") && KluisTekst.soort("image/jpeg", "f.jpg").equals("beeld")
                && KluisTekst.soort("text/plain", "n").equals("tekst") && KluisTekst.soort("application/zip", "a.zip").isEmpty() && KluisTekst.soort(null, null).isEmpty(), "soort bepaalt hoe er gelezen wordt");
        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
        System.out.println("KluisTekstTest geslaagd");
    }
}
