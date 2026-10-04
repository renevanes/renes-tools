package nl.rene.tools;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Radiowekker, herinneringen bij notities, en na het herstarten van de telefoon alles opnieuw plannen. */
public class AlarmReceiver extends BroadcastReceiver {
    static final String WIDGET_PP = "nl.rene.tools.WIDGET_PLAYPAUSE", WIDGET_STOP = "nl.rene.tools.WIDGET_STOP";
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i == null ? null : i.getAction();
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
        else if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                || "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED".equals(a)) {
            RadioAlarm.schedule(c);
            RadioAlarm.rearmSnooze(c);
            Reminders.rearm(c);
            RadioWidget.refresh(c);
        }
    }
}
