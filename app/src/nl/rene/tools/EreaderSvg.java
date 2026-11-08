package nl.rene.tools;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NamedNodeMap;
import java.util.Locale;

/** Alleen stilstaande vectorinhoud; geen scripts, HTML, animaties, filters of externe verwijzingen. */
final class EreaderSvg {
    interface Images { String load(String source) throws Exception; }
    private static final String TAGS = " svg g path rect circle ellipse line polyline polygon text tspan defs linearGradient radialGradient stop clipPath mask pattern use image title desc symbol ";
    private static final String NUMBERS = " x y x1 y1 x2 y2 cx cy r rx ry width height dx dy offset stroke-width stroke-miterlimit stroke-dashoffset opacity fill-opacity stroke-opacity stop-opacity font-size textLength lengthAdjust ";
    private static final String COLORS = " fill stroke color stop-color ";
    private int nodes;
    private final Images images;
    private EreaderSvg(Images images) { this.images = images; }
    static String clean(byte[] data, Images images) throws Exception {
        Element root = EreaderBook.xml(data).getDocumentElement();
        if (!"svg".equals(root.getLocalName()) && !"svg".equals(root.getNodeName())) throw new Exception("svg");
        // Ook oudere SVG's met alleen vaste afmetingen moeten zonder afsnijden kunnen schalen.
        if (!root.hasAttribute("viewBox")) {
            double width = dimension(root.getAttribute("width")), height = dimension(root.getAttribute("height"));
            if (width > 0 && height > 0) root.setAttribute("viewBox", "0 0 " + width + " " + height);
        }
        Expansion expansion = new Expansion(root); expansion.count(root, new java.util.HashSet<>(), 0);
        StringBuilder out = new StringBuilder(); new EreaderSvg(images).write(root, out, 0);
        if (out.length() > 8 * 1024 * 1024) throw new Exception("svg");
        return out.toString();
    }
    static double dimension(String value) {
        if (!value.matches("[0-9]+(?:\\.[0-9]+)?(?:px|pt)?")) return 0;
        try { double n = Double.parseDouble(value.replaceAll("(?:px|pt)$", "")); return n > 0 && n <= 100000 ? n : 0; } catch (Exception ignored) { return 0; }
    }
    private static final class Expansion {
        final java.util.Map<String,Element> ids = new java.util.HashMap<>();
        final java.util.Map<Element,Integer> counts = new java.util.HashMap<>();
        Expansion(Element root) throws Exception {
            org.w3c.dom.NodeList all = root.getElementsByTagNameNS("*", "*");
            if (all.getLength() > 25000) throw new Exception("svg");
            if (root.hasAttribute("id")) ids.put(root.getAttribute("id"), root);
            for (int i = 0; i < all.getLength(); i++) { Element e = (Element)all.item(i); if (e.hasAttribute("id")) ids.put(e.getAttribute("id"), e); }
        }
        int count(Element e, java.util.Set<Element> active, int depth) throws Exception {
            if (depth > 100 || !active.add(e)) throw new Exception("svg");
            if (counts.containsKey(e)) { active.remove(e); return counts.get(e); }
            int total = 1;
            if ("use".equals(e.getLocalName()) || "use".equals(e.getNodeName())) {
                String ref = e.getAttribute("href"); if (ref.isEmpty()) ref = e.getAttributeNS("http://www.w3.org/1999/xlink", "href");
                if (ref.startsWith("#") && ids.containsKey(ref.substring(1))) total += count(ids.get(ref.substring(1)), active, depth + 1);
            }
            for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element) { total += count((Element)n, active, depth + 1); if (total > 100000) throw new Exception("svg"); }
            active.remove(e); counts.put(e, total); return total;
        }
    }
    private void write(Node node, StringBuilder out, int depth) throws Exception {
        if (++nodes > 25000 || depth > 100 || out.length() > 8 * 1024 * 1024) throw new Exception("svg");
        if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) { out.append(escape(node.getNodeValue())); return; }
        if (!(node instanceof Element)) return;
        Element e = (Element)node; String tag = e.getLocalName() == null ? e.getNodeName() : e.getLocalName();
        if (tag.equals("style")) { out.append("<style>").append(escape(styles(e.getTextContent()))).append("</style>"); return; }
        if (!TAGS.contains(" " + tag + " ")) return;
        out.append('<').append(tag);
        if (depth == 0) out.append(" xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\"");
        NamedNodeMap attributes = e.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node a = attributes.item(i); String key = a.getNodeName(), value = a.getNodeValue();
            if (key.equals("style")) { String s = declarations(value); if (!s.isEmpty()) out.append(" style=\"").append(escape(s)).append('"'); }
            else if (safe(key, value)) out.append(' ').append(key).append("=\"").append(escape(value)).append('"');
        }
        String href = e.getAttribute("href"); if (href.isEmpty()) href = e.getAttributeNS("http://www.w3.org/1999/xlink", "href");
        String target = "";
        if (tag.equals("use") && href.matches("#[A-Za-z_][A-Za-z0-9_.:-]{0,200}")) target = href;
        if (tag.equals("image") && !href.isEmpty()) { try { target = images.load(href); } catch (Exception ignored) { } }
        if (!target.isEmpty() && (target.startsWith("#") || target.matches("data:image/(?:png|jpeg|gif|webp|svg\\+xml);base64,[A-Za-z0-9+/=]+")))
            out.append(" href=\"").append(escape(target)).append("\" xlink:href=\"").append(escape(target)).append('"');
        out.append('>');
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) write(child, out, depth + 1);
        out.append("</").append(tag).append('>');
    }
    private static boolean safe(String key, String value) {
        if (value.length() > 200000) return false;
        if (key.equals("id")) return value.matches("[A-Za-z_][A-Za-z0-9_.:-]{0,200}");
        if (key.equals("class")) return value.length() < 300 && value.matches("[A-Za-z0-9 _-]+");
        if (key.equals("viewBox")) return value.length() < 150 && value.matches("[-+0-9.eE, \\t\\r\\n]+");
        if (key.equals("d")) return value.matches("[MmZzLlHhVvCcSsQqTtAaEe0-9+., \\t\\r\\n-]+");
        if (key.equals("points") || key.equals("stroke-dasharray")) return value.length() < 20000 && value.matches("[-+0-9.eE, \\t\\r\\n]+");
        if (NUMBERS.contains(" " + key + " ")) return value.length() < 40 && value.matches("[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]{1,2})?(?:%|px|pt|em)?");
        if (COLORS.contains(" " + key + " ")) return value.length() < 150 && (value.matches("#[a-fA-F0-9]{3,8}|[A-Za-z]{1,30}|rgba?\\([0-9.,% ]+\\)") || value.matches("url\\(#[A-Za-z_][A-Za-z0-9_.:-]{0,200}\\)"));
        if (key.equals("transform") || key.equals("gradientTransform") || key.equals("patternTransform")) return value.length() < 1000 && value.matches("[A-Za-z0-9+.,() \\t\\r\\n-]+") && !value.contains("url");
        if (key.equals("preserveAspectRatio")) return value.matches("none|x(?:Min|Mid|Max)Y(?:Min|Mid|Max)(?: (?:meet|slice))?");
        if (key.equals("clip-path") || key.equals("mask")) return value.matches("url\\(#[A-Za-z_][A-Za-z0-9_.:-]{0,200}\\)");
        if (key.equals("fill-rule") || key.equals("clip-rule")) return value.matches("nonzero|evenodd");
        if (key.equals("text-anchor")) return value.matches("start|middle|end");
        if (key.equals("stroke-linecap")) return value.matches("butt|round|square");
        if (key.equals("stroke-linejoin")) return value.matches("miter|round|bevel");
        if (key.equals("font-style")) return value.matches("normal|italic|oblique");
        if (key.equals("font-weight")) return value.matches("normal|bold|[1-9]00");
        if (key.equals("font-family")) return value.matches("serif|sans-serif|monospace");
        if (key.equals("gradientUnits") || key.equals("patternUnits") || key.equals("patternContentUnits") || key.equals("clipPathUnits") || key.equals("maskUnits")) return value.matches("userSpaceOnUse|objectBoundingBox");
        return false;
    }
    private static String declarations(String css) {
        StringBuilder out = new StringBuilder();
        for (String item : css.split(";")) {
            int colon = item.indexOf(':'); if (colon < 0 || item.length() > 500) continue;
            String key = item.substring(0, colon).trim().toLowerCase(Locale.ROOT), value = item.substring(colon + 1).trim();
            if (safe(key, value) && !key.matches("id|class|viewBox|d|points|transform")) out.append(key).append(':').append(value).append(';');
        }
        return out.toString();
    }
    private static String styles(String css) {
        if (css.length() > 128 * 1024) return ""; StringBuilder out = new StringBuilder(); int pos = 0;
        while (pos < css.length()) {
            int open = css.indexOf('{', pos), close = open < 0 ? -1 : css.indexOf('}', open + 1); if (close < 0) break;
            String selector = css.substring(pos, open).trim(), d = declarations(css.substring(open + 1, close));
            if (selector.length() < 200 && selector.matches("[A-Za-z0-9_.# ,>-]+") && !d.isEmpty()) out.append(selector).append('{').append(d).append('}');
            pos = close + 1;
        }
        return out.toString();
    }
    static String escape(String text) { return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
}
