package nl.rene.tools;

import java.util.Arrays;
import java.util.List;

/** Podcasts: vanzelf downloaden over een periode (kiezen binnen de ruimte) en vanzelf gedownloade afleveringen weer verwijderen. */
public class PodcastPeriodeTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    public static void main(String[] a) {
        long mb = 1024L * 1024;
        long[] dates = {100, 400, 300, 200};
        long[] sizes = {10 * mb, 30 * mb, 20 * mb, 40 * mb};
        check(PodcastDownloads.kiesPeriode(dates, sizes, -1, 40).equals(Arrays.asList(1, 2, 3, 0)), "geen grens: alles, de nieuwste eerst");
        check(PodcastDownloads.kiesPeriode(dates, sizes, 55 * mb, 40).equals(Arrays.asList(1, 2)), "ruimte op: de nieuwste die passen");
        List<Integer> klein = PodcastDownloads.kiesPeriode(dates, sizes, 65 * mb, 40);
        check(klein.equals(Arrays.asList(1, 2, 0)), "een te grote overgeslagen, een kleinere oudere past nog: " + klein);
        check(PodcastDownloads.kiesPeriode(dates, sizes, 0, 40).isEmpty(), "geen ruimte meer: niets");
        check(PodcastDownloads.kiesPeriode(dates, sizes, -1, 2).equals(Arrays.asList(1, 2)), "hooguit zoveel per keer");
        check(PodcastDownloads.kiesPeriode(new long[0], new long[0], -1, 40).isEmpty(), "niets te kiezen");

        check(PodcastDownloads.schat(12345, 600) == 12345, "opgegeven grootte gaat voor");
        check(PodcastDownloads.schat(0, 600) == 600 * 16_000L, "anders uit de lengte (128 kbit/s)");
        check(PodcastDownloads.schat(0, 0) == 50 * mb, "anders 50 MB");

        long dag = PodcastDownloads.DAG, nu = 100 * dag;
        check(PodcastDownloads.teOud(nu - 15 * dag, nu, 14), "na 2 weken weg");
        check(!PodcastDownloads.teOud(nu - 13 * dag, nu, 14), "binnen 2 weken blijft hij");
        check(!PodcastDownloads.teOud(nu - 400 * dag, nu, 0), "Nooit: blijft altijd");
        check(!PodcastDownloads.teOud(0, nu, 14), "onbekende downloadtijd: niet weg");

        check(PodcastDownloads.kies(14, PodcastDownloads.PERIODE_DAGEN, 14) == 14 && PodcastDownloads.kies(99, PodcastDownloads.PERIODE_DAGEN, 14) == 14, "alleen bekende keuzes");
        check(PodcastDownloads.STD_DAGEN == 14 && PodcastDownloads.STD_BEWAAR == 14, "standaard: 2 weken terug, na 2 weken weer weg");

        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
        System.out.println("alles goed");
    }
}
