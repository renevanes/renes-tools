package nl.rene.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Instellingen van de app via de assistent. De interface (het instellingenregister in de webcode) zoekt bij een vraag
 * over de app de paar instellingen die erbij horen en stuurt die mee (id, naam, waar, stand nu, mogelijke waarden).
 * Het model krijgt alleen die lijst, en mag een wijziging voorstellen met een regel als
 * [[instelling: ai.voiceSend = aan]]. Die regel wordt hier gelezen, uit het antwoord gehaald en streng gecontroleerd:
 * alleen een id uit de meegestuurde lijst, met een waarde die daar bij hoort, en nooit een gevoelige instelling.
 * De interface controleert daarna nog eens tegen het register, en er verandert pas iets na een tik op Toepassen.
 * Zuiver rekenwerk (getest in AiSettingsTest); de JSON-kant staat in LocalAi.
 */
final class AiSettings {
    private AiSettings() { }

    /** Hooguit zoveel instellingen gaan mee naar het model (kleine modellen raken anders de draad kwijt). */
    static final int MAX_ENTRIES = 5;
    /** Hooguit zoveel wijzigingen uit één antwoord. */
    static final int MAX_CHANGES = 4;

    /** Een instelling zoals de interface die meestuurt. vals: {waarde, naam}. safe = alleen uitleggen. */
    static final class Entry {
        String id = "", label = "", where = "", desc = "", cur = "", type = "";
        boolean safe;
        final List<String[]> vals = new ArrayList<>();
    }

    /** Een voorgestelde wijziging. */
    static final class Change {
        final String id, value;
        Change(String id, String value) { this.id = id; this.value = value; }
    }

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,59}");
    private static final Pattern LINE = Pattern.compile("(?iu)`*\\[\\[\\s*(?:instelling|instellingen|setting)\\s*[:：]\\s*([^\\]\\n]{0,240})\\]{1,2}`*");
    private static final Pattern TIME = Pattern.compile("^([01]?\\d|2[0-3])[:.]([0-5]\\d)$");

    /** Mag de vraag instellingen meekrijgen? Niet bij "onthoud/vergeet" (het geheugen) en niet in een gesprek met
     *  berichten van anderen (een samenvatting): hun tekst mag geen wijziging voorstellen. */
    static boolean allowed(boolean has, boolean hasAtt, String remember, String forget) {
        return has && !hasAtt && remember == null && forget == null;
    }

    /** Tekst voor het model: korter gemaakt en zonder regeleinden of haken (die horen niet in de lijst). */
    static String clean(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("[\\u0000-\\u001f\\[\\]]", " ").replaceAll("\\s+", " ").trim();
        if (t.length() > max) { int n = max; if (Character.isHighSurrogate(t.charAt(n - 1))) n--; t = t.substring(0, n).trim() + "…"; }
        return t;
    }

    private static String norm(String s) {
        String t = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
        t = t.replaceAll("^[\"'`«“„(]+|[\"'`»”)]+$", "").replaceAll("[\\s.,;!]+$", "").trim();
        return t;
    }

    /** De waarde zoals de app hem kent, of null als hij niet bij deze instelling hoort. Een naam ("Kort") mag ook. */
    static String canon(Entry e, String value) {
        if (e == null || e.safe || value == null) return null;
        String v = norm(value);
        if (v.isEmpty() || v.length() > 60) return null;
        if ("tijd".equals(e.type)) {
            Matcher m = TIME.matcher(v);
            if (!m.find()) return null;
            int h = Integer.parseInt(m.group(1));
            return (h < 10 ? "0" + h : String.valueOf(h)) + ":" + m.group(2);
        }
        if (!"aan".equals(e.type) && !"keuze".equals(e.type)) return null; // tekst en openen: nooit via het model
        for (String[] x : e.vals) if (norm(x[0]).equals(v)) return x[0];
        for (String[] x : e.vals) if (x.length > 1 && norm(x[1]).equals(v)) return x[0];
        return null;
    }

    /** Alle regels [[instelling: id = waarde]] uit een antwoord, in volgorde (hooguit MAX_CHANGES, per id de laatste). */
    static List<Change> parse(String answer) {
        List<Change> out = new ArrayList<>();
        if (answer == null || answer.indexOf('[') < 0) return out;
        Matcher m = LINE.matcher(answer.length() > 20000 ? answer.substring(0, 20000) : answer);
        while (m.find()) {
            String body = m.group(1);
            int eq = body.indexOf('=');
            if (eq < 0) eq = body.indexOf('→');
            if (eq < 0) continue;
            String id = body.substring(0, eq).trim().toLowerCase(Locale.ROOT).replaceAll("^[\"'`]+|[\"'`]+$", "");
            String val = body.substring(eq + 1).replaceAll("^\\s*>", "").trim();
            if (!ID.matcher(id).matches() || val.isEmpty()) continue;
            for (int i = out.size() - 1; i >= 0; i--) if (out.get(i).id.equals(id)) out.remove(i);
            out.add(new Change(id, val));
        }
        while (out.size() > MAX_CHANGES) out.remove(0);
        return out;
    }

    /** Het antwoord zonder de instellingsregels. */
    static String strip(String answer) {
        if (answer == null) return "";
        if (answer.indexOf('[') < 0) return answer;
        String s = LINE.matcher(answer).replaceAll("");
        return s.replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").trim();
    }

    /** Alleen wijzigingen van meegestuurde, niet-gevoelige instellingen, met een waarde die erbij hoort; en geen
     *  "wijziging" naar wat er al staat. De waarde komt terug zoals de app hem kent. */
    static List<Change> filter(List<Change> in, List<Entry> offered) {
        List<Change> out = new ArrayList<>();
        if (in == null || offered == null) return out;
        for (Change c : in) {
            Entry e = null;
            for (Entry x : offered) if (x.id.equals(c.id)) { e = x; break; }
            String v = canon(e, c.value);
            if (v == null || v.equals(e.cur)) continue;
            out.add(new Change(e.id, v));
        }
        return out;
    }

    /** Hooguit MAX_ENTRIES geldige instellingen (met een nette id), in de volgorde van de interface (beste eerst). */
    static List<Entry> limit(List<Entry> in) {
        List<Entry> out = new ArrayList<>();
        if (in == null) return out;
        for (Entry e : in) {
            if (e == null || !ID.matcher(e.id).matches() || e.label.isEmpty()) continue;
            boolean dup = false;
            for (Entry x : out) if (x.id.equals(e.id)) dup = true;
            if (!dup) out.add(e);
            if (out.size() >= MAX_ENTRIES) break;
        }
        return out;
    }

    private static String valName(Entry e, String v) {
        for (String[] x : e.vals) if (x[0].equals(v)) return x.length > 1 && !x[1].isEmpty() ? x[1] : x[0];
        return v;
    }

    /** Wat het model achter de vraag meekrijgt. */
    static String prompt(List<Entry> entries) {
        StringBuilder s = new StringBuilder("De vraag gaat over de app Tools. Dit zijn de instellingen van de app die erbij kunnen horen; gebruik alleen deze gegevens en verzin geen andere instellingen, schermen of waarden:\n");
        int i = 1;
        boolean change = false;
        for (Entry e : entries) {
            s.append(i++).append(". id: ").append(e.id).append(" · \"").append(clean(e.label, 80)).append('"');
            if (!e.where.isEmpty()) s.append(" · te vinden bij: ").append(clean(e.where, 120));
            if (!e.cur.isEmpty()) s.append(" · staat nu: ").append(clean(valName(e, e.cur), 60));
            if (e.safe) s.append(" · alleen uitleggen, niet voorstellen om te veranderen (de gebruiker doet dit zelf op het scherm)");
            else if ("tijd".equals(e.type)) { s.append(" · kan: een tijd, bijvoorbeeld 07:30"); change = true; }
            else if (!e.vals.isEmpty() && ("aan".equals(e.type) || "keuze".equals(e.type))) {
                s.append(" · kan: ");
                for (int k = 0; k < e.vals.size() && k < 12; k++) {
                    String[] x = e.vals.get(k);
                    if (k > 0) s.append(", ");
                    s.append(clean(x[0], 30));
                    if (x.length > 1 && !x[1].isEmpty() && !x[1].equalsIgnoreCase(x[0])) s.append(" (").append(clean(x[1], 40)).append(')');
                }
                change = true;
            }
            if (!e.desc.isEmpty()) s.append(" · ").append(clean(e.desc, 220));
            s.append('\n');
        }
        s.append("Antwoord kort in het Nederlands en zeg waar de gebruiker het vindt.");
        if (change) s.append(" Vraagt de gebruiker iets te veranderen, zeg dan in één korte zin wat je voorstelt (de gebruiker bevestigt het zelf met een knop, dus zeg niet dat het al gedaan is) en eindig je antwoord met één regel per wijziging, precies zo:\n")
                .append("[[instelling: id = waarde]]\n")
                .append("Gebruik alleen een id en een waarde uit de lijst. Wil de gebruiker niets veranderen, zet dan geen regel.");
        return s.toString();
    }
}
