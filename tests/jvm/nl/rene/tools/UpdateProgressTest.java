package nl.rene.tools;

import java.util.ArrayList;
import java.util.List;

/** Voortgang van een update: hooguit eens per 200 ms, maar altijd bij een nieuwe fase en als het klaar is. */
public class UpdateProgressTest {
    static boolean all = true;
    static void ok(boolean c, String m) { System.out.println((c ? "✓ " : "✗ ") + m); if (!c) all = false; }

    public static void main(String[] a) {
        long[] now = {1000};
        List<String> seen = new ArrayList<>();
        Updater.Throttle t = new Updater.Throttle((ph, d, tot) -> seen.add(ph + ":" + d + "/" + tot), 200, () -> now[0]);
        t.step("start", 0, 0);
        t.step("download", 0, 1000);           // nieuwe fase: door
        now[0] += 50; t.step("download", 100, 1000);  // te snel: niet
        now[0] += 50; t.step("download", 200, 1000);  // te snel: niet
        now[0] += 150; t.step("download", 300, 1000); // 250 ms na de vorige: door
        now[0] += 10; t.step("download", 1000, 1000); // klaar: altijd door
        now[0] += 10; t.step("check", 0, 0);          // nieuwe fase: door
        ok(seen.size() == 5, "vijf stappen doorgegeven (" + seen + ")");
        ok(seen.get(0).equals("start:0/0") && seen.get(1).equals("download:0/1000"), "begin en nieuwe fase");
        ok(seen.get(2).equals("download:300/1000"), "tussenstap na 200 ms");
        ok(seen.get(3).equals("download:1000/1000"), "klaar komt altijd door");
        ok(seen.get(4).equals("check:0/0"), "volgende fase");
        // Onbekende grootte (0): gewoon op tijd doorgeven
        seen.clear();
        now[0] += 1000; t.step("download2", 10, 0);
        now[0] += 10; t.step("download2", 20, 0);
        now[0] += 300; t.step("download2", 30, 0);
        ok(seen.size() == 2 && seen.get(1).equals("download2:30/0"), "zonder grootte: op tijd (" + seen + ")");
        System.out.println(all ? "Alle voortgangstests geslaagd" : "Voortgangstest mislukt");
        System.exit(all ? 0 : 1);
    }
}
