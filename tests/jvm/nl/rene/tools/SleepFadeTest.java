package nl.rene.tools;

/** Slaaptimer: langzaam en gelijkmatig uitfaden, zonder sprongen en tot bijna stil. */
public class SleepFadeTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }
    static double db(float g) { return 20 * Math.log10(Math.max(1e-9, g)); }

    public static void main(String[] args) {
        // Hoe lang het uitfaden duurt: zoals gekozen (standaard 20 seconden), bij een korte timer hooguit een derde
        check(SleepFade.STANDAARD_MS == 20_000, "standaard 20 seconden (keuze van Rene)");
        check(SleepFade.geldig(SleepFade.STANDAARD_MS) == SleepFade.STANDAARD_MS, "de standaard is zelf een van de keuzes");
        check(SleepFade.length(30 * 60_000L, SleepFade.STANDAARD_MS) == 20_000, "30 minuten, standaard: 20 seconden uitfaden");
        check(SleepFade.length(60_000L, SleepFade.STANDAARD_MS) == 20_000, "1 minuut, standaard: ook 20 seconden (een derde)");
        check(SleepFade.length(30 * 60_000L, 180_000) == 180_000, "30 minuten, 3 minuten gekozen: 3 minuten");
        check(SleepFade.length(120 * 60_000L, 15_000) == 15_000, "2 uur, 15 seconden gekozen: 15 seconden");
        check(SleepFade.length(6 * 60_000L, 180_000) == 120_000, "6 minuten, 3 minuten gekozen: een derde, 2 minuten");
        check(SleepFade.length(60_000L, 60_000) == 20_000, "1 minuut: een derde, 20 seconden");
        check(SleepFade.length(60_000L, 15_000) == 15_000 && SleepFade.length(0, 60_000) == 10_000, "heel kort: minstens 10 seconden, of korter als dat gekozen is");
        check(SleepFade.length(30 * 60_000L, 0) == 0 && SleepFade.gain(5_000, SleepFade.length(30 * 60_000L, 0)) == 1f, "uit: niet zachter, vol tot het stoppen");
        // Alleen vaste keuzes
        check(SleepFade.geldig(15_000) == 15_000 && SleepFade.geldig(0) == 0 && SleepFade.geldig(180_000) == 180_000, "vaste keuzes geldig");
        check(SleepFade.geldig(45_000) == SleepFade.STANDAARD_MS && SleepFade.geldig(-1000) == SleepFade.STANDAARD_MS && SleepFade.geldig(3_600_000) == SleepFade.STANDAARD_MS, "andere waarden: de standaard");
        for (long k : SleepFade.KEUZES) check(k == 0 || (k >= 15_000 && k <= SleepFade.MAX_MS), "keuze binnen de grenzen: " + k);
        // Halverwege korter kiezen (of uit): geen sprong terug naar vol, zo zacht blijven als het al was
        float bijna = SleepFade.gain(50_000, 180_000);
        float laag = SleepFade.omlaag(SleepFade.omlaag(1f, SleepFade.gain(60_000, 180_000)), bijna);
        check(db(SleepFade.omlaag(laag, SleepFade.gain(50_000, 15_000))) < -30, "3 min → 15 s met 50 s over: blijft zacht (" + String.format("%.1f dB", db(SleepFade.omlaag(laag, SleepFade.gain(50_000, 15_000)))) + ")");
        check(SleepFade.omlaag(laag, SleepFade.gain(50_000, 0)) == laag, "3 min → uit tijdens het uitfaden: blijft zacht");
        check(SleepFade.omlaag(1f, SleepFade.gain(10_000, 15_000)) < 1f && SleepFade.omlaag(laag, 0f) == 0f, "verder omlaag kan altijd");
        // Wakker worden: tijdens het uitfaden elke 200 ms, in de laatste 3 minuten elke seconde, daarvoor zelden
        check(SleepFade.wacht(10 * 60_000L, 15_000) == 30_000, "ver weg: na 30 s weer kijken");
        check(SleepFade.wacht(SleepFade.MAX_MS + 4_000, 15_000) == 4_000, "vlak voor de laatste 3 minuten: op tijd");
        check(SleepFade.wacht(120_000, 15_000) == 1000, "laatste 3 minuten, nog niet aan het uitfaden: elke seconde (niet elke 200 ms)");
        check(SleepFade.wacht(15_500, 15_000) == 500 && SleepFade.wacht(10_000, 15_000) == SleepFade.STEP_MS, "tijdens het uitfaden: kleine stapjes, precies op tijd beginnen");
        check(SleepFade.wacht(0, 0) >= 1 && SleepFade.wacht(500, 0) == 500 && SleepFade.wacht(5_000, 0) == 1000, "uit: geen drukke lus, op tijd stoppen");
        // Ook de kortste keuze zakt zonder grote sprongen
        double max15 = 0; float p15 = 1f;
        for (long left = 15_000; left >= SleepFade.STEP_MS; left -= SleepFade.STEP_MS) { float g = SleepFade.gain(left, 15_000); max15 = Math.max(max15, db(p15) - db(g)); p15 = g; }
        check(max15 < 1.2, "15 seconden: per stap minder dan 1,2 dB: " + String.format("%.3f dB", max15));

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
        for (long left = SleepFade.STANDAARD_MS; left >= SleepFade.STEP_MS; left -= SleepFade.STEP_MS) {
            float g = SleepFade.gain(left, SleepFade.STANDAARD_MS); maxEnd = Math.max(maxEnd, db(prev) - db(g)); prev = g;
        }
        check(maxEnd < 1.0, "einde aflevering (standaard 20 s): per stap minder dan 1 dB: " + String.format("%.3f dB", maxEnd));

        // Wachten: buiten het uitfaden pas vlak ervoor wakker, tijdens het uitfaden elke 200 ms
        check(SleepFade.nextDelay(10 * 60_000L, f) == 30_000, "ver weg: na 30 s weer kijken");
        check(SleepFade.nextDelay(f + 5_000, f) == 5_000, "vlak voor het uitfaden: precies op tijd beginnen");
        check(SleepFade.nextDelay(f - 1, f) == SleepFade.STEP_MS && SleepFade.nextDelay(50, f) == 50, "tijdens het uitfaden: kleine stapjes, niet voorbij het einde");
        check(SleepFade.nextDelay(0, f) >= 1, "nooit 0 ms (geen drukke lus)");

        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
        System.out.println("errors []");
    }
}
