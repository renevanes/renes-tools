package nl.rene.tools;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Logboek van het inlogvenster van Nieuws ({@link NieuwsInlogActivity}): wat de pagina opvraagt, welke fouten de site
 * geeft en welke vensters Tools stil wegklikt. Alleen in het geheugen, alleen zolang het venster open is; Rene kan het
 * kopiëren als het inloggen blijft hangen. Zonder geheimen: adressen zonder ?… en #… (daar staan inlogcodes in), ook in
 * vrije tekst (foutmeldingen), lange reeksen die op een sleutel lijken en e-mailadressen worden "…", van cookies alleen
 * de namen. Fouten en de eerste regels blijven altijd bewaard; gelijke regels na elkaar worden één regel met een teller.
 * Zuiver Java (geen Android), getest in NieuwsDiagnoseTest.
 */
final class NieuwsDiagnose {
    static final int MAX = 2000, MAX_FOUTEN = 200, BEGIN = 30, MAX_REGEL = 300, MAX_IN = 2000;
    /** Soorten die altijd bewaard blijven (ook als het logboek vol is). */
    static final String[] FOUTSOORTEN = {"HTTP", "FOUT", "SSL", "STOP", "JS-FOUT", "venster"};
    private static final Pattern MAIL = Pattern.compile("[A-Za-z0-9._%+-]{1,64}(?:@|%40)[A-Za-z0-9.-]{1,253}\\.[A-Za-z]{2,24}");
    /** Kandidaat voor een sleutel of code: 24 of meer letters, cijfers, - of _ achter elkaar (wat het is, beslist {@link #sleutel}). */
    private static final Pattern REEKS = Pattern.compile("[A-Za-z0-9_-]{24,}");
    /** Een adres in vrije tekst (een foutmelding van de pagina). */
    private static final Pattern ADRES = Pattern.compile("(?i)\\b(?:https?|wss?|blob):[^\\s\"'<>()]{1,2000}");

    private final ArrayDeque<String> regels = new ArrayDeque<>(), fouten = new ArrayDeque<>();
    private final ArrayList<String> begin = new ArrayList<>();
    private final long start;
    private int weg, foutenWeg, herhaald;
    private String vorige = "";

    NieuwsDiagnose(long nu) { start = nu; }

    /** Een regel: tijd sinds het openen, soort en tekst (ingekort en zonder geheimen). */
    synchronized void log(long nu, String soort, String tekst) {
        String t = verberg(tekst == null ? "" : tekst.replace('\n', ' ').replace('\r', ' '));
        if (t.length() > MAX_REGEL) t = t.substring(0, MAX_REGEL) + "…";
        String sleutel = soort + " " + t;
        if (sleutel.equals(vorige) && (!regels.isEmpty() || !begin.isEmpty())) {
            // dezelfde regel nog eens: alleen tellen (scripts die steeds hetzelfde opvragen of melden)
            herhaald++;
            boolean inBegin = regels.isEmpty();
            String laatste = inBegin ? begin.remove(begin.size() - 1) : regels.removeLast();
            int i = laatste.lastIndexOf(" (×");
            String nieuw = (i > 0 && laatste.endsWith(")") ? laatste.substring(0, i) : laatste) + " (×" + (herhaald + 1) + ")";
            if (inBegin) begin.add(nieuw); else regels.addLast(nieuw);
            return;
        }
        vorige = sleutel;
        herhaald = 0;
        long ms = Math.max(0, nu - start);
        String r = String.format(Locale.ROOT, "%5d.%01ds %-7s %s", ms / 1000, (ms % 1000) / 100, soort, t);
        if (begin.size() < BEGIN && regels.isEmpty()) begin.add(r);
        else {
            regels.addLast(r);
            while (regels.size() > MAX) { regels.removeFirst(); weg++; }
        }
        for (String f : FOUTSOORTEN) if (f.equals(soort)) {
            fouten.addLast(r);
            while (fouten.size() > MAX_FOUTEN) { fouten.removeFirst(); foutenWeg++; }
            break;
        }
    }

    synchronized int aantal() { return begin.size() + regels.size(); }

    /** Alles als tekst: een kop (wat Tools over het venster weet), de fouten en tegengehouden adressen, dan alles op volgorde. */
    synchronized String tekst(String kop) {
        StringBuilder sb = new StringBuilder();
        if (kop != null && !kop.isEmpty()) sb.append(kop).append('\n');
        sb.append("Een aanvraag zonder HTTP- of FOUT-regel erna is gelukt of loopt nog.\n"); // taal-vast: logboek voor de ontwikkelaar
        if (!fouten.isEmpty()) {
            sb.append("\n== Fouten en tegengehouden ==\n"); // taal-vast: logboek voor de ontwikkelaar
            if (foutenWeg > 0) sb.append("(").append(foutenWeg).append(" oudere weggelaten)\n"); // taal-vast: logboek voor de ontwikkelaar
            for (String r : fouten) sb.append(r).append('\n');
        }
        sb.append("\n== Alles ==\n"); // taal-vast: logboek voor de ontwikkelaar
        for (String r : begin) sb.append(r).append('\n');
        if (weg > 0) sb.append("(").append(weg).append(" regels weggelaten)\n"); // taal-vast: logboek voor de ontwikkelaar
        for (String r : regels) sb.append(r).append('\n');
        return sb.toString();
    }

    /** Een adres zonder vraag, anker en gebruiker, met lange sleutels en e-mailadressen weggehaald. */
    static String schoon(String url) {
        if (url == null) return "";
        String u = url.length() > MAX_IN ? url.substring(0, MAX_IN) : url;
        if (u.regionMatches(true, 0, "data:", 0, 5)) return "data:…";
        if (u.regionMatches(true, 0, "blob:", 0, 5)) return "blob:" + schoon(u.substring(5));
        try {
            URI x = new URI(u);
            if (x.getScheme() != null && x.getRawAuthority() != null) {
                String pad = x.getRawPath() == null ? "" : x.getRawPath();
                return masker(x.getScheme().toLowerCase(Locale.ROOT) + "://" + x.getRawAuthority().replaceFirst("^.*@", "") + pad
                        + (x.getRawQuery() != null ? "?…" : "") + (x.getRawFragment() != null ? "#…" : ""));
            }
        } catch (Throwable ignored) { }
        // onleesbaar adres: toch gebruiker/wachtwoord, vraag en anker weg
        u = u.replaceFirst("^([A-Za-z][A-Za-z0-9+.-]{0,30}:)?//[^/@?#]{0,500}@", "$1//");
        int q = u.length();
        for (char c : new char[]{'?', '#'}) { int i = u.indexOf(c); if (i >= 0 && i < q) q = i; }
        return masker(q < u.length() ? u.substring(0, q) + "?…" : u);
    }

    /** Vrije tekst (een foutmelding van de pagina): adressen erin geschoond, e-mailadressen en sleutels weg. */
    static String verberg(String s) {
        if (s == null || s.isEmpty()) return "";
        String t = s.length() > 4 * MAX_IN ? s.substring(0, 4 * MAX_IN) : s;
        Matcher m = ADRES.matcher(t);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(schoon(m.group())));
        m.appendTail(sb);
        return masker(sb.toString());
    }

    /** E-mailadressen (ook met %40) en reeksen die op een sleutel lijken weg. */
    static String masker(String s) {
        if (s == null || s.isEmpty()) return "";
        String t = MAIL.matcher(s).replaceAll("…@…");
        Matcher m = REEKS.matcher(t);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(sleutel(m.group()) ? "…" : m.group()));
        m.appendTail(sb);
        return sb.toString();
    }

    /** Lijkt op een sleutel: met een cijfer, of 32+ tekens met hoofd- én kleine letters (een titel in een adres is klein). */
    static boolean sleutel(String r) {
        boolean cijfer = false, hoog = false, laag = false;
        for (int i = 0; i < r.length(); i++) {
            char c = r.charAt(i);
            if (c >= '0' && c <= '9') cijfer = true;
            else if (c >= 'A' && c <= 'Z') hoog = true;
            else if (c >= 'a' && c <= 'z') laag = true;
        }
        return cijfer || (r.length() >= 32 && hoog && laag);
    }
}
