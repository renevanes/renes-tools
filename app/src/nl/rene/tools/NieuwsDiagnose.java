package nl.rene.tools;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Logboek van het inlogvenster van Nieuws ({@link NieuwsInlogActivity}): wat de pagina opvraagt, welke fouten de site
 * geeft en welke vensters Tools stil wegklikt. Alleen in het geheugen, alleen zolang het venster open is; Rene kan het
 * kopiëren als het inloggen blijft hangen. Zonder geheimen: adressen zonder ?… en #… (daar staan inlogcodes in), lange
 * reeksen die op een sleutel lijken en e-mailadressen worden "…", van cookies alleen de namen.
 * Zuiver Java (geen Android), getest in NieuwsDiagnoseTest.
 */
final class NieuwsDiagnose {
    static final int MAX = 400, MAX_REGEL = 300;
    private static final Pattern MAIL = Pattern.compile("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,253}\\.[A-Za-z]{2,24}");
    /** Iets wat op een sleutel of code lijkt: 24 of meer letters, cijfers, - of _ achter elkaar, met minstens één cijfer. */
    private static final Pattern SLEUTEL = Pattern.compile("(?=[A-Za-z_-]{0,200}[0-9])[A-Za-z0-9_-]{24,}");

    private final ArrayDeque<String> regels = new ArrayDeque<>();
    private final long begin;
    private int weg;

    NieuwsDiagnose(long nu) { begin = nu; }

    /** Een regel: tijd sinds het openen, soort en tekst (ingekort en zonder geheimen). */
    synchronized void log(long nu, String soort, String tekst) {
        String t = tekst == null ? "" : tekst.length() > 4 * MAX_REGEL ? tekst.substring(0, 4 * MAX_REGEL) : tekst;
        t = verberg(t.replace('\n', ' ').replace('\r', ' '));
        if (t.length() > MAX_REGEL) t = t.substring(0, MAX_REGEL) + "…";
        long ms = Math.max(0, nu - begin);
        regels.addLast(String.format(Locale.ROOT, "%5d.%01ds %-7s %s", ms / 1000, (ms % 1000) / 100, soort, t));
        while (regels.size() > MAX) { regels.removeFirst(); weg++; }
    }

    synchronized int aantal() { return regels.size(); }

    /** Alles als tekst, met een kop (wat Tools over het venster weet) erboven. */
    synchronized String tekst(String kop) {
        StringBuilder sb = new StringBuilder();
        if (kop != null && !kop.isEmpty()) sb.append(kop).append('\n');
        if (weg > 0) sb.append("(").append(weg).append(" oudere regels weggelaten)\n"); // taal-vast: logboek voor de ontwikkelaar
        for (String r : regels) sb.append(r).append('\n');
        return sb.toString();
    }

    /** Een adres zonder vraag en anker (inlogcodes), met lange sleutels en e-mailadressen weggehaald. */
    static String schoon(String url) {
        if (url == null) return "";
        String u = url;
        if (u.regionMatches(true, 0, "data:", 0, 5)) return "data:…";
        if (u.regionMatches(true, 0, "blob:", 0, 5)) return "blob:" + schoon(u.substring(5));
        try {
            URI x = new URI(u);
            if (x.getScheme() != null && x.getRawAuthority() != null) {
                String pad = x.getRawPath() == null ? "" : x.getRawPath();
                u = x.getScheme().toLowerCase(Locale.ROOT) + "://" + x.getRawAuthority().replaceFirst("^.*@", "") + pad
                        + (x.getRawQuery() != null ? "?…" : "") + (x.getRawFragment() != null ? "#…" : "");
                return verberg(u);
            }
        } catch (Throwable ignored) { }
        int q = u.length();
        for (char c : new char[]{'?', '#'}) { int i = u.indexOf(c); if (i >= 0 && i < q) q = i; }
        return verberg(q < u.length() ? u.substring(0, q) + "?…" : u);
    }

    /** E-mailadressen en reeksen die op een sleutel lijken weg. */
    static String verberg(String s) {
        if (s == null || s.isEmpty()) return "";
        return SLEUTEL.matcher(MAIL.matcher(s).replaceAll("…@…")).replaceAll("…");
    }
}
