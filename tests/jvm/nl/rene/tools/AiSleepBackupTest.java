package nl.rene.tools;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** Alles back-uppen: geheugen, gesprekken en Slaappraat terugzetten (samenvoegen zonder iets te overschrijven). */
public class AiSleepBackupTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    public static void main(String[] a) {
        int ADD = AiSleepBackup.ADD, DUP = AiSleepBackup.DUP, BAD = AiSleepBackup.BAD, FULL = AiSleepBackup.FULL;

        // ---------- geheugen ----------
        List<String> haveIds = Arrays.asList("m1", "m2"), haveTexts = Arrays.asList("Woont in Rotterdam.", "Heeft een zoon, Daan.");
        int[] p = AiSleepBackup.memoryPlan(haveIds, haveTexts,
                Arrays.asList("m1", "x1", "x2", "x3", "x4", "x5"),
                Arrays.asList("Iets anders met hetzelfde id.", "woont in Rotterdam", "Sport op dinsdag.", "  ", "Sport elke dinsdag.", "Werkt bij ESAPE."), 300);
        check(p[0] == DUP, "zelfde id: staat er al");
        check(p[1] == DUP, "(bijna) dezelfde tekst: staat er al");
        check(p[2] == ADD && p[5] == ADD, "nieuwe herinneringen: toevoegen");
        check(p[3] == BAD, "lege herinnering: overslaan");
        check(p[4] == DUP, "dubbel binnen de backup zelf: maar één keer");
        int[] full = AiSleepBackup.memoryPlan(haveIds, haveTexts, Arrays.asList("y1", "y2"), Arrays.asList("Drinkt graag thee.", "Rijdt een Volvo."), 3);
        check(full[0] == ADD && full[1] == FULL, "geheugen vol: de rest past niet");
        int[] badId = AiSleepBackup.memoryPlan(haveIds, haveTexts, Arrays.asList("<script>"), Arrays.asList("Speelt gitaar."), 300);
        check(badId[0] == ADD, "vreemd id: wel toevoegen (krijgt een eigen id)");

        // ---------- gesprekken ----------
        int[] c = AiSleepBackup.chatPlan(new HashSet<>(Arrays.asList("a", "b")), 2,
                Arrays.asList("a", "c", "../x", "d", "c"), Arrays.asList(5L, 10L, 11L, 20L, 9L), 100);
        check(c[0] == DUP, "gesprek met bestaand id: blijft zoals het is");
        check(c[1] == ADD && c[3] == ADD, "nieuwe gesprekken: toevoegen");
        check(c[2] == BAD, "vreemd id: overslaan");
        check(c[4] == DUP, "zelfde id twee keer in de backup: één keer");
        int[] cf = AiSleepBackup.chatPlan(new HashSet<>(Arrays.asList("a")), 1, Arrays.asList("old", "new", "mid"), Arrays.asList(1L, 30L, 20L), 3);
        check(cf[1] == ADD && cf[2] == ADD && cf[0] == FULL, "vol: de nieuwste uit de backup gaan voor, bestaande blijven");
        int[] none = AiSleepBackup.chatPlan(Collections.<String>emptySet(), 100, Arrays.asList("z"), Arrays.asList(1L), 100);
        check(none[0] == FULL, "al 100 gesprekken: niets erbij en niets weg");

        // ---------- nachten ----------
        long now = 1_761_300_000_000L, day = 86_400_000L;
        String recent = String.valueOf(now - 3 * day), old = String.valueOf(now - 20 * day);
        check(AiSleepBackup.nightPlan(recent, false, "", now, 14).isEmpty(), "nacht van 3 dagen geleden: terugzetten");
        check("dup".equals(AiSleepBackup.nightPlan(recent, true, "", now, 14)), "nacht die er al is: nooit overschrijven");
        check("running".equals(AiSleepBackup.nightPlan(recent, true, recent, now, 14)) && "running".equals(AiSleepBackup.nightPlan(recent, false, recent, now, 14)), "de nacht die nu loopt: nooit aanraken");
        check("old".equals(AiSleepBackup.nightPlan(old, false, "", now, 14)) && AiSleepBackup.nightPlan(old, false, "", now, 30).isEmpty(), "ouder dan de bewaartermijn: niet (zou meteen weer opgeruimd worden)");
        check("bad".equals(AiSleepBackup.nightPlan("../1761", false, "", now, 14)) && "bad".equals(AiSleepBackup.nightPlan(null, false, "", now, 14)), "vreemd id: overslaan");

        // ---------- geluid ----------
        byte[] w = SleepTalk.wav(new short[]{1, 2, 3});
        check(AiSleepBackup.isWav(w, w.length), "WAV herkennen");
        check(!AiSleepBackup.isWav(w, 20) && !AiSleepBackup.isWav("RIFF....AVIxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx".getBytes(), 44) && !AiSleepBackup.isWav(null, 0), "geen of half WAV-bestand: niet terugzetten");
        check("1761256800000/c1761256801000.wav".equals(AiSleepBackup.audioKey("1761256800000", "c1761256801000")), "plek van het geluid in de backup");
        check(AiSleepBackup.safeId("lq3x9a_b-1") && !AiSleepBackup.safeId("a/b") && !AiSleepBackup.safeId("") && !AiSleepBackup.safeId(null), "id's alleen uit veilige tekens");

        if (fails > 0) { System.out.println(fails + " fout(en)"); System.exit(1); }
        System.out.println("alles goed");
        System.exit(0);
    }
}
