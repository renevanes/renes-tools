package nl.rene.tools;

import java.util.Calendar;
import java.util.TimeZone;

/** Terugkerende lijstjes: wanneer gaan de vinkjes weer weg (Notes.nextReset). */
public class NotesRepeatTest {
    static int failed = 0;
    static void check(String what, boolean ok) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) failed++; }

    static long at(TimeZone tz, int y, int m, int d, int h, int min) {
        Calendar c = Calendar.getInstance(tz); c.clear(); c.set(y, m - 1, d, h, min); return c.getTimeInMillis();
    }

    public static void main(String[] a) {
        TimeZone ams = TimeZone.getTimeZone("Europe/Amsterdam");
        // maandag 5 oktober 2026, 12:00
        long mon = at(ams, 2026, 10, 5, 12, 0);
        check("elke dag: vannacht om 0:00", Notes.nextReset("day", 1, mon, ams) == at(ams, 2026, 10, 6, 0, 0));
        check("elke maandag, op maandag gezet: pas volgende week", Notes.nextReset("week", 1, mon, ams) == at(ams, 2026, 10, 12, 0, 0));
        check("elke zaterdag: komende zaterdag", Notes.nextReset("week", 6, mon, ams) == at(ams, 2026, 10, 10, 0, 0));
        check("elke zondag: komende zondag", Notes.nextReset("week", 7, mon, ams) == at(ams, 2026, 10, 11, 0, 0));
        check("maand op de 1e: 1 november", Notes.nextReset("month", 1, mon, ams) == at(ams, 2026, 11, 1, 0, 0));
        check("maand op de 31e in februari: de laatste dag", Notes.nextReset("month", 31, at(ams, 2027, 2, 1, 9, 0), ams) == at(ams, 2027, 2, 28, 0, 0));
        // zomertijd → wintertijd (25 oktober 2026): nog steeds om middernacht
        check("over de wintertijd heen: middernacht", Notes.nextReset("day", 1, at(ams, 2026, 10, 24, 22, 0), ams) == at(ams, 2026, 10, 25, 0, 0));
        check("onbekend: nooit", Notes.nextReset("x", 1, mon, ams) == Long.MAX_VALUE);
        System.out.println(failed == 0 ? "Alle herhaal-tests geslaagd" : failed + " test(s) mislukt");
        System.exit(failed == 0 ? 0 : 1);
    }
}
