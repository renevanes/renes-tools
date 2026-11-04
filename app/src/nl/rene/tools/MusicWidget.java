package nl.rene.tools;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** Eén knop: opent Muziek herkennen en begint meteen te luisteren. */
public class MusicWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_music);
        Intent i = new Intent(c, MainActivity.class).putExtra("open", "music-now").putExtra("tok", App.token(c))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        v.setOnClickPendingIntent(R.id.m_root, PendingIntent.getActivity(c, 60, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        Taal.layout(c, v, new int[]{R.id.m_label}, new String[]{"Herkennen"});
        Taal.describe(c, v, new int[]{R.id.m_icon}, new String[]{"Muziek herkennen"});
        m.updateAppWidget(ids, v);
    }

    /** Opnieuw tekenen (bijv. na een andere taal). */
    static void refresh(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new android.content.ComponentName(c, MusicWidget.class));
            if (ids != null && ids.length > 0) new MusicWidget().onUpdate(c, m, ids);
        } catch (Throwable t) { App.log(c, "TAAL", String.valueOf(t)); }
    }
}
