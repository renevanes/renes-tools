package nl.rene.tools;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.util.Map;

/** Herinneringen bij notities: een melding op het gekozen tijdstip; tikken opent de notitie. */
final class Reminders {

    private Reminders() { }

    static final String ACTION = "nl.rene.tools.NOTE_REMINDER";
    static final String CHANNEL = "reminders";

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("reminders", Context.MODE_PRIVATE); }

    static PendingIntent op(Context c, String id) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(ACTION).putExtra("id", id)
                .setData(android.net.Uri.fromParts("note", id, null)); // uniek per notitie
        return PendingIntent.getBroadcast(c, 5000, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void arm(Context c, String id, long t) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, op(c, id));
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, op(c, id));
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, op(c, id));
        }
    }

    /** t = 0 haalt de herinnering weg. */
    static void set(Context c, String id, String title, long t) {
        if (id == null || id.isEmpty()) return;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (t <= 0) {
            if (am != null) am.cancel(op(c, id));
            prefs(c).edit().remove(id).apply();
            return;
        }
        try {
            prefs(c).edit().putString(id, new JSONObject().put("t", t).put("title", title == null ? "" : title).toString()).apply();
        } catch (Exception ignored) { }
        arm(c, id, t);
    }

    static String all(Context c) {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, ?> e : prefs(c).getAll().entrySet()) {
            try { o.put(e.getKey(), new JSONObject(String.valueOf(e.getValue())).optLong("t")); } catch (Exception ignored) { }
        }
        return o.toString();
    }

    /** Na herstarten van de telefoon alles opnieuw plannen (gemiste meteen melden). */
    static void rearm(Context c) {
        for (Map.Entry<String, ?> e : prefs(c).getAll().entrySet()) {
            try {
                long t = new JSONObject(String.valueOf(e.getValue())).optLong("t");
                arm(c, e.getKey(), Math.max(t, System.currentTimeMillis() + 5000));
            } catch (Exception ignored) { }
        }
    }

    static void fire(Context c, String id) {
        String raw = prefs(c).getString(id, null);
        prefs(c).edit().remove(id).apply();
        if (raw == null) return;
        String title;
        try { title = new JSONObject(raw).optString("title"); } catch (Exception e) { title = ""; }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Herinneringen", NotificationManager.IMPORTANCE_HIGH);
            ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
        Intent open = new Intent(c, MainActivity.class).putExtra("open", "note:" + id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .setData(android.net.Uri.fromParts("note", id, null));
        PendingIntent pi = PendingIntent.getActivity(c, 6000 + (id.hashCode() & 0xffff), open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, NotifCenter.ch(c, CHANNEL)) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_notif).setContentTitle(title.isEmpty() ? "Herinnering" : title)
                .setContentText("Herinnering uit je notities").setAutoCancel(true).setContentIntent(pi)
                .setCategory(Notification.CATEGORY_REMINDER);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);
        NotifCenter.post(c, 7000 + (id.hashCode() & 0xffff), b.build());
    }
}
