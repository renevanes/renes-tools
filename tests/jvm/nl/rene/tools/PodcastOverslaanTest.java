package nl.rene.tools;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Podcasts: reclame herkennen in hoofdstukken, begin en einde overslaan, en de instellingen opschonen. */
public class PodcastOverslaanTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    public static void main(String[] a) throws Exception {
        List<String> geen = Arrays.asList();
        // Reclame aan de titel
        for (String t : new String[]{"Reclame", "Advertentie", "Reclameblok 2", "Sponsor: Bol", "Gesponsorde inhoud", "Ad", "Ads", "ad break",
                "Ad 2", "Midroll", "Pre-roll", "Advertisement", "Onze sponsors", "COMMERCIAL"})
            check(PodcastOverslaan.isReclame(t, geen), "reclame: " + t);
        for (String t : new String[]{"Ad Melkert over de PvdA", "Adverbia in het Nederlands", "Inleiding", "Bad Bunny", "Ads in de krant van toen", ""})
            check(!PodcastOverslaan.isReclame(t, geen), "geen reclame: " + t);
        check(PodcastOverslaan.isReclame("Een woord van onze partner", PodcastOverslaan.woorden("partner, aanbieding")), "eigen woord telt");
        check(!PodcastOverslaan.isReclame("Partnerschap", PodcastOverslaan.woorden("partner")), "eigen woord alleen als heel woord");
        check(PodcastOverslaan.woorden(" a ,, B;c\nc ").equals(Arrays.asList("a", "b", "c")), "woorden opschonen (dubbel en leeg weg)");

        // Hoofdstukken → stukken
        String json = "{\"version\":\"1.2.0\",\"chapters\":["
                + "{\"startTime\":0,\"title\":\"Intro\"},"
                + "{\"startTime\":60.5,\"title\":\"Reclame\"},"
                + "{\"startTime\":120,\"title\":\"Sponsor\"},"
                + "{\"startTime\":180,\"title\":\"Het gesprek\"},"
                + "{\"startTime\":1500,\"endTime\":1560,\"title\":\"Advertentie\"},"
                + "{\"startTime\":1560,\"title\":\"Afsluiting\"},"
                + "{\"startTime\":1700,\"title\":\"Ads\"}]}";
        List<PodcastOverslaan.Stuk> r = PodcastOverslaan.reclameUit(json, 1800_000, geen);
        check(r.size() == 3, "drie reclamestukken (twee aaneengesloten worden één): " + r.size());
        check(r.get(0).van == 60_500 && r.get(0).tot == 180_000, "eerste stuk 60,5–180 s");
        check(r.get(1).van == 1500_000 && r.get(1).tot == 1560_000, "endTime telt");
        check(r.get(2).van == 1700_000 && r.get(2).tot == 1800_000, "laatste hoofdstuk loopt tot het eind");
        check(PodcastOverslaan.reclameUit(json, 0, geen).size() == 2, "lengte onbekend: het laatste hoofdstuk niet");
        check(PodcastOverslaan.reclameUit("geen json", 1000, geen).isEmpty() && PodcastOverslaan.reclameUit("{}", 1000, geen).isEmpty(), "onleesbaar = niets");
        String door = "{\"chapters\":[{\"startTime\":300,\"title\":\"Verder\"},{\"startTime\":100,\"title\":\"Reclame\"},{\"startTime\":-5,\"title\":\"Ad\"},{\"title\":\"Ads\"}]}";
        List<PodcastOverslaan.Stuk> d = PodcastOverslaan.reclameUit(door, 600_000, geen);
        check(d.size() == 1 && d.get(0).van == 100_000 && d.get(0).tot == 300_000, "door elkaar gezet en ongeldige tijden overgeslagen");

        // Intro, outro en de plek
        PodcastOverslaan.Keuze k = new PodcastOverslaan.Keuze();
        k.reclame = true; k.intro = 30; k.outro = 45;
        List<PodcastOverslaan.Stuk> st = PodcastOverslaan.stukken(k, r, 1800_000);
        check(st.size() == 4 && st.get(0).waarom.equals("intro") && st.get(0).tot == 30_000, "intro voorop");
        check(PodcastOverslaan.outroVanaf(k, 1800_000) == 1755_000, "einde vanaf 29:15");
        check(PodcastOverslaan.outroVanaf(k, 60_000) == -1 && PodcastOverslaan.stukken(k, r, 50_000).isEmpty(), "korte aflevering: niets van begin en einde");
        k.reclame = false;
        check(PodcastOverslaan.stukken(k, r, 1800_000).size() == 1, "reclame uit: alleen de intro");
        k.reclame = true;
        Set<Long> toe = new HashSet<>();
        check(PodcastOverslaan.nu(st, 5_000, toe).waarom.equals("intro"), "in de intro");
        check(PodcastOverslaan.nu(st, 61_000, toe).van == 60_500, "in de reclame");
        check(PodcastOverslaan.nu(st, 179_000, toe) == null, "laatste anderhalve seconde: niet meer springen");
        check(PodcastOverslaan.nu(st, 200_000, toe) == null, "in het gesprek: niets");
        toe.add(60_500L);
        check(PodcastOverslaan.nu(st, 61_000, toe) == null, "zelf teruggegaan: speelt");
        check(PodcastOverslaan.bij(st, 61_000).van == 60_500, "bij() vindt het stuk ook als het mag spelen");

        // Instellingen
        JSONObject in = new JSONObject("{\"reclame\":false,\"woorden\":\"x\",\"per\":{\"p1\":{\"reclame\":true,\"intro\":9999,\"outro\":-3},"
                + "\"../x\":{\"intro\":5},\"p2\":{},\"p3\":{\"outro\":20,\"onzin\":1}},\"geheim\":1}");
        JSONObject s = PodcastOverslaan.schoon(in);
        check(!s.optBoolean("reclame") && s.optString("woorden").equals("x") && !s.has("geheim"), "algemeen: bekende velden");
        JSONObject per = s.getJSONObject("per");
        check(per.length() == 2 && per.getJSONObject("p1").optInt("intro") == 600 && !per.getJSONObject("p1").has("outro"), "per podcast: begrensd, leeg weg, vreemd id weg");
        check(!per.getJSONObject("p3").has("onzin"), "onbekende velden weg");
        PodcastOverslaan.Keuze k1 = PodcastOverslaan.keuze(s, "p1"), k3 = PodcastOverslaan.keuze(s, "p3"), k9 = PodcastOverslaan.keuze(s, "p9");
        check(k1.reclame && k1.intro == 600, "eigen keuze gaat voor");
        check(!k3.reclame && k3.outro == 20 && !k9.reclame && k9.intro == 0, "anders de standaard");
        check(PodcastOverslaan.schoon(null).optBoolean("reclame"), "standaard: reclame overslaan aan");

        // Feed: het hoofdstukkenadres per aflevering (alleen https, alleen JSON)
        String rss = "<rss xmlns:podcast=\"https://podcastindex.org/namespace/1.0\"><channel><title>T</title>"
                + "<item><title>A</title><enclosure url=\"https://x.nl/a.mp3\" type=\"audio/mpeg\"/><podcast:chapters url=\"https://x.nl/a.json\" type=\"application/json+chapters\"/></item>"
                + "<item><title>B</title><enclosure url=\"https://x.nl/b.mp3\" type=\"audio/mpeg\"/><podcast:chapters url=\"http://x.nl/b.json\" type=\"application/json+chapters\"/></item>"
                + "<item><title>C</title><enclosure url=\"https://x.nl/c.mp3\" type=\"audio/mpeg\"/><podcast:chapters url=\"https://x.nl/c.psc\" type=\"application/xml+psc\"/></item>"
                + "</channel></rss>";
        PodcastFeed.Feed f = PodcastFeed.parse(new java.io.ByteArrayInputStream(rss.getBytes("UTF-8")), 10);
        check(f.items.get(0).chapters.equals("https://x.nl/a.json"), "hoofdstukken gelezen");
        check(f.items.get(1).chapters.isEmpty() && f.items.get(2).chapters.isEmpty(), "geen http en geen ander formaat");

        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
        System.out.println("alles goed");
    }
}
