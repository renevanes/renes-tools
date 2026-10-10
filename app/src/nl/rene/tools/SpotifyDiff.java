package nl.rene.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Het rekenwerk van de Spotify-backup, puur Java (getest met SpotifyDiffTest): wat is er in een playlist bij
 * gekomen of weg, een leesbaar overzicht, en het CSV-bestand voor de backup-map.
 */
final class SpotifyDiff {
    private SpotifyDiff() { }

    /** Eén nummer of aflevering in een playlist. */
    static final class T {
        String uri = "", name = "", artists = "", album = "", added = "", isrc = "";
        long dur;
        boolean local;
        T() { }
        T(String uri, String name, String artists) { this.uri = uri; this.name = name; this.artists = artists; }
        /** Sleutel om te vergelijken (lokale bestanden hebben geen vaste uri: dan op naam). */
        String key() { return local || uri.isEmpty() ? "local:" + name.toLowerCase(Locale.ROOT) + "|" + artists.toLowerCase(Locale.ROOT) : uri; }
        String label() { return artists.isEmpty() ? name : name + " – " + artists; }
    }

    static final class Change {
        final List<T> added = new ArrayList<>(), removed = new ArrayList<>();
        boolean empty() { return added.isEmpty() && removed.isEmpty(); }
    }

    /** Wat er bij kwam en wat er weg is (een nummer dat er twee keer in stond en nu één keer telt als één weg). */
    static Change diff(List<T> before, List<T> now) {
        Change c = new Change();
        Map<String, Integer> left = new HashMap<>();
        for (T t : before) left.merge(t.key(), 1, Integer::sum);
        for (T t : now) {
            Integer n = left.get(t.key());
            if (n == null || n == 0) c.added.add(t); else left.put(t.key(), n - 1);
        }
        Map<String, Integer> rest = new HashMap<>(left);
        for (T t : before) {
            Integer n = rest.get(t.key());
            if (n != null && n > 0) { c.removed.add(t); rest.put(t.key(), n - 1); }
        }
        return c;
    }

    /** "+3 (A – X, B – Y, …), −1 (C – Z)" */
    static String summary(Change c, int maxNames) {
        StringBuilder b = new StringBuilder();
        if (!c.added.isEmpty()) b.append('+').append(c.added.size()).append(" (").append(names(c.added, maxNames)).append(')');
        if (!c.removed.isEmpty()) { if (b.length() > 0) b.append(", "); b.append('−').append(c.removed.size()).append(" (").append(names(c.removed, maxNames)).append(')'); }
        return b.toString();
    }

    private static String names(List<T> l, int max) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < l.size() && i < max; i++) { if (i > 0) b.append(", "); b.append(l.get(i).label()); }
        if (l.size() > max) b.append(", …");
        return b.toString();
    }

    /** Duur als 3:45 of 1:02:03 */
    static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /** CSV voor Excel/Numbers (puntkomma, UTF-8 met BOM): Nr;Titel;Artiest(en);Album;Duur;Toegevoegd;Spotify-link;ISRC */
    static String csv(List<T> l) {
        StringBuilder b = new StringBuilder("﻿Nr;Titel;Artiest(en);Album;Duur;Toegevoegd;Spotify-link;ISRC\r\n");
        int i = 0;
        for (T t : l) {
            i++;
            b.append(i).append(';').append(cell(t.name)).append(';').append(cell(t.artists)).append(';').append(cell(t.album)).append(';')
                    .append(t.dur > 0 ? clock(t.dur) : "").append(';').append(cell(t.added.length() >= 10 ? t.added.substring(0, 10) : t.added)).append(';')
                    .append(cell(link(t.uri))).append(';').append(cell(t.isrc)).append("\r\n");
        }
        return b.toString();
    }

    /** spotify:track:abc → https://open.spotify.com/track/abc (lokale bestanden: leeg) */
    static String link(String uri) {
        if (uri == null || !uri.startsWith("spotify:") || uri.startsWith("spotify:local:")) return "";
        String[] p = uri.split(":");
        return p.length == 3 ? "https://open.spotify.com/" + p[1] + "/" + p[2] : "";
    }

    static String cell(String s) {
        if (s == null) return "";
        // Formules niet laten uitvoeren in een rekenblad (=, +, -, @ vooraan)
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return s.matches(".*[;\"\\r\\n].*") ? "\"" + s.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\"" : s;
    }

    /** Kan dit terug in een Spotify-playlist? (Lokale bestanden niet.) */
    static boolean restorable(T t) { return !t.local && (t.uri.startsWith("spotify:track:") || t.uri.startsWith("spotify:episode:")); }
}
