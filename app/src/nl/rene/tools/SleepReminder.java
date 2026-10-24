package nl.rene.tools;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Slaappraat: een herinnering op bedtijd ("Slaappraat aanzetten?"). Android laat de microfoon niet vanzelf
 * beginnen; de melding opent het scherm, waar je met één tik start. Ongeveer op tijd (niet tot op de seconde):
 * zuinig, en er is geen toestemming voor exacte wekkers nodig.
 */
final class SleepReminder {

    private SleepReminder() { }

    static final String ACTION = "nl.rene.tools.SLEEP_REMIND";
    static final String CHANNEL = "sleeptalk-remind";
    private static final int NOTIFICATION = 6101;

    /** De eerstvolgende keer dat het "HH:mm" is, na nu (0 = uit). Zuiver (getest). */
    static long next(long now, String hhmm, TimeZone tz) {
        if (hhmm == null || !hhmm.matches("([01]\\d|2[0-3]):[0-5]\\d")) return 0;
        Calendar k = Calendar.getInstance(tz);
        k.setTimeInMillis(now);
        k.set(Calendar.HOUR_OF_DAY, Integer.parseInt(hhmm.substring(0, 2)));
        k.set(Calendar.MINUTE, Integer.parseInt(hhmm.substring(3)));
        k.set(Calendar.SECOND, 0); k.set(Calendar.MILLISECOND, 0);
        while (k.getTimeInMillis() <= now + 30_000L) k.add(Calendar.DAY_OF_MONTH, 1);
        return k.getTimeInMillis();
    }

    private static PendingIntent op(Context c) {
        return PendingIntent.getBroadcast(c, 6100, new Intent(c, AlarmReceiver.class).setAction(ACTION), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Opnieuw plannen (na een wijziging, herstart van de telefoon of als hij afging). */
    static void arm(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            long t = next(System.currentTimeMillis(), SleepTalk.remindAt(c), TimeZone.getDefault());
            if (t == 0) { am.cancel(op(c)); return; }
            // Binnen een kwartier na de gekozen tijd: zuinig (Android mag het combineren met andere wekkers)
            am.setWindow(AlarmManager.RTC_WAKEUP, t, 15 * 60_000L, op(c));
        } catch (Throwable e) { App.log(c, "SLAAP", "herinnering plannen: " + e); }
    }

    static void fire(Context c) {
        try {
            if (!SleepService.running() && !SleepTalk.remindAt(c).isEmpty() && !NotifCenter.muted(c)) {
                NotificationManager nm = c.getSystemService(NotificationManager.class);
                if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
                    NotificationChannel ch = new NotificationChannel(CHANNEL, "Slaappraat: bedtijd", NotificationManager.IMPORTANCE_DEFAULT);
                    ch.setDescription("Een herinnering op bedtijd om Slaappraat aan te zetten");
                    nm.createNotificationChannel(ch);
                }
                Intent open = new Intent(c, MainActivity.class).putExtra("open", "sleep").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                PendingIntent content = PendingIntent.getActivity(c, NOTIFICATION, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                String ch = NotifCenter.ch(c, CHANNEL);
                Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, ch) : new Notification.Builder(c);
                b.setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Slaappraat aanzetten?")
                        .setContentText("Tik hier en dan op Begin met luisteren. Welterusten!").setAutoCancel(true).setContentIntent(content);
                if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(3 * 3_600_000L);
                nm.notify(NOTIFICATION, b.build());
            }
        } catch (Throwable e) { App.log(c, "SLAAP", "herinnering: " + e); }
        arm(c);
    }

    /** Als je begint met luisteren: de herinnering van vanavond weg. */
    static void dismiss(Context c) {
        try { c.getSystemService(NotificationManager.class).cancel(NOTIFICATION); } catch (Throwable ignored) { }
    }
}
