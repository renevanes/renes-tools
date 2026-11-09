package nl.rene.tools;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Bitmap;
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
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Nieuws: de cookiekeuze voor een site die zijn artikelpagina's pas laat zien als je die gemaakt hebt (Tweakers, via de
 * toestemmingspagina van DPG Media). Rene kiest zelf op de echte pagina; Tools klikt niets aan. De WebView bewaart de
 * cookies, en {@link Nieuws#beelden} stuurt die van de site zelf mee als hij een artikelpagina ophaalt. Alleen https en
 * alleen de hosts van de bron ({@link Nieuws#cookieAdres}); al het andere wordt niet geladen. Het venster sluit vanzelf als
 * de site de keuze bewaard heeft ({@link Nieuws#cookieStap} "bewaar") en de pagina weer op de site zelf staat.
 */
public class NieuwsCookiesActivity extends Activity {

    private final Handler h = new Handler(Looper.getMainLooper());
    WebView wv;
    private TextView status;
    String bron = "";
    /** Origins die geladen zijn: bij het sluiten gaan hun websitegegevens weg (de cookies blijven). */
    final Set<String> origins = Collections.synchronizedSet(new HashSet<>());
    volatile boolean keuzeGezien = false, bewaard = false, laadFout = false, klaar = false;

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
        // De site bewaart de keuze zelf (via /privacy-gate/…); geen cookies van derden, zodat DPG Media je niet over sites heen volgt
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, false);
        wv.setWebViewClient(new Client(this, bron));
        wv.setWebChromeClient(new Chrome());
        if (Build.VERSION.SDK_INT >= 33)
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, (android.window.OnBackInvokedCallback) this::terug);
        wv.loadUrl(cfg[2]);
    }

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }

    void zet(String s) { h.post(() -> { if (status != null) status.setText(s); }); }

    void sluit() {
        try { CookieManager.getInstance().flush(); } catch (Throwable ignored) { }
        setResult(RESULT_OK);
        finish();
    }

    void terug() {
        if (wv != null && wv.canGoBack()) wv.goBack();
        else sluit();
    }

    /** Een adres dat geladen wordt: de origin onthouden (om op te ruimen). */
    void geladenVan(String url) {
        try { URL u = new URL(url); origins.add(u.getProtocol() + "://" + u.getHost()); } catch (Throwable ignored) { }
    }

    /** De pagina gaat naar {@code url} (ook via een doorverwijzing): onthouden of de toestemmingspagina en het bewaren voorbij kwamen. */
    void onderweg(String url) {
        String stap = Nieuws.cookieStap(bron, url);
        if (stap.equals("keuze")) keuzeGezien = true;
        else if (stap.equals("bewaar")) bewaard = true;
    }

    /** De pagina is geladen: terug op de site na het bewaren = klaar. */
    void geladen(String url) {
        if (klaar || url == null || laadFout) return;
        onderweg(url);
        String stap = Nieuws.cookieStap(bron, url);
        if (stap.equals("keuze")) zet(Taal.t(this, "Kies op de pagina welke cookies je toestaat. Daarna sluit dit venster vanzelf."));
        if (!stap.equals("site")) return;
        if (bewaard) {
            klaar = true;
            zet(Taal.t(this, "Je keuze is bewaard."));
            h.postDelayed(this::sluit, 1500);
        } else if (keuzeGezien) zet(Taal.t(this, "Klaar met kiezen? Tik dan op Klaar."));
        else zet(Taal.t(this, "Je cookiekeuze is al gemaakt. Tik op Klaar."));
    }

    void laadFout() {
        laadFout = true;
        zet(Taal.t(this, "De pagina laden lukte niet. Controleer de verbinding en probeer het opnieuw."));
    }

    @Override
    public void onBackPressed() { terug(); }

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
        // Opgeslagen websitegegevens weg; de cookies met de keuze blijven
        for (String o : new ArrayList<>(origins)) try { WebStorage.getInstance().deleteOrigin(o); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    static final class Client extends WebViewClient {
        final NieuwsCookiesActivity a;
        final String bron;
        Client(NieuwsCookiesActivity a, String bron) { this.a = a; this.bron = bron; }

        @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
            try {
                String u = req.getUrl().toString();
                if (Nieuws.cookieAdres(bron, u)) {
                    a.geladenVan(u);
                    if (req.isForMainFrame()) a.onderweg(u);
                    return null;
                }
            } catch (Throwable ignored) { }
            return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
        }
        @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
            String u = req.getUrl().toString();
            if (Nieuws.cookieAdres(bron, u)) { a.onderweg(u); return false; }
            a.zet(Taal.t(a, "Een andere website is geblokkeerd"));
            return true;
        }
        @Override public void onPageStarted(WebView v, String url, Bitmap fav) {
            a.laadFout = false;
            if (url != null) a.onderweg(url);
        }
        @Override public void onPageFinished(WebView v, String url) { a.geladen(url); }
        @Override public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
            if (req.isForMainFrame()) a.laadFout();
        }
        @Override public void onReceivedHttpError(WebView v, WebResourceRequest req, WebResourceResponse res) {
            if (req.isForMainFrame() && res != null && res.getStatusCode() >= 400) a.laadFout();
        }
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
