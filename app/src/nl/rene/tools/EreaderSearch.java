package nl.rene.tools;

import java.util.ArrayList;
import java.util.List;

/** Letterlijk en zonder reguliere expressies zoeken; posities blijven UTF-16-tekenposities, net als in de WebView. */
final class EreaderSearch {
    private EreaderSearch() { }
    static final class Hit {
        final int start, end; final String excerpt;
        Hit(String text, int start, int length) {
            this.start = start; this.end = start + length;
            this.excerpt = text.substring(Math.max(0, start - 65), Math.min(text.length(), end + 100)).replaceAll("\\s+", " ").trim();
        }
    }
    static List<Hit> find(String text, String query, int max) {
        List<Hit> hits = new ArrayList<>();
        if (query == null || query.length() < 2 || query.length() > 80) return hits;
        for (int i = 0; i <= text.length() - query.length() && hits.size() < max; i++) {
            if (Thread.currentThread().isInterrupted()) break;
            if (text.regionMatches(true, i, query, 0, query.length())) { hits.add(new Hit(text, i, query.length())); i += query.length() - 1; }
        }
        return hits;
    }
}
