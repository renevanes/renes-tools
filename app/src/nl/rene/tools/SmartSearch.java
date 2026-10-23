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

    /** Hoe een bron heet vóór de titel (voor het zoekmodel en voor de assistent). Zuiver (getest). */
    static String kindLabel(String kind) {
        switch (kind == null ? "" : kind) {
            case "tx": return "Gesprek met ";
            case "sms": return "Sms met ";
            case "wa": return "WhatsApp met ";
            case "calls": return "Oproepen met ";
            case "contacts": return "Contact ";
            default: return "Notitie: ";
        }
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
        if (LocalAi.srcOn(c, "notes")) try {
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
        File[] ts = LocalAi.srcOn(c, "tx") ? Transcribe.transcriptDir(c).listFiles() : null;
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
        // Alleen wat je bij Lokale AI → Gegevens aanzette (en waar de app toestemming voor heeft)
        if (LocalAi.srcOn(c, "sms") && Search.has(c, android.Manifest.permission.READ_SMS)) try { smsDocs(c, out); } catch (Throwable e) { App.log(c, "AI", "sms: " + e); }
        if (LocalAi.srcOn(c, "wa") && WaChats.dbFile(c).isFile()) try { waDocs(c, out); } catch (Throwable e) { App.log(c, "AI", "whatsapp: " + e); }
        if (LocalAi.srcOn(c, "calls") && Search.has(c, android.Manifest.permission.READ_CALL_LOG)) try { callDocs(c, out); } catch (Throwable e) { App.log(c, "AI", "oproepen: " + e); }
        if (LocalAi.srcOn(c, "contacts") && Search.has(c, android.Manifest.permission.READ_CONTACTS)) try { contactDocs(c, out); } catch (Throwable e) { App.log(c, "AI", "contacten: " + e); }
        return out;
    }

    private static final java.text.SimpleDateFormat DF = new java.text.SimpleDateFormat("d MMM yyyy HH:mm", new java.util.Locale("nl", "NL"));
    private static synchronized String when(long t) { return t > 0 ? DF.format(new java.util.Date(t)) : ""; }

    /** Regels (bijv. berichten, oudste eerst) aan elkaar, in stukken van hooguit CHUNK tekens; per stuk de tijd van de
     *  eerste regel. Hooguit ±60000 tekens: de nieuwste regels. Een stuk eindigt ook (vanaf de helft) vóór een regel die
     *  daar "zin in heeft" (afhankelijk van de regel zelf): komt er een bericht bij of valt het oudste weg, dan vallen
     *  de grenzen na een paar stukken weer op dezelfde plek en hoeven alleen de nieuwe stukken door het model. Zuiver (getest). */
    static Doc linesDoc(String kind, String id, String title, List<String> lines, List<Long> times, long t) {
        StringBuilder sb = new StringBuilder();
        List<long[]> marks = new ArrayList<>();
        long start = -1, from = 0;
        int first = lines.size(), total = 0;
        while (first > 0) {
            String x = lines.get(first - 1);
            int len = x == null ? 0 : Math.min(CHUNK, x.trim().length()) + 1;
            if (total + len > 60000) break;
            total += len; first--;
        }
        for (int i = first; i < lines.size(); i++) {
            String x = lines.get(i);
            if (x == null || x.trim().isEmpty()) continue;
            x = x.trim();
            if (x.length() > CHUNK) x = x.substring(0, CHUNK);
            int size = start < 0 ? 0 : (int) (sb.length() - start);
            if (start >= 0 && (size + x.length() + 1 > CHUNK || (size >= CHUNK / 2 && (mix(x.hashCode()) & 3) == 0))) { marks.add(new long[]{start, sb.length() - 1, from}); start = -1; }
            if (start < 0) { start = sb.length(); from = times == null || i >= times.size() ? 0 : times.get(i); }
            sb.append(x.trim()).append('\n');
        }
        if (start >= 0 && sb.length() > start) marks.add(new long[]{start, sb.length() - 1, from});
        return marks.isEmpty() ? null : new Doc(kind, id, title, sb.toString(), t, marks);
    }

    /** Bits goed door elkaar (de laagste bits van String.hashCode lijken bij soortgelijke regels te veel op elkaar). */
    static int mix(int h) { h ^= h >>> 16; h *= 0x85ebca6b; h ^= h >>> 13; h *= 0xc2b2ae35; return h ^ (h >>> 16); }

    /** Sms: per gesprek de laatste 300 berichten (de 150 recentste gesprekken). */
    private static void smsDocs(Context c, List<Doc> out) {
        List<Sms.Msg> all = Sms.read(c);
        List<Sms.Conv> convs = Sms.conversations(c, all);
        java.util.Collections.sort(convs, (x, y) -> Long.compare(y.last, x.last));
        java.util.Map<Long, Sms.Conv> pick = new java.util.HashMap<>();
        for (int i = 0; i < convs.size() && i < 150; i++) pick.put(convs.get(i).threadId, convs.get(i));
        java.util.Map<Long, List<Sms.Msg>> by = new java.util.HashMap<>();
        for (Sms.Msg m : all) if (pick.containsKey(m.threadId)) { List<Sms.Msg> l = by.get(m.threadId); if (l == null) { l = new ArrayList<>(); by.put(m.threadId, l); } l.add(m); }
        for (Sms.Conv cv : pick.values()) {
            List<Sms.Msg> l = by.get(cv.threadId);
            if (l == null) continue;
            List<String> lines = new ArrayList<>(); List<Long> times = new ArrayList<>();
            String who = cv.name == null || cv.name.isEmpty() ? (cv.address == null ? "Onbekend" : cv.address) : cv.name;
            for (int i = Math.max(0, l.size() - 300); i < l.size(); i++) {
                Sms.Msg m = l.get(i);
                if (m.body == null || m.body.trim().isEmpty()) continue;
                boolean me = m.type == 2 || m.type == 4 || m.type == 5 || m.type == 6;
                lines.add("[" + when(m.date) + "] " + (me ? "Ik" : who) + ": " + m.body.replace('\n', ' '));
                times.add(m.date);
            }
            Doc d = linesDoc("sms", String.valueOf(cv.threadId), who, lines, times, cv.last);
            if (d != null) out.add(d);
        }
    }

    /** WhatsApp (alleen als je de chats leesbaar maakte): per chat de laatste 300 berichten (100 recentste chats). */
    private static void waDocs(Context c, List<Doc> out) throws Exception {
        JSONArray chats = new JSONObject(WaChats.chatsJson(c)).optJSONArray("chats");
        if (chats == null) return;
        List<JSONObject> l = new ArrayList<>();
        for (int i = 0; i < chats.length(); i++) l.add(chats.getJSONObject(i));
        java.util.Collections.sort(l, (x, y) -> Long.compare(y.optLong("last"), x.optLong("last")));
        for (int i = 0; i < l.size() && i < 100; i++) {
            JSONObject ch = l.get(i);
            JSONArray ms = new JSONArray(WaChats.messagesJson(c, ch.optLong("id"), 0, 300));
            List<String> lines = new ArrayList<>(); List<Long> times = new ArrayList<>();
            for (int k = 0; k < ms.length(); k++) {
                JSONObject m = ms.getJSONObject(k);
                String text = m.optString("text");
                if (text.isEmpty()) text = m.optString("label");
                if (text.isEmpty()) continue;
                lines.add("[" + when(m.optLong("t")) + "] " + (m.optBoolean("me") ? "Ik" : m.optString("sender", ch.optString("name"))) + ": " + text.replace('\n', ' '));
                times.add(m.optLong("t"));
            }
            Doc d = linesDoc("wa", String.valueOf(ch.optLong("id")), ch.optString("name", "WhatsApp"), lines, times, ch.optLong("last"));
            if (d != null) out.add(d);
        }
    }

    /** Oproepen: per persoon (of nummer) een lijstje van de laatste 50 oproepen. */
    private static void callDocs(Context c, List<Doc> out) {
        List<Calls.Call> all = Calls.read(c, 2000);
        java.util.LinkedHashMap<String, List<Calls.Call>> by = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> names = new java.util.HashMap<>();
        for (Calls.Call k : all) {
            String num = k.number == null ? "" : k.number;
            if (num.isEmpty() || k.presentation != 1) continue;
            List<Calls.Call> l = by.get(num);
            if (l == null) { l = new ArrayList<>(); by.put(num, l); names.put(num, Calls.name(c, k)); }
            if (l.size() < 50) l.add(k);
        }
        int n = 0;
        for (java.util.Map.Entry<String, List<Calls.Call>> e : by.entrySet()) {
            if (n++ >= 300) break;
            String who = names.get(e.getKey());
            List<String> lines = new ArrayList<>(); List<Long> times = new ArrayList<>();
            lines.add("Oproepen met " + who + (who.equals(e.getKey()) ? "" : " (" + e.getKey() + ")") + ":");
            times.add(e.getValue().get(0).date);
            long last = 0;
            for (Calls.Call k : e.getValue()) {
                String kind = Calls.kind(k.type);
                String label = "in".equals(kind) ? "inkomend" : "out".equals(kind) ? "uitgaand" : "missed".equals(kind) ? "gemist" : "rejected".equals(kind) ? "geweigerd" : "oproep";
                lines.add("- " + when(k.date) + ", " + label + (k.duration > 0 ? ", " + (k.duration / 60) + " min " + (k.duration % 60) + " s" : ""));
                times.add(k.date); last = Math.max(last, k.date);
            }
            Doc d = linesDoc("calls", e.getKey(), who, lines, times, last);
            if (d != null) out.add(d);
        }
    }

    /** Contacten: naam en de velden (nummers, e-mail, adres, verjaardag, notitie …). */
    private static void contactDocs(Context c, List<Doc> out) throws Exception {
        int n = 0;
        for (ContactsDiff.Rec r : Contacts.readCached(c)) {
            if (n++ >= 3000) break;
            StringBuilder sb = new StringBuilder("Contact: ").append(r.name == null ? "" : r.name);
            for (java.util.Map.Entry<String, String> f : r.f.entrySet()) {
                String v = f.getValue();
                if (v == null || v.isEmpty() || f.getKey().startsWith("photo")) continue;
                sb.append('\n').append(f.getKey()).append(": ").append(v.length() > 400 ? v.substring(0, 400) : v);
            }
            String text = sb.toString();
            if (text.length() > CHUNK * 2) text = text.substring(0, CHUNK * 2);
            out.add(new Doc("contacts", r.key + "|" + r.id, r.name == null || r.name.isEmpty() ? "Contact" : r.name, text, r.updated, null));
        }
    }

    // ---------- de index ----------

    static final class Entry {
        final String kind, id, title, text; final long from, t; final byte[] v;
        Entry(String kind, String id, String title, String text, long from, long t, byte[] v) { this.kind = kind; this.id = id; this.title = title; this.text = text; this.from = from; this.t = t; this.v = v; }
    }

    // LOCK: kort, voor het laden en het wisselen van de lijst. UPD: één update tegelijk (kan bij sms en WhatsApp minuten
    // duren). Zoeken wacht nooit op UPD: het zoekt in de lijst die er op dat moment is (die wordt per stukje bijgewerkt).
    private static final java.util.concurrent.locks.ReentrantLock LOCK = new java.util.concurrent.locks.ReentrantLock();
    private static final java.util.concurrent.locks.ReentrantLock UPD = new java.util.concurrent.locks.ReentrantLock();
    private static volatile List<Entry> entries;   // in het geheugen; nooit veranderen, alleen vervangen (publish)
    /** Een lopende update stoppen (bron uitgezet, zoekmodel verwijderd). */
    static volatile boolean stopUpdate;
    static boolean updating() { return UPD.isLocked(); }
    private static volatile long builtAt;
    private static volatile String builtSig = "";
    private static volatile int snapItems, snapChunks;

    private static void snapshot() {
        List<Entry> es = entries;
        java.util.Set<String> docs = new java.util.HashSet<>();
        if (es != null) for (Entry e : es) docs.add(e.kind + ":" + e.id);
        snapItems = docs.size(); snapChunks = es == null ? 0 : es.size();
    }
    private static void publish(List<Entry> work) {
        LOCK.lock();
        try { entries = java.util.Collections.unmodifiableList(new ArrayList<>(work)); snapshot(); } finally { LOCK.unlock(); }
    }
    private static List<Entry> current(Context c) {
        LOCK.lock();
        try { load(c); return entries; } finally { LOCK.unlock(); }
    }
    static volatile int busyDone = -1, busyTotal = 0;

    static File file(Context c) { File d = new File(c.getFilesDir(), "ai"); d.mkdirs(); return new File(d, "index.json"); }

    /** Klein vingerafdrukje van de bronnen: is er iets veranderd sinds de vorige keer? */
    static String sourcesSig(Context c) {
        long tx = 0; int n = 0;
        File[] ts = Transcribe.transcriptDir(c).listFiles();
        if (ts != null) for (File f : ts) { tx = tx * 31 + f.lastModified() + f.length(); n++; }
        StringBuilder s = new StringBuilder().append(Notes.version(c)).append(':').append(Notes.file(c).lastModified()).append(':').append(n).append(':').append(Long.toHexString(tx));
        // Welke bronnen aan staan, en of daar iets veranderde (goedkoop: aantallen en tijden)
        for (String k : LocalAi.SOURCES) s.append(LocalAi.srcOn(c, k) ? '1' : '0');
        try {
            if (LocalAi.srcOn(c, "sms") && Search.has(c, android.Manifest.permission.READ_SMS)) s.append(":s").append(Sms.countTotal(c));
            if (LocalAi.srcOn(c, "wa")) s.append(":w").append(WaChats.dbFile(c).lastModified());
            if (LocalAi.srcOn(c, "calls") && Search.has(c, android.Manifest.permission.READ_CALL_LOG)) { List<Calls.Call> l = Calls.read(c, 1); s.append(":c").append(l.isEmpty() ? 0 : l.get(0).date); }
            if (LocalAi.srcOn(c, "contacts") && Search.has(c, android.Manifest.permission.READ_CONTACTS)) s.append(":k").append(contactsStamp(c));
        } catch (Throwable ignored) { }
        return s.toString();
    }

    /** Aantal contacten en de laatste wijziging (zonder alle contacten te lezen). */
    private static String contactsStamp(Context c) {
        try (android.database.Cursor cur = c.getContentResolver().query(android.provider.ContactsContract.Contacts.CONTENT_URI,
                new String[]{android.provider.ContactsContract.Contacts._ID}, null, null, null)) {
            long max = 0;
            try (android.database.Cursor m = c.getContentResolver().query(android.provider.ContactsContract.Contacts.CONTENT_URI,
                    new String[]{android.provider.ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP}, null, null,
                    android.provider.ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP + " DESC")) {
                if (m != null && m.moveToFirst()) max = m.getLong(0);
            }
            return (cur == null ? 0 : cur.getCount()) + "/" + max;
        } catch (Throwable t) { return "?"; }
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

    /** Onder LOCK. */
    private static void load(Context c) {
        if (entries != null) return;
        List<Entry> es = new ArrayList<>();
        File f = file(c);
        try {
            JSONObject o = f.isFile() ? new JSONObject(Contacts.readText(f, false)) : null;
            // ander model: opnieuw
            if (o != null && o.optInt("dims") == DIMS && LocalAi.EMBED.sha.equals(o.optString("model"))) {
                builtAt = o.optLong("at"); builtSig = o.optString("sig");
                JSONArray a = o.optJSONArray("e");
                if (a != null) for (int i = 0; i < a.length(); i++) {
                    JSONObject e = a.getJSONObject(i);
                    es.add(new Entry(e.optString("k"), e.optString("id"), e.optString("ti"), e.optString("x"), e.optLong("f"), e.optLong("t"), unb64(e.optString("v"))));
                }
            }
        } catch (Exception e) { es = new ArrayList<>(); }
        entries = java.util.Collections.unmodifiableList(es);
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

    private static void loadHashes(Context c, List<Entry> es) {
        hashes = new HashMap<>();
        try {
            File f = file(c);
            if (!f.isFile() || es == null || es.isEmpty()) return;
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
        UPD.lock();
        try { updateLocked(c, force, p); } finally { UPD.unlock(); }
    }

    /** Sleutel om een stuk met precies dezelfde tekst niet opnieuw door het model te halen. */
    private static String pieceKey(String kind, String id, String text) { return kind + ":" + id + "\u0000" + text; }

    private static void updateLocked(Context c, boolean force, Progress p) throws Exception {
        List<Entry> entries = new ArrayList<>(current(c));
        if (force) { entries = new ArrayList<>(); hashes = new HashMap<>(); } else if (hashes.isEmpty()) loadHashes(c, entries);
        // Bij een nieuw bericht verandert het hele gesprek, maar de meeste stukken niet: die houden hun vector
        Map<String, byte[]> reuse = new HashMap<>();
        for (Entry e : entries) reuse.put(pieceKey(e.kind, e.id, e.text), e.v);
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
        List<Object[]> pieces = new ArrayList<>(); // {doc, text, from, vector (al bekend) of null}
        for (Doc d : todo) {
            if (d.marks != null) {
                // Berichten: de nieuwste stukken (achteraan); gesprekken: het begin
                boolean msgs = !"tx".equals(d.kind);
                int from = msgs ? Math.max(0, d.marks.size() - 80) : 0, to = msgs ? d.marks.size() : Math.min(80, d.marks.size());
                for (int i = from; i < to; i++) { long[] m = d.marks.get(i); String x = d.text.substring((int) m[0], (int) Math.min(d.text.length(), m[1])); pieces.add(new Object[]{d, x, m[2], reuse.get(pieceKey(d.kind, d.id, x))}); }
            } else {
                int n = 0;
                for (int[] r : chunks(d.text, CHUNK, OVERLAP)) { if (n++ >= 40) break; String x = d.text.substring(r[0], r[1]); pieces.add(new Object[]{d, x, (long) r[0], reuse.get(pieceKey(d.kind, d.id, x))}); }
            }
        }
        reuse = null;
        int need = 0;
        for (Object[] pc : pieces) if (pc[3] == null) need++;
        busyTotal = need; busyDone = 0;
        try {
            if (p != null) p.step(0, need);
            final int BATCH = 8;
            int i = 0, done = 0;
            while (i < pieces.size()) {
                // Tot BATCH stukken die nog door het model moeten (bekende stukken gaan gewoon mee)
                List<String> texts = new ArrayList<>();
                int end = i;
                while (end < pieces.size() && (texts.size() < BATCH || pieces.get(end)[3] != null)) {
                    Object[] pc = pieces.get(end); Doc d = (Doc) pc[0];
                    if (pc[3] == null) texts.add(kindLabel(d.kind) + d.title + "\n" + pc[1]);
                    end++;
                }
                List<float[]> vs = texts.isEmpty() ? new ArrayList<float[]>() : LocalAi.embed(c, texts, DIMS, true);
                int t = 0;
                for (int j = i; j < end; j++) {
                    Object[] pc = pieces.get(j); Doc d = (Doc) pc[0];
                    byte[] v = pc[3] != null ? (byte[]) pc[3] : quant(vs.get(t++));
                    entries.add(new Entry(d.kind, d.id, d.title, (String) pc[1], (Long) pc[2], d.t, v));
                    // Een bron is pas klaar als al zijn stukken er zijn
                    boolean last = j + 1 >= pieces.size() || pieces.get(j + 1)[0] != d;
                    if (last) hashes.put(d.key(), d.hash());
                }
                done += texts.size();
                i = end;
                busyDone = done;
                publish(entries);
                if (p != null) p.step(done, need);
                if (stopUpdate) throw new Exception("Gestopt");
            }
            // Bronnen zonder stukken (te kort) ook als klaar markeren
            for (Doc d : todo) if (!hashes.containsKey(d.key())) hashes.put(d.key(), d.hash());
            builtAt = System.currentTimeMillis();
            builtSig = sig;
        } finally {
            busyDone = -1;
            publish(entries);
            save(c); // ook halverwege: wat klaar is, hoeft de volgende keer niet opnieuw
        }
    }

    /** Zo lang wacht een zoekvraag hooguit op het bijwerken (een nieuwe notitie of een paar berichten is dan klaar). */
    static final long WAIT_MS = 12_000L;

    /** Zoeken op betekenis: [{kind, id, title, snip, from, t, score}]. Veranderde er iets, dan wordt de index op de
     *  achtergrond bijgewerkt; er wordt even op gewacht, daarna wordt gezocht in wat er al is. */
    static JSONArray search(Context c, String q, int k) throws Exception {
        current(c);
        if (!builtSig.equals(sourcesSig(c))) {
            LocalAi.indexLater(c);
            long until = System.currentTimeMillis() + WAIT_MS;
            Thread.sleep(50); // de index-thread even laten beginnen
            while ((updating() || LocalAi.indexQueued()) && System.currentTimeMillis() < until) Thread.sleep(200);
        }
        List<float[]> qv = LocalAi.embed(c, java.util.Collections.singletonList(QUERY_PREFIX + q), DIMS);
        float[] v = qv.get(0);
        List<Entry> es = current(c);
        String[] keys = new String[es.size()];
        float[] sc = new float[es.size()];
        for (int i = 0; i < es.size(); i++) { keys[i] = es.get(i).kind + ":" + es.get(i).id; sc[i] = dot(v, es.get(i).v); }
        JSONArray out = new JSONArray();
        // Bronnen die intussen uit staan, nooit tonen (ook als de index nog niet is bijgewerkt)
        for (int i = 0; i < es.size(); i++) if (!LocalAi.srcOn(c, es.get(i).kind)) sc[i] = -1;
        for (int i : best(keys, sc, k, MIN_SCORE)) {
            Entry e = es.get(i);
            out.put(new JSONObject().put("kind", e.kind).put("id", e.id).put("title", e.title).put("snip", e.text)
                    .put("from", e.from).put("t", e.t).put("score", Math.round(sc[i] * 1000) / 1000.0));
        }
        return out;
    }

    static void clear(Context c) {
        UPD.lock();
        try {
            LOCK.lock();
            try { entries = java.util.Collections.unmodifiableList(new ArrayList<Entry>()); hashes = new HashMap<>(); builtAt = 0; builtSig = ""; snapshot(); file(c).delete(); }
            finally { LOCK.unlock(); }
        } finally { UPD.unlock(); }
    }
}
