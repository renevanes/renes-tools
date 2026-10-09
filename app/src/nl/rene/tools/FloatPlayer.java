package nl.rene.tools;

import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Zwevende speler boven andere apps (alleen als je die zelf aanzet en Android het toestaat). Klein = een rond
 * hoesje dat je kunt verslepen (tik = groot, naar beneden slepen of lang drukken = uit); groot = hoes, titel,
 * voortgang en knoppen. Alleen zichtbaar als er iets speelt of net gepauzeerd is (de service draait nog), en niet
 * zolang er een scherm van de app zelf open is (daar is de mini-speler). Alles hier draait op de hoofdthread.
 */
final class FloatPlayer {
    private FloatPlayer() { }

    private static final Handler H = new Handler(Looper.getMainLooper());

    static volatile boolean appVisible;

    private static Context app;
    private static WindowManager wm;
    private static WindowManager.LayoutParams lp;
    private static FrameLayout root;
    private static View small, big;
    private static ImageView smallArt, bigArt;
    private static TextView title, sub, kind;
    private static ImageButton pp, smallPp, back, fwd, prev, next;
    private static ProgressBar prog;
    private static String artKey = "", sig = "";
    private static int color = ArtColor.FALLBACK;
    private static boolean shown, callbacks;

    static boolean enabled(Context c) { return Player.prefs(c).getBoolean("float", false); }

    static boolean allowed(Context c) { return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c); }

    static void setEnabled(Context c, boolean on) {
        Player.prefs(c).edit().putBoolean("float", on).apply();
        refresh(c);
    }

    /** Het systeemscherm om "Weergeven over andere apps" toe te staan. */
    static Intent permissionIntent(Context c) {
        return new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + c.getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Wanneer hij in beeld hoort: iets speelt, of het is gepauzeerd terwijl de service nog draait. */
    private static boolean wanted(Player.Now n) {
        return n != null && (n.playing || (n.live && n.active && !"error".equals(n.status)));
    }

    /** Tonen, bijwerken of weghalen. Mag van elke thread. */
    static void refresh(Context c) {
        final Context a = c.getApplicationContext();
        if (Looper.myLooper() != Looper.getMainLooper()) { H.post(() -> refresh(a)); return; }
        app = a;
        Player.Now n = enabled(a) && !appVisible && App.unlocked(a) && allowed(a) ? Player.now(a) : null;
        if (!wanted(n)) { hide(); return; }
        try { show(a); update(n); } catch (Exception e) { hide(); }
    }

    /** Een scherm van de app kwam in beeld of verdween (App.Visible). Bij weggaan even wachten: geen geflikker bij wisselen. */
    static void setAppVisible(Context c, boolean v) {
        appVisible = v;
        final Context a = c.getApplicationContext();
        H.removeCallbacks(LATER);
        if (v) refresh(a);
        else { app = a; H.postDelayed(LATER, 600); }
    }

    private static final Runnable LATER = () -> { if (app != null) refresh(app); };

    // ---------- opbouw ----------

    private static int dp(Context c, float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics())); }

    private static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(radiusPx); return g;
    }

    private static ImageButton button(Context c, int icon, String label, int sizeDp, boolean white, Runnable click) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(icon);
        b.setContentDescription(label);
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int p = dp(c, white ? 12 : 10);
        b.setPadding(p, p, p, p);
        GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(white ? 0xFFFFFFFF : 0x00000000);
        b.setBackground(g);
        b.setOnClickListener(v -> click.run());
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return b;
    }

    /** Knoppen: op een eigen thread (volgende/vorige leest bestanden), nooit achter het laden van een hoesje. */
    private static void cmd(Runnable r) {
        new Thread(() -> { try { r.run(); } catch (Throwable ignored) { } Player.changed(app); }, "float-button").start();
    }

    private static void show(Context c) {
        if (shown) return;
        wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        int sw = c.getResources().getDisplayMetrics().widthPixels, sh = c.getResources().getDisplayMetrics().heightPixels;
        lp.x = Player.prefs(c).getInt("floatX", sw - dp(c, 80));
        lp.y = Player.prefs(c).getInt("floatY", sh / 3);
        root = new FrameLayout(c);
        root.setElevation(dp(c, 8));
        artKey = ""; sig = "";

        // Klein: rond hoesje met een afspeelknop
        FrameLayout s = new FrameLayout(c);
        smallArt = new ImageView(c);
        smallArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        smallArt.setBackground(round(0xFF000000 | color, dp(c, 34)));
        smallArt.setClipToOutline(true);
        smallArt.setContentDescription("Zwevende speler. Dubbeltik om groot te maken; dubbeltik en vasthouden om te sluiten.");
        smallArt.setOnClickListener(v -> setBig(true));
        smallArt.setOnLongClickListener(v -> { closeByUser(); return true; });
        s.addView(smallArt, new FrameLayout.LayoutParams(dp(c, 68), dp(c, 68)));
        smallPp = new ImageButton(c);
        smallPp.setImageResource(R.drawable.ic_play_dark);
        smallPp.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        smallPp.setPadding(dp(c, 7), dp(c, 7), dp(c, 7), dp(c, 7));
        GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(0xFFFFFFFF); smallPp.setBackground(g);
        smallPp.setOnClickListener(v -> cmd(() -> Player.playPause(app)));
        s.addView(smallPp, new FrameLayout.LayoutParams(dp(c, 34), dp(c, 34), Gravity.BOTTOM | Gravity.END));
        s.setLayoutParams(new FrameLayout.LayoutParams(dp(c, 80), dp(c, 80)));
        small = s;

        // Groot: kaart
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(dp(c, 12), dp(c, 10), dp(c, 8), dp(c, 8));
        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        bigArt = new ImageView(c);
        bigArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bigArt.setBackground(round(0x33FFFFFF, dp(c, 8)));
        bigArt.setClipToOutline(true);
        bigArt.setContentDescription("Speler openen in de app");
        bigArt.setOnClickListener(v -> openApp());
        top.addView(bigArt, new LinearLayout.LayoutParams(dp(c, 56), dp(c, 56)));
        LinearLayout txt = new LinearLayout(c);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setPadding(dp(c, 10), 0, dp(c, 4), 0);
        txt.setOnClickListener(v -> openApp());
        kind = new TextView(c); kind.setTextColor(0xB3FFFFFF); kind.setTextSize(10); kind.setAllCaps(true); kind.setLetterSpacing(0.12f); kind.setSingleLine(true);
        title = new TextView(c); title.setTextColor(0xFFFFFFFF); title.setTextSize(15); title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setMaxLines(2); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        sub = new TextView(c); sub.setTextColor(0xD9FFFFFF); sub.setTextSize(12); sub.setSingleLine(true); sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        txt.addView(kind); txt.addView(title); txt.addView(sub);
        top.addView(txt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        top.addView(button(c, R.drawable.ic_collapse, "Klein maken", 44, false, () -> setBig(false)));
        top.addView(button(c, R.drawable.ic_close, "Zwevende speler sluiten", 44, false, FloatPlayer::closeByUser));
        b.addView(top);
        prog = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        prog.setMax(1000);
        prog.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        prog.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x55FFFFFF));
        prog.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 4));
        plp.topMargin = dp(c, 8); plp.rightMargin = dp(c, 4);
        b.addView(prog, plp);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        back = button(c, R.drawable.ic_back, "Terug", 44, false, () -> cmd(() -> Player.skip(app, -1)));
        prev = button(c, R.drawable.ic_prev, "Vorige", 44, false, () -> cmd(() -> toastIf(Player.step(app, null, -1))));
        pp = button(c, R.drawable.ic_play_dark, "Afspelen", 52, true, () -> cmd(() -> Player.playPause(app)));
        next = button(c, R.drawable.ic_next, "Volgende", 44, false, () -> cmd(() -> toastIf(Player.step(app, null, 1))));
        fwd = button(c, R.drawable.ic_fwd, "Vooruit", 44, false, () -> cmd(() -> Player.skip(app, 1)));
        for (View v : new View[]{back, prev, pp, next, fwd}) {
            LinearLayout.LayoutParams l = (LinearLayout.LayoutParams) v.getLayoutParams();
            l.leftMargin = dp(c, 4); l.rightMargin = dp(c, 4);
            row.addView(v, l);
        }
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(c, 4);
        b.addView(row, rlp);
        b.setLayoutParams(new FrameLayout.LayoutParams(dp(c, 312), FrameLayout.LayoutParams.WRAP_CONTENT));
        b.setBackground(round(0xF2000000 | color, dp(c, 18)));
        big = b;

        // Slepen: aan het kleine hoesje en aan de bovenkant van de kaart (een tik blijft een gewone klik)
        Drag d = new Drag();
        smallArt.setOnTouchListener(d);
        bigArt.setOnTouchListener(d);
        txt.setOnTouchListener(d);
        root.addView(small); root.addView(big);
        boolean isBig = Player.prefs(c).getBoolean("floatBig", false);
        small.setVisibility(isBig ? View.GONE : View.VISIBLE);
        big.setVisibility(isBig ? View.VISIBLE : View.GONE);
        // Na elke maatverandering (klein ↔ groot) binnen het scherm houden
        root.addOnLayoutChangeListener((v, l, t, r, bo, ol, ot, or, ob) -> { if (r - l != or - ol || bo - t != ob - ot) clamp(); });
        if (!callbacks) { callbacks = true; c.registerComponentCallbacks(new Rotate()); }
        wm.addView(root, lp);
        shown = true;
    }

    /** Draaien van het scherm: opnieuw binnen het scherm zetten. */
    static final class Rotate implements ComponentCallbacks {
        @Override public void onConfigurationChanged(Configuration cfg) { H.post(FloatPlayer::clamp); }
        @Override public void onLowMemory() { }
    }

    private static void toastIf(String msg) { if (msg != null && !msg.isEmpty()) Player.toast(app, msg); }

    private static void hide() {
        H.removeCallbacks(TICK);
        if (!shown) return;
        shown = false;
        try { wm.removeView(root); } catch (Exception ignored) { }
        root = null; small = big = null; smallArt = bigArt = null; title = sub = kind = null;
        pp = smallPp = back = fwd = prev = next = null; prog = null; lp = null;
        artKey = ""; sig = "";
    }

    /** Voortgang van een podcast (alleen zolang die speelt en hij zichtbaar is). */
    private static final Runnable TICK = () -> { if (shown && app != null) refresh(app); };

    private static void setBig(boolean on) {
        if (!shown) return;
        Player.prefs(app).edit().putBoolean("floatBig", on).apply();
        small.setVisibility(on ? View.GONE : View.VISIBLE);
        big.setVisibility(on ? View.VISIBLE : View.GONE);
        sig = "";
        if (app != null) refresh(app);
        (on ? pp : smallArt).sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED);
    }

    private static void closeByUser() {
        setEnabled(app, false);
        Player.toast(app, "Zwevende speler uit. Weer aanzetten: ⋯ in de speler.");
    }

    private static void update(Player.Now n) {
        Context c = app;
        boolean radio = Player.RADIO.equals(n.src);
        int pct = !radio && n.dur > 0 ? (int) Math.min(1000, n.pos * 1000 / n.dur) : -1;
        // Alleen iets doen als er echt iets veranderde (anders elke seconde een nieuwe opmaak van het venster)
        String s2 = n.src + "|" + n.title + "|" + n.sub + "|" + n.playing + "|" + n.shift + "|" + n.atLive + "|" + (pct < 0 ? -1 : pct / 5);
        if (!s2.equals(sig)) {
            sig = s2;
            title.setText(n.title);
            sub.setText(n.sub);
            kind.setText(radio ? "Radio" : "Podcast");
            int icon = n.playing ? R.drawable.ic_pause_dark : R.drawable.ic_play_dark;
            pp.setImageResource(icon); smallPp.setImageResource(icon);
            String lbl = n.playing ? "Pauze" : "Afspelen";
            pp.setContentDescription(lbl); smallPp.setContentDescription(lbl);
            prev.setContentDescription(radio ? "Vorige zender" : "Vorige aflevering");
            next.setContentDescription(radio ? "Volgende zender" : "Volgende aflevering");
            back.setContentDescription(radio ? "30 seconden terug" : "15 seconden terug");
            fwd.setContentDescription("30 seconden vooruit");
            boolean skips = !radio || n.shift;
            back.setVisibility(skips ? View.VISIBLE : View.INVISIBLE);
            fwd.setVisibility(skips ? View.VISIBLE : View.INVISIBLE);
            fwd.setEnabled(!radio || !n.atLive); fwd.setAlpha(fwd.isEnabled() ? 1f : 0.35f);
            prog.setVisibility(pct >= 0 ? View.VISIBLE : View.INVISIBLE);
            if (pct >= 0) prog.setProgress(pct);
        }
        // Elke seconde alleen als er een podcast speelt en de kaart groot is (de voortgangsbalk)
        H.removeCallbacks(TICK);
        if (!radio && n.playing && big.getVisibility() == View.VISIBLE) H.postDelayed(TICK, 1000);
        // Hoes en kleur: wat al bewaard is meteen, de rest op de achtergrond (en dan nog eens bijwerken)
        final String key = n.src + "|" + n.art + "|" + n.key;
        if (!key.equals(artKey)) {
            Bitmap bm = Art.smallCached(c, n.art, 256);
            if (bm == null) {
                smallArt.setImageResource(radio ? R.drawable.ic_radio : R.drawable.ic_podcast);
                bigArt.setImageResource(radio ? R.drawable.ic_radio : R.drawable.ic_podcast);
                int pad = dp(c, 16);
                smallArt.setPadding(pad, pad, pad, pad); bigArt.setPadding(pad / 2, pad / 2, pad / 2, pad / 2);
                Art.fetch(c, n.art, () -> refresh(c));
            } else {
                artKey = key; // pas als het plaatje er is, anders nooit meer opnieuw geprobeerd
                smallArt.setPadding(0, 0, 0, 0); bigArt.setPadding(0, 0, 0, 0);
                smallArt.setImageBitmap(bm); bigArt.setImageBitmap(bm);
            }
            int col = Art.colorCached(c, n.art);
            if (col < 0) {
                int nc = RadioWidget.nameColor(n.src + "|" + (n.station != null ? n.station.optString("name") : n.pod != null ? n.pod.optString("title") : ""));
                col = ArtColor.forBackground((nc >> 16) & 0xff, (nc >> 8) & 0xff, nc & 0xff);
            }
            if (col != color || bm != null) {
                color = col;
                smallArt.setBackground(round(0xFF000000 | col, dp(c, 34)));
                big.setBackground(round(0xF2000000 | col, dp(c, 18)));
            }
        }
    }

    /** Binnen het scherm houden. */
    private static void clamp() {
        if (!shown || root == null) return;
        int sw = app.getResources().getDisplayMetrics().widthPixels, sh = app.getResources().getDisplayMetrics().heightPixels;
        int w = root.getWidth() > 0 ? root.getWidth() : dp(app, 80), h = root.getHeight() > 0 ? root.getHeight() : dp(app, 80);
        lp.x = Math.max(0, Math.min(lp.x, sw - w));
        lp.y = Math.max(dp(app, 24), Math.min(lp.y, sh - h - dp(app, 24)));
        try { wm.updateViewLayout(root, lp); } catch (Exception ignored) { }
    }

    /** Slepen; een tik zonder slepen wordt een gewone klik (zo werkt het ook met TalkBack). */
    static final class Drag implements View.OnTouchListener {
        private float x0, y0;
        private int lx, ly;
        private boolean moved;

        @Override public boolean onTouch(View v, MotionEvent e) {
            if (!shown) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    x0 = e.getRawX(); y0 = e.getRawY(); lx = lp.x; ly = lp.y; moved = false;
                    return false; // ook de gewone klik/lang-drukken laten werken
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - x0, dy = e.getRawY() - y0;
                    if (!moved && Math.hypot(dx, dy) < ViewConfiguration.get(v.getContext()).getScaledTouchSlop()) return false;
                    if (!moved) { moved = true; v.cancelLongPress(); v.setPressed(false); }
                    lp.x = lx + Math.round(dx); lp.y = ly + Math.round(dy);
                    try { wm.updateViewLayout(root, lp); } catch (Exception ignored) { }
                    boolean inClose = small.getVisibility() == View.VISIBLE && e.getRawY() > app.getResources().getDisplayMetrics().heightPixels - dp(app, 110);
                    root.setAlpha(inClose ? 0.45f : 1f);
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    root.setAlpha(1f);
                    if (!moved) return false; // gewone tik: de klik doet het werk
                    int sw = app.getResources().getDisplayMetrics().widthPixels, sh = app.getResources().getDisplayMetrics().heightPixels;
                    if (small.getVisibility() == View.VISIBLE) {
                        if (e.getRawY() > sh - dp(app, 110)) { closeByUser(); return true; } // naar beneden gesleept = uit
                        lp.x = lp.x + root.getWidth() / 2 < sw / 2 ? 0 : sw - root.getWidth(); // tegen de rand
                    }
                    clamp();
                    Player.prefs(app).edit().putInt("floatX", lp.x).putInt("floatY", lp.y).apply();
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    root.setAlpha(1f);
                    return moved;
                default: return false;
            }
        }
    }

    private static void openApp() {
        try {
            app.startActivity(new Intent(app, MainActivity.class).putExtra("open", "player")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } catch (Exception ignored) { }
    }
}
