package nl.rene.tools;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Alleen lezen uit Spotify; het volgen van openbare feeds gebeurt apart in Podcasts. */
final class SpotifyPodcasts {
    private SpotifyPodcasts() { }
    static final int MAX_SHOWS = 2000;
    static final String FIRST = "/me/shows?limit=50";

    static final class Show {
        final String id, title, author;
        Show(String id, String title, String author) { this.id = id; this.title = title; this.author = author; }
    }
    static final class Page {
        final List<Show> shows;
        final String next;
        Page(List<Show> shows, String next) { this.shows = shows; this.next = next; }
    }
    interface Source { Page read(String path) throws Exception; }

    /** Ook bij onvolledige paginering nooit een gedeeltelijke lijst als volledig aanbieden. */
    static List<Show> collect(Source source) throws Exception {
        List<Show> out = new ArrayList<>();
        Set<String> pages = new HashSet<>(), ids = new HashSet<>();
        String next = FIRST;
        while (next != null && !next.isEmpty()) {
            if (!pageUrl(next) || !pages.add(next) || pages.size() > 100)
                throw new Exception("Spotify gaf geen volledige podcastlijst; probeer opnieuw");
            Page p = source.read(next);
            if (p == null || p.shows == null) throw new Exception("Spotify gaf geen geldige podcastlijst");
            for (Show s : p.shows) {
                if (s == null || s.id == null || !s.id.matches("[A-Za-z0-9]{22}") || s.title == null || s.title.trim().isEmpty() || !ids.add(s.id)) continue;
                if (out.size() >= MAX_SHOWS) throw new Exception("Deze lijst bevat meer dan 2000 podcasts en kan niet in één keer worden overgenomen");
                out.add(s);
            }
            next = p.next;
        }
        return out;
    }

    static boolean pageUrl(String path) {
        try {
            URI u = new URI(path);
            if (u.getFragment() != null || u.getUserInfo() != null || u.getPort() != -1) return false;
            return u.isAbsolute() ? "https".equals(u.getScheme()) && "api.spotify.com".equals(u.getHost()) && "/v1/me/shows".equals(u.getPath())
                    : u.getRawAuthority() == null && "/me/shows".equals(u.getPath());
        } catch (Exception e) { return false; }
    }

    private static final class Live implements Source {
        final Context context;
        Live(Context c) { context = c; }
        public Page read(String path) throws Exception {
            JSONObject p = Spotify.api(context, "GET", path, null);
            JSONArray items = p.optJSONArray("items");
            if (items == null) throw new Exception("Spotify gaf geen geldige podcastlijst");
            List<Show> shows = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i), show = item == null ? null : item.optJSONObject("show");
                if (show != null) shows.add(new Show(show.optString("id"), show.optString("name"), show.optString("publisher")));
            }
            return new Page(shows, p.isNull("next") ? "" : p.optString("next"));
        }
    }

    static JSONObject list(Context c, boolean backup) throws Exception {
        List<Show> shows;
        long at = System.currentTimeMillis();
        if (backup) {
            JSONObject saved = SpotifyLib.read(SpotifyLib.file(c, "shows"));
            if (saved == null || saved.optJSONArray("items") == null)
                throw new Exception("Nog geen podcastlijst in je Spotify-backup. Haal de huidige lijst op of maak eerst een Spotify-backup.");
            JSONArray items = saved.getJSONArray("items");
            List<Show> rows = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String uri = item.optString("u");
                if (uri.startsWith("spotify:show:")) rows.add(new Show(uri.substring(13), item.optString("n"), item.optString("a")));
            }
            shows = collect(path -> new Page(rows, ""));
            at = saved.optLong("t");
        } else {
            if (!Spotify.linked(c)) throw new Spotify.Scope("Koppel Spotify om je gevolgde podcasts op te halen");
            if (!(" " + Spotify.prefs(c).getString("scopes", "") + " ").contains(" user-library-read "))
                throw new Spotify.Scope("Koppel Spotify opnieuw en geef toegang tot je bibliotheek");
            shows = collect(new Live(c));
        }
        JSONArray result = new JSONArray();
        for (Show s : shows) result.put(new JSONObject().put("id", s.id).put("title", PodcastFeed.cut(s.title, 300))
                .put("author", PodcastFeed.cut(s.author == null ? "" : s.author, 200)).put("url", "https://open.spotify.com/show/" + s.id));
        return new JSONObject().put("shows", result).put("source", backup ? "backup" : "live").put("at", at);
    }
}
