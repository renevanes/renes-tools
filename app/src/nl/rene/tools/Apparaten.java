package nl.rene.tools;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Apparaten in de buurt: de zuivere rekenregels (geen Android, geen org.json), zodat ze op een gewone JVM getest kunnen
 * worden (tests/jvm/…/ApparatenTest.java). Bluetooth-advertenties ontleden (maker, soort, trackers), afstand uit de
 * signaalsterkte, wifi-kanalen en drukte, SSDP/UPnP en NetBIOS, het eigen /24-subnet, de regel "deze tracker reist met
 * je mee" en de geschiedenis (vaak in de buurt) met bewaartermijn.
 *
 * Alles wat hier gebeurt, gebeurt op de telefoon: er staat geen opzoektabel op internet, de tabel met makers zit in de app.
 */
final class Apparaten {

    private Apparaten() { }

    // ---------- makers (Bluetooth SIG company identifiers, de gangbare) ----------

    static final Map<Integer, String> COMPANIES;
    static {
        Map<Integer, String> m = new HashMap<>();
        Object[][] t = {
                {0x0000, "Ericsson"}, {0x0001, "Nokia"}, {0x0002, "Intel"}, {0x0003, "IBM"}, {0x0006, "Microsoft"},
                {0x000A, "Qualcomm"}, {0x000D, "Texas Instruments"}, {0x000F, "Broadcom"}, {0x001D, "Qualcomm"},
                {0x0030, "STMicroelectronics"}, {0x0046, "MediaTek"}, {0x004C, "Apple"}, {0x0057, "Harman (JBL)"},
                {0x0059, "Nordic Semiconductor"}, {0x005D, "Realtek"}, {0x0067, "GN (Jabra)"}, {0x006B, "Polar"},
                {0x0075, "Samsung"}, {0x0078, "Nike"}, {0x0087, "Garmin"}, {0x0089, "GN ReSound"}, {0x009E, "Bose"},
                {0x00C4, "LG"}, {0x00E0, "Google"}, {0x0103, "Bang & Olufsen"}, {0x012D, "Sony"}, {0x0131, "Cypress"},
                {0x0157, "Huami (Amazfit)"}, {0x0171, "Amazon"}, {0x027D, "Huawei"}, {0x02E5, "Espressif"},
                {0x038F, "Xiaomi"}, {0x0499, "Ruuvi"}, {0x05A7, "Sonos"}, {0x067C, "Tile"},
        };
        for (Object[] x : t) m.put((Integer) x[0], (String) x[1]);
        COMPANIES = Collections.unmodifiableMap(m);
    }

    static String company(int id) { return id < 0 ? "" : COMPANIES.containsKey(id) ? COMPANIES.get(id) : ""; }

    /** Makers van 16-bits service-UUID's die aan één bedrijf horen (uit de lijst van de Bluetooth SIG). */
    static String serviceMaker(int uuid) {
        switch (uuid) {
            case 0xFD5A: case 0xFD59: case 0xFD69: return "Samsung";
            case 0xFEED: case 0xFEEC: return "Tile";
            case 0xFE33: return "Chipolo";
            case 0xFE2C: case 0xFE9F: case 0xFEAA: return "Google";
            case 0xFE03: return "Amazon";
            case 0xFE07: return "Sonos";
            default: return "";
        }
    }

    // ---------- de advertentie (AD-structuren) ----------

    /** Wat een Bluetooth-apparaat uitzendt, uit elkaar gehaald. */
    static final class Adv {
        int flags = -1, appearance = -1, devClass = -1;
        /** TX power level (dBm, gemeten op 0 m); Integer.MIN_VALUE = niet meegestuurd. */
        int txLevel = Integer.MIN_VALUE;
        String name = "";
        final List<Integer> uuid16 = new ArrayList<>();
        final List<String> uuid128 = new ArrayList<>();
        final Map<Integer, byte[]> mfg = new LinkedHashMap<>();
        final Map<Integer, byte[]> svc16 = new LinkedHashMap<>();

        /** De eerste fabrikant in de advertentie, of -1. */
        int company() { return mfg.isEmpty() ? -1 : mfg.keySet().iterator().next(); }
        boolean hasService(int u) { return uuid16.contains(u) || svc16.containsKey(u); }
    }

    private static int u8(byte[] b, int i) { return b[i] & 0xFF; }
    private static int le16(byte[] b, int i) { return u8(b, i) | (u8(b, i + 1) << 8); }

    /** De ruwe advertentie (zoals ScanRecord.getBytes()) ontleden. Kapotte of afgekapte stukken worden overgeslagen. */
    static Adv parse(byte[] b) {
        Adv a = new Adv();
        if (b == null) return a;
        int i = 0;
        while (i < b.length) {
            int len = u8(b, i);
            if (len == 0) break;
            if (i + 1 + len > b.length) break; // afgekapt
            int type = u8(b, i + 1), s = i + 2, n = len - 1;
            try {
                switch (type) {
                    case 0x01: if (n >= 1) a.flags = u8(b, s); break;
                    case 0x02: case 0x03: for (int k = 0; k + 1 < n; k += 2) a.uuid16.add(le16(b, s + k)); break;
                    case 0x04: case 0x05: for (int k = 0; k + 3 < n; k += 4) { int v = le16(b, s + k + 2); if (v == 0) a.uuid16.add(le16(b, s + k)); } break;
                    case 0x06: case 0x07: for (int k = 0; k + 15 < n; k += 16) a.uuid128.add(uuid128(b, s + k)); break;
                    case 0x08: case 0x09: {
                        String nm = new String(b, s, n, StandardCharsets.UTF_8).replace("\u0000", "").trim();
                        if (type == 0x09 || a.name.isEmpty()) a.name = clean(nm);
                        break;
                    }
                    case 0x0A: if (n >= 1) a.txLevel = (byte) b[s]; break;
                    case 0x0D: if (n >= 3) a.devClass = u8(b, s) | (u8(b, s + 1) << 8) | (u8(b, s + 2) << 16); break;
                    case 0x16: if (n >= 2) { byte[] d = new byte[n - 2]; System.arraycopy(b, s + 2, d, 0, n - 2); a.svc16.put(le16(b, s), d); } break;
                    case 0x19: if (n >= 2) a.appearance = le16(b, s); break;
                    case 0xFF: if (n >= 2) { byte[] d = new byte[n - 2]; System.arraycopy(b, s + 2, d, 0, n - 2); if (!a.mfg.containsKey(le16(b, s))) a.mfg.put(le16(b, s), d); } break;
                    default: break;
                }
            } catch (RuntimeException ignored) { }
            i += len + 1;
        }
        return a;
    }

    private static String uuid128(byte[] b, int s) {
        StringBuilder h = new StringBuilder();
        for (int k = 15; k >= 0; k--) h.append(String.format(Locale.ROOT, "%02x", u8(b, s + k)));
        String x = h.toString();
        return x.substring(0, 8) + "-" + x.substring(8, 12) + "-" + x.substring(12, 16) + "-" + x.substring(16, 20) + "-" + x.substring(20);
    }

    /** Naam veilig om te tonen: zonder stuurtekens, hooguit 40 tekens. */
    static String clean(String s) {
        if (s == null) return "";
        StringBuilder o = new StringBuilder();
        for (int i = 0; i < s.length() && o.length() < 40; i++) { char c = s.charAt(i); if (c >= 0x20 && c != 0x7F && c != 0xFFFD) o.append(c); }
        return o.toString().trim();
    }

    // ---------- trackers ----------

    /** Een tracker-signaal: soort (id), naam, of hij uit de buurt van zijn eigenaar is (null = niet te zien), en of hij telt voor de waarschuwing. */
    static final class Tracker {
        final String kind, label; final Boolean separated; final boolean warn;
        Tracker(String k, String l, Boolean sep, boolean w) { kind = k; label = l; separated = sep; warn = w; }
    }

    /**
     * Herkent trackers aan hun handtekening:
     * - Apple Find My: fabrikant 0x004C, type 0x12. Lengte 0x19 = uit de buurt van de eigenaar (offline zoeken, het adres
     *   blijft dan tot een dag gelijk); lengte 0x02 = de eigenaar is in de buurt. Bits 4-5 van de statusbyte: 1 AirTag,
     *   2 Find My-accessoire (bijv. Chipolo Spot), 3 AirPods, 0 een ander Apple-apparaat.
     * - Samsung Galaxy SmartTag: service data 0xFD5A (0xFD59 = nog niet ingesteld).
     * - Tile: service 0xFEED of 0xFEEC. Chipolo: service 0xFE33.
     * - Google Zoek mijn apparaat (FMDN): service data 0xFEAA met frame 0x40, of 0x41 = beschermingsmodus (uit de buurt van de eigenaar).
     * - DULT (gezamenlijke norm van Apple en Google voor ongewenst volgen): service 0xFCB2.
     */
    static Tracker tracker(Adv a) {
        byte[] ap = a.mfg.get(0x004C);
        if (ap != null && ap.length >= 2 && u8(ap, 0) == 0x12) {
            boolean sep = u8(ap, 1) >= 0x19 && ap.length >= 3;
            if (!sep) return new Tracker("findmy-near", "Find My (eigenaar in de buurt)", false, false);
            int t = (u8(ap, 2) & 0x30) >> 4;
            if (t == 1) return new Tracker("airtag", "AirTag", true, true);
            if (t == 2) return new Tracker("findmy", "Find My-tracker", true, true);
            if (t == 3) return new Tracker("airpods", "AirPods (kwijt bij de eigenaar)", true, false);
            return new Tracker("apple-lost", "Apple-apparaat (offline zoeken)", true, false);
        }
        if (a.svc16.containsKey(0xFD5A) || a.hasService(0xFD5A)) return new Tracker("smarttag", "Galaxy SmartTag", null, true);
        if (a.svc16.containsKey(0xFD59)) return new Tracker("smarttag-new", "Galaxy SmartTag (niet ingesteld)", null, false);
        if (a.hasService(0xFEED) || a.hasService(0xFEEC)) return new Tracker("tile", "Tile", null, true);
        if (a.hasService(0xFE33)) return new Tracker("chipolo", "Chipolo", null, true);
        byte[] g = a.svc16.get(0xFEAA);
        if (g != null && g.length >= 1 && (u8(g, 0) == 0x40 || u8(g, 0) == 0x41)) {
            boolean sep = u8(g, 0) == 0x41;
            return new Tracker("google", "Tracker (Google Zoek mijn apparaat)", sep, sep);
        }
        if (a.hasService(0xFCB2)) return new Tracker("dult", "Tracker", null, true);
        return null;
    }

    // ---------- soort apparaat ----------

    /** Soort uit de "appearance" (categorie = bits 6-15, Bluetooth SIG Assigned Numbers). "" = onbekend. */
    static String typeFromAppearance(int ap) {
        if (ap < 0) return "";
        int cat = ap >> 6, sub = ap & 0x3F;
        switch (cat) {
            case 0x01: return "telefoon";
            case 0x02: return "computer";
            case 0x03: return "horloge";
            case 0x05: return "tv";
            case 0x06: return "afstandsbediening";
            case 0x08: case 0x09: return "tracker";
            case 0x0A: return "speaker";
            case 0x0C: case 0x0D: case 0x0E: case 0x10: case 0x31: case 0x32: case 0x34: return "gezondheid";
            case 0x0F: return sub == 4 || sub == 3 ? "spel" : "invoer";
            case 0x11: case 0x12: return "sport";
            case 0x15: return "sensor";
            case 0x16: return "lamp";
            case 0x21: return "speaker";
            case 0x22: return "speaker";
            case 0x23: return "auto";
            case 0x25: return sub == 1 ? "oortjes" : "koptelefoon";
            case 0x27: return "speaker";
            case 0x28: return "tv";
            case 0x29: return "hoortoestel";
            case 0x2A: return "spel";
            default: return "";
        }
    }

    /** Soort uit de Bluetooth-class (BluetoothClass.getDeviceClass(): hoofd- plus subklasse, bijv. 0x0418 = koptelefoon). */
    static String typeFromClass(int cls) {
        if (cls < 0) return "";
        int dev = cls & 0x1FFC, major = cls & 0x1F00;
        switch (dev) {
            case 0x0404: case 0x0408: return "koptelefoon";
            case 0x0418: return "koptelefoon";
            case 0x0414: case 0x041C: case 0x0428: return "speaker";
            case 0x0420: return "auto";
            case 0x0424: case 0x043C: case 0x0438: return "tv";
            case 0x0448: return "spel";
            case 0x0704: return "horloge";
            default: break;
        }
        switch (major) {
            case 0x0100: return "computer";
            case 0x0200: return "telefoon";
            case 0x0300: return "router";
            case 0x0400: return "speaker";
            case 0x0500: return "invoer";
            case 0x0600: return "printer";
            case 0x0700: return "horloge";
            case 0x0800: return "spel";
            case 0x0900: return "gezondheid";
            default: return "";
        }
    }

    private static final Object[][] NAME_HINTS = {
            {Pattern.compile("airpods|buds|earbuds|oordop|wf-|tws|freebuds|galaxy buds|pixel buds|jabra elite|beats (studio|fit|flex)|powerbeats"), "oortjes"},
            {Pattern.compile("wh-|headphone|koptelefoon|quietcomfort|qc ?\\d|headset|beats solo"), "koptelefoon"},
            {Pattern.compile("watch|band ?\\d|mi band|amazfit|fenix|forerunner|venu|vivo|fitbit|charge ?\\d|versa|polar|suunto|garmin"), "horloge"},
            {Pattern.compile("\\[tv\\]|\\btv\\b|bravia|chromecast|fire ?tv|roku|apple tv"), "tv"},
            {Pattern.compile("jbl|flip ?\\d|charge|soundlink|sonos|speaker|boombox|echo|homepod|nest (mini|audio)|soundbar|ue boom|marshall"), "speaker"},
            {Pattern.compile("iphone|galaxy [asmz]\\d|pixel \\d|oneplus|oppo|xiaomi|redmi|realme|nokia|motorola|moto [eg]|phone"), "telefoon"},
            {Pattern.compile("macbook|imac|laptop|desktop|thinkpad|surface|\\bpc\\b|windows"), "computer"},
            {Pattern.compile("keyboard|toetsenbord|mouse|muis|mx master|trackpad|magic"), "invoer"},
            {Pattern.compile("tile|chipolo|smarttag|airtag|nut ?find|pebblebee"), "tracker"},
            {Pattern.compile("\\bcar\\b|carplay|vw|volkswagen|bmw|audi|tesla|toyota|kia|hyundai|skoda|peugeot|renault|volvo|ford|mercedes|mazda|nissan|opel|seat"), "auto"},
            {Pattern.compile("hue|lamp|bulb|light"), "lamp"},
            {Pattern.compile("printer|deskjet|laserjet|envy|pixma|ecotank"), "printer"},
            {Pattern.compile("xbox|playstation|dualsense|dualshock|joy-?con|switch|controller"), "spel"},
    };

    /** Soort uit de naam (alleen als hulp, als de advertentie niets zegt). */
    static String typeFromName(String name) {
        if (name == null || name.isEmpty()) return "";
        String n = name.toLowerCase(Locale.ROOT);
        for (Object[] h : NAME_HINTS) if (((Pattern) h[0]).matcher(n).find()) return (String) h[1];
        return "";
    }

    /** Soort uit service-UUID's (16 bits). */
    static String typeFromServices(Adv a) {
        if (a.hasService(0x180D) || a.hasService(0x1814) || a.hasService(0x1816) || a.hasService(0x1818) || a.hasService(0x1826)) return "sport";
        if (a.hasService(0x1812)) return "invoer";
        if (a.hasService(0x1809) || a.hasService(0x1810) || a.hasService(0x1808) || a.hasService(0x181D) || a.hasService(0x1822)) return "gezondheid";
        if (a.hasService(0x184E) || a.hasService(0x1850) || a.hasService(0x1853) || a.hasService(0x184F)) return "oortjes"; // LE Audio
        if (a.hasService(0x181A) || a.hasService(0x183B)) return "sensor";
        if (a.hasService(0xFE07)) return "speaker";
        if (a.hasService(0xFE03)) return "speaker";
        return "";
    }

    /** Soort uit Apple-advertenties (type in de eerste byte na 0x004C). */
    static String typeFromApple(byte[] d) {
        if (d == null || d.length < 1) return "";
        switch (u8(d, 0)) {
            case 0x07: return "oortjes";       // nabij koppelen: AirPods / Beats
            case 0x09: return "tv";            // AirPlay-doel: Apple TV, HomePod
            case 0x0B: return "horloge";
            case 0x0E: return "telefoon";      // persoonlijke hotspot
            case 0x10: case 0x0C: case 0x0F: return "apple";
            case 0x12: return "tracker";
            case 0x02: return "baken";         // iBeacon
            default: return "";
        }
    }

    /** Alles bij elkaar: soort, maker en (eventueel) tracker. */
    static final class Info { String type = "", maker = ""; Tracker tracker; }

    static Info classify(Adv a, String name, int devClass) {
        Info r = new Info();
        r.tracker = tracker(a);
        int co = a.company();
        r.maker = company(co);
        if (r.maker.isEmpty()) for (int u : a.uuid16) { r.maker = serviceMaker(u); if (!r.maker.isEmpty()) break; }
        if (r.maker.isEmpty()) for (int u : a.svc16.keySet()) { r.maker = serviceMaker(u); if (!r.maker.isEmpty()) break; }
        String t = r.tracker != null && r.tracker.warn ? "tracker" : "";
        if (t.isEmpty()) t = typeFromAppearance(a.appearance);
        if (t.isEmpty()) t = typeFromClass(devClass >= 0 ? devClass : a.devClass >= 0 ? a.devClass & 0x1FFC : -1);
        if (t.isEmpty() && co == 0x004C) { t = typeFromApple(a.mfg.get(0x004C)); if ("tracker".equals(t) && r.tracker != null && !r.tracker.warn) t = "apple"; }
        if (t.isEmpty()) t = typeFromServices(a);
        if (t.isEmpty()) t = typeFromName(name != null && !name.isEmpty() ? name : a.name);
        if (t.isEmpty() && co == 0x0006) t = "computer"; // Microsoft: Swift Pair / Windows
        if (t.isEmpty() && a.svc16.containsKey(0xFEAA)) t = "baken";
        if (t.isEmpty() && a.svc16.containsKey(0xFE2C)) t = "oortjes"; // Google Fast Pair: meestal oortjes of een koptelefoon
        r.type = t.isEmpty() ? "apparaat" : t;
        return r;
    }

    // ---------- adressen en herkenning ----------

    /** Wisselt dit adres (resolvable private address: bovenste bits 01)? Telefoons, horloges en Apple doen dat elke ~15 minuten. */
    static boolean rotating(String addr) {
        int b = firstByte(addr);
        return b >= 0 && (b & 0xC0) == 0x40;
    }

    /** Willekeurig adres dat niet terug te herkennen is (non-resolvable, bovenste bits 00) kan niet met zekerheid; alleen 01 telt. */
    static int firstByte(String addr) {
        if (addr == null || addr.length() < 2) return -1;
        try { return Integer.parseInt(addr.substring(0, 2), 16); } catch (NumberFormatException e) { return -1; }
    }

    /** Makers van wifi-routers en slimme apparaten, uit de eerste drie bytes van het adres (een kleine, vaste lijst). */
    static final Map<String, String> OUI;
    static {
        Map<String, String> m = new HashMap<>();
        Object[][] t = {
                {"AVM (FRITZ!Box)", "00040e 3ca62f 7cff4d 989bcb c80e14 e0286d 2c91ab 5c4979 3810d5 444e6d dc396f b0f208 ccce1e e8df70 f0b014"},
                {"Ubiquiti", "24a43c 44d9e7 687251 788a20 802aa8 b4fbe4 dc9fdb f09fc2 fcecda 7483c2 e063da 18e829"},
                {"TP-Link", "50c7bf 14cc20 c04a00 f4f26d 98ded0 ec086b 60e327 a0f3c1 30b5c2 54c80f"},
                {"Netgear", "a040a0 20e52a 9c3dcf c40415 e091f5 28c68e 100d7f"},
                {"Google", "f4f5d8 f4f5e8 3c5ab4 546009"},
                {"Raspberry Pi", "b827eb dca632 e45f01 28cdc1 d83add"},
                {"Espressif (slim apparaat)", "240ac4 30aea4 84f3eb a4cf12 246f28 3c6105 8caab5 cc50e3 ecfabc 600194 5ccf7f 18fe34"},
                {"Sonos", "000e58 5caafd 7828ca 949f3e b8e937 48a6b8 542a1b 347e5c"},
                {"Synology", "001132 9009d0"},
        };
        for (Object[] x : t) for (String o : ((String) x[1]).split(" ")) m.put(o, (String) x[0]);
        OUI = Collections.unmodifiableMap(m);
    }

    /** Maker volgens het wifi-adres (BSSID), of "" (onbekend, of een zelfgekozen adres). */
    static String ouiMaker(String mac) {
        if (mac == null || localMac(mac)) return "";
        String h = mac.replace(":", "").replace("-", "").toLowerCase(Locale.ROOT);
        if (h.length() < 6) return "";
        String v = OUI.get(h.substring(0, 6));
        return v == null ? "" : v;
    }

    /** Lokaal beheerd MAC-adres (wifi): bit 1 van de eerste byte. Dan is het adres zelf gekozen (willekeurig), geen maker te zien. */
    static boolean localMac(String mac) {
        int b = firstByte(mac);
        return b >= 0 && (b & 0x02) != 0;
    }

    static String norm(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "); }

    /**
     * De sleutel waaronder een Bluetooth-apparaat gegroepeerd en herkend wordt:
     * a:ADRES  gekoppeld, of een adres dat niet wisselt;
     * n:naam|maker  een wisselend adres met een naam (dezelfde naam = hetzelfde apparaat);
     * g:maker|soort  wisselend adres zonder naam: niet uit elkaar te houden, dus samen als groep.
     */
    static String key(String addr, boolean bonded, String name, int company, String type) {
        String a = addr == null ? "" : addr.toUpperCase(Locale.ROOT);
        if (bonded || (!a.isEmpty() && !rotating(a))) return "a:" + a;
        if (name != null && !name.trim().isEmpty()) return "n:" + norm(name) + "|" + company;
        return "g:" + company + "|" + (type == null ? "" : type);
    }

    /** Is dit apparaat terug te herkennen (voor bijnamen, geschiedenis en zoeken)? Groepen niet. */
    static boolean stableKey(String key) { return key != null && (key.startsWith("a:") || key.startsWith("n:") || key.startsWith("l:")); }

    // ---------- afstand en signaal ----------

    /**
     * Ongeveer hoe ver weg, in meters, uit de signaalsterkte. Zendvermogen op 1 meter: uit de TX power level van de
     * advertentie (gemeten op 0 m, min ~41 dB tot 1 m), anders -59 dBm (gebruikelijk voor telefoons en oortjes).
     * n = hoe snel het signaal afneemt (2 in de open lucht, binnen meer). Grof: muren, je lichaam en de tas maken veel uit.
     */
    static double distance(int rssi, int txLevel, double n) {
        if (rssi >= 0 || rssi < -127) return -1;
        int at1m = txLevel != Integer.MIN_VALUE && txLevel > -100 && txLevel < 30 ? txLevel - 41 : -59;
        at1m = Math.max(-90, Math.min(-30, at1m));
        double d = Math.pow(10, (at1m - rssi) / (10.0 * (n <= 0 ? 2.0 : n)));
        return Math.max(0.1, Math.min(99, d));
    }

    /** Afgevlakt signaal (voortschrijdend gemiddelde); prev = NaN voor de eerste meting. */
    static double smooth(double prev, int rssi, double alpha) {
        if (Double.isNaN(prev)) return rssi;
        return prev + alpha * (rssi - prev);
    }

    /** Warmer (+1), kouder (-1) of gelijk (0): het verschil tussen nu en een paar seconden geleden, met een marge van 2 dB. */
    static int trend(double now, double before) {
        if (Double.isNaN(now) || Double.isNaN(before)) return 0;
        double d = now - before;
        return d >= 2 ? 1 : d <= -2 ? -1 : 0;
    }

    /** Signaal als 0..100 voor de meter (−100 dBm = 0, −35 dBm = 100). */
    static int meter(double rssi) {
        if (Double.isNaN(rssi)) return 0;
        return (int) Math.round(Math.max(0, Math.min(100, (rssi + 100) * 100.0 / 65.0)));
    }

    // ---------- wifi ----------

    /** Kanaalnummer uit de frequentie (MHz); 0 = onbekend. */
    static int channel(int f) {
        if (f == 2484) return 14;
        if (f >= 2412 && f <= 2472) return (f - 2407) / 5;
        if (f == 5935) return 2; // 6 GHz kanaal 2 valt buiten de gewone reeks
        if (f >= 5955 && f <= 7115) return (f - 5950) / 5;
        if (f >= 5160 && f <= 5885) return (f - 5000) / 5;
        if (f >= 4915 && f <= 4980) return (f - 4000) / 5;
        return 0;
    }

    static String band(int f) {
        if (f >= 2400 && f < 2500) return "2,4 GHz";
        if (f >= 5925 && f < 7200) return "6 GHz";
        if (f >= 4900 && f < 5925) return "5 GHz";
        return "";
    }

    /** Kanaalbreedte in MHz uit ScanResult.channelWidth (0=20, 1=40, 2=80, 3=160, 4=80+80, 5=320). */
    static int width(int code) {
        switch (code) { case 1: return 40; case 2: return 80; case 3: case 4: return 160; case 5: return 320; default: return 20; }
    }

    /** Beveiliging uit ScanResult.capabilities: open, wep, wpa, wpa2, wpa3, owe of zakelijk. */
    static String security(String caps) {
        String c = caps == null ? "" : caps.toUpperCase(Locale.ROOT);
        if (c.contains("WEP")) return "wep";
        if (c.contains("EAP")) return "zakelijk";
        if (c.contains("SAE")) return "wpa3";
        if (c.contains("OWE")) return "owe";
        if (c.contains("WPA2") || c.contains("RSN")) return "wpa2";
        if (c.contains("WPA")) return "wpa";
        return "open";
    }

    /** Onveilig: open (zonder versleuteling) of WEP (al lang te kraken). OWE is open maar wel versleuteld. */
    static boolean unsafe(String sec) { return "open".equals(sec) || "wep".equals(sec); }

    /**
     * Drukte per kanaal: elk netwerk telt mee naar sterkte (sterk = 1, heel zwak = 0,05). Op 2,4 GHz overlappen kanalen
     * tot 4 nummers verder (minder naarmate ze verder liggen); op 5 en 6 GHz telt alleen hetzelfde kanaal.
     * Geeft kanaal → score, alleen voor de kanalen van die band die er toe doen.
     */
    static Map<Integer, Double> crowding(int[] freqs, int[] rssis, String bandWanted) {
        Map<Integer, Double> m = new java.util.TreeMap<>();
        boolean b24 = "2,4 GHz".equals(bandWanted);
        if (b24) for (int ch = 1; ch <= 13; ch++) m.put(ch, 0.0);
        for (int i = 0; i < freqs.length; i++) {
            if (!bandWanted.equals(band(freqs[i]))) continue;
            int ch = channel(freqs[i]);
            if (ch <= 0) continue;
            double w = Math.max(0.05, Math.min(1, (rssis[i] + 100) / 60.0));
            if (b24) {
                for (int k = 1; k <= 13; k++) { int d = Math.abs(k - ch); if (d <= 4) m.put(k, m.get(k) + w * (1 - d / 5.0)); }
            } else m.put(ch, (m.containsKey(ch) ? m.get(ch) : 0) + w);
        }
        for (Map.Entry<Integer, Double> e : m.entrySet()) e.setValue(Math.round(e.getValue() * 100) / 100.0);
        return m;
    }

    /** Het rustigste van de kanalen 1, 6 en 11 (die overlappen elkaar niet) op 2,4 GHz. */
    static int quietest24(Map<Integer, Double> crowd) {
        int best = 1; double low = Double.MAX_VALUE;
        for (int ch : new int[]{1, 6, 11}) { Double v = crowd.get(ch); double x = v == null ? 0 : v; if (x < low - 1e-9) { low = x; best = ch; } }
        return best;
    }

    // ---------- eigen netwerk: subnet ----------

    static int ipInt(String s) {
        if (s == null) return 0;
        String[] p = s.trim().split("\\.");
        if (p.length != 4) return 0;
        int v = 0;
        try { for (String x : p) { int n = Integer.parseInt(x); if (n < 0 || n > 255) return 0; v = (v << 8) | n; } } catch (NumberFormatException e) { return 0; }
        return v;
    }

    static String ipStr(int v) { return ((v >>> 24) & 255) + "." + ((v >>> 16) & 255) + "." + ((v >>> 8) & 255) + "." + (v & 255); }

    /** Privé-adres (thuisnetwerk): 10/8, 172.16/12, 192.168/16. Alleen daar wordt gezocht. */
    static boolean privateIp(int ip) {
        int a = (ip >>> 24) & 255, b = (ip >>> 16) & 255;
        return a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168);
    }

    /** Het deel van het netwerk waarin gezocht mag worden: nooit groter dan /24 (ook als het netwerk groter is). */
    static int effPrefix(int prefix) { return Math.max(24, Math.min(32, prefix)); }

    static boolean sameNet(int own, int other, int prefix) {
        int p = effPrefix(prefix);
        int mask = p == 0 ? 0 : (int) (0xFFFFFFFFL << (32 - p));
        return (own & mask) == (other & mask);
    }

    /** Alle adressen om te proberen: het eigen /24 (of kleiner), zonder netwerk-, broadcast- en eigen adres. Leeg buiten een privé-netwerk. */
    static int[] hosts(int own, int prefix) {
        if (!privateIp(own)) return new int[0];
        int p = effPrefix(prefix);
        if (p >= 31) return new int[0];
        int size = 1 << (32 - p), mask = (int) (0xFFFFFFFFL << (32 - p)), base = own & mask;
        int[] tmp = new int[size];
        int n = 0;
        for (int i = 1; i < size - 1; i++) { int h = base + i; if (h != own) tmp[n++] = h; }
        int[] out = new int[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    // ---------- SSDP / UPnP ----------

    /** Kopregels van een SSDP-antwoord (HTTP over UDP), sleutels in kleine letters. */
    static Map<String, String> ssdpHeaders(String resp) {
        Map<String, String> m = new LinkedHashMap<>();
        if (resp == null) return m;
        String[] lines = resp.split("\r?\n");
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c <= 0) continue;
            String k = lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT), v = lines[i].substring(c + 1).trim();
            if (!k.isEmpty() && !m.containsKey(k) && v.length() < 400) m.put(k, v);
        }
        return m;
    }

    /** De inhoud van het eerste &lt;tag&gt; in de beschrijving van een UPnP-apparaat (zonder opmaak, hooguit 80 tekens). */
    static String xmlTag(String xml, String tag) {
        if (xml == null) return "";
        Matcher m = Pattern.compile("<(?:\\w+:)?" + Pattern.quote(tag) + "\\b[^>]*>([^<]{0,400})</", Pattern.CASE_INSENSITIVE).matcher(xml);
        if (!m.find()) return "";
        String v = m.group(1).replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'").trim();
        return clean(v.length() > 80 ? v.substring(0, 80) : v);
    }

    /** Host van een LOCATION-adres (http://192.168.1.20:49152/desc.xml → 192.168.1.20); "" als het geen gewoon http-adres is. */
    static String urlHost(String url) {
        if (url == null) return "";
        // Elk getal zonder voorloopnullen (zo kan "010" niet als ander adres gelezen worden), en precies terug te schrijven
        String oct = "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
        Matcher m = Pattern.compile("^http://(" + oct + "\\." + oct + "\\." + oct + "\\." + oct + ")(?::(\\d{1,5}))?(/.*)?$").matcher(url.trim());
        if (!m.find()) return "";
        String h = m.group(1);
        return ipStr(ipInt(h)).equals(h) ? h : "";
    }

    // ---------- NetBIOS (naam van een Windows-computer of NAS) ----------

    /** NBSTAT-vraag ("wie ben je?") aan één adres op poort 137. */
    static byte[] nbstatQuery(int txid) {
        byte[] q = new byte[50];
        q[0] = (byte) (txid >> 8); q[1] = (byte) txid;
        q[5] = 1; // één vraag
        q[12] = 0x20; q[13] = 'C'; q[14] = 'K'; // "*" gecodeerd: 'C','K' en daarna 'A' x 30
        for (int i = 15; i < 45; i++) q[i] = 'A';
        q[45] = 0;
        q[47] = 0x21; // NBSTAT
        q[49] = 1;    // IN
        return q;
    }

    /** De naam van de computer uit een NBSTAT-antwoord (de eerste unieke naam met achtervoegsel 0x00 of 0x20), of "". */
    static String nbstatName(byte[] r, int len) {
        try {
            if (r == null || len < 57) return "";
            int i = 12;
            while (i < len && r[i] != 0) i += (r[i] & 0xFF) + 1; // naam in de vraag-echo overslaan
            i += 1 + 2 + 2 + 4 + 2; // eind, type, klasse, ttl, lengte
            if (i >= len) return "";
            int n = r[i] & 0xFF; i++;
            String best = "";
            for (int k = 0; k < n && i + 18 <= len; k++, i += 18) {
                String name = new String(r, i, 15, StandardCharsets.US_ASCII).trim();
                int suffix = r[i + 15] & 0xFF, flags = r[i + 16] & 0xFF;
                boolean group = (flags & 0x80) != 0;
                if (!group && (suffix == 0x00 || suffix == 0x20) && !name.isEmpty()) { best = clean(name); break; }
            }
            return best;
        } catch (RuntimeException e) { return ""; }
    }

    // ---------- apparaten op het eigen netwerk ----------

    /** Leesbare naam van een mDNS-dienst. */
    static String serviceLabel(String type) {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        if (t.startsWith("_googlecast")) return "Chromecast";
        if (t.startsWith("_airplay")) return "AirPlay";
        if (t.startsWith("_raop")) return "AirPlay-audio";
        if (t.startsWith("_ipp") || t.startsWith("_printer") || t.startsWith("_pdl-datastream")) return "Printer";
        if (t.startsWith("_hap")) return "HomeKit";
        if (t.startsWith("_spotify-connect")) return "Spotify Connect";
        if (t.startsWith("_sonos")) return "Sonos";
        if (t.startsWith("_workstation")) return "Computer";
        if (t.startsWith("_smb")) return "Gedeelde map";
        if (t.startsWith("_afpovertcp")) return "Gedeelde map (Mac)";
        if (t.startsWith("_companion-link")) return "Apple-apparaat";
        if (t.startsWith("_androidtvremote")) return "Android TV";
        if (t.startsWith("_amzn-wplay")) return "Fire TV";
        if (t.startsWith("_matter")) return "Matter";
        if (t.startsWith("_http")) return "Webpagina";
        if (t.startsWith("_ssh")) return "SSH";
        return t.replaceAll("^_|\\._(tcp|udp)\\.?$", "");
    }

    static String portLabel(int p) {
        switch (p) {
            case 22: return "SSH"; case 53: return "DNS"; case 80: return "Webpagina"; case 443: return "Webpagina (https)";
            case 139: case 445: return "Gedeelde map"; case 554: return "Camerabeeld (RTSP)"; case 631: return "Printer (IPP)";
            case 1400: return "Sonos"; case 5000: case 5001: return "NAS-beheer"; case 7000: return "AirPlay";
            case 8008: case 8009: return "Chromecast"; case 8080: return "Webpagina (8080)"; case 9100: return "Printer";
            case 62078: return "iPhone/iPad (synchroniseren)"; case 3689: return "iTunes-delen"; case 8123: return "Home Assistant";
            default: return "Poort " + p;
        }
    }

    /** Welke poorten (gangbare diensten) voorzichtig geprobeerd worden. */
    static final int[] PORTS = {80, 443, 22, 445, 554, 631, 9100, 8008, 8009, 1400, 5000, 62078, 8123, 8080};

    /**
     * Soort van een apparaat op het eigen netwerk, uit wat het zelf zegt (mDNS-diensten, UPnP-soort, open poorten).
     * gateway = het is de router.
     */
    static String lanKind(Set<String> services, Set<Integer> ports, String upnpType, boolean gateway, String model) {
        String u = upnpType == null ? "" : upnpType.toLowerCase(Locale.ROOT), md = model == null ? "" : model.toLowerCase(Locale.ROOT);
        if (gateway || u.contains("internetgatewaydevice")) return "router";
        Set<String> s = new TreeSet<>();
        for (String x : services) s.add(x.toLowerCase(Locale.ROOT));
        if (has(s, "_ipp") || has(s, "_printer") || has(s, "_pdl-datastream") || ports.contains(9100) || ports.contains(631) || u.contains("printer")) return "printer";
        if (has(s, "_googlecast") || has(s, "_androidtvremote") || has(s, "_amzn-wplay") || ports.contains(8008) || ports.contains(8009)) return md.contains("speaker") || md.contains("audio") || md.contains("nest mini") || md.contains("home mini") ? "speaker" : "tv";
        if (has(s, "_sonos") || ports.contains(1400) || has(s, "_spotify-connect") || has(s, "_raop")) return has(s, "_airplay") && md.contains("tv") ? "tv" : "speaker";
        if (has(s, "_airplay")) return md.contains("audio") || md.contains("homepod") ? "speaker" : "tv";
        if (u.contains("mediarenderer")) return "tv";
        if (has(s, "_hap") || has(s, "_matter")) return "slim";
        if (has(s, "_companion-link") || ports.contains(62078)) return "telefoon";
        if (ports.contains(554)) return "camera";
        if (ports.contains(5000) || ports.contains(5001) || u.contains("mediaserver")) return "nas";
        if (has(s, "_workstation") || has(s, "_smb") || has(s, "_afpovertcp") || has(s, "_ssh") || ports.contains(445) || ports.contains(22)) return "computer";
        if (ports.contains(8123)) return "slim";
        return "apparaat";
    }

    private static boolean has(Set<String> s, String prefix) { for (String x : s) if (x.startsWith(prefix)) return true; return false; }

    /** Model of naam uit de TXT-gegevens van een mDNS-dienst (fn/md bij Chromecast, model bij AirPlay, ty bij printers). */
    static String txtModel(Map<String, String> txt) {
        if (txt == null) return "";
        for (String k : new String[]{"md", "model", "ty", "product", "usb_mdl", "am"}) {
            String v = txt.get(k);
            if (v != null && !v.trim().isEmpty()) return clean(v.replaceAll("^\\(|\\)$", ""));
        }
        return "";
    }

    static String txtName(Map<String, String> txt) {
        if (txt == null) return "";
        String v = txt.get("fn");
        return v == null ? "" : clean(v);
    }

    // ---------- tracker reist mee ----------

    /** Eén keer gezien: tijd en (grove) plek. Zonder plek: lat/lon = NaN. */
    static final class Sighting {
        final long t; final double lat, lon, acc;
        Sighting(long t, double lat, double lon) { this(t, lat, lon, 0); }
        /** t = tijd van de plaatsbepaling (niet van de waarneming), acc = nauwkeurigheid in meters (0 = onbekend). */
        Sighting(long t, double lat, double lon, double acc) { this.t = t; this.lat = lat; this.lon = lon; this.acc = Double.isNaN(acc) || acc < 0 ? 0 : acc; }
        boolean located() { return !Double.isNaN(lat) && !Double.isNaN(lon); }
    }

    /** Een plaatsbepaling telt voor de tracker-regel als hij hooguit 5 minuten oud is en tot op 200 m nauwkeurig. */
    static final long FIX_MAX_AGE = 5 * 60_000L;
    static final double FIX_MAX_ACC = 200;

    static boolean usableFix(long fixTime, double acc, long now) {
        return fixTime > 0 && now - fixTime <= FIX_MAX_AGE && fixTime <= now + 60_000 && !Double.isNaN(acc) && acc >= 0 && acc <= FIX_MAX_ACC;
    }

    /** Afstand in meters (haversine). */
    static double meters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000, dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** Uitkomst van de regel: hoeveel plekken ver genoeg uit elkaar, over hoeveel minuten, en of dat genoeg is. */
    static final class Follow {
        int places; long minutes; boolean follows;
    }

    /** De regel: standaard ≥ 3 plekken die ≥ 500 m uit elkaar liggen, over ≥ 30 minuten, binnen de laatste 8 uur. */
    static final int FOLLOW_PLACES = 3;
    static final double FOLLOW_METERS = 500;
    static final long FOLLOW_MINUTES = 30, FOLLOW_WINDOW = 8 * 3600_000L;

    static Follow follows(List<Sighting> list, long now) { return follows(list, now, FOLLOW_PLACES, FOLLOW_METERS, FOLLOW_MINUTES, FOLLOW_WINDOW); }

    /**
     * Reist een tracker mee? Alleen waarnemingen met een plek, binnen het venster, op volgorde van tijd. Een plek telt als
     * nieuwe plek als hij ver genoeg ligt van alle plekken die al telden: minMeters plus de onnauwkeurigheid van beide
     * plaatsbepalingen (zo wordt een tracker die stil ligt niet "meegereisd" door een verspringende netwerklocatie). Genoeg plekken én lang genoeg (tussen de eerste
     * en de laatste waarneming van die plekken) = meereizen.
     */
    static Follow follows(List<Sighting> list, long now, int minPlaces, double minMeters, long minMinutes, long window) {
        Follow f = new Follow();
        List<Sighting> s = new ArrayList<>();
        for (Sighting x : list) if (x != null && x.located() && x.t <= now + 60_000 && now - x.t <= window) s.add(x);
        Collections.sort(s, Apparaten::byTime);
        List<Sighting> places = new ArrayList<>();
        long last = 0;
        for (Sighting x : s) {
            boolean far = true;
            for (Sighting p : places) if (meters(p.lat, p.lon, x.lat, x.lon) < minMeters + p.acc + x.acc) { far = false; break; }
            if (far) places.add(x);
            last = x.t;
        }
        f.places = places.size();
        f.minutes = places.isEmpty() ? 0 : (last - places.get(0).t) / 60_000L;
        f.follows = f.places >= minPlaces && f.minutes >= minMinutes;
        return f;
    }

    private static int byTime(Sighting a, Sighting b) { return Long.compare(a.t, b.t); }

    /** Grove plek voor de tracker-regel: afgerond op 3 decimalen (~100 m). */
    static double round3(double v) { return Math.round(v * 1000) / 1000.0; }

    /** Grove plek voor de geschiedenis: een vakje van ~1 km (2 decimalen). */
    static String cell(double lat, double lon) {
        if (Double.isNaN(lat) || Double.isNaN(lon)) return "";
        return String.format(Locale.ROOT, "%.2f,%.2f", lat, lon);
    }

    /** Thuis = binnen 300 m van de plek die je als thuis koos. */
    static final double HOME_METERS = 300;

    static boolean atHome(double lat, double lon, double hLat, double hLon) {
        if (Double.isNaN(lat) || Double.isNaN(hLat)) return false;
        return meters(lat, lon, hLat, hLon) <= HOME_METERS;
    }

    // ---------- geschiedenis: vaak in de buurt ----------

    /** Per apparaat: alleen eerste/laatste keer, op welke dagen (thuis of elders) en de laatste grove plek. Geen spoor. */
    static final class Hist {
        String key = "", name = "", type = "", maker = "", lastPlace = "";
        long first, last;
        final TreeSet<String> homeDays = new TreeSet<>(), awayDays = new TreeSet<>();
        int days() { TreeSet<String> all = new TreeSet<>(homeDays); all.addAll(awayDays); return all.size(); }
    }

    static String dayKey(long t, TimeZone tz) {
        Calendar k = Calendar.getInstance(tz == null ? TimeZone.getDefault() : tz);
        k.setTimeInMillis(t);
        return String.format(Locale.ROOT, "%04d-%02d-%02d", k.get(Calendar.YEAR), k.get(Calendar.MONTH) + 1, k.get(Calendar.DAY_OF_MONTH));
    }

    /** Een waarneming bijschrijven. place = "thuis", een vakje ("51.92,4.48") of "" (plek onbekend: telt als elders). */
    static void record(Hist h, long t, String place, TimeZone tz) {
        if (h.first == 0 || t < h.first) h.first = t;
        if (t >= h.last) { h.last = t; h.lastPlace = place == null ? "" : place; }
        String d = dayKey(t, tz);
        if ("thuis".equals(place)) h.homeDays.add(d); else h.awayDays.add(d);
    }

    /** Hoeveel apparaten de geschiedenis hooguit bijhoudt (de oudste vallen eraf). */
    static final int HIST_MAX = 500;

    /** Bewaartermijn toepassen: wat langer dan keepDays niet gezien is weg, oude dagen weg, en hooguit HIST_MAX apparaten. */
    static void prune(Map<String, Hist> all, long now, int keepDays, TimeZone tz) {
        long cut = now - keepDays * 86_400_000L;
        String cutDay = dayKey(cut, tz);
        for (java.util.Iterator<Map.Entry<String, Hist>> it = all.entrySet().iterator(); it.hasNext(); ) {
            Hist h = it.next().getValue();
            if (h.last < cut) { it.remove(); continue; }
            h.homeDays.headSet(cutDay).clear();
            h.awayDays.headSet(cutDay).clear();
            if (h.first < cut) h.first = cut;
        }
        if (all.size() > HIST_MAX) {
            List<Hist> l = new ArrayList<>(all.values());
            Collections.sort(l, Apparaten::byLastDesc);
            for (int i = HIST_MAX; i < l.size(); i++) all.remove(l.get(i).key);
        }
    }

    private static int byLastDesc(Hist a, Hist b) { return Long.compare(b.last, a.last); }

    /** Vaak in de buurt: op minstens minDays verschillende dagen gezien, thuis (away=false) of elders (away=true), meest eerst. */
    static List<Hist> often(Collection<Hist> all, boolean away, int minDays) {
        List<Hist> l = new ArrayList<>();
        for (Hist h : all) if ((away ? h.awayDays.size() : h.homeDays.size()) >= minDays) l.add(h);
        Collections.sort(l, away ? Apparaten::byAway : Apparaten::byHome);
        return l;
    }

    private static int byAway(Hist a, Hist b) { int c = Integer.compare(b.awayDays.size(), a.awayDays.size()); return c != 0 ? c : Long.compare(b.last, a.last); }
    private static int byHome(Hist a, Hist b) { int c = Integer.compare(b.homeDays.size(), a.homeDays.size()); return c != 0 ? c : Long.compare(b.last, a.last); }
}
