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

    // Vanzelf downloaden over een periode (alle gevolgde podcasts), en vanzelf gedownloade afleveringen weer verwijderen
    static final int[] PERIODE_DAGEN = {3, 7, 14, 21, 30}, BEWAAR_DAGEN = {0, 7, 14, 21, 30}, MAX_MB = {0, 1000, 2000, 5000, 10000};
    static final int STD_DAGEN = 14, STD_BEWAAR = 14, STD_MB = 2000, MAX_PER_KEER = 40;
    static final long DAG = 24 * 3_600_000L, SCHAT_PER_SEC = 16_000, SCHAT_ONBEKEND = 50L * 1024 * 1024; // 128 kbit/s
    static boolean periode(Context c) { return prefs(c).getBoolean("periode", true); }
    static boolean periodeAlle(Context c) { return !"gekozen".equals(prefs(c).getString("periodeWelke", "alle")); }
    static int periodeDagen(Context c) { return kies(prefs(c).getInt("periodeDagen", STD_DAGEN), PERIODE_DAGEN, STD_DAGEN); }
    static int bewaarDagen(Context c) { return kies(prefs(c).getInt("bewaarDagen", STD_BEWAAR), BEWAAR_DAGEN, STD_BEWAAR); }
    static int maxMb(Context c) { return kies(prefs(c).getInt("maxMb", STD_MB), MAX_MB, STD_MB); }
    static int kies(int v, int[] mag, int std) { for (int m : mag) if (m == v) return v; return std; }

    /** Instellingen voor de periode uit {periode, periodeWelke, periodeDagen, bewaarDagen, maxMb} (alleen geldige waarden). */
    static void zetPeriode(SharedPreferences.Editor e, JSONObject o) {
        if (o.has("periode")) e.putBoolean("periode", o.optBoolean("periode", true));
        if (o.has("periodeWelke")) e.putString("periodeWelke", "gekozen".equals(o.optString("periodeWelke")) ? "gekozen" : "alle");
        if (o.has("periodeDagen")) e.putInt("periodeDagen", kies(o.optInt("periodeDagen"), PERIODE_DAGEN, STD_DAGEN));
        if (o.has("bewaarDagen")) e.putInt("bewaarDagen", kies(o.optInt("bewaarDagen", -1), BEWAAR_DAGEN, STD_BEWAAR));
        if (o.has("maxMb")) e.putInt("maxMb", kies(o.optInt("maxMb", -1), MAX_MB, STD_MB));
    }

    /** Doet deze gevolgde podcast mee met vanzelf downloaden? Periode met "alle": tenzij uitgezet; anders als aangezet. */
    static boolean doetMee(Context c, JSONObject s) {
        if (s == null) return false;
        return periode(c) && periodeAlle(c) ? !s.optBoolean("dlUit") : s.optBoolean("dl");
    }

    /** Geschatte grootte van een aflevering: de opgegeven grootte, anders uit de lengte (128 kbit/s), anders 50 MB. */
    static long schat(long size, long durSec) { return size > 0 ? size : durSec > 0 ? durSec * SCHAT_PER_SEC : SCHAT_ONBEKEND; }

    /**
     * Welke afleveringen over de periode (zuiver, getest): nieuwste eerst, alleen wat nog in de ruimte past
     * ({@code ruimte} &lt; 0 = geen grens), hooguit {@code max}. Geeft de plekken in de lijst.
     */
    static List<Integer> kiesPeriode(long[] dates, long[] sizes, long ruimte, int max) {
        Integer[] volg = new Integer[dates.length];
        for (int i = 0; i < volg.length; i++) volg[i] = i;
        java.util.Arrays.sort(volg, (x, y) -> Long.compare(dates[y], dates[x]));
        List<Integer> uit = new ArrayList<>();
        long over = ruimte;
        for (Integer i : volg) {
            if (uit.size() >= max) break;
            if (ruimte >= 0) { if (sizes[i] > over) continue; over -= sizes[i]; }
            uit.add(i);
        }
        return uit;
    }

    /** Is een vanzelf gedownloade aflevering (gedownload op {@code t}) oud genoeg om weg te gaan? bewaar 0 = nooit. Zuiver. */
    static boolean teOud(long t, long nu, int bewaarDagen) { return bewaarDagen > 0 && t > 0 && t < nu - bewaarDagen * DAG; }

    /** Vanzelf gedownloade afleveringen die langer dan de bewaartijd staan: weg (niet wat nu speelt, niet wat je zelf downloadde). */
    static synchronized int opruimen(Context c) {
        int dagen = bewaarDagen(c);
        if (dagen <= 0) return 0;
        long nu = System.currentTimeMillis();
        String speelt = PodcastService.playingKey();
        JSONObject idx = index(c);
        List<String> weg = new ArrayList<>();
        for (Iterator<String> k = idx.keys(); k.hasNext(); ) {
            String key = k.next(); JSONObject it = idx.optJSONObject(key);
            if (it != null && it.optBoolean("auto") && teOud(it.optLong("t"), nu, dagen) && !key.equals(speelt)) weg.add(key);
        }
        for (String key : weg) remove(c, key); // remove zet hem ook bij "weggehaald": niet nog eens vanzelf
        if (!weg.isEmpty()) App.log(c, "PODCAST", "vanzelf verwijderd: " + weg.size());
        return weg.size();
    }

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
                opruimen(app);
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
        for (int i = 0; i < u.length(); i++) { char ch = u.charAt(i); if (ch == '?' || ch == '#') { u = u.substring(0, i); break; } }
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
                    .put("desc", Podcasts.FeedText.clip(ep.optString("desc"), 600)).put("ch", ep.optString("ch")).put("size", ep.optLong("size"));
            JSONObject slimPod = pod == null ? new JSONObject() : new JSONObject().put("id", pod.optString("id")).put("feed", pod.optString("feed"))
                    .put("title", pod.optString("title")).put("author", pod.optString("author")).put("image", pod.optString("image")).put("link", pod.optString("link"));
            idx.put(key, new JSONObject().put("id", id).put("file", name).put("t", System.currentTimeMillis()).put("auto", auto).put("ep", slimEp).put("pod", slimPod));
            save(c, idx);
            unGone(c, key);
            // De hoofdstukken (voor reclame overslaan) meteen mee, zodat dat offline ook werkt
            final Context app = c.getApplicationContext();
            final JSONObject e0 = slimEp, p0 = slimPod;
            PodcastOverslaan.VOORUIT.execute(() -> {
                String adres = PodcastOverslaan.adres(app, e0, p0);
                if (!adres.isEmpty() && PodcastOverslaan.keuze(PodcastOverslaan.lees(app), PodcastOverslaan.podId(p0)).reclame) PodcastOverslaan.hoofdstukken(app, adres);
            });
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
        for (int i = 0; i < s.length(); i++) if (doetMee(c, s.optJSONObject(i))) return true;
        // Ook zonder: zolang er vanzelf gedownloade afleveringen zijn die nog weg moeten
        if (bewaarDagen(c) > 0) { JSONObject idx = index(c); for (Iterator<String> k = idx.keys(); k.hasNext(); ) { JSONObject it = idx.optJSONObject(k.next()); if (it != null && it.optBoolean("auto")) return true; } }
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
        opruimen(c);
        if (periode(c)) return periodeQueue(c);
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

    /** Periode: van elke podcast die meedoet de nog niet beluisterde afleveringen van de afgelopen dagen, binnen de ruimte. */
    static int periodeQueue(Context c) {
        long nu = System.currentTimeMillis(), van = nu - periodeDagen(c) * DAG;
        int mb = maxMb(c);
        JSONArray subs = Podcasts.subs(c);
        JSONObject prog = Podcasts.progress(c), idx = index(c);
        // Wat vanzelf gedownload is, telt mee voor de ruimte
        long inGebruik = 0;
        File d = dir(c);
        for (Iterator<String> k = idx.keys(); k.hasNext(); ) {
            JSONObject it = idx.optJSONObject(k.next());
            if (it == null || !it.optBoolean("auto")) continue;
            File f = fileIn(d, it.optString("file"));
            JSONObject e = it.optJSONObject("ep");
            inGebruik += f != null && f.isFile() && f.length() > 0 ? f.length() : schat(e == null ? 0 : e.optLong("size"), e == null ? 0 : e.optLong("dur"));
        }
        List<JSONObject> eps = new ArrayList<>(), pods = new ArrayList<>();
        for (int i = 0; i < subs.length(); i++) {
            JSONObject s = subs.optJSONObject(i);
            if (!doetMee(c, s)) continue;
            JSONObject feed = Podcasts.readJson(Podcasts.cacheFile(c, s.optString("id")));
            JSONArray items = feed == null ? null : feed.optJSONArray("items");
            if (items == null) continue;
            for (int k = 0; k < Math.min(items.length(), 100); k++) {
                JSONObject e = items.optJSONObject(k);
                if (e == null) continue;
                String key = e.optString("key");
                if (!Podcasts.validKey(key) || e.optLong("date") < van || idx.has(key) || isGone(c, key)) continue;
                JSONObject p = prog.optJSONObject(key);
                if (p != null && p.optBoolean("done")) continue;
                eps.add(e); pods.add(s);
            }
        }
        long[] dates = new long[eps.size()], sizes = new long[eps.size()];
        for (int i = 0; i < dates.length; i++) { dates[i] = eps.get(i).optLong("date"); sizes[i] = schat(eps.get(i).optLong("size"), eps.get(i).optLong("dur")); }
        long ruimte = mb <= 0 ? -1 : Math.max(0, mb * 1024L * 1024L - inGebruik);
        int n = 0;
        for (int i : kiesPeriode(dates, sizes, ruimte, MAX_PER_KEER)) if ("".equals(start(c, eps.get(i), pods.get(i), true))) n++;
        if (n > 0) App.log(c, "PODCAST", "vanzelf downloaden (periode): " + n);
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
                if (on) s.remove("dlUit"); else s.put("dlUit", true); // bij "alle podcasts": deze niet
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
