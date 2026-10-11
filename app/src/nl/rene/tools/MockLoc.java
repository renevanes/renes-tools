package nl.rene.tools;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Locatie kiezen: de telefoon denkt dat hij op een plek staat die jij op de kaart kiest (testlocatie van Android).
 * Werkt pas als Rene's Tools bij Ontwikkelaarsopties is gekozen als "app voor neplocatie". Het doorgeven zelf doet
 * {@link MockLocSvc}.
 */
final class MockLoc {
    private MockLoc() { }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("mockloc", Context.MODE_PRIVATE); }

    /** Is Rene's Tools gekozen als app voor neplocatie? */
    static boolean allowed(Context c) {
        try {
            AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            return ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), c.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) { return false; }
    }

    static boolean devOptionsOn(Context c) {
        try { return Settings.Global.getInt(c.getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0; } catch (Exception e) { return false; }
    }

    static boolean validLatLng(double lat, double lng) {
        return !Double.isNaN(lat) && !Double.isNaN(lng) && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180;
    }

    /** Aanzetten op deze plek. Geeft "" of "perm" (locatietoestemming nodig), "mock" (nog niet gekozen als neplocatie-app) of een melding. */
    static String start(Context c, double lat, double lng, String name) {
        if (!validLatLng(lat, lng)) return "Ongeldige plek";
        if (!Auto.fineOk(c)) return "perm";
        if (!allowed(c)) return "mock";
        String n = name == null ? "" : name.trim();
        if (n.length() > 60) n = n.substring(0, 60);
        prefs(c).edit().putString("lat", Double.toString(lat)).putString("lng", Double.toString(lng)).putString("name", n).putBoolean("active", true).apply();
        addRecent(c, lat, lng, n);
        Intent i = new Intent(c, MockLocSvc.class).setAction(MockLocSvc.START);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
            return "";
        } catch (Exception e) {
            return "Starten lukt niet: " + e.getClass().getSimpleName();
        }
    }

    static void stop(Context c) {
        MockLocSvc s = MockLocSvc.inst;
        if (s != null) s.h.post(s::stopMock); // op de hoofdthread, net als het doorgeven
        else cleanup(c);
    }

    /**
     * Testlocaties van Android weghalen zonder de dienst. Nodig als de app stopte zonder op te ruimen (update,
     * geforceerd stoppen, vastlopen): Android ruimt ze zelf pas op bij een herstart, en tot dan blijft de telefoon
     * op de laatste neplocatie staan.
     */
    static void cleanup(Context c) {
        android.location.LocationManager lm = (android.location.LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        java.util.List<String> ps = new java.util.ArrayList<>();
        ps.add(android.location.LocationManager.GPS_PROVIDER);
        ps.add(android.location.LocationManager.NETWORK_PROVIDER);
        if (Build.VERSION.SDK_INT >= 31) ps.add(android.location.LocationManager.FUSED_PROVIDER);
        for (String p : ps) {
            try { lm.setTestProviderEnabled(p, false); } catch (Exception ignored) { }
            try { lm.removeTestProvider(p); } catch (Exception ignored) { }
        }
        prefs(c).edit().putBoolean("active", false).apply();
    }

    /** Na een update of herstart: stond hij nog aan zonder dienst? Dan opruimen. */
    static void cleanupIfStale(Context c) {
        if (prefs(c).getBoolean("active", false) && !MockLocSvc.running) cleanup(c);
    }

    static double lat(Context c) { try { return Double.parseDouble(prefs(c).getString("lat", "")); } catch (Exception e) { return Double.NaN; } }
    static double lng(Context c) { try { return Double.parseDouble(prefs(c).getString("lng", "")); } catch (Exception e) { return Double.NaN; } }

    static JSONArray list(Context c, String key) {
        try { return new JSONArray(prefs(c).getString(key, "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    private static JSONObject place(double lat, double lng, String name) throws Exception {
        return new JSONObject().put("lat", Math.round(lat * 1e6) / 1e6).put("lng", Math.round(lng * 1e6) / 1e6).put("name", name);
    }

    private static void addRecent(Context c, double lat, double lng, String name) {
        try {
            JSONArray old = list(c, "recent"), out = new JSONArray();
            out.put(place(lat, lng, name));
            for (int i = 0; i < old.length() && out.length() < 8; i++) {
                JSONObject o = old.optJSONObject(i);
                if (o == null) continue;
                if (Math.abs(o.optDouble("lat") - lat) < 1e-5 && Math.abs(o.optDouble("lng") - lng) < 1e-5) continue;
                out.put(o);
            }
            prefs(c).edit().putString("recent", out.toString()).apply();
        } catch (Exception ignored) { }
    }

    static String savePlace(Context c, String name, double lat, double lng) {
        if (!validLatLng(lat, lng)) return "Ongeldige plek";
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) return "Geef de plek een naam";
        if (n.length() > 60) n = n.substring(0, 60);
        try {
            JSONArray a = list(c, "places");
            if (a.length() >= 50) return "Er zijn al 50 plekken bewaard";
            a.put(place(lat, lng, n));
            prefs(c).edit().putString("places", a.toString()).apply();
            return "";
        } catch (Exception e) { return "Bewaren lukt niet"; }
    }

    static void deletePlace(Context c, int i) {
        JSONArray a = list(c, "places"), out = new JSONArray();
        for (int j = 0; j < a.length(); j++) if (j != i) out.put(a.opt(j));
        prefs(c).edit().putString("places", out.toString()).apply();
    }

    static JSONObject state(Context c) {
        JSONObject o = new JSONObject();
        try {
            cleanupIfStale(c);
            double la = lat(c), ln = lng(c);
            o.put("on", MockLocSvc.running).put("allowed", allowed(c)).put("dev", devOptionsOn(c)).put("perm", Auto.fineOk(c))
                    .put("name", prefs(c).getString("name", "")).put("jitter", prefs(c).getBoolean("jitter", true))
                    .put("places", list(c, "places")).put("recent", list(c, "recent")).put("error", MockLocSvc.error == null ? "" : MockLocSvc.error);
            if (validLatLng(la, ln)) o.put("lat", la).put("lng", ln);
        } catch (Exception ignored) { }
        return o;
    }
}
