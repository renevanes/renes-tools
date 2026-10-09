package nl.rene.tools;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Radiowekker, herinneringen bij notities, en na het herstarten van de telefoon alles opnieuw plannen.
 * Werkt ook vóór de eerste ontgrendeling na een herstart (directBootAware): dan alleen de noodwekker.
 */
public class AlarmReceiver extends BroadcastReceiver {
    static final String WIDGET_PP = "nl.rene.tools.WIDGET_PLAYPAUSE", WIDGET_STOP = "nl.rene.tools.WIDGET_STOP";
    static final String ACTION_SENTINEL = "nl.rene.tools.SENTINEL";

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i == null ? null : i.getAction();
        // Vóór de eerste ontgrendeling zijn de gewone instellingen onleesbaar: alleen de noodwekker
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(a)) { RadioAlarm.armLocked(c); return; }
        if (RadioAlarm.ACTION_LOCKED.equals(a)) { RadioAlarm.fireLocked(c); return; }
        if (!App.unlocked(c)) return;
        if (ACTION_SENTINEL.equals(a)) { setSentinel(c); return; }
        if (RadioAlarm.ACTION.equals(a)) RadioAlarm.fire(c, false);
        else if (RadioAlarm.ACTION_SNOOZE.equals(a)) RadioAlarm.fire(c, true);
        else if (WIDGET_PP.equals(a) || WIDGET_STOP.equals(a)) {
            // Knoppen van de radio-widget
            boolean playing = "playing".equals(RadioService.status) || "connecting".equals(RadioService.status);
            if (WIDGET_STOP.equals(a)) RadioService.send(c, RadioService.STOP, null);
            else RadioService.send(c, playing ? RadioService.PAUSE : RadioService.RESUME, null);
            RadioWidget.refresh(c);
        }
        else if (Reminders.ACTION.equals(a)) Reminders.fire(c, i.getStringExtra("id"));
        else if (Reminders.ACTION_SNOOZE.equals(a) || Reminders.ACTION_DONE.equals(a)) Reminders.onAction(c, i);
        else if (RedialPlan.ACTION.equals(a)) RedialPlan.fire(c);
        else if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                || "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED".equals(a)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(a) || Intent.ACTION_TIME_CHANGED.equals(a)) {
            rearmAll(c);
        }
    }

    /** Alle wekkers en tijden opnieuw zetten (allemaal veilig om vaker te doen). */
    /** synchronized: na een herstart lopen de app-start en BOOT_COMPLETED anders tegelijk door elkaar. */
    static synchronized void rearmAll(Context c) {
        RadioAlarm.cancelLocked(c);
        RadioAlarm.schedule(c);
        RadioAlarm.rearmSnooze(c);
        Reminders.rearm(c);
        RedialPlan.arm(c);
        TranscribeJob.ensureScheduled(c);
        RadioWidget.refresh(c);
        Auto.armPlaces(c, true);
        AutoActions.armTimes(c);
        AutoActions.armCharge(c);
        setSentinel(c);
    }

    private static PendingIntent sentinel(Context c, int flags) {
        return PendingIntent.getBroadcast(c, 77, new Intent(c, AlarmReceiver.class).setAction(ACTION_SENTINEL), PendingIntent.FLAG_IMMUTABLE | flags);
    }

    /** Een wekker ver in de toekomst als verklikker: Android wist alle wekkers bij "geforceerd stoppen". */
    static void setSentinel(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.set(AlarmManager.RTC, System.currentTimeMillis() + 300L * 86_400_000L, sentinel(c, PendingIntent.FLAG_UPDATE_CURRENT));
    }

    /**
     * Bij het starten van de app: is de verklikker weg (geforceerd gestopt, bijv. door de batterijbesparing van
     * ColorOS), dan zijn ook de radiowekker, herinneringen en geplande acties weg. Die dan meteen terugzetten.
     */
    static void ensureArmed(Context c) {
        try {
            if (!App.unlocked(c)) return;
            synchronized (AlarmReceiver.class) {
                if (sentinel(c, PendingIntent.FLAG_NO_CREATE) != null) return; // (ook als BOOT_COMPLETED het net deed)
                App.log(c, "ALARM", "wekkers waren weg (geforceerd gestopt of herstart): opnieuw gezet");
                rearmAll(c);
            }
        } catch (Exception e) { App.log(c, "ALARM", "opnieuw zetten mislukt: " + e); }
    }
}
