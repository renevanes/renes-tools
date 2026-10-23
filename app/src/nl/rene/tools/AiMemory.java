package nl.rene.tools;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Het geheugen van de assistent: korte feiten over de gebruiker ("Woont in Rotterdam."), alleen op de telefoon
 * (files/ai/memory.json, niet in Android-backups). Alles is zichtbaar en aan te passen bij Lokale AI → Geheugen:
 * de assistent leert alleen met jouw toestemming (of vanzelf, als je dat kiest), en laat elke keer zien wat hij
 * onthield. Het zuivere rekenwerk (herkennen, inkorten, vergelijken) wordt getest in AiMemoryTest.
 */
final class AiMemory {

    private AiMemory() { }

    static final int MAX = 300, TEXT = 240;

    static final class Item {
        final String id, src, chat, title; String text; final long t; long u;
        Item(String id, String text, String src, String chat, String title, long t, long u) { this.id = id; this.text = text; this.src = src; this.chat = chat; this.title = title; this.t = t; this.u = u; }
    }

    // ---------- zuiver (getest) ----------

    /** Eén regel, zonder opsommingsteken of aanhalingstekens, hooguit TEXT tekens, met een hoofdletter. */
    static String clean(String s) {
        if (s == null) return "";
        String x = s.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        x = x.replaceAll("^([-*•·]|\\d+[.)])\\s*", "").trim();
        if (x.length() >= 2 && (x.startsWith("\"") && x.endsWith("\"") || x.startsWith("«") && x.endsWith("»") || x.startsWith("'") && x.endsWith("'")))
            x = x.substring(1, x.length() - 1).trim();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < x.length() && b.length() < TEXT; i++) { char ch = x.charAt(i); if (!Character.isISOControl(ch)) b.append(ch); }
        x = b.toString().trim();
        if (x.length() > 0 && Character.isHighSurrogate(x.charAt(x.length() - 1))) x = x.substring(0, x.length() - 1);
        if (!x.isEmpty()) x = x.substring(0, 1).toUpperCase(Locale.ROOT) + x.substring(1);
        return x;
    }

    private static final Set<String> STOP = new HashSet<>(java.util.Arrays.asList(
            "de", "het", "een", "en", "van", "in", "op", "is", "dat", "die", "te", "met", "voor", "aan", "er", "ik", "mijn", "je", "jij", "zijn", "heeft", "hij", "zij", "ze", "the", "a", "an", "of", "to", "and", "is", "my", "i"));

    /** Woorden zonder hoofdletters, leestekens en kleine woordjes. */
    static Set<String> words(String s) {
        Set<String> w = new HashSet<>();
        if (s == null) return w;
        String n = java.text.Normalizer.normalize(s.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        for (String x : n.split("[^\\p{L}\\p{N}]+")) if (x.length() > 1 && !STOP.contains(x)) w.add(x);
        return w;
    }

    /** Zegt het (bijna) hetzelfde? Dan niet nog eens onthouden. */
    static boolean similar(String a, String b) {
        Set<String> x = words(a), y = words(b);
        if (x.isEmpty() || y.isEmpty()) return x.isEmpty() && y.isEmpty() && clean(a).equalsIgnoreCase(clean(b));
        int both = 0;
        for (String w : x) if (y.contains(w)) both++;
        int union = x.size() + y.size() - both;
        if ((double) both / union >= 0.7) return true;
        // Het een staat helemaal in het ander (bijv. "Woont in Rotterdam" en "Woont in Rotterdam-Noord met zijn zoon")
        return both == Math.min(x.size(), y.size()) && Math.min(x.size(), y.size()) >= 2;
    }

    /** Hoeveel tekens het geheugen in de vaste instructie mag gebruiken (de rest is voor het gesprek). */
    static int limit(int ctx) { return ctx >= 4096 ? 1200 : ctx >= 3072 ? 800 : 400; }

    /** De nieuwste herinneringen die samen in budget tekens passen. */
    static List<String> pick(List<String> newestFirst, int budget) {
        List<String> out = new ArrayList<>();
        int used = 0;
        for (String s : newestFirst) {
            if (s == null || s.isEmpty()) continue;
            if (used + s.length() + 3 > budget) break;
            out.add(s); used += s.length() + 3;
        }
        return out;
    }

    private static final Pattern REMEMBER = Pattern.compile("^\\s*(?:wil je\\s+|kun je\\s+|kan je\\s+|graag\\s+)?(?:onthoud|onthou|onthouden|remember|noteer)\\b[\\s,:]*(?:even\\s+|alsjeblieft\\s+|aub\\s+|please\\s+)?(?:dat|that)?\\s*(.+?)[\\s?.!]*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern FORGET = Pattern.compile("^\\s*(?:wil je\\s+|kun je\\s+|kan je\\s+)?(?:vergeet|forget)\\b[\\s,:]*(?:even\\s+|alsjeblieft\\s+|aub\\s+|please\\s+)?(?:dat|that)?\\s*(.+?)[\\s?.!]*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** "Onthoud dat ik op dinsdag sport" → "Ik sport op dinsdag"-achtig: de tekst om te onthouden, of null. */
    static String rememberCmd(String msg) { return cmd(REMEMBER, msg); }
    /** "Vergeet dat ik in Delft woon" → "ik in Delft woon", of null. */
    static String forgetCmd(String msg) { return cmd(FORGET, msg); }
    private static String cmd(Pattern p, String msg) {
        if (msg == null || msg.length() > 600) return null;
        Matcher m = p.matcher(msg);
        if (!m.matches()) return null;
        String x = clean(m.group(1));
        return words(x).isEmpty() ? null : x;
    }

    private static final Pattern FIRST_PERSON = Pattern.compile("(?iu)(^|[^\\p{L}])(ik|mijn|m'n|me|mij|mijzelf|wij|we|ons|onze|i|my|mine|i'm|i've)([^\\p{L}]|$)");
    private static final Pattern TASK = Pattern.compile("(?iu)^\\s*(vat|vertaal|schrijf|herschrijf|maak|leg|verbeter|controleer|corrigeer|samenvat|translate|summari[sz]e|write|rewrite|fix|explain)\\b");

    /** Is het de moeite om te kijken of er iets te onthouden valt? Iets over jezelf, geen opdracht met geplakte tekst. */
    static boolean worthy(String msg) {
        if (msg == null) return false;
        String m = msg.trim();
        if (m.length() < 12 || m.length() > 1500) return false;
        if (TASK.matcher(m).find()) return false; // "Vat samen: …" bevat vaak de tekst van een ander
        return FIRST_PERSON.matcher(m).find();
    }

    private static final Pattern SECRET = Pattern.compile("(?iu)(wachtwoord|password|wachtzin|passphrase|pincode|\\bpin\\b|iban|creditcard|cvc|inlogcode|toegangscode|verificatiecode|\\btoken\\b|bsn|burgerservicenummer)"
            + "|\\d{6,}|\\b[A-Z]{2}\\d{2}[A-Z0-9]{10,}\\b");

    /** Wachtwoorden, codes en nummers onthoudt de assistent nooit vanzelf. */
    static boolean secret(String s) { return s != null && (SECRET.matcher(s).find() || SECRET.matcher(s.replace(" ", "").replace("-", "")).find()); }

    /** De regels die het model voorstelt, opgeschoond: hooguit 3, geen dubbelen, geen vragen of geheimen. */
    static List<String> parseFacts(String out, List<String> existing) {
        List<String> res = new ArrayList<>();
        if (out == null) return res;
        String o = out.replaceAll("(?s)<think>.*?</think>", "");
        for (String line : o.split("\n")) {
            String raw = line.trim();
            if (!raw.matches("^([-*•·]|\\d+[.)]).*")) continue;
            String x = clean(raw);
            String low = x.toLowerCase(Locale.ROOT);
            if (x.length() < 4 || low.startsWith("geen") || low.startsWith("niets") || low.startsWith("none") || low.startsWith("nothing")) continue;
            if (x.endsWith("?") || words(x).size() < 2 || secret(x)) continue;
            boolean dup = false;
            if (existing != null) for (String e : existing) if (similar(e, x)) { dup = true; break; }
            for (String e : res) if (similar(e, x)) { dup = true; break; }
            if (dup) continue;
            if (!x.matches(".*[.!]$")) x = x + ".";
            res.add(x);
            if (res.size() >= 3) break;
        }
        return res;
    }

    /** De opdracht voor het model om te kijken wat er te onthouden valt (als extra bericht achter het gesprek). */
    static String learnPrompt(String userMsg, List<String> known) {
        StringBuilder s = new StringBuilder("(Dit is een opdracht van de app, geen vraag van de gebruiker. Antwoord niet op de vraag zelf.)\n")
                .append("Staat er in mijn laatste bericht iets over mijzelf dat je later nog moet weten? Bijvoorbeeld wie ik ben, mijn werk, mensen om mij heen, waar ik woon, voorkeuren, vaste gewoontes of plannen. ")
                .append("Alleen wat ik zelf over mijzelf zeg en wat over een week nog klopt. Niet: de vraag zelf, dingen die alleen voor nu gelden, tekst van anderen, wachtwoorden, codes of nummers, en niets wat je al weet.\n\n")
                .append("Mijn laatste bericht: «").append(userMsg.length() > 1500 ? userMsg.substring(0, 1500) : userMsg).append("»\n");
        if (known != null && !known.isEmpty()) {
            s.append("\nWat je al weet:\n");
            int n = 0;
            for (String k : known) { if (n++ >= 25) break; s.append("- ").append(k).append('\n'); }
        }
        s.append("\nAntwoord alleen met hooguit 3 korte regels die beginnen met \"- \", in korte zinnen zonder onderwerp, zoals:\n- Woont in Rotterdam.\n- Heeft een zoon, Daan.\nIs er niets te onthouden, antwoord dan alleen: GEEN");
        return s.toString();
    }

    /** Na "onthoud dat …": het model maakt er een nette korte zin van ("dat ik op dinsdag sport" → "Sport op dinsdag."). */
    static String rephrasePrompt(String raw) {
        return "(Dit is een opdracht van de app, geen vraag van de gebruiker.)\nSchrijf dit als één korte, nette zin over mij, zonder onderwerp, zoals \"Woont in Rotterdam.\" of \"Zoon heet Daan.\". "
                + "Verander niets aan de inhoud en voeg niets toe.\n«" + raw + "»\nAntwoord alleen met één regel die begint met \"- \".";
    }

    /** De nette zin van het model, of null als die er niet is of over iets anders gaat. */
    static String rephrased(String raw, String out) {
        List<String> f = parseFacts(out, null);
        if (f.isEmpty()) return null;
        String x = f.get(0);
        Set<String> a = words(raw), b = words(x);
        int both = 0;
        for (String w : b) if (a.contains(w)) both++;
        // Het moet over hetzelfde gaan: de meeste woorden van de nette zin staan in de oorspronkelijke (vervoegingen daargelaten)
        int stems = 0;
        for (String w : b) for (String v : a) if (w.length() >= 4 && v.length() >= 4 && (w.startsWith(v.substring(0, 4)) || v.startsWith(w.substring(0, 4)))) { stems++; break; }
        return b.isEmpty() || Math.max(both, stems) * 2 < b.size() ? null : x;
    }

    /** Het stuk voor de vaste instructie. */
    static String section(List<String> mem) {
        if (mem == null || mem.isEmpty()) return "";
        StringBuilder s = new StringBuilder("Wat je over de gebruiker onthouden hebt (uit eerdere gesprekken; de gebruiker kan dit zien en aanpassen bij Geheugen). Gebruik het alleen als het bij de vraag past:\n");
        for (String m : mem) s.append("- ").append(m).append('\n');
        return s.toString().trim();
    }

    // ---------- opslag (alleen op de telefoon) ----------

    private static List<Item> cache;

    static File file(Context c) { File d = new File(c.getFilesDir(), "ai"); d.mkdirs(); return new File(d, "memory.json"); }

    private static List<Item> load(Context c) {
        if (cache != null) return cache;
        List<Item> l = new ArrayList<>();
        try {
            File f = file(c);
            if (f.isFile()) {
                JSONArray a = new JSONArray(Contacts.readText(f, false));
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null || o.optString("text").isEmpty()) continue;
                    l.add(new Item(o.optString("id"), o.optString("text"), o.optString("src"), o.optString("chat"), o.optString("title"), o.optLong("t"), o.optLong("u", o.optLong("t"))));
                }
            }
        } catch (Exception ignored) { }
        cache = l;
        return l;
    }

    private static void save(Context c) throws Exception {
        JSONArray a = new JSONArray();
        for (Item m : load(c)) a.put(new JSONObject().put("id", m.id).put("text", m.text).put("src", m.src).put("chat", m.chat).put("title", m.title).put("t", m.t).put("u", m.u));
        Contacts.writeText(file(c), a.toString(), false);
    }

    /** Nieuwste eerst. */
    static synchronized List<Item> list(Context c) {
        List<Item> l = new ArrayList<>(load(c));
        Collections.sort(l, (x, y) -> Long.compare(y.u, x.u));
        return l;
    }

    static List<String> texts(Context c) { List<String> out = new ArrayList<>(); for (Item m : list(c)) out.add(m.text); return out; }

    static JSONArray json(Context c) throws Exception {
        JSONArray a = new JSONArray();
        for (Item m : list(c)) a.put(new JSONObject().put("id", m.id).put("text", m.text).put("src", m.src).put("chat", m.chat).put("title", m.title).put("t", m.t).put("u", m.u));
        return a;
    }

    /** Toevoegen; geeft {id, dup} of {error}. Iets wat er (bijna) al staat: niet nog eens, wel het id daarvan. */
    static synchronized JSONObject add(Context c, String text, String src, String chat, String title) throws Exception {
        String x = clean(text);
        if (words(x).isEmpty()) return new JSONObject().put("error", "Er staat niets om te onthouden");
        List<Item> l = load(c);
        for (Item m : l) if (similar(m.text, x)) {
            // De nieuwe zin zegt meer: die bewaren
            if (x.length() > m.text.length()) m.text = x;
            m.u = System.currentTimeMillis();
            save(c);
            return new JSONObject().put("id", m.id).put("text", m.text).put("dup", true);
        }
        if (l.size() >= MAX) return new JSONObject().put("error", "Je geheugen is vol (" + MAX + "). Wis eerst iets bij Geheugen.");
        long now = System.currentTimeMillis();
        Item m = new Item(Long.toString(now, 36) + Integer.toString((int) (Math.random() * 1296), 36), x, src == null ? "" : src,
                chat == null ? "" : chat, title == null ? "" : cleanTitle(title), now, now);
        l.add(m);
        save(c);
        return new JSONObject().put("id", m.id).put("text", m.text);
    }

    private static String cleanTitle(String t) { String x = t.replace('\n', ' ').trim(); return x.length() > 80 ? x.substring(0, 80) : x; }

    static synchronized String edit(Context c, String id, String text) throws Exception {
        String x = clean(text);
        if (words(x).isEmpty()) return "Er staat niets";
        for (Item m : load(c)) if (m.id.equals(id)) { m.text = x; m.u = System.currentTimeMillis(); save(c); return ""; }
        return "Niet gevonden";
    }

    /** id "*" = alles vergeten. */
    static synchronized void delete(Context c, String id) throws Exception {
        List<Item> l = load(c);
        if ("*".equals(id)) l.clear();
        else for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).id.equals(id)) l.remove(i);
        save(c);
    }

    /** "Vergeet dat …": de best passende herinnering weg; geeft de tekst die weg is, of "". */
    static synchronized String forget(Context c, String what) throws Exception {
        Set<String> w = words(what);
        if (w.isEmpty()) return "";
        Item best = null; double score = 0;
        for (Item m : load(c)) {
            Set<String> y = words(m.text);
            int both = 0;
            for (String x : w) if (y.contains(x)) both++;
            double s = (double) both / Math.max(1, w.size());
            if (s > score) { score = s; best = m; }
        }
        if (best == null || score < 0.5) return "";
        load(c).remove(best);
        save(c);
        return best.text;
    }

    static synchronized int count(Context c) { return load(c).size(); }
}
