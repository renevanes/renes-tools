package nl.rene.tools;

import android.content.Context;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regio en taal (Instellingen → Regio en taal): tijdsnotatie, datumnotatie, thuis/werk, eenheden, valuta en getallen.
 * De interface (web/src/script/02-regio.js) is de baas over de keuze en geeft hem hier door (Android.regioSet), zodat
 * meldingen, widgets, de Telefoon-skin en leesbare exports precies hetzelfde schrijven als de schermen.
 * Thuis en werk staan alleen hier (sleutel "spots" in regio.xml): niet in de interface-opslag, zodat ze nooit in de
 * Android-backup komen (regio.xml is daar uitgesloten); wel in "Alle appgegevens" (versleuteld, zie AppData).
 * Tijdzone: standaard die van de telefoon; kiest Rene een tijdzone, dan rekent de hele app daarin (de app-zone, zone()):
 * tijden in meldingen en widgets, "vandaag", en tijden uit de opslag ("07:30" is wandklok in de app-zone: wekkers,
 * check-in, herinneringen en planningen rekenen het moment met toMillis/nextAt). Een onbekende zone: de telefoon.
 * Tekst voor het taalmodel blijft vast nl-NL (niet via deze klasse).
 * De standaard is hoe de app het altijd deed: Nederlands, 24 uur, 3-11-2026, euro, km, °C, 1024 per stap.
 * Alles hieronder dat een Cfg krijgt is puur (getest in RegioTest); de varianten met een Context lezen de keuze.
 */
final class Regio {

    private Regio() { }

    /** Eigen bestand: een kopie van de keuze ("regio") en thuis/werk ("spots"). Zie AppData en backup_rules.xml. */
    static final String PREFS = "regio";
    static final String KEY = "regio";
    static final String SPOTS = "spots";
    private static final int MAX = 8 * 1024;
    /** Smalle vaste spatie voor duizendtallen (breekt een getal nooit af). */
    static final String NNBSP = " ";

    /** Talen: een register, zodat er later talen bij kunnen. Nu alleen Nederlands. */
    static final String[][] LANGS = {{"nl", "Nederlands", "nl", "NL"}};

    /** Een gecontroleerde keuze; na het maken verandert hij niet meer. */
    static final class Cfg {
        final String lang, clock, date, place, dist, temp, speed, cur, grp, homeName, workName;
        /** Gekozen tijdzone ("" = volg de telefoon); kan onbekend zijn op deze telefoon (dan geldt de telefoon, zie zone). */
        final String tz;
        final int week, size;
        final char dec;
        private final double[] home, work;

        Cfg(String lang, String clock, String date, String place, int week, String dist, String temp, String speed, int size,
            String cur, char dec, String grp, double[] home, String homeName, double[] work, String workName) {
            this(lang, clock, date, "", place, week, dist, temp, speed, size, cur, dec, grp, home, homeName, work, workName);
        }

        Cfg(String lang, String clock, String date, String tz, String place, int week, String dist, String temp, String speed, int size,
            String cur, char dec, String grp, double[] home, String homeName, double[] work, String workName) {
            this.tz = tz == null ? "" : tz;
            this.lang = lang; this.clock = clock; this.date = date; this.place = place; this.week = week; this.dist = dist;
            this.temp = temp; this.speed = speed; this.size = size; this.cur = cur; this.dec = dec; this.grp = grp;
            this.home = home == null ? null : home.clone(); this.homeName = homeName;
            this.work = work == null ? null : work.clone(); this.workName = workName;
        }

        Locale locale() {
            for (String[] l : LANGS) if (l[0].equals(lang)) return new Locale(l[2], l[3]);
            return new Locale("nl", "NL");
        }
        boolean h12() { return "12".equals(clock); }
        boolean miles() { return "mi".equals(dist); }
        boolean fahrenheit() { return "f".equals(temp); }
        /** Thuis als {lat, lon} (een kopie), of null. */
        double[] home() { return home == null ? null : home.clone(); }
        double[] work() { return work == null ? null : work.clone(); }
    }

    static final Cfg DEFAULT = parse("", "");

    /** Wat er laatst gelezen is: één onveranderlijk paar (tekst en keuze), zodat twee threads nooit een half paar zien. */
    private static final class Held {
        final String key; final Cfg cfg;
        Held(String key, Cfg cfg) { this.key = key; this.cfg = cfg; }
    }
    private static volatile Held held = new Held("\u0000", DEFAULT);

    static Cfg parse(String json) { return parse(json, ""); }

    /** Uit JSON (zoals de interface hem bewaart) en thuis/werk; onbekende of kapotte waarden worden de standaard. */
    static Cfg parse(String json, String spots) {
        String lang = "nl", clock = "24", date = "dmy", place = "auto", dist = "km", temp = "c", speed = "kmh", cur = "EUR", grp = ".", tz = "";
        int week = 1, size = 1024;
        char dec = ',';
        if (json != null && !json.isEmpty() && json.length() <= MAX) {
            try {
                JSONObject o = new JSONObject(json);
                for (String[] l : LANGS) if (l[0].equals(o.optString("lang"))) lang = l[0];
                clock = "12".equals(o.optString("clock")) ? "12" : "24";
                String v = o.optString("date", "dmy");
                date = v.equals("dmon") || v.equals("iso") ? v : "dmy";
                int w = o.optInt("week", 1);
                week = w == 0 || w == 6 ? w : 1;
                dist = "mi".equals(o.optString("dist")) ? "mi" : "km";
                temp = "f".equals(o.optString("temp")) ? "f" : "c";
                speed = "mph".equals(o.optString("speed")) ? "mph" : "kmh";
                size = "1000".equals(o.optString("size")) ? 1000 : 1024;
                v = o.optString("cur", "EUR");
                cur = v.matches("[A-Z]{3}") ? v : "EUR";
                dec = ".".equals(o.optString("dec")) ? '.' : ',';
                v = o.has("grp") ? o.optString("grp", ".") : ".";
                grp = v.equals(" ") || v.isEmpty() || v.equals(",") || v.equals(".") ? v : ".";
                if (grp.equals(String.valueOf(dec))) grp = dec == ',' ? "." : ",";
                v = o.optString("place", "auto");
                place = v.matches("[a-z\\-]{1,20}") ? v : "auto";
                v = o.optString("tz", "");
                tz = TZ_RE.matcher(v).matches() ? v : "";
            } catch (Exception e) {
                lang = "nl"; clock = "24"; date = "dmy"; place = "auto"; dist = "km"; temp = "c"; speed = "kmh"; cur = "EUR"; grp = "."; tz = "";
                week = 1; size = 1024; dec = ',';
            }
        }
        double[] home = null, work = null;
        String hn = "", wn = "";
        if (spots != null && !spots.isEmpty() && spots.length() <= MAX) {
            try {
                JSONObject s = new JSONObject(spots);
                home = spot(s.optJSONObject("home")); work = spot(s.optJSONObject("work"));
                if (home != null) hn = cut(s.getJSONObject("home").optString("name", ""), 60);
                if (work != null) wn = cut(s.getJSONObject("work").optString("name", ""), 60);
            } catch (Exception ignored) { home = null; work = null; }
        }
        return new Cfg(lang, clock, date, tz, place, week, dist, temp, speed, size, cur, dec, grp, home, hn, work, wn);
    }

    private static double[] spot(JSONObject p) {
        if (p == null || !p.has("lat") || !p.has("lon")) return null;
        double lat = p.optDouble("lat"), lon = p.optDouble("lon");
        if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
        return new double[]{lat, lon};
    }

    /** Opnieuw opgebouwde, gecontroleerde keuze om te bewaren (zonder thuis en werk); null als het geen object is. */
    static String clean(String json) {
        if (json == null || json.length() > MAX) return null;
        try { new JSONObject(json); } catch (Exception e) { return null; }
        Cfg c = parse(json, "");
        try {
            return new JSONObject().put("lang", c.lang).put("clock", c.clock).put("date", c.date).put("week", c.week).put("tz", c.tz).put("place", c.place)
                    .put("dist", c.dist).put("temp", c.temp).put("speed", c.speed).put("size", String.valueOf(c.size))
                    .put("cur", c.cur).put("dec", String.valueOf(c.dec)).put("grp", c.grp).toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Thuis en werk, gecontroleerd en opnieuw opgebouwd ({home, work}, elk met naam, adres en plek); null als het niet klopt. */
    static String cleanSpots(String json) {
        if (json == null || json.length() > MAX) return null;
        try {
            JSONObject in = new JSONObject(json), o = new JSONObject();
            for (String w : new String[]{"home", "work"}) {
                JSONObject p = in.optJSONObject(w);
                double[] s = spot(p);
                if (s == null) continue;
                o.put(w, new JSONObject().put("name", cut(p.optString("name"), 60)).put("addr", cut(p.optString("addr"), 200)).put("lat", s[0]).put("lon", s[1]));
            }
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
        return true;
    }

    static boolean saveSpots(Context c, String json) {
        String s = cleanSpots(json);
        if (s == null) return false;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(SPOTS, s).apply();
        return true;
    }

    static String json(Context c) {
        try { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, ""); } catch (Throwable t) { return ""; }
    }

    static String spots(Context c) {
        try { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SPOTS, "{}"); } catch (Throwable t) { return "{}"; }
    }

    /** Wat de Telefoon-skin nodig heeft: alleen de notaties, geen plekken. */
    static String skinJson(Context ctx) {
        Cfg c = cfg(ctx);
        try {
            return new JSONObject().put("clock", c.clock).put("date", c.date).put("temp", c.temp).put("dec", String.valueOf(c.dec)).put("grp", c.grp)
                    .put("tz", tzOk(c.tz) ? c.tz : "").toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** De keuze van nu (onthouden zolang hij niet verandert). */
    static Cfg cfg(Context c) {
        if (c == null) return DEFAULT;
        String j = json(c), s = spots(c), k = j + "\u0000" + s;
        Held h = held;
        if (h.key.equals(k)) return h.cfg;
        Cfg n = parse(j, s);
        held = new Held(k, n);
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

    /** In de app-zone (zie zone). */
    static SimpleDateFormat df(Cfg c, String p) {
        SimpleDateFormat f = new SimpleDateFormat(pattern(c, p), c.locale());
        f.setTimeZone(zone(c));
        return f;
    }

    /** Voor wat Rene leest (meldingen, widgets, leesbare exports); niet voor bestandsnamen of opslag. */
    static SimpleDateFormat df(Context ctx, String p) { return df(cfg(ctx), p); }

    static String fmt(Context ctx, String p, long t) { return df(ctx, p).format(new Date(t)); }

    /** "14:05" of "2:05 p.m." */
    static String time(Context ctx, long t) { return fmt(ctx, "HH:mm", t); }

    // ---------------------------------------------------------------- tijdzone (de app-zone)

    static final Pattern TZ_RE = Pattern.compile("[A-Za-z][A-Za-z0-9_+\\-/]{0,63}");

    /** Onthouden: TimeZone.getTimeZone is niet goedkoop. Eén onveranderlijk paar (id, zone); de zone zelf wordt gekloond. */
    private static final class Zone {
        final String id; final TimeZone tz; final boolean ok;
        Zone(String id, TimeZone tz, boolean ok) { this.id = id; this.tz = tz; this.ok = ok; }
    }
    private static volatile Zone lastZone = new Zone("\u0000", null, false);

    /** Kent deze telefoon de zone precies zo (hoofdletters tellen, geen GMT voor onbekend)? Zoals rgTzOk in 02-regio.js. */
    static boolean tzOk(String id) {
        if (id == null || !TZ_RE.matcher(id).matches()) return false;
        Zone z = lastZone;
        if (z.id.equals(id)) return z.ok;
        TimeZone t = TimeZone.getTimeZone(id);
        boolean ok = t != null && id.equals(t.getID());
        lastZone = new Zone(id, ok ? t : null, ok);
        return ok;
    }

    /** De app-zone: de gekozen zone als de telefoon die kent, anders die van de telefoon. Een eigen kopie. */
    static TimeZone zone(Cfg c) {
        if (c != null && !c.tz.isEmpty() && tzOk(c.tz)) {
            Zone z = lastZone;
            if (z.id.equals(c.tz) && z.tz != null) return (TimeZone) z.tz.clone();
            return TimeZone.getTimeZone(c.tz);
        }
        return TimeZone.getDefault();
    }

    static TimeZone zone(Context ctx) { return zone(cfg(ctx)); }

    /** Volgt de app de telefoon (geen eigen, bekende zone gekozen)? */
    static boolean followsPhone(Cfg c) { return c.tz.isEmpty() || !tzOk(c.tz); }

    /** Een Calendar in de app-zone op tijdstip t. */
    static Calendar cal(TimeZone tz, long t) {
        Calendar k = Calendar.getInstance(tz);
        k.setTimeInMillis(t);
        return k;
    }

    static Calendar cal(Context ctx, long t) { return cal(zone(ctx), t); }

    private static String two(int n) { return n < 10 ? "0" + n : String.valueOf(n); }

    /** "2026-11-03": de dag van tijdstip t in zone tz. */
    static String dayKey(TimeZone tz, long t) {
        Calendar k = cal(tz, t);
        return k.get(Calendar.YEAR) + "-" + two(k.get(Calendar.MONTH) + 1) + "-" + two(k.get(Calendar.DAY_OF_MONTH));
    }

    static String dayKey(Context ctx, long t) { return dayKey(zone(ctx), t); }

    /** Valt t op dezelfde dag als now (in zone tz)? */
    static boolean isToday(TimeZone tz, long t, long now) { return dayKey(tz, t).equals(dayKey(tz, now)); }

    static boolean isToday(Context ctx, long t) { TimeZone z = zone(ctx); return isToday(z, t, System.currentTimeMillis()); }

    /** Kalenderrekenen: "2026-11-03" plus n dagen (zonder tijdzone); null als het geen datum is. */
    static String dayAdd(String key, int n) {
        int[] d = ymd(key);
        if (d == null) return null;
        Calendar k = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        k.clear();
        k.set(d[0], d[1] - 1, d[2], 12, 0, 0);
        k.add(Calendar.DAY_OF_MONTH, n);
        return k.get(Calendar.YEAR) + "-" + two(k.get(Calendar.MONTH) + 1) + "-" + two(k.get(Calendar.DAY_OF_MONTH));
    }

    /** Weekdag van een datum als Calendar.DAY_OF_WEEK (1 = zondag). */
    static int weekday(String key) {
        int[] d = ymd(key);
        if (d == null) return 0;
        Calendar k = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        k.clear();
        k.set(d[0], d[1] - 1, d[2], 12, 0, 0);
        return k.get(Calendar.DAY_OF_WEEK);
    }

    private static final Pattern YMD = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
    private static final Pattern HM = Pattern.compile("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?");

    private static int[] ymd(String key) {
        if (key == null) return null;
        Matcher m = YMD.matcher(key);
        if (!m.matches()) return null;
        int y = Integer.parseInt(m.group(1)), mo = Integer.parseInt(m.group(2)), d = Integer.parseInt(m.group(3));
        return mo < 1 || mo > 12 || d < 1 || d > 31 ? null : new int[]{y, mo, d};
    }

    /**
     * Wandklok (ms alsof het UTC is) → tijdstip in zone tz. Bestaat de tijd twee keer (de klok gaat terug), dan de eerste;
     * bestaat hij niet (de klok springt vooruit), dan schuift hij mee met de sprong: 02:30 wordt 03:30, zoals een wekker
     * en Checkin. Precies zoals rgWallToMs in 02-regio.js.
     */
    static long wallToMillis(TimeZone tz, long w) {
        int oa = tz.getOffset(w - 86_400_000L), ob = tz.getOffset(w + 86_400_000L);
        long ta = w - oa, tb = w - ob;
        boolean va = tz.getOffset(ta) == oa, vb = tz.getOffset(tb) == ob;
        if (va && vb) return Math.min(ta, tb);
        if (vb && !va) return tb;
        return ta; // geldig, of in het gat: met de tijd van vóór de sprong (komt na de sprong uit)
    }

    /** "2026-11-03" en "07:30" (wandklok in zone tz) → tijdstip in ms; -1 als het niet klopt. */
    static long toMillis(TimeZone tz, String date, String hm) {
        int[] d = ymd(date);
        Matcher m = HM.matcher(hm == null || hm.isEmpty() ? "00:00" : hm);
        if (d == null || !m.matches()) return -1;
        int h = Integer.parseInt(m.group(1)), mi = Integer.parseInt(m.group(2)), s = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
        if (h > 23 || mi > 59 || s > 59) return -1;
        return toMillis(tz, d[0], d[1], d[2], h, mi, s);
    }

    /** Jaar, maand (1–12), dag, uur, minuut, seconde (wandklok in zone tz) → tijdstip in ms. */
    static long toMillis(TimeZone tz, int y, int mo, int d, int h, int mi, int s) {
        Calendar u = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        u.clear();
        u.set(y, mo - 1, d, h, mi, s);
        return wallToMillis(tz, u.getTimeInMillis());
    }

    static long toMillis(Context ctx, String date, String hm) { return toMillis(zone(ctx), date, hm); }

    /** Begin van de dag (00:00 in zone tz) waarin t valt. */
    static long startOfDay(TimeZone tz, long t) { return toMillis(tz, dayKey(tz, t), "00:00"); }

    /**
     * Eerstvolgend tijdstip na `after` waarop het in zone tz hour:minute is. days: bit 0 = maandag … bit 6 = zondag;
     * 0 = elke dag. 0 als er binnen 8 dagen niets is. (Zoals rgNextAt in 02-regio.js.)
     */
    static long nextAt(TimeZone tz, int hour, int minute, int days, long after) {
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return 0;
        String k0 = dayKey(tz, after);
        for (int i = 0; i < 9; i++) {
            String k = dayAdd(k0, i);
            long t = toMillis(tz, k, two(hour) + ":" + two(minute));
            if (t <= after) continue;
            int dow = (weekday(k) + 5) % 7; // maandag = 0
            if (days == 0 || (days & (1 << dow)) != 0) return t;
        }
        return 0;
    }

    /** Minuut van de dag (0–1439) van t in zone tz. */
    static int minuteOfDay(TimeZone tz, long t) { Calendar k = cal(tz, t); return k.get(Calendar.HOUR_OF_DAY) * 60 + k.get(Calendar.MINUTE); }

    // ---------------------------------------------------------------- getallen

    /** Getal met vaste decimalen en de gekozen tekens: 1.234,5 (group = false: zonder duizendtallen). */
    static String num(Cfg c, double v, int digits, boolean group) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "";
        String s = String.format(Locale.ROOT, "%." + Math.max(0, digits) + "f", Math.abs(v));
        int dot = s.indexOf('.');
        String in = dot < 0 ? s : s.substring(0, dot), fr = dot < 0 ? "" : s.substring(dot + 1);
        String g = " ".equals(c.grp) ? NNBSP : c.grp;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < in.length(); i++) {
            if (group && i > 0 && (in.length() - i) % 3 == 0) b.append(g);
            b.append(in.charAt(i));
        }
        boolean zero = true;
        for (char ch : s.toCharArray()) if (ch >= '1' && ch <= '9') { zero = false; break; }
        return (v < 0 && !zero ? "-" : "") + b + (fr.isEmpty() ? "" : c.dec + fr);
    }

    static String num(Cfg c, double v, int digits) { return num(c, v, digits, true); }

    static String num(Context ctx, double v, int digits) { return num(cfg(ctx), v, digits, true); }

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

    /** Bedrag: "€ 1.234,56" (cur = munt van het bedrag zelf, of null voor de standaardvaluta); negatief met "− ", afgerond nul zonder. */
    static String money(Cfg c, double v, String cur) {
        String k = cur == null || cur.isEmpty() ? c.cur : cur;
        int dig = "JPY".equals(k) ? 0 : 2;
        String n = num(c, Math.abs(v), dig);
        boolean zero = true;
        for (char ch : n.toCharArray()) if (ch >= '1' && ch <= '9') { zero = false; break; }
        return (v < 0 && !zero ? "− " : "") + symbol(k) + " " + n;
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

    /** Opslag zoals de app het altijd schreef (zonder duizendtallen): "12 kB", "340,0 MB" (mbDigits), "1,25 GB"; 1024 of 1000 per stap. */
    static String size(Cfg c, long b, int mbDigits, int gbDigits) {
        double k = c.size, m = k * k, g = m * k;
        if (b < m) return num(c, Math.max(1, Math.round(b / k)), 0, false) + " kB";
        if (b < g) return num(c, b / m, mbDigits, false) + " MB";
        return num(c, b / g, gbDigits, false) + " GB";
    }

    static String size(Context ctx, long b, int mbDigits, int gbDigits) { return size(cfg(ctx), b, mbDigits, gbDigits); }

    /** Altijd in GB met 1000 per stap (modelgroottes zoals Hugging Face ze noemt), met het gekozen decimaalteken. */
    static String gbDecimal(Context ctx, long b) { return num(cfg(ctx), b / 1e9, 1, false) + " GB"; }

    // ---------------------------------------------------------------- locatie

    /** Thuis als {lat, lon} (een kopie), of null. */
    static double[] home(Context ctx) { return cfg(ctx).home(); }

    /**
     * De standaardplaats als {lat, lon} (thuis, werk of een vaste plaats), of null bij "Mijn locatie".
     * Vaste plaatsen: dezelfde lijst als RG_CITIES in 02-regio.js.
     */
    static double[] place(Context ctx) { return place(cfg(ctx)); }

    static double[] place(Cfg c) {
        if ("home".equals(c.place)) return c.home();
        if ("work".equals(c.place)) return c.work();
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
