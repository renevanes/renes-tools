package nl.rene.tools;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

/**
 * Leest een podcast-feed (RSS 2.0 met iTunes-velden). Puur Java (SAX), zodat het ook op een gewone JVM te testen
 * is. Begrensd: hooguit maxItems afleveringen, teksten ingekort, en de invoer mag niet eindeloos groot zijn.
 */
final class PodcastFeed {

    static final class Episode {
        String guid = "", title = "", url = "", type = "", image = "", desc = "";
        long date, dur, size; // date in ms, dur in seconden, size in bytes
        int season, number;
    }

    static final class Feed {
        String title = "", author = "", image = "", desc = "", link = "";
        final List<Episode> items = new ArrayList<>();
        boolean truncated;
    }

    /** Genoeg afleveringen gelezen: netjes stoppen (geen echte fout). */
    static final class Enough extends SAXException { Enough() { super("genoeg"); } }

    static Feed parse(InputStream in, int maxItems) throws Exception { return parse(in, maxItems, null); }

    /**
     * httpCharset = tekenset uit de Content-Type-kop (mag null). De tekenset wordt zelf bepaald (BOM, xml-kop,
     * kop van de server): Android's parser negeert anders de xml-kop en leest alles als UTF-8.
     */
    static Feed parse(InputStream raw, int maxItems, String httpCharset) throws Exception {
        java.io.BufferedInputStream in = new java.io.BufferedInputStream(raw, 1 << 16);
        in.mark(4096);
        byte[] head = new byte[4096];
        int n = 0, r;
        while (n < head.length && (r = in.read(head, n, head.length - n)) > 0) n += r;
        in.reset();
        java.nio.charset.Charset cs = charset(head, n, httpCharset);
        // Een BOM niet als eerste teken doorgeven (sommige parsers weigeren dan de hele feed)
        long bom = n >= 3 && (head[0] & 0xff) == 0xEF && (head[1] & 0xff) == 0xBB && (head[2] & 0xff) == 0xBF ? 3
                : n >= 2 && (((head[0] & 0xff) == 0xFE && (head[1] & 0xff) == 0xFF) || ((head[0] & 0xff) == 0xFF && (head[1] & 0xff) == 0xFE)) ? 2 : 0;
        while (bom > 0) { long k = in.skip(bom); if (k <= 0) break; bom -= k; }
        // Eigen entiteiten in een DTD (kunnen exploderen tot enorme tekst): zulke feeds lezen we niet (zie EntityGuard)
        SAXParserFactory f = SAXParserFactory.newInstance();
        f.setNamespaceAware(false);
        // Geen externe bestanden of DTD's laden (XXE)
        for (String feat : new String[]{"http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities",
                "http://apache.org/xml/features/nonvalidating/load-external-dtd"}) {
            try { f.setFeature(feat, false); } catch (Exception ignored) { }
        }
        try { f.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true); } catch (Exception ignored) { }
        SAXParser p = f.newSAXParser();
        Handler h = new Handler(maxItems);
        // Ook verder in het bestand: een entiteit-declaratie stopt het lezen (waar de parser dit doorgeeft)
        try { p.setProperty("http://xml.org/sax/properties/declaration-handler", h); } catch (Exception ignored) { }
        try { p.parse(new org.xml.sax.InputSource(new EntityGuard(new java.io.InputStreamReader(in, cs))), h); }
        catch (Enough e) { h.feed.truncated = true; }
        if (h.feed.title.isEmpty() && h.feed.items.isEmpty()) throw new Exception("Dit is geen podcast-feed");
        return h.feed;
    }

    /**
     * Weigert een entiteit-declaratie waar die ook staat vóór het begin van het hoofdelement (alleen daar kan een
     * DTD staan). Nodig omdat de parser van Android declaraties niet doorgeeft en oudere versies geen grens hebben.
     */
    static final class EntityGuard extends java.io.FilterReader {
        private static final String BAD = "<!ENTITY", COM = "<!--";
        private final StringBuilder tail = new StringBuilder();
        private boolean inComment, done;
        EntityGuard(java.io.Reader r) { super(r); }

        private void see(char ch) throws IOException {
            if (done) return;
            char prev = tail.length() > 0 ? tail.charAt(tail.length() - 1) : 0;
            tail.append(ch);
            if (tail.length() > 8) tail.delete(0, tail.length() - 8);
            String t = tail.toString();
            if (t.endsWith(BAD)) throw new IOException("Deze feed wordt niet gelezen (onveilige opbouw)");
            if (inComment) { if (t.endsWith("-->")) inComment = false; return; }
            if (t.endsWith(COM)) { inComment = true; return; }
            if (prev == '<' && Character.isLetter(ch)) done = true; // het hoofdelement begint: verder geen DTD meer
        }

        @Override public int read() throws IOException { int c = super.read(); if (c >= 0) see((char) c); return c; }

        @Override public int read(char[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (!done) for (int i = 0; i < n; i++) see(b[off + i]);
            return n;
        }
    }

    static final class Handler extends org.xml.sax.ext.DefaultHandler2 {
        final Feed feed = new Feed();
        final int max;
        Episode ep;
        final StringBuilder text = new StringBuilder();
        boolean inImage, inChannel;
        String mediaUrl = "", mediaType = "";
        Handler(int max) { this.max = max; }

        @Override public void internalEntityDecl(String name, String value) throws SAXException { throw new SAXException("Deze feed wordt niet gelezen (onveilige opbouw)"); }
        @Override public void externalEntityDecl(String name, String pub, String sys) throws SAXException { throw new SAXException("Deze feed wordt niet gelezen (onveilige opbouw)"); }

        @Override public void startElement(String uri, String local, String q, Attributes a) throws SAXException {
            text.setLength(0);
            String n = q.toLowerCase(Locale.ROOT);
            if (n.equals("channel")) inChannel = true;
            else if (n.equals("item")) { ep = new Episode(); mediaUrl = ""; mediaType = ""; }
            else if (n.equals("image") && ep == null) inImage = true;
            else if (n.equals("enclosure") && ep != null) {
                String u = attr(a, "url");
                if (ep.url.isEmpty() && !u.isEmpty()) {
                    ep.url = u.trim(); ep.type = attr(a, "type");
                    try { ep.size = Long.parseLong(attr(a, "length").trim()); } catch (Exception ignored) { }
                }
            } else if (n.equals("media:content") && ep != null) {
                String u = attr(a, "url"), t = attr(a, "type");
                if (mediaUrl.isEmpty() && !u.isEmpty() && (t.startsWith("audio") || t.startsWith("video") || t.isEmpty())) { mediaUrl = u.trim(); mediaType = t; }
            } else if (n.equals("itunes:image")) {
                String href = attr(a, "href").trim();
                if (ep != null) { if (ep.image.isEmpty()) ep.image = href; }
                else if (!href.isEmpty()) feed.image = href; // itunes:image heeft voorrang op <image><url>
            }
        }

        @Override public void endElement(String uri, String local, String q) throws SAXException {
            String n = q.toLowerCase(Locale.ROOT), t = text.toString().trim();
            if (ep != null) {
                switch (n) {
                    case "item":
                        if (ep.url.isEmpty() && !mediaUrl.isEmpty()) { ep.url = mediaUrl; ep.type = mediaType; }
                        if (!ep.url.isEmpty()) {
                            if (ep.guid.isEmpty()) ep.guid = ep.url;
                            if (ep.title.isEmpty()) ep.title = "Aflevering";
                            feed.items.add(ep);
                        }
                        ep = null;
                        if (feed.items.size() >= max) throw new Enough();
                        break;
                    case "title": if (ep.title.isEmpty()) ep.title = cut(t, 300); break;
                    case "guid": ep.guid = cut(t, 500); break;
                    case "pubdate": ep.date = parseDate(t); break;
                    case "itunes:duration": ep.dur = parseDuration(t); break;
                    case "itunes:episode": try { ep.number = Integer.parseInt(t); } catch (Exception ignored) { } break;
                    case "itunes:season": try { ep.season = Integer.parseInt(t); } catch (Exception ignored) { } break;
                    case "description": case "content:encoded": case "itunes:summary":
                        if (ep.desc.length() < 40 && !t.isEmpty()) ep.desc = cut(text(t), 1500);
                        break;
                    default: break;
                }
            } else if (inChannel) {
                if (inImage) {
                    if (n.equals("url") && feed.image.isEmpty()) feed.image = t;
                    if (n.equals("image")) inImage = false;
                    return;
                }
                switch (n) {
                    case "title": if (feed.title.isEmpty()) feed.title = cut(t, 300); break;
                    case "itunes:author": feed.author = cut(t, 200); break;
                    case "link": if (feed.link.isEmpty()) feed.link = cut(t, 500); break;
                    case "description": case "itunes:summary": if (feed.desc.length() < 40 && !t.isEmpty()) feed.desc = cut(text(t), 4000); break;
                    default: break;
                }
            }
            text.setLength(0);
        }

        @Override public void characters(char[] ch, int start, int len) {
            if (text.length() < 200_000) text.append(ch, start, Math.min(len, 200_000 - text.length()));
        }

        static String attr(Attributes a, String name) { String v = a.getValue(name); return v == null ? "" : v; }
    }

    /** Tekenset: BOM, dan encoding="…" in de xml-kop, dan de kop van de server, anders UTF-8. */
    static java.nio.charset.Charset charset(byte[] b, int n, String http) {
        if (n >= 3 && (b[0] & 0xff) == 0xEF && (b[1] & 0xff) == 0xBB && (b[2] & 0xff) == 0xBF) return java.nio.charset.StandardCharsets.UTF_8;
        if (n >= 2 && (b[0] & 0xff) == 0xFE && (b[1] & 0xff) == 0xFF) return java.nio.charset.StandardCharsets.UTF_16BE;
        if (n >= 2 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xFE) return java.nio.charset.StandardCharsets.UTF_16LE;
        String h = new String(b, 0, Math.min(n, 300), java.nio.charset.StandardCharsets.ISO_8859_1);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*<\\?xml[^>]*encoding\\s*=\\s*[\"']([A-Za-z0-9._-]+)[\"']").matcher(h);
        String name = m.find() ? m.group(1) : http;
        try { if (name != null && !name.trim().isEmpty()) return java.nio.charset.Charset.forName(name.trim()); } catch (Exception ignored) { }
        return java.nio.charset.StandardCharsets.UTF_8;
    }

    static String cut(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }

    /** HTML uit een beschrijving halen: alinea's worden regels, tags weg, tekens terug. */
    static String text(String html) {
        String s = html.replaceAll("(?i)<br\\s*/?>|</p>|</li>|</h\\d>", "\n").replaceAll("(?s)<[^>]{0,2000}>", "");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("&#(x?)([0-9a-fA-F]{1,6});").matcher(s);
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            int cp;
            try { cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16); } catch (Exception e) { cp = '?'; }
            m.appendReplacement(b, java.util.regex.Matcher.quoteReplacement(Character.isValidCodePoint(cp) ? new String(Character.toChars(cp)) : "?"));
        }
        m.appendTail(b);
        return b.toString().replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll(" *\n *", "\n").replaceAll("\n{3,}", "\n\n").trim();
    }

    private static final String[] DATE_FORMATS = {"EEE, d MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss zzz", "d MMM yyyy HH:mm:ss Z", "d MMM yyyy HH:mm:ss zzz",
            "EEE, d MMM yyyy HH:mm Z", "EEE, d MMM yyyy HH:mm zzz", "EEE, d MMM yy HH:mm:ss Z", "EEE, d MMM yyyy", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd"};

    /** RFC 822-datum (en een paar varianten die feeds gebruiken). 0 = onbekend. */
    static long parseDate(String s) {
        if (s == null) return 0;
        s = s.trim().replaceAll("\\s+", " ");
        if (s.isEmpty()) return 0;
        for (String fmt : DATE_FORMATS) {
            SimpleDateFormat f = new SimpleDateFormat(fmt, Locale.US);
            if (fmt.endsWith("'Z'")) f.setTimeZone(TimeZone.getTimeZone("UTC"));
            f.setLenient(true);
            ParsePosition pp = new ParsePosition(0);
            Date d = f.parse(s, pp);
            if (d != null && pp.getIndex() > 0) return d.getTime();
        }
        return 0;
    }

    /** "1:02:03", "62:03", "3723" of "3723.5" → seconden. */
    static long parseDuration(String s) {
        if (s == null) return 0;
        s = s.trim();
        if (s.isEmpty()) return 0;
        try {
            if (s.contains(":")) {
                long t = 0;
                for (String p : s.split(":")) t = t * 60 + (long) Double.parseDouble(p.trim());
                return Math.max(0, t);
            }
            return Math.max(0, (long) Double.parseDouble(s));
        } catch (Exception e) { return 0; }
    }

    /** Invoer begrenzen (een kapotte of kwaadaardige feed mag het geheugen niet vullen). */
    static final class Limited extends FilterInputStream {
        private long left;
        Limited(InputStream in, long max) { super(in); left = max; }
        @Override public int read() throws IOException { if (left <= 0) throw new IOException("Feed is te groot"); int r = super.read(); if (r >= 0) left--; return r; }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) throw new IOException("Feed is te groot");
            int r = super.read(b, off, (int) Math.min(len, left));
            if (r > 0) left -= r;
            return r;
        }
    }
}
