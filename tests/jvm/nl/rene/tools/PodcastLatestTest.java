package nl.rene.tools;

import java.util.Arrays;

/** "Nieuwe afleveringen" op Podcasts: welke afleveringen, in welke volgorde. */
public class PodcastLatestTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    public static void main(String[] a) {
        // Twee podcasts door elkaar: de nieuwste eerst, over alle podcasts heen
        String[] k = { "a3", "a2", "a1", "b2", "b1" };
        long[] d = { 300, 200, 100, 250, 150 };
        check(Podcasts.pickLatest(k, d, 10).equals(Arrays.asList(0, 3, 1, 4, 2)), "nieuwste eerst, over alle podcasts heen");
        check(Podcasts.pickLatest(k, d, 2).equals(Arrays.asList(0, 3)), "hooguit het gevraagde aantal");
        // Gelijke datum: de volgorde van binnenkomst (podcastvolgorde) blijft
        check(Podcasts.pickLatest(new String[] { "x", "y", "z" }, new long[] { 5, 5, 5 }, 3).equals(Arrays.asList(0, 1, 2)), "gelijke datum: volgorde blijft");
        // Dezelfde aflevering in twee feeds, of zonder sleutel: één keer, of niet
        check(Podcasts.pickLatest(new String[] { "x", "x", "", null, "y" }, new long[] { 9, 8, 7, 6, 5 }, 10).equals(Arrays.asList(0, 4)), "dubbel of leeg overslaan");
        // Zonder datum achteraan
        check(Podcasts.pickLatest(new String[] { "x", "y" }, new long[] { 0, 3 }, 10).equals(Arrays.asList(1, 0)), "zonder datum achteraan");
        check(Podcasts.pickLatest(new String[0], new long[0], 5).isEmpty(), "niets gevolgd: leeg");
        if (fails > 0) { System.out.println(fails + " fout(en)"); System.exit(1); }
        System.out.println("alles goed");
    }
}
