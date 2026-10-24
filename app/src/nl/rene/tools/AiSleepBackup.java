package nl.rene.tools;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Het geheugen en de gesprekken van de assistent en de nachten van Slaappraat als (gevoelige) onderdelen van
 * Alles back-uppen. Ze staan standaard uit en gaan alleen mee als Rene ze aanvinkt; met Versleutelen aan zitten ze in
 * het versleutelde archief, anders als leesbaar bestand in zijn eigen backup-map. Nooit mee: modellen (ai-models),
 * de zoekindex (die wordt opnieuw gemaakt) en verder niets wat niet van hem is.
 *
 * In de backup-map (of het archief):
 *  - Assistent/assistent.json   {format, kind:"assistent", t, memory:[…], chats:[…]}
 *  - Slaappraat/slaappraat.json {format, kind:"slaappraat", t, audio, nights:[night.json…], files:{"nacht/stukje.wav": bytes}}
 *  - Slaappraat/&lt;nacht&gt;/&lt;stukje&gt;.wav  (alleen bij "met geluid")
 *
 * Terugzetten voegt samen en overschrijft nooit iets: herinneringen die er (bijna) al staan, gesprekken en nachten
 * met een bestaand id en de nacht die nu loopt blijven zoals ze zijn. Het zuivere rekenwerk staat bovenaan en wordt
 * getest in AiSleepBackupTest.
 */
final class AiSleepBackup {

    private AiSleepBackup() { }

    static final int FORMAT = 1;
    static final String AI_DIR = "Assistent", AI_FILE = "assistent.json";
    static final String SLEEP_DIR = "Slaappraat", SLEEP_FILE = "slaappraat.json";
    /** Een stukje is hooguit 60 s (±1,9 MB); groter is geen stukje van Slaappraat. */
    static final long MAX_CLIP = 8L * 1024 * 1024;

    // ---------- zuiver (getest) ----------

    static final int ADD = 0, DUP = 1, BAD = 2, FULL = 3;

    static boolean safeId(String id) { return id != null && id.matches("[A-Za-z0-9_-]{1,40}"); }

    /**
     * Herinneringen terugzetten: per binnenkomende herinnering ADD, DUP (zelfde id, of (bijna) dezelfde tekst als wat
     * er al staat of net is toegevoegd), BAD (geen tekst) of FULL (het geheugen zit vol).
     */
    static int[] memoryPlan(List<String> haveIds, List<String> haveTexts, List<String> inIds, List<String> inTexts, int max) {
        int[] r = new int[inTexts.size()];
        Set<String> ids = new HashSet<>(haveIds);
        List<String> texts = new ArrayList<>(haveTexts);
        int count = haveTexts.size();
        for (int i = 0; i < r.length; i++) {
            String x = AiMemory.clean(inTexts.get(i));
            String id = i < inIds.size() ? inIds.get(i) : null;
            if (AiMemory.words(x).isEmpty()) { r[i] = BAD; continue; }
            if (safeId(id) && ids.contains(id)) { r[i] = DUP; continue; }
            boolean dup = false;
            for (String t : texts) if (AiMemory.similar(t, x)) { dup = true; break; }
            if (dup) { r[i] = DUP; continue; }
            if (count >= max) { r[i] = FULL; continue; }
            r[i] = ADD;
            count++;
            texts.add(x);
            if (safeId(id)) ids.add(id);
        }
        return r;
    }

    /**
     * Gesprekken terugzetten: per binnenkomend gesprek ADD, DUP (id staat er al), BAD (geen geldig id) of FULL
     * (er passen er hooguit max; de nieuwste uit de backup gaan voor, bestaande gesprekken blijven altijd).
     */
    static int[] chatPlan(Set<String> haveIds, int haveCount, final List<String> inIds, final List<Long> inT, int max) {
        int[] r = new int[inIds.size()];
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < r.length; i++) order.add(i);
        Collections.sort(order, (a, b) -> Long.compare(inT.get(b), inT.get(a)));
        Set<String> seen = new HashSet<>(haveIds);
        int count = haveCount;
        for (int i : order) {
            String id = inIds.get(i);
            if (!safeId(id)) r[i] = BAD;
            else if (seen.contains(id)) r[i] = DUP;
            else if (count >= max) r[i] = FULL;
            else { r[i] = ADD; count++; seen.add(id); }
        }
        return r;
    }

    /**
     * Een nacht terugzetten? "" = ja; anders waarom niet: "bad" (geen geldig id), "running" (de nacht die nu loopt),
     * "dup" (staat er al), "old" (ouder dan de bewaartermijn: Slaappraat zou hem meteen weer opruimen).
     */
    static String nightPlan(String id, boolean have, String running, long now, int keepDays) {
        if (!SleepTalk.safeId(id)) return "bad";
        if (id.equals(running)) return "running";
        if (have) return "dup";
        if (Long.parseLong(id) < now - keepDays * 86_400_000L) return "old";
        return "";
    }

    /** Begint het als een WAV-bestand (RIFF…WAVE, met kop)? */
    static boolean isWav(byte[] b, int len) {
        return b != null && len >= 44 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'A' && b[10] == 'V' && b[11] == 'E';
    }

    /** Waar het geluid van een stukje staat, binnen Slaappraat/ in de backup. */
    static String audioKey(String night, String clip) { return night + "/" + clip + ".wav"; }

    // ---------- opschonen (een vreemd bestand mag de app niet breken) ----------

    static String cut(String s, int n) { String x = s == null ? "" : s; return x.length() > n ? x.substring(0, n) : x; }

    static JSONObject cleanChat(JSONObject x) throws Exception {
        JSONArray in = x.optJSONArray("msgs"), msgs = new JSONArray();
        if (in != null) for (int i = Math.max(0, in.length() - 200); i < in.length(); i++) {
            JSONObject m = in.optJSONObject(i);
            if (m == null || !(m.opt("c") instanceof String) || !("user".equals(m.optString("r")) || "ai".equals(m.optString("r")))) continue;
            msgs.put(new JSONObject(m.toString()));
        }
        String title = cut(x.optString("title").replace('\n', ' ').trim(), 80);
        return new JSONObject().put("id", x.optString("id")).put("title", title.isEmpty() ? "Gesprek" : title)
                .put("t", x.optLong("t", System.currentTimeMillis())).put("msgs", msgs);
    }

    /** Een nacht uit de backup, met alleen bekende velden; audio = alleen de stukjes waarvan het geluid echt terug is. */
    static JSONObject cleanNight(JSONObject n, Set<String> withAudio) throws Exception {
        String id = n.optString("id");
        JSONObject o = new JSONObject().put("id", id).put("start", n.optLong("start", Long.parseLong(id))).put("end", n.optLong("end"))
                .put("sens", n.optInt("sens", 2)).put("reason", cut(n.optString("reason"), 120)).put("restored", System.currentTimeMillis());
        JSONArray in = n.optJSONArray("clips"), clips = new JSONArray();
        Set<String> seen = new HashSet<>();
        if (in != null) for (int i = 0; i < in.length(); i++) {
            JSONObject k = in.optJSONObject(i);
            if (k == null || !SleepTalk.safeClip(k.optString("id")) || !seen.add(k.optString("id"))) continue;
            boolean audio = withAudio.contains(k.optString("id")), done = k.optBoolean("done");
            JSONObject c = new JSONObject().put("id", k.optString("id")).put("t", k.optLong("t")).put("dur", k.optDouble("dur", 0))
                    .put("ratio", k.optDouble("ratio", 0)).put("peak", k.optLong("peak")).put("guess", cut(k.optString("guess"), 20))
                    .put("audio", audio).put("done", done).put("full", k.optBoolean("full"));
            if (done) c.put("text", cut(k.optString("text"), 4000)).put("kind", "praten".equals(k.optString("kind")) ? "praten" : "geluid");
            // Nog niet uitgeschreven en het geluid zat niet in de backup: dat is niet meer uit te schrijven
            if (!done && !audio && k.optBoolean("audio")) c.put("nobk", true);
            clips.put(c);
        }
        return o.put("clips", clips).put("heard", clips.length());
    }

    // ---------- grootte (voor de keuze bij Alles back-uppen) ----------

    static JSONObject sizes(Context c) {
        JSONObject o = new JSONObject();
        try {
            File mem = AiMemory.file(c), chats = LocalAi.chatsFile(c);
            o.put("aiMem", AiMemory.count(c)).put("aiChats", LocalAi.chats(c).length()).put("aiBytes", mem.length() + chats.length());
            int nights = 0;
            long text = 0, audio = 0;
            File[] ds = SleepTalk.root(c).listFiles();
            if (ds != null) for (File d : ds) {
                File nf = new File(d, "night.json");
                if (!SleepTalk.safeId(d.getName()) || !nf.isFile()) continue;
                nights++;
                text += nf.length();
                File[] fs = d.listFiles();
                if (fs != null) for (File f : fs) if (f.getName().endsWith(".wav")) audio += f.length();
            }
            o.put("nights", nights).put("sleepText", text).put("sleepAudio", audio);
        } catch (Exception ignored) { }
        return o;
    }

    // ---------- maken ----------

    /** Geheugen en gesprekken naar Assistent/assistent.json. -1 = niets te bewaren. */
    static int exportAi(Context c) throws Exception {
        JSONArray mem = AiMemory.json(c);
        JSONArray chats = LocalAi.chatsStrict(c);
        if (mem.length() == 0 && chats.length() == 0) return -1;
        JSONObject o = new JSONObject().put("format", FORMAT).put("kind", "assistent").put("t", System.currentTimeMillis())
                .put("memory", mem).put("chats", chats);
        WaBackup.Dest dest = new WaBackup.Dest(c.getContentResolver(), WaBackup.destUri(c));
        WaBackup.DestDir dir = dest.dir(AI_DIR, true);
        try (Sms.Out w = Sms.open(dest, dir, AI_FILE, "application/json")) { w.write(o.toString()); w.done(); }
        return mem.length() + chats.length();
    }

    /** Nachten naar Slaappraat/slaappraat.json, met audio ook de WAV-bestanden. Geeft het aantal nachten; -1 = geen. */
    static int exportSleep(Context c, boolean audio) throws Exception {
        JSONArray nights = SleepTalk.allNights(c);
        if (nights.length() == 0) return -1;
        WaBackup.Dest dest = new WaBackup.Dest(c.getContentResolver(), WaBackup.destUri(c));
        WaBackup.DestDir root = dest.dir(SLEEP_DIR, true);
        JSONObject files = new JSONObject();
        int failed = 0;
        if (audio) for (int i = 0; i < nights.length(); i++) {
            JSONObject n = nights.getJSONObject(i);
            String id = n.optString("id");
            JSONArray a = n.optJSONArray("clips");
            if (a != null) for (int j = 0; j < a.length(); j++) {
                if (AllBackup.stop) throw new Exception("Gestopt");
                JSONObject k = a.optJSONObject(j);
                if (k == null || !k.optBoolean("audio")) continue;
                File f = SleepTalk.clipFile(c, id, k.optString("id"));
                if (f == null || !f.isFile() || f.length() > MAX_CLIP) continue;
                byte[] b;
                try { b = PdfTools.readAll(f, MAX_CLIP); } catch (Exception e) { continue; } // net gewist
                try { writeBin(dest, SLEEP_DIR + "/" + id, k.optString("id") + ".wav", b); files.put(audioKey(id, k.optString("id")), b.length); }
                catch (Exception e) { failed++; App.log(c, "BACKUP", "slaappraat " + id + "/" + k.optString("id") + ": " + e.getMessage()); }
            }
        }
        JSONObject o = new JSONObject().put("format", FORMAT).put("kind", "slaappraat").put("t", System.currentTimeMillis())
                .put("audio", audio).put("nights", nights).put("files", files);
        try (Sms.Out w = Sms.open(dest, root, SLEEP_FILE, "application/json")) { w.write(o.toString()); w.done(); }
        // Leesbare backup-map: wat niet meer in slaappraat.json staat (oude nachten, gewiste stukjes, of geluid dat
        // niet meer mee hoeft) weghalen, anders groeit de map eindeloos met geluid dat niet terug te zetten is.
        if (dest.zip == null && failed == 0) prune(dest, root, files);
        if (failed > 0) throw new Exception(nights.length() + " nachten bewaard, geluid van " + failed + (failed == 1 ? " stukje" : " stukjes") + " mislukt");
        return nights.length();
    }

    /** Binair bestand in de backup: in het archief, of veilig (eerst .rt-part) in de backup-map. */
    static void writeBin(WaBackup.Dest dest, String dirPath, String name, byte[] b) throws Exception {
        if (dest.zip != null) { try (OutputStream o = dest.zip.stream(dirPath, name)) { o.write(b); } return; }
        WaBackup.DestDir d = dest.dir(dirPath, true);
        WaBackup.Child old = d.kids.get(name);
        if (old != null && !old.dir && old.size == b.length) return; // staat er al (een stukje verandert nooit)
        String tmpName = name + ".rt-part";
        WaBackup.Child stale = d.kids.remove(tmpName);
        if (stale != null && !stale.dir) try { DocumentsContract.deleteDocument(dest.cr, DocumentsContract.buildDocumentUriUsingTree(dest.tree, stale.docId)); } catch (Exception ignored) { }
        Uri tmp = WaBackup.create(dest, d.uri, "application/octet-stream", tmpName);
        if (tmp == null) throw new Exception("Bestand maken lukt niet: " + name);
        boolean ok = false;
        try {
            try (OutputStream o = dest.cr.openOutputStream(tmp, "w")) {
                if (o == null) throw new Exception("Schrijven lukt niet: " + name);
                o.write(b);
            }
            if (old != null && !old.dir) { DocumentsContract.deleteDocument(dest.cr, DocumentsContract.buildDocumentUriUsingTree(dest.tree, old.docId)); d.kids.remove(name); }
            Uri r = WaBackup.rename(dest, tmp, name);
            if (r == null) throw new Exception("Hernoemen lukt niet: " + name);
            d.kids.put(name, new WaBackup.Child(DocumentsContract.getDocumentId(r), b.length, System.currentTimeMillis(), false));
            ok = true;
        } finally {
            if (!ok) try { DocumentsContract.deleteDocument(dest.cr, tmp); } catch (Exception ignored) { }
        }
    }

    /** Alleen binnen Slaappraat/ en alleen mappen en WAV-bestanden van nachten. */
    private static void prune(WaBackup.Dest dest, WaBackup.DestDir root, JSONObject files) {
        for (Map.Entry<String, WaBackup.Child> e : new ArrayList<>(root.kids.entrySet())) {
            String night = e.getKey();
            if (!e.getValue().dir || !SleepTalk.safeId(night)) continue;
            try {
                WaBackup.DestDir d = dest.dir(SLEEP_DIR + "/" + night, false);
                if (d == null) continue;
                int keep = 0;
                for (Map.Entry<String, WaBackup.Child> f : new ArrayList<>(d.kids.entrySet())) {
                    String name = f.getKey();
                    boolean ours = !f.getValue().dir && (name.endsWith(".wav") || name.endsWith(".wav.rt-part"));
                    if (!ours) { keep++; continue; }
                    if (name.endsWith(".wav") && files.has(night + "/" + name)) { keep++; continue; }
                    DocumentsContract.deleteDocument(dest.cr, DocumentsContract.buildDocumentUriUsingTree(dest.tree, f.getValue().docId));
                    d.kids.remove(name);
                }
                if (keep == 0) { DocumentsContract.deleteDocument(dest.cr, DocumentsContract.buildDocumentUriUsingTree(dest.tree, e.getValue().docId)); root.kids.remove(night); }
            } catch (Exception ignored) { } // opruimen is bijzaak
        }
    }

    // ---------- terugzetten ----------

    private static JSONArray pendingMem, pendingChats;
    private static List<JSONObject> pendingNights;
    /** Geluid dat terug kan: sleutel (nacht/stukje.wav) → grootte; met "tree" uit de backup-map, met "stage" uit het archief. */
    private static Map<String, Long> pendingAudio = new HashMap<>();
    private static Map<String, String> pendingDocs = new HashMap<>();
    private static String pendingSrc = "";

    static JSONObject parse(String text, String kind, String what) throws Exception {
        JSONObject o;
        try { o = new JSONObject(text.trim()); } catch (Exception e) { throw new Exception("Dit is geen " + what); }
        if (!kind.equals(o.optString("kind"))) throw new Exception("Dit is geen " + what);
        if (o.optInt("format", 1) > FORMAT) throw new Exception("Deze backup is gemaakt met een nieuwere versie van Tools; werk de app eerst bij");
        return o;
    }

    static synchronized JSONObject previewAi(Context c, String text) throws Exception {
        JSONObject o = parse(text, "assistent", "backup van de assistent (assistent.json)");
        JSONArray mem = o.optJSONArray("memory"), chats = o.optJSONArray("chats");
        if (mem == null) mem = new JSONArray();
        if (chats == null) chats = new JSONArray();
        List<String> haveIds = new ArrayList<>(), haveTexts = new ArrayList<>(), inIds = new ArrayList<>(), inTexts = new ArrayList<>();
        for (String[] p : AiMemory.pairs(c)) { haveIds.add(p[0]); haveTexts.add(p[1]); }
        for (int i = 0; i < mem.length(); i++) { JSONObject m = mem.optJSONObject(i); inIds.add(m == null ? "" : m.optString("id")); inTexts.add(m == null ? "" : m.optString("text")); }
        int[] mp = memoryPlan(haveIds, haveTexts, inIds, inTexts, AiMemory.MAX);
        JSONArray have = LocalAi.chatsStrict(c);
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < have.length(); i++) { JSONObject x = have.optJSONObject(i); if (x != null) ids.add(x.optString("id")); }
        List<String> cIds = new ArrayList<>();
        List<Long> cT = new ArrayList<>();
        for (int i = 0; i < chats.length(); i++) { JSONObject x = chats.optJSONObject(i); cIds.add(x == null ? "" : x.optString("id")); cT.add(x == null ? 0L : x.optLong("t")); }
        int[] cp = chatPlan(ids, have.length(), cIds, cT, LocalAi.MAX_CHATS);
        int[] m = count(mp), k = count(cp);
        JSONArray sample = new JSONArray();
        for (int i = 0; i < mp.length && sample.length() < 5; i++) if (mp[i] == ADD) sample.put(AiMemory.clean(inTexts.get(i)));
        pendingMem = mem; pendingChats = chats;
        return new JSONObject().put("kind", "ai").put("memTotal", mem.length()).put("memFresh", m[ADD]).put("memDup", m[DUP]).put("memFull", m[FULL])
                .put("chatTotal", chats.length()).put("chatFresh", k[ADD]).put("chatDup", k[DUP]).put("chatFull", k[FULL])
                .put("total", mem.length() + chats.length()).put("fresh", m[ADD] + k[ADD]).put("sample", sample);
    }

    private static int[] count(int[] plan) { int[] n = new int[4]; for (int p : plan) n[p]++; return n; }

    static synchronized int applyAi(Context c) throws Exception {
        JSONArray mem = pendingMem, chats = pendingChats;
        pendingMem = null; pendingChats = null;
        if (mem == null && chats == null) throw new Exception("Niets om terug te zetten");
        int n = 0;
        if (mem != null && mem.length() > 0) n += AiMemory.restore(c, mem);
        if (chats != null && chats.length() > 0) n += LocalAi.chatsMerge(c, chats);
        return n;
    }

    /** fromTree: het geluid zoeken in de backup-map (gewoon terugzetten); anders komt het uit het archief (stageFromArchive). */
    static synchronized JSONObject previewSleep(Context c, String text, boolean fromTree) throws Exception {
        clearStage(c);
        JSONObject o = parse(text, "slaappraat", "Slaappraat-backup (slaappraat.json)");
        JSONArray ns = o.optJSONArray("nights");
        JSONObject files = o.optJSONObject("files");
        if (ns == null) ns = new JSONArray();
        if (files == null) files = new JSONObject();
        String running = SleepService.running() ? SleepService.night : "";
        int keep = SleepTalk.keepDays(c);
        long now = System.currentTimeMillis();
        int fresh = 0, dup = 0, old = 0, run = 0, bad = 0, clips = 0;
        List<JSONObject> list = new ArrayList<>();
        Map<String, Long> want = new HashMap<>();
        Set<String> seen = new HashSet<>();
        JSONArray sample = new JSONArray();
        for (int i = 0; i < ns.length(); i++) {
            JSONObject n = ns.optJSONObject(i);
            String id = n == null ? "" : n.optString("id");
            String why = nightPlan(id, SleepTalk.exists(c, id) || !seen.add(id), running, now, keep);
            if ("bad".equals(why)) { bad++; continue; }
            if ("running".equals(why)) { run++; continue; }
            if ("dup".equals(why)) { dup++; continue; }
            if ("old".equals(why)) { old++; continue; }
            fresh++;
            list.add(n);
            if (sample.length() < 8) sample.put(Long.parseLong(id));
            JSONArray a = n.optJSONArray("clips");
            if (a != null) for (int j = 0; j < a.length(); j++) {
                JSONObject k = a.optJSONObject(j);
                if (k == null || !SleepTalk.safeClip(k.optString("id"))) continue;
                clips++;
                String key = audioKey(id, k.optString("id"));
                long size = files.optLong(key, -1);
                if (k.optBoolean("audio") && size > 44 && size <= MAX_CLIP) want.put(key, size);
            }
        }
        Map<String, Long> found = new HashMap<>();
        Map<String, String> docs = new HashMap<>();
        if (fromTree && !want.isEmpty()) {
            // Het geluid staat naast slaappraat.json in de backup-map (Slaappraat/<nacht>/<stukje>.wav)
            Uri tree = WaBackup.destUri(c);
            if (tree != null) try {
                WaBackup.Dest dest = new WaBackup.Dest(c.getContentResolver(), tree);
                for (Map.Entry<String, Long> e : want.entrySet()) {
                    String[] p = e.getKey().split("/");
                    WaBackup.DestDir d = dest.dir(SLEEP_DIR + "/" + p[0], false);
                    WaBackup.Child ch = d == null ? null : d.kids.get(p[1]);
                    if (ch == null || ch.dir || (ch.size >= 0 && ch.size != e.getValue())) continue;
                    found.put(e.getKey(), e.getValue());
                    docs.put(e.getKey(), ch.docId);
                }
            } catch (Exception e) { App.log(c, "RESTORE", "slaappraat-geluid zoeken: " + e.getMessage()); }
        } else found.putAll(want);
        pendingNights = list; pendingAudio = found; pendingDocs = docs; pendingSrc = fromTree ? "tree" : "stage";
        long bytes = 0;
        for (long b : found.values()) bytes += b;
        return new JSONObject().put("kind", "sleep").put("total", ns.length()).put("fresh", fresh).put("dup", dup).put("old", old)
                .put("running", run).put("bad", bad).put("keep", keep).put("clips", clips).put("audio", found.size()).put("audioBytes", bytes)
                .put("withAudio", o.optBoolean("audio")).put("sample", sample);
    }

    static File stageDir(Context c) { return new File(c.getCacheDir(), "sleep-restore"); }

    static void clearStage(Context c) {
        File[] ds = stageDir(c).listFiles();
        if (ds != null) for (File d : ds) { File[] fs = d.listFiles(); if (fs != null) for (File f : fs) f.delete(); d.delete(); }
        stageDir(c).delete();
    }

    /**
     * Versleutelde backup: het geluid van de nachten die terugkomen alvast uit het (open) archief halen, naar de cache
     * van de app (niet in Android-backups), zodat de sleutel daarna meteen vergeten kan worden.
     */
    static synchronized void stageFromArchive(Context c, JSONObject preview) throws Exception {
        Map<String, Long> want = pendingAudio;
        Map<String, Long> got = new HashMap<>();
        if (!want.isEmpty()) {
            long need = 0;
            for (long b : want.values()) need += b;
            long free;
            try { free = new android.os.StatFs(c.getCacheDir().getPath()).getAvailableBytes(); } catch (Throwable t) { free = Long.MAX_VALUE; }
            if (free < need + 300L * 1024 * 1024) preview.put("noSpace", true);
            else {
                File st = stageDir(c);
                byte[] buf = new byte[1 << 16];
                try (ZipInputStream z = new ZipInputStream(Secure.stream(c))) {
                    ZipEntry e;
                    while ((e = z.getNextEntry()) != null) {
                        String name = e.getName();
                        if (!name.startsWith(SLEEP_DIR + "/")) continue;
                        String key = name.substring(SLEEP_DIR.length() + 1);
                        Long size = want.get(key);
                        if (size == null) continue;
                        String[] p = key.split("/");
                        File d = new File(st, p[0]);
                        d.mkdirs();
                        File f = new File(d, p[1]);
                        long n = 0;
                        byte[] head = new byte[44];
                        try (OutputStream o = new FileOutputStream(f)) {
                            int r;
                            while ((r = z.read(buf)) > 0) {
                                if (n < 44) System.arraycopy(buf, 0, head, (int) n, (int) Math.min(r, 44 - n));
                                n += r;
                                if (n > size) break;
                                o.write(buf, 0, r);
                            }
                        }
                        if (n == size && isWav(head, 44)) got.put(key, size); else f.delete();
                    }
                }
            }
        }
        pendingAudio = got;
        long bytes = 0;
        for (long b : got.values()) bytes += b;
        preview.put("audio", got.size()).put("audioBytes", bytes);
    }

    static int applySleep(Context c) throws Exception {
        List<JSONObject> ns;
        Map<String, Long> audio;
        Map<String, String> docs;
        String src;
        synchronized (AiSleepBackup.class) {
            ns = pendingNights; audio = pendingAudio; docs = pendingDocs; src = pendingSrc;
            pendingNights = null; pendingAudio = new HashMap<>(); pendingDocs = new HashMap<>(); pendingSrc = "";
        }
        if (ns == null) throw new Exception("Niets om terug te zetten");
        int added = 0, lost = 0;
        try {
            Uri tree = WaBackup.destUri(c);
            String running = SleepService.running() ? SleepService.night : "";
            int keep = SleepTalk.keepDays(c);
            for (JSONObject n : ns) {
                String id = n.optString("id");
                // Opnieuw kijken: intussen kan er een nacht begonnen of teruggezet zijn
                if (!nightPlan(id, SleepTalk.exists(c, id), running, System.currentTimeMillis(), keep).isEmpty()) continue;
                File dir = SleepTalk.nightDir(c, id);
                Set<String> withAudio = new HashSet<>();
                JSONArray a = n.optJSONArray("clips");
                if (a != null) for (int j = 0; j < a.length(); j++) {
                    JSONObject k = a.optJSONObject(j);
                    String clip = k == null ? "" : k.optString("id");
                    Long size = SleepTalk.safeClip(clip) ? audio.get(audioKey(id, clip)) : null;
                    if (size == null) continue;
                    try {
                        dir.mkdirs();
                        File out = new File(dir, clip + ".wav");
                        if (!(out.isFile() && out.length() == size)) {
                            File tmp = new File(dir, clip + ".wav.rt-part"); // niet .tmp: dat ruimt SleepTalk.cleanup tussendoor op
                            InputStream in = "stage".equals(src) ? new FileInputStream(new File(new File(stageDir(c), id), clip + ".wav"))
                                    : tree != null && docs.get(audioKey(id, clip)) != null ? c.getContentResolver().openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, docs.get(audioKey(id, clip)))) : null;
                            if (in == null) throw new Exception("niet te openen");
                            byte[] head = new byte[44];
                            long got = 0;
                            try (InputStream i = in; OutputStream o = new FileOutputStream(tmp)) {
                                byte[] buf = new byte[1 << 16];
                                int r;
                                while ((r = i.read(buf)) > 0) {
                                    if (got < 44) System.arraycopy(buf, 0, head, (int) got, (int) Math.min(r, 44 - got));
                                    got += r;
                                    if (got > size) break;
                                    o.write(buf, 0, r);
                                }
                            }
                            if (got != size || !isWav(head, 44) || !tmp.renameTo(out)) { tmp.delete(); throw new Exception("onvolledig"); }
                        }
                        withAudio.add(clip);
                    } catch (Exception e) { lost++; App.log(c, "RESTORE", "slaappraat " + id + "/" + clip + ": " + e.getMessage()); }
                }
                if (SleepTalk.addRestored(c, cleanNight(n, withAudio))) added++;
            }
        } finally { clearStage(c); }
        if (lost > 0) App.log(c, "RESTORE", "slaappraat: geluid van " + lost + " stukjes niet teruggezet");
        return added;
    }
}
