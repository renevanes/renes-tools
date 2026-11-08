package nl.rene.tools;

import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Eigen kopieën van boeken en leesposities; gaan mee in Alle appgegevens. */
final class Ereader {
    private Ereader() { }
    static File root(Context c) { return new File(c.getFilesDir(), "ereader"); }
    static File dir(Context c, String id) throws Exception {
        if (id == null || !id.matches("[a-f0-9]{64}")) throw new Exception(Taal.T("Dit boek staat niet meer in de boekenlijst"));
        return new File(root(c), id);
    }
    static String read(File file, int max) throws Exception {
        try (InputStream in = new AtomicFile(file).openRead()) { return new String(EreaderBook.read(in, max), StandardCharsets.UTF_8); }
    }
    static void write(File f, String value) throws Exception { writeBytes(f, value.getBytes(StandardCharsets.UTF_8)); }
    static void writeBytes(File f, byte[] value) throws Exception {
        if (!f.getParentFile().isDirectory() && !f.getParentFile().mkdirs()) throw new Exception(Taal.T("Boek opslaan lukt niet"));
        AtomicFile a = new AtomicFile(f); FileOutputStream out = null;
        try { out = a.startWrite(); out.write(value); a.finishWrite(out); }
        catch (Throwable e) { if (out != null) a.failWrite(out); throw new Exception(Taal.T("Boek opslaan lukt niet")); }
    }
    private static File dataDir(Context c, String id, JSONObject meta) throws Exception {
        return meta.optInt("edition") >= 3 ? new File(dir(c, id), "content-v3") : dir(c, id);
    }
    static synchronized JSONObject meta(Context c, String id) throws Exception { return new JSONObject(read(new File(dir(c, id), "boek.json"), 4 * 1024 * 1024)); }
    static synchronized JSONArray list(Context c) throws Exception {
        JSONArray out = new JSONArray(); File[] dirs = root(c).listFiles();
        if (dirs != null) for (File d : dirs) {
            if (!d.getName().matches("[a-f0-9]{64}") || !new File(d, "boek.json").isFile()) continue;
            out.put(meta(c, d.getName()));
        }
        return out;
    }
    static JSONObject add(Context c, Uri uri) throws Exception { return add(c, uri, Voortgang.GEEN); }
    /** Boek toevoegen; v meldt lezen (bytes), verwerken en opslaan (hoofdstukken en afbeeldingen) aan de balk onderin. */
    static synchronized JSONObject add(Context c, Uri uri, Voortgang v) throws Exception {
        String name = PdfTools.displayName(c, uri);
        byte[] bytes;
        try (InputStream in = v.open(c, uri)) {
            if (in == null) throw new Exception(Taal.T("Boek openen lukt niet"));
            bytes = EreaderBook.read(in, EreaderBook.MAX_FILE);
        }
        StringBuilder hash = new StringBuilder();
        for (byte h : MessageDigest.getInstance("SHA-256").digest(bytes)) hash.append(String.format(java.util.Locale.ROOT, "%02x", h & 255));
        String id = hash.toString(); File d = dir(c, id);
        JSONObject previous = new File(d, "boek.json").isFile() ? meta(c, id) : null;
        if (previous != null && previous.optInt("edition") >= 3) return details(c, id);
        v.stap("verwerken", 0, 0);
        EreaderBook b = EreaderBook.parse(bytes, name);
        final int delen = b.chapters.size() + b.assets.size();
        File stage = new File(d, "content-v3");
        JSONObject m;
        JSONArray marks;
        if (previous != null) {
            File old = dataDir(c, id, previous);
            EreaderUpgrade upgrade = new EreaderUpgrade(b, index -> read(new File(old, index + ".txt"), EreaderBook.MAX_FILE));
            m = upgrade.metadata(previous);
            File annotations = new File(old, "leeswijzers.json");
            marks = upgrade.marks(annotations.isFile() ? new JSONArray(read(annotations, 4 * 1024 * 1024)) : new JSONArray());
        } else { m = new JSONObject().put("chapter", 0).put("offset", 0); marks = new JSONArray(); }
        JSONArray chapters = new JSONArray(), lengths = new JSONArray(), order = new JSONArray(), assets = new JSONArray(); int words = 0;
        for (int i = 0; i < b.chapters.size(); i++) {
            v.stap("opslaan", i, delen); String text = b.chapters.get(i); write(new File(stage, i + ".txt"), text); chapters.put(b.names.get(i)); lengths.put(text.length()); order.put(b.linear.get(i));
            if (i < b.content.size()) write(new File(stage, i + ".json"), b.content.get(i).toString());
            if (b.linear.get(i)) words += wordCount(text);
        }
        for (int i = 0; i < b.assets.size(); i++) {
            v.stap("opslaan", b.chapters.size() + i, delen); EreaderBook.Asset asset = b.assets.get(i); writeBytes(new File(stage, "images/" + i + ".bin"), asset.bytes);
            assets.put(new JSONObject().put("mime", asset.mime).put("base", asset.base));
        }
        v.stap("opslaan", delen, delen);
        if (b.format.equals("EPUB")) writeBytes(new File(stage, "bron.epub"), bytes);
        write(new File(stage, "leeswijzers.json"), marks.toString());
        m.put("id", id).put("title", previous == null ? cut(b.title) : previous.optString("title", cut(b.title))).put("author", cut(b.author))
                .put("chapters", chapters).put("lengths", lengths).put("words", words).put("linear", order).put("assets", assets).put("toc", b.toc).put("language", b.language)
                .put("format", b.format).put("edition", 3).put("last", System.currentTimeMillis());
        if (b.cover != null) saveCover(d, b.cover);
        m.put("cover", new File(d, "omslag.jpg").isFile());
        // Alleen dit laatste, atomaire bestand maakt de complete nieuwe inhoud actief. De oude bestanden blijven intact.
        write(new File(d, "boek.json"), m.toString());
        return details(c, id);
    }
    private static String cut(String value) { return value.length() > 300 ? value.substring(0, 300) : value; }
    static int wordCount(String text) {
        int words = 0; boolean in = false;
        for (int i = 0; i < text.length(); i++) { boolean letter = Character.isLetterOrDigit(text.charAt(i)); if (letter && !in) words++; in = letter; }
        return words;
    }
    /** Alleen een verkleinde, opnieuw gecodeerde bitmap wordt aan de interface gegeven. */
    private static void saveCover(File dir, byte[] source) {
        Bitmap bitmap = null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(source, 0, source.length, o);
            if (o.outWidth < 1 || o.outHeight < 1 || (long) o.outWidth * o.outHeight > 32_000_000) return;
            o.inSampleSize = 1; while (Math.max(o.outWidth, o.outHeight) / o.inSampleSize > 640) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false; bitmap = BitmapFactory.decodeByteArray(source, 0, source.length, o);
            if (bitmap == null) return;
            AtomicFile file = new AtomicFile(new File(dir, "omslag.jpg")); FileOutputStream out = file.startWrite();
            try { if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)) throw new Exception(); file.finishWrite(out); }
            catch (Throwable e) { file.failWrite(out); }
        } catch (Throwable ignored) { } finally { if (bitmap != null) bitmap.recycle(); }
    }
    static synchronized String cover(Context c, String id) throws Exception {
        File f = new File(dir(c, id), "omslag.jpg"); if (!f.isFile()) return "";
        try (InputStream in = new FileInputStream(f)) { return "data:image/jpeg;base64," + android.util.Base64.encodeToString(EreaderBook.read(in, 512 * 1024), android.util.Base64.NO_WRAP); }
    }
    static synchronized JSONObject details(Context c, String id) throws Exception {
        JSONObject m = meta(c, id);
        if (!m.has("lengths")) {
            JSONArray lengths = new JSONArray(); int words = 0;
            for (int i = 0; i < m.getJSONArray("chapters").length(); i++) {
                String text = chapter(c, id, i).getString("text"); lengths.put(text.length()); words += wordCount(text);
            }
            m.put("lengths", lengths).put("words", words); write(new File(dir(c, id), "boek.json"), m.toString());
        }
        File f = new File(dataDir(c, id, m), "leeswijzers.json");
        return m.put("annotations", f.isFile() ? new JSONArray(read(f, 4 * 1024 * 1024)) : new JSONArray());
    }
    static synchronized JSONObject update(Context c, String id, String json) throws Exception {
        JSONObject m = meta(c, id), p = new JSONObject(json);
        for (String k : new String[]{"favorite", "finished"}) if (p.has(k)) m.put(k, p.getBoolean(k));
        if (p.has("title")) { String title = p.getString("title").trim(); if (!title.isEmpty()) m.put("title", cut(title)); }
        write(new File(dir(c, id), "boek.json"), m.toString()); return details(c, id);
    }
    static synchronized JSONObject annotate(Context c, String id, String json) throws Exception {
        JSONObject m = details(c, id), p = new JSONObject(json);
        String text = p.optBoolean("remove") ? "" : chapter(c, id, p.optInt("chapter", -1)).getString("text");
        JSONArray out = EreaderMarks.change(m.getJSONArray("annotations"), p, m.getJSONArray("chapters").length(), text, System.currentTimeMillis());
        String value = out.toString(); if (value.getBytes(StandardCharsets.UTF_8).length > 4 * 1024 * 1024) throw new Exception(Taal.T("Er zijn te veel notities in dit boek"));
        write(new File(dataDir(c, id, m), "leeswijzers.json"), value); return m.put("annotations", out);
    }
    static synchronized JSONObject chapter(Context c, String id, int index) throws Exception {
        JSONObject m = meta(c, id);
        if (index < 0 || index >= m.getJSONArray("chapters").length()) throw new Exception(Taal.T("Dit hoofdstuk bestaat niet"));
        File directory = dataDir(c, id, m), rich = new File(directory, index + ".json");
        return rich.isFile() ? new JSONObject(read(rich, 24 * 1024 * 1024)) : new JSONObject().put("text", read(new File(directory, index + ".txt"), EreaderBook.MAX_FILE));
    }
    static synchronized void position(Context c, String id, int chapter, double offset) throws Exception {
        position(c, id, chapter, offset, -1);
    }
    static synchronized void position(Context c, String id, int chapter, double offset, int anchor) throws Exception {
        JSONObject m = meta(c, id);
        if (chapter < 0 || chapter >= m.getJSONArray("chapters").length() || Double.isNaN(offset) || Double.isInfinite(offset)) return;
        m.put("chapter", chapter).put("offset", Math.max(0, Math.min(1, offset))).put("last", System.currentTimeMillis());
        if (anchor >= 0) m.put("anchor", anchor); else m.remove("anchor");
        JSONArray linear = m.optJSONArray("linear");
        if (linear == null || linear.optBoolean(chapter, true)) m.put("reading", new JSONObject().put("chapter", chapter).put("offset", offset).put("anchor", anchor));
        write(new File(dir(c, id), "boek.json"), m.toString());
    }
    static JSONObject image(Context c, String id, int index) throws Exception {
        JSONObject m = meta(c, id); JSONArray assets = m.optJSONArray("assets");
        if (index < 0 || assets == null || index >= assets.length() || index >= 2000) throw new Exception(Taal.T("Deze afbeelding staat niet in het boek"));
        JSONObject asset = assets.getJSONObject(index); File directory = dataDir(c, id, m);
        byte[] bytes; try (InputStream in = new FileInputStream(new File(directory, "images/" + index + ".bin"))) { bytes = EreaderBook.read(in, EreaderBook.MAX_ENTRY); }
        return EreaderMedia.image(new File(directory, "bron.epub"), bytes, asset.getString("mime"), asset.getString("base"));
    }
    private static void remove(File f) throws Exception {
        if (f.isDirectory()) { File[] children = f.listFiles(); if (children == null) throw new Exception(Taal.T("Boek verwijderen lukt niet")); for (File child : children) remove(child); }
        if (f.exists() && !f.delete()) throw new Exception(Taal.T("Boek verwijderen lukt niet"));
    }
    static synchronized void delete(Context c, String id) throws Exception {
        remove(dir(c, id));
    }
}
