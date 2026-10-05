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
import android.provider.DocumentsContract;
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
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.MimeTypeMap;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    static final String ACTION_INSTALL_STATUS = "nl.rene.tools.INSTALL_STATUS";
    private static final int REQ_PERMS = 11;
    private static final int REQ_CONTACT = 12;
    private static final int REQ_TREE = 13;
    private static final int REQ_STORAGE = 14;
    private static final int REQ_CONTACTS = 15;
    private static final int REQ_LOCATION = 16;
    private static final int REQ_SMS = 17;
    private static final int REQ_CALLS = 18;
    private static final int REQ_CONTACTS_RW = 19;
    private static final int REQ_TR = 20;
    private static final int REQ_RECTREE = 21;
    private static final int REQ_MIC = 22;
    private static final int REQ_SETTINGS = 23;
    private static final int REQ_IMPORT = 24;
    private static final int REQ_HOME = 25;
    volatile String importKind = null;
    String pendingShare = null;
    volatile Uri pendingArchive = null;
    private static final String BASE_URL = "https://app.renes-tools.local/";

    private WebView web;
    /** De pagina heeft zichzelf getekend (met app-slot: het slotscherm staat er al). Tot dan blijft de WebView verborgen. */
    private boolean uiReady = false;
    private View topBar, bottomBar;
    private final Handler h = new Handler(Looper.getMainLooper());
    private String pendingOpen = null;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        RedialService.createChannel(this);
        WaBackupService.createChannel(this);
        WaBackupJob.ensureScheduled(this); // houdt de nachtelijke backup gepland
        AllBackupJob.ensureScheduled(this);
        TranscribeJob.ensureScheduled(this);

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
        s.setTextZoom(textZoom(this));
        web.setWebViewClient(new MediaClient(this));
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(this), "Android");
        web.setBackgroundColor(Color.parseColor("#F3F5F9"));

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, (OnBackInvokedCallback) this::handleBack);
        }

        pendingOpen = getIntent() != null ? getIntent().getStringExtra("open") : null;
        boolean fromHistory = getIntent() != null && (getIntent().getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0;
        if (b == null && !fromHistory && takeShare(getIntent())) pendingOpen = "share";
        playFromShortcut(getIntent());
        if (b == null && !fromHistory) RedialPlan.startMissed(this, getIntent());
        // Met app-slot: niets van de inhoud laten zien voordat het slotscherm er staat (anders flitst het startscherm).
        if (Lock.active(this)) {
            web.setVisibility(View.INVISIBLE);
            h.postDelayed(() -> { if (!uiReady) showUi(); }, 4000); // vangnet als de pagina het niet meldt
        }
        loadUi();
    }

    /** Pagina klaar (slotscherm al getekend als de app op slot is): nu pas tonen. */
    void showUi() {
        uiReady = true;
        if (web == null) return;
        if (Lock.locked(this)) web.evaluateJavascript("window.onLock&&window.onLock();true", v -> { if (web != null) web.setVisibility(View.VISIBLE); });
        else web.setVisibility(View.VISIBLE);
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
        playFromShortcut(i);
        RedialPlan.startMissed(this, i);
        if (takeShare(i)) { js("openTool", "\"share\""); return; }
        String open = i.getStringExtra("open");
        if (open != null) js("openTool", JSONObject.quote(open));
    }

    /** Gedeelde tekst uit een andere app bewaren voor een nieuwe notitie. */
    private boolean takeShare(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return false;
        CharSequence t = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (t == null) return false;
        try {
            String subj = i.getStringExtra(Intent.EXTRA_SUBJECT);
            String text = t.toString();
            if (text.length() > 20_000) text = text.substring(0, 20_000);
            pendingShare = new JSONObject().put("title", subj == null ? "" : subj).put("text", text).toString();
        } catch (Exception ignored) { }
        i.setAction(Intent.ACTION_MAIN); // niet nog eens verwerken
        return pendingShare != null;
    }

    /** Snelkoppeling naar een radiozender: meteen afspelen. */
    private void playFromShortcut(Intent i) {
        String play = i == null ? null : i.getStringExtra("play");
        if (play == null) return;
        try {
            JSONObject s = new JSONObject(play);
            String url = s.optString("url").toLowerCase();
            if (!url.startsWith("http://") && !url.startsWith("https://")) return;
            Radio.prefs(this).edit().putString("last", play).apply();
            RadioService.send(this, RadioService.PLAY, play);
        } catch (Exception ignored) { }
        i.removeExtra("play"); // niet opnieuw starten bij draaien van het scherm
    }

    @Override
    protected void onStart() {
        super.onStart();
        applySecure();
        if (web == null) return;
        boolean lock = Lock.onShown(this);
        if (!uiReady && Lock.active(this)) return; // eerste keer: showUi() toont hem zodra de pagina (met slotscherm) klaar is
        if (lock) {
            // Eerst het slotscherm tekenen, dan pas de WebView weer tonen (geen flits van de inhoud).
            web.evaluateJavascript("window.onLock&&window.onLock();true", v -> { if (web != null) web.setVisibility(View.VISIBLE); });
        } else web.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onStop() {
        Lock.onHidden();
        // Met app-slot de inhoud alvast verbergen; bij terugkomen beslist onStart of hij op slot moet.
        if (web != null && Lock.active(this) && !Lock.internalNav && !Lock.authBusy) web.setVisibility(View.INVISIBLE);
        super.onStop();
    }

    /** Elk scherm dat de app zelf opent (behalve links naar andere apps) telt als "binnen de app". */
    @Override
    public void startActivityForResult(Intent intent, int requestCode, android.os.Bundle options) {
        boolean external = intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null
                && ("http".equals(intent.getData().getScheme()) || "https".equals(intent.getData().getScheme()));
        if (!external) Lock.internalNav = true;
        super.startActivityForResult(intent, requestCode, options);
    }

    /** Met app-slot: inhoud niet tonen in "recente apps". */
    void applySecure() {
        boolean on = Lock.enabled(this);
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!on);
        else if (on) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
    }

    @Override
    protected void onPause() {
        super.onPause();
        js("onPauseApp", "");
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

    /** Tekstgrootte: zelf gekozen percentage, of (0) die van de telefoon volgen. */
    static int textZoom(Context c) {
        int z = c.getSharedPreferences("ui", MODE_PRIVATE).getInt("textZoom", 0);
        if (z >= 80 && z <= 200) return z;
        float fs = c.getResources().getConfiguration().fontScale;
        return Math.max(85, Math.min(200, Math.round(fs * 100)));
    }

    /** Roept window[fn](arg) aan in de pagina als die functie bestaat. */
    void js(String fn, String arg) {
        if (web == null) return;
        String code = "window." + fn + "&&window." + fn + "(" + arg + ")";
        h.post(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    private static String jsonErr(String m) {
        return "{\"state\":\"error\",\"error\":" + JSONObject.quote(m) + "}";
    }

    private void handleBack() {
        if (web == null) { finish(); return; }
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
        if (req == REQ_STORAGE || req == REQ_CONTACTS) js("onWaChanged", "");
        if (req == REQ_SMS) js("onSmsChanged", "");
        if (req == REQ_CALLS) js("onCallsChanged", "");
        if (req == REQ_CONTACTS_RW) js("onContactsChanged", "");
        if (req == REQ_TR) js("onTrChanged", "");
        if (req == REQ_MIC) js("onMusicChanged", "");
        if (req == REQ_SETTINGS) js("onSettingsChanged", "\"\"");
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
        if (req == REQ_TREE) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                Uri t = data.getData();
                String tid = "";
                try { tid = DocumentsContract.getTreeDocumentId(t); } catch (Exception ignored) { }
                if (tid.startsWith("primary:Android/media/com.whatsapp")) {
                    Toast.makeText(this, "Kies een map buiten de WhatsApp-map", Toast.LENGTH_LONG).show();
                    js("onWaChanged", "");
                    return;
                }
                try {
                    getContentResolver().takePersistableUriPermission(t,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    WaBackup.prefs(this).edit().putString("dest", t.toString()).apply();
                } catch (Exception e) {
                    Toast.makeText(this, "Deze map kan niet worden gebruikt", Toast.LENGTH_LONG).show();
                }
            }
            js("onWaChanged", "");
            return;
        }
        if (req == Lock.REQ_CONFIRM) { Lock.onConfirmResult(res == RESULT_OK); return; }
        if (req == REQ_HOME) { js("onHomeRole", ""); return; }
        if (req == REQ_IMPORT) {
            final String kind = importKind;
            if (res != RESULT_OK || data == null || data.getData() == null || kind == null) return;
            final Uri u = data.getData();
            if ("archive".equals(kind)) { pendingArchive = u; Secure.forget(); js("onArchivePicked", ""); return; }
            new Thread(() -> {
                String r;
                try { r = Restore.preview(getApplicationContext(), kind, u).toString(); }
                catch (Exception e) {
                    try { r = new JSONObject().put("error", e.getMessage() == null ? "Bestand niet te lezen" : e.getMessage()).toString(); }
                    catch (Exception e2) { r = "{\"error\":\"Bestand niet te lezen\"}"; }
                }
                js("onRestorePreview", r);
            }, "restore-preview").start();
            return;
        }
        if (req == REQ_RECTREE) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                // Boommap omzetten naar een gewoon pad (de app heeft toegang tot alle bestanden).
                try {
                    if (!"com.android.externalstorage.documents".equals(data.getData().getAuthority()))
                        throw new Exception("geen gewone map");
                    String tid = DocumentsContract.getTreeDocumentId(data.getData());
                    int c = tid.indexOf(':');
                    String vol = c < 0 ? tid : tid.substring(0, c), rel = c < 0 ? "" : tid.substring(c + 1);
                    File base = "primary".equals(vol) ? android.os.Environment.getExternalStorageDirectory() : new File("/storage/" + vol);
                    File dir = rel.isEmpty() ? base : new File(base, rel);
                    if (dir.isDirectory()) Transcribe.prefs(this).edit().putString("folder", dir.getAbsolutePath()).apply();
                    else Toast.makeText(this, "Deze map kan niet worden gebruikt", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Deze map kan niet worden gebruikt", Toast.LENGTH_LONG).show();
                }
            }
            js("onTrChanged", "");
            return;
        }
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

    static boolean hasFilesAccess(Context c) {
        if (Build.VERSION.SDK_INT >= 30) return android.os.Environment.isExternalStorageManager();
        return c.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                && c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    void requestFilesAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    void pickBackupFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try { startActivityForResult(i, REQ_TREE); }
        catch (ActivityNotFoundException e) { Toast.makeText(this, "Mappenkiezer niet beschikbaar", Toast.LENGTH_SHORT).show(); }
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
        static volatile boolean smsBusy = false;
        static volatile boolean callsBusy = false;
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
            return redialStart2(number, name, attempts, interval, 0, 0, stopWhenAnswered, speaker);
        }

        /** Zoals redialStart, maar met willekeurige wachttijd tussen randomMin en randomMax seconden (0 = vast). */
        @JavascriptInterface public String redialStart2(String number, String name, int attempts, int interval,
                                                        int randomMin, int randomMax,
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
                    .putExtra("randomMin", randomMin)
                    .putExtra("randomMax", randomMax)
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

        // ----- Auto redial op een tijdstip -----
        @JavascriptInterface public String redialPlanSet(String json) { return RedialPlan.set(ctx, json); }
        @JavascriptInterface public void redialPlanClear() { RedialPlan.clear(ctx); }
        @JavascriptInterface public String redialPlanState() { return RedialPlan.stateJson(ctx); }

        // ----- Automatisch uitschrijven -----
        @JavascriptInterface public void txAutoSet(boolean on) { TranscribeJob.setEnabled(ctx, on); }
        @JavascriptInterface public String txAutoState() {
            try {
                android.content.SharedPreferences p = Transcribe.prefs(ctx);
                return new JSONObject().put("on", TranscribeJob.enabled(ctx)).put("next", p.getLong("autoNext", 0))
                        .put("last", p.getLong("autoLast", 0)).put("found", p.getInt("autoFound", 0))
                        .put("failed", TranscribeJob.failed(ctx).size()).put("model", Transcribe.activeModel(ctx) != null)
                        .put("blocked", p.getBoolean("autoBlocked", false)).put("battery", SelfTest.battery(ctx).optString("status").equals("ok")).toString();
            } catch (Exception e) { return "{}"; }
        }
        @JavascriptInterface public void txAutoRetryFailed() { Transcribe.prefs(ctx).edit().remove("failedIds").apply(); }

        // ----- WhatsApp backup -----

        @JavascriptInterface public String waInfo() {
            try {
                android.content.SharedPreferences p = WaBackup.prefs(ctx);
                JSONObject o = new JSONObject();
                o.put("filesAccess", hasFilesAccess(ctx));
                org.json.JSONArray src = new org.json.JSONArray();
                for (WaBackup.Source s : WaBackup.sources())
                    if (s.dir != null) src.put(new JSONObject().put("name", s.name).put("path", s.dir.getAbsolutePath()));
                o.put("sources", src);
                o.put("dest", WaBackup.destUri(ctx) != null);
                o.put("destName", WaBackup.destName(ctx));
                o.put("auto", p.getBoolean("auto", false));
                o.put("autoCharging", p.getBoolean("autoCharging", true));
                o.put("autoMode", p.getString("autoMode", "all"));
                o.put("sel", p.getString("sel", "chats,images,voice,documents"));
                o.put("nextAuto", p.getLong("nextAuto", 0));
                o.put("lastOk", p.getLong("lastOk", 0));
                o.put("history", new org.json.JSONArray(p.getString("history", "[]")));
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface public void waRequestFilesAccess() { a.h.post(a::requestFilesAccess); }
        @JavascriptInterface public void waPickFolder() { a.h.post(a::pickBackupFolder); }

        @JavascriptInterface public void waScanSizes() {
            new Thread(() -> {
                String r;
                try { r = WaBackup.sizes().toString(); } catch (Exception e) { r = "null"; }
                a.js("onWaSizes", r);
            }).start();
        }

        @JavascriptInterface public void waSaveSettings(boolean auto, boolean charging, String autoMode, String sel) {
            WaBackup.prefs(ctx).edit().putBoolean("auto", auto).putBoolean("autoCharging", charging)
                    .putString("autoMode", autoMode).putString("sel", sel).apply();
            // Tijdens een lopende backup niet opnieuw plannen (dat zou hem stoppen); hij plant zichzelf na afloop.
            if (!WaBackup.busy) WaBackupJob.schedule(ctx);
        }

        @JavascriptInterface public String waStart(String mode, String cats) {
            if (WaBackup.busy) return "Er loopt al een backup";
            if (!hasFilesAccess(ctx)) return "Geef eerst toegang tot bestanden";
            if (WaBackup.destUri(ctx) == null) return "Kies eerst een backup-map";
            if (cats == null || cats.trim().isEmpty()) return "Kies minstens één onderdeel";
            Intent i = new Intent(ctx, WaBackupService.class).setAction(WaBackupService.ACTION_BACKUP)
                    .putExtra("mode", mode).putExtra("cats", "all".equals(mode) ? WaBackup.ALL : cats);
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
                return "";
            } catch (Exception e) { return "Starten mislukt: " + e.getMessage(); }
        }

        @JavascriptInterface public String waRestore() {
            if (WaBackup.busy) return "Er loopt al een backup";
            if (!hasFilesAccess(ctx)) return "Geef eerst toegang tot bestanden";
            if (WaBackup.destUri(ctx) == null) return "Kies eerst de map met de backup";
            Intent i = new Intent(ctx, WaBackupService.class).setAction(WaBackupService.ACTION_RESTORE);
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
                return "";
            } catch (Exception e) { return "Starten mislukt: " + e.getMessage(); }
        }

        @JavascriptInterface public void waCancel() { WaBackup.cancel = true; }
        @JavascriptInterface public String waStatus() { return WaBackup.status(ctx); }

        // ----- Leesbare chats -----

        @JavascriptInterface public String waReadInfo() {
            try {
                android.content.SharedPreferences p = WaBackup.prefs(ctx);
                JSONObject o = new JSONObject();
                o.put("hasKey", WaChats.key(ctx) != null);
                o.put("auto", p.getBoolean("readableAuto", true));
                o.put("hasDb", WaChats.dbFile(ctx).exists());
                o.put("dbFrom", p.getLong("waDbFrom", 0));
                o.put("dest", WaBackup.destUri(ctx) != null);
                o.put("contacts", ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED);
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }

        /** Controleert de sleutel door de nieuwste backup te ontsleutelen; resultaat via onWaKeyResult. */
        @JavascriptInterface public void waSetKey(final String hex) {
            new Thread(() -> {
                JSONObject r = new JSONObject();
                try {
                    if (!hasFilesAccess(ctx)) throw new Exception("Geef eerst toegang tot bestanden");
                    WaCrypt.parseKey(hex);
                    WaChats.decryptLatest(ctx, hex);
                    WaBackup.prefs(ctx).edit().putString("waKey", hex.toLowerCase().replaceAll("[^0-9a-f]", "")).apply();
                    int chats = new JSONObject(WaChats.chatsJson(ctx)).getJSONArray("chats").length();
                    r.put("ok", true);
                    r.put("chats", chats);
                } catch (Exception e) {
                    try { r.put("ok", false); r.put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) { }
                }
                a.js("onWaKeyResult", r.toString());
            }).start();
        }

        @JavascriptInterface public void waForgetKey() {
            WaBackup.prefs(ctx).edit().remove("waKey").remove("waDbFrom").apply();
            WaChats.closeDb();
            WaChats.dbFile(ctx).delete();
        }

        @JavascriptInterface public void waSetReadableAuto(boolean on) {
            WaBackup.prefs(ctx).edit().putBoolean("readableAuto", on).apply();
        }

        @JavascriptInterface public String waMakeReadable() {
            if (WaBackup.busy) return "Er loopt al een backup";
            if (WaChats.key(ctx) == null) return "Vul eerst je sleutel in";
            if (!hasFilesAccess(ctx)) return "Geef eerst toegang tot bestanden";
            Intent i = new Intent(ctx, WaBackupService.class).setAction(WaBackupService.ACTION_READABLE);
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
                return "";
            } catch (Exception e) { return "Starten mislukt: " + e.getMessage(); }
        }

        @JavascriptInterface public String waChats() {
            try { return WaChats.chatsJson(ctx); } catch (Exception e) { return errJson(e); }
        }

        @JavascriptInterface public String waMessages(String chatId, String before, int limit) {
            try { return WaChats.messagesJson(ctx, Long.parseLong(chatId), Long.parseLong(before), limit); }
            catch (Exception e) { return errJson(e); }
        }

        @JavascriptInterface public String waSearch(String q) {
            try { return WaChats.searchJson(ctx, q); } catch (Exception e) { return errJson(e); }
        }

        @JavascriptInterface public void waRequestContacts() {
            a.h.post(() -> a.requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQ_CONTACTS));
        }

        private static String errJson(Exception e) {
            try { return new JSONObject().put("error", String.valueOf(e.getMessage())).toString(); } catch (Exception x) { return "{}"; }
        }

        // ----- Mijn routes -----

        @JavascriptInterface public boolean trackHasPermission() {
            return ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }

        @JavascriptInterface public void trackRequestPermission() {
            a.h.post(() -> {
                java.util.ArrayList<String> l = new java.util.ArrayList<>();
                l.add(Manifest.permission.ACCESS_FINE_LOCATION);
                l.add(Manifest.permission.ACCESS_COARSE_LOCATION);
                if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS);
                a.requestPermissions(l.toArray(new String[0]), REQ_LOCATION);
            });
        }

        @JavascriptInterface public boolean trackGpsOn() {
            try {
                android.location.LocationManager lm = (android.location.LocationManager) ctx.getSystemService(LOCATION_SERVICE);
                return lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
                        || lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER);
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface public void trackOpenLocationSettings() {
            a.h.post(() -> { try { a.startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); } catch (Exception ignored) { } });
        }

        @JavascriptInterface public String trackStart() {
            if (TracksService.running) return "Er loopt al een opname";
            if (!trackHasPermission()) return "Geef eerst toestemming voor je locatie";
            Intent i = new Intent(ctx, TracksService.class).setAction(TracksService.ACTION_START);
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
                return "";
            } catch (Exception e) { return "Starten mislukt: " + e.getMessage(); }
        }

        @JavascriptInterface public void trackPause() { send(TracksService.ACTION_PAUSE); }
        @JavascriptInterface public void trackResume() { send(TracksService.ACTION_RESUME); }
        @JavascriptInterface public void trackStop() { send(TracksService.ACTION_STOP); }

        private void send(String action) {
            try { ctx.startService(new Intent(ctx, TracksService.class).setAction(action)); } catch (Exception ignored) { }
        }

        @JavascriptInterface public String trackStatus() { return TracksService.status(ctx); }

        /** Na stoppen: slaat de opgenomen route op onder een titel. Geeft "" of een fout. */
        @JavascriptInterface public String trackSave(String title) {
            try {
                android.content.SharedPreferences p = Tracks.prefs(ctx);
                long startT = p.getLong("lastStartT", System.currentTimeMillis());
                Tracks.finalize(ctx, startT, title);
                p.edit().remove("justStopped").apply();
                return "";
            } catch (Exception e) { return "Opslaan mislukt: " + e.getMessage(); }
        }

        @JavascriptInterface public void trackDiscard() {
            Tracks.liveFile(ctx).delete();
            Tracks.prefs(ctx).edit().remove("justStopped").apply();
        }

        @JavascriptInterface public String trackLivePoints() {
            return Tracks.liveLineJson(ctx);
        }

        /** Opent een http(s)- of geo-link in de bijbehorende app (bijv. Google Maps). */
        @JavascriptInterface public void openUrl(String url) {
            if (url == null) return;
            String low = url.toLowerCase();
            if (!low.startsWith("https://") && !low.startsWith("http://") && !low.startsWith("geo:")) return;
            a.h.post(() -> {
                try {
                    a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception e) {
                    Toast.makeText(ctx, "Geen app gevonden om dit te openen", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface public String trackJustStopped() {
            android.content.SharedPreferences p = Tracks.prefs(ctx);
            if (!p.getBoolean("justStopped", false)) return "";
            try {
                JSONObject o = new JSONObject();
                o.put("points", p.getInt("lastPoints", 0));
                o.put("stats", new JSONObject(p.getString("lastStats", "{}")));
                return o.toString();
            } catch (Exception e) { return ""; }
        }

        @JavascriptInterface public String trackList() {
            try { return Tracks.listJson(ctx); } catch (Exception e) { return "[]"; }
        }

        @JavascriptInterface public String trackDetail(String id, int w, int h) {
            try { return Tracks.detailJson(ctx, id, w, h); } catch (Exception e) { return errJson(e); }
        }

        @JavascriptInterface public String trackRename(String id, String title) {
            File f = Tracks.byId(ctx, id);
            if (f == null) return "Route niet gevonden";
            return Tracks.rename(ctx, f, title) ? "" : "Naam wijzigen lukt niet";
        }

        @JavascriptInterface public String trackDelete(String id) {
            File f = Tracks.byId(ctx, id);
            if (f == null) return "Route niet gevonden";
            return f.delete() ? "" : "Verwijderen lukt niet";
        }

        @JavascriptInterface public String trackExportBackup(String id) {
            File f = Tracks.byId(ctx, id);
            if (f == null) return "Route niet gevonden";
            if (WaBackup.destUri(ctx) == null) return "Kies eerst een backup-map (bij WhatsApp backup)";
            try { return Tracks.exportToBackup(ctx, f) ? "" : "Export mislukt"; }
            catch (Exception e) { return "Export mislukt: " + e.getMessage(); }
        }

        @JavascriptInterface public String trackShare(String id) {
            File f = Tracks.byId(ctx, id);
            if (f == null) return "Route niet gevonden";
            a.h.post(() -> {
                try {
                    File gpx = Tracks.writeGpxFile(ctx, f);
                    Uri u = Uri.parse("content://" + a.getPackageName() + ".files/" + gpx.getName());
                    Intent i = new Intent(Intent.ACTION_SEND).setType("application/gpx+xml")
                            .putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    a.startActivity(Intent.createChooser(i, "Route delen"));
                } catch (Exception e) {
                    Toast.makeText(ctx, "Delen lukt niet: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
            return "";
        }

        // ----- SMS-backup -----

        @JavascriptInterface public boolean smsHasPermission() {
            return ctx.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
        }

        @JavascriptInterface public void smsRequestPermission() {
            a.h.post(() -> a.requestPermissions(new String[]{Manifest.permission.READ_SMS}, REQ_SMS));
        }

        @JavascriptInterface public String smsInfo() {
            try {
                JSONObject o = new JSONObject();
                o.put("perm", smsHasPermission());
                o.put("dest", WaBackup.destUri(ctx) != null);
                o.put("contacts", ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED);
                o.put("lastExport", Sms.prefs(ctx).getLong("lastExport", 0));
                o.put("lastCount", Sms.prefs(ctx).getInt("lastCount", 0));
                o.put("total", smsHasPermission() ? Sms.countTotal(ctx) : 0);
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface public void smsRequestContacts() {
            a.h.post(() -> a.requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQ_SMS));
        }

        @JavascriptInterface public String smsConversations() {
            try { return Sms.conversationsJson(ctx); } catch (Exception e) { return errJson(e); }
        }

        @JavascriptInterface public String smsMessages(String threadId) {
            try { return Sms.messagesJson(ctx, Long.parseLong(threadId)); } catch (Exception e) { return errJson(e); }
        }

        @JavascriptInterface public String smsSearch(String q) {
            try { return Sms.searchJson(ctx, q); } catch (Exception e) { return errJson(e); }
        }

        @JavascriptInterface public String smsExport() {
            if (smsBusy) return "Er loopt al een export";
            if (!smsHasPermission()) return "Geef eerst toegang tot sms";
            if (WaBackup.destUri(ctx) == null) return "Kies eerst een backup-map (bij WhatsApp backup)";
            smsBusy = true;
            Sms.prefs(ctx).edit().putString("status", "{\"running\":true,\"done\":0,\"total\":0}").apply();
            new Thread(() -> {
                String err = null; int n = 0;
                try {
                    n = Sms.export(ctx, (done, total) -> {
                        try { Sms.prefs(ctx).edit().putString("status",
                                new JSONObject().put("running", true).put("done", done).put("total", total).toString()).apply(); } catch (Exception ignored) { }
                    });
                } catch (Throwable e) {
                    err = e.getMessage() != null ? e.getMessage() : "onvoldoende geheugen of fout";
                } finally {
                    try {
                        JSONObject o = new JSONObject().put("running", false);
                        if (err == null) o.put("ok", true).put("count", n); else o.put("ok", false).put("error", err);
                        Sms.prefs(ctx).edit().putString("status", o.toString()).apply();
                    } catch (Exception ignored) { }
                    smsBusy = false;
                }
            }, "sms-export").start();
            return "";
        }

        @JavascriptInterface public String smsStatus() {
            return Sms.prefs(ctx).getString("status", "{\"running\":false}");
        }

        // ----- Oproepen-backup -----

        @JavascriptInterface public boolean callsHasPermission() {
            return ctx.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED;
        }

        @JavascriptInterface public void callsRequestPermission() {
            a.h.post(() -> a.requestPermissions(new String[]{Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS}, REQ_CALLS));
        }

        @JavascriptInterface public String callsInfo() {
            try {
                JSONObject o = new JSONObject();
                o.put("perm", callsHasPermission());
                o.put("dest", WaBackup.destUri(ctx) != null);
                o.put("contacts", ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED);
                o.put("lastExport", Calls.prefs(ctx).getLong("lastExport", 0));
                o.put("lastCount", Calls.prefs(ctx).getInt("lastCount", 0));
                o.put("total", callsHasPermission() ? Calls.countTotal(ctx) : 0);
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface public String callsList(String filterJson) {
            try { return Calls.listJson(ctx, filterJson, 500); } catch (Exception e) { return errJson(e); }
        }

        @JavascriptInterface public String callsExport(String filterJson) {
            if (callsBusy) return "Er loopt al een export";
            if (!callsHasPermission()) return "Geef eerst toegang tot de oproepgeschiedenis";
            if (WaBackup.destUri(ctx) == null) return "Kies eerst een backup-map (bij WhatsApp backup)";
            callsBusy = true;
            Calls.prefs(ctx).edit().putString("status", "{\"running\":true,\"done\":0,\"total\":0}").apply();
            new Thread(() -> {
                String err = null; int n = 0;
                try {
                    n = Calls.export(ctx, filterJson, (done, total) -> {
                        try { Calls.prefs(ctx).edit().putString("status",
                                new JSONObject().put("running", true).put("done", done).put("total", total).toString()).apply(); } catch (Exception ignored) { }
                    });
                } catch (Throwable e) {
                    err = e.getMessage() != null ? e.getMessage() : "onvoldoende geheugen of fout";
                } finally {
                    try {
                        JSONObject o = new JSONObject().put("running", false);
                        if (err == null) o.put("ok", true).put("count", n); else o.put("ok", false).put("error", err);
                        Calls.prefs(ctx).edit().putString("status", o.toString()).apply();
                    } catch (Exception ignored) { }
                    callsBusy = false;
                }
            }, "calls-export").start();
            return "";
        }

        @JavascriptInterface public String callsStatus() {
            return Calls.prefs(ctx).getString("status", "{\"running\":false}");
        }

        // ----- Notities -----

        @JavascriptInterface public String notesLoad() { return Notes.load(ctx); }

        @JavascriptInterface public String notesSave(String json) { return Notes.save(ctx, json); }

        @JavascriptInterface public String notesExport() {
            try { return "ok:" + Notes.export(ctx); } catch (Exception e) { return e.getMessage() != null ? e.getMessage() : "Mislukt"; }
        }

        // ----- Contacten -----

        private boolean contactsRead() {
            return ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
        }

        @JavascriptInterface public String contactsInfo() {
            try {
                JSONObject o = new JSONObject();
                o.put("perm", contactsRead());
                o.put("write", ctx.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED);
                o.put("dest", WaBackup.destUri(ctx) != null);
                o.put("lastCheck", ctx.getSharedPreferences("contacts", Context.MODE_PRIVATE).getLong("lastCheck", 0));
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface public void contactsRequestPermission() {
            a.h.post(() -> a.requestPermissions(new String[]{Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS}, REQ_CONTACTS_RW));
        }

        /** Legt een nieuwe versie vast als er iets veranderd is; geeft het versienummer of 0. */
        @JavascriptInterface public String contactsSnapshot() {
            if (!contactsRead()) return "{\"error\":\"Geen toegang tot contacten\"}";
            try {
                ContactsJob.ensureScheduled(ctx);
                return new JSONObject().put("v", Contacts.snapshot(ctx)).toString();
            } catch (Exception e) { return errJson(e); }
        }

        @JavascriptInterface public String contactsList(String q) {
            try { return Contacts.listJson(ctx, q); } catch (Exception e) { return errJson(e); }
        }

        @JavascriptInterface public String contactsDetail(String key, String id) {
            try { return Contacts.detailJson(ctx, key, id == null || id.isEmpty() ? -1 : Long.parseLong(id)); } catch (Exception e) { return errJson(e); }
        }

        @JavascriptInterface public String contactsVersions() {
            try { return Contacts.versionsJson(ctx); } catch (Exception e) { return errJson(e); }
        }

        @JavascriptInterface public String contactsVersion(String v) {
            try { return Contacts.versionJson(ctx, Integer.parseInt(v)); } catch (Exception e) { return errJson(e); }
        }

        @JavascriptInterface public String contactsRestore(String v, String key) {
            if (ctx.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED)
                return "Geef eerst toestemming om contacten te wijzigen";
            try {
                String n = Contacts.restoreRemoved(ctx, Integer.parseInt(v), key);
                Contacts.snapshot(ctx);
                return "ok:" + n;
            } catch (Exception e) { return e.getMessage() != null ? e.getMessage() : "Terugzetten mislukt"; }
        }

        @JavascriptInterface public String contactsExport(String v) {
            try { return "ok:" + Contacts.export(ctx, Integer.parseInt(v)); }
            catch (Exception e) { return e.getMessage() != null ? e.getMessage() : "Exporteren mislukt"; }
        }

        /** Opent de contacten-app met een nieuw contact waarin dit nummer al is ingevuld. */
        @JavascriptInterface public void contactsAddNumber(String number) {
            a.h.post(() -> {
                try {
                    android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_INSERT, android.provider.ContactsContract.Contacts.CONTENT_URI);
                    i.putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, number);
                    a.startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(a, "Contacten-app openen lukt niet", Toast.LENGTH_LONG).show();
                }
            });
        }

        /** Opent de contacten-app om een contact te bewerken (key) of een nieuw contact te maken (leeg). */
        @JavascriptInterface public void contactsEdit(String key, String id) {
            a.h.post(() -> {
                try {
                    android.content.Intent i;
                    if (key == null || key.isEmpty()) {
                        i = new android.content.Intent(android.content.Intent.ACTION_INSERT, android.provider.ContactsContract.Contacts.CONTENT_URI);
                    } else {
                        android.net.Uri u = android.provider.ContactsContract.Contacts.getLookupUri(Long.parseLong(id), key);
                        i = new android.content.Intent(android.content.Intent.ACTION_EDIT).setDataAndType(u, android.provider.ContactsContract.Contacts.CONTENT_ITEM_TYPE);
                        i.putExtra("finishActivityOnSaveCompleted", true);
                    }
                    a.startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(a, "Contacten-app openen lukt niet", Toast.LENGTH_LONG).show();
                }
            });
        }

        // ----- Gesprekken uitschrijven -----

        @JavascriptInterface public String txInfo() {
            try {
                JSONObject o = new JSONObject();
                o.put("supported", Transcribe.supported());
                o.put("files", hasFilesAccess(ctx));
                o.put("calls", ctx.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED);
                o.put("dest", WaBackup.destUri(ctx) != null);
                o.put("folder", Transcribe.prefs(ctx).getString("folder", ""));
                o.put("lang", Transcribe.prefs(ctx).getString("lang", "nl"));
                Transcribe.Model act = Transcribe.activeModel(ctx);
                o.put("model", act == null ? "" : act.id);
                JSONArray ms = new JSONArray();
                for (Transcribe.Model m : Transcribe.MODELS)
                    ms.put(new JSONObject().put("id", m.id).put("label", m.label).put("installed", Transcribe.modelFile(ctx, m).isFile()));
                o.put("models", ms);
                o.put("busy", TranscribeService.busy);
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface public void txRequestFiles() { a.h.post(a::requestFilesAccess); }

        @JavascriptInterface public void txRequestCalls() {
            a.h.post(() -> a.requestPermissions(new String[]{Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS}, REQ_TR));
        }

        @JavascriptInterface public void txPickFolder() {
            a.h.post(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                try { a.startActivityForResult(i, REQ_RECTREE); }
                catch (ActivityNotFoundException e) { Toast.makeText(a, "Mappenkiezer niet beschikbaar", Toast.LENGTH_SHORT).show(); }
            });
        }

        @JavascriptInterface public void txClearFolder() { Transcribe.prefs(ctx).edit().remove("folder").apply(); }

        @JavascriptInterface public void txSetModel(String id) {
            if (Transcribe.model(id) != null) Transcribe.prefs(ctx).edit().putString("model", id).apply();
        }

        @JavascriptInterface public void txSetLang(String lang) {
            if ("nl".equals(lang) || "en".equals(lang) || "auto".equals(lang)) Transcribe.prefs(ctx).edit().putString("lang", lang).apply();
        }

        @JavascriptInterface public String txDownload(String id) {
            if (Transcribe.model(id) == null) return "Onbekend model";
            if (TranscribeService.busy) return "Er loopt al iets; wacht tot dat klaar is";
            Transcribe.prefs(ctx).edit().putString("status", "{\"running\":true,\"phase\":\"download\",\"pct\":0}").apply();
            TranscribeService.download(ctx, id);
            return "";
        }

        @JavascriptInterface public void txDeleteModel(String id) {
            Transcribe.Model m = Transcribe.model(id);
            if (m != null && !TranscribeService.busy) Transcribe.modelFile(ctx, m).delete();
        }

        @JavascriptInterface public String txList() {
            if (!hasFilesAccess(ctx)) return "{\"error\":\"Geef eerst toegang tot bestanden\"}";
            try { return Transcribe.listJson(ctx); } catch (Exception e) { return errJson(e); }
        }

        /** paths: JSON-array met paden van opnames. */
        @JavascriptInterface public String txStart(String paths) {
            if (!Transcribe.supported()) return "Deze telefoon wordt niet ondersteund (64-bit ARM nodig)";
            if (Transcribe.activeModel(ctx) == null) return "Download eerst een spraakmodel";
            try {
                JSONArray a2 = new JSONArray(paths);
                java.util.ArrayList<String> l = new java.util.ArrayList<>();
                for (int i = 0; i < a2.length(); i++) if (new File(a2.getString(i)).isFile()) l.add(a2.getString(i));
                if (l.isEmpty()) return "Geen opnames gekozen";
                Transcribe.prefs(ctx).edit().putString("status", "{\"running\":true,\"phase\":\"decode\",\"pct\":0}").apply();
                TranscribeService.run(ctx, l);
                return "";
            } catch (Exception e) { return e.getMessage() != null ? e.getMessage() : "Starten mislukt"; }
        }

        @JavascriptInterface public void txCancel() {
            Transcribe.cancel = true;
            Process p = Transcribe.proc;
            if (p != null) p.destroy();
        }

        @JavascriptInterface public String txStatus() {
            String s = Transcribe.prefs(ctx).getString("status", "{\"running\":false}");
            if (!TranscribeService.busy && s.contains("\"running\":true")) return "{\"running\":false}";
            return s;
        }

        @JavascriptInterface public String txGet(String id) {
            if (id == null || !id.matches("[0-9a-f]+")) return "{\"error\":\"Transcript niet gevonden\"}";
            JSONObject t = Transcribe.loadTranscript(ctx, id);
            return t == null ? "{\"error\":\"Transcript niet gevonden\"}" : t.toString();
        }

        @JavascriptInterface public String txSearch(String q) {
            try { return Transcribe.searchJson(ctx, q); } catch (Exception e) { return errJson(e); }
        }

        @JavascriptInterface public String txExport(String id) {
            try { return "ok:" + Transcribe.export(ctx, id); }
            catch (Exception e) { return e.getMessage() != null ? e.getMessage() : "Exporteren mislukt"; }
        }

        @JavascriptInterface public void txDelete(String id) {
            if (id != null && id.matches("[0-9a-f]+")) Transcribe.delete(ctx, id);
        }

        // Afspelen van de opname bij een transcript (tik op een zin = daarheen springen).
        private static android.media.MediaPlayer player;
        private static String playerPath;

        @JavascriptInterface public String txPlay(String id, String ms) {
            if (id == null || !id.matches("[0-9a-f]+")) return "Opname niet gevonden";
            JSONObject t = Transcribe.loadTranscript(ctx, id);
            String path = t == null ? null : t.optString("path");
            if (path == null || !new File(path).isFile()) return "De opname is niet meer op de telefoon";
            try {
                synchronized (Bridge.class) {
                    if (player == null || !path.equals(playerPath)) {
                        if (player != null) player.release();
                        player = new android.media.MediaPlayer();
                        player.setDataSource(path);
                        player.prepare();
                        playerPath = path;
                    }
                    player.seekTo(Integer.parseInt(ms));
                    player.start();
                }
                return "";
            } catch (Exception e) {
                synchronized (Bridge.class) { if (player != null) player.release(); player = null; playerPath = null; }
                return "Afspelen lukt niet";
            }
        }

        @JavascriptInterface public void txPause() {
            synchronized (Bridge.class) { if (player != null && player.isPlaying()) player.pause(); }
        }

        @JavascriptInterface public String txPlayState() {
            synchronized (Bridge.class) {
                if (player == null) return "{\"playing\":false,\"pos\":0}";
                try { return "{\"playing\":" + player.isPlaying() + ",\"pos\":" + player.getCurrentPosition() + ",\"dur\":" + player.getDuration() + "}"; }
                catch (Exception e) { return "{\"playing\":false,\"pos\":0}"; }
            }
        }

        @JavascriptInterface public void txStop() {
            synchronized (Bridge.class) { if (player != null) player.release(); player = null; playerPath = null; }
        }

        // ----- Instellingen, foutrapport en app-slot -----

        private boolean has(String p) { return ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

        @JavascriptInterface public String settingsInfo() {
            try {
                JSONObject o = new JSONObject();
                o.put("dest", WaBackup.destUri(ctx) != null).put("destName", WaBackup.destName(ctx));
                o.put("audd", musicTokenHint());
                o.put("crashes", App.count(ctx));
                o.put("lock", new JSONObject(lockState()));
                JSONArray p = new JSONArray();
                p.put(perm("phone", "Bellen", "Auto redial", has(Manifest.permission.CALL_PHONE)));
                p.put(perm("calllog", "Oproepgeschiedenis", "Auto redial, Oproepen, Gesprekken", has(Manifest.permission.READ_CALL_LOG)));
                p.put(perm("contacts", "Contacten", "Namen, Contacten-tool", has(Manifest.permission.READ_CONTACTS) && has(Manifest.permission.WRITE_CONTACTS)));
                p.put(perm("sms", "Sms", "SMS-backup", has(Manifest.permission.READ_SMS)));
                p.put(perm("location", "Locatie", "Mijn routes", has(Manifest.permission.ACCESS_FINE_LOCATION)));
                p.put(perm("mic", "Microfoon", "Muziek herkennen", has(Manifest.permission.RECORD_AUDIO)));
                if (Build.VERSION.SDK_INT >= 33) p.put(perm("notif", "Meldingen", "Voortgang en bediening", has(Manifest.permission.POST_NOTIFICATIONS)));
                p.put(perm("files", "Alle bestanden", "WhatsApp backup, Gesprekken", hasFilesAccess(ctx)));
                if (Build.VERSION.SDK_INT >= 26) p.put(perm("install", "Updates installeren", "Nieuwe app-versies", ctx.getPackageManager().canRequestPackageInstalls()));
                o.put("perms", p);
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }

        private JSONObject perm(String id, String name, String used, boolean ok) throws Exception {
            return new JSONObject().put("id", id).put("name", name).put("used", used).put("ok", ok);
        }

        @JavascriptInterface public void permRequest(String id) {
            a.h.post(() -> {
                String[] p = null;
                switch (id == null ? "" : id) {
                    case "phone": p = new String[]{Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE}; break;
                    case "calllog": p = new String[]{Manifest.permission.READ_CALL_LOG}; break;
                    case "contacts": p = new String[]{Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS}; break;
                    case "sms": p = new String[]{Manifest.permission.READ_SMS}; break;
                    case "location": p = new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}; break;
                    case "mic": p = new String[]{Manifest.permission.RECORD_AUDIO}; break;
                    case "notif": if (Build.VERSION.SDK_INT >= 33) p = new String[]{Manifest.permission.POST_NOTIFICATIONS}; break;
                    case "files": a.requestFilesAccess(); return;
                    case "install": a.openInstallSettings(); return;
                    default: a.openAppSettings(); return;
                }
                if (p == null) return;
                // Al eens geweigerd met "niet meer vragen": Android toont geen vraag meer, dus naar de app-instellingen.
                android.content.SharedPreferences sp = ctx.getSharedPreferences("perm", Context.MODE_PRIVATE);
                if (sp.getBoolean(id, false) && !a.shouldShowRequestPermissionRationale(p[0])
                        && ctx.checkSelfPermission(p[0]) != PackageManager.PERMISSION_GRANTED) { a.openAppSettings(); return; }
                sp.edit().putBoolean(id, true).apply();
                a.requestPermissions(p, REQ_SETTINGS);
            });
        }

        @JavascriptInterface public void crashShare() {
            a.h.post(() -> {
                Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "Foutrapport Rene's Tools " + Version.NAME)
                        .putExtra(Intent.EXTRA_TEXT, App.report(ctx));
                try { a.startActivity(Intent.createChooser(i, "Foutrapport delen")); }
                catch (Exception e) { Toast.makeText(a, "Delen lukt niet", Toast.LENGTH_SHORT).show(); }
            });
        }

        /** Foutrapport naar het klembord (om bijv. in een chat te plakken). */
        @JavascriptInterface public void crashCopy() {
            a.h.post(() -> {
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Foutrapport Rene's Tools", App.report(ctx)));
                    if (Build.VERSION.SDK_INT < 33) Toast.makeText(a, "Foutrapport gekopieerd", Toast.LENGTH_SHORT).show(); // nieuwere Android meldt het zelf
                } catch (Exception e) { Toast.makeText(a, "Kopiëren lukt niet", Toast.LENGTH_SHORT).show(); }
            });
        }

        /** Eerste keer na installeren (niet na een update): dan geen "Wat is er nieuw". */
        @JavascriptInterface public boolean freshInstall() {
            try {
                android.content.pm.PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
                return pi.firstInstallTime == pi.lastUpdateTime;
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface public void crashClear() {
            App.file(ctx).delete();
            ctx.getSharedPreferences("crash", Context.MODE_PRIVATE).edit().remove("new").apply();
        }

        /** True als de app sinds de vorige keer is vastgelopen (één keer melden). */
        @JavascriptInterface public boolean crashNew() {
            boolean n = ctx.getSharedPreferences("crash", Context.MODE_PRIVATE).getBoolean("new", false);
            if (n) ctx.getSharedPreferences("crash", Context.MODE_PRIVATE).edit().remove("new").apply();
            return n;
        }

        /** Fout in de interface (window.onerror) vastleggen. */
        @JavascriptInterface public void logJs(String msg) {
            if (msg != null) App.log(ctx, "JS", msg.length() > 2000 ? msg.substring(0, 2000) : msg);
        }

        /** De pagina is opgebouwd (en toont het slotscherm als dat moet). */
        @JavascriptInterface public void uiReady() { a.h.postDelayed(a::showUi, 50); }
        @JavascriptInterface public String lockState() {
            try {
                return new JSONObject().put("on", Lock.enabled(ctx)).put("locked", Lock.locked(ctx))
                        .put("timeout", Lock.timeout(ctx)).put("secure", Lock.deviceSecure(ctx)).toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface public void lockUnlock() {
            a.h.post(() -> Lock.prompt(a, "Rene's Tools ontgrendelen", (ok, msg) -> {
                if (ok) { Lock.unlocked = true; a.js("onUnlocked", ""); }
                else a.js("onLockFail", JSONObject.quote(msg == null ? "" : msg));
            }));
        }

        /** App-slot aan- of uitzetten; vraagt eerst de schermvergrendeling ter bevestiging. */
        @JavascriptInterface public void lockSet(boolean on) {
            a.h.post(() -> Lock.prompt(a, on ? "App-slot aanzetten" : "App-slot uitzetten", (ok, msg) -> {
                if (ok) {
                    Lock.prefs(ctx).edit().putBoolean("on", on).apply();
                    Lock.unlocked = true;
                    a.applySecure();
                }
                a.js("onSettingsChanged", JSONObject.quote(ok ? "" : (msg == null ? "Niet bevestigd" : msg)));
            }));
        }

        @JavascriptInterface public void lockTimeout(String ms) {
            try {
                long v = Long.parseLong(ms);
                if (v >= 0 && v <= 3_600_000L) Lock.prefs(ctx).edit().putLong("timeout", v).apply();
            } catch (Exception ignored) { }
        }

        // ----- Alles back-uppen en terugzetten -----

        /** Overal zoeken; uitkomst via onSearchAll(json met id). Geeft het id terug. */
        @JavascriptInterface public int searchAll(String q) {
            final int id = Search.latest.incrementAndGet();
            final String query = q == null ? "" : q;
            Search.pool.execute(() -> Search.run(ctx, query, id, r -> a.js("onSearchAll", r.toString())));
            return id;
        }

        @JavascriptInterface public String backupState(boolean full) { return AllBackup.stateJson(ctx, full); }

        // ----- Versleutelen, opruimen, ruimtegebruik -----
        /** Wachtwoord instellen (sleutel afleiden duurt even); uitkomst via onSecure("" of foutmelding). */
        @JavascriptInterface public void backupSetPassword(String pw) {
            new Thread(() -> {
                String r = "";
                try { Secure.setPassword(ctx, pw); }
                catch (Exception e) { r = e.getMessage() != null ? e.getMessage() : "Instellen mislukt"; App.log(ctx, "SECURE", "wachtwoord: " + e); }
                a.js("onSecure", JSONObject.quote(r));
            }, "secure").start();
        }

        @JavascriptInterface public void backupEncryptOff() { Secure.off(ctx); }

        @JavascriptInterface public void backupSetRotate(boolean on) { AllBackup.prefs(ctx).edit().putBoolean("rotate", on).apply(); }

        /** Opruimen (dry = alleen tellen); uitkomst via onRotate({count, bytes} of {error}). */
        @JavascriptInterface public void backupRotate(boolean dry) {
            new Thread(() -> {
                String r;
                try { r = Secure.rotate(ctx, dry).put("dry", dry).toString(); }
                catch (Exception e) { r = errJson(e); }
                a.js("onRotate", r);
            }, "rotate").start();
        }

        @JavascriptInterface public void backupSpace() {
            new Thread(() -> {
                String r;
                try { r = Secure.space(ctx).toString(); }
                catch (Exception e) { r = errJson(e); }
                a.js("onSpace", r);
            }, "space").start();
        }

        /** Versleutelde backup openen: eerst kiezen (onArchivePicked), dan openen met of zonder wachtwoord (onArchive). */
        @JavascriptInterface public void archivePick() {
            a.importKind = "archive";
            a.h.post(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                try { a.startActivityForResult(i, REQ_IMPORT); }
                catch (ActivityNotFoundException e) { Toast.makeText(a, "Bestandskiezer niet beschikbaar", Toast.LENGTH_SHORT).show(); }
            });
        }

        @JavascriptInterface public void archiveOpen(String pw) {
            final Uri u = a.pendingArchive;
            new Thread(() -> {
                String r;
                try { r = Secure.open(ctx, u, pw).toString(); }
                catch (Exception e) { r = errJson(e); }
                a.js("onArchive", r);
            }, "archive").start();
        }

        /** Contacten of notities uit het geopende archief: daarna hetzelfde overzicht als bij gewoon terugzetten. */
        @JavascriptInterface public void archiveRestore(String kind, String entry) {
            if (!"contacts".equals(kind) && !"notes".equals(kind)) return;
            new Thread(() -> {
                String r;
                try { r = Restore.previewText(ctx, kind, Secure.readEntry(ctx, entry)).toString(); }
                catch (Exception e) { r = errJson(e); }
                a.js("onRestorePreview", r);
            }, "archive-restore").start();
        }

        @JavascriptInterface public void archiveUnpack() {
            new Thread(() -> {
                String r;
                try { r = "ok:" + Secure.unpack(ctx); }
                catch (Exception e) { r = e.getMessage() != null ? e.getMessage() : "Uitpakken mislukt"; App.log(ctx, "SECURE", "uitpakken: " + e); }
                a.js("onArchiveUnpacked", JSONObject.quote(r));
            }, "archive-unpack").start();
        }

        @JavascriptInterface public void archiveClose() { Secure.forget(); a.pendingArchive = null; }

        @JavascriptInterface public void backupSetPart(String part, boolean on) {
            for (String p : AllBackup.PARTS) if (p.equals(part)) AllBackup.prefs(ctx).edit().putBoolean("part_" + p, on).apply();
        }

        @JavascriptInterface public void backupSetAuto(boolean auto, boolean charging) {
            AllBackup.prefs(ctx).edit().putBoolean("auto", auto).putBoolean("charging", charging).apply();
            AllBackupJob.schedule(ctx, 0);
        }

        /** Start alles nu; met withWa ook de WhatsApp-backup (die loopt in zijn eigen service). */
        @JavascriptInterface public String backupStart(boolean withWa) {
            if (WaBackup.destUri(ctx) == null) return "Kies eerst een backup-map";
            if (!AllBackup.tryBegin()) return "Er loopt al een backup";
            new Thread(() -> {
                org.json.JSONObject r = AllBackup.run(ctx, false);
                a.js("onBackupDone", r.toString());
            }, "allbackup").start();
            if (withWa) {
                String w = waStart("all", WaBackup.ALL);
                if (!w.isEmpty()) return "WhatsApp: " + w;
            }
            return "";
        }

        /** Kies een backupbestand om terug te zetten (contacts = vCard, notes = notities.json). */
        @JavascriptInterface public void restorePick(String kind) {
            if (!"contacts".equals(kind) && !"notes".equals(kind)) return;
            a.importKind = kind;
            a.h.post(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                if ("contacts".equals(kind)) i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/x-vcard", "text/vcard", "text/directory", "application/octet-stream", "text/plain"});
                else i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "application/octet-stream", "text/plain"});
                try { a.startActivityForResult(i, REQ_IMPORT); }
                catch (ActivityNotFoundException e) { Toast.makeText(a, "Bestandskiezer niet beschikbaar", Toast.LENGTH_SHORT).show(); }
            });
        }

        /** Terugzetten op de achtergrond; uitkomst via window.onRestoreDone("ok:N" of foutmelding). */
        @JavascriptInterface public String restoreApply() {
            if ("contacts".equals(Restore.pendingKind) && ctx.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED)
                return "Geef eerst toestemming om contacten te wijzigen";
            new Thread(() -> {
                String r;
                try { r = "ok:" + Restore.apply(ctx); }
                catch (Exception e) { r = e.getMessage() != null ? e.getMessage() : "Terugzetten mislukt"; }
                a.js("onRestoreDone", JSONObject.quote(r));
            }, "restore").start();
            return "";
        }

        // ----- Radiowekker -----

        @JavascriptInterface public String alarmState() { return RadioAlarm.stateJson(ctx); }

        @JavascriptInterface public String alarmSet(boolean on, int hour, int minute, int days, String station) {
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59 || days < 0 || days > 127) return "Ongeldige tijd";
            android.content.SharedPreferences.Editor e = RadioAlarm.prefs(ctx).edit()
                    .putBoolean("on", on).putInt("hour", hour).putInt("minute", minute).putInt("days", days);
            if (station != null && !station.isEmpty()) {
                try {
                    JSONObject s = new JSONObject(station);
                    String url = s.optString("url").toLowerCase();
                    if (!url.startsWith("http://") && !url.startsWith("https://")) return "Kies een zender";
                    e.putString("station", Radio.slimFav(s).toString());
                } catch (Exception ex) { return "Kies een zender"; }
            }
            e.apply();
            if (on && RadioAlarm.prefs(ctx).getString("station", null) == null) return "Kies een zender";
            RadioAlarm.schedule(ctx);
            return "";
        }

        /** Proef: de wekker gaat over 10 seconden af. */
        @JavascriptInterface public String alarmTest() {
            if (RadioAlarm.prefs(ctx).getString("station", null) == null) return "Kies eerst een zender";
            RadioAlarm.setAt(ctx, System.currentTimeMillis() + 10_000L, true); // als snooze: verandert de echte wekker niet
            return "";
        }

        @JavascriptInterface public void alarmExactSettings() {
            if (Build.VERSION.SDK_INT >= 31) a.h.post(() -> {
                try { a.startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.getPackageName()))); }
                catch (Exception e) { a.openAppSettings(); }
            });
        }

        // ----- Notities: herinneringen, delen, ontvangen -----

        @JavascriptInterface public void noteRemind(String id, String title, String time) {
            try { Reminders.set(ctx, id, title, Long.parseLong(time)); } catch (Exception ignored) { }
        }

        @JavascriptInterface public String noteReminders() { return Reminders.all(ctx); }

        @JavascriptInterface public void noteShare(String title, String text) {
            a.h.post(() -> {
                Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
                if (title != null && !title.isEmpty()) i.putExtra(Intent.EXTRA_SUBJECT, title);
                try { a.startActivity(Intent.createChooser(i, "Notitie delen")); }
                catch (Exception e) { Toast.makeText(a, "Delen lukt niet", Toast.LENGTH_SHORT).show(); }
            });
        }

        /** Tekst die vanuit een andere app met Delen naar Rene's Tools is gestuurd (één keer ophalen). */
        @JavascriptInterface public String pendingShare() {
            String s = a.pendingShare;
            a.pendingShare = null;
            return s == null ? "" : s;
        }

        // ----- Zelftest -----

        @JavascriptInterface public String selfTestRun() {
            if (!SelfTest.busy.compareAndSet(false, true)) return "De zelftest loopt al";
            new Thread(() -> {
                try { a.js("onSelfTest", SelfTest.run(ctx).toString()); }
                catch (Throwable e) { App.log(ctx, "ZELFTEST", String.valueOf(e)); a.js("onSelfTest", "[]"); }
                finally { SelfTest.busy.set(false); }
            }, "selftest").start();
            return "";
        }

        // ----- Telefoon-skin -----
        @JavascriptInterface public boolean homeIsDefault() { return Launcher.isDefaultHome(ctx); }
        /** Vraagt Android om Rene's Tools als startscherm te gebruiken (of opent de instelling ervoor). */
        @JavascriptInterface public void homeMakeDefault() {
            a.h.post(() -> {
                if (Build.VERSION.SDK_INT >= 29) {
                    try {
                        android.app.role.RoleManager rm = a.getSystemService(android.app.role.RoleManager.class);
                        if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME) && !rm.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)) {
                            a.startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME), REQ_HOME);
                            return;
                        }
                    } catch (Exception ignored) { }
                }
                homeSettings();
            });
        }
        /** Naar de Android-instelling voor het standaard-startscherm (bijv. om terug te gaan naar dat van Oppo). */
        @JavascriptInterface public void homeSettings() {
            try { a.startActivity(new Intent(android.provider.Settings.ACTION_HOME_SETTINGS)); }
            catch (Exception e) { try { a.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)); } catch (Exception ignored) { } }
            Lock.internalNav = false; // je verlaat de app: het app-slot blijft gelden
        }
        @JavascriptInterface public void homeOpen() {
            try { a.startActivity(new Intent(ctx, HomeActivity.class).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
            Lock.internalNav = false;
        }

        @JavascriptInterface public int textZoomGet() { return ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getInt("textZoom", 0); }
        @JavascriptInterface public int textZoomActual() { return textZoom(ctx); }
        @JavascriptInterface public void textZoomSet(int z) {
            ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putInt("textZoom", z).apply();
            a.h.post(() -> { if (a.web != null) a.web.getSettings().setTextZoom(textZoom(ctx)); });
        }

        @JavascriptInterface public String selfTestLast() {
            return ctx.getSharedPreferences("selftest", Context.MODE_PRIVATE).getString("last", "{}");
        }

        /** Vraagt Android om de app niet te beperken op de achtergrond (belangrijk op Oppo). */
        @JavascriptInterface public void batterySettings() {
            a.h.post(() -> {
                try {
                    a.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.getPackageName())));
                } catch (Exception e) {
                    try { a.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (Exception e2) { a.openAppSettings(); }
                }
            });
        }

        @JavascriptInterface public void notificationSettings() {
            a.h.post(() -> {
                try {
                    if (Build.VERSION.SDK_INT >= 26) a.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.getPackageName()));
                    else a.openAppSettings();
                } catch (Exception e) { a.openAppSettings(); }
            });
        }

        // ----- Snelkoppelingen -----

        @JavascriptInterface public String shortcutPin(String tool, String label, String color, String glyph) {
            if (tool == null || !tool.matches("[a-z]{2,20}")) return "Onbekende tool";
            return Shortcuts.pin(ctx, "tool-" + tool, label, tool, null, color, glyph);
        }

        /** Snelkoppeling naar één notitie op het startscherm van de telefoon. */
        @JavascriptInterface public String shortcutPinNote(String id, String title) {
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) return "Onbekende notitie";
            String label = title == null || title.trim().isEmpty() ? "Notitie" : title.trim();
            return Shortcuts.pin(ctx, "note-" + id, label, "note:" + id, null, "#E67E22", "📝");
        }
        @JavascriptInterface public String notePins(String where) { return Notes.pinned(ctx, where).toString(); }
        @JavascriptInterface public void notePinSet(String where, String id, boolean on) { Notes.setPinned(ctx, where, id, on); }

        @JavascriptInterface public String shortcutPinStation(String station) {
            try {
                JSONObject s = new JSONObject(station);
                String url = s.optString("url").toLowerCase();
                if (!url.startsWith("http://") && !url.startsWith("https://")) return "Geen geldige zender";
                String id = s.optString("id").replaceAll("[^A-Za-z0-9-]", "");
                if (id.isEmpty()) id = Integer.toHexString(url.hashCode());
                return Shortcuts.pin(ctx, "station-" + id, s.optString("name", "Radio"), "radio",
                        Radio.slimFav(s).toString(), "#D35400", "📻");
            } catch (Exception e) { return "Snelkoppeling maken lukt niet"; }
        }

        /** items: JSON [[tool, label, color, glyph], ...] voor lang indrukken van het app-icoon. */
        @JavascriptInterface public void shortcutsDynamic(String items) {
            try {
                JSONArray a2 = new JSONArray(items);
                String[][] it = new String[a2.length()][];
                for (int i = 0; i < a2.length(); i++) {
                    JSONArray x = a2.getJSONArray(i);
                    it[i] = new String[]{x.getString(0), x.getString(1), x.getString(2), x.getString(3)};
                }
                Shortcuts.dynamic(ctx, it);
            } catch (Exception ignored) { }
        }

        // ----- Radio -----

        /** Laadt de zenderlijst op de achtergrond; resultaat via window.onRadioStations(json). */
        @JavascriptInterface public void radioLoad(String q) {
            new Thread(() -> {
                String r;
                try { r = new JSONObject().put("q", q == null ? "" : q).put("stations", new JSONArray(Radio.stations(ctx, q))).toString(); }
                catch (Exception e) {
                    try { r = new JSONObject().put("q", q == null ? "" : q).put("error", e.getMessage() == null ? "Zenderlijst niet bereikbaar" : e.getMessage()).toString(); }
                    catch (Exception e2) { r = "{\"error\":\"Zenderlijst niet bereikbaar\"}"; }
                }
                a.js("onRadioStations", r);
            }, "radio-load").start();
        }

        @JavascriptInterface public String radioCache() { return Radio.prefs(ctx).getString("cache", "[]"); }

        @JavascriptInterface public String radioFavorites() { return Radio.favorites(ctx).toString(); }

        @JavascriptInterface public boolean radioToggleFav(String station) {
            try {
                boolean on = Radio.toggleFavorite(ctx, station);
                a.h.post(() -> { RadioService s = RadioService.inst; if (s != null) s.notifyChildrenChanged("root"); }); // lijst in de auto bijwerken
                return on;
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface public void radioMoveFav(String id, int dir) {
            try { Radio.moveFavorite(ctx, id, dir); } catch (Exception ignored) { }
        }

        @JavascriptInterface public void radioPlay(String station) {
            try {
                JSONObject s = new JSONObject(station);
                String url = s.optString("url").toLowerCase();
                if (!url.startsWith("http://") && !url.startsWith("https://")) return;
                Radio.prefs(ctx).edit().putString("last", station).apply();
                RadioService.send(ctx, RadioService.PLAY, station);
                final String id = s.optString("id");
                new Thread(() -> Radio.click(ctx, id), "radio-click").start();
            } catch (Exception ignored) { }
        }

        @JavascriptInterface public void radioPause() { RadioService.send(ctx, RadioService.PAUSE, null); }
        /** Terugspoelen (rew), vooruit (fwd) of terug naar live (live) in de buffer. */
        @JavascriptInterface public void radioShift(String what) {
            if (RadioService.REW.equals(what) || RadioService.FWD.equals(what) || RadioService.LIVE.equals(what)) RadioService.send(ctx, what, null);
        }
        @JavascriptInterface public boolean radioTimeshift() { return Radio.prefs(ctx).getBoolean("timeshift", true); }
        @JavascriptInterface public void radioSetTimeshift(boolean on) { Radio.prefs(ctx).edit().putBoolean("timeshift", on).apply(); }

        @JavascriptInterface public void radioResume() {
            if (RadioService.station == null) {
                String last = Radio.prefs(ctx).getString("last", null);
                if (last != null) radioPlay(last);
                return;
            }
            RadioService.send(ctx, RadioService.RESUME, null);
        }

        @JavascriptInterface public void radioStop() { RadioService.send(ctx, RadioService.STOP, null); }

        @JavascriptInterface public void radioSleep(int minutes) { RadioService.send(ctx, RadioService.SLEEP, String.valueOf(minutes)); }

        @JavascriptInterface public String radioState() {
            String s = RadioService.stateJson();
            if (RadioService.station == null) {
                String last = Radio.prefs(ctx).getString("last", null);
                if (last != null) try { return new JSONObject(s).put("last", new JSONObject(last)).toString(); } catch (Exception ignored) { }
            }
            return s;
        }

        // ----- Muziek herkennen -----

        @JavascriptInterface public boolean musicHasMic() {
            return ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        }

        @JavascriptInterface public void musicRequestMic() {
            a.h.post(() -> a.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC));
        }

        @JavascriptInterface public void musicSetToken(String t) {
            Music.prefs(ctx).edit().putString("token", t == null ? "" : t.trim()).apply();
        }

        @JavascriptInterface public String musicTokenHint() {
            String t = Music.prefs(ctx).getString("token", "");
            return t.length() <= 6 ? (t.isEmpty() ? "" : "••••") : t.substring(0, 3) + "…" + t.substring(t.length() - 3);
        }

        @JavascriptInterface public String musicStart() {
            if (!musicHasMic()) return "Geef eerst toegang tot de microfoon";
            return Music.start(ctx, null, 0, null);
        }

        /** Herkent wat de radio nu speelt, met een stukje van de stream zelf (geen microfoon nodig). */
        @JavascriptInterface public String musicStartRadio() {
            String st = RadioService.station;
            if (st == null) return "Er speelt geen radio";
            try {
                JSONObject s = new JSONObject(st);
                return Music.start(ctx, s.optString("url"), s.optInt("bitrate"), s.optString("name"));
            } catch (Exception e) { return "Herkennen mislukt"; }
        }

        @JavascriptInterface public void musicCancel() { Music.cancel(); }

        @JavascriptInterface public String musicState() { return Music.stateJson(ctx); }

        @JavascriptInterface public String musicHistory() { return Music.history(ctx).toString(); }

        @JavascriptInterface public void musicDelete(String t) {
            try { Music.deleteHistory(ctx, Long.parseLong(t)); } catch (Exception ignored) { }
        }

        @JavascriptInterface public String pendingOpen() {
            String p = a.pendingOpen; a.pendingOpen = null; return p == null ? "" : p;
        }
    }

    /** Serveert foto's uit de WhatsApp-map en de meegeleverde kaartbibliotheek aan de WebView. */
    static final class MediaClient extends WebViewClient {
        private final Context ctx;
        private final Activity act;
        MediaClient(Activity c) { act = c; ctx = c.getApplicationContext(); }

        /** Weergaveproces gestopt (vastgelopen of door Android opgeruimd): scherm opnieuw opbouwen i.p.v. de hele app (en de radio) te laten vallen. */
        @Override
        public boolean onRenderProcessGone(WebView v, android.webkit.RenderProcessGoneDetail d) {
            App.log(ctx, "APP", "weergave gestopt" + (Build.VERSION.SDK_INT >= 26 && d.didCrash() ? " (vastgelopen)" : ""));
            try { ((android.view.ViewGroup) v.getParent()).removeView(v); } catch (Exception ignored) { }
            v.destroy();
            if (act instanceof MainActivity) ((MainActivity) act).web = null;
            if (!act.isFinishing() && !act.isDestroyed()) act.recreate();
            return true;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (u == null || !"app.renes-tools.local".equals(u.getHost()) || u.getPath() == null) return null;
            String path = u.getPath();
            // Kaartbibliotheek (Leaflet) uit de meegeleverde assets
            if (path.startsWith("/vendor/")) {
                String name = path.substring("/vendor/".length());
                if (name.contains("/") || name.contains("..")) return notFound();
                try {
                    String mime = name.endsWith(".css") ? "text/css" : name.endsWith(".js") ? "application/javascript" : "application/octet-stream";
                    return new WebResourceResponse(mime, "utf-8", ctx.getAssets().open("vendor/" + name));
                } catch (Exception e) { return notFound(); }
            }
            if (!path.startsWith("/wa-media/")) return null;
            try {
                File root = null;
                for (WaBackup.Source s : WaBackup.sources()) if ("WhatsApp".equals(s.name) && s.dir != null) root = s.dir;
                if (root == null) return notFound();
                File media = new File(root, "Media").getCanonicalFile();
                File f = new File(root, Uri.decode(u.getPath().substring("/wa-media/".length()))).getCanonicalFile();
                if (!f.getPath().startsWith(media.getPath() + File.separator) || !f.isFile()) return notFound();
                String ext = MimeTypeMap.getFileExtensionFromUrl(f.getName());
                String mime = ext == null ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());
                return new WebResourceResponse(mime != null ? mime : "application/octet-stream", null, new java.io.FileInputStream(f));
            } catch (Exception e) {
                return notFound();
            }
        }

        private static WebResourceResponse notFound() {
            WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8", new java.io.ByteArrayInputStream(new byte[0]));
            r.setStatusCodeAndReasonPhrase(404, "Not found");
            return r;
        }
    }
}
