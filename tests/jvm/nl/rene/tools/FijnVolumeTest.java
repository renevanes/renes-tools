package nl.rene.tools;

/** Fijner volume met de knoppen: welke stap van Android en hoeveel zachter daarbinnen. */
public class FijnVolumeTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }
    static boolean near(double a, double b) { return Math.abs(a - b) < 1e-4; }

    public static void main(String[] a) {
        // Keuzes
        check(FijnVolume.norm(2) == 2 && FijnVolume.norm(3) == 3 && FijnVolume.norm(4) == 4, "geldige keuzes");
        check(FijnVolume.norm(0) == FijnVolume.STANDAARD && FijnVolume.norm(7) == FijnVolume.STANDAARD && FijnVolume.STANDAARD == 3, "onbekend = standaard (3 keer zo fijn)");

        // Stap van Android: naar boven afgerond, binnen min..max
        check(FijnVolume.stap(0, 3, 0, 15) == 0, "niveau 0 = stil");
        check(FijnVolume.stap(1, 3, 0, 15) == 1 && FijnVolume.stap(3, 3, 0, 15) == 1 && FijnVolume.stap(4, 3, 0, 15) == 2, "afronden naar boven");
        check(FijnVolume.stap(45, 3, 0, 15) == 15 && FijnVolume.stap(99, 3, 0, 15) == 15, "niet boven het hoogste");
        check(FijnVolume.stap(0, 3, 1, 15) == 1, "niet onder het laagste van het toestel");

        // Precies op een stap van Android: gewoon vol (geen eigen versterking)
        check(FijnVolume.gain(9, 3, 3, -30, -27) == 1f, "op een stap: 1");
        // Tussen twee stappen: in dB ertussen (stap van 3 dB, 1 en 2 fijne stappen eronder)
        check(near(FijnVolume.gain(8, 3, 3, -30, -27), Math.pow(10, -1.0 / 20)), "1/3 stap lager = 1 dB zachter");
        check(near(FijnVolume.gain(7, 3, 3, -30, -27), Math.pow(10, -2.0 / 20)), "2/3 stap lager = 2 dB zachter");
        // Elke fijne stap klinkt zachter dan de vorige, en geen sprong bij de grens
        float vorige = 2f; boolean dalend = true;
        for (int v = 9; v >= 4; v--) { int s = FijnVolume.stap(v, 3, 0, 15); float g = FijnVolume.gain(v, 3, s, -27 - 3 * (3 - s + 1), -27 - 3 * (3 - s));
            double db = -27 - 3 * (3 - s) + 20 * Math.log10(g); if (db >= vorige) dalend = false; vorige = (float) db; }
        check(dalend, "van niveau 9 naar 4: telkens zachter");
        // Onbekende of vreemde dB: een vaste stap
        check(near(FijnVolume.gain(8, 3, 3, Double.NaN, -27), Math.pow(10, -FijnVolume.STAP_DB / 3 / 20)), "onbekend: vaste stap van 2,5 dB");
        check(near(FijnVolume.gain(8, 3, 3, -27, -27), Math.pow(10, -FijnVolume.STAP_DB / 3 / 20)), "Bluetooth (geen verschil in dB): vaste stap");
        // Van stilte naar stap 1: rechtlijnig
        check(near(FijnVolume.gain(1, 3, 1, Double.NEGATIVE_INFINITY, -50), 1.0 / 3) && near(FijnVolume.gain(2, 4, 1, Double.NEGATIVE_INFINITY, -50), 0.5), "onderste stap: deel van stap 1");
        check(FijnVolume.gain(5, 1, 5, -20, -18) == 1f, "fijn 1: niets eigens");

        // Een druk: één fijne stap; snel achter elkaar of vasthouden: naar de volgende hele stap van Android
        check(FijnVolume.doel(9, 3, -1, false) == 8 && FijnVolume.doel(9, 3, 1, false) == 10, "losse druk: één fijne stap");
        check(FijnVolume.doel(9, 3, -1, true) == 6 && FijnVolume.doel(8, 3, -1, true) == 6 && FijnVolume.doel(7, 3, -1, true) == 6, "snel omlaag: naar de hele stap eronder");
        check(FijnVolume.doel(9, 3, 1, true) == 12 && FijnVolume.doel(10, 3, 1, true) == 12 && FijnVolume.doel(11, 3, 1, true) == 12, "snel omhoog: naar de hele stap erboven");
        check(FijnVolume.doel(2, 3, -1, true) == 0 && FijnVolume.doel(0, 3, -1, true) == 0, "snel omlaag: niet onder 0");
        check(FijnVolume.doel(5, 1, -1, true) == 4, "fijn 1: gewoon één stap");
        // Van heel hard (45) naar zacht (15) met vasthouden: net zo veel drukken als zonder fijn volume (10)
        int v = 45, n = 0; while (v > 15) { v = FijnVolume.doel(v, 3, -1, true); n++; }
        check(n == 10, "vasthouden: 10 drukken van stap 15 naar 5 (" + n + ")");
        // Wanneer telt een druk als snel
        check(FijnVolume.reeks(0, 100, true) == 1 && FijnVolume.reeks(1, 200, true) == 2, "snel achter elkaar telt op");
        check(FijnVolume.reeks(3, 900, true) == 0 && FijnVolume.reeks(3, 100, false) == 0 && FijnVolume.reeks(3, -1, true) == 0, "te laat, andere richting of eerste druk: opnieuw");

        if (fails > 0) { System.out.println(fails + " fout(en)"); System.exit(1); }
        System.out.println("alles goed");
    }
}
