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
import android.webkit.JavascriptInterface;
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
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * Bankzaken, automatisch importeren: de website van de bank (eerst Rabobank) in beeld. Je logt zelf in met de app van je
 * bank; de robot (web/bank-robot.js) zoekt daarna "Transacties downloaden", kiest CAMT.053 of CSV en de periode, en het
 * bestand gaat in het geheugen rechtstreeks naar Bankzaken (versleuteld opgeslagen). Er is geen derde partij en het
 * afschrift komt nooit onversleuteld op de telefoon. Alleen https en alleen de domeinen van de bank; na afloop worden
 * de cookies en opgeslagen websitegegevens van de bank gewist.
 */
public class BankLinkActivity extends Activity {

    static final long MAX_MS = 12 * 60_000L;

    private final Handler h = new Handler(Looper.getMainLooper());
    private WebView wv;
    private TextView status;
    private Button knop;
    JSONObject bank;
    String cfg = "", robot = "";
    final String sleutel = java.util.UUID.randomUUID().toString().replace("-", "");
    final Set<String> hosts = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    final Set<String> opgehaald = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    volatile boolean klaar = false, bezig = false, geladen = false;
    volatile String paginaUrl = "";
    long start, inlogSinds, wachtSinds;
    /** Uit beeld sinds (0 = in beeld) en wanneer de app van de bank geopend werd (om de inlog te bevestigen). */
    volatile long wegSinds = 0, appGeopend = 0;
    String ua = "";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        setTheme(Build.VERSION.SDK_INT >= 29 ? android.R.style.Theme_DeviceDefault_DayNight : android.R.style.Theme_DeviceDefault_Light);
        super.onCreate(b);
        // Geen schermafdrukken, schermopnames of voorbeeld in "recente apps"
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        String id = getIntent().getStringExtra("bank");
        bank = id == null ? null : BankLink.bank(BankLink.banken(this), id);
        if (bank == null || !bank.optBoolean("robot")) { Bank.linking = false; finish(); return; }
        if (!Bank.isOpen()) { Bank.linking = false; Toast.makeText(this, "Ontgrendel Bankzaken eerst", Toast.LENGTH_LONG).show(); finish(); return; }
        Bank.linking = true;
        start = System.currentTimeMillis();
        try {
            try (InputStream in = getAssets().open("bank-robot.js")) { robot = new String(Facturen.lees(in, 200_000), StandardCharsets.UTF_8).replace("__SLEUTEL__", sleutel); }
            cfg = BankLink.robotCfg(bank, getIntent().getStringExtra("van"));
        } catch (Exception e) { Bank.linking = false; finish(); return; }

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
        titel.setText("Bankzaken · " + bank.optString("naam"));
        titel.setTypeface(Typeface.DEFAULT_BOLD);
        titel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setText("Website van " + bank.optString("naam") + " openen…");
        status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        tekst.addView(titel);
        tekst.addView(status);
        bar.addView(tekst, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        // Knop om de app van de bank te openen (om de inlog te bevestigen), alleen als die op de telefoon staat
        String app = appPakket();
        if (app != null) {
            Button open = new Button(this);
            open.setText(bank.optString("appNaam", "Bank-app"));
            open.setOnClickListener(v -> openApp(app));
            bar.addView(open);
        }
        knop = new Button(this);
        knop.setText("Stoppen");
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
        ua = s.getUserAgentString().replace("; wv)", ")").replaceAll("Version/[0-9.]+ ", "");
        s.setUserAgentString(ua);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, false);
        wv.setWebViewClient(new Client(this));
        wv.setWebChromeClient(new Chrome(this));
        wv.setDownloadListener((url, agent, cd, mime, len) -> opDownload(url, cd));
        wv.addJavascriptInterface(new Brug(this), "BrBrug");
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
        try { Intent i = getPackageManager().getLaunchIntentForPackage(pkg); if (i != null) { appGeopend = System.currentTimeMillis(); startActivity(i); } }
        catch (Throwable t) { Toast.makeText(this, "Openen lukt niet", Toast.LENGTH_SHORT).show(); }
    }

    void zet(String s) { h.post(() -> { if (status != null) status.setText(s); }); }

    /** Elke 1,5 seconde: de robot een stap laten doen (alleen op de website van de bank). */
    void tik() {
        if (klaar || wv == null || isFinishing()) return;
        if (System.currentTimeMillis() - start > MAX_MS) { einde("Gestopt: het duurde te lang. Probeer het opnieuw of importeer een bestand.", false); return; }
        if (wegSinds > 0) {
            // Uit beeld: Bankzaken niet open houden. Alleen even wachten als je net de app van je bank opende om te bevestigen.
            long nu = System.currentTimeMillis(), mag = nu - appGeopend < 4 * 60_000L ? 3 * 60_000L : 45_000L;
            if (nu - wegSinds > mag) { Bank.close(); Bank.linking = false; finish(); return; }
            h.postDelayed(this::tik, 1500);
            return;
        }
        Bank.touch();
        if (!bezig && geladen && BankLink.toegestaan(paginaUrl, bank)) {
            wv.evaluateJavascript(robot, null);
            wv.evaluateJavascript("(window.__br?__br.stap(" + cfg + "):'')", v -> {
                String r = FacturenRun.str(v);
                long nu = System.currentTimeMillis();
                if (r.equals("inlog")) { if (inlogSinds == 0) inlogSinds = nu; wachtSinds = 0;
                    zet("Log in met de " + bank.optString("appNaam", "app van je bank") + ". Daarna gaat het vanzelf verder."); }
                else if (r.equals("menu") || r.equals("naar")) { wachtSinds = 0; zet("Naar Transacties downloaden…"); }
                else if (r.equals("formulier")) { wachtSinds = 0; zet("Formaat en periode kiezen…"); }
                else if (r.equals("download")) { wachtSinds = 0; zet("Downloaden…"); }
                else if (r.equals("wacht")) {
                    if (wachtSinds == 0) wachtSinds = nu;
                    if (nu - wachtSinds > 20_000) zet("Lukt het niet vanzelf? Ga zelf naar Transacties downloaden en tik op Downloaden; de app vangt het bestand op.");
                }
            });
        }
        h.postDelayed(this::tik, 1500);
    }

    // ---------- bestanden opvangen ----------

    void opDownload(String url, String cd) {
        if (url == null || klaar || wv == null) return;
        String naam = Facturen.naamUitDisposition(cd);
        if (url.startsWith("blob:")) { wv.evaluateJavascript("window.__br&&__br.blob(" + JSONObject.quote(url) + "," + JSONObject.quote(naam) + ")", null); return; }
        if (url.startsWith("data:")) { new Thread(() -> { try { opData(url, naam); } catch (Throwable ignored) { } }, "bank-data").start(); return; }
        if (!BankLink.toegestaan(url, bank) || !opgehaald.add(url)) return;
        final String ref = wv.getUrl();
        zet("Bestand ophalen…");
        new Thread(() -> { try { haal(url, cd, ref); } catch (Throwable t) { zet("Ophalen lukte niet; probeer het nog eens"); } }, "bank-download").start();
    }

    void opData(String dataUrl, String naam) {
        try {
            int i = dataUrl.indexOf(',');
            if (i < 0 || dataUrl.length() > 36_000_000) return;
            String kop = dataUrl.substring(0, i);
            byte[] b = kop.contains(";base64") ? android.util.Base64.decode(dataUrl.substring(i + 1), android.util.Base64.DEFAULT)
                    : Uri.decode(dataUrl.substring(i + 1)).getBytes(StandardCharsets.UTF_8);
            neem(b, naam);
        } catch (Throwable ignored) { }
    }

    private void haal(String url, String cd, String referer) throws Exception {
        CookieManager cm = CookieManager.getInstance();
        String u = url;
        for (int hop = 0; hop < 6; hop++) {
            if (!BankLink.toegestaan(u, bank)) return; // ook na een doorverwijzing alleen de bank zelf
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            try {
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(20_000); c.setReadTimeout(60_000);
                c.setRequestProperty("User-Agent", ua);
                String ck = cm.getCookie(u);
                if (ck != null) c.setRequestProperty("Cookie", ck);
                if (referer != null && Facturen.host(referer).equals(Facturen.host(u))) c.setRequestProperty("Referer", referer);
                int code = c.getResponseCode();
                List<String> zetC = c.getHeaderFields().get("Set-Cookie");
                if (zetC != null) for (String z : zetC) cm.setCookie(u, z);
                if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) { u = new URL(new URL(u), c.getHeaderField("Location")).toString(); continue; }
                if (code >= 400) { zet("De bank gaf geen bestand (fout " + code + ")"); return; }
                byte[] b;
                try (InputStream in = c.getInputStream()) { b = Facturen.lees(in, BankImport.MAX_BYTES); }
                String naam = Facturen.naamUitDisposition(c.getHeaderField("Content-Disposition"));
                neem(b, naam.isEmpty() ? Facturen.naamUitDisposition(cd) : naam);
                return;
            } finally { c.disconnect(); }
        }
    }

    /** Een binnengekomen bestand: alleen als het echt een afschrift is (anders bijv. een webpagina: negeren). */
    void neem(byte[] b, String naam) {
        if (b == null || b.length == 0 || klaar) return;
        String n = naam == null || naam.isEmpty() ? "afschrift-" + bank.optString("id") : naam;
        try { BankImport.parse(b, n); } catch (Throwable t) { zet("Dit was geen afschrift; de app wacht op het juiste bestand"); return; }
        synchronized (this) { if (klaar || bezig) return; bezig = true; }
        zet("Afschrift ontvangen, versleuteld opslaan…");
        new Thread(() -> {
            java.util.List<Bank.Bestand> l = new java.util.ArrayList<>();
            l.add(new Bank.Bestand(n, b));
            String uit;
            try {
                JSONObject r = Bank.importeer(getApplicationContext(), l, null);
                r.put("bank", bank.optString("naam"));
                uit = r.toString();
                JSONObject s = r.getJSONArray("bestanden").getJSONObject(0).optJSONObject("sum");
                einde(s == null ? "Klaar" : "Klaar: " + s.optInt("nieuw") + " nieuwe boekingen", true);
            } catch (Throwable t) {
                // Op slot gegaan (of de sleutel verliep): bewaren in het geheugen tot je weer ontgrendelt
                synchronized (Bank.WACHT) { Bank.WACHT.addAll(l); Bank.wachtTot = System.currentTimeMillis() + 30 * 60_000L; }
                uit = "{\"wacht\":true,\"bank\":" + JSONObject.quote(bank.optString("naam")) + "}";
                einde("Opgehaald. Ontgrendel Bankzaken om het af te ronden.", true);
            }
            Bank.laatsteKoppeling = uit;
            App.log(getApplicationContext(), "BANK", "automatisch");
        }, "bank-link-import").start();
    }

    void einde(String tekst, boolean ok) {
        klaar = true;
        if (!ok) Bank.linking = false; // mislukt: Bankzaken gaat weer gewoon op slot als de app uit beeld gaat
        zet(tekst);
        h.post(() -> { if (knop != null) knop.setText("Sluiten"); });
        h.postDelayed(this::finish, ok ? 2500 : 6000);
    }

    @Override protected void onStop() { super.onStop(); if (!isFinishing()) wegSinds = System.currentTimeMillis(); }
    @Override protected void onStart() {
        super.onStart();
        // Terug na te lang weg (Bankzaken ging op slot): stoppen
        if (wegSinds > 0 && !Bank.isOpen() && !klaar) { Bank.linking = false; finish(); return; }
        wegSinds = 0;
    }

    // ---------- opruimen ----------

    @Override
    protected void onDestroy() {
        Bank.linking = false;
        h.removeCallbacksAndMessages(null);
        wisSporen();
        if (wv != null) {
            try { wv.stopLoading(); wv.removeJavascriptInterface("BrBrug"); } catch (Throwable ignored) { }
            try { ((ViewGroup) wv.getParent()).removeView(wv); } catch (Throwable ignored) { }
            try { wv.destroy(); } catch (Throwable ignored) { }
            wv = null;
        }
        super.onDestroy();
    }

    /** Sessie bij de bank afsluiten op de telefoon: cookies, opgeslagen websitegegevens en de cache van de bank weg. */
    void wisSporen() {
        try { wv.clearCache(true); wv.clearHistory(); wv.clearFormData(); } catch (Throwable ignored) { }
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
        // Sessiecookies (zonder einddatum) van de bank weg, als er geen Facturen-run loopt die ze nodig heeft
        if (FacturenRun.LOPEND.get() == null) try { cm.removeSessionCookies(null); } catch (Throwable ignored) { }
        try { cm.flush(); } catch (Throwable ignored) { }
    }

    // ---------- website ----------

    static final class Client extends WebViewClient {
        final BankLinkActivity a;
        Client(BankLinkActivity a) { this.a = a; }
        @Override public void onPageStarted(WebView v, String url, Bitmap fav) { a.geladen = false; a.paginaUrl = url == null ? "" : url; }
        @Override public void onPageFinished(WebView v, String url) {
            a.paginaUrl = url == null ? "" : url;
            a.geladen = true;
            if (BankLink.toegestaan(url, a.bank)) { a.hosts.add(Facturen.host(url)); v.evaluateJavascript(a.robot, null); }
        }
        @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
            Uri u = req.getUrl();
            String s = u.toString();
            if (BankLink.toegestaan(s, a.bank)) { a.hosts.add(Facturen.host(s)); return false; }
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (!scheme.equals("https") && !scheme.equals("http")) {
                // Inloggen met de app van de bank: alleen die app (uit de lijst) mag geopend worden
                try {
                    Intent i = scheme.equals("intent") ? Intent.parseUri(s, Intent.URI_INTENT_SCHEME) : new Intent(Intent.ACTION_VIEW, u);
                    // Geen rechten of bestanden meegeven: alleen de app van de bank openen
                    Uri data = i.getData();
                    if (data != null && data.getScheme() != null && data.getScheme().toLowerCase(java.util.Locale.ROOT).matches("content|file|android\\.resource")) return true;
                    i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    i.setClipData(null);
                    i.addCategory(Intent.CATEGORY_BROWSABLE);
                    i.setComponent(null);
                    if (Build.VERSION.SDK_INT >= 15) i.setSelector(null);
                    String pkg = i.getPackage();
                    if (pkg == null) { android.content.pm.ResolveInfo ri = a.getPackageManager().resolveActivity(i, 0); if (ri != null && ri.activityInfo != null) pkg = ri.activityInfo.packageName; }
                    if (BankLink.appToegestaan(pkg, a.bank)) { i.setPackage(pkg); a.appGeopend = System.currentTimeMillis(); a.startActivity(i); }
                } catch (Throwable ignored) { }
                return true;
            }
            a.zet("Een andere website dan " + a.bank.optString("naam") + " is geblokkeerd");
            return true;
        }
        @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
            if (v != a.wv) { try { v.destroy(); } catch (Throwable ignored) { } return true; }
            a.einde("De website liep vast. Probeer het opnieuw.", false);
            try { ((ViewGroup) v.getParent()).removeView(v); v.destroy(); } catch (Throwable ignored) { }
            a.wv = null;
            return true;
        }
    }

    /** Vensters die de website opent (vaak het bestand zelf): het adres opvangen in plaats van een nieuw venster. */
    static final class Chrome extends WebChromeClient {
        final BankLinkActivity a;
        Chrome(BankLinkActivity a) { this.a = a; }
        @Override public boolean onCreateWindow(WebView v, boolean dialog, boolean gebaar, Message msg) {
            if (a.klaar) return false;
            WebView kind = new WebView(a);
            kind.getSettings().setJavaScriptEnabled(false);
            kind.setWebViewClient(new Kind(a));
            kind.setDownloadListener((url, agent, cd, mime, len) -> { a.opDownload(url, cd); a.h.post(kind::destroy); });
            ((WebView.WebViewTransport) msg.obj).setWebView(kind);
            msg.sendToTarget();
            a.h.postDelayed(kind::destroy, 30_000);
            return true;
        }
        @Override public boolean onJsAlert(WebView v, String url, String m, JsResult res) { res.confirm(); return true; }
        @Override public boolean onJsConfirm(WebView v, String url, String m, JsResult res) { res.cancel(); return true; } // nooit ongezien iets bevestigen
        @Override public boolean onJsPrompt(WebView v, String url, String m, String d, JsPromptResult res) { res.cancel(); return true; }
    }

    static final class Kind extends WebViewClient {
        final BankLinkActivity a;
        Kind(BankLinkActivity a) { this.a = a; }
        @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) { vang(v, req.getUrl().toString()); return true; }
        @Override public void onPageStarted(WebView v, String url, Bitmap fav) { if (url != null && !url.startsWith("about:")) { v.stopLoading(); vang(v, url); } }
        private void vang(WebView v, String url) {
            // Een pagina van de bank in een nieuw venster: in het hoofdvenster openen; een bestand: opvangen
            if (BankLink.toegestaan(url, a.bank) && a.wv != null) a.h.post(() -> { if (a.wv != null) a.wv.loadUrl(url); });
            a.h.post(v::destroy);
        }
        @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) { try { v.destroy(); } catch (Throwable ignored) { } return true; }
    }

    /** Alleen voor de robot in de pagina: bestanden die de pagina zelf maakt (blob:/data:). */
    static final class Brug {
        final BankLinkActivity a;
        Brug(BankLinkActivity a) { this.a = a; }
        @JavascriptInterface public void bestand(String sleutel, String dataUrl, String naam) {
            if (a.klaar || dataUrl == null || !a.sleutel.equals(sleutel) || !BankLink.toegestaan(a.paginaUrl, a.bank)) return;
            a.opData(dataUrl, naam == null ? "" : naam);
        }
    }
}
