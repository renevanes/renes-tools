package nl.rene.tools;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Kiezen welke notitie de widget "Lijstje" toont. Komt bij het plaatsen van de widget (en via "opnieuw instellen").
 * Met app-slot eerst de vingerafdruk, want hier staan de titels van je notities.
 */
public class NoteWidgetConfig extends Activity {

    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private String style = null;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent i = getIntent();
        if (i != null) widgetId = i.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        // Afbreken (terug) = widget niet plaatsen
        setResult(RESULT_CANCELED, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId));
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return; }
        if (Lock.wouldLock(this)) {
            // Alleen voor dit scherm bevestigen; de app zelf blijft op slot zoals hij was
            if (Lock.authBusy) { finish(); return; }
            Lock.prompt(this, "Lijstje kiezen", (ok, msg) -> runOnUiThread(() -> { if (ok) show(); else finish(); }));
        } else show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == Lock.REQ_CONFIRM) Lock.onConfirmResult(res == RESULT_OK);
    }

    private int dp(float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics())); }

    private void show() {
        if (style == null) style = NoteWidget.style(this, widgetId);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(18), dp(20), dp(12));
        TextView t = new TextView(this);
        t.setText("Welk lijstje op het startscherm?");
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        t.setPadding(0, 0, 0, dp(10));
        box.addView(t);

        // Uiterlijk
        TextView sl = new TextView(this);
        sl.setText("Uiterlijk");
        sl.setPadding(0, dp(4), 0, dp(4));
        box.addView(sl);
        LinearLayout styles = new LinearLayout(this);
        styles.setOrientation(LinearLayout.HORIZONTAL);
        String[][] opts = {{"dark", "Donker"}, {"light", "Licht"}, {"glass", "Doorzichtig"}};
        for (String[] o : opts) {
            Button sb = new Button(this);
            sb.setAllCaps(false);
            sb.setText((o[0].equals(style) ? "✓ " : "") + o[1]);
            sb.setOnClickListener(v -> { style = o[0]; show(); });
            styles.addView(sb, new LinearLayout.LayoutParams(0, -2, 1));
        }
        box.addView(styles);
        TextView ll = new TextView(this);
        ll.setText("Lijstje");
        ll.setPadding(0, dp(10), 0, dp(4));
        box.addView(ll);

        List<String[]> notes = Notes.list(this);
        if (notes.isEmpty()) {
            TextView e = new TextView(this);
            e.setText("Je hebt nog geen notities. Maak in Rene's Tools een notitie met een lijstje (bijv. boodschappen) en plaats de widget daarna opnieuw.");
            e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            box.addView(e);
            Button open = new Button(this);
            open.setText("Notities openen");
            open.setOnClickListener(v -> {
                startActivity(new Intent(this, MainActivity.class).putExtra("open", "notes").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                finish();
            });
            box.addView(open);
        } else {
            for (String[] n : notes) {
                Button bt = new Button(this);
                bt.setAllCaps(false);
                bt.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                bt.setText(n[1] + "\n" + n[2]);
                bt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                bt.setOnClickListener(v -> choose(n[0]));
                box.addView(bt, new LinearLayout.LayoutParams(-1, -2));
            }
        }
        Button cancel = new Button(this);
        cancel.setText("Annuleren");
        cancel.setOnClickListener(v -> finish());
        box.addView(cancel);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        setContentView(sv);
    }

    private void choose(String noteId) {
        NoteWidget.setNote(this, widgetId, noteId);
        NoteWidget.setStyle(this, widgetId, style);
        AppWidgetManager m = AppWidgetManager.getInstance(this);
        NoteWidget.update(this, m, widgetId);
        m.notifyAppWidgetViewDataChanged(new int[]{widgetId}, R.id.w_nlist);
        setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId));
        finish();
    }
}
