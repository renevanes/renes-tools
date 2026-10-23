package nl.rene.tools;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/** Welke podcast-afleveringen vanzelf gedownload worden, en de bestandsextensie. */
public class PodcastDownloadsTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    static List<String> pick(String[] k, long[] d, boolean[] p, String[] have, long from, int max) {
        return PodcastDownloads.pickAuto(k, d, p, new HashSet<>(Arrays.asList(have)), from, max);
    }

    public static void main(String[] a) {
        String[] k = { "e5", "e4", "e3", "e2", "e1" };
        long[] d = { 50, 40, 30, 20, 10 };
        boolean[] none = new boolean[5];
        check(pick(k, d, none, new String[0], 0, 3).equals(Arrays.asList("e5", "e4", "e3")), "de nieuwste 3");
        boolean[] p = { false, true, false, false, false };
        check(pick(k, d, p, new String[0], 0, 3).equals(Arrays.asList("e5", "e3", "e2")), "beluisterde tellen niet mee");
        check(pick(k, d, none, new String[] { "e5" }, 0, 3).equals(Arrays.asList("e4", "e3")), "al gedownload of weggehaald neemt een plek in");
        check(pick(k, d, none, new String[0], 35, 3).equals(Arrays.asList("e5", "e4")), "niets van vóór het aanzetten");
        check(pick(k, d, new boolean[] { true, true, true, true, true }, new String[0], 0, 3).isEmpty(), "alles beluisterd: niets");
        check(pick(new String[] { "", "x" }, new long[] { 9, 8 }, new boolean[2], new String[0], 0, 3).equals(Arrays.asList("x")), "lege sleutel overslaan");
        check(pick(k, new long[] { 0, 0, 0, 0, 0 }, none, new String[0], 100, 2).equals(Arrays.asList("e5", "e4")), "zonder datum: wel meenemen");

        check(".mp3".equals(PodcastDownloads.ext("https://x.nl/a.mp3?x=1", "")), "mp3 uit de url");
        check(".m4a".equals(PodcastDownloads.ext("https://x.nl/a", "audio/x-m4a")), "m4a uit het type");
        check(".ogg".equals(PodcastDownloads.ext("https://x.nl/a.ogg", "")), "ogg");
        check(".opus".equals(PodcastDownloads.ext("https://x.nl/a.opus", "")), "opus");
        check(".mp3".equals(PodcastDownloads.ext("https://x.nl/stream", "")), "onbekend: mp3");
        if (fails > 0) { System.out.println(fails + " fout(en)"); System.exit(1); }
        System.out.println("alles goed");
    }
}
