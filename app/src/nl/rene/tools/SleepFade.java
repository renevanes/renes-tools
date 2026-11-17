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

    /** Langste uitfaadtijd, en hoe kort hij bij een korte timer hooguit wordt (tenzij er korter gekozen is). */
    static final long MAX_MS = 180_000, MIN_MS = 10_000;
    /** Wat Rene kan kiezen (in het slaaptimervenster): uit, 15, 20 of 30 s, 1, 2 of 3 minuten. Standaard 20 seconden. */
    static final long[] KEUZES = { 0, 15_000, 20_000, 30_000, 60_000, 120_000, 180_000 };
    static final long STANDAARD_MS = 20_000;
    /** In de instellingen van de speler (Player.prefs), in seconden. */
    static final String KEY = "sleepFade";
    /** Hoe vaak het volume een stapje zakt (zo klein dat je geen trapjes hoort). */
    static final long STEP_MS = 200;
    /** Waar het uitfaden eindigt: zo zacht dat stoppen niet meer opvalt. */
    static final double FLOOR_DB = -45;

    /** Een geldige keuze (in ms), anders de standaard. */
    static long geldig(long ms) {
        for (long k : KEUZES) if (k == ms) return ms;
        return STANDAARD_MS;
    }

    /** De gekozen uitfaadtijd (ms); 0 = uit. Bij een onleesbare waarde de standaard (wordt elke 200 ms gelezen). */
    static long gekozen(android.content.Context c) {
        try { return geldig(Player.prefs(c).getInt(KEY, (int) (STANDAARD_MS / 1000)) * 1000L); }
        catch (Throwable e) { return STANDAARD_MS; }
    }

    /** Het volume tijdens het uitfaden gaat alleen omlaag: kiest Rene halverwege een kortere tijd (of uit), dan geen
     *  sprong terug naar vol, maar zo zacht blijven als het al was. vorige = het laagste tot nu toe (1 bij een nieuwe timer). */
    static float omlaag(float vorige, float nu) { return Math.min(vorige, nu); }

    /** Uitfaadtijd kiezen (seconden). Alleen een van de KEUZES; geeft false bij iets anders. */
    static boolean kies(android.content.Context c, int sec) {
        long ms = sec * 1000L;
        if (geldig(ms) != ms) return false;
        Player.prefs(c).edit().putInt(KEY, sec).apply();
        return true;
    }

    /** Uitfaadtijd bij een slaaptimer die over totalMs afloopt, als wantMs gekozen is: de gekozen tijd, maar bij een korte
     *  timer nooit meer dan een derde ervan (wel minstens MIN_MS, of korter als dat gekozen is). 0 = niet uitfaden. */
    static long length(long totalMs, long wantMs) {
        if (wantMs <= 0) return 0;
        return Math.min(wantMs, Math.max(MIN_MS, totalMs / 3));
    }

    /** Volume (0..1) als er nog leftMs over is van een uitfaadtijd van fadeMs. */
    static float gain(long leftMs, long fadeMs) {
        if (fadeMs <= 0 || leftMs >= fadeMs) return 1f;
        if (leftMs <= 0) return 0f;
        double t = 1 - leftMs / (double) fadeMs;   // 0 = begin van het uitfaden, 1 = einde
        double s = t * t * (3 - 2 * t);            // zacht beginnen en zacht eindigen
        return (float) Math.pow(10, FLOOR_DB * s / 20);
    }

    /** Wanneer de volgende stap nodig is als de keuze intussen kan veranderen: tijdens het uitfaden elke 200 ms; in de
     *  laatste MAX_MS daarvoor elke seconde (een langere keuze begint dan op tijd); daarvoor pas vlak voor die laatste MAX_MS. */
    static long wacht(long leftMs, long fadeMs) {
        if (leftMs <= fadeMs) return Math.max(1, Math.min(STEP_MS, leftMs));
        if (leftMs <= MAX_MS) return Math.max(1, Math.min(1000, leftMs - fadeMs));
        return Math.max(1, Math.min(30_000, leftMs - MAX_MS));
    }

    /** Wanneer de volgende stap nodig is: vlak voor het uitfaden pas wakker worden, tijdens het uitfaden vaak. */
    static long nextDelay(long leftMs, long fadeMs) {
        if (leftMs <= fadeMs) return Math.max(1, Math.min(STEP_MS, leftMs));
        return Math.max(1, Math.min(30_000, leftMs - fadeMs));
    }
}
