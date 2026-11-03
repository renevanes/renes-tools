package nl.rene.tools;

import java.util.Calendar;
import java.util.TimeZone;

/** Regio en taal in Java: de standaard is precies zoals de app het altijd schreef; de keuzes werken in patronen, getallen en eenheden. */
public class RegioTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }
    static void eq(String got, String want, String what) { check(want.equals(got), what + ": " + got + (want.equals(got) ? "" : " (verwacht " + want + ")")); }

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

        // Tijdzone
        Regio.Cfg ny = Regio.parse("{\"tz\":\"America/New_York\"}");
        eq(Regio.df(ny, "HH:mm").format(new java.util.Date(t)), "08:05", "vaste tijdzone (New York)");
        check(Regio.parse("{\"tz\":\"Mars/Olympus\"}").tz.isEmpty() && Regio.parse("{\"tz\":\"../x\"}").tz.isEmpty(), "onbekende tijdzone: zoals de telefoon");

        // Getallen, valuta en eenheden
        Regio.Cfg us = Regio.parse("{\"dec\":\".\",\"grp\":\",\",\"cur\":\"USD\",\"dist\":\"mi\",\"temp\":\"f\",\"size\":\"1000\"}");
        eq(Regio.num(us, 1234567.891, 2), "1,234,567.89", "punt als decimaalteken, komma voor duizendtallen");
        eq(Regio.money(us, 1234.5, null), "$ 1,234.50", "gekozen munt (dollar)");
        eq(Regio.money(us, 1234.5, "EUR"), "€ 1,234.50", "eigen munt van het bedrag (euro)");
        eq(Regio.dist(us, 16093.44, 1), "10.0 mi", "mijlen");
        eq(Regio.deg(us, 20), "68°", "Fahrenheit");
        eq(Regio.size(us, 2_500_000_000L, 1, 2), "2.50 GB", "1000 per stap");
        Regio.Cfg sp = Regio.parse("{\"grp\":\" \"}");
        eq(Regio.num(sp, 1234567, 0), "1 234 567", "spatie voor duizendtallen");
        eq(Regio.num(Regio.parse("{\"grp\":\"\"}"), 1234567, 0), "1234567", "geen teken voor duizendtallen");
        eq(Regio.parse("{\"dec\":\",\",\"grp\":\",\"}").grp, ".", "hetzelfde teken voor beide kan niet");
        eq(Regio.money(Regio.parse("{\"cur\":\"JPY\"}"), 1234.5, null), "¥ 1.235", "yen zonder decimalen");

        // Thuis, werk en de standaardplaats
        Regio.Cfg p = Regio.parse("{\"home\":{\"name\":\"Thuis\",\"lat\":51.9,\"lon\":4.4},\"place\":\"home\"}");
        check(p.home != null && p.home[0] == 51.9 && Regio.place(p)[1] == 4.4 && "Thuis".equals(Regio.placeName(p)), "thuis als standaardplaats");
        check(Regio.place(Regio.parse("{\"place\":\"groningen\"}"))[0] == 53.22, "vaste plaats");
        check(Regio.place(d) == null, "mijn locatie: geen vaste plaats");
        check(Regio.parse("{\"home\":{\"lat\":95,\"lon\":4}}").home == null, "onmogelijke plek geweigerd");

        // Bewaren: alleen een gecontroleerde, opnieuw opgebouwde versie
        check(Regio.clean("kapot{") == null && Regio.clean("[1]") == null && Regio.clean(null) == null, "onleesbaar geweigerd");
        String c = Regio.clean("{\"clock\":\"12\",\"evil\":\"<script>\",\"home\":{\"name\":\"Thuis\",\"addr\":\"Coolsingel 40\",\"lat\":51.92,\"lon\":4.48,\"x\":1}}");
        check(c != null && !c.contains("evil") && !c.contains("\"x\"") && c.contains("Coolsingel 40") && Regio.parse(c).h12(), "schoon bewaard: " + c);
        check(Regio.parse("{\"lang\":\"en\"}").lang.equals("nl"), "alleen bekende talen (nu Nederlands)");
        check(Regio.DEFAULT.locale().getLanguage().equals("nl") && Regio.DEFAULT.locale().getCountry().equals("NL"), "taal: nl-NL");

        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
    }
}
