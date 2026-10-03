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
