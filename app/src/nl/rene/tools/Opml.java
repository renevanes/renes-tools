package nl.rene.tools;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

/**
 * OPML: de gangbare lijst met abonnementen (podcasts) of bronnen (nieuws), om over te zetten naar of van een andere app.
 * Zuiver (getest in OpmlTest). Lezen: alleen xmlUrl en de titel van elk outline-element; geen externe bestanden of
 * eigen entiteiten (zelfde bewaking als de feeds), hooguit MAX regels en MAX_BYTES groot.
 */
final class Opml {

    private Opml() { }

    static final int MAX = 500, MAX_BYTES = 2_000_000;

    /** Eén regel: titel en feedadres. */
    static final class Regel {
        final String titel, url;
        Regel(String titel, String url) { this.titel = titel; this.url = url; }
    }

    static final class Genoeg extends RuntimeException {
        Genoeg() { super(null, null, false, false); }
    }

    static final class Lezer extends DefaultHandler {
        final List<Regel> regels = new ArrayList<>();
        final Set<String> gezien = new LinkedHashSet<>();
        boolean opml;
        @Override public void startElement(String uri, String local, String q, Attributes at) {
            String naam = q == null || q.isEmpty() ? local : q;
            if ("opml".equalsIgnoreCase(naam)) opml = true;
            if (!"outline".equalsIgnoreCase(naam) || at == null) return;
            String url = waarde(at, "xmlUrl");
            if (url == null) return;
            url = url.trim();
            if (!adresGoed(url) || !gezien.add(url)) return;
            String titel = waarde(at, "title");
            if (titel == null || titel.trim().isEmpty()) titel = waarde(at, "text");
            regels.add(new Regel(netjes(titel), url));
            if (regels.size() >= MAX) throw new Genoeg();
        }
        private static String waarde(Attributes at, String naam) {
            for (int i = 0; i < at.getLength(); i++) {
                String n = at.getQName(i);
                if (n == null || n.isEmpty()) n = at.getLocalName(i);
                if (naam.equalsIgnoreCase(n)) return at.getValue(i);
            }
            return null;
        }
    }

    /** Alleen gewone webadressen, zonder spaties of stuurtekens, hooguit 2000 tekens. */
    static boolean adresGoed(String u) {
        if (u == null || u.length() > 2000 || !(u.startsWith("https://") || u.startsWith("http://"))) return false;
        for (int i = 0; i < u.length(); i++) { char c = u.charAt(i); if (c <= ' ' || c == 0x7f || c == '"' || c == '<' || c == '>') return false; }
        return u.indexOf('.', u.indexOf("://") + 3) > 0 || u.contains("://localhost");
    }

    /** Titel als gewone korte tekst (geen stuur- of richtingtekens). */
    static String netjes(String s) {
        String n = s == null ? "" : s.replaceAll("[\\p{Cntrl}\\p{Cf}]", " ").replaceAll("\\s+", " ").trim();
        return n.length() > 300 ? n.substring(0, 300) : n;
    }

    static List<Regel> lees(byte[] xml) throws Exception {
        if (xml == null || xml.length == 0) throw new Exception("Het bestand is leeg");
        if (xml.length > MAX_BYTES) throw new Exception("Het bestand is te groot");
        int van = xml.length >= 3 && (xml[0] & 0xff) == 0xEF && (xml[1] & 0xff) == 0xBB && (xml[2] & 0xff) == 0xBF ? 3 : 0;
        SAXParserFactory f = SAXParserFactory.newInstance();
        f.setNamespaceAware(false);
        for (String feat : new String[]{"http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities",
                "http://apache.org/xml/features/nonvalidating/load-external-dtd"}) {
            try { f.setFeature(feat, false); } catch (Exception ignored) { }
        }
        try { f.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true); } catch (Exception ignored) { }
        SAXParser p = f.newSAXParser();
        Lezer h = new Lezer();
        try {
            p.parse(new org.xml.sax.InputSource(new PodcastFeed.EntityGuard(new InputStreamReader(new ByteArrayInputStream(xml, van, xml.length - van), StandardCharsets.UTF_8))), h);
        } catch (Genoeg vol) { /* de eerste MAX regels */ }
        catch (org.xml.sax.SAXException | java.io.IOException e) {
            if (h.regels.isEmpty()) throw new Exception("Dit is geen OPML-bestand");
        }
        if (!h.opml && h.regels.isEmpty()) throw new Exception("Dit is geen OPML-bestand");
        if (h.regels.isEmpty()) throw new Exception("In dit bestand staan geen feeds");
        return h.regels;
    }

    static String schrijf(String titel, List<Regel> regels) {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<opml version=\"2.0\">\n  <head><title>")
                .append(esc(netjes(titel))).append("</title></head>\n  <body>\n");
        if (regels != null) for (Regel r : regels) {
            if (r == null || !adresGoed(r.url)) continue;
            String t = esc(netjes(r.titel));
            b.append("    <outline type=\"rss\" text=\"").append(t).append("\" title=\"").append(t).append("\" xmlUrl=\"").append(esc(r.url)).append("\"/>\n");
        }
        return b.append("  </body>\n</opml>\n").toString();
    }

    static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                case '\'': b.append("&apos;"); break;
                default: if (c >= ' ' || c == '\t') b.append(c);
            }
        }
        return b.toString();
    }
}
