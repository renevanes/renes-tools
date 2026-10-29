package nl.rene.tools;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Check-in: het zuivere rekenwerk (zonder Android of JSON, getest in CheckinTest). Wanneer is het volgende moment,
 * welke periode hoort erbij (dag, week, maand of een tijdstip op een dag), staat een periode open, is hij ingevuld
 * of gemist, hoe lang is de reeks, en hoe vaak werd elk antwoord gekozen. De opslag staat in CheckinStore, de
 * meldingen in CheckinNotify.
 *
 * Periodes:
 *  - elke dag, werkdagen, gekozen dagen: "2026-10-29"
 *  - elke week (op een gekozen dag): "2026-W44" (ISO-week, begint op maandag)
 *  - elke maand (op een gekozen dag; 31 = de laatste dag in korte maanden): "2026-10"
 *  - meerdere keren per dag: "2026-10-29T21:00" (per tijdstip een eigen periode)
 */
final class Checkin {

    private Checkin() { }

    static final String[] RHYTHMS = {"daily", "workdays", "weekdays", "weekly", "monthly", "multi"};

    /** Het ritme van één vraag. */
    static final class Rule {
        String rhythm = "daily";
        /** [1..7] = maandag..zondag (ISO); voor "weekdays" en "multi". */
        boolean[] days = {false, true, true, true, true, true, true, true};
        int wday = 5;              // elke week: 1 = maandag … 7 = zondag
        int dom = 1;               // elke maand: 1..31
        int[] times = {21 * 60};   // minuten na middernacht, oplopend (alleen "multi" gebruikt er meer dan één)
        int until = 23 * 60 + 59;  // tot wanneer je kunt antwoorden (minuten na middernacht)
        boolean quietWeekend;      // geen melding in het weekend (en dat telt niet als gemist)
        String pauseUntil = "";    // "yyyy-MM-dd": tot en met die dag stil (vakantie)
        long created;              // periodes die al voorbij waren toen de vraag er kwam, tellen niet mee
    }

    /** Eén periode: het moment van de melding, tot wanneer je kunt antwoorden en de sleutel. */
    static final class Slot {
        final long at, end;
        final String key;
        final boolean quiet;
        Slot(long at, long end, String key, boolean quiet) { this.at = at; this.end = end; this.key = key; this.quiet = quiet; }
    }

    // ---------- datums ----------

    /** 1 = maandag … 7 = zondag. */
    static int isoDow(Calendar k) { return (k.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1; }

    static String dateKey(Calendar k) {
        return String.format(Locale.ROOT, "%04d-%02d-%02d", k.get(Calendar.YEAR), k.get(Calendar.MONTH) + 1, k.get(Calendar.DAY_OF_MONTH));
    }

    static String dateKey(long t, TimeZone tz) { Calendar k = Calendar.getInstance(tz); k.setTimeInMillis(t); return dateKey(k); }

    /** ISO-week: "2026-W44" (het jaar van de donderdag van die week). */
    static String weekKey(Calendar day) {
        Calendar k = (Calendar) day.clone();
        k.add(Calendar.DAY_OF_MONTH, 4 - isoDow(k)); // donderdag van dezelfde week
        int year = k.get(Calendar.YEAR), doy = k.get(Calendar.DAY_OF_YEAR);
        return String.format(Locale.ROOT, "%04d-W%02d", year, (doy - 1) / 7 + 1);
    }

    static String monthKey(Calendar k) { return String.format(Locale.ROOT, "%04d-%02d", k.get(Calendar.YEAR), k.get(Calendar.MONTH) + 1); }

    static boolean validDate(String s) { return s != null && s.matches("\\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])"); }

    static boolean validTime(String s) { return s != null && s.matches("([01]\\d|2[0-3]):[0-5]\\d"); }

    static int minutes(String hhmm) { return validTime(hhmm) ? Integer.parseInt(hhmm.substring(0, 2)) * 60 + Integer.parseInt(hhmm.substring(3)) : -1; }

    static String hhmm(int min) { return String.format(Locale.ROOT, "%02d:%02d", min / 60, min % 60); }

    /** Middernacht van een "yyyy-MM-dd" (of null bij een foute datum). */
    static Calendar day(String key, TimeZone tz) {
        if (!validDate(key)) return null;
        Calendar k = Calendar.getInstance(tz);
        k.clear();
        k.set(Integer.parseInt(key.substring(0, 4)), Integer.parseInt(key.substring(5, 7)) - 1, Integer.parseInt(key.substring(8, 10)));
        return k;
    }

    /** Het tijdstip "min" op die dag (rond een zomertijd-sprong schuift Android het een uur op, zoals een wekker). */
    static long at(Calendar day, int min) {
        Calendar k = (Calendar) day.clone();
        k.set(Calendar.HOUR_OF_DAY, min / 60);
        k.set(Calendar.MINUTE, min % 60);
        k.set(Calendar.SECOND, 0);
        k.set(Calendar.MILLISECOND, 0);
        return k.getTimeInMillis();
    }

    // ---------- de regels ----------

    /** Komt de vraag op deze dag (los van stil en pauze)? */
    static boolean applies(Rule r, Calendar day) {
        int dow = isoDow(day);
        switch (r.rhythm == null ? "" : r.rhythm) {
            case "daily": return true;
            case "workdays": return dow <= 5;
            case "weekdays": case "multi": return r.days != null && r.days.length > dow && r.days[dow];
            case "weekly": return dow == Math.max(1, Math.min(7, r.wday));
            case "monthly": {
                int want = Math.max(1, Math.min(31, r.dom));
                return day.get(Calendar.DAY_OF_MONTH) == Math.min(want, day.getActualMaximum(Calendar.DAY_OF_MONTH));
            }
            default: return false;
        }
    }

    /** Stil op deze dag: in het weekend (als dat aan staat) of tijdens de pauze. */
    static boolean quiet(Rule r, Calendar day) {
        if (r.quietWeekend && isoDow(day) >= 6) return true;
        return validDate(r.pauseUntil) && dateKey(day).compareTo(r.pauseUntil) <= 0;
    }

    static int[] times(Rule r) {
        if (r.times == null || r.times.length == 0) return new int[0];
        if (!"multi".equals(r.rhythm)) return new int[]{r.times[0]};
        int[] t = r.times.clone();
        java.util.Arrays.sort(t);
        return t;
    }

    /** De sleutel van de periode van een moment op deze dag. */
    static String key(Rule r, Calendar day, int min) {
        switch (r.rhythm == null ? "" : r.rhythm) {
            case "weekly": return weekKey(day);
            case "monthly": return monthKey(day);
            case "multi": return dateKey(day) + "T" + hhmm(min);
            default: return dateKey(day);
        }
    }

    /**
     * Tot wanneer je kunt antwoorden: op dezelfde dag tot "until" (ligt dat vóór of op het moment, dan de volgende dag).
     * Meerdere keren per dag: hooguit tot het volgende tijdstip, ook als dat op de volgende dag valt (zo overlappen
     * twee periodes nooit, bijv. 09:00 en 21:00 met "tot 10:00").
     */
    static long end(Rule r, Calendar day, int min, int[] ts, int idx) {
        int u = r.until < 0 || r.until >= 24 * 60 ? 23 * 60 + 59 : r.until;
        long e;
        if (u > min) e = at(day, u);
        else { Calendar n = (Calendar) day.clone(); n.add(Calendar.DAY_OF_MONTH, 1); e = at(n, u); }
        if ("multi".equals(r.rhythm)) {
            if (idx + 1 < ts.length) e = Math.min(e, at(day, ts[idx + 1]));
            else for (int i = 1; i <= 7; i++) { // het eerste tijdstip van de eerstvolgende dag waarop de vraag komt
                Calendar n = (Calendar) day.clone(); n.add(Calendar.DAY_OF_MONTH, i);
                if (applies(r, n)) { e = Math.min(e, at(n, ts[0])); break; }
            }
        }
        return e;
    }

    /** Alle periodes waarvan het moment in [from, to) valt, op volgorde (ook de stille). */
    static List<Slot> slots(Rule r, long from, long to, TimeZone tz) {
        List<Slot> out = new ArrayList<>();
        int[] ts = times(r);
        if (ts.length == 0 || to <= from) return out;
        Calendar d = Calendar.getInstance(tz);
        d.setTimeInMillis(from);
        d.set(Calendar.HOUR_OF_DAY, 0); d.set(Calendar.MINUTE, 0); d.set(Calendar.SECOND, 0); d.set(Calendar.MILLISECOND, 0);
        d.add(Calendar.DAY_OF_MONTH, -1); // een moment net na middernacht van de dag ervoor kan nog in het bereik vallen
        for (int guard = 0; guard < 4000; guard++) {
            Calendar day = (Calendar) d.clone();
            if (at(day, 0) >= to) break;
            if (applies(r, day)) {
                boolean q = quiet(r, day);
                for (int i = 0; i < ts.length; i++) {
                    long a = at(day, ts[i]);
                    if (a >= from && a < to) out.add(new Slot(a, end(r, day, ts[i], ts, i), key(r, day, ts[i]), q));
                }
            }
            d.add(Calendar.DAY_OF_MONTH, 1);
        }
        return out;
    }

    /** Het eerstvolgende moment voor een melding na "after" (stil en pauze overgeslagen); 0 = geen. */
    static long next(Rule r, long after, TimeZone tz) {
        long day = 86_400_000L;
        for (long from = after + 1; from < after + 800 * day; from += 40 * day) {
            for (Slot s : slots(r, from, from + 40 * day, tz)) if (!s.quiet && s.at > after) return s.at;
        }
        return 0;
    }

    /** De periode waar het om gaat als je nu in de app antwoordt: die nu open staat, of die van vandaag die nog komt. */
    static Slot current(Rule r, long now, TimeZone tz) {
        long day = 86_400_000L;
        Slot open = null, today = null;
        String todayKey = dateKey(now, tz);
        for (Slot s : slots(r, now - 40 * day, now + 2 * day, tz)) {
            if (s.at <= now && now < s.end) open = s; // de nieuwste die open staat
            if (today == null && s.at > now && dateKey(s.at, tz).equals(todayKey)) today = s;
        }
        return open != null ? open : today;
    }

    /** De periode met deze sleutel (binnen ruim een jaar rond "around"), of null. */
    static Slot find(Rule r, String key, long around, TimeZone tz) {
        long day = 86_400_000L;
        Calendar k = null;
        if (key != null && key.length() >= 10 && validDate(key.substring(0, 10))) k = day(key.substring(0, 10), tz);
        else if (key != null && key.matches("\\d{4}-(0[1-9]|1[0-2])")) k = day(key + "-01", tz);
        else if (key != null && key.matches("\\d{4}-W\\d{2}")) {
            // 4 januari ligt altijd in week 1
            Calendar j = day(key.substring(0, 4) + "-01-04", tz);
            if (j != null) { j.add(Calendar.DAY_OF_MONTH, 1 - isoDow(j) + (Integer.parseInt(key.substring(6)) - 1) * 7); k = j; }
        }
        if (k == null) return null;
        long from = at(k, 0) - 2 * day, to = at(k, 0) + 33 * day;
        if (Math.abs(from - around) > 800 * day) return null;
        for (Slot s : slots(r, from, to, tz)) if (s.key.equals(key)) return s;
        return null;
    }

    // ---------- meldingen: wanneer, en welke (zuiver, getest) ----------

    /**
     * Vanaf wanneer de wekker kijkt: wat er bewaard is (nooit later dan nu, zodat een teruggezette klok geen
     * periodes overslaat; nooit ouder dan twee dagen, zodat er na lange tijd geen stapel oude vragen komt).
     * 0 = nog nooit gekeken: vanaf nu.
     */
    static long lookFrom(long stored, long now) {
        if (stored <= 0) return now;
        return Math.max(Math.min(stored, now), now - 2 * 86_400_000L);
    }

    /**
     * De periode waarvoor nu een melding moet komen: de nieuwste die sinds "from" begon en nog open staat, niet stil
     * is, en nog geen melding kreeg (doneKey = de laatste periode met een melding). null = geen melding.
     */
    static Slot toNotify(Rule r, long from, long now, String doneKey, TimeZone tz) {
        Slot pick = null;
        for (Slot s : slots(r, from - 60_000L, now + 1, tz)) if (!s.quiet && s.at <= now && now < s.end) pick = s;
        return pick == null || pick.key.equals(doneKey) ? null : pick;
    }

    /**
     * Na het bewaren van een vraag: de periode die nu al loopt telt als gemeld (aanzetten of wijzigen geeft niet meteen
     * een melding). Geeft die sleutel, of null als er nu niets loopt. De andere vragen merken hier niets van.
     */
    static String doneOnChange(Rule r, long now, TimeZone tz) {
        Slot s = current(r, now, tz);
        return s != null && s.at <= now ? s.key : null;
    }

    /** Een melding die er staat na het wijzigen van de vraag: opnieuw tonen (met de nieuwe knoppen) of weghalen. */
    static boolean repostOnChange(boolean active, Slot posted, long now) { return active && posted != null && now < posted.end; }

    // ---------- toestand, reeks, tellen ----------

    /**
     * Hoe staat een periode ervoor: answered (ingevuld), quiet (stil of pauze: telt niet), before (al voorbij toen de
     * vraag er kwam: telt niet), upcoming (komt nog), open (nu te beantwoorden) of missed (gemist).
     */
    static String status(Rule r, Slot s, boolean answered, long now) {
        if (answered) return "answered";
        if (s.quiet) return "quiet";
        if (r.created > 0 && s.end <= r.created) return "before";
        if (now < s.at) return "upcoming";
        if (now < s.end) return "open";
        return "missed";
    }

    /**
     * De reeks: hoeveel periodes achter elkaar ingevuld, van nieuw naar oud. Wat nog komt of nu open staat breekt de
     * reeks niet, stille periodes tellen niet mee, gemist (of van vóór de vraag) stopt hem.
     */
    static int streak(String[] newestFirst) {
        int n = 0;
        boolean started = false;
        for (String s : newestFirst) {
            if ("answered".equals(s)) { n++; started = true; continue; }
            if ("quiet".equals(s)) continue;
            if (!started && ("upcoming".equals(s) || "open".equals(s))) continue;
            break;
        }
        return n;
    }

    /** Hoe vaak elk antwoord gekozen werd: per optie (op waarde) het aantal; een onbekende waarde telt niet mee. */
    static int[] counts(double[] optionValues, double[] answers) {
        int[] n = new int[optionValues.length];
        for (double a : answers) for (int i = 0; i < optionValues.length; i++) if (Math.abs(optionValues[i] - a) < 1e-9) { n[i]++; break; }
        return n;
    }

    /** Gemiddelde (NaN als er niets is). */
    static double average(double[] v) {
        if (v.length == 0) return Double.NaN;
        double s = 0;
        for (double x : v) s += x;
        return s / v.length;
    }

    /** Is dit een sleutel van een periode (dag, dag+tijd, week of maand)? */
    static boolean validKey(String k) {
        if (k == null) return false;
        if (k.length() == 10) return validDate(k);
        if (k.length() == 16) return validDate(k.substring(0, 10)) && k.charAt(10) == 'T' && validTime(k.substring(11));
        return k.matches("\\d{4}-(0[1-9]|1[0-2])") || k.matches("\\d{4}-W(0[1-9]|[1-4]\\d|5[0-3])");
    }

    /** De eerste dag van een periode als "yyyy-MM-dd" (een week: de maandag), of "" bij een foute sleutel. */
    static String keyDay(String k) {
        if (!validKey(k)) return "";
        if (k.length() >= 10) return k.substring(0, 10);
        if (k.length() == 7) return k + "-01";
        TimeZone utc = TimeZone.getTimeZone("UTC");
        Calendar j = day(k.substring(0, 4) + "-01-04", utc);
        j.add(Calendar.DAY_OF_MONTH, 1 - isoDow(j) + (Integer.parseInt(k.substring(6)) - 1) * 7);
        return dateKey(j);
    }

    // ---------- terugzetten (samenvoegen, nooit overschrijven) ----------

    static final int ADD = 0, DUP = 1, BAD = 2;

    /**
     * Antwoorden uit een backup: per antwoord ADD, DUP (id staat er al, of hetzelfde antwoord: vraag, periode, tijd en
     * waarde) of BAD (onbekende vraag of foute periode). Dubbel binnen de backup zelf telt ook als DUP.
     */
    static int[] answerPlan(java.util.Set<String> haveIds, java.util.Set<String> haveSigs, java.util.Set<String> questions,
                            List<String> ids, List<String> sigs, List<String> qs, List<String> keys) {
        return answerPlan(haveIds, haveSigs, questions, new java.util.HashSet<String>(), new java.util.HashSet<String>(), ids, sigs, qs, keys);
    }

    /**
     * Zelfde, en bij vragen met één antwoord per periode (oneQ) ook DUP als die periode al een antwoord heeft
     * (haveQP = "vraag|periode"), zodat een vervangen antwoord niet terugkomt.
     */
    static int[] answerPlan(java.util.Set<String> haveIds, java.util.Set<String> haveSigs, java.util.Set<String> questions,
                            java.util.Set<String> oneQ, java.util.Set<String> haveQP,
                            List<String> ids, List<String> sigs, List<String> qs, List<String> keys) {
        int[] r = new int[ids.size()];
        java.util.Set<String> seenIds = new java.util.HashSet<>(haveIds), seenSigs = new java.util.HashSet<>(haveSigs), seenQP = new java.util.HashSet<>(haveQP);
        for (int i = 0; i < r.length; i++) {
            String id = ids.get(i), sig = sigs.get(i), qp = qs.get(i) + "|" + keys.get(i);
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,40}") || !questions.contains(qs.get(i)) || !validKey(keys.get(i))) { r[i] = BAD; continue; }
            if (seenIds.contains(id) || seenSigs.contains(sig) || (oneQ.contains(qs.get(i)) && seenQP.contains(qp))) { r[i] = DUP; continue; }
            r[i] = ADD;
            seenIds.add(id); seenSigs.add(sig); seenQP.add(qp);
        }
        return r;
    }
}
