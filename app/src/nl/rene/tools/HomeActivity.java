package nl.rene.tools;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Telefoon-skin: Rene's Tools als startscherm. Een eigen WebView-pagina (start.html) met klok, weer, agenda,
 * Rene's Tools (overzicht, radio) en je apps. Losstaand van MainActivity (eigen taak), zodat de gewone app
 * en het startscherm elkaar niet in de weg zitten; het app-slot geldt hier niet.
 */
public class HomeActivity extends Activity {

    static final String BASE = "https://start.renes-tools.local/";
    static final int REQ_CAL = 50, REQ_LOC = 51;

    WebView web;
    final Handler h = new Handler(Looper.getMainLooper());
    private BroadcastReceiver pkgReceiver;
    private volatile boolean loaded = false;
    volatile int insTop = 24, insBottom = 16, insKb = 0;
    private long loadedAt = 0;

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

        makeWeb();
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    (OnBackInvokedCallback) this::back);
        }
        new Thread(() -> pruneIcons(getApplicationContext()), "icon-prune").start();

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

    /** WebView (opnieuw) opbouwen, ook na een vastgelopen weergaveproces. */
    void makeWeb() {
        if (web != null) { try { ((android.view.ViewGroup) web.getParent()).removeView(web); } catch (Exception ignored) { } web.destroy(); }
        loaded = false;
        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setTextZoom(MainActivity.textZoom(this));
        web.setWebViewClient(new Client(this));
        web.addJavascriptInterface(new Bridge(this), "Android");
        web.setOnApplyWindowInsetsListener((v, ins) -> {
            float d = getResources().getDisplayMetrics().density;
            int top = Math.round(ins.getSystemWindowInsetTop() / d), bottom = Math.round(ins.getStableInsetBottom() / d), kb = 0;
            if (Build.VERSION.SDK_INT >= 30) {
                bottom = Math.round(ins.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom / d);
                kb = Math.round(ins.getInsets(android.view.WindowInsets.Type.ime()).bottom / d);
            } else kb = Math.max(0, Math.round(ins.getSystemWindowInsetBottom() / d) - bottom);
            insTop = top; insBottom = bottom; insKb = kb;
            js("setInsets", top + "," + bottom + "," + kb);
            return ins;
        });
        load();
    }

    /** Oude pictogrammen (van eerdere app-versies) opruimen als de cache groot wordt. */
    static void pruneIcons(Context c) {
        java.io.File[] fs = new java.io.File(c.getCacheDir(), "icons").listFiles();
        if (fs == null || fs.length < 800) return;
        long old = System.currentTimeMillis() - 14L * 24 * 3600_000L;
        for (java.io.File f : fs) if (f.lastModified() < old) f.delete();
    }

    /** Pagina: bijgewerkte versie uit een stille update, anders die uit de app. */
    private void load() {
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
        html = html.replace("__VERSION_NAME__", Updater.webName(this));
        loaded = false;
        web.loadDataWithBaseURL(BASE, html, "text/html", "utf-8", null);
        loadedAt = System.currentTimeMillis();
    }

    /** Pagina klaar: vanaf nu kunnen meldingen naar de pagina, en de randen (balken, toetsenbord) opnieuw doorgeven. */
    void pageReady() {
        loaded = true;
        if (web != null) web.requestApplyInsets();
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
    final Runnable appsChanged = () -> js("onAppsChanged", "");

    static HomeActivity inst;

    @Override protected void onStart() { super.onStart(); inst = this; }
    @Override protected void onResume() {
        super.onResume();
        inst = this;
        // Stil bijgewerkt startscherm: opnieuw laden (alleen als er niets openstaat dat verloren gaat).
        java.io.File sf = Updater.startFile(this);
        if (Updater.downloadedStart(this) != null && sf.lastModified() > loadedAt && web != null) { load(); return; }
        js("onResumeHome", "");
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration c) { super.onConfigurationChanged(c); js("applyLook", ""); }

    static boolean night(Context c) {
        return (c.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }
    @Override protected void onPause() { super.onPause(); js("onPauseHome", ""); }

    /** Op de home-knop drukken terwijl het startscherm al open is: alles dicht en naar boven. */
    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i != null && Intent.ACTION_MAIN.equals(i.getAction())) js("onHomePressed", "");
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() { back(); }

    /** Terug: de pagina sluit wat er open staat. Is de pagina (nog) niet geladen, dan niet vastzitten. */
    void back() {
        if (loaded) js("onBack", "");
        else if (!Launcher.isDefaultHome(this)) finish();
    }

    @Override
    protected void onDestroy() {
        if (inst == this) inst = null;
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) { }
        h.removeCallbacksAndMessages(null);
        WebView w = web;
        web = null;
        loaded = false;
        if (w != null) w.destroy();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        js(req == REQ_CAL ? "onCalendarPerm" : "onLocationPerm", "");
    }

    void js(String fn, String arg) {
        if (web == null || !loaded) return;
        String code = "window." + fn + "&&window." + fn + "(" + arg + ")";
        h.post(() -> { if (web != null && loaded) web.evaluateJavascript(code, null); });
    }

    /** Pictogrammen van apps (/icon/pakket/activity?s=grootte), verder niets van buiten. */
    static final class Client extends WebViewClient {
        private final Context ctx;
        private final HomeActivity act;
        Client(HomeActivity a) { act = a; ctx = a.getApplicationContext(); }

        @Override public void onPageFinished(WebView v, String url) { if (v == act.web) act.pageReady(); }

        /** Weergaveproces vastgelopen of door Android gestopt: niet de hele app laten vallen, opnieuw opbouwen. */
        @Override
        public boolean onRenderProcessGone(WebView v, android.webkit.RenderProcessGoneDetail d) {
            App.log(ctx, "START", "weergave gestopt" + (Build.VERSION.SDK_INT >= 26 && d.didCrash() ? " (vastgelopen)" : ""));
            if (v == act.web) { act.web = null; try { ((android.view.ViewGroup) v.getParent()).removeView(v); } catch (Exception ignored) { } v.destroy(); }
            if (!act.isDestroyed()) act.h.post(act::makeWeb);
            return true;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            // De pagina zelf (loadDataWithBaseURL komt op sommige telefoons ook hierlangs) en data:-adressen gewoon laten laden.
            if (req.isForMainFrame() || u == null || u.getScheme() == null
                    || !("http".equals(u.getScheme()) || "https".equals(u.getScheme()))) return null;
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
        Bridge(HomeActivity act) { a = act; ctx = act.getApplicationContext(); }

        @JavascriptInterface public String apps() { return Launcher.appsJson(ctx); }
        /** Zelfde, op de achtergrond (de lijst ophalen duurt even): uitkomst via onApps(json). */
        @JavascriptInterface public void appsAsync() { new Thread(() -> a.js("onApps", Launcher.appsJson(ctx)), "apps").start(); }
        /** De pagina meldt zelf dat hij klaar is (voor het geval onPageFinished uitblijft). */
        @JavascriptInterface public void ready() { a.h.post(() -> { if (!a.loaded) a.pageReady(); }); }
        @JavascriptInterface public String insets() { return a.insTop + "," + a.insBottom + "," + a.insKb; }
        @JavascriptInterface public boolean isNight() { return night(a); }
        /** Terug zonder iets te sluiten: als dit (nog) niet het startscherm van de telefoon is, gewoon dicht. */
        @JavascriptInterface public void backUnhandled() { a.h.post(() -> { if (!Launcher.isDefaultHome(ctx)) a.finish(); }); }
        @JavascriptInterface public boolean locked() { return Lock.active(ctx); }
        @JavascriptInterface public String launch(String key) { return Launcher.launch(a, key); }
        @JavascriptInterface public void appInfo(String key) { Launcher.appInfo(a, key); }
        @JavascriptInterface public void uninstall(String key) { Launcher.uninstall(a, key); }
        @JavascriptInterface public String cfg() { return Launcher.prefs(ctx).getString("cfg", "{}"); }
        @JavascriptInterface public void cfgSet(String json) { Launcher.prefs(ctx).edit().putString("cfg", json).apply(); }

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
