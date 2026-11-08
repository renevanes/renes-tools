package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Nieuws: bronnen, cache, gelezen en bewaard (in tijdelijke mappen), adressen en de backupregels. Zonder netwerk. */
public class NieuwsTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    static File tmp(String naam) throws Exception { return Files.createTempDirectory("nieuws-" + naam).toFile(); }
    static NieuwsFeed.Feed rss() throws Exception { try (InputStream in = new FileInputStream("tests/jvm/nieuws/rss.xml")) { return NieuwsFeed.parse(in, null); } }
    static JSONObject bron(JSONArray bs, String id) { for (int i = 0; i < bs.length(); i++) if (bs.optJSONObject(i).optString("id").equals(id)) return bs.optJSONObject(i); return null; }
    static JSONObject art(JSONArray as, String titel) { for (int i = 0; i < as.length(); i++) if (as.optJSONObject(i).optString("titel").startsWith(titel)) return as.optJSONObject(i); return null; }
    interface Doe { void doe() throws Exception; }
    static boolean geeftFout(Doe d) { try { d.doe(); return false; } catch (Nieuws.Fout e) { return true; } catch (Exception e) { return false; } }
    static boolean bevat(JSONArray a, String s) { for (int i = 0; i < a.length(); i++) if (a.optString(i).equals(s)) return true; return false; }

    public static void main(String[] a) throws Exception {
        File dir = tmp("dir"), cache = tmp("cache");
        long nu = 1_794_000_000_000L;

        // Bronnen: de bekende staan klaar, NOS en Rijnmond aan
        JSONArray bs = Nieuws.bronnen(dir);
        check(bs.length() == Nieuws.STANDAARD.length, "alle bekende bronnen: " + bs.length());
        check(bron(bs, "nos").optBoolean("aan") && bron(bs, "rijnmond").optBoolean("aan") && !bron(bs, "nu").optBoolean("aan"), "standaard aan: NOS en Rijnmond");
        for (String[] s : Nieuws.STANDAARD) check(Nieuws.https(s[2]) && s[0].matches("[a-z0-9-]+") && !s[4].isEmpty(), "bekende bron " + s[0] + ": https, geldige id en een site");
        Nieuws.zetAan(dir, "nu", true);
        Nieuws.zetAan(dir, "rijnmond", false);
        bs = Nieuws.bronnen(dir);
        check(bron(bs, "nu").optBoolean("aan") && !bron(bs, "rijnmond").optBoolean("aan"), "aan- en uitzetten bewaard");
        check(geeftFout(() -> Nieuws.zetAan(dir, "bestaat-niet", true)), "onbekende bron: fout");

        // Eigen bronnen
        JSONObject e = Nieuws.voegToe(dir, "https://blog.example.org/feed", "Mijn <b>blog</b> &amp; meer");
        check(e.getString("id").matches("e[0-9a-f]{16}") && e.optBoolean("eigen") && e.optBoolean("aan"), "eigen bron: id e…, aan: " + e);
        check("Mijn blog & meer".equals(e.getString("naam")), "naam als tekst: " + e.getString("naam"));
        JSONObject al = Nieuws.voegToe(dir, "https://www.blog.example.org/feed/", "Anders");
        check(al.optBoolean("al") && al.getString("id").equals(e.getString("id")), "zelfde feed (www., /) niet dubbel");
        JSONObject nos = Nieuws.voegToe(dir, "http://feeds.nos.nl/nosnieuwsalgemeen", "NOS");
        check(nos.optBoolean("al") && "nos".equals(nos.getString("id")), "adres van een bekende bron: die bron");
        Nieuws.zetAan(dir, "tweakers", false);
        check(Nieuws.voegToe(dir, "https://tweakers.net/feeds/mixed.xml", "").optBoolean("al") && bron(Nieuws.bronnen(dir), "tweakers").optBoolean("aan"), "bekende bron die uit stond: weer aan");
        check("x.example.org".equals(Nieuws.voegToe(dir, "https://www.x.example.org/rss", "  ").getString("naam")), "zonder titel: de site als naam");
        File vol = tmp("vol");
        for (int i = 0; i < Nieuws.MAX_EIGEN; i++) Nieuws.voegToe(vol, "https://site" + i + ".example.org/rss", "Site " + i);
        check(geeftFout(() -> Nieuws.voegToe(vol, "https://nog.example.org/rss", "Te veel")), "hooguit " + Nieuws.MAX_EIGEN + " eigen bronnen");

        // Verwijderen: alleen eigen bronnen, met de cache
        File cf = new File(cache, e.getString("id") + ".json");
        Nieuws.bewaarFeed(cache, e.getString("id"), "https://blog.example.org/feed", rss(), nu);
        check(cf.isFile(), "cache van de eigen bron");
        Nieuws.verwijder(dir, cache, e.getString("id"));
        check(bron(Nieuws.bronnen(dir), e.getString("id")) == null && !cf.exists(), "eigen bron en zijn cache weg");
        check(geeftFout(() -> Nieuws.verwijder(dir, cache, "nos")), "een bekende bron kan niet weg (alleen uit)");

        // Onleesbaar bestand: niet overschreven maar opzij gezet
        File kapot = tmp("kapot");
        Files.write(new File(kapot, "bronnen.json").toPath(), "{kapot".getBytes(StandardCharsets.UTF_8));
        check(Nieuws.bronnen(kapot).length() == Nieuws.STANDAARD.length, "onleesbare bronnen: de standaard");
        Nieuws.zetAan(kapot, "nu", true);
        check(new File(kapot, "bronnen-onleesbaar.json").isFile() && bron(Nieuws.bronnen(kapot), "nu").optBoolean("aan"), "onleesbaar bestand opzij gezet, nieuw bestand werkt");
        Files.write(new File(kapot, "bronnen.json").toPath(), ("{\"standaard\":{},\"eigen\":[{\"id\":\"e../../x\",\"url\":\"https://a.example.org/\"},"
                + "{\"id\":\"eabc\",\"url\":\"http://a.example.org/\"},{\"id\":\"eabd\",\"naam\":\"<i>Goed</i>\",\"url\":\"https://b.example.org/rss\"}]}").getBytes(StandardCharsets.UTF_8));
        JSONArray kb = Nieuws.bronnen(kapot);
        check(kb.length() == Nieuws.STANDAARD.length + 1 && "Goed".equals(bron(kb, "eabd").getString("naam")), "eigen bronnen met een rare id of zonder https genegeerd");

        // Cache en overzicht
        Nieuws.zetAan(dir, "nu", false);
        int nieuw = Nieuws.bewaarFeed(cache, "nos", "https://nieuws.example.org/rss.xml", rss(), nu);
        check(nieuw == 0, "eerste keer: niets 'nieuw' (alles is nieuw)");
        JSONObject l = Nieuws.lijst(dir, cache);
        JSONArray as = l.getJSONArray("artikelen");
        check(as.length() == 4, "4 artikelen in het overzicht: " + as.length());
        JSONObject zonder = art(as, "Zonder datum");
        check(zonder != null && zonder.getLong("datum") == nu, "zonder datum: het moment van ophalen");
        check(as.getJSONObject(0).getLong("datum") >= as.getJSONObject(as.length() - 1).getLong("datum"), "nieuwste eerst");
        JSONObject brug = art(as, "Brug");
        check("https://nieuws.example.org/artikel/1?id=7#top".equals(brug.getString("link")), "volgparameters uit de link: " + brug.getString("link"));
        check("https://nieuws.example.org/relatief/3".equals(zonder.getString("link")), "relatieve link afgemaakt: " + zonder.getString("link"));
        check(brug.getString("sam").startsWith("Eerste alinea") && !brug.has("blokken") && brug.getInt("lang") > 50, "overzicht: samenvatting, zonder de hele tekst");
        check(brug.getString("id").matches("n[0-9a-f]{16}") && "nos".equals(brug.getString("bron")), "artikel-id en bron");
        check(bron(l.getJSONArray("bronnen"), "nos").getInt("aantal") == 4 && bron(l.getJSONArray("bronnen"), "nos").getLong("t") == nu, "bron: aantal en tijd");
        check(!l.optBoolean("bezig"), "niet bezig");
        // Tweede keer: oud artikel houdt zijn tijd, een nieuw artikel telt
        NieuwsFeed.Feed meer = rss();
        NieuwsFeed.Artikel extra = new NieuwsFeed.Artikel();
        extra.titel = "Net binnen"; extra.sleutel = "extra-1"; extra.datum = nu + 3_000_000L;
        meer.artikelen.add(extra);
        NieuwsFeed.Artikel toekomst = new NieuwsFeed.Artikel();
        toekomst.titel = "Uit de toekomst"; toekomst.sleutel = "toekomst"; toekomst.datum = nu + 100L * 86_400_000L;
        meer.artikelen.add(toekomst);
        nieuw = Nieuws.bewaarFeed(cache, "nos", "https://nieuws.example.org/rss.xml", meer, nu + 3_600_000L);
        as = Nieuws.lijst(dir, cache).getJSONArray("artikelen");
        check(nieuw == 2 && as.length() == 6, "tweede keer: 2 nieuw: " + nieuw);
        check(art(as, "Zonder datum").getLong("datum") == nu, "zonder datum: houdt de eerste tijd");
        check(art(as, "Uit de toekomst").getLong("datum") == nu + 3_600_000L, "datum in de toekomst: het moment van ophalen");
        // Mislukt: oude artikelen blijven, met de fout
        Nieuws.bewaarFout(cache, "nos", "Geen verbinding", nu + 7_200_000L);
        l = Nieuws.lijst(dir, cache);
        JSONObject nb = bron(l.getJSONArray("bronnen"), "nos");
        check(l.getJSONArray("artikelen").length() == 6 && "Geen verbinding".equals(nb.optString("fout")) && nb.getLong("poging") == nu + 7_200_000L, "mislukt: artikelen blijven, fout erbij");
        // Bron uit: niet in het overzicht
        Nieuws.zetAan(dir, "nos", false);
        l = Nieuws.lijst(dir, cache);
        check(l.getJSONArray("artikelen").length() == 0 && bron(l.getJSONArray("bronnen"), "nos").getInt("aantal") == 0, "bron uit: geen artikelen");
        Nieuws.zetAan(dir, "nos", true);
        // Teveel per bron
        NieuwsFeed.Feed groot = new NieuwsFeed.Feed();
        for (int i = 0; i < 90; i++) { NieuwsFeed.Artikel x = new NieuwsFeed.Artikel(); x.titel = "Bericht " + i; x.sleutel = "g" + i; x.datum = nu - i * 60_000L; groot.artikelen.add(x); }
        Nieuws.bewaarFeed(cache, "rijnmond", "https://rijnmond.example.org/", groot, nu);
        Nieuws.zetAan(dir, "rijnmond", true);
        as = Nieuws.lijst(dir, cache).getJSONArray("artikelen");
        check(as.length() == 6 + Nieuws.MAX_PER_BRON && art(as, "Bericht 89") == null && art(as, "Bericht 59") != null, "hooguit " + Nieuws.MAX_PER_BRON + " per bron, de nieuwste: " + as.length());
        Nieuws.zetAan(dir, "rijnmond", false);

        // Een artikel lezen
        String id = brug.getString("id");
        JSONObject heel = Nieuws.artikel(dir, cache, id);
        check(heel.getJSONArray("blokken").length() >= 5 && "NOS Nieuws".equals(heel.getString("bronnaam")) && !heel.getBoolean("isBewaard") && !heel.getBoolean("gelezen"), "artikel met de hele tekst en de naam van de bron");
        check(heel.getJSONArray("blokken").getJSONObject(1).optInt("k") == 1, "tussenkop gemarkeerd");
        check(geeftFout(() -> Nieuws.artikel(dir, cache, "../bronnen")) && geeftFout(() -> Nieuws.artikel(dir, cache, "n0000000000000000")), "onbekend of raar id: fout");

        // Gelezen
        Nieuws.gelezen(dir, new JSONArray().put(id).put("../x").put(art(as, "Tweede").getString("id")), true, nu);
        JSONArray gl = Nieuws.lijst(dir, cache).getJSONArray("gelezen");
        check(gl.length() == 2 && bevat(gl, id), "gelezen (rare id genegeerd): " + gl);
        check(Nieuws.artikel(dir, cache, id).getBoolean("gelezen"), "artikel weet dat het gelezen is");
        Nieuws.gelezen(dir, new JSONArray().put(art(as, "Tweede").getString("id")), false, nu);
        check(Nieuws.lijst(dir, cache).getJSONArray("gelezen").length() == 1, "weer ongelezen");

        // Bewaren
        Nieuws.bewaar(dir, cache, id, true, nu + 1);
        JSONArray bw = Nieuws.lijst(dir, cache).getJSONArray("bewaard");
        check(bw.length() == 1 && bw.getJSONObject(0).getString("id").equals(id) && bw.getJSONObject(0).getLong("bewaard") == nu + 1 && "NOS Nieuws".equals(bw.getJSONObject(0).getString("bronnaam")), "bewaard, met tijd en bronnaam");
        Nieuws.bewaar(dir, cache, id, true, nu + 2);
        check(Nieuws.lijst(dir, cache).getJSONArray("bewaard").length() == 1, "twee keer bewaren = één keer");
        check(new File(cache, "nos.json").delete(), "cache weg (bijv. Android ruimt op)");
        heel = Nieuws.artikel(dir, cache, id);
        check(heel.getBoolean("isBewaard") && heel.getJSONArray("blokken").length() >= 5, "bewaard artikel blijft leesbaar zonder cache");
        check(geeftFout(() -> Nieuws.bewaar(dir, cache, "n00000000000000ff", true, nu)), "bewaren van iets wat er niet is: fout");
        // Gelezen van een bewaard artikel blijft, ook na 60 dagen; de rest valt weg
        long later = nu + Nieuws.GELEZEN_BEWAREN + 86_400_000L;
        Nieuws.gelezen(dir, new JSONArray().put("n00000000000000aa"), true, nu);
        Nieuws.gelezen(dir, new JSONArray(), true, later);
        gl = Nieuws.lijst(dir, cache).getJSONArray("gelezen");
        check(gl.length() == 1 && bevat(gl, id), "na 60 dagen: alleen het bewaarde artikel nog gelezen: " + gl);
        Nieuws.bewaar(dir, cache, id, false, later);
        check(Nieuws.lijst(dir, cache).getJSONArray("bewaard").length() == 0, "niet meer bewaard");

        // Wat bijgewerkt moet worden
        File d2 = tmp("todo-dir"), c2 = tmp("todo-cache");
        check(Nieuws.teDoen(d2, c2, false, nu).size() == 2, "zonder cache: de twee bronnen die aan staan");
        Nieuws.bewaarFeed(c2, "nos", "https://x.example.org/", rss(), nu);
        Nieuws.bewaarFout(c2, "rijnmond", "Geen verbinding", nu);
        check(Nieuws.teDoen(d2, c2, false, nu + 60_000L).isEmpty(), "net bijgewerkt of net mislukt: niets");
        check(Nieuws.teDoen(d2, c2, true, nu + 60_000L).size() == 2, "met de hand: alles wat aan staat");
        check(Nieuws.teDoen(d2, c2, false, nu + Nieuws.VERS + 1).size() == 2, "na een kwartier: weer bijwerken");
        Files.write(new File(c2, "weg.json").toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        Nieuws.ruimOp(d2, c2);
        check(!new File(c2, "weg.json").exists() && new File(c2, "nos.json").isFile(), "opruimen: cache van onbekende bronnen weg");

        // Adressen
        check("https://nos.nl".equals(Nieuws.adres(" nos.nl ")), "nos.nl → https://nos.nl");
        check("https://x.example.org/feed".equals(Nieuws.adres("http://x.example.org/feed")), "http → https");
        check("https://x.example.org/rss".equals(Nieuws.adres("feed://x.example.org/rss")), "feed:// → https");
        check(geeftFout(() -> Nieuws.adres("")) && geeftFout(() -> Nieuws.adres("ftp://x.example.org/")) && geeftFout(() -> Nieuws.adres("geen adres.nl"))
                && geeftFout(() -> Nieuws.adres("localhost")) && geeftFout(() -> Nieuws.adres("https://user:pw@x.example.org/")), "ongeldige adressen geweigerd");
        check(!Nieuws.https("http://x.example.org/") && Nieuws.https("https://x.example.org/a?b=c"), "alleen https");
        check("nu".equals(Nieuws.bekendeSite("https://www.nu.nl/")) && "nos-sport".equals(Nieuws.bekendeSite("https://nos.nl/sport")) && Nieuws.bekendeSite("https://nu.nl/rss/Sport") == null, "bekende sites");
        check("https://a.example.org/x?id=1&b=2#k".equals(Nieuws.schoon("https://a.example.org/x?utm_source=rss&id=1&at_medium=RSS&b=2&fbclid=z#k")), "volgparameters weg, de rest blijft");
        check("https://a.example.org/x".equals(Nieuws.schoon("https://a.example.org/x?utm_campaign=y")), "alleen volgparameters: geen ? meer");
        check("".equals(Nieuws.link("javascript:alert(1)", "https://a.example.org/")) && "".equals(Nieuws.link("data:text/html,x", "https://a.example.org/")), "link: alleen http(s)");

        // Feed op een webpagina
        String html = new String(Files.readAllBytes(Paths.get("tests/jvm/nieuws/pagina.html")), StandardCharsets.UTF_8);
        check("https://site.example.org/feed.xml?soort=alles&taal=nl".equals(Nieuws.zoekFeed(html, "https://site.example.org/nieuws/")), "feed op de pagina gevonden (https, relatief): " + Nieuws.zoekFeed(html, "https://site.example.org/nieuws/"));
        check(Nieuws.zoekFeed("<link rel=\"alternate\" type=\"application/rss+xml\" href=\"http://x.example.org/rss\">", "https://x.example.org/") == null, "feed via http: niet");
        check(Nieuws.zoekFeed("<link rel=\"stylesheet\" type=\"application/rss+xml\" href=\"/rss\">", "https://x.example.org/") == null, "geen alternate: niet");

        // Fouten voor Rene
        check("Geen verbinding".equals(Nieuws.kort(new java.net.UnknownHostException("x"))) && "Geen verbinding".equals(Nieuws.kort(new java.net.SocketTimeoutException())), "geen netwerk");
        check("Geen veilige verbinding met de nieuwssite".equals(Nieuws.kort(new javax.net.ssl.SSLHandshakeException("cert"))), "certificaat");
        check("Feed is te groot".equals(Nieuws.kort(new java.io.IOException("Feed is te groot"))), "te groot");
        check("Bijwerken lukt niet".equals(Nieuws.kort(new RuntimeException("NullPointer at x.y"))), "onbekende fout: geen technische tekst");
        check("Deze feed is niet goed leesbaar".equals(Nieuws.kort(new org.xml.sax.SAXParseException("x", null))), "kapotte XML");

        // Backup: nieuws/ gaat mee in Alle appgegevens, niet in de Android-backup
        check(AppData.FILE_ROOTS.contains("nieuws"), "Alle appgegevens: nieuws/ gaat mee");
        String br = new String(Files.readAllBytes(Paths.get("app/res/xml/backup_rules.xml")), StandardCharsets.UTF_8);
        String dr = new String(Files.readAllBytes(Paths.get("app/res/xml/data_rules.xml")), StandardCharsets.UTF_8);
        String ex = "<exclude domain=\"file\" path=\"nieuws/\" />";
        int cb = dr.indexOf("<cloud-backup>"), dt = dr.indexOf("<device-transfer>");
        check(br.contains(ex) && cb >= 0 && dt > cb && dr.substring(cb, dt).contains(ex) && dr.substring(dt).contains(ex), "Android-backup en overzetten: nieuws/ uitgesloten");

        System.out.println(fails == 0 ? "alles goed" : fails + " fout(en)");
        System.exit(fails == 0 ? 0 : 1);
    }
}
