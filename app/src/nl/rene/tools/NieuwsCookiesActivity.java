package nl.rene.tools;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayInputStream;

/**
 * Nieuws: de cookiekeuze voor een site die zijn artikelpagina's pas laat zien als je die gemaakt hebt (Tweakers, via de
 * toestemmingspagina van DPG Media). Rene kiest zelf op de echte pagina; Tools klikt niets aan. De WebView bewaart de
 * cookies, en {@link Nieuws#beelden} stuurt die van de site zelf mee als hij een artikelpagina ophaalt. Alleen https en
 * alleen de domeinen van de bron ({@link Nieuws#cookieAdres}); al het andere (advertenties, metingen) is geblokkeerd.
 */
public class NieuwsCookiesActivity extends Activity {

    private final Handler h = new Handler(Looper.getMainLooper());
    WebView wv;
    private TextView status;
    String bron = "";
    volatile boolean keuzeGezien = false, klaar = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        setTheme(Build.VERSION.SDK_INT >= 29 ? android.R.style.Theme_DeviceDefault_DayNight : android.R.style.Theme_DeviceDefault_Light);
        super.onCreate(b);
        String id = getIntent().getStringExtra("bron");
        String[] cfg = id == null ? null : Nieuws.paginaBron(id);
        if (cfg == null) { finish(); return; }
        bron = id;

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
        titel.setText(Taal.t(this, "Cookiekeuze voor {naam}", "naam", cfg[1]));
        titel.setTypeface(Typeface.DEFAULT_BOLD);
        titel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setText(Taal.t(this, "Kies op de pagina welke cookies je toestaat. Daarna sluit dit venster vanzelf."));
        status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        tekst.addView(titel);
        tekst.addView(status);
        bar.addView(tekst, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button knop = new Button(this);
        knop.setText(Taal.t(this, "Klaar"));
        knop.setOnClickListener(v -> sluit());
        bar.addView(knop);
        root.addView(bar);
        wv = new WebView(this);
        root.addView(wv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSaveFormData(false);
        s.setGeolocationEnabled(false);
        CookieManager.getInstance().setAcceptCookie(true);
        // De toestemmingspagina zet ook cookies voor de site zelf; andere sites komen hier niet binnen (Client blokkeert ze)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
        wv.setWebViewClient(new Client(this));
        wv.setWebChromeClient(new Chrome());
        wv.loadUrl(cfg[2]);
    }

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }

    void zet(String s) { h.post(() -> { if (status != null) status.setText(s); }); }

    void sluit() {
        try { CookieManager.getInstance().flush(); } catch (Throwable ignored) { }
        setResult(RESULT_OK);
        finish();
    }

    /** Terug op de site zelf na de toestemmingspagina (elk ander toegestaan adres): de keuze is gemaakt. */
    void geladen(String url) {
        if (klaar || url == null) return;
        if (!Nieuws.cookieKlaar(bron, url)) {
            if (Nieuws.cookieAdres(bron, url)) keuzeGezien = true;
            return;
        }
        if (!keuzeGezien) { zet(Taal.t(this, "Je cookiekeuze is al gemaakt. Tik op Klaar.")); return; }
        klaar = true;
        zet(Taal.t(this, "Je keuze is bewaard."));
        h.postDelayed(this::sluit, 1500);
    }

    @Override
    public void onBackPressed() {
        if (wv != null && wv.canGoBack()) wv.goBack();
        else sluit();
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        try { CookieManager.getInstance().flush(); } catch (Throwable ignored) { }
        if (wv != null) {
            try { wv.stopLoading(); } catch (Throwable ignored) { }
            try { ((ViewGroup) wv.getParent()).removeView(wv); } catch (Throwable ignored) { }
            try { wv.destroy(); } catch (Throwable ignored) { }
            wv = null;
        }
        super.onDestroy();
    }

    static final class Client extends WebViewClient {
        final NieuwsCookiesActivity a;
        Client(NieuwsCookiesActivity a) { this.a = a; }

        @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
            try { if (Nieuws.cookieAdres(a.bron, req.getUrl().toString())) return null; } catch (Throwable ignored) { }
            return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
        }
        @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
            if (Nieuws.cookieAdres(a.bron, req.getUrl().toString())) return false;
            a.zet(Taal.t(a, "Een andere website is geblokkeerd"));
            return true;
        }
        @Override public void onPageFinished(WebView v, String url) { a.geladen(url); }
        @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
            a.zet(Taal.t(a, "De website liep vast. Probeer het opnieuw."));
            try { ((ViewGroup) v.getParent()).removeView(v); v.destroy(); } catch (Throwable ignored) { }
            a.wv = null;
            return true;
        }
    }

    /** Geen vensters van de pagina die ongezien iets bevestigen. */
    static final class Chrome extends WebChromeClient {
        @Override public boolean onJsAlert(WebView v, String url, String m, JsResult res) { res.confirm(); return true; }
        @Override public boolean onJsConfirm(WebView v, String url, String m, JsResult res) { res.cancel(); return true; }
        @Override public boolean onJsPrompt(WebView v, String url, String m, String d, JsPromptResult res) { res.cancel(); return true; }
    }
}
