package nl.rene.tools;

import java.util.Arrays;
import java.util.List;

/** Spotify-backup: verschillen tussen versies, CSV en wat terug kan. */
public class SpotifyDiffTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    static SpotifyDiff.T t(String id, String name) { return new SpotifyDiff.T("spotify:track:" + id, name, "Artiest " + id); }

    public static void main(String[] a) {
        List<SpotifyDiff.T> before = Arrays.asList(t("a", "Een"), t("b", "Twee"), t("c", "Drie"), t("b", "Twee"));
        List<SpotifyDiff.T> now = Arrays.asList(t("a", "Een"), t("d", "Vier"), t("b", "Twee"), t("e", "Vijf"));
        SpotifyDiff.Change c = SpotifyDiff.diff(before, now);
        check(c.added.size() == 2 && c.added.get(0).name.equals("Vier") && c.added.get(1).name.equals("Vijf"), "toegevoegd: Vier en Vijf");
        check(c.removed.size() == 2 && c.removed.get(0).name.equals("Twee") && c.removed.get(1).name.equals("Drie"), "weg: één keer Twee (stond er dubbel) en Drie");
        check(SpotifyDiff.diff(now, now).empty(), "geen verschil met zichzelf");
        check(SpotifyDiff.diff(Arrays.asList(t("a", "x"), t("b", "y")), Arrays.asList(t("b", "y"), t("a", "x"))).empty(), "alleen volgorde anders: geen verschil");
        String s = SpotifyDiff.summary(c, 1);
        check(s.equals("+2 (Vier – Artiest d, …), −2 (Twee – Artiest b, …)"), "samenvatting: " + s);
        // Lokale bestanden: op naam vergelijken, niet terug te zetten
        SpotifyDiff.T loc = new SpotifyDiff.T("spotify:local:Artiest:Album:Liedje:200", "Liedje", "Artiest"); loc.local = true;
        SpotifyDiff.T loc2 = new SpotifyDiff.T("spotify:local:Artiest:Album:Liedje:201", "Liedje", "Artiest"); loc2.local = true;
        check(SpotifyDiff.diff(Arrays.asList(loc), Arrays.asList(loc2)).empty(), "lokaal bestand: zelfde naam = zelfde");
        check(!SpotifyDiff.restorable(loc) && SpotifyDiff.restorable(t("a", "x")) && SpotifyDiff.restorable(new SpotifyDiff.T("spotify:episode:z", "Ep", "")), "terug te zetten: nummers en afleveringen, geen lokale bestanden");
        // CSV
        SpotifyDiff.T x = t("q", "Hallo; \"wereld\""); x.album = "=SOM(1)"; x.dur = 225_000; x.added = "2026-10-10T08:00:00Z"; x.isrc = "NLA123";
        String csv = SpotifyDiff.csv(Arrays.asList(x));
        String[] lines = csv.split("\r\n");
        check(lines[0].startsWith("﻿Nr;Titel;"), "kopregel met BOM");
        check(lines[1].equals("1;\"Hallo; \"\"wereld\"\"\";Artiest q;'=SOM(1);3:45;2026-10-10;https://open.spotify.com/track/q;NLA123"), "regel: " + lines[1]);
        check(SpotifyDiff.link("spotify:local:a:b:c:1").isEmpty() && SpotifyDiff.link("spotify:episode:e1").equals("https://open.spotify.com/episode/e1"), "links");
        check(SpotifyDiff.clock(3_723_000).equals("1:02:03"), "duur boven het uur");
        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
        System.out.println("Alle Spotify-tests geslaagd");
    }
}
