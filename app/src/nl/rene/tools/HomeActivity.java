package nl.rene.tools;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Telefoon-skin: Rene's Tools als startscherm.
 *
 * Normaal is dit een echte launcher (Desk): pagina's om opzij te vegen met apps, mappen en widgets, en het dock.
 * Links staat de pagina "Vandaag" (webpagina start.html, modus today: klok, weer, agenda, Rene's Tools, notities).
 * Alle apps, menu's, de widgetkiezer en Look aanpassen komen in een tweede webpagina die erover schuift (modus overlay).
 *
 * Vangnet: start het nieuwe startscherm twee keer niet goed op, dan valt het terug op de klassieke skin
 * (één webpagina, modus legacy), zodat de telefoon altijd bruikbaar blijft.
 */
public class HomeActivity extends Activity {

    static final String BASE = "https://start.renes-tools.local/";
    static final int REQ_CAL = 50, REQ_LOC = 51;
    static final int DOCK_AREA_DP = 10 + 84 + 4 + 18 + 6; // dock + puntjes, voor de Vandaag-pagina

    WebView web;                  // klassiek: de hele skin; launcher: de Vandaag-pagina
    WebView overlay;              // launcher: alle apps, menu's, widgetkiezer, look
    Desk desk;
    FrameLayout frame;
    final Handler h = new Handler(Looper.getMainLooper());
    private BroadcastReceiver pkgReceiver;
    volatile boolean loaded = false, overlayLoaded = false;
    volatile int insTop = 24, insBottom = 16, insKb = 0;
    private long loadedAt = 0;
    boolean nativeMode = false, fellBack = false;
    volatile String wsCache = "{}";
    /** Overlay: volgnummer per keer openen, en wat er moet gebeuren zodra de pagina geladen is. */
    int ovSeq = 0, pickerSeq = 0;
    private boolean recreating = false;
    private final java.util.List<String[]> ovQueue = new java.util.ArrayList<>();
    private boolean firstFrame = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 30) w.setDecorFitsSystemWindows(false);
        else w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        if (Build.VERSION.SDK_INT >= 29) w.setNavigationBarContrastEnforced(false);
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);

        // Vangnet: kwam de vorige start niet tot rust, dan telt dat als mislukt.
        // Na een update van de app krijgt de launcher een nieuwe kans.
        SharedPreferences lp = Launcher.prefs(this);
        int fails = lp.getInt("bootCode", 0) != Version.CODE ? 0 : lp.getInt("bootFails", 0) + (lp.getBoolean("booting", false) ? 1 : 0);
        boolean classic = lp.getBoolean("classic", false);
        lp.edit().putInt("bootFails", fails).putInt("bootCode", Version.CODE).putBoolean("booting", true).commit();
        nativeMode = !classic && fails < 2;
        fellBack = !classic && fails >= 2;

        frame = new FrameLayout(this);
        setContentView(frame);
        frame.setOnApplyWindowInsetsListener((v, ins) -> { onInsets(ins); return ins; });
        if (nativeMode) {
            try { buildNative(); }
            catch (Throwable e) {
                App.log(this, "START", "launcher opbouwen mislukt: " + e);
                frame.removeAllViews();
                WebView hw = web, ho = overlay;
                web = null; overlay = null; desk = null;
                loaded = false; overlayLoaded = false;
                // Niet bij elke start opnieuw proberen (en mislukken); na een update van de app wel weer.
                Launcher.prefs(this).edit().putInt("bootFails", 2).commit();
                try { if (hw != null) hw.destroy(); } catch (Throwable ignored) { }
                try { if (ho != null) ho.destroy(); } catch (Throwable ignored) { }
                nativeMode = false; fellBack = true;
            }
        }
        if (!nativeMode) {
            web = makeWeb("legacy");
            frame.addView(web, new FrameLayout.LayoutParams(-1, -1));
        }
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    (OnBackInvokedCallback) this::back);
        }
        new Thread(() -> pruneIcons(getApplicationContext()), "icon-prune").start();
        // Vangnet: pas als er echt iets getekend is (en het daarna even goed blijft gaan) is de start gelukt.
        frame.getViewTreeObserver().addOnDrawListener(() -> {
            if (firstFrame) return;
            firstFrame = true;
            h.postDelayed(stable, 3000);
        });

        pkgReceiver = new PkgReceiver();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(pkgReceiver, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(pkgReceiver, f);
    }

    /** Echte launcher opbouwen: werkblad met Vandaag-pagina, plus de overlay (verborgen tot nodig). */
    void buildNative() {
        web = makeWeb("today");
        desk = new Desk(this, web);
        frame.setBackground(Desk.background(this));
        frame.addView(desk.build(), new FrameLayout.LayoutParams(-1, -1));
        overlay = makeWeb("overlay");
        overlay.setVisibility(View.GONE);
        frame.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
    }

    /** Raamranden (statusbalk, navigatie, toetsenbord) doorgeven aan werkblad en pagina's. */
    void onInsets(android.view.WindowInsets ins) {
        float d = getResources().getDisplayMetrics().density;
        int top = Math.round(ins.getSystemWindowInsetTop() / d), bottom = Math.round(ins.getStableInsetBottom() / d), kb;
        if (Build.VERSION.SDK_INT >= 30) {
            bottom = Math.round(ins.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom / d);
            kb = Math.round(ins.getInsets(android.view.WindowInsets.Type.ime()).bottom / d);
        } else kb = Math.max(0, Math.round(ins.getSystemWindowInsetBottom() / d) - bottom);
        insTop = top; insBottom = bottom; insKb = kb;
        if (desk != null) {
            desk.setInsets(Math.round(top * d), Math.round(bottom * d));
            jsTo(web, "setInsets", top + "," + (bottom + DOCK_AREA_DP) + "," + kb);
            jsTo(overlay, "setInsets", top + "," + bottom + "," + kb);
        } else jsTo(web, "setInsets", top + "," + bottom + "," + kb);
    }

    WebView makeWeb(String mode) {
        WebView v = new WebView(this);
        v.setBackgroundColor(Color.TRANSPARENT);
        WebSettings s = v.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setTextZoom(MainActivity.textZoom(this));
        v.setWebViewClient(new Client(this));
        v.addJavascriptInterface(new Bridge(this, mode), "Android");
        load(v, mode);
        return v;
    }

    /** Oude pictogrammen (van eerdere app-versies) opruimen als de cache groot wordt. */
    static void pruneIcons(Context c) {
        java.io.File[] fs = new java.io.File(c.getCacheDir(), "icons").listFiles();
        if (fs == null || fs.length < 800) return;
        long old = System.currentTimeMillis() - 14L * 24 * 3600_000L;
        for (java.io.File f : fs) if (f.lastModified() < old) f.delete();
    }

    /** Pagina: bijgewerkte versie uit een stille update, anders die uit de app. */
    void load(WebView v, String mode) {
        String html = Updater.downloadedStart(this);
        if (html == null) {
            try (java.io.InputStream in = getAssets().open("start.html")) {
                java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                html = new String(o.toByteArray(), StandardCharsets.UTF_8);
            } catch (Exception e) { html = "<p>Startscherm niet gevonden</p>"; }
        }
        html = html.replace("__VERSION_NAME__", Updater.webName(this)).replace("__MODE__", mode);
        if (v == overlay) { overlayLoaded = false; v.setVisibility(View.GONE); ovQueue.clear(); ovSeq++; } else loaded = false;
        v.loadDataWithBaseURL(BASE, html, "text/html", "utf-8", null);
        loadedAt = System.currentTimeMillis();
    }

    /** Pagina klaar: vanaf nu kunnen meldingen naar de pagina, en de randen (balken, toetsenbord) opnieuw doorgeven. */
    void pageReady(WebView v) {
        if (v == null) return;
        if (v == overlay) overlayLoaded = true; else if (v == web) loaded = true; else return;
        frame.requestApplyInsets();
        if (v == overlay && !ovQueue.isEmpty()) {
            // Wat er gevraagd werd terwijl de pagina nog laadde (bijv. meteen na het opstarten omhoog vegen)
            java.util.List<String[]> q = new java.util.ArrayList<>(ovQueue);
            ovQueue.clear();
            for (String[] c : q) ovCall(c[0], c[1], "1".equals(c[2]));
        }
    }

    /** Nieuwe apps of verwijderde apps: lijst opnieuw laden (één keer, na een update komen er meerdere berichten). */
    static final class PkgReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            HomeActivity a = inst;
            if (a == null) return;
            if (Intent.ACTION_PACKAGE_REMOVED.equals(i.getAction()) && i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            a.h.removeCallbacks(a.appsChanged);
            a.h.postDelayed(a.appsChanged, 1500);
        }
    }
    final Runnable appsChanged = () -> { js("onAppsChanged", ""); if (desk != null) desk.appsChanged(); };

    static HomeActivity inst;

    @Override protected void onStart() {
        super.onStart();
        inst = this;
        if (desk != null) try { desk.host.startListening(); } catch (Exception e) { App.log(this, "START", "widgets: " + e); }
    }

    @Override protected void onStop() {
        super.onStop();
        // Zo ver gekomen: dit was geen mislukte start (bijv. opgebouwd terwijl het scherm uit stond).
        if (firstFrame || nativeMode) Launcher.prefs(this).edit().putBoolean("booting", false).apply();
        if (desk != null) try { desk.host.stopListening(); } catch (Exception ignored) { }
    }

    final Runnable stable = () -> {
        // Startscherm draait: vangnet-teller terug naar nul (alleen in de launcher-modus, anders blijft de klassieke skin).
        if (nativeMode) Launcher.prefs(this).edit().putBoolean("booting", false).putInt("bootFails", 0).apply();
        else Launcher.prefs(this).edit().putBoolean("booting", false).apply();
    };

    @Override protected void onResume() {
        super.onResume();
        inst = this;
        // Stil bijgewerkt startscherm: opnieuw laden.
        java.io.File sf = Updater.startFile(this);
        if (Updater.downloadedStart(this) != null && sf.lastModified() > loadedAt && web != null) {
            load(web, nativeMode ? "today" : "legacy");
            if (overlay != null) load(overlay, "overlay");
            return;
        }
        js("onResumeHome", "");
        if (fellBack) { fellBack = false; h.postDelayed(() -> toast("Het nieuwe startscherm startte niet goed; je ziet de klassieke skin. Zie Look aanpassen."), 1500); }
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        js("applyLook", "");
        if (desk != null) desk.applyCfg();
    }

    static boolean night(Context c) {
        return (c.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }
    @Override protected void onPause() {
        super.onPause();
        js("onPauseHome", "");
        if (desk != null) desk.cancelDrag();
    }

    /** Op de home-knop drukken terwijl het startscherm al open is: alles dicht en naar het hoofdscherm. */
    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i == null || !Intent.ACTION_MAIN.equals(i.getAction())) return;
        js("onHomePressed", "");
        if (desk != null) {
            ovQueue.clear();
            ovSeq++;
            hideOverlay();
            desk.cancelDrag();
            desk.closeFolder();
            desk.stopResize();
            desk.ws.snapTo(desk.home + 1, true);
        }
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() { back(); }

    /** Terug: eerst wat er open staat; anders naar het hoofdscherm. Nooit vastzitten. */
    void back() { back(false); }

    /** @param todayDone de Vandaag-pagina had zelf niets meer om te sluiten (geen nieuwe ronde daarheen). */
    void back(boolean todayDone) {
        if (desk != null) {
            if (overlay != null && overlay.getVisibility() == View.VISIBLE) {
                if (overlayLoaded) jsTo(overlay, "onBack", ""); else hideOverlay();
                return;
            }
            if (desk.root.dragging) { desk.cancelDrag(); return; }
            if (desk.stopResize() || desk.closeFolder()) return;
            // Op de Vandaag-pagina eerst wat daar open staat (bijv. plaats zoeken voor het weer)
            if (desk.ws.page == 0 && !todayDone && loaded) { jsTo(web, "onBack", ""); return; }
            if (desk.ws.page != desk.home + 1) { desk.ws.snapTo(desk.home + 1, true); return; }
            if (!Launcher.isDefaultHome(this)) finish();
            return;
        }
        if (loaded) js("onBack", "");
        else if (!Launcher.isDefaultHome(this)) finish();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (desk != null && (req == Desk.REQ_BIND || req == Desk.REQ_CONFIG)) desk.onActivityResult(req, res, data);
    }

    @Override
    protected void onDestroy() {
        if (inst == this) inst = null;
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) { }
        h.removeCallbacksAndMessages(null);
        loaded = false; overlayLoaded = false;
        WebView w = web, o = overlay;
        web = null; overlay = null;
        if (w != null) w.destroy();
        if (o != null) o.destroy();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        js(req == REQ_CAL ? "onCalendarPerm" : "onLocationPerm", "");
    }

    /** Naar alle geladen pagina's (Vandaag en overlay). */
    void js(String fn, String arg) {
        if (loaded) jsTo(web, fn, arg);
        if (overlayLoaded) jsTo(overlay, fn, arg);
    }

    void jsTo(WebView v, String fn, String arg) {
        if (v == null) return;
        if (v == web && !loaded) return;
        if (v == overlay && !overlayLoaded) return;
        String code = "window." + fn + "&&window." + fn + "(" + arg + ")";
        h.post(() -> { if (v == web || v == overlay) v.evaluateJavascript(code, null); });
    }

    // ---------- overlay (alle apps, menu's, look) ----------

    /**
     * Iets in de overlay openen. Nog niet geladen? Dan onthouden en pas tonen als de pagina klaar is, zodat er nooit
     * een lege, onzichtbare laag over het werkblad ligt. Elke keer tonen krijgt een volgnummer; de pagina geeft dat
     * terug bij "klaar", zodat een late melding van een vorig venster het nieuwe niet verbergt.
     */
    void ovCall(String fn, String arg, boolean show) {
        if (overlay == null) return;
        if (!overlayLoaded) { ovQueue.add(new String[]{fn, arg, show ? "1" : "0"}); return; }
        String call = "window." + fn + "&&window." + fn + "(" + arg + ")";
        if (show) {
            ovSeq++;
            overlay.setVisibility(View.VISIBLE);
            overlay.bringToFront();
            overlay.requestFocus();
            // Volgnummer en openen in één script, zodat er geen 'klaar' tussendoor kan komen
            call = "window.__setSeq&&window.__setSeq(" + ovSeq + ");" + call;
        } else if (overlay.getVisibility() != View.VISIBLE) return; // laag is intussen dicht: niets meer bijwerken
        final String code = call;
        final WebView v = overlay;
        h.post(() -> { if (v == overlay && overlayLoaded) v.evaluateJavascript(code, null); });
    }

    void overlayDone(int seq) { if (seq == ovSeq) hideOverlay(); }

    void hideOverlay() {
        ovSeq++; // late meldingen van wat er open was tellen niet meer
        if (overlay == null || overlay.getVisibility() != View.VISIBLE) return;
        overlay.setVisibility(View.GONE);
        overlay.clearFocus();
        try { ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(overlay.getWindowToken(), 0); } catch (Exception ignored) { }
    }

    void openDrawer() { ovCall("openDrawer", "", true); }
    void openLook() { ovCall("openLook", "", true); }

    void sheet(JSONObject o) { ovCall("nativeSheet", o.toString(), true); }

    void askFolderName(long uid, String name) {
        try { ovCall("askFolderName", new JSONObject().put("uid", String.valueOf(uid)).put("name", name).toString(), true); } catch (Exception ignored) { }
    }

    void openWidgetPicker() {
        if (overlay == null || desk == null) return;
        ovCall("widgetPicker", "null", true); // eerst leeg (laden…), lijst volgt
        final Desk dk = desk;
        final float[] cell = dk.cellSize(); // maat van de cellen hier (hoofdthread) opvragen
        final int seq = ++pickerSeq;
        new Thread(() -> {
            String j = dk.widgetsJson(cell);
            // Alleen voor de kiezer die nu open is (de pagina negeert het ook als de kiezer dicht is)
            h.post(() -> { if (seq == pickerSeq) ovCall("widgetPicker", j, false); });
        }, "widgets").start();
    }

    void wallpaper() {
        try { startActivity(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), "Achtergrond kiezen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
    }

    void openTool(String name) {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (name != null && !name.isEmpty()) i.putExtra("open", name);
        try { startActivity(i); } catch (Exception ignored) { }
    }

    void notificationsDown() {
        try {
            Object sb = getSystemService("statusbar");
            sb.getClass().getMethod("expandNotificationsPanel").invoke(sb);
        } catch (Throwable ignored) { }
    }

    void onPageChanged(int p) { if (p == 0) jsTo(web, "onResumeHome", ""); }

    void toast(String m) { h.post(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show()); }

    void vibrate(int ms) {
        try {
            android.os.Vibrator v = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(android.os.VibrationEffect.createOneShot(Math.max(5, Math.min(ms, 60)), android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(Math.max(5, Math.min(ms, 60)));
        } catch (Exception ignored) { }
    }

    static final class Client extends WebViewClient {
        private final Context ctx;
        private final HomeActivity act;
        Client(HomeActivity a) { act = a; ctx = a.getApplicationContext(); }

        @Override public void onPageFinished(WebView v, String url) { act.pageReady(v); }

        /** Weergaveproces vastgelopen of door Android gestopt: niet de hele app laten vallen, opnieuw opbouwen. */
        @Override
        public boolean onRenderProcessGone(WebView v, android.webkit.RenderProcessGoneDetail d) {
            App.log(ctx, "START", "weergave gestopt" + (Build.VERSION.SDK_INT >= 26 && d.didCrash() ? " (vastgelopen)" : ""));
            // Eenvoudigst en veiligst: de hele activiteit opnieuw opbouwen (werkblad, pagina's en widgets).
            try { ((android.view.ViewGroup) v.getParent()).removeView(v); } catch (Exception ignored) { }
            if (v == act.web) act.web = null;
            if (v == act.overlay) act.overlay = null;
            v.destroy();
            if (act.recreating) return true; // beide pagina's delen één weergaveproces: maar één keer opnieuw opbouwen
            act.recreating = true;
            // Opzettelijk opnieuw opbouwen telt niet als mislukte start, tenzij het steeds opnieuw gebeurt
            // (bijv. een kapotte bijgewerkte pagina): dan telt het wel, zodat het vangnet de klassieke skin kiest.
            android.content.SharedPreferences p = Launcher.prefs(ctx);
            long now = System.currentTimeMillis();
            int n = now - p.getLong("renderGoneAt", 0) < 120_000 ? p.getInt("renderGone", 0) + 1 : 1;
            p.edit().putLong("renderGoneAt", now).putInt("renderGone", n).putBoolean("booting", n >= 3).commit();
            if (!act.isDestroyed() && !act.isFinishing()) act.h.post(act::recreate);
            return true;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            // De pagina zelf (loadDataWithBaseURL komt op sommige telefoons ook hierlangs) en data:-adressen gewoon laten laden.
            if (req.isForMainFrame() || u == null || u.getScheme() == null
                    || !("http".equals(u.getScheme()) || "https".equals(u.getScheme()))) return null;
            if ("start.renes-tools.local".equals(u.getHost()) && u.getPath() != null && u.getPath().startsWith("/wpreview/") && act.desk != null) {
                byte[] png = act.desk.widgetPreview(Uri.decode(u.getPath().substring(10)), 360);
                if (png != null) return new WebResourceResponse("image/png", null, new ByteArrayInputStream(png));
            }
            if ("start.renes-tools.local".equals(u.getHost()) && u.getPath() != null && u.getPath().startsWith("/icon/")) {
                String key = Uri.decode(u.getPath().substring(6));
                int size = 144;
                try { size = Integer.parseInt(u.getQueryParameter("s")); } catch (Exception ignored) { }
                byte[] png = Launcher.iconPng(ctx, key, size);
                if (png != null) {
                    WebResourceResponse r = new WebResourceResponse("image/png", null, new ByteArrayInputStream(png));
                    java.util.Map<String, String> hd = new java.util.HashMap<>();
                    hd.put("Cache-Control", "max-age=86400");
                    r.setResponseHeaders(hd);
                    return r;
                }
            }
            // Verder niets van internet in het startscherm.
            WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
            r.setStatusCodeAndReasonPhrase(404, "Not found");
            return r;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) { return true; } // geen navigatie weg van het startscherm
    }

    static final class Bridge {
        private final HomeActivity a;
        private final Context ctx;
        private final String mode;
        Bridge(HomeActivity act, String m) { a = act; ctx = act.getApplicationContext(); mode = m; }

        // ----- launcher (werkblad) -----
        @JavascriptInterface public String mode() { return mode; }
        @JavascriptInterface public void openDrawer() { a.h.post(a::openDrawer); }
        @JavascriptInterface public void overlayDone(int seq) { a.h.post(() -> a.overlayDone(seq)); }
        @JavascriptInterface public void homeMenu() { a.h.post(() -> { if (a.desk != null) a.desk.homeMenu(); }); }
        @JavascriptInterface public void openLookNative() { a.h.post(a::openLook); }
        @JavascriptInterface public void sheetAction(String uid, String id) {
            a.h.post(() -> {
                if (a.desk == null || id == null) return;
                if ("-1".equals(uid)) a.desk.homeAction(id);
                else try { a.desk.itemAction(Long.parseLong(uid), id); } catch (NumberFormatException ignored) { }
            });
        }
        @JavascriptInterface public void folderRename(String uid, String name) {
            a.h.post(() -> { if (a.desk != null) try { a.desk.renameFolder(Long.parseLong(uid), name); } catch (NumberFormatException ignored) { } });
        }
        @JavascriptInterface public void wsAddApp(String key) {
            a.h.post(() -> { if (a.desk == null) return; String e = a.desk.addApp(key); a.toast(e.isEmpty() ? "Op het startscherm gezet" : e); });
        }
        @JavascriptInterface public void wsDock(String key, boolean on) {
            a.h.post(() -> { if (a.desk == null) return; String e = a.desk.toDock(key, on); if (!e.isEmpty()) a.toast(e); });
        }
        /** Welke apps er op het werkblad en in het dock staan: {pinned:[…], dock:[…]}. */
        @JavascriptInterface public String wsApps() { return a.wsCache; }
        @JavascriptInterface public void wsApply(String json) {
            a.h.post(() -> { if (a.desk == null) return; a.desk.applyApps(json); });
        }
        @JavascriptInterface public void widgetAdd(String provider) { a.h.post(() -> { a.hideOverlay(); if (a.desk != null) a.desk.addWidget(provider); }); }
        @JavascriptInterface public void widgetPicker() { a.h.post(a::openWidgetPicker); }
        /** Klassieke skin (zonder pagina's en widgets) aan/uit; daarna opnieuw opbouwen. */
        @JavascriptInterface public void setClassic(boolean on) {
            Launcher.prefs(ctx).edit().putBoolean("classic", on).putInt("bootFails", 0).putBoolean("booting", false).commit();
            a.h.post(a::recreate);
        }
        @JavascriptInterface public boolean classic() { return !a.nativeMode; }

        @JavascriptInterface public String apps() { return Launcher.appsJson(ctx); }
        /** Zelfde, op de achtergrond (de lijst ophalen duurt even): uitkomst via onApps(json). */
        @JavascriptInterface public void appsAsync() { new Thread(() -> a.js("onApps", Launcher.appsJson(ctx)), "apps").start(); }
        /** De pagina meldt zelf dat hij klaar is (voor het geval onPageFinished uitblijft). */
        @JavascriptInterface public void ready() {
            a.h.post(() -> { if ("overlay".equals(mode)) { if (!a.overlayLoaded) a.pageReady(a.overlay); } else if (!a.loaded) a.pageReady(a.web); });
        }
        @JavascriptInterface public String insets() {
            int b = "today".equals(mode) ? a.insBottom + DOCK_AREA_DP : a.insBottom;
            return a.insTop + "," + b + "," + a.insKb;
        }
        @JavascriptInterface public boolean isNight() { return night(a); }
        /** Terug zonder iets te sluiten: als dit (nog) niet het startscherm van de telefoon is, gewoon dicht. */
        @JavascriptInterface public void backUnhandled() {
            a.h.post(() -> {
                if ("overlay".equals(mode)) { a.hideOverlay(); return; }
                if (a.desk != null) { a.back(true); return; }
                if (!Launcher.isDefaultHome(ctx)) a.finish();
            });
        }
        @JavascriptInterface public boolean locked() { return Lock.active(ctx); }
        @JavascriptInterface public String launch(String key) { return Launcher.launch(a, key); }
        @JavascriptInterface public void appInfo(String key) { Launcher.appInfo(a, key); }
        @JavascriptInterface public void uninstall(String key) { Launcher.uninstall(a, key); }
        @JavascriptInterface public String cfg() { return Launcher.prefs(ctx).getString("cfg", "{}"); }
        @JavascriptInterface public void cfgSet(String json) {
            Launcher.prefs(ctx).edit().putString("cfg", json).apply();
            // Launcher: raster, achtergrond en de andere pagina bijwerken
            if (a.desk != null) a.h.post(() -> {
                if (a.desk == null) return;
                a.frame.setBackground(Desk.background(ctx));
                a.desk.applyCfg();
                if ("overlay".equals(mode)) a.jsTo(a.web, "cfgReload", ""); else a.jsTo(a.overlay, "cfgReload", "");
            });
        }

        @JavascriptInterface public String calendar() { return Launcher.calendarJson(ctx); }
        @JavascriptInterface public void calendarPermission() {
            a.h.post(() -> {
                android.content.SharedPreferences p = Launcher.prefs(ctx);
                // Eerder al geweigerd en Android vraagt niet meer: naar de app-instellingen.
                if (p.getBoolean("calAsked", false) && !a.shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR)) {
                    try { a.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.getPackageName(), null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                    catch (Exception ignored) { }
                    return;
                }
                p.edit().putBoolean("calAsked", true).apply();
                a.requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, REQ_CAL);
            });
        }
        @JavascriptInterface public void openEvent(String id) { try { Launcher.openEvent(a, Long.parseLong(id)); } catch (Exception e) { Launcher.openEvent(a, 0); } }

        @JavascriptInterface public long nextAlarm() { return Launcher.nextAlarm(ctx); }
        @JavascriptInterface public void openClock() { Launcher.openClock(a); }

        /** Weer op de achtergrond; uitkomst via onWeather(json). */
        @JavascriptInterface public void weather(boolean force) {
            new Thread(() -> a.js("onWeather", Launcher.weatherJson(ctx, force)), "weather").start();
        }
        @JavascriptInterface public void cities(String q) {
            new Thread(() -> a.js("onCities", Launcher.citiesJson(q)), "cities").start();
        }
        @JavascriptInterface public void setPlace(String name, double lat, double lon) { Launcher.setPlace(ctx, name, lat, lon); }
        @JavascriptInterface public String placeName() { return Launcher.prefs(ctx).getString("placeName", ""); }
        @JavascriptInterface public void locationPermission() {
            a.h.post(() -> a.requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC));
        }

        @JavascriptInterface public String tools() { return Launcher.toolsJson(ctx); }
        /** Notities die op de skin staan (met de eerste open items). */
        @JavascriptInterface public String notes() { return Notes.pinnedJson(ctx, "skin"); }
        @JavascriptInterface public void noteUnpin(String id) { Notes.setPinned(ctx, "skin", id, false); }
        /** Radio vanaf het startscherm: play (laatste zender), pause, resume, stop. */
        @JavascriptInterface public void radio(String what) {
            if ("play".equals(what)) {
                String last = Radio.prefs(ctx).getString("last", null);
                if (last != null) RadioService.send(ctx, RadioService.PLAY, last);
            } else if ("pause".equals(what)) RadioService.send(ctx, RadioService.PAUSE, null);
            else if ("resume".equals(what)) RadioService.send(ctx, RadioService.RESUME, null);
            else if ("stop".equals(what)) RadioService.send(ctx, RadioService.STOP, null);
            else if ("rew".equals(what) || "live".equals(what)) RadioService.send(ctx, what, null);
        }
        /** Een tool van Rene's Tools openen (de gewone app, in een eigen taak). */
        @JavascriptInterface public void openTool(String name) {
            Intent i = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (name != null && !name.isEmpty()) i.putExtra("open", name);
            try { a.startActivity(i); } catch (Exception ignored) { }
        }

        @JavascriptInterface public boolean isDefault() { return Launcher.isDefaultHome(ctx); }
        @JavascriptInterface public void homeSettings() {
            int f = Intent.FLAG_ACTIVITY_NEW_TASK; // niet in de taak van het startscherm (die wordt bij "home" leeggemaakt)
            try { a.startActivity(new Intent(android.provider.Settings.ACTION_HOME_SETTINGS).addFlags(f)); }
            catch (Exception e) { try { a.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(f)); } catch (Exception ignored) { } }
        }
        @JavascriptInterface public void wallpaper() {
            try { a.startActivity(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), "Achtergrond kiezen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
        }
        @JavascriptInterface public void webSearch(String q) {
            try { a.startActivity(new Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, q).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception e) {
                try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
            }
        }
        /** Meldingenpaneel openen (vegen omlaag). Niet elke telefoon staat dat toe. */
        @SuppressWarnings({"WrongConstant", "JavaReflectionMemberAccess"})
        @JavascriptInterface public boolean notifications() {
            try {
                Object sb = ctx.getSystemService("statusbar");
                sb.getClass().getMethod("expandNotificationsPanel").invoke(sb);
                return true;
            } catch (Throwable e) { return false; }
        }
        @JavascriptInterface public void vibrate(int ms) {
            try {
                android.os.Vibrator v = (android.os.Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
                if (v == null) return;
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(android.os.VibrationEffect.createOneShot(Math.max(5, Math.min(ms, 60)), android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                else v.vibrate(Math.max(5, Math.min(ms, 60)));
            } catch (Exception ignored) { }
        }
        @JavascriptInterface public boolean hasPerm(String which) {
            if ("calendar".equals(which)) return ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
            return ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
        @JavascriptInterface public void logJs(String m) { App.log(ctx, "START", m); }
        @JavascriptInterface public String version() { try { return new JSONObject().put("name", Version.NAME).toString(); } catch (Exception e) { return "{}"; } }
    }
}
