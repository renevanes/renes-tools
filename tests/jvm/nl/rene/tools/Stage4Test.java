package nl.rene.tools;

import java.util.Calendar;

/** Eigen acties, tijd-triggers en verjaardagen. */
public class Stage4Test {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }
    static long at(int y, int m, int d, int h, int min) { Calendar c = Calendar.getInstance(); c.clear(); c.set(y, m - 1, d, h, min); return c.getTimeInMillis(); }
    public static void main(String[] a) {
        // (Controle van de acties zit in de interfacetest auto2: org.json is hier alleen een stub)
        // Tijd: om 07:15 alleen op werkdagen (ma..vr = 1+2+4+8+16)
        int m = AutoLogic.minutes("07:15");
        long sat = at(2026, 10, 10, 9, 0); // zaterdag
        check(AutoActions.nextTime(m, 31, sat) == at(2026, 10, 12, 7, 15), "na zaterdag 9:00 → maandag 07:15");
        check(AutoActions.nextTime(m, 31, at(2026, 10, 12, 7, 0)) == at(2026, 10, 12, 7, 15), "maandag 07:00 → dezelfde dag 07:15");
        check(AutoActions.nextTime(m, 0, sat) == at(2026, 10, 11, 7, 15), "elke dag: zondag 07:15");
        check(AutoActions.nextTime(AutoLogic.minutes("x"), 0, sat) == 0, "geen geldige tijd → 0");
        // Verjaardagen
        int[] p = Birthdays.parse("--05-17");
        check(p != null && p[0] == 0 && p[1] == 5 && p[2] == 17, "--05-17 zonder jaar");
        p = Birthdays.parse("1980-02-29");
        check(p != null && p[0] == 1980 && p[2] == 29, "1980-02-29");
        check(Birthdays.parse("19750102")[1] == 1, "19750102");
        check(Birthdays.parse("onzin") == null, "onzin → null");
        Calendar today = Calendar.getInstance(); today.clear(); today.set(2026, 9, 7, 10, 0);
        check(Birthdays.daysUntil(10, 7, today) == 0, "vandaag jarig = 0 dagen");
        check(Birthdays.daysUntil(10, 9, today) == 2, "over 2 dagen");
        check(Birthdays.daysUntil(10, 6, today) == 364, "gisteren jarig → bijna een jaar");
        Calendar feb = Calendar.getInstance(); feb.clear(); feb.set(2027, 1, 27, 10, 0);
        check(Birthdays.daysUntil(2, 29, feb) == 1, "29 feb valt in 2027 op 28 feb");
        System.out.println(fails == 0 ? "Alle stap-4-tests geslaagd" : fails + " stap-4-tests mislukt");
        System.exit(fails == 0 ? 0 : 1);
    }
}
