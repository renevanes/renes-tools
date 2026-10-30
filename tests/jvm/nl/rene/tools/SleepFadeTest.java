package nl.rene.tools;

/** Slaaptimer: langzaam en gelijkmatig uitfaden, zonder sprongen en tot bijna stil. */
public class SleepFadeTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }
    static double db(float g) { return 20 * Math.log10(Math.max(1e-9, g)); }

    public static void main(String[] args) {
        // Hoe lang het uitfaden duurt
        check(SleepFade.length(30 * 60_000L) == 180_000, "30 minuten: 3 minuten uitfaden");
        check(SleepFade.length(120 * 60_000L) == 180_000, "2 uur: ook 3 minuten (niet langer)");
        check(SleepFade.length(6 * 60_000L) == 120_000, "6 minuten: een derde, 2 minuten");
        check(SleepFade.length(60_000L) == 20_000 && SleepFade.length(0) == 20_000, "heel kort: minstens 20 seconden");

        long f = 180_000;
        check(SleepFade.gain(f + 1, f) == 1f && SleepFade.gain(f, f) == 1f && SleepFade.gain(10 * 60_000L, f) == 1f, "voor het uitfaden: vol");
        check(SleepFade.gain(0, f) == 0f && SleepFade.gain(-5, f) == 0f, "op het eind: stil");
        check(SleepFade.gain(1000, 0) == 1f, "geen uitfaadtijd: vol");
        float end = SleepFade.gain(SleepFade.STEP_MS, f);
        check(db(end) < -44 && db(end) > -46, "net voor het stoppen bijna stil (−45 dB): " + String.format("%.1f dB", db(end)));
        float half = SleepFade.gain(f / 2, f);
        check(Math.abs(db(half) + 22.5) < 0.5, "halverwege −22,5 dB (gelijkmatig in decibel): " + String.format("%.1f dB", db(half)));

        // Elke stap zakt een heel klein beetje: nooit omhoog, nooit een hoorbare sprong
        float prev = 1f; double maxStep = 0; boolean down = true;
        for (long left = f; left >= SleepFade.STEP_MS; left -= SleepFade.STEP_MS) {
            float g = SleepFade.gain(left, f);
            if (g > prev + 1e-6f) down = false;
            maxStep = Math.max(maxStep, db(prev) - db(g));
            prev = g;
        }
        check(down, "het volume gaat alleen omlaag");
        check(maxStep < 0.2, "per stap minder dan 0,2 dB (geen trapjes): " + String.format("%.3f dB", maxStep));
        // Zacht beginnen: de eerste 10 seconden nauwelijks iets
        check(db(SleepFade.gain(f - 10_000, f)) > -0.5, "zacht beginnen: na 10 s nog geen halve dB zachter");
        // Ook bij het uitfaden aan het eind van een aflevering (1 minuut, stappen van 1 s buiten het slot)
        double maxEnd = 0; prev = 1f;
        for (long left = PodcastService.END_FADE_MS; left >= SleepFade.STEP_MS; left -= SleepFade.STEP_MS) {
            float g = SleepFade.gain(left, PodcastService.END_FADE_MS); maxEnd = Math.max(maxEnd, db(prev) - db(g)); prev = g;
        }
        check(maxEnd < 0.5, "einde aflevering: per stap minder dan 0,5 dB: " + String.format("%.3f dB", maxEnd));

        // Wachten: buiten het uitfaden pas vlak ervoor wakker, tijdens het uitfaden elke 200 ms
        check(SleepFade.nextDelay(10 * 60_000L, f) == 30_000, "ver weg: na 30 s weer kijken");
        check(SleepFade.nextDelay(f + 5_000, f) == 5_000, "vlak voor het uitfaden: precies op tijd beginnen");
        check(SleepFade.nextDelay(f - 1, f) == SleepFade.STEP_MS && SleepFade.nextDelay(50, f) == 50, "tijdens het uitfaden: kleine stapjes, niet voorbij het einde");
        check(SleepFade.nextDelay(0, f) >= 1, "nooit 0 ms (geen drukke lus)");

        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
        System.out.println("errors []");
    }
}
