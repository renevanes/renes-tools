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
    static final String ACTION_LOCKED = "nl.rene.tools.RADIO_ALARM_LOCKED";
    static final String CH_LOCKED = "wekker-vergrendeld";
    static final int NOTIF_LOCKED = 4711;

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

    // ---------- na een herstart, vóór de eerste ontgrendeling ----------
    // Na een nachtelijke update-herstart komt BOOT_COMPLETED pas na ontgrendelen: dan zou de wekker van vandaag
    // ongemerkt overgeslagen worden. Daarom staat de tijd ook in de "apparaatopslag" (leesbaar vóór ontgrendelen)
    // en klinkt dan de gewone wekkertoon (de radio kan pas na ontgrendelen).

    static SharedPreferences dp(Context c) { return c.createDeviceProtectedStorageContext().getSharedPreferences("alarm_dp", Context.MODE_PRIVATE); }

    private static void mirror(Context c, SharedPreferences p) {
        try {
            dp(c).edit().putBoolean("on", p.getBoolean("on", false) && p.getString("station", null) != null)
                    .putInt("hour", p.getInt("hour", 7)).putInt("minute", p.getInt("minute", 0)).putInt("days", p.getInt("days", 31)).apply();
        } catch (Exception ignored) { }
    }

    private static PendingIntent lockedOp(Context c) {
        return PendingIntent.getBroadcast(c, 43, new Intent(c, AlarmReceiver.class).setAction(ACTION_LOCKED), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** LOCKED_BOOT_COMPLETED: de noodwekker zetten volgens de gespiegelde instellingen. */
    static void armLocked(Context c) {
        SharedPreferences d = dp(c);
        if (!d.getBoolean("on", false)) return;
        long t = next(d.getInt("hour", 7), d.getInt("minute", 0), d.getInt("days", 31), System.currentTimeMillis());
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (t <= 0 || am == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, lockedOp(c));
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, lockedOp(c));
        } catch (SecurityException e) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, lockedOp(c)); }
    }

    /** Na ontgrendelen neemt de gewone radiowekker het over. */
    static void cancelLocked(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(lockedOp(c));
        android.app.NotificationManager nm = (android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIF_LOCKED);
    }

    /** De noodwekker gaat af: is de telefoon intussen ontgrendeld, dan gewoon de radio; anders de wekkertoon. */
    static void fireLocked(Context c) {
        if (App.unlocked(c)) {
            // De gewone wekker staat dan meestal al (BOOT_COMPLETED); alleen afgaan als die er niet is
            if (PendingIntent.getBroadcast(c, 40, new Intent(c, AlarmReceiver.class).setAction(ACTION), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_NO_CREATE) == null) fire(c, false);
            return;
        }
        android.app.NotificationManager nm = (android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        // De standaard-wekkertoon via de instellingen (die zijn ook vóór ontgrendelen leesbaar)
        android.net.Uri tone = android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI;
        if (Build.VERSION.SDK_INT >= 26) {
            android.app.NotificationChannel ch = new android.app.NotificationChannel(CH_LOCKED, "Wekker (telefoon nog vergrendeld)", android.app.NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(tone, new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
        android.app.Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new android.app.Notification.Builder(c, CH_LOCKED) : new android.app.Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_radio).setContentTitle("⏰ Wekker").setContentText("Ontgrendel je telefoon voor de radio. Veeg weg om te stoppen.")
                .setCategory(android.app.Notification.CATEGORY_ALARM).setAutoCancel(true);
        if (Build.VERSION.SDK_INT < 26) b.setSound(tone, android.media.AudioManager.STREAM_ALARM).setPriority(android.app.Notification.PRIORITY_MAX);
        android.app.Notification n = b.build();
        n.flags |= android.app.Notification.FLAG_INSISTENT; // blijft klinken tot wegvegen
        try { nm.notify(NOTIF_LOCKED, n); } catch (Exception ignored) { }
        if (dp(c).getInt("days", 31) != 0) armLocked(c); // nog steeds niet ontgrendeld morgen? Dan ook dan weer (niet bij eenmalig)
        else dp(c).edit().putBoolean("on", false).apply();
    }

    /** Plant de volgende keer volgens de instellingen (of zet hem uit). */
    static void schedule(Context c) {
        SharedPreferences p = prefs(c);
        mirror(c, p);
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
