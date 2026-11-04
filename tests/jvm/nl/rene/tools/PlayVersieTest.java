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
        ok(PlayPolicy.allows("nl.rene.tools", "recorder"), "gewone functies blijven beschikbaar");
        ok(PlayPolicy.allows("nl.rene.tools.play", "notes"), "Play-notities beschikbaar");
        for (String feature : new String[]{"recorder", "unlockPhoto", "ai", "vpn", "auto", "onbekend"})
            ok(!PlayPolicy.allows("nl.rene.tools.play", feature), "Play weigert " + feature);
        ok(!PlayPolicy.keepPreference("nl.rene.tools.play", "gebruik", "photoUnlock"), "oude foto-instelling geblokkeerd");
        ok(!PlayPolicy.keepPreference("nl.rene.tools.play", "call_recordings", "auto"), "oude opname-instelling geblokkeerd");
        ok(PlayPolicy.keepPreference("nl.rene.tools", "gebruik", "photoUnlock"), "gewone backup behouden");
        try {
            ok("[]".equals(PlayPolicy.sanitizeRules("nl.rene.tools.play", "beschadigd")), "ongeldige uitgesloten regels blokkeren herstel niet");
            ok("beschadigd".equals(PlayPolicy.sanitizeRules("nl.rene.tools", "beschadigd")), "gewone regels ongewijzigd");
        } catch (Exception e) { ok(false, "opschonen faalde"); }
        if (!all) System.exit(1);
    }
}
