package nl.rene.tools;

import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Nieuws: onthouden waar je was in een video in het leesvenster (NieuwsInlogActivity).
 *
 * De video van een artikel zit vaak in een venster (iframe) van een videodienst, waar Tools niet in kan. Daarom haalt
 * Tools de pagina van zo'n speler zelf op (alleen GET, alleen https, alleen de diensten in {@link #SPELERS}) en zet er
 * vooraan het script web/nieuws-video.js in. Dat script bewaart per video alleen een tijd, in de opslag van de speler
 * zelf; er gaat niets naar Tools of naar buiten, en er is geen brug. Lukt iets niet (andere soort antwoord, te groot,
 * fout), dan geeft Tools de pagina ongewijzigd door of laat hij de WebView hem gewoon zelf laden.
 * Het zuivere deel (welke speler, waar het script komt, de CSP) is getest in NieuwsVideoTest.
 */
final class NieuwsVideo {
    private NieuwsVideo() { }

    /** Videodiensten waarvan de speler het script krijgt: mave (video-dns.com, o.a. bij AI Report). */
    static final String[] SPELERS = {"video-dns.com"};
    static final int MAX_HTML = 2_000_000, WACHT_MS = 10_000;

    /** Is dit de pagina van een bekende videospeler (https, host = een dienst of een subdomein ervan)? */
    static boolean speler(String url) {
        if (url == null || !url.regionMatches(true, 0, "https://", 0, 8)) return false;
        String host;
        try { host = new URL(url).getHost().toLowerCase(Locale.ROOT); } catch (Exception e) { return false; }
        for (String s : SPELERS) if (host.equals(s) || host.endsWith("." + s)) return true;
        return false;
    }

    /** Vraagt de pagina om een HTML-document (een venster), niet om een plaatje, script of video? */
    static boolean document(String accept) {
        return accept != null && accept.toLowerCase(Locale.ROOT).contains("text/html");
    }

    /**
     * Het script vooraan: direct na &lt;head …&gt;, anders na &lt;html …&gt;, anders helemaal vooraan (vóór een CSP in een
     * meta-tag; na een doctype en na een UTF-8-BOM). Een &lt;head&gt; in commentaar telt niet.
     */
    static String injecteer(String html, String script, String nonce) {
        String tag = "<script" + (nonce == null ? "" : " nonce=\"" + nonce + "\"") + ">" + script.replace("</script", "<\\/script") + "</script>";
        String laag = html.toLowerCase(Locale.ROOT);
        int na = naTag(laag, "<head");
        if (na < 0) na = naTag(laag, "<html");
        if (na < 0) {
            int d = laag.startsWith("\u00ef\u00bb\u00bf") ? 3 : 0; // UTF-8-BOM (per byte gelezen)
            // een doctype blijft vooraan, anders valt de pagina in de oude weergave
            if (laag.startsWith("<!doctype", d)) d = laag.indexOf('>', d) + 1;
            return html.substring(0, d) + tag + html.substring(d);
        }
        return html.substring(0, na) + tag + html.substring(na);
    }

    /** Begint de pagina met een BOM van UTF-16 (dan per byte niets aan te passen)? */
    static boolean utf16(byte[] b) {
        return b.length >= 2 && ((b[0] == (byte) 0xfe && b[1] == (byte) 0xff) || (b[0] == (byte) 0xff && b[1] == (byte) 0xfe));
    }

    private static int naTag(String laag, String begin) {
        int i = laag.indexOf(begin);
        while (i >= 0) {
            char c = i + begin.length() < laag.length() ? laag.charAt(i + begin.length()) : '>';
            if ((c == '>' || c == ' ' || c == '\t' || c == '\n' || c == '\r') && !inCommentaar(laag, i)) {
                int e = laag.indexOf('>', i);
                return e < 0 ? -1 : e + 1;
            }
            i = laag.indexOf(begin, i + 1);
        }
        return -1;
    }

    private static boolean inCommentaar(String laag, int i) {
        int open = laag.lastIndexOf("<!--", i);
        if (open < 0) return false;
        int dicht = laag.indexOf("-->", open + 4);
        return dicht < 0 || dicht > i;
    }

    /**
     * Een CSP-kop (één of meer beleidsregels, met komma's) zo aanpassen dat alleen ons script (met nonce) erbij mag; verder
     * blijft alles hetzelfde. Per beleid telt voor een &lt;script&gt; de eerste script-src-elem, anders de eerste
     * script-src, anders default-src. Mag 'unsafe-inline' daar al zonder nonce, hash of 'strict-dynamic' (die zouden
     * 'unsafe-inline' uitzetten): dat beleid ongewijzigd. Geeft null als het script er niet bij kan ('none').
     */
    static String csp(String kop, String nonce) {
        if (kop == null || kop.trim().isEmpty()) return kop;
        StringBuilder uit = new StringBuilder();
        for (String beleid : kop.split(",")) {
            String m = cspBeleid(beleid, nonce);
            if (m == null) return null;
            if (m.trim().isEmpty()) continue;
            if (uit.length() > 0) uit.append(", ");
            uit.append(m);
        }
        return uit.toString();
    }

    private static String cspBeleid(String beleid, String nonce) {
        String[] delen = beleid.split(";");
        int elem = -1, script = -1, standaard = -1;
        for (int i = 0; i < delen.length; i++) {
            String n = naam(delen[i]);
            if (n.equals("script-src-elem") && elem < 0) elem = i;
            else if (n.equals("script-src") && script < 0) script = i;
            else if (n.equals("default-src") && standaard < 0) standaard = i;
        }
        int bron = elem >= 0 ? elem : script >= 0 ? script : standaard;
        if (bron < 0) return beleid.trim();
        String n0 = naam(delen[bron]);
        String waarden = delen[bron].trim().substring(n0.length()).trim();
        String laag = waarden.toLowerCase(Locale.ROOT);
        if (laag.equals("'none'") || laag.isEmpty()) return null;
        boolean inline = laag.contains("'unsafe-inline'"), nonceOfHash = laag.contains("'nonce-") || laag.contains("'sha256-") || laag.contains("'sha384-") || laag.contains("'sha512-");
        if (inline && !nonceOfHash && !laag.contains("'strict-dynamic'")) return beleid.trim();
        int doel = elem >= 0 ? elem : script; // de regel die we aanpassen; zonder: een nieuwe script-src
        String nieuw = (doel >= 0 ? n0 : "script-src") + " " + waarden + " 'nonce-" + nonce + "'";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < delen.length; i++) {
            if (delen[i].trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(i == doel ? nieuw : delen[i].trim());
        }
        if (doel < 0) sb.append("; ").append(nieuw);
        return sb.toString();
    }

    /** De koppen van het antwoord voor de WebView: zonder lengte, codering en type (die zet het antwoord zelf), cookies apart, CSP met onze nonce. */
    static final class Koppen {
        final Map<String, String> kop = new LinkedHashMap<>();
        final List<String> koekjes = new java.util.ArrayList<>();
        boolean mag = true;
    }

    static Koppen koppen(Map<String, List<String>> velden, String nonce) {
        Koppen k = new Koppen();
        for (Map.Entry<String, List<String>> e : velden.entrySet()) {
            String naam = e.getKey();
            if (naam == null || e.getValue() == null || e.getValue().isEmpty()) continue;
            String kl = naam.toLowerCase(Locale.ROOT);
            if (kl.equals("content-length") || kl.equals("content-encoding") || kl.equals("transfer-encoding") || kl.equals("content-type")) continue;
            if (kl.equals("set-cookie")) { k.koekjes.addAll(e.getValue()); continue; }
            boolean beleid = kl.equals("content-security-policy") || kl.equals("content-security-policy-report-only");
            StringBuilder w = new StringBuilder();
            for (String v : e.getValue()) {
                String m = v;
                if (beleid) {
                    // elke kop is een eigen beleid: ons script moet ze allemaal halen (ook "alleen melden": anders krijgt de dienst een melding)
                    m = csp(v, nonce);
                    if (m == null) { k.mag = false; m = v; }
                }
                if (w.length() > 0) w.append(", ");
                w.append(m);
            }
            k.kop.put(naam, w.toString());
        }
        return k;
    }

    private static String naam(String deel) {
        String t = deel.trim();
        int s = 0;
        while (s < t.length() && !Character.isWhitespace(t.charAt(s))) s++;
        return t.substring(0, s).toLowerCase(Locale.ROOT);
    }

    /** Het script met de vertaalde teksten erin (vertalen gebeurt in Java: de speler weet de taal van Tools niet). */
    static String script(String bron, String verder, String begin) {
        return ascii(bron.replace("__TEKST__", "{\"verder\":" + json(verder) + ",\"begin\":" + json(begin) + "}"));
    }

    /** Alleen ASCII (al het andere als \\uXXXX): zo kan het script in de bytes van de pagina, welke tekenset die ook heeft. */
    static String ascii(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c < 0x80) sb.append(c);
            else sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
        }
        return sb.toString();
    }

    static String json(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20 || c > 0x7e || c == '<' || c == '>' || c == '&') sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    static String nonce() {
        byte[] b = new byte[18];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return sb.toString();
    }

    static final long TOTAAL_MS = 15_000;

    /** Wat haal() deed: het antwoord met het script, of null met de reden (voor Wat gebeurt er?). */
    static final class Uitkomst {
        final WebResourceResponse antwoord; final String reden;
        Uitkomst(WebResourceResponse a, String r) { antwoord = a; reden = r; }
    }
    private static Uitkomst niet(String reden) { return new Uitkomst(null, reden); }

    /** Een document (venster)? Zegt de WebView geen Accept, dan een adres dat op .html of / eindigt. */
    static boolean documentVraag(String accept, String pad) {
        if (accept != null) return document(accept);
        String p = pad == null ? "" : pad.toLowerCase(Locale.ROOT);
        return p.endsWith(".html") || p.endsWith(".htm") || p.endsWith("/");
    }

    /**
     * De pagina van de speler ophalen en het script erin zetten. Zonder antwoord laadt de WebView hem gewoon zelf:
     * geen speler, geen GET, geen document, een doorverwijzing, een fout, te groot of te traag. Niet op de hoofdthread.
     */
    static Uitkomst haal(WebResourceRequest req, String script, boolean cookies) {
        String u = req.getUrl().toString();
        if (script == null) return niet("geen script");
        if (req.isForMainFrame() || !"GET".equalsIgnoreCase(req.getMethod()) || !speler(u)) return niet("geen speler");
        Map<String, String> vraag = req.getRequestHeaders();
        String accept = null;
        if (vraag != null) for (Map.Entry<String, String> e : vraag.entrySet()) if ("accept".equalsIgnoreCase(e.getKey())) accept = e.getValue();
        if (!documentVraag(accept, req.getUrl().getPath())) return niet("geen document");
        long tot = System.currentTimeMillis() + TOTAAL_MS;
        HttpURLConnection h = null;
        try {
            // zoals Nieuws.haal: niet naar het toestel of het thuisnetwerk
            for (java.net.InetAddress ia : java.net.InetAddress.getAllByName(java.net.IDN.toASCII(req.getUrl().getHost()))) if (Nieuws.intern(ia)) return niet("adres niet op internet");
            h = (HttpURLConnection) new URL(u).openConnection();
            h.setInstanceFollowRedirects(false);
            h.setConnectTimeout(WACHT_MS);
            h.setReadTimeout(WACHT_MS);
            if (vraag != null) for (Map.Entry<String, String> e : vraag.entrySet()) {
                String k = e.getKey();
                if (k == null || k.equalsIgnoreCase("accept-encoding") || k.equalsIgnoreCase("range")) continue;
                h.setRequestProperty(k, e.getValue());
            }
            String koek = cookies ? CookieManager.getInstance().getCookie(u) : null;
            if (koek != null && !koek.isEmpty()) h.setRequestProperty("Cookie", koek);
            int code = h.getResponseCode();
            if (code != 200) { h.disconnect(); return niet("antwoord " + code); }
            String type = h.getContentType() == null ? "" : h.getContentType();
            String mime = type.split(";")[0].trim().toLowerCase(Locale.ROOT);
            if (!mime.equals("text/html")) { h.disconnect(); return niet("geen html"); }
            String cs = charset(type); // null: de pagina zegt het zelf (meta), de WebView kijkt dat na
            String nonce = nonce();
            Koppen k = koppen(h.getHeaderFields(), nonce);
            if (cookies) for (String c : k.koekjes) CookieManager.getInstance().setCookie(u, c);
            boolean mag = k.mag && (cs == null || !cs.toUpperCase(Locale.ROOT).startsWith("UTF-16"));
            InputStream in = h.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) {
                buf.write(b, 0, n);
                if (buf.size() > MAX_HTML || System.currentTimeMillis() > tot) {
                    // te groot of te traag om aan te passen: ongewijzigd doorgeven (het gelezen stuk plus de rest)
                    return new Uitkomst(antwoord(mime, cs, k.kop, new SequenceInputStream(new ByteArrayInputStream(buf.toByteArray()), in), h.getResponseMessage()),
                            buf.size() > MAX_HTML ? "te groot" : "te traag");
                }
            }
            in.close();
            byte[] ruw = buf.toByteArray();
            if (utf16(ruw)) mag = false;
            // per byte (ISO-8859-1 houdt elke byte heel): de tekenset van de pagina blijft zoals hij was; het script is ASCII
            byte[] uit = mag ? injecteer(new String(ruw, StandardCharsets.ISO_8859_1), script, nonce).getBytes(StandardCharsets.ISO_8859_1) : ruw;
            return new Uitkomst(antwoord(mime, cs, k.kop, new ByteArrayInputStream(uit), h.getResponseMessage()), mag ? null : "CSP of tekenset laat het script niet toe");
        } catch (Throwable e) {
            try { if (h != null) h.disconnect(); } catch (Throwable ignored) { }
            return niet("fout");
        }
    }

    private static WebResourceResponse antwoord(String mime, String cs, Map<String, String> kop, InputStream in, String reden) {
        return new WebResourceResponse(mime, cs, 200, reden == null || reden.trim().isEmpty() ? "OK" : reden, kop, in);
    }

    /** De tekenset uit Content-Type, of null als die er niet in staat. */
    static String charset(String type) {
        String l = type == null ? "" : type.toLowerCase(Locale.ROOT);
        int i = l.indexOf("charset=");
        if (i < 0) return null;
        String c = l.substring(i + 8).split(";")[0].trim().replace("\"", "");
        try { return Charset.forName(c).name(); } catch (Exception e) { return null; }
    }
}
