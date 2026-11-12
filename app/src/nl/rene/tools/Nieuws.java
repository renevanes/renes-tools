package nl.rene.tools;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nieuws: artikelen uit de feeds (RSS/Atom) van nieuwssites. Tools haalt elke feed rechtstreeks bij de site op (alleen
 * https; geen tussendienst, account of volgen); wat Rene leest en bewaart blijft op de telefoon.
 * <ul>
 * <li>{@code nieuws/bronnen.json}: welke bronnen aan staan en de eigen bronnen;</li>
 * <li>{@code nieuws/gelezen.json}: wat gelezen is (60 dagen);</li>
 * <li>{@code nieuws/bewaard.json}: bewaarde artikelen (een volledige kopie, ook als de feed ze niet meer heeft).</li>
 * </ul>
 * Die gaan mee in "Alle appgegevens", niet in de Android-backup. De opgehaalde artikelen staan in de cache
 * ({@code cache/nieuws/<bron>.json}) en komen bij het bijwerken opnieuw.
 */
final class Nieuws {
    private Nieuws() { }

    static final int MAX_PER_BRON = 60, MAX_EIGEN = 30, MAX_BEWAARD = 300, MAX_GELEZEN = 5000, MAX_LIJST = 800;
    static final int MAX_FEED = 4 * 1024 * 1024, MAX_HOPS = 6;
    static final long VERS = 15 * 60_000L, OPNIEUW_NA_FOUT = 2 * 60_000L, GELEZEN_BEWAREN = 60L * 86_400_000L, MAX_DUUR = 90_000L, MAX_HAAL = 60_000L;
    /** Geen naam of adres van Rene of de repository: een nieuwssite hoeft niet te weten wie er leest. */
    static final String UA = "Mozilla/5.0 (Linux; Android) Nieuwslezer/1.0";

    /**
     * Bronnen die de app kent: id, naam, adres, standaard aan ("1"), de sites ervan (wie zo'n site intikt, zet de bron aan:
     * veel sites noemen hun feed niet of tonen eerst een toestemmingspagina) en eventueel hoe de link van een artikel moet
     * beginnen (een feed die nieuws mengt met andere dingen). Naam en adres komen altijd uit deze lijst.
     */
    static final String[][] STANDAARD = {
        {"nos", "NOS Nieuws", "https://feeds.nos.nl/nosnieuwsalgemeen", "1", "nos.nl"},
        {"rijnmond", "Rijnmond", "https://www.rijnmond.nl/rss/index.xml", "1", "rijnmond.nl"},
        {"nos-sport", "NOS Sport", "https://feeds.nos.nl/nossportalgemeen", "", "nos.nl/sport"},
        {"nu", "NU.nl", "https://www.nu.nl/rss/Algemeen", "", "nu.nl"},
        {"rtl", "RTL Nieuws", "https://www.rtl.nl/rss.xml", "", "rtl.nl rtlnieuws.nl"},
        {"nrc", "NRC", "https://www.nrc.nl/rss/", "", "nrc.nl"},
        {"volkskrant", "de Volkskrant", "https://www.volkskrant.nl/voorpagina/rss.xml", "", "volkskrant.nl"},
        {"ad", "AD", "https://www.ad.nl/home/rss.xml", "", "ad.nl"},
        // De gemengde feed is de enige die ook vanaf servers werkt; downloads, reviews en aanbiedingen eruit
        {"tweakers", "Tweakers Nieuws", "https://tweakers.net/feeds/mixed.xml", "", "tweakers.net tweakers.net/nieuws", "tweakers.net/nieuws/"},
        {"bbc", "BBC News", "https://feeds.bbci.co.uk/news/rss.xml", "", "bbc.co.uk bbc.com bbc.co.uk/news bbc.com/news"},
        {"guardian", "The Guardian", "https://www.theguardian.com/world/rss", "", "theguardian.com theguardian.com/world"},
        // Geen RSS (beehiiv zet die alleen aan als de uitgever het kiest): de sitemap, alleen de artikelen (/p/), niet de podcast
        {"aireport", "AI Report", "https://www.aireport.nl/sitemap.xml", "1", "aireport.nl", "aireport.nl/p/"},
    };

    /**
     * Bronnen waarbij Inloggen bij de website standaard aan staat: id, de pagina waar het inlogvenster begint en de sites die
     * dat venster mag openen en waarheen de cookies van de inlog meegaan ({@link #siteMag}). Net als alles van een bron is dit
     * per bron aan te passen; bij de andere bronnen staat inloggen standaard uit, met de site van de bron als begin. Het
     * vierde veld: "1" = cookies van andere sites toestaan in het inlogvenster ({@code derden}; beehiiv logt in via eigen
     * diensten en een controle tegen robots, die zonder die cookies blijven hangen).
     */
    static final String[][] INLOG_STANDAARD = {
        {"aireport", "https://www.aireport.nl/", "aireport.nl *.aireport.nl *.beehiiv.com", "1"},
    };

    /** Staan cookies van andere sites in het inlogvenster van deze bron standaard aan? */
    static boolean derdenStandaard(String id) {
        String[] il = inlogStandaard(id);
        return il != null && il.length > 3 && "1".equals(il[3]);
    }

    /** De bekende bron bij een site die Rene intikt ("nu.nl", "https://www.nos.nl/"), of null. */
    static String bekendeSite(String adres) {
        String s = norm(adres);
        for (String[] b : STANDAARD) for (String site : b[4].split(" ")) if (s.equals(site)) return b[0];
        return null;
    }

    /** Hoe de link van een artikel van deze bron moet beginnen (zonder https:// en www.), of "" als alles mag. */
    static String alleen(String id) {
        for (String[] b : STANDAARD) if (b[0].equals(id)) return b.length > 5 ? b[5] : "";
        return "";
    }

    /** Alleen de artikelen die bij de bron horen ({@link #alleen}). Blijft er niets over, dan is dat een fout: de vorige artikelen blijven. */
    static void filter(String id, String basis, NieuwsFeed.Feed f) throws Fout { filterOp(alleen(id), basis, f); }

    /** Zo ook met een eigen begin van de link ({@code alleen}, zoals in de instellingen van de bron; "" = alles). */
    static void filterOp(String alleen, String basis, NieuwsFeed.Feed f) throws Fout {
        if (alleen == null || alleen.isEmpty() || f.artikelen.isEmpty()) return;
        for (Iterator<NieuwsFeed.Artikel> it = f.artikelen.iterator(); it.hasNext(); ) {
            NieuwsFeed.Artikel a = it.next();
            String l;
            try { l = norm(new URL(new URL(basis), a.link).toURI().normalize().toString()); } catch (Exception e) { l = ""; }
            if (!l.startsWith(alleen)) it.remove();
        }
        if (f.artikelen.isEmpty()) throw new Fout(Taal.T("In deze feed staat nu geen nieuws"));
    }

    /**
     * Bronnen waarvan de feed geen afbeeldingen heeft: die komen dan van de artikelpagina zelf ({@link #beelden}). Per bron:
     * id, naam, waar Rene zijn cookiekeuze maakt (de site vraagt die eerst), de hosts die de pagina's daarbij mogen laden
     * (de rest is geblokkeerd; "*." = alle subdomeinen) en de hosts van de toestemmingspagina zelf. Van DPG Media alleen
     * precies de hosts die de toestemmingspagina nodig heeft, zodat de meetdiensten er niet bij zitten.
     */
    static final String[][] BEELD_VAN_PAGINA = {
        {"tweakers", "Tweakers", "https://tweakers.net/nieuws/",
            "tweakers.net *.tweakers.net tweakimg.net *.tweakimg.net myprivacy.dpgmedia.nl myprivacy-static.dpgmedia.net pg.dpgmedia.nl",
            "myprivacy.dpgmedia.nl pg.dpgmedia.nl"},
    };
    static final int MAX_PAGINA = 128 * 1024, MAX_PAGINAS = 20;
    static final long MAX_PER_PAGINA = 10_000L;

    static String[] paginaBron(String id) {
        for (String[] b : BEELD_VAN_PAGINA) if (b[0].equals(id)) return b;
        return null;
    }

    /** Mag het venster voor de cookiekeuze van deze bron dit adres laden? Alleen https naar de hosts van de bron. */
    static boolean cookieAdres(String id, String url) {
        String[] b = paginaBron(id);
        if (b == null || url == null || !https(url)) return false;
        String h;
        try { h = new URL(url).getHost().toLowerCase(Locale.ROOT); } catch (Exception e) { return false; }
        for (String d : b[3].split(" ")) if (d.startsWith("*.") ? h.endsWith(d.substring(1)) : h.equals(d)) return true;
        return false;
    }

    /**
     * Welke pagina het venster voor de cookiekeuze opent (alleen de pagina zelf, niet wat ze laadt): "keuze" (de
     * toestemmingspagina), "bewaar" (de site bewaart de keuze: /privacy-gate/…), "site" (de pagina waar het venster begint,
     * of de voorpagina) of "" (geblokkeerd: andere pagina's, zoals inloggen of een artikel, gaan in dit venster niet open).
     */
    static String cookieStap(String id, String url) {
        String[] b = paginaBron(id);
        if (b == null || !cookieAdres(id, url)) return "";
        try {
            URL u = new URL(url);
            String h = u.getHost().toLowerCase(Locale.ROOT), p = zonderSlash(u.getPath().toLowerCase(Locale.ROOT));
            if (Arrays.asList(b[4].split(" ")).contains(h)) return "keuze";
            if (!host(url).equals(host(b[2]))) return "";
            if (p.startsWith("/privacy-gate") || p.startsWith("/privacygate")) return "bewaar";
            return p.isEmpty() || p.equals(zonderSlash(new URL(b[2]).getPath())) ? "site" : "";
        } catch (Exception e) { return ""; }
    }

    /**
     * Wat het venster voor de cookiekeuze laat zien als pagina {@code url} geladen is: "klaar" (de toestemmingspagina en het
     * bewaren kwamen voorbij en de cookies van de site zijn veranderd: sluiten), "kiezen" (de toestemmingspagina), "nagaan"
     * (terug op de site, maar of de keuze bewaard is, is niet zeker), "al" (de keuze was er al) of "" (niets veranderen:
     * een tussenstap, een fout of een geblokkeerde pagina).
     */
    static String cookieVenster(String id, String url, boolean keuzeGezien, boolean bewaard, boolean fout, boolean koekjesAnders) {
        if (fout) return "";
        String stap = cookieStap(id, url);
        if (stap.equals("keuze")) return "kiezen";
        if (!stap.equals("site")) return "";
        return keuzeGezien && bewaard && koekjesAnders ? "klaar" : keuzeGezien ? "nagaan" : "al";
    }

    /**
     * Gaan er cookies mee naar dit adres? Alleen naar de site zelf van een bron uit {@link #BEELD_VAN_PAGINA}: de WebView
     * deelt één cookiepot met de rest van de app (zoals de inlog bij een leverancier in Facturen).
     */
    static boolean koekjesNaar(String url) {
        if (url == null || !https(url)) return false;
        for (String[] b : BEELD_VAN_PAGINA) if (host(url).equals(host(b[2]))) return true;
        return false;
    }

    /** De namen in een Cookie-kop ("a=1; b=2"), gesorteerd: de waarden ververst de site bij elk antwoord, nieuwe namen = een bewaarde keuze. */
    static String koekjesNamen(String kop) {
        List<String> n = new ArrayList<>();
        if (kop != null) for (String c : kop.split(";")) {
            int i = c.indexOf('=');
            String naam = (i < 0 ? c : c.substring(0, i)).trim();
            if (!naam.isEmpty() && !n.contains(naam)) n.add(naam);
        }
        Collections.sort(n);
        StringBuilder s = new StringBuilder();
        for (String naam : n) s.append(s.length() == 0 ? "" : ";").append(naam);
        return s.toString();
    }

    /** Het begin van een artikelpagina ophalen (in de test zonder internet). */
    interface Pagina { String haal(String url, long tot) throws Exception; }

    /** De cookies die bij een adres horen (de cookiekeuze die Rene in het venster maakte), of null. */
    interface Koekjes { String voor(String url); }

    /** Eén artikelpagina ophalen als {@code ua} (in de test zonder internet). */
    interface Haler { Antwoord haal(String url, long tot, String ua) throws Exception; }

    /**
     * Artikelpagina's ophalen met de cookies {@code k} en als {@code ua}: de User-Agent van de WebView waarin Rene de
     * cookiekeuze maakte. De firewall van de site kan die cookies aan die browser koppelen; met een andere naam ziet hij een robot.
     */
    static WebPagina web(Koekjes k, String ua) { return new WebPagina((url, tot, naam) -> haal(url, tot, true, k, naam), ua); }

    /**
     * Weigert de site (403) de User-Agent van de WebView, dan die pagina nog één keer als {@link #UA}; lukt dat, dan de
     * rest van deze ronde ook zo. Welke naam een firewall accepteert, verschilt per site en is van buitenaf niet te zien.
     */
    static final class WebPagina implements Pagina {
        private final Haler h;
        private final String eerst;
        private final AtomicReference<String> naam;

        WebPagina(Haler h, String ua) { this.h = h; eerst = paginaUa(ua); naam = new AtomicReference<>(eerst); }

        /** Weigerde de site de User-Agent van de WebView en lukte het daarna als {@link #UA}? (Voor het logboek.) */
        boolean alsTools() { return !UA.equals(eerst) && UA.equals(naam.get()); }

        @Override public String haal(String url, long tot) throws Exception {
            String n = naam.get();
            Antwoord a;
            try {
                a = h.haal(url, tot, n);
            } catch (HttpFout e) {
                if (e.code != 403 || UA.equals(n)) throw e;
                a = h.haal(url, tot, UA);
                naam.set(UA);
            }
            return new String(a.data, 0, a.data.length, PodcastFeed.charset(a.data, a.data.length, a.charset));
        }
    }

    /** De logregel over de afbeeldingen van de artikelpagina's: alleen vaste woorden en codes; "" als er niets te melden is. */
    static String beeldLog(String stand, String reden, boolean alsTools) {
        if (stand == null || stand.isEmpty()) return "";
        String extra = stand.equals("fout") && reden != null && !reden.isEmpty() ? " (" + reden + ")" : stand.equals("ok") && alsTools ? " (als Tools)" : "";
        return "afbeeldingen van de pagina: " + stand + extra;
    }

    /**
     * De kop Accept-Language zoals de WebView hem maakt uit de talen van de telefoon ({@code tags}, zoals "nl-NL,en-US"):
     * "nl-NL,nl;q=0.9,en-US;q=0.8,en;q=0.7". Zoals Chromium alleen taal en land (geen schrift of voorkeuren als
     * "nl-NL-u-mu-celsius"), hooguit acht; zonder bruikbare taal Nederlands en Engels.
     */
    static String talen(String tags) {
        List<String> uit = new ArrayList<>();
        if (tags != null) for (String t : tags.split(",")) {
            Matcher m = TAALCODE.matcher(t.trim());
            if (!m.matches()) continue;
            String taal = m.group(1).toLowerCase(Locale.ROOT), s = m.group(2) == null ? taal : taal + "-" + m.group(2).toUpperCase(Locale.ROOT);
            if (!uit.contains(s) && uit.size() < 8) uit.add(s);
            if (!uit.contains(taal) && uit.size() < 8) uit.add(taal);
        }
        if (uit.isEmpty()) return "nl-NL,nl;q=0.9,en;q=0.8";
        StringBuilder b = new StringBuilder(uit.get(0));
        for (int i = 1; i < uit.size() && i < 9; i++) b.append(',').append(uit.get(i)).append(";q=0.").append(10 - i);
        return b.toString();
    }

    /** Taal, eventueel schrift (weggelaten), land en dan alleen nog onderdelen met een streepje ervoor (voorkeuren, extensies). */
    private static final Pattern TAALCODE = Pattern.compile("([A-Za-z]{2,3})(?:-[A-Za-z]{4})?(?:-([A-Za-z]{2}|[0-9]{3}))?(?:-[A-Za-z0-9]{1,8}){0,12}");

    /** De talen van de telefoon (zoals de WebView ze meestuurt), of null. */
    private static String telefoonTalen() {
        try { return android.os.LocaleList.getDefault().toLanguageTags(); } catch (Throwable e) { return null; }
    }

    /**
     * Mag een pagina in het inlogvenster dit laden (geen hoofdpagina)? https en wss van elke site, en wat de pagina zelf maakt:
     * blob:, data: en about: (werkers, plaatjes en lege vensters, bijv. van een robotcontrole). Geen http, bestanden of apps.
     */
    static boolean ladenMag(String u) {
        if (u == null) return false;
        String l = u.toLowerCase(Locale.ROOT);
        return l.startsWith("https://") || l.startsWith("wss://") || l.startsWith("blob:") || l.startsWith("data:") || l.startsWith("about:");
    }

    /** De User-Agent voor artikelpagina's: die van de WebView, of {@link #UA} als die er niet is of niet in een kopregel past. */
    static String paginaUa(String webUa) {
        if (webUa == null) return UA;
        String s = webUa.trim();
        if (s.isEmpty() || s.length() > 512) return UA;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < 0x20 || s.charAt(i) > 0x7e) return UA;
        return s;
    }

    /**
     * Afbeeldingen van de artikelpagina's bij een bron uit {@link #BEELD_VAN_PAGINA}. Wat de vorige ronde al vond (ook dat een
     * pagina er geen heeft) komt uit de cache; daarna hooguit MAX_PAGINAS pagina's van de site zelf, in de volgorde van de
     * feed. Alleen een hele kop met og:-gegevens maar zonder afbeelding telt als "zonder afbeelding", en een pagina die
     * blijvend weg is (410) of naar een andere pagina op de site stuurt. Een pagina die er (nog) niet is (404) wordt
     * overgeslagen; bij elke andere fout wacht de rest op
     * de volgende ronde. Zonder {@code p} alleen
     * de cache. De stand staat in {@code f.beeldStand}: "ok", "cookiekeuze" (de site stuurt naar een toestemmingspagina) of "fout".
     */
    static void beelden(String bron, String basis, NieuwsFeed.Feed f, JSONObject oud, long tot, Pagina p) {
        beelden(bron, basis, f, oud, tot, p, paginaBron(bron) != null);
    }

    /** Zo ook voor elke bron waarbij Afbeeldingen van de artikelpagina in de instellingen aan staat ({@code aan}). */
    static void beelden(String bron, String basis, NieuwsFeed.Feed f, JSONObject oud, long tot, Pagina p, boolean aan) {
        if (!aan) return;
        f.beeldStand = oud == null ? "" : oud.optString("beeldStand");
        f.beeldReden = oud == null ? "" : oud.optString("beeldReden");
        Map<String, String> bekend = new HashMap<>();
        JSONArray oa = oud == null ? null : oud.optJSONArray("artikelen");
        if (oa != null) for (int i = 0; i < oa.length(); i++) {
            JSONObject a = oa.optJSONObject(i);
            if (a != null && (a.has("beeld") || a.optBoolean("zonderBeeld"))) bekend.put(a.optString("id"), a.optString("beeld"));
        }
        String site = host(basis);
        int gehaald = 0;
        boolean stop = p == null;
        for (NieuwsFeed.Artikel a : f.artikelen) {
            if (!a.beeld.isEmpty() || a.zonderBeeld) continue;
            String id = artikelId(bron, a.sleutel), b = bekend.get(id);
            if (b != null) { a.beeld = b; a.zonderBeeld = b.isEmpty(); continue; }
            String l = link(a.link, basis);
            if (stop || gehaald >= MAX_PAGINAS || System.currentTimeMillis() >= tot || !https(l) || !host(l).equals(site)) continue;
            gehaald++;
            long paginaTot = Math.min(tot, System.currentTimeMillis() + MAX_PER_PAGINA);
            try {
                String html = p.haal(l, paginaTot);
                String u = paginaBeeld(html, l);
                // geen afbeelding en geen hele kop met og:-gegevens: een muur, of een kop langer dan MAX_PAGINA
                if (u.isEmpty() && !artikelKop(html)) { stand(f, "fout", "geenkop"); stop = true; continue; }
                a.beeld = u;
                a.zonderBeeld = u.isEmpty();
                stand(f, "ok", "");
            } catch (Doorverwezen e) { stand(f, "cookiekeuze", ""); stop = true; }
            catch (Elders e) { a.zonderBeeld = true; }
            catch (HttpFout e) {
                if (e.code == 410) a.zonderBeeld = true;
                else if (e.code != 404) { stand(f, "fout", String.valueOf(e.code)); stop = true; }
            } catch (Throwable e) {
                String r = reden(e);
                // de tijd voor de hele bron was op, niet die voor de pagina: dat zegt niets over de site
                if (!(r.equals("traag") && paginaTot == tot)) stand(f, "fout", r);
                stop = true;
            }
            if (a.zonderBeeld || !a.beeld.isEmpty()) bekend.put(id, a.beeld);
        }
    }

    private static void stand(NieuwsFeed.Feed f, String stand, String reden) { f.beeldStand = stand; f.beeldReden = reden; }

    /** Waarom een artikelpagina niet lukte, als vast woord (voor het scherm en het logboek; nooit de fouttekst zelf). */
    static String reden(Throwable e) {
        if (e instanceof java.net.SocketTimeoutException || e instanceof TeTraag) return "traag";
        if (e instanceof java.net.UnknownHostException || e instanceof java.net.ConnectException || e instanceof java.net.NoRouteToHostException) return "verbinding";
        if (e instanceof javax.net.ssl.SSLException) return "beveiliging";
        return "anders";
    }

    private static final Pattern META = Pattern.compile("(?i)<meta\\b[^<>]{0,2000}>");
    private static final Pattern META_ATTR = Pattern.compile("(?i)(?<![\\w-])(property|name|content)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>\"']+)");

    private static final Pattern KOP_EIND = Pattern.compile("(?i)</head\\s*>|<body\\b");

    /** Staat de hele kop van een artikelpagina in {@code html} (tot &lt;/head&gt; of &lt;body&gt;), met echte og:- of twitter:-gegevens? */
    static boolean artikelKop(String html) {
        String s = html.length() > MAX_PAGINA ? html.substring(0, MAX_PAGINA) : html;
        if (!KOP_EIND.matcher(s).find()) return false;
        Matcher m = META.matcher(s);
        while (m.find()) {
            Matcher a = META_ATTR.matcher(m.group());
            while (a.find()) {
                if (a.group(1).equalsIgnoreCase("content")) continue;
                String v = a.group(2).replaceAll("^[\"']", "").toLowerCase(Locale.ROOT);
                if (v.startsWith("og:") || v.startsWith("twitter:")) return true;
            }
        }
        return false;
    }

    /** De afbeelding die een artikelpagina zelf noemt (og:image, anders twitter:image): alleen https naar een openbare site, anders "". */
    static String paginaBeeld(String html, String basis) {
        String og = "", tw = "";
        Matcher m = META.matcher(html.length() > MAX_PAGINA ? html.substring(0, MAX_PAGINA) : html);
        while (m.find() && og.isEmpty()) {
            String soort = "", inhoud = "";
            Matcher a = META_ATTR.matcher(m.group());
            while (a.find()) {
                String v = a.group(2);
                if (v.startsWith("\"") || v.startsWith("'")) v = v.substring(1, v.length() - 1);
                v = NieuwsFeed.ontsnap(v).trim();
                if (a.group(1).equalsIgnoreCase("content")) inhoud = v; else soort = v.toLowerCase(Locale.ROOT);
            }
            boolean isOg = soort.equals("og:image") || soort.equals("og:image:url") || soort.equals("og:image:secure_url");
            if (inhoud.isEmpty() || !(isOg || (tw.isEmpty() && (soort.equals("twitter:image") || soort.equals("twitter:image:src"))))) continue;
            String u;
            try { u = new URL(new URL(basis), inhoud).toString(); } catch (Exception e) { continue; }
            if (u.length() >= 1000 || !https(u)) continue;
            if (isOg) og = u; else tw = u;
        }
        return og.isEmpty() ? tw : og;
    }

    static final Object LOCK = new Object();
    static final AtomicBoolean BEZIG = new AtomicBoolean();
    private static final String BRON_ID_PATROON = "[a-z0-9-]{1,40}";
    private static final Pattern BRON_ID = Pattern.compile(BRON_ID_PATROON), ART_ID = Pattern.compile("n[0-9a-f]{1,16}");

    /** Een fout met een Nederlandse tekst voor Rene (de brug vertaalt hem). */
    static class Fout extends Exception { Fout(String nl) { super(nl); } }

    /** De site is niet klaar vóór de afgesproken tijd. */
    static final class TeTraag extends Fout { TeTraag() { super(Taal.T("De nieuwssite reageert te traag")); } }

    /** De site antwoordt met een andere code dan 200. */
    static final class HttpFout extends Fout {
        final int code;
        HttpFout(int code) { super(Taal.fill(Taal.T("De nieuwssite antwoordt met {code}"), "code", code)); this.code = code; }
    }

    /** Een artikelpagina stuurt naar een andere site (een toestemmingspagina): daar gaat Tools niet heen. */
    static final class Doorverwezen extends Exception { Doorverwezen() { super("doorverwezen naar een andere site"); } }

    /** Een artikelpagina stuurt binnen de site naar een andere pagina (een verplaatst artikel, inloggen): daar geen afbeelding van. */
    static final class Elders extends Exception { Elders() { super("doorverwezen naar een andere pagina"); } }

    static File dir(Context c) { return new File(c.getFilesDir(), "nieuws"); }
    static File cache(Context c) { return new File(c.getCacheDir(), "nieuws"); }

    /** Het cachebestand van een bron. Een id dat geen bron-id kan zijn, komt nooit buiten de map (ook niet als de lijst ooit anders gevuld wordt). */
    static File cacheBestand(File cache, String id) {
        // String.matches met een vast patroon: alleen zo ziet de beveiligingsscan (CodeQL) dat er geen / of .. in kan staan
        if (id == null || !id.matches(BRON_ID_PATROON)) throw new IllegalArgumentException("ongeldige bron");
        return new File(cache, id + ".json");
    }

    // ---------------------------------------------------------------- bestanden

    private static JSONObject leesObject(File f) {
        try { return f.isFile() ? new JSONObject(Contacts.readText(f, false)) : null; } catch (Exception e) { return null; }
    }

    private static JSONArray leesLijst(File f) {
        try { return f.isFile() ? new JSONArray(Contacts.readText(f, false)) : null; } catch (Exception e) { return null; }
    }

    /** Schrijven; een bestaand bestand dat niet te lezen is, wordt eerst opzij gezet in plaats van overschreven. */
    private static void schrijf(File f, String s, boolean lijst) throws Exception {
        File d = f.getParentFile();
        if (d != null && !d.isDirectory() && !d.mkdirs()) throw new Fout(Taal.T("Opslaan lukt niet"));
        if (f.isFile() && (lijst ? leesLijst(f) == null : leesObject(f) == null)) {
            String n = f.getName();
            File opzij = new File(d, n.substring(0, n.length() - 5) + "-onleesbaar.json");
            if (!opzij.exists() && !f.renameTo(opzij)) throw new Fout(Taal.T("Opslaan lukt niet"));
        }
        Contacts.writeText(f, s, false);
    }

    // ---------------------------------------------------------------- bronnen

    /**
     * Alle bronnen (eerst de bekende, dan de eigen) met aan/uit en hun instellingen, voor elke bron op dezelfde manier
     * (de vaste waarden van een bekende bron zijn alleen het begin; {@code instellingen} in bronnen.json gaat erboven):
     * naam, url (de feed), alleen (hoe de link van een artikel moet beginnen), pagina (afbeeldingen van de artikelpagina),
     * inlog (inloggen bij de website), inlogStart, inlogSites ({@link #siteMag}) en lezen ("app" of "browser").
     */
    static JSONArray bronnen(File dir) throws JSONException {
        JSONObject o = leesObject(new File(dir, "bronnen.json"));
        JSONObject st = o == null ? null : o.optJSONObject("standaard"), inst = o == null ? null : o.optJSONObject("instellingen");
        JSONArray out = new JSONArray();
        for (String[] s : STANDAARD) {
            boolean aan = st != null && st.has(s[0]) ? st.optBoolean(s[0]) : "1".equals(s[3]);
            JSONObject x = inst == null ? null : inst.optJSONObject(s[0]);
            String naam = x == null ? "" : NieuwsFeed.cut(NieuwsFeed.regel(x.optString("naam")).trim(), 80), url = x == null ? "" : x.optString("url");
            JSONObject b = new JSONObject().put("id", s[0]).put("naam", naam.isEmpty() ? s[1] : naam).put("url", https(url) ? url : s[2])
                    .put("aan", aan).put("eigen", false).put("standaardNaam", s[1]).put("standaardUrl", s[2]);
            out.put(instellingen(b, x));
        }
        JSONArray eigen = o == null ? null : o.optJSONArray("eigen");
        Set<String> ids = new HashSet<>();
        if (eigen != null) for (int i = 0; i < eigen.length() && ids.size() < MAX_EIGEN; i++) {
            JSONObject e = eigen.optJSONObject(i);
            if (e == null) continue;
            String id = e.optString("id"), url = e.optString("url");
            if (!id.startsWith("e") || !BRON_ID.matcher(id).matches() || !https(url) || !ids.add(id)) continue;
            String naam = NieuwsFeed.regel(e.optString("naam")).trim();
            JSONObject b = new JSONObject().put("id", id).put("naam", NieuwsFeed.cut(naam.isEmpty() ? host(url) : naam, 80))
                    .put("url", url).put("aan", e.optBoolean("aan", true)).put("eigen", true);
            out.put(instellingen(b, inst == null ? null : inst.optJSONObject(id)));
        }
        return out;
    }

    /** De bekende bron met dit id, of null. */
    static String[] standaard(String id) {
        for (String[] s : STANDAARD) if (s[0].equals(id)) return s;
        return null;
    }

    static String[] inlogStandaard(String id) {
        for (String[] s : INLOG_STANDAARD) if (s[0].equals(id)) return s;
        return null;
    }

    /** De site van een bron (zonder https:// en www.): de eerste site van een bekende bron, anders die van de feed zonder "feeds." en dergelijke. */
    static String site(JSONObject b) {
        String[] s = standaard(b.optString("id"));
        if (s != null && b.optString("url").equals(s[2])) return s[4].split(" ")[0].split("/")[0];
        String h = host(b.optString("url"));
        return h.matches("(?:feeds?|rss|atom)\\.[^.]+\\..+") ? h.substring(h.indexOf('.') + 1) : h;
    }

    /** De instellingen van een bron: de opgeslagen keuzes ({@code x}) boven de vaste waarden. "aangepast" = er is iets anders dan standaard. */
    private static JSONObject instellingen(JSONObject b, JSONObject x) throws JSONException {
        String id = b.getString("id");
        if (x == null) x = new JSONObject();
        String[] il = inlogStandaard(id);
        String alleen = x.has("alleen") ? x.optString("alleen") : alleen(id);
        boolean inlog = x.has("inlog") ? x.optBoolean("inlog") : il != null;
        String start = x.optString("inlogStart"), sites = x.optString("inlogSites");
        if (!https(start)) start = il != null ? il[1] : "https://" + site(b) + "/";
        if (sites.isEmpty() || !sites.equals(sitesOfLeeg(sites))) sites = il != null ? il[2] : standaardSites(start);
        String lezen = x.optString("lezen");
        if (!lezen.equals("app") && !lezen.equals("browser")) lezen = inlog ? "app" : "browser";
        boolean aangepast = x.length() > 0;
        return b.put("alleen", alleen).put("pagina", x.has("pagina") ? x.optBoolean("pagina") : paginaBron(id) != null)
                .put("cookiekeuze", paginaBron(id) != null).put("inlog", inlog).put("inlogStart", start).put("inlogSites", sites)
                .put("lezen", lezen).put("derden", x.has("derden") ? x.optBoolean("derden") : derdenStandaard(id)).put("aangepast", aangepast);
    }

    /** De sites bij een inlogpagina: de site zelf en alle subdomeinen ervan. */
    static String standaardSites(String start) {
        String h = host(start);
        try { h = IDN.toASCII(h).toLowerCase(Locale.ROOT); } catch (Exception ignored) { }
        return h + " *." + h;
    }

    private static final Pattern SITE = Pattern.compile("(?:\\*\\.)?[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+");
    static final int MAX_SITES = 12;
    /** Achtervoegsels waaronder iedereen een site kan hebben (geen eigen naam): niet als toegestane site. */
    static final Set<String> ALGEMEEN = new HashSet<>(Arrays.asList("co.uk", "org.uk", "ac.uk", "com.au", "net.au", "co.nz", "co.jp", "com.br",
            "github.io", "gitlab.io", "blogspot.com", "wordpress.com", "substack.com", "netlify.app", "vercel.app", "pages.dev",
            "herokuapp.com", "appspot.com", "web.app", "firebaseapp.com", "azurewebsites.net", "cloudfront.net", "amazonaws.com"));

    /**
     * De sites die Rene intikt (spaties of komma's ertussen), netjes: kleine letters, zonder https:// en zonder pad. Een
     * site met "*." ervoor = alle subdomeinen; "*.com" en dergelijke (geen eigen naam) en adressen als 192.168.1.1 tellen niet.
     */
    static String sitesNetjes(String in) throws Fout {
        List<String> uit = new ArrayList<>();
        for (String r : (in == null ? "" : in).toLowerCase(Locale.ROOT).split("[\\s,;]+")) {
            String d = r.replaceFirst("^[a-z]+://", "").replaceFirst("[/?#].*$", "").replaceFirst("^www\\.", "");
            if (d.isEmpty()) continue;
            String kaal = d.startsWith("*.") ? d.substring(2) : d;
            if (d.length() > 253 || !SITE.matcher(d).matches() || kaal.indexOf('.') < 0 || kaal.matches("[0-9.]+") || ALGEMEEN.contains(kaal))
                throw new Fout(Taal.fill(Taal.T("Geen geldige site: {site}"), "site", d));
            if (!uit.contains(d)) uit.add(d);
        }
        if (uit.isEmpty()) throw new Fout(Taal.T("Vul minstens één site in"));
        if (uit.size() > MAX_SITES) throw new Fout(Taal.fill(Taal.T("Hooguit {n} sites"), "n", MAX_SITES));
        StringBuilder b = new StringBuilder();
        for (String d : uit) b.append(b.length() == 0 ? "" : " ").append(d);
        return b.toString();
    }

    private static String sitesOfLeeg(String in) { try { return sitesNetjes(in); } catch (Fout e) { return ""; } }

    /**
     * Mag het inlogvenster van een bron dit adres openen, en gaan de cookies van de inlog ernaartoe? Alleen https naar een
     * site uit {@code sites}: "aireport.nl" = aireport.nl en www.aireport.nl, "*.aireport.nl" = elk subdomein.
     */
    static boolean siteMag(String sites, String url) {
        if (sites == null || url == null || !https(url)) return false;
        String h;
        try { h = IDN.toASCII(new URL(url).getHost()).toLowerCase(Locale.ROOT); } catch (Exception e) { return false; }
        for (String d : sites.split(" ")) {
            if (d.isEmpty()) continue;
            if (d.startsWith("*.") ? h.endsWith(d.substring(1)) : h.equals(d) || h.equals("www." + d)) return true;
        }
        return false;
    }

    /**
     * Een pagina op een site van de inlog via http (zoals beehiiv na het inloggen terugstuurt naar http://www.aireport.nl/…):
     * hetzelfde adres via https, zoals een browser het ook omzet; anders null. Tools laadt zelf nooit iets via http.
     */
    static String httpsVersie(String sites, String url) {
        if (url == null || !url.regionMatches(true, 0, "http://", 0, 7)) return null;
        try {
            // URL is soepel zoals Chromium ({, |, ^ in een vraag); de rest van het adres blijft precies zoals het was
            URL x = new URL(url);
            if (x.getUserInfo() != null || x.getHost() == null || x.getHost().isEmpty() || (x.getPort() != -1 && x.getPort() != 80)) return null;
            int r = 7;
            while (r < url.length() && "/?#".indexOf(url.charAt(r)) < 0) r++;
            String rest = url.substring(r);
            if (url.substring(7, r).indexOf('@') >= 0 || url.substring(7, r).indexOf('\\') >= 0) return null;
            String u = "https://" + x.getHost().toLowerCase(Locale.ROOT) + (rest.startsWith("/") ? rest : "/" + rest);
            return siteMag(sites, u) ? u : null;
        } catch (Exception e) { return null; }
    }

    /** Hooguit {@code max} keer per {@code venster} ms (tegen een lus); telt deze keer mee als hij mag. */
    static boolean binnenGrens(java.util.ArrayDeque<Long> tijden, long nu, int max, long venster) {
        while (!tijden.isEmpty() && nu - tijden.peekFirst() >= venster) tijden.removeFirst();
        if (tijden.size() >= max) return false;
        tijden.addLast(nu);
        return true;
    }

    /**
     * De adressen waarvan Uitloggen de cookies weghaalt: elke site uit {@code sites} met en zonder www. (van een "*."-site
     * de site zelf en www.: welke subdomeinen er nog meer zijn, weet Tools niet) plus de inlogpagina zelf.
     */
    static List<String> uitlogAdressen(String sites, String start) {
        List<String> uit = new ArrayList<>();
        if (start != null && https(start)) {
            try { URL u = new URL(start); uit.add("https://" + u.getHost().toLowerCase(Locale.ROOT) + "/"); } catch (Exception ignored) { }
        }
        if (sites != null) for (String d : sites.split(" ")) {
            String h = d.startsWith("*.") ? d.substring(2) : d;
            if (h.isEmpty()) continue;
            for (String u : new String[]{"https://" + h + "/", "https://www." + h + "/"}) if (!uit.contains(u)) uit.add(u);
        }
        return uit;
    }

    /** Hoe de link van een artikel moet beginnen, zoals Rene het intikt: zonder https:// en www., kleine letters ("" = alles). */
    static String alleenNetjes(String in) throws Fout {
        String s = (in == null ? "" : in).trim().toLowerCase(Locale.ROOT).replaceFirst("^[a-z]+://", "").replaceFirst("^www\\.", "");
        if (s.length() > 200 || s.matches(".*\\s.*")) throw new Fout(Taal.T("Dit is geen geldig begin van een link"));
        return s;
    }

    private static void bewaarBronnen(File dir, JSONArray bs) throws Exception {
        JSONObject o = leesObject(new File(dir, "bronnen.json"));
        bewaarBronnen(dir, bs, o == null ? null : o.optJSONObject("instellingen"));
    }

    /** Opslaan; de instellingen van bronnen die er niet meer zijn, vallen weg. */
    private static void bewaarBronnen(File dir, JSONArray bs, JSONObject inst) throws Exception {
        JSONObject st = new JSONObject(), in = new JSONObject();
        JSONArray eigen = new JSONArray();
        for (int i = 0; i < bs.length(); i++) {
            JSONObject b = bs.getJSONObject(i);
            String id = b.getString("id");
            if (b.optBoolean("eigen")) eigen.put(new JSONObject().put("id", id).put("naam", b.optString("naam"))
                    .put("url", b.getString("url")).put("aan", b.optBoolean("aan")));
            else st.put(id, b.optBoolean("aan"));
            JSONObject x = inst == null ? null : inst.optJSONObject(id);
            if (x != null && x.length() > 0) in.put(id, x);
        }
        schrijf(new File(dir, "bronnen.json"), new JSONObject().put("v", 2).put("standaard", st).put("eigen", eigen).put("instellingen", in).toString(), false);
    }

    /** Is {@code url} een andere feed dan nu bij de bron staat? (Dan leest de brug hem eerst, zie {@link #instellen}.) */
    static boolean andereFeed(File dir, String id, String url) throws Exception {
        synchronized (LOCK) {
            JSONObject b = zoekBron(bronnen(dir), id);
            if (b == null) throw new Fout(Taal.T("Deze bron bestaat niet meer"));
            return !zelfde(b.getString("url"), url);
        }
    }

    /**
     * De instellingen van een bron aanpassen; alleen de velden in {@code in} veranderen ("standaard": true = alles terug naar
     * de vaste waarden). Een andere feed ({@code url}) is al gelezen ({@code feed} = het echte adres); de opgehaalde artikelen
     * van de oude feed gaan dan weg. Geeft de bron zoals hij nu is.
     */
    static JSONObject instellen(File dir, File cache, String id, JSONObject in, String feed) throws Exception {
        synchronized (LOCK) {
            JSONObject o = leesObject(new File(dir, "bronnen.json"));
            JSONObject inst = o == null ? null : o.optJSONObject("instellingen");
            inst = inst == null ? new JSONObject() : new JSONObject(inst.toString());
            JSONArray bs = bronnen(dir);
            JSONObject b = zoekBron(bs, id);
            if (b == null) throw new Fout(Taal.T("Deze bron bestaat niet meer"));
            String[] st = standaard(id);
            JSONObject x = in.optBoolean("standaard") ? new JSONObject() : inst.optJSONObject(id);
            if (x == null) x = new JSONObject();
            boolean eigen = b.optBoolean("eigen"), andereFeed = false;
            if (in.has("naam")) {
                String n = NieuwsFeed.cut(NieuwsFeed.regel(in.optString("naam")).trim(), 80);
                if (eigen) b.put("naam", n.isEmpty() ? host(b.getString("url")) : n);
                else if (n.isEmpty() || n.equals(st[1])) x.remove("naam"); else x.put("naam", n);
            }
            if (feed != null) {
                for (int i = 0; i < bs.length(); i++) {
                    JSONObject a = bs.getJSONObject(i);
                    if (!a.getString("id").equals(id) && zelfde(a.getString("url"), feed))
                        throw new Fout(Taal.fill(Taal.T("Deze feed staat er al als {naam}"), "naam", a.optString("naam")));
                }
                andereFeed = !zelfde(b.getString("url"), feed);
                if (eigen) b.put("url", feed);
                else if (zelfde(feed, st[2])) x.remove("url"); else x.put("url", feed);
            }
            if (in.has("alleen")) {
                String a = alleenNetjes(in.optString("alleen"));
                if (a.equals(alleen(id))) x.remove("alleen"); else x.put("alleen", a);
            }
            if (in.has("pagina")) {
                boolean p = in.optBoolean("pagina");
                if (p == (paginaBron(id) != null)) x.remove("pagina"); else x.put("pagina", p);
            }
            String[] il = inlogStandaard(id);
            if (in.has("inlog")) {
                boolean aan = in.optBoolean("inlog");
                if (aan == (il != null)) x.remove("inlog"); else x.put("inlog", aan);
            }
            if (in.has("inlogSites")) x.put("inlogSites", sitesNetjes(in.optString("inlogSites")));
            if (in.has("inlogStart")) {
                String u = in.optString("inlogStart").trim();
                if (u.isEmpty()) x.remove("inlogStart"); else x.put("inlogStart", adres(u));
            }
            if (in.has("derden")) {
                boolean aan = in.optBoolean("derden");
                if (aan == derdenStandaard(id)) x.remove("derden"); else x.put("derden", aan);
            }
            if (in.has("lezen")) {
                String l = in.optString("lezen");
                if (!l.equals("app") && !l.equals("browser")) throw new Fout(Taal.T("Opslaan lukt niet"));
                x.put("lezen", l);
            }
            if (x.length() > 0) inst.put(id, x); else inst.remove(id);
            // Wat er nu zou gelden: de inlogpagina moet bij de sites horen, anders opent het venster hem niet
            JSONObject nu = instellingen(new JSONObject(b.toString()), x);
            boolean inlogVelden = in.has("inlog") || in.has("inlogStart") || in.has("inlogSites");
            if (nu.optBoolean("inlog") && inlogVelden && !siteMag(nu.getString("inlogSites"), nu.getString("inlogStart")))
                throw new Fout(Taal.T("De inlogpagina hoort niet bij de toegestane sites. Voeg de site van de inlogpagina toe."));
            // gelijk aan wat er zonder keuze zou gelden: niet bewaren (dan volgt het een ander adres van de bron)
            for (String k : new String[]{"inlogStart", "inlogSites", "lezen"}) {
                if (!x.has(k)) continue;
                JSONObject zonder = new JSONObject(x.toString());
                zonder.remove(k);
                if (x.optString(k).equals(instellingen(new JSONObject(b.toString()), zonder).optString(k))) x.remove(k);
            }
            if (x.length() > 0) inst.put(id, x); else inst.remove(id);
            bewaarBronnen(dir, bs, inst);
            if (andereFeed || in.optBoolean("standaard")) cacheBestand(cache, id).delete();
            return new JSONObject(zoekBron(bronnen(dir), id).toString());
        }
    }

    private static JSONObject zoekBron(JSONArray bs, String id) {
        for (int i = 0; i < bs.length(); i++) { JSONObject b = bs.optJSONObject(i); if (b != null && b.optString("id").equals(id)) return b; }
        return null;
    }

    static void zetAan(File dir, String id, boolean aan) throws Exception {
        synchronized (LOCK) {
            JSONArray bs = bronnen(dir);
            JSONObject b = zoekBron(bs, id);
            if (b == null) throw new Fout(Taal.T("Deze bron bestaat niet meer"));
            b.put("aan", aan);
            bewaarBronnen(dir, bs);
        }
    }

    /** Een eigen bron weghalen (de bekende bronnen kunnen alleen uit). */
    static void verwijder(File dir, File cache, String id) throws Exception {
        synchronized (LOCK) {
            JSONArray bs = bronnen(dir), rest = new JSONArray();
            boolean weg = false;
            for (int i = 0; i < bs.length(); i++) {
                JSONObject b = bs.getJSONObject(i);
                if (b.optBoolean("eigen") && b.getString("id").equals(id)) weg = true; else rest.put(b);
            }
            if (!weg) throw new Fout(Taal.T("Deze bron bestaat niet meer"));
            bewaarBronnen(dir, rest);
            cacheBestand(cache, id).delete();
        }
    }

    /** Een bron toevoegen nadat de feed gelezen is (url = het echte feedadres). Staat hij er al, dan gaat hij aan. */
    static JSONObject voegToe(File dir, String url, String titel) throws Exception {
        synchronized (LOCK) {
            JSONArray bs = bronnen(dir);
            int eigen = 0;
            for (int i = 0; i < bs.length(); i++) {
                JSONObject b = bs.getJSONObject(i);
                if (zelfde(b.getString("url"), url)) {
                    boolean was = b.optBoolean("aan");
                    b.put("aan", true);
                    if (!was) bewaarBronnen(dir, bs);
                    return new JSONObject(b.toString()).put("al", true);
                }
                if (b.optBoolean("eigen")) eigen++;
            }
            if (eigen >= MAX_EIGEN) throw new Fout(Taal.fill(Taal.T("Je hebt al {n} eigen bronnen"), "n", MAX_EIGEN));
            String naam = NieuwsFeed.cut(NieuwsFeed.regel(titel == null ? "" : titel), 80);
            // een eigen bron houdt zijn id als het adres verandert: een nieuwe bron met dat oude adres krijgt een ander id
            String id = "e" + Podcasts.hash(url);
            for (int n = 1; zoekBron(bs, id) != null && n < 100; n++) id = "e" + Podcasts.hash(url + "#" + n);
            JSONObject b = new JSONObject().put("id", id).put("naam", naam.isEmpty() ? host(url) : naam)
                    .put("url", url).put("aan", true).put("eigen", true);
            bs.put(b);
            bewaarBronnen(dir, bs);
            JSONObject nu = zoekBron(bronnen(dir), b.getString("id"));
            return new JSONObject((nu != null ? nu : b).toString());
        }
    }

    /** Twee adressen van dezelfde feed (hoofdletters, www., een / aan het eind en http/https tellen niet). */
    static boolean zelfde(String a, String b) { return norm(a).equals(norm(b)); }
    private static String norm(String u) {
        String s = u.trim().toLowerCase(Locale.ROOT).replaceFirst("^[a-z]+://", "").replaceFirst("^www\\.", "");
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    static String host(String url) {
        try { String h = new URL(url).getHost().toLowerCase(Locale.ROOT); return h.startsWith("www.") ? h.substring(4) : h; }
        catch (Exception e) { return url; }
    }

    static boolean https(String u) { return NieuwsFeed.httpsOpenbaar(u); }

    /** Wat Rene intikt ("nos.nl", "http://…", "feed://…") → een https-adres. */
    static String adres(String in) throws Fout {
        String u = in == null ? "" : in.trim();
        if (u.isEmpty()) throw new Fout(Taal.T("Vul het adres van een nieuwssite of feed in"));
        if (u.regionMatches(true, 0, "http://", 0, 7) || u.regionMatches(true, 0, "feed://", 0, 7)) u = "https://" + u.substring(7);
        else if (!u.regionMatches(true, 0, "https://", 0, 8)) {
            if (u.contains("://")) throw new Fout(Taal.T("Dit is geen geldig webadres"));
            u = "https://" + u;
        }
        if (u.contains(" ") || !https(u)) throw new Fout(Taal.T("Dit is geen geldig webadres"));
        return u;
    }

    // ---------------------------------------------------------------- artikelen

    static String artikelId(String bron, String sleutel) { return "n" + Podcasts.hash(bron + "|" + sleutel); }

    private static final Pattern VOLG = Pattern.compile("(?i)(utm_[a-z_]+|at_[a-z_]+|fbclid|gclid|dclid|yclid|mc_cid|mc_eid|ocid|cmpid|xtor|igshid|_ga)");

    /** Volgparameters (utm_… en dergelijke) uit een artikeladres; de rest blijft precies staan. */
    static String schoon(String link) {
        int q = link.indexOf('?');
        if (q < 0) return link;
        int h = link.indexOf('#', q);
        String query = link.substring(q + 1, h < 0 ? link.length() : h), frag = h < 0 ? "" : link.substring(h);
        StringBuilder b = new StringBuilder();
        for (String p : query.split("&")) {
            if (p.isEmpty()) continue;
            int e = p.indexOf('=');
            if (VOLG.matcher(e < 0 ? p : p.substring(0, e)).matches()) continue;
            b.append(b.length() == 0 ? "" : "&").append(p);
        }
        return link.substring(0, q) + (b.length() == 0 ? "" : "?" + b) + frag;
    }

    /** Adres van een artikel: relatief tegen de feed, alleen http(s). */
    static String link(String link, String basis) {
        if (link == null || link.trim().isEmpty()) return "";
        try {
            String u = new URL(new URL(basis), link.trim()).toString();
            return u.regionMatches(true, 0, "https://", 0, 8) || u.regionMatches(true, 0, "http://", 0, 7) ? schoon(u) : "";
        } catch (Exception e) { return ""; }
    }

    static JSONObject json(NieuwsFeed.Artikel a, String bron, long datum, String basis) throws JSONException {
        JSONArray bl = new JSONArray();
        for (NieuwsFeed.Blok b : a.blokken) { JSONObject o = new JSONObject().put("t", b.tekst); if (b.kop) o.put("k", 1); bl.put(o); }
        JSONObject o = new JSONObject().put("id", artikelId(bron, a.sleutel)).put("bron", bron).put("titel", a.titel)
                .put("link", link(a.link, basis)).put("datum", datum).put("blokken", bl);
        if (!a.auteur.isEmpty()) o.put("auteur", a.auteur);
        if (!a.beeld.isEmpty()) o.put("beeld", a.beeld);
        else if (a.zonderBeeld) o.put("zonderBeeld", true);
        if (a.ingekort) o.put("ingekort", true);
        return o;
    }

    private static long datum(JSONObject a) { return a.optLong("datum"); }

    private static void nieuwsteEerst(List<JSONObject> l) { Collections.sort(l, (x, y) -> Long.compare(datum(y), datum(x))); }

    /**
     * Een opgehaalde feed in de cache. Een artikel zonder (of met een toekomstige) datum houdt de tijd waarop Tools hem
     * voor het eerst zag. Geeft het aantal artikelen dat er nog niet was.
     */
    static int bewaarFeed(File cache, String bron, String basis, NieuwsFeed.Feed f, long nu) throws Exception {
        synchronized (LOCK) {
            File cf = cacheBestand(cache, bron);
            JSONObject oud = leesObject(cf);
            if (oud != null && later(oud, nu)) return 0;
            Map<String, Long> bekend = new HashMap<>();
            JSONArray oa = oud == null ? null : oud.optJSONArray("artikelen");
            long nieuwste = 0;
            if (oa != null) for (int i = 0; i < oa.length(); i++) {
                JSONObject a = oa.optJSONObject(i);
                if (a != null) { bekend.put(a.optString("id"), datum(a)); nieuwste = Math.max(nieuwste, datum(a)); }
            }
            List<JSONObject> lijst = new ArrayList<>();
            Set<String> gezien = new HashSet<>();
            int nieuw = 0;
            for (NieuwsFeed.Artikel a : f.artikelen) {
                String id = artikelId(bron, a.sleutel);
                if (!gezien.add(id)) continue;
                Long eerder = bekend.get(id);
                long d = a.datum;
                if (d <= 0 || d > nu + 3_600_000L) d = eerder != null ? eerder : nu;
                // uit een sitemap komen oudere artikelen er per ronde bij: die tellen niet als nieuw
                if (eerder == null && !(f.uitSitemap && d < nieuwste)) nieuw++;
                lijst.add(json(a, bron, d, basis));
            }
            nieuwsteEerst(lijst);
            JSONArray arr = new JSONArray();
            for (int i = 0; i < lijst.size() && i < MAX_PER_BRON; i++) arr.put(lijst.get(i));
            // het echte feedadres (een site waarop Tools de feed zocht): de volgende keer meteen daar
            JSONObject o = new JSONObject().put("t", nu).put("poging", nu).put("titel", f.titel).put("feed", basis).put("artikelen", arr);
            if (!f.beeldStand.isEmpty()) o.put("beeldStand", f.beeldStand);
            if (!f.beeldReden.isEmpty()) o.put("beeldReden", f.beeldReden);
            schrijf(cf, o.toString(), false);
            return oud == null ? 0 : nieuw;
        }
    }

    /** Een latere ronde was eerder klaar (een trage site). Een tijd in de toekomst (klok teruggezet) telt niet. */
    private static boolean later(JSONObject c, long nu) {
        long t = Math.max(c.optLong("t"), c.optLong("poging"));
        return t > nu && t <= System.currentTimeMillis();
    }

    /** Bijwerken mislukt: de vorige artikelen blijven, met de fout erbij. */
    static void bewaarFout(File cache, String bron, String fout, long nu) {
        synchronized (LOCK) {
            try {
                File cf = cacheBestand(cache, bron);
                JSONObject o = leesObject(cf);
                if (o == null) o = new JSONObject();
                if (later(o, nu)) return;
                schrijf(cf, o.put("poging", nu).put("fout", fout).toString(), false);
            } catch (Exception ignored) { }
        }
    }

    /** Kort overzicht van een artikel voor de lijst (zonder de hele tekst). */
    static JSONObject kort(JSONObject a) throws JSONException {
        JSONObject o = new JSONObject().put("id", a.optString("id")).put("bron", a.optString("bron")).put("titel", a.optString("titel"))
                .put("link", a.optString("link")).put("datum", datum(a));
        for (String k : new String[]{"beeld", "auteur", "bronnaam", "bewaard"}) if (a.has(k)) o.put(k, a.get(k));
        JSONArray bl = a.optJSONArray("blokken");
        String sam = "";
        int lang = 0;
        if (bl != null) for (int i = 0; i < bl.length(); i++) {
            JSONObject b = bl.optJSONObject(i);
            if (b == null) continue;
            String t = b.optString("t");
            lang += t.length();
            if (sam.isEmpty() && b.optInt("k") == 0 && !t.equals(o.optString("titel"))) sam = t.length() > 240 ? t.substring(0, 240).replaceAll("\\s+\\S*$", "") + " …" : t;
        }
        return o.put("sam", sam).put("lang", lang);
    }

    /** Alles voor het scherm: bronnen met hun stand, de artikelen van de bronnen die aan staan, wat gelezen en bewaard is. */
    static JSONObject lijst(File dir, File cache) throws Exception {
        synchronized (LOCK) {
            JSONArray bs = bronnen(dir);
            List<JSONObject> items = new ArrayList<>();
            for (int i = 0; i < bs.length(); i++) {
                JSONObject b = bs.getJSONObject(i);
                JSONObject c = leesObject(cacheBestand(cache, b.getString("id")));
                int n = 0;
                if (paginaBron(b.getString("id")) != null) b.put("cookiekeuze", true);
                if (c != null) {
                    b.put("t", c.optLong("t")).put("poging", c.optLong("poging"));
                    if (c.has("fout")) b.put("fout", c.optString("fout"));
                    if (c.has("beeldStand")) b.put("beeldStand", c.optString("beeldStand"));
                    if (c.has("beeldReden")) b.put("beeldReden", c.optString("beeldReden"));
                    JSONArray as = c.optJSONArray("artikelen");
                    if (as != null && b.optBoolean("aan")) for (int j = 0; j < as.length(); j++) {
                        JSONObject a = as.optJSONObject(j);
                        if (a != null) { items.add(kort(a)); n++; }
                    }
                }
                b.put("aantal", n);
            }
            nieuwsteEerst(items);
            JSONArray arts = new JSONArray();
            for (int i = 0; i < items.size() && i < MAX_LIJST; i++) arts.put(items.get(i));
            JSONArray bew = new JSONArray(), bw = leesLijst(new File(dir, "bewaard.json"));
            if (bw != null) for (int i = 0; i < bw.length(); i++) { JSONObject a = bw.optJSONObject(i); if (a != null) bew.put(kort(a)); }
            JSONObject g = leesObject(new File(dir, "gelezen.json"));
            JSONArray gel = new JSONArray();
            if (g != null) for (Iterator<String> it = g.keys(); it.hasNext(); ) gel.put(it.next());
            return new JSONObject().put("bronnen", bs).put("artikelen", arts).put("bewaard", bew).put("gelezen", gel).put("bezig", BEZIG.get());
        }
    }

    private static JSONObject zoekBewaard(File dir, String id) {
        JSONArray bw = leesLijst(new File(dir, "bewaard.json"));
        if (bw != null) for (int i = 0; i < bw.length(); i++) { JSONObject a = bw.optJSONObject(i); if (a != null && a.optString("id").equals(id)) return a; }
        return null;
    }

    private static JSONObject zoekCache(File dir, File cache, String id) throws JSONException {
        JSONArray bs = bronnen(dir);
        for (int i = 0; i < bs.length(); i++) {
            JSONObject b = bs.getJSONObject(i);
            JSONObject c = leesObject(cacheBestand(cache, b.getString("id")));
            JSONArray as = c == null ? null : c.optJSONArray("artikelen");
            if (as != null) for (int j = 0; j < as.length(); j++) {
                JSONObject a = as.optJSONObject(j);
                if (a != null && a.optString("id").equals(id)) return new JSONObject(a.toString()).put("bronnaam", b.optString("naam"));
            }
        }
        return null;
    }

    /** Een artikel met de hele tekst (eerst bij de bewaarde, dan in de cache). */
    static JSONObject artikel(File dir, File cache, String id) throws Exception {
        if (id == null || !ART_ID.matcher(id).matches()) throw new Fout(Taal.T("Dit artikel is niet meer beschikbaar"));
        synchronized (LOCK) {
            JSONObject b = zoekBewaard(dir, id), a = b != null ? new JSONObject(b.toString()) : zoekCache(dir, cache, id);
            if (a == null) throw new Fout(Taal.T("Dit artikel is niet meer beschikbaar"));
            JSONObject g = leesObject(new File(dir, "gelezen.json"));
            return a.put("isBewaard", b != null).put("gelezen", g != null && g.has(id));
        }
    }

    /** Gelezen of weer ongelezen. Wat ouder is dan 60 dagen valt weg (behalve bij bewaarde artikelen). */
    static void gelezen(File dir, JSONArray ids, boolean aan, long nu) throws Exception {
        synchronized (LOCK) {
            File f = new File(dir, "gelezen.json");
            JSONObject g = leesObject(f);
            if (g == null) g = new JSONObject();
            for (int i = 0; i < ids.length(); i++) {
                String id = ids.optString(i);
                if (!ART_ID.matcher(id).matches()) continue;
                if (!aan) g.remove(id);
                else if (!g.has(id)) g.put(id, nu);
            }
            Set<String> bewaard = new HashSet<>();
            JSONArray bw = leesLijst(new File(dir, "bewaard.json"));
            if (bw != null) for (int i = 0; i < bw.length(); i++) { JSONObject a = bw.optJSONObject(i); if (a != null) bewaard.add(a.optString("id")); }
            List<String> sleutels = new ArrayList<>();
            for (Iterator<String> it = g.keys(); it.hasNext(); ) sleutels.add(it.next());
            final JSONObject gg = g;
            Collections.sort(sleutels, (x, y) -> Long.compare(gg.optLong(y), gg.optLong(x)));
            for (int i = 0; i < sleutels.size(); i++) {
                String k = sleutels.get(i);
                if (bewaard.contains(k)) continue;
                if (i >= MAX_GELEZEN || g.optLong(k) < nu - GELEZEN_BEWAREN) g.remove(k);
            }
            schrijf(f, g.toString(), false);
        }
    }

    /** Het laatst niet meer bewaarde artikel: zo zet Bewaren het terug, ook als het intussen uit de feed is. */
    private static JSONObject wegGehaald;

    /** Bewaren (een kopie met de hele tekst, nieuwste bovenaan) of niet meer bewaren. */
    static void bewaar(File dir, File cache, String id, boolean aan, long nu) throws Exception {
        if (id == null || !ART_ID.matcher(id).matches()) throw new Fout(Taal.T("Dit artikel is niet meer beschikbaar"));
        synchronized (LOCK) {
            File f = new File(dir, "bewaard.json");
            JSONArray oud = leesLijst(f), rest = new JSONArray();
            boolean al = false;
            JSONObject weg = null;
            if (oud != null) for (int i = 0; i < oud.length(); i++) {
                JSONObject a = oud.optJSONObject(i);
                if (a == null) continue;
                if (a.optString("id").equals(id)) { al = true; weg = a; } else rest.put(a);
            }
            if (aan == al) return;
            JSONArray uit = rest;
            if (!aan) wegGehaald = weg;
            if (aan) {
                JSONObject a = zoekCache(dir, cache, id);
                if (a == null && wegGehaald != null && id.equals(wegGehaald.optString("id"))) a = new JSONObject(wegGehaald.toString());
                if (a == null) throw new Fout(Taal.T("Dit artikel is niet meer beschikbaar"));
                if (rest.length() >= MAX_BEWAARD) throw new Fout(Taal.fill(Taal.T("Je hebt al {n} artikelen bewaard"), "n", MAX_BEWAARD));
                uit = new JSONArray().put(a.put("bewaard", nu));
                for (int i = 0; i < rest.length(); i++) uit.put(rest.get(i));
            }
            schrijf(f, uit.toString(), true);
        }
    }

    // ---------------------------------------------------------------- ophalen

    static final class Antwoord {
        byte[] data = new byte[0];
        String url = "", charset;
    }

    static final class Gelezen {
        final NieuwsFeed.Feed feed;
        final String url;
        Gelezen(NieuwsFeed.Feed feed, String url) { this.feed = feed; this.url = url; }
    }

    private static final Pattern CHARSET = Pattern.compile("(?i)charset\\s*=\\s*\"?([A-Za-z0-9._:-]{1,40})");

    private static Fout antwoord(int code) { return new HttpFout(code); }

    /** Verbreekt een verbinding die over zijn tijd gaat (ook als hij nog op het antwoord van de server wacht). */
    private static final ScheduledThreadPoolExecutor WACHTER = wachter();

    private static ScheduledThreadPoolExecutor wachter() {
        ScheduledThreadPoolExecutor w = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "nieuws-wachter");
            t.setDaemon(true);
            return t;
        });
        w.setRemoveOnCancelPolicy(true);
        return w;
    }

    /** Het toestel zelf, het thuisnetwerk of een ander niet-openbaar adres (ook achter een naam als 127.0.0.1.nip.io). */
    static boolean intern(InetAddress a) {
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress() || a.isMulticastAddress()) return true;
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int o = b[0] & 0xff, o2 = b[1] & 0xff;
            // 0/8, 240/4 en hoger, 100.64/10 (provider-NAT), 198.18/15 (testnetwerken), 192.0.0/24 (IETF-protocollen)
            return o == 0 || o >= 240 || (o == 100 && (o2 & 0xc0) == 64) || (o == 198 && (o2 & 0xfe) == 18) || (o == 192 && o2 == 0 && b[2] == 0);
        }
        if (b.length != 16) return true;
        // Een IPv4-adres in IPv6: ::ffff:a.b.c.d en ::a.b.c.d (eerste 10 of 12 bytes nul), 64:ff9b::a.b.c.d (NAT64)
        boolean nul = true;
        for (int i = 0; i < 10 && nul; i++) nul = b[i] == 0;
        boolean v4 = nul && ((b[10] == (byte) 0xff && b[11] == (byte) 0xff) || (b[10] == 0 && b[11] == 0));
        boolean nat64 = b[0] == 0 && b[1] == 0x64 && b[2] == (byte) 0xff && b[3] == (byte) 0x9b;
        for (int i = 4; i < 12 && nat64; i++) nat64 = b[i] == 0;
        if (v4 || nat64) return intern4(Arrays.copyOfRange(b, 12, 16));
        // 64:ff9b:1::/48 (NAT64 voor eigen gebruik): niet openbaar
        if (b[0] == 0 && b[1] == 0x64 && b[2] == (byte) 0xff && b[3] == (byte) 0x9b && b[4] == 0 && b[5] == 1) return true;
        // ::ffff:0:a.b.c.d (SIIT), 100::/64 (weggooien), 2001::/32 (Teredo) en 2001:db8::/32 (voorbeelden): geen nieuwssites
        boolean acht = true; // de eerste 8 bytes na het begin (0 of 01 00) nul?
        for (int i = 1; i < 8 && acht; i++) acht = b[i] == 0;
        if (acht && b[0] == 0 && b[8] == (byte) 0xff && b[9] == (byte) 0xff && b[10] == 0 && b[11] == 0) return true;
        if (acht && b[0] == 0x01) return true;
        if (b[0] == 0x20 && b[1] == 0x01 && ((b[2] == 0 && b[3] == 0) || (b[2] == 0x0d && b[3] == (byte) 0xb8))) return true;
        // 2002::/16 (6to4): het IPv4-adres staat in byte 2 t/m 5
        if (b[0] == 0x20 && b[1] == 0x02) return intern4(Arrays.copyOfRange(b, 2, 6));
        return (b[0] & 0xfe) == 0xfc;
    }

    private static boolean intern4(byte[] v4) {
        try { return intern(InetAddress.getByAddress(v4)); } catch (Exception e) { return true; }
    }

    /** Hoeveel tijd er nog is tot {@code tot}; voorbij = de site is te traag. */
    private static int rest(long tot) throws Fout {
        long r = tot - System.currentTimeMillis();
        if (r <= 0) throw new TeTraag();
        return (int) Math.max(1000, Math.min(r, 20_000));
    }

    /** Een adres ophalen: alleen https naar openbare sites, ook bij doorverwijzingen; hooguit MAX_FEED bytes, klaar vóór {@code tot}. */
    static Antwoord haal(String adres, long tot) throws Exception { return haal(adres, tot, false, null, UA); }

    /** Waar een doorverwijzing ({@code loc}) heen gaat: alleen https, en bij een artikelpagina alleen binnen de eigen site. */
    static String volgende(String adres, String huidig, String loc, boolean pagina) throws Exception {
        String u = new URL(new URL(huidig), loc.trim()).toString();
        if (pagina && !host(u).equals(host(adres))) throw new Doorverwezen();
        if (!https(u)) throw new Fout(Taal.T("Alleen https-adressen"));
        return u;
    }

    /** Stuurt een artikelpagina door naar een pagina erboven (de voorpagina, /nieuws/)? Dan is het artikel er niet meer. */
    static boolean weg(String adres, String eind) {
        try {
            String a = zonderSlash(new URL(adres).getPath()), e = zonderSlash(new URL(eind).getPath());
            return e.length() < a.length() && a.startsWith(e + "/");
        } catch (Exception x) { return false; }
    }

    /**
     * Blijft een doorverwijzing bij hetzelfde artikel? Het begin van het pad (/nieuws/12345/) moet blijven; alleen de titel
     * erachter mag anders. Van een andere pagina op de site (/privacy-gate/…, inloggen) geen afbeelding, ook niet als die
     * een og:image (het logo) heeft.
     */
    static boolean zelfdeArtikel(String adres, String eind) {
        try {
            String a = new URL(adres).getPath(), e = new URL(eind).getPath();
            int i = a.indexOf('/', 1);
            if (i >= 0) i = a.indexOf('/', i + 1);
            return e.startsWith(i < 0 ? a : a.substring(0, i + 1));
        } catch (Exception x) { return false; }
    }

    private static String zonderSlash(String pad) {
        int n = pad.length();
        while (n > 0 && pad.charAt(n - 1) == '/') n--;
        return pad.substring(0, n);
    }

    /**
     * Zo ook een artikelpagina ({@code pagina}): daarvan alleen de eerste MAX_PAGINA bytes (de kop met de afbeelding), met de
     * cookies van de site zelf ({@code k}), als {@code ua} en nooit naar een andere site.
     */
    static Antwoord haal(String adres, long tot, boolean pagina, Koekjes k, String ua) throws Exception {
        String u = adres;
        int max = pagina ? MAX_PAGINA : MAX_FEED;
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            if (!https(u)) throw new Fout(Taal.T("Alleen https-adressen"));
            for (InetAddress ia : InetAddress.getAllByName(IDN.toASCII(new URL(u).getHost()))) if (intern(ia)) throw new Fout(Taal.T("Dit adres is niet van een site op internet"));
            HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
            AtomicBoolean teLaat = new AtomicBoolean();
            ScheduledFuture<?> wacht = WACHTER.schedule(() -> {
                teLaat.set(true);
                try { h.disconnect(); } catch (Throwable ignored) { }
            }, Math.max(1, tot - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            try {
                h.setInstanceFollowRedirects(false);
                h.setConnectTimeout(Math.min(15_000, rest(tot)));
                h.setReadTimeout(rest(tot));
                h.setUseCaches(false);
                h.setRequestProperty("User-Agent", ua);
                if (pagina) h.setRequestProperty("Accept-Language", talen(telefoonTalen()));
                h.setRequestProperty("Accept", pagina ? "text/html, application/xhtml+xml;q=0.9, */*;q=0.2"
                        : "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.9, text/html;q=0.5, */*;q=0.2");
                // k beslist per adres (ook na een doorverwijzing) of er cookies meegaan: zie ververs
                String ck = k != null ? k.voor(u) : null;
                if (ck != null && !ck.isEmpty()) h.setRequestProperty("Cookie", ck);
                int code = h.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = h.getHeaderField("Location");
                    if (loc == null || loc.trim().isEmpty()) throw antwoord(code);
                    u = volgende(adres, u, loc, pagina);
                    continue;
                }
                if (code != 200) throw antwoord(code);
                if (pagina && weg(adres, u)) throw new HttpFout(404);
                if (pagina && !zelfdeArtikel(adres, u)) {
                    String p = new URL(u).getPath().toLowerCase(Locale.ROOT);
                    if (p.startsWith("/privacy-gate") || p.startsWith("/privacygate")) throw new Doorverwezen();
                    throw new Elders();
                }
                long lengte = h.getContentLengthLong();
                if (!pagina && lengte > MAX_FEED) throw new Fout(Taal.T("Feed is te groot"));
                Antwoord a = new Antwoord();
                a.url = u;
                String ct = h.getContentType();
                Matcher m = CHARSET.matcher(ct == null ? "" : ct);
                if (m.find()) a.charset = m.group(1);
                ByteArrayOutputStream out = new ByteArrayOutputStream(lengte > 0 ? (int) Math.min(lengte, max) : 65536);
                try (InputStream in = new PodcastFeed.Limited(h.getInputStream(), max)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((!pagina || out.size() < max) && (n = in.read(buf)) > 0) { out.write(buf, 0, n); rest(tot); }
                }
                a.data = out.toByteArray();
                return a;
            } catch (IOException e) {
                if (teLaat.get() || System.currentTimeMillis() >= tot) throw new TeTraag();
                throw e;
            } finally { wacht.cancel(false); h.disconnect(); }
        }
        throw new Fout(Taal.T("Te veel doorverwijzingen"));
    }

    private static final Pattern LINK_TAG = Pattern.compile("(?i)<link\\b[^>]{0,2000}>");
    private static final Pattern ATTR = Pattern.compile("(?i)\\b(rel|type|href)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>\"']+)");
    private static final Set<String> FEED_TYPES = new HashSet<>(Arrays.asList("application/rss+xml", "application/atom+xml", "application/rdf+xml"));

    /** Het feedadres dat een webpagina zelf noemt ({@code <link rel="alternate" type="application/rss+xml">}), alleen https. */
    static String zoekFeed(String html, String basis) {
        Matcher m = LINK_TAG.matcher(html.length() > 1_000_000 ? html.substring(0, 1_000_000) : html);
        while (m.find()) {
            String rel = "", type = "", href = "";
            Matcher a = ATTR.matcher(m.group());
            while (a.find()) {
                String v = a.group(2);
                if (v.startsWith("\"") || v.startsWith("'")) v = v.substring(1, v.length() - 1);
                v = NieuwsFeed.ontsnap(v).trim();
                String k = a.group(1).toLowerCase(Locale.ROOT);
                if (k.equals("rel")) rel = v.toLowerCase(Locale.ROOT);
                else if (k.equals("type")) type = v.toLowerCase(Locale.ROOT);
                else href = v;
            }
            if (href.isEmpty() || !(" " + rel.replaceAll("\\s+", " ") + " ").contains(" alternate ")) continue;
            if (!FEED_TYPES.contains(type.replaceFirst(";.*", "").trim())) continue;
            try { String u = new URL(new URL(basis), href).toString(); if (https(u)) return u; } catch (Exception ignored) { }
        }
        return null;
    }

    private static final Pattern HTML = Pattern.compile("(?i)<(!doctype\\s+html|html|head|body)\\b");

    private static NieuwsFeed.Feed parse(Antwoord a) throws Exception { return NieuwsFeed.parse(new ByteArrayInputStream(a.data), a.charset); }

    /** Een feed lezen; is het adres een webpagina, dan de feed die die pagina noemt. */
    static Gelezen lees(String adres, long tot) throws Exception { return lees(adres, tot, null, UA); }

    /**
     * Zo ook met de cookies van een inlog ({@code k}, beslist per adres) en als {@code ua}. Noemt de webpagina geen feed,
     * dan de gangbare feedadressen op dezelfde site ({@link #kandidaten}).
     */
    static Gelezen lees(String adres, long tot, Koekjes k, String ua) throws Exception {
        Antwoord a = haal(adres, tot, false, k, ua);
        try { return new Gelezen(parse(a), a.url); }
        catch (NieuwsFeed.GeenFeed g) {
            Gelezen kaart = alsSitemap(a, tot, k, ua);
            if (kaart != null) return kaart;
            int n = Math.min(a.data.length, 1_000_000);
            String html = new String(a.data, 0, n, PodcastFeed.charset(a.data, n, a.charset));
            String alt = zoekFeed(html, a.url);
            boolean isHtml = HTML.matcher(html.length() > 4096 ? html.substring(0, 4096) : html).find();
            if (alt == null && isHtml) {
                // op de site die Rene opgaf (niet een cookiemuur of andere site waarheen de pagina doorstuurde)
                String site = host(a.url).equals(host(adres)) ? a.url : adres;
                for (String u : kandidaten(site)) {
                    if (System.currentTimeMillis() >= tot) break;
                    try { Antwoord b = haal(u, tot, false, k, ua); return new Gelezen(parse(b), b.url); }
                    catch (TeTraag t) { break; }
                    catch (Exception volgende) { /* niet hier */ }
                }
                // Geen feed: dan de sitemap van de site (artikelen met adres en datum; de rest komt van de pagina's)
                for (String u : sitemaps(site)) {
                    if (System.currentTimeMillis() >= tot) break;
                    try { Gelezen g2 = alsSitemap(haal(u, tot, false, k, ua), tot, k, ua); if (g2 != null) return g2; }
                    catch (TeTraag t) { break; }
                    catch (Exception volgende) { /* niet hier */ }
                }
                throw new Fout(Taal.T("Op deze site staat geen nieuwsfeed of sitemap. Zoek op de site naar RSS en vul dat adres in."));
            }
            if (alt == null) throw g;
            Antwoord b = haal(alt, tot, false, k, ua);
            return new Gelezen(parse(b), b.url);
        }
    }

    /** Waar een site zijn sitemap meestal heeft (op dezelfde site). */
    static final String[] SITEMAP_PADEN = {"/sitemap.xml", "/sitemap_index.xml"};
    static final int MAX_DEELKAARTEN = 3;

    static List<String> sitemaps(String pagina) {
        List<String> uit = new ArrayList<>();
        try { URL p = new URL(pagina); for (String pad : SITEMAP_PADEN) { String u = new URL(p, pad).toString(); if (https(u)) uit.add(u); } }
        catch (Exception ignored) { }
        return uit;
    }

    /** Is dit antwoord een sitemap? Dan de artikelen eruit (nieuwste eerst); anders null. Een sitemapindex: hooguit 3 delen van dezelfde site. */
    static Gelezen alsSitemap(Antwoord a, long tot, Koekjes k, String ua) throws Exception {
        NieuwsFeed.Sitemap kaart;
        try { kaart = NieuwsFeed.sitemap(new ByteArrayInputStream(a.data), a.charset); }
        catch (NieuwsFeed.GeenFeed geen) { return null; }
        if (kaart.index) {
            NieuwsFeed.Sitemap alles = new NieuwsFeed.Sitemap();
            for (String u : deelkaarten(kaart, a.url)) {
                if (System.currentTimeMillis() >= tot) break;
                try {
                    NieuwsFeed.Sitemap deel = NieuwsFeed.sitemap(new ByteArrayInputStream(haal(u, tot, false, k, ua).data), null);
                    if (!deel.index) alles.urls.addAll(deel.urls);
                } catch (TeTraag t) { break; } catch (Exception volgende) { /* een ander deel */ }
            }
            kaart = alles;
        }
        return new Gelezen(uitKaart(kaart, a.url), a.url);
    }

    /** De delen van een sitemapindex die het meest op nieuws lijken (post, news, nieuws, article, blog), dan de nieuwste; alleen van dezelfde site. */
    static List<String> deelkaarten(NieuwsFeed.Sitemap index, String basis) {
        List<Object[]> l = new ArrayList<>();
        for (Object[] u : index.urls) {
            String loc = link((String) u[0], basis);
            if (https(loc) && host(loc).equals(host(basis))) l.add(new Object[]{loc, u[1]});
        }
        Collections.sort(l, (x, y) -> {
            int nx = nieuwsachtig((String) x[0]) ? 0 : 1, ny = nieuwsachtig((String) y[0]) ? 0 : 1;
            return nx != ny ? Integer.compare(nx, ny) : Long.compare((Long) y[1], (Long) x[1]);
        });
        List<String> uit = new ArrayList<>();
        for (int i = 0; i < l.size() && uit.size() < MAX_DEELKAARTEN; i++) uit.add((String) l.get(i)[0]);
        return uit;
    }

    private static final Pattern NIEUWSACHTIG = Pattern.compile("(?i)post|news|nieuws|article|artikel|blog");
    private static boolean nieuwsachtig(String u) { return NIEUWSACHTIG.matcher(u).find(); }

    /** Een sitemap als feed: de pagina's (niet de voorpagina), nieuwste eerst, hooguit NieuwsFeed.MAX_ITEMS; titel en tekst vult {@link #aanvullen} aan. */
    static NieuwsFeed.Feed uitKaart(NieuwsFeed.Sitemap kaart, String basis) {
        NieuwsFeed.Feed f = new NieuwsFeed.Feed();
        f.uitSitemap = true;
        f.titel = host(basis);
        List<Object[]> l = new ArrayList<>();
        Set<String> gezien = new HashSet<>();
        for (Object[] u : kaart.urls) {
            String loc = link((String) u[0], basis);
            String pad;
            try { pad = zonderSlash(new URL(loc).getPath()); } catch (Exception e) { continue; }
            // alleen pagina's van de site zelf: Tools haalt ze op, en een andere site hoeft niet te zien dat je leest
            if (!https(loc) || !host(loc).equals(host(basis)) || pad.isEmpty() || !gezien.add(loc)) continue;
            l.add(new Object[]{loc, u[1], u.length > 2 ? u[2] : ""});
        }
        Collections.sort(l, (x, y) -> Long.compare((Long) y[1], (Long) x[1]));
        for (int i = 0; i < l.size() && f.artikelen.size() < NieuwsFeed.MAX_ITEMS; i++) {
            NieuwsFeed.Artikel a = new NieuwsFeed.Artikel();
            a.link = (String) l.get(i)[0];
            a.sleutel = a.link;
            a.datum = (Long) l.get(i)[1];
            a.titel = (String) l.get(i)[2];
            f.artikelen.add(a);
        }
        return f;
    }

    /** Wat de kop van een artikelpagina over het artikel zegt (og:, twitter:, article:, title). */
    static final class Kop { String titel = "", tekst = "", beeld = "", auteur = ""; long datum; }

    private static final Pattern TITLE = Pattern.compile("(?i)<title\\b[^>]{0,200}>([^<]{1,500})</title");

    static Kop paginaKop(String html, String basis) {
        Kop k = new Kop();
        String s = html.length() > MAX_PAGINA ? html.substring(0, MAX_PAGINA) : html;
        String ogT = "", twT = "", ogD = "", twD = "", d = "", tijd = "", auteur = "";
        Matcher m = META.matcher(s);
        while (m.find()) {
            String soort = "", inhoud = "";
            Matcher a = META_ATTR.matcher(m.group());
            while (a.find()) {
                String v = a.group(2);
                if (v.startsWith("\"") || v.startsWith("'")) v = v.substring(1, v.length() - 1);
                v = NieuwsFeed.ontsnap(v).trim();
                if (a.group(1).equalsIgnoreCase("content")) inhoud = v; else soort = v.toLowerCase(Locale.ROOT);
            }
            if (inhoud.isEmpty()) continue;
            switch (soort) {
                case "og:title": if (ogT.isEmpty()) ogT = inhoud; break;
                case "twitter:title": if (twT.isEmpty()) twT = inhoud; break;
                case "og:description": if (ogD.isEmpty()) ogD = inhoud; break;
                case "twitter:description": if (twD.isEmpty()) twD = inhoud; break;
                case "description": if (d.isEmpty()) d = inhoud; break;
                case "article:published_time": if (tijd.isEmpty()) tijd = inhoud; break;
                case "author": case "article:author": if (auteur.isEmpty() && !inhoud.startsWith("http")) auteur = inhoud; break;
                default: break;
            }
        }
        String titel = !ogT.isEmpty() ? ogT : !twT.isEmpty() ? twT : "";
        if (titel.isEmpty()) { Matcher t = TITLE.matcher(s); if (t.find()) titel = NieuwsFeed.ontsnap(t.group(1)); }
        k.titel = NieuwsFeed.cut(NieuwsFeed.regel(titel).trim(), 300);
        k.tekst = NieuwsFeed.cut(NieuwsFeed.regel(!ogD.isEmpty() ? ogD : !twD.isEmpty() ? twD : d).trim(), 2000);
        k.auteur = NieuwsFeed.cut(NieuwsFeed.regel(auteur).trim(), 120);
        k.beeld = paginaBeeld(html, basis);
        k.datum = NieuwsFeed.datum(tijd);
        return k;
    }

    /**
     * Artikelen uit een sitemap aanvullen met titel, samenvatting, afbeelding en datum van de pagina zelf ({@code p}, met de
     * cookies van de inlog). Wat de vorige ronde al vond, komt uit de cache ({@code oud}); daarna hooguit MAX_PAGINAS nieuwe
     * pagina's, nieuwste eerst. Een pagina zonder titel (geen artikel, of een muur) valt weg. Lukt geen enkele, dan een fout.
     */
    static void aanvullen(String bron, NieuwsFeed.Feed f, JSONObject oud, long tot, Pagina p) throws Fout {
        if (!f.uitSitemap) return;
        Map<String, JSONObject> bekend = new HashMap<>();
        JSONArray oa = oud == null ? null : oud.optJSONArray("artikelen");
        if (oa != null) for (int i = 0; i < oa.length(); i++) { JSONObject a = oa.optJSONObject(i); if (a != null) bekend.put(a.optString("id"), a); }
        List<NieuwsFeed.Artikel> uit = new ArrayList<>();
        int gehaald = 0;
        boolean stop = p == null;
        for (NieuwsFeed.Artikel a : f.artikelen) {
            if (uit.size() >= MAX_PER_BRON) break;
            JSONObject o = bekend.get(artikelId(bron, a.sleutel));
            if (o != null && !o.optString("titel").isEmpty()) { vanCache(a, o); uit.add(a); continue; }
            if (stop || gehaald >= MAX_PAGINAS || System.currentTimeMillis() >= tot || !https(a.link)) continue;
            gehaald++;
            long paginaTot = Math.min(tot, System.currentTimeMillis() + MAX_PER_PAGINA);
            try {
                String html = p.haal(a.link, paginaTot);
                Kop k = paginaKop(html, a.link);
                if (!k.titel.isEmpty()) a.titel = k.titel;
                if (!k.tekst.isEmpty()) a.blokken.add(new NieuwsFeed.Blok(k.tekst, false));
                a.auteur = k.auteur;
                a.beeld = k.beeld;
                // "geen afbeelding" alleen als de hele kop er was (anders probeert beelden() het later nog eens)
                a.zonderBeeld = k.beeld.isEmpty() && artikelKop(html);
                if (k.datum > 0) a.datum = k.datum;
            } catch (Doorverwezen | Elders | HttpFout e) {
                // naar een andere site of pagina, of weg: geen artikel
            } catch (Throwable e) {
                if (e instanceof TeTraag || e instanceof java.net.SocketTimeoutException) stop = true;
            }
            a.ingekort = true;
            if (!a.titel.isEmpty()) uit.add(a);
        }
        boolean waren = !f.artikelen.isEmpty();
        f.artikelen.clear();
        f.artikelen.addAll(uit);
        if (waren && uit.isEmpty()) throw new Fout(Taal.T("De artikelpagina's van deze site zijn niet te lezen"));
    }

    /** Een artikel zoals de vorige ronde het van de pagina haalde (de datum uit de sitemap gaat voor als die er is). */
    private static void vanCache(NieuwsFeed.Artikel a, JSONObject o) {
        a.titel = o.optString("titel");
        a.auteur = o.optString("auteur");
        a.beeld = o.optString("beeld");
        a.zonderBeeld = o.optBoolean("zonderBeeld");
        a.ingekort = true;
        // de datum van de vorige ronde (van de pagina zelf): een nieuwere lastmod (een bewerking) zet het artikel niet opnieuw bovenaan
        long d = o.optLong("datum");
        if (d > 0) a.datum = d;
        JSONArray bl = o.optJSONArray("blokken");
        if (bl != null) for (int i = 0; i < bl.length(); i++) {
            JSONObject b = bl.optJSONObject(i);
            if (b != null) a.blokken.add(new NieuwsFeed.Blok(b.optString("t"), b.optInt("k") == 1));
        }
    }

    /** Gangbare feedadressen (WordPress, Substack, Ghost, beehiiv, Hugo, Jekyll), alleen op de site van {@code pagina}. */
    static final String[] FEED_PADEN = {"/feed", "/rss", "/feed.xml", "/rss.xml", "/atom.xml", "/index.xml"};

    static List<String> kandidaten(String pagina) {
        List<String> uit = new ArrayList<>();
        try {
            URL p = new URL(pagina);
            for (String pad : FEED_PADEN) {
                String u = new URL(p, pad).toString();
                if (https(u) && !zelfde(u, pagina)) uit.add(u);
            }
        } catch (Exception ignored) { }
        return uit;
    }

    private static final Set<String> EIGEN_TEKST = new HashSet<>(Arrays.asList(
            "Feed is te groot", "Deze feed wordt niet gelezen (onveilige opbouw)"));

    /** De fout zoals hij bewaard wordt (Nederlands; de brug vertaalt hem bij het tonen). */
    static String kort(Throwable e) { return kort(e, Taal.T("Bijwerken lukt niet")); }

    static String kort(Throwable e, String anders) {
        if (e instanceof java.net.UnknownHostException || e instanceof java.net.SocketTimeoutException || e instanceof java.net.ConnectException
                || e instanceof java.net.NoRouteToHostException) return Taal.T("Geen verbinding");
        if (e instanceof Fout || e instanceof NieuwsFeed.GeenFeed) return e.getMessage();
        if (e.getMessage() != null && EIGEN_TEKST.contains(e.getMessage())) return e.getMessage();
        if (e instanceof javax.net.ssl.SSLException) return Taal.T("Geen veilige verbinding met de nieuwssite");
        if (e instanceof org.xml.sax.SAXException) return Taal.T("Deze feed is niet goed leesbaar");
        return anders;
    }

    /**
     * Eén bron ophalen en bewaren; -1 = mislukt (de fout staat dan bij de bron). Een bron die intussen weg is, komt niet terug.
     * Zonder {@code pagina} (Rene wil geen afbeeldingen zien) worden er geen artikelpagina's opgehaald; die zijn klaar vóór
     * {@code eind}, het eind van de hele ronde.
     */
    static int haalBron(File dir, File cache, String id, String url, Pagina pagina, long eind) {
        try {
            JSONObject b;
            synchronized (LOCK) { b = zoekBron(bronnen(dir), id); }
            if (b == null) b = new JSONObject().put("id", id).put("alleen", alleen(id)).put("pagina", paginaBron(id) != null);
            return haalBron(dir, cache, b.put("url", url), pagina, web(null, null), null, UA, eind);
        } catch (JSONException e) { return -1; }
    }

    /**
     * Zo ook met de instellingen van de bron ({@code b}: url, alleen, pagina) en voor de feed de cookies van de inlog
     * ({@code k}) als {@code ua}. Vond Tools de vorige keer de feed op een webpagina, dan eerst meteen die feed. Een sitemap
     * vult {@code kop} aan met titel en tekst van de artikelpagina's (ook als Rene geen afbeeldingen wil).
     */
    static int haalBron(File dir, File cache, JSONObject b, Pagina pagina, Pagina kop, Koekjes k, String ua, long eind) {
        long nu = System.currentTimeMillis();
        String id = b.optString("id"), url = b.optString("url");
        try {
            JSONObject oud;
            synchronized (LOCK) { oud = leesObject(cacheBestand(cache, id)); }
            String eerder = oud == null ? "" : oud.optString("feed");
            Gelezen g = null;
            if (!eerder.isEmpty() && https(eerder) && !zelfde(eerder, url)) {
                try { g = lees(eerder, nu + MAX_HAAL / 2, k, ua); } catch (Exception opnieuwZoeken) { g = null; }
            }
            if (g == null) g = lees(url, nu + MAX_HAAL, k, ua);
            filterOp(b.optString("alleen"), g.url, g.feed);
            aanvullen(id, g.feed, oud, Math.min(nu + MAX_HAAL, eind), kop);
            beelden(id, g.url, g.feed, oud, Math.min(nu + MAX_HAAL, eind), pagina, b.optBoolean("pagina"));
            synchronized (LOCK) { return zoekBron(bronnen(dir), id) != null ? bewaarFeed(cache, id, g.url, g.feed, nu) : -1; }
        } catch (Throwable e) {
            synchronized (LOCK) {
                try { if (zoekBron(bronnen(dir), id) != null) bewaarFout(cache, id, kort(e), nu); } catch (Throwable ignored) { }
            }
            return -1;
        }
    }

    /** Welke bronnen bijgewerkt moeten worden: die aan staan en ouder zijn dan VERS (alles = ook als ze net bijgewerkt zijn). */
    static List<String[]> teDoen(File dir, File cache, boolean alles, long nu) throws JSONException {
        synchronized (LOCK) {
            List<String[]> out = new ArrayList<>();
            JSONArray bs = bronnen(dir);
            for (int i = 0; i < bs.length(); i++) {
                JSONObject b = bs.getJSONObject(i);
                if (!b.optBoolean("aan")) continue;
                JSONObject c = leesObject(cacheBestand(cache, b.getString("id")));
                long t = c == null ? 0 : c.optLong("t"), p = c == null ? 0 : c.optLong("poging");
                boolean vers = c != null && t <= nu && nu - t < VERS; // een tijd in de toekomst: klok teruggezet
                boolean netMis = c != null && c.has("fout") && p <= nu && nu - p < OPNIEUW_NA_FOUT;
                if (alles || (!vers && !netMis)) out.add(new String[]{b.getString("id"), b.getString("url")});
            }
            return out;
        }
    }

    /** De cache van bronnen die er niet meer zijn, weg. */
    static void ruimOp(File dir, File cache) throws JSONException {
        synchronized (LOCK) {
            Set<String> ids = new HashSet<>();
            JSONArray bs = bronnen(dir);
            for (int i = 0; i < bs.length(); i++) ids.add(bs.getJSONObject(i).getString("id") + ".json");
            File[] fs = cache.listFiles();
            if (fs != null) for (File f : fs) if (!ids.contains(f.getName())) f.delete();
        }
    }

    /** De cookies van de WebView (de cookiekeuze uit {@link NieuwsCookiesActivity}). */
    private static String koekjes(String url) {
        try { return android.webkit.CookieManager.getInstance().getCookie(url); } catch (Throwable e) { return null; }
    }

    /** De cookies van de inlog bij een bron, alleen naar de sites van die inlog; null als inloggen uit staat. */
    static Koekjes inlogKoekjes(JSONObject b) {
        if (!b.optBoolean("inlog")) return null;
        String sites = b.optString("inlogSites");
        return u -> siteMag(sites, u) ? koekjes(u) : null;
    }

    /** De User-Agent van de WebView op deze telefoon (die van het venster voor de cookiekeuze), of null. */
    static String webUa(Context c) {
        try { return android.webkit.WebSettings.getDefaultUserAgent(c); } catch (Throwable e) { return null; }
    }

    /** Bijwerken (op een achtergrondthread aanroepen): de bronnen tegelijk, hooguit vier, hooguit MAX_DUUR. */
    static JSONObject ververs(Context c, boolean alles, boolean beelden) throws Exception {
        if (!BEZIG.compareAndSet(false, true)) return new JSONObject().put("bezig", true);
        try {
            File dir = dir(c), cache = cache(c);
            long start = System.currentTimeMillis();
            List<String[]> todo = teDoen(dir, cache, alles, start);
            int ok = 0, nieuw = 0;
            if (!todo.isEmpty()) {
                ExecutorService ex = Executors.newFixedThreadPool(Math.min(4, todo.size()));
                List<Future<Integer>> fs = new ArrayList<>();
                String ua = webUa(c);
                Map<String, WebPagina> paginas = new HashMap<>();
                JSONArray alle;
                synchronized (LOCK) { alle = bronnen(dir); }
                long eind = start + MAX_DUUR - 5_000L;
                for (String[] t : todo) {
                    JSONObject b = zoekBron(alle, t[0]);
                    if (b == null) continue;
                    boolean inlog = b.optBoolean("inlog"), keuze = b.optBoolean("cookiekeuze");
                    String sites = b.optString("inlogSites");
                    // Cookies alleen naar de sites van de inlog (feed en pagina's) en bij Tweakers naar de site met de cookiekeuze (pagina's)
                    Koekjes feedK = inlogKoekjes(b);
                    Koekjes pagK = u -> (inlog && siteMag(sites, u)) || (keuze && koekjesNaar(u)) ? koekjes(u) : null;
                    WebPagina pagina = beelden && b.optBoolean("pagina") ? web(pagK, ua) : null;
                    if (pagina != null) paginas.put(t[0], pagina);
                    // titel en tekst van de pagina's bij een sitemap: dezelfde cookies, maar zonder de keuze voor afbeeldingen
                    WebPagina kop = pagina != null ? pagina : web(pagK, ua);
                    String feedUa = inlog ? paginaUa(ua) : UA;
                    fs.add(ex.submit(() -> haalBron(dir, cache, b, pagina, kop, feedK, feedUa, eind)));
                }
                ex.shutdown();
                for (Future<Integer> f : fs) {
                    try {
                        int n = f.get(Math.max(1, start + MAX_DUUR - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                        if (n >= 0) { ok++; nieuw += n; }
                    } catch (Throwable e) { f.cancel(true); }
                }
                ex.shutdownNow();
                App.log(c, "NIEUWS", "bijgewerkt: " + ok + " van " + todo.size() + " bronnen");
                if (beelden) for (String[] b : todo) if (paginas.containsKey(b[0])) {
                    JSONObject cb;
                    synchronized (LOCK) { cb = leesObject(cacheBestand(cache, b[0])); }
                    WebPagina pagina = paginas.get(b[0]);
                    String regel = cb == null ? "" : beeldLog(cb.optString("beeldStand"), cb.optString("beeldReden"), pagina.alsTools());
                    if (!regel.isEmpty()) App.log(c, "NIEUWS", regel);
                }
            }
            ruimOp(dir, cache);
            return new JSONObject().put("ok", ok).put("totaal", todo.size()).put("nieuw", nieuw);
        } finally { BEZIG.set(false); }
    }

    /** Een bekende bron aanzetten (de site is ingetikt); geeft de bron met al = true. */
    static JSONObject zetBekendeAan(File dir, String id) throws Exception {
        synchronized (LOCK) {
            zetAan(dir, id, true);
            return new JSONObject(zoekBron(bronnen(dir), id).toString()).put("al", true);
        }
    }

    /** Een eigen bron toevoegen: eerst de feed lezen (of op de webpagina zoeken), dan pas bewaren. */
    static JSONObject toevoegen(Context c, String invoer) throws Exception {
        String u = adres(invoer);
        String bekend = bekendeSite(u);
        if (bekend != null) return zetBekendeAan(dir(c), bekend);
        Gelezen g = lees(u, System.currentTimeMillis() + MAX_HAAL);
        if (g.feed.artikelen.isEmpty()) throw new Fout(Taal.T("Deze feed heeft geen artikelen"));
        JSONObject b = voegToe(dir(c), g.url, g.feed.titel);
        try {
            filter(b.getString("id"), g.url, g.feed);
            aanvullen(b.getString("id"), g.feed, null, System.currentTimeMillis() + MAX_HAAL, web(null, webUa(c)));
            bewaarFeed(cache(c), b.getString("id"), g.url, g.feed, System.currentTimeMillis());
        } catch (Fout geenNieuws) { } // de bron staat aan; bijwerken haalt hem later
        App.log(c, "NIEUWS", "bron toegevoegd");
        return b;
    }
}
