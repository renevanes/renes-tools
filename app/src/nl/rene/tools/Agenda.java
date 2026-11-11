package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agenda: datums, ISO-weeknummers, herhaling, zoeken (ook in het verleden) en een direct antwoord voor de assistent.
 * Zuiver rekenwerk, zonder Android-agenda. De opslag zit in AgendaStore, de agenda van de telefoon in AgendaCal.
 * Tijden zijn wandklok ("07:30") en datums "JJJJ-MM-DD": een hele dag verschuift nooit.
 */
final class Agenda {
    private Agenda() { }

    static final int FORMAAT = 1;
    static final int MAX_ITEMS = 5000;
    static final int MAX_TITEL = 200;
    static final int MAX_PLEK = 200;
    static final int MAX_NOTITIE = 4000;
    static final int MAX_ZOEK = 200;
    static final int MAX_PER_REEKS = 8;
    static final int MAX_UITKLAP = 1200;
    static final int MAX_ANTWOORD = 12;

    static final class Item {
        String id = "", titel = "", plek = "", notitie = "", dag = "", eind = "", van = "", tot = "";
        String herhaal = "", totDag = "", kalender = "", kleur = "", naam = "";
        boolean heleDag;
        long ext;
    }

    /** Foutcode leeg = gelukt. titel, datum, herhaal, vol. */
    static final class Uitkomst {
        final JSONObject item;
        final String fout;
        Uitkomst(JSONObject item, String fout) { this.item = item; this.fout = fout == null ? "" : fout; }
        boolean ok() { return fout.isEmpty() && item != null; }
    }

    static final class Venster {
        String van = "", tot = "", woorden = "";
        boolean lijst;
    }

    // ---------- datums ----------

    private static final Pattern YMD = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
    private static final Pattern HM = Pattern.compile("([01]?\\d|2[0-3]):([0-5]\\d)");
    private static final String[] WD = {"zo", "ma", "di", "wo", "do", "vr", "za"};
    private static final String[] MAAND = {"jan", "feb", "mrt", "apr", "mei", "jun", "jul", "aug", "sep", "okt", "nov", "dec"};
    private static final String[] MAAND_LANG = {"januari", "februari", "maart", "april", "mei", "juni", "juli", "augustus", "september", "oktober", "november", "december"};
    private static final String[] DAG_LANG = {"zondag", "maandag", "dinsdag", "woensdag", "donderdag", "vrijdag", "zaterdag"};
    private static final String DAG_RE = "zondag|maandag|dinsdag|woensdag|donderdag|vrijdag|zaterdag";

    static int[] ymd(String key) {
        if (key == null) return null;
        Matcher m = YMD.matcher(key);
        if (!m.matches()) return null;
        int y = Integer.parseInt(m.group(1)), mo = Integer.parseInt(m.group(2)), d = Integer.parseInt(m.group(3));
        if (y < 1 || mo < 1 || mo > 12 || d < 1 || d > 31) return null;
        return new int[]{y, mo, d};
    }

    private static String two(int n) { return n < 10 ? "0" + n : String.valueOf(n); }

    static boolean dagOk(String key) {
        int[] d = ymd(key);
        if (d == null) return false;
        Calendar k = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        k.setLenient(false);
        k.clear();
        try { k.set(d[0], d[1] - 1, d[2], 12, 0, 0); k.getTimeInMillis(); return true; }
        catch (IllegalArgumentException e) { return false; }
    }

    static boolean hmOk(String hm) { return hm != null && HM.matcher(hm).matches(); }

    /** "HH:mm" plus minuten, of null als het buiten dezelfde dag valt. */
    static String hmPlus(String hm, int min) {
        if (!hmOk(hm)) return null;
        Matcher m = HM.matcher(hm);
        if (!m.matches()) return null;
        int t = Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2)) + min;
        if (t < 0 || t >= 24 * 60) return null;
        return two(t / 60) + ":" + two(t % 60);
    }

    static int verschil(String a, String b) {
        int[] x = ymd(a), y = ymd(b);
        if (x == null || y == null) return 0;
        return epoch(y[0], y[1], y[2]) - epoch(x[0], x[1], x[2]);
    }

    /** Dagen sinds 1970-01-01 (Howard Hinnant). 1970-01-01 = 0. Jaren buiten 1–9999 geven 0. */
    static int epoch(int y, int m, int d) {
        if (y < 1 || y > 9999 || m < 1 || m > 12 || d < 1 || d > 31) return 0;
        // long: de invoer past dan niet meer in een int-vermenigvuldiging die kan overlopen.
        long jaar = y, maand = m, dag = d;
        jaar -= maand <= 2 ? 1 : 0;
        long era = (jaar >= 0 ? jaar : jaar - 399) / 400;
        long yoe = jaar - era * 400;
        long doy = (153 * (maand + (maand > 2 ? -3 : 9)) + 2) / 5 + dag - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        long days = era * 146097 + doe - 719468;
        if (days > Integer.MAX_VALUE || days < Integer.MIN_VALUE) return 0;
        return (int) days;
    }

    /** Zelfde dag van de maand, of de laatste dag als die niet bestaat (31 jan → 28 of 29 feb). */
    static String plusMaand(String dag, int n) {
        int[] d = ymd(dag);
        if (d == null) return null;
        Calendar k = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        k.clear();
        k.set(d[0], d[1] - 1, 1, 12, 0, 0);
        k.add(Calendar.MONTH, n);
        int last = k.getActualMaximum(Calendar.DAY_OF_MONTH);
        int day = Math.min(d[2], last);
        return k.get(Calendar.YEAR) + "-" + two(k.get(Calendar.MONTH) + 1) + "-" + two(day);
    }

    /** 0 = zondag … 6 = zaterdag, zoals Date.getDay en weekStart(). */
    static int jsDow(String dag) {
        int w = Regio.weekday(dag);
        return w >= 1 && w <= 7 ? w - 1 : 0;
    }

    /** ISO-weekdag: 1 = maandag … 7 = zondag. */
    static int isoDow(String dag) {
        int w = jsDow(dag);
        return w == 0 ? 7 : w;
    }

    static String donderdag(String dag) { return Regio.dayAdd(dag, 4 - isoDow(dag)); }

    static int isoJaar(String dag) {
        String thu = donderdag(dag);
        return thu == null || thu.length() < 4 ? 0 : Integer.parseInt(thu.substring(0, 4));
    }

    /** ISO-weeknummer (week 1 bevat 4 januari). 0 als de datum niet klopt. */
    static int isoWeek(String dag) {
        if (!dagOk(dag)) return 0;
        String thu = donderdag(dag);
        int y = isoJaar(dag);
        String jan4 = y + "-01-04";
        String mon = Regio.dayAdd(jan4, 1 - isoDow(jan4));
        return verschil(mon, thu) / 7 + 1;
    }

    /** Maandag van ISO-week {@code week} in ISO-jaar {@code jaar}, of null. */
    static String isoMaandag(int jaar, int week) {
        if (week < 1 || week > 53) return null;
        String jan4 = jaar + "-01-04";
        if (!dagOk(jan4)) return null;
        String mon = Regio.dayAdd(jan4, 1 - isoDow(jan4) + (week - 1) * 7);
        return isoWeek(mon) == week ? mon : null;
    }

    static int weekStartOk(int w) { return w == 0 || w == 6 ? w : 1; }

    /** Inclusief bereik van een weergave. maand = het rooster (6 weken), lijst = de kalendermaand. */
    static String[] bereik(String view, String anker, int weekStart) {
        if (!dagOk(anker)) return null;
        int ws = weekStartOk(weekStart);
        if ("dag".equals(view)) return new String[]{anker, anker};
        if ("week".equals(view)) {
            String van = Regio.dayAdd(anker, -((jsDow(anker) - ws + 7) % 7));
            return new String[]{van, Regio.dayAdd(van, 6)};
        }
        String eerste = anker.substring(0, 8) + "01";
        if (!dagOk(eerste)) return null;
        if ("lijst".equals(view)) return new String[]{eerste, Regio.dayAdd(plusMaand(eerste, 1), -1)};
        String van = Regio.dayAdd(eerste, -((jsDow(eerste) - ws + 7) % 7));
        return new String[]{van, Regio.dayAdd(van, 41)};
    }

    /** Hele-dag-afspraak uit de Android-agenda: begin en eind zijn UTC-middernacht, eind is exclusief. */
    static String[] alleDag(long begin, long end) {
        TimeZone utc = TimeZone.getTimeZone("UTC");
        String dag = Regio.dayKey(utc, begin);
        if (!dagOk(dag)) return null;
        String ex = Regio.dayKey(utc, Math.max(begin + 1, end));
        String eind = Regio.dayAdd(ex, -1);
        if (eind == null || eind.compareTo(dag) < 0) eind = dag;
        return new String[]{dag, eind};
    }

    static String herhaalCode(String rrule) {
        if (rrule == null) return "";
        Matcher m = Pattern.compile("FREQ=(DAILY|WEEKLY|MONTHLY|YEARLY)").matcher(rrule.toUpperCase(Locale.ROOT));
        if (!m.find()) return "";
        switch (m.group(1)) {
            case "DAILY": return "d";
            case "WEEKLY": return "w";
            case "MONTHLY": return "m";
            default: return "y";
        }
    }

    static String totUitRrule(String rrule) {
        if (rrule == null) return "";
        Matcher m = Pattern.compile("UNTIL=(\\d{4})(\\d{2})(\\d{2})").matcher(rrule.toUpperCase(Locale.ROOT));
        if (!m.find()) return "";
        String d = m.group(1) + "-" + m.group(2) + "-" + m.group(3);
        return dagOk(d) ? d : "";
    }

    static String rrule(String herhaal, String totDag, boolean heleDag) {
        String freq = "d".equals(herhaal) ? "DAILY" : "w".equals(herhaal) ? "WEEKLY" : "m".equals(herhaal) ? "MONTHLY" : "y".equals(herhaal) ? "YEARLY" : "";
        if (freq.isEmpty()) return "";
        if (!dagOk(totDag)) return "FREQ=" + freq;
        return "FREQ=" + freq + ";UNTIL=" + totDag.replace("-", "") + (heleDag ? "" : "T235959Z");
    }

    /** Duur voor de agenda van de telefoon (RFC 5545). Een reeks krijgt dit in plaats van een eindtijdstip. */
    static String duurIcs(long ms, boolean all) {
        if (ms < 60_000L) ms = all ? 86_400_000L : 3_600_000L;
        if (all) {
            long dagen = (ms + 86_400_000L - 1) / 86_400_000L;
            if (dagen < 1) dagen = 1;
            return "P" + dagen + "D";
        }
        long min = ms / 60_000L;
        long dag = min / (24 * 60);
        min -= dag * 24 * 60;
        long uur = min / 60;
        min -= uur * 60;
        StringBuilder b = new StringBuilder("P");
        if (dag > 0) b.append(dag).append('D');
        if (uur > 0 || min > 0 || dag == 0) {
            b.append('T');
            if (uur > 0) b.append(uur).append('H');
            if (min > 0 || uur == 0) b.append(min).append('M');
        }
        return b.toString();
    }

    // ---------- opschonen ----------

    static String tekst(String s, int max, boolean regels) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < max; i++) {
            char c = s.charAt(i);
            if (c == '\n') b.append(regels ? '\n' : ' ');
            else if (c == '\t' || !Character.isISOControl(c)) b.append(c);
        }
        return b.toString().trim();
    }

    static boolean kleurOk(String k) { return k != null && k.matches("#[0-9A-Fa-f]{6}"); }

    static Uitkomst schoon(JSONObject in, int aantal, boolean nieuw) {
        if (in == null) return new Uitkomst(null, "datum");
        String titel = tekst(in.optString("titel"), MAX_TITEL, false);
        if (titel.isEmpty()) return new Uitkomst(null, "titel");
        String dag = in.optString("dag");
        if (!dagOk(dag)) return new Uitkomst(null, "datum");
        boolean hele = in.optBoolean("heleDag");
        String eind = in.optString("eind");
        if (!dagOk(eind) || eind.compareTo(dag) < 0) eind = dag;
        String van = "", tot = "";
        if (!hele) {
            van = in.optString("van");
            tot = in.optString("tot");
            if (!hmOk(van)) van = "09:00";
            if (!hmOk(tot)) {
                String plus = hmPlus(van, 60);
                if (plus == null) { tot = "00:00"; if (eind.equals(dag)) eind = Regio.dayAdd(dag, 1); }
                else tot = plus;
            } else if (eind.equals(dag) && tot.compareTo(van) <= 0) eind = Regio.dayAdd(dag, 1);
        }
        String her = in.optString("herhaal");
        if (!"d".equals(her) && !"w".equals(her) && !"m".equals(her) && !"y".equals(her)) her = "";
        String totDag = her.isEmpty() ? "" : in.optString("totDag");
        if (!totDag.isEmpty() && (!dagOk(totDag) || totDag.compareTo(dag) < 0)) return new Uitkomst(null, "datum");
        String kal = in.optString("kalender");
        if (!kal.isEmpty() && !kal.matches("[1-9]\\d{0,17}")) return new Uitkomst(null, "kalender");
        String id = in.optString("id");
        if (nieuw || !id.matches("a[a-z0-9]{1,24}")) id = nieuwId();
        if (nieuw && aantal >= MAX_ITEMS) return new Uitkomst(null, "vol");
        String kleur = in.optString("kleur");
        if (!kleurOk(kleur)) kleur = "";
        try {
            JSONObject o = new JSONObject();
            o.put("id", id).put("titel", titel).put("plek", tekst(in.optString("plek"), MAX_PLEK, false));
            o.put("notitie", tekst(in.optString("notitie"), MAX_NOTITIE, true));
            o.put("dag", dag).put("eind", eind).put("van", van).put("tot", tot).put("heleDag", hele);
            o.put("herhaal", her).put("totDag", totDag).put("kalender", kal).put("ext", Math.max(0, in.optLong("ext")));
            o.put("kleur", kleur);
            return new Uitkomst(o, "");
        } catch (JSONException e) { return new Uitkomst(null, "datum"); }
    }

    static String nieuwId() {
        String s = Long.toHexString(System.currentTimeMillis());
        int n = (int) (System.nanoTime() & 0xffff);
        return "a" + s + Integer.toHexString(n);
    }

    static Item item(JSONObject o) {
        if (o == null) return null;
        Item it = new Item();
        it.id = o.optString("id");
        it.titel = o.optString("titel");
        it.plek = o.optString("plek");
        it.notitie = o.optString("notitie");
        it.dag = o.optString("dag");
        it.eind = o.optString("eind", it.dag);
        it.van = o.optString("van");
        it.tot = o.optString("tot");
        it.heleDag = o.optBoolean("heleDag");
        it.herhaal = o.optString("herhaal");
        it.totDag = o.optString("totDag");
        it.kalender = o.optString("kalender");
        it.kleur = o.optString("kleur");
        it.naam = o.optString("naam");
        it.ext = o.optLong("ext");
        return it;
    }

    // ---------- herhaling en zoeken ----------

    private static String stap(String start, String her, int idx) {
        if (idx < 0 || start == null) return null;
        if ("d".equals(her)) return Regio.dayAdd(start, idx);
        if ("w".equals(her)) return Regio.dayAdd(start, idx * 7);
        if ("m".equals(her)) return plusMaand(start, idx);
        if ("y".equals(her)) return plusMaand(start, idx * 12);
        return idx == 0 ? start : null;
    }

    private static int indexDichtbij(String start, String her, String doel) {
        if ("d".equals(her)) return Math.max(0, verschil(start, doel));
        if ("w".equals(her)) return Math.max(0, verschil(start, doel) / 7);
        int[] a = ymd(start), b = ymd(doel);
        if (a == null || b == null) return 0;
        int maanden = (b[0] - a[0]) * 12 + (b[1] - a[1]);
        if ("y".equals(her)) return Math.max(0, maanden / 12);
        return Math.max(0, maanden);
    }

    private static int span(Item it) {
        if (!dagOk(it.eind) || it.eind.compareTo(it.dag) <= 0) return 0;
        return Math.max(0, verschil(it.dag, it.eind));
    }

    /** Afspraakmomenten die het bereik raken. Een kort bereik (een weergave) geeft ze allemaal; een lang bereik de dichtstbijzijnde. */
    static List<Item> momenten(Item it, String van, String tot, String vandaag) {
        List<Item> uit = new ArrayList<>();
        if (it == null || !dagOk(it.dag) || !dagOk(van) || !dagOk(tot) || van.compareTo(tot) > 0) return uit;
        int sp = span(it);
        String limiet = dagOk(it.totDag) ? it.totDag : "9999-12-31";
        String her = it.herhaal;
        if (!"d".equals(her) && !"w".equals(her) && !"m".equals(her) && !"y".equals(her)) her = "";
        if (her.isEmpty()) {
            String eind = Regio.dayAdd(it.dag, sp);
            if (eind.compareTo(van) >= 0 && it.dag.compareTo(tot) <= 0 && it.dag.compareTo(limiet) <= 0) uit.add(kopie(it, it.dag, eind));
            return uit;
        }
        boolean kort = verschil(van, tot) <= 45;
        if (kort) {
            int idx = Math.max(0, indexDichtbij(it.dag, her, van) - (sp > 0 ? 1 : 0));
            for (int n = 0; n < MAX_UITKLAP && uit.size() < 80; n++, idx++) {
                String cursor = stap(it.dag, her, idx);
                if (cursor == null || cursor.compareTo(limiet) > 0 || cursor.compareTo(tot) > 0) break;
                String eind = Regio.dayAdd(cursor, sp);
                if (eind.compareTo(van) >= 0) uit.add(kopie(it, cursor, eind));
            }
            return uit;
        }
        int center = indexDichtbij(it.dag, her, dagOk(vandaag) ? vandaag : van);
        for (int dist = 0; dist <= 800 && uit.size() < MAX_PER_REEKS; dist++) {
            int[] kanten = dist == 0 ? new int[]{0} : new int[]{-dist, dist};
            for (int kant : kanten) {
                if (uit.size() >= MAX_PER_REEKS) break;
                int idx = center + kant;
                if (idx < 0) continue;
                String cursor = stap(it.dag, her, idx);
                if (cursor == null || cursor.compareTo(limiet) > 0 || cursor.compareTo(tot) > 0) continue;
                String eind = Regio.dayAdd(cursor, sp);
                if (eind.compareTo(van) < 0) continue;
                uit.add(kopie(it, cursor, eind));
            }
        }
        return uit;
    }

    private static Item kopie(Item it, String dag, String eind) {
        Item o = new Item();
        o.id = it.id; o.titel = it.titel; o.plek = it.plek; o.notitie = it.notitie;
        o.dag = dag; o.eind = eind; o.van = it.van; o.tot = it.tot; o.heleDag = it.heleDag;
        o.herhaal = it.herhaal; o.totDag = it.totDag; o.kalender = it.kalender; o.kleur = it.kleur;
        o.naam = it.naam; o.ext = it.ext;
        return o;
    }

    static String vouw(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).toLowerCase(Locale.ROOT);
        return n.replaceAll("\\p{M}+", "");
    }

    static boolean past(Item it, String[] woorden) {
        if (woorden == null || woorden.length == 0) return true;
        String hay = vouw(it.titel + "\n" + it.plek + "\n" + it.notitie);
        for (String w : woorden) if (w != null && !w.isEmpty() && !hay.contains(w)) return false;
        return true;
    }

    static List<Item> lijst(JSONArray lokaal, JSONArray extern, String van, String tot, String vandaag, String[] woorden) {
        List<Item> uit = new ArrayList<>();
        if (!dagOk(van) || !dagOk(tot)) return uit;
        if (lokaal != null) for (int i = 0; i < lokaal.length(); i++) {
            Item it = item(lokaal.optJSONObject(i));
            if (it == null || it.titel.isEmpty() || !past(it, woorden)) continue;
            uit.addAll(momenten(it, van, tot, vandaag));
        }
        Set<String> heeft = new HashSet<>();
        for (Item it : uit) if (it.ext > 0) heeft.add(it.ext + "|" + it.dag);
        if (extern != null) for (int i = 0; i < extern.length(); i++) {
            Item it = item(extern.optJSONObject(i));
            if (it == null || it.titel.isEmpty() || !past(it, woorden)) continue;
            for (Item m : momenten(it, van, tot, vandaag)) {
                if (m.ext > 0 && heeft.contains(m.ext + "|" + m.dag)) continue;
                if (m.ext > 0) heeft.add(m.ext + "|" + m.dag);
                uit.add(m);
            }
        }
        Collections.sort(uit, (a, b) -> volgorde(a, b));
        if (uit.size() > MAX_ZOEK) {
            // Een lang bereik (zoeken) houdt de momenten het dichtst bij vandaag. De oudste 200
            // zouden een wekelijkse reeks van nu wegdrukken zodra er veel oudere treffers zijn.
            if (verschil(van, tot) > 45 && dagOk(vandaag)) {
                Collections.sort(uit, (a, b) -> {
                    int da = Math.abs(verschil(vandaag, a.dag)), db = Math.abs(verschil(vandaag, b.dag));
                    return da != db ? da - db : volgorde(a, b);
                });
                uit = new ArrayList<>(uit.subList(0, MAX_ZOEK));
                Collections.sort(uit, (a, b) -> volgorde(a, b));
            } else return new ArrayList<>(uit.subList(0, MAX_ZOEK));
        }
        return uit;
    }

    private static int volgorde(Item a, Item b) {
        int c = a.dag.compareTo(b.dag);
        if (c != 0) return c;
        if (a.heleDag != b.heleDag) return a.heleDag ? -1 : 1;
        c = (a.van == null ? "" : a.van).compareTo(b.van == null ? "" : b.van);
        if (c != 0) return c;
        return (a.titel == null ? "" : a.titel).compareToIgnoreCase(b.titel == null ? "" : b.titel);
    }

    static JSONObject toon(JSONArray lokaal, JSONArray extern, String view, String anker, int weekStart, String vandaag) throws JSONException {
        if (!"dag".equals(view) && !"week".equals(view) && !"maand".equals(view) && !"lijst".equals(view)) view = "week";
        if (!dagOk(anker)) anker = dagOk(vandaag) ? vandaag : "1970-01-01";
        String[] r = bereik(view, anker, weekStart);
        JSONObject o = new JSONObject().put("view", view).put("anker", anker).put("van", r[0]).put("tot", r[1]).put("week", isoWeek(anker));
        JSONArray dagen = new JSONArray();
        if (!"lijst".equals(view)) {
            String maand = anker.substring(0, 7);
            for (String d = r[0]; d != null && d.compareTo(r[1]) <= 0; d = Regio.dayAdd(d, 1)) {
                dagen.put(new JSONObject().put("dag", d).put("week", isoWeek(d)).put("buiten", "maand".equals(view) && !d.startsWith(maand)).put("vandaag", d.equals(vandaag)));
            }
        }
        o.put("dagen", dagen);
        JSONArray items = new JSONArray();
        for (Item it : lijst(lokaal, extern, r[0], r[1], vandaag, new String[0])) items.put(json(it));
        o.put("items", items);
        return o;
    }

    static JSONObject json(Item it) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", it.id).put("titel", it.titel).put("plek", it.plek).put("notitie", it.notitie);
        o.put("dag", it.dag).put("eind", it.eind).put("van", it.van).put("tot", it.tot).put("heleDag", it.heleDag);
        o.put("herhaal", it.herhaal == null ? "" : it.herhaal).put("totDag", it.totDag == null ? "" : it.totDag);
        o.put("kalender", it.kalender == null ? "" : it.kalender);
        o.put("naam", it.naam == null ? "" : it.naam).put("ext", it.ext);
        if (kleurOk(it.kleur)) o.put("kleur", it.kleur);
        return o;
    }

    /** Zoekwoorden nadat de periode uit de zin is gehaald. "morgen" is dan geen woord meer. */
    static String[] zoekwoorden(Venster v) {
        if (v == null || v.woorden == null || v.woorden.isEmpty()) return new String[0];
        return v.woorden.split(" ");
    }

    static String[] woorden(String q) {
        String[] ruw = vouw(q).split("[^\\p{L}\\p{N}]+");
        List<String> uit = new ArrayList<>();
        for (String w : ruw) if (w.length() >= 2 && !STOP.contains(w)) uit.add(w);
        return uit.toArray(new String[0]);
    }

    private static final Set<String> STOP;
    static {
        String[] s = ("wat wanneer welke hoeveel zoek toon laat staat stond staan heb had hebben gehad ik je jij mijn mn de het een in op voor met van naar bij uit aan "
                + "als ook nog al er is was niet en te tot over door dit dat deze die agenda kalender afspraak afspraken mijn").split(" ");
        Set<String> set = new HashSet<>();
        Collections.addAll(set, s);
        STOP = Collections.unmodifiableSet(set);
    }

    // ---------- assistent ----------

    private static final Pattern AANMAKEN = Pattern.compile("(?iu)\\b(zet|plan|maak|voeg|herinner|noteer|schrijf)\\b.{0,80}\\b(agenda|kalender)\\b");
    private static final Pattern ONDERWERP = Pattern.compile("(?iu)\\b(agenda|kalender|afspraken|afspraak)\\b");
    private static final Pattern VRAAGVORM = Pattern.compile("(?iu)\\b(wat|wanneer|welke|hoeveel|zoek|toon|laat|staat|stond|heb|had|hebben|planning)\\b");

    static String norm(String q) {
        if (q == null) return "";
        String s = q.toLowerCase(Locale.ROOT).replace('’', '\'');
        int end = s.length();
        while (end > 0 && wit(s.charAt(end - 1))) end--;
        int punct = end;
        while (punct > 0) {
            char c = s.charAt(punct - 1);
            if (c != '?' && c != '!' && c != '.') break;
            punct--;
        }
        if (punct < end) end = punct;
        StringBuilder b = new StringBuilder(end);
        boolean spatie = false;
        for (int i = 0; i < end; i++) {
            char c = s.charAt(i);
            if (wit(c)) { if (!spatie && b.length() > 0) { b.append(' '); spatie = true; } }
            else { b.append(c); spatie = false; }
        }
        int n = b.length();
        if (n > 0 && b.charAt(n - 1) == ' ') b.setLength(n - 1);
        return b.toString();
    }

    /** Zelfde tekens als \\s zonder vlag: spatie, tab, nieuwe regel, verticale tab, form feed, return. */
    private static boolean wit(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r';
    }

    /** Een vraag over de eigen agenda, geen opdracht om iets in de agenda te zetten. */
    static boolean gaatOver(String q) {
        String s = norm(q);
        if (s.isEmpty() || s.length() > 300) return false;
        if (AANMAKEN.matcher(s).find() || !ONDERWERP.matcher(s).find()) return false;
        int n = 0;
        for (String w : s.split(" ")) if (!w.isEmpty()) n++;
        return VRAAGVORM.matcher(s).find() || n >= 3;
    }

    static boolean isVraag(String q) { return gaatOver(q); }

    static Venster venster(String q, String vandaag) {
        Venster v = new Venster();
        String s = norm(q);
        if (!dagOk(vandaag)) vandaag = "1970-01-01";
        String wijdVan = Regio.dayAdd(vandaag, -365 * 15), wijdTot = Regio.dayAdd(vandaag, 365 * 3);
        v.van = wijdVan; v.tot = wijdTot;
        int[] plek = new int[2];
        String[] r = periode(s, vandaag, plek);
        String rest = s;
        if (r != null) {
            v.van = r[0]; v.tot = r[1]; v.lijst = true;
            rest = (s.substring(0, plek[0]) + " " + s.substring(plek[1])).replaceAll("\\s+", " ").trim();
        } else v.lijst = Pattern.compile("(?iu)\\b(wat staat|wat heb|wat had|laat zien|toon)\\b").matcher(s).find() && !s.contains("zoek");
        if (v.lijst && r == null) { v.van = vandaag; v.tot = Regio.dayAdd(vandaag, 14); }
        String[] w = woorden(rest);
        v.woorden = join(w);
        // Zoekwoorden zonder periode: de hele geschiedenis, niet alleen de komende dagen.
        if (w.length > 0) { v.lijst = false; if (r == null) { v.van = wijdVan; v.tot = wijdTot; } }
        return v;
    }

    private static String join(String[] w) {
        StringBuilder b = new StringBuilder();
        for (String s : w) { if (b.length() > 0) b.append(' '); b.append(s); }
        return b.toString();
    }

    /** [van, tot] en in plek de tekens van de periode in de zin, zodat die niet meegaan als zoekwoorden. */
    static String[] periode(String s, String vandaag, int[] plek) {
        if (s == null) return null;
        Matcher m;
        if ((m = Pattern.compile("\\bovermorgen\\b").matcher(s)).find()) return zet(plek, m, Regio.dayAdd(vandaag, 2), Regio.dayAdd(vandaag, 2));
        if ((m = Pattern.compile("\\bgisteren\\b").matcher(s)).find()) return zet(plek, m, Regio.dayAdd(vandaag, -1), Regio.dayAdd(vandaag, -1));
        if ((m = Pattern.compile("\\bvandaag\\b").matcher(s)).find()) return zet(plek, m, vandaag, vandaag);
        if ((m = Pattern.compile("\\bmorgen\\b").matcher(s)).find()) return zet(plek, m, Regio.dayAdd(vandaag, 1), Regio.dayAdd(vandaag, 1));
        if ((m = Pattern.compile("\\b(volgende|vorige|deze|komende)\\s+week\\s+(" + DAG_RE + ")\\b").matcher(s)).find()) {
            int plus = "volgende".equals(m.group(1)) || "komende".equals(m.group(1)) ? 7 : "vorige".equals(m.group(1)) ? -7 : 0;
            String dag = weekdagOp(vandaag, dagIndex(m.group(2)), plus);
            if (dag == null) return null;
            return zet(plek, m, dag, dag);
        }
        if ((m = Pattern.compile("\\b(volgende|vorige|deze|komende)\\s+week\\b").matcher(s)).find()) {
            String[] w = bereik("week", vandaag, 1);
            int plus = "volgende".equals(m.group(1)) || "komende".equals(m.group(1)) ? 7 : "vorige".equals(m.group(1)) ? -7 : 0;
            return zet(plek, m, Regio.dayAdd(w[0], plus), Regio.dayAdd(w[1], plus));
        }
        if ((m = Pattern.compile("\\b(volgende|vorige|deze|komende)\\s+maand\\b").matcher(s)).find()) {
            String eerste = vandaag.substring(0, 8) + "01";
            int plus = "volgende".equals(m.group(1)) || "komende".equals(m.group(1)) ? 1 : "vorige".equals(m.group(1)) ? -1 : 0;
            String van = plusMaand(eerste, plus);
            return zet(plek, m, van, Regio.dayAdd(plusMaand(van, 1), -1));
        }
        if ((m = Pattern.compile("\\bweek(?:nummer)?\\s+(\\d{1,2})\\b").matcher(s)).find()) {
            int nr = Integer.parseInt(m.group(1));
            int jaar = isoJaar(vandaag);
            if ((s.contains("was") || s.contains("vorige")) && nr > isoWeek(vandaag)) {
                if (jaar > 1) jaar--;
            }
            String mon = isoMaandag(jaar, nr);
            if (mon == null) return null;
            return zet(plek, m, mon, Regio.dayAdd(mon, 6));
        }
        if ((m = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b").matcher(s)).find()) {
            String d = m.group(1) + "-" + m.group(2) + "-" + m.group(3);
            if (dagOk(d)) return zet(plek, m, d, d);
        }
        if ((m = Pattern.compile("\\b(\\d{1,2})-(\\d{1,2})-(\\d{4})\\b").matcher(s)).find()) {
            String d = m.group(3) + "-" + two(Integer.parseInt(m.group(2))) + "-" + two(Integer.parseInt(m.group(1)));
            if (dagOk(d)) return zet(plek, m, d, d);
        }
        for (int i = 0; i < MAAND_LANG.length; i++) {
            m = Pattern.compile("\\b" + MAAND_LANG[i] + "(?:\\s+(\\d{4}))?\\b").matcher(s);
            if (!m.find()) continue;
            int jaar = m.group(1) != null ? Integer.parseInt(m.group(1)) : Integer.parseInt(vandaag.substring(0, 4));
            if (jaar < 1 || jaar > 9999) return null;
            int huidig = Integer.parseInt(vandaag.substring(5, 7));
            if (huidig < 1 || huidig > 12) huidig = 1;
            if (m.group(1) == null && (s.contains("was") || s.contains("geweest")) && (i + 1) > huidig) {
                if (jaar > 1) jaar--;
            }
            String van = jaar + "-" + two(i + 1) + "-01";
            if (!dagOk(van)) return null;
            return zet(plek, m, van, Regio.dayAdd(plusMaand(van, 1), -1));
        }
        if ((m = Pattern.compile("\\b(vorige|volgende|deze|komende|aanstaande)\\s+(" + DAG_RE + ")\\b").matcher(s)).find()) {
            String dag = weekdagOp(vandaag, dagIndex(m.group(2)), 0);
            if (dag == null) return null;
            // volgende = de week erna. aanstaande en komende = de eerstvolgende, ook als die nog in deze week valt.
            if ("volgende".equals(m.group(1))) dag = Regio.dayAdd(dag, 7);
            else if ("vorige".equals(m.group(1))) dag = Regio.dayAdd(dag, -7);
            else if (!"deze".equals(m.group(1)) && dag.compareTo(vandaag) < 0) dag = Regio.dayAdd(dag, 7);
            return zet(plek, m, dag, dag);
        }
        if ((m = Pattern.compile("\\b(" + DAG_RE + ")\\b").matcher(s)).find()) {
            String dag = weekdagOp(vandaag, dagIndex(m.group(1)), 0);
            if (dag == null) return null;
            if (dag.compareTo(vandaag) < 0) dag = Regio.dayAdd(dag, 7);
            return zet(plek, m, dag, dag);
        }
        return null;
    }

    private static int dagIndex(String welke) {
        for (int i = 0; i < DAG_LANG.length; i++) if (DAG_LANG[i].equals(welke)) return i;
        return -1;
    }

    /** Die weekdag in de week van vandaag (maandag als begin), plus extra dagen. */
    private static String weekdagOp(String vandaag, int doel, int plus) {
        if (doel < 0) return null;
        String[] w = bereik("week", vandaag, 1);
        if (w == null) return null;
        return Regio.dayAdd(w[0], (doel + 6) % 7 + plus);
    }

    private static String[] zet(int[] plek, Matcher m, String van, String tot) {
        if (plek != null && plek.length >= 2) { plek[0] = m.start(); plek[1] = m.end(); }
        return new String[]{van, tot};
    }

    /** Direct antwoord, of "" als dit geen feitenvraag is. Nederlands, zoals de andere antwoorden van de assistent. */
    static String antwoord(JSONArray lokaal, JSONArray extern, String q, String vandaag) {
        if (!isVraag(q) || !dagOk(vandaag)) return "";
        Venster v = venster(q, vandaag);
        String[] w = zoekwoorden(v);
        List<Item> items = lijst(lokaal, extern, v.van, v.tot, vandaag, w);
        if (items.isEmpty()) return "Niets gevonden.";
        StringBuilder b = new StringBuilder();
        int n = Math.min(items.size(), MAX_ANTWOORD);
        for (int i = 0; i < n; i++) {
            if (i > 0) b.append('\n');
            b.append(regel(items.get(i)));
        }
        if (items.size() > n) b.append("\nen nog ").append(items.size() - n).append('.');
        return b.toString();
    }

    /** Korte lijst voor het taalmodel, ook als de vraag geen direct antwoord is. */
    static String context(JSONArray lokaal, JSONArray extern, String q, String vandaag) {
        if (!gaatOver(q) || !dagOk(vandaag)) return "";
        Venster v = venster(q, vandaag);
        String[] w = zoekwoorden(v);
        List<Item> items = lijst(lokaal, extern, v.van, v.tot, vandaag, w);
        if (items.isEmpty()) return "Geen afspraken in deze periode.";
        StringBuilder b = new StringBuilder();
        int n = Math.min(items.size(), 20);
        for (int i = 0; i < n; i++) b.append("- ").append(regel(items.get(i))).append('\n');
        if (items.size() > n) b.append("en nog ").append(items.size() - n).append(" afspraken.\n");
        return b.toString().trim();
    }

    static String regel(Item it) {
        StringBuilder b = new StringBuilder();
        b.append(WD[jsDow(it.dag)]).append(' ').append(mooi(it.dag));
        if (dagOk(it.eind) && !it.eind.equals(it.dag)) b.append(" – ").append(mooi(it.eind));
        if (!it.heleDag && hmOk(it.van)) {
            b.append(", ").append(it.van);
            if (hmOk(it.tot)) b.append("–").append(it.tot);
        } else if (it.heleDag) b.append(", hele dag");
        b.append(' ').append(it.titel);
        if (!it.plek.isEmpty()) b.append(" (").append(it.plek).append(')');
        if ("d".equals(it.herhaal)) b.append(", elke dag");
        else if ("w".equals(it.herhaal)) b.append(", elke week");
        else if ("m".equals(it.herhaal)) b.append(", elke maand");
        else if ("y".equals(it.herhaal)) b.append(", elk jaar");
        return b.toString();
    }

    private static String mooi(String dag) {
        int[] d = ymd(dag);
        if (d == null) return dag;
        return d[2] + " " + MAAND[d[1] - 1] + " " + d[0];
    }

    /** Tekst voor slim zoeken: één afspraak, zonder iets dat er niet staat. */
    static String document(Item it) {
        if (it == null || it.titel.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        b.append(it.titel).append('\n').append(mooi(it.dag));
        if (!it.heleDag && hmOk(it.van)) b.append(' ').append(it.van);
        if (!it.plek.isEmpty()) b.append('\n').append(it.plek);
        if (!it.notitie.isEmpty()) b.append('\n').append(it.notitie.length() > 500 ? it.notitie.substring(0, 500) : it.notitie);
        return b.toString();
    }
}
