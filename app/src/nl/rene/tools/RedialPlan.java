package nl.rene.tools;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONObject;

/**
 * Auto redial op een vast tijdstip (eenmalig of op gekozen dagen), bijvoorbeeld om 09:00 de huisarts.
 * Een precieze wekker start dan de gewone Auto redial-service met de bewaarde instellingen.
 */
final class RedialPlan {

    private RedialPlan() { }

    static final String ACTION = "nl.rene.tools.REDIAL_AT";

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("redialplan", Context.MODE_PRIVATE); }

    static PendingIntent op(Context c) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(ACTION);
        return PendingIntent.getBroadcast(c, 6000, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Plan opslaan: {number, name, hour, minute, days (bitmasker ma=1 … zo=64, 0 = eenmalig), attempts, interval, randomMin, randomMax, stopWhenAnswered, speaker}. */
    static String set(Context c, String json) {
        try {
            JSONObject o = new JSONObject(json);
            String num = o.optString("number").trim();
            if (num.replaceAll("[^0-9]", "").length() < 3) return "Vul een geldig telefoonnummer in";
            if (c.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED
                    || c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
                return "Geef eerst toestemming om te bellen";
            int h = o.optInt("hour", -1), m = o.optInt("minute", -1);
            if (h < 0 || h > 23 || m < 0 || m > 59) return "Kies een tijd";
            if (o.optInt("days") == 0) o.put("at", RadioAlarm.next(h, m, 0, System.currentTimeMillis()));
            else o.remove("at");
            prefs(c).edit().putString("plan", o.toString()).remove("missed").apply();
            arm(c);
            return "";
        } catch (Exception e) { return "Plannen mislukt: " + e.getMessage(); }
    }

    static void clear(Context c) {
        prefs(c).edit().remove("plan").remove("next").apply();
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(op(c));
    }

    static JSONObject plan(Context c) {
        try { String s = prefs(c).getString("plan", null); return s == null ? null : new JSONObject(s); } catch (Exception e) { return null; }
    }

    static String stateJson(Context c) {
        try {
            JSONObject p = plan(c);
            JSONObject o = new JSONObject().put("on", p != null).put("next", prefs(c).getLong("next", 0)).put("last", prefs(c).getString("last", ""));
            if (p != null) o.put("plan", p);
            if (Build.VERSION.SDK_INT >= 31) {
                AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
                o.put("exact", am != null && am.canScheduleExactAlarms());
            } else o.put("exact", true);
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    /** Zet de wekker voor de eerstvolgende keer (ook na herstart van de telefoon). */
    static void arm(Context c) {
        JSONObject p = plan(c);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        if (p == null) { am.cancel(op(c)); return; }
        long now = System.currentTimeMillis(), t;
        if (p.optInt("days") == 0 && p.optLong("at") > 0) {
            t = p.optLong("at");
            if (t < now) {
                // Eenmalig en het moment is voorbij (telefoon stond uit): kort te laat → nu; anders vervalt het.
                if (now - t < 10 * 60 * 1000L) t = now + 2000;
                else { prefs(c).edit().remove("plan").remove("next").putString("last", "Gemist (telefoon stond uit) " + stamp()).apply(); am.cancel(op(c)); return; }
            }
        } else t = RadioAlarm.next(p.optInt("hour"), p.optInt("minute"), p.optInt("days"), now);
        prefs(c).edit().putLong("next", t).apply();
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, op(c));
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, op(c));
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, op(c));
        }
    }

    /** De wekker gaat af: Auto redial starten, en de volgende keer plannen (of het eenmalige plan opruimen). */
    static void fire(Context c) {
        JSONObject p = plan(c);
        if (p == null) return;
        String err = null;
        if (RedialService.alive) err = "Auto redial liep al";
        else {
            Intent i = new Intent(c, RedialService.class).setAction(RedialService.ACTION_START)
                    .putExtra("number", p.optString("number"))
                    .putExtra("name", p.optString("name"))
                    .putExtra("attempts", p.optInt("attempts", 10))
                    .putExtra("interval", p.optInt("interval", 10))
                    .putExtra("randomMin", p.optInt("randomMin", 0))
                    .putExtra("randomMax", p.optInt("randomMax", 0))
                    .putExtra("stopWhenAnswered", p.optBoolean("stopWhenAnswered", true))
                    .putExtra("speaker", p.optBoolean("speaker", false));
            try {
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
            } catch (Exception e) {
                err = "Android stond het automatisch starten niet toe";
                App.log(c, "REDIAL", "gepland starten: " + e);
                prefs(c).edit().putString("missed", p.toString()).putLong("missedAt", System.currentTimeMillis()).apply();
                notifyTap(c, p);
            }
        }
        prefs(c).edit().putString("last", (err == null ? "Gestart " : err + " ") + stamp()).apply();
        if (p.optInt("days") == 0) { prefs(c).edit().remove("plan").remove("next").apply(); }
        else arm(c);
    }

    static String stamp() { return new java.text.SimpleDateFormat("d MMM HH:mm", new java.util.Locale("nl", "NL")).format(new java.util.Date()); }

    /** Tik op de melding: vanuit de app (voorgrond) mag het starten wel. Alleen binnen een uur na het geplande moment. */
    static void startMissed(Context c, Intent i) {
        if (i == null || !i.getBooleanExtra("redialPlan", false)) return;
        i.removeExtra("redialPlan");
        if (!App.tokenOk(c, i)) return; // alleen vanuit onze eigen melding
        String s = prefs(c).getString("missed", null);
        long at = prefs(c).getLong("missedAt", 0);
        prefs(c).edit().remove("missed").apply();
        if (s == null || System.currentTimeMillis() - at > 60 * 60 * 1000L || RedialService.alive) return;
        try {
            JSONObject p = new JSONObject(s);
            Intent r = new Intent(c, RedialService.class).setAction(RedialService.ACTION_START)
                    .putExtra("number", p.optString("number")).putExtra("name", p.optString("name"))
                    .putExtra("attempts", p.optInt("attempts", 10)).putExtra("interval", p.optInt("interval", 10))
                    .putExtra("randomMin", p.optInt("randomMin", 0)).putExtra("randomMax", p.optInt("randomMax", 0))
                    .putExtra("stopWhenAnswered", p.optBoolean("stopWhenAnswered", true)).putExtra("speaker", p.optBoolean("speaker", false));
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(r); else c.startService(r);
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(1003);
        } catch (Exception e) { App.log(c, "REDIAL", "gemist starten: " + e); }
    }

    static final String CHANNEL = "redialplan";

    /** Als Android het automatisch starten blokkeert: een melding om het met één tik te starten. */
    static void notifyTap(Context c, JSONObject p) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager m = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (m != null) m.createNotificationChannel(new android.app.NotificationChannel(CHANNEL, "Auto redial op tijd", NotificationManager.IMPORTANCE_HIGH));
        }
        Intent open = new Intent(c, MainActivity.class).putExtra("open", "redial").putExtra("redialPlan", true).putExtra("tok", App.token(c))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, NotifCenter.ch(c, CHANNEL)) : new Notification.Builder(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && !NotifCenter.muted(c)) nm.notify(1003, b.setSmallIcon(R.drawable.ic_notif).setContentTitle("Tijd om " + (p.optString("name").isEmpty() ? p.optString("number") : p.optString("name")) + " te bellen")
                .setContentText("Tik om Auto redial te starten").setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(c, 62, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
    }
}
