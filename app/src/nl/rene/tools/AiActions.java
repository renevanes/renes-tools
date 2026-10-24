package nl.rene.tools;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Acties van de assistent: een notitie maken, een herinnering zetten of iets in de agenda zetten. Alles hier is zuiver
 * rekenwerk (getest in AiActionsTest); er gebeurt nooit iets zonder dat Rene op de knop onder het antwoord tikt.
 *
 * Hoe het werkt: alleen als de vraag erom lijkt te vragen (wanted: "herinner me", "maak een notitie", "zet in mijn
 * agenda" …) krijgt het model achter de vraag de datum van vandaag en de komende dagen mee, met de opdracht het antwoord
 * te eindigen met één regel als [[herinnering: 2026-10-25 09:00 | Jos bellen]]. Die regel wordt hier gelezen en uit
 * het antwoord gehaald. Kleine modellen rekenen slecht met datums: staat er in de vraag zelf een datum of tijd
 * ("morgen om 9 uur", "zaterdag", "over 2 uur"), dan gaat die vóór wat het model ervan maakte. Is het moment
 * onbekend, voorbij of onzinnig ver weg, dan kiest Rene het zelf (need = "tijd" of "datum").
 *
 * Los van het geheugen: "onthoud dat …" is iets over jezelf voor de assistent (AiMemory) en nooit een actie;
 * "herinner me …" of "noteer …" is een actie en wordt nooit onthouden.
 */
final class AiActions {
    private AiActions() { }

    static final long MIN = 60_000L, DAY = 86_400_000L;

    /** Een voorgestelde actie. at = het moment (ms) of 0; day = begin van de dag (ms) of 0; need = wat Rene nog moet kiezen. */
    static final class Act {
        String type = "", title = "", text = "", need = "", src = "";
        long at, day;
        int sug = -1; // voorgestelde minuut van de dag voor de tijdkiezer (ochtend, middag, avond)
        boolean allDay;
    }

    /** Een datum en/of tijd uit gewone taal. day/min = -1 als onbekend; abs = vast moment ("over 2 uur") of -1. */
    static final class When {
        long day = -1, abs = -1;
        int min = -1, sug = -1;
        String rest = ""; // de tekst zonder de datum- en tijdwoorden
        boolean any() { return day >= 0 || min >= 0 || abs >= 0; }
    }

    private static final Pattern WANT = Pattern.compile("(?iu)(^|[^\\p{L}])(herinner\\p{L}*|notitie\\p{L}*|noteer|noteren|opschrijven|agenda|remind\\p{L}*|calendar"
            + "|schrijf\\s+(?:dat|dit|het)?\\s*op|vergeet\\s+(?:me\\s+|mij\\s+)?niet)([^\\p{L}]|$)");

    /** Lijkt de vraag om een notitie, herinnering of afspraak te vragen? Lange geplakte teksten niet. */
    static boolean wanted(String q) {
        if (q == null) return false;
        String s = q.trim();
        if (s.isEmpty() || s.length() > 600) return false;
        if (AiMemory.rememberCmd(s) != null || AiMemory.forgetCmd(s) != null) return false; // dat is het geheugen
        return WANT.matcher(s).find();
    }

    private static final String[] DAYS = {"zondag", "maandag", "dinsdag", "woensdag", "donderdag", "vrijdag", "zaterdag"};
    private static final String[] MONTHS = {"januari", "februari", "maart", "april", "mei", "juni", "juli", "augustus", "september", "oktober", "november", "december"};

    private static Calendar cal(long t, TimeZone tz) { Calendar c = Calendar.getInstance(tz, Locale.ROOT); c.setTimeInMillis(t); return c; }
    static long dayStart(long t, TimeZone tz) {
        Calendar c = cal(t, tz);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
    /** Dag + n dagen (met zomer- en wintertijd goed). */
    static long addDays(long day, int n, TimeZone tz) { Calendar c = cal(day, tz); c.add(Calendar.DAY_OF_MONTH, n); return dayStart(c.getTimeInMillis(), tz); }
    /** Moment op een dag (minuut van de dag), ook rond de klokwissel. */
    static long at(long day, int min, TimeZone tz) {
        Calendar c = cal(day, tz);
        c.set(Calendar.HOUR_OF_DAY, min / 60); c.set(Calendar.MINUTE, min % 60); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
    private static String two(int n) { return n < 10 ? "0" + n : String.valueOf(n); }
    static String iso(long t, TimeZone tz) { Calendar c = cal(t, tz); return c.get(Calendar.YEAR) + "-" + two(c.get(Calendar.MONTH) + 1) + "-" + two(c.get(Calendar.DAY_OF_MONTH)); }
    static String nice(long t, TimeZone tz) { Calendar c = cal(t, tz); return DAYS[c.get(Calendar.DAY_OF_WEEK) - 1] + " " + c.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[c.get(Calendar.MONTH)] + " " + c.get(Calendar.YEAR); }

    /** Wat het model achter de vraag meekrijgt (alleen als wanted). De datums staan er kant-en-klaar in: kleine
     *  modellen rekenen slecht met "volgende week dinsdag". */
    static String prompt(long now, TimeZone tz) {
        Calendar c = cal(now, tz);
        long today = dayStart(now, tz);
        StringBuilder s = new StringBuilder("Misschien wil de gebruiker een notitie, een herinnering of iets in de agenda. Het is nu ")
                .append(nice(now, tz)).append(", ").append(two(c.get(Calendar.HOUR_OF_DAY))).append(':').append(two(c.get(Calendar.MINUTE)))
                .append(" (").append(iso(now, tz)).append("). De komende dagen: ");
        for (int i = 1; i <= 7; i++) {
            long d = addDays(today, i, tz);
            s.append(i == 1 ? "morgen " : i == 2 ? "overmorgen " : "").append(DAYS[cal(d, tz).get(Calendar.DAY_OF_WEEK) - 1]).append(' ').append(iso(d, tz)).append(i < 7 ? ", " : ". ");
        }
        s.append("Vraagt de gebruiker daar echt om, zeg dan in één korte zin wat je voorstelt (de gebruiker bevestigt het zelf met een knop, dus zeg niet dat het al gedaan is) en eindig je antwoord met precies één van deze regels:\n")
                .append("[[herinnering: JJJJ-MM-DD UU:MM | wat er moet gebeuren]]\n")
                .append("[[notitie: korte titel | de tekst]]\n")
                .append("[[agenda: JJJJ-MM-DD UU:MM | titel van de afspraak]]\n")
                .append("Weet je de datum of de tijd niet, laat die dan leeg, bijvoorbeeld [[herinnering:  | Jos bellen]]. Vraagt de gebruiker er niet om, zet dan geen regel.");
        return s.toString();
    }

    // ---------- datum en tijd uit gewone taal ----------

    private static final String WD = "(maandag|dinsdag|woensdag|donderdag|vrijdag|zaterdag|zondag|ma|di|wo|do|vr|za|zo)";
    private static final String NUMW = "(een|één|1|2|3|4|5|6|7|8|9|10|\\d{1,3}|twee|drie|vier|vijf|zes|zeven|acht|negen|tien|twintig|dertig|veertig|vijftig|anderhalf|anderhalve)";
    private static final Pattern P_OVER = Pattern.compile("(?iu)(?<!kwart\\s)\\bover\\s+(?:een\\s+half(?:\\s+uur|\\s+uurtje)|een\\s+kwartier|" + NUMW + "\\s*(minuten|minuut|min|uur|uurtjes|uurtje|dagen|dag|weken|week))\\b");
    private static final Pattern P_ISO = Pattern.compile("\\b(20\\d\\d)-(\\d{1,2})-(\\d{1,2})\\b");
    private static final Pattern P_DMY = Pattern.compile("(?iu)\\b(?:op\\s+)?(\\d{1,2})[-/](\\d{1,2})(?:[-/](\\d{2,4}))?\\b");
    private static final Pattern P_DMON = Pattern.compile("(?iu)\\b(?:op\\s+)?(?:" + WD + "\\.?\\s+)?(\\d{1,2})\\s+(jan(?:uari)?|feb(?:ruari)?|maart|mrt|apr(?:il)?|mei|jun(?:i)?|jul(?:i)?|aug(?:ustus)?|sep(?:t(?:ember)?)?|okt(?:ober)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\.?(?:\\s+(20\\d\\d))?\\b");
    private static final Pattern P_REL = Pattern.compile("(?iu)\\b(?:voor\\s+|op\\s+)?(vandaag|vanavond|vanmiddag|vanochtend|vanmorgen|vannacht|overmorgen|morgen)(ochtend|middag|avond|vroeg)?\\b");
    private static final Pattern P_NEXTWEEK = Pattern.compile("(?iu)\\b(?:op\\s+)?(?:volgende|komende)\\s+week\\s+" + WD + "(ochtend|middag|avond)?\\b");
    private static final Pattern P_WD = Pattern.compile("(?iu)\\b(?:op\\s+|voor\\s+)?(?:volgende\\s+|aanstaande\\s+|komende\\s+|deze\\s+)?(maandag|dinsdag|woensdag|donderdag|vrijdag|zaterdag|zondag)(ochtend|middag|avond)?\\b");
    // "tussen 9 en 10", "van 9-10 uur", "9-10 uur": het begin telt
    private static final Pattern P_RANGE = Pattern.compile("(?iu)\\b(?:(?:tussen|van)\\s+(\\d{1,2})(?:[:.](\\d{2}))?\\s*(?:-|–|tot|en)\\s*\\d{1,2}(?:[:.]\\d{2})?(?:\\s*(?:uur|u)\\b)?|(\\d{1,2})(?:[:.](\\d{2}))?\\s*(?:-|–)\\s*\\d{1,2}(?:[:.]\\d{2})?\\s*(?:uur|u)\\b)");
    private static final Pattern P_HM = Pattern.compile("(?iu)\\b(?:om\\s+|vanaf\\s+|tegen\\s+)?(\\d{1,2})[:.](\\d{2})\\s*(?:uur|u)?\\b");
    private static final Pattern P_HALF = Pattern.compile("(?iu)\\b(?:om\\s+|tegen\\s+)?(?:(kwart)\\s+(over|voor)\\s+|(half)\\s+)(\\d{1,2})(?:\\s*uur)?\\b");
    private static final Pattern P_H = Pattern.compile("(?iu)\\b(?:om\\s+|tegen\\s+|vanaf\\s+)?(\\d{1,2})\\s*(?:uur|u)\\b|\\bom\\s+(\\d{1,2})\\b");
    private static final Pattern P_PART = Pattern.compile("(?iu)(?:'s\\s*|s\\s+)?\\b(ochtends|morgens|middags|avonds|nachts|in\\s+de\\s+ochtend|in\\s+de\\s+middag|in\\s+de\\s+avond)\\b");

    private static int num(String w) {
        if (w == null) return -1;
        switch (w.toLowerCase(Locale.ROOT)) {
            case "een": case "één": return 1;
            case "twee": return 2; case "drie": return 3; case "vier": return 4; case "vijf": return 5; case "zes": return 6;
            case "zeven": return 7; case "acht": return 8; case "negen": return 9; case "tien": return 10; case "twintig": return 20;
            case "dertig": return 30; case "veertig": return 40; case "vijftig": return 50;
            default: try { return Integer.parseInt(w); } catch (Exception e) { return -1; }
        }
    }
    private static int monthOf(String m) {
        String s = m.toLowerCase(Locale.ROOT);
        if (s.startsWith("mrt")) return 3;
        if (s.startsWith("oct")) return 10;
        for (int i = 0; i < MONTHS.length; i++) if (MONTHS[i].startsWith(s.length() >= 3 ? s.substring(0, 3) : s)) return i + 1;
        return -1;
    }
    private static int weekday(String w) {
        String s = w.toLowerCase(Locale.ROOT);
        for (int i = 0; i < DAYS.length; i++) if (DAYS[i].startsWith(s)) return i + 1; // Calendar.SUNDAY = 1
        return -1;
    }
    /** Dag (begin) van een datum, of -1 als die niet bestaat. Zonder jaar: dit jaar, of volgend jaar als hij al voorbij is. */
    private static long date(int y, int mo, int d, long today, TimeZone tz) {
        if (mo < 1 || mo > 12 || d < 1 || d > 31) return -1;
        boolean noYear = y <= 0;
        if (y > 0 && y < 100) y += 2000;
        Calendar c = cal(today, tz);
        if (noYear) y = c.get(Calendar.YEAR);
        c.clear(); c.setTimeZone(tz); c.setLenient(false);
        try { c.set(y, mo - 1, d, 0, 0, 0); long t = c.getTimeInMillis(); if (noYear && t < today) { c.set(y + 1, mo - 1, d, 0, 0, 0); t = c.getTimeInMillis(); } return t; }
        catch (Exception e) { return -1; }
    }
    /** Dagdeel → voorgestelde tijd, en of uren erin "middag/avond" zijn. 0 = ochtend, 1 = middag, 2 = avond, 3 = nacht. */
    private static int part(String p) {
        if (p == null) return -1;
        String s = p.toLowerCase(Locale.ROOT);
        if (s.contains("ochtend") || s.contains("morgen") || s.contains("vroeg")) return 0;
        if (s.contains("middag")) return 1;
        if (s.contains("avond")) return 2;
        if (s.contains("nacht")) return 3;
        return -1;
    }
    private static final int[] PART_SUG = {9 * 60, 14 * 60, 19 * 60, 23 * 60};

    /** Uur met dagdeel: 8 uur 's avonds = 20:00; zonder dagdeel en "1 tot 6 uur" (met één cijfer) = 's middags. */
    private static int hour(int h, int part, boolean oneDigit) {
        if (h < 0 || h > 24) return -1;
        if (h == 24) return 0;
        if ((part == 1 || part == 2) && h < 12) return part == 1 && h > 6 ? h : h + 12;
        if (part == 0 && h == 12) return 0;
        if (part == 3) return h == 12 ? 0 : h >= 9 && h <= 11 ? h + 12 : h; // 11 uur 's nachts = 23:00, 2 uur 's nachts = 02:00
        if (part < 0 && oneDigit && h >= 1 && h <= 6) return h + 12;
        return h;
    }

    private static final Pattern P_MONEY_AFTER = Pattern.compile("(?iu)^\\s*(?:euro|eur\\b|€|,-)");
    /** Een bedrag ("2.50 euro", "€ 9.95") is geen tijd. */
    private static boolean money(String w, int start, int end) {
        if (P_MONEY_AFTER.matcher(w.substring(end, Math.min(w.length(), end + 8))).find()) return true;
        String before = w.substring(Math.max(0, start - 3), start).trim();
        return before.endsWith("€") || before.toLowerCase(Locale.ROOT).endsWith("eur");
    }

    /** Dag-maand ("2-11", "2/11/2026"), maar niet als het een tijd of bereik is ("tussen 9-10", "9-10 uur") of "24/7". */
    private static Matcher dmy(String w, boolean withYear) {
        Matcher m = P_DMY.matcher(w);
        while (m.find()) {
            if ((m.group(3) != null) != withYear) continue;
            String after = w.substring(m.end(), Math.min(w.length(), m.end() + 6)).toLowerCase(Locale.ROOT);
            String before = w.substring(Math.max(0, m.start() - 8), m.start()).toLowerCase(Locale.ROOT);
            if (!withYear && (after.matches("^\\s*(uur|u)\\b.*") || before.matches(".*\\b(tussen|van)\\s*$") || m.group(0).trim().matches("(?:op\\s+)?(24/7|7/24)"))) continue;
            return m;
        }
        return null;
    }

    private static String blank(String w, Matcher m) {
        StringBuilder b = new StringBuilder(w);
        for (int i = m.start(); i < m.end(); i++) b.setCharAt(i, ' ');
        return b.toString();
    }

    /** Datum en tijd uit een zin ("morgen om 9 uur", "zaterdag", "25 okt 14:30", "over 2 uur", "volgende week dinsdag"). */
    static When when(String text, long now, TimeZone tz) {
        When r = new When();
        if (text == null || text.isEmpty()) return r;
        long today = dayStart(now, tz);
        String w = text.length() > 600 ? text.substring(0, 600) : text;
        int dayPart = -1;
        boolean vannacht = false;
        Matcher m;
        // "over 2 uur", "over een kwartier", "over 3 dagen"
        if ((m = P_OVER.matcher(w)).find()) {
            String g = m.group(0).toLowerCase(Locale.ROOT);
            String unit = m.group(2) == null ? "" : m.group(2).toLowerCase(Locale.ROOT);
            int n = num(m.group(1));
            boolean oneHalf = m.group(1) != null && m.group(1).toLowerCase(Locale.ROOT).startsWith("anderhal"); // anderhalf, anderhalve = 1,5 ×
            if (oneHalf) {
                if (unit.startsWith("min")) r.abs = now + 90_000L;
                else if (unit.startsWith("uur")) r.abs = now + 90 * MIN;
                else if (unit.startsWith("dag")) r.abs = now + 36 * 60 * MIN;
                else if (unit.startsWith("we")) r.day = addDays(today, 11, tz); // 10,5 dag
                w = blank(w, m);
            } else {
                if (g.contains("een half")) { n = 30; unit = "min"; }
                else if (g.contains("kwartier")) { n = 15; unit = "min"; }
                if (n > 0) {
                    if (unit.startsWith("min")) r.abs = now + n * MIN;
                    else if (unit.startsWith("uur")) r.abs = now + n * 60 * MIN;
                    else if (unit.startsWith("dag")) r.day = addDays(today, n, tz);
                    else if (unit.startsWith("we")) r.day = addDays(today, 7 * n, tz);
                    w = blank(w, m);
                }
            }
        }
        // datums
        if (r.abs < 0 && r.day < 0 && (m = P_ISO.matcher(w)).find()) { r.day = date(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), today, tz); w = blank(w, m); }
        if (r.abs < 0 && r.day < 0 && (m = P_DMON.matcher(w)).find()) { r.day = date(m.group(4) == null ? 0 : Integer.parseInt(m.group(4)), monthOf(m.group(3)), Integer.parseInt(m.group(2)), today, tz); w = blank(w, m); }
        // 2-11-2026 (met jaar) is altijd een datum; 2-11 zonder jaar pas na morgen/zaterdag, en niet bij een tijd ("9-10 uur")
        if (r.abs < 0 && r.day < 0 && (m = dmy(w, true)) != null) { r.day = date(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)), today, tz); w = blank(w, m); }
        if (r.abs < 0 && r.day < 0 && (m = P_REL.matcher(w)).find()) {
            String g = m.group(1).toLowerCase(Locale.ROOT);
            int add = g.equals("overmorgen") ? 2 : g.equals("morgen") ? 1 : 0;
            r.day = addDays(today, add, tz);
            vannacht = g.equals("vannacht");
            dayPart = g.equals("vanavond") ? 2 : g.equals("vanmiddag") ? 1 : g.equals("vanochtend") || g.equals("vanmorgen") ? 0 : g.equals("vannacht") ? 3 : part(m.group(2));
            w = blank(w, m);
        }
        if (r.abs < 0 && r.day < 0 && (m = P_NEXTWEEK.matcher(w)).find()) {
            int wd = weekday(m.group(1));
            // maandag van volgende week + de dag
            Calendar c = cal(today, tz);
            int dow = c.get(Calendar.DAY_OF_WEEK); // 1 = zondag
            int toMonday = ((Calendar.MONDAY - dow) + 7) % 7; if (toMonday == 0) toMonday = 7;
            int off = (wd + 5) % 7; // maandag 0 … zondag 6
            r.day = addDays(today, toMonday + off, tz);
            dayPart = part(m.group(2));
            w = blank(w, m);
        }
        if (r.abs < 0 && r.day < 0 && (m = P_WD.matcher(w)).find()) {
            int wd = weekday(m.group(1));
            int dow = cal(today, tz).get(Calendar.DAY_OF_WEEK);
            int add = ((wd - dow) + 7) % 7; if (add == 0) add = 7; // "zaterdag" op zaterdag = volgende week
            r.day = addDays(today, add, tz);
            dayPart = part(m.group(2));
            w = blank(w, m);
        }
        if (r.abs < 0 && r.day < 0 && (m = dmy(w, false)) != null) {
            long d = date(0, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)), today, tz);
            if (d >= 0) { r.day = d; w = blank(w, m); }
        }
        // dagdeel los ("'s avonds")
        if ((m = P_PART.matcher(w)).find()) { if (dayPart < 0) dayPart = part(m.group(1)); w = blank(w, m); }
        // tijden
        if (r.abs < 0) {
            if ((m = P_RANGE.matcher(w)).find()) {
                String hs = m.group(1) != null ? m.group(1) : m.group(3), ms = m.group(1) != null ? m.group(2) : m.group(4);
                int hh = hour(Integer.parseInt(hs), dayPart, hs.length() == 1), mi = ms == null ? 0 : Integer.parseInt(ms);
                if (hh >= 0 && hh < 24 && mi < 60) { r.min = hh * 60 + mi; w = blank(w, m); }
            }
            m = P_HM.matcher(w);
            while (r.min < 0 && m.find()) {
                if (money(w, m.start(), m.end())) continue; // 2.50 euro is geen tijd
                int h = Integer.parseInt(m.group(1)), mi = Integer.parseInt(m.group(2));
                int hh = hour(h, dayPart, m.group(1).length() == 1);
                if (hh >= 0 && hh < 24 && mi < 60) { r.min = hh * 60 + mi; w = blank(w, m); }
            }
            if (r.min < 0 && (m = P_HALF.matcher(w)).find()) {
                int h = Integer.parseInt(m.group(4));
                boolean over = m.group(3) == null && "over".equalsIgnoreCase(m.group(2));
                // half 7 en kwart voor 7 vallen in het uur ervóór (6): daarop de regel "1 tot 6 = middag", net als bij "om 6"
                int raw = over ? h : (h + 23) % 24, rawH = raw == 0 ? 12 : raw;
                int base = hour(rawH, dayPart, rawH < 10);
                if (h <= 24 && base >= 0 && base < 24) {
                    int min = base * 60 + (over ? 15 : m.group(3) != null ? 30 : 45);
                    r.min = min % (24 * 60); w = blank(w, m);
                }
            }
            if (r.min < 0 && (m = P_H.matcher(w)).find()) {
                String g = m.group(1) != null ? m.group(1) : m.group(2);
                int hh = hour(Integer.parseInt(g), dayPart, g.length() == 1);
                if (hh >= 0 && hh < 24) { r.min = hh * 60; w = blank(w, m); }
            }
        }
        // "vannacht om 2 uur" 's middags gevraagd: de komende nacht
        if (vannacht && r.min >= 0 && r.min < 12 * 60 && at(r.day, r.min, tz) <= now) r.day = addDays(r.day, 1, tz);
        if (dayPart >= 0) r.sug = PART_SUG[dayPart];
        r.rest = w.replaceAll("\\s+", " ").trim();
        return r;
    }

    private static final Pattern M_ISO = Pattern.compile("^(20\\d\\d)-(\\d{1,2})-(\\d{1,2})(?:[ T]+(\\d{1,2})[:.](\\d{2}))?");
    private static final Pattern M_DMY = Pattern.compile("^(\\d{1,2})-(\\d{1,2})-(20\\d\\d)(?:[ T]+(\\d{1,2})[:.](\\d{2}))?");
    private static final Pattern M_HM = Pattern.compile("^(\\d{1,2})[:.](\\d{2})$");

    /** Het moment dat het model opgaf ("2026-10-25 09:00", "25-10-2026", of toch weer "morgen 9 uur"). */
    static When modelWhen(String s, long now, TimeZone tz) {
        When r = new When();
        if (s == null) return r;
        s = s.trim();
        if (s.isEmpty() || s.matches("(?i)[jmdu?:\\-\\s]*")) return r; // leeg, of de voorbeeldtekst "JJJJ-MM-DD UU:MM"
        long today = dayStart(now, tz);
        Matcher m = M_ISO.matcher(s);
        boolean iso = m.find();
        if (!iso) { m = M_DMY.matcher(s); if (!m.find()) m = null; }
        if (m != null) {
            int y = Integer.parseInt(iso ? m.group(1) : m.group(3)), mo = Integer.parseInt(m.group(2)), d = Integer.parseInt(iso ? m.group(3) : m.group(1));
            r.day = date(y, mo, d, today, tz);
            if (m.group(4) != null) { int h = Integer.parseInt(m.group(4)), mi = Integer.parseInt(m.group(5)); if (h < 24 && mi < 60) r.min = h * 60 + mi; }
            return r;
        }
        Matcher t = M_HM.matcher(s);
        if (t.find()) { int h = Integer.parseInt(t.group(1)), mi = Integer.parseInt(t.group(2)); if (h < 24 && mi < 60) r.min = h * 60 + mi; return r; }
        return when(s, now, tz);
    }

    /** Een zinnig moment: in de toekomst en hooguit twee jaar vooruit. */
    static boolean sane(long at, long now) { return at > now + 30_000L && at < now + 730 * DAY; }

    private static final Pattern LINE = Pattern.compile("(?iu)`*\\[\\[\\s*(notitie|note|herinnering|reminder|agenda|afspraak|calendar)\\s*[:：]\\s*([^\\]\\n]{0,800})\\]{1,2}`*");

    /** Het antwoord zonder de actieregel(s). */
    static String strip(String answer) {
        if (answer == null) return "";
        if (answer.indexOf('[') < 0) return answer;
        String s = LINE.matcher(answer).replaceAll("");
        return s.replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static String tidy(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("[\\u0000-\\u001f]", " ").replaceAll("\\s+", " ").trim();
        t = t.replaceAll("^[\"'«“„]+|[\"'»”]+$", "").trim();
        t = t.replaceAll("(?iu)^(aan|om|dat|op|voor|eraan|er\\s+aan|me|mij|te)\\s+", "").replaceAll("[\\s,;:.]+$", "").trim();
        t = t.replaceAll("(?iu)^(aan|om|dat|op|voor|eraan|te)\\s+", "").trim();
        if (t.length() > max) { int n = max; if (Character.isHighSurrogate(t.charAt(n - 1))) n--; t = t.substring(0, n).trim() + "…"; }
        return t.isEmpty() ? t : t.substring(0, 1).toUpperCase(Locale.ROOT) + t.substring(1);
    }

    /** Een titel uit een tekst: de eerste regel of zin, hooguit 60 tekens. */
    static String titleOf(String text) {
        String t = text == null ? "" : text.trim();
        int nl = t.indexOf('\n'); if (nl > 0) t = t.substring(0, nl);
        return tidy(t, 60);
    }

    /** Een actie uit het antwoord (de regel van het model), of anders uit een duidelijke opdracht in de vraag; null = niets. */
    static Act parse(String answer, String question, long now, TimeZone tz) {
        Matcher m = answer == null ? null : LINE.matcher(answer);
        Act a = null;
        if (m != null) while (m.find()) { // de laatste regel telt
            Act x = fromLine(m.group(1), m.group(2), question, now, tz);
            if (x != null) a = x;
        }
        if (a == null) a = fromQuestion(question, now, tz);
        return a;
    }

    private static String typeOf(String w) {
        String s = w.toLowerCase(Locale.ROOT);
        return s.startsWith("herinner") || s.startsWith("remind") ? "herinnering" : s.startsWith("agenda") || s.startsWith("afspraak") || s.startsWith("calendar") ? "agenda" : "notitie";
    }

    static Act fromLine(String kind, String body, String question, long now, TimeZone tz) {
        String type = typeOf(kind);
        String[] f = (body == null ? "" : body).split("\\|", -1);
        Act a = new Act();
        a.type = type; a.src = "model";
        if ("notitie".equals(type)) {
            String t = f.length > 1 ? f[0] : "", x = f.length > 1 ? join(f, 1) : f[0];
            a.text = tidyText(x);
            a.title = tidy(t, 60);
            if (a.title.isEmpty()) a.title = titleOf(a.text);
            if (a.text.equalsIgnoreCase(a.title)) a.text = "";
            if (a.title.isEmpty()) return null;
            return a;
        }
        String whenS = f.length > 1 ? f[0] : "", what = f.length > 1 ? join(f, 1) : f[0];
        if (f.length == 1) { // geen "|": misschien "2026-10-25 09:00 Jos bellen"
            Matcher d = Pattern.compile("^\\s*(20\\d\\d-\\d{1,2}-\\d{1,2}(?:[ T]+\\d{1,2}[:.]\\d{2})?)\\s+(.+)$").matcher(what);
            if (d.find()) { whenS = d.group(1); what = d.group(2); }
        }
        a.title = tidy(what, 120);
        if (a.title.isEmpty()) {
            // Geen tekst: dan uit de vraag (zonder datum en opdracht)
            Act q = fromQuestion(question, now, tz);
            if (q == null || q.title.isEmpty()) return null;
            a.title = q.title;
        }
        resolve(a, when(question, now, tz), modelWhen(whenS, now, tz), now, tz);
        return a;
    }

    private static String tidyText(String s) {
        String t = s == null ? "" : s.replace("\\n", "\n").trim();
        t = t.replaceAll("^[\"'«“„]+|[\"'»”]+$", "").trim();
        return t.length() > 4000 ? t.substring(0, 4000) : t;
    }
    private static String join(String[] f, int from) {
        StringBuilder b = new StringBuilder();
        for (int i = from; i < f.length; i++) { if (b.length() > 0) b.append(" | "); b.append(f[i].trim()); }
        return b.toString();
    }

    /** Het moment kiezen: wat in de vraag staat gaat voor wat het model ervan maakte (dat rekent vaak mis). */
    static void resolve(Act a, When q, When mdl, long now, TimeZone tz) {
        long today = dayStart(now, tz);
        long at = 0, day = -1; int min = -1;
        if (q.abs > 0) at = q.abs;
        else if (q.day < 0 && q.min < 0 && mdl.abs > 0) at = mdl.abs;
        else {
            day = q.day >= 0 ? q.day : mdl.day;
            // De tijd van het model alleen als die bij dezelfde dag hoort (of de vraag geen dag noemde)
            min = q.min >= 0 ? q.min : (q.day < 0 || q.day == mdl.day) ? mdl.min : -1;
            if (q.min >= 0 && q.day < 0 && mdl.day >= 0) day = mdl.day; // alleen een tijd in de vraag: de dag van het model
            if (day < 0 && min >= 0) { day = today; if (at(day, min, tz) <= now + 60_000L) day = addDays(today, 1, tz); } // "om 15:00": vandaag, of morgen als het al later is
            if (day >= 0 && min >= 0) at = at(day, min, tz);
        }
        a.sug = q.sug >= 0 ? q.sug : mdl.sug;
        if (at > 0) {
            if (sane(at, now)) { a.at = at; a.day = dayStart(at, tz); a.need = ""; return; }
            a.at = 0; a.day = 0; a.need = "datum"; // voorbij of veel te ver weg: zelf kiezen
            return;
        }
        if (day >= 0 && day >= today && day < now + 730 * DAY) {
            a.day = day;
            if ("agenda".equals(a.type)) { a.allDay = true; a.need = ""; }
            else a.need = "tijd";
            return;
        }
        a.need = "datum";
    }

    private static final Pattern Q_REMIND = Pattern.compile("(?iu)^\\s*(?:(?:kun|kan|wil)\\s+je\\s+|graag\\s+)?(?:me\\s+|mij\\s+)?(?:herinner(?:en)?|remind)\\s+(?:me\\s+|mij\\s+)?(?:er\\s*aan\\s+|eraan\\s+)?(.+?)\\s*\\??$");
    private static final Pattern Q_REMIND2 = Pattern.compile("(?iu)^\\s*(?:(?:kun|kan|wil)\\s+je\\s+)?(?:zet|maak|stel)\\s+(?:een\\s+)?herinnering\\s*(?:in\\s+)?(.+?)\\s*\\??$");
    private static final Pattern Q_REMIND3 = Pattern.compile("(?iu)^\\s*(?:(?:kun|kan|wil)\\s+je\\s+)(?:me|mij)\\s+(?:er\\s*aan|eraan)\\s+herinneren\\s+(.+?)\\s*\\??$");
    private static final Pattern Q_NOTE = Pattern.compile("(?iu)^\\s*(?:(?:kun|kan|wil)\\s+je\\s+)?(?:(?:maak|schrijf)\\s+(?:een\\s+)?notitie(?:\\s+(?:van|over|met))?|noteer|schrijf\\s+op)\\s*[:,]?\\s*(.+?)\\s*$");
    private static final Pattern Q_AGENDA = Pattern.compile("(?iu)^\\s*(?:(?:kun|kan|wil)\\s+je\\s+)?zet\\s+(.+?)\\s+in\\s+(?:mijn|m'n|de)\\s+agenda\\b\\s*(.*?)\\s*\\??$");

    /** Een duidelijke opdracht in de vraag zelf ("Herinner me morgen om 9 uur aan de vuilnis"), voor als het model
     *  de regel vergat. Alleen bij een zin die met de opdracht begint; anders null. */
    static Act fromQuestion(String q, long now, TimeZone tz) {
        if (q == null || q.length() > 600) return null;
        Matcher m;
        Act a = new Act();
        a.src = "vraag";
        String what;
        if ((m = Q_NOTE.matcher(q)).matches()) {
            a.type = "notitie";
            a.text = tidyText(m.group(1));
            a.title = titleOf(a.text);
            if (a.text.equalsIgnoreCase(a.title)) a.text = "";
            return a.title.isEmpty() ? null : a;
        }
        if ((m = Q_AGENDA.matcher(q)).matches()) { a.type = "agenda"; what = m.group(1) + " " + m.group(2); }
        else if ((m = Q_REMIND2.matcher(q)).matches() || (m = Q_REMIND3.matcher(q)).matches() || (m = Q_REMIND.matcher(q)).matches()) { a.type = "herinnering"; what = m.group(1); }
        else return null;
        When w = when(what, now, tz);
        a.title = tidy(w.rest.replaceAll("(?iu)^(?:voor|op|om)\\s+", "").replaceAll("(?iu)^[:,]\\s*", ""), 120);
        if (a.title.isEmpty()) return null;
        resolve(a, w, new When(), now, tz);
        return a;
    }
}
