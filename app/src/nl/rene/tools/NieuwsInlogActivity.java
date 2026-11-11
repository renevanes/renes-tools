package nl.rene.tools;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nieuws: de website van een bron, in een eigen venster, voor elke bron op dezelfde manier. Twee soorten:
 * <ul>
 * <li>{@link #INLOGGEN}: Rene logt zelf in op de echte site (Tools vult niets in en bewaart geen wachtwoord). Een site die
 * een inloglink mailt: de link kopiëren en op "Link uit e-mail" tikken. De WebView bewaart de cookies; Nieuws stuurt ze
 * alleen naar de sites van de inlog mee ({@link Nieuws#inlogKoekjes}).</li>
 * <li>{@link #LEZEN}: een artikel lezen terwijl je ingelogd bent.</li>
 * </ul>
 * Pagina's openen alleen op de sites van de bron ({@link Nieuws#siteMag}, in te stellen per bron); een link naar een
 * andere site gaat naar de browser. Wat een pagina zelf laadt (afbeeldingen, scripts) mag van elke https-site, zoals in een
 * browser; cookies van andere sites (derden) niet. Geen pop-ups en geen vensters die ongezien iets bevestigen.
 */
public class NieuwsInlogActivity extends Activity {
    static final String INLOGGEN = "inloggen", LEZEN = "lezen";

    final Handler h = new Handler(Looper.getMainLooper());
    WebView wv;
    private TextView status;
    String sites = "", soort = INLOGGEN, naam = "";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        setTheme(Build.VERSION.SDK_INT >= 29 ? android.R.style.Theme_DeviceDefault_DayNight : android.R.style.Theme_DeviceDefault_Light);
        super.onCreate(b);
        Intent in = getIntent();
        String id = in.getStringExtra("bron"), url = in.getStringExtra("url");
        soort = LEZEN.equals(in.getStringExtra("soort")) ? LEZEN : INLOGGEN;
        JSONObject bron = bron(this, id);
        if (bron == null || !bron.optBoolean("inlog")) { finish(); return; }
        sites = bron.optString("inlogSites");
        naam = bron.optString("naam");
        if (url == null || !Nieuws.siteMag(sites, url)) { finish(); return; }

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
        titel.setText(soort.equals(LEZEN) ? naam : Taal.t(this, "Inloggen bij {naam}", "naam", naam));
        titel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setText(soort.equals(LEZEN) ? Nieuws.host(url) : Taal.t(this, "Log in op de site zelf. Krijg je een link per e-mail? Kopieer die en tik op Link uit e-mail."));
        tekst.addView(titel);
        tekst.addView(status);
        bar.addView(tekst, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (soort.equals(INLOGGEN)) {
            Button link = new Button(this);
            link.setText(Taal.t(this, "Link uit e-mail"));
            link.setOnClickListener(v -> plak());
            bar.addView(link);
        }
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
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, false);
        wv.setWebViewClient(new Client(this));
        wv.setWebChromeClient(new Chrome());
        if (Build.VERSION.SDK_INT >= 33)
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, (android.window.OnBackInvokedCallback) this::terug);
        wv.loadUrl(url);
    }

    /** De bron zoals Nieuws hem nu kent (met de instellingen), of null. */
    static JSONObject bron(Context c, String id) {
        if (id == null) return null;
        try {
            JSONArray bs;
            synchronized (Nieuws.LOCK) { bs = Nieuws.bronnen(Nieuws.dir(c)); }
            for (int i = 0; i < bs.length(); i++) if (bs.getJSONObject(i).optString("id").equals(id)) return bs.getJSONObject(i);
        } catch (Throwable ignored) { }
        return null;
    }

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }

    void zet(String t) { h.post(() -> { if (status != null) status.setText(t); }); }

    private static final Pattern LINK = Pattern.compile("https://[^\\s<>\"']{1,2000}");

    /** Link uit e-mail: het eerste https-adres op het klembord, als het van een site van de bron is. */
    void plak() {
        String tekst = "";
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData d = cm == null ? null : cm.getPrimaryClip();
            if (d != null && d.getItemCount() > 0 && d.getItemAt(0).getText() != null) tekst = d.getItemAt(0).getText().toString();
        } catch (Throwable ignored) { }
        Matcher m = LINK.matcher(tekst);
        if (!m.find()) { zet(Taal.t(this, "Kopieer eerst de inloglink uit de e-mail.")); return; }
        String u = m.group();
        if (!Nieuws.siteMag(sites, u)) {
            zet(Taal.t(this, "Deze link is van {site}. Voeg die site toe bij Toegestane sites in de instellingen van de bron.", "site", Nieuws.host(u)));
            return;
        }
        if (wv != null) wv.loadUrl(u);
    }

    void sluit() {
        try { CookieManager.getInstance().flush(); } catch (Throwable ignored) { }
        setResult(RESULT_OK);
        finish();
    }

    void terug() {
        if (wv != null && wv.canGoBack()) wv.goBack();
        else sluit();
    }

    /** Een andere site: in de browser (lezen) of melden welke site er bij Toegestane sites moet (inloggen). */
    void andereSite(String url) {
        if (soort.equals(LEZEN)) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Throwable ignored) { }
            return;
        }
        zet(Taal.t(this, "{site} is geblokkeerd. Hoort die site bij het inloggen, voeg hem dan toe bij Toegestane sites.", "site", Nieuws.host(url)));
    }

    /** Uitloggen: cookies van deze adressen laten verlopen (ook die voor de hele site: Domain=) en de websitegegevens weg. */
    static void wis(List<String> adressen) {
        CookieManager cm = CookieManager.getInstance();
        for (String u : adressen) {
            try {
                String host = new URL(u).getHost().toLowerCase(Locale.ROOT), kaal = host.startsWith("www.") ? host.substring(4) : host;
                String namen = Nieuws.koekjesNamen(cm.getCookie(u));
                if (!namen.isEmpty()) for (String n : namen.split(";")) {
                    cm.setCookie(u, n + "=; Max-Age=0; Path=/");
                    cm.setCookie(u, n + "=; Max-Age=0; Path=/; Domain=" + kaal);
                    cm.setCookie(u, n + "=; Max-Age=0; Path=/; Domain=." + kaal);
                }
                WebStorage.getInstance().deleteOrigin("https://" + host);
            } catch (Throwable ignored) { }
        }
        try { cm.flush(); } catch (Throwable ignored) { }
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
        super.onDestroy();
    }

    static final class Client extends WebViewClient {
        final NieuwsInlogActivity a;
        Client(NieuwsInlogActivity a) { this.a = a; }

        @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
            try {
                String u = req.getUrl().toString();
                // de pagina zelf alleen op de sites van de bron; wat ze laadt van elke https-site (geen http, geen bestanden)
                if (req.isForMainFrame() ? Nieuws.siteMag(a.sites, u) : u.regionMatches(true, 0, "https://", 0, 8)) return null;
                // een doorverwijzing naar een andere site (de pagina zelf): zeggen welke, of in de browser bij lezen
                if (req.isForMainFrame() && u.regionMatches(true, 0, "https://", 0, 8)) a.h.post(() -> a.andereSite(u));
            } catch (Throwable ignored) { }
            return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
        }
        @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
            String u = req.getUrl().toString();
            if (!req.isForMainFrame()) return !u.regionMatches(true, 0, "https://", 0, 8);
            if (Nieuws.siteMag(a.sites, u)) return false;
            if (u.regionMatches(true, 0, "https://", 0, 8)) a.andereSite(u);
            return true;
        }
        @Override public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
            if (req.isForMainFrame()) a.zet(Taal.t(a, "De pagina laden lukte niet. Controleer de verbinding en probeer het opnieuw."));
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
