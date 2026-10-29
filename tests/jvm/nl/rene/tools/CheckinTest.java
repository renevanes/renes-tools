package nl.rene.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.TimeZone;

/** Check-in: volgende moment, periodes, open/ingevuld/gemist, reeks, tellen, zomer- en wintertijd, korte maanden, terugzetten. */
public class CheckinTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    static final TimeZone AMS = TimeZone.getTimeZone("Europe/Amsterdam");
    static long at(int y, int mo, int d, int h, int mi) { Calendar k = Calendar.getInstance(AMS); k.clear(); k.set(y, mo - 1, d, h, mi); return k.getTimeInMillis(); }
    static String local(long t) { Calendar k = Calendar.getInstance(AMS); k.setTimeInMillis(t); return Checkin.dateKey(k) + " " + Checkin.hhmm(k.get(Calendar.HOUR_OF_DAY) * 60 + k.get(Calendar.MINUTE)); }

    static Checkin.Rule rule(String rhythm, String... times) {
        Checkin.Rule r = new Checkin.Rule();
        r.rhythm = rhythm;
        r.times = new int[times.length];
        for (int i = 0; i < times.length; i++) r.times[i] = Checkin.minutes(times[i]);
        return r;
    }

    public static void main(String[] a) {
        // ---------- volgende moment ----------
        Checkin.Rule daily = rule("daily", "21:00");
        check(local(Checkin.next(daily, at(2026, 10, 29, 20, 0), AMS)).equals("2026-10-29 21:00"), "elke dag: vanavond om 21:00");
        check(local(Checkin.next(daily, at(2026, 10, 29, 21, 0), AMS)).equals("2026-10-30 21:00"), "precies op het moment: de volgende keer is morgen");
        Checkin.Rule work = rule("workdays", "17:30");
        check(local(Checkin.next(work, at(2026, 10, 30, 18, 0), AMS)).equals("2026-11-02 17:30"), "werkdagen: na vrijdag komt maandag");
        Checkin.Rule chosen = rule("weekdays", "08:00");
        chosen.days = new boolean[8]; chosen.days[2] = true; chosen.days[4] = true; // di en do
        check(local(Checkin.next(chosen, at(2026, 10, 29, 9, 0), AMS)).equals("2026-11-03 08:00"), "gekozen dagen (di en do): na donderdag komt dinsdag");
        Checkin.Rule none = rule("weekdays", "08:00"); none.days = new boolean[8];
        check(Checkin.next(none, at(2026, 10, 29, 9, 0), AMS) == 0, "geen dagen gekozen: geen melding");
        Checkin.Rule weekly = rule("weekly", "16:00"); weekly.wday = 5;
        check(local(Checkin.next(weekly, at(2026, 10, 30, 16, 30), AMS)).equals("2026-11-06 16:00"), "elke week op vrijdag");

        // ---------- maandelijks, ook dag 31 in korte maanden ----------
        Checkin.Rule monthly = rule("monthly", "20:00"); monthly.dom = 31;
        check(local(Checkin.next(monthly, at(2027, 2, 1, 0, 0), AMS)).equals("2027-02-28 20:00"), "dag 31 in februari: de 28e");
        check(local(Checkin.next(monthly, at(2027, 3, 31, 21, 0), AMS)).equals("2027-04-30 20:00"), "dag 31 in april: de 30e");
        check(local(Checkin.next(monthly, at(2028, 2, 1, 0, 0), AMS)).equals("2028-02-29 20:00"), "dag 31 in een schrikkeljaar: 29 februari");
        Checkin.Rule m15 = rule("monthly", "09:00"); m15.dom = 15;
        check(local(Checkin.next(m15, at(2026, 10, 16, 0, 0), AMS)).equals("2026-11-15 09:00"), "dag 15: volgende maand");

        // ---------- zomer- en wintertijd ----------
        long n1 = Checkin.next(daily, at(2026, 10, 24, 22, 0), AMS), n2 = Checkin.next(daily, n1, AMS);
        check(local(n1).equals("2026-10-25 21:00") && n1 - at(2026, 10, 24, 21, 0) == 25 * 3_600_000L, "wintertijd: toch 21:00 (25 uur na gisteren)");
        check(local(n2).equals("2026-10-26 21:00"), "wintertijd: daarna gewoon weer 21:00");
        Checkin.Rule night = rule("daily", "02:30");
        long s1 = Checkin.next(night, at(2027, 3, 27, 12, 0), AMS);
        check(local(s1).equals("2027-03-28 03:30"), "zomertijd: 02:30 bestaat niet, dus 03:30 (zoals een wekker)");
        check(local(Checkin.next(night, s1, AMS)).equals("2027-03-29 02:30"), "zomertijd: de dag erna weer 02:30");
        List<Checkin.Slot> ns = Checkin.slots(night, at(2027, 3, 28, 0, 0), at(2027, 3, 29, 0, 0), AMS);
        check(ns.size() == 1 && ns.get(0).key.equals("2027-03-28"), "zomertijd: de periode heet gewoon 28 maart");

        // ---------- periodes ----------
        check(Checkin.slots(daily, at(2026, 10, 29, 0, 0), at(2026, 10, 30, 0, 0), AMS).get(0).key.equals("2026-10-29"), "dag: 2026-10-29");
        check(Checkin.slots(weekly, at(2026, 10, 26, 0, 0), at(2026, 11, 2, 0, 0), AMS).get(0).key.equals("2026-W44"), "week: 2026-W44");
        Checkin.Rule wk = rule("weekly", "12:00"); wk.wday = 5;
        check(Checkin.slots(wk, at(2027, 1, 1, 0, 0), at(2027, 1, 2, 0, 0), AMS).get(0).key.equals("2026-W53"), "vrijdag 1 januari 2027 hoort bij week 53 van 2026");
        wk.wday = 1;
        check(Checkin.slots(wk, at(2024, 12, 30, 0, 0), at(2024, 12, 31, 0, 0), AMS).get(0).key.equals("2025-W01"), "maandag 30 december 2024 hoort bij week 1 van 2025");
        check(Checkin.slots(monthly, at(2027, 2, 1, 0, 0), at(2027, 3, 1, 0, 0), AMS).get(0).key.equals("2027-02"), "maand: 2027-02");
        Checkin.Rule multi = rule("multi", "21:00", "09:00", "13:00");
        multi.days = new boolean[]{false, true, true, true, true, true, true, true};
        List<Checkin.Slot> ms = Checkin.slots(multi, at(2026, 10, 29, 0, 0), at(2026, 10, 30, 0, 0), AMS);
        check(ms.size() == 3 && ms.get(0).key.equals("2026-10-29T09:00") && ms.get(1).key.equals("2026-10-29T13:00") && ms.get(2).key.equals("2026-10-29T21:00"), "meerdere keren per dag: per tijdstip, op volgorde");
        check(ms.get(0).end == at(2026, 10, 29, 13, 0) && ms.get(2).end == at(2026, 10, 29, 23, 59), "meerdere keren: open tot het volgende tijdstip, de laatste tot 23:59");
        Checkin.Rule late = rule("daily", "22:00"); late.until = Checkin.minutes("01:00");
        check(Checkin.slots(late, at(2026, 10, 29, 0, 0), at(2026, 10, 30, 0, 0), AMS).get(0).end == at(2026, 10, 30, 1, 0), "tot 01:00: over middernacht heen");
        check(Checkin.validKey("2026-10-29") && Checkin.validKey("2026-W44") && Checkin.validKey("2026-10") && Checkin.validKey("2026-10-29T21:00")
                && !Checkin.validKey("2026-13") && !Checkin.validKey("../x") && !Checkin.validKey("2026-W60") && !Checkin.validKey(null), "sleutels controleren");
        check(Checkin.keyDay("2026-W44").equals("2026-10-26") && Checkin.keyDay("2026-10").equals("2026-10-01") && Checkin.keyDay("2026-10-29T21:00").equals("2026-10-29") && Checkin.keyDay("x").isEmpty(), "eerste dag van een periode");
        Checkin.Slot f = Checkin.find(weekly, "2026-W44", at(2026, 10, 29, 12, 0), AMS);
        check(f != null && local(f.at).equals("2026-10-30 16:00"), "periode zoeken: week 44 is vrijdag 30 oktober");
        check(Checkin.find(daily, "2026-10-29", at(2026, 10, 29, 12, 0), AMS) != null && Checkin.find(work, "2026-10-31", at(2026, 10, 29, 12, 0), AMS) == null, "periode zoeken: een zaterdag hoort niet bij werkdagen");

        // ---------- open, ingevuld, gemist, stil ----------
        Checkin.Slot today = Checkin.slots(daily, at(2026, 10, 29, 0, 0), at(2026, 10, 30, 0, 0), AMS).get(0);
        check(Checkin.status(daily, today, false, at(2026, 10, 29, 20, 0)).equals("upcoming"), "voor het moment: komt nog");
        check(Checkin.status(daily, today, false, at(2026, 10, 29, 22, 0)).equals("open"), "na het moment: open");
        check(Checkin.status(daily, today, false, at(2026, 10, 30, 0, 5)).equals("missed"), "na 23:59: gemist");
        check(Checkin.status(daily, today, true, at(2026, 10, 30, 0, 5)).equals("answered"), "ingevuld");
        Checkin.Rule fresh = rule("daily", "21:00"); fresh.created = at(2026, 10, 30, 9, 0);
        check(Checkin.status(fresh, today, false, at(2026, 10, 30, 10, 0)).equals("before"), "van vóór de vraag: telt niet");
        Checkin.Rule wkend = rule("daily", "21:00"); wkend.quietWeekend = true;
        check(local(Checkin.next(wkend, at(2026, 10, 30, 22, 0), AMS)).equals("2026-11-02 21:00"), "stil in het weekend: na vrijdag komt maandag");
        Checkin.Slot sat = Checkin.slots(wkend, at(2026, 10, 31, 0, 0), at(2026, 11, 1, 0, 0), AMS).get(0);
        check(sat.quiet && Checkin.status(wkend, sat, false, at(2026, 11, 2, 0, 0)).equals("quiet"), "zaterdag: stil, niet gemist");
        Checkin.Rule pause = rule("daily", "21:00"); pause.pauseUntil = "2026-11-03";
        check(local(Checkin.next(pause, at(2026, 10, 29, 12, 0), AMS)).equals("2026-11-04 21:00"), "pauze tot 3 november: weer op de 4e");
        // Nu in de app antwoorden
        Checkin.Slot c1 = Checkin.current(daily, at(2026, 10, 29, 12, 0), AMS), c2 = Checkin.current(daily, at(2026, 10, 29, 22, 0), AMS), c3 = Checkin.current(weekly, at(2026, 10, 29, 12, 0), AMS);
        check(c1 != null && c1.key.equals("2026-10-29") && c2 != null && c2.key.equals("2026-10-29") && c3 == null, "nu antwoorden: vandaag (ook al vóór het moment), maar niet een week die nog komt");
        check(Checkin.current(late, at(2026, 10, 30, 0, 30), AMS).key.equals("2026-10-29"), "na middernacht: de vraag van gisteravond staat nog open");

        // ---------- reeks en tellen ----------
        check(Checkin.streak(new String[]{"open", "answered", "answered", "quiet", "answered", "missed", "answered"}) == 3, "reeks: open breekt niet, stil telt niet, gemist stopt");
        check(Checkin.streak(new String[]{"upcoming", "open", "answered", "answered"}) == 2, "reeks: wat nog komt breekt niet");
        check(Checkin.streak(new String[]{"missed", "answered"}) == 0 && Checkin.streak(new String[]{"answered", "before", "answered"}) == 1 && Checkin.streak(new String[0]) == 0, "reeks: gemist of van vóór de vraag");
        check(Checkin.streak(new String[]{"answered", "open", "answered"}) == 1, "reeks: een open periode verderop telt als niet ingevuld");
        check(Arrays.equals(Checkin.counts(new double[]{1, 2, 3, 4, 5}, new double[]{4, 4, 3, 5, 9}), new int[]{0, 0, 1, 2, 1}), "tellen per antwoord (onbekende waarde telt niet)");
        check(Math.abs(Checkin.average(new double[]{4, 4, 3, 5}) - 4.0) < 1e-9 && Double.isNaN(Checkin.average(new double[0])), "gemiddelde");

        // ---------- agenda: bezette tijd zonder dubbel tellen ----------
        List<long[]> busy = new ArrayList<>();
        busy.add(new long[]{0, 60}); busy.add(new long[]{30, 90}); busy.add(new long[]{120, 150}); busy.add(new long[]{125, 130});
        check(CheckinStore.unionMs(busy) == 120 && CheckinStore.unionMs(new ArrayList<long[]>()) == 0, "agenda: overlappende afspraken één keer tellen");

        // ---------- terugzetten: samenvoegen, nooit overschrijven ----------
        int[] plan = Checkin.answerPlan(new HashSet<>(Arrays.asList("a1")), new HashSet<>(Arrays.asList("q1|2026-10-28|5|4.0")), new HashSet<>(Arrays.asList("q1")),
                Arrays.asList("a1", "a2", "a3", "a4", "a5", "a6", "a6"),
                Arrays.asList("x", "q1|2026-10-28|5|4.0", "q2|2026-10-29|6|3.0", "q1|../x|7|1.0", "q1|2026-10-29|8|2.0", "q1|2026-10-30|9|2.0", "q1|2026-10-31|9|2.0"),
                Arrays.asList("q1", "q1", "q2", "q1", "q1", "q1", "q1"),
                Arrays.asList("2026-10-27", "2026-10-28", "2026-10-29", "../x", "2026-10-29", "2026-10-30", "2026-10-31"));
        check(Arrays.equals(plan, new int[]{Checkin.DUP, Checkin.DUP, Checkin.BAD, Checkin.BAD, Checkin.ADD, Checkin.ADD, Checkin.DUP}), "terugzetten: zelfde id of zelfde antwoord = al aanwezig; onbekende vraag of foute periode = overslaan");

        if (fails > 0) { System.out.println(fails + " fout(en)"); System.exit(1); }
        System.out.println("alles goed");
        System.exit(0);
    }
}
