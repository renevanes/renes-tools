package nl.rene.tools;

/** De Play-versie herkent zich aan de pakketnaam; alleen die krijgt geen updates via GitHub (zie docs/play-store.md). */
public class PlayVersieTest {
    static boolean all = true;
    static void ok(boolean c, String m) { System.out.println((c ? "✓ " : "✗ ") + m); if (!c) all = false; }

    public static void main(String[] a) {
        ok(!Updater.isPlayPackage("nl.rene.tools"), "gewone app: updates via GitHub");
        ok(Updater.isPlayPackage("nl.rene.tools.play"), "Play-versie herkend");
        ok(!Updater.isPlayPackage("nl.rene.tools.player"), "geen vergissing met een naam die met play begint");
        ok(!Updater.isPlayPackage(null), "zonder pakketnaam: gewone app");
        if (!all) System.exit(1);
    }
}
