package nl.rene.tools;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Widget "Lijstje": één notitie (bijv. de boodschappenlijst) op het startscherm, met de open items bovenaan.
 * Tik op een item = afstrepen (of terugzetten); tik op de titel = de notitie openen in Rene's Tools.
 * Welke notitie: per widget gekozen in NoteWidgetConfig.
 */
public class NoteWidget extends AppWidgetProvider {

    static final String TOGGLE = "nl.rene.tools.NOTE_TOGGLE";
    static final String EXTRA_NOTE = "note", EXTRA_ITEM = "item";

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("notewidget", Context.MODE_PRIVATE); }

    static String noteFor(Context c, int id) { return prefs(c).getString("w" + id, null); }

    static void setNote(Context c, int id, String noteId) { prefs(c).edit().putString("w" + id, noteId).apply(); }

    /** Uiterlijk per widget: "dark" (standaard), "light" of "glass" (doorzichtig). */
    static String style(Context c, int id) { return prefs(c).getString("s" + id, "dark"); }
    static void setStyle(Context c, int id, String s) { prefs(c).edit().putString("s" + id, s).apply(); }
    static boolean light(String s) { return "light".equals(s); }
    static int bg(String s) { return "light".equals(s) ? R.drawable.widget_bg_light : "glass".equals(s) ? R.drawable.widget_bg_glass : R.drawable.widget_bg; }

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) update(c, m, id);
    }

    @Override
    public void onDeleted(Context c, int[] ids) {
        SharedPreferences.Editor e = prefs(c).edit();
        for (int id : ids) { e.remove("w" + id); e.remove("s" + id); }
        e.apply();
    }

    @Override
    public void onReceive(Context c, Intent i) {
        if (TOGGLE.equals(i.getAction())) {
            String note = i.getStringExtra(EXTRA_NOTE), item = i.getStringExtra(EXTRA_ITEM);
            if (note != null && item != null) {
                final PendingResult pr = goAsync();
                final Context app = c.getApplicationContext();
                new Thread(() -> {
                    try {
                        // Notes.save werkt de widgets zelf bij; een geopende app leest het opnieuw in
                        if (Notes.toggleItem(app, note, item)) { MainActivity a = MainActivity.live; if (a != null) a.js("onNotesMaybeChanged", ""); }
                    } finally { pr.finish(); }
                }, "note-widget").start();
            }
            return;
        }
        super.onReceive(c, i);
    }

    /** Alle lijstje-widgets bijwerken (na opslaan in de app of afstrepen in een widget). */
    static void refresh(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, NoteWidget.class));
            if (ids == null || ids.length == 0) return;
            for (int id : ids) update(c, m, id);
            m.notifyAppWidgetViewDataChanged(ids, R.id.w_nlist);
        } catch (Exception ignored) { }
    }

    static void update(Context c, AppWidgetManager m, int id) {
        try { m.updateAppWidget(id, views(c, id)); } catch (Exception ignored) { }
    }

    static RemoteViews views(Context c, int id) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_note);
        String st = style(c, id);
        v.setInt(R.id.w_nroot, "setBackgroundResource", bg(st));
        int fg = light(st) ? 0xFF0F172A : 0xFFFFFFFF, fg2 = light(st) ? 0xCC0F172A : 0xCCFFFFFF;
        v.setTextColor(R.id.w_ntitle, fg); v.setTextColor(R.id.w_ncount, fg2); v.setTextColor(R.id.w_nempty, fg2); v.setTextColor(R.id.w_nadd, fg);
        String noteId = noteFor(c, id);
        JSONObject n = noteId == null ? null : Notes.note(c, noteId);
        int imm = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        if (n == null) {
            // Nog niets gekozen, of de notitie is verwijderd: tik om (opnieuw) te kiezen
            v.setTextViewText(R.id.w_ntitle, noteId == null ? "Lijstje kiezen" : "Notitie bestaat niet meer");
            v.setTextViewText(R.id.w_ncount, "");
            v.setTextViewText(R.id.w_nempty, "Tik om een notitie te kiezen");
            Intent cfg = new Intent(c, NoteWidgetConfig.class).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            cfg.setData(Uri.parse("renestools://notewidget/" + id));
            PendingIntent pi = PendingIntent.getActivity(c, 7000 + id, cfg, imm);
            v.setOnClickPendingIntent(R.id.w_nhead, pi);
            v.setOnClickPendingIntent(R.id.w_nempty, pi);
            v.setViewVisibility(R.id.w_nlist, android.view.View.GONE);
            v.setViewVisibility(R.id.w_nadd, android.view.View.GONE);
            return v;
        }
        v.setViewVisibility(R.id.w_nlist, android.view.View.VISIBLE);
        v.setViewVisibility(R.id.w_nadd, android.view.View.VISIBLE);
        // + : snel iets toevoegen zonder de app te openen
        Intent add = new Intent(c, NoteAddActivity.class).putExtra(EXTRA_NOTE, noteId).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK).setData(Uri.parse("renestools://noteadd/" + id));
        v.setOnClickPendingIntent(R.id.w_nadd, PendingIntent.getActivity(c, 8500 + id, add, imm));
        String title = n.optString("title").trim();
        v.setTextViewText(R.id.w_ntitle, title.isEmpty() ? "Notitie" : title);
        JSONArray items = n.optJSONArray("items");
        int open = 0, total = items == null ? 0 : items.length();
        if (items != null) for (int j = 0; j < items.length(); j++) { JSONObject it = items.optJSONObject(j); if (it != null && !it.optBoolean("done")) open++; }
        v.setTextViewText(R.id.w_ncount, total == 0 ? "" : open + " open");
        v.setTextViewText(R.id.w_nempty, total == 0 ? "Nog geen items. Tik op de titel om toe te voegen." : "Alles afgestreept ✓");

        // Titel: de notitie openen in de app (het app-slot geldt daar gewoon)
        Intent openNote = new Intent(c, MainActivity.class).putExtra("open", "note:" + noteId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        openNote.setData(Uri.parse("renestools://note/" + noteId));
        v.setOnClickPendingIntent(R.id.w_nhead, PendingIntent.getActivity(c, 8000 + id, openNote, imm));
        v.setOnClickPendingIntent(R.id.w_nempty, PendingIntent.getActivity(c, 8000 + id, openNote, imm));

        // De items komen uit NoteWidgetService; elke widget een eigen adres, anders delen ze dezelfde lijst
        Intent svc = new Intent(c, NoteWidgetService.class).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        svc.setData(Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME)));
        setAdapter(v, svc);
        v.setEmptyView(R.id.w_nlist, R.id.w_nempty);

        // Tik op een item: afstrepen (de items vullen notitie en item zelf in)
        Intent toggle = new Intent(c, NoteWidget.class).setAction(TOGGLE);
        int mut = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        v.setPendingIntentTemplate(R.id.w_nlist, PendingIntent.getBroadcast(c, 9000 + id, toggle, mut | PendingIntent.FLAG_UPDATE_CURRENT));
        return v;
    }

    @SuppressWarnings("deprecation")
    private static void setAdapter(RemoteViews v, Intent svc) { v.setRemoteAdapter(R.id.w_nlist, svc); }
}
