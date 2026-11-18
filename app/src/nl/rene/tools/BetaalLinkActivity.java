package nl.rene.tools;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Betalen, klaarzetten bij de bank: de website van de bank (eerst Rabobank) in beeld. Rene logt zelf in met de app van zijn
 * bank; de robot (web/betaal-robot.js) gaat daarna naar het formulier voor een overboeking en vult de naam, het IBAN, het
 * bedrag en het kenmerk of de omschrijving in. Meer niet: controleren, versturen en ondertekenen doet Rene zelf. Zodra het
 * formulier in beeld is geweest klikt de robot nergens meer op, en na het invullen stopt hij helemaal. De pagina krijgt geen
 * brug naar de app. Alleen https en alleen de domeinen van de bank; na afloop gaan de cookies en websitegegevens van de bank weg.
 */
public class BetaalLinkActivity extends Activity {

    static final long MAX_MS = 20 * 60_000L, WEG_MS = 6 * 60_000L;
    /** Er staat al een venster open (één tegelijk). */
    static volatile boolean LOPEND = false;
    /** Uitkomst voor het scherm Betalen (eenmalig): {id, stand: ingevuld|gestopt}. */
    static volatile String uitkomst = "";

    private final Handler h = new Handler(Looper.getMainLooper());
    private WebView wv;
    private TextView status;
    private Button knop;
    JSONObject bank;
    String cfg = "", cfgStil = "", robot = "", betaling = "", id = "";
    final Set<String> hosts = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    volatile boolean klaar = false, geladen = false, formulier = false, ingevuld = false;
    volatile String paginaUrl = "";
    long start, wachtSinds;
    volatile long wegSinds = 0;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        setTheme(Build.VERSION.SDK_INT >= 29 ? android.R.style.Theme_DeviceDefault_DayNight : android.R.style.Theme_DeviceDefault_Light);
        super.onCreate(b);
        // Geen schermafdrukken, schermopnames of voorbeeld in "recente apps"
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        String bankId = getIntent().getStringExtra("bank");
        id = getIntent().getStringExtra("id");
        bank = bankId == null ? null : BankLink.bank(BankLink.banken(this), bankId);
        try {
            if (!BetaalLink.kan(bank) || id == null) throw new Exception();
            JSONObject bet = Betalen.vind(Betalen.lijst(Betalen.lees(this)), id);
            betaling = BetaalLink.robotBetaling(bet);
            try (InputStream in = getAssets().open("betaal-robot.js")) { robot = new String(Facturen.lees(in, 200_000), StandardCharsets.UTF_8); }
            cfg = BetaalLink.robotCfg(bank);
            cfgStil = new JSONObject(cfg).put("geenNavigatie", true).toString();
        } catch (Throwable t) { LOPEND = false; finish(); return; }
        LOPEND = true;
        start = System.currentTimeMillis();

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
        titel.setText(Taal.fill(Taal.tx(this, Taal.T("Betalen · {bank}")), "bank", bank.optString("naam")));
        titel.setTypeface(Typeface.DEFAULT_BOLD);
        titel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setText(Taal.tx(this, Taal.T("Website van de bank openen…")));
        status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        tekst.addView(titel);
        tekst.addView(status);
        bar.addView(tekst, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        String app = appPakket();
        if (app != null) {
            Button open = new Button(this);
            open.setText(bank.optString("appNaam", Taal.tx(this, Taal.T("Bank-app"))));
            open.setOnClickListener(v -> openApp(app));
            bar.addView(open);
        }
        knop = new Button(this);
        knop.setText(Taal.tx(this, Taal.T("Sluiten")));
        knop.setOnClickListener(v -> finish());
        bar.addView(knop);
        root.addView(bar);
        wv = new WebView(this);
        root.addView(wv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSaveFormData(false);
        s.setGeolocationEnabled(false);
        s.setUserAgentString(s.getUserAgentString().replace("; wv)", ")").replaceAll("Version/[0-9.]+ ", ""));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, false);
        wv.setWebViewClient(new Client(this));
        wv.setWebChromeClient(new Chrome(this));
        wv.setDownloadListener((url, agent, cd, mime, len) -> { }); // hier wordt niets gedownload
        hosts.add(Facturen.host(bank.optString("startUrl")));
        wv.loadUrl(bank.optString("startUrl"));
        h.postDelayed(this::tik, 2000);
    }

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }

    String appPakket() {
        org.json.JSONArray a = bank.optJSONArray("apps");
        if (a == null) return null;
        for (int i = 0; i < a.length(); i++) {
            String pkg = a.optString(i);
            try { if (getPackageManager().getLaunchIntentForPackage(pkg) != null) return pkg; } catch (Throwable ignored) { }
        }
        return null;
    }

    void openApp(String pkg) {
        if (!BankLink.appToegestaan(pkg, bank)) return;
        try { Intent i = getPackageManager().getLaunchIntentForPackage(pkg); if (i != null) startActivity(i); }
        catch (Throwable t) { Toast.makeText(this, Taal.tx(this, Taal.T("Openen lukt niet")), Toast.LENGTH_SHORT).show(); }
    }

    void zet(String nl) { h.post(() -> { if (status != null) status.setText(Taal.tx(this, nl)); }); }

    /** Elke 1,5 seconde één stap van de robot (alleen op de website van de bank), tot het formulier is ingevuld. */
    void tik() {
        if (klaar || wv == null || isFinishing()) return;
        long nu = System.currentTimeMillis();
        if (nu - start > MAX_MS) { einde(Taal.T("Gestopt: het duurde te lang. Zet de betaling opnieuw klaar.")); return; }
        if (wegSinds > 0) { // uit beeld (bijv. in de app van de bank om in te loggen of te ondertekenen)
            if (nu - wegSinds > WEG_MS) { finish(); return; }
            h.postDelayed(this::tik, 1500);
            return;
        }
        if (!ingevuld && geladen && BankLink.toegestaan(paginaUrl, bank)) {
            wv.evaluateJavascript(robot, null);
            wv.evaluateJavascript("(window.__bt?__bt.stap(" + (formulier ? cfgStil : cfg) + "," + betaling + "):'')", v -> {
                String r = FacturenRun.str(v);
                if (r.equals("inlog")) { wachtSinds = 0; zet(Taal.T("Log zelf in met de app van je bank. Daarna vult Tools de overboeking in.")); }
                else if (r.equals("menu") || r.equals("naar")) { wachtSinds = 0; zet(Taal.T("Naar het formulier voor een overboeking…")); }
                else if (r.equals("invullen")) { formulier = true; wachtSinds = 0; zet(Taal.T("Overboeking invullen…")); }
                else if (r.equals("ingevuld")) {
                    formulier = true; ingevuld = true;
                    uitkomst = uit("ingevuld");
                    App.log(getApplicationContext(), "BETALEN", "ingevuld");
                    zet(Taal.T("Ingevuld. Controleer de naam, het IBAN en het bedrag, en verstuur en onderteken zelf."));
                }
                else if (r.equals("controleer")) { formulier = true; zet(Taal.T("Niet alles is overgenomen. Kijk de velden na en vul aan wat ontbreekt.")); }
                else if (r.equals("wacht")) {
                    if (wachtSinds == 0) wachtSinds = System.currentTimeMillis();
                    if (System.currentTimeMillis() - wachtSinds > 20_000) zet(formulier
                            ? Taal.T("Tools doet hier niets meer. Ga zelf verder; controleer alles voor je ondertekent.")
                            : Taal.T("Lukt het niet vanzelf? Ga zelf naar Overboeken; Tools vult het formulier dan in."));
                }
            });
        }
        if (!ingevuld) h.postDelayed(this::tik, 1500);
    }

    String uit(String stand) {
        try { return new JSONObject().put("id", id).put("stand", stand).put("bank", bank.optString("naam")).toString(); }
        catch (Exception e) { return ""; }
    }

    void einde(String nl) {
        klaar = true;
        zet(nl);
        h.postDelayed(this::finish, 6000);
    }

    @Override protected void onStop() { super.onStop(); if (!isFinishing()) wegSinds = System.currentTimeMillis(); }
    @Override protected void onStart() {
        super.onStart();
        if (wegSinds > 0 && System.currentTimeMillis() - wegSinds > WEG_MS) { finish(); return; }
        wegSinds = 0;
    }

    @Override
    protected void onDestroy() {
        LOPEND = false;
        if (uitkomst.isEmpty() && bank != null && id != null) uitkomst = uit("gestopt");
        h.removeCallbacksAndMessages(null);
        if (bank != null) wisSporen();
        if (wv != null) {
            try { wv.stopLoading(); } catch (Throwable ignored) { }
            try { ((ViewGroup) wv.getParent()).removeView(wv); } catch (Throwable ignored) { }
            try { wv.destroy(); } catch (Throwable ignored) { }
            wv = null;
        }
        super.onDestroy();
    }

    /** Sessie bij de bank afsluiten op de telefoon: cookies, opgeslagen websitegegevens en de cache van de bank weg. */
    void wisSporen() {
        try { if (wv != null) { wv.clearCache(true); wv.clearHistory(); wv.clearFormData(); } } catch (Throwable ignored) { }
        CookieManager cm = CookieManager.getInstance();
        org.json.JSONArray dom = bank.optJSONArray("domeinen");
        java.util.List<String> alle = new java.util.ArrayList<>(hosts);
        for (String hst : alle) {
            String u = "https://" + hst;
            try { WebStorage.getInstance().deleteOrigin(u); } catch (Throwable ignored) { }
            String ck = cm.getCookie(u);
            if (ck == null) continue;
            for (String part : ck.split(";")) {
                String naam = part.split("=", 2)[0].trim();
                if (naam.isEmpty()) continue;
                cm.setCookie(u, naam + "=; Max-Age=0; Path=/");
                cm.setCookie(u, naam + "=; Max-Age=0; Path=/; Secure"); // __Host-/__Secure-cookies verdwijnen alleen met Secure
                if (dom != null) for (int i = 0; i < dom.length(); i++) {
                    cm.setCookie(u, naam + "=; Max-Age=0; Path=/; Domain=." + dom.optString(i));
                    cm.setCookie(u, naam + "=; Max-Age=0; Path=/; Secure; Domain=." + dom.optString(i));
                }
            }
        }
        // Sessiecookies (zonder einddatum) van de bank weg, als er geen Facturen-run of bankimport loopt die ze nodig heeft
        if (FacturenRun.LOPEND.get() == null && !Bank.linking) try { cm.removeSessionCookies(null); } catch (Throwable ignored) { }
        try { cm.flush(); } catch (Throwable ignored) { }
    }

    // ---------- website ----------

    static final class Client extends WebViewClient {
        final BetaalLinkActivity a;
        Client(BetaalLinkActivity a) { this.a = a; }
        @Override public void onPageStarted(WebView v, String url, Bitmap fav) { a.geladen = false; a.paginaUrl = url == null ? "" : url; }
        @Override public void onPageFinished(WebView v, String url) {
            a.paginaUrl = url == null ? "" : url;
            a.geladen = true;
            if (BankLink.toegestaan(url, a.bank)) a.hosts.add(Facturen.host(url));
        }
        @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
            Uri u = req.getUrl();
            String s = u.toString();
            if (BankLink.toegestaan(s, a.bank)) { a.hosts.add(Facturen.host(s)); return false; }
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (!scheme.equals("https") && !scheme.equals("http")) {
                // Inloggen of ondertekenen met de app van de bank: alleen die app (uit de lijst) mag geopend worden
                try {
                    Intent i = scheme.equals("intent") ? Intent.parseUri(s, Intent.URI_INTENT_SCHEME) : new Intent(Intent.ACTION_VIEW, u);
                    Uri data = i.getData();
                    if (data != null && data.getScheme() != null && data.getScheme().toLowerCase(java.util.Locale.ROOT).matches("content|file|android\\.resource")) return true;
                    i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    i.setClipData(null);
                    i.addCategory(Intent.CATEGORY_BROWSABLE);
                    i.setComponent(null);
                    i.setSelector(null);
                    String pkg = i.getPackage();
                    if (pkg == null) { android.content.pm.ResolveInfo ri = a.getPackageManager().resolveActivity(i, 0); if (ri != null && ri.activityInfo != null) pkg = ri.activityInfo.packageName; }
                    if (BankLink.appToegestaan(pkg, a.bank)) { i.setPackage(pkg); a.startActivity(i); }
                } catch (Throwable ignored) { }
                return true;
            }
            a.zet(Taal.T("Een andere website dan die van je bank is geblokkeerd"));
            return true;
        }
        @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
            if (v != a.wv) { try { v.destroy(); } catch (Throwable ignored) { } return true; }
            a.einde(Taal.T("De website liep vast. Zet de betaling opnieuw klaar."));
            try { ((ViewGroup) v.getParent()).removeView(v); v.destroy(); } catch (Throwable ignored) { }
            a.wv = null;
            return true;
        }
    }

    /** Vensters die de website opent: een pagina van de bank in het hoofdvenster, al het andere niet. */
    static final class Chrome extends WebChromeClient {
        final BetaalLinkActivity a;
        Chrome(BetaalLinkActivity a) { this.a = a; }
        @Override public boolean onCreateWindow(WebView v, boolean dialog, boolean gebaar, Message msg) {
            if (a.klaar) return false;
            WebView kind = new WebView(a);
            kind.getSettings().setJavaScriptEnabled(false);
            kind.getSettings().setAllowFileAccess(false);
            kind.getSettings().setAllowContentAccess(false);
            kind.setWebViewClient(new Kind(a));
            ((WebView.WebViewTransport) msg.obj).setWebView(kind);
            msg.sendToTarget();
            a.h.postDelayed(kind::destroy, 30_000);
            return true;
        }
        // Meldingen en vragen van de website altijd laten zien: Rene beslist zelf, Tools bevestigt nooit iets ongezien
        @Override public boolean onJsAlert(WebView v, String url, String m, JsResult res) { return false; }
        @Override public boolean onJsConfirm(WebView v, String url, String m, JsResult res) { return false; }
        @Override public boolean onJsPrompt(WebView v, String url, String m, String d, JsPromptResult res) { res.cancel(); return true; }
    }

    static final class Kind extends WebViewClient {
        final BetaalLinkActivity a;
        Kind(BetaalLinkActivity a) { this.a = a; }
        @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) { vang(v, req.getUrl().toString()); return true; }
        @Override public void onPageStarted(WebView v, String url, Bitmap fav) { if (url != null && !url.startsWith("about:")) { v.stopLoading(); vang(v, url); } }
        private void vang(WebView v, String url) {
            if (BankLink.toegestaan(url, a.bank) && a.wv != null) a.h.post(() -> { if (a.wv != null) a.wv.loadUrl(url); });
            a.h.post(v::destroy);
        }
        @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) { try { v.destroy(); } catch (Throwable ignored) { } return true; }
    }
}
