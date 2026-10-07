package nl.rene.tools;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Radio: zenders komen uit de vrije database van radio-browser.info (actuele stream-adressen,
 * zodat gewijzigde URL's van omroepen vanzelf goed komen). Favorieten worden met hun
 * stream-adres lokaal bewaard. Daarnaast: "nu op de radio" via ICY-metadata en een kort
 * stukje van de stream ophalen om het nummer te laten herkennen.
 */
final class Radio {

    private Radio() { }

    static final String UA = "RenesTools/1.0 (Android; github.com/renevanes/renes-tools)";
    static final String[] SERVERS = {"de1.api.radio-browser.info", "de2.api.radio-browser.info",
            "fi1.api.radio-browser.info", "all.api.radio-browser.info"};

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("radio", Context.MODE_PRIVATE); }

    static String get(String url, int timeout) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(timeout);
        h.setReadTimeout(timeout);
        h.setRequestProperty("User-Agent", UA);
        try {
            if (h.getResponseCode() != 200) throw new Exception("HTTP " + h.getResponseCode());
            try (InputStream in = h.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) { b.write(buf, 0, n); if (b.size() > 4_000_000) break; }
                return new String(b.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally { h.disconnect(); }
    }

    /** Vraagt de database; probeert de servers op volgorde (de laatst werkende eerst). */
    static String api(Context c, String path) throws Exception {
        String last = prefs(c).getString("server", SERVERS[0]);
        Exception err = null;
        java.util.LinkedHashSet<String> order = new java.util.LinkedHashSet<>();
        order.add(last);
        java.util.Collections.addAll(order, SERVERS);
        for (String s : order) {
            try {
                String r = get("https://" + s + path, 8000);
                prefs(c).edit().putString("server", s).apply();
                return r;
            } catch (Exception e) { err = e; }
        }
        throw new Exception("Zenderlijst niet bereikbaar" + (err != null && err.getMessage() != null ? " (" + err.getMessage() + ")" : ""));
    }

    /** Alleen de velden die de app nodig heeft. */
    static JSONObject slim(JSONObject s) throws Exception {
        String url = s.optString("url_resolved");
        if (url.isEmpty()) url = s.optString("url");
        return new JSONObject().put("id", s.optString("stationuuid")).put("name", s.optString("name").trim())
                .put("url", url).put("logo", s.optString("favicon")).put("tags", s.optString("tags"))
                .put("codec", s.optString("codec")).put("bitrate", s.optInt("bitrate")).put("hls", s.optInt("hls") == 1)
                .put("home", s.optString("homepage"));
    }

    /** Nederlandse zenders, populairste eerst; met zoekterm op naam. */
    static String stations(Context c, String q) throws Exception {
        String path = "/json/stations/search?countrycode=NL&hidebroken=true&order=clickcount&reverse=true&limit="
                + (q == null || q.trim().isEmpty() ? "150" : "80")
                + (q == null || q.trim().isEmpty() ? "" : "&name=" + URLEncoder.encode(q.trim(), "UTF-8"));
        JSONArray a = new JSONArray(api(c, path));
        JSONArray out = new JSONArray();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject s = slim(a.getJSONObject(i));
            // Dubbele vermeldingen (zelfde naam) overslaan; de populairste blijft.
            String key = s.optString("name").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (key.isEmpty()) key = s.optString("id");
            if (s.optString("url").isEmpty() || !seen.add(key)) continue;
            out.put(s);
        }
        if (q == null || q.trim().isEmpty()) prefs(c).edit().putString("cache", out.toString()).putLong("cacheAt", System.currentTimeMillis()).apply();
        return out.toString();
    }

    /** Telt een klik (de database gebruikt dit voor populariteit) en geeft het actuele stream-adres. */
    static void click(Context c, String id) {
        if (id == null || !id.matches("[0-9a-fA-F-]{20,}")) return;
        try { api(c, "/json/url/" + id); } catch (Exception ignored) { }
    }

    // ---------- favorieten ----------

    static boolean isFavoriteUrl(Context c, String url) {
        if (url == null || url.isEmpty()) return false;
        JSONArray f = favorites(c);
        for (int i = 0; i < f.length(); i++) { JSONObject s = f.optJSONObject(i); if (s != null && url.equals(s.optString("url"))) return true; }
        return false;
    }

    static JSONArray favorites(Context c) {
        try { return new JSONArray(prefs(c).getString("favs", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    static synchronized boolean toggleFavorite(Context c, String stationJson) throws Exception {
        JSONObject s = new JSONObject(stationJson);
        JSONArray f = favorites(c), out = new JSONArray();
        boolean had = false;
        for (int i = 0; i < f.length(); i++) {
            JSONObject x = f.getJSONObject(i);
            boolean same = s.optString("id").isEmpty() ? x.optString("url").equals(s.optString("url"))
                    : x.optString("id").equals(s.optString("id"));
            if (same) had = true;
            else out.put(x);
        }
        if (!had) out.put(slimFav(s));
        prefs(c).edit().putString("favs", out.toString()).apply();
        return !had;
    }

    static JSONObject slimFav(JSONObject s) throws Exception {
        return new JSONObject().put("id", s.optString("id")).put("name", s.optString("name")).put("url", s.optString("url"))
                .put("logo", s.optString("logo")).put("tags", s.optString("tags")).put("codec", s.optString("codec"))
                .put("bitrate", s.optInt("bitrate")).put("hls", s.optBoolean("hls"));
    }

    static synchronized void moveFavorite(Context c, String id, int dir) throws Exception {
        JSONArray f = favorites(c);
        int i = -1;
        for (int k = 0; k < f.length(); k++) if (f.getJSONObject(k).optString("id").equals(id)) i = k;
        int j = i + dir;
        if (i < 0 || j < 0 || j >= f.length()) return;
        JSONObject a = f.getJSONObject(i), b = f.getJSONObject(j);
        f.put(i, b);
        f.put(j, a);
        prefs(c).edit().putString("favs", f.toString()).apply();
    }

    // ---------- nu op de radio ----------

    static String header(HttpURLConnection h, String name) {
        String v = h.getHeaderField(name);
        if (v == null) return "";
        // Headers komen binnen als Latin-1; vaak zijn het eigenlijk UTF-8-bytes.
        try {
            byte[] raw = v.getBytes(StandardCharsets.ISO_8859_1);
            String u = new String(raw, StandardCharsets.UTF_8);
            if (u.indexOf('\uFFFD') < 0) v = u;
        } catch (Exception ignored) { }
        return v.trim();
    }

    static String metaField(String m, String key) {
        int a = m.indexOf(key + "='");
        if (a < 0) return "";
        a += key.length() + 2;
        int b = m.indexOf("';", a);
        return (b >= a ? m.substring(a, b) : m.substring(a)).trim();
    }

    /**
     * Wat de zender meestuurt: uit de ICY-headers de zendernaam, omschrijving, genre, website en
     * bitrate, en uit de metadata in de stream de huidige titel (vaak "Artiest - Titel" of de
     * programmanaam) en soms een extra link. Lege velden als de zender iets niet meestuurt.
     */
    static JSONObject streamInfo(String url) {
        JSONObject o = new JSONObject();
        if (url == null || url.contains(".m3u8")) return o;
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL(url).openConnection();
            h.setConnectTimeout(6000);
            h.setReadTimeout(8000);
            h.setRequestProperty("Icy-MetaData", "1");
            h.setRequestProperty("User-Agent", UA);
            o.put("name", header(h, "icy-name")).put("desc", header(h, "icy-description"))
                    .put("genre", header(h, "icy-genre")).put("site", header(h, "icy-url")).put("br", header(h, "icy-br"));
            int metaint = h.getHeaderFieldInt("icy-metaint", 0);
            if (metaint <= 0 || metaint > 256_000) return o;
            InputStream in = h.getInputStream();
            long skip = metaint;
            byte[] buf = new byte[8192];
            while (skip > 0) { int n = in.read(buf, 0, (int) Math.min(buf.length, skip)); if (n < 0) return o; skip -= n; }
            int len = in.read() * 16;
            if (len <= 0) return o;
            byte[] meta = new byte[len];
            int got = 0;
            while (got < len) { int n = in.read(meta, got, len - got); if (n < 0) break; got += n; }
            String m = new String(meta, 0, got, StandardCharsets.UTF_8);
            if (m.indexOf('\uFFFD') >= 0) m = new String(meta, 0, got, StandardCharsets.ISO_8859_1); // oudere servers sturen Latin-1
            String t = metaField(m, "StreamTitle").replaceAll("^[\\s-]+|[\\s-]+$", "");
            o.put("title", t).put("streamUrl", metaField(m, "StreamUrl"));
            // "Artiest - Titel" splitsen als het daarop lijkt
            int d = t.indexOf(" - ");
            if (d > 0 && t.indexOf(" - ", d + 3) < 0) o.put("artist", t.substring(0, d).trim()).put("song", t.substring(d + 3).trim());
        } catch (Exception ignored) {
        } finally { if (h != null) h.disconnect(); }
        return o;
    }

    /** Haalt ongeveer 'seconds' seconden van de stream op (zonder metadata) om te laten herkennen. */
    static File snippet(Context c, String url, int bitrateKbps, int seconds, int id) throws Exception {
        if (url == null || url.contains(".m3u8")) throw new Exception("Deze zender gebruikt HLS; gebruik herkennen via de microfoon");
        int kbps = bitrateKbps > 0 ? bitrateKbps : 128;
        long want = Math.min(600_000L, (long) kbps * 125 * seconds);
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(8000);
        h.setReadTimeout(10000);
        h.setRequestProperty("User-Agent", UA);
        String type = h.getContentType() == null ? "" : h.getContentType().toLowerCase(Locale.ROOT);
        String ext = type.contains("aac") || type.contains("mp4") ? ".aac" : type.contains("ogg") ? ".ogg" : ".mp3";
        File f = new File(c.getCacheDir(), "radio-snippet-" + id + ext);
        long got = 0, t0 = System.currentTimeMillis();
        try (InputStream in = h.getInputStream(); FileOutputStream o = new FileOutputStream(f)) {
            byte[] buf = new byte[16384];
            int n;
            while (got < want && (n = in.read(buf)) > 0) {
                o.write(buf, 0, n);
                got += n;
                if (System.currentTimeMillis() - t0 > 25_000) break;
            }
        } finally { h.disconnect(); }
        if (got < 20_000) throw new Exception("Te weinig geluid van de zender ontvangen");
        return f;
    }
}
