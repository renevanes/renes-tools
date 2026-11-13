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

        File tmp = Files.createTempDirectory("tools-webstart-").toFile();
        try {
            File root = new File(tmp, "web");
            WebUpdateStore store = new WebUpdateStore(root);
            check(store.current(11) == null, "lege opslag: niets");
            store.activate(store.stage(10, "1.10", 3, data("interface 10"), data("start 10")));
            check(store.current(10) == null, "alleen de afgekeurde versie: de meegeleverde interface");
            store.activate(store.stage(11, "1.11", 3, data("interface 11"), data("start 11")));
            check(store.current(0).code == 11 && store.current(12).code == 11, "niets afgekeurd, of een andere versie: de nieuwste");
            WebUpdateStore.Snapshot terug = store.current(11);
            check(terug != null && terug.code == 10 && terug.readHtml().equals("interface 10") && terug.readStart().equals("start 10"), "afgekeurd: de vorige volledige versie, compleet");
            check(new WebUpdateStore(root).current(11).code == 10, "ook na opnieuw starten");
            check(store.current().code == 11, "de gewone verwijzing blijft zoals hij was");
            store.activate(store.stage(12, "1.12", 3, data("interface 12"), null));
            check(store.current(11).code == 12, "een nieuwere versie gaat voor op de afkeuring");
        } finally { erase(tmp); }
        System.out.println("Alle " + checks + " controles van de startbewaking geslaagd");
    }
    private static void erase(File f) { File[] kids = f.listFiles(); if (kids != null) for (File k : kids) erase(k); f.delete(); }
}
