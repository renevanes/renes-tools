package nl.rene.tools;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Mijn auto: waar staat hij (bij het uitzetten van de auto via Bluetooth de plek bewaren), een parkeerlogboek,
 * en rittenregistratie (Bluetooth van de auto aan = route opnemen, uit = opslaan als rit met kilometers,
 * zakelijk of privé, per maand te exporteren).
 */
final class Car {

    private Car() { }

    static final String CHANNEL = "car";
    static final int MAX_LOG = 300, MAX_TRIPS = 2000;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("car", Context.MODE_PRIVATE); }

    static String addr(Context c) { return prefs(c).getString("addr", ""); }

    static JSONArray arr(Context c, String key) {
        try { return new JSONArray(prefs(c).getString(key, "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    static void setCar(Context c, String addr, String name, boolean trips, String defType) {
        prefs(c).edit().putString("addr", addr == null ? "" : addr).putString("name", name == null ? "" : name)
                .putBoolean("trips", trips).putString("defType", "zakelijk".equals(defType) ? "zakelijk" : "prive").apply();
    }

    /** Bluetooth van een apparaat ging aan of uit. done wordt altijd precies één keer aangeroepen. */
    static void onBluetooth(Context c, boolean connected, String a, Runnable done) {
        String car = addr(c);
        if (car.isEmpty() || a == null || !car.equalsIgnoreCase(a)) { done.run(); return; }
        final Context app = c.getApplicationContext();
        if (connected) {
            try {
                closePark(app);
                if (prefs(app).getBoolean("trips", false)) startTrip(app);
            } finally { done.run(); }
            return;
        }
        if (prefs(app).getLong("tripStart", 0) > 0) stopTrip(app);
        Auto.locate(app, 25_000, loc -> {
            try { park(app, loc, "auto"); } finally { done.run(); }
        });
    }

    /** Parkeerplek bewaren (ook met de hand: "Hier staat mijn auto"). */
    static void park(Context c, Location loc, String how) {
        try {
            JSONObject p = new JSONObject().put("t", System.currentTimeMillis()).put("how", how);
            if (loc != null) p.put("lat", loc.getLatitude()).put("lon", loc.getLongitude()).put("acc", loc.hasAccuracy() ? Math.round(loc.getAccuracy()) : 0);
            JSONArray log = arr(c, "log"), out = new JSONArray().put(p);
            for (int i = 0; i < log.length() && out.length() < MAX_LOG; i++) out.put(log.get(i));
            prefs(c).edit().putString("park", p.toString()).putString("log", out.toString()).apply();
            HomeWidgets.refresh(c, HomeWidgets.Park.class);
        } catch (Exception ignored) { }
    }

    /** Weer weggereden: eindtijd in het logboek. */
    static void closePark(Context c) {
        try {
            JSONArray log = arr(c, "log");
            JSONObject last = log.length() > 0 ? log.getJSONObject(0) : null;
            if (last != null && !last.has("end")) { last.put("end", System.currentTimeMillis()); log.put(0, last); }
            prefs(c).edit().putString("log", log.toString()).remove("park").apply();
            HomeWidgets.refresh(c, HomeWidgets.Park.class);
        } catch (Exception ignored) { }
    }

    // ---------- ritten ----------

    static void startTrip(Context c) {
        // Zonder "locatie altijd" weigert Android een route-opname vanaf de achtergrond: dan vragen
        if (!Auto.bgLocOk(c) && !MainActivity.visible) { notifyTap(c); return; }
        Intent i = new Intent(c, TracksService.class).setAction(TracksService.ACTION_START).putExtra("trip", true);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
            prefs(c).edit().putLong("tripStart", System.currentTimeMillis()).apply();
        } catch (Exception e) {
            // Android staat starten vanaf de achtergrond niet toe (geen batterij-uitzondering): vragen met een melding
            notifyTap(c);
        }
    }

    static void stopTrip(Context c) {
        if (!TracksService.running || !Tracks.prefs(c).getBoolean("trip", false)) { prefs(c).edit().remove("tripStart").apply(); return; }
        try { c.startService(new Intent(c, TracksService.class).setAction(TracksService.ACTION_STOP)); } catch (Exception ignored) { }
    }

    /** Door TracksService aangeroepen als een rit stopt: route bewaren en de rit toevoegen. */
    static void tripDone(Context c, long startT, Tracks.Stats s) {
        prefs(c).edit().remove("tripStart").apply();
        if (s == null || s.points < 2 || s.distance < 200) { Tracks.liveFile(c).delete(); return; } // even de auto aangezet, niet gereden
        try {
            String title = "Rit " + new SimpleDateFormat("d MMM HH:mm", new Locale("nl", "NL")).format(new Date(startT));
            File route = Tracks.finalize(c, startT, title);
            JSONObject trip = new JSONObject().put("id", Long.toString(startT, 36)).put("start", startT)
                    .put("end", s.endT > 0 ? s.endT : System.currentTimeMillis()).put("m", Math.round(s.distance))
                    .put("type", prefs(c).getString("defType", "prive")).put("note", "").put("route", route == null ? "" : route.getName());
            JSONArray l = arr(c, "trips"), out = new JSONArray().put(trip);
            for (int i = 0; i < l.length() && out.length() < MAX_TRIPS; i++) out.put(l.get(i));
            prefs(c).edit().putString("trips", out.toString()).apply();
        } catch (Exception e) { App.log(c, "CAR", "rit opslaan: " + e.getMessage()); }
    }

    static String setTrip(Context c, String id, String type, String note) {
        try {
            JSONArray l = arr(c, "trips");
            for (int i = 0; i < l.length(); i++) {
                JSONObject t = l.getJSONObject(i);
                if (!t.optString("id").equals(id)) continue;
                if (type != null) t.put("type", "zakelijk".equals(type) ? "zakelijk" : "prive");
                if (note != null) t.put("note", note.length() > 200 ? note.substring(0, 200) : note);
                l.put(i, t);
            }
            prefs(c).edit().putString("trips", l.toString()).apply();
            return "";
        } catch (Exception e) { return "Opslaan lukt niet"; }
    }

    static String deleteTrip(Context c, String id) {
        JSONArray l = arr(c, "trips"), out = new JSONArray();
        for (int i = 0; i < l.length(); i++) { JSONObject t = l.optJSONObject(i); if (t != null && !t.optString("id").equals(id)) out.put(t); }
        prefs(c).edit().putString("trips", out.toString()).apply();
        return "";
    }

    /** Ritten van één maand ("2026-10") als CSV in Ritten/ in de backup-map. Geeft het aantal ritten. */
    static int exportMonth(Context c, String month) throws Exception {
        android.net.Uri tree = WaBackup.destUri(c);
        if (tree == null) throw new Exception("Kies eerst een backup-map");
        SimpleDateFormat ym = new SimpleDateFormat("yyyy-MM", Locale.US), d = new SimpleDateFormat("dd-MM-yyyy", Locale.US), hm = new SimpleDateFormat("HH:mm", Locale.US);
        JSONArray l = arr(c, "trips");
        StringBuilder b = new StringBuilder("﻿Datum;Vertrek;Aankomst;Kilometers;Soort;Omschrijving\r\n");
        int n = 0; double zak = 0, pri = 0;
        for (int i = l.length() - 1; i >= 0; i--) {
            JSONObject t = l.getJSONObject(i);
            Date s = new Date(t.optLong("start"));
            if (!ym.format(s).equals(month)) continue;
            double km = t.optLong("m") / 1000.0;
            if ("zakelijk".equals(t.optString("type"))) zak += km; else pri += km;
            b.append(d.format(s)).append(';').append(hm.format(s)).append(';').append(hm.format(new Date(t.optLong("end")))).append(';')
                    .append(String.format(Locale.GERMANY, "%.1f", km)).append(';').append("zakelijk".equals(t.optString("type")) ? "Zakelijk" : "Privé").append(';')
                    .append(t.optString("note").replace(';', ',').replace('\n', ' ')).append("\r\n");
            n++;
        }
        if (n == 0) throw new Exception("Geen ritten in deze maand");
        b.append("\r\nTotaal zakelijk;;;").append(String.format(Locale.GERMANY, "%.1f", zak)).append("\r\nTotaal privé;;;").append(String.format(Locale.GERMANY, "%.1f", pri)).append("\r\n");
        WaBackup.Dest dest = new WaBackup.Dest(c.getContentResolver(), tree);
        WaBackup.DestDir dir = dest.dir("Ritten", true);
        try (Writer w = Sms.open(dest, dir, "ritten-" + month + ".csv", "text/csv")) { w.write(b.toString()); }
        return n;
    }

    static String stateJson(Context c) {
        try {
            SharedPreferences p = prefs(c);
            JSONObject o = new JSONObject().put("addr", p.getString("addr", "")).put("name", p.getString("name", ""))
                    .put("trips", p.getBoolean("trips", false)).put("defType", p.getString("defType", "prive"))
                    .put("tripActive", TracksService.running && Tracks.prefs(c).getBoolean("trip", false))
                    .put("log", arr(c, "log")).put("tripList", arr(c, "trips"))
                    .put("locOk", Auto.locOk(c)).put("bgLoc", Auto.bgLocOk(c)).put("btOk", Auto.btOk(c))
                    .put("battery", ((android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE)).isIgnoringBatteryOptimizations(c.getPackageName()))
                    .put("dest", WaBackup.destUri(c) != null);
            String park = p.getString("park", null);
            if (park != null) o.put("park", new JSONObject(park));
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    static void notifyTap(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Mijn auto", NotificationManager.IMPORTANCE_DEFAULT));
        }
        Intent open = new Intent(c, MainActivity.class).putExtra("open", "car").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, NotifCenter.ch(c, CHANNEL)) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_notif).setContentTitle("Rit opnemen?").setContentText("Android liet het niet vanzelf starten. Tik om de rit op te nemen.")
                .setAutoCancel(true).setContentIntent(PendingIntent.getActivity(c, 4801, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        NotifCenter.post(c, 4801, b.build());
    }
}
