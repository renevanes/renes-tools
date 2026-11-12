package nl.rene.tools;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Het echte adres van een aflevering, vooraf opgezocht. Veel podcasts lopen via één of meer tellers (doorverwijzingen)
 * voordat het bestand komt. Android doet die hele keten bij elke sprong of herverbinding opnieuw; dat kost seconden en
 * geeft het "hangen" na terugspoelen. Daarom één keer opzoeken en daarna rechtstreeks.
 *
 * Grenzen: alles samen hooguit WAIT_MS (anders het gewone adres, zodat starten nooit lang wacht); lukt het niet, dan
 * wordt ook dat even onthouden (niet bij elke herverbinding opnieuw wachten). Het verzoek is een gewone GET zonder
 * Range-kop (de tellers van makers zien een gewone luisterbeurt); na de kopregels wordt de verbinding verbroken.
 * Ondertekende adressen (met een vervaltijd) worden maar kort bewaard. Bij een fout gaat het opnieuw via het gewone
 * adres (forget).
 */
final class PodcastUrl {
    private PodcastUrl() { }

    static final long KEEP_MS = 20 * 60_000L, KEEP_SIGNED_MS = 2 * 60_000L, WAIT_MS = 4000;
    private static final int HOPS = 8;
    private static final ConcurrentHashMap<String, String[]> CACHE = new ConcurrentHashMap<>();

    static boolean web(String u) { return u != null && (u.startsWith("https://") || u.startsWith("http://")); }

    /** Lijkt het eindadres een ondertekend adres met een vervaltijd (CDN-tokens)? Dan maar kort bewaren. */
    static boolean signed(String u) {
        String q = u == null ? "" : u.toLowerCase(java.util.Locale.ROOT);
        int at = q.indexOf('?');
        if (at < 0) return false;
        q = q.substring(at);
        return q.contains("expires=") || q.contains("x-amz-") || q.contains("signature=") || q.contains("token=") || q.contains("policy=") || q.contains("hdnts=");
    }

    /** Het adres om af te spelen: opgezocht (bewaard) of, als dat niet lukt, het gewone. Niet op de hoofdthread aanroepen. */
    static String resolve(String url) {
        if (!web(url)) return url;
        String c = cached(url);
        if (c != null) return c;
        long now = System.currentTimeMillis();
        String fin = follow(url, Radio.UA, now + WAIT_MS);
        String use = fin == null ? url : fin; // niet gelukt: het gewone adres, en dat ook onthouden
        if (CACHE.size() > 50) CACHE.clear();
        CACHE.put(url, new String[]{use, String.valueOf(now + (signed(use) ? KEEP_SIGNED_MS : KEEP_MS))});
        return use;
    }

    /** Wat al bekend is (ook "gewoon het gewone adres"), of null als het nog opgezocht moet worden. */
    static String cached(String url) {
        String[] c = url == null ? null : CACHE.get(url);
        return c != null && System.currentTimeMillis() < Long.parseLong(c[1]) ? c[0] : null;
    }

    static void forget(String url) { if (url != null) CACHE.remove(url); }

    /**
     * Volgt de doorverwijzingen (ook http ↔ https) tot het bestand, hooguit HOPS stappen en tot deadline (ms sinds
     * 1970). null = lukt niet (fout, te veel stappen, te laat, geen http(s)).
     */
    static String follow(String url, String ua, long deadline) {
        String u = url;
        try {
            for (int hop = 0; hop < HOPS; hop++) {
                if (!web(u)) return null;
                int left = (int) Math.min(Integer.MAX_VALUE, deadline - System.currentTimeMillis());
                if (left < 200) return null;
                HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
                try {
                    h.setInstanceFollowRedirects(false);
                    h.setConnectTimeout(left);
                    h.setReadTimeout(left);
                    if (ua != null) h.setRequestProperty("User-Agent", ua);
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
