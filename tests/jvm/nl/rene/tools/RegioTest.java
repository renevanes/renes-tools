package nl.rene.tools;

import java.util.Calendar;
import java.util.TimeZone;

/** Regio en taal in Java: de standaard is precies zoals de app het altijd schreef; de keuzes werken in patronen, getallen en eenheden. */
public class RegioTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }
    static void eq(String got, String want, String what) { check(want.equals(got), what + ": " + got + (want.equals(got) ? "" : " (verwacht " + want + ")")); }

    static long utc(String iso) { return java.time.Instant.parse(iso).toEpochMilli(); }

    /** De tijdzone (de app-zone): kiezen en controleren, dag, tijden uit de opslag over zomer- en wintertijd, planningen. */
    static void tijdzone() {
        TimeZone ny = TimeZone.getTimeZone("America/New_York"), ams = TimeZone.getTimeZone("Europe/Amsterdam");
        TimeZone ki = TimeZone.getTimeZone("Pacific/Kiritimati"), pp = TimeZone.getTimeZone("Pacific/Pago_Pago");
        // Kiezen en controleren (zoals rgTzOk)
        check(Regio.tzOk("America/New_York") && Regio.tzOk("UTC") && Regio.tzOk("Pacific/Kiritimati"), "bekende zones");
        check(!Regio.tzOk("america/new_york") && !Regio.tzOk("Mars/Olympus") && !Regio.tzOk("") && !Regio.tzOk(null) && !Regio.tzOk("../etc") && !Regio.tzOk("GMT+99"),
                "onbekend, verkeerde hoofdletters of rommel: niet (geen stille GMT)");
        Regio.Cfg c = Regio.parse("{\"tz\":\"America/New_York\"}");
        eq(c.tz, "America/New_York", "tijdzone gelezen");
        eq(Regio.zone(c).getID(), "America/New_York", "app-zone");
        check(!Regio.followsPhone(c) && Regio.followsPhone(Regio.parse("")), "volgt de telefoon alleen zonder keuze");
        Regio.Cfg bad = Regio.parse("{\"tz\":\"Mars/Olympus\"}");
        check(bad.tz.equals("Mars/Olympus") && Regio.zone(bad).getID().equals("Europe/Amsterdam") && Regio.followsPhone(bad), "onbekende zone blijft bewaard, de app volgt de telefoon");
        eq(Regio.parse("{\"tz\":\"<script>\"}").tz, "", "rommel als zone: leeg");
        check(Regio.parse(Regio.clean("{\"tz\":\"America/New_York\"}")).tz.equals("America/New_York") && Regio.clean("{}").contains("\"tz\":\"\""), "bewaard met de keuze");
        // Notaties in de app-zone
        long t = utc("2026-11-04T02:30:00Z"); // 03:30 Amsterdam, 21:30 (3 nov) New York
        eq(Regio.df(c, "EEEE d MMMM HH:mm").format(new java.util.Date(t)), "dinsdag 3 november 21:30", "Regio.df in de app-zone");
        eq(Regio.df(Regio.parse(""), "EEEE d MMMM HH:mm").format(new java.util.Date(t)), "woensdag 4 november 03:30", "zonder keuze: telefoontijd");
        // Dag
        eq(Regio.dayKey(ny, t), "2026-11-03", "dag in New York");
        eq(Regio.dayKey(ams, t), "2026-11-04", "dag in Amsterdam");
        eq(Regio.dayKey(ki, utc("2026-11-03T10:30:00Z")), "2026-11-04", "Kiritimati (UTC+14): al de volgende dag");
        eq(Regio.dayKey(pp, utc("2026-11-03T10:30:00Z")), "2026-11-02", "Pago Pago (UTC−11): nog de dag ervoor");
        check(Regio.isToday(ny, utc("2026-11-03T13:15:00Z"), t) && !Regio.isToday(ams, utc("2026-11-03T13:15:00Z"), t), "vandaag hangt af van de zone");
        eq(Regio.dayAdd("2026-12-31", 1) + " " + Regio.dayAdd("2028-03-01", -1), "2027-01-01 2028-02-29", "kalenderrekenen");
        check(Regio.weekday("2026-11-03") == Calendar.TUESDAY, "weekdag van een datum");
        // Tijden uit de opslag: wandklok in de zone, over zomer- en wintertijd
        check(Regio.toMillis(ny, "2026-11-01", "01:30") == utc("2026-11-01T05:30:00Z"), "New York 1 nov 01:30 bestaat twee keer: de eerste");
        check(Regio.toMillis(ny, "2026-11-01", "03:00") == utc("2026-11-01T08:00:00Z"), "New York 1 nov 03:00: wintertijd");
        check(Regio.toMillis(ny, "2027-03-14", "02:30") == utc("2027-03-14T07:30:00Z"), "New York 14 mrt 02:30 bestaat niet: 03:30 (zoals een wekker)");
        check(Regio.toMillis(ny, "2027-03-14", "03:30") == utc("2027-03-14T07:30:00Z"), "New York 14 mrt 03:30");
        check(Regio.toMillis(ams, "2026-10-25", "02:30") == utc("2026-10-25T00:30:00Z"), "Amsterdam 25 okt 02:30: de eerste keer");
        check(Regio.toMillis(ams, "2027-03-28", "02:30") == utc("2027-03-28T01:30:00Z"), "Amsterdam 28 mrt 02:30 bestaat niet: 03:30");
        check(Regio.toMillis(ki, "2026-11-04", "00:00") == utc("2026-11-03T10:00:00Z"), "Kiritimati middernacht");
        check(Regio.toMillis(ny, "x", "07:00") == -1 && Regio.toMillis(ny, "2026-11-01", "25:00") == -1, "geen datum of tijd: -1");
        check(Regio.startOfDay(ny, utc("2026-11-01T18:00:00Z")) == utc("2026-11-01T04:00:00Z"), "begin van de dag (New York, 1 nov)");
        // Planningen
        check(Regio.nextAt(ny, 2, 30, 0, utc("2027-03-13T17:00:00Z")) == utc("2027-03-14T07:30:00Z"), "volgende 02:30 op de dag van de zomertijd: 03:30");
        check(Regio.nextAt(ny, 2, 30, 0, utc("2027-03-14T08:00:00Z")) == utc("2027-03-15T06:30:00Z"), "de dag erna weer 02:30");
        check(Regio.nextAt(ny, 7, 0, 0, utc("2026-10-31T12:00:00Z")) == utc("2026-11-01T12:00:00Z"), "07:00 over de wintertijd heen: toch 07:00");
        check(Regio.nextAt(ny, 7, 0, 31, utc("2026-11-04T02:30:00Z")) == utc("2026-11-04T12:00:00Z"), "radiowekker ma–vr 07:00 in New York: woensdag 07:00 (niet 01:00)");
        check(Regio.nextAt(ny, 7, 0, 31, utc("2026-11-06T13:00:00Z")) == utc("2026-11-09T12:00:00Z"), "na vrijdag: maandag");
        check(RadioAlarm.next(ny, 7, 0, 31, utc("2026-11-04T02:30:00Z")) == utc("2026-11-04T12:00:00Z"), "RadioAlarm.next met de app-zone");
        check(SleepTalk.stopTime(utc("2026-11-04T02:30:00Z"), "23:00", ny) == utc("2026-11-04T04:00:00Z"), "slaappraat: stop om 23:00 New York");
        check(SleepReminder.next(utc("2026-11-04T02:30:00Z"), "22:30", ny) == utc("2026-11-04T03:30:00Z"), "herinnering bedtijd 22:30 New York");
        check(AutoActions.nextTime(7 * 60 + 15, 0, utc("2026-11-04T02:30:00Z"), ny) == utc("2026-11-04T12:15:00Z"), "automatisering om 07:15 New York");
        check(Reminders.next(utc("2026-10-31T13:00:00Z"), "day", utc("2026-10-31T13:00:00Z"), 0, ny) == utc("2026-11-01T14:00:00Z"), "herinnering elke dag 09:00 New York, over de wintertijd: 09:00");
        check(Regio.minuteOfDay(ny, utc("2026-11-04T02:30:00Z")) == 21 * 60 + 30, "minuut van de dag in de zone");
        check(Calls.sameDay(utc("2026-11-04T02:00:00Z"), utc("2026-11-04T04:30:00Z"), ny) && !Calls.sameDay(utc("2026-11-04T04:00:00Z"), utc("2026-11-04T05:30:00Z"), ny),
                "Oproepen-export: dezelfde dag in de zone");
        // Een hele dag voor de agenda-app (telefoontijd): dezelfde datum, ook op een dag van 23 of 25 uur
        long[] d1 = Regio.allDayFor(ny, ams, utc("2026-03-29T04:00:00Z"), utc("2026-03-30T04:00:00Z"));
        check(d1[0] == utc("2026-03-28T23:00:00Z") && d1[1] == utc("2026-03-29T22:00:00Z") && d1[1] - d1[0] == 23 * 3_600_000L, "hele dag 29 maart 2026 (Amsterdam, 23 uur)");
        long[] d2 = Regio.allDayFor(ny, ams, utc("2026-10-25T04:00:00Z"), utc("2026-10-26T04:00:00Z"));
        check(d2[0] == utc("2026-10-24T22:00:00Z") && d2[1] == utc("2026-10-25T23:00:00Z") && d2[1] - d2[0] == 25 * 3_600_000L, "hele dag 25 oktober 2026 (Amsterdam, 25 uur)");
        long[] d3 = Regio.allDayFor(ki, ams, utc("2026-11-03T10:00:00Z"), utc("2026-11-05T10:00:00Z"));
        check(d3[0] == utc("2026-11-03T23:00:00Z") && d3[1] == utc("2026-11-05T23:00:00Z"), "twee hele dagen vanuit Kiritimati: 4 en 5 november in de agenda");
        long[] d4 = Regio.allDayFor(ams, ams, utc("2026-03-28T23:00:00Z"), 0);
        check(d4[1] - d4[0] == 23 * 3_600_000L, "zonder einde: één dag");
        // Herinneringen naar een andere zone: dezelfde wandklok
        long now = utc("2026-11-04T06:00:00Z"); // 07:00 Amsterdam
        long daily = utc("2026-11-04T07:00:00Z"); // 08:00 Amsterdam
        check(Reminders.rezoneTime(daily, "day", 4, ams, ny, now) == utc("2026-11-04T13:00:00Z"), "elke dag 08:00 Amsterdam → 08:00 New York (niet 02:00)");
        check(Reminders.rezoneTime(daily, "", 4, ams, ny, now) == utc("2026-11-04T13:00:00Z"), "eenmalig 08:00 → 08:00 New York");
        check(Reminders.rezoneTime(utc("2026-11-04T07:30:00Z"), "", 4, ams, TimeZone.getTimeZone("Asia/Tokyo"), now) == utc("2026-11-04T07:30:00Z"),
                "eenmalig, in de nieuwe zone al voorbij: het oude moment blijft");
        check(Reminders.rezoneTime(utc("2026-11-04T07:30:00Z"), "day", 4, ams, TimeZone.getTimeZone("Asia/Tokyo"), now) == utc("2026-11-04T23:30:00Z"),
                "elke dag 08:30, in Tokio al voorbij: morgen 08:30 Tokio");
        check(Reminders.rezoneTime(daily, "day", 4, ams, ams, now) == daily, "zelfde zone: niets");
        // Wintertijd: het uur dat twee keer bestaat is overal de eerste keer (Checkin, Facturen, Regio, de interface)
        Calendar oct = Checkin.day("2026-10-25", ams);
        check(Checkin.at(oct, 2 * 60 + 30) == utc("2026-10-25T00:30:00Z") && Checkin.at(Checkin.day("2027-03-28", ams), 2 * 60 + 30) == utc("2027-03-28T01:30:00Z"),
                "check-in 02:30: wintertijd de eerste keer, zomertijd 03:30");
        try {
            org.json.JSONObject lev = new org.json.JSONObject().put("schema", "dag").put("tijd", "02:30");
            check(Facturen.gepland(lev, utc("2026-10-24T20:00:00Z"), true, ams) == utc("2026-10-25T00:30:00Z"), "facturen 02:30 op de dag van de wintertijd: de eerste keer");
            check(Facturen.gepland(lev, utc("2027-03-27T20:00:00Z"), true, ams) == utc("2027-03-28T01:30:00Z"), "facturen 02:30 op de dag van de zomertijd: 03:30");
        } catch (Exception e) { check(false, "facturen: " + e); }
        // Met dezelfde regels als de interface (02-regio.js): overal waar het moment geldig is, geeft heen en terug hetzelfde
        String[] zones = {"America/New_York", "Europe/Amsterdam", "Pacific/Kiritimati", "Pacific/Pago_Pago", "Australia/Lord_Howe", "Asia/Kathmandu"};
        int wrong = 0;
        for (String z : zones) {
            TimeZone tz = TimeZone.getTimeZone(z);
            for (long x = utc("2026-01-01T00:00:00Z"); x < utc("2028-01-01T00:00:00Z"); x += 7L * 3_600_000L + 60_000L) {
                Calendar k = Regio.cal(tz, x);
                String day = Regio.dayKey(tz, x), hm = String.format(java.util.Locale.ROOT, "%02d:%02d", k.get(Calendar.HOUR_OF_DAY), k.get(Calendar.MINUTE));
                long back = Regio.toMillis(tz, day, hm), want = x - x % 60_000L;
                // In het uur dat twee keer bestaat geeft toMillis de eerste keer
                if (back != want && !(back < want && Regio.dayKey(tz, back).equals(day) && Regio.minuteOfDay(tz, back) == Regio.minuteOfDay(tz, want))) wrong++;
            }
        }
        check(wrong == 0, "heen en terug over twee jaar in zes zones (ook Lord Howe met een half uur): " + wrong + " fout");
    }

    public static void main(String[] a) {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Amsterdam"));
        Calendar k = Calendar.getInstance();
        k.clear(); k.set(2026, Calendar.NOVEMBER, 3, 14, 5, 7);
        long t = k.getTimeInMillis();

        // Standaard: zoals de app altijd deed
        Regio.Cfg d = Regio.parse("");
        eq(d.lang + d.clock + d.date + d.week + d.dist + d.temp + d.cur + d.dec + d.grp + d.size, "nl24dmy1kmcEUR,.1024", "standaard");
        eq(Regio.df(d, "d MMM yyyy HH:mm").format(new java.util.Date(t)), new java.text.SimpleDateFormat("d MMM yyyy HH:mm", new java.util.Locale("nl", "NL")).format(new java.util.Date(t)), "standaard datum met tijd: zoals de app het altijd schreef");
        eq(Regio.pattern(d, "EEE d MMM HH:mm"), "EEE d MMM HH:mm", "standaard: patroon onveranderd");
        eq(Regio.pattern(d, "d-M-yyyy"), "d-M-yyyy", "standaard: cijferdatum onveranderd");
        eq(Regio.num(d, 1234567.891, 2), "1.234.567,89", "getal met punt en komma");
        eq(Regio.num(d, -0.004, 2), "0,00", "afgerond nul: geen minteken");
        eq(Regio.num(d, -12.5, 1), "-12,5", "negatief getal");
        eq(Regio.money(d, -1234.5, "EUR"), "− € 1.234,50", "bedrag in euro");
        eq(Regio.dist(d, 12345, 2), "12,35 km", "afstand in km");
        eq(Regio.deg(d, 17.6), "18°", "temperatuur kort");
        eq(Regio.deg(d, null), "–", "geen temperatuur");
        eq(Regio.size(d, 5 * 1024, 1, 2), "5 kB", "kB");
        eq(Regio.size(d, 5L * 1024 * 1024 + 1024 * 300, 1, 2), "5,3 MB", "MB");
        eq(Regio.size(d, 3L * 1024 * 1024 * 1024, 1, 2), "3,00 GB", "GB");
        eq(Regio.size(d, 1023L * 1024, 1, 2), "1023 kB", "geen duizendtallen bij kB (zoals altijd)");
        eq(Regio.size(d, 1023L * 1024 * 1024, 1, 2), "1023,0 MB", "geen duizendtallen bij MB");
        eq(Regio.money(d, -0.004, "EUR"), "€ 0,00", "afgerond nul: geen minteken");
        eq(Regio.money(d, -0.005, "EUR"), "− € 0,01", "afgerond niet nul: wel een minteken");

        // 12-uurs klok
        Regio.Cfg h = Regio.parse("{\"clock\":\"12\"}");
        eq(Regio.pattern(h, "HH:mm"), "h:mm a", "12-uurs: HH:mm");
        eq(Regio.pattern(h, "EEE d MMM HH:mm:ss"), "EEE d MMM h:mm:ss a", "12-uurs met seconden");
        String pm = Regio.df(h, "HH:mm").format(new java.util.Date(t));
        check(pm.startsWith("2:05 ") && pm.toLowerCase().contains("p"), "12-uurs: " + pm);

        // Datumnotatie
        Regio.Cfg iso = Regio.parse("{\"date\":\"iso\"}");
        eq(Regio.pattern(iso, "d MMM yyyy HH:mm"), "yyyy-MM-dd HH:mm", "ISO: datum met maand");
        eq(Regio.pattern(iso, "d-M-yyyy"), "yyyy-MM-dd", "ISO: cijferdatum");
        eq(Regio.pattern(iso, "EEEE d MMMM yyyy"), "EEEE d MMMM yyyy", "ISO: met dagnaam blijft uitgeschreven");
        eq(Regio.pattern(iso, "d MMM HH:mm"), "d MMM HH:mm", "ISO: zonder jaar blijft uitgeschreven");
        eq(Regio.df(iso, "d MMM yyyy").format(new java.util.Date(t)), "2026-11-03", "ISO geschreven");
        Regio.Cfg dmon = Regio.parse("{\"date\":\"dmon\"}");
        eq(Regio.pattern(dmon, "d-M-yyyy"), "d MMM yyyy", "31 okt 2026: cijferdatum met maandnaam");

        // Geen eigen tijdzone: altijd die van de telefoon (ook als er nog een oude keuze in staat)
        eq(Regio.df(Regio.parse("{\"tz\":\"America/New_York\"}"), "HH:mm").format(new java.util.Date(t)), "08:05", "gekozen tijdzone: tijden in de app-zone");
        eq(Regio.df(Regio.parse(""), "HH:mm").format(new java.util.Date(t)), "14:05", "zonder keuze: tijdzone van de telefoon");

        // Getallen, valuta en eenheden
        Regio.Cfg us = Regio.parse("{\"dec\":\".\",\"grp\":\",\",\"cur\":\"USD\",\"dist\":\"mi\",\"temp\":\"f\",\"size\":\"1000\"}");
        eq(Regio.num(us, 1234567.891, 2), "1,234,567.89", "punt als decimaalteken, komma voor duizendtallen");
        eq(Regio.money(us, 1234.5, null), "$ 1,234.50", "gekozen munt (dollar)");
        eq(Regio.money(us, 1234.5, "EUR"), "€ 1,234.50", "eigen munt van het bedrag (euro)");
        eq(Regio.dist(us, 16093.44, 1), "10.0 mi", "mijlen");
        eq(Regio.deg(us, 20), "68°", "Fahrenheit");
        eq(Regio.size(us, 2_500_000_000L, 1, 2), "2.50 GB", "1000 per stap");
        Regio.Cfg sp = Regio.parse("{\"grp\":\" \"}");
        eq(Regio.num(sp, 1234567, 0), "1\u202F234\u202F567", "spatie voor duizendtallen: smalle vaste spatie (U+202F)");
        eq(Regio.num(Regio.parse("{\"grp\":\"\"}"), 1234567, 0), "1234567", "geen teken voor duizendtallen");
        eq(Regio.parse("{\"dec\":\",\",\"grp\":\",\"}").grp, ".", "hetzelfde teken voor beide kan niet");
        eq(Regio.money(Regio.parse("{\"cur\":\"JPY\"}"), 1234.5, null), "¥ 1.235", "yen zonder decimalen");

        // Thuis, werk en de standaardplaats (thuis en werk apart: "spots")
        Regio.Cfg p = Regio.parse("{\"place\":\"home\"}", "{\"home\":{\"name\":\"Thuis\",\"lat\":51.9,\"lon\":4.4}}");
        check(p.home() != null && p.home()[0] == 51.9 && Regio.place(p)[1] == 4.4 && "Thuis".equals(Regio.placeName(p)), "thuis als standaardplaats");
        double[] hc = p.home(); hc[0] = 0;
        check(p.home()[0] == 51.9, "thuis is een kopie (de keuze verandert niet van buitenaf)");
        check(Regio.parse("{\"home\":{\"lat\":51.9,\"lon\":4.4}}", "").home() == null, "thuis in de keuze zelf telt niet (alleen in spots)");
        check(Regio.place(Regio.parse("{\"place\":\"groningen\"}"))[0] == 53.22, "vaste plaats");
        check(Regio.place(d) == null, "mijn locatie: geen vaste plaats");
        check(Regio.parse("", "{\"home\":{\"lat\":95,\"lon\":4}}").home() == null, "onmogelijke plek geweigerd");

        // Bewaren: alleen een gecontroleerde, opnieuw opgebouwde versie
        check(Regio.clean("kapot{") == null && Regio.clean("[1]") == null && Regio.clean(null) == null, "onleesbaar geweigerd");
        String c = Regio.clean("{\"clock\":\"12\",\"evil\":\"<script>\",\"home\":{\"name\":\"Thuis\",\"addr\":\"Coolsingel 40\",\"lat\":51.92,\"lon\":4.48}}");
        check(c != null && !c.contains("evil") && !c.contains("Coolsingel") && !c.contains("home") && Regio.parse(c).h12(), "keuze schoon bewaard, zonder thuis: " + c);
        String sp2 = Regio.cleanSpots("{\"home\":{\"name\":\"Thuis\",\"addr\":\"Coolsingel 40\",\"lat\":51.92,\"lon\":4.48,\"x\":1},\"evil\":1,\"work\":{\"lat\":200,\"lon\":1}}");
        check(sp2 != null && sp2.contains("Coolsingel 40") && !sp2.contains("\"x\"") && !sp2.contains("evil") && !sp2.contains("work"), "thuis en werk schoon bewaard: " + sp2);
        check(Regio.cleanSpots("kapot") == null, "onleesbare plekken geweigerd");

        // Backup: regio.xml (met thuis en werk) nooit in de Android-backup, wel in "Alle appgegevens" (versleuteld)
        try {
            String br = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("app/res/xml/backup_rules.xml")), "UTF-8");
            String dr = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("app/res/xml/data_rules.xml")), "UTF-8");
            String ex = "<exclude domain=\"sharedpref\" path=\"regio.xml\" />";
            int cb = dr.indexOf("<cloud-backup>"), dt = dr.indexOf("<device-transfer>");
            check(br.contains(ex), "Android-backup (7–11): regio.xml uitgesloten");
            check(cb >= 0 && dt > cb && dr.substring(cb, dt).contains(ex), "cloudbackup (12+): regio.xml uitgesloten");
            check(dt >= 0 && dr.substring(dt).contains(ex), "overzetten naar een nieuwe telefoon: regio.xml uitgesloten");
        } catch (Exception e) { check(false, "backupregels te lezen: " + e); }
        check(AppData.PREFS_KNOWN.contains("regio") && AppData.keepPrefsFile("regio"), "Alle appgegevens (eigen, versleuteld): regio gaat mee");
        check(Regio.parse("{\"lang\":\"en\"}").lang.equals("en") && Regio.parse("{\"lang\":\"fr\"}").lang.equals("nl"), "alleen bekende talen (Nederlands en Engels)");
        check(Regio.DEFAULT.locale().getLanguage().equals("nl") && Regio.DEFAULT.locale().getCountry().equals("NL"), "taal: nl-NL");

        tijdzone();

        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
    }
}
