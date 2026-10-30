package nl.rene.tools;

import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Process;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * Opslag en samenstellen van de gegevens voor "Gebruik": het overnemen van de gebeurtenissen uit UsageStatsManager naar
 * een eigen, compacte opslag op de telefoon, de blokken per ontgrendeling, schermtijd, telefoonfuncties, meldingen en
 * opladen, de instellingen en bewaartermijnen, en de foto's bij ontgrendelen. Het rekenwerk zelf staat in Gebruik
 * (puur, getest). Alles blijft op de telefoon; niets gaat naar buiten.
 *
 * Android houdt de losse gebeurtenissen maar een paar dagen bij; daarom neemt een taak (GebruikJob) ze geregeld over.
 * Camera, microfoon, Bluetooth/wifi/data-wissels zijn alleen te zien zolang de app leeft (Android staat niet meer toe);
 * dat staat eerlijk in de interface.
 */
final class GebruikStore {

    private GebruikStore() { }

    static final String PREFS = "gebruik";
    static final String DIR = "gebruik";
    static final String EVENTS = "events.jsonl";   // t|type|pkg (detail-bewaartermijn)
    static final String PW = "pw.jsonl";            // t|1/0 (gelukte/mislukte poging)
    static final String FUNCS = "funcs.jsonl";      // t|soort|pkg (camera, microfoon, wifi, bt, data, opladen, herstart)
    static final String DAYS = "days.json";         // samenvatting per dag (lange bewaartermijn)
    static final String PHOTO_DIR = "foto";

    // Standaardinstellingen
    static final int KEEP_DEFAULT = 365, DETAIL_DEFAULT = 60, PHOTO_AFTER_DEFAULT = 3, PHOTO_KEEP_DEFAULT = 30;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    static File dir(Context c) { File d = new File(c.getFilesDir(), DIR); if (!d.isDirectory()) d.mkdirs(); return d; }
    static File photoDir(Context c) { File d = new File(dir(c), PHOTO_DIR); if (!d.isDirectory()) d.mkdirs(); return d; }
    static TimeZone tz() { return TimeZone.getDefault(); }

    static JSONObject err(String m) { try { return new JSONObject().put("error", m); } catch (Exception e) { return new JSONObject(); } }

    // ---------- instellingen ----------

    static boolean collecting(Context c) { return prefs(c).getBoolean("collect", true); }
    static int keepDays(Context c) { return prefs(c).getInt("keep", KEEP_DEFAULT); }
    static int detailDays(Context c) { return prefs(c).getInt("detail", DETAIL_DEFAULT); }
    static boolean photoFail(Context c) { return prefs(c).getBoolean("photoFail", false); }
    static boolean photoUnlock(Context c) { return prefs(c).getBoolean("photoUnlock", false); }
    static int photoAfter(Context c) { return prefs(c).getInt("photoAfter", PHOTO_AFTER_DEFAULT); }
    static int photoKeepDays(Context c) { return prefs(c).getInt("photoKeep", PHOTO_KEEP_DEFAULT); }

    static JSONObject settings(Context c) {
        SharedPreferences p = prefs(c);
        try {
            return new JSONObject().put("collect", collecting(c)).put("keep", keepDays(c)).put("detail", detailDays(c))
                    .put("photoFail", photoFail(c)).put("photoUnlock", photoUnlock(c)).put("photoAfter", photoAfter(c))
                    .put("photoKeep", photoKeepDays(c));
        } catch (Exception e) { return new JSONObject(); }
    }

    /** Eén of meer instellingen bewaren. */
    static void saveSettings(Context c, JSONObject o) {
        SharedPreferences.Editor e = prefs(c).edit();
        if (o.has("collect")) e.putBoolean("collect", o.optBoolean("collect"));
        if (o.has("keep")) e.putInt("keep", clamp(o.optInt("keep"), 30, 730));
        if (o.has("detail")) e.putInt("detail", clamp(o.optInt("detail"), 7, 180));
        if (o.has("photoFail")) e.putBoolean("photoFail", o.optBoolean("photoFail"));
        if (o.has("photoUnlock")) e.putBoolean("photoUnlock", o.optBoolean("photoUnlock"));
        if (o.has("photoAfter")) e.putInt("photoAfter", clamp(o.optInt("photoAfter"), 1, 10));
        if (o.has("photoKeep")) e.putInt("photoKeep", clamp(o.optInt("photoKeep"), 1, 365));
        e.apply();
        prune(c);
    }

    static int clamp(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }

    // ---------- toegang ----------

    /** Bijzondere toegang "Toegang tot gebruik" (PACKAGE_USAGE_STATS) via Instellingen; zonder dit ziet de app niets. */
    static boolean usageAccess(Context c) {
        try {
            AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) return false;
            int mode;
            if (Build.VERSION.SDK_INT >= 29) mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
            else mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
            if (mode == AppOpsManager.MODE_DEFAULT) return c.checkCallingOrSelfPermission("android.permission.PACKAGE_USAGE_STATS") == PackageManager.PERMISSION_GRANTED;
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable e) { return false; }
    }

    static ComponentName admin(Context c) { return new ComponentName(c, GebruikAdmin.class); }

    static boolean adminActive(Context c) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
            return dpm != null && dpm.isAdminActive(admin(c));
        } catch (Throwable e) { return false; }
    }

    // ---------- overnemen van gebeurtenissen (UsageStatsManager) ----------

    /** Neemt nieuwe gebeurtenissen over sinds de laatste keer en werkt de samenvatting per dag bij. Vangt alles af. */
    static synchronized void collect(Context c) {
        try {
            if (!collecting(c) || !usageAccess(c)) return;
            long now = System.currentTimeMillis();
            long since = prefs(c).getLong("watermark", 0);
            if (since <= 0) since = now - 3L * 86_400_000L; // eerste keer: hooguit 3 dagen terug (verder houdt Android toch niet bij)
            android.app.usage.UsageStatsManager usm = (android.app.usage.UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return;
            android.app.usage.UsageEvents ue = usm.queryEvents(since, now);
            android.app.usage.UsageEvents.Event ev = new android.app.usage.UsageEvents.Event();
            StringBuilder lines = new StringBuilder();
            long max = since;
            String own = c.getPackageName();
            while (ue != null && ue.getNextEvent(ev)) {
                int t = mapType(ev.getEventType());
                if (t == 0) continue;
                long when = ev.getTimeStamp();
                if (when <= since) continue;
                if (when > max) max = when;
                String pkg = (t == Gebruik.APP_START || t == Gebruik.APP_STOP) ? ev.getPackageName() : "";
                if (pkg == null) pkg = "";
                if (pkg.equals(own)) pkg = own; // eigen app ook tonen (onder eigen naam)
                lines.append(when).append('|').append(t).append('|').append(pkg).append('\n');
            }
            if (lines.length() > 0) append(new File(dir(c), EVENTS), lines.toString());
            prefs(c).edit().putLong("watermark", max).putLong("lastCollect", now).apply();
            aggregate(c);
            snapshotFuncs(c);
            prune(c);
        } catch (Throwable e) { App.log(c, "GEBRUIK", "overnemen: " + e); }
    }

    /** UsageEvents-soort → eigen Gebruik-soort (0 = niet van belang). */
    static int mapType(int t) {
        switch (t) {
            case 1: return Gebruik.APP_START;  // MOVE_TO_FOREGROUND / ACTIVITY_RESUMED
            case 2: return Gebruik.APP_STOP;   // MOVE_TO_BACKGROUND / ACTIVITY_PAUSED
            case 15: return Gebruik.SCREEN_ON; // SCREEN_INTERACTIVE
            case 16: return Gebruik.SCREEN_OFF;// SCREEN_NON_INTERACTIVE
            case 17: return Gebruik.LOCKED;    // KEYGUARD_SHOWN
            case 18: return Gebruik.UNLOCKED;  // KEYGUARD_HIDDEN
            case 26: return Gebruik.BOOT;      // DEVICE_STARTUP
            default: return 0;
        }
    }

    /** Leest de bewaarde gebeurtenissen (binnen de detail-bewaartermijn) in als Gebruik.Ev. */
    static List<Gebruik.Ev> readEvents(Context c, long from, long to) {
        List<Gebruik.Ev> out = new ArrayList<>();
        File f = new File(dir(c), EVENTS);
        if (!f.isFile()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String l;
            while ((l = r.readLine()) != null) {
                int a = l.indexOf('|'); if (a < 0) continue;
                int b = l.indexOf('|', a + 1); if (b < 0) continue;
                long t = Long.parseLong(l.substring(0, a));
                if (t < from || t >= to) continue;
                int type = Integer.parseInt(l.substring(a + 1, b));
                out.add(new Gebruik.Ev(type, l.substring(b + 1), t));
            }
        } catch (Throwable e) { App.log(c, "GEBRUIK", "lezen: " + e); }
        return out;
    }

    static List<Gebruik.Pw> readPw(Context c, long from, long to) {
        List<Gebruik.Pw> out = new ArrayList<>();
        File f = new File(dir(c), PW);
        if (!f.isFile()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String l;
            while ((l = r.readLine()) != null) {
                int a = l.indexOf('|'); if (a < 0) continue;
                long t = Long.parseLong(l.substring(0, a));
                if (t < from || t >= to) continue;
                out.add(new Gebruik.Pw(t, "1".equals(l.substring(a + 1).trim())));
            }
        } catch (Throwable e) { App.log(c, "GEBRUIK", "lezen pw: " + e); }
        return out;
    }

    // ---------- pogingen bij het vergrendelscherm (van GebruikAdmin) ----------

    static synchronized void onPassword(Context c, boolean ok) {
        try {
            long now = System.currentTimeMillis();
            append(new File(dir(c), PW), now + "|" + (ok ? "1" : "0") + "\n");
            int fails = 0;
            if (!ok) { fails = prefs(c).getInt("failRun", 0) + 1; prefs(c).edit().putInt("failRun", fails).apply(); }
            else prefs(c).edit().putInt("failRun", 0).apply();
            // Foto bij een gelukte ontgrendeling, of bij genoeg mislukte pogingen achter elkaar
            boolean want = (ok && photoUnlock(c)) || (!ok && photoFail(c) && fails >= photoAfter(c));
            if (want) GebruikShot.capture(c, ok ? "ontgrendeld" : "mislukt");
        } catch (Throwable e) { App.log(c, "GEBRUIK", "poging: " + e); }
    }

    // ---------- samenvatting per dag (lange bewaartermijn) ----------

    /** Werkt days.json bij: per afgeronde dag de schermtijd per app, aantal ontgrendelingen en keer-oppakken. */
    static synchronized void aggregate(Context c) {
        try {
            long now = System.currentTimeMillis();
            JSONObject days = readJson(new File(dir(c), DAYS));
            long detailFrom = Gebruik.keepSince(now, detailDays(c), tz());
            List<Gebruik.Ev> evs = readEvents(c, detailFrom, now);
            List<Gebruik.Session> sess = Gebruik.sessions(evs);
            // Per dag de sessies verzamelen
            Map<String, List<Gebruik.Session>> byDay = new LinkedHashMap<>();
            for (Gebruik.Session s : sess) {
                String k = Gebruik.dateKey(s.start, tz());
                List<Gebruik.Session> l = byDay.get(k); if (l == null) { l = new ArrayList<>(); byDay.put(k, l); }
                l.add(s);
            }
            List<Gebruik.Unlock> unl = Gebruik.unlocks(evs, readPw(c, detailFrom, now), 30_000);
            Map<String, Integer> unlocksByDay = new HashMap<>(), pickupsByDay = new HashMap<>();
            for (Gebruik.Unlock u : unl) {
                if ("mislukt".equals(u.how)) continue;
                String k = Gebruik.dateKey(u.t, tz());
                Integer n = unlocksByDay.get(k); unlocksByDay.put(k, (n == null ? 0 : n) + 1);
                if (!"anders".equals(u.how)) { } // pincode telt gewoon mee
                Integer p = pickupsByDay.get(k); pickupsByDay.put(k, (p == null ? 0 : p) + 1);
            }
            for (Map.Entry<String, List<Gebruik.Session>> e : byDay.entrySet()) {
                JSONObject d = new JSONObject();
                JSONObject apps = new JSONObject();
                Map<String, Long> tot = Gebruik.totals(e.getValue());
                long total = 0;
                for (Map.Entry<String, Long> a : tot.entrySet()) { apps.put(a.getKey(), a.getValue()); total += a.getValue(); }
                d.put("apps", apps).put("total", total);
                d.put("unlocks", unlocksByDay.containsKey(e.getKey()) ? unlocksByDay.get(e.getKey()) : 0);
                d.put("pickups", pickupsByDay.containsKey(e.getKey()) ? pickupsByDay.get(e.getKey()) : 0);
                days.put(e.getKey(), d);
            }
            writeJson(new File(dir(c), DAYS), days);
        } catch (Throwable e) { App.log(c, "GEBRUIK", "samenvatten: " + e); }
    }

    // ---------- opruimen (bewaartermijn) ----------

    static synchronized void prune(Context c) {
        try {
            long now = System.currentTimeMillis();
            pruneLines(new File(dir(c), EVENTS), Gebruik.keepSince(now, detailDays(c), tz()));
            pruneLines(new File(dir(c), PW), Gebruik.keepSince(now, detailDays(c), tz()));
            pruneLines(new File(dir(c), FUNCS), Gebruik.keepSince(now, detailDays(c), tz()));
            // days.json op de lange bewaartermijn
            JSONObject days = readJson(new File(dir(c), DAYS));
            long keep = keepDays(c);
            JSONObject keptDays = new JSONObject();
            java.util.Iterator<String> it = days.keys();
            while (it.hasNext()) {
                String k = it.next();
                long dayMs = dayKeyToMs(k);
                if (dayMs > 0 && !Gebruik.olderThan(dayMs, now, (int) keep, tz())) keptDays.put(k, days.get(k));
            }
            writeJson(new File(dir(c), DAYS), keptDays);
            // foto's op hun eigen (kortere) bewaartermijn
            long photoFrom = Gebruik.keepSince(now, photoKeepDays(c), tz());
            File pd = photoDir(c);
            File[] fs = pd.listFiles();
            if (fs != null) for (File f : fs) { long t = photoTime(f.getName()); if (t > 0 && t < photoFrom) f.delete(); }
        } catch (Throwable e) { App.log(c, "GEBRUIK", "opruimen: " + e); }
    }

    static void pruneLines(File f, long keepSince) {
        if (!f.isFile()) return;
        try {
            List<String> keep = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String l;
                while ((l = r.readLine()) != null) {
                    int a = l.indexOf('|'); if (a < 0) continue;
                    long t = Long.parseLong(l.substring(0, a));
                    if (t >= keepSince) keep.add(l);
                }
            }
            try (Writer w = new FileWriter(f)) { for (String l : keep) w.write(l + "\n"); }
        } catch (Throwable ignored) { }
    }

    // ---------- interface: overzicht, ontgrendelingen, functies, meldingen ----------

    static JSONObject state(Context c) {
        JSONObject o = new JSONObject();
        try {
            o.put("access", usageAccess(c)).put("admin", adminActive(c));
            o.put("notifOn", NotificationHistory.enabled(c) && NotificationHistory.allowed(c));
            o.put("overlay", Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(c));
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("set", settings(c));
            o.put("photoCaps", GebruikShot.caps(c));
            o.put("photos", countPhotos(c));
            o.put("lastShot", prefs(c).getString("lastShot", ""));
        } catch (Exception ignored) { }
        return o;
    }

    /** period: "today" of "week". Totaal, grafiek (per uur bij vandaag, per dag bij week), top-apps, ontgrendelingen, oppakken. */
    static JSONObject overview(Context c, String period) {
        JSONObject o = new JSONObject();
        try {
            long now = System.currentTimeMillis();
            boolean week = "week".equals(period);
            long from = week ? Gebruik.dayStartAfter(now, -6, tz()) : Gebruik.dayStart(now, tz());
            List<Gebruik.Ev> evs = readEvents(c, from, now);
            List<Gebruik.Session> sess = Gebruik.sessions(evs);
            Map<String, Long> tot = Gebruik.totals(sess);
            Map<String, Integer> cnt = Gebruik.counts(sess);
            long total = 0; for (Long v : tot.values()) total += v;
            o.put("total", total).put("period", period);
            // grafiek
            JSONArray chart = new JSONArray();
            if (week) { long[] d = Gebruik.daily(sess, from, 7, tz()); for (long v : d) chart.put(v); }
            else { long[] h = Gebruik.hourly(sess, from); for (long v : h) chart.put(v); }
            o.put("chart", chart);
            // top apps (met naam en pictogram)
            JSONArray apps = new JSONArray();
            int n = 0;
            for (Map.Entry<String, Long> e : tot.entrySet()) {
                if (n++ >= 12) break;
                apps.put(new JSONObject().put("pkg", e.getKey()).put("name", appLabel(c, e.getKey())).put("icon", appIcon(c, e.getKey()))
                        .put("ms", e.getValue()).put("opens", cnt.containsKey(e.getKey()) ? cnt.get(e.getKey()) : 0));
            }
            o.put("apps", apps);
            List<Gebruik.Unlock> unl = Gebruik.unlocks(evs, readPw(c, from, now), 30_000);
            int unlocks = 0; for (Gebruik.Unlock u : unl) if (!"mislukt".equals(u.how)) unlocks++;
            o.put("unlocks", unlocks).put("pickups", unlocks);
        } catch (Throwable e) { return err(String.valueOf(e.getMessage())); }
        return o;
    }

    static JSONObject unlocksJson(Context c, long from, long to) {
        JSONObject o = new JSONObject();
        try {
            List<Gebruik.Ev> evs = readEvents(c, from, to);
            List<Gebruik.Unlock> unl = Gebruik.unlocks(evs, readPw(c, from, to), 30_000);
            Collections.sort(unl, (a, b) -> Long.compare(b.t, a.t));
            JSONArray arr = new JSONArray();
            int lim = 0;
            for (Gebruik.Unlock u : unl) {
                if (lim++ > 300) break;
                JSONObject b = new JSONObject().put("t", u.t).put("how", u.how).put("fails", u.fails).put("end", u.end);
                JSONArray apps = new JSONArray();
                for (Gebruik.AppUse a : u.apps) apps.put(new JSONObject().put("pkg", a.pkg).put("name", appLabel(c, a.pkg)).put("icon", appIcon(c, a.pkg)).put("start", a.start).put("dur", a.dur));
                b.put("apps", apps);
                String photo = photoNear(c, u.t);
                if (photo != null) b.put("photo", photo);
                arr.put(b);
            }
            o.put("blocks", arr);
        } catch (Throwable e) { return err(String.valueOf(e.getMessage())); }
        return o;
    }

    static JSONObject functionsJson(Context c, long from, long to) {
        JSONObject o = new JSONObject();
        try {
            JSONArray arr = new JSONArray();
            File f = new File(dir(c), FUNCS);
            if (f.isFile()) try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String l; List<String[]> all = new ArrayList<>();
                while ((l = r.readLine()) != null) {
                    String[] p = l.split("\\|", 3); if (p.length < 2) continue;
                    long t = Long.parseLong(p[0]); if (t < from || t >= to) continue;
                    all.add(new String[]{p[0], p[1], p.length > 2 ? p[2] : ""});
                }
                for (int i = all.size() - 1; i >= 0 && arr.length() < 400; i--) {
                    String[] p = all.get(i);
                    JSONObject e = new JSONObject().put("t", Long.parseLong(p[0])).put("kind", p[1]);
                    if (!p[2].isEmpty()) e.put("name", appLabel(c, p[2])).put("pkg", p[2]);
                    arr.put(e);
                }
            }
            o.put("events", arr);
        } catch (Throwable e) { return err(String.valueOf(e.getMessage())); }
        return o;
    }

    /** Meldingen per app in de periode (alleen tellen, nooit de inhoud) uit de bestaande meldingsgeschiedenis, als die aan staat. */
    static JSONObject notifsJson(Context c, long from, long to) {
        JSONObject o = new JSONObject();
        try {
            o.put("notifOn", NotificationHistory.enabled(c) && NotificationHistory.allowed(c));
            JSONArray apps = new JSONArray();
            Map<String, Integer> counts = NotificationHistory.countsByPackage(c, from, to);
            List<Map.Entry<String, Integer>> l = new ArrayList<>(counts.entrySet());
            Collections.sort(l, (a, b) -> Integer.compare(b.getValue(), a.getValue()));
            int total = 0;
            for (Map.Entry<String, Integer> e : l) { total += e.getValue(); if (apps.length() < 30) apps.put(new JSONObject().put("pkg", e.getKey()).put("name", appLabel(c, e.getKey())).put("icon", appIcon(c, e.getKey())).put("n", e.getValue())); }
            o.put("apps", apps).put("total", total);
            // opladen en herstarts uit funcs
            JSONArray charge = new JSONArray(), reboots = new JSONArray();
            File f = new File(dir(c), FUNCS);
            if (f.isFile()) try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] p = line.split("\\|", 3); if (p.length < 2) continue;
                    long t = Long.parseLong(p[0]); if (t < from || t >= to) continue;
                    if (p[1].equals("laden-aan") || p[1].equals("laden-uit")) charge.put(new JSONObject().put("t", t).put("on", p[1].equals("laden-aan")));
                    else if (p[1].equals("herstart")) reboots.put(new JSONObject().put("t", t));
                }
            }
            o.put("charge", charge).put("reboots", reboots);
        } catch (Throwable e) { return err(String.valueOf(e.getMessage())); }
        return o;
    }

    // ---------- foto's ----------

    static int countPhotos(Context c) { File[] fs = photoDir(c).listFiles(); int n = 0; if (fs != null) for (File f : fs) if (f.getName().endsWith(".jpg")) n++; return n; }

    static long photoTime(String name) {
        try { int a = name.indexOf('-'); int b = name.indexOf('-', a + 1); if (a < 0) return 0; String s = b < 0 ? name.substring(a + 1, name.indexOf('.')) : name.substring(a + 1, b); return Long.parseLong(s); }
        catch (Exception e) { return 0; }
    }

    /** Naam van een foto vlak bij tijdstip t (binnen 30 s), of null. */
    static String photoNear(Context c, long t) {
        File[] fs = photoDir(c).listFiles();
        if (fs == null) return null;
        for (File f : fs) { long pt = photoTime(f.getName()); if (pt > 0 && Math.abs(pt - t) < 30_000) return f.getName(); }
        return null;
    }

    static JSONObject photosJson(Context c) {
        JSONObject o = new JSONObject();
        try {
            File[] fs = photoDir(c).listFiles();
            List<File> l = new ArrayList<>();
            if (fs != null) for (File f : fs) if (f.getName().endsWith(".jpg")) l.add(f);
            Collections.sort(l, (a, b) -> Long.compare(photoTime(b.getName()), photoTime(a.getName())));
            JSONArray arr = new JSONArray();
            for (File f : l) {
                String name = f.getName();
                arr.put(new JSONObject().put("id", name).put("t", photoTime(name)).put("reason", name.contains("mislukt") ? "mislukt" : "ontgrendeld"));
            }
            o.put("photos", arr).put("keep", photoKeepDays(c));
        } catch (Exception e) { return err("Foto's lezen lukt niet"); }
        return o;
    }

    /** Eén foto als data-URL (base64), of "". */
    static String photo(Context c, String id) {
        try {
            if (id == null || id.contains("/") || !id.endsWith(".jpg")) return "";
            File f = new File(photoDir(c), id);
            if (!f.isFile()) return "";
            byte[] b = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) { int r = 0, o; while (r < b.length && (o = in.read(b, r, b.length - r)) > 0) r += o; }
            return "data:image/jpeg;base64," + AppData.b64(b);
        } catch (Throwable e) { return ""; }
    }

    static void clearPhotos(Context c) { File[] fs = photoDir(c).listFiles(); if (fs != null) for (File f : fs) f.delete(); }

    static void clearAll(Context c) {
        try {
            File d = dir(c);
            deleteTree(d);
            prefs(c).edit().remove("watermark").remove("lastCollect").remove("failRun").remove("lastShot").apply();
        } catch (Throwable e) { App.log(c, "GEBRUIK", "wissen: " + e); }
    }

    static void deleteTree(File f) { if (f == null) return; if (f.isDirectory()) { File[] fs = f.listFiles(); if (fs != null) for (File x : fs) deleteTree(x); } f.delete(); }

    // ---------- namen en pictogrammen van apps ----------

    private static final Map<String, String> LABELS = new HashMap<>();
    private static final Map<String, String> ICONS = new HashMap<>();

    static String appLabel(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        String v = LABELS.get(pkg); if (v != null) return v;
        String label = pkg;
        try { PackageManager pm = c.getPackageManager(); label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); }
        catch (Throwable ignored) { }
        LABELS.put(pkg, label);
        return label;
    }

    /** Pictogram als kleine PNG data-URL (gecachet). "" als het niet lukt. */
    static String appIcon(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        String v = ICONS.get(pkg); if (v != null) return v;
        String out = "";
        try {
            PackageManager pm = c.getPackageManager();
            Drawable d = pm.getApplicationIcon(pkg);
            int size = 72;
            Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(bm);
            d.setBounds(0, 0, size, size);
            d.draw(cv);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            bm.compress(Bitmap.CompressFormat.PNG, 90, bos);
            bm.recycle();
            out = "data:image/png;base64," + AppData.b64(bos.toByteArray());
        } catch (Throwable ignored) { }
        ICONS.put(pkg, out);
        return out;
    }

    // ---------- telefoonfuncties en opladen: momentopname en live ----------

    /** Momentopname van opladen en verbindingen bij elke overname; wissels worden als gebeurtenis bewaard. */
    static void snapshotFuncs(Context c) {
        try {
            long now = System.currentTimeMillis();
            SharedPreferences p = prefs(c);
            // Opladen
            android.content.IntentFilter f = new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED);
            android.content.Intent bat = c.registerReceiver(null, f);
            boolean charging = false;
            if (bat != null) { int st = bat.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1); charging = st == android.os.BatteryManager.BATTERY_STATUS_CHARGING || st == android.os.BatteryManager.BATTERY_STATUS_FULL; }
            boolean was = p.getBoolean("charging", false);
            if (charging != was) { append(new File(dir(c), FUNCS), now + "|" + (charging ? "laden-aan" : "laden-uit") + "|\n"); p.edit().putBoolean("charging", charging).apply(); }
        } catch (Throwable e) { App.log(c, "GEBRUIK", "momentopname: " + e); }
    }

    // ---------- json-bestandjes ----------

    static JSONObject readJson(File f) { try { if (!f.isFile()) return new JSONObject(); return new JSONObject(readAll(f)); } catch (Throwable e) { return new JSONObject(); } }
    static void writeJson(File f, JSONObject o) { try (Writer w = new FileWriter(f)) { w.write(o.toString()); } catch (Throwable ignored) { } }

    static String readAll(File f) throws Exception {
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) { String l; while ((l = r.readLine()) != null) b.append(l); }
        return b.toString();
    }

    static synchronized void append(File f, String s) { try (Writer w = new FileWriter(f, true)) { w.write(s); } catch (Throwable ignored) { } }

    static long dayKeyToMs(String key) {
        try { String[] p = key.split("-"); java.util.Calendar k = java.util.Calendar.getInstance(tz()); k.clear(); k.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2])); return k.getTimeInMillis(); }
        catch (Exception e) { return 0; }
    }
}
