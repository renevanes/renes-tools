package nl.rene.tools;

import java.util.Calendar;

/** Tests van de regels van Automatiseringen (AutoLogic): plek, dagen, tijden, wachttijd en knopteksten. */
public class AutoTest {

    static int failed = 0;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "✓ " : "✗ ") + what);
        if (!ok) failed++;
    }

    public static void main(String[] a) {
        // Rotterdam Centraal -> Erasmusbrug is ca. 2 km
        double d = AutoLogic.meters(51.9244, 4.4690, 51.9093, 4.4868);
        check("afstand ca. 2 km (" + Math.round(d) + " m)", d > 1900 && d < 2200);
        check("zelfde punt = 0 m", AutoLogic.meters(52, 5, 52, 5) < 0.001);
        // 150 m ten noorden van de plek (0,00135 graden)
        check("150 m buiten straal 100 bij nauwkeurige fix", !AutoLogic.inPlace(51.92385, 4.4790, 5, 51.9225, 4.4790, 100));
        check("150 m binnen straal 100 bij fix van 120 m (marge 60)", AutoLogic.inPlace(51.92385, 4.4790, 120, 51.9225, 4.4790, 100));
        check("marge max. 150 m", !AutoLogic.inPlace(51.9270, 4.4790, 2000, 51.9225, 4.4790, 100));

        check("geen dagen = elke dag", AutoLogic.dayOk(0, Calendar.SUNDAY));
        check("werkdagen: maandag ja", AutoLogic.dayOk(31, Calendar.MONDAY));
        check("werkdagen: zondag nee", !AutoLogic.dayOk(31, Calendar.SUNDAY));
        check("alleen zondag (64)", AutoLogic.dayOk(64, Calendar.SUNDAY) && !AutoLogic.dayOk(64, Calendar.SATURDAY));

        check("geen tijden = altijd", AutoLogic.timeOk("", "", 3 * 60));
        check("07:00-19:00 om 08:30", AutoLogic.timeOk("07:00", "19:00", 8 * 60 + 30));
        check("07:00-19:00 om 19:00 niet", !AutoLogic.timeOk("07:00", "19:00", 19 * 60));
        check("over middernacht 22:00-06:00 om 23:00", AutoLogic.timeOk("22:00", "06:00", 23 * 60));
        check("over middernacht 22:00-06:00 om 12:00 niet", !AutoLogic.timeOk("22:00", "06:00", 12 * 60));
        check("ongeldige tijd = altijd", AutoLogic.timeOk("25:00", "x", 600));

        long now = 10_000_000L;
        check("nooit gedaan = mag", AutoLogic.cooldownOk(0, now, 15));
        check("5 min geleden bij 15 = niet", !AutoLogic.cooldownOk(now - 5 * 60_000L, now, 15));
        check("20 min geleden bij 15 = mag", AutoLogic.cooldownOk(now - 20 * 60_000L, now, 15));

        check("gelijke tekst = 3", AutoLogic.textScore("Start parkeren", "start parkeren") == 3);
        check("accenten en leestekens", AutoLogic.textScore("Bevestígen!", "bevestigen") == 3);
        check("begint ermee = 2", AutoLogic.textScore("Start parkeren nu", "Start parkeren") == 2);
        check("komt erin voor = 1", AutoLogic.textScore("Nu parkeren starten", "parkeren") == 1);
        check("korte tekst niet ergens middenin", AutoLogic.textScore("Kaart", "aar") == 0);
        check("leeg = 0", AutoLogic.textScore(null, "x") == 0 && AutoLogic.textScore("x", "") == 0);

        check("dagenlabel werkdagen", "werkdagen".equals(AutoLogic.daysLabel(31)));
        check("dagenlabel ma, vr", "ma, vr".equals(AutoLogic.daysLabel(1 | 16)));

        if (failed > 0) { System.out.println(failed + " test(s) mislukt"); System.exit(1); }
        System.out.println("Alle Automatiseringen-tests geslaagd");
    }
}
