package nl.rene.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SpotifyPodcastsTest {
    static SpotifyPodcasts.Show show(String id) { return new SpotifyPodcasts.Show(id, "Podcast " + id, "Maker"); }
    static void check(boolean ok, String text) { if (!ok) throw new AssertionError(text); }
    interface Attempt { void run() throws Exception; }
    static void rejects(Attempt a, String text) throws Exception { boolean failed = false; try { a.run(); } catch (Exception e) { failed = true; } check(failed, text); }
    public static void main(String[] args) throws Exception {
        String a = "AAAAAAAAAAAAAAAAAAAAAA", b = "BBBBBBBBBBBBBBBBBBBBBB";
        List<String> paths = new ArrayList<>();
        List<SpotifyPodcasts.Show> all = SpotifyPodcasts.collect(path -> {
            paths.add(path);
            return paths.size() == 1 ? new SpotifyPodcasts.Page(Arrays.asList(show(a), null, show(a), show("ongeldig")), "https://api.spotify.com/v1/me/shows?offset=50&limit=50")
                    : new SpotifyPodcasts.Page(Arrays.asList(show(a), show(b), new SpotifyPodcasts.Show("CCCCCCCCCCCCCCCCCCCCCC", " ", "")), null);
        });
        check(paths.size() == 2 && paths.get(0).equals(SpotifyPodcasts.FIRST), "Alle pagina's lezen");
        check(all.size() == 2 && all.get(0).id.equals(a) && all.get(1).id.equals(b), "Dubbele, lege en onbeschikbare shows overslaan");
        check(SpotifyPodcasts.collect(path -> new SpotifyPodcasts.Page(Collections.emptyList(), "")).isEmpty(), "Lege lijst is geldig");
        rejects(() -> SpotifyPodcasts.collect(path -> new SpotifyPodcasts.Page(Arrays.asList(show(a)), path)), "Cyclische paginering weigeren");
        rejects(() -> SpotifyPodcasts.collect(path -> null), "Ontbrekende pagina weigeren");
        rejects(() -> SpotifyPodcasts.collect(path -> new SpotifyPodcasts.Page(null, "")), "Ontbrekende items weigeren");
        rejects(() -> SpotifyPodcasts.collect(path -> { throw new java.io.IOException("Offline"); }), "Netwerkfout doorgeven");
        for (String url : Arrays.asList("https://ander.example/v1/me/shows", "https://api.spotify.com.evil.example/v1/me/shows", "http://api.spotify.com/v1/me/shows", "//api.spotify.com/v1/me/shows", "https://user@api.spotify.com/v1/me/shows", "https://api.spotify.com:443/v1/me/shows", "https://api.spotify.com/v1/me/shows#x", "/me/tracks")) {
            check(!SpotifyPodcasts.pageUrl(url), "Onverwacht vervolgadres weigeren: " + url);
            final int[] calls = {0};
            rejects(() -> SpotifyPodcasts.collect(path -> { calls[0]++; return new SpotifyPodcasts.Page(Collections.emptyList(), url); }), "Vervolgadres weigeren");
            check(calls[0] == 1, "Geen aanroep naar onverwacht adres");
        }
        List<SpotifyPodcasts.Show> large = new ArrayList<>();
        for (int i = 0; i <= SpotifyPodcasts.MAX_SHOWS; i++) large.add(show(String.format(java.util.Locale.ROOT, "%022d", i)));
        rejects(() -> SpotifyPodcasts.collect(path -> new SpotifyPodcasts.Page(large, "")), "Te grote lijst geeft geen gedeeltelijk resultaat");
        check(SpotifyPodcasts.collect(path -> new SpotifyPodcasts.Page(large.subList(0, SpotifyPodcasts.MAX_SHOWS), "")).size() == SpotifyPodcasts.MAX_SHOWS, "Grens volledig ondersteunen");
        System.out.println("Spotify-podcastlijst: paginering, grenzen, deduplicatie en adrescontrole geslaagd");
    }
}
