package nl.rene.tools;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SMS-backup: leest de eigen sms-berichten van de telefoon (de sms-database die Android zelf
 * bijhoudt), toont ze in de app en exporteert ze naar de gekozen backup-map. Twee bestanden:
 * een herstelbaar XML-bestand in het formaat van "SMS Backup & Restore", en per gesprek een
 * leesbare HTML-pagina. Alleen lezen; er wordt niets op de telefoon gewijzigd.
 */
final class Sms {

    private Sms() { }

    static final String PREFS = "sms";
    static final String DIR = "SMS backup";
    static final Uri SMS_URI = Uri.parse("content://sms");

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    // ---------- bericht ----------

    static final class Msg {
        long id, threadId, date, dateSent;
        String address, body, contactName, serviceCenter;
        int type, read, status = -1, protocol;
    }

    static List<Msg> read(Context c) {
        List<Msg> out = new ArrayList<>();
        String[] cols = {"_id", "thread_id", "address", "date", "date_sent", "body", "type", "read", "status", "service_center", "protocol"};
        try (Cursor cur = c.getContentResolver().query(SMS_URI, cols, null, null, "date ASC")) {
            if (cur == null) return out;
            int iId = cur.getColumnIndex("_id"), iTh = cur.getColumnIndex("thread_id"), iAddr = cur.getColumnIndex("address"),
                    iDate = cur.getColumnIndex("date"), iDs = cur.getColumnIndex("date_sent"), iBody = cur.getColumnIndex("body"),
                    iType = cur.getColumnIndex("type"), iRead = cur.getColumnIndex("read"), iStatus = cur.getColumnIndex("status"),
                    iSc = cur.getColumnIndex("service_center"), iProto = cur.getColumnIndex("protocol");
            while (cur.moveToNext()) {
                Msg m = new Msg();
                m.id = iId >= 0 ? cur.getLong(iId) : 0;
                m.threadId = iTh >= 0 ? cur.getLong(iTh) : 0;
                m.address = iAddr >= 0 ? cur.getString(iAddr) : null;
                m.date = iDate >= 0 ? cur.getLong(iDate) : 0;
                m.dateSent = iDs >= 0 ? cur.getLong(iDs) : 0;
                m.body = iBody >= 0 ? cur.getString(iBody) : null;
                m.type = iType >= 0 ? cur.getInt(iType) : 0;
                m.read = iRead >= 0 ? cur.getInt(iRead) : 1;
                m.status = iStatus >= 0 && !cur.isNull(iStatus) ? cur.getInt(iStatus) : -1;
                m.serviceCenter = iSc >= 0 ? cur.getString(iSc) : null;
                m.protocol = iProto >= 0 && !cur.isNull(iProto) ? cur.getInt(iProto) : 0;
                out.add(m);
            }
        }
        return out;
    }

    // ---------- namen uit contacten ----------

    private static Map<String, String> names;
    private static long namesAt;

    static synchronized Map<String, String> names(Context c) {
        if (names != null && System.currentTimeMillis() - namesAt < 5 * 60 * 1000L) return names;
        // Zonder toestemming niets cachen, anders blijven namen weg nadat de gebruiker die net geeft.
        if (c.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            return new HashMap<>();
        Map<String, String> m = new HashMap<>();
        try (Cursor cur = c.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME},
                null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                String k = numKey(cur.getString(0));
                if (k != null && !m.containsKey(k)) m.put(k, cur.getString(1));
            }
        } catch (Exception ignored) { }
        names = m; namesAt = System.currentTimeMillis();
        return m;
    }

    private static String numKey(String n) {
        if (n == null) return null;
        String d = n.replaceAll("[^0-9]", "");
        return d.length() < 6 ? null : d.substring(Math.max(0, d.length() - 9));
    }

    static String nameFor(Context c, String address) {
        if (address == null || address.isEmpty()) return "Onbekend";
        String k = numKey(address);
        String n = k == null ? null : names(c).get(k);
        return n != null ? n : address;
    }

    // ---------- gesprekken groeperen ----------

    static final class Conv {
        long threadId, last;
        String address, name, lastText;
        int count;
    }

    static List<Conv> conversations(Context c, List<Msg> msgs) {
        Map<Long, Conv> map = new HashMap<>();
        List<Conv> order = new ArrayList<>();
        for (Msg m : msgs) {
            Conv cv = map.get(m.threadId);
            if (cv == null) {
                cv = new Conv();
                cv.threadId = m.threadId;
                cv.address = m.address;
                cv.name = nameFor(c, m.address);
                map.put(m.threadId, cv);
                order.add(cv);
            }
            cv.count++;
            if (m.date >= cv.last) { cv.last = m.date; cv.lastText = m.body; if (cv.address == null) cv.address = m.address; }
        }
        order.sort((a, b) -> Long.compare(b.last, a.last));
        return order;
    }

    // ---------- JSON voor de app ----------

    static String conversationsJson(Context c) throws Exception {
        List<Conv> cv = conversations(c, read(c));
        JSONArray a = new JSONArray();
        for (Conv x : cv) {
            JSONObject o = new JSONObject();
            o.put("thread", x.threadId);
            o.put("name", x.name);
            o.put("count", x.count);
            o.put("last", x.last);
            o.put("lastText", x.lastText == null ? "" : x.lastText);
            a.put(o);
        }
        JSONObject r = new JSONObject();
        r.put("conversations", a);
        r.put("total", countTotal(c));
        return r.toString();
    }

    static int countTotal(Context c) {
        try (Cursor cur = c.getContentResolver().query(SMS_URI, new String[]{"_id"}, null, null, null)) {
            return cur == null ? 0 : cur.getCount();
        } catch (Exception e) { return 0; }
    }

    private static JSONObject msgJson(Msg m) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", m.id);
        o.put("me", m.type == 2 || m.type == 4 || m.type == 5 || m.type == 6);
        o.put("t", m.date);
        o.put("text", m.body == null ? "" : m.body);
        o.put("type", m.type);
        return o;
    }

    static String messagesJson(Context c, long threadId) throws Exception {
        List<Msg> all = read(c);
        JSONArray a = new JSONArray();
        for (Msg m : all) if (m.threadId == threadId) a.put(msgJson(m));
        return a.toString();
    }

    static String searchJson(Context c, String q) throws Exception {
        String ql = q.toLowerCase();
        List<Msg> all = read(c);
        Map<Long, String> nm = new HashMap<>();
        for (Conv cv : conversations(c, all)) nm.put(cv.threadId, cv.name);
        JSONArray a = new JSONArray();
        for (int i = all.size() - 1; i >= 0 && a.length() < 200; i--) {
            Msg m = all.get(i);
            if (m.body != null && m.body.toLowerCase().contains(ql)) {
                JSONObject o = msgJson(m);
                o.put("name", nm.get(m.threadId));
                o.put("thread", m.threadId);
                a.put(o);
            }
        }
        return a.toString();
    }

    // ---------- export ----------

    /**
     * Escapet tekst voor zowel XML-attributen als HTML-tekst. Regeleindes en tabs worden
     * numerieke entiteiten (anders verliest een XML-parser ze in een attribuut bij het
     * herstellen), en in XML 1.0 verboden stuurtekens worden weggelaten, zodat het hele
     * backupbestand leesbaar blijft.
     */
    static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '&': b.append("&amp;"); break;
                case '"': b.append("&quot;"); break;
                case '\'': b.append("&#39;"); break;
                case '\n': b.append("&#10;"); break;
                case '\r': b.append("&#13;"); break;
                case '\t': b.append("&#9;"); break;
                default:
                    // Stuurtekens die XML 1.0 niet toestaat, overslaan (kunnen niet escaped worden).
                    if (ch < 0x20) break;
                    b.append(ch);
            }
        }
        return b.toString();
    }

    static String safeName(String n) {
        if (n == null) return "gesprek";
        StringBuilder b = new StringBuilder();
        for (char ch : n.trim().toCharArray()) b.append(Character.isLetterOrDigit(ch) || ch == '-' || ch == '+' ? ch : '_');
        String r = b.toString().replaceAll("_+", "_").replaceAll("^_|_$", "");
        if (r.isEmpty()) r = "gesprek";
        return r.length() > 60 ? r.substring(0, 60) : r;
    }

    interface Progress { void step(int done, int total); }

    /** Exporteert alle sms naar de backup-map: één XML (herstelbaar) en per gesprek een HTML-pagina. */
    static int export(Context c, Progress p) throws Exception {
        Uri tree = WaBackup.destUri(c);
        if (tree == null) throw new Exception("Kies eerst een backup-map (bij WhatsApp backup)");
        List<Msg> all = read(c);
        if (all.isEmpty()) throw new Exception("Geen sms-berichten gevonden op deze telefoon");
        WaBackup.Dest dest = new WaBackup.Dest(c.getContentResolver(), tree);
        WaBackup.DestDir dir = dest.dir(DIR, true);

        // 1. Herstelbaar XML-bestand (formaat van SMS Backup & Restore)
        SimpleDateFormat rd = new SimpleDateFormat("d MMM yyyy HH:mm:ss", Locale.US);
        SimpleDateFormat stamp = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String xmlName = "sms-" + stamp.format(new Date()) + ".xml";
        try (Writer w = open(dest, dir, xmlName, "text/xml")) {
            w.write("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n");
            w.write("<smses count=\"" + all.size() + "\" backup_set=\"renes-tools\" backup_date=\"" + System.currentTimeMillis() + "\">\n");
            int i = 0;
            for (Msg m : all) {
                w.write("  <sms protocol=\"" + m.protocol + "\" address=\"" + esc(m.address) + "\" date=\"" + m.date
                        + "\" type=\"" + m.type + "\" subject=\"null\" body=\"" + esc(m.body) + "\" toa=\"null\" sc_toa=\"null\""
                        + " service_center=\"" + (m.serviceCenter == null ? "null" : esc(m.serviceCenter)) + "\" read=\"" + m.read
                        + "\" status=\"" + m.status + "\" locked=\"0\" date_sent=\"" + m.dateSent
                        + "\" readable_date=\"" + esc(rd.format(new Date(m.date))) + "\" contact_name=\"" + esc(nameFor(c, m.address)) + "\" />\n");
                if ((++i % 200) == 0) p.step(i, all.size());
            }
            w.write("</smses>\n");
        }

        // 2. Leesbare HTML per gesprek + index
        List<Conv> convs = conversations(c, all);
        Map<Long, List<Msg>> byThread = new HashMap<>();
        for (Msg m : all) {
            List<Msg> l = byThread.get(m.threadId);
            if (l == null) { l = new ArrayList<>(); byThread.put(m.threadId, l); }
            l.add(m);
        }
        SimpleDateFormat day = new SimpleDateFormat("EEEE d MMMM yyyy", new Locale("nl", "NL"));
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.US);
        SimpleDateFormat full = new SimpleDateFormat("d MMM yyyy HH:mm", new Locale("nl", "NL"));
        java.util.Set<String> used = new java.util.HashSet<>();
        StringBuilder idx = new StringBuilder();
        idx.append("<!DOCTYPE html><html lang=nl><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>")
                .append("<title>Sms-berichten</title><style>").append(CSS).append("</style><header><h1>Sms-berichten</h1><small>Gemaakt door Rene's Tools op ")
                .append(esc(full.format(new Date()))).append(" · ").append(all.size()).append(" berichten</small></header><main><ul>");
        int done = 0;
        for (Conv cv : convs) {
            String file = safeName(cv.name);
            if (!used.add(file.toLowerCase())) file = file + "-" + cv.threadId;
            file = file + ".html";
            try (Writer w = open(dest, dir, file, "text/html")) {
                w.write("<!DOCTYPE html><html lang=nl><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>");
                w.write(esc(cv.name));
                w.write("</title><style>" + CSS + "</style><header><h1>" + esc(cv.name) + "</h1><small>" + cv.count
                        + " berichten · <a style='color:#fff' href='index.html'>alle gesprekken</a></small></header><main>");
                String lastDay = "";
                List<Msg> l = byThread.get(cv.threadId);
                for (Msg m : l) {
                    String dd = day.format(new Date(m.date));
                    if (!dd.equals(lastDay)) { w.write("<div class=cl></div><div class=d><span>" + esc(dd) + "</span></div>"); lastDay = dd; }
                    boolean me = m.type == 2 || m.type == 4 || m.type == 5 || m.type == 6;
                    w.write("<div class='m" + (me ? " me" : "") + "'>");
                    if (m.body != null) w.write(esc(m.body));
                    w.write("<span class=t>" + hm.format(new Date(m.date)) + "</span></div>");
                }
                w.write("<div class=cl></div></main></html>");
            }
            idx.append("<li><a href='").append(esc(file)).append("'>").append(esc(cv.name)).append("</a><small>")
                    .append(cv.count).append(" berichten · ").append(esc(full.format(new Date(cv.last)))).append("</small></li>");
            p.step(all.size(), all.size());
            done++;
        }
        idx.append("</ul></main></html>");
        try (Writer w = open(dest, dir, "index.html", "text/html")) { w.write(idx.toString()); }

        prefs(c).edit().putLong("lastExport", System.currentTimeMillis()).putInt("lastCount", all.size()).apply();
        return all.size();
    }

    static Writer open(WaBackup.Dest dest, WaBackup.DestDir dir, String name, String mime) throws Exception {
        WaBackup.Child ch = dir.kids.get(name);
        Uri u;
        if (ch != null && !ch.dir) u = DocumentsContract.buildDocumentUriUsingTree(dest.tree, ch.docId);
        else {
            u = DocumentsContract.createDocument(dest.cr, dir.uri, mime, name);
            if (u == null) throw new Exception("Bestand maken lukt niet: " + name);
            dir.kids.put(name, new WaBackup.Child(DocumentsContract.getDocumentId(u), 0, 0, false));
        }
        OutputStream o;
        try { o = dest.cr.openOutputStream(u, "wt"); } catch (Exception e) { o = dest.cr.openOutputStream(u, "w"); }
        if (o == null) throw new Exception("Schrijven lukt niet: " + name);
        return new java.io.BufferedWriter(new OutputStreamWriter(o, StandardCharsets.UTF_8), 1 << 16);
    }

    static final String CSS = "body{font:15px/1.4 system-ui,sans-serif;background:#ECE5DD;margin:0;color:#111}"
            + "header{background:#1E5AA8;color:#fff;padding:14px 18px;position:sticky;top:0}header h1{margin:0;font-size:19px}"
            + "header small{opacity:.85}main{max-width:820px;margin:0 auto;padding:12px}"
            + ".d{text-align:center;margin:14px 0 8px}.d span{background:#E1F2FB;border-radius:8px;padding:4px 10px;font-size:13px;color:#333}"
            + ".m{max-width:78%;margin:3px 0;padding:6px 9px 4px;border-radius:9px;background:#fff;box-shadow:0 1px 1px rgba(0,0,0,.12);clear:both;float:left;white-space:pre-wrap;word-wrap:break-word}"
            + ".me{float:right;background:#DCF8C6}.t{font-size:11px;color:#777;float:right;margin:4px 0 0 10px}.cl{clear:both}"
            + "ul{list-style:none;padding:0}li{background:#fff;margin:6px 0;border-radius:9px;padding:10px 14px}li a{color:#1E5AA8;font-weight:600;text-decoration:none}li small{display:block;color:#666}";
}
