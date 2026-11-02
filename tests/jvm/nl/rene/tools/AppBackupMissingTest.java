package nl.rene.tools;

/** De samenvatting bij terugzetten: wat er niet (helemaal) in de backup zit, in gewone woorden. */
public class AppBackupMissingTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    public static void main(String[] args) {
        AppData.Manifest m = new AppData.Manifest();
        m.skipped.add(new String[]{"gebruik-foto/a.jpg", "foto niet te lezen"});
        m.skipped.add(new String[]{"gebruik-foto/b.jpg", "foto niet te lezen"});
        m.skipped.add(new String[]{"spotify", "koppeling niet te lezen: opnieuw koppelen"});
        m.skipped.add(new String[]{"ai-models", "taalmodellen: worden opnieuw gedownload"});
        AppBackup.Staged s = new AppBackup.Staged();
        AppBackup.missing(s, m);
        check(s.missing.contains("2 foto's bij ontgrendelen (niet te lezen bij het maken)"), "overgeslagen foto's samen geteld: " + s.missing);
        check(s.missing.contains("de Spotify-koppeling (opnieuw koppelen)") && s.missing.size() == 2, "de rest zoals eerst; modellen bewust niet genoemd");

        m = new AppData.Manifest();
        m.skipped.add(new String[]{"gebruik-foto/a.jpg", "foto niet te lezen"});
        s = new AppBackup.Staged();
        AppBackup.missing(s, m);
        check(s.missing.size() == 1 && s.missing.get(0).startsWith("1 foto bij"), "één foto: enkelvoud");

        s = new AppBackup.Staged();
        AppBackup.missing(s, new AppData.Manifest());
        check(s.missing.isEmpty(), "niets overgeslagen: niets te melden");

        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
    }
}
