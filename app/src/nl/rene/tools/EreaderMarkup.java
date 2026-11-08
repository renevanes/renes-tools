package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Een gesloten inhoudsmodel: de interface ontvangt gegevens, nooit uitvoerbare boek-HTML. */
final class EreaderMarkup {
    interface Media { int image(String base, String source, Element svg) throws Exception; }
    static final class Budget { int nodes; }
    static final String TAGS = " div span p h1 h2 h3 h4 h5 h6 strong b em i u s del ins small sup sub blockquote q pre code kbd samp var ul ol li dl dt dd table thead tbody tfoot tr th td caption figure figcaption hr br a ruby rt rp abbr cite time aside section article header footer main nav address details summary ";
    static final String MATH = " math mrow mi mn mo mtext mspace ms msqrt mroot mfrac msup msub msubsup munder mover munderover mfenced menclose mtable mtr mtd mstyle mphantom mpadded semantics annotation ";
    final JSONObject root;
    final String text;
    final Map<String,Integer> anchors = new HashMap<>();
    private final StringBuilder raw = new StringBuilder();
    private final BitSet exact = new BitSet();
    private final EreaderCss css;
    private final Media media;
    private final String base;
    private final Budget budget;
    int images;
    private int visibleCharacters;

    EreaderMarkup(Element body, String base, EreaderCss css, Media media, Budget budget) throws Exception {
        this.base = base; this.css = css; this.media = media; this.budget = budget;
        root = visit(body, false, 0);
        int length = raw.length(), first = 0, last = length;
        while (first < last && raw.charAt(first) <= 32 && !exact.get(first)) first++;
        while (last > first && raw.charAt(last - 1) <= 32 && !exact.get(last - 1)) last--;
        StringBuilder normalized = new StringBuilder(); int[] map = new int[length + 1]; int newlines = 0;
        for (int i = 0; i < length;) {
            map[i] = normalized.length(); char ch = raw.charAt(i);
            if (i < first || i >= last) { i++; continue; }
            if (!exact.get(i) && (ch == ' ' || ch == '\t')) {
                int end = i; while (end < last && !exact.get(end) && (raw.charAt(end) == ' ' || raw.charAt(end) == '\t')) end++;
                if (end < last && raw.charAt(end) == '\n') { while (i < end) map[i++] = normalized.length(); continue; }
            }
            if (ch != '\n' || exact.get(i) || newlines < 2) normalized.append(ch);
            newlines = ch == '\n' ? newlines + 1 : 0; i++;
        }
        map[length] = normalized.length(); text = normalized.toString();
        normalize(root, map);
    }
    private JSONObject leaf(String value, boolean pre) throws Exception {
        int start = raw.length(); raw.append(value); if (pre) exact.set(start, raw.length());
        for (int i = 0; i < value.length(); i++) if (!Character.isWhitespace(value.charAt(i))) visibleCharacters++;
        return new JSONObject().put("t", value).put("s", start).put("e", raw.length());
    }
    private static String name(Node n) { String tag = n.getLocalName(); return (tag == null ? n.getNodeName() : tag).toLowerCase(Locale.ROOT); }
    private JSONObject visit(Node node, boolean pre, int depth) throws Exception {
        if (++budget.nodes > 150000 || depth > 120) throw new Exception(Taal.T("Dit EPUB-bestand is te ingewikkeld"));
        if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE)
            return leaf(pre ? node.getNodeValue() : node.getNodeValue().replaceAll("\\s+", " "), pre);
        if (!(node instanceof Element)) return null;
        Element e = (Element)node; String sourceTag = name(e);
        int beforeText = visibleCharacters;
        if (sourceTag.matches("script|style|head|iframe|frame|frameset|base|link|meta|title|embed")) return null;
        if (sourceTag.equals("svg") || sourceTag.equals("img") || sourceTag.equals("image") || sourceTag.equals("object")) {
            if (sourceTag.equals("object") && !e.getAttribute("type").startsWith("image/")) return null;
            String source = sourceTag.equals("object") ? e.getAttribute("data") : e.getAttribute("src");
            if (source.isEmpty()) source = e.getAttribute("href");
            if (source.isEmpty() && e.hasAttribute("srcset")) source = e.getAttribute("srcset").split(",")[0].trim().split("\\s+")[0];
            if (source.isEmpty()) source = e.getAttributeNS("http://www.w3.org/1999/xlink", "href");
            String alt = e.getAttribute("alt");
            if (alt.isEmpty() && sourceTag.equals("svg")) {
                org.w3c.dom.NodeList titles = e.getElementsByTagNameNS("*", "title");
                if (titles.getLength() > 0) alt = titles.item(0).getTextContent();
            }
            int asset = media.image(base, source, sourceTag.equals("svg") ? e : null); images++;
            JSONObject style = css.style(e);
            if (!style.has("width") && e.getAttribute("width").matches("[1-9][0-9]{0,3}")) style.put("width", e.getAttribute("width") + "px");
            if (!style.has("width") && e.getAttribute("width").matches("[1-9][0-9]?%|100%")) style.put("width", e.getAttribute("width"));
            return new JSONObject().put("tag", "img").put("asset", asset).put("alt", cut(alt, 500)).put("at", raw.length())
                    .put("id", cut(e.getAttribute("id"), 300)).put("style", style);
        }
        boolean math = "http://www.w3.org/1998/Math/MathML".equals(e.getNamespaceURI()) && MATH.contains(" " + sourceTag + " ");
        String tag = math || TAGS.contains(" " + sourceTag + " ") ? sourceTag : "span";
        if (sourceTag.equals("body")) tag = "div";
        JSONObject out = new JSONObject().put("tag", tag).put("at", raw.length()), a = new JSONObject();
        String id = e.getAttribute("id"); if (id.isEmpty()) id = e.getAttribute("name");
        if (id.isEmpty()) id = e.getAttributeNS("http://www.w3.org/XML/1998/namespace", "id");
        if (!id.isEmpty()) out.put("id", cut(id, 300));
        String lang = e.getAttribute("lang"); if (lang.isEmpty()) lang = e.getAttributeNS("http://www.w3.org/XML/1998/namespace", "lang");
        if (lang.length() <= 64 && lang.matches("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*")) a.put("lang", lang);
        if (e.getAttribute("dir").matches("ltr|rtl|auto")) a.put("dir", e.getAttribute("dir"));
        if (tag.equals("a") && e.hasAttribute("href")) a.put("href", cut(e.getAttribute("href"), 2048));
        if (tag.equals("ol") && e.getAttribute("start").matches("-?[0-9]{1,6}")) a.put("start", e.getAttribute("start"));
        if (tag.equals("li") && e.getAttribute("value").matches("-?[0-9]{1,6}")) a.put("value", e.getAttribute("value"));
        if (tag.equals("td") || tag.equals("th")) {
            for (String key : new String[]{"colspan", "rowspan"}) if (e.getAttribute(key).matches("[1-9][0-9]?")) a.put(key, e.getAttribute(key));
            if (e.getAttribute("scope").matches("row|col|rowgroup|colgroup")) a.put("scope", e.getAttribute("scope"));
        }
        String type = e.getAttributeNS("http://www.idpf.org/2007/ops", "type");
        if (type.isEmpty()) type = e.getAttribute("epub:type");
        if ((" " + type + " ").contains(" noteref ")) a.put("role", "doc-noteref");
        if ((" " + type + " ").contains(" footnote ") || (" " + type + " ").contains(" endnote ")) a.put("role", "doc-footnote");
        if (math) {
            out.put("math", true);
            for (String key : new String[]{"displaystyle", "stretchy", "accent", "accentunder"}) if (e.getAttribute(key).matches("true|false")) a.put(key, e.getAttribute(key));
            if (e.getAttribute("mathvariant").matches("normal|bold|italic|bold-italic|double-struck|script|fraktur|monospace|sans-serif")) a.put("mathvariant", e.getAttribute("mathvariant"));
            if (e.getAttribute("display").matches("block|inline")) a.put("display", e.getAttribute("display"));
            for (String key : new String[]{"open", "close", "separators"}) if (e.hasAttribute(key)) a.put(key, cut(e.getAttribute(key), 8));
        }
        JSONObject style = css.style(e); if (style.length() > 0) out.put("style", style);
        out.put("a", a); JSONArray children = new JSONArray();
        pre = pre || tag.equals("pre") || tag.equals("code") || style.optString("whiteSpace").matches("pre|pre-wrap");
        boolean block = tag.matches("p|div|section|article|h[1-6]|li|blockquote|tr|pre|figure|figcaption|ul|ol|dl|dt|dd|table|caption|aside|hr");
        if (block) children.put(leaf("\n", false));
        if (tag.equals("br")) {
            out.put("tag", "span");
            if (!pre) children.put(new JSONObject().put("tag", "br").put("at", raw.length()));
            children.put(leaf("\n", pre));
        } else for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            JSONObject c = visit(child, pre, depth + 1); if (c != null) children.put(c);
        }
        if (tag.equals("td") || tag.equals("th")) children.put(leaf("\t", false));
        if (block) children.put(leaf("\n", false));
        if (visibleCharacters == beforeText) out.put("mediaOnly", true);
        return out.put("c", children);
    }
    private void normalize(JSONObject node, int[] map) throws Exception {
        if (node.has("t")) {
            int start = map[node.getInt("s")], end = map[node.getInt("e")];
            node.put("s", start).put("e", end).put("t", text.substring(start, end));
        } else {
            int at = map[node.optInt("at")]; node.put("at", at);
            if (!node.optString("id").isEmpty()) anchors.put(node.getString("id"), at);
            JSONArray children = node.optJSONArray("c");
            for (int i = 0; children != null && i < children.length(); i++) normalize(children.getJSONObject(i), map);
        }
    }
    static String cut(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }
}
