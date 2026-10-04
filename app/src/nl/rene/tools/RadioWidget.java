package nl.rene.tools;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import org.json.JSONObject;

/** Radio-widget: zender, wat er nu speelt, afspelen/pauzeren en stoppen. */
public class RadioWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) { refresh(c); }

    static void refresh(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, RadioWidget.class));
            if (ids == null || ids.length == 0) return;
            m.updateAppWidget(ids, views(c));
        } catch (Exception ignored) { }
    }

    static RemoteViews views(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_radio);
        String st = RadioService.station;
        if (st == null) st = Radio.prefs(c).getString("last", null);
        String name = "Radio", sub;
        try { if (st != null) name = new JSONObject(st).optString("name", "Radio"); } catch (Exception ignored) { }
        String status = RadioService.status;
        boolean playing = "playing".equals(status) || "connecting".equals(status);
        if (st == null) sub = "Tik om een zender te kiezen";
        else if ("connecting".equals(status)) sub = "Verbinden…";
        else if (playing) sub = RadioService.title.isEmpty() ? "Live" : RadioService.title;
        else if ("error".equals(status)) sub = "Niet te bereiken";
        else sub = "Tik op ▶ om te luisteren";
        v.setTextViewText(R.id.w_name, name);
        v.setTextViewText(R.id.w_title, sub);
        v.setImageViewResource(R.id.w_pp, playing ? R.drawable.ic_pause : R.drawable.ic_play);

        Intent open = new Intent(c, MainActivity.class).putExtra("open", "radio").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(c, 50, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        if (st == null) {
            v.setOnClickPendingIntent(R.id.w_pp, PendingIntent.getActivity(c, 51, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            v.setOnClickPendingIntent(R.id.w_pp, PendingIntent.getBroadcast(c, 52,
                    new Intent(c, AlarmReceiver.class).setAction(AlarmReceiver.WIDGET_PP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        }
        v.setOnClickPendingIntent(R.id.w_stop, PendingIntent.getBroadcast(c, 54,
                new Intent(c, AlarmReceiver.class).setAction(AlarmReceiver.WIDGET_STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return v;
    }
}
