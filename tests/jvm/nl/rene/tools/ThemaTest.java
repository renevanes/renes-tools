package nl.rene.tools;

/** Thema: donker of licht bij het opstarten en voor de Telefoon-skin (rooster en venster, ook over middernacht). */
public class ThemaTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    public static void main(String[] a) {
        // Venster over middernacht: donker van 20:00 tot 07:00
        check(Thema.inWindow(20 * 60, 1200, 420) && Thema.inWindow(23 * 60 + 59, 1200, 420) && Thema.inWindow(0, 1200, 420) && Thema.inWindow(419, 1200, 420), "20:00–07:00: avond en nacht donker");
        check(!Thema.inWindow(420, 1200, 420) && !Thema.inWindow(12 * 60, 1200, 420) && !Thema.inWindow(1199, 1200, 420), "20:00–07:00: overdag licht");
        // Venster binnen één dag: donker van 01:00 tot 05:00
        check(Thema.inWindow(60, 60, 300) && Thema.inWindow(299, 60, 300) && !Thema.inWindow(300, 60, 300) && !Thema.inWindow(59, 60, 300), "01:00–05:00");
        check(!Thema.inWindow(0, 600, 600) && !Thema.inWindow(600, 600, 600), "zelfde tijd: nooit donker");
        check(Thema.inWindow(5, 0, 1440) && Thema.inWindow(1439, 0, 1440), "0–1440: de hele dag donker (poolnacht)");

        // Vaste keuzes en systeem
        check(!Thema.dark("", 0, 0, false) && Thema.dark("", 0, 0, true), "niets ingesteld: zoals de telefoon");
        check(Thema.dark("{\"mode\":\"system\"}", 0, 0, true) && !Thema.dark("{\"mode\":\"system\"}", 0, 0, false), "systeem volgt de telefoon");
        check(!Thema.dark("{\"mode\":\"light\"}", 0, 0, true), "licht, ook als de telefoon donker is");
        check(Thema.dark("{\"mode\":\"dark\"}", 0, 0, false), "donker, ook als de telefoon licht is");
        check(Thema.dark("kapot{", 0, 0, true) && !Thema.dark("kapot{", 0, 0, false), "onleesbaar: zoals de telefoon");

        // Op tijd
        String t = "{\"mode\":\"time\",\"win\":[1260,420]}";
        check(Thema.dark(t, 0, 22 * 60, false) && !Thema.dark(t, 0, 20 * 60 + 59, true) && !Thema.dark(t, 0, 7 * 60, true), "op tijd: 21:00–07:00");

        // Zon: rooster met wissels, daarna het venster van de laatste dag
        long h = 3600_000L;
        String s = "{\"mode\":\"sun\",\"sched\":[[1000,1],[" + (1000 + 8 * h) + ",0],[" + (1000 + 17 * h) + ",1],[" + (1000 + 32 * h) + ",0]],\"end\":" + (1000 + 40 * h) + ",\"win\":[1020,480]}";
        check(Thema.dark(s, 1000 + h, 0, false), "zon: nacht (na de eerste wissel)");
        check(!Thema.dark(s, 1000 + 9 * h, 0, true), "zon: overdag licht");
        check(Thema.dark(s, 1000 + 17 * h, 0, false), "zon: precies bij zonsondergang donker");
        check(!Thema.dark(s, 1000 + 33 * h, 0, true), "zon: volgende ochtend licht");
        check(Thema.dark(s, 1000 + 50 * h, 18 * 60, false) && !Thema.dark(s, 1000 + 50 * h, 12 * 60, true), "zon: na het rooster het venster van de laatste dag");
        check(Thema.dark(s, 500, 18 * 60, false) && !Thema.dark(s, 500, 12 * 60, true), "zon: vóór het rooster ook het venster");

        // Controle van wat de interface doorgeeft
        check(Thema.clean("{\"mode\":\"evil\"}") == null && Thema.clean(null) == null && Thema.clean("[1]") == null, "onbekende soort geweigerd");
        check(Thema.clean("{\"mode\":\"sun\",\"sched\":[[\"x\",1]]}") == null, "rooster zonder tijden geweigerd");
        String c = Thema.clean("{\"mode\":\"time\",\"amoled\":true,\"win\":[1260,420],\"extra\":\"weg\"}");
        check(c != null && !c.contains("extra") && c.contains("\"amoled\":true") && Thema.dark(c, 0, 23 * 60, false), "schoon opgebouwd, zonder extra velden");
        StringBuilder big = new StringBuilder("{\"mode\":\"dark\",\"x\":\"");
        for (int i = 0; i < 20000; i++) big.append('a');
        check(Thema.clean(big.append("\"}").toString()) == null, "te groot geweigerd");

        // Neutrale versie: @media vast op donker of licht
        String css = "a{}@media (prefers-color-scheme: dark){:root{--bg:#000}}@media (prefers-color-scheme:dark){b{}}";
        check(Thema.forceCss(css, true).equals("a{}@media all{:root{--bg:#000}}@media all{b{}}"), "neutrale versie: donker");
        check(Thema.forceCss(css, false).equals("a{}@media not all{:root{--bg:#000}}@media not all{b{}}"), "neutrale versie: licht");

        // Plek: grof afgerond
        check("51.92,4.48".equals(Thema.round(51.9244, 4.4777)) && "-33.87,151.21".equals(Thema.round(-33.8688, 151.2093)), "plek afgerond op 0,01°");
        check("".equals(Thema.round(Double.NaN, 4)) && "".equals(Thema.round(91, 4)), "ongeldige plek: leeg");
        check(Thema.hex(Thema.BG_DARK).equals("#111619") && Thema.hex(Thema.BG_LIGHT).equals("#F4F5F6"), "achtergrondkleuren als #RRGGBB");

        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
    }
}
