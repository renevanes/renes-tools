package nl.rene.tools;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Properties;
import java.util.UUID;

/** Complete, onveranderlijke interfaceversies; één atomair vervangen verwijzing kiest de actieve versie. */
final class WebUpdateStore {
    static final long MAX_FILE = 30_000_000L;
    final File root;
    private Snapshot cached;

    static final class Snapshot {
        final File dir;
        final int code, nativeLevel;
        final String name;
        final String htmlHash, startHash;
        Snapshot(File dir, int code, String name, int nativeLevel, String htmlHash, String startHash) {
            this.dir = dir; this.code = code; this.name = name; this.nativeLevel = nativeLevel;
            this.htmlHash = htmlHash; this.startHash = startHash;
        }
        File html() { return new File(dir, "index.html"); }
        File start() { return new File(dir, "start.html"); }
        String readHtml() throws Exception { return verified(html(), htmlHash); }
        String readStart() throws Exception { return startHash.isEmpty() ? null : verified(start(), startHash); }
        private static String verified(File file, String expected) throws Exception {
            byte[] data = bytes(file, MAX_FILE);
            if (!hash(data).equals(expected)) throw new IOException("De interfaceversie is beschadigd");
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    WebUpdateStore(File root) { this.root = root; }

    synchronized Snapshot current() {
        if (cached != null) return cached;
        String[] ids = pointers();
        for (String id : ids) {
            Snapshot candidate = read(id);
            if (candidate != null) { cached = candidate; return candidate; }
        }
        return null; // een onvolledige versie nooit laden; de aanroeper valt terug op de gebundelde interface
    }
    synchronized Snapshot refresh() { cached = null; return current(); }

    /** Klaarzetten heeft geen invloed op de actieve versie, ook niet als het proces hierna stopt. */
    synchronized Snapshot stage(int code, String name, int nativeLevel, byte[] html, byte[] start) throws Exception {
        if (code <= 0 || nativeLevel < 0 || name == null || !name.matches("\\d{1,4}(\\.\\d{1,4}){0,3}"))
            throw new IOException("Ongeldige interfaceversie");
        if (html == null || html.length == 0 || html.length > MAX_FILE || (start != null && start.length > MAX_FILE))
            throw new IOException("Interfacebestand is te groot of leeg");
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Updatemap maken lukt niet");
        File dir = new File(root, "versie-" + UUID.randomUUID());
        if (!dir.mkdir()) throw new IOException("Versiemap maken lukt niet");
        try {
            write(new File(dir, "index.html"), html);
            if (start != null) write(new File(dir, "start.html"), start);
            Properties p = new Properties();
            p.setProperty("code", String.valueOf(code)); p.setProperty("name", name);
            p.setProperty("native", String.valueOf(nativeLevel));
            p.setProperty("html", hash(html)); p.setProperty("start", start == null ? "" : hash(start));
            try (FileOutputStream out = new FileOutputStream(new File(dir, "versie.properties"))) {
                p.store(out, "Tools interface"); out.getFD().sync();
            }
            return new Snapshot(dir, code, name, nativeLevel, p.getProperty("html"), p.getProperty("start"));
        } catch (Exception | Error e) { erase(dir); throw e; }
    }

    /** Code, naam en beide bestanden wisselen samen. De vorige volledige versie blijft voor herstel bestaan. */
    synchronized void activate(Snapshot next) throws Exception {
        Snapshot checked = read(next.dir.getName());
        if (checked == null || !next.dir.getCanonicalFile().equals(checked.dir.getCanonicalFile()))
            throw new IOException("De interfaceversie is niet compleet");
        Snapshot previous = refresh(); // een inmiddels onleesbare gecachte versie is geen bruikbare herstelkopie
        String refs = next.dir.getName() + "\n" + (previous == null ? "" : previous.dir.getName()) + "\n";
        File tmp = new File(root, "actief.tmp");
        write(tmp, refs.getBytes(StandardCharsets.UTF_8));
        if (!tmp.renameTo(new File(root, "actief"))) throw new IOException("Activeren van de interface lukt niet");
        cached = checked;
        File[] dirs = root.listFiles();
        if (dirs != null) for (File dir : dirs) {
            if (dir.isDirectory() && validId(dir.getName()) && !dir.equals(next.dir)
                    && (previous == null || !dir.equals(previous.dir))) erase(dir);
        }
    }

    private String[] pointers() {
        try { return text(new File(root, "actief"), 256).split("\n"); }
        catch (Exception e) { return new String[0]; }
    }

    private static boolean validId(String id) { return id != null && id.matches("versie-[0-9a-f-]{36}"); }

    private Snapshot read(String id) {
        if (!validId(id)) return null;
        try {
            File dir = new File(root, id);
            Properties p = new Properties();
            try (java.io.StringReader reader = new java.io.StringReader(text(new File(dir, "versie.properties"), 4096))) { p.load(reader); }
            int code = Integer.parseInt(p.getProperty("code")), nativeLevel = Integer.parseInt(p.getProperty("native"));
            String name = p.getProperty("name"), startHash = p.getProperty("start", "");
            if (code <= 0 || nativeLevel < 0 || name == null || !name.matches("\\d{1,4}(\\.\\d{1,4}){0,3}")) return null;
            if (!p.getProperty("html", "").equals(hash(bytes(new File(dir, "index.html"), MAX_FILE)))) return null;
            if (!startHash.isEmpty() && !startHash.equals(hash(bytes(new File(dir, "start.html"), MAX_FILE)))) return null;
            if (startHash.isEmpty() && new File(dir, "start.html").exists()) return null;
            return new Snapshot(dir, code, name, nativeLevel, p.getProperty("html"), startHash);
        } catch (Exception e) { return null; }
    }

    static String text(File f, long max) throws IOException { return new String(bytes(f, max), StandardCharsets.UTF_8); }
    private static byte[] bytes(File f, long max) throws IOException {
        if (!f.isFile() || f.length() > max) throw new IOException("Bestand ontbreekt of is te groot");
        try (FileInputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[16384]; long size = 0; int n;
            while ((n = in.read(b)) != -1) { size += n; if (size > max) throw new IOException("Bestand is te groot"); out.write(b, 0, n); }
            return out.toByteArray();
        }
    }
    private static void write(File f, byte[] bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) { out.write(bytes); out.getFD().sync(); }
    }
    private static String hash(byte[] bytes) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder s = new StringBuilder();
        for (byte b : hash) s.append(String.format(java.util.Locale.ROOT, "%02x", b));
        return s.toString();
    }
    private static void erase(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) erase(child);
        file.delete();
    }
}
