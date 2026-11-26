package nl.rene.tools;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.widget.RemoteViews;

/** Widget met één knop: de assistent luistert. */
public class OpdrachtWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_opdracht);
        v.setOnClickPendingIntent(R.id.o_root, PendingIntent.getActivity(c, 61, Opdracht.intent(c), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        Taal.layout(c, v, new int[]{R.id.o_label}, new String[]{"Opdracht"});
        Taal.describe(c, v, new int[]{R.id.o_icon}, new String[]{"Opdracht geven"});
        m.updateAppWidget(ids, v);
    }

    /** Opnieuw tekenen (bijv. na een andere taal). */
    static void refresh(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, OpdrachtWidget.class));
            if (ids != null && ids.length > 0) new OpdrachtWidget().onUpdate(c, m, ids);
        } catch (Throwable t) { App.log(c, "TAAL", t.getClass().getSimpleName()); }
    }
}
