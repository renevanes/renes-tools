package nl.rene.tools;

/** Assistent: hooguit één antwoord tegelijk (twee keer snel op versturen = één antwoord). */
public class AiGateTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    public static void main(String[] a) throws Exception {
        LocalAi.Gate g = new LocalAi.Gate();
        check(!g.busy(), "begin: vrij");
        long one = g.enter();
        check(one != 0 && g.busy(), "eerste vraag krijgt de beurt");
        check(g.enter() == 0, "tweede vraag tijdens het antwoord: geweigerd");
        g.leave(one);
        check(!g.busy(), "klaar: weer vrij");
        g.leave(one);
        long two = g.enter();
        check(two != 0 && two != one, "daarna: nieuwe beurt");
        g.leave(one);
        check(g.busy(), "een oude beurt geeft een nieuwere niet vrij");
        g.leave(0);
        check(g.busy(), "beurt 0 (geweigerd) geeft niets vrij");
        g.leave(two);

        // Veel tikken tegelijk: precies één krijgt de beurt
        final LocalAi.Gate h = new LocalAi.Gate();
        final java.util.concurrent.atomic.AtomicInteger got = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        Thread[] ts = new Thread[16];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = new Thread(() -> { try { go.await(); if (h.enter() != 0) got.incrementAndGet(); } catch (Throwable ignored) { } });
            ts[i].start();
        }
        go.countDown();
        for (Thread t : ts) t.join();
        check(got.get() == 1, "16 vragen tegelijk: precies één antwoord");

        // Motor hangt na "stoppen": na 25 s beëindigen (en dan is de beurt weer vrij)
        check(!LocalAi.stuck(0, 999_999_999L), "niet gestopt: nooit hangend");
        check(!LocalAi.stuck(1_000L, 1_000L + LocalAi.STUCK_MS - 1), "net gestopt: nog even wachten");
        check(LocalAi.stuck(1_000L, 1_000L + LocalAi.STUCK_MS), "na 25 s nog geen einde: hangend");
        check(LocalAi.STUCK_MS == 25_000L, "wachttijd 25 s");

        System.out.println(fails == 0 ? "OK" : fails + " FOUT");
        System.exit(fails == 0 ? 0 : 1);
    }
}
