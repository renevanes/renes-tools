package nl.rene.tools;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

/**
 * Eigen acties en extra triggers voor Automatiseringen.
 * Acties (veld "act"): app (oud: app openen en knoppen indrukken), radio, track_start, track_stop, note, backup, redial.
 * Triggers (veld "trig"): naast Bluetooth en plekken ook "time" (om HH:MM, op gekozen dagen) en "charge_on" (aan de lader).
 */
public class AutoActions extends JobService {

    static final String ACTION_TIME = "nl.rene.tools.AUTO_TIME";
    static final int JOB_CHARGE = 4401, JOB_UNPLUG = 4402, JOB_BACKUP = 4403;

    static String act(JSONObject r) { String a = r.optString("act", "app"); return a.isEmpty() ? "app" : a; }
    static boolean internal(JSONObject r) { return !"app".equals(act(r)); }

    /** Controle bij opslaan (null = goed). */
    static String check(JSONObject o) {
        switch (act(o)) {
            case "app": return null;
            case "radio": return o.optJSONObject("radio") == null || o.optJSONObject("radio").optString("url").isEmpty() ? "Kies een radiozender" : null;
            case "note": return o.optJSONObject("note") == null || o.optJSONObject("note").optString("id").isEmpty() ? "Kies welke notitie getoond moet worden" : null;
            case "redial": return o.optString("number").replaceAll("[^0-9+]", "").length() < 3 ? "Vul het telefoonnummer in" : null;
            case "track_start": case "track_stop": case "backup": return null;
            default: return "Onbekende actie";
        }
    }

    static String label(JSONObject r) {
        switch (act(r)) {
            case "radio": return "Radio: " + (r.optJSONObject("radio") == null ? "" : r.optJSONObject("radio").optString("name"));
            case "note": return "Notitie tonen: " + (r.optJSONObject("note") == null ? "" : r.optJSONObject("note").optString("title"));
            case "redial": return "Auto redial: " + r.optString("number");
            case "track_start": return "Route-opname starten";
            case "track_stop": return "Route-opname stoppen en opslaan";
            case "backup": return "Alles back-uppen";
            default: return "";
        }
    }

    /** Voert een eigen actie uit. Geeft false als Android het nu niet toestaat (dan vraagt de aanroeper met een melding). */
    static boolean run(Context c, JSONObject r) {
        try {
            switch (act(r)) {
                case "radio": {
                    JSONObject s = r.optJSONObject("radio");
                    if (s == null) return true;
                    Radio.prefs(c).edit().putString("last", s.toString()).apply();
                    RadioService.send(c, RadioService.PLAY, s.toString());
                    return true; // de radio meldt zelf als starten niet lukt
                }
                case "track_start": {
                    if (TracksService.running) return true;
                    if (!Auto.bgLocOk(c) && !MainActivity.visible) return false; // Android weigert dan vanaf de achtergrond: vragen
                    Intent i = new Intent(c, TracksService.class).setAction(TracksService.ACTION_START);
                    if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
                    return true;
                }
                case "track_stop": {
                    if (!TracksService.running) return true;
                    c.startService(new Intent(c, TracksService.class).setAction(TracksService.ACTION_STOP).putExtra("autoSave", true));
                    return true;
                }
                case "note": showNote(c, r); return true;
                case "backup": {
                    JobScheduler js = (JobScheduler) c.getSystemService(JOB_SCHEDULER_SERVICE);
                    if (AllBackup.busy || js.getPendingJob(JOB_BACKUP) != null) return true; // loopt al
                    js.schedule(new JobInfo.Builder(JOB_BACKUP, new ComponentName(c, AutoActions.class)).setOverrideDeadline(1000).build());
                    return true;
                }
                case "redial": {
                    if (RedialService.alive) return true;
                    Intent i = new Intent(c, RedialService.class).setAction(RedialService.ACTION_START)
                            .putExtra("number", r.optString("number")).putExtra("name", r.optString("numberName"))
                            .putExtra("attempts", 10).putExtra("interval", 10).putExtra("stopWhenAnswered", true);
                    if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
                    return true;
                }
                default: return true;
            }
        } catch (Exception e) {
            App.log(c, "AUTO", "actie " + act(r) + ": " + e.getClass().getSimpleName());
            return false;
        }
    }

    /** "Notitie tonen": de open punten van het lijstje in een melding; tikken opent de notitie. */
    static void showNote(Context c, JSONObject r) {
        JSONObject ref = r.optJSONObject("note");
        if (ref == null) return;
        JSONObject n = Notes.note(c, ref.optString("id"));
        if (n == null) { Auto.log(c, r, "Notitie bestaat niet meer"); return; }
        StringBuilder b = new StringBuilder();
        JSONArray items = n.optJSONArray("items");
        int open = 0;
        if (items != null) for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null || it.optBoolean("done")) continue;
            if (open < 15) b.append("☐ ").append(it.optString("text")).append('\n');
            open++;
        }
        if (open > 15) b.append("en nog ").append(open - 15).append("…");
        String text = b.length() > 0 ? b.toString().trim() : (n.optString("text").isEmpty() ? "Alles afgestreept ✓" : n.optString("text"));
        Auto.channel(c);
        Intent openNote = new Intent(c, MainActivity.class).putExtra("open", "note:" + n.optString("id"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP).setData(android.net.Uri.fromParts("autonote", n.optString("id"), null));
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, NotifCenter.ch(c, Auto.CHANNEL)) : new Notification.Builder(c);
        String title = n.optString("title").isEmpty() ? "Notitie" : n.optString("title");
        nb.setSmallIcon(R.drawable.ic_notif).setContentTitle(title + (open > 0 ? " · " + open + " open" : ""))
                .setContentText(text.split("\n")[0]).setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true).setContentIntent(PendingIntent.getActivity(c, Auto.notifId(r.optString("id")) * 2, openNote, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        if (Build.VERSION.SDK_INT >= 26) nb.setTimeoutAfter(3 * 60 * 60_000L);
        Auto.post(c, Auto.notifId(r.optString("id")), nb.build());
    }

    // ---------- trigger: tijdstip ----------

    static PendingIntent timeOp(Context c, String id) {
        Intent i = new Intent(c, AutoReceiver.Priv.class).setAction(ACTION_TIME).putExtra("id", id)
                .setData(android.net.Uri.fromParts("autotime", id, null));
        return PendingIntent.getBroadcast(c, 4500, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Eerstvolgende moment voor een tijd-regel na 'after' (0 als geen geldige tijd). */
    static long nextTime(JSONObject r, long after) { return nextTime(AutoLogic.minutes(r.optString("at")), r.optInt("days", 0), after); }

    static long nextTime(int m, int days, long after) {
        if (m < 0) return 0;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(after);
        cal.set(Calendar.HOUR_OF_DAY, m / 60); cal.set(Calendar.MINUTE, m % 60); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
        for (int i = 0; i < 9; i++) {
            if (cal.getTimeInMillis() > after && AutoLogic.dayOk(days, cal.get(Calendar.DAY_OF_WEEK))) return cal.getTimeInMillis();
            cal.add(Calendar.DAY_OF_MONTH, 1);
            cal.set(Calendar.HOUR_OF_DAY, m / 60); cal.set(Calendar.MINUTE, m % 60);
        }
        return 0;
    }

    /** Alle tijd-regels (opnieuw) inplannen; uitgezette of verwijderde regels afmelden. */
    static void armTimes(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        JSONArray rules = Auto.rules(c);
        java.util.Set<String> armed = new java.util.HashSet<>(Auto.prefs(c).getStringSet("timeIds", new java.util.HashSet<>()));
        java.util.Set<String> now = new java.util.HashSet<>();
        for (int i = 0; i < rules.length(); i++) {
            JSONObject r = rules.optJSONObject(i);
            if (r == null || !"time".equals(r.optString("trig")) || !r.optBoolean("on", true)) continue;
            long t = nextTime(r, System.currentTimeMillis());
            if (t == 0) continue;
            String id = r.optString("id");
            now.add(id);
            try {
                if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, timeOp(c, id));
                else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, timeOp(c, id));
            } catch (SecurityException e) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, timeOp(c, id)); }
        }
        for (String id : armed) if (!now.contains(id)) am.cancel(timeOp(c, id));
        Auto.prefs(c).edit().putStringSet("timeIds", now).apply();
        armCharge(c);
    }

    static void onTime(Context c, String id) {
        JSONObject r = Auto.rule(c, id);
        if (r != null && r.optBoolean("on", true) && "time".equals(r.optString("trig"))) Auto.fire(c, r, "Om " + r.optString("at"));
        armTimes(c);
    }

    // ---------- trigger: aan de lader ----------

    static boolean hasRule(Context c, String trig) {
        JSONArray a = Auto.rules(c);
        for (int i = 0; i < a.length(); i++) { JSONObject r = a.optJSONObject(i); if (r != null && r.optBoolean("on", true) && trig.equals(r.optString("trig"))) return true; }
        return false;
    }

    static boolean charging(Context c) {
        Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int plugged = b == null ? 0 : b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        return plugged != 0;
    }

    /** Wacht op "aan de lader" (JobScheduler; Android meldt opladen niet meer los aan apps). */
    static void armCharge(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(JOB_SCHEDULER_SERVICE);
        if (!hasRule(c, "charge_on")) { js.cancel(JOB_CHARGE); js.cancel(JOB_UNPLUG); return; }
        if (js.getPendingJob(JOB_CHARGE) != null || js.getPendingJob(JOB_UNPLUG) != null) return;
        scheduleCharge(c);
    }

    static void scheduleCharge(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(JOB_SCHEDULER_SERVICE);
        js.schedule(new JobInfo.Builder(JOB_CHARGE, new ComponentName(c, AutoActions.class)).setRequiresCharging(true).setPersisted(true).build());
    }

    static void scheduleUnplugWatch(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(JOB_SCHEDULER_SERVICE);
        js.schedule(new JobInfo.Builder(JOB_UNPLUG, new ComponentName(c, AutoActions.class)).setMinimumLatency(10 * 60_000L).setPersisted(true).build());
    }

    @Override
    public boolean onStartJob(JobParameters p) {
        Context c = getApplicationContext();
        switch (p.getJobId()) {
            case JOB_CHARGE: {
                JSONArray a = Auto.rules(c);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject r = a.optJSONObject(i);
                    if (r != null && r.optBoolean("on", true) && "charge_on".equals(r.optString("trig")) && Auto.timeOk(r)) Auto.fire(c, r, "Aan de lader");
                }
                scheduleUnplugWatch(c); // pas na loskoppelen weer opnieuw
                return false;
            }
            case JOB_UNPLUG:
                if (charging(c)) scheduleUnplugWatch(c); else if (hasRule(c, "charge_on")) scheduleCharge(c);
                return false;
            case JOB_BACKUP:
                if (!AllBackup.tryBegin()) return false;
                new Thread(() -> {
                    try { AllBackup.run(c, true); } finally { jobFinished(p, false); }
                }, "auto-backup").start();
                return true;
            default: return false;
        }
    }

    @Override public boolean onStopJob(JobParameters p) {
        if (p.getJobId() == JOB_BACKUP) AllBackup.stop = true;
        return false;
    }
}
