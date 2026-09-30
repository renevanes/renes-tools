package nl.rene.tools;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    static final String ACTION_INSTALL_STATUS = "nl.rene.tools.INSTALL_STATUS";
    private static final int REQ_PERMS = 11;
    private static final int REQ_CONTACT = 12;
    private static final String BASE_URL = "https://app.renes-tools.local/";

    private WebView web;
    private View topBar, bottomBar;
    private final Handler h = new Handler(Looper.getMainLooper());
    private String pendingOpen = null;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        RedialService.createChannel(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        topBar = new View(this);
        bottomBar = new View(this);
        web = new WebView(this);
        root.addView(topBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0));
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(bottomBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0));
        setContentView(root);
        setBars("#1E5AA8", "#F3F5F9", true);

        if (Build.VERSION.SDK_INT >= 35) {
            // Android 15+: app tekent achter de systeembalken; wij houden ruimte vrij.
            root.setOnApplyWindowInsetsListener((v, ins) -> {
                android.graphics.Insets sb = ins.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = ins.getInsets(WindowInsets.Type.ime());
                topBar.getLayoutParams().height = sb.top;
                bottomBar.getLayoutParams().height = Math.max(sb.bottom, ime.bottom);
                v.setPadding(sb.left, 0, sb.right, 0);
                topBar.requestLayout();
                bottomBar.requestLayout();
                return WindowInsets.CONSUMED;
            });
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(this), "Android");
        web.setBackgroundColor(Color.parseColor("#F3F5F9"));

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, (OnBackInvokedCallback) this::handleBack);
        }

        pendingOpen = getIntent() != null ? getIntent().getStringExtra("open") : null;
        loadUi();
    }

    private void loadUi() {
        String html = Updater.downloadedHtml(this);
        if (html == null) html = readAsset("index.html");
        web.loadDataWithBaseURL(BASE_URL, html, "text/html", "utf-8", null);
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            return new String(Updater.readAll(in), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "<h1>Fout bij laden</h1>";
        }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i == null) return;
        if (ACTION_INSTALL_STATUS.equals(i.getAction())) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    try { startActivity(confirm); } catch (Exception e) { js("onUpdateStatus", "{\"state\":\"error\",\"error\":\"Installeren kon niet starten\"}"); }
                }
            } else if (st != PackageInstaller.STATUS_SUCCESS) {
                String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                js("onUpdateStatus", jsonErr("Installatie afgebroken" + (msg != null ? ": " + msg : "")));
            }
            return;
        }
        String open = i.getStringExtra("open");
        if (open != null) js("openTool", JSONObject.quote(open));
    }

    @Override
    protected void onResume() {
        super.onResume();
        js("onResumeApp", "");
        // Automatisch op updates controleren, hooguit eens per 30 minuten.
        long last = Updater.prefs(this).getLong("lastCheck", 0);
        if (System.currentTimeMillis() - last > 30 * 60 * 1000L) checkUpdates(false);
    }

    private void checkUpdates(final boolean manual) {
        new Thread(() -> {
            final JSONObject r = Updater.check(getApplicationContext());
            h.post(() -> {
                try { r.put("manual", manual); } catch (Exception ignored) { }
                if ("web".equals(r.optString("state"))) {
                    Toast.makeText(this, "Bijgewerkt naar versie " + r.optString("versionName"), Toast.LENGTH_LONG).show();
                    loadUi();
                } else {
                    js("onUpdateStatus", r.toString());
                }
            });
        }).start();
    }

    /** Roept window[fn](arg) aan in de pagina als die functie bestaat. */
    void js(String fn, String arg) {
        if (web == null) return;
        String code = "window." + fn + "&&window." + fn + "(" + arg + ")";
        h.post(() -> web.evaluateJavascript(code, null));
    }

    private static String jsonErr(String m) {
        return "{\"state\":\"error\",\"error\":" + JSONObject.quote(m) + "}";
    }

    private void handleBack() {
        web.evaluateJavascript("window.goBack?window.goBack():false", v -> {
            if (!"true".equals(v)) finish();
        });
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() { handleBack(); }

    void setBars(String top, String bottom, boolean lightBottom) {
        int t = Color.parseColor(top), b = Color.parseColor(bottom);
        topBar.setBackgroundColor(t);
        bottomBar.setBackgroundColor(b);
        Window w = getWindow();
        if (Build.VERSION.SDK_INT < 35) {
            w.setStatusBarColor(t);
            w.setNavigationBarColor(b);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                int val = (lightBottom ? WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS : 0)
                        | (isLight(t) ? WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS : 0);
                c.setSystemBarsAppearance(val, mask);
            }
        } else if (Build.VERSION.SDK_INT >= 26) {
            int f = w.getDecorView().getSystemUiVisibility();
            f = lightBottom ? (f | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR) : (f & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            f = isLight(t) ? (f | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) : (f & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            w.getDecorView().setSystemUiVisibility(f);
        }
    }

    private static boolean isLight(int c) {
        return (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000 > 160;
    }

    // ---------- toestemmingen & contact kiezen ----------

    String permissionState() {
        try {
            JSONObject o = new JSONObject();
            o.put("call", granted(Manifest.permission.CALL_PHONE));
            o.put("phoneState", granted(Manifest.permission.READ_PHONE_STATE));
            o.put("callLog", granted(Manifest.permission.READ_CALL_LOG));
            o.put("notifications", Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS));
            o.put("installUpdates", Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls());
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    private boolean granted(String p) { return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    void requestRedialPermissions() {
        java.util.ArrayList<String> l = new java.util.ArrayList<>();
        l.add(Manifest.permission.CALL_PHONE);
        l.add(Manifest.permission.READ_PHONE_STATE);
        l.add(Manifest.permission.READ_CALL_LOG);
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS);
        java.util.ArrayList<String> need = new java.util.ArrayList<>();
        for (String p : l) if (!granted(p)) need.add(p);
        if (need.isEmpty()) { js("onPermissions", permissionState()); return; }
        requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == REQ_PERMS) js("onPermissions", permissionState());
    }

    void pickContact() {
        Intent i = new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
        try { startActivityForResult(i, REQ_CONTACT); }
        catch (ActivityNotFoundException e) { Toast.makeText(this, "Geen contacten-app gevonden", Toast.LENGTH_SHORT).show(); }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_CONTACT || res != RESULT_OK || data == null || data.getData() == null) return;
        try (Cursor c = getContentResolver().query(data.getData(), new String[]{
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                JSONObject o = new JSONObject();
                o.put("number", c.getString(0));
                o.put("name", c.getString(1));
                js("onContactPicked", o.toString());
            }
        } catch (Exception e) {
            Toast.makeText(this, "Contact lezen lukt niet", Toast.LENGTH_SHORT).show();
        }
    }

    void openAppSettings() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
    }

    void openInstallSettings() {
        if (Build.VERSION.SDK_INT >= 26) {
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
        }
    }

    // ---------- brug naar JavaScript ----------

    static final class Bridge {
        private final MainActivity a;
        private final Context ctx;
        Bridge(MainActivity act) { a = act; ctx = act.getApplicationContext(); }

        @JavascriptInterface public String version() {
            try {
                JSONObject o = new JSONObject();
                o.put("appName", Version.NAME);
                o.put("appCode", Version.CODE);
                o.put("nativeLevel", Version.NATIVE_LEVEL);
                o.put("webName", Updater.webName(ctx));
                o.put("webCode", Updater.webCode(ctx));
                o.put("android", Build.VERSION.RELEASE);
                o.put("sdk", Build.VERSION.SDK_INT);
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface public String lastManifest() {
            return Updater.prefs(ctx).getString("manifest", "null");
        }

        @JavascriptInterface public void checkUpdates() { a.h.post(() -> a.checkUpdates(true)); }

        @JavascriptInterface public void installUpdate() {
            new Thread(() -> {
                String err = Updater.downloadAndInstall(ctx);
                if ("needs-permission".equals(err)) {
                    a.h.post(a::openInstallSettings);
                    a.js("onUpdateStatus", "{\"state\":\"needs-permission\"}");
                } else if (err != null) {
                    a.js("onUpdateStatus", jsonErr(err));
                }
            }).start();
        }

        @JavascriptInterface public String permissions() { return a.permissionState(); }
        @JavascriptInterface public void requestPermissions() { a.h.post(a::requestRedialPermissions); }
        @JavascriptInterface public void openAppSettings() { a.h.post(a::openAppSettings); }
        @JavascriptInterface public void pickContact() { a.h.post(a::pickContact); }

        @JavascriptInterface public void setBars(String top, String bottom, boolean lightBottom) {
            a.h.post(() -> { try { a.setBars(top, bottom, lightBottom); } catch (Exception ignored) { } });
        }

        @JavascriptInterface public void vibrate(int ms) {
            try {
                Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
                if (v == null) return;
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                else v.vibrate(ms);
            } catch (Exception ignored) { }
        }

        @JavascriptInterface public void toast(String m) {
            a.h.post(() -> Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface public String redialStart(String number, String name, int attempts, int interval,
                                                       boolean stopWhenAnswered, boolean speaker) {
            if (number == null || number.replaceAll("[^0-9]", "").length() < 3) return "Vul een geldig telefoonnummer in";
            if (ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
                return "Geef eerst toestemming om te bellen";
            if (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
                return "Geef eerst toestemming om de telefoonstatus te lezen";
            Intent i = new Intent(ctx, RedialService.class).setAction(RedialService.ACTION_START)
                    .putExtra("number", number.trim())
                    .putExtra("name", name == null ? "" : name)
                    .putExtra("attempts", attempts)
                    .putExtra("interval", interval)
                    .putExtra("stopWhenAnswered", stopWhenAnswered)
                    .putExtra("speaker", speaker);
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
                return "";
            } catch (Exception e) {
                return "Starten mislukt: " + e.getMessage();
            }
        }

        @JavascriptInterface public void redialStop() {
            if (!RedialService.alive) return;
            ctx.startService(new Intent(ctx, RedialService.class).setAction(RedialService.ACTION_STOP));
        }

        @JavascriptInterface public void redialNow() {
            if (!RedialService.alive) return;
            ctx.startService(new Intent(ctx, RedialService.class).setAction(RedialService.ACTION_NOW));
        }

        @JavascriptInterface public String redialStatus() { return RedialService.status(ctx); }

        @JavascriptInterface public String pendingOpen() {
            String p = a.pendingOpen; a.pendingOpen = null; return p == null ? "" : p;
        }
    }
}
