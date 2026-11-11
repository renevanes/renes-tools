package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
    /** Vier artikelen zoals bij Tweakers: twee gewone, een met een afbeelding in de feed en een met een link naar een andere site. */
    static NieuwsFeed.Feed tweakers() {
        NieuwsFeed.Feed f = new NieuwsFeed.Feed();
        String[][] as = {{"een", "https://tweakers.net/nieuws/1/een.html", ""}, {"twee", "/nieuws/2/twee.html", ""},
                {"drie", "https://tweakers.net/nieuws/3/drie.html", "https://tweakers.net/i/al.jpg"}, {"vier", "https://ander.example.org/nieuws/4/vier.html", ""}};
        for (String[] s : as) { NieuwsFeed.Artikel x = new NieuwsFeed.Artikel(); x.titel = s[0]; x.link = s[1]; x.sleutel = s[1]; x.beeld = s[2]; f.artikelen.add(x); }
        return f;
    }
    static boolean bevat(JSONArray a, String s) { for (int i = 0; i < a.length(); i++) if (a.optString(i).equals(s)) return true; return false; }

    public static void main(String[] a) throws Exception {
        File dir = tmp("dir"), cache = tmp("cache");
        long nu = 1_794_000_000_000L;

        // Bronnen: de bekende staan klaar, NOS en Rijnmond aan
        JSONArray bs = Nieuws.bronnen(dir);
        check(bs.length() == Nieuws.STANDAARD.length, "alle bekende bronnen: " + bs.length());
        check(bron(bs, "nos").optBoolean("aan") && bron(bs, "rijnmond").optBoolean("aan") && !bron(bs, "nu").optBoolean("aan"), "standaard aan: NOS en Rijnmond");
        for (String[] s : Nieuws.STANDAARD) check(Nieuws.https(s[2]) && s[0].matches("[a-z0-9-]+") && !s[4].isEmpty(), "bekende bron " + s[0] + ": https, geldige id en een site");
        for (String[] s : Nieuws.STANDAARD) {
            String al = Nieuws.alleen(s[0]);
            boolean vanDeSite = al.isEmpty();
            for (String site : s[4].split(" ")) vanDeSite |= al.startsWith(site + "/");
            check(al.isEmpty() || (al.endsWith("/") && vanDeSite), "bekende bron " + s[0] + ": alleen-links horen bij de eigen site");
        }
        // Tweakers mengt nieuws met downloads, reviews en aanbiedingen: alleen het nieuws blijft
        check("tweakers.net/nieuws/".equals(Nieuws.alleen("tweakers")) && Nieuws.alleen("nos").isEmpty() && Nieuws.alleen("e1234").isEmpty(), "alleen nieuws bij Tweakers");
        check("tweakers".equals(Nieuws.bekendeSite("https://tweakers.net/nieuws/")) && "tweakers".equals(Nieuws.bekendeSite("tweakers.net")), "tweakers.net/nieuws intikken: de bekende bron");
        NieuwsFeed.Feed gemengd = new NieuwsFeed.Feed();
        for (String l : new String[]{"https://tweakers.net/nieuws/1/een.html", "https://tweakers.net/downloads/2/twee.html", "https://tweakers.net/reviews/3/drie.html",
                "/nieuws/4/vier.html", "https://www.tweakers.net/nieuws/5/vijf.html", "https://tweakers.net/nieuws", "https://ander.example.org/tweakers.net/nieuws/6", "https://tweakers.net/plan/7/zeven.html", "", "https://tweakers.net/nieuws/../downloads/8/acht.html"}) {
            NieuwsFeed.Artikel x = new NieuwsFeed.Artikel();
            x.link = l; x.titel = l;
            gemengd.artikelen.add(x);
        }
        Nieuws.filter("tweakers", "https://tweakers.net/feeds/mixed.xml", gemengd);
        StringBuilder over = new StringBuilder();
        for (NieuwsFeed.Artikel x : gemengd.artikelen) over.append(x.link).append(' ');
        check("https://tweakers.net/nieuws/1/een.html /nieuws/4/vier.html https://www.tweakers.net/nieuws/5/vijf.html ".equals(over.toString()), "Tweakers: alleen nieuwsartikelen: " + over);
        NieuwsFeed.Feed alles = new NieuwsFeed.Feed();
        for (String l : new String[]{"https://nos.nl/l/1", "https://nos.nl/video/2"}) { NieuwsFeed.Artikel x = new NieuwsFeed.Artikel(); x.link = l; alles.artikelen.add(x); }
        Nieuws.filter("nos", "https://feeds.nos.nl/nosnieuwsalgemeen", alles);
        check(alles.artikelen.size() == 2, "andere bronnen: alles blijft");
        NieuwsFeed.Feed geenNieuws = new NieuwsFeed.Feed();
        NieuwsFeed.Artikel dl = new NieuwsFeed.Artikel(); dl.link = "https://tweakers.net/downloads/9/negen.html"; geenNieuws.artikelen.add(dl);
        String geen = "";
        try { Nieuws.filter("tweakers", "https://tweakers.net/feeds/mixed.xml", geenNieuws); } catch (Nieuws.Fout e) { geen = e.getMessage(); }
        check("In deze feed staat nu geen nieuws".equals(geen), "Tweakers zonder nieuws: een melding, de vorige artikelen blijven");
        NieuwsFeed.Feed leeg = new NieuwsFeed.Feed();
        Nieuws.filter("tweakers", "https://tweakers.net/feeds/mixed.xml", leeg);
        check(leeg.artikelen.isEmpty(), "lege feed: geen extra melding");

        // Tweakers zet geen afbeeldingen in de feed: die komt van de artikelpagina (og:image)
        String pag = "https://tweakers.net/nieuws/1/een.html";
        check("https://tweakers.net/i/abc=/1280x400/i/1.jpeg?f=fpa".equals(Nieuws.paginaBeeld("<html><head><meta property=\"og:title\" content=\"x\">"
                + "<meta property=\"og:image\" content=\"https://tweakers.net/i/abc=/1280x400/i/1.jpeg?f=fpa\"></head>", pag)), "og:image van de pagina");
        check("https://cdn.example.org/a.jpg?b=1&c=2".equals(Nieuws.paginaBeeld("<meta content='https://cdn.example.org/a.jpg?b=1&amp;c=2' property='og:image'>", pag)), "content eerst, enkele aanhalingstekens, &amp;");
        check("https://tweakers.net/i/2.jpg".equals(Nieuws.paginaBeeld("<meta property=\"og:image\" content=\"/i/2.jpg\">", pag)), "relatief: tegen de pagina");
        check("".equals(Nieuws.paginaBeeld("<meta property=\"og:image\" content=\"http://tweakers.net/i/3.jpg\">", pag))
                && "".equals(Nieuws.paginaBeeld("<meta property=\"og:image\" content=\"https://127.0.0.1/i/4.jpg\">", pag))
                && "".equals(Nieuws.paginaBeeld("<meta property=\"og:image\" content=\"https://localhost/i/5.jpg\">", pag)), "alleen https naar een openbare site");
        check("https://x.example.org/tw.jpg".equals(Nieuws.paginaBeeld("<meta name=\"twitter:image\" content=\"https://x.example.org/tw.jpg\">", pag))
                && "https://x.example.org/og.jpg".equals(Nieuws.paginaBeeld("<meta name=\"twitter:image\" content=\"https://x.example.org/tw.jpg\"><meta property=\"og:image\" content=\"https://x.example.org/og.jpg\">", pag)), "twitter:image alleen als er geen og:image is");
        check("https://x.example.org/b.jpg".equals(Nieuws.paginaBeeld("<meta property=\"og:image\" data-content=\"https://x.example.org/a.jpg\" content=\"https://x.example.org/b.jpg\">", pag)), "data-content telt niet als content");
        check("https://x.example.org/c.jpg".equals(Nieuws.paginaBeeld("<meta name=\"description\" content=\" > Lees meer\"><meta property=\"og:image\" content=\"https://x.example.org/c.jpg\">", pag)), "een > in een andere meta-tag: geen fout, de afbeelding erna telt");
        StringBuilder lang = new StringBuilder("<html><head>");
        while (lang.length() < Nieuws.MAX_PAGINA) lang.append("<!-- vulling -->");
        check("".equals(Nieuws.paginaBeeld(lang + "<meta property=\"og:image\" content=\"https://x.example.org/laat.jpg\">", pag)) && "".equals(Nieuws.paginaBeeld("<p>geen kop</p>", pag)), "alleen het begin van de pagina; geen afbeelding = leeg");

        java.util.List<String> gehaald = new java.util.ArrayList<>();
        Nieuws.Pagina site = (url, tot) -> {
            gehaald.add(url);
            return url.contains("/2/") ? "<head><meta property=\"og:title\" content=\"zonder\"></head>" : "<meta property=\"og:image\" content=\"https://tweakers.net/i/" + url.replaceAll("\\D", "") + ".jpg\">";
        };
        NieuwsFeed.Feed tf = tweakers();
        Nieuws.beelden("tweakers", "https://tweakers.net/feeds/mixed.xml", tf, null, System.currentTimeMillis() + 60_000, site);
        check(gehaald.size() == 2 && gehaald.get(0).endsWith("/nieuws/1/een.html") && gehaald.get(1).endsWith("/nieuws/2/twee.html"), "alleen pagina's van de site zelf, niet als de feed al een afbeelding heeft: " + gehaald);
        check("https://tweakers.net/i/1.jpg".equals(tf.artikelen.get(0).beeld) && tf.artikelen.get(1).beeld.isEmpty() && tf.artikelen.get(1).zonderBeeld
                && "https://tweakers.net/i/al.jpg".equals(tf.artikelen.get(2).beeld) && tf.artikelen.get(3).beeld.isEmpty() && !tf.artikelen.get(3).zonderBeeld, "afbeelding gevonden, pagina zonder afbeelding onthouden, andere site overgeslagen");
        check("ok".equals(tf.beeldStand), "stand: ok");
        File tc = tmp("tweakers");
        Nieuws.bewaarFeed(tc, "tweakers", "https://tweakers.net/feeds/mixed.xml", tf, nu);
        JSONObject tcache = new JSONObject(new String(Files.readAllBytes(Nieuws.cacheBestand(tc, "tweakers").toPath()), StandardCharsets.UTF_8));
        check(art(tcache.getJSONArray("artikelen"), "een").optString("beeld").endsWith("/i/1.jpg") && art(tcache.getJSONArray("artikelen"), "twee").optBoolean("zonderBeeld")
                && !Nieuws.kort(art(tcache.getJSONArray("artikelen"), "twee")).has("zonderBeeld"), "in de cache: afbeelding en zonderBeeld (niet in de lijst)");
        check("ok".equals(tcache.optString("beeldStand")), "stand in de cache");
        gehaald.clear();
        NieuwsFeed.Feed tf2 = tweakers();
        Nieuws.beelden("tweakers", "https://tweakers.net/feeds/mixed.xml", tf2, tcache, System.currentTimeMillis() + 60_000, site);
        check(gehaald.isEmpty() && "https://tweakers.net/i/1.jpg".equals(tf2.artikelen.get(0).beeld) && tf2.artikelen.get(1).zonderBeeld, "volgende ronde: uit de cache, geen pagina's opnieuw");
        check("ok".equals(tf2.beeldStand), "niets opgehaald: de vorige stand blijft");
        File td = tmp("tweakers-dir");
        Nieuws.zetAan(td, "tweakers", true);
        JSONObject tl = Nieuws.lijst(td, tc);
        check(bron(tl.getJSONArray("bronnen"), "tweakers").optBoolean("cookiekeuze") && "ok".equals(bron(tl.getJSONArray("bronnen"), "tweakers").optString("beeldStand"))
                && !bron(tl.getJSONArray("bronnen"), "nos").has("cookiekeuze") && !bron(tl.getJSONArray("bronnen"), "nos").has("beeldStand"), "in de lijst: cookiekeuze en stand, alleen bij Tweakers");

        // De toestemmingspagina: de site stuurt naar een andere site (DPG Media); dan stoppen en de cookiekeuze vragen
        gehaald.clear();
        NieuwsFeed.Feed td1 = tweakers();
        Nieuws.beelden("tweakers", "https://tweakers.net/feeds/mixed.xml", td1, tcache, System.currentTimeMillis() + 60_000, (url, tot) -> {
            gehaald.add(url); throw new Nieuws.Doorverwezen(); });
        NieuwsFeed.Feed td2 = tweakers();
        Nieuws.beelden("tweakers", "https://tweakers.net/feeds/mixed.xml", td2, null, System.currentTimeMillis() + 60_000, (url, tot) -> {
            gehaald.add(url); throw new Nieuws.Doorverwezen(); });
        check(gehaald.size() == 1 && "ok".equals(td1.beeldStand) && "cookiekeuze".equals(td2.beeldStand) && !td2.artikelen.get(0).zonderBeeld && !td2.artikelen.get(1).zonderBeeld,
                "doorverwezen: na één pagina stoppen, stand cookiekeuze, niets onthouden (uit de cache: niets opgehaald)");
        gehaald.clear();
        NieuwsFeed.Feed muur = tweakers();
        Nieuws.beelden("tweakers", "https://tweakers.net/feeds/mixed.xml", muur, null, System.currentTimeMillis() + 60_000, (url, tot) -> {
            gehaald.add(url); return "<html><body><h1>Kies je cookies</h1></body></html>"; });
        check(gehaald.size() == 1 && "fout".equals(muur.beeldStand) && "geenkop".equals(muur.beeldReden) && !muur.artikelen.get(0).zonderBeeld, "een pagina zonder og:-gegevens (een muur): stoppen, niet als zonder afbeelding onthouden");
        for (String[] pg : new String[][]{
                {"<html><head><script>var x = 'twitter:card';</script></head><body>Kies je cookies</body></html>", "og: alleen als tekst in een script"},
                {"<html><head><meta property=\"og:title\" content=\"x\"><link rel=\"stylesheet\" href=\"/a.css\">", "kop niet helemaal gelezen"}}) {
            gehaald.clear();
            NieuwsFeed.Feed half = tweakers();
            Nieuws.beelden("tweakers", "https://tweakers.net/feeds/mixed.xml", half, null, System.currentTimeMillis() + 60_000, (url, tot) -> { gehaald.add(url); return pg[0]; });
            check(gehaald.size() == 1 && "fout".equals(half.beeldStand) && !half.artikelen.get(0).zonderBeeld, pg[1] + ": stoppen, niet als zonder afbeelding onthouden");
        }
        check(Nieuws.artikelKop("<head><meta name='twitter:card' content='x'></head>") && Nieuws.artikelKop("<meta property=og:title content=x><body>")
                && !Nieuws.artikelKop("<head><meta name=\"description\" content=\"og: twitter:\"></head>") && !Nieuws.artikelKop("<meta property=\"og:title\" content=\"x\">"), "artikelKop: echte og:-gegevens en een hele kop");
        gehaald.clear();
        NieuwsFeed.Feed weg = tweakers();
        Nieuws.beelden("tweakers", "https://tweakers.net/feeds/mixed.xml", weg, null, System.currentTimeMillis() + 60_000, (url, tot) -> {
            if (url.contains("/1/")) { gehaald.add(url); throw new Nieuws.HttpFout(404); }
            return site.haal(url, tot);
        });
        check(gehaald.size() == 2 && !weg.artikelen.get(0).zonderBeeld && "".equals(weg.artikelen.get(0).beeld) && weg.artikelen.get(1).zonderBeeld && "ok".equals(weg.beeldStand),
                "404 (misschien net gepubliceerd): overslaan zonder iets te onthouden, en verder");
        gehaald.clear();
        NieuwsFeed.Feed gone = tweakers();
        Nieuws.beelden("tweakers", "https://tweakers.net/feeds/mixed.xml", gone, null, System.currentTimeMillis() + 60_000, (url, tot) -> {
            if (url.contains("/1/")) { gehaald.add(url); throw new Nieuws.HttpFout(410); }
            return site.haal(url, tot);
        });
        check(gehaald.size() == 2 && gone.artikelen.get(0).zonderBeeld && "".equals(gone.artikelen.get(0).beeld) && "ok".equals(gone.beeldStand), "410 (blijvend weg): onthouden als zonder afbeelding, en verder");
        gehaald.clear();
        NieuwsFeed.Feed elders = tweakers();
        Nieuws.beelden("tweakers", "https://tweakers.net/feeds/mixed.xml", elders, null, System.currentTimeMillis() + 60_000, (url, tot) -> {
            if (url.contains("/1/")) { gehaald.add(url); throw new Nieuws.Elders(); }
            return site.haal(url, tot);
        });
        check(gehaald.size() == 2 && elders.artikelen.get(0).zonderBeeld && "".equals(elders.artikelen.get(0).beeld) && "ok".equals(elders.beeldStand),
                "naar een andere pagina op de site (verplaatst, inloggen): zonder afbeelding onthouden, geen cookiekeuze vragen, en verder");
        check("".equals(Nieuws.koekjesNamen(null)) && "".equals(Nieuws.koekjesNamen("")) && "a;b".equals(Nieuws.koekjesNamen("b=2; a=1"))
                && Nieuws.koekjesNamen("ak_bmsc=1; bm_sv=2").equals(Nieuws.koekjesNamen("bm_sv=3; ak_bmsc=4"))
                && !Nieuws.koekjesNamen("ak_bmsc=1").equals(Nieuws.koekjesNamen("ak_bmsc=1; pg_consent=x")) && "a".equals(Nieuws.koekjesNamen("a=1; a=2; ;")),
                "cookienamen: nieuwe waarden tellen niet, een nieuwe naam wel");

        // Doorverwijzing binnen de site: alleen de titel in het adres mag anders, een andere pagina is een muur
        check(Nieuws.zelfdeArtikel(pag, pag) && Nieuws.zelfdeArtikel(pag, "https://tweakers.net/nieuws/1/een-nieuwe-titel.html") && Nieuws.zelfdeArtikel(pag, "https://www.tweakers.net/nieuws/1/een.html?x=1")
                && !Nieuws.zelfdeArtikel(pag, "https://tweakers.net/privacy-gate/?returnUrl=x") && !Nieuws.zelfdeArtikel(pag, "https://tweakers.net/nieuws/12/een.html")
                && !Nieuws.zelfdeArtikel(pag, "https://tweakers.net/my.tnet/login/"), "zelfde artikel: alleen een andere titel");

        // Cookies gaan alleen mee naar de site zelf van een bron met afbeeldingen van de pagina
        check(Nieuws.koekjesNaar("https://tweakers.net/nieuws/1/een.html") && Nieuws.koekjesNaar("https://www.tweakers.net/nieuws/")
                && !Nieuws.koekjesNaar("https://myprivacy.dpgmedia.nl/consent") && !Nieuws.koekjesNaar("https://pg.tweakers.net/x") && !Nieuws.koekjesNaar("https://mijn.kpn.com/facturen")
                && !Nieuws.koekjesNaar("http://tweakers.net/") && !Nieuws.koekjesNaar("https://nos.nl/") && !Nieuws.koekjesNaar(null), "cookies alleen naar tweakers.net");

        // Doorverwijzingen bij het ophalen: een artikelpagina blijft op de eigen site en op https
        check("https://tweakers.net/nieuws/1/b.html".equals(Nieuws.volgende(pag, pag, "/nieuws/1/b.html", true))
                && "https://www.tweakers.net/nieuws/1/een.html".equals(Nieuws.volgende(pag, pag, "https://www.tweakers.net/nieuws/1/een.html", true))
                && "https://cdn.example.org/feed.xml".equals(Nieuws.volgende("https://x.example.org/rss", "https://x.example.org/rss", "https://cdn.example.org/feed.xml", false)), "doorverwijzing: relatief, www en (bij een feed) een andere site");
        String dv = "";
        try { Nieuws.volgende(pag, pag, "https://myprivacy.dpgmedia.nl/consent?siteKey=kzz6o4y8ybwvpkea&callbackUrl=https://tweakers.net/privacy-gate/store/", true); } catch (Nieuws.Doorverwezen e) { dv = "doorverwezen"; }
        check("doorverwezen".equals(dv) && geeftFout(() -> Nieuws.volgende(pag, pag, "http://tweakers.net/nieuws/1/een.html", true))
                && geeftFout(() -> Nieuws.volgende("https://x.example.org/rss", "https://x.example.org/rss", "http://x.example.org/rss", false)), "artikelpagina naar een andere site: doorverwezen; naar http: fout");
        check(Nieuws.weg(pag, "https://tweakers.net/nieuws/") && Nieuws.weg(pag, "https://tweakers.net/") && !Nieuws.weg(pag, pag) && !Nieuws.weg(pag, "https://tweakers.net/nieuws/1/een-nieuwe-titel.html")
                && !Nieuws.weg(pag, pag + "?x=1") && !Nieuws.weg("https://tweakers.net/nieuws/1/een", "https://tweakers.net/nieuws/1/een/"), "naar een pagina erboven = het artikel is weg; een nieuwe titel niet");
        gehaald.clear();
        NieuwsFeed.Feed dubbel = tweakers();
        NieuwsFeed.Artikel kopie = new NieuwsFeed.Artikel();
        kopie.titel = "een (kopie)"; kopie.link = dubbel.artikelen.get(0).link; kopie.sleutel = dubbel.artikelen.get(0).sleutel;
        dubbel.artikelen.add(1, kopie);
        Nieuws.beelden("tweakers", "https://tweakers.net/feeds/mixed.xml", dubbel, null, System.currentTimeMillis() + 60_000, site);
        check(gehaald.size() == 2 && "https://tweakers.net/i/1.jpg".equals(dubbel.artikelen.get(1).beeld), "hetzelfde artikel twee keer in de feed: één keer ophalen: " + gehaald);

        // Het venster voor de cookiekeuze: alleen https en alleen de hosts van de bron
        check(Nieuws.cookieAdres("tweakers", "https://tweakers.net/nieuws/") && Nieuws.cookieAdres("tweakers", "https://pg.tweakers.net/api/x")
                && Nieuws.cookieAdres("tweakers", "https://myprivacy.dpgmedia.nl/consent?siteKey=a") && Nieuws.cookieAdres("tweakers", "https://myprivacy-static.dpgmedia.net/consent.js")
                && Nieuws.cookieAdres("tweakers", "https://pg.dpgmedia.nl/api/x") && Nieuws.cookieAdres("tweakers", "https://tweakimg.net/g/x.png") && Nieuws.cookieAdres("tweakers", "https://ic.tweakimg.net/a.jpg"),
                "toegestaan: Tweakers en de toestemmingspagina van DPG Media");
        check(!Nieuws.cookieAdres("tweakers", "http://tweakers.net/") && !Nieuws.cookieAdres("tweakers", "https://eviltweakers.net/") && !Nieuws.cookieAdres("tweakers", "https://tweakers.net.example.org/")
                && !Nieuws.cookieAdres("tweakers", "https://www.google-analytics.com/a.js") && !Nieuws.cookieAdres("nos", "https://nos.nl/") && !Nieuws.cookieAdres("tweakers", null)
                && !Nieuws.cookieAdres("tweakers", "javascript:alert(1)") && !Nieuws.cookieAdres("tweakers", "intent://tweakers.net/#Intent;end"), "geblokkeerd: andere sites, http en andere soorten adressen");
        check(!Nieuws.cookieAdres("tweakers", "https://sp.dpgmedia.net/sp.js") && !Nieuws.cookieAdres("tweakers", "https://c.dpgmedia.net/a/b") && !Nieuws.cookieAdres("tweakers", "https://pg.dpgmedia.net/api/metrics")
                && !Nieuws.cookieAdres("tweakers", "https://account.dpgmedia.nl/login") && !Nieuws.cookieAdres("tweakers", "https://dpgmedia.nl/"), "geblokkeerd: de meetdiensten en het inloggen van DPG Media");
        // De echte stroom: tweakers.net → myprivacy.dpgmedia.nl → tweakers.net/privacy-gate/store/ (bewaart) → tweakers.net
        String keuze = "https://myprivacy.dpgmedia.nl/consent?siteKey=kzz6o4y8ybwvpkea&callbackUrl=https://tweakers.net/privacy-gate/store/?returnUrl=https%3A%2F%2Ftweakers.net%2Fnieuws%2F";
        check("site".equals(Nieuws.cookieStap("tweakers", "https://tweakers.net/nieuws/")) && "keuze".equals(Nieuws.cookieStap("tweakers", keuze))
                && "bewaar".equals(Nieuws.cookieStap("tweakers", "https://tweakers.net/privacy-gate/store/?returnUrl=https%3A%2F%2Ftweakers.net%2Fnieuws%2F"))
                && "bewaar".equals(Nieuws.cookieStap("tweakers", "https://tweakers.net/privacygate-confirm?redirectUri=x")) && "site".equals(Nieuws.cookieStap("tweakers", "https://www.tweakers.net/"))
                && "site".equals(Nieuws.cookieStap("tweakers", "https://tweakers.net/nieuws")) && "keuze".equals(Nieuws.cookieStap("tweakers", "https://pg.dpgmedia.nl/x"))
                && "".equals(Nieuws.cookieStap("tweakers", "https://www.google.com/")) && "".equals(Nieuws.cookieStap("nos", "https://nos.nl/")), "stappen van het venster: site, keuze, bewaar");
        check("".equals(Nieuws.cookieStap("tweakers", "https://tweakers.net/my.tnet/login/")) && "".equals(Nieuws.cookieStap("tweakers", "https://tweakers.net/nieuws/245678/titel.html"))
                && "".equals(Nieuws.cookieStap("tweakers", "https://pg.tweakers.net/api/")) && "".equals(Nieuws.cookieStap("tweakers", "https://tweakimg.net/g/x.png"))
                && "".equals(Nieuws.cookieStap("tweakers", "https://myprivacy-static.dpgmedia.net/consent.js")), "als pagina geblokkeerd: inloggen, artikelen en wat alleen onderdeel van een pagina is");
        // Wat het venster zegt (keuzeGezien, bewaard, fout, cookies veranderd)
        String start = "https://tweakers.net/nieuws/", bewaar = "https://tweakers.net/privacy-gate/store/?returnUrl=x";
        check("al".equals(Nieuws.cookieVenster("tweakers", start, false, false, false, false)), "keuze was er al: site meteen");
        check("kiezen".equals(Nieuws.cookieVenster("tweakers", keuze, true, false, false, false)) && "".equals(Nieuws.cookieVenster("tweakers", bewaar, true, true, false, true))
                && "klaar".equals(Nieuws.cookieVenster("tweakers", start, true, true, false, true)), "keuze → bewaren → site met nieuwe cookies: klaar");
        check("nagaan".equals(Nieuws.cookieVenster("tweakers", start, true, true, false, false)) && "nagaan".equals(Nieuws.cookieVenster("tweakers", start, true, false, false, true)),
                "bewaren zonder nieuwe cookies, of zonder bewaren terug op de site: niet vanzelf klaar");
        check("al".equals(Nieuws.cookieVenster("tweakers", start, false, true, false, true)), "bewaren zonder de toestemmingspagina: niet vanzelf klaar");
        check("".equals(Nieuws.cookieVenster("tweakers", start, true, true, true, true)) && "".equals(Nieuws.cookieVenster("tweakers", keuze, true, false, true, false)),
                "een foutpagina (ook een 5xx op de site na het bewaren): niets veranderen");
        check("".equals(Nieuws.cookieVenster("tweakers", "https://tweakers.net/my.tnet/login/", true, true, false, true)) && "".equals(Nieuws.cookieVenster("nos", "https://nos.nl/", true, true, false, true)),
                "geblokkeerde pagina of andere bron: niets");
        gehaald.clear();
        NieuwsFeed.Feed tf3 = tweakers();
        Nieuws.beelden("tweakers", "https://tweakers.net/feeds/mixed.xml", tf3, tcache, System.currentTimeMillis() + 60_000, null);
        NieuwsFeed.Feed tf4 = tweakers();
        Nieuws.beelden("tweakers", "https://tweakers.net/feeds/mixed.xml", tf4, null, System.currentTimeMillis() + 60_000, null);
        check("https://tweakers.net/i/1.jpg".equals(tf3.artikelen.get(0).beeld) && tf4.artikelen.get(0).beeld.isEmpty() && !tf4.artikelen.get(0).zonderBeeld && gehaald.isEmpty(), "afbeeldingen uit: geen pagina's, wel wat de cache al had");
        Nieuws.Pagina weigert = (url, tot) -> { gehaald.add(url); throw new Nieuws.HttpFout(403); };
        NieuwsFeed.Feed tf5 = tweakers();
        Nieuws.beelden("tweakers", "https://tweakers.net/feeds/mixed.xml", tf5, null, System.currentTimeMillis() + 60_000, weigert);
        check(gehaald.size() == 1 && !tf5.artikelen.get(0).zonderBeeld && !tf5.artikelen.get(1).zonderBeeld && "fout".equals(tf5.beeldStand), "de site weigert: na één pagina stoppen, niets als zonder afbeelding onthouden");
        check("403".equals(tf5.beeldReden), "de reden: de HTTP-code");
        File tc5 = tmp("tweakers-reden");
        Nieuws.bewaarFeed(tc5, "tweakers", "https://tweakers.net/feeds/mixed.xml", tf5, nu);
        JSONObject c5 = new JSONObject(new String(Files.readAllBytes(Nieuws.cacheBestand(tc5, "tweakers").toPath()), StandardCharsets.UTF_8));
        File td5 = tmp("tweakers-reden-dir");
        Nieuws.zetAan(td5, "tweakers", true);
        check("403".equals(c5.optString("beeldReden")) && "403".equals(bron(Nieuws.lijst(td5, tc5).getJSONArray("bronnen"), "tweakers").optString("beeldReden")), "de reden in de cache en in de lijst");
        gehaald.clear();
        NieuwsFeed.Feed tf6 = tweakers();
        Nieuws.beelden("tweakers", "https://tweakers.net/feeds/mixed.xml", tf6, c5, System.currentTimeMillis() + 60_000, site);
        check("ok".equals(tf6.beeldStand) && "".equals(tf6.beeldReden), "daarna lukt het: de reden is weg");
        NieuwsFeed.Feed tf7 = tweakers();
        Nieuws.beelden("tweakers", "https://tweakers.net/feeds/mixed.xml", tf7, c5, System.currentTimeMillis() + 60_000, null);
        check("fout".equals(tf7.beeldStand) && "403".equals(tf7.beeldReden), "afbeeldingen uit (niets opgehaald): stand en reden blijven");
        Nieuws.bewaarFeed(tc5, "tweakers", "https://tweakers.net/feeds/mixed.xml", tf6, nu);
        JSONObject c6 = new JSONObject(new String(Files.readAllBytes(Nieuws.cacheBestand(tc5, "tweakers").toPath()), StandardCharsets.UTF_8));
        check(!c6.has("beeldReden") && !bron(Nieuws.lijst(td5, tc5).getJSONArray("bronnen"), "tweakers").has("beeldReden"), "geen reden: ook niet in de cache of de lijst");
        c6.put("beeldStand", "fout").put("beeldReden", "403");
        gehaald.clear();
        NieuwsFeed.Feed tf8 = tweakers();
        Nieuws.beelden("tweakers", "https://tweakers.net/feeds/mixed.xml", tf8, c6, System.currentTimeMillis() + 60_000, site);
        check(gehaald.isEmpty() && "fout".equals(tf8.beeldStand) && "403".equals(tf8.beeldReden), "alles al bekend (niets opgehaald): stand en reden blijven");
        NieuwsFeed.Feed tf9 = tweakers();
        Nieuws.beelden("tweakers", "https://tweakers.net/feeds/mixed.xml", tf9, c5, System.currentTimeMillis() + 60_000, (url, tot) -> { throw new Nieuws.Doorverwezen(); });
        check("cookiekeuze".equals(tf9.beeldStand) && "".equals(tf9.beeldReden), "cookiekeuze: de reden van de fout is weg");
        NieuwsFeed.Feed tfa = tweakers();
        Nieuws.beelden("tweakers", "https://tweakers.net/feeds/mixed.xml", tfa, c6, System.currentTimeMillis() + 3_000, (url, tot) -> { throw new Nieuws.TeTraag(); });
        NieuwsFeed.Feed tfb = tweakers();
        Nieuws.beelden("tweakers", "https://tweakers.net/feeds/mixed.xml", tfb, null, System.currentTimeMillis() + 60_000, (url, tot) -> { throw new Nieuws.TeTraag(); });
        check("fout".equals(tfa.beeldStand) && "403".equals(tfa.beeldReden) && "fout".equals(tfb.beeldStand) && "traag".equals(tfb.beeldReden),
                "te traag: alleen een fout als de pagina zelf te lang duurde, niet als de tijd voor de hele bron op was");
        check(Nieuws.beeldLog("ok", "", false).equals("afbeeldingen van de pagina: ok") && Nieuws.beeldLog("ok", "", true).equals("afbeeldingen van de pagina: ok (als Tools)")
                && Nieuws.beeldLog("fout", "403", true).equals("afbeeldingen van de pagina: fout (403)") && Nieuws.beeldLog("fout", "", false).equals("afbeeldingen van de pagina: fout")
                && Nieuws.beeldLog("cookiekeuze", "", true).equals("afbeeldingen van de pagina: cookiekeuze") && Nieuws.beeldLog("", "403", true).isEmpty() && Nieuws.beeldLog(null, null, false).isEmpty(),
                "logregel: vaste woorden en de code, niets als er geen stand is");
        check(Nieuws.talen("nl-NL,en-US").equals("nl-NL,nl;q=0.9,en-US;q=0.8,en;q=0.7") && Nieuws.talen("en-GB").equals("en-GB,en;q=0.9")
                && Nieuws.talen("nl").equals("nl") && Nieuws.talen("nl-NL,nl,en").equals("nl-NL,nl;q=0.9,en;q=0.8")
                && Nieuws.talen(null).equals("nl-NL,nl;q=0.9,en;q=0.8") && Nieuws.talen("").equals("nl-NL,nl;q=0.9,en;q=0.8")
                && Nieuws.talen("nl-NL\r\nCookie: x,en").equals("en") && Nieuws.talen("a,b-c,ü-NL").equals("nl-NL,nl;q=0.9,en;q=0.8")
                && Nieuws.talen("de,fr,it,es,pt,pl,sv,da,fi,no,cs").split(",").length == 8
                && Nieuws.talen("nl-NL-u-mu-celsius,en-US").equals("nl-NL,nl;q=0.9,en-US;q=0.8,en;q=0.7") && Nieuws.talen("nl-NL-u-mu-celsius").equals("nl-NL,nl;q=0.9")
                && Nieuws.talen("zh-Hant-TW,NL-nl").equals("zh-TW,zh;q=0.9,nl-NL;q=0.8,nl;q=0.7") && Nieuws.talen("es-419").equals("es-419,es;q=0.9")
                && Nieuws.talen("de-DE,fr-FR,it-IT,es-ES,pt-PT").equals("de-DE,de;q=0.9,fr-FR;q=0.8,fr;q=0.7,it-IT;q=0.6,it;q=0.5,es-ES;q=0.4,es;q=0.3"),
                "Accept-Language uit de talen van de telefoon, zoals de WebView: alleen geldige taalcodes, hooguit acht");
        check("traag".equals(Nieuws.reden(new java.net.SocketTimeoutException())) && "traag".equals(Nieuws.reden(new Nieuws.TeTraag()))
                && "verbinding".equals(Nieuws.reden(new java.net.UnknownHostException("x"))) && "verbinding".equals(Nieuws.reden(new java.net.ConnectException()))
                && "beveiliging".equals(Nieuws.reden(new javax.net.ssl.SSLHandshakeException("x"))) && "anders".equals(Nieuws.reden(new RuntimeException("geheim: tweakers.net/nieuws/1"))),
                "reden: alleen vaste woorden, nooit de fouttekst");
        // Artikelpagina's met de User-Agent van de WebView (de cookies horen bij die browser)
        String wvUa = "Mozilla/5.0 (Linux; Android 14; Pixel 7 Build/AP2A; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/130.0.0.0 Mobile Safari/537.36";
        check(wvUa.equals(Nieuws.paginaUa(wvUa)) && wvUa.equals(Nieuws.paginaUa(" " + wvUa + " ")) && Nieuws.UA.equals(Nieuws.paginaUa(null)) && Nieuws.UA.equals(Nieuws.paginaUa(""))
                && Nieuws.UA.equals(Nieuws.paginaUa("Mozilla\r\nCookie: x=1")) && Nieuws.UA.equals(Nieuws.paginaUa("Mozilla/5.0 (Linux; Android 14; Ünïcode)"))
                && Nieuws.UA.equals(Nieuws.paginaUa(new String(new char[600]).replace('\0', 'a'))), "User-Agent van de WebView, anders de eigen naam (en nooit iets wat de kopregel breekt)");
        List<String> namen = new ArrayList<>();
        Nieuws.WebPagina alsTools = new Nieuws.WebPagina((url, tot, ua) -> { namen.add(ua); if (!Nieuws.UA.equals(ua)) throw new Nieuws.HttpFout(403); Nieuws.Antwoord ant = new Nieuws.Antwoord(); ant.data = "<head></head>".getBytes(StandardCharsets.UTF_8); return ant; }, wvUa);
        alsTools.haal("https://tweakers.net/nieuws/1/een.html", nu + 60_000);
        alsTools.haal("https://tweakers.net/nieuws/2/twee.html", nu + 60_000);
        check(namen.equals(Arrays.asList(wvUa, Nieuws.UA, Nieuws.UA)) && alsTools.alsTools(), "weigert de site de WebView-naam: die pagina nog eens als Tools, de rest van de ronde ook: " + namen);
        namen.clear();
        Nieuws.WebPagina alsWv = new Nieuws.WebPagina((url, tot, ua) -> { namen.add(ua); Nieuws.Antwoord ant = new Nieuws.Antwoord(); ant.data = "<head></head>".getBytes(StandardCharsets.UTF_8); return ant; }, wvUa);
        alsWv.haal("https://tweakers.net/nieuws/1/een.html", nu + 60_000);
        alsWv.haal("https://tweakers.net/nieuws/2/twee.html", nu + 60_000);
        check(namen.equals(Arrays.asList(wvUa, wvUa)) && !alsWv.alsTools(), "lukt het met de WebView-naam: geen tweede poging");
        for (int code : new int[]{403, 404, 503}) {
            namen.clear();
            String fout = null;
            try {
                new Nieuws.WebPagina((url, tot, ua) -> { namen.add(ua); throw new Nieuws.HttpFout(code); }, code == 403 ? null : wvUa).haal("https://tweakers.net/nieuws/1/een.html", nu + 60_000);
            } catch (Nieuws.HttpFout e) { fout = String.valueOf(e.code); }
            check(String.valueOf(code).equals(fout) && namen.size() == 1, code + ": geen tweede poging (403 als het al de eigen naam was), de fout gaat door");
        }
        namen.clear();
        try {
            new Nieuws.WebPagina((url, tot, ua) -> { namen.add(ua); throw new Nieuws.HttpFout(403); }, wvUa).haal("https://tweakers.net/nieuws/1/een.html", nu + 60_000);
            check(false, "beide namen geweigerd: een fout");
        } catch (Nieuws.HttpFout e) { check(e.code == 403 && namen.size() == 2, "beide namen geweigerd: 403, twee pogingen"); }
        gehaald.clear();
        NieuwsFeed.Feed nosf = tweakers();
        Nieuws.beelden("nos", "https://feeds.nos.nl/nosnieuwsalgemeen", nosf, null, System.currentTimeMillis() + 60_000, site);
        NieuwsFeed.Feed veel = new NieuwsFeed.Feed();
        for (int i = 0; i < 30; i++) { NieuwsFeed.Artikel x = new NieuwsFeed.Artikel(); x.link = "https://tweakers.net/nieuws/" + (100 + i) + "/x.html"; x.sleutel = x.link; x.titel = "x" + i; veel.artikelen.add(x); }
        Nieuws.beelden("tweakers", "https://tweakers.net/feeds/mixed.xml", veel, null, System.currentTimeMillis() + 60_000, site);
        check(gehaald.size() == Nieuws.MAX_PAGINAS && veel.artikelen.get(0).beeld.endsWith("/i/100.jpg") && veel.artikelen.get(29).beeld.isEmpty(), "andere bronnen niet; hooguit " + Nieuws.MAX_PAGINAS + " pagina's per ronde: " + gehaald.size());
        gehaald.clear();
        NieuwsFeed.Feed traag = tweakers();
        Nieuws.beelden("tweakers", "https://tweakers.net/feeds/mixed.xml", traag, null, System.currentTimeMillis() - 1, (url, tot) -> {
            gehaald.add(url);
            if (System.currentTimeMillis() >= tot) throw new Nieuws.TeTraag();
            return "";
        });
        check(gehaald.isEmpty() && traag.artikelen.get(0).beeld.isEmpty() && !traag.artikelen.get(0).zonderBeeld, "geen tijd meer: geen pagina's, niets onthouden");
        Nieuws.zetAan(dir, "nu", true);
        Nieuws.zetAan(dir, "rijnmond", false);
        bs = Nieuws.bronnen(dir);
        check(bron(bs, "nu").optBoolean("aan") && !bron(bs, "rijnmond").optBoolean("aan"), "aan- en uitzetten bewaard");
        check(geeftFout(() -> Nieuws.zetAan(dir, "bestaat-niet", true)), "onbekende bron: fout");
        check(Nieuws.cacheBestand(cache, "nos").equals(new File(cache, "nos.json")) && Nieuws.cacheBestand(cache, "e0123456789abcdef").getParentFile().equals(cache), "cachebestand van een bron staat in de cachemap");
        for (int i = 0; i < bs.length(); i++) Nieuws.cacheBestand(cache, bs.getJSONObject(i).getString("id")); // elke bron (ook de vaste) heeft een geldig id
        for (String slecht : new String[]{"../dir/bronnen", "a/b", "", "NOS", null}) {
            boolean geweigerd;
            try { Nieuws.cacheBestand(cache, slecht); geweigerd = false; } catch (IllegalArgumentException x) { geweigerd = true; }
            check(geweigerd, "geen bron-id, geen cachebestand: " + slecht);
        }

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
        // Een ronde die pas klaar is na een nieuwere (een trage site): overschrijft niets
        check(Nieuws.bewaarFeed(cache, "nos", "https://nieuws.example.org/rss.xml", rss(), nu + 1_800_000L) == 0, "oudere ronde: niets nieuws");
        Nieuws.bewaarFout(cache, "nos", "Oud", nu + 1_800_000L);
        l = Nieuws.lijst(dir, cache);
        nb = bron(l.getJSONArray("bronnen"), "nos");
        check(l.getJSONArray("artikelen").length() == 6 && "Geen verbinding".equals(nb.optString("fout")) && nb.getLong("poging") == nu + 7_200_000L, "oudere ronde overschrijft de nieuwere niet");
        // Klok teruggezet: een bewaarde tijd in de toekomst houdt bijwerken niet tegen
        File dk = tmp("klok-dir"), ck = tmp("klok-cache");
        long morgen = System.currentTimeMillis() + 86_400_000L;
        Nieuws.bewaarFout(ck, "nos", "Geen verbinding", morgen);
        Nieuws.bewaarFeed(ck, "nos", "https://x.example.org/", rss(), nu);
        l = Nieuws.lijst(dk, ck);
        check(l.getJSONArray("artikelen").length() > 0 && bron(l.getJSONArray("bronnen"), "nos").getLong("t") == nu, "tijd in de toekomst (klok teruggezet): toch bijgewerkt");
        Nieuws.bewaarFeed(ck, "nos", "https://x.example.org/", rss(), morgen);
        Nieuws.bewaarFout(ck, "nos", "Geen verbinding", morgen);
        boolean nosTeDoen = false;
        for (String[] b : Nieuws.teDoen(dk, ck, false, nu)) nosTeDoen |= "nos".equals(b[0]);
        check(nosTeDoen, "bijgewerkt of mislukt 'morgen' (klok teruggezet): vanzelf weer bijwerken");
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
        Nieuws.bewaar(dir, cache, id, true, later + 1);
        bw = Nieuws.lijst(dir, cache).getJSONArray("bewaard");
        check(bw.length() == 1 && bw.getJSONObject(0).getString("id").equals(id) && Nieuws.artikel(dir, cache, id).getJSONArray("blokken").length() >= 5,
                "per ongeluk niet meer bewaard: opnieuw bewaren kan, ook als het niet meer in de feed staat");
        Nieuws.bewaar(dir, cache, id, false, later + 2);

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
        check(!Nieuws.https("https://192.168.1.1/feed") && !Nieuws.https("https://localhost./rss") && geeftFout(() -> Nieuws.adres("10.0.0.2/rss")) && geeftFout(() -> Nieuws.adres("http://[::1]/"))
                && geeftFout(() -> Nieuws.adres("nas.local/feed")), "geen adressen op het toestel of in het thuisnetwerk");
        for (String ip : new String[]{"127.0.0.1", "10.0.0.8", "192.168.2.1", "172.20.1.1", "169.254.3.4", "100.100.1.1", "0.0.0.0", "224.0.0.1", "240.0.0.1", "255.255.255.255", "::1", "fe80::1", "fd12:3456::1", "::ffff:127.0.0.1", "::ffff:192.168.1.1"})
            check(Nieuws.intern(InetAddress.getByName(ip)), "naam die naar " + ip + " wijst: niet ophalen");
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xff; mapped[11] = (byte) 0xff; mapped[12] = 10; mapped[15] = 1;
        check(Nieuws.intern(Inet6Address.getByAddress(null, mapped, -1)), "IPv6-vorm van 10.0.0.1: niet ophalen");
        mapped[12] = 8; mapped[13] = 8; mapped[14] = 8; mapped[15] = 8;
        check(!Nieuws.intern(Inet6Address.getByAddress(null, mapped, -1)), "IPv6-vorm van 8.8.8.8: openbaar");
        for (String ip : new String[]{"198.18.0.1", "198.19.255.254", "192.0.0.8", "64:ff9b::7f00:1", "64:ff9b::c0a8:101", "64:ff9b:1::808:808", "2002:7f00:1::1", "2002:c0a8:101::1", "2002:a00:1::", "::127.0.0.1", "::10.0.0.1"})
            check(Nieuws.intern(InetAddress.getByName(ip)), "niet openbaar (testnet, NAT64, 6to4 of ingebed IPv4): " + ip);
        for (String ip : new String[]{"145.58.29.30", "8.8.8.8", "2a00:1450::1", "100.128.0.1", "198.20.0.1", "198.17.255.1", "192.0.2.1", "64:ff9b::808:808", "2002:808:808::1"}) check(!Nieuws.intern(InetAddress.getByName(ip)), "openbaar adres " + ip);
        check("nu".equals(Nieuws.bekendeSite("https://www.nu.nl/")) && "nos-sport".equals(Nieuws.bekendeSite("https://nos.nl/sport")) && Nieuws.bekendeSite("https://nu.nl/rss/Sport") == null, "bekende sites");
        check("https://a.example.org/x?id=1&b=2#k".equals(Nieuws.schoon("https://a.example.org/x?utm_source=rss&id=1&at_medium=RSS&b=2&fbclid=z#k")), "volgparameters weg, de rest blijft");
        check("https://a.example.org/x".equals(Nieuws.schoon("https://a.example.org/x?utm_campaign=y")), "alleen volgparameters: geen ? meer");
        check("".equals(Nieuws.link("javascript:alert(1)", "https://a.example.org/")) && "".equals(Nieuws.link("data:text/html,x", "https://a.example.org/")), "link: alleen http(s)");

        // Feed op een webpagina
        String html = new String(Files.readAllBytes(Paths.get("tests/jvm/nieuws/pagina.html")), StandardCharsets.UTF_8);
        check("https://site.example.org/feed.xml?soort=alles&taal=nl".equals(Nieuws.zoekFeed(html, "https://site.example.org/nieuws/")), "feed op de pagina gevonden (https, relatief): " + Nieuws.zoekFeed(html, "https://site.example.org/nieuws/"));
        check(Nieuws.zoekFeed("<link rel=\"alternate\" type=\"application/rss+xml\" href=\"http://x.example.org/rss\">", "https://x.example.org/") == null, "feed via http: niet");
        check(Nieuws.zoekFeed("<link rel=\"stylesheet\" type=\"application/rss+xml\" href=\"/rss\">", "https://x.example.org/") == null, "geen alternate: niet");
        check(Nieuws.zoekFeed("<link rel=\"alternate\" type=\"application/rss+xml\" href=\">", "https://x.example.org/") == null, "een losse \" in een link-tag: geen fout");

        // Fouten voor Rene
        check("Geen verbinding".equals(Nieuws.kort(new java.net.UnknownHostException("x"))) && "Geen verbinding".equals(Nieuws.kort(new java.net.SocketTimeoutException())), "geen netwerk");
        check("Geen veilige verbinding met de nieuwssite".equals(Nieuws.kort(new javax.net.ssl.SSLHandshakeException("cert"))), "certificaat");
        check("Feed is te groot".equals(Nieuws.kort(new java.io.IOException("Feed is te groot"))), "te groot");
        check("Bijwerken lukt niet".equals(Nieuws.kort(new RuntimeException("NullPointer at x.y"))), "onbekende fout: geen technische tekst");
        check("Deze feed is niet goed leesbaar".equals(Nieuws.kort(new org.xml.sax.SAXParseException("x", null))), "kapotte XML");
        check("Deze feed wordt niet gelezen (onveilige opbouw)".equals(Nieuws.kort(new NieuwsFeed.Onveilig())), "onveilige opbouw: eigen tekst");
        check("Toevoegen lukt niet".equals(Nieuws.kort(new RuntimeException("x"), "Toevoegen lukt niet")) && "Geen verbinding".equals(Nieuws.kort(new java.net.UnknownHostException("x"), "Toevoegen lukt niet")),
                "toevoegen: eigen tekst bij een onbekende fout");

        // Backup: nieuws/ gaat mee in Alle appgegevens, niet in de Android-backup
        check(AppData.FILE_KNOWN.contains("nieuws") && !AppData.FILE_SKIP.containsKey("nieuws"), "Alle appgegevens: nieuws/ gaat mee");
        String br = new String(Files.readAllBytes(Paths.get("app/res/xml/backup_rules.xml")), StandardCharsets.UTF_8);
        String dr = new String(Files.readAllBytes(Paths.get("app/res/xml/data_rules.xml")), StandardCharsets.UTF_8);
        String ex = "<exclude domain=\"file\" path=\"nieuws/\" />";
        int cb = dr.indexOf("<cloud-backup>"), dt = dr.indexOf("<device-transfer>");
        check(br.contains(ex) && cb >= 0 && dt > cb && dr.substring(cb, dt).contains(ex) && dr.substring(dt).contains(ex), "Android-backup en overzetten: nieuws/ uitgesloten");
        for (String c : new String[]{"app_webview/Cookies", "app_webview/Cookies-journal", "app_webview/Cookies-wal", "app_webview/Cookies-shm", "app_webview/Default/Cookies",
                "app_webview/Default/Cookies-journal", "app_webview/Default/Cookies-wal", "app_webview/Default/Cookies-shm", "app_webview/Default/Network/"}) {
            String wv = "<exclude domain=\"root\" path=\"" + c + "\" />";
            check(br.contains(wv) && dr.substring(cb, dt).contains(wv) && dr.substring(dt).contains(wv), "Android-backup en overzetten: cookies van de WebView uitgesloten: " + c);
        }
        String heelWv = "path=\"app_webview/\"";
        check(!br.contains(heelWv) && !dr.contains(heelWv), "de rest van app_webview/ (localStorage met de instellingen van de interface) gaat wel mee");

        System.out.println(fails == 0 ? "alles goed" : fails + " fout(en)");
        System.exit(fails == 0 ? 0 : 1);
    }
}
