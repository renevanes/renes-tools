package nl.rene.tools;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StrikethroughSpan;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Levert de items van het lijstje aan de widget: eerst de open items, daarna (lichter, doorgestreept) de afgestreepte. */
public class NoteWidgetService extends RemoteViewsService {

    static final int MAX = 80;

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent i) {
        return new Factory(getApplicationContext(), i.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1));
    }

    static final class Factory implements RemoteViewsFactory {
        private final Context c;
        private final int widgetId;
        private String noteId;
        private final List<String[]> rows = new ArrayList<>(); // {itemId, tekst, "1" als afgestreept}

        Factory(Context ctx, int id) { c = ctx; widgetId = id; }

        @Override public void onCreate() { }

        @Override
        public void onDataSetChanged() {
            rows.clear();
            noteId = NoteWidget.noteFor(c, widgetId);
            JSONObject n = noteId == null ? null : Notes.note(c, noteId);
            JSONArray items = n == null ? null : n.optJSONArray("items");
            if (items == null) return;
            List<String[]> done = new ArrayList<>();
            for (int j = 0; j < items.length(); j++) {
                JSONObject it = items.optJSONObject(j);
                if (it == null || it.optString("id").isEmpty()) continue;
                String[] r = {it.optString("id"), it.optString("text"), it.optBoolean("done") ? "1" : ""};
                if (r[2].isEmpty()) rows.add(r); else done.add(r);
            }
            rows.addAll(done);
            while (rows.size() > MAX) rows.remove(rows.size() - 1);
        }

        @Override public void onDestroy() { rows.clear(); }

        @Override public int getCount() { return rows.size(); }

        @Override
        public RemoteViews getViewAt(int pos) {
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_note_item);
            if (pos < 0 || pos >= rows.size()) return v;
            String[] r = rows.get(pos);
            boolean done = !r[2].isEmpty();
            v.setTextViewText(R.id.w_icheck, done ? "☑" : "☐");
            if (done) {
                SpannableString s = new SpannableString(r[1]);
                s.setSpan(new StrikethroughSpan(), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                v.setTextViewText(R.id.w_itext, s);
                v.setTextColor(R.id.w_itext, 0x99FFFFFF);
                v.setTextColor(R.id.w_icheck, 0x99FFFFFF);
            } else {
                v.setTextViewText(R.id.w_itext, r[1]);
                v.setTextColor(R.id.w_itext, 0xFFFFFFFF);
                v.setTextColor(R.id.w_icheck, 0xFFFFFFFF);
            }
            v.setContentDescription(R.id.w_irow, (done ? "Afgestreept: " : "") + r[1]);
            v.setOnClickFillInIntent(R.id.w_irow, new Intent().putExtra(NoteWidget.EXTRA_NOTE, noteId).putExtra(NoteWidget.EXTRA_ITEM, r[0]));
            return v;
        }

        @Override public RemoteViews getLoadingView() { return null; }
        @Override public int getViewTypeCount() { return 1; }
        @Override public long getItemId(int pos) { return pos < rows.size() ? rows.get(pos)[0].hashCode() : pos; }
        @Override public boolean hasStableIds() { return true; }
    }
}
