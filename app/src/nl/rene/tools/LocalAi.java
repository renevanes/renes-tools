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
        /** Een nieuwer model dat een ouder vervangt (met een app-update meegekomen): de app meldt het, maar
         *  downloadt nooit vanzelf. Een nieuw model krijgt altijd een eigen id; het bestand van een bestaand id
         *  verandert nooit. news = één regel waarom het beter is, bijv. "Beter Nederlands, even snel". */
        String replaces = "", news = "";
        Model replaces(String oldId, String why) { replaces = oldId; news = why; return this; }
        /** Geheugen (RAM) dat aangeraden is; minder: de app waarschuwt bij Taalmodel en maakt eerst ruimte. 0 = geen eis. */
        long ram;
        Model ram(long bytes) { ram = bytes; return this; }
        /** Een groot model: op een kleiner geheugen eerst het zoekmodel stoppen. */
        boolean big() { return ram > 0; }
    }

    /** Aangeraden geheugen voor de grote modellen: 8 GB-telefoons melden ±7,4–7,7 GB. */
    static final long BIG_RAM = 7_500_000_000L;

    // Controlegetallen van Hugging Face (LFS-oid = SHA-256 en de precieze grootte)
    static final Model SMALL = new Model("klein", "Klein en snel", "Qwen3 1.7B · werkt op de meeste telefoons",
            "bartowski/Qwen_Qwen3-1.7B-GGUF", "822702a8257cf1b05a27d73f775d8606cf2e33a4", "Qwen_Qwen3-1.7B-Q4_K_M.gguf", 1_282_439_584L,
            "72c5c3cb38fa32d5256e2fe30d03e7a64c6c79e668ad84057e3bd66e250b24fb", 4096, false);
    static final Model LARGE = new Model("groot", "Groter en slimmer", "Qwen3 4B · beter Nederlands, trager, 8 GB geheugen aangeraden",
            "Qwen/Qwen3-4B-GGUF", "a9a60d009fa7ff9606305047c2bf77ac25dbec49", "Qwen3-4B-Q4_K_M.gguf", 2_497_280_256L,
            "7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5", 3072, false).ram(BIG_RAM);
    static final Model EMBED = new Model("zoek", "Slim zoeken", "Qwen3-Embedding 0.6B · zoeken op betekenis",
            "Qwen/Qwen3-Embedding-0.6B-GGUF", "d20cf9c16f82914a21dbd9c645f56895fb1d7750", "Qwen3-Embedding-0.6B-Q8_0.gguf", 639_150_592L,
            "06507c7b42688469c4e7298b0a1e16deff06caf291cf0a5b278c308249c3e439", 1024, true);
    // Opvolgers (Qwen3.5: hybride lineaire aandacht, dus een veel kleiner KV-geheugen: 4096 tokens past ruim).
    // Rene kiest zelf of hij ze downloadt; de oude blijven werken tot hij ze verwijdert.
    static final Model SMALL35 = new Model("klein35", "Klein en snel (Qwen3.5)", "Qwen3.5 2B · werkt op de meeste telefoons",
            "bartowski/Qwen_Qwen3.5-2B-GGUF", "7d26695454df6de5fbcce2e58681e62dae06ce43", "Qwen_Qwen3.5-2B-Q4_K_M.gguf", 1_396_198_496L,
            "57a1085840f497d764a7fc5d346922dbde961efb54cc792ea81d694fd846a1d8", 4096, false)
            .replaces("klein", "Nieuwer en beter in Nederlands, iets groter (1,4 GB)");
    static final Model LARGE35 = new Model("groot35", "Groter en slimmer (Qwen3.5)", "Qwen3.5 4B · beter Nederlands, trager, 8 GB geheugen aangeraden",
            "bartowski/Qwen_Qwen3.5-4B-GGUF", "4168f45a16a1290d65a4ec0fa312ae917a4c15d6", "Qwen_Qwen3.5-4B-Q4_K_M.gguf", 3_013_027_808L,
            "13c16f426047e2de38cd075bdade4a7bcbc8c774384876f677740cda65f8a983", 4096, false).ram(BIG_RAM)
            .replaces("groot", "Nieuwer en slimmer, groter (3,0 GB)");
    /** De AI-motor in de app (app/lib/arm64-v8a/README.txt; EngineVersionTest houdt die twee gelijk). */
    static final String ENGINE = "llama.cpp b11500";

    static final Model[] MODELS = {SMALL, LARGE, SMALL35, LARGE35, EMBED};

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
    static File partFile(Context c, Model m) { return new File(dlDir(c), m.file + ".download"); }
    private static File tmpFile(Context c, Model m) { return new File(modelDir(c), m.file + ".tmp"); }

    /** Bestanden van modellen die deze app-versie niet meer kent (na een update met een nieuwer model). Zuiver (getest). */
    static boolean orphan(String name, Model[] models) {
        for (Model m : models) if (name.equals(m.file) || name.equals(m.file + ".tmp") || name.equals(m.file + ".download")) return false;
        // .keep: even opzij gezet bij "Via de app downloaden" (blijft alleen staan als de app net toen stopte)
        return name.endsWith(".gguf") || name.endsWith(".gguf.tmp") || name.endsWith(".gguf.download") || name.endsWith(".gguf.download.keep");
    }
    /** Ook op te ruimen: resten (half gedownload, niet gecontroleerd) van een model dat door een nieuwer vervangen is
     *  en dat je niet hebt en niet aan het downloaden bent; zo'n model staat niet meer bij Taalmodel. busy = ids die
     *  klaar zijn of bezig (downloaden/controleren): daarvan blijft alles staan. Zuiver (getest). */
    static boolean leftover(String name, Model[] models, java.util.Set<String> busy) {
        if (orphan(name, models)) return true;
        for (Model m : models) {
            if (!name.equals(m.file) && !name.equals(m.file + ".tmp") && !name.equals(m.file + ".download")) continue;
            if (busy.contains(m.id)) return false;
            for (Model x : models) if (m.id.equals(x.replaces)) return true;
            return false;
        }
        return false;
    }
    private static List<File> orphans(Context c) {
        java.util.Set<String> busy = new java.util.HashSet<>();
        for (Model m : MODELS) if (ready(c, m) || VERIFYING.containsKey(m.id) || prefs(c).getLong("dl_" + m.id, -1) >= 0 || prefs(c).getBoolean("own_" + m.id, false)) busy.add(m.id);
        List<File> out = new ArrayList<>();
        for (File d : new File[]{modelDir(c), c.getExternalFilesDir("ai")}) {
            File[] fs = d == null ? null : d.listFiles();
            if (fs != null) for (File f : fs) if (f.isFile() && leftover(f.getName(), MODELS, busy)) out.add(f);
        }
        return out;
    }
    static long orphanBytes(Context c) { long n = 0; for (File f : orphans(c)) n += f.length(); return n; }
    /** Oude modelbestanden weg; geeft hoeveel ruimte dat opleverde. */
    static long cleanOrphans(Context c) { long n = 0; for (File f : orphans(c)) { long l = f.length(); if (f.delete()) n += l; } return n; }

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
        if (!ready(c, m)) for (Model x : MODELS) if (!x.embed && ready(c, x)) return x; // ook een nieuwer model
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
    static final String[] SOURCES = {"notes", "tx", "sms", "wa", "calls", "contacts", "sleep", "checkin"};
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
        // Het geheugen aan of uit is een privacykeuze: die blijft (net als wat er onthouden is)
        for (String[] r : CFG) if (!"mem".equals(r[0]) && !"learn".equals(r[0])) e.remove("cfg_" + r[0]);
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

    /** Downloaden: altijd door de app zelf (hervat waar hij was, voortgang in de meldingenbalk). */
    static String download(Context c, Model m) {
        // Een nieuwe download (niet "verder"): een oud stuk dat nergens meer bij hoort niet hergebruiken
        if (!prefs(c).getBoolean("own_" + m.id, false) && prefs(c).getLong("dl_" + m.id, -1) < 0 && !VERIFYING.containsKey(m.id)) partFile(c, m).delete();
        return downloadApp(c, m);
    }

    /**
     * Bij het openen van de app (of van Modellen): een onderbroken download vanzelf laten verdergaan als het netwerk
     * nu mag, en een oude download van de DownloadManager die stilstaat overnemen. Niet na een fout waarbij opnieuw
     * proberen niet helpt (zoals een verkeerd bestand op de server).
     */
    static synchronized void resumeDownloads(Context c) {
        try {
            if (ModelDownloadService.netBlocked(c) != null || !ModelDownloadService.online(c)) return;
            DownloadManager mgr = dm(c);
            boolean started = false;
            for (Model m : MODELS) {
                if (ready(c, m) || VERIFYING.containsKey(m.id) || ModelDownloadService.RUN.containsKey(m.id) || ModelDownloadService.QUEUED.contains(m.id)) continue;
                if (replaced(m)) continue; // een vervangen (oudere) versie niet vanzelf verder: die kan weg
                if (prefs(c).getBoolean("own_" + m.id, false)) {
                    // Niet steeds opnieuw als het net mislukte (een server die blijft weigeren)
                    if (prefs(c).getBoolean("retry_" + m.id, true) && System.currentTimeMillis() - prefs(c).getLong("tried_" + m.id, 0) > 10 * 60_000L) {
                        prefs(c).edit().putLong("tried_" + m.id, System.currentTimeMillis()).apply();
                        ModelDownloadService.start(c, m.id); started = true;
                    }
                    continue;
                }
                long id = prefs(c).getLong("dl_" + m.id, -1);
                if (id < 0 || mgr == null) continue;
                int st = -1;
                try (Cursor cur = mgr.query(new DownloadManager.Query().setFilterById(id))) { if (cur != null && cur.moveToFirst()) st = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)); }
                if (st == DownloadManager.STATUS_PAUSED || st == DownloadManager.STATUS_PENDING) { downloadApp(c, m); started = true; }
            }
            if (started) MainActivity.jsAll("onAiModels", "");
        } catch (Throwable t) { App.log(c, "AI", "downloads hervatten: " + t); }
    }

    /** Is er een nieuwer model dat dit vervangt? */
    static boolean replaced(Model m) { for (Model x : MODELS) if (m.id.equals(x.replaces)) return true; return false; }

    /** Moet de app dit model (nog) zelf downloaden? (Niet als het intussen gestopt, verwijderd of klaar is.) */
    static synchronized boolean wantsOwn(Context c, String id) {
        Model m = model(id);
        return m != null && prefs(c).getBoolean("own_" + id, false) && !ready(c, m) && !VERIFYING.containsKey(id);
    }

    /**
     * Door de app zelf laten downloaden (als de DownloadManager van Android blijft wachten, bijv. "Wacht op internet").
     * Een stuk dat de DownloadManager al binnen had, blijft staan: de app gaat daar verder (de SHA-256 controleert
     * daarna toch het hele bestand). Ook voor "Verder" na een onderbreking.
     */
    static synchronized String downloadApp(Context c, Model m) {
        if (ready(c, m)) return "";
        if (VERIFYING.containsKey(m.id) || ModelDownloadService.RUN.containsKey(m.id) || ModelDownloadService.QUEUED.contains(m.id)) return "";
        long free;
        try { free = new StatFs(c.getFilesDir().getPath()).getAvailableBytes(); } catch (Throwable t) { free = Long.MAX_VALUE; }
        File part = partFile(c, m);
        long need = 2 * m.size - Math.min(part.length(), m.size) + 200L * 1024 * 1024;
        if (free < need) return "Te weinig opslag: nodig " + gb(need) + ", vrij " + gb(free);
        long id = prefs(c).getLong("dl_" + m.id, -1);
        DownloadManager mgr = dm(c);
        if (id >= 0 && mgr != null) {
            // Wat de DownloadManager echt binnen heeft (zijn bestand kan al op de volle grootte staan, met nullen erachter)
            long got = -1;
            try (Cursor cur = mgr.query(new DownloadManager.Query().setFilterById(id))) {
                if (cur != null && cur.moveToFirst()) {
                    if (cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_RUNNING)
                        return "Het downloaden loopt weer; even geduld";
                    got = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                }
            } catch (Throwable ignored) { }
            // De DownloadManager gooit zijn bestand weg bij remove(): eerst even opzij zetten
            File keep = new File(part.getPath() + ".keep");
            keep.delete();
            boolean moved = part.isFile() && part.renameTo(keep);
            try { mgr.remove(id); } catch (Throwable ignored) { }
            if (moved && (!keepPart(keep, got) || !keep.renameTo(part))) keep.delete();
        }
        prefs(c).edit().remove("dl_" + m.id).remove("err_" + m.id).remove("ok_" + m.id).remove("retry_" + m.id).putBoolean("own_" + m.id, true).apply();
        ModelDownloadService.start(c, m.id);
        return "";
    }

    /** Alleen het stuk dat echt binnen is houden (bytes = wat de DownloadManager meldt; onbekend: niets). Zuiver (getest). */
    static boolean keepPart(File f, long bytes) {
        if (bytes <= 0 || bytes > f.length()) return false;
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "rw")) { r.setLength(bytes); return true; }
        catch (Exception e) { return false; }
    }

    /** De app heeft hem helemaal binnen: controleren en in de privémap zetten ("own_" blijft tot dat klaar is). */
    static synchronized void ownDone(Context c, Model m) {
        if (!prefs(c).getBoolean("own_" + m.id, false)) return; // intussen weggehaald
        prefs(c).edit().remove("err_" + m.id).apply();
        startVerify(c, m);
        MainActivity.jsAll("onAiModels", "");
    }

    /** Downloaden door de app lukte (nog) niet: het stuk dat binnen is blijft staan voor "Verder". */
    static void ownFailed(Context c, String id, String why) { ownFailed(c, id, why, true); }

    /** retry = later vanzelf opnieuw proberen (bij het openen van de app); niet bij een fout die dan niet weg is. */
    static void ownFailed(Context c, String id, String why, boolean retry) {
        prefs(c).edit().putString("err_" + id, why).putBoolean("retry_" + id, retry).apply();
        MainActivity.jsAll("onAiModels", "");
    }

    static String gb(long b) { return String.format(Locale.ROOT, "%.1f GB", b / 1e9).replace('.', ','); }

    /** Weg: lopende download stoppen, motor stoppen, bestanden weg (de zoekindex ruimt de AI-thread op). */
    static void delete(final Context c, final Model m) {
        long id;
        synchronized (LocalAi.class) {
            id = prefs(c).getLong("dl_" + m.id, -1);
            prefs(c).edit().remove("dl_" + m.id).remove("ok_" + m.id).remove("err_" + m.id).remove("own_" + m.id).remove("retry_" + m.id).remove("tried_" + m.id).apply();
        }
        ModelDownloadService.stop(m.id); // downloadt de app hem zelf: stoppen
        ModelUse.forget(c, "ai:" + m.id);
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
            o.put("id", m.id).put("label", m.label).put("desc", m.desc).put("size", m.size).put("embed", m.embed).put("ctx", m.ctx);
            if (!m.replaces.isEmpty()) o.put("replaces", m.replaces).put("news", m.news);
            if (m.ram > 0) o.put("ram", m.ram);
            if (ready(c, m)) return o.put("state", "ready");
            Integer v = VERIFYING.get(m.id);
            if (v != null) return o.put("state", "verifying").put("pct", v);
            String err = prefs(c).getString("err_" + m.id, "");
            // De app downloadt hem zelf (na "Via de app downloaden")
            long[] run = ModelDownloadService.RUN.get(m.id);
            if (run != null) {
                o.put("via", "app").put("bytes", run[0]).put("total", run[1] > 0 ? run[1] : m.size);
                String wait = ModelDownloadService.WAIT_NET.get(m.id);
                return wait != null ? o.put("state", "paused").put("reason", wait).put("waiting", true) : o.put("state", "running");
            }
            if (prefs(c).getBoolean("own_" + m.id, false)) {
                long have = partFile(c, m).length();
                o.put("via", "app").put("bytes", have).put("total", m.size);
                if (ModelDownloadService.QUEUED.contains(m.id)) return o.put("state", "queued");
                // Een fout die niet vanzelf overgaat (verkeerd bestand op de server, te weinig opslag): mislukt
                if (!prefs(c).getBoolean("retry_" + m.id, true)) return o.put("state", "failed").put("reason", err.isEmpty() ? "Downloaden mislukt" : err);
                return o.put("state", "paused").put("reason", err.isEmpty() ? "Onderbroken" : err);
            }
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
        if (VERIFYING.putIfAbsent(m.id, 0) != null) return;
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
                prefs(c).edit().remove("own_" + m.id).apply(); // klaar met dit bestand (ook als de app het zelf downloadde)
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
    static String loaded() {
        LlmProcess p = chat;
        if (p == null || !p.alive()) return "";
        for (Model m : MODELS) if (!m.embed && p.model.getName().equals(m.file)) return m.id;
        return "";
    }

    static synchronized void closeChat() { if (chat != null) { chat.destroy(); chat = null; } }
    static synchronized void closeEmbed() { if (embed != null) { embed.destroy(); embed = null; } }
    /** Het zoekmodel stoppen na de vraag die er nu loopt (een stuk van de index wordt dus afgemaakt). */
    static void closeEmbedSafe() { EMB.lock(); try { closeEmbed(); } finally { EMB.unlock(); } }

    private static LlmProcess chatEngine(Context c, Model m) throws Exception {
        ModelUse.touch(c, "ai:" + m.id);
        LlmProcess p = chat;
        if (p != null && p.alive() && p.model.equals(file(c, m)) && p.threads == threads()) return p;
        closeChat();
        if (m.big() && !bigRam(c)) closeEmbedSafe(); // geheugen vrijmaken voor het grote model
        p = LlmProcess.start(c, file(c, m), m.ctx, threads(), false, true);
        chat = p;
        return p;
    }

    private static LlmProcess embedEngine(Context c) throws Exception {
        ModelUse.touch(c, "ai:" + EMBED.id);
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
        /** Er werd om een actie gevraagd: het antwoord kan eindigen met een actieregel. */
        default void actWanted() { }
    }

    static String systemPrompt(Context c, int ctx, List<String> mem, String note) {
        String today = new java.text.SimpleDateFormat("EEEE d MMMM yyyy", new Locale("nl", "NL")).format(new java.util.Date());
        String[] p = persFit(cfg(c, "about"), cfg(c, "instr"), ctx);
        return buildSystem(today, cfg(c, "style"), cfg(c, "lang"), p[0], p[1], mem, note);
    }

    /** Wat er met het geheugen gebeurde, voor het model (zodat het dat kort bevestigt). Zuiver (getest). */
    static String memNote(String remembered, String forgot, boolean forgetTried, boolean memOff) {
        if (memOff && (remembered != null || forgetTried))
            return "De gebruiker vroeg iets te onthouden of te vergeten, maar het geheugen staat uit (Lokale AI → Geheugen). Er is niets veranderd; zeg dat kort.";
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
        stopLearning();
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
            String remember = AiMemory.rememberCmd(question), forgetWhat = remember == null ? AiMemory.forgetCmd(question) : null, rememberedId = null;
            // Wat er met het geheugen gebeurde, voor het model: achter de vraag (niet in de vaste instructie, dan kan de
            // motor het begin van het gesprek gewoon hergebruiken)
            String note = "";
            if (remember != null) {
                if (!memOn) note = memNote(remember, null, false, true);
                else if (AiMemory.secretWord(remember)) {
                    sink.memory(ev(job, true).put("error", "Wachtwoorden, codes en rekeningnummers onthoud ik niet. Bewaar die versleuteld in de Kluis."));
                    note = "De gebruiker vroeg je een wachtwoord, code of rekeningnummer te onthouden. Dat doe je niet (het geheugen is niet versleuteld): zeg dat kort en raad de Kluis in de app aan, waar het versleuteld staat.";
                } else {
                    JSONObject r = AiMemory.add(c, remember, "gevraagd", chatId, chatTitle);
                    if (r.has("error")) { sink.memory(ev(job, true).put("error", r.optString("error"))); note = "De gebruiker vroeg je iets te onthouden, maar het geheugen is vol. Zeg dat kort: bij Lokale AI → Geheugen kan iets weg."; }
                    else { if (!r.optBoolean("dup")) rememberedId = r.optString("id"); sink.memory(ev(job, true).put("saved", new JSONArray().put(r)).put("explicit", true)); note = memNote(r.optString("text"), null, false, false); }
                }
            }
            if (forgetWhat != null) {
                if (!memOn) note = memNote(null, null, true, true);
                else {
                    JSONObject f = AiMemory.forget(c, forgetWhat);
                    JSONObject e = ev(job, true).put("forgot", f == null ? "" : f.optString("text"));
                    if (f != null) e.put("forgotItem", f);
                    sink.memory(e);
                    note = memNote(null, f == null ? "" : f.optString("text"), true, false);
                }
            }
            // Per gesprek steeds dezelfde herinneringen (vast vanaf de eerste vraag): dan blijft de vaste instructie
            // gelijk en hoeft de motor het gesprek niet opnieuw in te lezen. Wat intussen weg is, gaat er wel uit.
            List<String> mem = memOn ? memFor(chatId, AiMemory.pairs(c), AiMemory.limit(m.ctx)) : null;
            String sys = systemPrompt(c, m.ctx, mem, "");
            final int memUsed = mem == null ? 0 : mem.size();
            // Acties (notitie, herinnering, agenda): alleen als de vraag erom lijkt te vragen; nooit bij "onthoud …" (dat is
            // het geheugen) of bij een samenvatting van een gesprek. Ze gebeuren pas als je onder het antwoord op de knop tikt.
            // Een gesprek om samen te vatten (berichten van anderen) zit als bijlage in het gesprek: zelf nagaan, niet alleen op de interface vertrouwen
            boolean hasAtt = false, hasOth = false;
            for (int i = 0; i < in.length(); i++) { JSONObject x = in.optJSONObject(i); if (x != null && x.optBoolean("att")) hasAtt = true; if (x != null && x.optBoolean("oth")) hasOth = true; }
            final boolean actWanted = actAllowed(req.optBoolean("noAct"), hasAtt, remember, forgetWhat, question);
            // Een vraag over de instellingen van de app: de paar instellingen die de interface erbij zocht (AiSettings)
            final List<AiSettings.Entry> setOffer = AiSettings.allowed(req.optJSONArray("settings") != null, hasAtt, hasOth, remember, forgetWhat)
                    ? AiSettings.limit(setEntries(req.optJSONArray("settings"))) : null;
            final boolean setWanted = setOffer != null && !setOffer.isEmpty();
            // Niet leren bij een actie ("herinner me …" is geen feit over jou) of als er berichten van anderen in het gesprek staan
            boolean learnWanted = learnAllowed(memOn, req.optBoolean("noLearn"), hasAtt, actWanted || setWanted, remember, forgetWhat, cfg(c, "learn"), question);
            JSONArray msgs = new JSONArray().put(new JSONObject().put("role", "system").put("content", sys));
            int from = Math.max(0, in.length() - historyFor(cfg(c, "history")));
            int attAt = -1;
            for (int i = 0; i < in.length(); i++) {
                JSONObject x = in.optJSONObject(i);
                if (x == null) continue;
                boolean att = x.optBoolean("att") && !"assistant".equals(x.optString("role"));
                if (i < from && !att) continue; // de bijlage blijft altijd mee, ook in een lang gesprek
                if (x.optBoolean("oth")) learnWanted = false; // een eerder antwoord steunde op berichten van anderen: daar niets uit leren
                String role = "assistant".equals(x.optString("role")) ? "assistant" : "user";
                String content = x.optString("content");
                if (att) attAt = msgs.length();
                else if (content.length() > 12000) content = content.substring(0, 12000);
                msgs.put(new JSONObject().put("role", role).put("content", content));
            }
            JSONObject last = msgs.getJSONObject(msgs.length() - 1);
            if (!"user".equals(last.optString("role"))) throw new Exception("Geen vraag");
            // Met mijn notities: eerst slim zoeken, dan de stukken meegeven
            // Niet bij een vraag over de instellingen: tekst van anderen (sms, WhatsApp) mag geen wijziging voorstellen
            if (req.optBoolean("notes") && ready(c, EMBED) && remember == null && forgetWhat == null && !setWanted) {
                sink.status("Zoeken in je gegevens…");
                try {
                    JSONArray hits = SmartSearch.search(c, last.optString("content"), 4);
                    if (actWanted) { // bij een actie: geen berichten van anderen erbij (die mogen het voorstel niet sturen)
                        JSONArray own = new JSONArray();
                        for (int i = 0; i < hits.length(); i++) { String k = hits.getJSONObject(i).optString("kind"); if (!"sms".equals(k) && !"wa".equals(k) && !"calls".equals(k) && !"contacts".equals(k)) own.put(hits.get(i)); }
                        hits = own;
                    }
                    if (hits.length() > 0) {
                        learnWanted = false; // met berichten van anderen erbij: niets leren (dat zou over hen kunnen gaan)
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
            if (!note.isEmpty()) last.put("content", last.optString("content") + "\n\n(Bericht van de app: " + note + ")");
            // Achter de vraag, niet in de vaste instructie: die blijft zo gelijk en de motor hoeft het gesprek niet opnieuw in te lezen
            final String questionF = question;
            if (actWanted) last.put("content", last.optString("content") + "\n\n(Bericht van de app: " + AiActions.prompt(System.currentTimeMillis(), java.util.TimeZone.getDefault()) + ")");
            if (setWanted) last.put("content", last.optString("content") + "\n\n(Bericht van de app: " + AiSettings.prompt(setOffer) + ")");
            else if (req.optBoolean("noDo") && remember == null && forgetWhat == null && !hasAtt && !actWanted) last.put("content", last.optString("content") + "\n\n(Bericht van de app: " + AiSettings.NO_DO + ")");
            final boolean willLearn = learnWanted;
            // Antwoordlengte; met een bijlage hooguit 700 woordjes (ook bij doorvragen) en de bijlage zelf passend gemaakt:
            // anders laat de motor het oudste bericht weg, en dat is juist het gesprek
            int maxTok = maxFor(m.ctx, cfg(c, "style"), sys.length());
            if (hasAtt || req.optBoolean("sum")) maxTok = Math.min(maxTok, 700);
            if (attAt > 0) {
                int other = 0;
                for (int i = 0; i < msgs.length(); i++) if (i != attAt) other += msgs.getJSONObject(i).optString("content").length();
                JSONObject a = msgs.getJSONObject(attAt);
                a.put("content", fitAttachment(a.optString("content"), attBudget(m.ctx, maxTok, other, msgs.length())));
            }
            final int max = maxTok;
            if (actWanted || setWanted) sink.actWanted(); // de interface verbergt de actieregel dan al tijdens het schrijven
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
            final String rememberedIdF = rememberedId;
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
                    // Een actieregel ([[herinnering: …]]) uit het antwoord halen en als voorstel meesturen (de interface vraagt bevestiging)
                    try {
                        String full = answer.toString();
                        if (full.length() < 20000 && (actWanted || setWanted)) { // alleen als er om een actie of instelling gevraagd werd
                            String clean = actWanted ? AiActions.strip(full) : full;
                            if (setWanted) clean = AiSettings.strip(clean);
                            if (!clean.equals(full)) o.put("clean", clean);
                            AiActions.Act act = actWanted && ok[0] ? AiActions.parse(full, questionF, System.currentTimeMillis(), java.util.TimeZone.getDefault()) : null;
                            if (act != null) o.put("action", actJson(act));
                            // Voorgestelde wijzigingen: alleen van meegestuurde instellingen; de interface vraagt bevestiging
                            if (setWanted && ok[0]) {
                                JSONArray sa = new JSONArray();
                                for (AiSettings.Change ch : AiSettings.filter(AiSettings.parse(full), setOffer)) sa.put(new JSONObject().put("id", ch.id).put("value", ch.value));
                                if (sa.length() > 0) o.put("settings", sa);
                            }
                        }
                    } catch (Throwable ignored) { }
                    try { o.put("turn", job); if (memUsed > 0) o.put("mem", memUsed); if (ok[0] && (willLearn || rememberedIdF != null)) o.put("learning", true); } catch (Exception ignored) { }
                    GATE.leave(job); done.countDown(); sink.done(o);
                }
                else if (o.has("error")) { GATE.leave(job); done.countDown(); sink.error(o.optString("error")); }
            });
            try {
                double temp = temperature(cfg(c, "creative"));
                p.send(new JSONObject().put("op", "chat").put("id", rid).put("messages", msgs).put("max", max)
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
            if (learning && ok[0] && !learnStop && p.alive() && rememberedId != null) rephraseRun(c, p, m, msgs, answer.toString(), rememberedId, remember, sink, job);
            else if (learning && ok[0] && !learnStop && p.alive()) learnRun(c, p, m, msgs, answer.toString(), question, chatId, chatTitle, sink, job);
            else if (ok[0] && (willLearn || rememberedId != null)) sink.memory(ev(job, false).put("none", true));
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

    /** Mag het model een actie voorstellen? Alleen als de vraag erom vraagt; nooit bij "onthoud/vergeet" (het geheugen) en
     *  nooit in een gesprek met berichten van anderen (een samenvatting): hun tekst mag geen voorstel sturen. Zuiver (getest). */
    static boolean actAllowed(boolean noAct, boolean hasAtt, String remember, String forget, String question) {
        return !noAct && !hasAtt && remember == null && forget == null && AiActions.wanted(question);
    }
    /** Mag de assistent na het antwoord kijken wat er te onthouden valt? Niet bij een actie, niet met berichten van
     *  anderen erbij, niet als het geheugen of leren uit staat. Zuiver (getest). */
    static boolean learnAllowed(boolean memOn, boolean noLearn, boolean hasAtt, boolean actWanted, String remember, String forget, String learn, String question) {
        return memOn && !noLearn && !hasAtt && !actWanted && remember == null && forget == null && !"uit".equals(learn) && AiMemory.worthy(question);
    }
    /** Hoeveel tekens de bijlage mag zijn: wat er in het model past naast de instructie, de andere berichten en het
     *  antwoord (voorzichtig: ±2,6 tekens per woordje). Zuiver (getest). */
    static int attBudget(int ctx, int maxTok, int otherChars, int msgCount) {
        int tok = ctx - maxTok - 96 - otherChars * 10 / 26 - 6 * msgCount;
        return Math.max(600, tok * 26 / 10);
    }
    /** De bijlage inkorten: de opdracht (tot en met de kopregel) blijft, van het gesprek de nieuwste regels. Zuiver (getest). */
    static String fitAttachment(String content, int maxChars) {
        if (content == null || content.length() <= maxChars) return content;
        int p = content.indexOf("\n\n"), nl = p < 0 ? -1 : content.indexOf('\n', p + 2);
        String head = nl < 0 ? "" : content.substring(0, nl), body = nl < 0 ? content : content.substring(nl + 1);
        String mark = "(het begin van het gesprek is weggelaten)";
        int keep = Math.max(200, maxChars - head.length() - mark.length() - 2);
        if (body.length() > keep) {
            String tail = body.substring(body.length() - keep);
            int cut = tail.indexOf('\n');
            if (cut >= 0 && cut < tail.length() - 1) tail = tail.substring(cut + 1); // op een regelgrens beginnen
            body = mark + "\n" + tail;
        }
        return head.isEmpty() ? body : head + "\n" + body;
    }

    /** De instellingen die de interface bij de vraag meestuurde: [{id, label, where, desc, cur, type, safe, vals:[[waarde, naam]]}]. */
    static List<AiSettings.Entry> setEntries(JSONArray a) {
        List<AiSettings.Entry> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length() && i < 12; i++) {
            JSONObject x = a.optJSONObject(i);
            if (x == null) continue;
            AiSettings.Entry e = new AiSettings.Entry();
            e.id = x.optString("id"); e.label = AiSettings.clean(x.optString("label"), 80); e.where = AiSettings.clean(x.optString("where"), 120);
            e.desc = AiSettings.clean(x.optString("desc"), 220); e.cur = AiSettings.clean(x.optString("cur"), 60); e.type = x.optString("type"); e.safe = x.optBoolean("safe");
            JSONArray v = x.optJSONArray("vals");
            if (v != null) for (int k = 0; k < v.length() && k < 24; k++) {
                JSONArray p = v.optJSONArray(k);
                if (p != null && p.length() > 0) e.vals.add(new String[]{AiSettings.clean(p.optString(0), 30), AiSettings.clean(p.optString(1), 40)});
            }
            out.add(e);
        }
        return out;
    }

    /** Een voorgestelde actie voor de interface. */
    static JSONObject actJson(AiActions.Act a) throws Exception {
        JSONObject o = new JSONObject().put("type", a.type).put("title", a.title).put("text", a.text).put("need", a.need).put("src", a.src);
        if (a.at > 0) o.put("at", a.at);
        if (a.day > 0) o.put("day", a.day);
        if (a.allDay) o.put("allDay", true);
        if (a.sug >= 0) o.put("sug", a.sug);
        return o;
    }

    private static volatile boolean learning, learnStop;

    /** Er loopt nu echt een extra vraag (leren of netjes maken); alleen dan mag "cancel" naar de motor: anders zou
     *  het het antwoord stoppen dat nog geschreven wordt. */
    private static volatile boolean inSide;

    static void stopLearning() {
        if (!learning) return;
        learnStop = true;
        LlmProcess p = chat;
        if (inSide && p != null && p.alive()) try { p.send(new JSONObject().put("op", "cancel")); } catch (Throwable ignored) { }
    }

    /** Een korte extra vraag aan het model, achter het gesprek (dat staat al in het geheugen van de motor, dus snel).
     *  Geeft de tekst, of null als het stopte (nieuwe vraag, motor weg, te lang). */
    private static String sideAsk(LlmProcess p, Model model, JSONArray msgs, String answer, String prompt, int max) throws Exception {
        // Past het niet zonder dat de motor het begin van het gesprek moet inkorten: overslaan (dat zou het hergebruik
        // voor de volgende vraag kosten). Ruwe schatting: ±3 tekens per woordje.
        if (sideTooBig(msgs.toString().length() + answer.length() + prompt.length(), max, model.ctx)) return null;
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
            inSide = true;
            if (learnStop) return null;
            p.send(new JSONObject().put("op", "chat").put("id", rid).put("messages", lm).put("max", max).put("temp", 0.2).put("top_p", 0.8).put("top_k", 20));
            long until = System.currentTimeMillis() + 90_000L;
            while (!done.await(250, TimeUnit.MILLISECONDS)) {
                p.lastUse = System.currentTimeMillis(); // niet uit het geheugen halen terwijl hij hier mee bezig is
                if (learnStop || !p.alive() || System.currentTimeMillis() > until) {
                    if (p.alive()) try { p.send(new JSONObject().put("op", "cancel")); } catch (Throwable ignored) { }
                    return null;
                }
            }
        } finally { inSide = false; p.listen(null); p.lastUse = System.currentTimeMillis(); }
        return good[0] && !learnStop ? out.toString() : null;
    }

    /** Na "onthoud dat …": er een nette korte zin van laten maken (te zien in het gesprek en bij Geheugen). */
    private static void rephraseRun(Context c, LlmProcess p, Model model, JSONArray msgs, String answer, String id, String raw, Sink sink, long job) {
        JSONObject res = ev(job, false);
        try {
            String out = sideAsk(p, model, msgs, answer, AiMemory.rephrasePrompt(raw), 60);
            String x = out == null ? null : AiMemory.rephrased(raw, out);
            if (x != null && !x.equalsIgnoreCase(raw) && !AiMemory.secret(x) && "".equals(AiMemory.edit(c, id, x)))
                res.put("updated", new JSONObject().put("id", id).put("text", x));
        } catch (Throwable t) { App.log(c, "AI", "netjes maken: " + t); }
        finally { try { res.put("none", true); } catch (Exception ignored) { } sink.memory(res); }
    }

    /** Groter dan het geheugen van het model (met ruimte voor het antwoord)? Zuiver (getest). */
    static boolean sideTooBig(int chars, int max, int ctx) { return chars / 3 + max + 64 > ctx; }

    /** Vaste herinneringen per gesprek (hooguit 8 gesprekken onthouden): de ids, en het budget waarmee gekozen werd. */
    private static final java.util.Map<String, List<String>> MEM_CHAT = new java.util.HashMap<>();
    private static final java.util.Map<String, Integer> MEM_BUDGET = new java.util.HashMap<>();
    private static final java.util.LinkedList<String> MEM_ORDER = new java.util.LinkedList<>();

    /** De herinneringen voor dit gesprek: bij de eerste vraag gekozen en daarna dezelfde (met hun huidige tekst; wat
     *  weg is, gaat eruit). all = {id, tekst}, nieuwste eerst. Een ander model (ander budget): opnieuw kiezen. Getest. */
    static synchronized List<String> memFor(String chatId, List<String[]> all, int budget) {
        java.util.Map<String, String> text = new java.util.HashMap<>();
        for (String[] x : all) text.put(x[0], x[1]);
        boolean keyed = chatId != null && !chatId.isEmpty();
        List<String> ids = keyed ? MEM_CHAT.get(chatId) : null;
        if (ids == null || !Integer.valueOf(budget).equals(MEM_BUDGET.get(chatId))) {
            ids = new ArrayList<>();
            int used = 0; // zoals AiMemory.pick: de nieuwste die samen in het budget passen
            for (String[] x : all) {
                if (x[1] == null || x[1].isEmpty()) continue;
                if (used + x[1].length() + 3 > budget) break;
                ids.add(x[0]); used += x[1].length() + 3;
            }
            if (keyed) {
                MEM_CHAT.put(chatId, ids); MEM_BUDGET.put(chatId, budget); MEM_ORDER.remove(chatId); MEM_ORDER.addLast(chatId);
                while (MEM_ORDER.size() > 8) { String old = MEM_ORDER.removeFirst(); MEM_CHAT.remove(old); MEM_BUDGET.remove(old); }
            }
        }
        List<String> out = new ArrayList<>();
        for (String id : ids) { String t = text.get(id); if (t != null) out.add(t); }
        return out;
    }

    /** Begin van een geheugenmelding: bij welke vraag (turn) hoort hij, en komt hij vóór het antwoord (pre). */
    private static JSONObject ev(long job, boolean pre) {
        JSONObject o = new JSONObject();
        try { o.put("turn", job); if (pre) o.put("pre", true); } catch (Exception ignored) { }
        return o;
    }

    /** Na een antwoord: het model vragen wat er te onthouden valt, en dat voorstellen (Eerst vragen) of meteen
     *  bewaren (Vanzelf). Altijd te zien in het gesprek. */
    private static void learnRun(Context c, LlmProcess p, Model model, JSONArray msgs, String answer, String question, String chatId, String title, Sink sink, long job) {
        JSONObject res = ev(job, false);
        try {
            sink.memory(ev(job, false).put("thinking", true));
            List<String> known = AiMemory.texts(c);
            String out = sideAsk(p, model, msgs, answer, AiMemory.learnPrompt(question, known), 160);
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
        stopLearning(); // wie zoekt, wacht niet op het leren
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
                    if (!busy() && !learning && p != null && now - p.lastUse > IDLE_MS) closeChat();
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

    static final int MAX_CHATS = 100;

    /** Zoals chats(), maar een kapot bestand is een fout (dan nooit eroverheen schrijven of een lege backup maken). */
    static synchronized JSONArray chatsStrict(Context c) throws Exception {
        File f = chatsFile(c);
        if (!f.isFile()) return new JSONArray();
        try { return new JSONArray(Contacts.readText(f, false)); }
        catch (Exception e) { throw new Exception("De gesprekken van de assistent zijn niet te lezen; er is niets veranderd"); }
    }

    /**
     * Terugzetten uit Alles back-uppen: gesprekken met een id dat er nog niet is erbij (nieuwste eerst, zolang er
     * plaats is); bestaande gesprekken blijven zoals ze zijn. Geeft het aantal toegevoegde.
     */
    static synchronized int chatsMerge(Context c, JSONArray in) throws Exception {
        JSONArray have = chatsStrict(c);
        java.util.Set<String> ids = new java.util.HashSet<>();
        List<JSONObject> all = new ArrayList<>();
        for (int i = 0; i < have.length(); i++) { JSONObject x = have.optJSONObject(i); if (x != null) { ids.add(x.optString("id")); all.add(x); } }
        List<String> inIds = new ArrayList<>();
        List<Long> inT = new ArrayList<>();
        for (int i = 0; i < in.length(); i++) { JSONObject x = in.optJSONObject(i); inIds.add(x == null ? "" : x.optString("id")); inT.add(x == null ? 0L : x.optLong("t")); }
        int[] plan = AiSleepBackup.chatPlan(ids, all.size(), inIds, inT, MAX_CHATS);
        int n = 0;
        for (int i = 0; i < plan.length; i++) if (plan[i] == AiSleepBackup.ADD) { all.add(AiSleepBackup.cleanChat(in.getJSONObject(i))); n++; }
        if (n == 0) return 0;
        java.util.Collections.sort(all, (x, y) -> Long.compare(y.optLong("t"), x.optLong("t"))); // nieuwste eerst, zoals chatSave
        JSONArray out = new JSONArray();
        for (JSONObject x : all) out.put(x);
        Contacts.writeText(chatsFile(c), out.toString(), false);
        return n;
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
        for (int i = 0; i < a.length() && out.length() < MAX_CHATS; i++) { JSONObject y = a.optJSONObject(i); if (y != null && !id.equals(y.optString("id"))) out.put(y); }
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
        o.put("models", ms).put("chat", chatModel(c).id).put("wifiOnly", wifiOnly(c)).put("notes", prefs(c).getBoolean("notes", true)).put("voiceSend", prefs(c).getBoolean("voiceSend", false))
                .put("speakVoice", prefs(c).getBoolean("speakVoice", false)).put("loaded", loaded()).put("busy", busy());
        if (ready(c, EMBED)) o.put("index", SmartSearch.info(c));
        JSONObject cf = new JSONObject();
        for (String[] r : CFG) cf.put(r[0], cfg(c, r[0]));
        cf.put("about", cfg(c, "about")).put("instr", cfg(c, "instr"));
        o.put("cfg", cf);
        o.put("memCount", AiMemory.count(c));
        try { o.put("orphans", orphanBytes(c)); } catch (Throwable ignored) { }
        JSONObject src = new JSONObject(), perm = new JSONObject();
        for (String k : SOURCES) src.put(k, srcOn(c, k));
        perm.put("sms", Search.has(c, android.Manifest.permission.READ_SMS)).put("calls", Search.has(c, android.Manifest.permission.READ_CALL_LOG))
                .put("contacts", Search.has(c, android.Manifest.permission.READ_CONTACTS)).put("wa", WaChats.dbFile(c).isFile())
                .put("notes", true).put("tx", true).put("sleep", true).put("checkin", true);
        o.put("src", src).put("perm", perm);
        return o;
    }
}
