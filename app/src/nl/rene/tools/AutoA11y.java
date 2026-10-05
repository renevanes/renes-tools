package nl.rene.tools;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Toegankelijkheidsdienst van Automatiseringen: drukt knoppen in andere apps in (zoals MacroDroid/Tasker AutoInput).
 * - Afspelen: app openen en per stap de knop zoeken op tekst, beschrijving of id en erop tikken.
 * - Opnemen: tikken van de gebruiker in de gekozen app vastleggen, met een zwevende balk (Kies knop / Klaar / ✕).
 * Als er niets loopt, luistert de dienst alleen naar de eigen app (packageNames), dus hij leest dan niets van andere apps.
 */
public class AutoA11y extends AccessibilityService {

    static volatile AutoA11y inst;
    final Handler h = new Handler(Looper.getMainLooper());

    // opnemen
    String recPkg;
    JSONArray recSteps;
    long suppressUntil;
    View bar, picker;
    TextView barText;

    // afspelen
    Runner runner;

    static boolean ready() { return inst != null; }

    /** Staat de dienst aan in de Android-instellingen (ook als hij net nog niet verbonden is)? */
    static boolean enabled(Context c) {
        try {
            String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (s == null) return false;
            String full = c.getPackageName() + "/" + AutoA11y.class.getName(), shrt = c.getPackageName() + "/.AutoA11y";
            for (String p : s.split(":")) if (p.equalsIgnoreCase(full) || p.equalsIgnoreCase(shrt)) return true;
            return false;
        } catch (Exception e) { return false; }
    }

    @Override
    protected void onServiceConnected() {
        inst = this;
        watch(null);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        inst = null;
        removeOverlays();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        inst = null;
        removeOverlays();
        super.onDestroy();
    }

    @Override public void onInterrupt() { }

    /** Alleen gebeurtenissen van deze app (en de eigen app) ontvangen; null = alleen de eigen app. */
    void watch(String pkg) {
        try {
            AccessibilityServiceInfo i = getServiceInfo();
            if (i == null) return;
            i.packageNames = pkg == null ? new String[]{getPackageName()} : new String[]{pkg, getPackageName()};
            setServiceInfo(i);
        } catch (Exception ignored) { }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || recPkg == null) return;
        if (e.getEventType() != AccessibilityEvent.TYPE_VIEW_CLICKED) return;
        if (e.getPackageName() == null || !recPkg.contentEquals(e.getPackageName())) return;
        if (System.currentTimeMillis() < suppressUntil) return;
        AccessibilityNodeInfo n = e.getSource();
        JSONObject st = n == null ? null : describe(n);
        if (st == null) {
            // Geen bron: de tekst uit de gebeurtenis gebruiken
            String t = e.getText() == null || e.getText().isEmpty() ? "" : String.valueOf(e.getText().get(0));
            if (e.getContentDescription() != null && t.isEmpty()) t = e.getContentDescription().toString();
            if (t.trim().isEmpty()) return;
            try { st = new JSONObject().put("t", "tap").put("text", t.trim()); } catch (Exception ignored) { return; }
        }
        addStep(st);
    }

    // ---------- knoppen beschrijven en vinden ----------

    static String str(CharSequence s) { return s == null ? "" : s.toString().trim(); }

    /** Eerste tekst in de knop of de kinderen ervan (max. 3 niveaus diep). */
    static String innerText(AccessibilityNodeInfo n, int depth) {
        if (n == null) return "";
        String t = str(n.getText());
        if (!t.isEmpty()) return t;
        if (depth >= 3) return "";
        for (int i = 0; i < n.getChildCount(); i++) {
            String c = innerText(n.getChild(i), depth + 1);
            if (!c.isEmpty()) return c;
        }
        return "";
    }

    /** Stap uit een aangetikte knop: tekst, beschrijving en id; zonder één daarvan een tik op een plek op het scherm. */
    JSONObject describe(AccessibilityNodeInfo n) {
        try {
            String text = innerText(n, 0), desc = str(n.getContentDescription()), id = str(n.getViewIdResourceName());
            if (text.length() > 60) text = text.substring(0, 60);
            JSONObject o = new JSONObject().put("t", "tap");
            if (!text.isEmpty()) o.put("text", text);
            if (!desc.isEmpty()) o.put("desc", desc);
            if (!id.isEmpty()) o.put("id", id);
            if (text.isEmpty() && desc.isEmpty()) {
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                DisplayMetrics dm = getResources().getDisplayMetrics();
                if (r.width() <= 0 || dm.widthPixels <= 0) return id.isEmpty() ? null : o;
                // id met een plek als terugval
                o.put("x", Math.round(r.exactCenterX() * 1000f / dm.widthPixels) / 1000.0)
                 .put("y", Math.round(r.exactCenterY() * 1000f / dm.heightPixels) / 1000.0);
                if (id.isEmpty()) o.put("t", "xy");
            }
            return o;
        } catch (Exception e) { return null; }
    }

    /** Wortels van de vensters van deze app (anders het actieve venster). */
    List<AccessibilityNodeInfo> roots(String pkg) {
        ArrayList<AccessibilityNodeInfo> l = new ArrayList<>();
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null && r.getPackageName() != null && pkg.contentEquals(r.getPackageName())) l.add(r);
            }
        } catch (Exception ignored) { }
        if (l.isEmpty()) {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null && r.getPackageName() != null && pkg.contentEquals(r.getPackageName())) l.add(r);
        }
        return l;
    }

    static int score(AccessibilityNodeInfo n, JSONObject st) {
        String want = st.optString("text"), wdesc = st.optString("desc"), wid = st.optString("id");
        int s = 0;
        if (!want.isEmpty()) {
            int t = Math.max(AutoLogic.textScore(n.getText(), want), AutoLogic.textScore(n.getContentDescription(), want));
            if (t == 0) return 0;
            s += t * 10;
        } else if (!wdesc.isEmpty()) {
            int t = AutoLogic.textScore(n.getContentDescription(), wdesc);
            if (t == 0) return 0;
            s += t * 10;
        }
        if (!wid.isEmpty()) {
            boolean same = wid.equals(str(n.getViewIdResourceName()));
            if (same) s += 5;
            else if (want.isEmpty() && wdesc.isEmpty()) return 0;
        }
        if (s > 0 && n.isClickable()) s += 1;
        return s;
    }

    AccessibilityNodeInfo find(String pkg, JSONObject st) {
        AccessibilityNodeInfo best = null;
        int bestScore = 0;
        for (AccessibilityNodeInfo root : roots(pkg)) {
            ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
            q.add(root);
            int seen = 0;
            while (!q.isEmpty() && seen++ < 3000) {
                AccessibilityNodeInfo n = q.poll();
                if (n == null) continue;
                if (n.isVisibleToUser()) {
                    int s = score(n, st);
                    if (s > bestScore) { bestScore = s; best = n; }
                }
                for (int i = 0; i < n.getChildCount(); i++) { AccessibilityNodeInfo c = n.getChild(i); if (c != null) q.add(c); }
            }
        }
        return best;
    }

    /** Tikken: de knop zelf of de eerste aanklikbare ouder; lukt dat niet, dan een echte tik op het midden. */
    boolean click(AccessibilityNodeInfo n) {
        suppressUntil = System.currentTimeMillis() + 900;
        AccessibilityNodeInfo x = n;
        for (int i = 0; i < 8 && x != null; i++) {
            if (x.isClickable() && x.isEnabled() && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            x = x.getParent();
        }
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        return r.width() > 0 && tap(r.exactCenterX(), r.exactCenterY());
    }

    boolean tap(float x, float y) {
        if (Build.VERSION.SDK_INT < 24) return false;
        Path p = new Path();
        p.moveTo(Math.max(0, x), Math.max(0, y));
        GestureDescription g = new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build();
        return dispatchGesture(g, null, null);
    }

    boolean tapFraction(double fx, double fy) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return tap((float) (fx * dm.widthPixels), (float) (fy * dm.heightPixels));
    }

    /** Knoppen met tekst op het huidige scherm van de app (voor "Kies knop" bij apps die tikken niet doorgeven). */
    List<JSONObject> candidates(String pkg) {
        ArrayList<JSONObject> out = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (AccessibilityNodeInfo root : roots(pkg)) {
            ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
            q.add(root);
            int count = 0;
            while (!q.isEmpty() && count++ < 3000 && out.size() < 40) {
                AccessibilityNodeInfo n = q.poll();
                if (n == null) continue;
                for (int i = 0; i < n.getChildCount(); i++) { AccessibilityNodeInfo c = n.getChild(i); if (c != null) q.add(c); }
                if (!n.isVisibleToUser() || !(n.isClickable() || clickableParent(n))) continue;
                String t = str(n.getText()), d = str(n.getContentDescription());
                String label = !t.isEmpty() ? t : d;
                if (label.isEmpty() || label.length() > 60 || !seen.add(label.toLowerCase())) continue;
                try {
                    JSONObject o = new JSONObject().put("t", "tap");
                    if (!t.isEmpty()) o.put("text", t); else o.put("desc", d);
                    String id = str(n.getViewIdResourceName());
                    if (!id.isEmpty()) o.put("id", id);
                    out.add(o);
                } catch (Exception ignored) { }
            }
        }
        return out;
    }

    static boolean clickableParent(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo p = n.getParent();
        for (int i = 0; i < 3 && p != null; i++) { if (p.isClickable()) return true; p = p.getParent(); }
        return false;
    }

    static String label(JSONObject st) {
        if ("wait".equals(st.optString("t"))) return "Wachten " + Math.round(st.optInt("ms", 1000) / 100.0) / 10.0 + " s";
        if ("xy".equals(st.optString("t"))) return "Tik op het scherm (" + Math.round(st.optDouble("x") * 100) + "%, " + Math.round(st.optDouble("y") * 100) + "%)";
        String t = st.optString("text");
        if (t.isEmpty()) t = st.optString("desc");
        if (t.isEmpty()) t = st.optString("id").replaceAll(".*/", "");
        return "Tik op '" + t + "'";
    }

    // ---------- opnemen ----------

    /** Opnemen starten: app openen met een zwevende balk. Geeft "" of een foutmelding. */
    static String record(Context c, String pkg) {
        AutoA11y s = inst;
        if (s == null) return "Zet eerst de toegankelijkheid voor Rene's Tools aan";
        Intent li = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (li == null) return "Deze app kan niet geopend worden";
        s.h.post(() -> s.startRecord(pkg, li));
        return "";
    }

    void startRecord(String pkg, Intent li) {
        stopRun("");
        removeOverlays();
        recPkg = pkg;
        recSteps = new JSONArray();
        watch(pkg);
        try { startActivity(li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
        h.postDelayed(this::showBar, 600);
    }

    void addStep(JSONObject st) {
        if (recSteps == null) return;
        // dezelfde knop twee keer achter elkaar (dubbele gebeurtenis) maar één keer
        JSONObject last = recSteps.length() > 0 ? recSteps.optJSONObject(recSteps.length() - 1) : null;
        if (last != null && last.toString().equals(st.toString()) && System.currentTimeMillis() - lastAdd < 700) return;
        lastAdd = System.currentTimeMillis();
        recSteps.put(st);
        updateBar();
    }
    long lastAdd;

    void finishRecord(boolean keep) {
        String pkg = recPkg;
        JSONArray steps = recSteps;
        recPkg = null;
        recSteps = null;
        removeOverlays();
        watch(null);
        try {
            if (keep && pkg != null && steps != null)
                Auto.prefs(this).edit().putString("rec", new JSONObject().put("pkg", pkg).put("steps", steps).put("t", System.currentTimeMillis()).toString()).apply();
        } catch (Exception ignored) { }
        Intent i = new Intent(this, MainActivity.class).putExtra("open", keep ? "auto-rec" : "auto")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try { startActivity(i); } catch (Exception ignored) { }
    }

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    Button btn(String text, int bg, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(dp(18));
        b.setBackground(d);
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setMinHeight(dp(40));
        b.setMinimumHeight(dp(40));
        b.setOnClickListener(l);
        return b;
    }

    WindowManager.LayoutParams lp(int h, int gravity, int y) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = gravity;
        p.y = y;
        return p;
    }

    /** Zwevende balk onderin: "● Opnemen: N knoppen" + Kies knop / Klaar / ✕ (of tijdens afspelen alleen Stoppen). */
    void showBar() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null || bar != null) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(10), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF01E2A3A);
        bg.setCornerRadius(dp(18));
        box.setBackground(bg);
        barText = new TextView(this);
        barText.setTextColor(Color.WHITE);
        barText.setTextSize(15);
        box.addView(barText);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, 0);
        LinearLayout.LayoutParams w = new LinearLayout.LayoutParams(0, dp(42), 1f);
        w.setMargins(dp(3), 0, dp(3), 0);
        if (recPkg != null) {
            row.addView(btn("Kies knop", 0xFF2471A3, v -> showPicker()), w);
            row.addView(btn("Klaar", 0xFF1D8A4E, v -> finishRecord(true)), w);
            row.addView(btn("✕", 0xFF7F8C8D, v -> finishRecord(false)), new LinearLayout.LayoutParams(dp(52), dp(42)));
        } else {
            row.addView(btn("Stoppen", 0xFFC0392B, v -> stopRun("Gestopt")), w);
        }
        box.addView(row);
        LinearLayout outer = new LinearLayout(this);
        outer.setPadding(dp(12), 0, dp(12), 0);
        outer.addView(box, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        bar = outer;
        updateBar();
        // Bovenin, want de belangrijke knoppen van apps zitten meestal onderin. Tik op de tekst = balk verplaatsen.
        barText.setOnClickListener(v -> moveBar());
        try { wm.addView(bar, lp(WindowManager.LayoutParams.WRAP_CONTENT, barTop ? Gravity.TOP : Gravity.BOTTOM, dp(barTop ? 40 : 72))); } catch (Exception e) { bar = null; }
    }

    boolean barTop = true;

    void moveBar() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null || bar == null) return;
        barTop = !barTop;
        try { wm.updateViewLayout(bar, lp(WindowManager.LayoutParams.WRAP_CONTENT, barTop ? Gravity.TOP : Gravity.BOTTOM, dp(barTop ? 40 : 72))); } catch (Exception ignored) { }
    }

    void updateBar() {
        if (barText == null) return;
        if (recPkg != null) {
            int n = recSteps == null ? 0 : recSteps.length();
            String last = n > 0 ? "\nLaatste: " + label(recSteps.optJSONObject(n - 1)) : "\nTik zoals je dat altijd doet. Werkt een knop niet? Gebruik 'Kies knop'. (Balk in de weg? Tik op deze tekst.)";
            barText.setText("● Opnemen: " + n + (n == 1 ? " knop" : " knoppen") + last);
        } else if (runner != null) {
            barText.setText("Rene's Tools tikt voor je: " + runner.status);
        }
    }

    /** Lijst met knoppen op het huidige scherm; kiezen = vastleggen en meteen tikken. */
    void showPicker() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null || recPkg == null) return;
        removePicker();
        List<JSONObject> c = candidates(recPkg);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(12), dp(12), dp(12));
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        t.setText(c.isEmpty() ? "Geen knoppen met tekst gevonden op dit scherm." : "Welke knop? Hij wordt opgenomen en ingedrukt.");
        list.addView(t);
        for (JSONObject st : c) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
            p.setMargins(0, dp(6), 0, 0);
            String lb = st.optString("text", st.optString("desc"));
            list.addView(btn(lb, 0xFF2C3E50, v -> pick(st)), p);
        }
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        p.setMargins(0, dp(10), 0, 0);
        list.addView(btn("Annuleren", 0xFF7F8C8D, v -> removePicker()), p);
        ScrollView sv = new ScrollView(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF5141C28);
        bg.setCornerRadius(dp(18));
        sv.setBackground(bg);
        sv.addView(list);
        LinearLayout outer = new LinearLayout(this);
        outer.setPadding(dp(12), 0, dp(12), 0);
        outer.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        picker = outer;
        int hgt = Math.min(getResources().getDisplayMetrics().heightPixels / 2, dp(110 + 50 * c.size()));
        try { wm.addView(picker, lp(hgt, barTop ? Gravity.TOP : Gravity.BOTTOM, dp(barTop ? 170 : 200))); } catch (Exception e) { picker = null; }
    }

    void pick(JSONObject st) {
        removePicker();
        String pkg = recPkg;
        if (pkg == null) return;
        AccessibilityNodeInfo n = find(pkg, st);
        addStep(st);
        if (n != null) click(n);
    }

    void removePicker() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (picker != null && wm != null) try { wm.removeView(picker); } catch (Exception ignored) { }
        picker = null;
    }

    void removeOverlays() {
        removePicker();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (bar != null && wm != null) try { wm.removeView(bar); } catch (Exception ignored) { }
        bar = null;
        barText = null;
    }

    // ---------- afspelen ----------

    static void run(Context c, JSONObject rule) {
        AutoA11y s = inst;
        if (s == null) return;
        s.h.post(() -> s.startRun(rule));
    }

    void startRun(JSONObject rule) {
        if (recPkg != null) return; // niet tijdens opnemen
        stopRun("");
        JSONObject app = rule.optJSONObject("app");
        String pkg = app == null ? "" : app.optString("p");
        Intent li = pkg.isEmpty() ? null : getPackageManager().getLaunchIntentForPackage(pkg);
        if (li == null) { Auto.result(this, rule, false, "App niet gevonden: " + (app == null ? "?" : app.optString("n"))); return; }
        runner = new Runner(this, rule, pkg);
        watch(pkg);
        try { startActivity(li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)); }
        catch (Exception e) { runner = null; Auto.result(this, rule, false, "App openen lukt niet"); return; }
        showBar();
        h.postDelayed(runner::tick, 1500);
    }

    void stopRun(String why) {
        Runner r = runner;
        runner = null;
        if (r == null) return;
        r.stopped = true;
        removeOverlays();
        watch(recPkg);
        if (!why.isEmpty()) Auto.result(this, r.rule, false, why + " na " + r.idx + " van " + r.steps.length() + " stappen");
    }

    /** Loopt de stappen af: per knop max. 12 s wachten tot hij verschijnt, dan tikken en even wachten. */
    static final class Runner {
        final AutoA11y s; final JSONObject rule; final JSONArray steps; final String pkg;
        int idx; long stepStart = System.currentTimeMillis(); final long started = System.currentTimeMillis();
        boolean stopped; String status = "app openen";

        Runner(AutoA11y s, JSONObject rule, String pkg) {
            this.s = s; this.rule = rule; this.pkg = pkg;
            JSONArray st = rule.optJSONArray("steps");
            this.steps = st == null ? new JSONArray() : st;
        }

        void next(long delay) {
            idx++;
            stepStart = System.currentTimeMillis() + delay;
            s.h.postDelayed(this::tick, delay);
        }

        void tick() {
            if (stopped || s.runner != this) return;
            if (idx >= steps.length()) { done(true, steps.length() == 0 ? "App geopend" : "Gelukt: " + steps.length() + (steps.length() == 1 ? " knop" : " knoppen") + " ingedrukt"); return; }
            if (System.currentTimeMillis() - started > 120_000L) { done(false, "Duurde te lang, gestopt bij stap " + (idx + 1)); return; }
            JSONObject st = steps.optJSONObject(idx);
            if (st == null) { next(0); return; }
            status = "stap " + (idx + 1) + " van " + steps.length() + ": " + label(st);
            s.updateBar();
            String t = st.optString("t", "tap");
            if ("wait".equals(t)) { next(Math.max(100, Math.min(30_000, st.optInt("ms", 1000)))); return; }
            if ("xy".equals(t)) {
                if (System.currentTimeMillis() - stepStart < 1200) { s.h.postDelayed(this::tick, 400); return; } // scherm laten opbouwen
                s.tapFraction(st.optDouble("x"), st.optDouble("y"));
                next(1200);
                return;
            }
            AccessibilityNodeInfo n = s.find(pkg, st);
            if (n != null && s.click(n)) { next(1100); return; }
            if (System.currentTimeMillis() - stepStart > 12_000L) {
                // Terugval: had de knop een opgenomen plek, tik daar dan.
                if (st.has("x") && st.has("y")) { s.tapFraction(st.optDouble("x"), st.optDouble("y")); next(1200); return; }
                done(false, "Knop niet gevonden bij stap " + (idx + 1) + ": " + label(st) + ". Maak het zelf af in de app.");
                return;
            }
            s.h.postDelayed(this::tick, 400);
        }

        void done(boolean ok, String msg) {
            if (s.runner != this) return;
            s.runner = null;
            stopped = true;
            s.removeOverlays();
            s.watch(null);
            Auto.result(s, rule, ok, msg);
        }
    }
}
