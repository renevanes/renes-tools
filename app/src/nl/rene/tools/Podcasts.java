package nl.rene.tools;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Podcasts: zoeken (Apple Podcasts-zoekdienst, zonder account), populair in Nederland, abonnementen,
 * afleveringen uit de feed van de maker, en per aflevering waar je was. Alles blijft op de telefoon.
 */
final class Podcasts {
    private Podcasts() { }

    static final int MAX_EPISODES = 500;
    static final long FEED_MAX = 25L * 1024 * 1024;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("podcast", Context.MODE_PRIVATE); }
    static File dir(Context c) { File d = new File(c.getFilesDir(), "podcasts"); d.mkdirs(); return d; }

    static String hash(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 8; i++) b.append(String.format(java.util.Locale.ROOT, "%02x", h[i]));
            return b.toString();
        } catch (Exception e) { return Integer.toHexString(s.hashCode()); }
    }

    static String podId(String feedUrl) { return "p" + hash(feedUrl.trim()); }
    static String epKey(String url) { return "e" + hash(url.trim()); }
    /** Sleutel van een aflevering: podcast + guid (blijft gelijk als het audio-adres wisselt), anders het adres. */
    static String epKey(String feedUrl, PodcastFeed.Episode e) {
        return e.guid.isEmpty() || e.guid.equals(e.url) ? epKey(e.url) : "e" + hash(feedUrl.trim() + "|" + e.guid.trim());
    }
    static boolean validKey(String k) { return k != null && k.matches("e[0-9a-f]{16}"); }

    /** Eén schrijf-thread voor de podcastbestanden (niet op de hoofdthread, en nooit twee tegelijk). */
    static final java.util.concurrent.ExecutorService IO = java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "podcast-io"));
    /** Eén slot per feed (een bijwerkronde en het openen van dezelfde podcast mogen niet door elkaar schrijven). */
    static final java.util.concurrent.ConcurrentHashMap<String, Object> FEED_LOCKS = new java.util.concurrent.ConcurrentHashMap<>();
    static final java.util.concurrent.atomic.AtomicBoolean REFRESHING = new java.util.concurrent.atomic.AtomicBoolean();

    static boolean httpUrl(String u) { return u != null && (u.startsWith("https://") || u.startsWith("http://")); }

    // ---------- netwerk ----------

    /** GET met de eigen User-Agent; volgt doorverwijzingen (ook http ↔ https, wat Java zelf niet doet). */
    static HttpURLConnection open(String url, int timeout) throws Exception {
        String u = url;
        for (int hop = 0; hop < 6; hop++) {
            if (!httpUrl(u)) throw new Exception("Ongeldig adres");
            HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
            h.setInstanceFollowRedirects(false);
            h.setConnectTimeout(timeout);
            h.setReadTimeout(timeout);
            h.setRequestProperty("User-Agent", Radio.UA);
            h.setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml, application/json, */*");
            int code = h.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = h.getHeaderField("Location");
                h.disconnect();
                if (loc == null) throw new Exception("HTTP " + code);
                u = new URL(new URL(u), loc).toString();
                continue;
            }
            if (code != 200) { h.disconnect(); throw new Exception(code == 404 ? "Niet (meer) gevonden" : "HTTP " + code); }
            return h;
        }
        throw new Exception("Te veel doorverwijzingen");
    }

    static String getText(String url, int timeout, int max) throws Exception {
        HttpURLConnection h = open(url, timeout);
        try (InputStream in = new PodcastFeed.Limited(h.getInputStream(), max)) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        } finally { h.disconnect(); }
    }

    // ---------- zoeken ----------

    static JSONObject slimResult(JSONObject r) throws Exception {
        String art = r.optString("artworkUrl600", r.optString("artworkUrl100", ""));
        return new JSONObject().put("feed", r.optString("feedUrl")).put("title", r.optString("collectionName", r.optString("trackName")))
                .put("author", r.optString("artistName")).put("image", art).put("genre", r.optString("primaryGenreName"))
                .put("count", r.optInt("trackCount")).put("id", podId(r.optString("feedUrl")));
    }

    static JSONArray search(String q) throws Exception {
        String url = "https://itunes.apple.com/search?media=podcast&entity=podcast&limit=40&country=NL&term=" + URLEncoder.encode(q.trim(), "UTF-8");
        JSONArray in = new JSONObject(getText(url, 15000, 3_000_000)).optJSONArray("results"), out = new JSONArray();
        if (in != null) for (int i = 0; i < in.length(); i++) {
            JSONObject r = in.optJSONObject(i);
            if (r != null && httpUrl(r.optString("feedUrl"))) out.put(slimResult(r));
        }
        return out;
    }

    /** Populaire podcasts in Nederland (twaalf uur bewaard). */
    static JSONArray top(Context c) throws Exception {
        SharedPreferences p = prefs(c);
        if (System.currentTimeMillis() - p.getLong("topAt", 0) < 12 * 3600_000L) {
            try { return new JSONArray(p.getString("top", "[]")); } catch (Exception ignored) { }
        }
        JSONArray chart = new JSONObject(getText("https://rss.applemarketingtools.com/api/v2/nl/podcasts/top/40/podcasts.json", 15000, 2_000_000))
                .getJSONObject("feed").getJSONArray("results");
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < chart.length(); i++) { String id = chart.getJSONObject(i).optString("id"); if (id.matches("\\d+")) ids.append(ids.length() > 0 ? "," : "").append(id); }
        JSONArray look = new JSONObject(getText("https://itunes.apple.com/lookup?entity=podcast&country=NL&id=" + ids, 15000, 3_000_000)).optJSONArray("results");
        java.util.Map<String, JSONObject> byId = new java.util.HashMap<>();
        if (look != null) for (int i = 0; i < look.length(); i++) { JSONObject r = look.optJSONObject(i); if (r != null) byId.put(String.valueOf(r.optLong("collectionId")), r); }
        JSONArray out = new JSONArray();
        for (int i = 0; i < chart.length(); i++) {
            JSONObject r = byId.get(chart.getJSONObject(i).optString("id"));
            if (r != null && httpUrl(r.optString("feedUrl"))) out.put(slimResult(r));
        }
        if (out.length() > 0) p.edit().putString("top", out.toString()).putLong("topAt", System.currentTimeMillis()).apply();
        return out;
    }

    // ---------- feeds ----------

    static File cacheFile(Context c, String id) { return new File(dir(c), Restore.safeId(id, "p") + ".json"); }

    static JSONObject readJson(File f) { try { return f.isFile() ? new JSONObject(Contacts.readText(f, false)) : null; } catch (Exception e) { return null; } }

    static JSONObject epJson(String feedUrl, PodcastFeed.Episode e) throws Exception { return epJsonKey(epKey(feedUrl, e), e); }

    static JSONObject epJsonKey(String key, PodcastFeed.Episode e) throws Exception {
        return new JSONObject().put("key", key).put("guid", e.guid).put("title", e.title).put("url", e.url).put("type", e.type)
                .put("date", e.date).put("dur", e.dur).put("image", e.image).put("desc", e.desc).put("n", e.number).put("s", e.season);
    }

    /** Feed ophalen en bewaren. Geeft {pod:{…}, items:[…], t}. */
    static JSONObject fetch(Context c, String feedUrl) throws Exception { // niet synchronized: netwerk mag andere aanroepen niet ophouden
        if (!httpUrl(feedUrl)) throw new Exception("Ongeldig feed-adres");
        String id0 = podId(feedUrl);
        Object lock = FEED_LOCKS.computeIfAbsent(id0, k -> new Object());
        synchronized (lock) { return fetchLocked(c, feedUrl); }
    }

    private static JSONObject fetchLocked(Context c, String feedUrl) throws Exception {
        HttpURLConnection h = open(feedUrl, 20000);
        PodcastFeed.Feed f;
        String ct = h.getContentType(), cs = null;
        if (ct != null) { java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)charset=\\s*\"?([A-Za-z0-9._-]+)").matcher(ct); if (m.find()) cs = m.group(1); }
        // Ruim lezen en daarna de nieuwste houden: sommige feeds staan met de oudste bovenaan
        try (InputStream in = new PodcastFeed.Limited(h.getInputStream(), FEED_MAX)) { f = PodcastFeed.parse(in, MAX_EPISODES * 4, cs); }
        finally { h.disconnect(); }
        String id = podId(feedUrl);
        JSONObject pod = new JSONObject().put("id", id).put("feed", feedUrl).put("title", f.title.isEmpty() ? "Podcast" : f.title).put("author", f.author)
                .put("image", f.image).put("desc", f.desc).put("link", f.link);
        JSONArray items = new JSONArray();
        List<PodcastFeed.Episode> l = new ArrayList<>(f.items);
        Collections.sort(l, (x, y) -> Long.compare(y.date, x.date)); // nieuwste eerst
        // Sommige feeds geven meerdere afleveringen dezelfde guid: die krijgen allemaal het audio-adres als sleutel
        // (anders delen ze hun plek, en schuift de sleutel door naar elke nieuwe aflevering)
        java.util.Map<String, Integer> guidCount = new java.util.HashMap<>();
        for (PodcastFeed.Episode e : l) { Integer k = guidCount.get(e.guid); guidCount.put(e.guid, k == null ? 1 : k + 1); }
        java.util.Set<String> used = new java.util.HashSet<>();
        for (PodcastFeed.Episode e : l) {
            if (!httpUrl(e.url)) continue;
            if (e.image.isEmpty()) e.image = f.image;
            String key = guidCount.get(e.guid) > 1 ? epKey(e.url) : epKey(feedUrl, e);
            if (!used.add(key)) continue; // twee keer hetzelfde bestand: één keer tonen
            items.put(epJsonKey(key, e));
            if (items.length() >= MAX_EPISODES) break;
        }
        JSONObject o = new JSONObject().put("pod", pod).put("items", items).put("t", System.currentTimeMillis());
        Contacts.writeText(cacheFile(c, id), o.toString(), false);
        updateSub(c, pod, items);
        return o;
    }

    /** Uit de cache als die vers genoeg is (of het ophalen mislukt); anders opnieuw ophalen. */
    static JSONObject feed(Context c, String feedUrl, boolean force) throws Exception {
        String id = podId(feedUrl);
        JSONObject cached = readJson(cacheFile(c, id));
        if (!force && cached != null && System.currentTimeMillis() - cached.optLong("t") < 30 * 60_000L) return cached;
        try { return fetch(c, feedUrl); }
        catch (Exception e) {
            if (cached == null) throw e;
            return cached.put("stale", e.getMessage() == null ? "Bijwerken lukt niet" : e.getMessage());
        }
    }

    /** Afleveringen met voortgang erbij (voor het scherm). */
    static JSONObject withProgress(Context c, JSONObject feed) throws Exception {
        JSONArray items = feed.optJSONArray("items");
        if (items != null) for (int i = 0; i < items.length(); i++) {
            JSONObject e = items.getJSONObject(i), p = progressOf(c, e.optString("key"));
            if (p != null) e.put("pos", p.optLong("p")).put("done", p.optBoolean("done")).put("pd", p.optLong("d"));
        }
        JSONObject pod = feed.optJSONObject("pod");
        if (pod != null) feed.put("subscribed", isSub(c, pod.optString("id")));
        return feed;
    }

    // ---------- abonnementen ----------

    static File subsFile(Context c) { return new File(dir(c), "abonnementen.json"); }

    static synchronized JSONArray subs(Context c) {
        try { File f = subsFile(c); return f.isFile() ? new JSONArray(Contacts.readText(f, false)) : new JSONArray(); }
        catch (Exception e) { return new JSONArray(); }
    }

    static synchronized void saveSubs(Context c, JSONArray a) throws Exception { Contacts.writeText(subsFile(c), a.toString(), false); }

    static boolean isSub(Context c, String id) {
        JSONArray a = subs(c);
        for (int i = 0; i < a.length(); i++) if (id.equals(a.optJSONObject(i).optString("id"))) return true;
        return false;
    }

    static synchronized void subscribe(Context c, JSONObject pod) throws Exception {
        String feed = pod.optString("feed");
        if (!httpUrl(feed)) throw new Exception("Ongeldig feed-adres");
        String id = podId(feed);
        if (isSub(c, id)) return;
        JSONArray a = subs(c);
        JSONObject cached = readJson(cacheFile(c, id));
        long newest = 0;
        JSONArray items = cached == null ? null : cached.optJSONArray("items");
        if (items != null && items.length() > 0) newest = items.getJSONObject(0).optLong("date");
        a.put(new JSONObject().put("id", id).put("feed", feed).put("title", FeedText.clip(pod.optString("title"), 300)).put("author", FeedText.clip(pod.optString("author"), 200))
                .put("image", pod.optString("image")).put("added", System.currentTimeMillis()).put("seen", newest).put("newest", newest));
        saveSubs(c, a);
    }

    static synchronized void unsubscribe(Context c, String id) throws Exception {
        JSONArray a = subs(c), out = new JSONArray();
        for (int i = 0; i < a.length(); i++) if (!id.equals(a.optJSONObject(i).optString("id"))) out.put(a.get(i));
        saveSubs(c, out);
    }

    /** Na ophalen: titel, plaatje en nieuwste aflevering van het abonnement bijwerken. */
    static synchronized void updateSub(Context c, JSONObject pod, JSONArray items) throws Exception {
        JSONArray a = subs(c);
        boolean changed = false;
        for (int i = 0; i < a.length(); i++) {
            JSONObject s = a.getJSONObject(i);
            if (!s.optString("id").equals(pod.optString("id"))) continue;
            s.put("title", pod.optString("title")).put("author", pod.optString("author")).put("image", pod.optString("image"));
            long newest = items.length() > 0 ? items.getJSONObject(0).optLong("date") : 0;
            // Nog nooit bijgewerkt (gevolgd vóór het laden, of na terugzetten): alles wat er nu is, telt niet als nieuw
            if (!s.has("checked") && s.optLong("seen") == 0) s.put("seen", newest);
            int fresh = 0;
            for (int k = 0; k < items.length(); k++) if (items.getJSONObject(k).optLong("date") > s.optLong("seen")) fresh++;
            s.put("newest", newest).put("fresh", fresh).put("checked", System.currentTimeMillis());
            changed = true;
        }
        if (changed) saveSubs(c, a);
    }

    /** Afleveringen van deze podcast gezien: de teller "nieuw" op nul. */
    static synchronized void seen(Context c, String id) throws Exception {
        JSONArray a = subs(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject s = a.getJSONObject(i);
            if (s.optString("id").equals(id)) s.put("seen", Math.max(s.optLong("newest"), s.optLong("seen"))).put("fresh", 0);
        }
        saveSubs(c, a);
    }

    /** Alle abonnementen bijwerken (oudste controle eerst). Geeft het aantal nieuwe afleveringen. */
    static int refreshAll(Context c) {
        if (!REFRESHING.compareAndSet(false, true)) return -1; // loopt al
        try {
            prefs(c).edit().putLong("refreshedAt", System.currentTimeMillis()).apply(); // meteen: niet opnieuw starten bij elke keer openen
            return refreshAllLocked(c);
        } finally { REFRESHING.set(false); }
    }

    private static int refreshAllLocked(Context c) {
        JSONArray a = subs(c);
        int fresh = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONObject s = a.optJSONObject(i);
            if (s == null) continue;
            try { fetch(c, s.optString("feed")); } catch (Exception e) { App.log(c, "PODCAST", s.optString("title") + ": " + e.getMessage()); }
        }
        JSONArray b = subs(c);
        for (int i = 0; i < b.length(); i++) fresh += b.optJSONObject(i).optInt("fresh");
        return fresh;
    }

    // ---------- voortgang en verder luisteren ----------

    static File progressFile(Context c) { return new File(dir(c), "voortgang.json"); }
    static File recentFile(Context c) { return new File(dir(c), "verder.json"); }

    /** Voortgang in het geheugen; schrijven gebeurt op de achtergrond (hooguit om de paar seconden). */
    private static JSONObject progCache;
    private static boolean progDirty;
    private static final Object PROG = new Object();

    private static JSONObject progLoaded(Context c) {
        if (progCache == null) {
            try { File f = progressFile(c); progCache = f.isFile() ? new JSONObject(Contacts.readText(f, false)) : new JSONObject(); }
            catch (Exception e) { progCache = new JSONObject(); }
        }
        return progCache;
    }

    /** Kopie van alles (voor backup). */
    static JSONObject progress(Context c) {
        synchronized (PROG) { try { return new JSONObject(progLoaded(c).toString()); } catch (Exception e) { return new JSONObject(); } }
    }

    /** Eén aflevering (kopie), of null. */
    static JSONObject progressOf(Context c, String key) {
        synchronized (PROG) {
            JSONObject p = progLoaded(c).optJSONObject(key == null ? "" : key);
            try { return p == null ? null : new JSONObject(p.toString()); } catch (Exception e) { return null; }
        }
    }

    /** Plek bewaren (ms). done = helemaal beluisterd. Snel: het bestand wordt op de achtergrond geschreven. */
    static void saveProgress(Context c, String key, long pos, long dur, boolean done) {
        if (!validKey(key)) return;
        synchronized (PROG) {
            JSONObject p = progLoaded(c);
            try { put(p, key, pos, dur, done, System.currentTimeMillis()); trim(p); } catch (Exception ignored) { }
        }
        scheduleWrite(c);
    }

    private static void put(JSONObject p, String key, long pos, long dur, boolean done, long t) throws Exception {
        p.put(key, new JSONObject().put("p", done ? 0 : Math.max(0, pos)).put("d", Math.max(0, dur)).put("done", done).put("t", t));
    }

    /** Hooguit 3000 bewaren: de oudste gaan weg. */
    private static void trim(JSONObject p) {
        if (p.length() <= 3000) return;
        List<String> keys = new ArrayList<>();
        for (Iterator<String> it = p.keys(); it.hasNext(); ) keys.add(it.next());
        final JSONObject pp = p;
        Collections.sort(keys, (x, y) -> Long.compare(pp.optJSONObject(x) == null ? 0 : pp.optJSONObject(x).optLong("t"), pp.optJSONObject(y) == null ? 0 : pp.optJSONObject(y).optLong("t")));
        int drop = Math.min(keys.size(), p.length() - 2500);
        for (int i = 0; i < drop; i++) p.remove(keys.get(i));
    }

    private static void scheduleWrite(Context c) {
        synchronized (PROG) {
            if (progDirty) return; // er staat al een schrijfbeurt klaar
            progDirty = true;
        }
        final Context app = c.getApplicationContext();
        IO.execute(() -> {
            try { Thread.sleep(1500); } catch (InterruptedException ignored) { } // wijzigingen even verzamelen
            String snap;
            synchronized (PROG) { progDirty = false; snap = progCache == null ? null : progCache.toString(); }
            if (snap != null) try { Contacts.writeText(progressFile(app), snap, false); } catch (Exception ignored) { }
        });
    }

    static synchronized JSONArray recent(Context c) {
        try { File f = recentFile(c); return f.isFile() ? new JSONArray(Contacts.readText(f, false)) : new JSONArray(); }
        catch (Exception e) { return new JSONArray(); }
    }

    /** Bovenaan "Verder luisteren" zetten. */
    /** Op de achtergrond (schrijft een bestand). */
    static void touchRecent(Context c, JSONObject ep, JSONObject pod) {
        final Context app = c.getApplicationContext();
        IO.execute(() -> touchRecentNow(app, ep, pod));
    }

    static synchronized void touchRecentNow(Context c, JSONObject ep, JSONObject pod) {
        try {
            JSONArray a = recent(c), out = new JSONArray();
            String key = ep.optString("key");
            out.put(new JSONObject().put("ep", ep).put("pod", pod).put("t", System.currentTimeMillis()));
            for (int i = 0; i < a.length() && out.length() < 30; i++) {
                JSONObject r = a.optJSONObject(i);
                if (r != null && !key.equals(r.optJSONObject("ep") == null ? "" : r.optJSONObject("ep").optString("key"))) out.put(r);
            }
            Contacts.writeText(recentFile(c), out.toString(), false);
        } catch (Exception ignored) { }
    }

    static synchronized void removeRecent(Context c, String key) {
        try {
            JSONArray a = recent(c), out = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                JSONObject r = a.optJSONObject(i);
                if (r != null && !key.equals(r.optJSONObject("ep") == null ? "" : r.optJSONObject("ep").optString("key"))) out.put(r);
            }
            Contacts.writeText(recentFile(c), out.toString(), false);
        } catch (Exception ignored) { }
    }

    /** Verder luisteren: recent gestarte afleveringen die nog niet af zijn, met hun plek. */
    static JSONArray continueList(Context c) throws Exception {
        JSONArray a = recent(c), out = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.getJSONObject(i), ep = r.optJSONObject("ep");
            if (ep == null) continue;
            JSONObject p = progressOf(c, ep.optString("key"));
            if (p != null && p.optBoolean("done")) continue;
            ep.put("pos", p == null ? 0 : p.optLong("p")).put("pd", p == null ? 0 : p.optLong("d"));
            out.put(r);
        }
        return out;
    }

    // ---------- Hierna (wachtrij) ----------

    static File queueFile(Context c) { return new File(dir(c), "hierna.json"); }

    static synchronized JSONArray queue(Context c) {
        try { File f = queueFile(c); return f.isFile() ? new JSONArray(Contacts.readText(f, false)) : new JSONArray(); }
        catch (Exception e) { return new JSONArray(); }
    }

    private static synchronized void saveQueue(Context c, JSONArray a) {
        try { Contacts.writeText(queueFile(c), a.toString(), false); } catch (Exception ignored) { }
    }

    private static String keyOf(JSONObject r) { JSONObject e = r == null ? null : r.optJSONObject("ep"); return e == null ? "" : e.optString("key"); }

    /** Toevoegen (achteraan, of vooraan = "als volgende"). Staat hij er al, dan verplaatst hij. Hooguit 200. */
    static synchronized int queueAdd(Context c, JSONObject ep, JSONObject pod, boolean next) throws Exception {
        String key = ep.optString("key");
        if (!validKey(key) || !httpUrl(ep.optString("url"))) throw new Exception("Deze aflevering kan niet in Hierna");
        JSONArray a = queue(c), out = new JSONArray();
        JSONObject item = new JSONObject().put("ep", ep).put("pod", pod == null ? new JSONObject() : pod);
        if (next) out.put(item);
        for (int i = 0; i < a.length(); i++) { JSONObject r = a.optJSONObject(i); if (r != null && !key.equals(keyOf(r))) out.put(r); }
        if (!next) out.put(item);
        while (out.length() > 200) out.remove(out.length() - 1);
        saveQueue(c, out);
        return out.length();
    }

    static synchronized void queueRemove(Context c, String key) {
        if (key == null) return;
        JSONArray a = queue(c), out = new JSONArray();
        boolean hit = false;
        for (int i = 0; i < a.length(); i++) { JSONObject r = a.optJSONObject(i); if (r != null && !key.equals(keyOf(r))) out.put(r); else hit = true; }
        if (hit) saveQueue(c, out);
    }

    static synchronized void queueMove(Context c, int from, int to) {
        JSONArray a = queue(c);
        if (from < 0 || from >= a.length()) return;
        to = Math.max(0, Math.min(a.length() - 1, to));
        java.util.List<Object> l = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) l.add(a.opt(i));
        l.add(to, l.remove(from));
        saveQueue(c, new JSONArray(l));
    }

    static synchronized void queueMoveKey(Context c, String key, int to) {
        JSONArray a = queue(c);
        for (int i = 0; i < a.length(); i++) if (keyOf(a.optJSONObject(i)).equals(key)) { queueMove(c, i, to); return; }
    }

    static synchronized void queueClear(Context c) { saveQueue(c, new JSONArray()); }

    /** De eerste uit Hierna halen (of null). */
    static synchronized JSONObject queuePop(Context c) {
        JSONArray a = queue(c);
        if (a.length() == 0) return null;
        JSONObject r = a.optJSONObject(0);
        a.remove(0);
        saveQueue(c, a);
        return r;
    }

    /**
     * Volgende aflevering (vegen op de hoes, ⏭): eerst Hierna; anders in dezelfde podcast de nieuwere aflevering,
     * en als die er niet is (of al beluisterd) de oudere. {ep, pod} of null.
     */
    static JSONObject next(Context c, JSONObject ep, JSONObject pod) {
        JSONObject q = queuePop(c);
        if (q != null) return q;
        if (ep == null || pod == null) return null;
        try {
            JSONObject feed = readJson(cacheFile(c, pod.optString("id", podId(pod.optString("feed")))));
            JSONArray items = feed == null ? null : feed.optJSONArray("items");
            if (items == null) return null;
            String key = ep.optString("key");
            int at = -1;
            for (int i = 0; i < items.length(); i++) if (key.equals(items.getJSONObject(i).optString("key"))) { at = i; break; }
            if (at < 0) return null;
            for (int i : new int[]{at - 1, at + 1}) { // de lijst staat nieuwste eerst
                if (i < 0 || i >= items.length()) continue;
                JSONObject e = items.getJSONObject(i), p = progressOf(c, e.optString("key"));
                if (p != null && p.optBoolean("done")) continue;
                return new JSONObject().put("ep", e).put("pod", pod);
            }
        } catch (Exception ignored) { }
        return null;
    }

    /**
     * Wat er eerder speelde (uit de lijst van recent gestart, nieuwste eerst), of null. depth 1 = de vorige; vlak na
     * elkaar teruggaan geeft depth 2, 3, … (wat je net terug-speelde staat dan zelf vooraan, de rest schuift op).
     */
    static JSONObject previous(Context c, String key, int depth) {
        JSONArray a = recent(c);
        int n = 0;
        JSONObject last = null;
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.optJSONObject(i);
            if (r == null || r.optJSONObject("ep") == null || keyOf(r).equals(key)) continue;
            last = r;
            if (++n >= Math.max(1, depth)) return r;
        }
        return last; // niet zo ver terug: de oudste
    }

    // ---------- backup ----------

    /** Voor Alles back-uppen (instellingen): abonnementen en voortgang. */
    static JSONObject backupJson(Context c) throws Exception {
        return new JSONObject().put("subs", subs(c)).put("progress", progress(c)).put("speed", prefs(c).getFloat("speed", 1f));
    }

    /** Terugzetten: abonnementen samenvoegen (bestaande blijven), voortgang alleen waar die nog niet bekend is. */
    static synchronized int restore(Context c, JSONObject o) throws Exception {
        if (o == null) return 0;
        int n = 0;
        JSONArray in = o.optJSONArray("subs");
        if (in != null) for (int i = 0; i < in.length(); i++) {
            JSONObject s = in.optJSONObject(i);
            if (s == null || !httpUrl(s.optString("feed"))) continue;
            if (!isSub(c, podId(s.optString("feed")))) {
                subscribe(c, new JSONObject().put("feed", s.optString("feed")).put("title", s.optString("title")).put("author", s.optString("author"))
                        .put("image", httpUrl(s.optString("image")) ? s.optString("image") : ""));
                n++;
            }
        }
        JSONObject pr = o.optJSONObject("progress");
        if (pr != null) {
            long now = System.currentTimeMillis();
            synchronized (PROG) {
                JSONObject p = progLoaded(c);
                for (Iterator<String> it = pr.keys(); it.hasNext(); ) {
                    String k = it.next();
                    JSONObject v = pr.optJSONObject(k);
                    if (!validKey(k) || v == null || p.has(k)) continue;
                    long t = v.optLong("t"); // de oorspronkelijke datum houden, zodat het opruimen de juiste weggooit
                    put(p, k, v.optLong("p"), v.optLong("d"), v.optBoolean("done"), t > 0 && t <= now ? t : now - 1);
                }
                trim(p);
            }
            scheduleWrite(c);
        }
        return n;
    }

    /** Kleine tekst-hulp (geen eigen bestand waard). */
    static final class FeedText {
        static String clip(String s, int max) { return s == null ? "" : s.length() > max ? s.substring(0, max) : s; }
    }
}
