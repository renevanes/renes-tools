package nl.rene.tools;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Widgets van Rene's Tools voor elk startscherm (Oppo of de skin): Agenda, Weer, Overzicht en Parkeren.
 * Ze verversen zichzelf elk half uur (Android-minimum) en direct als er in de app iets verandert.
 * Het werk gebeurt op een achtergrondthread (goAsync): het weer komt van internet.
 */
public final class HomeWidgets {

    private HomeWidgets() { }

    static final String REFRESH = "nl.rene.tools.WIDGET_REFRESH", PARK_DONE = "nl.rene.tools.PARK_DONE";
    static final int[] ROWS = {R.id.w_r0, R.id.w_r1, R.id.w_r2, R.id.w_r3};
    static final Locale NL = new Locale("nl", "NL");
    static final int IMM = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;

    static final java.util.concurrent.ExecutorService WORKER = java.util.concurrent.Executors.newSingleThreadExecutor();

    /** Basis: alle widgets van één soort bijwerken, op de achtergrond. */
    public abstract static class Base extends AppWidgetProvider {
        abstract RemoteViews build(Context c, boolean force);

        @Override
        public void onUpdate(Context c, AppWidgetManager m, int[] ids) { refreshAsync(c, false); }

        @Override
        public void onReceive(Context c, Intent i) {
            if (REFRESH.equals(i.getAction())) { refreshAsync(c, true); return; }
            super.onReceive(c, i);
        }

        void refreshAsync(Context c, boolean force) {
            final PendingResult pr = goAsync();
            final Context app = c.getApplicationContext();
            final Class<?> cls = getClass();
            // Eén thread voor alle widgets: bijwerken gebeurt op volgorde (nooit een oude stand over een nieuwe)
            try {
                WORKER.execute(() -> {
                    try { push(app, cls, build(app, force)); }
                    catch (Exception e) { App.log(app, "WIDGET", cls.getSimpleName() + ": " + e); }
                    finally { pr.finish(); }
                });
            } catch (Exception e) { pr.finish(); }
        }
    }

    static void push(Context c, Class<?> cls, RemoteViews v) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, cls));
        if (ids != null && ids.length > 0 && v != null) m.updateAppWidget(ids, v);
    }

    /** Ververs-verzoek naar een widget-soort (bijv. na een backup of als de radio verandert). */
    static void refresh(Context c, Class<?> cls) {
        try {
            int[] ids = AppWidgetManager.getInstance(c).getAppWidgetIds(new ComponentName(c, cls));
            if (ids == null || ids.length == 0) return;
            c.sendBroadcast(new Intent(c, cls).setAction(REFRESH));
        } catch (Exception ignored) { }
    }

    static PendingIntent openTool(Context c, String tool, int code) {
        Intent i = new Intent(c, MainActivity.class).putExtra("open", tool).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .setData(Uri.parse("renestools://widget/" + code + "/" + tool));
        return PendingIntent.getActivity(c, code, i, IMM);
    }

    static PendingIntent broadcast(Context c, Class<?> cls, String action, int code) {
        return PendingIntent.getBroadcast(c, code, new Intent(c, cls).setAction(action), IMM);
    }

    static void rows(RemoteViews v, List<String> lines, List<PendingIntent> taps) {
        for (int k = 0; k < ROWS.length; k++) {
            if (k < lines.size()) {
                v.setViewVisibility(ROWS[k], android.view.View.VISIBLE);
                v.setTextViewText(ROWS[k], lines.get(k));
                if (taps.get(k) != null) v.setOnClickPendingIntent(ROWS[k], taps.get(k));
            } else v.setViewVisibility(ROWS[k], android.view.View.GONE);
        }
    }

    // =====================================================================
    //  Agenda
    // =====================================================================

    public static class Agenda extends Base {
        @Override
        RemoteViews build(Context c, boolean force) {
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_rows);
            v.setTextViewText(R.id.w_title, "📅 Agenda");
            v.setTextViewText(R.id.w_sub, new SimpleDateFormat("EEE d MMM", NL).format(new Date()));
            Intent cal = new Intent(Intent.ACTION_VIEW, android.provider.CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            v.setOnClickPendingIntent(R.id.w_head, PendingIntent.getActivity(c, 7100, cal, IMM));
            List<String> lines = new ArrayList<>();
            List<PendingIntent> taps = new ArrayList<>();
            try {
                JSONObject o = new JSONObject(Launcher.calendarJson(c));
                if (Lock.active(c)) {
                    // Met app-slot geen afspraken op het startscherm (net als bij het overzicht)
                    lines.add("🔒 App-slot staat aan: tik om je agenda te openen");
                    taps.add(PendingIntent.getActivity(c, 7102, cal, IMM));
                } else if (!o.optBoolean("perm")) {
                    lines.add("Tik om Rene's Tools toegang tot je agenda te geven");
                    Intent s = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", c.getPackageName(), null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    taps.add(PendingIntent.getActivity(c, 7101, s, IMM));
                } else {
                    JSONArray ev = o.optJSONArray("events");
                    Calendar today = Calendar.getInstance();
                    SimpleDateFormat time = new SimpleDateFormat("HH:mm", NL), day = new SimpleDateFormat("EEE", NL);
                    if (ev != null) for (int i = 0; i < ev.length() && lines.size() < ROWS.length; i++) {
                        JSONObject e = ev.optJSONObject(i);
                        if (e == null || e.optLong("end") < System.currentTimeMillis()) continue;
                        Calendar b = Calendar.getInstance();
                        b.setTimeInMillis(e.optLong("begin"));
                        boolean isToday = b.get(Calendar.YEAR) == today.get(Calendar.YEAR) && b.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR);
                        String when = (isToday ? "" : day.format(b.getTime()) + " ") + (e.optBoolean("allDay") ? "hele dag" : time.format(b.getTime()));
                        lines.add(when + "  ·  " + e.optString("title"));
                        Intent open = new Intent(Intent.ACTION_VIEW, android.content.ContentUris.withAppendedId(android.provider.CalendarContract.Events.CONTENT_URI, e.optLong("id")))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        taps.add(PendingIntent.getActivity(c, 7110 + lines.size(), open, IMM));
                    }
                    if (lines.isEmpty()) { lines.add("Geen afspraken de komende dagen"); taps.add(null); }
                }
            } catch (Exception e) { lines.add("Agenda niet te lezen"); taps.add(null); }
            rows(v, lines, taps);
            return v;
        }
    }

    // =====================================================================
    //  Weer
    // =====================================================================

    static String[] wmo(int code, boolean day) {
        switch (code) {
            case 0: return new String[]{day ? "☀️" : "🌙", "Zonnig"};
            case 1: return new String[]{day ? "🌤️" : "🌙", "Licht bewolkt"};
            case 2: return new String[]{day ? "⛅" : "☁️", "Half bewolkt"};
            case 3: return new String[]{"☁️", "Bewolkt"};
            case 45: case 48: return new String[]{"🌫️", "Mist"};
            case 51: case 53: return new String[]{"🌦️", "Motregen"};
            case 55: return new String[]{"🌧️", "Motregen"};
            case 56: case 57: case 66: case 67: return new String[]{"🌧️", "IJzel"};
            case 61: return new String[]{"🌦️", "Lichte regen"};
            case 63: return new String[]{"🌧️", "Regen"};
            case 65: return new String[]{"🌧️", "Zware regen"};
            case 71: return new String[]{"🌨️", "Lichte sneeuw"};
            case 73: case 77: return new String[]{"🌨️", "Sneeuw"};
            case 75: return new String[]{"❄️", "Zware sneeuw"};
            case 80: case 81: return new String[]{"🌦️", "Buien"};
            case 82: return new String[]{"⛈️", "Zware buien"};
            case 85: case 86: return new String[]{"🌨️", "Sneeuwbuien"};
            case 95: return new String[]{"⛈️", "Onweer"};
            case 96: case 99: return new String[]{"⛈️", "Onweer met hagel"};
            default: return new String[]{"🌡️", ""};
        }
    }

    static String deg(Object d) { return d instanceof Number ? Math.round(((Number) d).doubleValue()) + "°" : "–"; }

    static volatile long lastForce = 0;

    public static class Weather extends Base {
        @Override
        RemoteViews build(Context c, boolean force) {
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_weather);
            v.setOnClickPendingIntent(R.id.w_root, broadcast(c, Weather.class, REFRESH, 7200)); // tik = nu verversen
            try {
                // Tikken = verversen, maar niet vaker dan eens per minuut naar internet
                boolean f = force && System.currentTimeMillis() - lastForce > 60_000L;
                if (f) lastForce = System.currentTimeMillis();
                JSONObject w = new JSONObject(Launcher.weatherJson(c, f));
                if (w.optBoolean("noPlace")) {
                    v.setTextViewText(R.id.w_wt, ""); v.setTextViewText(R.id.w_wd, "Weer");
                    v.setTextViewText(R.id.w_ws, "Kies een plaats in Rene's Tools (skin: tik op het weer)");
                    v.setTextViewText(R.id.w_wdays, "");
                    return v;
                }
                if (w.has("error") && !w.has("temp")) {
                    v.setTextViewText(R.id.w_wd, "Weer"); v.setTextViewText(R.id.w_ws, w.optString("error") + " · tik om opnieuw te proberen");
                    return v;
                }
                String[] now = wmo(w.optInt("code"), w.optBoolean("day", true));
                v.setTextViewText(R.id.w_wi, now[0]);
                v.setTextViewText(R.id.w_wt, deg(w.opt("temp")));
                String place = w.optString("place");
                v.setTextViewText(R.id.w_wd, now[1] + (place.isEmpty() ? "" : " · " + place));
                JSONArray days = w.optJSONArray("days");
                JSONObject today = days == null ? null : days.optJSONObject(0);
                int rain = w.optInt("rainSoon", -1);
                v.setTextViewText(R.id.w_ws, (today == null ? "" : deg(today.opt("max")) + " / " + deg(today.opt("min")))
                        + (rain >= 30 ? "  ·  ☔ " + rain + "% kans op regen" : rain >= 0 ? "  ·  droog de komende uren" : ""));
                StringBuilder sb = new StringBuilder();
                if (days != null) for (int i = 1; i < Math.min(4, days.length()); i++) {
                    JSONObject d = days.optJSONObject(i);
                    if (d == null) continue;
                    String wd;
                    try { wd = new SimpleDateFormat("EEE", NL).format(new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(d.optString("date"))); }
                    catch (Exception e) { continue; }
                    if (sb.length() > 0) sb.append("    ");
                    sb.append(wd).append(' ').append(wmo(d.optInt("code"), true)[0]).append(' ').append(deg(d.opt("max"))).append('/').append(deg(d.opt("min")));
                }
                v.setTextViewText(R.id.w_wdays, sb.toString());
            } catch (Exception e) { v.setTextViewText(R.id.w_ws, "Weer niet op te halen"); }
            return v;
        }
    }

    // =====================================================================
    //  Overzicht (backup, herinneringen, Auto redial, radio)
    // =====================================================================

    public static class Overview extends Base {
        @Override
        RemoteViews build(Context c, boolean force) {
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_rows);
            v.setTextViewText(R.id.w_title, "🧰 Rene's Tools");
            v.setTextViewText(R.id.w_sub, "");
            Intent app = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            v.setOnClickPendingIntent(R.id.w_head, PendingIntent.getActivity(c, 7300, app, IMM));
            List<String> lines = new ArrayList<>();
            List<PendingIntent> taps = new ArrayList<>();
            try {
                JSONObject t = new JSONObject(Launcher.toolsJson(c));
                SimpleDateFormat when = new SimpleDateFormat("EEE d MMM HH:mm", NL), hm = new SimpleDateFormat("HH:mm", NL);
                JSONObject bk = t.optJSONObject("backup");
                JSONObject last = bk == null ? null : bk.optJSONObject("last");
                if (bk != null && bk.optBoolean("busy")) lines.add("💾  Backup is bezig…");
                else if (last != null && last.optLong("t") > 0) {
                    int failed = 0;
                    java.util.Iterator<String> it = last.keys();
                    while (it.hasNext()) { JSONObject r = last.optJSONObject(it.next()); if (r != null && r.has("ok") && !r.optBoolean("ok")) failed++; }
                    boolean old = System.currentTimeMillis() - last.optLong("t") > 3 * 86_400_000L;
                    lines.add("💾  Backup: " + when.format(new Date(last.optLong("t"))) + (failed > 0 ? " · " + failed + " mislukt" : old ? " · al even geleden" : " ✓"));
                } else lines.add("💾  Nog geen backup gemaakt");
                taps.add(openTool(c, "backup", 7301));

                JSONObject rem = t.optJSONObject("reminders");
                if (rem != null) {
                    long first = Long.MAX_VALUE; int n = 0;
                    Calendar end = Calendar.getInstance(); end.set(Calendar.HOUR_OF_DAY, 23); end.set(Calendar.MINUTE, 59);
                    java.util.Iterator<String> it = rem.keys();
                    while (it.hasNext()) { long x = rem.optLong(it.next()); if (x >= System.currentTimeMillis() && x <= end.getTimeInMillis()) { n++; first = Math.min(first, x); } }
                    if (n > 0) { lines.add("🔔  " + n + (n == 1 ? " herinnering" : " herinneringen") + " vandaag, eerst om " + hm.format(new Date(first))); taps.add(openTool(c, "notes", 7302)); }
                }
                JSONObject rp = t.optJSONObject("redial");
                if (rp != null && rp.optJSONObject("plan") != null && rp.optLong("next") > System.currentTimeMillis()) {
                    JSONObject p = rp.getJSONObject("plan");
                    lines.add("📞  Auto redial: " + (p.optString("name").isEmpty() ? p.optString("number") : p.optString("name")) + " · " + when.format(new Date(rp.optLong("next"))));
                    taps.add(openTool(c, "redial", 7303));
                }
                JSONObject r = t.optJSONObject("radio");
                String st = r == null ? "stopped" : r.optString("status", "stopped");
                JSONObject station = r == null ? null : r.optJSONObject("station");
                if (station == null) station = t.optJSONObject("radioLast");
                if (station != null && lines.size() < ROWS.length) {
                    lines.add("📻  " + station.optString("name") + " · " + ("playing".equals(st) ? (r.optString("title").isEmpty() ? "speelt" : r.optString("title"))
                            : "paused".equals(st) ? "gepauzeerd" : "tik om te openen"));
                    taps.add(openTool(c, "radio", 7304));
                }
            } catch (Exception e) { lines.add("Overzicht niet te lezen"); taps.add(null); }
            rows(v, lines, taps);
            return v;
        }
    }

    // =====================================================================
    //  Parkeren (Automatiseringen met een parkeerapp)
    // =====================================================================

    /** De automatisering die het parkeren start: een regel met een parkeerapp, het liefst bij uitstappen/aankomen. */
    static JSONObject parkRule(Context c) {
        JSONArray a = Auto.rules(c);
        JSONObject best = null;
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.optJSONObject(i);
            JSONObject app = r == null ? null : r.optJSONObject("app");
            if (app == null || !Auto.isParkingApp(app.optString("p"), app.optString("n"))) continue;
            boolean start = isStartRule(r);
            boolean bestStart = best != null && isStartRule(best);
            if (start && (!bestStart || !best.optBoolean("on", true))) best = r;
            else if (best == null) best = r;
        }
        return best;
    }

    /** Start de regel het parkeren? Uitstappen/aankomen wel; met de hand alleen als de naam niet over stoppen gaat. */
    static boolean isStartRule(JSONObject r) {
        String trig = r == null ? "" : r.optString("trig");
        if ("bt_off".equals(trig) || "arrive".equals(trig)) return true;
        if ("manual".equals(trig)) { String n = r.optString("name").toLowerCase(java.util.Locale.ROOT); return !n.contains("stop") && !n.contains("klaar") && !n.contains("beëindig"); }
        return false;
    }

    /** Een parkeer-automatisering is gestart (of gestopt): tijd onthouden en de widget bijwerken. */
    static void parkMark(Context c, JSONObject r) {
        JSONObject app = r == null ? null : r.optJSONObject("app");
        if (app == null || !Auto.isParkingApp(app.optString("p"), app.optString("n"))) return;
        Auto.prefs(c).edit().putLong("parkStart", isStartRule(r) ? System.currentTimeMillis() : 0).apply();
        refresh(c, Park.class);
    }

    public static class Park extends Base {
        @Override
        public void onReceive(Context c, Intent i) {
            if (PARK_DONE.equals(i.getAction())) {
                Auto.prefs(c).edit().putLong("parkStart", 0).apply();
                refreshAsync(c, false);
                return;
            }
            super.onReceive(c, i);
        }

        @Override
        RemoteViews build(Context c, boolean force) {
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_park);
            JSONObject r = parkRule(c);
            if (r == null) {
                v.setTextViewText(R.id.w_ptitle, "Parkeren");
                v.setTextViewText(R.id.w_pstate, "Maak eerst een automatisering met je parkeerapp");
                v.setViewVisibility(R.id.w_pstart, android.view.View.GONE);
                v.setViewVisibility(R.id.w_popen, android.view.View.GONE);
                v.setOnClickPendingIntent(R.id.w_root, openTool(c, "auto", 7400));
                return v;
            }
            JSONObject app = r.optJSONObject("app");
            v.setTextViewText(R.id.w_ptitle, app == null ? "Parkeren" : app.optString("n", "Parkeren"));
            long start = Auto.prefs(c).getLong("parkStart", 0);
            boolean running = start > 0 && System.currentTimeMillis() - start < 24 * 3_600_000L;
            v.setTextViewText(R.id.w_pstate, running ? "Gestart om " + new SimpleDateFormat("HH:mm", NL).format(new Date(start)) + " · tik als je klaar bent"
                    : "Niet gestart (" + r.optString("name") + ")");
            v.setViewVisibility(R.id.w_pstart, running ? android.view.View.GONE : android.view.View.VISIBLE);
            v.setViewVisibility(R.id.w_popen, android.view.View.VISIBLE);
            v.setOnClickPendingIntent(R.id.w_pstart, Auto.runIntent(c, r.optString("id"), true, 7401));
            Intent li = app == null ? null : c.getPackageManager().getLaunchIntentForPackage(app.optString("p"));
            if (li != null) v.setOnClickPendingIntent(R.id.w_popen, PendingIntent.getActivity(c, 7402, li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), IMM));
            v.setOnClickPendingIntent(R.id.w_pinfo, running ? broadcast(c, Park.class, PARK_DONE, 7403) : openTool(c, "auto", 7404));
            return v;
        }
    }
}
