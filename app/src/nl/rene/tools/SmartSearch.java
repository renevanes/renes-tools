package nl.rene.tools;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Slim zoeken: notities en uitgeschreven gesprekken doorzoeken op betekenis. Elk stuk tekst krijgt van het
 * zoekmodel (Qwen3-Embedding, lokaal) een getallenreeks; een vraag ook; de stukken die er het meest op lijken
 * komen bovenaan. De index staat alleen op de telefoon (files/ai/index.json, niet in Android-backups).
 *
 * Het rekenwerk zonder Android (chunks, quant, dot, best) is los getest in SmartSearchTest.
 */
final class SmartSearch {

    private SmartSearch() { }

    static final int DIMS = 512;          // eerste 512 waarden van het zoekmodel (Matryoshka): klein en goed genoeg
    static final int CHUNK = 700, OVERLAP = 120;
    static final float MIN_SCORE = 0.30f;
    static final String QUERY_PREFIX = "Instruct: Given a search query, retrieve relevant passages from personal notes and call transcripts that answer the query\nQuery:";

    // ---------- zuiver rekenwerk (getest) ----------

    /** Tekst in stukken van ongeveer size tekens, met wat overlap, bij voorkeur geknipt na een zin of op een spatie. [begin, eind] */
    static List<int[]> chunks(String text, int size, int overlap) {
        List<int[]> out = new ArrayList<>();
        if (text == null) return out;
        int n = text.length(), start = 0;
        while (start < n && Character.isWhitespace(text.charAt(start))) start++;
        while (start < n) {
            int end = Math.min(n, start + size);
            if (end < n) {
                int best = -1;
                for (int i = end; i > start + size / 2; i--) {
                    char ch = text.charAt(i - 1);
                    if (ch == '.' || ch == '!' || ch == '?' || ch == '\n') { best = i; break; }
                }
                if (best < 0) for (int i = end; i > start + size / 2; i--) if (Character.isWhitespace(text.charAt(i - 1))) { best = i; break; }
                if (best > 0) end = best;
            }
            int e = end;
            while (e > start && Character.isWhitespace(text.charAt(e - 1))) e--;
            if (e > start) out.add(new int[]{start, e});
            if (end >= n) break;
            int next = Math.max(end - overlap, start + 1);
            // de overlap op een woordgrens laten beginnen
            while (next < end && !Character.isWhitespace(text.charAt(next - 1))) next++;
            while (next < n && Character.isWhitespace(text.charAt(next))) next++;
            start = next;
        }
        return out;
    }

    /** Getallen tussen −1 en 1 als bytes (×127). */
    static byte[] quant(float[] v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) Math.max(-127, Math.min(127, Math.round(v[i] * 127f)));
        return b;
    }

    /** Overeenkomst (cosinus, de reeksen hebben lengte 1) tussen een vraag en een opgeslagen stuk. */
    static float dot(float[] q, byte[] v) {
        int n = Math.min(q.length, v.length);
        float s = 0;
        for (int i = 0; i < n; i++) s += q[i] * v[i];
        return s / 127f;
    }

    /** Per bron (sleutel) het beste stuk, dan de beste k bronnen boven min. Geeft indexen in scores. */
    static int[] best(String[] keys, float[] scores, int k, float min) {
        Map<String, Integer> top = new HashMap<>();
        for (int i = 0; i < scores.length; i++) {
            if (scores[i] < min) continue;
            Integer j = top.get(keys[i]);
            if (j == null || scores[i] > scores[j]) top.put(keys[i], i);
        }
        List<Integer> l = new ArrayList<>(top.values());
        Collections.sort(l, (a, b) -> Float.compare(scores[b], scores[a]));
        int m = Math.min(k, l.size());
        int[] out = new int[m];
        for (int i = 0; i < m; i++) out[i] = l.get(i);
        return out;
    }

    static String b64(byte[] b) { return android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP); }
    static byte[] unb64(String s) { return android.util.Base64.decode(s, android.util.Base64.NO_WRAP); }

    // ---------- bronnen: notities en gesprekken ----------

    static final class Doc {
        final String kind, id, title, text; final long t;
        final List<long[]> marks; // per stuk: [begin, eind, tijd in het gesprek (ms)]
        Doc(String kind, String id, String title, String text, long t, List<long[]> marks) { this.kind = kind; this.id = id; this.title = title; this.text = text; this.t = t; this.marks = marks; }
        String key() { return kind + ":" + id; }
        String hash() { return Integer.toHexString(text.hashCode()) + ":" + text.length() + ":" + Integer.toHexString(title.hashCode()); }
    }

    static List<Doc> sources(Context c) {
        List<Doc> out = new ArrayList<>();
        try {
            String raw = Notes.load(c);
            JSONArray a = new JSONArray(raw == null || raw.isEmpty() ? "[]" : raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject n = a.optJSONObject(i);
                if (n == null || n.optString("id").isEmpty()) continue;
                StringBuilder sb = new StringBuilder();
                String title = n.optString("title");
                if (!title.isEmpty()) sb.append(title).append('\n');
                sb.append(n.optString("text"));
                JSONArray items = n.optJSONArray("items");
                if (items != null) for (int j = 0; j < items.length(); j++) {
                    JSONObject it = items.optJSONObject(j);
                    if (it != null && !it.optString("text").isEmpty()) sb.append("\n- ").append(it.optString("text"));
                }
                String text = sb.toString().trim();
                if (text.length() < 3) continue;
                if (text.length() > 30000) text = text.substring(0, 30000);
                out.add(new Doc("notes", n.optString("id"), title.isEmpty() ? "Zonder titel" : title, text, n.optLong("updated"), null));
            }
        } catch (Exception ignored) { }
        File[] ts = Transcribe.transcriptDir(c).listFiles();
        if (ts != null) for (File tf : ts) {
            if (!tf.getName().endsWith(".json")) continue;
            String id = tf.getName().substring(0, tf.getName().length() - 5);
            JSONObject t = Transcribe.loadTranscript(c, id);
            if (t == null) continue;
            JSONArray segs = t.optJSONArray("segments");
            if (segs == null) continue;
            // Segmenten aan elkaar, met per stuk de tijd van het eerste segment erin
            StringBuilder sb = new StringBuilder();
            List<long[]> marks = new ArrayList<>();
            long chunkStart = -1, chunkFrom = 0;
            for (int i = 0; i < segs.length() && sb.length() < 60000; i++) {
                JSONObject s = segs.optJSONObject(i);
                if (s == null) continue;
                String x = s.optString("text").trim();
                if (x.isEmpty()) continue;
                if (chunkStart < 0) { chunkStart = sb.length(); chunkFrom = s.optLong("from"); }
                sb.append(x).append(' ');
                if (sb.length() - chunkStart >= CHUNK) { marks.add(new long[]{chunkStart, sb.length() - 1, chunkFrom}); chunkStart = -1; }
            }
            if (chunkStart >= 0 && sb.length() > chunkStart) marks.add(new long[]{chunkStart, sb.length() - 1, chunkFrom});
            if (marks.isEmpty()) continue;
            String name = t.optString("name", t.optString("file", "Gesprek"));
            out.add(new Doc("tx", id, name.isEmpty() ? "Gesprek" : name, sb.toString(), t.optLong("callDate", t.optLong("mtime")), marks));
        }
        return out;
    }

    // ---------- de index ----------

    static final class Entry {
        final String kind, id, title, text; final long from, t; final byte[] v;
        Entry(String kind, String id, String title, String text, long from, long t, byte[] v) { this.kind = kind; this.id = id; this.title = title; this.text = text; this.from = from; this.t = t; this.v = v; }
    }

    // Eén slot voor de index. Bijwerken kan minuten duren: info() wacht daar nooit op (de interface blijft vlot).
    private static final java.util.concurrent.locks.ReentrantLock LOCK = new java.util.concurrent.locks.ReentrantLock();
    private static List<Entry> entries;   // in het geheugen
    private static volatile long builtAt;
    private static volatile String builtSig = "";
    private static volatile int snapItems, snapChunks;

    private static void snapshot() {
        java.util.Set<String> docs = new java.util.HashSet<>();
        for (Entry e : entries) docs.add(e.kind + ":" + e.id);
        snapItems = docs.size(); snapChunks = entries.size();
    }
    static volatile int busyDone = -1, busyTotal = 0;

    static File file(Context c) { File d = new File(c.getFilesDir(), "ai"); d.mkdirs(); return new File(d, "index.json"); }

    /** Klein vingerafdrukje van de bronnen: is er iets veranderd sinds de vorige keer? */
    static String sourcesSig(Context c) {
        long tx = 0; int n = 0;
        File[] ts = Transcribe.transcriptDir(c).listFiles();
        if (ts != null) for (File f : ts) { tx = tx * 31 + f.lastModified() + f.length(); n++; }
        return Notes.version(c) + ":" + Notes.file(c).lastModified() + ":" + n + ":" + Long.toHexString(tx);
    }

    /** Hoe staat de index ervoor (wacht niet op een lopende update). */
    static JSONObject info(Context c) {
        JSONObject o = new JSONObject();
        try {
            if (entries == null && LOCK.tryLock()) { try { load(c); } finally { LOCK.unlock(); } }
            o.put("items", snapItems).put("chunks", snapChunks).put("at", builtAt).put("stale", !builtSig.equals(sourcesSig(c)));
            if (busyDone >= 0) o.put("busy", true).put("done", busyDone).put("total", busyTotal);
        } catch (Exception ignored) { }
        return o;
    }

    private static void load(Context c) {
        if (entries != null) return;
        entries = new ArrayList<>();
        File f = file(c);
        if (!f.isFile()) { snapshot(); return; }
        try {
            JSONObject o = new JSONObject(Contacts.readText(f, false));
            if (o.optInt("dims") != DIMS || !LocalAi.EMBED.sha.equals(o.optString("model"))) { snapshot(); return; } // ander model: opnieuw
            builtAt = o.optLong("at"); builtSig = o.optString("sig");
            JSONArray a = o.optJSONArray("e");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.getJSONObject(i);
                entries.add(new Entry(e.optString("k"), e.optString("id"), e.optString("ti"), e.optString("x"), e.optLong("f"), e.optLong("t"), unb64(e.optString("v"))));
            }
        } catch (Exception e) { entries = new ArrayList<>(); }
        snapshot();
    }

    private static Map<String, String> hashes = new HashMap<>();

    private static void save(Context c) throws Exception {
        JSONArray a = new JSONArray();
        for (Entry e : entries) a.put(new JSONObject().put("k", e.kind).put("id", e.id).put("ti", e.title).put("x", e.text).put("f", e.from).put("t", e.t).put("v", b64(e.v)));
        JSONObject h = new JSONObject();
        for (Map.Entry<String, String> x : hashes.entrySet()) h.put(x.getKey(), x.getValue());
        Contacts.writeText(file(c), new JSONObject().put("v", 1).put("dims", DIMS).put("model", LocalAi.EMBED.sha).put("at", builtAt).put("sig", builtSig).put("h", h).put("e", a).toString(), false);
    }

    private static void loadHashes(Context c) {
        hashes = new HashMap<>();
        try {
            File f = file(c);
            if (!f.isFile() || entries == null || entries.isEmpty()) return;
            JSONObject h = new JSONObject(Contacts.readText(f, false)).optJSONObject("h");
            if (h != null) for (java.util.Iterator<String> k = h.keys(); k.hasNext(); ) { String key = k.next(); hashes.put(key, h.optString(key)); }
        } catch (Exception ignored) { }
    }

    interface Progress { void step(int done, int total); }

    /**
     * Index bijwerken: alleen wat nieuw of veranderd is opnieuw door het zoekmodel. Draait op de AI-thread
     * (LocalAi), die het zoekmodel start. force = alles opnieuw.
     */
    static void update(Context c, boolean force, Progress p) throws Exception {
        LOCK.lock();
        try { updateLocked(c, force, p); } finally { LOCK.unlock(); }
    }

    private static void updateLocked(Context c, boolean force, Progress p) throws Exception {
        load(c);
        if (force) { entries = new ArrayList<>(); hashes = new HashMap<>(); } else if (hashes.isEmpty()) loadHashes(c);
        String sig = sourcesSig(c);
        List<Doc> docs = sources(c);
        Map<String, Doc> byKey = new LinkedHashMap<>();
        for (Doc d : docs) byKey.put(d.key(), d);
        // Weg of veranderd: eruit
        List<Entry> keep = new ArrayList<>();
        for (Entry e : entries) {
            String k = e.kind + ":" + e.id;
            Doc d = byKey.get(k);
            if (d != null && d.hash().equals(hashes.get(k))) keep.add(e);
        }
        entries = keep;
        java.util.Set<String> have = new java.util.HashSet<>();
        for (Entry e : entries) have.add(e.kind + ":" + e.id);
        hashes.keySet().retainAll(have);
        // Nieuw of veranderd: stukken maken
        List<Doc> todo = new ArrayList<>();
        for (Doc d : docs) if (!have.contains(d.key())) todo.add(d);
        List<Object[]> pieces = new ArrayList<>(); // {doc, text, from}
        for (Doc d : todo) {
            if (d.marks != null) {
                int n = 0;
                for (long[] m : d.marks) { if (n++ >= 80) break; pieces.add(new Object[]{d, d.text.substring((int) m[0], (int) Math.min(d.text.length(), m[1])), m[2]}); }
            } else {
                int n = 0;
                for (int[] r : chunks(d.text, CHUNK, OVERLAP)) { if (n++ >= 40) break; pieces.add(new Object[]{d, d.text.substring(r[0], r[1]), (long) r[0]}); }
            }
        }
        busyTotal = pieces.size(); busyDone = 0;
        try {
            if (p != null) p.step(0, pieces.size());
            final int BATCH = 8;
            for (int i = 0; i < pieces.size(); i += BATCH) {
                List<String> texts = new ArrayList<>();
                int end = Math.min(pieces.size(), i + BATCH);
                for (int j = i; j < end; j++) { Doc d = (Doc) pieces.get(j)[0]; texts.add((d.kind.equals("tx") ? "Gesprek met " : "Notitie: ") + d.title + "\n" + pieces.get(j)[1]); }
                List<float[]> vs = LocalAi.embed(c, texts, DIMS);
                for (int j = i; j < end; j++) {
                    Object[] pc = pieces.get(j); Doc d = (Doc) pc[0];
                    entries.add(new Entry(d.kind, d.id, d.title, (String) pc[1], (Long) pc[2], d.t, quant(vs.get(j - i))));
                }
                // Een bron is pas klaar als al zijn stukken er zijn
                for (int j = i; j < end; j++) {
                    Doc d = (Doc) pieces.get(j)[0];
                    boolean last = j + 1 >= pieces.size() || pieces.get(j + 1)[0] != d;
                    if (last) hashes.put(d.key(), d.hash());
                }
                busyDone = end;
                snapshot();
                if (p != null) p.step(end, pieces.size());
                if (LocalAi.cancelled()) throw new Exception("Gestopt");
            }
            // Bronnen zonder stukken (te kort) ook als klaar markeren
            for (Doc d : todo) if (!hashes.containsKey(d.key())) hashes.put(d.key(), d.hash());
            builtAt = System.currentTimeMillis();
            builtSig = sig;
        } finally {
            busyDone = -1;
            snapshot();
            save(c); // ook halverwege: wat klaar is, hoeft de volgende keer niet opnieuw
        }
    }

    /** Zoeken op betekenis: [{kind, id, title, snip, from, t, score}]. Werkt de index eerst bij als er iets veranderde. */
    static JSONArray search(Context c, String q, int k) throws Exception {
        LOCK.lock();
        try {
            load(c);
            if (!builtSig.equals(sourcesSig(c))) updateLocked(c, false, null);
        } finally { LOCK.unlock(); }
        List<float[]> qv = LocalAi.embed(c, java.util.Collections.singletonList(QUERY_PREFIX + q), DIMS);
        float[] v = qv.get(0);
        List<Entry> es;
        LOCK.lock();
        try { es = new ArrayList<>(entries); } finally { LOCK.unlock(); }
        String[] keys = new String[es.size()];
        float[] sc = new float[es.size()];
        for (int i = 0; i < es.size(); i++) { keys[i] = es.get(i).kind + ":" + es.get(i).id; sc[i] = dot(v, es.get(i).v); }
        JSONArray out = new JSONArray();
        for (int i : best(keys, sc, k, MIN_SCORE)) {
            Entry e = es.get(i);
            out.put(new JSONObject().put("kind", e.kind).put("id", e.id).put("title", e.title).put("snip", e.text)
                    .put("from", e.from).put("t", e.t).put("score", Math.round(sc[i] * 1000) / 1000.0));
        }
        return out;
    }

    static void clear(Context c) {
        LOCK.lock();
        try { entries = new ArrayList<>(); hashes = new HashMap<>(); builtAt = 0; builtSig = ""; snapshot(); file(c).delete(); }
        finally { LOCK.unlock(); }
    }
}
