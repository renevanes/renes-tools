package nl.rene.tools;

import java.io.File;

/** Bestandsnamen uit opgeslagen lijsten en de brug mogen niet buiten hun eigen map wijzen. */
public final class SafePathTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    public static void main(String[] args) {
        File d = new File("/tmp/podcasts");
        check(PodcastDownloads.fileIn(d, "e0123456789abcdef.mp3") != null, "gewone downloadnaam");
        check(PodcastDownloads.fileIn(d, "e0123456789abcdef-1.mp3") != null, "door Android hernoemde naam");
        check(PodcastDownloads.fileIn(d, "../../shared_prefs/secure.xml") == null, "pad omhoog geweigerd");
        check(PodcastDownloads.fileIn(d, "/data/data/nl.rene.tools/files/x") == null, "absoluut pad geweigerd");
        check(PodcastDownloads.fileIn(d, "..") == null, "bovenliggende map geweigerd");
        check(PodcastDownloads.fileIn(d, "") == null, "lege naam geweigerd");
        check(PodcastDownloads.fileIn(d, null) == null, "geen naam geweigerd");
        check(PodcastDownloads.fileIn(null, "a.mp3") == null, "geen map");

        check(SpotifyLib.known("tracks") && SpotifyLib.known("recent") && SpotifyLib.known("top-artists-short"), "bekende soorten");
        check(!SpotifyLib.known("../spotify") && !SpotifyLib.known("tracks-weg") && !SpotifyLib.known(""), "onbekende soort");

        check(RecordingFiles.validName("opname-1234567890123-0123456789abcdef0123456789abcdef.m4a"), "geldige opnamenaam");
        check(!RecordingFiles.validName("../opname-1234567890123-0123456789abcdef0123456789abcdef.m4a"), "opnamenaam met pad");
        System.out.println("Alle " + checks + " padtests geslaagd");
    }
}
