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

    /** Het script vooraan: direct na &lt;head …&gt;, anders na &lt;html …&gt;, anders helemaal vooraan (vóór een CSP in een meta-tag). */
    static String injecteer(String html, String script, String nonce) {
        String tag = "<script" + (nonce == null ? "" : " nonce=\"" + nonce + "\"") + ">" + script.replace("</script", "<\\/script") + "</script>";
        String laag = html.toLowerCase(Locale.ROOT);
        int na = naTag(laag, "<head");
        if (na < 0) na = naTag(laag, "<html");
        if (na < 0) {
            // een doctype blijft vooraan, anders valt de pagina in de oude weergave
            int d = laag.startsWith("<!doctype") ? laag.indexOf('>') + 1 : 0;
            return html.substring(0, d) + tag + html.substring(d);
        }
        return html.substring(0, na) + tag + html.substring(na);
    }

    private static int naTag(String laag, String begin) {
        int i = laag.indexOf(begin);
        while (i >= 0) {
            char c = i + begin.length() < laag.length() ? laag.charAt(i + begin.length()) : '>';
            if (c == '>' || c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                int e = laag.indexOf('>', i);
                return e < 0 ? -1 : e + 1;
            }
            i = laag.indexOf(begin, i + 1);
        }
        return -1;
    }

    /**
     * Een CSP-kop zo aanpassen dat alleen ons script (met nonce) erbij mag; verder blijft alles hetzelfde. Geen CSP of
     * geen regel voor scripts: ongewijzigd. Mag 'unsafe-inline' al en is er geen nonce of hash (die zou 'unsafe-inline'
     * uitzetten): ongewijzigd. Geeft null als het script er niet bij kan (dan geen script).
     */
    static String csp(String kop, String nonce) {
        if (kop == null || kop.trim().isEmpty()) return kop;
        String[] delen = kop.split(";");
        int script = -1, standaard = -1;
        for (int i = 0; i < delen.length; i++) {
            String n = naam(delen[i]);
            if (n.equals("script-src")) script = i;
            else if (n.equals("default-src")) standaard = i;
        }
        int bron = script >= 0 ? script : standaard;
        if (bron < 0) return kop;
        String waarden = delen[bron].trim().substring(naam(delen[bron]).length()).trim();
        String laag = waarden.toLowerCase(Locale.ROOT);
        boolean inline = laag.contains("'unsafe-inline'"), nonceOfHash = laag.contains("'nonce-") || laag.contains("'sha256-") || laag.contains("'sha384-") || laag.contains("'sha512-");
        if (laag.equals("'none'")) return null;
        if (inline && !nonceOfHash && !laag.contains("'strict-dynamic'")) return kop;
        String nieuw = "script-src " + waarden + " 'nonce-" + nonce + "'";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < delen.length; i++) {
            if (delen[i].trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(i == script ? nieuw : delen[i].trim());
        }
        if (script < 0) sb.append("; ").append(nieuw);
        return sb.toString();
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

    /**
     * De pagina van de speler ophalen en het script erin zetten. null = niet doen (de WebView laadt hem dan gewoon zelf):
     * geen speler, geen GET, een doorverwijzing of een fout. Niet op de hoofdthread.
     */
    static WebResourceResponse haal(WebResourceRequest req, String script, boolean cookies) {
        String u = req.getUrl().toString();
        if (req.isForMainFrame() || !"GET".equalsIgnoreCase(req.getMethod()) || !speler(u)) return null;
        Map<String, String> vraag = req.getRequestHeaders();
        String accept = null;
        if (vraag != null) for (Map.Entry<String, String> e : vraag.entrySet()) if ("accept".equalsIgnoreCase(e.getKey())) accept = e.getValue();
        if (!document(accept)) return null;
        HttpURLConnection h = null;
        try {
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
            if (code != 200) { h.disconnect(); return null; }
            String type = h.getContentType() == null ? "" : h.getContentType();
            String mime = type.split(";")[0].trim().toLowerCase(Locale.ROOT);
            if (!mime.equals("text/html")) { h.disconnect(); return null; }
            String cs = charset(type); // null: de pagina zegt het zelf (meta), de WebView kijkt dat na
            Map<String, String> kop = new LinkedHashMap<>();
            String nonce = nonce();
            boolean mag = true;
            for (Map.Entry<String, List<String>> e : h.getHeaderFields().entrySet()) {
                String k = e.getKey();
                if (k == null || e.getValue() == null || e.getValue().isEmpty()) continue;
                String kl = k.toLowerCase(Locale.ROOT);
                if (kl.equals("content-length") || kl.equals("content-encoding") || kl.equals("transfer-encoding") || kl.equals("content-type")) continue;
                if (kl.equals("set-cookie")) { if (cookies) for (String c : e.getValue()) CookieManager.getInstance().setCookie(u, c); continue; }
                String w = String.join(", ", e.getValue());
                if (kl.equals("content-security-policy")) {
                    // elke kop is een eigen beleid: ons script moet ze allemaal halen
                    StringBuilder nw = new StringBuilder();
                    for (String c : e.getValue()) {
                        String m = csp(c, nonce);
                        if (m == null) { mag = false; m = c; }
                        if (nw.length() > 0) nw.append(", ");
                        nw.append(m);
                    }
                    w = nw.toString();
                }
                kop.put(k, w);
            }
            InputStream in = h.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) {
                buf.write(b, 0, n);
                if (buf.size() > MAX_HTML) {
                    // te groot om aan te passen: ongewijzigd doorgeven (het gelezen stuk plus de rest)
                    return antwoord(mime, cs, kop, new SequenceInputStream(new ByteArrayInputStream(buf.toByteArray()), in), h.getResponseMessage());
                }
            }
            in.close();
            // per byte (ISO-8859-1 houdt elke byte heel): de tekenset van de pagina blijft zoals hij was; het script is ASCII
            byte[] uit = mag ? injecteer(new String(buf.toByteArray(), StandardCharsets.ISO_8859_1), script, nonce).getBytes(StandardCharsets.ISO_8859_1) : buf.toByteArray();
            return antwoord(mime, cs, kop, new ByteArrayInputStream(uit), h.getResponseMessage());
        } catch (Throwable e) {
            try { if (h != null) h.disconnect(); } catch (Throwable ignored) { }
            return null;
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
