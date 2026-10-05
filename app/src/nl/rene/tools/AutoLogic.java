package nl.rene.tools;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Pure-Java regels van Automatiseringen (los getest in AutoTest):
 * afstand tot een plek, dagen, tijdvenster, wachttijd tussen twee keer, en teksten van knoppen vergelijken.
 */
final class AutoLogic {

    private AutoLogic() { }

    /** Afstand in meters tussen twee punten (haversine). */
    static double meters(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371000, p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * Ligt een meting binnen de plek? Een onnauwkeurige meting telt mee tot de helft van zijn
     * onnauwkeurigheid (max. 150 m extra), zodat een slechte gps-fix in een parkeergarage niet alles blokkeert.
     */
    static boolean inPlace(double lat, double lng, float acc, double pLat, double pLng, int radius) {
        double extra = Math.min(150, Math.max(0, acc) / 2.0);
        return meters(lat, lng, pLat, pLng) <= radius + extra;
    }

    /** Dagen als bitmasker ma=1, di=2 … zo=64; 0 = elke dag. dow: Calendar.DAY_OF_WEEK (zo=1 … za=7). */
    static boolean dayOk(int mask, int dow) {
        if (mask == 0) return true;
        int bit = dow == 1 ? 6 : dow - 2; // zo -> 6, ma -> 0
        return (mask & (1 << bit)) != 0;
    }

    /** Minuten sinds middernacht uit "HH:MM", of -1 als leeg/ongeldig. */
    static int minutes(String hhmm) {
        if (hhmm == null) return -1;
        String s = hhmm.trim();
        int c = s.indexOf(':');
        if (c <= 0) return -1;
        try {
            int h = Integer.parseInt(s.substring(0, c)), m = Integer.parseInt(s.substring(c + 1));
            if (h < 0 || h > 23 || m < 0 || m > 59) return -1;
            return h * 60 + m;
        } catch (NumberFormatException e) { return -1; }
    }

    /** Valt 'now' (minuten) in het venster van..tot? Leeg = altijd. Over middernacht (22:00–06:00) kan ook. */
    static boolean timeOk(String from, String to, int now) {
        int f = minutes(from), t = minutes(to);
        if (f < 0 || t < 0 || f == t) return true;
        return f < t ? (now >= f && now < t) : (now >= f || now < t);
    }

    /** Niet vaker dan eens per 'cooldownMin' minuten (tegen een Bluetooth-verbinding die even hapert). */
    static boolean cooldownOk(long lastFired, long now, int cooldownMin) {
        if (lastFired <= 0) return true;
        return now - lastFired >= Math.max(0, cooldownMin) * 60_000L;
    }

    /** Tekst voor vergelijken: kleine letters, zonder accenten, leestekens en dubbele spaties. */
    static String norm(CharSequence s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s.toString(), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        n = n.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        return n;
    }

    /**
     * Hoe goed past een knoptekst bij de gezochte tekst? 3 = gelijk, 2 = begint ermee,
     * 1 = komt erin voor (alleen bij zoektekst van minstens 4 tekens), 0 = niet.
     */
    static int textScore(CharSequence nodeText, String wanted) {
        String a = norm(nodeText), w = norm(wanted);
        if (w.isEmpty() || a.isEmpty()) return 0;
        if (a.equals(w)) return 3;
        if (a.startsWith(w + " ") || a.startsWith(w)) return 2;
        if (w.length() >= 4 && a.contains(w)) return 1;
        return 0;
    }

    /** Leesbare samenvatting van dagen: "elke dag", "werkdagen", "weekend" of "ma, wo, vr". */
    static String daysLabel(int mask) {
        if (mask == 0 || mask == 127) return "elke dag";
        if (mask == 31) return "werkdagen";
        if (mask == 96) return "weekend";
        String[] d = {"ma", "di", "wo", "do", "vr", "za", "zo"};
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 7; i++) if ((mask & (1 << i)) != 0) { if (b.length() > 0) b.append(", "); b.append(d[i]); }
        return b.toString();
    }
}
