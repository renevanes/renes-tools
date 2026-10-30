package nl.rene.tools;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * Apparaten: wat bewaard wordt. Alles staat alleen op de telefoon, in de eigen map van de app (files/apparaten),
 * buiten de Android-backup (backup_rules/data_rules) en wel in "Alle appgegevens" (versleuteld archief, zie AppData).
 *
 *  instellingen (prefs "apparaten"): bg, every, trackers, hist, keep, away, thuis (homeLat/homeLon/homeSsid)
 *  bekend.json        je eigen apparaten: sleutel → {nick, type, name, t}
 *  geschiedenis.json  vaak in de buurt: sleutel → {name, type, maker, first, last, home[], away[], place}
 *                     (dagen als "jjjj-mm-dd", plek = "thuis" of een vakje van ~1 km; nooit een spoor)
 *  trackers.json      onbekende trackers: id → {kind, label, first, last, s:[[t, lat, lon]] (~100 m, hooguit 8 uur), warned, status}
 *  netwerk.json       apparaten op je eigen wifi, per netwerk: om nieuwe apparaten te laten opvallen
 */
final class ApparatenStore {

    private ApparatenStore() { }

    static final String DIR = "apparaten";

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("apparaten", Context.MODE_PRIVATE); }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "apparaten");
        if (!d.isDirectory() && !d.mkdirs()) App.log(c, "APPARATEN", "map maken lukt niet");
        return d;
    }

    private static JSONObject read(Context c, String name) {
        File f = new File(dir(c), name);
        try { return f.isFile() ? new JSONObject(Contacts.readText(f, false)) : new JSONObject(); }
        catch (Throwable e) { App.log(c, "APPARATEN", "lezen " + name + ": " + e); return new JSONObject(); }
    }

    private static void write(Context c, String name, JSONObject o) {
        try { Contacts.writeText(new File(dir(c), name), o.toString(), false); }
        catch (Throwable e) { App.log(c, "APPARATEN", "opslaan " + name + ": " + e); }
    }

    // ---------- instellingen ----------

    static final int[] EVERY = {15, 30, 60};
    static final int[] KEEP = {7, 30, 90};

    static JSONObject settings(Context c) {
        SharedPreferences p = prefs(c);
        JSONObject o = new JSONObject();
        try {
            o.put("bg", p.getBoolean("bg", false)).put("every", p.getInt("every", 30)).put("trackers", p.getBoolean("trackers", true))
                    .put("hist", p.getBoolean("hist", true)).put("keep", p.getInt("keep", 30)).put("away", p.getBoolean("away", false));
            boolean home = p.contains("homeLat");
            o.put("home", home).put("homeSsid", p.getString("homeSsid", ""));
        } catch (Exception ignored) { }
        return o;
    }

    private static boolean in(int v, int[] a) { for (int x : a) if (x == v) return true; return false; }

    /** Instellingen aanpassen (alleen bekende sleutels, alleen geldige waarden). */
    static void set(Context c, JSONObject o) {
        SharedPreferences.Editor e = prefs(c).edit();
        for (String k : new String[]{"bg", "trackers", "hist", "away"}) if (o.has(k)) e.putBoolean(k, o.optBoolean(k));
        if (o.has("every") && in(o.optInt("every"), EVERY)) e.putInt("every", o.optInt("every"));
        if (o.has("keep") && in(o.optInt("keep"), KEEP)) e.putInt("keep", o.optInt("keep"));
        e.apply();
    }

    static boolean bg(Context c) { return prefs(c).getBoolean("bg", false); }
    static boolean trackersOn(Context c) { return prefs(c).getBoolean("trackers", true); }
    static boolean histOn(Context c) { return prefs(c).getBoolean("hist", true); }
    static int keep(Context c) { return prefs(c).getInt("keep", 30); }

    /** Thuis: een grove plek (~100 m) en eventueel de naam van je wifi thuis. */
    static void setHome(Context c, double lat, double lon, String ssid) {
        SharedPreferences.Editor e = prefs(c).edit();
        if (Double.isNaN(lat)) e.remove("homeLat").remove("homeLon");
        else e.putString("homeLat", String.valueOf(Apparaten.round3(lat))).putString("homeLon", String.valueOf(Apparaten.round3(lon)));
        e.putString("homeSsid", ssid == null ? "" : ssid).apply();
    }

    static void clearHome(Context c) { prefs(c).edit().remove("homeLat").remove("homeLon").remove("homeSsid").apply(); }

    static double[] home(Context c) {
        SharedPreferences p = prefs(c);
        try { return p.contains("homeLat") ? new double[]{Double.parseDouble(p.getString("homeLat", "")), Double.parseDouble(p.getString("homeLon", ""))} : null; }
        catch (Exception e) { return null; }
    }

    /** Ben je thuis? Verbonden met je wifi thuis, of binnen 300 m van de plek die je als thuis koos. */
    static boolean atHome(Context c, double lat, double lon, String ssid) {
        String hs = prefs(c).getString("homeSsid", "");
        if (!hs.isEmpty() && hs.equals(ssid)) return true;
        double[] h = home(c);
        return h != null && Apparaten.atHome(lat, lon, h[0], h[1]);
    }

    // ---------- bekende apparaten ----------

    static synchronized JSONObject known(Context c) { return read(c, "bekend.json"); }

    static synchronized void setKnown(Context c, String key, String nick, String type, String name) {
        if (key == null || key.length() > 120 || !Apparaten.stableKey(key)) return;
        JSONObject k = known(c);
        try {
            if (nick == null) k.remove(key);
            else k.put(key, new JSONObject().put("nick", Apparaten.clean(nick)).put("type", type == null ? "" : type).put("name", name == null ? "" : Apparaten.clean(name)).put("t", System.currentTimeMillis()));
        } catch (Exception ignored) { }
        write(c, "bekend.json", k);
    }

    // ---------- geschiedenis ----------

    static Map<String, Apparaten.Hist> histLoad(Context c) {
        Map<String, Apparaten.Hist> m = new HashMap<>();
        JSONObject o = read(c, "geschiedenis.json");
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject x = o.optJSONObject(k);
            if (x == null) continue;
            Apparaten.Hist h = new Apparaten.Hist();
            h.key = k; h.name = x.optString("name"); h.type = x.optString("type"); h.maker = x.optString("maker");
            h.first = x.optLong("first"); h.last = x.optLong("last"); h.lastPlace = x.optString("place");
            JSONArray a = x.optJSONArray("home"), b = x.optJSONArray("away");
            if (a != null) for (int i = 0; i < a.length(); i++) h.homeDays.add(a.optString(i));
            if (b != null) for (int i = 0; i < b.length(); i++) h.awayDays.add(b.optString(i));
            m.put(k, h);
        }
        return m;
    }

    static void histSave(Context c, Map<String, Apparaten.Hist> m) {
        JSONObject o = new JSONObject();
        try {
            for (Apparaten.Hist h : m.values())
                o.put(h.key, new JSONObject().put("name", h.name).put("type", h.type).put("maker", h.maker).put("first", h.first).put("last", h.last)
                        .put("place", h.lastPlace).put("home", new JSONArray(h.homeDays)).put("away", new JSONArray(h.awayDays)));
        } catch (Exception ignored) { }
        write(c, "geschiedenis.json", o);
    }

    /** Eén waarneming per apparaat in één ronde. place = "thuis", een vakje van ~1 km of "". */
    static final class Seen { String key, name, type, maker; }

    static synchronized void histRecord(Context c, List<Seen> seen, long now, String place) {
        if (seen.isEmpty()) return;
        Map<String, Apparaten.Hist> m = histLoad(c);
        TimeZone tz = TimeZone.getDefault();
        for (Seen s : seen) {
            if (!Apparaten.stableKey(s.key)) continue;
            Apparaten.Hist h = m.get(s.key);
            if (h == null) { h = new Apparaten.Hist(); h.key = s.key; m.put(s.key, h); }
            if (s.name != null && !s.name.isEmpty()) h.name = s.name;
            if (s.type != null && !s.type.isEmpty()) h.type = s.type;
            if (s.maker != null && !s.maker.isEmpty()) h.maker = s.maker;
            Apparaten.record(h, now, place, tz);
        }
        Apparaten.prune(m, now, keep(c), tz);
        histSave(c, m);
    }

    static synchronized void histPrune(Context c) {
        Map<String, Apparaten.Hist> m = histLoad(c);
        int n = m.size();
        Apparaten.prune(m, System.currentTimeMillis(), keep(c), TimeZone.getDefault());
        if (m.size() != n || n > 0) histSave(c, m);
    }

    static JSONObject histJson(Apparaten.Hist h) throws Exception {
        return new JSONObject().put("key", h.key).put("name", h.name).put("type", h.type).put("maker", h.maker).put("first", h.first).put("last", h.last)
                .put("place", h.lastPlace).put("homeDays", h.homeDays.size()).put("awayDays", h.awayDays.size()).put("days", h.days());
    }

    /** Overzicht: vaak thuis, vaak elders, en je eigen apparaten met waar ze het laatst gezien zijn. */
    static synchronized JSONObject histOverview(Context c) {
        JSONObject o = new JSONObject();
        try {
            Map<String, Apparaten.Hist> m = histLoad(c);
            Apparaten.prune(m, System.currentTimeMillis(), keep(c), TimeZone.getDefault());
            JSONObject k = known(c);
            JSONArray home = new JSONArray(), away = new JSONArray(), mine = new JSONArray();
            for (Apparaten.Hist h : Apparaten.often(m.values(), false, 2)) if (!k.has(h.key) && home.length() < 40) home.put(histJson(h));
            for (Apparaten.Hist h : Apparaten.often(m.values(), true, 2)) if (!k.has(h.key) && away.length() < 40) away.put(histJson(h));
            for (Iterator<String> it = k.keys(); it.hasNext(); ) {
                String key = it.next();
                JSONObject x = k.optJSONObject(key);
                Apparaten.Hist h = m.get(key);
                JSONObject j = h != null ? histJson(h) : new JSONObject().put("key", key).put("name", x.optString("name")).put("type", x.optString("type"));
                mine.put(j.put("nick", x.optString("nick")).put("known", true));
            }
            o.put("home", home).put("away", away).put("mine", mine).put("count", m.size()).put("keep", keep(c));
        } catch (Exception ignored) { }
        return o;
    }

    // ---------- trackers ----------

    static synchronized JSONObject trackers(Context c) { return read(c, "trackers.json"); }
    static synchronized void trackersSave(Context c, JSONObject o) { write(c, "trackers.json", o); }

    static List<Apparaten.Sighting> sightings(JSONObject t) {
        List<Apparaten.Sighting> l = new ArrayList<>();
        JSONArray s = t == null ? null : t.optJSONArray("s");
        if (s != null) for (int i = 0; i < s.length(); i++) {
            JSONArray x = s.optJSONArray(i);
            if (x != null && x.length() >= 3) l.add(new Apparaten.Sighting(x.optLong(0), x.optDouble(1, Double.NaN), x.optDouble(2, Double.NaN)));
        }
        return l;
    }

    /** Oude waarnemingen weg (buiten het venster); trackers zonder waarnemingen na een dag weg, "negeren" na een week. */
    static void trackersPrune(JSONObject all, long now) {
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            String id = it.next();
            JSONObject t = all.optJSONObject(id);
            if (t == null) { it.remove(); continue; }
            JSONArray s = t.optJSONArray("s"), keep = new JSONArray();
            if (s != null) for (int i = 0; i < s.length(); i++) { JSONArray x = s.optJSONArray(i); if (x != null && now - x.optLong(0) <= Apparaten.FOLLOW_WINDOW) keep.put(x); }
            try { t.put("s", keep); } catch (Exception ignored) { }
            long last = t.optLong("last");
            String st = t.optString("status");
            if (st.isEmpty() && now - last > 86_400_000L) it.remove();
            else if (!st.isEmpty() && now - last > 7 * 86_400_000L) it.remove();
        }
    }

    // ---------- eigen netwerk ----------

    static synchronized JSONObject network(Context c) { return read(c, "netwerk.json"); }
    static synchronized void networkSave(Context c, JSONObject o) { write(c, "netwerk.json", o); }

    /** Alles wissen: geschiedenis, trackers, apparaten op je netwerk (bijnamen en instellingen blijven, tenzij all). */
    static synchronized void wipe(Context c, boolean all) {
        for (String n : new String[]{"geschiedenis.json", "trackers.json", "netwerk.json"}) { File f = new File(dir(c), n); if (f.exists() && !f.delete()) App.log(c, "APPARATEN", "wissen lukt niet: " + n); }
        if (all) { File f = new File(dir(c), "bekend.json"); if (f.exists() && !f.delete()) App.log(c, "APPARATEN", "wissen lukt niet: bekend"); clearHome(c); }
    }
}
