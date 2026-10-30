package nl.rene.tools;

/**
 * Slaaptimer: langzaam en gelijkmatig uitfaden (podcast en radio).
 *
 * Het oor hoort volume in decibel, niet als getal tussen 0 en 1: een rechte lijn van 1 naar 0 klinkt eerst lang
 * hetzelfde en valt dan aan het eind ineens weg. Daarom zakt het volume hier gelijkmatig in decibel, met een zachte
 * start en een zacht einde, tot bijna niets (−45 dB). Pas dan stopt het afspelen, zodat je het stoppen niet hoort.
 */
final class SleepFade {
    private SleepFade() { }

    /** Langste en kortste uitfaadtijd. */
    static final long MAX_MS = 180_000, MIN_MS = 20_000;
    /** Hoe vaak het volume een stapje zakt (zo klein dat je geen trapjes hoort). */
    static final long STEP_MS = 200;
    /** Waar het uitfaden eindigt: zo zacht dat stoppen niet meer opvalt. */
    static final double FLOOR_DB = -45;

    /** Uitfaadtijd bij een slaaptimer die over totalMs afloopt: 3 minuten, maar nooit meer dan een derde ervan. */
    static long length(long totalMs) {
        return Math.max(MIN_MS, Math.min(MAX_MS, totalMs / 3));
    }

    /** Volume (0..1) als er nog leftMs over is van een uitfaadtijd van fadeMs. */
    static float gain(long leftMs, long fadeMs) {
        if (fadeMs <= 0 || leftMs >= fadeMs) return 1f;
        if (leftMs <= 0) return 0f;
        double t = 1 - leftMs / (double) fadeMs;   // 0 = begin van het uitfaden, 1 = einde
        double s = t * t * (3 - 2 * t);            // zacht beginnen en zacht eindigen
        return (float) Math.pow(10, FLOOR_DB * s / 20);
    }

    /** Wanneer de volgende stap nodig is: vlak voor het uitfaden pas wakker worden, tijdens het uitfaden vaak. */
    static long nextDelay(long leftMs, long fadeMs) {
        if (leftMs <= fadeMs) return Math.max(1, Math.min(STEP_MS, leftMs));
        return Math.max(1, Math.min(30_000, leftMs - fadeMs));
    }
}
