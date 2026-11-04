package nl.rene.tools;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * Meekijken bij Facturen ophalen: dezelfde robot als op de achtergrond, maar in beeld. Handig de eerste keer, of als
 * de website iets vraagt wat de app niet weet (zelf inloggen, een code overtypen); daarna gaat de app zelf verder en
 * onthoudt de website je inlog meestal een tijd.
 */
public class FacturenActivity extends Activity {

    private final Handler h = new Handler(Looper.getMainLooper());
    private WebView wv;
    private TextView status;
    private Button knop;
    private FacturenRun run;
    private boolean klaar;

    @Override
    protected void onCreate(Bundle b) {
        setTheme(Build.VERSION.SDK_INT >= 29 ? android.R.style.Theme_DeviceDefault_DayNight : android.R.style.Theme_DeviceDefault_Light);
        super.onCreate(b);
        String id = getIntent().getStringExtra("id");
        JSONObject lev = id == null ? null : Facturen.leverancier(this, id);
        if (lev == null) { finish(); return; }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            v.setPadding(ins.getSystemWindowInsetLeft(), ins.getSystemWindowInsetTop(), ins.getSystemWindowInsetRight(), ins.getSystemWindowInsetBottom());
            return ins.consumeSystemWindowInsets();
        });
        int p = dp(12);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(p, p / 2, p / 2, p / 2);
        LinearLayout tekst = new LinearLayout(this);
        tekst.setOrientation(LinearLayout.VERTICAL);
        TextView titel = new TextView(this);
        titel.setText("Facturen · " + lev.optString("naam"));
        titel.setTypeface(Typeface.DEFAULT_BOLD);
        titel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setText("Starten…");
        status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        tekst.addView(titel);
        tekst.addView(status);
        bar.addView(tekst, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        knop = new Button(this);
        knop.setText("Stoppen");
        knop.setOnClickListener(v -> { if (klaar || run == null) finish(); else run.stoppen(); });
        bar.addView(knop);
        root.addView(bar);
        wv = new WebView(this);
        root.addView(wv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        String err = FacturenRun.begin(this, id, wv, r -> h.post(() -> afgelopen(r)));
        if (!err.isEmpty()) { Toast.makeText(this, Taal.tx(this, err), Toast.LENGTH_LONG).show(); finish(); return; }
        run = FacturenRun.LOPEND.get();
        h.post(this::bijwerken);
    }

    private void bijwerken() {
        if (klaar || run == null) return;
        status.setText(run.voortgang);
        h.postDelayed(this::bijwerken, 500);
    }

    private void afgelopen(JSONObject r) {
        if (isFinishing() || isDestroyed()) return;
        klaar = true;
        status.setText((r.optBoolean("ok") ? "✓ " : "") + r.optString("msg"));
        knop.setText("Sluiten");
        if (r.optBoolean("ok")) h.postDelayed(this::finish, 2500);
    }

    @Override
    public void onBackPressed() {
        if (!klaar && run != null) run.stoppen();
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (!klaar && run != null) run.stoppen();
        if (wv != null) {
            ViewGroup p = (ViewGroup) wv.getParent();
            if (p != null) p.removeView(wv);
            // Pas opruimen als de robot klaar is met deze WebView
            WebView w = wv;
            new Handler(Looper.getMainLooper()).postDelayed(w::destroy, 1500);
        }
        super.onDestroy();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
