package nl.rene.tools;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.webkit.MimeTypeMap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WhatsApp backup: kopieert de lokale WhatsApp-map (chats-backup + media) naar
 * een door de gebruiker gekozen map (Storage Access Framework), en terug.
 *
 * Stateless en incrementeel: per map wordt de doelmap één keer opgevraagd en
 * alleen ontbrekende of gewijzigde bestanden worden gekopieerd. Een afgebroken
 * backup gaat de volgende keer gewoon verder. In de backup wordt nooit iets
 * verwijderd.
 */
final class WaBackup {

    static final String PREFS = "wabackup";
    static final String BACKUP_DIR = "WhatsApp backup";
    static final String[] CATS = {"chats", "images", "video", "voice", "audio", "documents", "stickers", "other", "statuses"};
    /** "Alles": alles behalve statussen (die verdwijnen na 24 uur en zijn van anderen). */
    static final String ALL = "chats,images,video,voice,audio,documents,stickers,other";

    static volatile boolean busy = false;
    static volatile boolean cancel = false;
    /** Reden bij stoppen door het systeem (null = gebruiker stopte). */
    static volatile String cancelMsg = null;

    private WaBackup() { }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    // ---------- bronnen ----------

    static final class Source {
        final String name;   // "WhatsApp" of "WhatsApp Business" (mapnaam in de backup)
        final File dir;      // bestaande map op de telefoon, of null
        final File restoreTo;
        Source(String n, File d, File r) { name = n; dir = d; restoreTo = r; }
    }

    static List<Source> sources() {
        File ext = Environment.getExternalStorageDirectory();
        List<Source> l = new ArrayList<>();
        File wa = new File(ext, "Android/media/com.whatsapp/WhatsApp");
        File waOld = new File(ext, "WhatsApp");
        l.add(new Source("WhatsApp", wa.isDirectory() ? wa : (waOld.isDirectory() ? waOld : null), wa));
        File wb = new File(ext, "Android/media/com.whatsapp.w4b/WhatsApp Business");
        File wbOld = new File(ext, "WhatsApp Business");
        l.add(new Source("WhatsApp Business", wb.isDirectory() ? wb : (wbOld.isDirectory() ? wbOld : null), wb));
        return l;
    }

    /** Categorie van een pad binnen de WhatsApp-map, of null om over te slaan. */
    static String category(String rel) {
        if (rel.startsWith("Databases/") || rel.startsWith("Backups/")) return "chats";
        if (!rel.startsWith("Media/")) return null;
        String rest = rel.substring(6);
        int i = rest.indexOf('/');
        if (i < 0) return null;
        String dir = rest.substring(0, i);
        if (dir.equals(".Statuses")) return "statuses";
        if (dir.startsWith(".")) return null;
        String d = dir.replaceFirst("^WhatsApp Business ", "").replaceFirst("^WhatsApp ", "");
        switch (d) {
            case "Images": return "images";
            case "Video": case "Video Notes": return "video";
            case "Voice Notes": return "voice";
            case "Audio": return "audio";
            case "Documents": return "documents";
            case "Stickers": case "Animated Gifs": case "Backup Excluded Stickers": return "stickers";
            default: return "other";
        }
    }

    static final class Item {
        final File file; final String rel; final long size; final long mod; final String cat;
        Item(File f, String r, String c) { file = f; rel = r; size = f.length(); mod = f.lastModified(); cat = c; }
    }

    static void walk(File root, File dir, String prefix, Set<String> cats, List<Item> out) { walk(root, dir, prefix, cats, out, true); }

    static void walk(File root, File dir, String prefix, Set<String> cats, List<Item> out, boolean stoppable) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (stoppable && cancel) return;
            String rel = prefix.isEmpty() ? f.getName() : prefix + "/" + f.getName();
            if (f.isDirectory()) {
                if (prefix.isEmpty() && !(rel.equals("Databases") || rel.equals("Backups") || rel.equals("Media"))) continue;
                if (f.getName().equals(BACKUP_DIR)) continue; // nooit de backup zelf meenemen
                walk(root, f, rel, cats, out, stoppable);
            } else if (f.isFile()) {
                if (f.getName().endsWith(".rt-part")) continue;
                String c = category(rel);
                if (c != null && (cats == null || cats.contains(c))) out.add(new Item(f, rel, c));
            }
        }
    }

    /** Grootte en aantal bestanden per categorie (alle bronnen samen). */
    static JSONObject sizes() throws Exception {
        Map<String, long[]> m = new HashMap<>();
        for (String c : CATS) m.put(c, new long[2]);
        for (Source s : sources()) {
            if (s.dir == null) continue;
            List<Item> items = new ArrayList<>();
            walk(s.dir, s.dir, "", null, items, false);
            for (Item it : items) { long[] a = m.get(it.cat); a[0] += it.size; a[1]++; }
        }
        JSONObject o = new JSONObject();
        for (String c : CATS) {
            JSONObject x = new JSONObject();
            x.put("bytes", m.get(c)[0]);
            x.put("files", m.get(c)[1]);
            o.put(c, x);
        }
        return o;
    }

    // ---------- doelmap (Storage Access Framework) ----------

    static final class Child { final String docId; final long size; final long mod; final boolean dir;
        Child(String i, long s, long m, boolean d) { docId = i; size = s; mod = m; dir = d; } }

    static final class DestDir { Uri uri; String path = ""; final Map<String, Child> kids = new HashMap<>(); }

    /** Doelmap met cache: één opvraging per map. */
    static final class Dest {
        final ContentResolver cr; final Uri tree;
        final Map<String, DestDir> cache = new HashMap<>();
        /** Als er een versleuteld archief wordt gemaakt, gaan alle bestanden daarin in plaats van in de map. */
        final Secure.Zip zip = Secure.CAPTURE.get();
        Dest(ContentResolver r, Uri t) { cr = r; tree = t; }

        DestDir root() throws Exception {
            DestDir d = cache.get("");
            if (d == null) {
                d = new DestDir();
                if (zip != null) { cache.put("", d); return d; }
                d.uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
                list(d);
                cache.put("", d);
            }
            return d;
        }

        void list(DestDir d) throws Exception {
            Uri q = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(d.uri));
            try (Cursor c = cr.query(q, new String[]{Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
                    Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_MIME_TYPE}, null, null, null)) {
                if (c == null) throw new Exception("Backup-map niet leesbaar");
                while (c.moveToNext()) {
                    boolean isDir = Document.MIME_TYPE_DIR.equals(c.getString(4));
                    d.kids.put(c.getString(1), new Child(c.getString(0),
                            c.isNull(2) ? -1 : c.getLong(2), c.isNull(3) ? 0 : c.getLong(3), isDir));
                }
            }
        }

        /** Map op relatief pad; null als hij niet bestaat en create false is. */
        DestDir dir(String rel, boolean create) throws Exception {
            DestDir d = cache.get(rel);
            if (d != null) return d;
            if (zip != null) { d = new DestDir(); d.path = rel; cache.put(rel, d); return d; }
            int i = rel.lastIndexOf('/');
            DestDir parent = i < 0 ? root() : dir(rel.substring(0, i), create);
            if (parent == null) return null;
            String name = i < 0 ? rel : rel.substring(i + 1);
            Child ch = parent.kids.get(name);
            d = new DestDir();
            if (ch != null && ch.dir) {
                d.uri = DocumentsContract.buildDocumentUriUsingTree(tree, ch.docId);
                list(d);
            } else if (create) {
                Uri u = DocumentsContract.createDocument(cr, parent.uri, Document.MIME_TYPE_DIR, name);
                if (u == null) throw new Exception("Map maken lukt niet: " + name);
                d.uri = u;
                parent.kids.put(name, new Child(DocumentsContract.getDocumentId(u), 0, 0, true));
            } else {
                return null;
            }
            d.path = rel;
            cache.put(rel, d);
            return d;
        }
    }

    /** Bestandsnaam die ook op USB-sticks en SD-kaarten (FAT) kan. */
    static String safeName(String n) {
        StringBuilder b = new StringBuilder(n.length());
        for (char ch : n.toCharArray()) b.append(ch < 32 || "\"*/:<>?\\|".indexOf(ch) >= 0 ? '_' : ch);
        return b.toString();
    }

    static String mime(String name) {
        int i = name.lastIndexOf('.');
        String m = i < 0 ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(i + 1).toLowerCase());
        return m != null ? m : "application/octet-stream";
    }

    static Uri destUri(Context c) {
        String s = prefs(c).getString("dest", null);
        return s == null ? null : Uri.parse(s);
    }

    static String destName(Context c) {
        Uri t = destUri(c);
        if (t == null) return null;
        try (Cursor cur = c.getContentResolver().query(
                DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t)),
                new String[]{Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } catch (Exception ignored) { }
        return "(map niet meer bereikbaar)";
    }

    // ---------- uitvoeren ----------

    interface Listener { void progress(Status s); }

    static final class Status {
        String mode = "", phase = "", current = "", result = "", message = "";
        long filesTotal, filesDone, bytesTotal, bytesDone, startedAt, finishedAt;
        int errors;
        boolean running;

        JSONObject json() {
            JSONObject o = new JSONObject();
            try {
                o.put("running", running); o.put("mode", mode); o.put("phase", phase); o.put("current", current);
                o.put("filesTotal", filesTotal); o.put("filesDone", filesDone);
                o.put("bytesTotal", bytesTotal); o.put("bytesDone", bytesDone);
                o.put("errors", errors); o.put("result", result); o.put("message", message);
                o.put("startedAt", startedAt); o.put("finishedAt", finishedAt);
            } catch (Exception ignored) { }
            return o;
        }
    }

    /** Backup maken. mode = all | selection | auto. */
    static Status backup(Context c, String mode, Set<String> cats, Listener l) {
        Status st = new Status();
        st.mode = mode; st.running = true; st.startedAt = System.currentTimeMillis(); st.phase = "scan";
        l.progress(st);
        try {
            Uri tree = destUri(c);
            if (tree == null) throw new Exception("Kies eerst een backup-map");
            Dest dest = new Dest(c.getContentResolver(), tree);
            dest.root();

            // 1. Bestanden op de telefoon verzamelen
            List<Item> todo = new ArrayList<>();
            List<String> todoBase = new ArrayList<>();
            boolean any = false;
            for (Source s : sources()) {
                if (s.dir == null) continue;
                any = true;
                List<Item> items = new ArrayList<>();
                walk(s.dir, s.dir, "", cats, items);
                if (cancel) break;
                // 2. Vergelijken met de backup
                st.phase = "compare";
                l.progress(st);
                String base = BACKUP_DIR + "/" + s.name;
                for (Item it : items) {
                    if (cancel) break;
                    int i = it.rel.lastIndexOf('/');
                    String parent = base + (i < 0 ? "" : "/" + it.rel.substring(0, i));
                    String name = safeName(it.rel.substring(i + 1));
                    DestDir d = dest.dir(parent, false);
                    Child ch = d == null ? null : d.kids.get(name);
                    boolean need = ch == null || ch.size != it.size
                            || ("chats".equals(it.cat) && (ch.mod == 0 || it.mod > ch.mod));
                    if (need) { todo.add(it); todoBase.add(base); st.bytesTotal += it.size; }
                }
            }
            if (!any) throw new Exception("Geen WhatsApp-map gevonden op deze telefoon");
            st.filesTotal = todo.size();

            // 3. Kopiëren
            st.phase = "copy";
            l.progress(st);
            int consecutiveErrors = 0;
            for (int k = 0; k < todo.size() && !cancel; k++) {
                Item it = todo.get(k);
                st.current = it.file.getName();
                try {
                    copyToDest(dest, todoBase.get(k), it, st, l);
                    consecutiveErrors = 0;
                } catch (Exception e) {
                    st.errors++;
                    if (++consecutiveErrors >= 15) throw new Exception("Backup-map niet bereikbaar (" + e.getMessage() + ")");
                }
                st.filesDone++;
                l.progress(st);
            }
            finish(st, cancel ? "cancelled" : (st.errors > 0 ? "partial" : "ok"), null);
        } catch (Exception e) {
            finish(st, "error", e.getMessage());
        }
        l.progress(st);
        return st;
    }

    private static void copyToDest(Dest dest, String base, Item it, Status st, Listener l) throws Exception {
        int i = it.rel.lastIndexOf('/');
        String parent = base + (i < 0 ? "" : "/" + it.rel.substring(0, i));
        String name = safeName(it.rel.substring(i + 1));
        DestDir d = dest.dir(parent, true);
        Child old = d.kids.get(name);
        if (old != null && old.dir) throw new Exception("In de backup staat een map met dezelfde naam: " + name);
        String tmpName = name + ".rt-part";
        Child stale = d.kids.remove(tmpName);
        if (stale != null) {
            Uri staleUri = DocumentsContract.buildDocumentUriUsingTree(dest.tree, stale.docId);
            if (old == null && stale.size == it.size) {
                // Vorige keer onderbroken vlak na het verwijderen van het oude bestand: de kopie is compleet.
                try {
                    Uri r = DocumentsContract.renameDocument(dest.cr, staleUri, name);
                    if (r != null) {
                        d.kids.put(name, new Child(DocumentsContract.getDocumentId(r), it.size, System.currentTimeMillis(), false));
                        st.bytesDone += it.size;
                        return;
                    }
                } catch (Exception ignored) { }
            }
            try { DocumentsContract.deleteDocument(dest.cr, staleUri); } catch (Exception ignored) { }
        }
        // Altijd eerst volledig naar een tijdelijk bestand (.rt-part). Pas als de kopie compleet is krijgt hij de
        // echte naam: zo staat er nooit een half bestand onder de echte naam (dat terugzetten zou terugzetten).
        Uri tmp = DocumentsContract.createDocument(dest.cr, d.uri, "application/octet-stream", tmpName);
        if (tmp == null) throw new Exception("Tijdelijk bestand maken lukt niet");
        try {
            writeTo(dest, tmp, it, st, l);
        } catch (Exception e) {
            try { DocumentsContract.deleteDocument(dest.cr, tmp); } catch (Exception ignored) { }
            throw e;
        }
        if (old != null) {
            Uri oldUri = DocumentsContract.buildDocumentUriUsingTree(dest.tree, old.docId);
            boolean kept = false;
            if (!"chats".equals(it.cat)) {
                // Media en documenten veranderen niet: andere grootte = een ánder bestand met dezelfde naam
                // (bijv. een tweede "Factuur.pdf"). De oude versie bewaren onder naam~datum.
                String vn = versionName(name, d);
                try {
                    Uri r = DocumentsContract.renameDocument(dest.cr, oldUri, vn);
                    if (r != null) { d.kids.put(vn, new Child(DocumentsContract.getDocumentId(r), old.size, old.mod, false)); kept = true; }
                } catch (Exception ignored) { }
            }
            if (!kept) {
                try {
                    if (!DocumentsContract.deleteDocument(dest.cr, oldUri)) throw new Exception("verwijderen geweigerd");
                } catch (Exception e) {
                    // Oude kopie blijft staan; de nieuwe wordt de volgende keer opnieuw geprobeerd.
                    try { DocumentsContract.deleteDocument(dest.cr, tmp); } catch (Exception ignored) { }
                    throw new Exception("Oude kopie kan niet worden vervangen: " + name);
                }
            }
            d.kids.remove(name);
        }
        Uri done;
        try {
            done = DocumentsContract.renameDocument(dest.cr, tmp, name);
            if (done == null) throw new Exception("hernoemen mislukt");
        } catch (Exception e) {
            // Hernoemen niet ondersteund: de volledige kopie staat veilig in het tijdelijke
            // bestand. Schrijf hem opnieuw onder de echte naam en ruim het tijdelijke op.
            done = DocumentsContract.createDocument(dest.cr, d.uri, mime(name), name);
            if (done == null) throw new Exception("Vervangen lukt niet (kopie staat in " + tmpName + ")");
            try { writeTo(dest, done, it, st, l); }
            catch (Exception e2) { try { DocumentsContract.deleteDocument(dest.cr, done); } catch (Exception ignored) { } throw e2; }
            try { DocumentsContract.deleteDocument(dest.cr, tmp); } catch (Exception ignored) { }
        }
        d.kids.put(name, new Child(DocumentsContract.getDocumentId(done), it.size, System.currentTimeMillis(), false));
    }

    /** "Factuur.pdf" → "Factuur~20261009.pdf" (met -2, -3 … als die al bestaat). */
    static String versionName(String name, DestDir d) {
        int dot = name.lastIndexOf('.');
        String b = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        String day = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(new java.util.Date());
        String n = b + "~" + day + ext;
        for (int i = 2; d != null && d.kids.containsKey(n); i++) n = b + "~" + day + "-" + i + ext;
        return n;
    }

    /** Een bewaarde oude versie (naam~jjjjmmdd)? Die gaan niet terug naar de telefoon. */
    static boolean isVersion(String name) { return name.matches(".*~\\d{8}(-\\d+)?(\\.[^.]*)?"); }

    private static void writeTo(Dest dest, Uri target, Item it, Status st, Listener l) throws Exception {
        long t = 0, written = 0;
        // Eerst de bron openen: verdwijnt het bestand (WhatsApp ruimt op), dan blijft er geen schrijfkanaal open hangen
        try (InputStream in = new FileInputStream(it.file); OutputStream o = dest.cr.openOutputStream(target, "w")) {
            if (o == null) throw new Exception("Schrijven lukt niet");
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancel) throw new Exception("Gestopt");
                o.write(buf, 0, n);
                written += n;
                st.bytesDone += n;
                long now = System.currentTimeMillis();
                if (now - t > 400) { t = now; l.progress(st); }
            }
        }
        if (written != it.size) throw new Exception("Bestand veranderde tijdens het kopiëren");
    }

    /** Terugzetten: backup-map -> WhatsApp-map op de telefoon. Alleen ontbrekende of afwijkende bestanden. */
    static Status restore(Context c, Listener l) {
        Status st = new Status();
        st.mode = "restore"; st.running = true; st.startedAt = System.currentTimeMillis(); st.phase = "scan";
        l.progress(st);
        try {
            Uri tree = destUri(c);
            if (tree == null) throw new Exception("Kies eerst de map met de backup");
            Dest dest = new Dest(c.getContentResolver(), tree);
            DestDir base = dest.dir(BACKUP_DIR, false);
            if (base == null) throw new Exception("Geen \"" + BACKUP_DIR + "\" gevonden in deze map");
            List<Object[]> todo = new ArrayList<>(); // {docId, File target, size}
            boolean any = false;
            for (Source s : sources()) {
                DestDir app = dest.dir(BACKUP_DIR + "/" + s.name, false);
                if (app == null) continue;
                any = true;
                collectRestore(dest, BACKUP_DIR + "/" + s.name, "", s.restoreTo, todo, st);
            }
            if (!any) throw new Exception("De backup-map bevat geen WhatsApp-backup");
            st.filesTotal = todo.size();
            st.phase = "copy";
            l.progress(st);
            int consecutiveErrors = 0;
            for (int k = 0; k < todo.size() && !cancel; k++) {
                Object[] x = todo.get(k);
                File f = (File) x[1];
                st.current = f.getName();
                try {
                    f.getParentFile().mkdirs();
                    File tmp = new File(f.getParentFile(), f.getName() + ".rt-part");
                    long t = 0;
                    try (InputStream in = dest.cr.openInputStream(DocumentsContract.buildDocumentUriUsingTree(dest.tree, (String) x[0]));
                         OutputStream o = new FileOutputStream(tmp)) {
                        if (in == null) throw new Exception("Lezen lukt niet");
                        byte[] buf = new byte[256 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            if (cancel) throw new Exception("Gestopt");
                            o.write(buf, 0, n);
                            st.bytesDone += n;
                            long now = System.currentTimeMillis();
                            if (now - t > 400) { t = now; l.progress(st); }
                        }
                    }
                    if (f.exists() && !f.delete()) throw new Exception("Overschrijven lukt niet");
                    if (!tmp.renameTo(f)) throw new Exception("Hernoemen lukt niet");
                    consecutiveErrors = 0;
                } catch (Exception e) {
                    st.errors++;
                    new File(f.getParentFile(), f.getName() + ".rt-part").delete();
                    if (++consecutiveErrors >= 15) throw new Exception("Terugzetten lukt niet (" + e.getMessage() + ")");
                }
                st.filesDone++;
                l.progress(st);
            }
            finish(st, cancel ? "cancelled" : (st.errors > 0 ? "partial" : "ok"), null);
        } catch (Exception e) {
            finish(st, "error", e.getMessage());
        }
        l.progress(st);
        return st;
    }

    private static void collectRestore(Dest dest, String destRel, String rel, File targetRoot, List<Object[]> todo, Status st) throws Exception {
        DestDir d = dest.dir(destRel, false);
        if (d == null) return;
        for (Map.Entry<String, Child> e : new HashMap<>(d.kids).entrySet()) {
            if (cancel) return;
            String r = rel.isEmpty() ? e.getKey() : rel + "/" + e.getKey();
            Child ch = e.getValue();
            if (ch.dir) {
                collectRestore(dest, destRel + "/" + e.getKey(), r, targetRoot, todo, st);
            } else if (!e.getKey().endsWith(".rt-part") && !e.getKey().endsWith(".rt-tmp") && !e.getKey().endsWith(".rt-old") && !isVersion(e.getKey())) {
                File f = new File(targetRoot, r);
                boolean need;
                if (!f.exists()) need = true;
                else if (f.length() == ch.size) need = false;
                // Andere grootte: alleen terugzetten als het bestand op de telefoon ouder is dan de backup-kopie.
                // Een later ontvangen bestand met dezelfde naam (of nieuwere chats) wordt nooit overschreven.
                else need = ch.mod > 0 && ch.mod > f.lastModified();
                if (need) {
                    todo.add(new Object[]{ch.docId, f, ch.size});
                    st.bytesTotal += Math.max(0, ch.size);
                }
            }
        }
    }

    static void finish(Status st, String result, String msg) {
        st.running = false;
        st.phase = "done";
        st.current = "";
        st.result = result;
        st.finishedAt = System.currentTimeMillis();
        if (msg != null) st.message = msg;
        else if ("cancelled".equals(result)) st.message = cancelMsg != null ? cancelMsg : "Gestopt";
        else if (st.filesTotal == 0) st.message = "restore".equals(st.mode) ? "Alles stond al op de telefoon" : "Alles was al bijgewerkt";
        else st.message = st.filesDone - st.errors + " bestanden gekopieerd" + (st.errors > 0 ? ", " + st.errors + " mislukt" : "");
    }

    // ---------- status & geschiedenis ----------

    static void saveStatus(Context c, Status st) {
        SharedPreferences p = prefs(c);
        SharedPreferences.Editor e = p.edit().putString("status", st.json().toString());
        if (!st.running && st.finishedAt > 0) {
            try {
                JSONArray h = new JSONArray(p.getString("history", "[]"));
                JSONArray n = new JSONArray();
                n.put(st.json());
                for (int i = 0; i < h.length() && i < 19; i++) n.put(h.get(i));
                e.putString("history", n.toString());
                if (!"restore".equals(st.mode) && !"readable".equals(st.mode) && ("ok".equals(st.result) || "partial".equals(st.result)))
                    e.putLong("lastOk", st.finishedAt);
            } catch (Exception ignored) { }
        }
        e.apply();
    }

    static String status(Context c) {
        try {
            JSONObject o = new JSONObject(prefs(c).getString("status", "{\"running\":false,\"phase\":\"idle\"}"));
            if (o.optBoolean("running") && !busy) {
                o.put("running", false); o.put("phase", "done"); o.put("result", "cancelled"); o.put("message", "Onderbroken");
            }
            return o.toString();
        } catch (Exception e) { return "{\"running\":false}"; }
    }

    static Set<String> parseCats(String csv) {
        Set<String> s = new HashSet<>();
        if (csv != null) for (String x : csv.split(",")) if (!x.trim().isEmpty()) s.add(x.trim());
        return s;
    }
}
