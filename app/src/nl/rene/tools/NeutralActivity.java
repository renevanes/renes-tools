package nl.rene.tools;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowInsetsController;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * De neutrale versie van de app (na een verkeerde app-code, als dat zo is ingesteld): alleen neutrale
 * hulpmiddelen (Omrekenen), zonder enige toegang tot je gegevens. Bewust een eigen scherm zonder brug naar de
 * rest van de app: de pagina (assets/neutraal.html) kan niets lezen of starten, ook geen internet.
 * Gaat de neutrale versie uit beeld, dan sluit hij; de volgende keer opent de app weer met het slotscherm.
 */
public class NeutralActivity extends Activity {

    private WebView web;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int brand = dark ? 0xFF173F75 : 0xFF1E5AA8, bg = dark ? 0xFF0F141B : 0xFFF3F5F9;
        getWindow().setStatusBarColor(brand);
        getWindow().setNavigationBarColor(bg);
        if (Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
            getWindow().getInsetsController().setSystemBarsAppearance(dark ? 0 : WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }
        web = new WebView(this);
        web.setBackgroundColor(bg);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);      // alleen voor het rekenwerk in de pagina zelf
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setBlockNetworkLoads(true);      // niets van internet
        s.setDomStorageEnabled(false);
        s.setGeolocationEnabled(false);
        web.setWebViewClient(new Blocker());
        setContentView(web);
        String html;
        try (java.io.InputStream in = getAssets().open("neutraal.html")) { html = new String(SelfTest.readAll(in), java.nio.charset.StandardCharsets.UTF_8); }
        catch (Exception e) { html = "<html><body style='font-family:sans-serif;padding:24px'>Rene's Tools</body></html>"; }
        html = html.replace("__VERSION_NAME__", versionName());
        web.loadDataWithBaseURL("about:blank", html, "text/html", "utf-8", null);
        // Niet in "recente apps" met inhoud
        if (Build.VERSION.SDK_INT >= 33) {
            setRecentsScreenshotEnabled(false);
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
        }
    }

    /** Terug: eerst binnen de pagina (Omrekenen → overzicht), dan sluiten. */
    private void back() {
        if (web == null) { finish(); return; }
        web.evaluateJavascript("typeof goBack==='function'&&goBack()", v -> { if (!"true".equals(v)) finish(); });
    }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
    }

    /** Geen links of andere pagina's openen. */
    static final class Blocker extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView v, android.webkit.WebResourceRequest r) { return true; }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() { back(); } // Android 12 en ouder

    @Override
    protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations()) finish(); // weg = dicht: daarna opent de app weer met het slotscherm
    }

    @Override
    protected void onDestroy() {
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
