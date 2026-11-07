package nl.rene.tools;

import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;
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
    static void write(File f, String value) throws Exception {
        if (!f.getParentFile().isDirectory() && !f.getParentFile().mkdirs()) throw new Exception(Taal.T("Boek opslaan lukt niet"));
        AtomicFile a = new AtomicFile(f); FileOutputStream out = null;
        try { out = a.startWrite(); out.write(value.getBytes(StandardCharsets.UTF_8)); a.finishWrite(out); }
        catch (Throwable e) { if (out != null) a.failWrite(out); throw new Exception(Taal.T("Boek opslaan lukt niet")); }
    }
    static JSONObject meta(Context c, String id) throws Exception { return new JSONObject(read(new File(dir(c, id), "boek.json"), 4 * 1024 * 1024)); }
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
        if (new File(d, "boek.json").isFile()) return meta(c, id);
        EreaderBook b = EreaderBook.parse(bytes, name);
        JSONArray chapters = new JSONArray();
        for (int i = 0; i < b.chapters.size(); i++) {
            write(new File(d, i + ".txt"), b.chapters.get(i)); chapters.put(b.names.get(i));
        }
        JSONObject m = new JSONObject().put("id", id).put("title", cut(b.title)).put("author", cut(b.author)).put("chapters", chapters)
                .put("chapter", 0).put("offset", 0).put("last", System.currentTimeMillis());
        write(new File(d, "boek.json"), m.toString()); // pas een compleet boek in de lijst zetten
        return m;
    }
    private static String cut(String value) { return value.length() > 300 ? value.substring(0, 300) : value; }
    static synchronized JSONObject chapter(Context c, String id, int index) throws Exception {
        JSONObject m = meta(c, id);
        if (index < 0 || index >= m.getJSONArray("chapters").length()) throw new Exception(Taal.T("Dit hoofdstuk bestaat niet"));
        return new JSONObject().put("text", read(new File(dir(c, id), index + ".txt"), EreaderBook.MAX_FILE));
    }
    static synchronized void position(Context c, String id, int chapter, double offset) throws Exception {
        JSONObject m = meta(c, id);
        if (chapter < 0 || chapter >= m.getJSONArray("chapters").length() || Double.isNaN(offset) || Double.isInfinite(offset)) return;
        m.put("chapter", chapter).put("offset", Math.max(0, Math.min(1, offset))).put("last", System.currentTimeMillis());
        write(new File(dir(c, id), "boek.json"), m.toString());
    }
    static synchronized void delete(Context c, String id) throws Exception {
        File d = dir(c, id); File[] files = d.listFiles();
        if (files != null) for (File f : files) if (!f.delete()) throw new Exception(Taal.T("Boek verwijderen lukt niet"));
        if (d.exists() && !d.delete()) throw new Exception(Taal.T("Boek verwijderen lukt niet"));
    }
}
