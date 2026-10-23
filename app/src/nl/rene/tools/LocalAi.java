package nl.rene.tools;

import android.app.ActivityManager;
import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.StatFs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lokale AI: een offline assistent en slim zoeken, helemaal op de telefoon (llama.cpp, zie LlmProcess).
 * De modellen komen eenmalig van Hugging Face; ze worden gecontroleerd met hun SHA-256 (een ander bestand
 * wordt nooit gebruikt). Gesprekken en de zoekindex blijven op de telefoon en gaan niet in Android-backups.
 */
final class LocalAi {

    private LocalAi() { }

    // ---------- modellen ----------

    static final class Model {
        final String id, label, desc, repo, file, sha; final long size; final int ctx; final boolean embed;
        Model(String id, String label, String desc, String repo, String file, long size, String sha, int ctx, boolean embed) {
            this.id = id; this.label = label; this.desc = desc; this.repo = repo; this.file = file; this.size = size; this.sha = sha; this.ctx = ctx; this.embed = embed;
        }
        String url() { return "https://huggingface.co/" + repo + "/resolve/main/" + file; }
    }

    // Controlegetallen van Hugging Face (LFS-oid = SHA-256 en de precieze grootte)
    static final Model SMALL = new Model("klein", "Klein en snel", "Qwen3 1.7B · werkt op de meeste telefoons",
            "bartowski/Qwen_Qwen3-1.7B-GGUF", "Qwen_Qwen3-1.7B-Q4_K_M.gguf", 1_282_439_584L,
            "72c5c3cb38fa32d5256e2fe30d03e7a64c6c79e668ad84057e3bd66e250b24fb", 4096, false);
    static final Model LARGE = new Model("groot", "Groter en slimmer", "Qwen3 4B · beter Nederlands, trager, 8 GB geheugen aangeraden",
            "Qwen/Qwen3-4B-GGUF", "Qwen3-4B-Q4_K_M.gguf", 2_497_280_256L,
            "7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5", 3072, false);
    static final Model EMBED = new Model("zoek", "Slim zoeken", "Qwen3-Embedding 0.6B · zoeken op betekenis",
            "Qwen/Qwen3-Embedding-0.6B-GGUF", "Qwen3-Embedding-0.6B-Q8_0.gguf", 639_150_592L,
            "06507c7b42688469c4e7298b0a1e16deff06caf291cf0a5b278c308249c3e439", 1024, true);
    static final Model[] MODELS = {SMALL, LARGE, EMBED};

    static Model model(String id) { for (Model m : MODELS) if (m.id.equals(id)) return m; return null; }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("localai", Context.MODE_PRIVATE); }

    static File dir(Context c) {
        File d = c.getExternalFilesDir("ai");
        if (d == null) d = new File(c.getFilesDir(), "ai-models");
        d.mkdirs();
        return d;
    }
    static File file(Context c, Model m) { return new File(dir(c), m.file); }
    private static File partFile(Context c, Model m) { return new File(dir(c), m.file + ".download"); }

    /** Klaar en gecontroleerd? (De controle gebeurt één keer, na het downloaden.) */
    static boolean ready(Context c, Model m) {
        File f = file(c, m);
        return f.isFile() && f.length() == m.size && m.sha.equals(prefs(c).getString("ok_" + m.id, ""));
    }

    static Model chatModel(Context c) {
        Model m = model(prefs(c).getString("chat", SMALL.id));
        if (m == null || m.embed) m = SMALL;
        if (!ready(c, m)) for (Model x : new Model[]{SMALL, LARGE}) if (ready(c, x)) return x;
        return m;
    }

    static boolean supported() {
        for (String a : Build.SUPPORTED_64_BIT_ABIS) if ("arm64-v8a".equals(a)) return true;
        return false;
    }

    static long ramBytes(Context c) {
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return mi.totalMem;
        } catch (Throwable t) { return 0; }
    }

    static int threads() { return Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() - 2)); }

    static boolean wifiOnly(Context c) { return prefs(c).getBoolean("wifiOnly", true); }

    // ---------- downloaden (Android DownloadManager: gaat door op de achtergrond en hervat zelf) ----------

    private static DownloadManager dm(Context c) { return (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE); }

    static synchronized String download(Context c, Model m) {
        if (ready(c, m)) return "";
        if (prefs(c).getLong("dl_" + m.id, -1) >= 0 && "running".equals(state(c, m).optString("state"))) return "";
        File d = dir(c);
        long free;
        try { free = new StatFs(d.getPath()).getAvailableBytes(); } catch (Throwable t) { free = Long.MAX_VALUE; }
        long need = m.size + 200L * 1024 * 1024;
        if (free < need) return "Te weinig opslag: nodig " + gb(need) + ", vrij " + gb(free);
        DownloadManager mgr = dm(c);
        if (mgr == null) return "Downloaden kan niet op deze telefoon";
        partFile(c, m).delete();
        try {
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(m.url()))
                    .addRequestHeader("User-Agent", "Tools")
                    .setTitle("AI-model: " + m.label)
                    .setDescription("Voor de offline assistent in Tools")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setAllowedOverMetered(!wifiOnly(c))
                    .setAllowedOverRoaming(false)
                    .setDestinationUri(Uri.fromFile(partFile(c, m)));
            long id = mgr.enqueue(r);
            prefs(c).edit().putLong("dl_" + m.id, id).remove("err_" + m.id).remove("ok_" + m.id).apply();
            return "";
        } catch (Exception e) {
            return "Downloaden lukt niet";
        }
    }

    static String gb(long b) { return String.format(Locale.ROOT, "%.1f GB", b / 1e9).replace('.', ','); }

    /** Weg: lopende download stoppen, bestanden weg. */
    static synchronized void delete(Context c, Model m) {
        long id = prefs(c).getLong("dl_" + m.id, -1);
        DownloadManager mgr = dm(c);
        if (id >= 0 && mgr != null) try { mgr.remove(id); } catch (Throwable ignored) { }
        if (m.embed) closeEmbed(); else if (chat != null && m.file.equals(chat.model.getName())) closeChat();
        file(c, m).delete(); partFile(c, m).delete();
        prefs(c).edit().remove("dl_" + m.id).remove("ok_" + m.id).remove("err_" + m.id).apply();
        if (m.embed) SmartSearch.clear(c);
    }

    private static final java.util.Map<String, Integer> VERIFYING = new java.util.concurrent.ConcurrentHashMap<>();

    /** Status van één model: ready, running (bytes/total), paused (reason), verifying (pct), failed (reason), none. */
    static synchronized JSONObject state(Context c, Model m) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", m.id).put("label", m.label).put("desc", m.desc).put("size", m.size).put("embed", m.embed);
            if (ready(c, m)) return o.put("state", "ready");
            Integer v = VERIFYING.get(m.id);
            if (v != null) return o.put("state", "verifying").put("pct", v);
            String err = prefs(c).getString("err_" + m.id, "");
            long id = prefs(c).getLong("dl_" + m.id, -1);
            DownloadManager mgr = dm(c);
            if (id < 0 || mgr == null) return o.put("state", err.isEmpty() ? "none" : "failed").put("reason", err);
            try (Cursor cur = mgr.query(new DownloadManager.Query().setFilterById(id))) {
                if (cur == null || !cur.moveToFirst()) { prefs(c).edit().remove("dl_" + m.id).apply(); return o.put("state", "none"); }
                int st = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                int reason = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                long done = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                long total = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                o.put("bytes", Math.max(0, done)).put("total", total > 0 ? total : m.size);
                switch (st) {
                    case DownloadManager.STATUS_SUCCESSFUL:
                        startVerify(c, m);
                        return o.put("state", "verifying").put("pct", 0);
                    case DownloadManager.STATUS_RUNNING: return o.put("state", "running");
                    case DownloadManager.STATUS_PENDING: return o.put("state", "queued");
                    case DownloadManager.STATUS_PAUSED:
                        return o.put("state", "paused").put("reason", reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI ? "Wacht op wifi"
                                : reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK ? "Wacht op internet" : reason == DownloadManager.PAUSED_WAITING_TO_RETRY ? "Probeert het zo opnieuw" : "Gepauzeerd");
                    default:
                        prefs(c).edit().remove("dl_" + m.id).putString("err_" + m.id, reason == DownloadManager.ERROR_INSUFFICIENT_SPACE ? "Te weinig opslag" : "Downloaden mislukt").apply();
                        try { mgr.remove(id); } catch (Throwable ignored) { }
                        return o.put("state", "failed").put("reason", prefs(c).getString("err_" + m.id, ""));
                }
            }
        } catch (Exception e) {
            try { o.put("state", "none"); } catch (Exception ignored) { }
            return o;
        }
    }

    /** Na het downloaden één keer de SHA-256 controleren (een paar seconden per GB), dan pas gebruiken. */
    private static void startVerify(final Context c, final Model m) {
        if (VERIFYING.containsKey(m.id)) return;
        VERIFYING.put(m.id, 0);
        new Thread(() -> {
            File part = partFile(c, m), fin = file(c, m);
            String err = "";
            try {
                if (!part.isFile() && fin.isFile() && fin.length() == m.size) part = fin; // al hernoemd
                if (!part.isFile()) throw new Exception("Download is weg; probeer opnieuw");
                if (part.length() != m.size) throw new Exception("Download onvolledig; probeer opnieuw");
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] buf = new byte[1 << 20];
                long got = 0;
                try (InputStream in = new FileInputStream(part)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        md.update(buf, 0, n); got += n;
                        VERIFYING.put(m.id, (int) (got * 100 / m.size));
                    }
                }
                StringBuilder hex = new StringBuilder();
                for (byte b : md.digest()) hex.append(String.format(Locale.ROOT, "%02x", b));
                if (!hex.toString().equals(m.sha)) throw new Exception("Download beschadigd; probeer opnieuw");
                if (part != fin) { fin.delete(); if (!part.renameTo(fin)) throw new Exception("Opslaan lukt niet"); }
                prefs(c).edit().putString("ok_" + m.id, m.sha).remove("dl_" + m.id).remove("err_" + m.id).apply();
            } catch (Throwable t) {
                err = t.getMessage() == null ? "Controleren mislukt" : t.getMessage();
                part.delete();
                prefs(c).edit().remove("dl_" + m.id).putString("err_" + m.id, err).apply();
            } finally {
                VERIFYING.remove(m.id);
                MainActivity.jsAll("onAiModels", "");
            }
        }, "ai-verify").start();
    }

    // ---------- de motoren (één voor het gesprek, één voor zoeken) ----------

    static final ExecutorService AI = Executors.newSingleThreadExecutor(r -> new Thread(r, "ai"));
    private static volatile LlmProcess chat, embed;
    private static volatile boolean cancel;
    private static volatile boolean busy;

    static boolean cancelled() { return cancel; }
    static boolean busy() { return busy; }
    static String loaded() { LlmProcess p = chat; return p != null && p.alive() ? (p.model.getName().equals(LARGE.file) ? LARGE.id : SMALL.id) : ""; }

    static synchronized void closeChat() { if (chat != null) { chat.destroy(); chat = null; } }
    static synchronized void closeEmbed() { if (embed != null) { embed.destroy(); embed = null; } }

    private static LlmProcess chatEngine(Context c, Model m) throws Exception {
        LlmProcess p = chat;
        if (p != null && p.alive() && p.model.equals(file(c, m))) return p;
        closeChat();
        if (m == LARGE) closeEmbed(); // geheugen vrijmaken voor het grote model
        p = LlmProcess.start(c, file(c, m), m.ctx, threads(), false, true);
        chat = p;
        return p;
    }

    private static LlmProcess embedEngine(Context c) throws Exception {
        LlmProcess p = embed;
        if (p != null && p.alive()) return p;
        if (!ready(c, EMBED)) throw new Exception("Download eerst het model voor slim zoeken");
        LlmProcess q = chat;
        if (q != null && q.model.getName().equals(LARGE.file)) closeChat();
        p = LlmProcess.start(c, file(c, EMBED), EMBED.ctx, threads(), true, false);
        embed = p;
        return p;
    }

    /** Teksten door het zoekmodel (op de AI-thread aanroepen). */
    static List<float[]> embed(Context c, List<String> texts, int dims) throws Exception {
        LlmProcess p = embedEngine(c);
        final AtomicReference<JSONObject> res = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        p.listen(o -> { if (o.has("e") || o.has("error")) { res.set(o); done.countDown(); } });
        JSONArray a = new JSONArray();
        for (String t : texts) a.put(t.length() > 4000 ? t.substring(0, 4000) : t);
        p.send(new JSONObject().put("op", "embed").put("texts", a).put("dims", dims));
        if (!done.await(120, TimeUnit.SECONDS)) throw new Exception("Het zoekmodel reageert niet");
        JSONObject o = res.get();
        if (o.has("error")) throw new Exception(o.optString("error"));
        JSONArray e = o.optJSONArray("e");
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            JSONArray r = e == null ? null : e.optJSONArray(i);
            float[] v = new float[dims];
            if (r != null) for (int j = 0; j < Math.min(dims, r.length()); j++) v[j] = (float) r.optDouble(j);
            out.add(v);
        }
        p.lastUse = System.currentTimeMillis();
        return out;
    }

    /** Waar de assistent naartoe praat (de interface). */
    interface Sink {
        void status(String text);
        void sources(JSONArray items);
        void token(String t);
        void done(JSONObject info);
        void error(String msg);
    }

    static String systemPrompt() {
        String today = new java.text.SimpleDateFormat("EEEE d MMMM yyyy", new Locale("nl", "NL")).format(new java.util.Date());
        return "Je bent de offline assistent in de app Tools. Je draait helemaal op de telefoon van de gebruiker, zonder internet. "
                + "Antwoord in het Nederlands, tenzij de gebruiker om een andere taal vraagt. Wees duidelijk, behulpzaam en beknopt; "
                + "gebruik opsommingen als dat helpt. Je hebt geen internet en geen actuele informatie: zeg dat eerlijk als dat nodig is, "
                + "en verzin geen feiten. Vandaag is het " + today + ".";
    }

    /** Context uit slim zoeken, vóór de vraag gezet. Zuiver (getest). */
    static String withSources(String question, List<String[]> sources) {
        if (sources == null || sources.isEmpty()) return question;
        StringBuilder sb = new StringBuilder("Stukken uit mijn eigen notities en gesprekken (gebruik ze alleen als ze helpen, en noem dan het nummer, bijvoorbeeld [1]):\n");
        int i = 1;
        for (String[] s : sources) {
            String body = s[1].length() > 900 ? s[1].substring(0, 900) + "…" : s[1];
            sb.append('[').append(i++).append("] ").append(s[0]).append(":\n").append(body.trim()).append("\n\n");
        }
        return sb.append("Mijn vraag: ").append(question).toString();
    }

    /** Een antwoord maken. req = {messages:[{role, content}], notes:bool}. Draait op de AI-thread. */
    static void chat(final Context c, final JSONObject req, final Sink sink) {
        cancel = false;
        AI.execute(() -> {
            busy = true;
            try {
                if (!supported()) throw new Exception("De lokale AI werkt alleen op telefoons met een 64-bits ARM-processor");
                Model m = chatModel(c);
                if (!ready(c, m)) throw new Exception("Download eerst een taalmodel");
                JSONArray in = req.optJSONArray("messages");
                if (in == null || in.length() == 0) throw new Exception("Geen vraag");
                JSONArray msgs = new JSONArray().put(new JSONObject().put("role", "system").put("content", systemPrompt()));
                int from = Math.max(0, in.length() - 24);
                for (int i = from; i < in.length(); i++) {
                    JSONObject x = in.optJSONObject(i);
                    if (x == null) continue;
                    String role = "assistant".equals(x.optString("role")) ? "assistant" : "user";
                    String content = x.optString("content");
                    if (content.length() > 12000) content = content.substring(0, 12000);
                    msgs.put(new JSONObject().put("role", role).put("content", content));
                }
                JSONObject last = msgs.getJSONObject(msgs.length() - 1);
                if (!"user".equals(last.optString("role"))) throw new Exception("Geen vraag");
                // Met mijn notities: eerst slim zoeken, dan de stukken meegeven
                if (req.optBoolean("notes") && ready(c, EMBED)) {
                    sink.status("Zoeken in je notities en gesprekken…");
                    try {
                        JSONArray hits = SmartSearch.search(c, last.optString("content"), 4);
                        if (hits.length() > 0) {
                            List<String[]> src = new ArrayList<>();
                            for (int i = 0; i < hits.length(); i++) {
                                JSONObject h = hits.getJSONObject(i);
                                src.add(new String[]{("tx".equals(h.optString("kind")) ? "Gesprek met " : "Notitie ") + "\"" + h.optString("title") + "\"", h.optString("snip")});
                            }
                            last.put("content", withSources(last.optString("content"), src));
                            sink.sources(hits);
                        }
                    } catch (Exception e) { sink.status("Zoeken lukte niet: " + e.getMessage()); }
                    if (cancel) { sink.done(new JSONObject().put("cancelled", true)); return; }
                    if (m == LARGE) closeEmbed();
                }
                boolean starting = chat == null || !chat.alive() || !chat.model.equals(file(c, m));
                if (starting) sink.status("Model laden…");
                LlmProcess p = chatEngine(c, m);
                if (cancel) { sink.done(new JSONObject().put("cancelled", true)); return; }
                sink.status("");
                final CountDownLatch done = new CountDownLatch(1);
                p.listen(o -> {
                    if (o.has("t")) sink.token(o.optString("t"));
                    else if (o.optBoolean("done")) { sink.done(o); done.countDown(); }
                    else if (o.has("error")) { sink.error(o.optString("error")); done.countDown(); }
                });
                p.send(new JSONObject().put("op", "chat").put("messages", msgs).put("max", 1200)
                        .put("temp", 0.7).put("top_p", 0.8).put("top_k", 20));
                while (!done.await(500, TimeUnit.MILLISECONDS)) {
                    if (!p.alive()) { sink.error("De AI-motor is gestopt"); break; }
                }
                p.lastUse = System.currentTimeMillis();
            } catch (Throwable t) {
                sink.error(t.getMessage() == null ? "Er ging iets mis" : t.getMessage());
            } finally {
                busy = false;
                scheduleIdle();
            }
        });
    }

    static void cancel() {
        cancel = true;
        LlmProcess p = chat;
        if (p != null && p.alive()) try { p.send(new JSONObject().put("op", "cancel")); } catch (Throwable ignored) { }
        LlmProcess q = embed;
        if (q != null && q.alive()) try { q.send(new JSONObject().put("op", "cancel")); } catch (Throwable ignored) { }
    }

    /** Slim zoeken vanuit de interface (op de AI-thread, na een eventueel lopend antwoord). */
    static void search(final Context c, final String q, final java.util.function.Consumer<JSONObject> cb) {
        AI.execute(() -> {
            cancel = false;
            JSONObject o = new JSONObject();
            try {
                o.put("q", q);
                if (!ready(c, EMBED)) o.put("need", true);
                else o.put("items", SmartSearch.search(c, q, 6));
            } catch (Throwable t) {
                try { o.put("error", t.getMessage() == null ? "Zoeken lukte niet" : t.getMessage()); } catch (Exception ignored) { }
            } finally { scheduleIdle(); }
            cb.accept(o);
        });
    }

    /** Index (opnieuw) opbouwen; voortgang via cb. */
    static void index(final Context c, final boolean force, final java.util.function.Consumer<JSONObject> cb) {
        cancel = false;
        AI.execute(() -> {
            try {
                SmartSearch.update(c, force, (d, t) -> {
                    try { cb.accept(new JSONObject().put("busy", true).put("done", d).put("total", t)); } catch (Exception ignored) { }
                });
                cb.accept(SmartSearch.info(c));
            } catch (Throwable t) {
                try { cb.accept(SmartSearch.info(c).put("error", t.getMessage() == null ? "Bijwerken lukte niet" : t.getMessage())); } catch (Exception ignored) { }
            } finally { scheduleIdle(); }
        });
    }

    // ---------- na een paar minuten niets doen: motoren stoppen (geheugen vrij) ----------

    private static final long IDLE_MS = 4 * 60_000L;
    private static Thread idle;

    private static synchronized void scheduleIdle() {
        if (idle != null && idle.isAlive()) return;
        idle = new Thread(() -> {
            try {
                while (true) {
                    Thread.sleep(30_000);
                    long now = System.currentTimeMillis();
                    LlmProcess p = chat, q = embed;
                    if (!busy && p != null && now - p.lastUse > IDLE_MS) closeChat();
                    if (!busy && q != null && now - q.lastUse > IDLE_MS) closeEmbed();
                    if (chat == null && embed == null) return;
                }
            } catch (Throwable ignored) { }
        }, "ai-idle");
        idle.setDaemon(true);
        idle.start();
    }

    // ---------- gesprekken (alleen op de telefoon) ----------

    static File chatsFile(Context c) { File d = new File(c.getFilesDir(), "ai"); d.mkdirs(); return new File(d, "chats.json"); }

    static synchronized JSONArray chats(Context c) {
        try { File f = chatsFile(c); return f.isFile() ? new JSONArray(Contacts.readText(f, false)) : new JSONArray(); } catch (Exception e) { return new JSONArray(); }
    }

    /** Overzicht: nieuwste eerst, zonder de berichten. */
    static String chatList(Context c) throws Exception {
        JSONArray a = chats(c), out = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.getJSONObject(i);
            JSONArray m = x.optJSONArray("msgs");
            out.put(new JSONObject().put("id", x.optString("id")).put("title", x.optString("title")).put("t", x.optLong("t")).put("n", m == null ? 0 : m.length()));
        }
        return out.toString();
    }

    static String chatGet(Context c, String id) {
        JSONArray a = chats(c);
        for (int i = 0; i < a.length(); i++) { JSONObject x = a.optJSONObject(i); if (x != null && id.equals(x.optString("id"))) return x.toString(); }
        return "";
    }

    static synchronized String chatSave(Context c, JSONObject chat) throws Exception {
        String id = chat.optString("id");
        if (!id.matches("[A-Za-z0-9_-]{1,40}")) id = Long.toString(System.currentTimeMillis(), 36);
        JSONArray msgs = chat.optJSONArray("msgs");
        if (msgs == null) msgs = new JSONArray();
        JSONArray keep = new JSONArray();
        for (int i = Math.max(0, msgs.length() - 200); i < msgs.length(); i++) keep.put(msgs.get(i));
        String title = chat.optString("title").trim();
        if (title.length() > 80) title = title.substring(0, 80);
        JSONObject x = new JSONObject().put("id", id).put("title", title.isEmpty() ? "Gesprek" : title).put("t", System.currentTimeMillis()).put("msgs", keep);
        JSONArray a = chats(c), out = new JSONArray().put(x);
        for (int i = 0; i < a.length() && out.length() < 100; i++) { JSONObject y = a.optJSONObject(i); if (y != null && !id.equals(y.optString("id"))) out.put(y); }
        Contacts.writeText(chatsFile(c), out.toString(), false);
        return id;
    }

    static synchronized void chatDelete(Context c, String id) throws Exception {
        JSONArray a = chats(c), out = new JSONArray();
        for (int i = 0; i < a.length(); i++) { JSONObject y = a.optJSONObject(i); if (y != null && !id.equals(y.optString("id")) && !"*".equals(id)) out.put(y); }
        Contacts.writeText(chatsFile(c), out.toString(), false);
    }

    /** Alles voor de interface in één keer. */
    static JSONObject info(Context c) throws Exception {
        JSONObject o = new JSONObject();
        o.put("supported", supported()).put("engine", LlmProcess.binary(c).isFile()).put("ram", ramBytes(c));
        JSONArray ms = new JSONArray();
        for (Model m : MODELS) ms.put(state(c, m));
        o.put("models", ms).put("chat", chatModel(c).id).put("wifiOnly", wifiOnly(c)).put("notes", prefs(c).getBoolean("notes", true))
                .put("loaded", loaded()).put("busy", busy);
        if (ready(c, EMBED)) o.put("index", SmartSearch.info(c));
        return o;
    }
}
