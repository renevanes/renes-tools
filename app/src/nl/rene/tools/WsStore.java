package nl.rene.tools;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/** Opslaan en laden van het launcher-werkblad (WsModel) als JSON, met overname van de oude skin-indeling. */
final class WsStore {

    private WsStore() { }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("workspace", Context.MODE_PRIVATE); }

    static JSONObject item(WsModel.Item i) throws Exception {
        JSONObject o = new JSONObject().put("t", i.type).put("x", i.x).put("y", i.y).put("w", i.w).put("h", i.h);
        if (i.isApp()) o.put("k", i.key);
        if (i.isFolder()) { o.put("n", i.name); JSONArray a = new JSONArray(); for (String k : i.apps) a.put(k); o.put("a", a); }
        if (i.isWidget()) o.put("id", i.widgetId).put("p", i.provider);
        return o;
    }

    static WsModel.Item item(WsModel m, JSONObject o) {
        String t = o.optString("t", WsModel.APP);
        if (!WsModel.APP.equals(t) && !WsModel.FOLDER.equals(t) && !WsModel.WIDGET.equals(t)) return null;
        WsModel.Item i = m.newItem(t);
        i.x = o.optInt("x"); i.y = o.optInt("y"); i.w = Math.max(1, o.optInt("w", 1)); i.h = Math.max(1, o.optInt("h", 1));
        i.key = o.optString("k"); i.name = o.optString("n", "Map");
        JSONArray a = o.optJSONArray("a");
        if (a != null) for (int k = 0; k < a.length(); k++) if (!a.optString(k).isEmpty()) i.apps.add(a.optString(k));
        i.widgetId = o.optInt("id", -1); i.provider = o.optString("p");
        if (i.isApp() && i.key.isEmpty()) return null;
        if (i.isFolder() && i.apps.isEmpty()) return null;
        if (i.isWidget() && i.widgetId < 0) return null;
        return i;
    }

    static String toJson(WsModel m, int home) {
        try {
            JSONObject o = new JSONObject().put("v", 1).put("cols", m.cols).put("rows", m.rows).put("home", home);
            JSONArray pages = new JSONArray();
            for (WsModel.Page p : m.pages) { JSONArray a = new JSONArray(); for (WsModel.Item i : p.items) a.put(item(i)); pages.put(a); }
            JSONArray dock = new JSONArray();
            for (WsModel.Item i : m.dock) dock.put(item(i));
            return o.put("pages", pages).put("dock", dock).toString();
        } catch (Exception e) { return null; }
    }

    static void save(Context c, WsModel m, int home) {
        String s = toJson(m, home);
        if (s != null) prefs(c).edit().putString("ws", s).apply();
    }

    /** Laden; geen opgeslagen werkblad → overnemen uit de skin-indeling (apps op het startscherm, dock). */
    static WsModel load(Context c, int cols, int rows, boolean rowsAuto, int[] homeOut) {
        WsModel m = new WsModel();
        m.cols = cols; m.rows = rows;
        String s = prefs(c).getString("ws", null);
        try {
            if (s != null) {
                JSONObject o = new JSONObject(s);
                m.cols = o.optInt("cols", cols); m.rows = o.optInt("rows", rows);
                m.pages.clear();
                java.util.List<WsModel.Item> later = new java.util.ArrayList<>();
                JSONArray pages = o.optJSONArray("pages");
                if (pages != null) for (int p = 0; p < pages.length(); p++) {
                    WsModel.Page pg = new WsModel.Page();
                    JSONArray a = pages.optJSONArray(p);
                    if (a != null) for (int k = 0; k < a.length(); k++) {
                        WsModel.Item i = a.optJSONObject(k) == null ? null : item(m, a.getJSONObject(k));
                        if (i == null) continue;
                        // Kapotte of overlappende plek: later opnieuw plaatsen
                        if (m.isFree(pg, i.x, i.y, i.w, i.h, null)) pg.items.add(i);
                        else later.add(i);
                    }
                    m.pages.add(pg);
                }
                if (m.pages.isEmpty()) m.pages.add(new WsModel.Page());
                for (WsModel.Item i : later) if (m.place(i, 0) < 0) m.lost.add(i);
                JSONArray dock = o.optJSONArray("dock");
                if (dock != null) for (int k = 0; k < dock.length() && m.dock.size() < WsModel.DOCK_MAX; k++) {
                    WsModel.Item i = dock.optJSONObject(k) == null ? null : item(m, dock.getJSONObject(k));
                    if (i != null && !i.isWidget()) m.dock.add(i);
                }
                homeOut[0] = Math.max(0, Math.min(o.optInt("home", 0), m.pages.size() - 1));
                // Kolommen of (zelf gekozen) rijen veranderd: opnieuw verdelen wat niet past. Automatische rijen blijven zoals ze waren.
                m.setGrid(cols, rowsAuto ? m.rows : rows);
                return m;
            }
        } catch (Exception e) { App.log(c, "START", "werkblad niet te lezen: " + e); m = new WsModel(); m.cols = cols; m.rows = rows; }
        // Overnemen uit de skin (start.html bewaarde pinned en dock in de launcher-instellingen)
        try {
            JSONObject cfg = new JSONObject(Launcher.prefs(c).getString("cfg", "{}"));
            JSONArray pinned = cfg.optJSONArray("pinned"), dock = cfg.optJSONArray("dock");
            JSONObject folders = cfg.optJSONObject("folders");
            if (pinned != null) for (int k = 0; k < pinned.length(); k++) {
                String key = pinned.optString(k);
                WsModel.Item i;
                if (key.startsWith("f:") && folders != null && folders.optJSONArray(key.substring(2)) != null) {
                    i = m.newItem(WsModel.FOLDER); i.name = key.substring(2);
                    JSONArray a = folders.optJSONArray(key.substring(2));
                    for (int j = 0; j < a.length(); j++) i.apps.add(a.optString(j));
                    if (i.apps.isEmpty()) continue;
                } else if (!key.startsWith("f:") && !key.isEmpty()) { i = m.newItem(WsModel.APP); i.key = key; }
                else continue;
                m.place(i, 0);
            }
            if (dock != null) for (int k = 0; k < dock.length() && m.dock.size() < WsModel.DOCK_MAX; k++) {
                String key = dock.optString(k);
                if (key.isEmpty() || key.startsWith("f:")) continue;
                WsModel.Item i = m.newItem(WsModel.APP); i.key = key; m.dock.add(i);
            }
        } catch (Exception ignored) { }
        homeOut[0] = 0;
        return m;
    }
}
