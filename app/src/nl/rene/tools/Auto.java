package nl.rene.tools;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Set;

/**
 * Automatiseringen: "als dit gebeurt, doe dan dat", zoals MacroDroid.
 * Triggers: Bluetooth (auto) verbroken/verbonden, aankomen op of weggaan van een plek.
 * Voorwaarden: plek, dagen, tijdvenster. Acties: melding, app openen en knoppen indrukken (AutoA11y).
 * Regels staan als JSON in de voorkeuren "auto" (zie {@link #save}).
 */
final class Auto {

    private Auto() { }

    static final String CHANNEL = "auto";
    static final String ACTION_PROX = "nl.rene.tools.AUTO_PROX";
    static final String ACTION_DISMISS = "nl.rene.tools.AUTO_DISMISS";
    static final String[] PARKING_APPS = {"net.easypark.android", "com.parkmobile", "nl.parkmobile", "com.yellowbrick.app", "nl.yellowbrick", "com.parkline"};

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("auto", Context.MODE_PRIVATE); }

    static JSONArray rules(Context c) {
        try { return new JSONArray(prefs(c).getString("rules", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    static JSONObject rule(Context c, String id) {
        JSONArray a = rules(c);
        for (int i = 0; i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o != null && id != null && id.equals(o.optString("id"))) return o; }
        return null;
    }

    /**
     * Regel opslaan (nieuw of bestaand). Velden: id, name, on, trig (bt_off|bt_on|arrive|leave|manual), bt (adressen),
     * btNames, usePlace, place {name,lat,lng,r}, days, from, to, mode (ask|auto|notify), app {p,n}, steps, msg, cool.
     * Geeft "" of een foutmelding.
     */
    static String save(Context c, String json) {
        try {
            JSONObject o = new JSONObject(json);
            String trig = o.optString("trig");
            if (o.optString("name").trim().isEmpty()) return "Geef de automatisering een naam";
            boolean bt = trig.startsWith("bt_"), geo = "arrive".equals(trig) || "leave".equals(trig);
            if (!bt && !geo && !"manual".equals(trig)) return "Kies wanneer het moet gebeuren";
            if (bt && (o.optJSONArray("bt") == null || o.optJSONArray("bt").length() == 0)) return "Kies het Bluetooth-apparaat van je auto";
            JSONObject p = o.optJSONObject("place");
            boolean needPlace = geo || o.optBoolean("usePlace");
            if (needPlace && (p == null || !p.has("lat") || !p.has("lng"))) return "Kies de plek";
            if (p != null) p.put("r", Math.max(50, Math.min(5000, p.optInt("r", 200))));
            String mode = o.optString("mode", "ask");
            JSONArray steps = o.optJSONArray("steps");
            boolean hasApp = o.optJSONObject("app") != null && !o.optJSONObject("app").optString("p").isEmpty();
            if (!"notify".equals(mode) && !hasApp) return "Kies welke app geopend moet worden";
            if ("auto".equals(mode) && (steps == null || steps.length() == 0)) return "Volledig automatisch heeft minstens één knop nodig (neem ze op)";
            if (!o.has("cool")) o.put("cool", 15);
            if (o.optString("id").isEmpty()) o.put("id", Long.toString(System.currentTimeMillis(), 36));
            JSONArray a = rules(c), out = new JSONArray();
            boolean replaced = false;
            for (int i = 0; i < a.length(); i++) {
                JSONObject x = a.optJSONObject(i);
                if (x == null) continue;
                if (x.optString("id").equals(o.optString("id"))) { out.put(o); replaced = true; } else out.put(x);
            }
            if (!replaced) out.put(o);
            prefs(c).edit().putString("rules", out.toString()).apply();
            armPlaces(c);
            return "";
        } catch (Exception e) { return "Opslaan mislukt: " + e.getMessage(); }
    }

    static void delete(Context c, String id) {
        JSONArray a = rules(c), out = new JSONArray();
        for (int i = 0; i < a.length(); i++) { JSONObject x = a.optJSONObject(i); if (x != null && !x.optString("id").equals(id)) out.put(x); }
        prefs(c).edit().putString("rules", out.toString()).remove("last_" + id).remove("in_" + id).apply();
        armPlaces(c);
    }

    static void setOn(Context c, String id, boolean on) {
        JSONObject r = rule(c, id);
        if (r == null) return;
        try { r.put("on", on); save(c, r.toString()); } catch (Exception ignored) { }
    }

    // ---------- toestemmingen en status ----------

    static boolean granted(Context c, String p) { return c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    static boolean locOk(Context c) { return granted(c, Manifest.permission.ACCESS_FINE_LOCATION) || granted(c, Manifest.permission.ACCESS_COARSE_LOCATION); }
    static boolean bgLocOk(Context c) { return Build.VERSION.SDK_INT < 29 || granted(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION); }
    static boolean btOk(Context c) { return Build.VERSION.SDK_INT < 31 || granted(c, Manifest.permission.BLUETOOTH_CONNECT); }
    static boolean notifOk(Context c) { return Build.VERSION.SDK_INT < 33 || granted(c, Manifest.permission.POST_NOTIFICATIONS); }

    static String stateJson(Context c) {
        try {
            JSONObject o = new JSONObject();
            o.put("rules", rules(c));
            JSONObject last = new JSONObject();
            for (String k : prefs(c).getAll().keySet()) if (k.startsWith("last_")) last.put(k.substring(5), prefs(c).getLong(k, 0));
            o.put("last", last);
            o.put("log", new JSONArray(prefs(c).getString("log", "[]")));
            JSONObject p = new JSONObject();
            p.put("a11y", AutoA11y.enabled(c)).put("a11yRunning", AutoA11y.ready())
                    .put("loc", locOk(c)).put("bgloc", bgLocOk(c)).put("bt", btOk(c)).put("notif", notifOk(c))
                    .put("battery", SelfTest.battery(c).optString("status").equals("ok"))
                    .put("sdk", Build.VERSION.SDK_INT);
            o.put("perms", p);
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    /** Gekoppelde Bluetooth-apparaten: [{a: adres, n: naam, car: lijkt een auto}]. */
    static String btDevices(Context c) {
        JSONArray a = new JSONArray();
        try {
            if (!btOk(c)) return "{\"error\":\"perm\"}";
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad == null) return "{\"error\":\"Deze telefoon heeft geen Bluetooth\"}";
            Set<BluetoothDevice> s = ad.getBondedDevices();
            if (s != null) for (BluetoothDevice d : s) {
                String n = d.getName();
                int cls = 0;
                try { cls = d.getBluetoothClass() == null ? 0 : d.getBluetoothClass().getMajorDeviceClass(); } catch (Exception ignored) { }
                boolean car = cls == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO
                        && d.getBluetoothClass().getDeviceClass() == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO;
                if (!car && n != null) {
                    String l = n.toLowerCase();
                    car = l.contains("car") || l.contains("auto") || l.contains("vw") || l.contains("toyota") || l.contains("bmw") || l.contains("mercedes")
                            || l.contains("audi") || l.contains("ford") || l.contains("kia") || l.contains("hyundai") || l.contains("skoda")
                            || l.contains("peugeot") || l.contains("renault") || l.contains("volvo") || l.contains("tesla") || l.contains("mazda");
                }
                a.put(new JSONObject().put("a", d.getAddress()).put("n", n == null ? d.getAddress() : n).put("car", car));
            }
            return new JSONObject().put("devices", a).toString();
        } catch (SecurityException e) { return "{\"error\":\"perm\"}"; }
        catch (Exception e) { return "{\"error\":\"Bluetooth lezen lukt niet\"}"; }
    }

    /** Apps met een pictogram, parkeerapps eerst: [{p, n, park}]. */
    static String apps(Context c) {
        try {
            JSONArray src = new JSONArray(Launcher.appsJson(c)), park = new JSONArray(), rest = new JSONArray();
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            java.util.ArrayList<JSONObject> l = new java.util.ArrayList<>();
            for (int i = 0; i < src.length(); i++) l.add(src.getJSONObject(i));
            java.util.Collections.sort(l, (x, y) -> x.optString("n").compareToIgnoreCase(y.optString("n")));
            for (JSONObject x : l) {
                String p = x.optString("p");
                if (p.equals(c.getPackageName()) || !seen.add(p)) continue;
                JSONObject o = new JSONObject().put("p", p).put("n", x.optString("n"));
                if (isParkingApp(p, x.optString("n"))) park.put(o.put("park", true)); else rest.put(o);
            }
            for (int i = 0; i < rest.length(); i++) park.put(rest.get(i));
            return park.toString();
        } catch (Exception e) { return "[]"; }
    }

    static boolean isParkingApp(String pkg, String label) {
        for (String p : PARKING_APPS) if (p.equals(pkg)) return true;
        String l = label == null ? "" : label.toLowerCase();
        return l.contains("easypark") || l.contains("parkmobile") || l.contains("yellowbrick") || l.contains("parkline") || pkg.contains("easypark");
    }

    // ---------- gebeurtenissen ----------

    /** Bluetooth verbonden/verbroken met dit apparaat. pr mag null zijn (wordt anders afgesloten als alles klaar is). */
    static void onBluetooth(Context c, boolean connected, String addr, android.content.BroadcastReceiver.PendingResult pr) {
        String trig = connected ? "bt_on" : "bt_off";
        JSONArray a = rules(c);
        java.util.ArrayList<JSONObject> hits = new java.util.ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.optJSONObject(i);
            if (r == null || !r.optBoolean("on", true) || !trig.equals(r.optString("trig"))) continue;
            JSONArray bt = r.optJSONArray("bt");
            boolean match = false;
            if (bt != null) for (int k = 0; k < bt.length(); k++) if (bt.optString(k).equalsIgnoreCase(addr)) match = true;
            if (match && timeOk(r)) hits.add(r);
        }
        if (hits.isEmpty()) { if (pr != null) pr.finish(); return; }
        boolean needLoc = false;
        for (JSONObject r : hits) if (r.optBoolean("usePlace") && r.optJSONObject("place") != null) needLoc = true;
        if (!needLoc) {
            for (JSONObject r : hits) fire(c, r, connected ? "Auto verbonden" : "Auto uitgezet");
            if (pr != null) pr.finish();
            return;
        }
        final Context app = c.getApplicationContext();
        locate(app, 25_000, loc -> {
            for (JSONObject r : hits) {
                JSONObject p = r.optJSONObject("place");
                if (!r.optBoolean("usePlace") || p == null) { fire(app, r, connected ? "Auto verbonden" : "Auto uitgezet"); continue; }
                if (loc == null) { log(app, r, "Locatie onbekend, overgeslagen"); continue; }
                if (AutoLogic.inPlace(loc.getLatitude(), loc.getLongitude(), loc.hasAccuracy() ? loc.getAccuracy() : 50,
                        p.optDouble("lat"), p.optDouble("lng"), p.optInt("r", 200)))
                    fire(app, r, (connected ? "Auto verbonden" : "Auto uitgezet") + " bij " + p.optString("name", "de plek"));
                else log(app, r, "Niet bij " + p.optString("name", "de plek") + ", niets gedaan");
            }
            if (pr != null) pr.finish();
        });
    }

    /** Aankomen op / weggaan van een plek (LocationManager.addProximityAlert). */
    static void onProximity(Context c, String id, boolean entering) {
        JSONObject r = rule(c, id);
        if (r == null || !r.optBoolean("on", true)) return;
        SharedPreferences sp = prefs(c);
        boolean wasIn = sp.getBoolean("in_" + id, false);
        sp.edit().putBoolean("in_" + id, entering).apply();
        // Vlak na het (opnieuw) instellen meldt Android vaak "binnen" omdat je er al bent: dat is geen aankomst.
        if (System.currentTimeMillis() - sp.getLong("armedAt", 0) < 120_000L) return;
        if (entering == wasIn) return;
        String trig = r.optString("trig");
        if (entering && !"arrive".equals(trig)) return;
        if (!entering && !"leave".equals(trig)) return;
        if (!timeOk(r)) return;
        JSONObject p = r.optJSONObject("place");
        String where = p == null ? "de plek" : p.optString("name", "de plek");
        fire(c, r, (entering ? "Aangekomen bij " : "Weg van ") + where);
    }

    static boolean timeOk(JSONObject r) {
        Calendar cal = Calendar.getInstance();
        return AutoLogic.dayOk(r.optInt("days", 0), cal.get(Calendar.DAY_OF_WEEK))
                && AutoLogic.timeOk(r.optString("from"), r.optString("to"), cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE));
    }

    // ---------- uitvoeren ----------

    /** Voert de acties van de regel uit (met wachttijd tegen dubbel starten). */
    static void fire(Context c, JSONObject r, String why) {
        String id = r.optString("id");
        long now = System.currentTimeMillis();
        if (!AutoLogic.cooldownOk(prefs(c).getLong("last_" + id, 0), now, r.optInt("cool", 15))) {
            log(c, r, why + " (net al gedaan, overgeslagen)");
            return;
        }
        prefs(c).edit().putLong("last_" + id, now).apply();
        run(c, r, why, false);
    }

    /** Uitvoeren; test = via "Nu testen" in de app. */
    static void run(Context c, JSONObject r, String why, boolean test) {
        String mode = r.optString("mode", "ask");
        if ("auto".equals(mode)) {
            if (AutoA11y.ready()) {
                log(c, r, why + ": automatisch uitvoeren");
                AutoA11y.run(c, r);
                return;
            }
            log(c, r, why + ": toegankelijkheid staat uit, gevraagd met een melding");
            ask(c, r, why, "Automatisch tikken staat uit (toegankelijkheid). Tik op Starten.");
            return;
        }
        if ("notify".equals(mode)) { log(c, r, why + ": melding"); notifyOnly(c, r, why); return; }
        if (test) { log(c, r, "Test: gevraagd met een melding"); }
        else log(c, r, why + ": gevraagd met een melding");
        ask(c, r, why, null);
    }

    static void channel(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Automatiseringen", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Vragen en meldingen van je automatiseringen, zoals parkeren starten");
            ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    static int notifId(String id) { return 9100 + (id.hashCode() & 0x7ff); }

    static PendingIntent runIntent(Context c, String id, boolean doRun, int code) {
        Intent i = new Intent(c, AutoRunActivity.class).putExtra("id", id).putExtra("run", doRun)
                .setData(Uri.fromParts("auto", id + (doRun ? "/run" : "/open"), null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_HISTORY);
        return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static Notification.Builder builder(Context c) {
        channel(c);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_notif).setAutoCancel(true).setCategory(Notification.CATEGORY_REMINDER);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);
        return b;
    }

    /** Melding met de knop "Starten" (één tik voert de actie uit). */
    static void ask(Context c, JSONObject r, String why, String extra) {
        String id = r.optString("id");
        JSONObject app = r.optJSONObject("app");
        String appName = app == null ? "" : app.optString("n");
        String text = r.optString("msg").trim();
        if (text.isEmpty()) text = why + (appName.isEmpty() ? "" : ". Tik op Starten om " + appName + " te openen.");
        if (extra != null) text = extra;
        PendingIntent go = runIntent(c, id, true, notifId(id) * 2);
        Intent no = new Intent(c, AutoReceiver.Priv.class).setAction(ACTION_DISMISS).putExtra("nid", notifId(id));
        PendingIntent dismiss = PendingIntent.getBroadcast(c, notifId(id) * 2 + 1, no, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = builder(c).setContentTitle(r.optString("name"))
                .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(go)
                .addAction(new Notification.Action.Builder(null, "Starten", go).build())
                .addAction(new Notification.Action.Builder(null, "Niet nu", dismiss).build());
        if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(60 * 60_000L);
        post(c, notifId(id), b.build());
    }

    static void notifyOnly(Context c, JSONObject r, String why) {
        String id = r.optString("id");
        String text = r.optString("msg").trim();
        if (text.isEmpty()) text = why;
        JSONObject app = r.optJSONObject("app");
        Notification.Builder b = builder(c).setContentTitle(r.optString("name")).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text));
        if (app != null && !app.optString("p").isEmpty()) {
            PendingIntent open = runIntent(c, id, false, notifId(id) * 2);
            b.setContentIntent(open).addAction(new Notification.Action.Builder(null, app.optString("n", "Openen"), open).build());
        }
        if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(2 * 60 * 60_000L);
        post(c, notifId(id), b.build());
    }

    /** Uitkomst van automatisch tikken (AutoA11y). */
    static void result(Context c, JSONObject r, boolean ok, String msg) {
        log(c, r, msg);
        Notification.Builder b = builder(c).setContentTitle(r.optString("name") + (ok ? " ✓" : ": niet gelukt"))
                .setContentText(msg).setStyle(new Notification.BigTextStyle().bigText(msg));
        if (!ok) b.setContentIntent(runIntent(c, r.optString("id"), false, notifId(r.optString("id")) * 2));
        if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(ok ? 10 * 60_000L : 2 * 60 * 60_000L);
        post(c, notifId(r.optString("id")), b.build());
    }

    static void post(Context c, int nid, Notification n) {
        if (!notifOk(c)) return;
        try { ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(nid, n); } catch (Exception ignored) { }
    }

    static void cancel(Context c, int nid) {
        try { ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(nid); } catch (Exception ignored) { }
    }

    /** Opent de app van de regel (vanuit een tik op een melding mag dat altijd). */
    static boolean openApp(Context c, JSONObject r) {
        JSONObject app = r.optJSONObject("app");
        if (app == null) return false;
        Intent li = c.getPackageManager().getLaunchIntentForPackage(app.optString("p"));
        if (li == null) return false;
        li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { c.startActivity(li); return true; } catch (Exception e) { return false; }
    }

    static synchronized void log(Context c, JSONObject r, String what) {
        try {
            JSONArray a = new JSONArray(prefs(c).getString("log", "[]")), out = new JSONArray();
            out.put(new JSONObject().put("t", System.currentTimeMillis()).put("id", r == null ? "" : r.optString("id"))
                    .put("name", r == null ? "" : r.optString("name")).put("what", what));
            for (int i = 0; i < a.length() && out.length() < 40; i++) out.put(a.get(i));
            prefs(c).edit().putString("log", out.toString()).apply();
        } catch (Exception ignored) { }
    }

    // ---------- plekken (proximity alerts) ----------

    static PendingIntent proxIntent(Context c, String id) {
        Intent i = new Intent(c, AutoReceiver.Priv.class).setAction(ACTION_PROX).putExtra("id", id)
                .setData(Uri.fromParts("autoprox", id, null));
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        return PendingIntent.getBroadcast(c, 9500 + (id.hashCode() & 0x3ff), i, flags);
    }

    /** Stelt de plekken van alle aan-staande "aankomen/weggaan"-regels opnieuw in (na opslaan, starten app en herstart telefoon). */
    static void armPlaces(Context c) {
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        SharedPreferences sp = prefs(c);
        for (String id : sp.getString("armed", "").split(",")) {
            if (id.isEmpty()) continue;
            try { lm.removeProximityAlert(proxIntent(c, id)); } catch (Exception ignored) { }
        }
        StringBuilder armed = new StringBuilder();
        if (locOk(c)) {
            JSONArray a = rules(c);
            for (int i = 0; i < a.length(); i++) {
                JSONObject r = a.optJSONObject(i);
                if (r == null || !r.optBoolean("on", true)) continue;
                String t = r.optString("trig");
                JSONObject p = r.optJSONObject("place");
                if (p == null || !("arrive".equals(t) || "leave".equals(t))) continue;
                try {
                    lm.addProximityAlert(p.optDouble("lat"), p.optDouble("lng"), p.optInt("r", 200), -1, proxIntent(c, r.optString("id")));
                    if (armed.length() > 0) armed.append(',');
                    armed.append(r.optString("id"));
                } catch (Exception ignored) { }
            }
        }
        sp.edit().putString("armed", armed.toString()).putLong("armedAt", System.currentTimeMillis()).apply();
    }

    // ---------- huidige locatie ----------

    interface LocCb { void done(Location l); }

    /** Eén goede locatiemeting: een recente bekende als die er is, anders kort meten (max. timeoutMs). */
    static void locate(Context c, long timeoutMs, LocCb cb) {
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null || !locOk(c)) { cb.done(null); return; }
        Location best = null;
        try {
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l == null) continue;
                long age = System.currentTimeMillis() - l.getTime();
                if (age > 120_000L) continue;
                if (best == null || (l.hasAccuracy() && l.getAccuracy() < (best.hasAccuracy() ? best.getAccuracy() : 9999))) best = l;
            }
        } catch (SecurityException ignored) { }
        if (best != null && best.hasAccuracy() && best.getAccuracy() <= 60) { cb.done(best); return; }
        Listener li = new Listener(lm, cb, best);
        Handler h = new Handler(Looper.getMainLooper());
        h.post(() -> {
            try {
                for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused"}) {
                    if (Build.VERSION.SDK_INT < 31 && "fused".equals(p)) continue;
                    try { if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, 1000, 0, li, Looper.getMainLooper()); } catch (Exception ignored) { }
                }
            } catch (Exception ignored) { }
            h.postDelayed(li::finish, timeoutMs);
        });
    }

    /** Neemt de eerste nauwkeurige meting (≤ 50 m), of de beste na de wachttijd. */
    static final class Listener implements LocationListener {
        final LocationManager lm; final LocCb cb; Location best; boolean done;
        Listener(LocationManager lm, LocCb cb, Location start) { this.lm = lm; this.cb = cb; this.best = start; }
        @Override public void onLocationChanged(Location l) {
            if (done || l == null) return;
            if (best == null || !best.hasAccuracy() || (l.hasAccuracy() && l.getAccuracy() <= best.getAccuracy()) || l.getTime() - best.getTime() > 60_000L) best = l;
            if (l.hasAccuracy() && l.getAccuracy() <= 50) finish();
        }
        void finish() {
            if (done) return;
            done = true;
            try { lm.removeUpdates(this); } catch (Exception ignored) { }
            cb.done(best);
        }
        @Override public void onStatusChanged(String p, int s, Bundle e) { }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) { }
    }
}
