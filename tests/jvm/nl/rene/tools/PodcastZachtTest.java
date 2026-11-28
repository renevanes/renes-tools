package nl.rene.tools;

public class PodcastZachtTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    public static void main(String[] a) {
        int van = 20 * 60, tot = 7 * 60;
        check(PodcastZacht.nu("avond", van, tot, 22 * 60) && PodcastZacht.nu("avond", van, tot, 2 * 60) && PodcastZacht.nu("avond", van, tot, van), "avond en nacht: zacht");
        check(!PodcastZacht.nu("avond", van, tot, 12 * 60) && !PodcastZacht.nu("avond", van, tot, tot) && !PodcastZacht.nu("avond", van, tot, van - 1), "overdag: niet");
        check(PodcastZacht.nu("avond", 9 * 60, 17 * 60, 12 * 60) && !PodcastZacht.nu("avond", 9 * 60, 17 * 60, 18 * 60), "een venster binnen de dag");
        check(PodcastZacht.nu("altijd", van, tot, 12 * 60) && !PodcastZacht.nu("uit", van, tot, 22 * 60) && PodcastZacht.nu("iets", van, tot, 22 * 60), "altijd, uit, en onbekend = avond");
        long nu = 1_800_000_000_000L;
        check(PodcastZacht.nieuweBeurt(nu, 0) && PodcastZacht.nieuweBeurt(nu, nu - PodcastZacht.PAUZE_MS) && PodcastZacht.nieuweBeurt(nu, nu + 5000), "nog niets gespeeld, lang geleden of klok teruggezet: nieuwe beurt");
        check(!PodcastZacht.nieuweBeurt(nu, nu - 60_000) && !PodcastZacht.nieuweBeurt(nu, nu), "net nog gespeeld (ook: vanzelf de volgende): geen nieuwe beurt");
        check(PodcastZacht.niveau(15, 7) == 1 && PodcastZacht.niveau(45, 7) == 3 && PodcastZacht.niveau(15, 1) == 1 && PodcastZacht.niveau(0, 7) == 0, "zacht niveau: minstens één stap");
        check(PodcastZacht.doel(30, 45, 7) == 3 && PodcastZacht.doel(0, 45, 7) == 3, "harder of stil: naar zacht");
        check(PodcastZacht.doel(3, 45, 7) == -1 && PodcastZacht.doel(2, 45, 7) == -1 && PodcastZacht.doel(5, 0, 7) == -1, "al zacht(er): blijft zo");
        check(PodcastZacht.pct(0) == PodcastZacht.PCT && PodcastZacht.pct(99) == PodcastZacht.PCT && PodcastZacht.pct(20) == 20 && PodcastZacht.minuut(-1, 5) == 5 && PodcastZacht.minuut(1440, 5) == 5, "ongeldige waarden: de standaard");
        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
    }
}
