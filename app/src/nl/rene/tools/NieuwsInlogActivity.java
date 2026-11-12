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
    private LinearLayout balk;
    /** Hoeveel er sinds de laatste richtingswissel gescrold is (positief = naar beneden). */
    private int gescrold;
    String sites = "", soort = INLOGGEN, naam = "";
    boolean derden;

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
        derden = bron.optBoolean("derden");
        naam = bron.optString("naam");
        if (url == null || !Nieuws.siteMag(sites, url)) { finish(); return; }

        // Geen titelbalk van Android: de eigen balk zegt al waar je bent (scheelt bij het lezen een hele balk)
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        boolean lezen = soort.equals(LEZEN);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            v.setPadding(ins.getSystemWindowInsetLeft(), ins.getSystemWindowInsetTop(), ins.getSystemWindowInsetRight(), ins.getSystemWindowInsetBottom());
            return ins.consumeSystemWindowInsets();
        });
        int p = dp(12);
        LinearLayout bar = new LinearLayout(this);
        balk = bar;
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        // lezen: één smalle regel (sluiten, naam en site); inloggen: ruimer, met uitleg en Link uit e-mail
        if (lezen) bar.setPadding(0, 0, p, 0); else bar.setPadding(p, p / 2, p / 2, p / 2);
        if (lezen) {
            Button dicht = new Button(this, null, android.R.attr.borderlessButtonStyle);
            dicht.setText("✕");
            dicht.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            dicht.setContentDescription(Taal.t(this, "Sluiten"));
            dicht.setMinWidth(dp(48));
            dicht.setMinimumWidth(dp(48));
            dicht.setOnClickListener(v -> sluit());
            dicht.setMinHeight(dp(48));
            dicht.setMinimumHeight(dp(48));
            bar.addView(dicht, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        LinearLayout tekst = new LinearLayout(this);
        tekst.setOrientation(LinearLayout.VERTICAL);
        TextView titel = new TextView(this);
        titel.setText(lezen ? naam : Taal.t(this, "Inloggen bij {naam}", "naam", naam));
        titel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titel.setTextSize(TypedValue.COMPLEX_UNIT_SP, lezen ? 14 : 16);
        titel.setSingleLine(true);
        titel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, lezen ? 12 : 13);
        status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        if (lezen) { status.setSingleLine(true); status.setEllipsize(android.text.TextUtils.TruncateAt.END); }
        status.setText(lezen ? Nieuws.host(url) : Taal.t(this, "Log in op de site zelf. Krijg je een link per e-mail? Kopieer die en tik op Link uit e-mail."));
        tekst.addView(titel);
        tekst.addView(status);
        bar.addView(tekst, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (!lezen) {
            Button link = new Button(this);
            link.setText(Taal.t(this, "Link uit e-mail"));
            link.setOnClickListener(v -> plak());
            bar.addView(link);
            Button knop = new Button(this);
            knop.setText(Taal.t(this, "Klaar"));
            knop.setOnClickListener(v -> sluit());
            bar.addView(knop);
        }
        root.addView(bar);
        wv = new WebView(this);
        root.addView(wv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        // Lezen: de balk gaat weg als je verder leest (naar beneden scrolt) en komt terug als je terug scrolt of bovenaan bent
        if (lezen) wv.setOnScrollChangeListener((v, x, y, ox, oy) -> balkBijScrollen(y, oy));

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
        // Cookies van andere sites alleen als dat bij deze bron aan staat (nodig als het inloggen via een andere dienst loopt)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, derden);
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

    /** De balk bij het lezen: weg na een stuk naar beneden scrollen, terug na een stuk omhoog of helemaal bovenaan. */
    void balkBijScrollen(int y, int oud) {
        if (balk == null) return;
        int d = y - oud;
        // onderaan de pagina schuift de WebView terug als hij groter wordt (balk weg): dat is geen terug scrollen
        if (d < 0 && wv != null && !wv.canScrollVertically(1)) return;
        gescrold = (d > 0) == (gescrold > 0) ? gescrold + d : d;
        boolean zichtbaar = balk.getVisibility() == android.view.View.VISIBLE;
        int drempel = Math.max(dp(48), balk.getHeight());
        if (!zichtbaar && (y <= 0 || gescrold < -drempel)) { balk.setVisibility(android.view.View.VISIBLE); gescrold = 0; }
        else if (zichtbaar && gescrold > drempel && y > 2 * drempel) { balk.setVisibility(android.view.View.GONE); gescrold = 0; }
    }

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }

    /** Een melding in de balk: altijd zichtbaar en helemaal leesbaar, ook bij het lezen (waar de balk weg kan zijn). */
    void zet(String t) {
        h.post(() -> {
            if (status == null) return;
            status.setSingleLine(false);
            status.setMaxLines(3);
            status.setText(t);
            if (balk != null) { balk.setVisibility(android.view.View.VISIBLE); gescrold = 0; }
        });
    }

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

    private final java.util.Set<String> gemeld = new java.util.HashSet<>();

    /** Een onderdeel van de pagina dat Tools tegenhield (zoals http): één keer per site melden, alleen bij inloggen. */
    void tegengehouden(String url) {
        if (!soort.equals(INLOGGEN)) return;
        String host;
        try { host = Uri.parse(url).getScheme() + "://" + Uri.parse(url).getHost(); } catch (Throwable e) { return; }
        if (!gemeld.add(host)) return;
        zet(Taal.t(this, "Tools hield een onderdeel van de pagina tegen: {site}. Blijft het inloggen hangen, meld dan deze regel.", "site", host));
    }

    /** Een andere site: in de browser (lezen) of melden welke site er bij Toegestane sites moet (inloggen). */
    private String laatsteAndere = "";

    void andereSite(String url) {
        if (url.equals(laatsteAndere)) return; // doorverwijzing: zowel onderschept als overgenomen
        laatsteAndere = url;
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
                // de pagina zelf alleen op de sites van de bron; wat ze laadt van elke https-site, en wat ze zelf maakt
                // (blob:, data:, about:: werkers en vensters van een robotcontrole of het inlogformulier); geen http of bestanden
                if (req.isForMainFrame() ? Nieuws.siteMag(a.sites, u) : Nieuws.ladenMag(u)) return null;
                // iets wat de pagina nodig heeft, maar niet mag (http): zeggen wat, zodat een hangende pagina te verklaren is
                if (!req.isForMainFrame()) a.h.post(() -> a.tegengehouden(u));
                // een doorverwijzing naar een andere site (de pagina zelf): zeggen welke, of in de browser bij lezen
                if (req.isForMainFrame() && u.regionMatches(true, 0, "https://", 0, 8)) a.h.post(() -> a.andereSite(u));
            } catch (Throwable ignored) { }
            return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
        }
        @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
            String u = req.getUrl().toString();
            if (!req.isForMainFrame()) return !Nieuws.ladenMag(u);
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
