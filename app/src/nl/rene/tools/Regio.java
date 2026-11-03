package nl.rene.tools;

import android.content.Context;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regio en taal (Instellingen → Regio en taal): tijdsnotatie, datumnotatie, tijdzone, thuis/werk, eenheden, valuta en
 * getallen. De interface (web/src/script/02-regio.js) is de baas over de keuze en geeft hem hier door (Android.regioSet),
 * zodat meldingen, widgets, de Telefoon-skin en leesbare exports precies hetzelfde schrijven als de schermen.
 * De standaard is hoe de app het altijd deed: Nederlands, 24 uur, 3-11-2026, euro, km, °C, 1024 per stap.
 * Alles hieronder dat een Cfg krijgt is puur (getest in RegioTest); de varianten met een Context lezen de keuze.
 */
final class Regio {

    private Regio() { }

    /** Eigen bestand met een kopie van de keuze (met thuis en werk); de keuze zelf gaat mee met de interface-instellingen. Zie AppData. */
    static final String PREFS = "regio";
    static final String KEY = "regio";
    private static final int MAX = 8 * 1024;

    /** Talen: een register, zodat er later talen bij kunnen. Nu alleen Nederlands. */
    static final String[][] LANGS = {{"nl", "Nederlands", "nl", "NL"}};

    /** Een gecontroleerde keuze. */
    static final class Cfg {
        String lang = "nl", clock = "24", date = "dmy", tz = "", place = "auto";
        int week = 1;
        String dist = "km", temp = "c", vol = "l", speed = "kmh", cur = "EUR";
        int size = 1024;
        char dec = ',';
        String grp = ".";
        double[] home, work;
        String homeName = "", workName = "";

        Locale locale() {
            for (String[] l : LANGS) if (l[0].equals(lang)) return new Locale(l[2], l[3]);
            return new Locale("nl", "NL");
        }
        boolean h12() { return "12".equals(clock); }
        boolean miles() { return "mi".equals(dist); }
        boolean fahrenheit() { return "f".equals(temp); }
    }

    static final Cfg DEFAULT = new Cfg();

    private static volatile String lastJson = null;
    private static volatile Cfg last = DEFAULT;

    /** Uit JSON (zoals de interface hem bewaart); onbekende of kapotte waarden worden de standaard. */
    static Cfg parse(String json) {
        Cfg c = new Cfg();
        if (json == null || json.isEmpty() || json.length() > MAX) return c;
        try {
            JSONObject o = new JSONObject(json);
            for (String[] l : LANGS) if (l[0].equals(o.optString("lang"))) c.lang = l[0];
            String v = o.optString("clock", "24");
            c.clock = "12".equals(v) ? "12" : "24";
            v = o.optString("date", "dmy");
            c.date = v.equals("dmon") || v.equals("iso") ? v : "dmy";
            int w = o.optInt("week", 1);
            c.week = w == 0 || w == 6 ? w : 1;
            v = o.optString("tz", "");
            if (!v.isEmpty() && v.length() <= 64 && v.matches("[A-Za-z0-9_+\\-/]+") && (v.equals("UTC") || !"GMT".equals(TimeZone.getTimeZone(v).getID()))) c.tz = v;
            c.dist = "mi".equals(o.optString("dist")) ? "mi" : "km";
            c.temp = "f".equals(o.optString("temp")) ? "f" : "c";
            c.vol = "gal".equals(o.optString("vol")) ? "gal" : "l";
            c.speed = "mph".equals(o.optString("speed")) ? "mph" : "kmh";
            c.size = "1000".equals(o.optString("size")) ? 1000 : 1024;
            v = o.optString("cur", "EUR");
            c.cur = v.matches("[A-Z]{3}") ? v : "EUR";
            c.dec = ".".equals(o.optString("dec")) ? '.' : ',';
            v = o.has("grp") ? o.optString("grp", ".") : ".";
            c.grp = v.equals(" ") || v.isEmpty() || v.equals(",") || v.equals(".") ? v : ".";
            if (c.grp.equals(String.valueOf(c.dec))) c.grp = c.dec == ',' ? "." : ",";
            c.home = spot(o.optJSONObject("home"));
            c.work = spot(o.optJSONObject("work"));
            if (c.home != null) c.homeName = o.getJSONObject("home").optString("name", "");
            if (c.work != null) c.workName = o.getJSONObject("work").optString("name", "");
            v = o.optString("place", "auto");
            c.place = v.matches("[a-z\\-]{1,20}") ? v : "auto";
        } catch (Exception e) {
            return new Cfg();
        }
        return c;
    }

    private static double[] spot(JSONObject p) {
        if (p == null || !p.has("lat") || !p.has("lon")) return null;
        double lat = p.optDouble("lat"), lon = p.optDouble("lon");
        if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
        return new double[]{lat, lon};
    }

    /** Opnieuw opgebouwde, gecontroleerde JSON om te bewaren; null als het geen object is. */
    static String clean(String json) {
        if (json == null || json.length() > MAX) return null;
        try { new JSONObject(json); } catch (Exception e) { return null; }
        Cfg c = parse(json);
        try {
            JSONObject in = new JSONObject(json), o = new JSONObject();
            o.put("lang", c.lang).put("clock", c.clock).put("date", c.date).put("week", c.week).put("tz", c.tz).put("place", c.place)
                    .put("dist", c.dist).put("temp", c.temp).put("vol", c.vol).put("speed", c.speed).put("size", String.valueOf(c.size))
                    .put("cur", c.cur).put("dec", String.valueOf(c.dec)).put("grp", c.grp);
            if (c.home != null) o.put("home", new JSONObject().put("name", cut(in.getJSONObject("home").optString("name"), 60)).put("addr", cut(in.getJSONObject("home").optString("addr"), 200)).put("lat", c.home[0]).put("lon", c.home[1]));
            if (c.work != null) o.put("work", new JSONObject().put("name", cut(in.getJSONObject("work").optString("name"), 60)).put("addr", cut(in.getJSONObject("work").optString("addr"), 200)).put("lat", c.work[0]).put("lon", c.work[1]));
            return o.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String cut(String s, int n) { return s == null ? "" : s.length() > n ? s.substring(0, n) : s; }

    static boolean save(Context c, String json) {
        String s = clean(json);
        if (s == null) return false;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, s).apply();
        lastJson = null;
        return true;
    }

    static String json(Context c) {
        try { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, ""); } catch (Throwable t) { return ""; }
    }

    /** De keuze van nu (onthouden zolang hij niet verandert). */
    static Cfg cfg(Context c) {
        if (c == null) return DEFAULT;
        String j = json(c);
        if (j.equals(lastJson)) return last;
        Cfg n = parse(j);
        last = n; lastJson = j;
        return n;
    }

    // ---------------------------------------------------------------- datum en tijd

    private static final Pattern TIME = Pattern.compile("HH(:mm(?::ss)?)");
    private static final Pattern FULL_TEXT = Pattern.compile("^d MMMM? yyyy");
    private static final Pattern FULL_NUM = Pattern.compile("^d{1,2}-M{1,2}-yyyy");

    /**
     * Een SimpleDateFormat-patroon (zoals de app het altijd schreef, met HH voor 24 uur) aangepast aan de keuze:
     * 12-uurs klok (h:mm a), en een volledige datum zonder dagnaam volgens de datumnotatie (31-10-2026, 31 okt 2026 of
     * 2026-10-31). Patronen met een dagnaam of zonder jaar blijven uitgeschreven, net als in de interface.
     */
    static String pattern(Cfg c, String p) {
        String out = p;
        if (!out.contains("E")) {
            Matcher num = FULL_NUM.matcher(out), txt = FULL_TEXT.matcher(out);
            if ("iso".equals(c.date)) {
                if (num.find()) out = "yyyy-MM-dd" + out.substring(num.end());
                else if (txt.find()) out = "yyyy-MM-dd" + out.substring(txt.end());
            } else if ("dmon".equals(c.date) && num.find()) out = "d MMM yyyy" + out.substring(num.end());
        }
        if (c.h12()) {
            Matcher m = TIME.matcher(out);
            if (m.find()) out = out.substring(0, m.start()) + "h" + m.group(1) + " a" + out.substring(m.end());
        }
        return out;
    }

    static SimpleDateFormat df(Cfg c, String p) {
        SimpleDateFormat f = new SimpleDateFormat(pattern(c, p), c.locale());
        if (!c.tz.isEmpty()) f.setTimeZone(TimeZone.getTimeZone(c.tz));
        return f;
    }

    /** Voor wat Rene leest (meldingen, widgets, leesbare exports); niet voor bestandsnamen of opslag. */
    static SimpleDateFormat df(Context ctx, String p) { return df(cfg(ctx), p); }

    static String fmt(Context ctx, String p, long t) { return df(ctx, p).format(new Date(t)); }

    /** "14:05" of "2:05 p.m." */
    static String time(Context ctx, long t) { return fmt(ctx, "HH:mm", t); }

    // ---------------------------------------------------------------- getallen

    /** Getal met vaste decimalen en de gekozen tekens: 1.234,5 */
    static String num(Cfg c, double v, int digits) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "";
        String s = String.format(Locale.ROOT, "%." + Math.max(0, digits) + "f", Math.abs(v));
        int dot = s.indexOf('.');
        String in = dot < 0 ? s : s.substring(0, dot), fr = dot < 0 ? "" : s.substring(dot + 1);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < in.length(); i++) {
            if (i > 0 && (in.length() - i) % 3 == 0) b.append(c.grp);
            b.append(in.charAt(i));
        }
        boolean zero = true;
        for (char ch : s.toCharArray()) if (ch >= '1' && ch <= '9') { zero = false; break; }
        return (v < 0 && !zero ? "-" : "") + b + (fr.isEmpty() ? "" : c.dec + fr);
    }

    static String num(Context ctx, double v, int digits) { return num(cfg(ctx), v, digits); }

    static String symbol(String cur) {
        switch (cur) {
            case "EUR": return "€";
            case "USD": case "CAD": case "AUD": case "SRD": return "$";
            case "GBP": return "£";
            case "JPY": case "CNY": return "¥";
            case "INR": return "₹";
            case "TRY": return "₺";
            case "PLN": return "zł";
            case "CZK": return "Kč";
            case "DKK": case "SEK": case "NOK": return "kr";
            case "ANG": return "ƒ";
            case "ZAR": return "R";
            case "BRL": return "R$";
            default: return cur;
        }
    }

    /** Bedrag: "€ 1.234,56" (cur = munt van het bedrag zelf, of null voor de gekozen munt); negatief met "− ". */
    static String money(Cfg c, double v, String cur) {
        String k = cur == null || cur.isEmpty() ? c.cur : cur;
        int dig = "JPY".equals(k) ? 0 : 2;
        String s = symbol(k) + " " + num(c, Math.abs(v), dig);
        return (v < 0 && Math.abs(v) >= (dig == 0 ? 0.5 : 0.005) ? "− " : "") + s;
    }

    // ---------------------------------------------------------------- eenheden

    /** "12,50 km" of "7,77 mi" (meters in) */
    static String dist(Cfg c, double meters, int digits) {
        return num(c, meters / (c.miles() ? 1609.344 : 1000.0), digits) + (c.miles() ? " mi" : " km");
    }

    static String dist(Context ctx, double meters, int digits) { return dist(cfg(ctx), meters, digits); }

    /** "18°" (kort, zoals het weer) uit graden Celsius; null of geen getal = "–" */
    static String deg(Cfg c, Object celsius) {
        if (!(celsius instanceof Number)) return "–";
        double v = ((Number) celsius).doubleValue();
        if (c.fahrenheit()) v = v * 9 / 5 + 32;
        return num(c, Math.round(v), 0) + "°";
    }

    static String deg(Context ctx, Object celsius) { return deg(cfg(ctx), celsius); }

    /** Opslag zoals de app het altijd schreef: "12 kB", "340,0 MB" (mbDigits), "1,25 GB" (gbDigits); 1024 of 1000 per stap. */
    static String size(Cfg c, long b, int mbDigits, int gbDigits) {
        double k = c.size, m = k * k, g = m * k;
        if (b < m) return num(c, Math.max(1, Math.round(b / k)), 0) + " kB";
        if (b < g) return num(c, b / m, mbDigits) + " MB";
        return num(c, b / g, gbDigits) + " GB";
    }

    static String size(Context ctx, long b, int mbDigits, int gbDigits) { return size(cfg(ctx), b, mbDigits, gbDigits); }

    // ---------------------------------------------------------------- locatie

    /** Thuis als {lat, lon}, of null. */
    static double[] home(Context ctx) { return cfg(ctx).home; }

    /**
     * De standaardplaats als {lat, lon} (thuis, werk of een vaste plaats), of null bij "Mijn locatie".
     * Vaste plaatsen: dezelfde lijst als RG_CITIES in 02-regio.js.
     */
    static double[] place(Context ctx) { return place(cfg(ctx)); }

    static double[] place(Cfg c) {
        if ("home".equals(c.place)) return c.home;
        if ("work".equals(c.place)) return c.work;
        for (Object[] p : CITIES) if (p[0].equals(c.place)) return new double[]{(Double) p[2], (Double) p[3]};
        return null;
    }

    static String placeName(Cfg c) {
        if ("home".equals(c.place)) return c.home == null ? "" : c.homeName.isEmpty() ? "Thuis" : c.homeName;
        if ("work".equals(c.place)) return c.work == null ? "" : c.workName.isEmpty() ? "Werk" : c.workName;
        for (Object[] p : CITIES) if (p[0].equals(c.place)) return (String) p[1];
        return "";
    }

    static final Object[][] CITIES = {
            {"rotterdam", "Rotterdam", 51.92, 4.48}, {"amsterdam", "Amsterdam", 52.37, 4.9}, {"den-haag", "Den Haag", 52.08, 4.31}, {"utrecht", "Utrecht", 52.09, 5.12},
            {"eindhoven", "Eindhoven", 51.44, 5.48}, {"groningen", "Groningen", 53.22, 6.57}, {"maastricht", "Maastricht", 50.85, 5.69}, {"middelburg", "Middelburg", 51.5, 3.61},
            {"leeuwarden", "Leeuwarden", 53.2, 5.8}, {"enschede", "Enschede", 52.22, 6.89}, {"brussel", "Brussel", 50.85, 4.35}};
}
