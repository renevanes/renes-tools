package nl.rene.tools;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.util.SizeF;
import android.widget.RemoteViews;

/**
 * De speler-widget (radio en podcasts; de naam is gebleven zodat een geplaatste radio-widget vanzelf meegaat).
 * Groeit mee: klein = hoes + afspelen + volgende; breed = met titel en vorige; groot = grote hoes, voortgang en
 * alle knoppen. De achtergrond krijgt de kleur van de hoes (Android 12+).
 */
public class RadioWidget extends AppWidgetProvider {

    // goAsync: de app mag pas weer "slapen" als de widget getekend is (bijv. direct na een update van de app)
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) { refresh(c, goAsync()); }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle o) { refresh(c, goAsync()); }

    /** Eén tekenthread: hoesjes laden mag de hoofdthread niet ophouden. */
    private static final java.util.concurrent.ExecutorService EX =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "player-widget"));
    private static final java.util.concurrent.atomic.AtomicBoolean QUEUED = new java.util.concurrent.atomic.AtomicBoolean();

    static void refresh(Context c) { refresh(c, null); }

    static void refresh(Context c, final android.content.BroadcastReceiver.PendingResult done) {
        final Context app = c.getApplicationContext();
        try { HomeWidgets.refresh(app, HomeWidgets.Overview.class); } catch (Exception ignored) { }
        if (done == null && !QUEUED.compareAndSet(false, true)) return; // er staat al een ronde klaar
        EX.execute(() -> {
            if (done == null) QUEUED.set(false);
            try {
                AppWidgetManager m = AppWidgetManager.getInstance(app);
                int[] ids = m.getAppWidgetIds(new ComponentName(app, RadioWidget.class));
                if (ids == null || ids.length == 0) return;
                Player.Now n = Player.now(app);
                // Alleen wat al bewaard is (geen netwerk: anders wacht ▶/❚❚ op een trage server); de rest komt zo
                Bitmap art = n == null ? null : Art.smallCached(app, n.art, 160);
                int color = n == null ? -1 : Art.colorCached(app, n.art);
                if (n != null && art == null) Art.fetch(app, n.art, () -> refresh(app));
                if (color < 0) {
                    int nc = nameColor(n == null ? "" : n.src + "|" + (n.station != null ? n.station.optString("name") : n.pod != null ? n.pod.optString("title") : ""));
                    color = n == null ? ArtColor.FALLBACK : ArtColor.forBackground((nc >> 16) & 0xff, (nc >> 8) & 0xff, nc & 0xff);
                }
                RemoteViews v;
                if (Build.VERSION.SDK_INT >= 31) {
                    java.util.Map<SizeF, RemoteViews> map = new java.util.HashMap<>();
                    map.put(new SizeF(130, 40), views(app, R.layout.widget_player_s, n, art, color));
                    map.put(new SizeF(220, 40), views(app, R.layout.widget_player_m, n, art, color));
                    map.put(new SizeF(220, 165), views(app, R.layout.widget_player_l, n, art, color)); // hoes + titel + voortgang + knoppen
                    v = new RemoteViews(map);
                } else v = views(app, R.layout.widget_player_m, n, art, color);
                m.updateAppWidget(ids, v);
            } catch (Throwable ignored) { }
            finally { if (done != null) try { done.finish(); } catch (Exception ignored) { } }
        });
    }

    /** Een vaste kleur bij een naam (als er geen hoes is). */
    static int nameColor(String s) { int h = s == null ? 0 : s.hashCode(); return 0x404040 | (h & 0xBFBFBF); }

    private static PendingIntent cmd(Context c, String action, int code) {
        return PendingIntent.getBroadcast(c, code, new Intent(c, PlayerReceiver.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static RemoteViews views(Context c, int layout, Player.Now n, Bitmap art, int color) {
        RemoteViews v = new RemoteViews(c.getPackageName(), layout);
        boolean radio = n == null || Player.RADIO.equals(n.src);
        boolean large = layout == R.layout.widget_player_l, small = layout == R.layout.widget_player_s;
        if (Build.VERSION.SDK_INT >= 31) {
            v.setInt(R.id.w_root, "setBackgroundResource", R.drawable.widget_player_bg);
            v.setColorStateList(R.id.w_root, "setBackgroundTintList", android.content.res.ColorStateList.valueOf(0xFF000000 | color));
        }
        if (art != null) v.setImageViewBitmap(R.id.w_art, art);
        else v.setImageViewResource(R.id.w_art, radio ? R.drawable.ic_radio : R.drawable.ic_podcast);
        String title = n == null ? "Speler" : n.title, sub = n == null ? "Tik om radio of een podcast te kiezen" : n.sub;
        if (!small) { v.setTextViewText(R.id.w_title, title); v.setTextViewText(R.id.w_sub, sub); }
        if (large) {
            v.setTextViewText(R.id.w_kind, n == null ? "Speler" : radio ? "Radio" : "Podcast");
            int p = n != null && !radio && n.dur > 0 ? (int) Math.min(1000, n.pos * 1000 / n.dur) : 0;
            v.setProgressBar(R.id.w_prog, 1000, p, false);
            v.setViewVisibility(R.id.w_prog, n != null && !radio && n.dur > 0 ? android.view.View.VISIBLE : android.view.View.INVISIBLE);
            boolean skips = n != null && (!radio || n.shift);
            v.setViewVisibility(R.id.w_back, skips ? android.view.View.VISIBLE : android.view.View.INVISIBLE);
            v.setViewVisibility(R.id.w_fwd, skips ? android.view.View.VISIBLE : android.view.View.INVISIBLE);
            v.setContentDescription(R.id.w_back, radio ? "30 seconden terug" : "15 seconden terug");
            v.setContentDescription(R.id.w_fwd, "30 seconden vooruit");
            v.setOnClickPendingIntent(R.id.w_back, cmd(c, PlayerReceiver.BACK, 61));
            v.setOnClickPendingIntent(R.id.w_fwd, cmd(c, PlayerReceiver.FWD, 62));
        }
        boolean playing = n != null && n.playing;
        v.setImageViewResource(R.id.w_pp, playing ? R.drawable.ic_pause_dark : R.drawable.ic_play_dark);
        v.setContentDescription(R.id.w_pp, playing ? "Pauze" : "Afspelen");
        v.setContentDescription(R.id.w_next, radio ? "Volgende zender" : "Volgende aflevering");
        if (!small) v.setContentDescription(R.id.w_prev, radio ? "Vorige zender" : "Vorige aflevering");
        v.setContentDescription(R.id.w_art, "Speler openen: " + title);

        Intent open = new Intent(c, MainActivity.class).putExtra("open", n == null ? "radio" : "player")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(c, 50, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        if (n == null) v.setOnClickPendingIntent(R.id.w_pp, PendingIntent.getActivity(c, 51, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        else v.setOnClickPendingIntent(R.id.w_pp, cmd(c, PlayerReceiver.PP, 52));
        v.setOnClickPendingIntent(R.id.w_next, cmd(c, PlayerReceiver.NEXT, 53));
        if (!small) v.setOnClickPendingIntent(R.id.w_prev, cmd(c, PlayerReceiver.PREV, 54));
        return v;
    }
}
