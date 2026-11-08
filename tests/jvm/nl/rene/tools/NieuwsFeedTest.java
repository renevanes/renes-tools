package nl.rene.tools;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Nieuwsfeeds lezen (RSS, Atom, RDF) en artikeltekst opschonen, zonder Android. */
public class NieuwsFeedTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    static NieuwsFeed.Feed lees(String naam) throws Exception {
        try (InputStream in = new FileInputStream("tests/jvm/nieuws/" + naam)) { return NieuwsFeed.parse(in, null); }
    }
    static NieuwsFeed.Feed lees(String xml, String charset) throws Exception {
        return NieuwsFeed.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), charset);
    }
    static String herhaal(String s, int n) { StringBuilder b = new StringBuilder(s.length() * n); for (int i = 0; i < n; i++) b.append(s); return b.toString(); }
    static long iso(String s) { return java.time.Instant.parse(s).toEpochMilli(); }
    static String tekst(List<NieuwsFeed.Blok> bl) {
        StringBuilder b = new StringBuilder();
        for (NieuwsFeed.Blok x : bl) b.append(x.kop ? "## " : "").append(x.tekst).append(" | ");
        return b.toString();
    }

    public static void main(String[] a) throws Exception {
        // RSS 2.0
        NieuwsFeed.Feed f = lees("rss.xml");
        check("Testnieuws & meer".equals(f.titel), "feedtitel (niet die van het logo): " + f.titel);
        check("https://nieuws.example.org/".equals(f.link), "feedadres (niet dat van het logo): " + f.link);
        check(f.artikelen.size() == 4, "4 artikelen (een leeg item overgeslagen): " + f.artikelen.size());
        NieuwsFeed.Artikel r1 = f.artikelen.get(0);
        check("Brug dicht: 3 < 5 & \"omleiding\"".equals(r1.titel), "titel: tags weg, een losse < blijft: " + r1.titel);
        check("art-1".equals(r1.sleutel), "sleutel = guid: " + r1.sleutel);
        check(r1.link.startsWith("https://nieuws.example.org/artikel/1?"), "link: " + r1.link);
        check(r1.datum == iso("2026-11-08T16:50:43Z"), "RFC 822-datum met +0100");
        check("Redactie Stad".equals(r1.auteur), "dc:creator: " + r1.auteur);
        check("https://beeld.example.org/groot.jpg".equals(r1.beeld), "breedste plaatje (geen video): " + r1.beeld);
        String t1 = tekst(r1.blokken);
        check(t1.startsWith("Eerste alinea met vet & een link. | "), "content:encoded wint van description, zonder tags: " + t1);
        check(!t1.contains("alert") && !t1.contains("Bijschrift") && !t1.contains("weg"), "script, figuur en formulier weg: " + t1);
        check(t1.contains("## Tussenkop | ") && t1.contains("• punt een | • punt twee | "), "tussenkop en lijstje: " + t1);
        check(t1.contains("Regel een\nregel twee | "), "br = nieuwe regel");
        check(t1.contains("Café é € …"), "tekens (namen en nummers) terug: " + t1);
        check(!r1.ingekort, "niet ingekort");
        NieuwsFeed.Artikel r2 = f.artikelen.get(1);
        check("Jan Jansen".equals(r2.auteur), "RSS-auteur: alleen de naam, geen e-mailadres: " + r2.auteur);
        check("https://beeld.example.org/2.jpg".equals(r2.beeld), "plaatje uit de HTML van de beschrijving (geen mp3): " + r2.beeld);
        check("Met HTML als tekst | ".equals(tekst(r2.blokken)), "geëscapete HTML wordt tekst: " + tekst(r2.blokken));
        check("https://nieuws.example.org/artikel/2".equals(r2.sleutel), "zonder guid: link als sleutel");
        NieuwsFeed.Artikel r3 = f.artikelen.get(2);
        check(r3.datum == 0 && "/relatief/3".equals(r3.link), "zonder datum: 0; relatieve link blijft (Nieuws maakt hem af)");
        check(r3.beeld.isEmpty(), "media:content zonder soort met .mp4 is geen plaatje");
        check(r3.auteur.isEmpty(), "alleen een e-mailadres als auteur: leeg");
        check("Platte tekst. | Tweede alinea. | ".equals(tekst(r3.blokken)), "platte tekst in alinea's: " + tekst(r3.blokken));
        NieuwsFeed.Artikel r4 = f.artikelen.get(3);
        check(r4.beeld.isEmpty() && "Tekst | ".equals(tekst(r4.blokken)), "plaatje via http telt niet");

        // Atom
        f = lees("atom.xml");
        check("Atom Test".equals(f.titel) && "https://atom.example.org/".equals(f.link), "Atom: titel en alternate-link: " + f.link);
        check(f.artikelen.size() == 2, "Atom: 2 artikelen");
        NieuwsFeed.Artikel e1 = f.artikelen.get(0);
        check("Kop <met> HTML".equals(e1.titel), "Atom: titel type html: " + e1.titel);
        check("tag:atom.example.org,2026:1".equals(e1.sleutel), "Atom: eerste id (niet die van <source>): " + e1.sleutel);
        check("https://atom.example.org/1".equals(e1.link), "Atom: alternate-link, niet de enclosure: " + e1.link);
        check(e1.datum == iso("2026-11-08T15:50:43Z"), "Atom: published met fractie en zone wint van updated");
        check("Piet".equals(e1.auteur), "Atom: auteur zonder e-mail: " + e1.auteur);
        check("Alinea & één | ## Kop | Twee | ".equals(tekst(e1.blokken)), "Atom: xhtml-inhoud: " + tekst(e1.blokken));
        check("https://atom.example.org/a.png".equals(e1.beeld), "Atom: plaatje uit de xhtml: " + e1.beeld);
        NieuwsFeed.Artikel e2 = f.artikelen.get(1);
        check("https://atom.example.org/2".equals(e2.link) && e2.datum == iso("2026-11-07T10:00:00Z"), "Atom: link zonder rel; updated als er geen published is");
        check("Tekst met <b> tags die tekst blijven | ".equals(tekst(e2.blokken)), "Atom: type text blijft tekst: " + tekst(e2.blokken));

        // RDF (RSS 1.0)
        f = lees("rdf.xml");
        check("RDF Test".equals(f.titel) && f.artikelen.size() == 1 && "Eén".equals(f.artikelen.get(0).titel), "RDF: titel en artikel");
        check(f.artikelen.get(0).datum == iso("2026-11-08T12:00:00Z"), "RDF: dc:date zonder seconden");

        // Tekensets
        f = lees("latin1.xml");
        check("Café Nieuws".equals(f.titel) && "Crème brûlée".equals(f.artikelen.get(0).titel), "Latin-1 volgens de xml-kop: " + f.titel);
        byte[] cp = "<rss><channel><title>Caf\u00e9</title></channel></rss>".getBytes("windows-1252");
        check("Café".equals(NieuwsFeed.parse(new ByteArrayInputStream(cp), "windows-1252").titel), "tekenset uit de HTTP-kop als de feed er geen noemt");
        byte[] bom = ("\uFEFF<rss><channel><title>Met BOM</title></channel></rss>").getBytes(StandardCharsets.UTF_8);
        check("Met BOM".equals(NieuwsFeed.parse(new ByteArrayInputStream(bom), null).titel), "UTF-8 met BOM");

        // Geen feed, of onveilig
        boolean geen = false;
        try (InputStream in = new FileInputStream("tests/jvm/nieuws/pagina.html")) { NieuwsFeed.parse(in, null); } catch (NieuwsFeed.GeenFeed x) { geen = true; }
        check(geen, "webpagina: GeenFeed");
        geen = false;
        try { lees("<opml><body/></opml>", null); } catch (NieuwsFeed.GeenFeed x) { geen = true; }
        check(geen, "ander XML: GeenFeed");
        geen = false;
        try { lees("dit is geen xml", null); } catch (NieuwsFeed.GeenFeed x) { geen = true; }
        check(geen, "geen XML: GeenFeed");
        String ent = "";
        try { lees("entiteit.xml"); } catch (Exception x) { ent = String.valueOf(x.getMessage()); }
        check(ent.contains("onveilig"), "eigen entiteiten geweigerd: " + ent);
        check(Nieuws.kort(new java.io.IOException(ent)).equals(ent), "die fout houdt zijn eigen tekst");

        // Grenzen
        StringBuilder veel = new StringBuilder("<rss><channel><title>Veel</title>");
        for (int i = 0; i < 260; i++) veel.append("<item><title>Bericht ").append(i).append("</title><guid>g").append(i).append("</guid></item>");
        f = lees(veel.append("</channel></rss>").toString(), null);
        check(f.artikelen.size() == NieuwsFeed.MAX_ITEMS, "hooguit " + NieuwsFeed.MAX_ITEMS + " artikelen: " + f.artikelen.size());
        StringBuilder lang = new StringBuilder();
        for (int i = 0; i < 400; i++) lang.append("<p>Alinea ").append(i).append(" met wat meer tekst erin om lang te worden, en nog wat woorden.</p>");
        boolean[] kort = new boolean[1];
        List<NieuwsFeed.Blok> bl = NieuwsFeed.blokken(lang.toString(), kort);
        int som = 0;
        for (NieuwsFeed.Blok x : bl) som += x.tekst.length();
        check(kort[0] && bl.size() <= NieuwsFeed.MAX_BLOKKEN && som <= NieuwsFeed.MAX_TEKST + 2, "lange tekst ingekort: " + bl.size() + " blokken, " + som + " tekens");
        check("Prijs < 5 euro | Commentaar weg | ".equals(tekst(NieuwsFeed.blokken("<p>Prijs &lt; 5 euro</p><!-- <p>x</p> --><p>Commentaar weg</p>", null))), "losse < en commentaar");
        String grootKop = "<h2>" + new String(new char[300]).replace('\0', 'x') + "</h2>";
        check(!NieuwsFeed.blokken(grootKop, null).get(0).kop, "een 'kop' van 300 tekens is gewone tekst");
        check("Voor | ".equals(tekst(NieuwsFeed.blokken("<p>Voor</p><script>x = 1; <p>na</p>", null))) && "Kop".equals(NieuwsFeed.regel("Kop <!-- rest")),
                "niet afgesloten script of opmerking: de rest valt weg");
        kort[0] = false;
        bl = NieuwsFeed.blokken(herhaal("<span class=\"opmaak\"></span>", 5_000) + "<p>Achteraan</p>", kort);
        check(kort[0] && !tekst(bl).contains("Achteraan"), "meer dan " + NieuwsFeed.MAX_HTML + " tekens HTML: alleen het begin, en ingekort");

        // Halve tags in grote hoeveelheden: geen kwadratische tijd (Android kan een zoekopdracht niet afbreken)
        for (String stuk : new String[]{"<!--", "<script>", "<h1 a", "<img a", "<li a", "<p a", "<a ", "<b", "<form x>", "<!-- <script>"}) {
            String h = herhaal(stuk + "x", 200_000 / (stuk.length() + 1));
            long t0 = System.nanoTime();
            NieuwsFeed.blokken(h, null);
            NieuwsFeed.regel(h);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            check(ms < 1500, "200.000 tekens '" + stuk + "…' snel gelezen (" + ms + " ms)");
        }

        // Eén fout teken of een kapot artikel kost niet de hele feed
        String stuur = "<rss><channel><title>T</title><item><title>Met \u0001stuurteken</title><guid>a</guid></item><item><title>Twee</title><guid>b</guid></item></channel></rss>";
        f = lees(stuur, null);
        check(f.artikelen.size() == 2 && "Met stuurteken".equals(f.artikelen.get(0).titel), "stuurteken in de feed overgeslagen");
        String half = "<rss><channel><title>T</title><item><title>Goed</title><guid>a</guid></item><item><title>Kapot &#1; </title></item></channel></rss>";
        f = lees(half, null);
        check(f.artikelen.size() == 1 && "Goed".equals(f.artikelen.get(0).titel), "kapotte feed: wat ervoor stond blijft");
        boolean leeg = false;
        try { lees("<rss><channel><item><title>Kapot &#1;</title></item></channel></rss>", null); } catch (org.xml.sax.SAXException x) { leeg = true; }
        check(leeg, "kapot vóór het eerste artikel: niet goed leesbaar");

        // Afbeeldingen en adressen alleen van openbare sites
        f = lees("<rss><channel><title>T</title><item><title>Thuis</title><guid>a</guid><media:thumbnail url=\"https://192.168.1.10/a.jpg\"/>"
                + "<description>&lt;img src=\"https://localhost./b.jpg\"&gt;</description></item></channel></rss>", null);
        check(f.artikelen.get(0).beeld.isEmpty(), "geen afbeelding van het thuisnetwerk of het toestel zelf");
        for (String h : new String[]{"localhost", "127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.1", "192.168.1.1", "169.254.1.1", "100.64.0.1", "0.0.0.0",
                "[::1]", "::1", "printer.local", "nas.lan", "router.home.arpa", "intranet", "", null})
            check(!NieuwsFeed.openbaar(h), "niet openbaar: " + h);
        for (String h : new String[]{"nos.nl", "feeds.nos.nl.", "8.8.8.8", "172.32.0.1", "www.rijnmond.nl"}) check(NieuwsFeed.openbaar(h), "openbaar: " + h);
        check(!NieuwsFeed.httpsOpenbaar("https://user:pw@nos.nl/feed") && !NieuwsFeed.httpsOpenbaar("http://nos.nl/") && NieuwsFeed.httpsOpenbaar("https://nos.nl/feed"),
                "https zonder inloggegevens");

        // Datums
        check(NieuwsFeed.datum("2026-11-08T12:00:00+0200") == iso("2026-11-08T10:00:00Z"), "ISO met +0200 zonder dubbele punt");
        check(NieuwsFeed.datum("2026-11-08 12:00:00") == iso("2026-11-08T12:00:00Z"), "ISO zonder zone = UTC");
        check(NieuwsFeed.datum("Sun, 08 Nov 2026 12:00:00 GMT") == iso("2026-11-08T12:00:00Z"), "RFC 822 in GMT");
        check(NieuwsFeed.datum("gisteren") == 0 && NieuwsFeed.datum(null) == 0, "onleesbare datum = 0");

        System.out.println(fails == 0 ? "alles goed" : fails + " fout(en)");
        System.exit(fails == 0 ? 0 : 1);
    }
}
