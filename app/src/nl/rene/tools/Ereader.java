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
import java.io.ByteArrayOutputStream;
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
    static void write(File f, String value) throws Exception {
        if (!f.getParentFile().isDirectory() && !f.getParentFile().mkdirs()) throw new Exception(Taal.T("Boek opslaan lukt niet"));
        AtomicFile a = new AtomicFile(f); FileOutputStream out = null;
        try { out = a.startWrite(); out.write(value.getBytes(StandardCharsets.UTF_8)); a.finishWrite(out); }
        catch (Throwable e) { if (out != null) a.failWrite(out); throw new Exception(Taal.T("Boek opslaan lukt niet")); }
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
    static synchronized JSONObject add(Context c, Uri uri) throws Exception {
        String name = PdfTools.displayName(c, uri);
        byte[] bytes;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception(Taal.T("Boek openen lukt niet"));
            bytes = EreaderBook.read(in, EreaderBook.MAX_FILE);
        }
        StringBuilder hash = new StringBuilder();
        for (byte v : MessageDigest.getInstance("SHA-256").digest(bytes)) hash.append(String.format(java.util.Locale.ROOT, "%02x", v & 255));
        String id = hash.toString(); File d = dir(c, id);
        JSONObject previous = new File(d, "boek.json").isFile() ? meta(c, id) : null;
        if (previous != null && previous.optInt("edition") >= 2) return details(c, id);
        EreaderBook b = EreaderBook.parse(bytes, name);
        JSONArray chapters = new JSONArray(), lengths = new JSONArray(); int words = 0;
        for (int i = 0; i < b.chapters.size(); i++) {
            String text = b.chapters.get(i);
            write(new File(d, i + ".txt"), text); chapters.put(b.names.get(i)); lengths.put(text.length());
            words += wordCount(text);
        }
        JSONObject m = previous == null ? new JSONObject().put("chapter", 0).put("offset", 0) : previous;
        m.put("id", id).put("title", cut(b.title)).put("author", cut(b.author)).put("chapters", chapters).put("lengths", lengths).put("words", words)
                .put("format", name.toLowerCase(java.util.Locale.ROOT).endsWith(".epub") ? "EPUB" : "TXT").put("edition", 2).put("last", System.currentTimeMillis());
        if (b.cover != null) saveCover(d, b.cover);
        m.put("cover", new File(d, "omslag.jpg").isFile());
        write(new File(d, "boek.json"), m.toString()); // pas een compleet boek in de lijst zetten
        return m;
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
        File f = new File(dir(c, id), "leeswijzers.json");
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
        write(new File(dir(c, id), "leeswijzers.json"), value); return m.put("annotations", out);
    }
    static synchronized JSONObject chapter(Context c, String id, int index) throws Exception {
        JSONObject m = meta(c, id);
        if (index < 0 || index >= m.getJSONArray("chapters").length()) throw new Exception(Taal.T("Dit hoofdstuk bestaat niet"));
        return new JSONObject().put("text", read(new File(dir(c, id), index + ".txt"), EreaderBook.MAX_FILE));
    }
    static synchronized void position(Context c, String id, int chapter, double offset) throws Exception {
        position(c, id, chapter, offset, -1);
    }
    static synchronized void position(Context c, String id, int chapter, double offset, int anchor) throws Exception {
        JSONObject m = meta(c, id);
        if (chapter < 0 || chapter >= m.getJSONArray("chapters").length() || Double.isNaN(offset) || Double.isInfinite(offset)) return;
        m.put("chapter", chapter).put("offset", Math.max(0, Math.min(1, offset))).put("last", System.currentTimeMillis());
        if (anchor >= 0) m.put("anchor", anchor); else m.remove("anchor");
        write(new File(dir(c, id), "boek.json"), m.toString());
    }
    static synchronized void delete(Context c, String id) throws Exception {
        File d = dir(c, id); File[] files = d.listFiles();
        if (files != null) for (File f : files) if (!f.delete()) throw new Exception(Taal.T("Boek verwijderen lukt niet"));
        if (d.exists() && !d.delete()) throw new Exception(Taal.T("Boek verwijderen lukt niet"));
    }
}
