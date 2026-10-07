package nl.rene.tools;

import android.Manifest;
import android.app.AlarmManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.provider.CalendarContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;

/**
 * Telefoon-skin (startscherm): de gegevens achter HomeActivity. Apps en hun pictogrammen, agenda, weer
 * (Open-Meteo, zonder sleutel) en de volgende wekker. De indeling en de look bewaart de interface zelf als JSON.
 */
final class Launcher {

    private Launcher() { }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("launcher", Context.MODE_PRIVATE); }

    static boolean has(Context c, String p) { return c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    // ---------- apps ----------

    static String key(ResolveInfo r) { return r.activityInfo.packageName + "/" + r.activityInfo.name; }

    static ComponentName component(String key) {
        int i = key == null ? -1 : key.indexOf('/');
        return i <= 0 ? null : new ComponentName(key.substring(0, i), key.substring(i + 1));
    }

    /** Alle apps met een pictogram in het app-overzicht: {k, n (naam), p (pakket), t (geïnstalleerd), u (bijgewerkt), sys}. */
    static String appsJson(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> l = pm.queryIntentActivities(i, 0);
        JSONArray a = new JSONArray();
        for (ResolveInfo r : l) {
            try {
                CharSequence label = r.loadLabel(pm);
                JSONObject o = new JSONObject().put("k", key(r)).put("n", label == null ? r.activityInfo.packageName : label.toString().trim())
                        .put("p", r.activityInfo.packageName);
                try {
                    android.content.pm.PackageInfo pi = pm.getPackageInfo(r.activityInfo.packageName, 0);
                    o.put("t", pi.firstInstallTime).put("u", pi.lastUpdateTime);
                } catch (Exception ignored) { }
                o.put("sys", (r.activityInfo.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0);
                a.put(o);
            } catch (Exception ignored) { }
        }
        return a.toString();
    }

    /** Pictogram als PNG (vierkant, size px), met een cache op schijf per app-versie. */
    static byte[] iconPng(Context c, String key, int size) {
        ComponentName cn = component(key);
        if (cn == null) return null;
        size = Math.max(32, Math.min(256, size));
        PackageManager pm = c.getPackageManager();
        long upd = 0;
        try { upd = pm.getPackageInfo(cn.getPackageName(), 0).lastUpdateTime; } catch (Exception ignored) { }
        File dir = new File(c.getCacheDir(), "icons");
        dir.mkdirs();
        File f = new File(dir, Integer.toHexString((key + "|" + upd + "|" + size).hashCode()) + ".png");
        try {
            if (f.isFile()) try (java.io.FileInputStream in = new java.io.FileInputStream(f)) { return SelfTest.readAll(in); }
        } catch (Exception ignored) { }
        try {
            Drawable d;
            try { d = pm.getActivityIcon(cn); } catch (Exception e) { d = pm.getApplicationIcon(cn.getPackageName()); }
            Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            d.setBounds(0, 0, size, size);
            d.draw(cv);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
            b.recycle();
            byte[] png = o.toByteArray();
            File tmp = new File(dir, f.getName() + "." + Thread.currentThread().getId() + ".tmp");
            try (FileOutputStream fo = new FileOutputStream(tmp)) { fo.write(png); } catch (Exception ignored) { }
            if (!tmp.renameTo(f)) tmp.delete(); // in één keer, zodat niemand een half bestand leest
            return png;
        } catch (Exception e) { return null; }
    }

    /** Apps die in de skin geblokkeerd zijn: verborgen én "Verborgen apps blokkeren" aan (Look aanpassen). */
    static java.util.Set<String> blocked(Context c) {
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            JSONObject cfg = new JSONObject(prefs(c).getString("cfg", "{}"));
            if (!cfg.optBoolean("lockHidden", false)) return out;
            JSONArray h = cfg.optJSONArray("hidden");
            if (h != null) for (int i = 0; i < h.length(); i++) out.add(h.optString(i));
        } catch (Exception ignored) { }
        return out;
    }

    /** Starten vanuit de skin: geblokkeerde apps niet. */
    static String launchFromSkin(Context c, String key) {
        if (key != null && blocked(c).contains(key)) return "Deze app is geblokkeerd in de skin (Look aanpassen → Apps in de skin)";
        return launch(c, key);
    }

    static String launch(Context c, String key) {
        ComponentName cn = component(key);
        if (cn == null) return "Onbekende app";
        // Alleen echte app-pictogrammen (geen interne schermen van deze app zelf).
        if (cn.getPackageName().equals(c.getPackageName()) && !cn.getClassName().equals(MainActivity.class.getName())) return "Onbekende app";
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            c.startActivity(i);
            return "";
        } catch (Exception e) { return "Deze app kan niet worden geopend"; }
    }

    static void appInfo(Context c, String key) {
        ComponentName cn = component(key);
        if (cn == null) return;
        try {
            c.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", cn.getPackageName(), null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) { }
    }

    static void uninstall(Context c, String key) {
        ComponentName cn = component(key);
        if (cn == null) return;
        try { c.startActivity(new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", cn.getPackageName(), null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception ignored) { }
    }

    // ---------- agenda ----------

    /** Komende afspraken (7 dagen, max. 8): {title, begin, end, allDay, loc, id}. */
    static String calendarJson(Context c) {
        try {
            if (!has(c, Manifest.permission.READ_CALENDAR)) return new JSONObject().put("perm", false).toString();
            long now = System.currentTimeMillis();
            Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
            android.content.ContentUris.appendId(b, now - 12 * 3600_000L);
            android.content.ContentUris.appendId(b, now + 7 * 24 * 3600_000L);
            JSONArray a = new JSONArray();
            try (Cursor cur = c.getContentResolver().query(b.build(), new String[]{
                    CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.EVENT_ID,
                    CalendarContract.Instances.DISPLAY_COLOR},
                    CalendarContract.Instances.VISIBLE + "=1", null, CalendarContract.Instances.BEGIN + " ASC")) {
                if (cur != null) while (cur.moveToNext() && a.length() < 8) {
                    long begin = cur.getLong(1), end = cur.getLong(2);
                    boolean allDay = cur.getInt(3) == 1;
                    if (allDay) { // hele-dag-afspraken staan in UTC: naar middernacht hier
                        java.util.TimeZone tz = java.util.TimeZone.getDefault();
                        begin -= tz.getOffset(begin);
                        end -= tz.getOffset(end);
                    }
                    if (end < now) continue; // al voorbij
                    a.put(new JSONObject().put("title", cur.getString(0) == null ? "(zonder titel)" : cur.getString(0))
                            .put("begin", begin).put("end", end).put("allDay", allDay)
                            .put("loc", cur.getString(4) == null ? "" : cur.getString(4)).put("id", cur.getLong(5))
                            .put("color", String.format(Locale.US, "#%06X", cur.getInt(6) & 0xFFFFFF)));
                }
            }
            return new JSONObject().put("perm", true).put("events", a).toString();
        } catch (Exception e) { return "{\"perm\":true,\"events\":[],\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}"; }
    }

    static void openEvent(Context c, long id) {
        try {
            Intent i = id > 0 ? new Intent(Intent.ACTION_VIEW, android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id))
                    : new Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build());
            c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            try { c.startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception ignored) { }
        }
    }

    // ---------- wekker ----------

    static long nextAlarm(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        AlarmManager.AlarmClockInfo i = am == null ? null : am.getNextAlarmClock();
        return i == null ? 0 : i.getTriggerTime();
    }

    static void openClock(Context c) {
        try { c.startActivity(new Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception ignored) { }
    }

    // ---------- weer ----------

    /** Plek voor het weer: zelf gekozen plaats, anders de laatst bekende locatie van de telefoon. */
    static double[] place(Context c) {
        SharedPreferences p = prefs(c);
        if (p.contains("lat")) return new double[]{Double.longBitsToDouble(p.getLong("lat", 0)), Double.longBitsToDouble(p.getLong("lon", 0))};
        if (!has(c, Manifest.permission.ACCESS_COARSE_LOCATION) && !has(c, Manifest.permission.ACCESS_FINE_LOCATION)) return null;
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            Location best = null;
            for (String prov : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(prov);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            return best == null ? null : new double[]{best.getLatitude(), best.getLongitude()};
        } catch (SecurityException e) { return null; }
    }

    static void setPlace(Context c, String name, double lat, double lon) {
        SharedPreferences.Editor e = prefs(c).edit().remove("weather");
        if (name == null || name.isEmpty()) e.remove("lat").remove("lon").remove("placeName");
        else e.putLong("lat", Double.doubleToLongBits(lat)).putLong("lon", Double.doubleToLongBits(lon)).putString("placeName", name);
        e.apply();
    }

    static String get(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(10_000);
        h.setReadTimeout(15_000);
        h.setRequestProperty("User-Agent", Radio.UA);
        try (java.io.InputStream in = h.getInputStream()) { return new String(SelfTest.readAll(in), "UTF-8"); }
        finally { h.disconnect(); }
    }

    /** Getal of null (Open-Meteo geeft soms null; JSONObject.put(NaN) zou de hele uitkomst laten mislukken). */
    static Object num(JSONObject o, String k) { double d = o.optDouble(k); return Double.isNaN(d) ? JSONObject.NULL : d; }
    static Object num(JSONArray a, int i) { double d = a.optDouble(i); return Double.isNaN(d) ? JSONObject.NULL : d; }

    /** Weer van nu en de komende dagen; 30 minuten in de cache. */
    static synchronized String weatherJson(Context c, boolean force) {
        SharedPreferences p = prefs(c);
        try {
            String cached = p.getString("weather", null);
            if (!force && cached != null && System.currentTimeMillis() - new JSONObject(cached).optLong("t") < 30 * 60_000L) return cached;
            double[] at = place(c);
            if (at == null) return new JSONObject().put("noPlace", true).put("locPerm", has(c, Manifest.permission.ACCESS_COARSE_LOCATION)).toString();
            String u = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                    + "&current=temperature_2m,weather_code,is_day,wind_speed_10m,precipitation"
                    + "&hourly=precipitation_probability&forecast_hours=6"
                    + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset&forecast_days=4&timezone=auto", at[0], at[1]);
            JSONObject w = new JSONObject(get(u));
            JSONObject cur = w.getJSONObject("current"), d = w.getJSONObject("daily");
            JSONArray days = new JSONArray();
            for (int i = 0; i < d.getJSONArray("time").length(); i++)
                days.put(new JSONObject().put("date", d.getJSONArray("time").getString(i)).put("code", d.getJSONArray("weather_code").optInt(i))
                        .put("max", num(d.getJSONArray("temperature_2m_max"), i)).put("min", num(d.getJSONArray("temperature_2m_min"), i))
                        .put("rain", d.getJSONArray("precipitation_probability_max").optInt(i, -1)));
            int rainSoon = -1;
            JSONObject hourly = w.optJSONObject("hourly");
            if (hourly != null && hourly.optJSONArray("precipitation_probability") != null) {
                JSONArray pp = hourly.getJSONArray("precipitation_probability");
                for (int i = 0; i < pp.length(); i++) rainSoon = Math.max(rainSoon, pp.optInt(i, -1));
            }
            JSONObject o = new JSONObject().put("t", System.currentTimeMillis())
                    .put("temp", num(cur, "temperature_2m")).put("code", cur.optInt("weather_code")).put("day", cur.optInt("is_day", 1) == 1)
                    .put("wind", num(cur, "wind_speed_10m")).put("rainSoon", rainSoon)
                    .put("sunrise", d.optJSONArray("sunrise") == null ? "" : d.getJSONArray("sunrise").optString(0))
                    .put("sunset", d.optJSONArray("sunset") == null ? "" : d.getJSONArray("sunset").optString(0))
                    .put("place", p.getString("placeName", "")).put("days", days);
            p.edit().putString("weather", o.toString()).apply();
            return o.toString();
        } catch (Exception e) {
            String cached = p.getString("weather", null);
            if (cached != null) return cached; // liever oud weer dan geen weer
            try { return new JSONObject().put("error", "Weer niet op te halen").toString(); } catch (Exception x) { return "{}"; }
        }
    }

    /** Plaatsen zoeken (Open-Meteo geocoding). */
    static String citiesJson(String q) {
        try {
            JSONObject r = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=8&language=nl&name=" + Uri.encode(q)));
            JSONArray in = r.optJSONArray("results"), out = new JSONArray();
            if (in != null) for (int i = 0; i < in.length(); i++) {
                JSONObject x = in.getJSONObject(i);
                out.put(new JSONObject().put("name", x.optString("name")).put("sub", (x.optString("admin1") + ", " + x.optString("country")).replaceAll("^, |, $", ""))
                        .put("lat", x.optDouble("latitude")).put("lon", x.optDouble("longitude")));
            }
            return out.toString();
        } catch (Exception e) { return "{\"error\":\"Zoeken lukt nu niet\"}"; }
    }

    // ---------- Rene's Tools op het startscherm ----------

    /** Overzicht: backup, radio, herinneringen van vandaag, geplande Auto redial. */
    static String toolsJson(Context c) {
        try {
            JSONObject o = new JSONObject();
            o.put("backup", new JSONObject(AllBackup.stateJson(c, false)));
            o.put("radio", new JSONObject(RadioService.stateJson()));
            String last = Radio.prefs(c).getString("last", null);
            if (last != null) o.put("radioLast", new JSONObject(last));
            // Met app-slot aan: geen herinneringen of te bellen nummers op het startscherm.
            if (!Lock.active(c)) {
                o.put("reminders", new JSONObject(Reminders.all(c)));
                o.put("redial", new JSONObject(RedialPlan.stateJson(c)));
                o.put("birthdays", new JSONArray(Birthdays.upcoming(c, 7)));
            }
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    static boolean isDefaultHome(Context c) {
        try {
            ResolveInfo r = c.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
            return r != null && r.activityInfo != null && c.getPackageName().equals(r.activityInfo.packageName);
        } catch (Exception e) { return false; }
    }
}
