package nl.rene.tools;

/**
 * Opstarttijden van het hoofdscherm vastleggen (taak Prestaties en stabiliteit): alleen namen van vaste stappen en
 * aantallen milliseconden, in logcat onder {@link #TAG}. De telefoontest (tests/telefoon/rooktest.sh) leest die regels
 * en zet ze bij de Metingen. Er gaat niets de telefoon uit en er staan geen gegevens in.
 */
final class Opstart {
    static final String TAG = "ToolsStart";

    private final java.util.function.LongSupplier klok;
    private final long begin;
    private long vorige;
    private final StringBuilder stappen = new StringBuilder();

    Opstart(java.util.function.LongSupplier klok) {
        this.klok = klok;
        this.begin = this.vorige = klok.getAsLong();
    }

    /** Op de telefoon: de klok sinds het opstarten van Android (loopt niet door in diepe slaap; prima voor korte stukken). */
    static Opstart nu() { return new Opstart(android.os.SystemClock::uptimeMillis); }

    /** Einde van een stap: de tijd sinds de vorige stap komt erbij als " naam=ms". Namen alleen vaste woorden uit de code. */
    Opstart stap(String naam) {
        long t = klok.getAsLong();
        stappen.append(' ').append(naam).append('=').append(Math.max(0, t - vorige));
        vorige = t;
        return this;
    }

    /** Tijd sinds het begin (ms). */
    long sinds() { return Math.max(0, klok.getAsLong() - begin); }

    /** De regel voor logcat, bijv. "onCreate voorWeb=12 web=180 laden=40 totaal=232". */
    String regel(String wat) { return wat + stappen + " totaal=" + Math.max(0, vorige - begin); }

    /** Naar logcat (alleen getallen); nooit een fout als logcat er niet is (JVM-tests). */
    static void log(String regel) {
        try { android.util.Log.i(TAG, regel); } catch (Throwable ignored) { }
    }
}
