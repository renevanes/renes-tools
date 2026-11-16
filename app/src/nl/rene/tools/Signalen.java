package nl.rene.tools;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.URL;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Signalen: bronnen die Tools regelmatig naloopt op iets nieuws (eerste soort: een webpagina met trefwoorden), met één
 * inbox en per bron de keuze hoe het gemeld wordt. Alles gebeurt op de telefoon: Tools haalt de pagina zelf op, er zit
 * geen dienst tussen. Dit bestand is het zuivere deel (getest in SignalenTest) plus de opslag in signalen/.
 */
final class Signalen {
    static final String DIR = "signalen", CHANNEL = "signalen";
    static final int FORMAAT = 1, MAX_BRONNEN = 40, MAX_ITEMS = 500, MAX_GEZIEN = 3000, MAX_PER_SCAN = 6, MAX_LINKS = 800, MAX_WOORDEN = 20;
    /** Hoe vaak een bron nagekeken kan worden, in uren. Standaard 6 (vier keer per dag). */
    static final int[] ELKE = {1, 3, 6, 12, 24, 168};
    /** Na zoveel mislukte scans achter elkaar één melding dat de bron niet meer werkt. */
    static final int FOUT_MELDEN = 3;

    private Signalen() { }

    static class Fout extends Exception { Fout(String nl) { super(nl); } }

    // ---------------------------------------------------------------- bronnen en instellingen

    /** Sjablonen bij "Website toevoegen": alleen een voorzet, alles is daarna aan te passen. */
    static JSONArray sjablonen() {
        JSONArray a = new JSONArray();
        try {
            a.put(new JSONObject().put("sjabloon", "aseal").put("naam", "A Seal: vrijlatingen").put("url", "https://www.aseal.nl/nieuws")
                    .put("woorden", new JSONArray().put("vrijlating").put("vrijgelaten")).put("alleen", "https://www.aseal.nl/nieuws/"));
        } catch (Exception ignored) { }
        return a;
    }

    private static String schoon(String s, int max) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < max; i++) { char ch = s.charAt(i); b.append(ch < ' ' || ch == 127 ? ' ' : ch); }
        return b.toString().trim();
    }

    static boolean hm(String s) { return s != null && s.matches("([01]\\d|2[0-3]):[0-5]\\d"); }

    /** Soorten bronnen: een webpagina, of iets uit Tools zelf. Van elke soort uit Tools is er hooguit één. */
    static final String[] SOORTEN = {"web", "agenda", "nieuws", "facturen", "notities", "taken"};
    static final int[] VOORAF = {1, 3, 12, 24, 48}, DAGEN = {3, 7, 14, 30};

    static String soort(String s) {
        for (String x : SOORTEN) if (x.equals(s)) return x;
        return "web";
    }

    /** Heeft deze soort geen internet nodig (de gegevens staan al op de telefoon)? */
    static boolean lokaal(String soort) { return !"web".equals(soort) && !"nieuws".equals(soort); }

    /** Bij deze soorten is wat er de eerste keer al staat oud nieuws (geen melding); een afspraak of een mislukte taak is dat niet. */
    static boolean eersteStil(String soort) { return !"agenda".equals(soort) && !"taken".equals(soort); }

    /** De tool waar een bericht van deze soort uit komt (voor het slot van die tool en "Open in …"); leeg bij een website. */
    static String tool(String soort) {
        switch (soort == null ? "" : soort) {
            case "agenda": return "agenda";
            case "nieuws": return "nieuws";
            case "facturen": return "facturen";
            case "notities": return "notes";
            default: return "";
        }
    }

    static String naamVan(String soort) {
        switch (soort) {
            case "agenda": return Taal.T("Agenda");
            case "nieuws": return Taal.T("Nieuws");
            case "facturen": return Taal.T("Facturen");
            case "notities": return Taal.T("Notities");
            case "taken": return Taal.T("Taken van Tools");
            default: return "";
        }
    }

    private static int uit(int[] keuzes, int v, int anders) {
        for (int k : keuzes) if (k == v) return v;
        return anders;
    }

    /** Een bron uit de interface nakijken en netjes maken. Een fout is een Nederlandse zin voor Rene. */
    static JSONObject normaal(JSONObject in) throws Exception {
        JSONObject b = new JSONObject();
        String id = in.optString("id"), soort = soort(in.optString("soort", "web"));
        boolean web = "web".equals(soort);
        b.put("id", id.matches("s[0-9a-f]{8,16}") ? id : "");
        b.put("soort", soort);
        String url = web ? in.optString("url").trim() : "";
        if (web) {
            if (!url.isEmpty() && !url.contains("://")) url = "https://" + url;
            if (!NieuwsFeed.httpsOpenbaar(url)) throw new Fout(Taal.T("Vul een https-adres van een website in."));
        }
        b.put("url", url);
        String naam = schoon(in.optString("naam"), 80);
        if (naam.isEmpty()) naam = web ? host(url) : naamVan(soort);
        b.put("naam", naam);
        JSONArray w = new JSONArray(), wi = web || "nieuws".equals(soort) ? in.optJSONArray("woorden") : null;
        Set<String> al = new HashSet<>();
        for (int i = 0; wi != null && i < wi.length() && w.length() < MAX_WOORDEN; i++) {
            String x = schoon(wi.optString(i), 60);
            if (x.length() >= 2 && al.add(vouw(x))) w.put(x);
        }
        if ("nieuws".equals(soort) && w.length() == 0) throw new Fout(Taal.T("Vul minstens één trefwoord in: anders komt al het nieuws in Signalen."));
        b.put("woorden", w);
        String alleen = web ? in.optString("alleen").trim() : "";
        if (!alleen.isEmpty() && (!NieuwsFeed.httpsOpenbaar(alleen) || !host(alleen).equals(host(url))))
            throw new Fout(Taal.T("\"Alleen links die beginnen met\" moet een https-adres op dezelfde site zijn."));
        b.put("alleen", alleen);
        int standaard = "agenda".equals(soort) ? 1 : "nieuws".equals(soort) ? 3 : "notities".equals(soort) ? 24 : 6;
        b.put("elke", uit(ELKE, in.optInt("elke", standaard), standaard));
        b.put("wifi", !lokaal(soort) && in.optBoolean("wifi", true));
        if ("agenda".equals(soort)) b.put("vooraf", uit(VOORAF, in.optInt("vooraf", 24), 24));
        if ("notities".equals(soort)) b.put("dagen", uit(DAGEN, in.optInt("dagen", 7), 7));
        String m = in.optString("melden", "direct");
        b.put("melden", "overzicht".equals(m) || "inbox".equals(m) ? m : "direct");
        b.put("belangrijk", in.optBoolean("belangrijk", false));
        b.put("aan", in.optBoolean("aan", true));
        return b;
    }

    /** Instellingen voor alle bronnen: stille uren en het moment van het dagoverzicht. */
    static JSONObject inst(JSONObject in) throws Exception {
        if (in == null) in = new JSONObject();
        JSONObject o = new JSONObject();
        o.put("stilAan", in.optBoolean("stilAan", true));
        o.put("stilVan", hm(in.optString("stilVan")) ? in.optString("stilVan") : "22:00");
        o.put("stilTot", hm(in.optString("stilTot")) ? in.optString("stilTot") : "07:30");
        o.put("overzicht", hm(in.optString("overzicht")) ? in.optString("overzicht") : "18:00");
        return o;
    }

    /** Verandert er iets waardoor de bron opnieuw moet beginnen (ander adres, andere trefwoorden)? */
    static boolean opnieuw(JSONObject oud, JSONObject nieuw) {
        return oud == null || !oud.optString("soort", "web").equals(nieuw.optString("soort", "web")) || !oud.optString("url").equals(nieuw.optString("url")) || !oud.optString("alleen").equals(nieuw.optString("alleen"))
                || !String.valueOf(oud.optJSONArray("woorden")).equals(String.valueOf(nieuw.optJSONArray("woorden")));
    }

    static String host(String url) {
        try { String h = new URL(url).getHost().toLowerCase(Locale.ROOT); return h.startsWith("www.") ? h.substring(4) : h; }
        catch (Exception e) { return ""; }
    }

    // ---------------------------------------------------------------- pagina's lezen (zuiver)

    /** Kleine letters zonder accenten, voor het vergelijken met trefwoorden. */
    static String vouw(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        StringBuilder b = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char ch = n.charAt(i);
            if (Character.getType(ch) != Character.NON_SPACING_MARK) b.append(Character.toLowerCase(ch));
        }
        return b.toString();
    }

    /** Staat een van de trefwoorden in de tekst? Zonder trefwoorden telt alles. */
    static boolean past(String tekst, JSONArray woorden) {
        if (woorden == null || woorden.length() == 0) return true;
        String t = vouw(tekst);
        for (int i = 0; i < woorden.length(); i++) {
            String w = vouw(woorden.optString(i));
            if (!w.isEmpty() && t.contains(w)) return true;
        }
        return false;
    }

    private static int zoek(String s, String wat, int van) { return zoek(s, wat, van, s.length()); }

    /** Zoekt {@code wat} (hoofdletters maken niet uit) vanaf {@code van}; het begin moet vóór {@code tot} liggen. */
    private static int zoek(String s, String wat, int van, int tot) {
        int n = wat.length(), eind = Math.min(tot, s.length() - n + 1);
        for (int i = Math.max(0, van); i < eind; i++) if (s.regionMatches(true, i, wat, 0, n)) return i;
        return -1;
    }

    /** Platte tekst van een stuk HTML: zonder script en style, tags worden een spatie. Eén doorloop, geen zoekpatronen. */
    static String tekstVan(String html, int max) {
        if (html == null) return "";
        StringBuilder b = new StringBuilder();
        int i = 0, n = html.length();
        boolean wit = true;
        while (i < n && b.length() < max * 2) {
            char ch = html.charAt(i);
            if (ch == '<') {
                if (html.regionMatches(true, i, "<script", 0, 7)) { int e = zoek(html, "</script", i + 7); if (e < 0) break; i = e; }
                else if (html.regionMatches(true, i, "<style", 0, 6)) { int e = zoek(html, "</style", i + 6); if (e < 0) break; i = e; }
                else if (html.startsWith("<!--", i)) { int e = html.indexOf("-->", i + 4); if (e < 0) break; i = e + 3; if (!wit) { b.append(' '); wit = true; } continue; }
                int e = html.indexOf('>', i);
                if (e < 0) break;
                i = e + 1;
                if (!wit) { b.append(' '); wit = true; }
                continue;
            }
            if (ch <= ' ' || ch == 160) { if (!wit) { b.append(' '); wit = true; } }
            else { b.append(ch); wit = false; }
            i++;
        }
        String t = NieuwsFeed.ontsnap(b.toString()).replace(' ', ' ').trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** De waarde van href in een a-tag (het stuk tussen {@code <a} en {@code >}). */
    private static String href(String tag) {
        int i = 0;
        while (true) {
            i = zoek(tag, "href", i);
            if (i < 0) return "";
            boolean los = i == 0 || tag.charAt(i - 1) <= ' ' || tag.charAt(i - 1) == '"' || tag.charAt(i - 1) == '\'';
            int j = i + 4;
            while (j < tag.length() && tag.charAt(j) <= ' ') j++;
            if (los && j < tag.length() && tag.charAt(j) == '=') {
                j++;
                while (j < tag.length() && tag.charAt(j) <= ' ') j++;
                if (j >= tag.length()) return "";
                char q = tag.charAt(j);
                if (q == '"' || q == '\'') { int e = tag.indexOf(q, j + 1); return e < 0 ? "" : tag.substring(j + 1, e); }
                int e = j;
                while (e < tag.length() && tag.charAt(e) > ' ') e++;
                return tag.substring(j, e);
            }
            i += 4;
        }
    }

    /**
     * De links op een pagina naar andere pagina's van dezelfde site: {adres, tekst van de link}, in de volgorde van de
     * pagina, elk adres één keer (de langste tekst telt). Alleen https; het deel na # telt niet mee.
     */
    static List<String[]> links(String html, String basis) {
        List<String[]> uit = new ArrayList<>();
        if (html == null) return uit;
        java.util.Map<String, String[]> per = new java.util.HashMap<>();
        String site = host(basis), zelf = zonderAnker(basis);
        int i = 0, n = html.length(), tags = 0, blok = -2, blokEind = 0;
        int[] blokPos = {-2, -2, -2};
        while (uit.size() < MAX_LINKS && tags++ < MAX_LINKS * 10) { // ook een grens op wat we bekijken: de tijd blijft begrensd
            i = zoek(html, "<a", i);
            if (i < 0) break;
            // wat in een script, style of commentaar staat is geen link op de pagina
            boolean erin = false;
            while (true) {
                if (blok == -2) { blok = volgendBlok(html, blokEind, blokPos); blokEind = blok < 0 ? n : blokSluit(html, blok); }
                if (blok < 0 || i < blok) break;
                if (i < blokEind) { i = blokEind; erin = true; }
                blok = -2;
                if (erin) break;
            }
            if (erin) continue;
            char na = i + 2 < n ? html.charAt(i + 2) : '>';
            if (na > ' ' && na != '>') { i += 2; continue; }
            int e = html.indexOf('>', i);
            if (e < 0) break;
            String tag = html.substring(i + 2, Math.min(e, i + 2 + 4000));
            int sluit = zoek(html, "</a", e, e + 6000);
            int tot = sluit < 0 ? Math.min(n, e + 1 + 600) : sluit;
            String tekst = tekstVan(html.substring(e + 1, tot), 300);
            i = e + 1;
            String h = NieuwsFeed.ontsnap(href(tag)).trim();
            if (h.isEmpty() || h.startsWith("#") || h.regionMatches(true, 0, "javascript:", 0, 11) || h.regionMatches(true, 0, "mailto:", 0, 7) || h.regionMatches(true, 0, "tel:", 0, 4)) continue;
            String u;
            try { u = zonderAnker(new URL(new URL(basis), h).toString()); } catch (Exception x) { continue; }
            if (!NieuwsFeed.httpsOpenbaar(u) || !host(u).equals(site) || u.equals(zelf)) continue;
            String[] al = per.get(u);
            if (al == null) { al = new String[]{u, tekst}; per.put(u, al); uit.add(al); }
            else if (tekst.length() > al[1].length()) al[1] = tekst;
        }
        return uit;
    }

    private static final String[] BLOK = {"<script", "<style", "<!--"}, BLOK_SLUIT = {"</script", "</style", "-->"};

    /** Het eerste script, style of commentaar vanaf {@code van} (-1 = geen). {@code pos} onthoudt per soort de volgende plek, zodat elk stuk van de pagina één keer bekeken wordt. */
    private static int volgendBlok(String html, int van, int[] pos) {
        int uit = -1;
        for (int k = 0; k < 3; k++) {
            if (pos[k] == -2 || (pos[k] >= 0 && pos[k] < van)) pos[k] = zoek(html, BLOK[k], van);
            if (pos[k] >= 0 && (uit < 0 || pos[k] < uit)) uit = pos[k];
        }
        return uit;
    }

    private static int blokSluit(String html, int blok) {
        for (int k = 0; k < 3; k++) if (html.regionMatches(true, blok, BLOK[k], 0, BLOK[k].length())) {
            int e = zoek(html, BLOK_SLUIT[k], blok + BLOK[k].length());
            return e < 0 ? html.length() : e + BLOK_SLUIT[k].length();
        }
        return html.length();
    }

    private static String zonderAnker(String u) { int k = u.indexOf('#'); return k < 0 ? u : u.substring(0, k); }

    /** Het laatste stuk van een adres als woorden ("/nieuws/vrijlating-zeehond/" wordt "vrijlating zeehond"). */
    static String slak(String url) {
        try {
            String p = new URL(url).getPath();
            while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
            String s = p.substring(p.lastIndexOf('/') + 1);
            return java.net.URLDecoder.decode(s, "UTF-8").replace('-', ' ').replace('_', ' ');
        } catch (Exception e) { return ""; }
    }

    /** Een stuk van de tekst rond het eerste trefwoord (of het begin), afgebroken op hele woorden. */
    static String stuk(String tekst, JSONArray woorden, int max) {
        if (tekst == null || tekst.isEmpty()) return "";
        String v = vouw(tekst);
        int plek = -1;
        // vouw kan de lengte veranderen (een losse accentletter verdwijnt): alleen een richtpunt, geen exacte plek
        for (int i = 0; woorden != null && i < woorden.length(); i++) {
            int k = v.indexOf(vouw(woorden.optString(i)));
            if (k >= 0 && (plek < 0 || k < plek)) plek = k;
        }
        int van = plek < 0 ? 0 : Math.max(0, Math.min(tekst.length(), plek) - max / 4);
        while (van > 0 && van < tekst.length() && tekst.charAt(van - 1) != ' ') van++;
        if (van >= tekst.length()) van = 0;
        int tot = Math.min(tekst.length(), van + max);
        if (tot < tekst.length()) { int sp = tekst.lastIndexOf(' ', tot); if (sp > van + max / 2) tot = sp; }
        return (van > 0 ? "… " : "") + tekst.substring(van, tot).trim() + (tot < tekst.length() ? " …" : "");
    }

    // ---------------------------------------------------------------- datum en tijd uit een tekst

    private static final String[] MAANDEN = {"januari", "februari", "maart", "april", "mei", "juni", "juli", "augustus", "september", "oktober", "november", "december"};
    private static final Pattern DATUM_WOORD = Pattern.compile("(?<![0-9])([0-3]?[0-9]) (januari|februari|maart|april|mei|juni|juli|augustus|september|oktober|november|december|jan|feb|mrt|apr|jun|jul|aug|sept|sep|okt|nov|dec)\\b\\.?(?: ((?:19|20)[0-9]{2})(?![0-9]))?");
    private static final Pattern DATUM_CIJFER = Pattern.compile("(?<![0-9])([0-3]?[0-9])[-/]([01]?[0-9])[-/]((?:19|20)[0-9]{2})(?![0-9])");
    private static final Pattern TIJD = Pattern.compile("(?<![0-9:.])([01]?[0-9]|2[0-3])[:.]([0-5][0-9])(?: ?uur\\b|(?![0-9]))");
    private static final Pattern TIJD_UUR = Pattern.compile("(?<![0-9:.,])([01]?[0-9]|2[0-3]) uur\\b");

    private static int maand(String s) {
        for (int i = 0; i < 12; i++) if (MAANDEN[i].startsWith(s) || (s.equals("mrt") && i == 2) || (s.equals("sept") && i == 8)) return i + 1;
        return 0;
    }

    private static int dagen(int jaar, int maand) {
        if (maand == 2) return jaar % 4 == 0 && (jaar % 100 != 0 || jaar % 400 == 0) ? 29 : 28;
        return maand == 4 || maand == 6 || maand == 9 || maand == 11 ? 30 : 31;
    }

    private static String sleutel(int j, int m, int d) {
        if (m < 1 || m > 12 || d < 1 || d > dagen(j, m)) return "";
        return String.format(Locale.ROOT, "%04d-%02d-%02d", j, m, d);
    }

    /**
     * De eerste datum in een Nederlandse tekst ("zaterdag 14 november 2026", "14 nov", "14-11-2026") als {dag, tijd}:
     * dag als 2026-11-14, tijd als 14:00 of leeg. Zonder jaar: het jaar waarin de datum het dichtst bij {@code vandaag}
     * ligt ("geplaatst op 2 november" is dus geen datum volgend jaar). Een datum vanaf vandaag gaat voor op een eerdere.
     * Niets gevonden: {"", ""}.
     */
    static String[] datum(String tekst, String vandaag) {
        String[] geen = {"", ""};
        if (tekst == null || vandaag == null || !vandaag.matches("\\d{4}-\\d{2}-\\d{2}")) return geen;
        String t = vouw(tekst.length() > 6000 ? tekst.substring(0, 6000) : tekst).replace(' ', ' ');
        int nuJaar = Integer.parseInt(vandaag.substring(0, 4));
        String eerste = "", komend = "";
        int eersteNa = -1, komendNa = -1, eerstePlek = Integer.MAX_VALUE, komendPlek = Integer.MAX_VALUE;
        Matcher m = DATUM_WOORD.matcher(t);
        while (m.find()) {
            int d = Integer.parseInt(m.group(1)), mnd = maand(m.group(2));
            String k;
            if (m.group(3) != null) k = sleutel(Integer.parseInt(m.group(3)), mnd, d);
            else k = dichtstbij(nuJaar, mnd, d, vandaag);
            if (k.isEmpty()) continue;
            if (m.start() < eerstePlek) { eerste = k; eerstePlek = m.start(); eersteNa = m.end(); }
            if (k.compareTo(vandaag) >= 0 && m.start() < komendPlek) { komend = k; komendPlek = m.start(); komendNa = m.end(); }
        }
        m = DATUM_CIJFER.matcher(t);
        while (m.find()) {
            String k = sleutel(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
            if (k.isEmpty()) continue;
            if (m.start() < eerstePlek) { eerste = k; eerstePlek = m.start(); eersteNa = m.end(); }
            if (k.compareTo(vandaag) >= 0 && m.start() < komendPlek) { komend = k; komendPlek = m.start(); komendNa = m.end(); }
        }
        String dag = !komend.isEmpty() ? komend : eerste;
        if (dag.isEmpty()) return geen;
        int na = !komend.isEmpty() ? komendNa : eersteNa;
        // de tijd staat meestal vlak na de datum ("om 14.00 uur"); anders de eerste tijd met "uur" in de tekst
        String tijd = tijdIn(t.substring(na, Math.min(t.length(), na + 80)));
        return new String[]{dag, tijd};
    }

    /** Dagnummer (dagen sinds 1970) van een datum. */
    private static long dagNr(int j, int m, int d) {
        int y = m <= 2 ? j - 1 : j;
        long era = (y >= 0 ? y : y - 399) / 400, yoe = y - era * 400;
        long doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1, doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }

    /** Een datum zonder jaar: vorig jaar, dit jaar of volgend jaar, wat het dichtst bij vandaag ligt (bij gelijk: de komende). */
    static String dichtstbij(int nuJaar, int m, int d, String vandaag) {
        long nu = dagNr(nuJaar, Integer.parseInt(vandaag.substring(5, 7)), Integer.parseInt(vandaag.substring(8, 10)));
        String beste = "";
        long afstand = Long.MAX_VALUE;
        for (int j = nuJaar + 1; j >= nuJaar - 1; j--) {
            String k = sleutel(j, m, d);
            if (k.isEmpty()) continue;
            long a = Math.abs(dagNr(j, m, d) - nu);
            if (a < afstand) { afstand = a; beste = k; }
        }
        return beste;
    }

    /** Begint dit als een webpagina? (Een pdf of plaatje waar een link naar wijst lezen we niet als tekst.) */
    static boolean lijktHtml(String s) {
        if (s == null) return false;
        int n = Math.min(s.length(), 4000);
        return zoek(s, "<html", 0, n) >= 0 || zoek(s, "<!doctype html", 0, n) >= 0 || zoek(s, "<head", 0, n) >= 0 || zoek(s, "<body", 0, n) >= 0;
    }

    static String tijdIn(String t) {
        Matcher m = TIJD.matcher(t);
        while (m.find()) {
            boolean uur = m.group().endsWith("uur");
            boolean dubbel = m.group().indexOf(':') >= 0;
            if (!uur && !dubbel) continue; // "14.30" zonder "uur" kan ook een bedrag of een versie zijn
            return String.format(Locale.ROOT, "%02d:%s", Integer.parseInt(m.group(1)), m.group(2));
        }
        m = TIJD_UUR.matcher(t);
        if (m.find()) return String.format(Locale.ROOT, "%02d:00", Integer.parseInt(m.group(1)));
        return "";
    }

    // ---------------------------------------------------------------- wat is nieuw (zuiver)

    static final class Vondst {
        final List<String[]> nieuw = new ArrayList<>();   // {adres, linktekst}: past bij de trefwoorden en nog niet gezien
        final List<String> gezien = new ArrayList<>();    // alle adressen die nu als gezien gelden
        int links;
    }

    /**
     * Welke links van de pagina zijn nieuw voor deze bron? Wat niet bij de trefwoorden past is meteen gezien; van wat wel
     * past hooguit {@code max} per ronde (de rest komt de volgende keer).
     */
    static Vondst nieuw(JSONObject bron, List<String[]> links, Set<String> gezien, int max) {
        Vondst v = new Vondst();
        String alleen = bron.optString("alleen");
        JSONArray woorden = bron.optJSONArray("woorden");
        for (String[] l : links) {
            if (!alleen.isEmpty() && !l[0].startsWith(alleen)) continue;
            v.links++;
            if (gezien.contains(l[0])) continue;
            if (!past(l[1] + " " + slak(l[0]), woorden)) { v.gezien.add(l[0]); continue; }
            if (v.nieuw.size() >= max) continue;
            v.nieuw.add(l);
            v.gezien.add(l[0]);
        }
        return v;
    }

    static String itemId(String link) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(link.getBytes("UTF-8"));
            StringBuilder b = new StringBuilder("i");
            for (int i = 0; i < 8; i++) b.append(String.format(Locale.ROOT, "%02x", h[i] & 255));
            return b.toString();
        } catch (Exception e) { return "i" + Integer.toHexString(link.hashCode()); }
    }

    // ---------------------------------------------------------------- melden: nu, later of in het overzicht (zuiver)

    private static int minuten(String hm) { return Integer.parseInt(hm.substring(0, 2)) * 60 + Integer.parseInt(hm.substring(3)); }

    /** Vallen we nu in de stille uren? (Mag over middernacht lopen; van en tot gelijk = nooit stil.) */
    static boolean stil(JSONObject inst, int minuut) {
        if (inst == null || !inst.optBoolean("stilAan")) return false;
        int van = minuten(inst.optString("stilVan", "22:00")), tot = minuten(inst.optString("stilTot", "07:30"));
        if (van == tot) return false;
        return van < tot ? minuut >= van && minuut < tot : minuut >= van || minuut < tot;
    }

    /** Het einde van de stille uren die nu lopen (in de app-zone). */
    static long stilEinde(JSONObject inst, long nu, TimeZone tz) {
        int tot = minuten(inst.optString("stilTot", "07:30"));
        return Regio.nextAt(tz, tot / 60, tot % 60, 0, nu);
    }

    static final int NU = 0, WACHT = 1, OVERZICHT = 2, STIL = 3, NIET = 4;

    /**
     * Wat er met een item moet gebeuren: NU melden, WACHT tot na de stille uren of tot "later", OVERZICHT (dagoverzicht),
     * STIL (alleen in de inbox, geldt als afgehandeld voor het melden) of NIET (al gemeld of afgehandeld).
     */
    static int wijze(JSONObject item, JSONObject bron, JSONObject inst, long nu, TimeZone tz) {
        if (item.optBoolean("gemeld") || "klaar".equals(item.optString("status"))) return NIET;
        long later = item.optLong("later");
        boolean belangrijk = bron != null && bron.optBoolean("belangrijk");
        boolean stil = !belangrijk && stil(inst, Regio.minuteOfDay(tz, nu));
        if (later > 0) return later > nu || stil ? WACHT : NU; // zelf gevraagd om een herinnering: altijd een eigen melding
        String m = bron == null ? "direct" : bron.optString("melden", "direct");
        if ("inbox".equals(m)) return STIL;
        if ("overzicht".equals(m)) return OVERZICHT;
        return stil ? WACHT : NU;
    }

    /** Het eerstvolgende moment waarop er zonder internet iets te melden kan zijn; Long.MAX_VALUE = niets. */
    static long volgendeMelding(JSONArray items, JSONObject bronnen, JSONObject inst, String overzichtDag, long nu, TimeZone tz) {
        long uit = Long.MAX_VALUE;
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            JSONObject b = bronnen == null ? null : bronnen.optJSONObject(it.optString("bron"));
            int w = wijze(it, b, inst, nu, tz);
            if (w == NU) uit = Math.min(uit, nu + 60_000L);
            else if (w == WACHT) {
                long t = Math.max(nu + 60_000L, it.optLong("later"));
                boolean belangrijk = b != null && b.optBoolean("belangrijk");
                if (!belangrijk && stil(inst, Regio.minuteOfDay(tz, t))) t = stilEinde(inst, t, tz);
                uit = Math.min(uit, t);
            } else if (w == OVERZICHT) {
                int o = minuten(inst.optString("overzicht", "18:00"));
                long vandaag = Regio.toMillis(tz, Regio.dayKey(tz, nu), inst.optString("overzicht", "18:00"));
                boolean gehad = Regio.dayKey(tz, nu).equals(overzichtDag);
                uit = Math.min(uit, !gehad && vandaag <= nu ? nu + 60_000L : Regio.nextAt(tz, o / 60, o % 60, 0, gehad ? Math.max(nu, vandaag) : nu));
            }
        }
        return uit;
    }

    /** Is het tijd voor het dagoverzicht (het moment is geweest en er is vandaag nog geen overzicht gestuurd)? */
    static boolean overzichtNu(JSONObject inst, String overzichtDag, long nu, TimeZone tz) {
        String dag = Regio.dayKey(tz, nu);
        return !dag.equals(overzichtDag) && Regio.toMillis(tz, dag, inst.optString("overzicht", "18:00")) <= nu;
    }

    /** Wanneer een bron weer aan de beurt is: {met elk internet, alleen wifi, zonder internet (bronnen uit Tools zelf)}. */
    static long[] volgendeScan(JSONArray bronnen, JSONObject toestand, long nu) {
        long any = Long.MAX_VALUE, wifi = Long.MAX_VALUE, lokaal = Long.MAX_VALUE;
        for (int i = 0; bronnen != null && i < bronnen.length(); i++) {
            JSONObject b = bronnen.optJSONObject(i);
            if (b == null || !b.optBoolean("aan", true)) continue;
            long m = aanDeBeurt(b, toestand == null ? null : toestand.optJSONObject(b.optString("id")), nu);
            if (lokaal(b.optString("soort", "web"))) lokaal = Math.min(lokaal, m);
            else if (b.optBoolean("wifi", true)) wifi = Math.min(wifi, m); else any = Math.min(any, m);
        }
        return new long[]{any, wifi, lokaal};
    }

    /** Van de berichten die een bron uit Tools aanlevert: welke zijn nog niet gezien? Hooguit {@code max}; {@code gezien} krijgt ze erbij. */
    static List<JSONObject> nieuwIntern(List<JSONObject> kandidaten, Set<String> gezien, int max) {
        List<JSONObject> uit = new ArrayList<>();
        for (JSONObject k : kandidaten) {
            String id = k.optString("id");
            if (id.isEmpty() || gezien.contains(id)) continue;
            if (uit.size() >= max) continue;
            gezien.add(id);
            uit.add(k);
        }
        return uit;
    }

    static long aanDeBeurt(JSONObject bron, JSONObject t, long nu) {
        if (t == null) return nu;
        long elke = bron.optInt("elke", 6) * 3_600_000L;
        long ok = t.optLong("lastOk"), poging = t.optLong("lastTry");
        if (ok > nu) ok = 0;        // de klok is teruggezet
        if (poging > nu) poging = 0;
        long m = ok + elke;
        if (poging > ok) m = Math.min(Math.max(m, poging + elke), poging + Math.min(elke, 3_600_000L) * Math.max(1, Math.min(6, t.optInt("fouten", 1))));
        return Math.max(nu, m);
    }

    // ---------------------------------------------------------------- opslag (signalen/, gaat mee in Alle appgegevens)

    static File map(Context c) {
        File d = new File(c.getFilesDir(), DIR);
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /** null = het bestand is er wel maar niet te lezen (dan nooit overschrijven); ontbrekend = leeg. */
    private static JSONObject lees(Context c, String naam) {
        File f = new File(map(c), naam);
        if (!f.isFile()) { try { return new JSONObject().put("format", FORMAAT); } catch (Exception e) { return null; } }
        try {
            JSONObject o = new JSONObject(Contacts.readText(f, false));
            return o.optInt("format", FORMAAT) > FORMAAT ? null : o;
        } catch (Exception e) { return null; }
    }

    private static void schrijf(Context c, String naam, JSONObject o) throws Exception {
        o.put("format", FORMAAT);
        Contacts.writeText(new File(map(c), naam), o.toString(), false); // schrijft eerst een .tmp en hernoemt dan
    }

    static synchronized JSONObject bronBestand(Context c) { return lees(c, "bronnen.json"); }

    static synchronized JSONArray bronnen(Context c) {
        JSONObject o = bronBestand(c);
        JSONArray a = o == null ? null : o.optJSONArray("bronnen");
        return a == null ? new JSONArray() : a;
    }

    static synchronized JSONObject instellingen(Context c) {
        JSONObject o = bronBestand(c);
        try { return inst(o == null ? null : o.optJSONObject("inst")); } catch (Exception e) { return new JSONObject(); }
    }

    static JSONObject perId(JSONArray bronnen) {
        JSONObject o = new JSONObject();
        for (int i = 0; i < bronnen.length(); i++) {
            JSONObject b = bronnen.optJSONObject(i);
            if (b != null) try { o.put(b.optString("id"), b); } catch (Exception ignored) { }
        }
        return o;
    }

    /** Een bron toevoegen of wijzigen. Geeft de bewaarde bron; bij een ander adres of andere trefwoorden begint hij opnieuw. */
    static synchronized JSONObject bewaarBron(Context c, JSONObject in) throws Exception {
        JSONObject o = bronBestand(c);
        if (o == null) throw new Fout(Taal.T("De bronnen van Signalen zijn niet leesbaar. Er is niets overschreven."));
        JSONObject b = normaal(in);
        JSONArray a = o.optJSONArray("bronnen");
        if (a == null) a = new JSONArray();
        int plek = -1;
        for (int i = 0; i < a.length(); i++) if (!b.optString("id").isEmpty() && b.optString("id").equals(a.getJSONObject(i).optString("id"))) plek = i;
        if (plek < 0) {
            if (!"web".equals(b.optString("soort"))) for (int i = 0; i < a.length(); i++) if (b.optString("soort").equals(a.getJSONObject(i).optString("soort")))
                throw new Fout(Taal.T("Die bron staat er al. Je kunt hem aanpassen."));
            if (a.length() >= MAX_BRONNEN) throw new Fout(Taal.fill(Taal.T("Hooguit {n} bronnen."), "n", MAX_BRONNEN));
            b.put("id", "s" + Long.toHexString(System.currentTimeMillis() & 0xffffffffffL) + Integer.toHexString(new java.security.SecureRandom().nextInt(0xfff) + 0x1000).substring(1));
            a.put(b);
        } else {
            if (!a.getJSONObject(plek).optString("soort", "web").equals(b.optString("soort"))) throw new Fout(Taal.T("De soort van een bron kun je niet veranderen."));
            if (opnieuw(a.getJSONObject(plek), b)) {
                JSONObject t = toestandBestand(c);
                if (t != null && t.optJSONObject("bronnen") != null) { t.optJSONObject("bronnen").remove(b.optString("id")); schrijf(c, "toestand.json", t); }
            }
            a.put(plek, b);
        }
        o.put("bronnen", a);
        schrijf(c, "bronnen.json", o);
        return b;
    }

    static synchronized void wisBron(Context c, String id) throws Exception {
        JSONObject o = bronBestand(c);
        if (o == null) throw new Fout(Taal.T("De bronnen van Signalen zijn niet leesbaar. Er is niets overschreven."));
        JSONArray a = o.optJSONArray("bronnen"), uit = new JSONArray();
        for (int i = 0; a != null && i < a.length(); i++) if (!a.getJSONObject(i).optString("id").equals(id)) uit.put(a.getJSONObject(i));
        o.put("bronnen", uit);
        schrijf(c, "bronnen.json", o);
        JSONObject t = toestandBestand(c);
        if (t != null && t.optJSONObject("bronnen") != null) { t.optJSONObject("bronnen").remove(id); schrijf(c, "toestand.json", t); }
    }

    static synchronized void bewaarInst(Context c, JSONObject in) throws Exception {
        JSONObject o = bronBestand(c);
        if (o == null) throw new Fout(Taal.T("De bronnen van Signalen zijn niet leesbaar. Er is niets overschreven."));
        o.put("inst", inst(in));
        schrijf(c, "bronnen.json", o);
    }

    static synchronized JSONObject toestandBestand(Context c) { return lees(c, "toestand.json"); }

    /** Toestand per bron: {gezien:[adressen], klaar, lastOk, lastTry, fout, fouten, links, gemeldFout}. */
    static synchronized JSONObject toestand(Context c) {
        JSONObject o = toestandBestand(c);
        JSONObject b = o == null ? null : o.optJSONObject("bronnen");
        return b == null ? new JSONObject() : b;
    }

    static synchronized String overzichtDag(Context c) {
        JSONObject o = toestandBestand(c);
        return o == null ? "" : o.optString("overzichtDag");
    }

    static synchronized void zetToestand(Context c, String id, JSONObject t, String overzichtDag) {
        try {
            JSONObject o = toestandBestand(c);
            if (o == null) return;
            if (id != null) {
                JSONObject b = o.optJSONObject("bronnen");
                if (b == null) { b = new JSONObject(); o.put("bronnen", b); }
                b.put(id, t);
            }
            if (overzichtDag != null) o.put("overzichtDag", overzichtDag);
            schrijf(c, "toestand.json", o);
        } catch (Exception ignored) { }
    }

    /** null = onleesbaar. Nieuwste eerst. */
    static synchronized JSONArray items(Context c) {
        JSONObject o = lees(c, "items.json");
        if (o == null) return null;
        JSONArray a = o.optJSONArray("items");
        return a == null ? new JSONArray() : a;
    }

    static synchronized boolean bewaarItems(Context c, JSONArray items) {
        try {
            if (lees(c, "items.json") == null) return false;
            JSONArray kort = items;
            if (items.length() > MAX_ITEMS) { kort = new JSONArray(); for (int i = 0; i < MAX_ITEMS; i++) kort.put(items.get(i)); }
            schrijf(c, "items.json", new JSONObject().put("items", kort));
            return true;
        } catch (Exception e) { return false; }
    }

    /** Nieuwe items vooraan zetten; een item met een link die er al staat (ook via een andere bron) komt er niet nog eens bij. */
    static synchronized int voegToe(Context c, List<JSONObject> nieuw) {
        JSONArray oud = items(c);
        if (oud == null || nieuw.isEmpty()) return 0;
        Set<String> al = new HashSet<>();
        for (int i = 0; i < oud.length(); i++) al.add(oud.optJSONObject(i) == null ? "" : oud.optJSONObject(i).optString("id"));
        JSONArray uit = new JSONArray();
        int n = 0;
        for (JSONObject it : nieuw) if (al.add(it.optString("id"))) { uit.put(it); n++; }
        for (int i = 0; i < oud.length(); i++) uit.put(oud.opt(i));
        return bewaarItems(c, uit) ? n : 0;
    }

    /** Eén item bijwerken: gelezen, klaar, open (terug naar nieuw), later (millis) of wis. */
    static synchronized boolean itemActie(Context c, String id, String actie, long arg) {
        JSONArray a = items(c);
        if (a == null) return false;
        JSONArray uit = new JSONArray();
        boolean raak = false;
        try {
            for (int i = 0; i < a.length(); i++) {
                JSONObject it = a.optJSONObject(i);
                if (it == null) continue;
                if (!it.optString("id").equals(id)) { uit.put(it); continue; }
                raak = true;
                if ("wis".equals(actie)) continue;
                if ("gelezen".equals(actie)) { if ("nieuw".equals(it.optString("status", "nieuw"))) it.put("status", "gelezen"); }
                else if ("klaar".equals(actie)) { it.put("status", "klaar"); it.put("later", 0); }
                else if ("open".equals(actie)) { it.put("status", "gelezen"); }
                else if ("later".equals(actie)) {
                    long nu = System.currentTimeMillis();
                    if (arg <= nu || arg > nu + 400L * 86_400_000L) return false;
                    it.put("later", arg); it.put("gemeld", false); it.put("status", "gelezen");
                } else if ("dag".equals(actie)) { it.put("inAgenda", true); }
                else return false;
                uit.put(it);
            }
        } catch (Exception e) { return false; }
        return raak && bewaarItems(c, uit);
    }
}
