package nl.rene.tools;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Vanuit de widget "Lijstje" (+): snel iets toevoegen, zonder de app te openen. Elke regel wordt een item. */
public class NoteAddActivity extends Activity {

    private String noteId;
    private EditText in;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        noteId = getIntent() == null ? null : getIntent().getStringExtra(NoteWidget.EXTRA_NOTE);
        JSONObject n = noteId == null ? null : Notes.note(this, noteId);
        if (n == null) { Toast.makeText(this, "Deze notitie bestaat niet meer", Toast.LENGTH_SHORT).show(); finish(); return; }
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Math.round(20 * dp);
        box.setPadding(p, p, p, Math.round(12 * dp));
        TextView t = new TextView(this);
        String title = n.optString("title").trim();
        t.setText("Toevoegen aan " + (title.isEmpty() || Lock.active(this) ? "het lijstje" : title));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        box.addView(t);
        in = new EditText(this);
        in.setHint("Bijv. melk (elke regel wordt een item)");
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        in.setMinLines(2);
        in.setImeOptions(EditorInfo.IME_ACTION_DONE);
        box.addView(in, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button cancel = new Button(this), ok = new Button(this);
        cancel.setText("Annuleren"); ok.setText("Toevoegen");
        cancel.setOnClickListener(v -> finish());
        ok.setOnClickListener(v -> add(true));
        row.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(ok, new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(row);
        setContentView(box);
        getWindow().setLayout(Math.round(getResources().getDisplayMetrics().widthPixels * .9f), WindowManager.LayoutParams.WRAP_CONTENT);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        in.requestFocus();
    }

    private void add(boolean close) {
        List<String> items = new ArrayList<>();
        for (String s : in.getText().toString().split("\n")) { s = s.trim(); if (!s.isEmpty()) items.add(s.length() > 500 ? s.substring(0, 500) : s); }
        if (items.isEmpty()) { finish(); return; }
        if (Notes.addItems(this, noteId, items)) {
            MainActivity a = MainActivity.live;
            if (a != null) a.js("onNotesMaybeChanged", "");
            Toast.makeText(this, items.size() == 1 ? "Toegevoegd" : items.size() + " items toegevoegd", Toast.LENGTH_SHORT).show();
        } else Toast.makeText(this, "Toevoegen lukt niet", Toast.LENGTH_SHORT).show();
        if (close) finish();
    }
}
