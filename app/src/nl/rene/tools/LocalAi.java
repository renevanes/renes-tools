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
        final String id, label, desc, repo, commit, file, sha; final long size; final int ctx; final boolean embed;
        Model(String id, String label, String desc, String repo, String commit, String file, long size, String sha, int ctx, boolean embed) {
            this.id = id; this.label = label; this.desc = desc; this.repo = repo; this.commit = commit; this.file = file; this.size = size; this.sha = sha; this.ctx = ctx; this.embed = embed;
        }
        /** Vaste versie (commit) op Hugging Face: een latere wijziging daar verandert niets aan wat we downloaden. */
        String url() { return "https://huggingface.co/" + repo + "/resolve/" + commit + "/" + file; }
    }

    // Controlegetallen van Hugging Face (LFS-oid = SHA-256 en de precieze grootte)
    static final Model SMALL = new Model("klein", "Klein en snel", "Qwen3 1.7B · werkt op de meeste telefoons",
            "bartowski/Qwen_Qwen3-1.7B-GGUF", "822702a8257cf1b05a27d73f775d8606cf2e33a4", "Qwen_Qwen3-1.7B-Q4_K_M.gguf", 1_282_439_584L,
            "72c5c3cb38fa32d5256e2fe30d03e7a64c6c79e668ad84057e3bd66e250b24fb", 4096, false);
    static final Model LARGE = new Model("groot", "Groter en slimmer", "Qwen3 4B · beter Nederlands, trager, 8 GB geheugen aangeraden",
            "Qwen/Qwen3-4B-GGUF", "a9a60d009fa7ff9606305047c2bf77ac25dbec49", "Qwen3-4B-Q4_K_M.gguf", 2_497_280_256L,
            "7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5", 3072, false);
    static final Model EMBED = new Model("zoek", "Slim zoeken", "Qwen3-Embedding 0.6B · zoeken op betekenis",
            "Qwen/Qwen3-Embedding-0.6B-GGUF", "d20cf9c16f82914a21dbd9c645f56895fb1d7750", "Qwen3-Embedding-0.6B-Q8_0.gguf", 639_150_592L,
            "06507c7b42688469c4e7298b0a1e16deff06caf291cf0a5b278c308249c3e439", 1024, true);
    static final Model[] MODELS = {SMALL, LARGE, EMBED};

    static Model model(String id) { for (Model m : MODELS) if (m.id.equals(id)) return m; return null; }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("localai", Context.MODE_PRIVATE); }

    /** Waar de DownloadManager naartoe schrijft (die kan niet in de privémap van de app). */
    static File dlDir(Context c) {
        File d = c.getExternalFilesDir("ai");
        if (d == null) d = modelDir(c);
        d.mkdirs();
        return d;
    }
    /** De modellen zelf: in de privémap van de app, waar geen andere app bij kan (ook niet op oudere Android). */
    static File modelDir(Context c) { File d = new File(c.getFilesDir(), "ai-models"); d.mkdirs(); return d; }
    static File file(Context c, Model m) { return new File(modelDir(c), m.file); }
    private static File partFile(Context c, Model m) { return new File(dlDir(c), m.file + ".download"); }
    private static File tmpFile(Context c, Model m) { return new File(modelDir(c), m.file + ".tmp"); }

    /** Genoeg geheugen om het taal- en het zoekmodel tegelijk te laden? (8 GB-telefoons melden ±7,4 GB) */
    static boolean bigRam(Context c) { return ramBytes(c) >= 7_000_000_000L; }

    /** Klaar en gecontroleerd? (De controle gebeurt bij het kopiëren naar de privémap, na het downloaden.) */
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

    static int threads() { return threadsFor(cfg(LocalAi.appCtx, "power"), Runtime.getRuntime().availableProcessors()); }

    // ---------- instellingen van de assistent (zuiver rekenwerk getest in LocalAiConfigTest) ----------

    /** Toegestane waarden per instelling; de eerste is de standaard. Tekstvelden (about, instr) staan er niet in. */
    static final String[][] CFG = {
            {"style", "normaal", "kort", "uitgebreid"},
            {"creative", "normaal", "precies", "creatief"},
            {"lang", "nl", "en", "auto"},
            {"history", "24", "8", "48"},
            {"power", "normaal", "zuinig", "max"},
            {"idle", "4", "1", "15"},
            {"mem", "aan", "uit"},                  // geheugen gebruiken in antwoorden
            {"learn", "vragen", "vanzelf", "uit"},  // leren uit gesprekken: eerst vragen, vanzelf, of niet
    };
    static final int TEXT_MAX = 1000;

    /** Gegevens die de assistent (en slim zoeken) mag gebruiken. Notities en gesprekken staan standaard aan, de rest uit. */
    static final String[] SOURCES = {"notes", "tx", "sms", "wa", "calls", "contacts"};
    static boolean srcDefault(String kind) { return "notes".equals(kind) || "tx".equals(kind); }
    static boolean srcOn(Context c, String kind) {
        if (c == null) return srcDefault(kind);
        boolean known = false;
        for (String k : SOURCES) if (k.equals(kind)) known = true;
        return known && prefs(c).getBoolean("src_" + kind, srcDefault(kind));
    }
    static volatile Context appCtx;

    /** Geldige waarde voor een instelling, anders null. */
    static String cfgValid(String name, String value) {
        if ("about".equals(name) || "instr".equals(name)) return cleanText(value, TEXT_MAX);
        for (String[] r : CFG) if (r[0].equals(name)) { for (int i = 1; i < r.length; i++) if (r[i].equals(value)) return value; return null; }
        return null;
    }
    static String cfgDefault(String name) { for (String[] r : CFG) if (r[0].equals(name)) return r[1]; return ""; }
    /** Instelling opslaan; geeft "" of een melding. */
    static String cfgSet(Context c, String name, String value) {
        String ok = cfgValid(name, value);
        if (ok == null) return "Ongeldige instelling";
        prefs(c).edit().putString("cfg_" + name, ok).apply();
        // Rekenkracht geldt vanaf de volgende start van een motor (chatEngine/embedEngine kijken naar het aantal kernen):
        // een lopend antwoord of het bijwerken van de zoekindex wordt dus niet afgebroken
        return "";
    }
    static void cfgReset(Context c) {
        SharedPreferences.Editor e = prefs(c).edit();
        for (String[] r : CFG) e.remove("cfg_" + r[0]);
        e.remove("cfg_about").remove("cfg_instr").apply();
    }
    static String cfg(Context c, String name) {
        if (c == null) return cfgDefault(name);
        String v = prefs(c).getString("cfg_" + name, null);
        String ok = v == null ? null : cfgValid(name, v);
        return ok == null ? cfgDefault(name) : ok;
    }

    /** Tekst die de gebruiker zelf invult: zonder stuurtekens, ingekort, zonder witruimte rondom. */
    static String cleanText(String s, int max) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < max; i++) {
            char ch = s.charAt(i);
            if (ch == '\n' || ch == '\t' || !Character.isISOControl(ch)) b.append(ch);
        }
        return b.toString().trim();
    }

    static int maxTokens(String style) { return "kort".equals(style) ? 400 : "uitgebreid".equals(style) ? 2400 : 1200; }
    static double temperature(String creative) { return "precies".equals(creative) ? 0.3 : "creatief".equals(creative) ? 1.0 : 0.7; }
    static int historyFor(String h) { try { int n = Integer.parseInt(h); return n == 8 || n == 48 ? n : 24; } catch (Exception e) { return 24; } }
    static long idleMs(String m) { try { int n = Integer.parseInt(m); return (n == 1 || n == 15 ? n : 4) * 60_000L; } catch (Exception e) { return 4 * 60_000L; } }
    /** Rekenkracht: zuinig = 2 kernen (koeler, minder accu), normaal = tot 4, maximaal = tot 6. Altijd 2 kernen vrij:
     *  op een telefoon zijn dat meestal de trage zuinige kernen, en die maken het model alleen maar langzamer. */
    static int threadsFor(String power, int cores) {
        if ("zuinig".equals(power)) return 2;
        if ("max".equals(power)) return Math.max(2, Math.min(6, cores - 2));
        return Math.max(2, Math.min(4, cores - 2));
    }

    /** De vaste instructie voor het model, met de keuzes van de gebruiker. */
    static String buildSystem(String today, String style, String lang, String about, String instr) { return buildSystem(today, style, lang, about, instr, null, ""); }

    /** mem = herinneringen (nieuwste eerst, al ingekort), note = wat er net met het geheugen gebeurde ("onthoud …"). */
    static String buildSystem(String today, String style, String lang, String about, String instr, List<String> mem, String note) {
        StringBuilder s = new StringBuilder("Je bent de offline assistent in de app Tools. Je draait helemaal op de telefoon van de gebruiker, zonder internet. ");
        if ("en".equals(lang)) s.append("Antwoord altijd in het Engels (English), ook als de vraag in een andere taal is. ");
        else if ("auto".equals(lang)) s.append("Antwoord in dezelfde taal als de vraag. ");
        else s.append("Antwoord in het Nederlands, tenzij de gebruiker om een andere taal vraagt. ");
        if ("kort".equals(style)) s.append("Houd je antwoorden kort en bondig: een paar zinnen, alleen het belangrijkste. ");
        else if ("uitgebreid".equals(style)) s.append("Geef uitgebreide, grondige antwoorden met uitleg, voorbeelden en een duidelijke opbouw. ");
        else s.append("Wees duidelijk, behulpzaam en beknopt; gebruik opsommingen als dat helpt. ");
        s.append("Je hebt geen internet en geen actuele informatie: zeg dat eerlijk als dat nodig is, en verzin geen feiten. Vandaag is het ").append(today).append('.');
        if (about != null && !about.isEmpty()) s.append("\n\nOver de gebruiker (door de gebruiker zelf ingevuld):\n").append(about);
        if (instr != null && !instr.isEmpty()) s.append("\n\nZo wil de gebruiker dat je antwoordt:\n").append(instr);
        String ms = AiMemory.section(mem);
        if (!ms.isEmpty()) s.append("\n\n").append(ms);
        if (note != null && !note.isEmpty()) s.append("\n\n").append(note);
        return s.toString();
    }

    static boolean wifiOnly(Context c) { return prefs(c).getBoolean("wifiOnly", true); }

    // ---------- downloaden (Android DownloadManager: gaat door op de achtergrond en hervat zelf) ----------

    private static DownloadManager dm(Context c) { return (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE); }

    static synchronized String download(Context c, Model m) {
        if (ready(c, m)) return "";
        if (prefs(c).getLong("dl_" + m.id, -1) >= 0 && "running".equals(state(c, m).optString("state"))) return "";
        long free;
        try { free = new StatFs(c.getFilesDir().getPath()).getAvailableBytes(); } catch (Throwable t) { free = Long.MAX_VALUE; }
        // Downloaden en daarna kopiëren naar de privémap: even twee keer de grootte nodig
        long need = 2 * m.size + 200L * 1024 * 1024;
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

    /** Weg: lopende download stoppen, motor stoppen, bestanden weg (de zoekindex ruimt de AI-thread op). */
    static void delete(final Context c, final Model m) {
        long id;
        synchronized (LocalAi.class) {
            id = prefs(c).getLong("dl_" + m.id, -1);
            prefs(c).edit().remove("dl_" + m.id).remove("ok_" + m.id).remove("err_" + m.id).apply();
        }
        DownloadManager mgr = dm(c);
        if (id >= 0 && mgr != null) try { mgr.remove(id); } catch (Throwable ignored) { }
        if (VERIFYING.containsKey(m.id)) STOP_VERIFY.add(m.id); // het kopiëren stopt en ruimt zelf op
        LlmProcess p = chat;
        if (m.embed) { SmartSearch.stopUpdate = true; closeEmbed(); } // een lopend antwoord (zonder notities) loopt gewoon door
        else if (p != null && p.model.equals(file(c, m))) { cancel(); closeChat(); }
        file(c, m).delete(); partFile(c, m).delete(); tmpFile(c, m).delete();
        // Na een eventueel lopende update (die stopt nu): de index weg
        if (m.embed) IDX.execute(() -> { try { SmartSearch.clear(c); } catch (Throwable ignored) { } });
    }

    private static final java.util.Map<String, Integer> VERIFYING = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> STOP_VERIFY = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

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

    /**
     * Na het downloaden: kopiëren naar de privémap en tegelijk de SHA-256 berekenen. Zo is precies het bestand dat
     * gecontroleerd is ook het bestand dat gebruikt wordt (een andere app kan het daarna niet meer vervangen).
     */
    private static void startVerify(final Context c, final Model m) {
        if (VERIFYING.containsKey(m.id)) return;
        VERIFYING.put(m.id, 0);
        new Thread(() -> {
            File part = partFile(c, m), tmp = tmpFile(c, m), fin = file(c, m);
            try {
                if (!part.isFile()) throw new Exception("Download is weg; probeer opnieuw");
                if (part.length() != m.size) throw new Exception("Download onvolledig; probeer opnieuw");
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] buf = new byte[1 << 20];
                long got = 0;
                try (InputStream in = new FileInputStream(part); java.io.OutputStream out = new java.io.FileOutputStream(tmp)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (STOP_VERIFY.contains(m.id)) throw new Exception("Gestopt");
                        md.update(buf, 0, n); out.write(buf, 0, n); got += n;
                        VERIFYING.put(m.id, (int) (got * 100 / m.size));
                    }
                }
                StringBuilder hex = new StringBuilder();
                for (byte b : md.digest()) hex.append(String.format(Locale.ROOT, "%02x", b));
                if (got != m.size || !hex.toString().equals(m.sha)) throw new Exception("Download beschadigd; probeer opnieuw");
                if (STOP_VERIFY.contains(m.id)) throw new Exception("Gestopt");
                fin.delete();
                if (!tmp.renameTo(fin)) throw new Exception("Opslaan lukt niet");
                prefs(c).edit().putString("ok_" + m.id, m.sha).remove("dl_" + m.id).remove("err_" + m.id).apply();
            } catch (Throwable t) {
                String err = t.getMessage() == null ? "Controleren mislukt" : t.getMessage();
                if (t instanceof java.io.IOException && err.toLowerCase(Locale.ROOT).contains("space")) err = "Te weinig opslag om het model te bewaren";
                tmp.delete();
                if (STOP_VERIFY.contains(m.id)) prefs(c).edit().remove("dl_" + m.id).remove("err_" + m.id).remove("ok_" + m.id).apply(); // weggehaald: geen foutmelding
                else prefs(c).edit().remove("dl_" + m.id).putString("err_" + m.id, err).apply();
            } finally {
                part.delete(); // de download zelf (in de gedeelde map) is niet meer nodig
                STOP_VERIFY.remove(m.id);
                VERIFYING.remove(m.id);
                MainActivity.jsAll("onAiModels", "");
            }
        }, "ai-verify").start();
    }

    // ---------- de motoren (één voor het gesprek, één voor zoeken) ----------

    static final ExecutorService AI = Executors.newSingleThreadExecutor(r -> new Thread(r, "ai"));
    /** De zoekindex bijwerken (kan bij sms en WhatsApp minuten duren) op een eigen thread: de assistent wacht daar niet op. */
    static final ExecutorService IDX = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "ai-index"); t.setPriority(Thread.MIN_PRIORITY); return t; });
    /** Eén vraag tegelijk aan het zoekmodel (de index en een zoekvraag delen hem). Volgorde: eerst EMB, dan LocalAi.class. */
    private static final java.util.concurrent.locks.ReentrantLock EMB = new java.util.concurrent.locks.ReentrantLock();
    /** Het gesprek heeft het taalmodel nodig: de index wacht dan even (anders delen ze de processor, of op een
     *  kleinere telefoon zelfs het geheugen). */
    private static volatile boolean chatPhase;
    /** Moet het bijwerken van de index nu wachten? Tijdens een antwoord altijd; op een kleinere telefoon ook nog even
     *  daarna (anders moet het taalmodel voor een vervolgvraag opnieuw laden). */
    static boolean chatWaits(Context c) {
        if (chatPhase) return true;
        LlmProcess p = chat;
        return p != null && p.alive() && !bigRam(c) && System.currentTimeMillis() - p.lastUse < 60_000L;
    }
    private static volatile LlmProcess chat, embed;
    private static volatile boolean cancel;
    private static volatile long cancelAt; // wanneer er om stoppen gevraagd werd (0 = niet)
    /** Zo lang mag de motor na "stoppen" nog doorgaan; daarna wordt hij beëindigd (hij hangt dan). */
    static final long STUCK_MS = 25_000L; // ruim: één lange batch van een groot model op een trage telefoon is geen hang
    /** Hangt de motor: om stoppen gevraagd en na STUCK_MS nog geen einde? Zuiver (getest). */
    static boolean stuck(long cancelAt, long now) { return cancelAt > 0 && now - cancelAt >= STUCK_MS; }
    private static final java.util.concurrent.atomic.AtomicLong REQ = new java.util.concurrent.atomic.AtomicLong();

    /** Hooguit één antwoord tegelijk: wie vraagt, neemt de beurt (al bij het aanvragen, niet pas op de AI-thread). Zuiver (getest). */
    static final class Gate {
        private final java.util.concurrent.atomic.AtomicLong owner = new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.atomic.AtomicLong seq = new java.util.concurrent.atomic.AtomicLong();
        /** Een nummer voor deze beurt, of 0 als er al een loopt. */
        long enter() { long id = seq.incrementAndGet(); return owner.compareAndSet(0, id) ? id : 0; }
        /** Alleen de eigen beurt vrijgeven (een oude beurt mag een nieuwere niet vrijgeven). */
        void leave(long id) { if (id != 0) owner.compareAndSet(id, 0); }
        boolean busy() { return owner.get() != 0; }
    }
    private static final Gate GATE = new Gate();

    static boolean cancelled() { return cancel; }
    static boolean busy() { return GATE.busy(); }
    static String loaded() { LlmProcess p = chat; return p != null && p.alive() ? (p.model.getName().equals(LARGE.file) ? LARGE.id : SMALL.id) : ""; }

    static synchronized void closeChat() { if (chat != null) { chat.destroy(); chat = null; } }
    static synchronized void closeEmbed() { if (embed != null) { embed.destroy(); embed = null; } }
    /** Het zoekmodel stoppen na de vraag die er nu loopt (een stuk van de index wordt dus afgemaakt). */
    static void closeEmbedSafe() { EMB.lock(); try { closeEmbed(); } finally { EMB.unlock(); } }

    private static LlmProcess chatEngine(Context c, Model m) throws Exception {
        LlmProcess p = chat;
        if (p != null && p.alive() && p.model.equals(file(c, m)) && p.threads == threads()) return p;
        closeChat();
        if (m == LARGE && !bigRam(c)) closeEmbedSafe(); // geheugen vrijmaken voor het grote model
        p = LlmProcess.start(c, file(c, m), m.ctx, threads(), false, true);
        chat = p;
        return p;
    }

    private static LlmProcess embedEngine(Context c) throws Exception {
        LlmProcess p = embed;
        if (p != null && p.alive() && p.threads == threads()) return p;
        closeEmbed();
        if (!ready(c, EMBED)) throw new Exception("Download eerst het model voor slim zoeken");
        LlmProcess q = chat;
        if (q != null && !bigRam(c)) closeChat(); // niet twee modellen tegelijk in een kleiner geheugen
        p = LlmProcess.start(c, file(c, EMBED), EMBED.ctx, threads(), true, false);
        embed = p;
        return p;
    }

    /** Teksten door het zoekmodel (voor een zoekvraag). */
    static List<float[]> embed(Context c, List<String> texts, int dims) throws Exception { return embed(c, texts, dims, false); }

    /** background = voor de index: wacht zolang het gesprek het geheugen nodig heeft (kleinere telefoons). */
    static List<float[]> embed(Context c, List<String> texts, int dims, boolean background) throws Exception {
        while (true) {
            EMB.lock();
            if (!background || !chatWaits(c)) break;
            EMB.unlock();
            if (SmartSearch.stopUpdate) throw new Exception("Gestopt");
            Thread.sleep(500);
        }
        try { return embedLocked(c, texts, dims); } finally { EMB.unlock(); }
    }

    private static List<float[]> embedLocked(Context c, List<String> texts, int dims) throws Exception {
        LlmProcess p = embedEngine(c);
        final long rid = REQ.incrementAndGet();
        final AtomicReference<JSONObject> res = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        // Alleen het antwoord op déze vraag (een laat antwoord op een eerdere telt niet)
        p.listen(o -> { if ((o.optLong("id") == rid && (o.has("e") || o.has("error"))) || o.optBoolean("gone")) { res.set(o); done.countDown(); } });
        try {
            JSONArray a = new JSONArray();
            for (String t : texts) a.put(t.length() > 4000 ? t.substring(0, 4000) : t);
            p.send(new JSONObject().put("op", "embed").put("id", rid).put("texts", a).put("dims", dims));
            int waited = 0;
            while (!done.await(500, TimeUnit.MILLISECONDS)) {
                if (!p.alive()) throw new Exception("Het zoekmodel is gestopt");
                if (++waited > 240) throw new Exception("Het zoekmodel reageert niet");
            }
        } finally { p.listen(null); }
        JSONObject o = res.get();
        if (o.has("error")) throw new Exception(o.optString("error"));
        JSONArray e = o.optJSONArray("e");
        if (e == null || e.length() != texts.size()) throw new Exception("Onverwacht antwoord van het zoekmodel");
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            JSONArray r = e.optJSONArray(i);
            if (r == null || r.length() != dims) throw new Exception("Onverwacht antwoord van het zoekmodel");
            float[] v = new float[dims];
            for (int j = 0; j < dims; j++) v[j] = (float) r.optDouble(j);
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
        /** Iets met het geheugen: {saved:[{id,text}]}, {ask:[tekst]}, {forgot}, {thinking}, {none}, {error}. */
        void memory(JSONObject o);
    }

    static String systemPrompt(Context c, int ctx, List<String> mem, String note) {
        String today = new java.text.SimpleDateFormat("EEEE d MMMM yyyy", new Locale("nl", "NL")).format(new java.util.Date());
        String[] p = persFit(cfg(c, "about"), cfg(c, "instr"), ctx);
        return buildSystem(today, cfg(c, "style"), cfg(c, "lang"), p[0], p[1], mem, note);
    }

    /** Wat er met het geheugen gebeurde, voor het model (zodat het dat kort bevestigt). Zuiver (getest). */
    static String memNote(String remembered, String forgot, boolean forgetTried, boolean memOff) {
        if (memOff && (remembered != null || forgetTried))
            return "De gebruiker vroeg iets te onthouden of te vergeten, maar het geheugen staat uit (Lokale AI → Instellingen → Geheugen). Er is niets veranderd; zeg dat kort.";
        if (remembered != null) return "De gebruiker vroeg je net iets te onthouden. Dat staat nu in je geheugen: \"" + remembered + "\". Bevestig dat kort.";
        if (forgetTried) return forgot != null && !forgot.isEmpty()
                ? "De gebruiker vroeg je iets te vergeten. Dit staat niet meer in je geheugen: \"" + forgot + "\". Bevestig dat kort."
                : "De gebruiker vroeg je iets te vergeten, maar daar stond niets over in je geheugen. Zeg dat kort.";
        return "";
    }

    /** Hoeveel tekens "Over mij" en "Instructies" samen in het geheugen van het model mogen (de rest is voor het gesprek). */
    static int persLimit(int ctx) { return ctx >= 4096 ? 2000 : ctx >= 3072 ? 1200 : 600; }

    /** Persoonlijke teksten inkorten (naar verhouding) als ze samen te lang zijn voor dit model. Zuiver (getest). */
    static String[] persFit(String about, String instr, int ctx) {
        about = about == null ? "" : about; instr = instr == null ? "" : instr;
        int lim = persLimit(ctx), tot = about.length() + instr.length();
        if (tot <= lim) return new String[]{about, instr};
        int a = (int) ((long) lim * about.length() / tot), i = lim - a;
        return new String[]{cut(about, a), cut(instr, i)};
    }
    /** Hooguit n tekens, zonder een emoji (twee chars) doormidden te knippen. */
    static String cut(String s, int n) {
        if (s.length() <= n) return s;
        if (n > 0 && Character.isHighSurrogate(s.charAt(n - 1))) n--;
        return s.substring(0, Math.max(0, n)).trim();
    }

    /** Hoeveel woorden het antwoord mag worden: de keuze bij Lengte, maar altijd ruimte voor je vraag (±700) en de
     *  vaste instructie (±3 tekens per woordje). Zuiver (getest). */
    static int maxFor(int ctx, String style, int systemChars) {
        int room = ctx - systemChars / 3 - 700;
        return Math.max(256, Math.min(maxTokens(style), Math.min(ctx / 2, room)));
    }

    /** Context uit slim zoeken, vóór de vraag gezet. Zuiver (getest). */
    static String withSources(String question, List<String[]> sources) {
        if (sources == null || sources.isEmpty()) return question;
        // De vraag eerst: wordt het te lang voor het model, dan valt het einde (de laatste bron) weg, niet de vraag
        StringBuilder sb = new StringBuilder("Mijn vraag: ").append(question)
                .append("\n\nStukken uit mijn eigen gegevens (notities, gesprekken, berichten, oproepen of contacten; gebruik ze alleen als ze helpen, en noem dan het nummer, bijvoorbeeld [1]):\n");
        int i = 1;
        for (String[] s : sources) {
            String body = s[1].length() > 900 ? s[1].substring(0, 900) + "…" : s[1];
            sb.append('[').append(i++).append("] ").append(s[0]).append(":\n").append(body.trim()).append("\n\n");
        }
        return sb.toString().trim();
    }

    /** Een antwoord maken. req = {messages:[{role, content}], notes:bool}. Draait op de AI-thread.
     *  Geeft false (en doet niets) als er al een antwoord loopt, bijv. na twee keer snel op versturen tikken. */
    static boolean chat(final Context c, final JSONObject req, final Sink sink) {
        final long job = GATE.enter();
        if (job == 0) return false;
        cancel = false; cancelAt = 0;
        // Was de assistent nog aan het kijken wat er te onthouden valt: dat stopt (de nieuwe vraag gaat voor)
        if (learning) {
            learnStop = true;
            LlmProcess p = chat;
            if (p != null && p.alive()) try { p.send(new JSONObject().put("op", "cancel")); } catch (Throwable ignored) { }
        }
        try {
            AI.execute(() -> chatRun(c, req, sink, job));
        } catch (Throwable t) { GATE.leave(job); throw t; }
        return true;
    }

    private static void chatRun(final Context c, final JSONObject req, final Sink sink, final long job) {
        try {
            if (!supported()) throw new Exception("De lokale AI werkt alleen op telefoons met een 64-bits ARM-processor");
            Model m = chatModel(c);
            if (!ready(c, m)) throw new Exception("Download eerst een taalmodel");
            JSONArray in = req.optJSONArray("messages");
            if (in == null || in.length() == 0) throw new Exception("Geen vraag");
            // De vraag zelf (voor het geheugen: "onthoud …", "vergeet …", en wat er te leren valt)
            String question = "";
            for (int i = in.length() - 1; i >= 0; i--) { JSONObject x = in.optJSONObject(i); if (x != null && !"assistant".equals(x.optString("role"))) { question = x.optString("content"); break; } }
            boolean memOn = "aan".equals(cfg(c, "mem"));
            String chatId = req.optString("chat"), chatTitle = req.optString("title");
            String remember = AiMemory.rememberCmd(question), forgetWhat = remember == null ? AiMemory.forgetCmd(question) : null, forgot = null, rememberedId = null;
            if (memOn && remember != null) {
                JSONObject r = AiMemory.add(c, remember, "gevraagd", chatId, chatTitle);
                if (r.has("error")) { sink.memory(new JSONObject().put("error", r.optString("error"))); remember = null; }
                else { remember = r.optString("text"); if (!r.optBoolean("dup")) rememberedId = r.optString("id"); sink.memory(new JSONObject().put("saved", new JSONArray().put(r)).put("explicit", true)); }
            }
            if (memOn && forgetWhat != null) {
                forgot = AiMemory.forget(c, forgetWhat);
                sink.memory(new JSONObject().put("forgot", forgot));
            }
            List<String> mem = memOn ? AiMemory.pick(AiMemory.texts(c), AiMemory.limit(m.ctx)) : null;
            String sys = systemPrompt(c, m.ctx, mem, memNote(remember, forgot, forgetWhat != null, !memOn));
            final int memUsed = mem == null ? 0 : mem.size();
            final boolean willLearn = memOn && remember == null && forgetWhat == null && !"uit".equals(cfg(c, "learn")) && AiMemory.worthy(question);
            JSONArray msgs = new JSONArray().put(new JSONObject().put("role", "system").put("content", sys));
            int from = Math.max(0, in.length() - historyFor(cfg(c, "history")));
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
            if (req.optBoolean("notes") && ready(c, EMBED) && remember == null && forgetWhat == null) {
                sink.status("Zoeken in je gegevens…");
                try {
                    JSONArray hits = SmartSearch.search(c, last.optString("content"), 4);
                    if (hits.length() > 0) {
                        List<String[]> src = new ArrayList<>();
                        for (int i = 0; i < hits.length(); i++) {
                            JSONObject h = hits.getJSONObject(i);
                            src.add(new String[]{SmartSearch.kindLabel(h.optString("kind")).replace(": ", " ") + "\"" + h.optString("title") + "\"", h.optString("snip")});
                        }
                        last.put("content", withSources(last.optString("content"), src));
                        sink.sources(hits);
                    }
                } catch (Exception e) { sink.status("Zoeken lukte niet: " + e.getMessage()); }
                if (cancel) { GATE.leave(job); sink.done(new JSONObject().put("cancelled", true)); return; }
            }
            chatPhase = true; // vanaf hier het taalmodel: de index wacht op een kleinere telefoon
            if (!bigRam(c)) closeEmbedSafe(); // niet twee modellen tegelijk in een kleiner geheugen
            boolean starting = chat == null || !chat.alive() || !chat.model.equals(file(c, m)) || chat.threads != threads();
            if (starting) sink.status("Model laden…");
            LlmProcess p = chatEngine(c, m);
            if (cancel) { GATE.leave(job); sink.done(new JSONObject().put("cancelled", true)); return; }
            sink.status("");
            final CountDownLatch done = new CountDownLatch(1);
            final long rid = REQ.incrementAndGet();
            final StringBuilder answer = new StringBuilder();
            final boolean[] ok = {false};
            // Al vóór het einde: wie meteen een nieuwe vraag stelt, stopt het leren (chat())
            learning = willLearn || rememberedId != null; learnStop = false;
            p.listen(o -> {
                // de beurt eerst vrijgeven: wie meteen opnieuw vraagt, krijgt geen "nog bezig"
                if (o.optBoolean("gone")) { if (done.getCount() > 0) { GATE.leave(job); done.countDown(); sink.error(o.optString("error")); } return; }
                if (o.optLong("id") != rid || done.getCount() == 0) return; // niet van deze vraag
                if (o.has("t")) { String t = o.optString("t"); if (answer.length() < 20000) answer.append(t); sink.token(t); }
                else if (o.optBoolean("done")) {
                    ok[0] = !o.optBoolean("cancelled");
                    try { if (memUsed > 0) o.put("mem", memUsed); if (ok[0] && willLearn) o.put("learning", true); } catch (Exception ignored) { }
                    GATE.leave(job); done.countDown(); sink.done(o);
                }
                else if (o.has("error")) { GATE.leave(job); done.countDown(); sink.error(o.optString("error")); }
            });
            try {
                double temp = temperature(cfg(c, "creative"));
                p.send(new JSONObject().put("op", "chat").put("id", rid).put("messages", msgs).put("max", maxFor(m.ctx, cfg(c, "style"), sys.length()))
                        .put("temp", temp).put("top_p", temp > 0.9 ? 0.95 : 0.8).put("top_k", 20));
                while (!done.await(500, TimeUnit.MILLISECONDS)) {
                    if (!p.alive()) { if (done.getCount() > 0) { GATE.leave(job); done.countDown(); sink.error("De AI-motor is gestopt"); } break; }
                    if (cancel && stuck(cancelAt, System.currentTimeMillis())) {
                        // Gestopt, maar de motor reageert niet: beëindigen, zodat de assistent niet voor altijd "bezig" blijft
                        p.listen(null);
                        if (done.getCount() > 0) { GATE.leave(job); done.countDown(); sink.done(new JSONObject().put("cancelled", true)); }
                        synchronized (LocalAi.class) { if (chat == p) closeChat(); else p.destroy(); }
                        break;
                    }
                }
            } finally { p.listen(null); }
            p.lastUse = System.currentTimeMillis();
            // Leren: kijken of er in de vraag iets over de gebruiker stond om te onthouden (onderbreekbaar)
            if (learning && ok[0] && !learnStop && p.alive() && rememberedId != null) rephraseRun(c, p, msgs, answer.toString(), rememberedId, remember, sink);
            else if (learning && ok[0] && !learnStop && p.alive()) learnRun(c, p, msgs, answer.toString(), question, chatId, chatTitle, sink);
            else if (ok[0] && willLearn) sink.memory(new JSONObject().put("none", true));
        } catch (Throwable t) {
            GATE.leave(job);
            sink.error(t.getMessage() == null ? "Er ging iets mis" : t.getMessage());
        } finally {
            learning = false;
            chatPhase = false;
            GATE.leave(job);
            scheduleIdle();
        }
    }

    private static volatile boolean learning, learnStop;

    /** Een korte extra vraag aan het model, achter het gesprek (dat staat al in het geheugen van de motor, dus snel).
     *  Geeft de tekst, of null als het stopte (nieuwe vraag, motor weg, te lang). */
    private static String sideAsk(LlmProcess p, JSONArray msgs, String answer, String prompt, int max) throws Exception {
        JSONArray lm = new JSONArray(msgs.toString());
        lm.put(new JSONObject().put("role", "assistant").put("content", answer.trim()));
        lm.put(new JSONObject().put("role", "user").put("content", prompt));
        final long rid = REQ.incrementAndGet();
        final StringBuilder out = new StringBuilder();
        final CountDownLatch done = new CountDownLatch(1);
        final boolean[] good = {false};
        p.listen(o -> {
            if (o.optBoolean("gone")) { done.countDown(); return; }
            if (o.optLong("id") != rid) return;
            if (o.has("t")) { if (out.length() < 4000) out.append(o.optString("t")); }
            else if (o.optBoolean("done")) { good[0] = !o.optBoolean("cancelled"); done.countDown(); }
            else if (o.has("error")) done.countDown();
        });
        try {
            if (learnStop) return null;
            p.send(new JSONObject().put("op", "chat").put("id", rid).put("messages", lm).put("max", max).put("temp", 0.2).put("top_p", 0.8).put("top_k", 20));
            long until = System.currentTimeMillis() + 90_000L;
            while (!done.await(250, TimeUnit.MILLISECONDS)) {
                if (learnStop || !p.alive() || System.currentTimeMillis() > until) {
                    if (p.alive()) try { p.send(new JSONObject().put("op", "cancel")); } catch (Throwable ignored) { }
                    return null;
                }
            }
        } finally { p.listen(null); p.lastUse = System.currentTimeMillis(); }
        return good[0] && !learnStop ? out.toString() : null;
    }

    /** Na "onthoud dat …": er een nette korte zin van laten maken (te zien in het gesprek en bij Geheugen). */
    private static void rephraseRun(Context c, LlmProcess p, JSONArray msgs, String answer, String id, String raw, Sink sink) {
        try {
            String out = sideAsk(p, msgs, answer, AiMemory.rephrasePrompt(raw), 60);
            String x = out == null ? null : AiMemory.rephrased(raw, out);
            if (x != null && !x.equalsIgnoreCase(raw) && "".equals(AiMemory.edit(c, id, x)))
                sink.memory(new JSONObject().put("updated", new JSONObject().put("id", id).put("text", x)));
        } catch (Throwable t) { App.log(c, "AI", "netjes maken: " + t); }
    }

    /** Na een antwoord: het model vragen wat er te onthouden valt, en dat voorstellen (Eerst vragen) of meteen
     *  bewaren (Vanzelf). Altijd te zien in het gesprek. */
    private static void learnRun(Context c, LlmProcess p, JSONArray msgs, String answer, String question, String chatId, String title, Sink sink) {
        JSONObject res = new JSONObject();
        try {
            sink.memory(new JSONObject().put("thinking", true));
            List<String> known = AiMemory.texts(c);
            String out = sideAsk(p, msgs, answer, AiMemory.learnPrompt(question, known), 160);
            if (out == null) return;
            List<String> facts = AiMemory.parseFacts(out, known);
            if (facts.isEmpty()) return;
            if ("vanzelf".equals(cfg(c, "learn"))) {
                JSONArray saved = new JSONArray();
                for (String f : facts) { JSONObject r = AiMemory.add(c, f, "geleerd", chatId, title); if (!r.has("error")) saved.put(r); else { res.put("error", r.optString("error")); break; } }
                if (saved.length() > 0) res.put("saved", saved);
            } else {
                JSONArray ask = new JSONArray();
                for (String f : facts) ask.put(f);
                res.put("ask", ask);
            }
        } catch (Throwable t) {
            App.log(c, "AI", "leren: " + t);
        } finally {
            try { if (!res.has("saved") && !res.has("ask") && !res.has("error")) res.put("none", true); if (learnStop) res.put("stopped", true); } catch (Exception ignored) { }
            sink.memory(res);
        }
    }

    static void cancel() {
        if (!cancel || cancelAt == 0) cancelAt = System.currentTimeMillis();
        cancel = true;
        LlmProcess p = chat;
        if (p != null && p.alive()) try { p.send(new JSONObject().put("op", "cancel")); } catch (Throwable ignored) { }
        // Het zoekmodel niet: een zoekvraag is zo klaar, en het bijwerken van de index (eigen thread) gaat gewoon door
    }

    /** Slim zoeken vanuit de interface (op de AI-thread, na een eventueel lopend antwoord). */
    static void search(final Context c, final String q, final java.util.function.BooleanSupplier current, final java.util.function.Consumer<JSONObject> cb) {
        AI.execute(() -> {
            JSONObject o = new JSONObject();
            try {
                o.put("q", q);
                if (!ready(c, EMBED)) o.put("need", true);
                else if (!current.getAsBoolean()) o.put("skipped", true); // er is intussen verder getypt
                else { o.put("items", SmartSearch.search(c, q, 6, false)); if (SmartSearch.updating() || indexQueued()) o.put("updating", true); }
            } catch (Throwable t) {
                try { o.put("error", t.getMessage() == null ? "Zoeken lukte niet" : t.getMessage()); } catch (Exception ignored) { }
            } finally { scheduleIdle(); }
            cb.accept(o);
        });
    }

    /** Waar de voortgang van de index heen gaat (de interface; gezet door AiBridge). */
    static volatile java.util.function.Consumer<JSONObject> idxSink;
    private static final java.util.concurrent.atomic.AtomicBoolean IDX_QUEUED = new java.util.concurrent.atomic.AtomicBoolean();

    private static void idxSend(JSONObject o) { java.util.function.Consumer<JSONObject> s = idxSink; if (s != null) try { s.accept(o); } catch (Throwable ignored) { } }

    /** Index (opnieuw) opbouwen op de index-thread; voortgang via idxSink. quiet = vanzelf (geen melding als hij klaar is). */
    static void index(final Context c, final boolean force, final boolean quiet) {
        if (!quiet) userStopped = false; // zelf op Bijwerken getikt
        IDX.execute(() -> {
            IDX_QUEUED.set(false);
            try {
                // Gestopt met de knop: niet vanzelf weer beginnen (pas na Bijwerken of als je een bron aan- of uitzet)
                if (!ready(c, EMBED) || (quiet && userStopped)) { idxSend(SmartSearch.info(c).put("quiet", true)); return; }
                SmartSearch.stopUpdate = false;
                SmartSearch.update(c, force, (d, t) -> {
                    try { idxSend(new JSONObject().put("busy", true).put("done", d).put("total", t)); } catch (Exception ignored) { }
                });
                JSONObject o = SmartSearch.info(c);
                if (quiet) o.put("quiet", true);
                idxSend(o);
            } catch (Throwable t) {
                try {
                    JSONObject o = SmartSearch.info(c);
                    if (!SmartSearch.stopUpdate) o.put("error", t.getMessage() == null ? "Bijwerken lukte niet" : t.getMessage());
                    else o.put("quiet", true);
                    idxSend(o);
                } catch (Exception ignored) { }
            } finally { scheduleIdle(); }
        });
    }

    static boolean indexQueued() { return IDX_QUEUED.get(); }
    /** Met de knop gestopt: niet vanzelf opnieuw beginnen. */
    static volatile boolean userStopped;
    static void indexStop() { userStopped = true; SmartSearch.stopUpdate = true; }

    /** Index op de achtergrond bijwerken (als er iets veranderde), hooguit één keer in de wachtrij. */
    static void indexLater(Context c) {
        if (userStopped || !ready(c, EMBED) || SmartSearch.updating() || !IDX_QUEUED.compareAndSet(false, true)) return;
        try { index(c, false, true); } catch (Throwable t) { IDX_QUEUED.set(false); }
    }

    /** Een bron gaat uit of aan: een lopende update stopt (die kent de oude keuze) en begint opnieuw. */
    static void sourceChanged(Context c) {
        if (!ready(c, EMBED)) return;
        userStopped = false;
        SmartSearch.stopUpdate = true;
        indexLaterForce(c);
    }
    private static void indexLaterForce(Context c) {
        if (!IDX_QUEUED.compareAndSet(false, true)) return;
        try { index(c, false, true); } catch (Throwable t) { IDX_QUEUED.set(false); }
    }

    // ---------- na een paar minuten niets doen: motoren stoppen (geheugen vrij) ----------

    private static Thread idle;

    private static synchronized void scheduleIdle() {
        if (idle != null && idle.isAlive()) return;
        idle = new Thread(() -> {
            try {
                while (true) {
                    Thread.sleep(30_000);
                    long now = System.currentTimeMillis(), IDLE_MS = idleMs(cfg(appCtx, "idle"));
                    LlmProcess p = chat, q = embed;
                    if (!busy() && p != null && now - p.lastUse > IDLE_MS) closeChat();
                    if (!busy() && q != null && now - q.lastUse > IDLE_MS && !SmartSearch.updating() && EMB.tryLock()) { try { closeEmbed(); } finally { EMB.unlock(); } }
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
        o.put("models", ms).put("chat", chatModel(c).id).put("wifiOnly", wifiOnly(c)).put("notes", prefs(c).getBoolean("notes", true)).put("voiceSend", prefs(c).getBoolean("voiceSend", true))
                .put("loaded", loaded()).put("busy", busy());
        if (ready(c, EMBED)) o.put("index", SmartSearch.info(c));
        JSONObject cf = new JSONObject();
        for (String[] r : CFG) cf.put(r[0], cfg(c, r[0]));
        cf.put("about", cfg(c, "about")).put("instr", cfg(c, "instr"));
        o.put("cfg", cf);
        o.put("memCount", AiMemory.count(c));
        JSONObject src = new JSONObject(), perm = new JSONObject();
        for (String k : SOURCES) src.put(k, srcOn(c, k));
        perm.put("sms", Search.has(c, android.Manifest.permission.READ_SMS)).put("calls", Search.has(c, android.Manifest.permission.READ_CALL_LOG))
                .put("contacts", Search.has(c, android.Manifest.permission.READ_CONTACTS)).put("wa", WaChats.dbFile(c).isFile())
                .put("notes", true).put("tx", true);
        o.put("src", src).put("perm", perm);
        return o;
    }
}
