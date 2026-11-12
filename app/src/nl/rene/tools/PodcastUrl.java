package nl.rene.tools;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Het echte adres van een aflevering, vooraf opgezocht. Veel podcasts lopen via één of meer tellers (doorverwijzingen)
 * voordat het bestand komt. Android doet die hele keten bij elke sprong of herverbinding opnieuw; dat kost seconden en
 * geeft het "hangen" na terugspoelen. Daarom één keer opzoeken en daarna rechtstreeks. Bij een fout gaat het opnieuw
 * via het gewone adres (forget).
 */
final class PodcastUrl {
    private PodcastUrl() { }

    static final long KEEP_MS = 20 * 60_000L;
    private static final int TIMEOUT = 5000, HOPS = 8;
    private static final ConcurrentHashMap<String, String[]> CACHE = new ConcurrentHashMap<>();

    /** Het adres om af te spelen: opgezocht (bewaard) of, als dat niet lukt, het gewone. Niet op de hoofdthread aanroepen. */
    static String resolve(String url) {
        if (!Podcasts.httpUrl(url)) return url;
        String[] c = CACHE.get(url);
        long now = System.currentTimeMillis();
        if (c != null && now - Long.parseLong(c[1]) < KEEP_MS) return c[0];
        String fin = follow(url);
        if (fin == null) return url;
        if (CACHE.size() > 50) CACHE.clear();
        CACHE.put(url, new String[]{fin, String.valueOf(now)});
        return fin;
    }

    /** Alleen wat al opgezocht is (voor de hoofdthread), anders het gewone adres. */
    static String cached(String url) {
        String[] c = url == null ? null : CACHE.get(url);
        return c != null && System.currentTimeMillis() - Long.parseLong(c[1]) < KEEP_MS ? c[0] : url;
    }

    static void forget(String url) { if (url != null) CACHE.remove(url); }

    /** Volgt de doorverwijzingen (ook http ↔ https) met één klein verzoek per stap; null = lukt niet. */
    private static String follow(String url) {
        String u = url;
        try {
            for (int hop = 0; hop < HOPS; hop++) {
                if (!Podcasts.httpUrl(u)) return null;
                HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
                try {
                    h.setInstanceFollowRedirects(false);
                    h.setConnectTimeout(TIMEOUT);
                    h.setReadTimeout(TIMEOUT);
                    h.setRequestProperty("User-Agent", Radio.UA);
                    h.setRequestProperty("Range", "bytes=0-0"); // geen heel bestand ophalen
                    int code = h.getResponseCode();
                    if (code >= 300 && code < 400) {
                        String loc = h.getHeaderField("Location");
                        if (loc == null) return null;
                        u = new URL(new URL(u), loc).toString();
                        continue;
                    }
                    return code == 200 || code == 206 ? u : null;
                } finally { h.disconnect(); }
            }
        } catch (Throwable ignored) { }
        return null;
    }
}
