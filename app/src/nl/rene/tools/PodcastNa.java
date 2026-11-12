package nl.rene.tools;

/**
 * Wat de podcastspeler doet als een aflevering afgelopen is, en wanneer hij als vastgelopen geldt.
 * Zuiver (zonder Android), zodat het te testen is (PodcastNaTest).
 */
final class PodcastNa {
    private PodcastNa() { }

    /** Na een aflevering: stoppen; alleen Hierna; Hierna, dan de volgende van dezelfde podcast; Hierna, dan je nieuwe afleveringen. */
    static final String STOP = "stop", HIERNA = "hierna", PODCAST = "podcast", NIEUW = "nieuw";
    static final String STANDAARD = PODCAST;

    static String norm(String s) {
        return STOP.equals(s) || HIERNA.equals(s) || PODCAST.equals(s) || NIEUW.equals(s) ? s : STANDAARD;
    }

    private static int rank(String s) { return STOP.equals(s) ? 0 : HIERNA.equals(s) ? 1 : PODCAST.equals(s) ? 2 : 3; }

    /**
     * Wat er nu echt gebeurt. "Stoppen na deze aflevering" (slaaptimer) stopt altijd. Loopt er een slaaptimer op tijd en
     * staat "doorgaan zolang de slaaptimer loopt" aan, dan gaat hij minstens door met de volgende van dezelfde podcast.
     */
    static String effective(String setting, boolean sleepTimer, boolean sleepEnd, boolean keepWithTimer) {
        if (sleepEnd) return STOP;
        String s = norm(setting);
        if (sleepTimer && keepWithTimer && rank(s) < rank(PODCAST)) return PODCAST;
        return s;
    }

    // ---------- vastlopen ----------

    /** Zo lang mag de plek stilstaan terwijl hij speelt (ook tijdens bufferen), dan opnieuw verbinden. */
    static final long STALL_MS = 15_000;
    /** Zo lang mag een sprong (terugspoelen, schuif) duren. */
    static final long SEEK_MS = 12_000;
    /** Zo lang mag het laden van een aflevering duren. */
    static final long PREPARE_MS = 30_000;
    /** Zo vaak achter elkaar opnieuw verbinden; daarna een melding. Telt opnieuw zodra hij weer een halve minuut speelt. */
    static final int MAX_RECONNECT = 3;
    static final long HEALTHY_MS = 30_000;

    /** Houdt bij of de plek nog vooruitgaat. */
    static final class Stall {
        private long lastPos = -1, lastMove, healthySince;
        int reconnects;

        void reset(long now) { lastPos = -1; lastMove = now; }

        /** true = vastgelopen. Alleen tijdens afspelen; tijdens een sprong let de sprong zelf op de tijd. */
        boolean check(long now, long pos, boolean playing, boolean seeking) {
            if (!playing || seeking) { lastPos = pos; lastMove = now; healthySince = 0; return false; }
            if (pos != lastPos) {
                if (lastPos >= 0 && healthySince == 0) healthySince = now;
                lastPos = pos; lastMove = now;
                if (healthySince > 0 && now - healthySince >= HEALTHY_MS) reconnects = 0;
                return false;
            }
            healthySince = 0;
            return now - lastMove > STALL_MS;
        }
    }

    /**
     * Meldt de speler "afgelopen" terwijl er nog ruim een minuut over is, dan brak de verbinding af (Android meldt dat
     * soms zo). Dan één keer opnieuw op die plek; komt hij daar weer niet verder, dan geldt het als echt afgelopen.
     */
    static boolean prematureEnd(long pos, long dur) { return dur > 0 && pos > 0 && pos < dur - 60_000; }

    /** Zelfde plek als de vorige poging (binnen 20 s): dan niet nog eens. */
    static boolean sameSpot(long a, long b) { return a >= 0 && b >= 0 && Math.abs(a - b) < 20_000; }
}
