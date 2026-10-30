package nl.rene.tools;

/** Gesprek met de assistent: merken dat je uitgesproken bent, en onderbreken tijdens het voorlezen (zonder op de eigen stem te reageren). */
public class AiTalkTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    /** Stukjes van 100 ms met hetzelfde niveau; geeft de eerste uitkomst die niet BUSY is, of BUSY. */
    static int run(AiTalk.End e, float lv, int ms) { int r = AiTalk.BUSY; for (int t = 0; t < ms; t += 100) { r = e.feed(lv, 100); if (r != AiTalk.BUSY) return r; } return r; }

    public static void main(String[] args) {
        // Stil, dan praten, dan 1,3 s stil: klaar
        AiTalk.End e = new AiTalk.End(1300);
        check(run(e, 0.15f, 600) == AiTalk.BUSY && !e.speech, "eerst stil: nog niets");
        check(run(e, 0.7f, 1500) == AiTalk.BUSY && e.speech, "praten: spraak gehoord");
        check(run(e, 0.16f, 1000) == AiTalk.BUSY, "1 s stil: nog niet klaar");
        check(run(e, 0.16f, 400) == AiTalk.DONE, "na 1,3 s stilte: klaar");

        // Korte adempauzes tussen woorden tellen niet als einde
        e = new AiTalk.End(1300);
        run(e, 0.1f, 300);
        boolean early = false;
        for (int i = 0; i < 8; i++) { if (run(e, 0.75f, 700) != AiTalk.BUSY) early = true; if (run(e, 0.12f, 600) != AiTalk.BUSY) early = true; }
        check(!early, "pauzes van 0,6 s tussen zinnen: niet klaar");
        check(run(e, 0.12f, 1500) == AiTalk.DONE, "daarna echt stil: klaar");

        // Een tik of klik (kort) is geen spraak
        e = new AiTalk.End(1300);
        run(e, 0.1f, 500); e.feed(0.9f, 100); run(e, 0.1f, 2000);
        check(!e.speech, "een korte klik is geen spraak");

        // Niets gezegd: na 8 s NONE
        e = new AiTalk.End(1300);
        check(run(e, 0.1f, 9000) == AiTalk.NONE, "8 s niets gezegd: niets gehoord");

        // Lawaaiige omgeving: de drempel gaat mee met het achtergrondgeluid
        e = new AiTalk.End(1300);
        run(e, 0.42f, 1500);
        check(!e.speech && e.threshold() > 0.5f, "rumoer (0,42): geen spraak, drempel hoger (" + e.threshold() + ")");
        run(e, 0.85f, 1200);
        check(e.speech, "duidelijk harder praten in rumoer: spraak");
        check(run(e, 0.43f, 1500) == AiTalk.DONE, "terug naar het rumoer: klaar");

        // Instelbare stilte
        AiTalk.End slow = new AiTalk.End(2200);
        run(slow, 0.1f, 300); run(slow, 0.7f, 800);
        check(run(slow, 0.1f, 1600) == AiTalk.BUSY && run(slow, 0.1f, 800) == AiTalk.DONE, "stilte 2,2 s: pas na 2,2 s klaar");
        check(new AiTalk.End(100).silenceMs == 500 && new AiTalk.End(99_999).silenceMs == 4000, "stilte begrensd tot 0,5–4 s");

        // Heel lang praten: na een minuut toch klaar
        e = new AiTalk.End(1300);
        check(run(e, 0.7f, 61_000) == AiTalk.LONG, "na een minuut praten: klaar (te lang)");

        // Onderbreken: eerst de echo leren, dan alleen op duidelijk harder geluid dat even aanhoudt
        AiTalk.Barge b = new AiTalk.Barge();
        boolean hit = false;
        for (int t = 0; t < 3000; t += 50) hit |= b.feed(0.45f + (t % 200 == 0 ? 0.08f : 0f), 50);
        check(!hit, "alleen de eigen stem (echo 0,45–0,53): niet onderbreken");
        check(b.threshold() >= 0.65f, "drempel boven de echo (" + b.threshold() + ")");
        for (int t = 0; t < 200; t += 50) hit |= b.feed(0.95f, 50);
        check(!hit, "een korte piek (0,2 s): niet onderbreken");
        for (int t = 0; t < 300; t += 50) b.feed(0.45f, 50);
        for (int t = 0; t < 500 && !hit; t += 50) hit = b.feed(0.9f, 50);
        check(hit, "duidelijk door de stem heen praten (0,35 s): onderbreken");

        AiTalk.Barge q = new AiTalk.Barge();
        for (int t = 0; t < 600; t += 50) q.feed(0.05f, 50);
        boolean h2 = false;
        for (int t = 0; t < 1000; t += 50) h2 |= q.feed(0.4f, 50);
        check(!h2 && q.threshold() >= 0.5f, "met oortjes (bijna geen echo): zacht geluid nog niet, drempel minstens 0,5");
        for (int t = 0; t < 600 && !h2; t += 50) h2 = q.feed(0.7f, 50);
        check(h2, "met oortjes: gewoon praten onderbreekt");
        AiTalk.Barge loud = new AiTalk.Barge();
        for (int t = 0; t < 600; t += 50) loud.feed(0.9f, 50);
        check(loud.threshold() <= 0.93f, "luide echo: drempel hooguit 0,93 (" + loud.threshold() + ")");

        // Onderbreken: nooit eindeloos de microfoon open
        check(!AiTalk.bargeExpired(0, 0, 2_000) && !AiTalk.bargeExpired(0, 118_000, 119_000), "luistert zolang de interface kijkt (tot 2 minuten)");
        check(AiTalk.bargeExpired(0, 119_500, 120_000), "na 2 minuten: stoppen");
        check(AiTalk.bargeExpired(0, 1_000, 4_000) && !AiTalk.bargeExpired(0, 1_000, 3_900), "de interface vroeg 3 s niets meer: stoppen");

        System.out.println(fails == 0 ? "\nAlles goed" : "\n" + fails + " mislukt");
        if (fails > 0) System.exit(1);
    }
}
