package nl.rene.tools;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;

import java.io.InputStream;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

/**
 * Leest een nieuwsfeed: RSS 2.0, RSS 1.0 (RDF) en Atom. Puur Java (SAX), zodat het op een gewone JVM te testen is.
 * Dezelfde bewaking als PodcastFeed (tekenset, geen eigen entiteiten of externe bestanden, begrensde invoer).
 * De tekst van een artikel komt eruit als losse alinea's en tussenkoppen, zonder HTML: de interface toont alleen tekst.
 */
final class NieuwsFeed {
    private NieuwsFeed() { }

    static final int MAX_TEKST = 20_000, MAX_BLOKKEN = 120, MAX_ITEMS = 200, MAX_HTML = 100_000;

    static final class Blok {
        final String tekst; final boolean kop;
        Blok(String tekst, boolean kop) { this.tekst = tekst; this.kop = kop; }
    }

    static final class Artikel {
        String sleutel = "", titel = "", link = "", auteur = "", beeld = "";
        long datum;
        List<Blok> blokken = new ArrayList<>();
        boolean ingekort;
        /** De artikelpagina is nagekeken en heeft geen afbeelding ({@link Nieuws#beelden}). */
        boolean zonderBeeld;
    }

    static final class Feed {
        String titel = "", link = "";
        /** Hoe het ophalen van afbeeldingen van de artikelpagina's ging ({@link Nieuws#beelden}), of "". */
        String beeldStand = "";
        final List<Artikel> artikelen = new ArrayList<>();
    }

    /** Geen nieuwsfeed (bijv. een gewone webpagina): dan kan de aanroeper op die pagina naar een feed zoeken. */
    static final class GeenFeed extends Exception { GeenFeed() { super(Taal.T("Dit is geen nieuwsfeed")); } }

    /** Eigen entiteiten of een DTD: nooit lezen, ook niet half. */
    static final class Onveilig extends SAXException { Onveilig() { super(Taal.T("Deze feed wordt niet gelezen (onveilige opbouw)")); } }

    /** Tekens die in XML niet mogen (stuurtekens behalve tab en regeleinden) weg, zodat één fout teken niet de hele feed kost. */
    static final class XmlTekens extends java.io.FilterReader {
        XmlTekens(java.io.Reader r) { super(r); }
        static boolean mag(int c) { return c >= 0x20 || c == '\t' || c == '\n' || c == '\r'; }
        @Override public int read() throws java.io.IOException { int c; do { c = super.read(); } while (c >= 0 && !mag(c)); return c; }
        @Override public int read(char[] b, int off, int len) throws java.io.IOException {
            while (true) {
                int n = super.read(b, off, len);
                if (n <= 0) return n;
                int w = off;
                for (int i = off; i < off + n; i++) if (mag(b[i])) b[w++] = b[i];
                if (w > off) return w - off;
            }
        }
    }

    /** Een adres naar een gewone site op internet: geen eigen apparaat, thuisnetwerk of lokale naam. */
    static boolean openbaar(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        if (h.isEmpty() || !h.contains(".") || h.startsWith("[") || h.contains(":")) return false;
        if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return false;
        Matcher m = IPV4.matcher(h);
        if (!m.matches()) {
            // "127.1", "0x7f.0.0.1": het toestel leest dat als IP-adres; een echte naam eindigt nooit op cijfers
            String laatste = h.substring(h.lastIndexOf('.') + 1);
            return !laatste.matches("[0-9]+|0x[0-9a-f]*");
        }
        for (int i = 1; i <= 4; i++) { String o = m.group(i); if ((o.length() > 1 && o.charAt(0) == '0') || Integer.parseInt(o) > 255) return false; }
        int a = Integer.parseInt(m.group(1)), b = Integer.parseInt(m.group(2));
        return !(a == 0 || a == 10 || a == 127 || a >= 224 || (a == 169 && b == 254) || (a == 172 && b >= 16 && b <= 31)
                || (a == 192 && b == 168) || (a == 100 && b >= 64 && b <= 127));
    }
    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");

    /** Een https-adres naar een openbare site (zonder inloggegevens in het adres). */
    static boolean httpsOpenbaar(String u) {
        if (u == null || u.length() > 2000 || !u.regionMatches(true, 0, "https://", 0, 8)) return false;
        try { java.net.URL x = new java.net.URL(u); return openbaar(x.getHost()) && x.getUserInfo() == null; }
        catch (Exception e) { return false; }
    }

    static Feed parse(InputStream raw, String httpCharset) throws Exception {
        java.io.BufferedInputStream in = new java.io.BufferedInputStream(raw, 1 << 16);
        in.mark(4096);
        byte[] head = new byte[4096];
        int n = 0, r;
        while (n < head.length && (r = in.read(head, n, head.length - n)) > 0) n += r;
        in.reset();
        java.nio.charset.Charset cs = PodcastFeed.charset(head, n, httpCharset);
        long bom = n >= 3 && (head[0] & 0xff) == 0xEF && (head[1] & 0xff) == 0xBB && (head[2] & 0xff) == 0xBF ? 3
                : n >= 2 && (((head[0] & 0xff) == 0xFE && (head[1] & 0xff) == 0xFF) || ((head[0] & 0xff) == 0xFF && (head[1] & 0xff) == 0xFE)) ? 2 : 0;
        while (bom > 0) { long k = in.skip(bom); if (k <= 0) break; bom -= k; }
        SAXParserFactory f = SAXParserFactory.newInstance();
        f.setNamespaceAware(false);
        for (String feat : new String[]{"http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities",
                "http://apache.org/xml/features/nonvalidating/load-external-dtd"}) {
            try { f.setFeature(feat, false); } catch (Exception ignored) { }
        }
        try { f.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true); } catch (Exception ignored) { }
        SAXParser p = f.newSAXParser();
        Handler h = new Handler();
        try { p.setProperty("http://xml.org/sax/properties/declaration-handler", h); } catch (Exception ignored) { }
        try { p.parse(new org.xml.sax.InputSource(new PodcastFeed.EntityGuard(new XmlTekens(new java.io.InputStreamReader(in, cs)))), h); }
        catch (PodcastFeed.Enough ignored) { }
        catch (Onveilig e) { throw e; }
        catch (SAXException e) {
            if (!h.feedRoot) throw new GeenFeed();
            if (h.feed.artikelen.isEmpty()) throw e;
        }
        if (!h.feedRoot) throw new GeenFeed();
        return h.feed;
    }

    static final class Handler extends org.xml.sax.ext.DefaultHandler2 {
        final Feed feed = new Feed();
        boolean feedRoot, started, atom;
        Artikel art;
        String samenvatting = "", inhoud = "", guid = "";
        boolean guidLink, inAuthor, inImage;
        /** Atom-inhoud van type xhtml: de elementen zelf zijn de opmaak. Diepte binnen dat element (0 = niet). */
        int xhtml;
        String xhtmlVeld = "";
        final StringBuilder text = new StringBuilder();

        @Override public void internalEntityDecl(String name, String value) throws SAXException { throw new Onveilig(); }
        @Override public void externalEntityDecl(String name, String pub, String sys) throws SAXException { throw new Onveilig(); }

        @Override public void startElement(String uri, String local, String q, Attributes a) throws SAXException {
            String n = q.toLowerCase(Locale.ROOT);
            if (!started) {
                started = true;
                feedRoot = n.equals("rss") || n.equals("rdf:rdf") || n.equals("feed");
                atom = n.equals("feed");
                if (!feedRoot) throw new SAXException("geen feed");
            }
            if (xhtml > 0) {
                xhtml++;
                text.append('<').append(n);
                if (n.equals("img")) text.append(" src=\"").append(attr(a, "src").replace("\"", "")).append('"');
                text.append('>');
                return;
            }
            text.setLength(0);
            if (n.equals("item") || n.equals("entry")) {
                art = new Artikel(); samenvatting = ""; inhoud = ""; guid = ""; guidLink = false; inAuthor = false; beeldBreedte = 0;
                return;
            }
            if (art == null) {
                if (n.equals("image")) inImage = true;
                else if (atom && n.equals("link") && feed.link.isEmpty() && alternate(a)) feed.link = cut(attr(a, "href").trim(), 500);
                return;
            }
            switch (n) {
                case "link":
                    if (atom && alternate(a) && art.link.isEmpty()) art.link = cut(attr(a, "href").trim(), 1000);
                    break;
                case "guid": guidLink = !"false".equalsIgnoreCase(attr(a, "isPermaLink").trim()); break;
                case "author": inAuthor = true; break;
                case "content": case "summary":
                    if ("xhtml".equalsIgnoreCase(attr(a, "type").trim())) { xhtml = 1; xhtmlVeld = n; }
                    else if ("text".equalsIgnoreCase(attr(a, "type").trim())) xhtmlVeld = n + ":text";
                    else xhtmlVeld = "";
                    break;
                case "media:thumbnail": beeld(attr(a, "url"), attr(a, "width")); break;
                case "media:content": {
                    String medium = attr(a, "medium").trim(), type = attr(a, "type").trim().toLowerCase(Locale.ROOT);
                    boolean plaatje = medium.equalsIgnoreCase("image") || type.startsWith("image/")
                            || (medium.isEmpty() && type.isEmpty() && !GEEN_BEELD.matcher(attr(a, "url")).find());
                    if (plaatje) beeld(attr(a, "url"), attr(a, "width"));
                    break;
                }
                case "enclosure":
                    if (attr(a, "type").toLowerCase(Locale.ROOT).startsWith("image/")) beeld(attr(a, "url"), "");
                    break;
                default: break;
            }
        }

        /** Het eerste plaatje, of een breder als de feed er meer maten van geeft. */
        private int beeldBreedte;
        private void beeld(String u, String breed) {
            u = u.trim();
            if (u.length() >= 1000 || !httpsOpenbaar(u)) return;
            int w = 0;
            try { w = Integer.parseInt(breed.trim()); } catch (NumberFormatException ignored) { }
            if (art.beeld.isEmpty() || (w > beeldBreedte && beeldBreedte > 0)) { art.beeld = u; beeldBreedte = w; }
        }

        private static boolean alternate(Attributes a) { String rel = attr(a, "rel").trim(); return rel.isEmpty() || rel.equalsIgnoreCase("alternate"); }

        @Override public void endElement(String uri, String local, String q) throws SAXException {
            String n = q.toLowerCase(Locale.ROOT);
            if (xhtml > 1) { xhtml--; text.append("</").append(n).append('>'); return; }
            String t = text.toString();
            if (xhtml == 1) {
                xhtml = 0;
                if (n.equals("content")) inhoud = t; else samenvatting = t;
                text.setLength(0);
                return;
            }
            if (art != null) {
                switch (n) {
                    case "item": case "entry": klaar(); return;
                    case "title": if (art.titel.isEmpty()) art.titel = cut(regel(t), 400); break;
                    case "link": if (!atom && art.link.isEmpty()) art.link = cut(t.trim(), 1000); break;
                    case "guid": case "id": if (guid.isEmpty()) guid = cut(t.trim(), 1000); break;
                    case "pubdate": case "dc:date": case "published": case "a10:updated": art.datum = datum(t); break;
                    case "updated": if (art.datum == 0) art.datum = datum(t); break;
                    case "dc:creator": if (art.auteur.isEmpty()) art.auteur = cut(regel(t), 200); break;
                    case "name": if (inAuthor && art.auteur.isEmpty()) art.auteur = cut(regel(t), 200); break;
                    case "author":
                        inAuthor = false;
                        if (!atom && art.auteur.isEmpty()) art.auteur = cut(rssAuteur(regel(t)), 200);
                        break;
                    case "description": samenvatting = t; break;
                    case "summary": samenvatting = xhtmlVeld.endsWith(":text") ? escape(t) : t; break;
                    case "content:encoded": inhoud = t; break;
                    case "content": inhoud = xhtmlVeld.endsWith(":text") ? escape(t) : t; break;
                    default: break;
                }
            } else if (inImage) {
                if (n.equals("image")) inImage = false;
            } else if (n.equals("title") && feed.titel.isEmpty()) feed.titel = cut(regel(t), 200);
            else if (n.equals("link") && !atom && feed.link.isEmpty()) feed.link = cut(t.trim(), 500);
            text.setLength(0);
        }

        private void klaar() throws SAXException {
            Artikel x = art; art = null;
            if (x.link.isEmpty() && guidLink && guid.startsWith("http")) x.link = guid;
            String html = inhoud.trim().length() > samenvatting.trim().length() ? inhoud : samenvatting;
            if (x.beeld.isEmpty()) { Matcher m = IMG.matcher(cut(html, MAX_HTML)); if (m.find()) { String u = ontsnap(m.group(1)).trim(); if (u.length() < 1000 && httpsOpenbaar(u)) x.beeld = u; } }
            boolean[] kort = new boolean[1];
            x.blokken = blokken(html, kort);
            x.ingekort = kort[0];
            x.sleutel = !guid.isEmpty() ? guid : !x.link.isEmpty() ? x.link : x.titel + "|" + x.datum;
            if (x.titel.isEmpty() && x.blokken.isEmpty()) return;
            if (x.titel.isEmpty()) x.titel = cut(x.blokken.get(0).tekst, 120);
            feed.artikelen.add(x);
            if (feed.artikelen.size() >= MAX_ITEMS) throw new PodcastFeed.Enough();
        }

        @Override public void characters(char[] ch, int start, int len) {
            if (xhtml > 0) {
                for (int i = start; i < start + len && text.length() < 200_000; i++) {
                    char c = ch[i];
                    if (c == '<') text.append("&lt;"); else if (c == '&') text.append("&amp;"); else text.append(c);
                }
                return;
            }
            if (text.length() < 200_000) text.append(ch, start, Math.min(len, 200_000 - text.length()));
        }

        static String attr(Attributes a, String name) { String v = a.getValue(name); return v == null ? "" : v; }
    }

    static String cut(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }

    /** RSS-auteur is vaak een e-mailadres ("x@y.nl (Naam)"): alleen de naam, nooit het adres. */
    static String rssAuteur(String s) {
        Matcher m = Pattern.compile("\\(([^()@]{1,100})\\)").matcher(s);
        if (m.find()) return m.group(1).trim();
        return s.contains("@") ? "" : s;
    }

    static String escape(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    private static final Pattern IMG = Pattern.compile("(?i)<img\\b[^<>]{0,2000}?\\bsrc\\s*=\\s*[\"']([^\"'<>\\s]{1,1000})[\"']");
    /** media:content zonder soort: geen plaatje als het adres op video of geluid eindigt. */
    static final Pattern GEEN_BEELD = Pattern.compile("(?i)\\.(mp4|m4v|mov|webm|m3u8|mp3|m4a|aac|ogg|opus|wav)(\\?|#|$)");
    /* Elk patroon stopt bij de volgende < of aan het eind van de tekst: een feed vol halve tags kost zo geen kwadratische tijd
     * (Android kan een zoekopdracht niet afbreken). Een niet afgesloten script of opmerking loopt door tot het eind. */
    private static final Pattern WEG = Pattern.compile("(?is)<(script|style)\\b[^<>]{0,2000}(?<!/)>.*?(?:</\\1\\s*>|\\z)");
    /** Blokken die alleen met hun sluittag weg gaan (zelfsluitend of nooit gesloten: alleen de tag zelf). */
    private static final Pattern WEG_BLOK = Pattern.compile("(?i)<(noscript|iframe|object|svg|template|button|form|figure)\\b[^<>]{0,2000}(?<!/)>");
    private static final Pattern KOP_BEGIN = Pattern.compile("(?i)<h[1-6]\\b[^<>]{0,2000}>"), KOP_EIND = Pattern.compile("(?i)</h[1-6]\\s*>");
    private static final Pattern LI = Pattern.compile("(?i)<li\\b[^<>]{0,2000}>");
    private static final Pattern BLOK = Pattern.compile("(?i)</?(p|div|ul|ol|li|blockquote|section|article|table|tr|figcaption|dl|dd|dt|hr|pre)\\b[^<>]{0,2000}>");
    private static final Pattern OPMERKING = Pattern.compile("(?s)<!--.*?(?:-->|\\z)");
    private static final Pattern BR = Pattern.compile("(?i)<br\\s*/?>");
    /** Alleen wat echt een tag is (een letter, / ! of ? na de <): "3 < 5 en 6 > 4" blijft staan. */
    private static final Pattern TAG = Pattern.compile("(?s)<[/!?]?[A-Za-z][^<>]{0,2000}>|<!--.*?(?:-->|\\z)");
    private static final Pattern INLINE = Pattern.compile("(?i)</?(b|i|em|strong|span|a|small|sup|sub|u|mark|abbr|q|cite|code|font)\\b[^<>]{0,2000}>");
    private static final char KOP = '\u0001';

    /**
     * Figuren, formulieren, iframes en dergelijke met hun inhoud weg, tot de eerstvolgende sluittag. Zonder sluittag erna
     * blijft de tekst staan. Per soort één keer opzoeken waar de laatste sluittag staat, zodat het lineair blijft.
     */
    static String wegBlok(String s) {
        char[] k = s.toCharArray();
        for (int i = 0; i < k.length; i++) if (k[i] >= 'A' && k[i] <= 'Z') k[i] += 32; // even lang als s (toLowerCase kan tekens verdubbelen)
        String l = new String(k);
        Map<String, Integer> laatste = new HashMap<>();
        StringBuilder b = new StringBuilder(s.length());
        Matcher m = WEG_BLOK.matcher(s);
        int pos = 0;
        while (pos < s.length() && m.find(pos)) {
            String naam = m.group(1).toLowerCase(Locale.ROOT), sluit = "</" + naam;
            Integer last = laatste.get(naam);
            if (last == null) { last = l.lastIndexOf(sluit); laatste.put(naam, last); }
            int eind = last >= m.end() ? l.indexOf(sluit, m.end()) : -1, gt = eind < 0 ? -1 : l.indexOf('>', eind);
            if (gt < 0) {
                if (eind >= 0) laatste.put(naam, -1); // een sluittag zonder '>': ook latere van deze soort sluiten niet
                b.append(s, pos, m.end()); pos = m.end(); continue;
            }
            b.append(s, pos, m.start()).append(' ');
            pos = gt + 1;
        }
        if (pos < s.length()) b.append(s, pos, s.length());
        return b.toString();
    }

    /** Eén regel tekst (titel, naam): tags weg, tekens terug, witruimte samen. */
    static String regel(String s) {
        if (s == null) return "";
        s = cut(s, 4000);
        String r = s.indexOf('<') >= 0 ? TAG.matcher(INLINE.matcher(s).replaceAll("")).replaceAll(" ") : s;
        return ontsnap(r).replaceAll("[\\s\\u00a0]+", " ").trim();
    }

    /**
     * HTML (of platte tekst) → alinea's en tussenkoppen. Scripts, stijlen, formulieren en afbeeldingen vallen weg; lijstjes
     * krijgen een •. Hooguit MAX_TEKST tekens en MAX_BLOKKEN blokken (ingekort[0] wordt dan true).
     */
    static List<Blok> blokken(String html, boolean[] ingekort) {
        List<Blok> out = new ArrayList<>();
        if (html == null) return out;
        String s = html;
        if (s.length() > MAX_HTML) { s = s.substring(0, MAX_HTML); if (ingekort != null) ingekort[0] = true; }
        s = s.replaceAll("[\\u0000-\\u0008\\u000b\\u000c\\u000e-\\u001f]", "");
        if (s.indexOf('<') < 0 && s.indexOf('&') < 0) s = s.replaceAll("\\r?\\n\\s*\\r?\\n", "\n\n");
        else {
            s = OPMERKING.matcher(s.replaceAll("\\s+", " ")).replaceAll(" ");
            s = wegBlok(WEG.matcher(s).replaceAll(" "));
            s = KOP_BEGIN.matcher(s).replaceAll("\n\n" + KOP);
            s = KOP_EIND.matcher(s).replaceAll("\n\n");
            s = LI.matcher(s).replaceAll("\n\n• ");
            s = BLOK.matcher(s).replaceAll("\n\n");
            s = BR.matcher(s).replaceAll("\n");
            s = TAG.matcher(s).replaceAll("");
            s = ontsnap(s);
        }
        int total = 0;
        for (String deel : s.split("\\n\\s*\\n")) {
            boolean kop = deel.indexOf(KOP) >= 0;
            String t = deel.replace(String.valueOf(KOP), "").replaceAll("[ \\t\\u00a0\\u000b\\f\\r]+", " ").replaceAll(" *\\n *", "\n").replaceAll("\\n{2,}", "\n").trim();
            if (t.isEmpty() || t.equals("•")) continue;
            if (kop && t.length() > 200) kop = false;
            if (out.size() >= MAX_BLOKKEN || total + t.length() > MAX_TEKST) {
                if (ingekort != null) ingekort[0] = true;
                int rest = MAX_TEKST - total;
                if (out.size() < MAX_BLOKKEN && rest > 200) out.add(new Blok(t.substring(0, rest).replaceAll("\\s+\\S*$", "") + " …", false));
                break;
            }
            out.add(new Blok(t, kop));
            total += t.length();
        }
        return out;
    }

    private static final Map<String, String> NAMEN = new HashMap<>();
    static {
        String[] p = {"nbsp", "\u00a0", "amp", "&", "lt", "<", "gt", ">", "quot", "\"", "apos", "'", "hellip", "…", "mdash", "—", "ndash", "–",
                "lsquo", "‘", "rsquo", "’", "sbquo", "‚", "ldquo", "“", "rdquo", "”", "bdquo", "„", "laquo", "«", "raquo", "»", "euro", "€",
                "copy", "©", "reg", "®", "trade", "™", "deg", "°", "middot", "·", "bull", "•", "times", "×", "shy", "", "zwnj", "", "zwj", "",
                "eacute", "é", "egrave", "è", "ecirc", "ê", "euml", "ë", "aacute", "á", "agrave", "à", "acirc", "â", "auml", "ä", "aring", "å",
                "iacute", "í", "igrave", "ì", "icirc", "î", "iuml", "ï", "oacute", "ó", "ograve", "ò", "ocirc", "ô", "ouml", "ö", "oslash", "ø",
                "uacute", "ú", "ugrave", "ù", "ucirc", "û", "uuml", "ü", "ccedil", "ç", "ntilde", "ñ", "szlig", "ß", "Eacute", "É", "Euml", "Ë",
                "Auml", "Ä", "Ouml", "Ö", "Uuml", "Ü", "Iuml", "Ï", "Ccedil", "Ç", "pound", "£", "sect", "§", "para", "¶", "frac12", "½", "frac14", "¼"};
        for (int i = 0; i + 1 < p.length; i += 2) NAMEN.put(p[i], p[i + 1]);
    }
    private static final Pattern ENT = Pattern.compile("&(#x[0-9a-fA-F]{1,6}|#[0-9]{1,7}|[A-Za-z][A-Za-z0-9]{1,10});");

    /** HTML-tekens terug (&amp;, &#233;, &eacute; …); onbekende namen blijven staan. */
    static String ontsnap(String s) {
        if (s.indexOf('&') < 0) return s;
        Matcher m = ENT.matcher(s);
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            String e = m.group(1), v;
            if (e.charAt(0) == '#') {
                int cp;
                try { cp = e.charAt(1) == 'x' || e.charAt(1) == 'X' ? Integer.parseInt(e.substring(2), 16) : Integer.parseInt(e.substring(1)); } catch (Exception x) { cp = -1; }
                v = cp > 0 && Character.isValidCodePoint(cp) && (cp >= 0x20 || cp == '\n' || cp == '\t') ? new String(Character.toChars(cp)) : "";
            } else v = NAMEN.containsKey(e) ? NAMEN.get(e) : m.group();
            m.appendReplacement(b, Matcher.quoteReplacement(v));
        }
        m.appendTail(b);
        return b.toString();
    }

    private static final Pattern ISO = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})[T ](\\d{2}:\\d{2})(:\\d{2})?(?:[.,]\\d+)?\\s*(Z|[+-]\\d{2}:?\\d{2})?");

    /** Datum uit een feed: ISO 8601 (Atom, dc:date; ook met fracties van seconden) of RFC 822 (RSS). 0 = onbekend. */
    static long datum(String s) {
        if (s == null) return 0;
        s = s.trim();
        Matcher m = ISO.matcher(s);
        if (m.lookingAt()) {
            String zone = m.group(4) == null || m.group(4).equals("Z") ? "+00:00" : m.group(4).length() == 5 ? m.group(4).substring(0, 3) + ":" + m.group(4).substring(3) : m.group(4);
            String v = m.group(1) + "T" + m.group(2) + (m.group(3) == null ? ":00" : m.group(3)) + zone;
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
            Date d = f.parse(v, new ParsePosition(0));
            if (d != null) return d.getTime();
        }
        return PodcastFeed.parseDate(s);
    }
}
