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
    };

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
    static void filter(String id, String basis, NieuwsFeed.Feed f) throws Fout {
        String alleen = alleen(id);
        if (alleen.isEmpty() || f.artikelen.isEmpty()) return;
        for (Iterator<NieuwsFeed.Artikel> it = f.artikelen.iterator(); it.hasNext(); ) {
            NieuwsFeed.Artikel a = it.next();
            String l;
            try { l = norm(new URL(new URL(basis), a.link).toURI().normalize().toString()); } catch (Exception e) { l = ""; }
            if (!l.startsWith(alleen)) it.remove();
        }
        if (f.artikelen.isEmpty()) throw new Fout(Taal.T("In deze feed staat nu geen nieuws"));
    }

    /** Bronnen waarvan de feed geen afbeeldingen heeft: die komen dan van de artikelpagina zelf ({@link #beelden}). */
    static final Set<String> BEELD_VAN_PAGINA = new HashSet<>(Collections.singletonList("tweakers"));
    static final int MAX_PAGINA = 64 * 1024, MAX_PAGINAS = 20;
    static final long MAX_PER_PAGINA = 10_000L;

    /** Het begin van een artikelpagina ophalen (in de test zonder internet). */
    interface Pagina { String haal(String url, long tot) throws Exception; }

    static final Pagina WEB = (url, tot) -> {
        Antwoord a = haal(url, tot, true);
        return new String(a.data, 0, a.data.length, PodcastFeed.charset(a.data, a.data.length, a.charset));
    };

    /**
     * Afbeeldingen van de artikelpagina's bij een bron uit {@link #BEELD_VAN_PAGINA}. Wat de vorige ronde al vond (ook dat een
     * pagina er geen heeft) komt uit de cache; daarna hooguit MAX_PAGINAS pagina's van de site zelf, in de volgorde van de
     * feed. Na een fout (de site weigert of is traag) wacht de rest op de volgende ronde. Zonder {@code p} alleen de cache.
     */
    static void beelden(String bron, String basis, NieuwsFeed.Feed f, JSONObject oud, long tot, Pagina p) {
        if (!BEELD_VAN_PAGINA.contains(bron)) return;
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
            if (!a.beeld.isEmpty()) continue;
            String b = bekend.get(artikelId(bron, a.sleutel));
            if (b != null) { a.beeld = b; a.zonderBeeld = b.isEmpty(); continue; }
            String l = link(a.link, basis);
            if (stop || gehaald >= MAX_PAGINAS || System.currentTimeMillis() >= tot || !https(l) || !host(l).equals(site)) continue;
            gehaald++;
            try {
                String u = paginaBeeld(p.haal(l, Math.min(tot, System.currentTimeMillis() + MAX_PER_PAGINA)), l);
                a.beeld = u;
                a.zonderBeeld = u.isEmpty();
            } catch (Throwable e) { stop = true; }
        }
    }

    private static final Pattern META = Pattern.compile("(?i)<meta\\b[^<>]{0,2000}>");
    private static final Pattern META_ATTR = Pattern.compile("(?i)(?<![\\w-])(property|name|content)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>\"']+)");

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
    private static final Pattern BRON_ID = Pattern.compile("[a-z0-9-]{1,40}"), ART_ID = Pattern.compile("n[0-9a-f]{1,16}");

    /** Een fout met een Nederlandse tekst voor Rene (de brug vertaalt hem). */
    static final class Fout extends Exception { Fout(String nl) { super(nl); } }

    static File dir(Context c) { return new File(c.getFilesDir(), "nieuws"); }
    static File cache(Context c) { return new File(c.getCacheDir(), "nieuws"); }

    /** Het cachebestand van een bron. Een id dat geen bron-id kan zijn, komt nooit buiten de map (ook niet als de lijst ooit anders gevuld wordt). */
    static File cacheBestand(File cache, String id) {
        if (id == null || !BRON_ID.matcher(id).matches()) throw new IllegalArgumentException("ongeldige bron");
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

    /** Alle bronnen (eerst de bekende, dan de eigen) met aan/uit. */
    static JSONArray bronnen(File dir) throws JSONException {
        JSONObject o = leesObject(new File(dir, "bronnen.json"));
        JSONObject st = o == null ? null : o.optJSONObject("standaard");
        JSONArray out = new JSONArray();
        for (String[] s : STANDAARD) {
            boolean aan = st != null && st.has(s[0]) ? st.optBoolean(s[0]) : "1".equals(s[3]);
            out.put(new JSONObject().put("id", s[0]).put("naam", s[1]).put("url", s[2]).put("aan", aan).put("eigen", false));
        }
        JSONArray eigen = o == null ? null : o.optJSONArray("eigen");
        Set<String> ids = new HashSet<>();
        if (eigen != null) for (int i = 0; i < eigen.length() && ids.size() < MAX_EIGEN; i++) {
            JSONObject e = eigen.optJSONObject(i);
            if (e == null) continue;
            String id = e.optString("id"), url = e.optString("url");
            if (!id.startsWith("e") || !BRON_ID.matcher(id).matches() || !https(url) || !ids.add(id)) continue;
            String naam = NieuwsFeed.regel(e.optString("naam")).trim();
            out.put(new JSONObject().put("id", id).put("naam", NieuwsFeed.cut(naam.isEmpty() ? host(url) : naam, 80))
                    .put("url", url).put("aan", e.optBoolean("aan", true)).put("eigen", true));
        }
        return out;
    }

    private static void bewaarBronnen(File dir, JSONArray bs) throws Exception {
        JSONObject st = new JSONObject();
        JSONArray eigen = new JSONArray();
        for (int i = 0; i < bs.length(); i++) {
            JSONObject b = bs.getJSONObject(i);
            if (b.optBoolean("eigen")) eigen.put(new JSONObject().put("id", b.getString("id")).put("naam", b.optString("naam"))
                    .put("url", b.getString("url")).put("aan", b.optBoolean("aan")));
            else st.put(b.getString("id"), b.optBoolean("aan"));
        }
        schrijf(new File(dir, "bronnen.json"), new JSONObject().put("v", 1).put("standaard", st).put("eigen", eigen).toString(), false);
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
            JSONObject b = new JSONObject().put("id", "e" + Podcasts.hash(url)).put("naam", naam.isEmpty() ? host(url) : naam)
                    .put("url", url).put("aan", true).put("eigen", true);
            bs.put(b);
            bewaarBronnen(dir, bs);
            return new JSONObject(b.toString());
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
            if (oa != null) for (int i = 0; i < oa.length(); i++) { JSONObject a = oa.optJSONObject(i); if (a != null) bekend.put(a.optString("id"), datum(a)); }
            List<JSONObject> lijst = new ArrayList<>();
            Set<String> gezien = new HashSet<>();
            int nieuw = 0;
            for (NieuwsFeed.Artikel a : f.artikelen) {
                String id = artikelId(bron, a.sleutel);
                if (!gezien.add(id)) continue;
                Long eerder = bekend.get(id);
                long d = a.datum;
                if (d <= 0 || d > nu + 3_600_000L) d = eerder != null ? eerder : nu;
                if (eerder == null) nieuw++;
                lijst.add(json(a, bron, d, basis));
            }
            nieuwsteEerst(lijst);
            JSONArray arr = new JSONArray();
            for (int i = 0; i < lijst.size() && i < MAX_PER_BRON; i++) arr.put(lijst.get(i));
            schrijf(cf, new JSONObject().put("t", nu).put("poging", nu).put("titel", f.titel).put("artikelen", arr).toString(), false);
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
                if (c != null) {
                    b.put("t", c.optLong("t")).put("poging", c.optLong("poging"));
                    if (c.has("fout")) b.put("fout", c.optString("fout"));
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

    private static Fout antwoord(int code) { return new Fout(Taal.fill(Taal.T("De nieuwssite antwoordt met {code}"), "code", code)); }

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
        if (b.length == 4) { int o = b[0] & 0xff; return o == 0 || o >= 240 || (o == 100 && (b[1] & 0xc0) == 64); }
        if (b.length != 16) return true;
        boolean v4 = b[10] == (byte) 0xff && b[11] == (byte) 0xff;
        for (int i = 0; i < 10 && v4; i++) v4 = b[i] == 0;
        if (v4) {
            try { return intern(InetAddress.getByAddress(Arrays.copyOfRange(b, 12, 16))); } catch (Exception e) { return true; }
        }
        return (b[0] & 0xfe) == 0xfc;
    }

    /** Hoeveel tijd er nog is tot {@code tot}; voorbij = de site is te traag. */
    private static int rest(long tot) throws Fout {
        long r = tot - System.currentTimeMillis();
        if (r <= 0) throw new Fout(Taal.T("De nieuwssite reageert te traag"));
        return (int) Math.max(1000, Math.min(r, 20_000));
    }

    /** Een adres ophalen: alleen https naar openbare sites, ook bij doorverwijzingen; hooguit MAX_FEED bytes, klaar vóór {@code tot}. */
    static Antwoord haal(String adres, long tot) throws Exception { return haal(adres, tot, false); }

    /** Zo ook een artikelpagina ({@code pagina}): daarvan alleen de eerste MAX_PAGINA bytes (de kop met de afbeelding). */
    static Antwoord haal(String adres, long tot, boolean pagina) throws Exception {
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
                h.setRequestProperty("User-Agent", UA);
                h.setRequestProperty("Accept", pagina ? "text/html, application/xhtml+xml;q=0.9, */*;q=0.2"
                        : "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.9, text/html;q=0.5, */*;q=0.2");
                int code = h.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = h.getHeaderField("Location");
                    if (loc == null || loc.trim().isEmpty()) throw antwoord(code);
                    u = new URL(new URL(u), loc.trim()).toString();
                    // een artikelpagina die naar een andere site stuurt (een toestemmingspagina): daar niet heen
                    if (pagina && !host(u).equals(host(adres))) throw new Fout(Taal.T("Bijwerken lukt niet"));
                    continue;
                }
                if (code != 200) throw antwoord(code);
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
                if (teLaat.get() || System.currentTimeMillis() >= tot) throw new Fout(Taal.T("De nieuwssite reageert te traag"));
                throw e;
            } finally { wacht.cancel(false); h.disconnect(); }
        }
        throw new Fout(Taal.T("Te veel doorverwijzingen"));
    }

    private static final Pattern LINK_TAG = Pattern.compile("(?i)<link\\b[^>]{0,2000}>");
    private static final Pattern ATTR = Pattern.compile("(?i)\\b(rel|type|href)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)");
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
    static Gelezen lees(String adres, long tot) throws Exception {
        Antwoord a = haal(adres, tot);
        try { return new Gelezen(parse(a), a.url); }
        catch (NieuwsFeed.GeenFeed g) {
            int n = Math.min(a.data.length, 1_000_000);
            String html = new String(a.data, 0, n, PodcastFeed.charset(a.data, n, a.charset));
            String alt = zoekFeed(html, a.url);
            if (alt == null && HTML.matcher(html.length() > 4096 ? html.substring(0, 4096) : html).find())
                throw new Fout(Taal.T("Op deze pagina staat geen nieuwsfeed. Zoek op de site naar RSS en vul dat adres in."));
            if (alt == null) throw g;
            Antwoord b = haal(alt, tot);
            return new Gelezen(parse(b), b.url);
        }
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
     * Zonder {@code beelden} (Rene wil geen afbeeldingen zien) worden er geen artikelpagina's opgehaald.
     */
    static int haalBron(File dir, File cache, String id, String url, boolean beelden) {
        long nu = System.currentTimeMillis();
        try {
            Gelezen g = lees(url, nu + MAX_HAAL);
            filter(id, g.url, g.feed);
            JSONObject oud;
            synchronized (LOCK) { oud = leesObject(cacheBestand(cache, id)); }
            beelden(id, g.url, g.feed, oud, nu + MAX_HAAL, beelden ? WEB : null);
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
                for (String[] b : todo) fs.add(ex.submit(() -> haalBron(dir, cache, b[0], b[1], beelden)));
                ex.shutdown();
                for (Future<Integer> f : fs) {
                    try {
                        int n = f.get(Math.max(1, start + MAX_DUUR - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                        if (n >= 0) { ok++; nieuw += n; }
                    } catch (Throwable e) { f.cancel(true); }
                }
                ex.shutdownNow();
                App.log(c, "NIEUWS", "bijgewerkt: " + ok + " van " + todo.size() + " bronnen");
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
            bewaarFeed(cache(c), b.getString("id"), g.url, g.feed, System.currentTimeMillis());
        } catch (Fout geenNieuws) { } // de bron staat aan; bijwerken haalt hem later
        App.log(c, "NIEUWS", "bron toegevoegd");
        return b;
    }
}
