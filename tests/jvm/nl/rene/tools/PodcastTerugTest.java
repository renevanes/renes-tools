package nl.rene.tools;

import java.util.Arrays;
import java.util.List;

public class PodcastTerugTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    public static void main(String[] a) {
        // Er speelde A, toen B, nu C (recent: nieuwste eerst)
        List<String> recent = Arrays.asList("C", "B", "A");
        PodcastTerug.Stap s1 = PodcastTerug.terug(recent, "C", null);
        check("B".equals(s1.key), "terug vanaf C: B");
        // B speelt nu en staat vooraan in recent; nog een keer terug gaat verder terug, niet heen en weer
        List<String> r2 = Arrays.asList("B", "C", "A");
        PodcastTerug.Stap s2 = PodcastTerug.terug(r2, "B", s1.stand);
        check("A".equals(s2.key), "nog een keer terug: A (niet terug naar C)");
        List<String> r3 = Arrays.asList("A", "B", "C");
        PodcastTerug.Stap s3 = PodcastTerug.terug(r3, "A", s2.stand);
        check(s3.key == null, "verder terug is er niets");
        // Vooruit: dezelfde weg terug
        PodcastTerug.Stap v1 = PodcastTerug.vooruit(r3, "A", s2.stand);
        check("B".equals(v1.key), "vooruit vanaf A: B");
        PodcastTerug.Stap v2 = PodcastTerug.vooruit(r2, "B", v1.stand);
        check("C".equals(v2.key), "vooruit vanaf B: C");
        check(PodcastTerug.vooruit(recent, "C", v2.stand).key == null, "vooraan: geen vooruit (de speler kiest zelf)");
        // Iets anders gekozen: de rij begint opnieuw
        List<String> r4 = Arrays.asList("D", "A", "B", "C");
        check("A".equals(PodcastTerug.terug(r4, "D", s2.stand).key), "andere aflevering: nieuwe rij vanaf wat nu speelt");
        check(PodcastTerug.vooruit(r4, "D", s2.stand).key == null, "andere aflevering: geen vooruit");
        // Een aflevering die uit de lijst is gehaald wordt overgeslagen
        check("A".equals(PodcastTerug.terug(Arrays.asList("C", "A"), "C", new PodcastTerug.Stand(Arrays.asList("C", "B", "A"), 0)).key), "wat weg is wordt overgeslagen");
        // Niets speelt: de laatst gestarte
        check("C".equals(PodcastTerug.terug(recent, "", null).key), "niets speelt: de laatste");
        check(PodcastTerug.terug(Arrays.asList("C"), "C", null).key == null && PodcastTerug.terug(Arrays.<String>asList(), "", null).key == null, "niets eerder: niets");
        // Bewaren en teruglezen
        PodcastTerug.Stand l = PodcastTerug.lees(PodcastTerug.json(s2.stand));
        check(l != null && l.i == 2 && l.rij.equals(Arrays.asList("C", "B", "A")) && l.klopt("A"), "stand bewaren en teruglezen");
        check(PodcastTerug.lees("") == null && PodcastTerug.lees("{\"i\":9,\"rij\":[\"a\"]}") == null && PodcastTerug.lees("kapot") == null, "onleesbaar: geen stand");
        // Terugspoelen aan het begin: het einde van de vorige
        check(PodcastTerug.plekAanEinde(1_800_000, 15_000) == 1_780_000, "een half uur: 20 s voor het einde");
        check(PodcastTerug.plekAanEinde(0, 15_000) == -1 && PodcastTerug.plekAanEinde(20_000, 15_000) == -1, "lengte onbekend of heel kort: niet");
        check(PodcastTerug.overBegin(0, -1) && PodcastTerug.overBegin(2_500, 5_000) && !PodcastTerug.overBegin(2_000, 400) && !PodcastTerug.overBegin(9_000, -1), "alleen aan het begin en niet bij snel drukken");
        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
    }
}
