package nl.rene.tools;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.Writer;
import java.util.List;

/**
 * Indeling van de telefoon-skin in "Alles back-uppen": pagina's, mappen, dock, look en vastgezette notities.
 * Terugzetten vervangt de indeling (de vorige blijft bewaard om terug te kunnen). Widgets kunnen niet mee:
 * die horen bij deze installatie; ze worden bij terugzetten weggelaten.
 */
final class LauncherBackup {

    private LauncherBackup() { }

    static final String DIR = "Startscherm", FILE = "startscherm.json", FORMAT = "renes-tools-launcher";

    static int export(Context c) throws Exception {
        Uri tree = WaBackup.destUri(c);
        if (tree == null) throw new Exception("Kies eerst een backup-map");
        String ws = WsStore.prefs(c).getString("ws", null);
        String cfg = Launcher.prefs(c).getString("cfg", null);
        if (ws == null && cfg == null) return -1; // de skin is nooit gebruikt
        JSONObject o = new JSONObject().put("format", FORMAT).put("version", 1).put("exportedAt", System.currentTimeMillis());
        if (ws != null) o.put("workspace", new JSONObject(ws));
        if (cfg != null) o.put("look", new JSONObject(cfg));
        o.put("notePins", Notes.pinned(c, "skin"));
        WaBackup.Dest dest = new WaBackup.Dest(c.getContentResolver(), tree);
        WaBackup.DestDir dir = dest.dir(DIR, true);
        try (Sms.Out w = Sms.open(dest, dir, FILE, "application/json")) { w.write(o.toString(2)); w.done(); }
        return count(o);
    }

    static int count(JSONObject o) {
        int n = 0;
        JSONObject ws = o.optJSONObject("workspace");
        JSONArray pages = ws == null ? null : ws.optJSONArray("pages");
        if (pages != null) for (int i = 0; i < pages.length(); i++) { JSONArray p = pages.optJSONArray(i); if (p != null) n += p.length(); }
        JSONArray dock = ws == null ? null : ws.optJSONArray("dock");
        if (dock != null) n += dock.length();
        return n;
    }

    /** Controleren en klaarzetten: {kind, total, pages, sample}. */
    static JSONObject preview(String text) throws Exception {
        JSONObject o;
        try { o = new JSONObject(text.trim()); } catch (Exception e) { throw new Exception("Dit is geen startscherm-backup (startscherm.json)"); }
        if (!FORMAT.equals(o.optString("format"))) throw new Exception("Dit is geen startscherm-backup (startscherm.json)");
        JSONObject ws = o.optJSONObject("workspace");
        int pages = ws == null || ws.optJSONArray("pages") == null ? 0 : ws.optJSONArray("pages").length();
        return new JSONObject().put("kind", "launcher").put("total", count(o)).put("fresh", count(o)).put("dup", 0)
                .put("pages", pages).put("hasLook", o.has("look")).put("sample", new JSONArray());
    }

    /** Zet de indeling terug (zonder widgets). Geeft het aantal onderdelen. */
    static int apply(Context c, String text) throws Exception {
        JSONObject o = new JSONObject(text.trim());
        if (!FORMAT.equals(o.optString("format"))) throw new Exception("Dit is geen startscherm-backup");
        JSONObject ws = o.optJSONObject("workspace");
        if (ws != null) {
            // Widgets weglaten: hun nummers horen bij de vorige installatie
            JSONArray pages = ws.optJSONArray("pages"), clean = new JSONArray();
            if (pages != null) for (int i = 0; i < pages.length(); i++) {
                JSONArray p = pages.optJSONArray(i), q = new JSONArray();
                if (p != null) for (int k = 0; k < p.length(); k++) {
                    JSONObject it = p.optJSONObject(k);
                    // Widgets en vastgezette snelkoppelingen horen bij deze installatie: weglaten
                    if (it != null && !"widget".equals(it.optString("t")) && !"shortcut".equals(it.optString("t"))) q.put(it);
                }
                clean.put(q);
            }
            ws.put("pages", clean);
            String before = WsStore.prefs(c).getString("ws", null);
            pendingDelete.addAll(widgetIds(before)); // na het opnieuw opbouwen vrijgeven
            android.content.SharedPreferences.Editor e = WsStore.prefs(c).edit().putString("ws", ws.toString());
            if (before != null) e.putString("ws_before_restore", before);
            e.commit();
        }
        JSONObject look = o.optJSONObject("look");
        if (look != null) Launcher.prefs(c).edit().putString("cfg", look.toString()).commit();
        JSONArray pins = o.optJSONArray("notePins");
        if (pins != null) Notes.pinPrefs(c).edit().putString("skin", pins.toString()).apply();
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            // Widgets van de oude indeling vrijgeven (anders blijven ze voor altijd gekoppeld)
            HomeActivity a = HomeActivity.inst;
            android.appwidget.AppWidgetHost host = a != null && a.desk != null ? a.desk.host : new android.appwidget.AppWidgetHost(c.getApplicationContext(), Desk.HOST_ID);
            for (Integer id : pendingDelete) { try { host.deleteAppWidgetId(id); } catch (Exception ignored) { } }
            pendingDelete.clear();
            if (a != null) a.recreate();
        });
        return count(o);
    }

    private static final List<Integer> pendingDelete = new java.util.ArrayList<>();

    /** Widget-nummers in een opgeslagen indeling. */
    static List<Integer> widgetIds(String wsJson) {
        List<Integer> out = new java.util.ArrayList<>();
        if (wsJson == null) return out;
        try {
            JSONArray pages = new JSONObject(wsJson).optJSONArray("pages");
            if (pages != null) for (int i = 0; i < pages.length(); i++) {
                JSONArray p = pages.optJSONArray(i);
                if (p != null) for (int k = 0; k < p.length(); k++) {
                    JSONObject it = p.optJSONObject(k);
                    if (it != null && "widget".equals(it.optString("t")) && it.optInt("id", -1) >= 0) out.add(it.optInt("id"));
                }
            }
        } catch (Exception ignored) { }
        return out;
    }
}
