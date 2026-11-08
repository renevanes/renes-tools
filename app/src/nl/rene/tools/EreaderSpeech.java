package nl.rene.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.json.JSONArray;

/**
 * Zuivere logica voor voorlezen in de e-reader (getest in EreaderSpeechTest): de tekst van een hoofdstuk in zinnen
 * knippen (met posities die gelijk zijn aan die in de interface), de zin bij een leesplek vinden, het volgende
 * hoofdstuk in de leesvolgorde en de slaaptimer.
 */
final class EreaderSpeech {
    private EreaderSpeech() { }

    /** Langste stuk dat in één keer naar de spraakmotor gaat (de motor zelf kan ±4000 tekens). */
    static final int MAX = 600;
    static final float MIN_RATE = 0.75f, MAX_RATE = 2f;

    /** Afkortingen (kleine letters, zonder de laatste punt) waarna een punt geen einde van een zin is. */
    private static final Set<String> ABBR = new HashSet<>(Arrays.asList(
            // Nederlands
            "bijv", "bv", "b.v", "d.w.z", "o.a", "m.b.t", "i.p.v", "t.a.v", "m.a.w", "e.d", "e.a", "enz", "etc", "ca", "dhr", "mevr",
            "mw", "dr", "drs", "ir", "ing", "mr", "prof", "st", "nr", "blz", "vgl", "zgn", "jl", "a.s", "z.g.a.n", "resp", "evt",
            "ong", "incl", "excl", "max", "min", "tel", "afb", "hfst", "jr", "sr", "n.a.v", "i.v.m", "t.o.v", "v.d", "v.h", "o.i.d",
            // Engels
            "mrs", "ms", "messrs", "vs", "e.g", "i.e", "no", "vol", "fig", "approx", "inc", "ltd", "co", "mt", "gen", "col", "lt",
            "capt", "rev", "hon", "gov", "sgt", "cf", "ed", "eds", "pp", "ch", "dept", "est", "u.s", "u.k"));

    private static boolean terminal(char c) { return c == '.' || c == '!' || c == '?' || c == '…'; }
    private static boolean closer(char c) { return c == '"' || c == '\'' || c == '”' || c == '’' || c == ')' || c == ']' || c == '»' || c == '›'; }
    private static boolean space(char c) { return Character.isWhitespace(c) || c == ' '; }

    /** Is het woord vóór de punt op plek dot een afkorting of een voorletter (J. K. Rowling)? */
    static boolean abbreviation(String text, int from, int dot) {
        int b = dot;
        while (b > from && !space(text.charAt(b - 1)) && "(\"'“‘«".indexOf(text.charAt(b - 1)) < 0) b--;
        String word = text.substring(b, dot);
        if (word.isEmpty()) return false;
        if (word.length() == 1 && Character.isLetter(word.charAt(0)) && Character.isUpperCase(word.charAt(0))) return true; // voorletter
        return ABBR.contains(word.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Zinnen als [begin0, eind0, begin1, eind1, …] (posities in text, eind exclusief, zonder witruimte eromheen).
     * Een zin eindigt bij . ! ? … gevolgd door witruimte en geen kleine letter, of bij een regeleinde (een regel die
     * met een kleine letter verdergaat telt als dezelfde zin: hard afgebroken regels in tekstbestanden). Afkortingen en
     * getallen (3.5) breken geen zin; heel lange zinnen worden geknipt bij een komma of spatie.
     */
    static int[] split(String text) {
        if (text == null) return new int[0];
        List<int[]> out = new ArrayList<>();
        int n = text.length(), i = 0;
        while (i < n) {
            while (i < n && space(text.charAt(i))) i++;
            if (i >= n) break;
            int start = i, end = -1, j = start;
            while (j < n) {
                char c = text.charAt(j);
                if (c == '\n' || c == '\r') {
                    int k = j, lines = 0;
                    while (k < n && space(text.charAt(k))) { if (text.charAt(k) == '\n') lines++; k++; }
                    // Een lege regel (alinea) of een nieuwe regel met een hoofdletter, cijfer of teken: nieuwe zin
                    if (k >= n || lines > 1 || !Character.isLowerCase(text.charAt(k))) { end = j; break; }
                    j = k; continue; // hard afgebroken regel: de zin loopt door
                }
                if (terminal(c)) {
                    int k = j + 1;
                    while (k < n && (terminal(text.charAt(k)) || closer(text.charAt(k)))) k++;
                    if (k >= n) { end = k; break; }
                    if (!space(text.charAt(k))) { j = k; continue; } // 3.5, www.voorbeeld.nl, "Ja!"zei
                    if (c == '.' && k == j + 1 && abbreviation(text, start, j)) { j = k; continue; }
                    int m = k; while (m < n && space(text.charAt(m)) && text.charAt(m) != '\n') m++;
                    if (m < n && Character.isLowerCase(text.charAt(m))) { j = m; continue; } // “Wat?” zei hij.
                    end = k; break;
                }
                if (j - start >= MAX) { end = cut(text, start, j); break; }
                j++;
            }
            if (end < 0) end = n;
            int e = end; while (e > start && space(text.charAt(e - 1))) e--;
            if (e > start) out.add(new int[]{start, e});
            i = Math.max(end, start + 1);
        }
        int[] r = new int[out.size() * 2];
        for (int k = 0; k < out.size(); k++) { r[2 * k] = out.get(k)[0]; r[2 * k + 1] = out.get(k)[1]; }
        return r;
    }

    /** Knippen in een te lange zin: na de laatste komma, puntkomma of dubbele punt, anders na de laatste spatie. */
    private static int cut(String text, int start, int j) {
        int half = start + MAX / 2;
        for (int k = j; k > half; k--) { char c = text.charAt(k - 1); if ((c == ',' || c == ';' || c == ':') && space(text.charAt(k))) return k; }
        for (int k = j; k > half; k--) if (space(text.charAt(k))) return k;
        if (j > start && Character.isLowSurrogate(text.charAt(j)) && Character.isHighSurrogate(text.charAt(j - 1))) return j - 1; // geen emoji doorknippen
        return j;
    }

    static int count(int[] s) { return s == null ? 0 : s.length / 2; }

    /** De zin waarin de leesplek valt (of de eerste erna); 0 bij een onbekende plek, -1 zonder zinnen. */
    static int at(int[] s, int anchor) {
        int n = count(s);
        if (n == 0) return -1;
        if (anchor <= 0) return 0;
        for (int i = 0; i < n; i++) if (s[2 * i + 1] > anchor) return i;
        return n - 1;
    }

    /** Leesplek als deel van het hoofdstuk (0..1), voor de voortgang. */
    static double offset(int anchor, int length) { return length <= 0 ? 0 : Math.max(0, Math.min(1, anchor / (double) length)); }

    /** Volgend (dir 1) of vorig (dir -1) hoofdstuk in de leesvolgorde (losse noten slaan we over); -1 als er geen is. */
    static int neighbor(JSONArray linear, int count, int from, int dir) {
        for (int i = from + dir; i >= 0 && i < count; i += dir) if (linear == null || !Boolean.FALSE.equals(linear.opt(i))) return i;
        return -1;
    }

    static float clampRate(float r) { return Float.isNaN(r) ? 1f : Math.max(MIN_RATE, Math.min(MAX_RATE, r)); }

    /** Slaaptimer: "15", "30" of "60" minuten → tijdstip; "chapter" (einde hoofdstuk), "0" of iets anders → 0. */
    static long sleepUntil(long now, String choice) {
        if ("15".equals(choice) || "30".equals(choice) || "60".equals(choice)) return now + Long.parseLong(choice) * 60_000L;
        return 0;
    }

    /** Na een zin: stoppen omdat de tijd om is? */
    static boolean sleepDue(long now, long sleepAt) { return sleepAt > 0 && now >= sleepAt; }
}
