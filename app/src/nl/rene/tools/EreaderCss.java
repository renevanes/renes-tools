package nl.rene.tools;

import org.json.JSONObject;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Alleen leesopmaak. Geen boek-CSS, imports, URL's, posities of selectors in de app plaatsen. */
final class EreaderCss {
    private static final Pattern SIMPLE = Pattern.compile("^([A-Za-z][A-Za-z0-9-]*|\\*)?((?:[.#][A-Za-z0-9_-]+)*)$");
    private static final Pattern PARTS = Pattern.compile("([.#])([A-Za-z0-9_-]+)");
    private int operations;
    private static final class Rule {
        final String selector, declarations; final int weight, order;
        Rule(String s, String d, int o) { selector = s; declarations = d; order = o; weight = s.contains("#") ? 100 : s.contains(".") ? 10 : 1; }
    }
    private final List<Rule> rules = new ArrayList<>();
    void add(String css) {
        if (css == null || css.length() > 512 * 1024) return;
        StringBuilder clean = new StringBuilder(); int from = 0;
        while (from < css.length()) {
            int open = css.indexOf("/*", from); if (open < 0) { clean.append(css.substring(from)); break; }
            clean.append(css, from, open); int end = css.indexOf("*/", open + 2); if (end < 0) break; from = end + 2;
        }
        String source = clean.toString(); int pos = 0;
        while (pos < source.length() && rules.size() < 500) {
            int open = source.indexOf('{', pos); if (open < 0) break;
            int close = source.indexOf('}', open + 1); if (close < 0) break;
            String declarations = source.substring(open + 1, close);
            for (String selector : source.substring(pos, open).split(",")) {
                selector = selector.trim();
                if (selector.length() < 180 && selector.matches("[A-Za-z0-9_.*# >-]+") && !selector.isEmpty()) rules.add(new Rule(selector, declarations, rules.size()));
            }
            pos = close + 1;
        }
        java.util.Collections.sort(rules, (a, b) -> a.weight == b.weight ? a.order - b.order : a.weight - b.weight);
    }
    private static boolean simple(Element e, String selector) {
        Matcher m = SIMPLE.matcher(selector);
        if (!m.matches()) return false;
        if (m.group(1) != null && !"*".equals(m.group(1)) && !m.group(1).equalsIgnoreCase(e.getLocalName())) return false;
        Matcher parts = PARTS.matcher(m.group(2));
        while (parts.find()) {
            if (parts.group(1).equals("#") && !parts.group(2).equals(e.getAttribute("id"))) return false;
            if (parts.group(1).equals(".") && !(" " + e.getAttribute("class").replaceAll("\\s+", " ") + " ").contains(" " + parts.group(2) + " ")) return false;
        }
        return true;
    }
    private static boolean matches(Element e, String selector) {
        // Vaak voorkomende type-/klasse-/id-selectors en afstammelingen; ingewikkeldere selectors worden genegeerd.
        String[] parts = selector.replace(">", " > ").trim().split("\\s+"); int at = parts.length - 1;
        if (!simple(e, parts[at--])) return false;
        Node n = e.getParentNode();
        while (at >= 0) {
            boolean direct = parts[at].equals(">"); if (direct) at--;
            if (at < 0) return false;
            while (n instanceof Element && !simple((Element)n, parts[at])) { if (direct) return false; n = n.getParentNode(); }
            if (!(n instanceof Element)) return false;
            at--; n = n.getParentNode();
        }
        return true;
    }
    JSONObject style(Element e) throws Exception {
        JSONObject out = new JSONObject();
        for (Rule r : rules) { if (++operations > 2_000_000) break; if (matches(e, r.selector)) declarations(out, r.declarations); }
        declarations(out, e.getAttribute("style"));
        String align = e.getAttribute("align").toLowerCase(Locale.ROOT); if (align.matches("left|right|center|justify")) out.put("textAlign", align);
        return out;
    }
    static void declarations(JSONObject out, String raw) throws Exception {
        for (String item : raw.split(";")) {
            int colon = item.indexOf(':'); if (colon < 0) continue;
            if (item.length() > 160) continue;
            String key = item.substring(0, colon).trim().toLowerCase(Locale.ROOT), value = item.substring(colon + 1).trim().toLowerCase(Locale.ROOT);
            if (value.endsWith("!important")) value = value.substring(0, value.length() - 10).trim();
            if (key.equals("text-align") && value.matches("start|end|left|right|center|justify")) out.put("textAlign", value);
            if (key.equals("font-weight") && value.matches("normal|bold|[1-9]00")) out.put("fontWeight", value);
            if (key.equals("font-style") && value.matches("normal|italic|oblique")) out.put("fontStyle", value);
            if (key.equals("text-decoration") && value.matches("none|underline|line-through|overline")) out.put("textDecoration", value);
            if (key.equals("white-space") && value.matches("normal|pre|pre-wrap|pre-line")) out.put("whiteSpace", value);
            if (key.equals("text-transform") && value.matches("none|uppercase|lowercase|capitalize")) out.put("textTransform", value);
            if (key.equals("vertical-align") && value.matches("baseline|super|sub")) out.put("verticalAlign", value);
            if (key.equals("font-variant") && value.matches("normal|small-caps")) out.put("fontVariant", value);
            if (key.matches("font-size|text-indent|margin-top|margin-bottom")) {
                Matcher size = Pattern.compile("(-?\\d+(?:\\.\\d+)?)(em|rem|%|pt|px)?").matcher(value);
                if (!size.matches()) continue;
                double amount = Double.parseDouble(size.group(1)); String unit = size.group(2);
                if ("%".equals(unit)) amount /= 100; else if ("pt".equals(unit)) amount /= 12; else if ("px".equals(unit)) amount /= 16;
                if (unit == null && amount != 0) continue;
                if (key.equals("font-size") && (amount < .5 || amount > 3)) continue;
                if (!key.equals("font-size") && (amount < (key.equals("text-indent") ? -2 : 0) || amount > 4)) continue;
                String mapped = key.equals("font-size") ? "fontSize" : key.equals("text-indent") ? "textIndent" : key.equals("margin-top") ? "marginTop" : "marginBottom";
                out.put(mapped, Double.toString(amount) + "em");
            }
        }
    }
}
