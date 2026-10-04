package nl.rene.tools;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.util.Calendar;

/**
 * Radiowekker: op een tijdstip (eenmalig of op gekozen dagen) start de radio met de gekozen zender,
 * zacht beginnend via het wekkervolume. Lukt de stream niet, dan klinkt de gewone wekkertoon.
 * Gebruikt AlarmManager.setAlarmClock (precies, ook in de diepe slaapstand, wekkericoon in de statusbalk).
 */
final class RadioAlarm {

    private RadioAlarm() { }

    static final String ACTION = "nl.rene.tools.RADIO_ALARM";
    static final String ACTION_SNOOZE = "nl.rene.tools.RADIO_ALARM_SNOOZE";

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("alarm", Context.MODE_PRIVATE); }

    /** days: bit 0 = maandag ... bit 6 = zondag; 0 = eenmalig. */
    static long next(int hour, int minute, int days, long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        for (int i = 0; i < 8; i++) {
            if (c.getTimeInMillis() > now) {
                int dow = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7; // maandag = 0
                if (days == 0 || (days & (1 << dow)) != 0) return c.getTimeInMillis();
            }
            c.add(Calendar.DAY_OF_MONTH, 1);
            c.set(Calendar.HOUR_OF_DAY, hour); // zomer-/wintertijd: uur opnieuw zetten
            c.set(Calendar.MINUTE, minute);
        }
        return 0;
    }

    static PendingIntent operation(Context c) { return operation(c, false); }

    /** Snooze/proef heeft een eigen wekker, zodat opslaan of herstarten hem niet wist. */
    static PendingIntent operation(Context c, boolean snooze) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(snooze ? ACTION_SNOOZE : ACTION);
        return PendingIntent.getBroadcast(c, snooze ? 42 : 40, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void setAt(Context c, long t) { setAt(c, t, false); }

    static void setAt(Context c, long t, boolean snooze) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent show = new Intent(c, MainActivity.class).putExtra("open", "radio").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent si = PendingIntent.getActivity(c, 41, show, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, operation(c, snooze)); // minder precies, maar gaat af
            } else {
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(t, si), operation(c, snooze));
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, operation(c, snooze));
        }
        prefs(c).edit().putLong(snooze ? "snoozeAt" : "nextAt", t).apply();
    }

    /** Plant de volgende keer volgens de instellingen (of zet hem uit). */
    static void schedule(Context c) {
        SharedPreferences p = prefs(c);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (!p.getBoolean("on", false) || p.getString("station", null) == null) {
            if (am != null) am.cancel(operation(c));
            p.edit().remove("nextAt").apply();
            return;
        }
        long t = next(p.getInt("hour", 7), p.getInt("minute", 0), p.getInt("days", 31), System.currentTimeMillis());
        if (t > 0) setAt(c, t);
    }

    static void snooze(Context c, int minutes) {
        setAt(c, System.currentTimeMillis() + minutes * 60_000L, true);
    }

    /** Na herstarten: een lopende snooze opnieuw zetten. */
    static void rearmSnooze(Context c) {
        long t = prefs(c).getLong("snoozeAt", 0);
        if (t > System.currentTimeMillis()) setAt(c, t, true); else prefs(c).edit().remove("snoozeAt").apply();
    }

    /** De wekker gaat af (snooze = na snoozen of als proef; verandert de instellingen niet). */
    static void fire(Context c, boolean snooze) {
        SharedPreferences p = prefs(c);
        String st = p.getString("station", null);
        if (snooze) p.edit().remove("snoozeAt").apply();
        else {
            if (p.getInt("days", 31) == 0) p.edit().putBoolean("on", false).apply(); // eenmalig: daarna uit
            schedule(c);
        }
        if (st == null) return;
        Intent i = new Intent(c, RadioService.class).setAction(RadioService.PLAY).putExtra("x", st).putExtra("alarm", true)
                .putExtra("k", RadioService.secret(c));
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception e) {
            App.log(c, "ALARM", "radio starten lukte niet: " + e);
        }
    }

    static String stateJson(Context c) {
        SharedPreferences p = prefs(c);
        try {
            JSONObject o = new JSONObject().put("on", p.getBoolean("on", false)).put("hour", p.getInt("hour", 7))
                    .put("minute", p.getInt("minute", 0)).put("days", p.getInt("days", 31)).put("nextAt", p.getLong("nextAt", 0));
            String st = p.getString("station", null);
            if (st != null) o.put("station", new JSONObject(st));
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            o.put("exact", Build.VERSION.SDK_INT < 31 || (am != null && am.canScheduleExactAlarms()));
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }
}
