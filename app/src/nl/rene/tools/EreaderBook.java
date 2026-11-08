package nl.rene.tools;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** EPUB wordt uitsluitend tekst: nooit boek-HTML, scripts of verwijzingen in de WebView laden. */
final class EreaderBook {
    static final int MAX_FILE = 20 * 1024 * 1024, MAX_ENTRY = 4 * 1024 * 1024;
    String title = "", author = "";
    byte[] cover;
    final List<String> chapters = new ArrayList<>();
    final List<String> names = new ArrayList<>();

    static byte[] read(InputStream in, int max) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) != -1) {
            if (out.size() > max - n) throw new Exception(Taal.T("Dit boek is te groot (maximaal 20 MB)"));
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    static Document xml(byte[] bytes) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        // Android kent deze optionele features niet allemaal; de controles en resolver hieronder zijn leidend.
        for (String feature : new String[]{"http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities", "http://apache.org/xml/features/nonvalidating/load-external-dtd"}) {
            try { f.setFeature(feature, false); } catch (Throwable ignored) { }
        }
        f.setExpandEntityReferences(false);
        // Geen DTD's, ook geen interne entiteiten of netwerkverzoeken. Een vaste XHTML-DOCTYPE mag weg.
        Charset encoding = StandardCharsets.UTF_8;
        if (bytes.length > 1) {
            int a = bytes[0] & 255, b = bytes[1] & 255;
            if (a == 255 && b == 254 || a == 60 && b == 0) encoding = StandardCharsets.UTF_16LE;
            if (a == 254 && b == 255 || a == 0 && b == 60) encoding = StandardCharsets.UTF_16BE;
        }
        String s = decode(bytes, encoding).replaceFirst("^\uFEFF", "");
        if (s.contains("<!ENTITY") || s.matches("(?s).*<!DOCTYPE[^>]*\\[.*")) throw new Exception(Taal.T("Dit EPUB-bestand kan niet veilig worden gelezen"));
        s = s.replaceAll("(?is)<!DOCTYPE[^>]*>", "").replace("&nbsp;", "&#160;");
        javax.xml.parsers.DocumentBuilder builder = f.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        return builder.parse(new InputSource(new StringReader(s)));
    }

    static String decode(byte[] bytes, Charset encoding) throws Exception {
        return encoding.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    static String first(Document d, String name) {
        NodeList n = d.getElementsByTagNameNS("*", name);
        return n.getLength() == 0 ? "" : n.item(0).getTextContent().trim();
    }

    static String path(String base, String href) throws Exception {
        URI u = new URI("/" + base).resolve(href).normalize();
        String p = u.getPath();
        if (u.isAbsolute() || u.getAuthority() != null || p == null || !p.startsWith("/") || p.contains("/../") || p.startsWith("/../") || p.contains("\\"))
            throw new Exception(Taal.T("Dit EPUB-bestand kan niet veilig worden gelezen"));
        return p.substring(1);
    }

    static EreaderBook parse(byte[] bytes, String filename) throws Exception {
        EreaderBook b = new EreaderBook();
        b.title = filename.replaceFirst("(?i)\\.(epub|txt)$", "");
        if (filename.toLowerCase(java.util.Locale.ROOT).endsWith(".txt")) {
            String text = decode(bytes, StandardCharsets.UTF_8).replaceFirst("^\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n').trim();
            if (text.indexOf('\0') >= 0 || text.isEmpty()) throw new Exception(Taal.T("Dit bestand bevat geen leesbare tekst"));
            // Korte stukken voorkomen een te grote overdracht naar de interface.
            for (int start = 0; start < text.length();) {
                int end = Math.min(start + 32000, text.length());
                if (end < text.length()) { int nl = text.lastIndexOf('\n', end); if (nl > start + 16000) end = nl + 1; }
                if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
                b.chapters.add(text.substring(start, end)); b.names.add(""); start = end;
            }
            return b;
        }
        if (!filename.toLowerCase(java.util.Locale.ROOT).endsWith(".epub")) throw new Exception(Taal.T("Kies een EPUB- of tekstbestand"));
        Map<String, byte[]> files = new HashMap<>(); int total = 0, count = 0;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (++count > 2000) throw new Exception(Taal.T("Dit EPUB-bestand bevat te veel onderdelen"));
                if (e.isDirectory()) continue;
                byte[] data = read(z, MAX_ENTRY); total += data.length;
                if (total > 40 * 1024 * 1024 || files.put(e.getName(), data) != null) throw new Exception(Taal.T("Dit EPUB-bestand is te groot of beschadigd"));
            }
        }
        if (files.containsKey("META-INF/encryption.xml")) throw new Exception(Taal.T("EPUB met versleutelde onderdelen wordt niet ondersteund"));
        byte[] container = files.get("META-INF/container.xml");
        if (container == null) throw new Exception(Taal.T("Dit is geen geldig EPUB-bestand"));
        NodeList roots = xml(container).getElementsByTagNameNS("*", "rootfile");
        if (roots.getLength() == 0) throw new Exception(Taal.T("Dit is geen geldig EPUB-bestand"));
        String opf = path("", ((Element) roots.item(0)).getAttribute("full-path"));
        if (!files.containsKey(opf)) throw new Exception(Taal.T("De inhoudsopgave van dit EPUB-bestand ontbreekt"));
        Document meta = xml(files.get(opf));
        String title = first(meta, "title"); if (!title.isEmpty()) b.title = title;
        b.author = first(meta, "creator");
        Map<String, String> items = new HashMap<>();
        NodeList manifest = meta.getElementsByTagNameNS("*", "item");
        String coverId = "", coverPath = "";
        NodeList tags = meta.getElementsByTagNameNS("*", "meta");
        for (int i = 0; i < tags.getLength(); i++) {
            Element e = (Element) tags.item(i);
            if ("cover".equals(e.getAttribute("name"))) coverId = e.getAttribute("content");
        }
        for (int i = 0; i < manifest.getLength(); i++) {
            Element e = (Element) manifest.item(i);
            if ("application/xhtml+xml".equals(e.getAttribute("media-type"))) items.put(e.getAttribute("id"), path(opf, e.getAttribute("href")));
            if (e.getAttribute("media-type").matches("image/(jpeg|png|webp)") &&
                    (e.getAttribute("id").equals(coverId) || (" " + e.getAttribute("properties") + " ").contains(" cover-image ")))
                coverPath = path(opf, e.getAttribute("href"));
        }
        b.cover = files.get(coverPath);
        NodeList spine = meta.getElementsByTagNameNS("*", "itemref");
        for (int i = 0; i < spine.getLength(); i++) {
            Element e = (Element) spine.item(i);
            String p = items.get(e.getAttribute("idref"));
            if (p == null || !files.containsKey(p)) throw new Exception(Taal.T("Een hoofdstuk van dit EPUB-bestand ontbreekt"));
            Document chapter = xml(files.get(p));
            NodeList bodies = chapter.getElementsByTagNameNS("*", "body");
            StringBuilder text = new StringBuilder();
            if (bodies.getLength() > 0) text(bodies.item(0), text, 0);
            String s = text.toString().replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").trim();
            if (s.isEmpty()) continue;
            String name = first(chapter, "h1"); if (name.isEmpty()) name = first(chapter, "h2");
            if (name.isEmpty()) name = first(chapter, "title");
            b.names.add(name.length() > 160 ? name.substring(0, 160) : name); b.chapters.add(s);
        }
        if (b.chapters.isEmpty()) throw new Exception(Taal.T("Dit bestand bevat geen leesbare tekst"));
        return b;
    }

    private static void text(Node n, StringBuilder out, int depth) throws Exception {
        if (depth > 150) throw new Exception(Taal.T("Dit EPUB-bestand is te ingewikkeld"));
        String tag = n.getLocalName();
        if ("script".equals(tag) || "style".equals(tag) || "head".equals(tag) || "svg".equals(tag) || "iframe".equals(tag) || "object".equals(tag)) return;
        if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) { out.append(n.getNodeValue().replaceAll("\\s+", " ")); return; }
        boolean block = tag != null && tag.matches("p|div|section|article|h[1-6]|li|blockquote|br|tr");
        if (block) out.append('\n');
        for (Node child = n.getFirstChild(); child != null; child = child.getNextSibling()) text(child, out, depth + 1);
        if (block) out.append('\n');
    }
}
