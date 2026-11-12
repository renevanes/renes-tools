package nl.rene.tools;

/** Podcastspeler: wat er na een aflevering gebeurt (met en zonder slaaptimer) en wanneer hij als vastgelopen geldt. */
public class PodcastNaTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    public static void main(String[] a) {
        // Instelling
        check(PodcastNa.norm(null).equals(PodcastNa.PODCAST) && PodcastNa.norm("onzin").equals(PodcastNa.PODCAST), "onbekend = standaard (volgende van de podcast)");
        for (String m : new String[]{"stop", "hierna", "podcast", "nieuw"}) check(PodcastNa.norm(m).equals(m), "geldig: " + m);

        // Zonder slaaptimer: wat ingesteld is
        for (String m : new String[]{"stop", "hierna", "podcast", "nieuw"}) check(PodcastNa.effective(m, false, false, true).equals(m), "zonder timer: " + m);
        // Stoppen na deze aflevering: altijd stoppen
        for (String m : new String[]{"stop", "hierna", "podcast", "nieuw"}) check(PodcastNa.effective(m, false, true, true).equals("stop"), "einde-aflevering stopt altijd (" + m + ")");
        // Slaaptimer op tijd met doorgaan aan: minstens de volgende van de podcast
        check(PodcastNa.effective("stop", true, false, true).equals("podcast"), "timer + stoppen → toch door met de podcast");
        check(PodcastNa.effective("hierna", true, false, true).equals("podcast"), "timer + alleen Hierna → ook de podcast");
        check(PodcastNa.effective("nieuw", true, false, true).equals("nieuw"), "timer houdt 'nieuw'");
        check(PodcastNa.effective("stop", true, false, false).equals("stop"), "doorgaan uit: de instelling");

        // Vastlopen: alleen tijdens afspelen, niet tijdens een sprong, na 15 s stilstand
        PodcastNa.Stall s = new PodcastNa.Stall();
        long t = 1_000_000;
        s.reset(t);
        check(!s.check(t, 5000, true, false), "eerste meting");
        check(!s.check(t + 1000, 6000, true, false), "speelt door");
        check(!s.check(t + 10_000, 6000, true, false), "10 s stil: nog niet");
        check(s.check(t + 17_000, 6000, true, false), "16 s stil: vastgelopen");
        check(!s.check(t + 40_000, 6000, false, false), "gepauzeerd telt niet");
        s.reset(t + 80_000); // play() begint opnieuw te tellen
        check(!s.check(t + 80_000, 6000, true, false), "na pauze opnieuw beginnen met tellen");
        check(!s.check(t + 90_000, 6000, true, true), "tijdens een sprong niet");
        check(!s.check(t + 100_000, 6000, true, false), "na een sprong opnieuw tellen");

        // Weer gezond: de teller van herverbindingen gaat terug naar 0
        PodcastNa.Stall h = new PodcastNa.Stall();
        h.reset(0); h.reconnects = 2;
        long p = 0;
        for (long now = 0; now <= 32_000; now += 1000) { h.check(now, p, true, false); p += 1000; }
        check(h.reconnects == 0, "halve minuut gespeeld: tellen begint opnieuw");

        // Te vroeg "afgelopen" (verbinding afgebroken) en dezelfde plek
        check(PodcastNa.prematureEnd(10 * 60_000, 30 * 60_000), "na 10 van 30 min afgelopen = afgebroken");
        check(!PodcastNa.prematureEnd(29 * 60_000 + 30_000, 30 * 60_000), "laatste minuut = echt afgelopen");
        check(!PodcastNa.prematureEnd(10 * 60_000, 0), "lengte onbekend: echt afgelopen");
        check(PodcastNa.sameSpot(600_000, 610_000) && !PodcastNa.sameSpot(600_000, 640_000) && !PodcastNa.sameSpot(-1, 600_000), "zelfde plek binnen 20 s");

        // Bufferen: langer geduld
        PodcastNa.Stall b = new PodcastNa.Stall();
        b.reset(0); b.check(0, 1000, true, false, true);
        check(!b.check(20_000, 1000, true, false, true), "bufferen: 20 s stil is nog geen hapering");
        check(b.check(41_000, 1000, true, false, true), "bufferen: na 40 s wel");

        // Sprongen samenvoegen
        PodcastNa.Seek k = new PodcastNa.Seek();
        check(k.ask(60_000) && k.busy() && k.where() == 60_000, "eerste sprong: meteen");
        check(!k.ask(45_000) && !k.ask(30_000) && k.where() == 30_000, "nog twee keer terug tijdens de sprong: alleen de laatste onthouden");
        check(k.done() == 30_000 && k.busy(), "na de eerste sprong: naar de laatste plek");
        check(k.done() == -1 && !k.busy() && k.where() == -1, "daarna klaar");
        k.ask(10_000); k.clear();
        check(!k.busy() && k.ask(5000), "na loslaten: opnieuw meteen");

        // De volgende van dezelfde podcast (lijst nieuwste eerst: index 0 is de nieuwste)
        final boolean[] done = new boolean[10];
        PodcastNa.Done d = i -> done[i];
        check(PodcastNa.nextIndex(10, 5, true, d) == 4, "eerst de nieuwere (at - 1)");
        done[4] = true; done[3] = true;
        check(PodcastNa.nextIndex(10, 5, true, d) == 2, "beluisterde nieuwere overslaan");
        done[2] = true; done[1] = true; done[0] = true;
        check(PodcastNa.nextIndex(10, 5, true, d) == 6, "alle nieuwere beluisterd: de oudere");
        check(PodcastNa.nextIndex(10, 5, false, d) == -1, "alleen nieuwere (bij nieuw): geen");
        check(PodcastNa.nextIndex(10, 0, true, i -> false) == 1, "de nieuwste: dan de oudere");
        check(PodcastNa.nextIndex(10, -1, true, i -> false) == -1 && PodcastNa.nextIndex(0, 0, true, i -> false) == -1, "onbekende aflevering: geen");
        check(PodcastNa.nextIndex(10, 5, true, i -> i == 5 || i != 7) == 7, "de huidige wordt nooit gekozen");

        if (fails > 0) { System.out.println(fails + " fout(en)"); System.exit(1); }
        System.out.println("alles goed");
    }
}
