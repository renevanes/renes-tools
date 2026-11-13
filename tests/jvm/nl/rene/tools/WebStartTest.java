package nl.rene.tools;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Startbewaking van een gedownloade interface: tellen, afkeuren en terugvallen op de vorige volledige versie. */
public final class WebStartTest {
    private static int checks;
    private static void check(boolean condition, String text) { checks++; if (!condition) throw new AssertionError(text); }
    private static byte[] data(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    public static void main(String[] args) throws Exception {
        check(WebStart.mislukt(null, 12) == 0 && WebStart.mislukt("", 12) == 0, "geen stand = niets mislukt");
        String een = WebStart.naMislukt("", 12);
        check(een.equals("12:1") && WebStart.mislukt(een, 12) == 1 && !WebStart.afgekeurd(een, 12), "één keer niet gestart: nog niet afgekeurd");
        String twee = WebStart.naMislukt(een, 12);
        check(WebStart.afgekeurd(twee, 12), "twee keer niet gestart: afgekeurd");
        check(WebStart.naMislukt(twee, 12).equals(twee), "de teller loopt niet verder op");
        check(WebStart.mislukt(twee, 13) == 0 && !WebStart.afgekeurd(twee, 13), "een andere versie begint opnieuw");
        check(WebStart.naMislukt(twee, 13).equals("13:1"), "een nieuwe versie vervangt de oude stand");
        for (String kapot : new String[]{"12", ":2", "x:2", "12:x", "12:-3", "12:2:2"})
            check(!WebStart.afgekeurd(kapot, 12), "onleesbare stand keurt niets af: " + kapot);
        check(WebStart.mislukt("12:99", 12) == WebStart.MAX, "een te hoog aantal wordt begrensd");
        check(!WebStart.afgekeurd("0:2", 0) && WebStart.mislukt("12:2", -1) == 0, "zonder versie geen afkeuring");

        // De lijst met afgekeurde versies groeit alleen; wat de app zelf heeft ingehaald valt eraf
        check(WebStart.lijst(null, 10).isEmpty() && WebStart.lijst("", 10).isEmpty() && WebStart.lijst("x,,-3, 0", 10).isEmpty(), "lege of onleesbare lijst");
        check(WebStart.metAfgekeurd("", 12, 10).equals("12"), "eerste afkeuring");
        check(WebStart.metAfgekeurd("12", 11, 10).equals("11,12") && WebStart.metAfgekeurd("11,12", 12, 10).equals("11,12"), "een tweede versie komt erbij, niets valt weg");
        check(WebStart.metAfgekeurd("11,12", 14, 12).equals("14") && WebStart.lijst("11,12", 12).isEmpty(), "een nieuwere app ruimt oude afkeuringen op");
        check(WebStart.metAfgekeurd("12", 9, 10).equals("12") && WebStart.metAfgekeurd("12", 0, 10).equals("12"), "de meegeleverde versie of ouder wordt nooit afgekeurd");

        // Een versie die hier al eens goed startte, wordt niet afgekeurd (de laatste twee tellen)
        check(WebStart.metGoed("", 12).equals("12") && WebStart.metGoed(null, 12).equals("12"), "eerste goede start");
        check(WebStart.metGoed("12", 12).equals("12") && WebStart.metGoed("12", 14).equals("14,12") && WebStart.metGoed("14,12", 15).equals("15,14"), "de laatste twee, nieuwste vooraan");
        check(WebStart.metGoed("14,12", 12).equals("12,14") && WebStart.metGoed("x", 0).equals("x"), "opnieuw gemeld of geen versie");
        check(WebStart.eerderGoed("14,12", 12) && WebStart.eerderGoed("14,12", 14) && !WebStart.eerderGoed("14,12", 15) && !WebStart.eerderGoed("", 12) && !WebStart.eerderGoed("12", 0), "alleen wat zich echt gemeld heeft");

        File tmp = Files.createTempDirectory("tools-webstart-").toFile();
        try {
            File root = new File(tmp, "web");
            WebUpdateStore store = new WebUpdateStore(root);
            java.util.Set<Integer> geen = new java.util.TreeSet<>(), s10 = WebStart.lijst("10", 0), s11 = WebStart.lijst("11", 0), beide = WebStart.lijst("10,11", 0);
            check(store.current(s11) == null, "lege opslag: niets");
            store.activate(store.stage(10, "1.10", 3, data("interface 10"), data("start 10")));
            check(store.current(s10) == null, "alleen de afgekeurde versie: de meegeleverde interface");
            store.activate(store.stage(11, "1.11", 3, data("interface 11"), data("start 11")));
            check(store.current(geen).code == 11 && store.current((java.util.Set<Integer>) null).code == 11 && store.current(WebStart.lijst("12", 0)).code == 11, "niets afgekeurd, of een andere versie: de nieuwste");
            WebUpdateStore.Snapshot terug = store.current(s11);
            check(terug != null && terug.code == 10 && terug.readHtml().equals("interface 10") && terug.readStart().equals("start 10"), "afgekeurd: de vorige volledige versie, compleet");
            check(store.current(s11) == terug, "de terugvalversie wordt niet elke keer opnieuw gelezen");
            check(new WebUpdateStore(root).current(s11).code == 10, "ook na opnieuw starten");
            check(store.current().code == 11, "de gewone verwijzing blijft zoals hij was");
            check(store.current(beide) == null, "beide versies afgekeurd: de meegeleverde interface, geen wisselen tussen twee");
            // Een nieuwere versie na een afkeuring: de herstelkopie is de laatste goede versie, niet de afgekeurde
            store.activate(store.stage(12, "1.12", 3, data("interface 12"), null), s11);
            check(store.current(s11).code == 12, "een nieuwere versie gaat voor");
            java.util.Set<Integer> s1112 = WebStart.lijst("11,12", 0);
            WebUpdateStore.Snapshot goed = store.current(s1112);
            check(goed != null && goed.code == 10 && goed.readHtml().equals("interface 10"), "start de nieuwere ook niet: terug naar de laatste goede (10), nooit naar de afgekeurde 11");
            check(new WebUpdateStore(root).current(s1112).code == 10, "ook na opnieuw starten");
            store.activate(store.stage(13, "1.13", 3, data("interface 13"), null));
            check(store.current(WebStart.lijst("13", 0)).code == 12, "zonder afkeuring blijft de vorige actieve versie de herstelkopie");
        } finally { erase(tmp); }
        System.out.println("Alle " + checks + " controles van de startbewaking geslaagd");
    }
    private static void erase(File f) { File[] kids = f.listFiles(); if (kids != null) for (File k : kids) erase(k); f.delete(); }
}
