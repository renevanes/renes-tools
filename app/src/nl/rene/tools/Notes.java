package nl.rene.tools;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Notities en lijstjes. De interface beheert de inhoud (JSON); hier wordt hij veilig
 * opgeslagen in de app-map (eerst naar een tijdelijk bestand, dan hernoemen, zodat een
 * onderbroken opslag de vorige versie niet kapotmaakt) en desgewenst als kopie in de
 * backup-map gezet.
 */
final class Notes {

    private Notes() { }

    static final String DIR = "Notities";

    static File file(Context c) { return new File(c.getFilesDir(), "notes.json"); }

    // ----- Vastgezette notities: op het startscherm van Rene's Tools ("app") en van de telefoon-skin ("skin") -----

    static android.content.SharedPreferences pinPrefs(Context c) { return c.getSharedPreferences("notepins", Context.MODE_PRIVATE); }

    static JSONArray pinned(Context c, String where) {
        try { return new JSONArray(pinPrefs(c).getString(where, "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    static synchronized void setPinned(Context c, String where, String id, boolean on) {
        if (!"app".equals(where) && !"skin".equals(where)) return;
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) return;
        JSONArray a = pinned(c, where), out = new JSONArray();
        for (int i = 0; i < a.length(); i++) if (!id.equals(a.optString(i))) out.put(a.optString(i));
        if (on) out.put(id);
        pinPrefs(c).edit().putString(where, out.toString()).apply();
    }

    /** De vastgezette notities met titel en de eerste open items: [{id, title, open, total, items:[…]}]. Verwijderde notities vallen weg. */
    static String pinnedJson(Context c, String where) {
        JSONArray out = new JSONArray();
        try {
            String raw = load(c);
            JSONArray all = new JSONArray(raw.isEmpty() ? "[]" : raw), ids = pinned(c, where);
            for (int k = 0; k < ids.length(); k++) {
                String id = ids.optString(k);
                for (int i = 0; i < all.length(); i++) {
                    JSONObject n = all.optJSONObject(i);
                    if (n == null || !id.equals(n.optString("id"))) continue;
                    JSONArray items = n.optJSONArray("items"), open = new JSONArray();
                    int total = items == null ? 0 : items.length(), nOpen = 0;
                    if (items != null) for (int j = 0; j < items.length(); j++) {
                        JSONObject it = items.optJSONObject(j);
                        if (it == null || it.optBoolean("done")) continue;
                        nOpen++;
                        if (open.length() < 5) open.put(it.optString("text"));
                    }
                    String title = n.optString("title").trim();
                    out.put(new JSONObject().put("id", id).put("title", title.isEmpty() ? "Notitie" : title)
                            .put("open", nOpen).put("total", total).put("items", open)
                            .put("text", n.optString("text").length() > 120 ? n.optString("text").substring(0, 120) : n.optString("text")));
                }
            }
        } catch (Exception ignored) { }
        return out.toString();
    }

    static File bak(Context c) { return new File(c.getFilesDir(), "notes.json.bak"); }

    /**
     * Leest de notities. Geeft "[]" als er nog niets is, en "" als het bestand niet te lezen
     * is: de interface slaat dan niets op, zodat de echte notities niet overschreven worden.
     * Valt terug op de vorige versie (notes.json.bak) als het hoofdbestand ontbreekt of kapot is.
     */
    static synchronized String load(Context c) {
        File f = file(c), b = bak(c);
        if (!f.exists() && !b.exists()) return "[]";
        String r = f.exists() ? readValid(f) : null;
        if (r == null && b.exists()) r = readValid(b);
        return r == null ? "" : r;
    }

    private static String readValid(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            String s = new String(b.toByteArray(), StandardCharsets.UTF_8);
            new JSONArray(s);
            return s;
        } catch (Exception e) { return null; }
    }

    /** Slaat de notities op. Geeft "" bij succes, anders een foutmelding. */
    static synchronized String save(Context c, String json) {
        try {
            new JSONArray(json); // alleen geldige JSON opslaan
            File f = file(c), tmp = new File(c.getFilesDir(), "notes.json.tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                o.write(json.getBytes(StandardCharsets.UTF_8));
                o.getFD().sync();
            }
            File b = bak(c);
            if (f.exists()) { b.delete(); if (!f.renameTo(b)) throw new Exception("Opslaan lukt niet"); }
            if (!tmp.renameTo(f)) throw new Exception("Opslaan lukt niet");
            return "";
        } catch (Exception e) {
            return e.getMessage() != null ? e.getMessage() : "Opslaan lukt niet";
        }
    }

    /** Zet een kopie in de backup-map: notities.json (terug te lezen) en notities.txt (leesbaar). */
    static int export(Context c) throws Exception {
        Uri tree = WaBackup.destUri(c);
        if (tree == null) throw new Exception("Kies eerst een backup-map (bij WhatsApp backup)");
        String json = load(c);
        if (json.isEmpty()) throw new Exception("Notities zijn niet te lezen");
        JSONArray arr = new JSONArray(json);
        WaBackup.Dest dest = new WaBackup.Dest(c.getContentResolver(), tree);
        WaBackup.DestDir dir = dest.dir(DIR, true);
        try (Writer w = Sms.open(dest, dir, "notities.json", "application/json")) { w.write(json); }
        SimpleDateFormat df = new SimpleDateFormat("d MMM yyyy HH:mm", new Locale("nl", "NL"));
        try (Writer w = Sms.open(dest, dir, "notities.txt", "text/plain")) {
            w.write("Notities - Rene's Tools - " + df.format(new Date()) + "\r\n\r\n");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject n = arr.getJSONObject(i);
                String title = n.optString("title", "").trim();
                w.write("== " + (title.isEmpty() ? "Zonder titel" : title) + " ==\r\n");
                String text = n.optString("text", "");
                if (!text.isEmpty()) w.write(text.replace("\n", "\r\n") + "\r\n");
                JSONArray items = n.optJSONArray("items");
                if (items != null) for (int j = 0; j < items.length(); j++) {
                    JSONObject it = items.getJSONObject(j);
                    w.write((it.optBoolean("done") ? "[x] " : "[ ] ") + it.optString("text") + "\r\n");
                }
                w.write("\r\n");
            }
        }
        return arr.length();
    }
}
