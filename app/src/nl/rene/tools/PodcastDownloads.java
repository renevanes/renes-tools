package nl.rene.tools;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Podcast-afleveringen downloaden om offline te luisteren. Het downloaden zelf doet Android (DownloadManager: gaat door
 * op de achtergrond, hervat na een onderbreking, voortgang in een melding). De bestanden staan in de eigen map van de
 * app (Android/data/…/files/Podcasts) en gaan niet mee in Android-backups. Afspelen kiest vanzelf het bestand als het er is.
 */
final class PodcastDownloads {

    private PodcastDownloads() { }

    static final int AUTO_PER_POD = 3;       // vanzelf downloaden: zoveel nieuwste, nog niet beluisterde afleveringen per podcast
    static final int MAX_GONE = 500;

    /** Klaar gedownload: sleutel → pad (zodat afspelen zonder schijf- of DownloadManager-werk het bestand vindt). */
    private static final java.util.concurrent.ConcurrentHashMap<String, String> READY = new java.util.concurrent.ConcurrentHashMap<>();

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("podcast_dl", Context.MODE_PRIVATE); }
    static boolean wifiOnly(Context c) { return prefs(c).getBoolean("wifiOnly", true); }
    static boolean autoDelete(Context c) { return prefs(c).getBoolean("autoDelete", true); }

    static File indexFile(Context c) { return new File(Podcasts.dir(c), "downloads.json"); }

    /** Welke afleveringen in de lijst staan (null = nog niet gelezen): afspelen weet zo meteen dat er niets is. */
    private static volatile java.util.Set<String> KEYS;

    private static JSONObject index(Context c) {
        JSONObject o = Podcasts.readJson(indexFile(c));
        if (o == null) o = new JSONObject();
        KEYS = keysOf(o);
        return o;
    }
    private static void save(Context c, JSONObject o) {
        KEYS = keysOf(o);
        try { Contacts.writeText(indexFile(c), o.toString(), false); } catch (Exception e) { App.log(c, "PODCAST", "downloads bewaren: " + e.getMessage()); }
    }
    private static java.util.Set<String> keysOf(JSONObject o) {
        java.util.Set<String> s = new java.util.HashSet<>();
        for (Iterator<String> k = o.keys(); k.hasNext(); ) s.add(k.next());
        return java.util.Collections.unmodifiableSet(s);
    }

    /** Bij het starten (op de achtergrond): de lijst inlezen, zodat afspelen nooit op schijf of Android hoeft te wachten. */
    static void warm(Context c) {
        final Context app = c.getApplicationContext();
        Podcasts.IO.execute(() -> {
            try {
                JSONObject idx = index(app);
                File d = dir(app);
                if (d == null) return;
                for (Iterator<String> k = idx.keys(); k.hasNext(); ) {
                    String key = k.next(); JSONObject it = idx.optJSONObject(key);
                    File f = it == null ? null : fileIn(d, it.optString("file"));
                    if (f != null && it.optBoolean("done") && f.isFile() && f.length() > 0) READY.put(key, f.getAbsolutePath());
                }
            } catch (Throwable ignored) { }
        });
    }

    /** Het bestand van een download in de downloadmap; null bij een vreemde naam (alleen een kale bestandsnaam mag). */
    static File fileIn(File d, String name) {
        if (d == null || !safeName(name)) return null;
        return new File(d, name);
    }
    static boolean safeName(String n) { return n != null && !n.isEmpty() && n.length() <= 200 && n.indexOf('/') < 0 && n.indexOf('\\') < 0 && n.indexOf('\0') < 0 && !n.equals(".") && !n.equals(".."); }

    static File dir(Context c) {
        File d = c.getExternalFilesDir(Environment.DIRECTORY_PODCASTS);
        if (d != null) d.mkdirs();
        return d;
    }

    static String ext(String url, String type) {
        String t = type == null ? "" : type.toLowerCase(java.util.Locale.ROOT);
        String u = url == null ? "" : url.toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < u.length(); i++) if (u.charAt(i) == '?' || u.charAt(i) == '#') { u = u.substring(0, i); break; } // zonder regex: lineair
        if (t.contains("mp4") || t.contains("m4a") || t.contains("aac") || u.endsWith(".m4a") || u.endsWith(".mp4") || u.endsWith(".aac")) return ".m4a";
        if (t.contains("ogg") || u.endsWith(".ogg")) return ".ogg";
        if (t.contains("opus") || u.endsWith(".opus")) return ".opus";
        return ".mp3";
    }

    private static DownloadManager dm(Context c) { return (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE); }

    /** Downloaden starten. Geeft "" of een melding. */
    static synchronized String start(Context c, JSONObject ep, JSONObject pod, boolean auto) {
        String key = ep.optString("key"), url = ep.optString("url");
        if (!Podcasts.validKey(key) || !Podcasts.httpUrl(url)) return "Deze aflevering kan niet gedownload worden";
        JSONObject idx = index(c);
        if (idx.has(key)) return "";
        File d = dir(c);
        if (d == null) return "Geen opslag beschikbaar om te downloaden";
        DownloadManager m = dm(c);
        if (m == null) return "Downloaden kan niet op deze telefoon";
        String name = key + ext(url, ep.optString("type"));
        try {
            new File(d, name).delete(); // resten van een eerdere keer
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url))
                    .addRequestHeader("User-Agent", Radio.UA)
                    .setTitle(Podcasts.FeedText.clip(ep.optString("title", "Aflevering"), 120))
                    .setDescription(Podcasts.FeedText.clip(pod == null ? "Podcast" : pod.optString("title", "Podcast"), 120))
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setAllowedOverMetered(!wifiOnly(c))
                    .setAllowedOverRoaming(false)
                    .setDestinationInExternalFilesDir(c, Environment.DIRECTORY_PODCASTS, name);
            long id = m.enqueue(r);
            JSONObject slimEp = new JSONObject().put("key", key).put("title", ep.optString("title")).put("url", url).put("type", ep.optString("type"))
                    .put("date", ep.optLong("date")).put("dur", ep.optLong("dur")).put("image", ep.optString("image")).put("guid", ep.optString("guid"))
                    .put("desc", Podcasts.FeedText.clip(ep.optString("desc"), 600));
            JSONObject slimPod = pod == null ? new JSONObject() : new JSONObject().put("id", pod.optString("id")).put("feed", pod.optString("feed"))
                    .put("title", pod.optString("title")).put("author", pod.optString("author")).put("image", pod.optString("image")).put("link", pod.optString("link"));
            idx.put(key, new JSONObject().put("id", id).put("file", name).put("t", System.currentTimeMillis()).put("auto", auto).put("ep", slimEp).put("pod", slimPod));
            save(c, idx);
            unGone(c, key);
            return "";
        } catch (Exception e) {
            return "Downloaden lukt niet";
        }
    }

    /** Status van één download: done, running, paused, queued, failed. Met bytes en total. */
    private static JSONObject status(Context c, DownloadManager m, JSONObject it) throws Exception {
        JSONObject s = new JSONObject();
        File f = fileIn(dir(c), it.optString("file"));
        if (f == null) return s.put("state", "failed").put("reason", "Bestand is weg");
        if (it.optBoolean("done")) {
            if (f.isFile() && f.length() > 0) return s.put("state", "done").put("bytes", f.length()).put("total", f.length());
            return s.put("state", "failed").put("reason", "Bestand is weg");
        }
        try (Cursor cur = m.query(new DownloadManager.Query().setFilterById(it.optLong("id")))) {
            if (cur == null || !cur.moveToFirst()) return s.put("state", "failed").put("reason", "Download is gestopt");
            int st = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            long done = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long total = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            int reason = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
            s.put("bytes", done).put("total", total);
            switch (st) {
                case DownloadManager.STATUS_SUCCESSFUL: {
                    // Android kan een andere naam kiezen (bijv. "…-1.mp3" als de naam al bestond): die gebruiken
                    String lu = cur.getString(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
                    String lp = lu == null ? null : Uri.parse(lu).getPath();
                    File d = dir(c);
                    if (lp != null && d != null) { File g = new File(lp); if (g.isFile() && d.equals(g.getParentFile())) { f = g; it.put("file", g.getName()); } }
                    return s.put("state", f.isFile() && f.length() > 0 ? "done" : "failed").put("bytes", f.length());
                }
                case DownloadManager.STATUS_RUNNING: return s.put("state", "running");
                case DownloadManager.STATUS_PENDING: return s.put("state", "queued");
                case DownloadManager.STATUS_PAUSED: return s.put("state", "paused").put("reason", pauseReason(reason)).put("wifi", reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI);
                default: return s.put("state", "failed").put("reason", reason == DownloadManager.ERROR_INSUFFICIENT_SPACE ? "Te weinig opslag" : "Downloaden mislukt");
            }
        }
    }

    static String pauseReason(int r) {
        switch (r) {
            case DownloadManager.PAUSED_QUEUED_FOR_WIFI: return "Wacht op wifi";
            case DownloadManager.PAUSED_WAITING_FOR_NETWORK: return "Wacht op internet";
            case DownloadManager.PAUSED_WAITING_TO_RETRY: return "Probeert het zo opnieuw";
            default: return "Gepauzeerd";
        }
    }

    /** Alle downloads, nieuwste eerst: [{key, ep, pod, t, auto, state, bytes, total, reason?}]. */
    static synchronized JSONArray list(Context c) {
        JSONArray out = new JSONArray();
        DownloadManager m = dm(c);
        JSONObject idx = index(c);
        boolean changed = false;
        List<JSONObject> rows = new ArrayList<>();
        for (Iterator<String> k = idx.keys(); k.hasNext(); ) {
            String key = k.next();
            JSONObject it = idx.optJSONObject(key);
            if (it == null) continue;
            try {
                JSONObject s = m == null ? new JSONObject().put("state", "failed") : status(c, m, it);
                if ("done".equals(s.optString("state")) && !it.optBoolean("done")) { it.put("done", true); changed = true; }
                File rf = fileIn(dir(c), it.optString("file"));
                if ("done".equals(s.optString("state")) && rf != null) READY.put(key, rf.getAbsolutePath()); else READY.remove(key);
                s.put("key", key).put("ep", it.optJSONObject("ep")).put("pod", it.optJSONObject("pod")).put("t", it.optLong("t")).put("auto", it.optBoolean("auto"));
                rows.add(s);
            } catch (Exception ignored) { }
        }
        if (changed) save(c, idx);
        java.util.Collections.sort(rows, (a, b) -> Long.compare(b.optLong("t"), a.optLong("t")));
        for (JSONObject r : rows) out.put(r);
        return out;
    }

    /** Pad van het gedownloade bestand, of null (nog niet klaar of niet gedownload). */
    static String localPath(Context c, String key) {
        if (!Podcasts.validKey(key)) return null;
        String p = READY.get(key);
        if (p != null) { if (new File(p).isFile()) return p; READY.remove(key); }
        java.util.Set<String> k = KEYS;
        if (k != null && !k.contains(key)) return null; // niet gedownload: niets op schijf te zoeken
        return localPathSlow(c, key);
    }

    /** Zonder slot (wordt vanaf de afspeelthread aangeroepen): alleen lezen; de lijst wordt later door list() bijgewerkt. */
    private static String localPathSlow(Context c, String key) {
        JSONObject idx = index(c), it = idx.optJSONObject(key);
        if (it == null) return null;
        File d = dir(c);
        if (d == null) return null;
        try {
            if (!it.optBoolean("done")) {
                DownloadManager m = dm(c);
                if (m == null || !"done".equals(status(c, m, it).optString("state"))) return null; // status() zet ook de echte bestandsnaam
            }
        } catch (Exception e) { return null; }
        File f = fileIn(d, it.optString("file"));
        if (f == null || !f.isFile() || f.length() == 0) return null;
        READY.put(key, f.getAbsolutePath());
        return f.getAbsolutePath();
    }

    static synchronized void remove(Context c, String key) {
        JSONObject idx = index(c), it = idx.optJSONObject(key);
        if (it == null) return;
        READY.remove(key);
        DownloadManager m = dm(c);
        try { if (m != null) m.remove(it.optLong("id")); } catch (Exception ignored) { }
        File d = dir(c);
        File f = fileIn(d, it.optString("file"));
        if (f != null) f.delete();
        idx.remove(key);
        save(c, idx);
        if (it.optBoolean("auto")) gone(c, key); // vanzelf gedownload en weggehaald: niet nog eens vanzelf
    }

    /** "Alleen via wifi" uitgezet: wat op wifi wacht, opnieuw starten (Android legt die keuze vast bij het starten). */
    static synchronized int requeueWaiting(Context c) {
        DownloadManager m = dm(c);
        if (m == null) return 0;
        JSONObject idx = index(c);
        List<JSONObject> again = new ArrayList<>();
        for (Iterator<String> k = idx.keys(); k.hasNext(); ) {
            JSONObject it = idx.optJSONObject(k.next());
            if (it == null || it.optBoolean("done")) continue;
            // Alleen wat op wifi wacht, of nog niet begonnen is: opnieuw starten gooit wat al binnen was weg
            try { JSONObject s = status(c, m, it); String st = s.optString("state");
                if (("paused".equals(st) && s.optBoolean("wifi")) || ("queued".equals(st) && s.optLong("bytes") <= 0)) again.add(it); } catch (Exception ignored) { }
        }
        for (JSONObject it : again) {
            String key = it.optJSONObject("ep") == null ? "" : it.optJSONObject("ep").optString("key");
            try { m.remove(it.optLong("id")); } catch (Exception ignored) { }
            JSONObject cur = index(c); cur.remove(key); save(c, cur);
            start(c, it.optJSONObject("ep"), it.optJSONObject("pod"), it.optBoolean("auto"));
        }
        return again.size();
    }

    static synchronized void removeAll(Context c) {
        JSONObject idx = index(c);
        for (Iterator<String> k = idx.keys(); k.hasNext(); ) {
            String key = k.next(); JSONObject it = idx.optJSONObject(key);
            if (it != null && it.optBoolean("auto")) gone(c, key);
        }
        List<String> keys = new ArrayList<>();
        for (Iterator<String> k = idx.keys(); k.hasNext(); ) keys.add(k.next());
        for (String key : keys) remove(c, key);
        File d = dir(c);
        File[] left = d == null ? null : d.listFiles();
        if (left != null) for (File f : left) f.delete();
    }

    /** Aflevering beluisterd: download weg als dat zo is ingesteld. Geeft true als er iets weg is. */
    static boolean played(Context c, String key) {
        if (!autoDelete(c)) return false;
        if (key.equals(PodcastService.playingKey())) return false; // speelt nog (bijv. de laatste seconden)
        java.util.Set<String> k = KEYS;
        if (k != null && !k.contains(key)) return false;
        boolean had = index(c).has(key);
        if (had) remove(c, key);
        return had;
    }

    // ---------- vanzelf downloaden van nieuwe afleveringen ----------

    private static void gone(Context c, String key) {
        try {
            JSONArray a = new JSONArray(prefs(c).getString("gone", "[]"));
            for (int i = 0; i < a.length(); i++) if (key.equals(a.optString(i))) return;
            a.put(key);
            while (a.length() > MAX_GONE) a.remove(0);
            prefs(c).edit().putString("gone", a.toString()).apply();
        } catch (Exception ignored) { }
    }
    private static void unGone(Context c, String key) {
        try {
            JSONArray a = new JSONArray(prefs(c).getString("gone", "[]")), b = new JSONArray();
            for (int i = 0; i < a.length(); i++) if (!key.equals(a.optString(i))) b.put(a.optString(i));
            if (b.length() != a.length()) prefs(c).edit().putString("gone", b.toString()).apply();
        } catch (Exception ignored) { }
    }
    private static boolean isGone(Context c, String key) {
        try { JSONArray a = new JSONArray(prefs(c).getString("gone", "[]")); for (int i = 0; i < a.length(); i++) if (key.equals(a.optString(i))) return true; }
        catch (Exception ignored) { }
        return false;
    }

    static boolean anyAuto(Context c) {
        JSONArray s = Podcasts.subs(c);
        for (int i = 0; i < s.length(); i++) if (s.optJSONObject(i) != null && s.optJSONObject(i).optBoolean("dl")) return true;
        return false;
    }

    /**
     * Welke afleveringen vanzelf downloaden (zuiver, getest): van nieuw naar oud, alleen vanaf {@code from}, beluisterde
     * overslaan; wat al gedownload is of door jou is weggehaald telt mee voor de {@code max} plekken.
     */
    static List<String> pickAuto(String[] keys, long[] dates, boolean[] played, java.util.Set<String> haveOrGone, long from, int max) {
        List<String> out = new ArrayList<>();
        int slots = 0;
        for (int k = 0; k < keys.length && slots < max; k++) {
            if (keys[k] == null || keys[k].isEmpty()) continue;
            if (dates[k] > 0 && dates[k] < from) break;
            if (played[k]) continue;
            slots++;
            if (!haveOrGone.contains(keys[k])) out.add(keys[k]);
        }
        return out;
    }

    /** Voor podcasts met "vanzelf downloaden": de nieuwste, nog niet beluisterde afleveringen. Geeft het aantal nieuwe. */
    static int autoQueue(Context c) {
        int n = 0;
        JSONArray subs = Podcasts.subs(c);
        JSONObject prog = Podcasts.progress(c);
        for (int i = 0; i < subs.length(); i++) {
            JSONObject s = subs.optJSONObject(i);
            if (s == null || !s.optBoolean("dl")) continue;
            JSONObject feed = Podcasts.readJson(Podcasts.cacheFile(c, s.optString("id")));
            JSONArray items = feed == null ? null : feed.optJSONArray("items");
            if (items == null) continue;
            int len = Math.min(items.length(), 60);
            String[] keys = new String[len]; long[] dates = new long[len]; boolean[] played = new boolean[len];
            java.util.Set<String> have = new java.util.HashSet<>();
            JSONObject idx = index(c);
            for (int k = 0; k < len; k++) {
                JSONObject e = items.optJSONObject(k);
                if (e == null) { keys[k] = ""; continue; }
                keys[k] = e.optString("key"); dates[k] = e.optLong("date");
                JSONObject p = prog.optJSONObject(keys[k]);
                played[k] = p != null && p.optBoolean("done");
                if (idx.has(keys[k]) || isGone(c, keys[k])) have.add(keys[k]);
            }
            for (String key : pickAuto(keys, dates, played, have, s.optLong("dlFrom"), AUTO_PER_POD)) {
                for (int k = 0; k < len; k++) if (key.equals(keys[k])) { if ("".equals(start(c, items.optJSONObject(k), s, true))) n++; break; }
            }
        }
        return n;
    }

    /** Vanzelf downloaden voor één podcast aan of uit. */
    static void setAuto(Context c, String podId, boolean on) throws Exception {
        synchronized (Podcasts.class) {
            JSONArray a = Podcasts.subs(c);
            for (int i = 0; i < a.length(); i++) {
                JSONObject s = a.getJSONObject(i);
                if (!s.optString("id").equals(podId)) continue;
                s.put("dl", on);
                if (on) {
                    JSONObject feed = Podcasts.readJson(Podcasts.cacheFile(c, podId));
                    JSONArray items = feed == null ? null : feed.optJSONArray("items");
                    // Vanaf de 3e nieuwste aflevering die er nu is: die (en nieuwere) worden meteen gedownload
                    long from = items != null && items.length() > 0 ? items.getJSONObject(Math.min(AUTO_PER_POD, items.length()) - 1).optLong("date") : System.currentTimeMillis();
                    s.put("dlFrom", from);
                } else s.remove("dlFrom");
            }
            Podcasts.saveSubs(c, a);
        }
        PodcastDownloadJob.schedule(c);
    }
}
