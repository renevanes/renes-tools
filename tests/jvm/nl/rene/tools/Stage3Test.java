package nl.rene.tools;

import java.util.Calendar;
import java.util.List;

/** Herhalende herinneringen, GPX inlezen en welke instellingen mee gaan. */
public class Stage3Test {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }
    static long at(int y, int m, int d, int h, int min) { Calendar c = Calendar.getInstance(); c.clear(); c.set(y, m - 1, d, h, min); return c.getTimeInMillis(); }
    public static void main(String[] a) {
        long fri = at(2026, 10, 9, 8, 0); // vrijdag
        check(Reminders.next(fri, "day", fri) == at(2026, 10, 10, 8, 0), "elke dag: morgen zelfde tijd");
        check(Reminders.next(fri, "weekdays", fri) == at(2026, 10, 12, 8, 0), "werkdagen: na vrijdag komt maandag");
        check(Reminders.next(fri, "week", fri) == at(2026, 10, 16, 8, 0), "elke week: volgende vrijdag");
        check(Reminders.next(at(2026, 1, 31, 9, 0), "month", at(2026, 1, 31, 9, 0)) == at(2026, 2, 28, 9, 0), "elke maand: 31 jan → 28 feb");
        check(Reminders.next(fri, "", fri) == 0, "eenmalig: geen volgende");
        long feb = Reminders.next(at(2026, 1, 31, 9, 0), "month", at(2026, 1, 31, 9, 0), 31);
        check(Reminders.next(feb, "month", feb, 31) == at(2026, 3, 31, 9, 0), "elke maand op de 31e: na 28 feb weer 31 maart");
        check(Reminders.next(fri, "week", at(2026, 11, 1, 0, 0)) > at(2026, 11, 1, 0, 0), "na lang uit: eerstvolgende in de toekomst");
        check(Reminders.noteOf("abc~3", null).equals("abc"), "herinnering abc~3 hoort bij notitie abc");

        String gpx = "<?xml version=\"1.0\"?><gpx><trk><name>Rondje &amp; terug</name><trkseg>"
                + "<trkpt lat=\"51.92\" lon=\"4.48\"><ele>2.5</ele><time>2026-10-01T08:00:00Z</time></trkpt>"
                + "<trkpt lon='4.481' lat='51.921'><time>2026-10-01T08:00:10.500Z</time></trkpt>"
                + "<trkpt lat=\"999\" lon=\"4\"/>"
                + "<gpxtpx:trkpt lat=\"51.922\" lon=\"4.482\"/></trkseg></trk></gpx>";
        List<Tracks.Pt> pts = Tracks.parseGpx(gpx);
        check(pts.size() == 3, "drie geldige punten (ongeldige breedte overgeslagen): " + pts.size());
        check(Math.abs(pts.get(0).ele - 2.5) < 1e-9, "hoogte gelezen");
        check(pts.get(1).t - pts.get(0).t == 1000, "één punt zonder tijd: hele route krijgt vaste tijden");
        check(Tracks.parseGpx(gpx).get(0).t == pts.get(0).t, "zelfde bestand → zelfde begintijd (dubbel importeren herkenbaar)");
        String timed = "<gpx><trkpt lat=\"1\" lon=\"2\"><time>2026-10-01T08:00:00Z</time></trkpt><trkpt lat=\"1.001\" lon=\"2\"><time>2026-10-01T10:00:10.5+02:00</time></trkpt></gpx>";
        List<Tracks.Pt> tp = Tracks.parseGpx(timed);
        check(tp.get(1).t - tp.get(0).t == 10_500, "tijden met fractie en tijdzone goed gelezen: " + (tp.get(1).t - tp.get(0).t));
        check(Tracks.parseIso("2026-10-01T08:00:00Z") == Tracks.parseIso("2026-10-01T10:00:00+02:00"), "+02:00 is twee uur eerder in UTC");
        check(Tracks.gpxTitle(gpx).equals("Rondje & terug"), "naam van de route: " + Tracks.gpxTitle(gpx));
        check(Tracks.parseGpx("<gpx></gpx>").isEmpty(), "leeg bestand: geen punten");

        check(SettingsBackup.keep("rules", "rules") && !SettingsBackup.keep("log", "rules"), "alleen de gekozen sleutel");
        check(!SettingsBackup.keep("nextAt", null) && SettingsBackup.keep("hour", null), "tijdelijke toestand gaat niet mee");
        System.out.println(fails == 0 ? "Alle stap-3-tests geslaagd" : fails + " stap-3-tests mislukt");
        System.exit(fails == 0 ? 0 : 1);
    }
}
