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

/** EPUB in een gesloten inhoudsmodel; boekcode wordt nergens uitgevoerd. */
final class EreaderBook {
    static final int MAX_FILE = 20 * 1024 * 1024, MAX_ENTRY = 4 * 1024 * 1024;
    String title = "", author = "", format = "EPUB";
    byte[] cover;
    final List<String> chapters = new ArrayList<>();
    final List<String> names = new ArrayList<>();
    final List<org.json.JSONObject> content = new ArrayList<>();
    final List<String> paths = new ArrayList<>();
    final List<Boolean> linear = new ArrayList<>();
    final List<Integer> legacyMap = new ArrayList<>();
    final List<Asset> assets = new ArrayList<>();
    final Map<String,Integer> assetIds = new HashMap<>();
    private Map<String,byte[]> sourceFiles;
    private final Map<String,String> resourceTypes = new HashMap<>();
    final org.json.JSONArray toc = new org.json.JSONArray();
    String language = "";
    static final class Asset {
        final String base, mime; final byte[] bytes;
        Asset(String base, String mime, byte[] bytes) { this.base = base; this.mime = mime; this.bytes = bytes; }
    }


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
        s = EreaderEntities.expand(s.replaceAll("(?is)<!DOCTYPE[^>]*>", ""));
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
        // Het basispad is al een gedecodeerde ZIP-naam; behandel spaties, # en % als padtekens.
        URI u = new URI(null, null, "/" + base, null).resolve(href).normalize();
        String p = u.getPath();
        if (u.isAbsolute() || u.getAuthority() != null || p == null || !p.startsWith("/") || p.contains("/../") || p.startsWith("/../") || p.contains("\\"))
            throw new Exception(Taal.T("Dit EPUB-bestand kan niet veilig worden gelezen"));
        return p.substring(1);
    }

    static EreaderBook parse(byte[] bytes, String filename) throws Exception {
        EreaderBook b = new EreaderBook();
        b.title = filename.replaceFirst("(?i)\\.(epub|txt)$", "");
        if (filename.toLowerCase(java.util.Locale.ROOT).endsWith(".txt") && !(bytes.length > 3 && bytes[0] == 'P' && bytes[1] == 'K')) {
            b.format = "TXT";
            String text = decode(bytes, StandardCharsets.UTF_8).replaceFirst("^\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n').trim();
            if (text.indexOf('\0') >= 0 || text.isEmpty()) throw new Exception(Taal.T("Dit bestand bevat geen leesbare tekst"));
            // Korte stukken voorkomen een te grote overdracht naar de interface.
            for (int start = 0; start < text.length();) {
                int end = Math.min(start + 32000, text.length());
                if (end < text.length()) { int nl = text.lastIndexOf('\n', end); if (nl > start + 16000) end = nl + 1; }
                if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
                b.chapters.add(text.substring(start, end)); b.names.add(""); b.linear.add(true); b.paths.add("text-" + b.paths.size()); b.legacyMap.add(b.legacyMap.size()); start = end;
            }
            return b;
        }
        if (!filename.toLowerCase(java.util.Locale.ROOT).endsWith(".epub") && !(bytes.length > 3 && bytes[0] == 'P' && bytes[1] == 'K')) throw new Exception(Taal.T("Kies een EPUB- of tekstbestand"));
        Map<String, byte[]> files = new HashMap<>(); int total = 0, count = 0;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (++count > 2000) throw new Exception(Taal.T("Dit EPUB-bestand bevat te veel onderdelen"));
                if (e.isDirectory()) continue;
                String entryName = e.getName();
                if (entryName.startsWith("/") || entryName.contains("\\") || java.util.Arrays.asList(entryName.split("/")).contains("..")) throw new Exception(Taal.T("Dit EPUB-bestand kan niet veilig worden gelezen"));
                byte[] data = read(z, MAX_ENTRY); total += data.length;
                if (total > 40 * 1024 * 1024 || files.put(e.getName(), data) != null) throw new Exception(Taal.T("Dit EPUB-bestand is te groot of beschadigd"));
            }
        }
        byte[] container = files.get("META-INF/container.xml");
        if (container == null) throw new Exception(Taal.T("Dit is geen geldig EPUB-bestand"));
        NodeList roots = xml(container).getElementsByTagNameNS("*", "rootfile");
        if (roots.getLength() == 0) throw new Exception(Taal.T("Dit is geen geldig EPUB-bestand"));
        Element rootfile = (Element)roots.item(0);
        for (int i = 0; i < roots.getLength(); i++) if ("application/oebps-package+xml".equals(((Element)roots.item(i)).getAttribute("media-type"))) { rootfile = (Element)roots.item(i); break; }
        String opf = path("", rootfile.getAttribute("full-path"));
        if (!files.containsKey(opf)) throw new Exception(Taal.T("De inhoudsopgave van dit EPUB-bestand ontbreekt"));
        Document meta = xml(files.get(opf));
        String title = first(meta, "title"); if (!title.isEmpty()) b.title = title;
        b.author = first(meta, "creator"); b.language = first(meta, "language");
        Map<String, String> items = new HashMap<>(), svgItems = new HashMap<>();
        NodeList manifest = meta.getElementsByTagNameNS("*", "item");
        Map<String,String> types = new HashMap<>(); String navPath = "", ncxPath = "";
        String coverId = "", coverPath = "";
        NodeList tags = meta.getElementsByTagNameNS("*", "meta");
        for (int i = 0; i < tags.getLength(); i++) {
            Element e = (Element) tags.item(i);
            if ("cover".equals(e.getAttribute("name"))) coverId = e.getAttribute("content");
        }
        for (int i = 0; i < manifest.getLength(); i++) {
            Element e = (Element) manifest.item(i);
            String mediaType = e.getAttribute("media-type");
            String itemPath = path(opf, e.getAttribute("href"));
            types.put(itemPath, mediaType);
            if (mediaType.equals("image/svg+xml")) svgItems.put(e.getAttribute("id"), itemPath);
            if (mediaType.equals("application/xhtml+xml") || mediaType.equals("text/html")) items.put(e.getAttribute("id"), itemPath);
            if ((" " + e.getAttribute("properties") + " ").contains(" nav ")) navPath = itemPath;
            if (mediaType.equals("application/x-dtbncx+xml")) ncxPath = itemPath;
            if (e.getAttribute("media-type").matches("image/(jpeg|png|webp)") &&
                    (e.getAttribute("id").equals(coverId) || (" " + e.getAttribute("properties") + " ").contains(" cover-image ")))
                coverPath = path(opf, e.getAttribute("href"));
        }
        b.cover = files.get(coverPath); b.resourceTypes.putAll(types); b.sourceFiles = files;
        checkEncryption(files, types);
        EreaderMarkup.Budget budget = new EreaderMarkup.Budget();
        java.util.Set<String> included = new java.util.HashSet<>();
        NodeList spine = meta.getElementsByTagNameNS("*", "itemref");
        for (int i = 0; i < spine.getLength(); i++) {
            Element e = (Element) spine.item(i);
            String p = items.get(e.getAttribute("idref"));
            if (p == null) p = svgItems.get(e.getAttribute("idref"));
            if (p == null || !files.containsKey(p)) throw new Exception(Taal.T("Een hoofdstuk van dit EPUB-bestand ontbreekt"));
            int index = b.document(files, p, !"no".equals(e.getAttribute("linear")), budget);
            included.add(p);
            // De vorige import sloeg lege teksthoofdstukken (ook beeldpagina's) over.
            if (!plain(xml(files.get(p))).isEmpty()) b.legacyMap.add(index);
        }
        java.util.List<String> extra = new ArrayList<>(items.values()); java.util.Collections.sort(extra);
        for (String p : extra) if (!included.contains(p) && files.containsKey(p)) { b.document(files, p, false, budget); included.add(p); }
        b.resolveAllLinks();
        b.navigation(files, navPath, ncxPath); b.sourceFiles = null;
        if (b.chapters.isEmpty()) throw new Exception(Taal.T("Dit bestand bevat geen leesbare tekst"));
        return b;
    }

    static String plain(Document chapter) throws Exception {
        org.w3c.dom.NodeList bodies = chapter.getElementsByTagNameNS("*", "body"); StringBuilder out = new StringBuilder();
        if (bodies.getLength() > 0) text(bodies.item(0), out, 0);
        return out.toString().replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").trim();
    }

    private int document(Map<String,byte[]> files, String p, boolean inReadingOrder, EreaderMarkup.Budget budget) throws Exception {
        Document chapter = xml(files.get(p)); NodeList bodies = chapter.getElementsByTagNameNS("*", "body");
        Element body;
        if ("image/svg+xml".equals(resourceTypes.get(p))) {
            Document wrapper = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            body = wrapper.createElement("body"); wrapper.appendChild(body); body.appendChild(wrapper.importNode(chapter.getDocumentElement(), true));
        } else { if (bodies.getLength() == 0) return -1; body = (Element)bodies.item(0); }
        EreaderCss css = new EreaderCss();
        NodeList sheets = chapter.getElementsByTagNameNS("*", "link");
        for (int i = 0; i < sheets.getLength(); i++) {
            Element sheet = (Element)sheets.item(i);
            if (!(" " + sheet.getAttribute("rel").toLowerCase(java.util.Locale.ROOT) + " ").contains(" stylesheet ")) continue;
            try { byte[] data = files.get(path(p, sheet.getAttribute("href"))); if (data != null) css.add(decode(data, StandardCharsets.UTF_8)); } catch (Exception ignored) { }
        }
        NodeList inline = chapter.getElementsByTagNameNS("*", "style");
        for (int i = 0; i < inline.getLength(); i++) css.add(inline.item(i).getTextContent());
        EreaderMarkup rich = new EreaderMarkup(body, p, css, (base, src, svg) -> image(files, base, src, svg), budget);
        if (rich.text.isEmpty() && rich.images == 0) return -1;
        String name = first(chapter, "h1"); if (name.isEmpty()) name = first(chapter, "h2"); if (name.isEmpty()) name = first(chapter, "title");
        org.json.JSONObject anchors = new org.json.JSONObject();
        for (Map.Entry<String,Integer> anchor : rich.anchors.entrySet()) anchors.put(anchor.getKey(), anchor.getValue());
        int index = chapters.size();
        names.add(EreaderMarkup.cut(name, 160)); chapters.add(rich.text); paths.add(p); linear.add(inReadingOrder);
        content.add(new org.json.JSONObject().put("text", rich.text).put("content", rich.root).put("anchors", anchors));
        return index;
    }
    private int image(Map<String,byte[]> files, String base, String src, Element svg) throws Exception {
        String key, mime, resourceBase = base; byte[] data;
        if (svg != null) {
            java.io.StringWriter out = new java.io.StringWriter();
            javax.xml.transform.Transformer transformer = javax.xml.transform.TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.transform(new javax.xml.transform.dom.DOMSource(svg), new javax.xml.transform.stream.StreamResult(out));
            data = out.toString().getBytes(StandardCharsets.UTF_8); key = base + "#inline-" + assets.size(); mime = "image/svg+xml";
        } else {
            try {
                if (src.startsWith("data:")) {
                    int comma = src.indexOf(','); String prefix = comma < 0 ? "" : src.substring(0, comma);
                    if (!prefix.matches("data:image/(?:png|jpeg|gif|webp|svg\\+xml);base64") || src.length() > 6 * 1024 * 1024) return -1;
                    data = EreaderBytes.decode(src.substring(comma + 1)); mime = prefix.substring(5, prefix.length() - 7); key = base + "#data-" + src.hashCode();
                } else {
                    key = path(base, src); resourceBase = key; data = files.get(key);
                    if (data == null) return -1;
                    mime = EreaderBytes.mime(data, key);
                    if (mime.isEmpty() && "image/svg+xml".equals(resourceTypes.get(key))) mime = "image/svg+xml";
                }
            } catch (Exception ignored) { return -1; }
        }
        if (data.length > MAX_ENTRY || mime.isEmpty()) return -1;
        if (assetIds.containsKey(key)) return assetIds.get(key);
        int id = assets.size(); if (id >= 2000) return -1;
        assetIds.put(key, id); assets.add(new Asset(resourceBase, mime, data)); return id;
    }
    org.json.JSONObject target(String base, String href) throws Exception {
        org.json.JSONObject target = new org.json.JSONObject();
        try {
            URI uri = new URI(href);
            if (uri.isAbsolute()) {
                if (("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) && uri.getHost() != null) return target.put("url", href);
                return target.put("missing", true);
            }
            String file = path(base, href), fragment = uri.getFragment(); int index = paths.indexOf(file);
            if (index < 0) {
                if (sourceFiles != null && resourceTypes.containsKey(file) && resourceTypes.get(file).startsWith("image/")) {
                    int asset = image(sourceFiles, base, href, null); if (asset >= 0) return target.put("asset", asset);
                }
                return target.put("missing", true);
            }
            target.put("chapter", index).put("fragment", fragment == null ? "" : fragment);
            return target.put("start", content.get(index).getJSONObject("anchors").optInt(fragment == null ? "" : fragment, 0));
        } catch (Exception ignored) { return target.put("missing", true); }
    }
    private void resolveAllLinks() throws Exception { for (int i = 0; i < content.size(); i++) resolve(content.get(i).getJSONObject("content"), paths.get(i)); }
    private void resolve(org.json.JSONObject node, String base) throws Exception {
        org.json.JSONObject a = node.optJSONObject("a");
        if (a != null && a.has("href")) { node.put("target", target(base, a.getString("href"))); a.remove("href"); }
        org.json.JSONArray children = node.optJSONArray("c");
        for (int i = 0; children != null && i < children.length(); i++) resolve(children.getJSONObject(i), base);
    }
    private void navigation(Map<String,byte[]> files, String nav, String ncx) throws Exception {
        if (!nav.isEmpty() && files.containsKey(nav)) {
            NodeList lists = xml(files.get(nav)).getElementsByTagNameNS("*", "nav");
            for (int i = 0; i < lists.getLength(); i++) {
                Element e = (Element)lists.item(i); String type = e.getAttributeNS("http://www.idpf.org/2007/ops", "type");
                if (!(" " + type + " ").contains(" toc ") && !"doc-toc".equals(e.getAttribute("role"))) continue;
                NodeList links = e.getElementsByTagNameNS("*", "a");
                for (int j = 0; j < links.getLength() && toc.length() < 2000; j++) {
                    Element link = (Element)links.item(j); int depth = -1;
                    for (Node n = link.getParentNode(); n != null && n != e; n = n.getParentNode()) if ("li".equals(n.getLocalName())) depth++;
                    addNavigation(nav, link.getAttribute("href"), link.getTextContent(), Math.max(0, depth));
                }
                if (toc.length() > 0) break;
            }
        }
        if (toc.length() == 0 && !ncx.isEmpty() && files.containsKey(ncx)) {
            NodeList points = xml(files.get(ncx)).getElementsByTagNameNS("*", "navPoint");
            for (int i = 0; i < points.getLength() && toc.length() < 2000; i++) {
                Element point = (Element)points.item(i); String label = "", href = ""; int depth = 0;
                for (Node n = point.getFirstChild(); n != null; n = n.getNextSibling()) {
                    if ("navLabel".equals(n.getLocalName())) label = n.getTextContent();
                    if ("content".equals(n.getLocalName())) href = ((Element)n).getAttribute("src");
                }
                for (Node n = point.getParentNode(); n != null; n = n.getParentNode()) if ("navPoint".equals(n.getLocalName())) depth++;
                addNavigation(ncx, href, label, depth);
            }
        }
    }
    private void addNavigation(String base, String href, String label, int depth) throws Exception {
        org.json.JSONObject t = target(base, href);
        if (t.has("chapter")) toc.put(t.put("label", EreaderMarkup.cut(label.replaceAll("\\s+", " ").trim(), 300)).put("depth", Math.min(6, depth)));
    }
    private static void checkEncryption(Map<String,byte[]> files, Map<String,String> types) throws Exception {
        if (!files.containsKey("META-INF/encryption.xml")) return;
        NodeList encrypted = xml(files.get("META-INF/encryption.xml")).getElementsByTagNameNS("*", "EncryptedData");
        boolean allowed = encrypted.getLength() > 0;
        for (int i = 0; i < encrypted.getLength(); i++) {
            Element e = (Element)encrypted.item(i); NodeList methods = e.getElementsByTagNameNS("*", "EncryptionMethod"), refs = e.getElementsByTagNameNS("*", "CipherReference");
            if (methods.getLength() != 1 || refs.getLength() != 1) { allowed = false; break; }
            String algorithm = ((Element)methods.item(0)).getAttribute("Algorithm"), p = path("", ((Element)refs.item(0)).getAttribute("URI"));
            String mime = types.containsKey(p) ? types.get(p) : "";
            if (!(algorithm.equals("http://www.idpf.org/2008/embedding") || algorithm.equals("http://ns.adobe.com/pdf/enc#RC")) ||
                    !p.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(otf|ttf|woff|woff2)") || !mime.matches("font/.*|application/(?:x-font-.*|vnd.ms-opentype|font-.*)")) allowed = false;
        }
        if (!allowed) throw new Exception(Taal.T("EPUB met versleutelde onderdelen wordt niet ondersteund"));
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
