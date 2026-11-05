package nl.rene.tools;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Eén keer facturen ophalen bij één leverancier, in een WebView: inloggen (met code per sms/Eigen Cloud), naar de
 * facturenpagina, downloadknoppen langs, PDF's opvangen. Onzichtbaar (achtergrond) of in FacturenActivity (meekijken).
 * Draait op de hoofdthread (eis van WebView); downloaden, de code en het afleveren op eigen threads.
 * De robot in de pagina staat in web/facturen-robot.js; hier zit de beslislogica.
 */
final class FacturenRun {

    interface Klaar { void klaar(JSONObject r); }

    static final AtomicReference<FacturenRun> LOPEND = new AtomicReference<>();
    private static volatile String robot;

    static final int LADEN = 0, INLOG = 1, NAAR = 2, ZOEK = 3, DOWNLOAD = 4, AFRONDEN = 5, KLAAR = 6;

    final Context c;
    final JSONObject lev;
    final String id, wachtwoord, gebruiker, cfgJs;
    final boolean zichtbaar, eigenWv;
    final WebView wv;
    final Klaar klaar;
    final Handler h = new Handler(Looper.getMainLooper());
    final AtomicBoolean stop = new AtomicBoolean();
    final ExecutorService net = Executors.newFixedThreadPool(2);
    final Map<String, Facturen.Pdf> pdfs = new LinkedHashMap<>();
    final Set<String> opgehaald = new java.util.HashSet<>();
    final AtomicInteger bezig = new AtomicInteger(), vangsten = new AtomicInteger(), geenPdf = new AtomicInteger();
    final long start = System.currentTimeMillis(), maxDuur;
    String ua = "";
    volatile String voortgang = "Starten…";
    volatile int fase = LADEN;

    /** Vanzelf gestart (achtergrondtaak) of met de hand. */
    volatile boolean klaarAuto;
    boolean geladen, codeBezig, handmatig, naarGeladen, klaarGemeld;
    int pwPogingen, userPogingen, codePogingen, inlogKlikken, stil, menuPogingen, zoekPogingen, terug;
    long codeSinds, wachtTot, knopSinds, afrondenSinds;
    int knopIndex, vangstenVoor, meerKlikken, meerVorige = -1;
    volatile int bekendOpRij;
    boolean meerKlaar;
    /** Facturen die al eerder zijn opgehaald (SHA-256) en of dit een vervolg is (niet de eerste keer of "opnieuw"). */
    final Set<String> bekend = new java.util.HashSet<>();
    boolean vervolg;
    JSONArray knoppen;
    String lijstUrl;
    /** Geheim van deze run: alleen de robot (in een afgesloten functie) kent het; andere scripts en iframes niet. */
    final String sleutel = java.util.UUID.randomUUID().toString().replace("-", "");
    /** Het domein van de leverancier (bijv. kpn.com): alleen daar wordt ingelogd en gedownload. */
    final String domein;
    /** Alle domeinen waar de leverancier op zit (bijv. ziggo.nl en vodafone.nl voor het inloggen). */
    final String[] domeinen;
    final Set<String> codeGezien = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    final java.util.List<WebView> kinderen = new java.util.ArrayList<>();
    volatile String paginaUrl = "";
    /** Facturen uit de mail (FacturenMail) in plaats van van een website. */
    final boolean mail;

    private FacturenRun(Context c, JSONObject lev, String wachtwoord, WebView zicht, Klaar k) throws Exception {
        this.c = c.getApplicationContext();
        this.lev = lev;
        this.id = lev.getString("id");
        this.wachtwoord = wachtwoord;
        this.gebruiker = lev.optString("gebruiker", "");
        this.domeinen = Facturen.domeinen(this.c, lev);
        // De robot controleert vlak voor het invullen nog een keer waar de pagina is (de code kan minuten later komen)
        this.cfgJs = new JSONObject().put("velden", lev.optJSONObject("velden") == null ? new JSONObject() : lev.optJSONObject("velden"))
                .put("domeinen", new JSONArray(java.util.Arrays.asList(domeinen))).toString();
        this.mail = "mail".equals(lev.optString("soort"));
        this.zichtbaar = zicht != null && !mail;
        this.eigenWv = zicht == null && !mail;
        this.wv = mail ? null : zicht != null ? zicht : new WebView(this.c); // mail: geen website, geen WebView
        this.klaar = k;
        this.maxDuur = zichtbaar ? 15 * 60_000L : 9 * 60_000L;
        this.domein = Facturen.domein(lev.optString("startUrl"));
        JSONObject tt = Facturen.toestand(this.c, this.id), vw = tt.optJSONObject("verwerkt");
        if (vw != null) for (java.util.Iterator<String> it = vw.keys(); it.hasNext(); ) bekend.add(it.next());
        // Vervolg (stoppen na een rij bekende facturen) alleen als de hele periode al eens is opgehaald
        this.vervolg = !Facturen.heelPeriode(lev, tt, System.currentTimeMillis(), Regio.zone(this.c));
    }

    /** Starten (op de hoofdthread). Geeft "" of de reden waarom het niet kan. */
    static String begin(Context c, String id, WebView zicht, Klaar k) {
        try {
            JSONObject lev = Facturen.leverancier(c, id);
            if (lev == null) return "Leverancier niet gevonden";
            FacturenRun r = new FacturenRun(c, lev, Facturen.geheim(c, "lev_" + id), zicht, k);
            if (!LOPEND.compareAndSet(null, r)) { if (r.eigenWv && r.wv != null) r.wv.destroy(); return "Er worden al facturen opgehaald"; }
            if (FacturenDossier.WEGHALEN.get()) { LOPEND.compareAndSet(r, null); if (r.eigenWv && r.wv != null) r.wv.destroy(); return "Het dossier wordt nu leeggemaakt; probeer het straks nog een keer"; }
            try { r.start(); } catch (Throwable e) { LOPEND.compareAndSet(r, null); throw e; }
            Facturen.werkBij(c, id, new JSONObject().put("bezigSinds", r.start));
            return "";
        } catch (Throwable e) { return Facturen.kort(e); }
    }

    static String robot(Context c) {
        if (robot == null) {
            try (InputStream in = c.getAssets().open("facturen-robot.js")) { robot = new String(Facturen.lees(in, 200_000), StandardCharsets.UTF_8); }
            catch (Exception e) { robot = ""; }
        }
        return robot;
    }

    /** Facturen uit de mail: op een eigen thread (geen WebView). */
    private void startMail() {
        voortgang = "Mail doorzoeken…";
        h.postDelayed(() -> einde(fout("Het zoeken in de mail duurde te lang", false)), maxDuur);
        new Thread(() -> {
            JSONObject r;
            try {
                JSONObject m = lev.optJSONObject("mail") == null ? new JSONObject() : lev.optJSONObject("mail");
                JSONObject tt = Facturen.toestand(c, id);
                long nuM = System.currentTimeMillis();
                boolean heel = Facturen.heelPeriode(lev, tt, nuM, Regio.zone(c));
                long sinds = heel ? FacturenMail.sindsHeel(lev, tt.optLong("lastOk"), tt.optLong("mailSinds"), nuM, Regio.zone(c))
                        : FacturenMail.sinds(lev, tt.optLong("lastOk"), tt.optLong("mailSinds"), nuM, Regio.zone(c));
                java.util.List<Facturen.Pdf> l = FacturenMail.bron(m.optString("bron", "gmail")).haal(c, lev, sinds, stop, t -> voortgang = t);
                if (klaarGemeld) { // te laat (de tijd was om): deze mails zijn niet bewaard, dus de volgende keer opnieuw vanaf hetzelfde punt
                    Facturen.werkBij(c, id, new JSONObject().put("mailSinds", tt.optLong("mailSinds")).put("mailMeer", tt.optBoolean("mailMeer")));
                }
                synchronized (pdfs) { for (Facturen.Pdf p : l) if (!pdfs.containsKey(p.sha)) pdfs.put(p.sha, p); }
                r = new JSONObject().put("ok", true);
            } catch (Throwable e) {
                String msg = Facturen.kort(e);
                r = fout(msg, false);
                try { if (msg.contains("Koppel") || msg.contains("toegang tot Gmail")) r.put("koppelNodig", true); } catch (Exception ignored) { }
            }
            final JSONObject uit = r;
            h.post(() -> einde(uit));
        }, "facturen-mail").start();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void start() {
        if (mail) { startMail(); return; }
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setSaveFormData(false); // nooit formuliergegevens (gebruikersnaam) in de WebView bewaren
        // Onzichtbaar inloggen: Android-autofill (wachtwoordbeheer) mag de ingevulde gegevens niet zien of opslaan
        if (eigenWv && android.os.Build.VERSION.SDK_INT >= 26) wv.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        // Als gewone Chrome: sommige inlogpagina's weigeren een "WebView"
        ua = s.getUserAgentString().replace("; wv)", ")").replaceAll("Version/[0-9.]+ ", "");
        s.setUserAgentString(ua);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(wv, true);
        wv.setWebViewClient(new Client(this));
        wv.setWebChromeClient(new Chrome(this));
        wv.setDownloadListener((url, agent, cd, mime, len) -> opDownload(url, cd));
        wv.addJavascriptInterface(new Brug(this), "FrBrug");
        if (eigenWv) {
            // Onzichtbaar: een vaste schermgrootte, anders tekenen sommige websites niets
            wv.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2200, View.MeasureSpec.EXACTLY));
            wv.layout(0, 0, 1080, 2200);
            wv.onResume();
            wv.resumeTimers();
        }
        voortgang = "Website openen…";
        wv.loadUrl(lev.optString("startUrl"));
        h.postDelayed(this::tik, 3000);
        // Waakhond: ook als de website niet meer antwoordt (geen terugmelding van de robot), stopt het op tijd
        h.postDelayed(() -> einde(fout("De website reageert niet meer", fase <= INLOG)), maxDuur + 60_000L);
    }

    // ================= de beslislus =================

    private void tik() {
        if (fase == KLAAR || wv == null) return;
        try {
            long nu = System.currentTimeMillis();
            if (stop.get()) { einde(fout("Gestopt", false)); return; }
            paginaUrl = wv.getUrl() == null ? "" : wv.getUrl();
            if (nu - start > maxDuur) { einde(fout(fase <= INLOG ? "Inloggen duurde te lang" : "Het ophalen duurde te lang", fase <= INLOG)); return; }
            if (fase == DOWNLOAD) { volgendeKnop(); return; }
            if (fase == AFRONDEN) { afronden(); return; }
            if (nu < wachtTot || codeBezig) { verder(1000); return; }
            js("__fr.stand(" + cfgJs + ")", this::opStand);
        } catch (Throwable e) { einde(fout("Onverwachte fout: " + Facturen.kort(e), false)); }
    }

    private void verder(long ms) { if (fase != KLAAR) h.postDelayed(this::tik, ms); }

    private void opStand(String res) {
        if (fase == KLAAR) return;
        try {
            JSONObject st = new JSONObject(res.isEmpty() ? "{}" : res);
            if (!st.has("url")) { verder(1500); return; } // pagina nog niet klaar
            switch (fase) {
                case LADEN:
                    if (!geladen && !"complete".equals(st.optString("klaar"))) break;
                    fase = INLOG;
                    // fall through
                case INLOG: inlog(st); break;
                case NAAR: naar(st); break;
                case ZOEK: zoek(); return; // zoek plant zelf verder
                default: break;
            }
            verder(2000);
        } catch (Throwable e) { einde(fout("Onverwachte fout: " + Facturen.kort(e), false)); }
    }

    /**
     * Vlak voor het invullen: staat de WebView (niet wat de pagina zelf zegt) nog op een eigen domein van de leverancier,
     * met https? Zo niet, dan niets invullen (een pagina kan zich anders voordoen, en de sms-code komt soms minuten later).
     */
    private boolean hierVeilig() {
        String u = null;
        try { u = wv == null ? null : wv.getUrl(); } catch (Throwable ignored) { }
        if (Facturen.eigenSite(u, domeinen)) return true;
        handmatigOf("De pagina staat niet (meer) op " + android.text.TextUtils.join(" of ", domeinen) + " (" + Facturen.host(u == null ? "" : u) + "): daar vult de app niets in");
        return false;
    }

    private void inlog(JSONObject st) {
        long nu = System.currentTimeMillis();
        boolean veld = st.optBoolean("pw") || st.optBoolean("user") || st.optBoolean("code");
        if (veld && !handmatig && !Facturen.eigenSite(st.optString("url"), domeinen)) {
            // Nooit gegevens invullen op een andere website (of zonder https)
            handmatigOf("De inlogpagina staat niet op " + android.text.TextUtils.join(" of ", domeinen) + " (" + Facturen.host(st.optString("url"))
                    + "). Hoort dat domein bij de leverancier, zet het dan bij Instellingen → Meer → Andere domeinen");
            return;
        }
        boolean pw = st.optBoolean("pw"), user = st.optBoolean("user"), code = st.optBoolean("code");
        String fout = st.optString("fout");
        if (code && !pw) {
            stil = 0;
            if (handmatig) return;
            if (codePogingen >= 2) { einde(fout(fout.isEmpty() ? "De inlogcode werd niet geaccepteerd" : "De website zegt: " + fout, true)); return; }
            String bron = lev.optJSONObject("code") == null ? "geen" : lev.optJSONObject("code").optString("bron", "geen");
            if ("geen".equals(bron)) { handmatigOf("De website vraagt een inlogcode"); return; }
            codeBezig = true;
            voortgang = "sms".equals(bron) ? "Wachten op de inlogcode per sms…" : "Wachten op de inlogcode in Eigen Cloud…";
            final long sinds = codeSinds > 0 ? codeSinds : nu - 120_000L;
            Thread t = new Thread(() -> {
                try {
                    String cd = Facturen.wachtOpCode(c, lev, sinds, stop, codeGezien);
                    h.post(() -> {
                        codeBezig = false; codePogingen++; codeSinds = System.currentTimeMillis();
                        if (fase == KLAAR) return;
                        voortgang = "Code invullen…";
                        if (!hierVeilig()) return;
                        js("__fr.vul('code'," + JSONObject.quote(cd) + "," + cfgJs + ")", r -> js("__fr.verstuur('code'," + cfgJs + ")", null));
                        wachtTot = System.currentTimeMillis() + 5000;
                    });
                } catch (Throwable e) {
                    h.post(() -> { codeBezig = false; if (zichtbaar) { handmatig = true; voortgang = Facturen.kort(e) + ". Vul de code zelf in."; } else einde(fout(Facturen.kort(e), true)); });
                }
            }, "facturen-code");
            t.start();
            return;
        }
        if (pw) {
            stil = 0;
            if (handmatig) return;
            if (wachtwoord == null || wachtwoord.isEmpty()) { handmatigOf("Er is geen wachtwoord ingevuld"); return; }
            if (pwPogingen >= 2) { einde(fout(fout.isEmpty() ? "Inloggen lukt niet: controleer je gebruikersnaam en wachtwoord, of kijk mee wat de website vraagt" : "De website zegt: " + fout, true)); return; }
            if (!hierVeilig()) return;
            if (user && st.optBoolean("userLeeg") && !gebruiker.isEmpty()) js("__fr.vul('gebruiker'," + JSONObject.quote(gebruiker) + "," + cfgJs + ")", null);
            js("__fr.vul('wachtwoord'," + JSONObject.quote(wachtwoord) + "," + cfgJs + ")", r -> js("__fr.verstuur('wachtwoord'," + cfgJs + ")", null));
            codeSinds = nu; pwPogingen++;
            voortgang = "Inloggen…";
            wachtTot = nu + 6000;
            return;
        }
        if (user) {
            stil = 0;
            if (handmatig) return;
            if (gebruiker.isEmpty()) { handmatigOf("Er is geen gebruikersnaam ingevuld"); return; }
            if (userPogingen >= 3) { einde(fout(fout.isEmpty() ? "De website accepteert de gebruikersnaam niet" : "De website zegt: " + fout, true)); return; }
            if (!hierVeilig()) return;
            if (st.optBoolean("userLeeg")) js("__fr.vul('gebruiker'," + JSONObject.quote(gebruiker) + "," + cfgJs + ")", r -> js("__fr.verstuur('gebruiker'," + cfgJs + ")", null));
            else js("__fr.verstuur('gebruiker'," + cfgJs + ")", null);
            userPogingen++;
            voortgang = "Gebruikersnaam invullen…";
            wachtTot = nu + 5000;
            return;
        }
        // Geen velden: een startpagina met een inlogknop, of al ingelogd
        if (!st.optBoolean("uit") && st.optBoolean("knop") && inlogKlikken < 2 && pwPogingen == 0) {
            js("__fr.inlogKnop()", null); inlogKlikken++; voortgang = "Naar de inlogpagina…"; wachtTot = nu + 5000; return;
        }
        if (!"complete".equals(st.optString("klaar"))) return;
        if (++stil >= (st.optBoolean("uit") ? 1 : 2)) {
            fase = NAAR; stil = 0; voortgang = "Ingelogd. Naar de facturen…"; handmatig = false;
        } else voortgang = handmatig ? voortgang : "Even kijken…";
    }

    private void handmatigOf(String reden) {
        if (zichtbaar) { handmatig = true; voortgang = reden + ": log zelf in, daarna gaat de app verder."; }
        else einde(fout(reden + ". Kijk één keer mee om zelf in te loggen.", true));
    }

    private void naar(JSONObject st) {
        long nu = System.currentTimeMillis();
        if ((st.optBoolean("pw") || st.optBoolean("user")) && pwPogingen < 2) { fase = INLOG; naarGeladen = false; return; } // sessie verlopen
        String url = lev.optString("facturenUrl", "");
        if (!url.isEmpty()) {
            if (!naarGeladen) { naarGeladen = true; wv.loadUrl(url); wachtTot = nu + 6000; return; }
            fase = ZOEK; return;
        }
        js("__fr.menu(" + JSONObject.quote(lev.optString("menu", "facturen")) + ")", r -> {
            if (fase != NAAR) return;
            if (str(r).isEmpty()) {
                if (++menuPogingen >= 5) einde(fout("Kan de facturenpagina niet vinden. Vul bij Geavanceerd het adres van de facturenpagina in.", false));
                return;
            }
            voortgang = "Facturenpagina openen…";
            fase = ZOEK; wachtTot = System.currentTimeMillis() + 6000;
        });
    }

    private void zoek() {
        // Eerst zoveel mogelijk facturen op de pagina laten zien ("Toon meer"), hooguit 15 keer; stoppen als er niets bijkomt
        if (!meerKlaar && !lev.optString("meer", Facturen.MEER).isEmpty() && meerKlikken < 15) {
            js("__fr.tel(" + JSONObject.quote(lev.optString("knoppen")) + ")", rt -> {
                if (fase != ZOEK) return;
                int n = 0;
                try { n = Integer.parseInt(str(rt).trim()); } catch (Exception ignored) { }
                if (meerVorige >= 0 && n <= meerVorige) { meerKlaar = true; verder(300); return; } // de vorige klik gaf niets extra
                meerVorige = n;
                js("__fr.meer(" + JSONObject.quote(lev.optString("meer", Facturen.MEER)) + ")", r -> {
                    if (fase != ZOEK) return;
                    if (str(r).isEmpty()) { meerKlaar = true; verder(300); return; }
                    meerKlikken++;
                    voortgang = "Meer facturen laden (" + meerKlikken + ")…";
                    verder(2500);
                });
            });
            return;
        }
        js("__fr.knoppen(" + JSONObject.quote(lev.optString("knoppen")) + "," + lev.optInt("max", 50) + ")", r -> {
            if (fase != ZOEK) return;
            try {
                JSONArray l = new JSONArray(str(r).isEmpty() ? "[]" : str(r));
                if (l.length() == 0) {
                    if (++zoekPogingen >= 6) { einde(fout("Geen downloadknoppen gevonden op de facturenpagina. Pas bij Geavanceerd het patroon voor downloadknoppen aan.", false)); return; }
                    voortgang = "Facturen zoeken…";
                    verder(2500); return;
                }
                knoppen = l; knopIndex = 0; lijstUrl = wv.getUrl(); knopSinds = 0;
                fase = DOWNLOAD;
                voortgang = l.length() + (l.length() == 1 ? " factuur gevonden" : " facturen gevonden") + ", ophalen…";
                verder(200);
            } catch (Throwable e) { einde(fout("Onverwachte fout: " + Facturen.kort(e), false)); }
        });
    }

    private void volgendeKnop() {
        long nu = System.currentTimeMillis();
        if (knopSinds > 0) { // wacht op wat de vorige klik opleverde
            if (vangsten.get() == vangstenVoor && nu - knopSinds < 12_000) { verder(500); return; }
            knopSinds = 0; knopIndex++;
        }
        if (knopIndex >= knoppen.length()) { fase = AFRONDEN; afrondenSinds = nu; verder(500); return; }
        // Vervolg (niet de eerste keer): na 5 al bekende facturen op rij zijn de rest oudere die er al zijn
        if (vervolg && bekendOpRij >= 5 && bezig.get() == 0) { voortgang = "Alle nieuwe facturen zijn binnen"; fase = AFRONDEN; afrondenSinds = nu; verder(300); return; }
        // Weggeklikt naar een andere pagina? Eerst terug naar de lijst (en weer "meer" uitklappen).
        String nuUrl = wv.getUrl();
        if (lijstUrl != null && nuUrl != null && !nuUrl.equals(lijstUrl) && terug < 10) {
            terug++;
            if (wv.canGoBack()) wv.goBack(); else wv.loadUrl(lijstUrl);
            final int uitklappen = meerKlikken;
            h.postDelayed(() -> klapUit(uitklappen, () -> js("__fr.knoppen(" + JSONObject.quote(lev.optString("knoppen")) + "," + lev.optInt("max", 50) + ")", r -> verder(300))), 4000);
            return;
        }
        JSONObject k = knoppen.optJSONObject(knopIndex);
        voortgang = "Factuur " + (knopIndex + 1) + " van " + knoppen.length() + " ophalen…";
        String href = k == null ? "" : k.optString("href", "");
        if (!href.isEmpty()) { if (Facturen.eigenSite(href, domeinen)) haal(href, null, lijstUrl); knopIndex++; verder(300); return; } // links naar andere websites: niet
        vangstenVoor = vangsten.get(); knopSinds = nu;
        js("__fr.klik(" + (k == null ? -1 : k.optInt("i")) + ")", null);
        verder(800);
    }

    /** Na het teruggaan naar de lijst: weer zo vaak op "meer" klikken als de eerste keer, zodat de knoppen kloppen. */
    private void klapUit(int n, Runnable daarna) {
        if (n <= 0 || fase == KLAAR) { daarna.run(); return; }
        js("__fr.meer(" + JSONObject.quote(lev.optString("meer", Facturen.MEER)) + ")", r -> {
            if (str(r).isEmpty()) { daarna.run(); return; }
            h.postDelayed(() -> klapUit(n - 1, daarna), 2500);
        });
    }

    private void afronden() {
        if (bezig.get() > 0 && System.currentTimeMillis() - afrondenSinds < 90_000) { voortgang = "Downloads afronden…"; verder(700); return; }
        JSONObject r = new JSONObject();
        try {
            if (pdfs.isEmpty()) r = fout(geenPdf.get() > 0 ? "Op de downloadknoppen kwam geen PDF terug (wel een webpagina). Pas bij Geavanceerd het patroon of de facturenpagina aan."
                    : "Op de downloadknoppen kwam geen PDF terug", false);
            else r.put("ok", true);
        } catch (Exception ignored) { }
        einde(r);
    }

    // ================= PDF's opvangen =================

    void opDownload(String url, String cd) {
        if (url == null) return;
        vangsten.incrementAndGet();
        if (url.startsWith("blob:")) { js("__fr.blob(" + JSONObject.quote(url) + "," + JSONObject.quote(Facturen.naamUitDisposition(cd)) + ")", null); return; }
        if (url.startsWith("data:")) { opData(url, Facturen.naamUitDisposition(cd)); return; }
        haal(url, cd, wv.getUrl());
    }

    void opData(String dataUrl, String naam) {
        try {
            int i = dataUrl.indexOf(',');
            if (i < 0 || dataUrl.length() > 21_000_000 || !dataUrl.substring(0, i).contains(";base64")) return;
            byte[] b = android.util.Base64.decode(dataUrl.substring(i + 1), android.util.Base64.DEFAULT);
            neem(b, naam, null);
        } catch (Throwable ignored) { }
    }

    void haal(String url, String cd, String referer) {
        if (url == null || !url.matches("(?i)https://.*")) return; // alleen https
        synchronized (opgehaald) { if (!opgehaald.add(url)) return; }
        bezig.incrementAndGet();
        final String ref = referer;
        net.execute(() -> {
            try { haalNu(url, cd, ref); } catch (Throwable ignored) { } finally { bezig.decrementAndGet(); }
        });
    }

    private void haalNu(String url, String cd, String referer) throws Exception {
        CookieManager cm = CookieManager.getInstance();
        String u = url;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
            try {
                h.setInstanceFollowRedirects(false);
                h.setConnectTimeout(20_000); h.setReadTimeout(60_000);
                h.setRequestProperty("User-Agent", ua);
                h.setRequestProperty("Accept", "application/pdf,*/*");
                String ck = cm.getCookie(u);
                if (ck != null) h.setRequestProperty("Cookie", ck);
                if (referer != null && Facturen.host(referer).equals(Facturen.host(u))) h.setRequestProperty("Referer", referer);
                int code = h.getResponseCode();
                List<String> zet = h.getHeaderFields().get("Set-Cookie");
                if (zet != null) for (String z : zet) cm.setCookie(u, z);
                if (code >= 300 && code < 400 && h.getHeaderField("Location") != null) {
                    u = new URL(new URL(u), h.getHeaderField("Location")).toString();
                    if (!u.matches("(?i)https://.*")) return;
                    continue;
                }
                if (code >= 400) return;
                byte[] b;
                try (InputStream in = h.getInputStream()) { b = Facturen.lees(in, Facturen.MAX_PDF); }
                String naam = Facturen.naamUitDisposition(h.getHeaderField("Content-Disposition"));
                if (naam.isEmpty()) naam = Facturen.naamUitDisposition(cd);
                if (!neem(b, naam, u)) geenPdf.incrementAndGet();
                return;
            } finally { h.disconnect(); }
        }
    }

    /** Een binnengekomen bestand: alleen echte PDF's tellen. */
    boolean neem(byte[] b, String naam, String url) {
        if (!Facturen.isPdf(b)) return false;
        try {
            String sha = Facturen.sha(b);
            synchronized (pdfs) {
                if (!pdfs.containsKey(sha)) {
                    if (bekend.contains(sha)) bekendOpRij++; else bekendOpRij = 0;
                    JSONObject bron = new JSONObject().put("site", domein).put("url", url == null ? "" : url.replaceAll("[?#].*$", "")).put("pagina", paginaUrl == null ? "" : paginaUrl.replaceAll("[?#].*$", ""));
                    pdfs.put(sha, new Facturen.Pdf(b, Facturen.bestandsnaam(naam, url, lev.optString("naam"), sha), sha, null, bron));
                }
            }
            vangsten.incrementAndGet();
            return true;
        } catch (Exception e) { return false; }
    }

    // ================= einde =================

    private JSONObject fout(String msg, boolean inlogNodig) {
        JSONObject r = new JSONObject();
        try { r.put("ok", false).put("msg", msg).put("inlogNodig", inlogNodig); } catch (Exception ignored) { }
        return r;
    }

    /** Afronden: WebView opruimen, facturen bewaren en afleveren, toestand bijwerken, klaar melden. */
    void einde(JSONObject r) {
        if (klaarGemeld) return;
        klaarGemeld = true;
        fase = KLAAR;
        stop.set(true);
        h.removeCallbacksAndMessages(null);
        try { CookieManager.getInstance().flush(); } catch (Throwable ignored) { }
        for (WebView k : kinderen) { try { k.destroy(); } catch (Throwable ignored) { } }
        kinderen.clear();
        if (wv != null && eigenWv) { try { wv.stopLoading(); wv.removeJavascriptInterface("FrBrug"); wv.destroy(); } catch (Throwable ignored) { } }
        else if (wv != null) { try { wv.removeJavascriptInterface("FrBrug"); } catch (Throwable ignored) { } }
        final java.util.List<Facturen.Pdf> lijst;
        synchronized (pdfs) { lijst = new java.util.ArrayList<>(pdfs.values()); }
        voortgang = r.optBoolean("ok") ? "Facturen bewaren…" : r.optString("msg");
        new Thread(() -> {
            JSONObject uit = r;
            try {
                uit.put("id", id).put("naam", lev.optString("naam"));
                if (r.optBoolean("ok")) {
                    JSONObject v = Facturen.verwerk(c, lev, lijst);
                    for (java.util.Iterator<String> it = v.keys(); it.hasNext(); ) { String k = it.next(); uit.put(k, v.get(k)); }
                    int n = v.optInt("nieuw");
                    uit.put("msg", n == 0 ? "Geen nieuwe facturen (" + v.optInt("gevonden") + " bekeken)" : n + (n == 1 ? " nieuwe factuur" : " nieuwe facturen"));
                    if (mail && v.optInt("gevonden") == 0) uit.put("msg", "Geen facturen gevonden in de mail");
                } else if (!lijst.isEmpty() && !mail) {
                    // Niet helemaal gelukt (bijv. de tijd was om), maar wel facturen gevonden: die toch bewaren
                    JSONObject v = Facturen.verwerk(c, lev, lijst);
                    int n = v.optInt("nieuw");
                    uit.put("nieuw", n).put("namen", v.optJSONArray("namen"));
                    if (n > 0) uit.put("msg", uit.optString("msg") + " " + Taal.tn(c, n, "({n} nieuwe factuur wel bewaard)", "({n} nieuwe facturen wel bewaard)"));
                }
                long nu = System.currentTimeMillis();
                JSONObject t = new JSONObject().put("lastTry", nu).put("ok", uit.optBoolean("ok")).put("msg", uit.optString("msg"))
                        .put("inlogNodig", uit.optBoolean("inlogNodig")).put("bezigSinds", 0);
                if (uit.optBoolean("ok")) {
                    t.put("lastOk", nu).put("nieuw", uit.optInt("nieuw"));
                    // De hele periode is nu opgehaald (bij mail pas als er niets meer klaarstaat)
                    boolean mailMeer = mail && Facturen.toestand(c, id).optBoolean("mailMeer");
                    if (uit.optBoolean("heel") && !mailMeer) t.put("gedekt", uit.optLong("vanaf")).put("heelPogingen", 0);
                } else if (!vervolg) {
                    t.put("heelPogingen", Facturen.toestand(c, id).optInt("heelPogingen") + 1); // de hele periode lukte niet
                }
                Facturen.werkBij(c, id, t);
                Facturen.logRun(c, id, new JSONObject().put("t", nu).put("ok", uit.optBoolean("ok")).put("msg", uit.optString("msg"))
                        .put("nieuw", uit.optInt("nieuw")).put("namen", uit.optJSONArray("namen") == null ? new JSONArray() : uit.optJSONArray("namen"))
                        .put("auto", !zichtbaar && klaarAuto));
                if (!uit.optBoolean("ok")) App.log(c, "FACTUREN", lev.optString("naam") + ": " + uit.optString("msg"));
            } catch (Throwable e) {
                try { uit = fout("Bewaren mislukt: " + Facturen.kort(e), false).put("id", id).put("naam", lev.optString("naam")); } catch (Exception ignored) { }
                Facturen.werkBij(c, id, uit);
            } finally {
                net.shutdown();
                LOPEND.compareAndSet(this, null);
            }
            voortgang = uit.optString("msg");
            if (klaar != null) { try { klaar.klaar(uit); } catch (Throwable ignored) { } }
            if (!klaarAuto) { try { FacturenJob.schedule(c); } catch (Throwable ignored) { } } // met de hand: planning bijwerken (de taak doet dat zelf)
        }, "facturen-bewaren").start();
    }

    /** Stoppen (knop in de app of Android stopt de achtergrondtaak). */
    void stoppen() { stop.set(true); h.post(this::tik); }

    // ================= hulp =================

    private void js(String expr, android.webkit.ValueCallback<String> cb) {
        if (wv == null || (fase == KLAAR && cb != null)) return;
        try {
            wv.evaluateJavascript(robot(c).replace("__SLEUTEL__", sleutel) + "\n;" + expr, v -> { if (cb != null) cb.onReceiveValue(str(v)); });
        } catch (Throwable e) { if (cb != null) cb.onReceiveValue(""); }
    }

    /** Uitkomst van evaluateJavascript (een JSON-waarde) als gewone tekst. */
    static String str(String v) {
        if (v == null || v.equals("null") || v.isEmpty()) return "";
        if (!v.startsWith("\"")) return v;
        try { return new JSONArray("[" + v + "]").optString(0, ""); } catch (Exception e) { return ""; }
    }

    static final class Client extends WebViewClient {
        final FacturenRun r;
        Client(FacturenRun r) { this.r = r; }
        @Override public void onPageStarted(WebView v, String url, Bitmap fav) { r.js("0", null); }
        @Override public void onPageFinished(WebView v, String url) { r.geladen = true; r.js("0", null); }
        @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
            // Alleen https; geen andere apps openen (intent:, tel: …) en geen onversleutelde pagina's
            return !"https".equalsIgnoreCase(req.getUrl().getScheme());
        }
        @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
            r.einde(r.fout("De website liep vast", false));
            if (!r.eigenWv) { // meekijken: deze WebView is onbruikbaar en moet weg
                try { android.view.ViewGroup p = (android.view.ViewGroup) v.getParent(); if (p != null) p.removeView(v); v.destroy(); } catch (Throwable ignored) { }
            }
            return true; // anders stopt de hele app
        }
    }

    /** Vensters die de website opent (vaak de PDF zelf): het adres opvangen en het venster weer sluiten. */
    static final class Chrome extends WebChromeClient {
        final FacturenRun r;
        Chrome(FacturenRun r) { this.r = r; }
        @Override public boolean onCreateWindow(WebView v, boolean dialog, boolean gebaar, Message msg) {
            if (r.fase == KLAAR) return false;
            WebView kind = new WebView(r.c);
            r.kinderen.add(kind);
            kind.getSettings().setJavaScriptEnabled(false);
            kind.setWebViewClient(new Kind(r));
            kind.setDownloadListener((url, agent, cd, mime, len) -> { r.opDownload(url, cd); r.h.post(kind::destroy); });
            ((WebView.WebViewTransport) msg.obj).setWebView(kind);
            msg.sendToTarget();
            r.h.postDelayed(kind::destroy, 30_000);
            return true;
        }
        @Override public boolean onJsAlert(WebView v, String url, String m, JsResult res) { res.confirm(); return true; }
        @Override public boolean onJsConfirm(WebView v, String url, String m, JsResult res) { res.confirm(); return true; }
        @Override public boolean onJsPrompt(WebView v, String url, String m, String d, JsPromptResult res) { res.cancel(); return true; }
        @Override public boolean onJsBeforeUnload(WebView v, String url, String m, JsResult res) { res.confirm(); return true; }
    }

    static final class Kind extends WebViewClient {
        final FacturenRun r;
        Kind(FacturenRun r) { this.r = r; }
        @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) { vang(v, req.getUrl().toString()); return true; }
        @Override public void onPageStarted(WebView v, String url, Bitmap fav) { if (url != null && !url.startsWith("about:")) { v.stopLoading(); vang(v, url); } }
        private void vang(WebView v, String url) { r.opDownload(url, null); r.h.post(v::destroy); }
        @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
            try { r.kinderen.remove(v); v.destroy(); } catch (Throwable ignored) { }
            return true; // nooit de hele app laten stoppen
        }
    }

    /** Alleen voor de robot in de pagina: PDF's die de pagina zelf maakt (blob:/data:). */
    static final class Brug {
        final FacturenRun r;
        Brug(FacturenRun r) { this.r = r; }
        @JavascriptInterface public void blob(String sleutel, String dataUrl, String naam) {
            if (r.fase < ZOEK || r.fase == KLAAR || dataUrl == null) return; // alleen tijdens het ophalen
            if (!r.sleutel.equals(sleutel) || !Facturen.eigenSite(r.paginaUrl, r.domeinen)) return; // alleen van onze robot, op de eigen website
            r.opData(dataUrl, naam == null ? "" : naam);
        }
    }
}
