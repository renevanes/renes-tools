package nl.rene.tools;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Leesbare chats: ontsleutelt de WhatsApp-backup met de sleutel van 64 tekens, leest de
 * chats uit de database (voor de app) en schrijft per chat een HTML-bestand in de backup-map.
 */
final class WaChats {

    private WaChats() { }

    static final String READABLE_DIR = "Leesbare chats";

    static File dbFile(Context c) { File d = new File(c.getFilesDir(), "wa"); d.mkdirs(); return new File(d, "msgstore.db"); }

    static String key(Context c) { return WaBackup.prefs(c).getString("waKey", null); }

    static boolean autoReadable(Context c) { return key(c) != null && WaBackup.prefs(c).getBoolean("readableAuto", true); }

    /** Nieuwste versleutelde backup van gewone WhatsApp op de telefoon. */
    static File latestCrypt() {
        for (WaBackup.Source s : WaBackup.sources()) {
            if (!"WhatsApp".equals(s.name) || s.dir == null) continue;
            File dbDir = new File(s.dir, "Databases");
            File main = new File(dbDir, "msgstore.db.crypt15");
            if (main.isFile()) return main;
            File best = null;
            File[] fs = dbDir.listFiles();
            if (fs != null) for (File f : fs)
                if (f.getName().startsWith("msgstore") && f.getName().endsWith(".crypt15") && (best == null || f.lastModified() > best.lastModified())) best = f;
            if (best != null) return best;
            File c14 = new File(dbDir, "msgstore.db.crypt14");
            if (c14.isFile()) return c14;
        }
        return null;
    }

    /** Ontsleutelt de nieuwste backup naar de app. Geeft de bron terug. */
    static File decryptLatest(Context c, String hexKey) throws Exception {
        File src = latestCrypt();
        if (src == null) throw new Exception("Geen WhatsApp-backup gevonden op de telefoon. Maak er eerst een in WhatsApp.");
        if (src.getName().endsWith(".crypt14")) throw new WaCrypt.NotE2EException();
        closeDb();
        WaCrypt.decrypt(src, WaCrypt.parseKey(hexKey), dbFile(c));
        WaBackup.prefs(c).edit().putLong("waDbFrom", src.lastModified()).putLong("waDbAt", System.currentTimeMillis()).apply();
        return src;
    }

    // ---------- database ----------

    private static SQLiteDatabase db;
    private static long dbStamp;
    private static Set<String> msgCols, mediaCols;
    private static boolean hasJidMap, hasMedia;

    static synchronized void closeDb() {
        if (db != null) { try { db.close(); } catch (Exception ignored) { } db = null; }
    }

    static synchronized SQLiteDatabase db(Context c) throws Exception {
        File f = dbFile(c);
        if (!f.exists()) throw new Exception("Nog geen leesbare chats. Vul eerst je sleutel in.");
        if (db != null && dbStamp == f.lastModified()) return db;
        closeDb();
        db = SQLiteDatabase.openDatabase(f.getPath(), null, SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
        dbStamp = f.lastModified();
        msgCols = columns(db, "message");
        if (msgCols.isEmpty()) { closeDb(); throw new Exception("Onbekend databaseformaat (te oude WhatsApp?)"); }
        mediaCols = columns(db, "message_media");
        hasMedia = mediaCols.contains("file_path");
        hasJidMap = !columns(db, "jid_map").isEmpty();
        return db;
    }

    private static Set<String> columns(SQLiteDatabase d, String table) {
        Set<String> s = new HashSet<>();
        try (Cursor c = d.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (c.moveToNext()) s.add(c.getString(1));
        } catch (Exception ignored) { }
        return s;
    }

    static String chatsSql(boolean jidMap) {
        return "SELECT c._id, j.user, j.server, c.subject, s.n, s.last, "
                + "(SELECT m2.text_data FROM message m2 WHERE m2.chat_row_id=c._id AND m2.message_type<>7 ORDER BY m2.timestamp DESC LIMIT 1), "
                + "(SELECT m3.message_type FROM message m3 WHERE m3.chat_row_id=c._id AND m3.message_type<>7 ORDER BY m3.timestamp DESC LIMIT 1), "
                + (jidMap ? "pj.user " : "NULL ")
                + "FROM chat c JOIN jid j ON j._id=c.jid_row_id "
                + "JOIN (SELECT chat_row_id, COUNT(*) AS n, MAX(timestamp) AS last FROM message WHERE message_type<>7 GROUP BY chat_row_id) s ON s.chat_row_id=c._id "
                + (jidMap ? "LEFT JOIN jid_map jm ON jm.lid_row_id=j._id LEFT JOIN jid pj ON pj._id=jm.jid_row_id " : "")
                + "WHERE j.server<>'broadcast' ORDER BY s.last DESC";
    }

    static String messagesSql(boolean jidMap, boolean media, boolean mediaName, String where, String order) {
        return "SELECT m._id, m.from_me, m.timestamp, m.message_type, m.text_data, sj.user, "
                + (jidMap ? "pj.user, " : "NULL, ")
                + (media ? "mm.file_path, mm.mime_type, " : "NULL, NULL, ")
                + (media && mediaName ? "mm.media_name, " : "NULL, ")
                + "m.chat_row_id "
                + "FROM message m LEFT JOIN jid sj ON sj._id=m.sender_jid_row_id "
                + (jidMap ? "LEFT JOIN jid_map jm ON jm.lid_row_id=sj._id LEFT JOIN jid pj ON pj._id=jm.jid_row_id " : "")
                + (media ? "LEFT JOIN message_media mm ON mm.message_row_id=m._id " : "")
                + "WHERE m.message_type<>7 AND " + where + " ORDER BY " + order;
    }

    // ---------- namen ----------

    private static Map<String, String> names;
    private static long namesAt;

    static synchronized Map<String, String> names(Context c) {
        if (names != null && System.currentTimeMillis() - namesAt < 5 * 60 * 1000L) return names;
        Map<String, String> m = new HashMap<>();
        if (c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            try (Cursor cur = c.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME},
                    null, null, null)) {
                while (cur != null && cur.moveToNext()) {
                    String k = numKey(cur.getString(0));
                    if (k != null && !m.containsKey(k)) m.put(k, cur.getString(1));
                }
            } catch (Exception ignored) { }
        }
        names = m;
        namesAt = System.currentTimeMillis();
        return m;
    }

    private static String numKey(String n) {
        if (n == null) return null;
        String d = n.replaceAll("[^0-9]", "");
        return d.length() < 6 ? null : d.substring(Math.max(0, d.length() - 9));
    }

    static String person(Context c, String user, String phoneUser) {
        String u = phoneUser != null ? phoneUser : user;
        if (u == null) return "Onbekend";
        if (phoneUser == null && user != null && user.length() > 13) return "Deelnemer"; // privé-ID zonder nummer
        String k = numKey(u);
        String n = k == null ? null : names(c).get(k);
        return n != null ? n : "+" + u;
    }

    static String chatName(Context c, String user, String server, String subject, String phoneUser) {
        if (subject != null && !subject.isEmpty()) return subject;
        if ("g.us".equals(server)) return "Groep";
        return person(c, user, "lid".equals(server) ? phoneUser : null);
    }

    static String typeLabel(int t) {
        switch (t) {
            case 1: case 42: return "📷 Foto";
            case 2: return "🎤 Spraakbericht";
            case 3: case 43: return "🎬 Video";
            case 4: return "👤 Contact";
            case 5: case 16: return "📍 Locatie";
            case 9: return "📄 Document";
            case 13: return "GIF";
            case 15: return "🚫 Dit bericht is verwijderd";
            case 20: return "Sticker";
            case 46: case 66: return "📊 Peiling";
            default: return null;
        }
    }

    /** Pad binnen de WhatsApp-map (vanaf "Media/"), of null. */
    static String mediaRel(String p) {
        if (p == null) return null;
        int i = p.indexOf("Media/");
        return i < 0 ? null : p.substring(i);
    }

    // ---------- JSON voor de app ----------

    static synchronized String chatsJson(Context c) throws Exception {
        SQLiteDatabase d = db(c);
        JSONArray a = new JSONArray();
        try (Cursor cur = d.rawQuery(chatsSql(hasJidMap), null)) {
            while (cur.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("id", cur.getLong(0));
                o.put("name", chatName(c, cur.getString(1), cur.getString(2), cur.getString(3), cur.getString(8)));
                o.put("group", "g.us".equals(cur.getString(2)));
                o.put("n", cur.getLong(4));
                o.put("last", cur.getLong(5));
                String t = cur.getString(6);
                if (t == null) t = typeLabel(cur.isNull(7) ? 0 : cur.getInt(7));
                o.put("lastText", t == null ? "" : t);
                a.put(o);
            }
        }
        JSONObject r = new JSONObject();
        r.put("chats", a);
        r.put("from", WaBackup.prefs(c).getLong("waDbFrom", 0));
        return r.toString();
    }

    private static JSONObject msg(Context c, Cursor cur, Map<Long, String> chatNames) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", cur.getLong(0));
        boolean me = cur.getInt(1) == 1;
        o.put("me", me);
        o.put("t", cur.getLong(2));
        int type = cur.isNull(3) ? 0 : cur.getInt(3);
        o.put("type", type);
        String text = cur.getString(4);
        String label = typeLabel(type);
        o.put("text", text == null ? "" : text);
        o.put("label", label == null ? "" : label);
        o.put("sender", me ? "Ik" : (cur.isNull(5) ? "" : person(c, cur.getString(5), cur.getString(6))));
        String rel = mediaRel(cur.getString(7));
        o.put("media", rel == null ? "" : rel);
        o.put("mime", cur.isNull(8) ? "" : cur.getString(8));
        o.put("fileName", cur.isNull(9) ? "" : cur.getString(9));
        if (chatNames != null) { long cid = cur.getLong(10); o.put("chat", cid); o.put("chatName", chatNames.get(cid)); }
        return o;
    }

    static synchronized String messagesJson(Context c, long chatId, long before, int limit) throws Exception {
        SQLiteDatabase d = db(c);
        // Nieuwste 'limit' berichten vóór 'before' ophalen (DESC), daarna omdraaien naar oplopend
        // zodat de weergave ze van oud naar nieuw toont en msgs[0] de oudste van de reeks is.
        java.util.ArrayList<JSONObject> list = new java.util.ArrayList<>();
        String sql = messagesSql(hasJidMap, hasMedia, mediaCols.contains("media_name"),
                "m.chat_row_id=? AND m.timestamp<?", "m.timestamp DESC LIMIT " + Math.max(1, Math.min(limit, 1000)));
        try (Cursor cur = d.rawQuery(sql, new String[]{String.valueOf(chatId), String.valueOf(before <= 0 ? Long.MAX_VALUE : before)})) {
            while (cur.moveToNext()) list.add(msg(c, cur, null));
        }
        JSONArray a = new JSONArray();
        for (int i = list.size() - 1; i >= 0; i--) a.put(list.get(i));
        return a.toString();
    }

    static synchronized String searchJson(Context c, String q) throws Exception {
        SQLiteDatabase d = db(c);
        Map<Long, String> chatNames = new HashMap<>();
        try (Cursor cur = d.rawQuery(chatsSql(hasJidMap), null)) {
            while (cur.moveToNext()) chatNames.put(cur.getLong(0), chatName(c, cur.getString(1), cur.getString(2), cur.getString(3), cur.getString(8)));
        }
        JSONArray a = new JSONArray();
        String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        String sql = messagesSql(hasJidMap, hasMedia, mediaCols.contains("media_name"),
                "m.text_data LIKE ? ESCAPE '\\'", "m.timestamp DESC LIMIT 200");
        try (Cursor cur = d.rawQuery(sql, new String[]{like})) {
            while (cur.moveToNext()) a.put(msg(c, cur, chatNames));
        }
        return a.toString();
    }

    // ---------- HTML in de backup-map ----------

    static final String CSS = "body{font:15px/1.4 system-ui,sans-serif;background:#ECE5DD;margin:0;color:#111}"
            + "header{background:#075E54;color:#fff;padding:14px 18px;position:sticky;top:0}header h1{margin:0;font-size:19px}"
            + "header small{opacity:.8}main{max-width:820px;margin:0 auto;padding:12px}"
            + ".d{text-align:center;margin:14px 0 8px}.d span{background:#E1F2FB;border-radius:8px;padding:4px 10px;font-size:13px;color:#333}"
            + ".m{max-width:78%;margin:3px 0;padding:6px 9px 4px;border-radius:9px;background:#fff;box-shadow:0 1px 1px rgba(0,0,0,.12);clear:both;float:left;white-space:pre-wrap;word-wrap:break-word}"
            + ".me{float:right;background:#DCF8C6}.s{font-size:13px;font-weight:600;color:#1f7a5c;display:block}"
            + ".t{font-size:11px;color:#777;float:right;margin:4px 0 0 10px}.l{color:#555;font-style:italic}"
            + ".m img{max-width:100%;max-height:320px;border-radius:6px;display:block;margin:2px 0}.cl{clear:both}"
            + "ul{list-style:none;padding:0}li{background:#fff;margin:6px 0;border-radius:9px;padding:10px 14px}li a{color:#075E54;font-weight:600;text-decoration:none}"
            + "li small{display:block;color:#666}";

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '&': b.append("&amp;"); break;
                case '"': b.append("&quot;"); break;
                default: b.append(ch);
            }
        }
        return b.toString();
    }

    static String urlPath(String rel) {
        StringBuilder b = new StringBuilder();
        for (String seg : rel.split("/")) {
            if (b.length() > 0) b.append('/');
            try { b.append(java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")); } catch (Exception e) { b.append(seg); }
        }
        return b.toString();
    }

    interface Progress { void step(int done, int total, String name); }

    /** Schrijft index.html + één HTML-bestand per chat naar "WhatsApp backup/Leesbare chats". */
    static int exportHtml(Context c, Progress p) throws Exception {
        Uri tree = WaBackup.destUri(c);
        if (tree == null) throw new Exception("Kies eerst een backup-map");
        SQLiteDatabase d = db(c);
        WaBackup.Dest dest = new WaBackup.Dest(c.getContentResolver(), tree);
        WaBackup.DestDir dir = dest.dir(WaBackup.BACKUP_DIR + "/" + READABLE_DIR, true);
        List<Object[]> chats = new ArrayList<>();
        try (Cursor cur = d.rawQuery(chatsSql(hasJidMap), null)) {
            while (cur.moveToNext()) chats.add(new Object[]{cur.getLong(0),
                    chatName(c, cur.getString(1), cur.getString(2), cur.getString(3), cur.getString(8)),
                    cur.getLong(4), cur.getLong(5), "g.us".equals(cur.getString(2))});
        }
        SimpleDateFormat day = new SimpleDateFormat("EEEE d MMMM yyyy", new Locale("nl", "NL"));
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.US);
        SimpleDateFormat full = new SimpleDateFormat("d MMM yyyy HH:mm", new Locale("nl", "NL"));
        Set<String> used = new HashSet<>();
        StringBuilder idx = new StringBuilder();
        idx.append("<!DOCTYPE html><html lang=nl><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>")
                .append("<title>WhatsApp-chats</title><style>").append(CSS).append("</style><header><h1>WhatsApp-chats</h1><small>Gemaakt door Rene's Tools op ")
                .append(esc(full.format(new Date()))).append(" uit de backup van ")
                .append(esc(full.format(new Date(WaBackup.prefs(c).getLong("waDbFrom", System.currentTimeMillis()))))).append("</small></header><main><ul>");
        int k = 0;
        String sql = messagesSql(hasJidMap, hasMedia, mediaCols.contains("media_name"), "m.chat_row_id=?", "m.timestamp ASC");
        for (Object[] ch : chats) {
            if (WaBackup.cancel) break;
            long id = (Long) ch[0];
            String name = (String) ch[1];
            boolean group = (Boolean) ch[4];
            String file = WaBackup.safeName(name.replace('/', '_')).trim();
            if (file.isEmpty()) file = "chat";
            if (file.length() > 80) file = file.substring(0, 80);
            if (!used.add(file.toLowerCase())) { file = file + " " + id; used.add(file.toLowerCase()); }
            file = file + ".html";
            p.step(k, chats.size(), name);
            Writer w = open(dest, dir, file);
            try {
                w.write("<!DOCTYPE html><html lang=nl><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>");
                w.write(esc(name));
                w.write("</title><style>" + CSS + "</style><header><h1>" + esc(name) + "</h1><small>" + ch[2] + " berichten · <a style='color:#fff' href='index.html'>alle chats</a></small></header><main>");
                String lastDay = "";
                try (Cursor cur = d.rawQuery(sql, new String[]{String.valueOf(id)})) {
                    while (cur.moveToNext()) {
                        long t = cur.getLong(2);
                        String dd = day.format(new Date(t));
                        if (!dd.equals(lastDay)) { w.write("<div class=cl></div><div class=d><span>" + esc(dd) + "</span></div>"); lastDay = dd; }
                        boolean me = cur.getInt(1) == 1;
                        int type = cur.isNull(3) ? 0 : cur.getInt(3);
                        String text = cur.getString(4);
                        String label = typeLabel(type);
                        String rel = mediaRel(cur.getString(7));
                        String mime = cur.isNull(8) ? "" : cur.getString(8);
                        w.write("<div class='m" + (me ? " me" : "") + "'>");
                        if (group && !me) w.write("<span class=s>" + esc(cur.isNull(5) ? "" : person(c, cur.getString(5), cur.getString(6))) + "</span>");
                        if (rel != null) {
                            String href = "../WhatsApp/" + urlPath(rel);
                            if (mime.startsWith("image/") && type != 20) w.write("<a href='" + href + "'><img loading=lazy src='" + href + "' alt=''></a>");
                            else w.write("<a href='" + href + "'>" + esc(label != null ? label : "Bestand") + (cur.isNull(9) ? "" : " " + esc(cur.getString(9))) + "</a>");
                            if (text != null && !text.isEmpty()) w.write("\n");
                        } else if (label != null && (text == null || text.isEmpty())) {
                            w.write("<span class=l>" + esc(label) + "</span>");
                        }
                        if (text != null) w.write(esc(text));
                        w.write("<span class=t>" + hm.format(new Date(t)) + "</span></div>");
                    }
                }
                w.write("<div class=cl></div></main></html>");
            } finally {
                w.close();
            }
            idx.append("<li><a href='").append(esc(urlPath(file))).append("'>").append(esc(name)).append("</a><small>")
                    .append(ch[2]).append(" berichten · laatste ").append(esc(full.format(new Date((Long) ch[3])))).append("</small></li>");
            k++;
        }
        idx.append("</ul></main></html>");
        try (Writer w = open(dest, dir, "index.html")) { w.write(idx.toString()); }
        return k;
    }

    static Writer open(WaBackup.Dest dest, WaBackup.DestDir dir, String name) throws Exception {
        WaBackup.Child ch = dir.kids.get(name);
        Uri u;
        if (ch != null && !ch.dir) u = DocumentsContract.buildDocumentUriUsingTree(dest.tree, ch.docId);
        else {
            u = DocumentsContract.createDocument(dest.cr, dir.uri, "text/html", name);
            if (u == null) throw new Exception("Bestand maken lukt niet: " + name);
            dir.kids.put(name, new WaBackup.Child(DocumentsContract.getDocumentId(u), 0, 0, false));
        }
        OutputStream o;
        try { o = dest.cr.openOutputStream(u, "wt"); }
        catch (Exception e) { o = dest.cr.openOutputStream(u, "w"); }
        if (o == null) throw new Exception("Schrijven lukt niet: " + name);
        return new java.io.BufferedWriter(new OutputStreamWriter(o, StandardCharsets.UTF_8), 1 << 16);
    }

    /** Ontsleutelen + HTML maken, met voortgang zoals een backup (mode "readable"). */
    static WaBackup.Status makeReadable(Context c, WaBackup.Listener l) {
        WaBackup.Status st = new WaBackup.Status();
        st.mode = "readable"; st.running = true; st.startedAt = System.currentTimeMillis(); st.phase = "decrypt";
        l.progress(st);
        try {
            String k = key(c);
            if (k == null) throw new Exception("Vul eerst je sleutel van 64 tekens in");
            decryptLatest(c, k);
            int n = 0;
            boolean haveDest = WaBackup.destUri(c) != null;
            if (haveDest) {
                st.phase = "export";
                l.progress(st);
                n = exportHtml(c, (done, total, name) -> {
                    st.filesDone = done; st.filesTotal = total; st.current = name; l.progress(st);
                });
                st.filesDone = st.filesTotal;
            }
            if (WaBackup.cancel) WaBackup.finish(st, "cancelled", null);
            else WaBackup.finish(st, "ok", haveDest ? n + " chats leesbaar gemaakt"
                    : "Chats leesbaar in de app (kies een backup-map voor de HTML-bestanden)");
        } catch (Exception e) {
            WaBackup.finish(st, "error", e.getMessage());
        }
        l.progress(st);
        return st;
    }
}
