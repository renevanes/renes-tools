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
    private static final int REQ_NOTIF = 41;
    private static final int REQ_HERSTEL_TREE = 44;
    private static final int REQ_RECTREE = 21;
    private static final int REQ_MIC = 22;
    private static final int REQ_SETTINGS = 23;
    private static final int REQ_IMPORT = 24;
    private static final int REQ_HOME = 25;
    private static final int REQ_AUTO = 26;
    static final int REQ_KLUIS = 27;
    static final int REQ_FACT_TREE = 46;
    static final int REQ_GOOGLE_ACC = 49;
    static final int REQ_FACT_PDF = 50;
    /** Bij "Factuur toevoegen": voor welke leverancier (id of eigen naam). */
    String factLevId = "", factLevNaam = "";

    /** Een PDF kiezen om zelf aan het dossier van Facturen toe te voegen. */
    void kiesFactuurPdf(String levId, String levNaam) {
        factLevId = levId == null ? "" : levId; factLevNaam = levNaam == null ? "" : levNaam;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf");
        try { startActivityForResult(i, REQ_FACT_PDF); }
        catch (ActivityNotFoundException e) { js("onFacturenToegevoegd", "{\"error\":\"Bestanden kiezen lukt niet op deze telefoon\"}"); }
    }
    /** Welke rechten (gmail, drive, gmailsend) bij het kiezen van het Google-account op de telefoon. */
    String googleRechten = "";

    /** Google koppelen met een account op de telefoon: eerst kiezen, dan vraagt Android toestemming. */
    void kiesGoogleAccount(String rechten) {
        googleRechten = rechten;
        Facturen.prefs(this).edit().putString("gdRechtenKeuze", rechten).apply(); // ook als Android de app intussen stopt
        try { startActivityForResult(FacturenGoogleToestel.kiesIntent(), REQ_GOOGLE_ACC); }
        catch (Exception e) { js("onFacturenDrive", "{\"error\":\"Accounts kiezen lukt niet op deze telefoon\"}"); }
    }
    static final int REQ_PDF_IMG = 28, REQ_PDF_FILE = 29, REQ_PDF_SAVE = 30, REQ_PDF_DOC = 31, REQ_PDF_VIEW = 32;
    static final int REQ_ID_COPY = 65;
    static final int REQ_SIG_IMG = 33;
    volatile IdCopy idCopy = new IdCopy();
    volatile IdCopy idPickStore;
    volatile String idSession = "", idPickSession = "";
    final java.util.concurrent.atomic.AtomicLong idSecurityGeneration = new java.util.concurrent.atomic.AtomicLong();
    volatile boolean idScreen;
    final java.util.concurrent.ConcurrentHashMap<String,String> masterGrants=new java.util.concurrent.ConcurrentHashMap<>();
    final MasterSession masterSession=new MasterSession();
    final PdfFormAi pdfFormAi=new PdfFormAi(this);
    final MasterPhotos masterPhotos=new MasterPhotos(this);
    final java.util.concurrent.atomic.AtomicLong masterAuthSerial=new java.util.concurrent.atomic.AtomicLong(),masterSecurityGeneration=new java.util.concurrent.atomic.AtomicLong();
    volatile boolean masterForeground,masterAuthPending,masterWanted,masterScreen,masterCover,masterFormBound,masterIdBound;
    volatile String masterView="";
    /**
     * Kaartfoto kiezen uit Stamgegevens: de kiezer gaat even over de app heen. Tot dit moment (elapsedRealtime, hooguit
     * 2 minuten) blijft de ontgrendeling staan, net als bij bestanden kiezen in Bankzaken. Komt de keuze op tijd terug, dan
     * gaat het zonder opnieuw ontgrendelen verder; kom je op een andere manier terug in de app, dan gaat hij alsnog op slot.
     * Tijdens het kiezen weigert de brug alles (masterForeground is dan false).
     */
    volatile long masterPickUntil;volatile boolean masterPickReturned;
    /** Wat er gekozen wordt: "photo" (kaartfoto), "contact" (contact koppelen), "perm" (toegang tot Contacten vragen) of "sig" (handtekening uit een foto). */
    volatile String masterPickKind="";
    final MasterContacts.Pick masterContacts=new MasterContacts.Pick(this);
    static final long MASTER_PICK_MS=120_000L;
    boolean masterPicking(){return masterPickUntil!=0&&android.os.SystemClock.elapsedRealtime()<=masterPickUntil&&masterSession.isOpen();}
    /** De 2 minuten zijn om terwijl de app nog uit beeld is (kiezer open gelaten, andere app, scherm uit): nu op slot. */
    void masterPickExpired(){if(!isDestroyed()&&masterPickUntil!=0&&!masterForeground&&!masterPicking()){masterPickUntil=0;masterPickReturned=false;suspendMaster(false);}}
    private Runnable masterAfterAuth;
    void afterMasterAuth(Runnable task){long due=android.os.SystemClock.elapsedRealtime()+5000;masterAfterAuth=()->{if(android.os.SystemClock.elapsedRealtime()>due)masterAuthSerial.incrementAndGet();task.run();};if(masterForeground)h.post(this::runMasterAfterAuth);}
    void runMasterAfterAuth(){Runnable task=masterAfterAuth;masterAfterAuth=null;if(task!=null)task.run();}
    private final Runnable masterExpiry=()->{if(masterSession.isOpen())scheduleMasterExpiry();else suspendMaster(false);};

    volatile String pdfSaveName = null;
    volatile String importKind = null;
    String pendingShare = null;
    volatile Uri pendingArchive = null;
    private static final String BASE_URL = "https://app.renes-tools.local/";
    /** De app staat op het scherm (dan mag een voorgronddienst met locatie altijd starten). */
    static volatile boolean visible;

    private WebView web;
    /** De pagina heeft zichzelf getekend (met app-slot: het slotscherm staat er al). Tot dan blijft de WebView verborgen. */
    private boolean uiReady = false;
    private View cover;
    private long coverSeq = 0;
    /** De app zoals hij nu draait (voor meldingen van buitenaf, bijv. de widget). */
    static volatile MainActivity live;
    /** Alle open vensters van de app: de gewone app en losse apps (SoloActivity). */
    private static final java.util.Set<MainActivity> ALL = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<MainActivity, Boolean>());
    /** Losse app: welke tool (anders null). Dan geen app-slot en alleen de schermen van die tool. */
    String solo;
    /** Losse kluis: met welke ontgrendeling (Kluis.gen) dit venster de kluis zelf opende; -1 = nog niet. */
    volatile int kluisGen = -1;
    volatile long bankGen = -1;
    final Bank.Unlock bankAuth = new Bank.Unlock();
    private Runnable bankAfterAuth;
    void afterBankAuth(Runnable task) { bankAfterAuth = task; h.post(this::runBankAfterAuth); }
    void runBankAfterAuth() { if (!masterForeground || isDestroyed() || isFinishing()) return; Runnable task = bankAfterAuth; bankAfterAuth = null; if (task != null) task.run(); }

    /** Bankzaken staat in beeld (schermbeveiliging aan) / de gekozen afschriften na inlezen verwijderen. */
    volatile boolean bankScreen = false, bankWis = false;
    /** Een tool met extra ontgrendelen staat in beeld (ToolSlot). */
    volatile boolean toolScreen = false;
    /** Extra ontgrendelen: de app opende zelf een scherm (kiezer, PDF-viewer); weg = tijdstip van uit beeld gaan. */
    private boolean eigenScherm, wegIntern, slotCover;
    private long wegOp;
    boolean isSoloKluis() { return isSolo() && "kluis".equals(solo); }
    /** Losse app (zonder app-slot): de kluis telt alleen als open als dit venster hem zelf met vingerafdruk opende. */
    boolean kluisMine() { return !isSolo() || kluisGen == Kluis.gen; }
    /** De kluis is open én mag door dit venster gebruikt worden. */
    boolean kluisOk() { return Kluis.isOpen() && kluisMine(); }
    /** Een losse bank-app kan niet meeliften op de ontgrendeling van een ander venster. */
    boolean bankOk() { return (!isSolo() || "bank".equals(solo)) && Bank.session(bankGen); }

    final String speechOwner=java.util.UUID.randomUUID().toString();
    boolean isSolo() { return this instanceof SoloActivity; }

    /** Losse app die intussen is uitgezet: naar de gewone app (met app-slot) en dit venster sluiten. */
    boolean soloRevoked() {
        if (!isSolo() || Solo.allowed(this, solo)) return false;
        Intent i = new Intent(this, MainActivity.class).setAction(Intent.ACTION_VIEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (Solo.known(solo)) i.putExtra("open", solo);
        try { startActivity(i); } catch (Exception ignored) { }
        finishAndRemoveTask();
        return true;
    }

    /** Losse apps van deze tool sluiten (uitgezet in Instellingen). */
    static void closeSolo(String tool) {
        MainActivity[] list;
        synchronized (ALL) { list = ALL.toArray(new MainActivity[0]); }
        for (MainActivity m : list) if (m != null && m.isSolo() && tool.equals(m.solo)) m.h.post(m::finishAndRemoveTask);
    }

    /** JavaScript in elk open venster uitvoeren (gewone app en losse apps). */
    static void jsAll(String fn, String arg) {
        MainActivity[] list;
        synchronized (ALL) { list = ALL.toArray(new MainActivity[0]); }
        for (MainActivity m : list) if (m != null) m.js(fn, arg);
    }
    /** VPN: welke tunnel aan moet na de toestemming van Android; mag de pagina nu de camera (QR-code scannen)? */
    String vpnPending;
    volatile boolean camAllowed;

    /** Inhoud afdekken (app gaat uit beeld of start nog). */
    void coverOn() { if (cover != null) { coverSeq++; cover.setVisibility(View.VISIBLE); } }

    /**
     * Afdekking weg: eerst (als het moet) het slotscherm tonen, en pas als de WebView dat echt getekend heeft
     * de laag weghalen. Zo is er nooit een flits van de inhoud. Vangnet: na 1,5 s in elk geval weg.
     */
    void coverOff(boolean lock) { coverOff(lock, false); }
    /** wachten: geen app-slot, maar wel eerst laten tekenen (een tool met extra ontgrendelen ging net op slot). */
    void coverOff(boolean lock, boolean wachten) {
        if (cover == null || web == null || masterCover) return;
        final long seq = ++coverSeq;
        Runnable off = () -> { if (seq == coverSeq && cover != null && !masterCover) cover.setVisibility(View.GONE); };
        if (!lock && !wachten) { cover.setVisibility(View.GONE); return; }
        web.evaluateJavascript(lock ? "window.onLock&&window.onLock();true" : "true", v -> {
            if (web == null) return;
            try { web.postVisualStateCallback(seq, new Drawn(off)); } catch (Throwable t) { off.run(); }
        });
        h.postDelayed(off, 1500);
    }

    static final class Drawn extends WebView.VisualStateCallback {
        private final Runnable r;
        Drawn(Runnable r) { this.r = r; }
        @Override public void onComplete(long id) { r.run(); }
    }
    private View topBar, bottomBar;
    final Handler h = new Handler(Looper.getMainLooper());
    private String pendingOpen = null;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // Meteen in de kleur van het gekozen thema (ook automatisch 's avonds donker): geen witte flits bij het opstarten
        final int bg0 = Thema.bg(this);
        final String bgHex = Thema.hex(bg0);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(bg0));
        if (isSolo()) {
            String t = getIntent() == null ? null : getIntent().getStringExtra("solo");
            if (!Solo.allowed(this, t)) {
                // Uitgezet (of onbekend): gewoon de app openen, met app-slot, bij die tool
                Intent i = new Intent(this, MainActivity.class).setAction(Intent.ACTION_VIEW)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                if (Solo.known(t)) i.putExtra("open", t);
                try { startActivity(i); } catch (Exception ignored) { }
                finish();
                return;
            }
            solo = t;
            if ("kluis".equals(t)) Kluis.close(this); // de kluis opnieuw laten openen met vingerafdruk, ook als hij in de app nog open stond
            String label = getIntent().getStringExtra("label"), color = getIntent().getStringExtra("color"), glyph = getIntent().getStringExtra("glyph");
            if (label == null || label.isEmpty() || label.length() > 40) label = "Tools";
            if (glyph != null && glyph.length() > 4) glyph = null;
            try { setTaskDescription(new android.app.ActivityManager.TaskDescription(label, Solo.taskIcon(color, glyph), Solo.color(color))); }
            catch (Exception ignored) { }
        } else live = this;
        synchronized (ALL) { ALL.add(this); }
        // Een terugzetten dat halverwege stopte (ook als de app vóór het ontgrendelen startte): eerst afmaken of terugdraaien
        if (!isSolo() || "backup".equals(solo)) AppBackup.recoverIfNeeded(getApplicationContext());
        IdCopy.cleanStale(getApplicationContext());
        PlayPolicy.sanitizePreferences(getApplicationContext());
        if (!Updater.viaPlay(this)) {
            RedialService.createChannel(this);
            WaBackupService.createChannel(this);
            WaBackupJob.ensureScheduled(this); // houdt de nachtelijke backup gepland
            Care.ensureScheduled(this);
            NotifCenter.ensureAll(this);        // alle soorten meldingen met nette namen in Android         // dagelijks onderhoud: backup-controle (maandelijks) en batterij (wekelijks)
            AllBackupJob.ensureScheduled(this);
            TranscribeJob.ensureScheduled(this);
            ApparatenJob.ensure(getApplicationContext(), false); // Apparaten op de achtergrond (alleen als dat aan staat)
            // Automatiseringen: plekken opnieuw instellen als Android ze kwijt kan zijn (bijv. na geforceerd stoppen op Oppo)
            if (System.currentTimeMillis() - Auto.prefs(this).getLong("armedAt", 0) > 6 * 3600_000L) Auto.armPlaces(this, true);
        }
        PodcastDownloads.warm(this);
        PodcastDownloadJob.schedule(this);
        // Net teruggezet na een herinstallatie: alles opnieuw inplannen en de modellen weer downloaden
        if (!isSolo() && !Updater.viaPlay(this)) {
            final Context app = getApplicationContext();
            new Thread(() -> { try { Herstel.afterRestart(app); Herstel.resumeModels(app); } catch (Throwable t) { App.log(app, "HERSTEL", "na start: " + t); } }, "herstel-start").start();
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        topBar = new View(this);
        bottomBar = new View(this);
        web = new PrivWeb(this);
        root.addView(topBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0));
        // De WebView met daarover een dichte laag: met app-slot blijft die liggen tot het slotscherm echt getekend is.
        android.widget.FrameLayout holder = new android.widget.FrameLayout(this);
        holder.addView(web, new android.widget.FrameLayout.LayoutParams(-1, -1));
        cover = new View(this);
        cover.setBackgroundColor(bg0);
        cover.setClickable(true); // aanrakingen niet doorlaten naar de inhoud eronder
        cover.setVisibility(View.GONE);
        holder.addView(cover, new android.widget.FrameLayout.LayoutParams(-1, -1));
        root.addView(holder, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(bottomBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0));
        setContentView(root);
        setBars(bgHex, bgHex, bg0 == Thema.BG_LIGHT);

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
        s.setAllowContentAccess(false); // geen content://-adressen: de pagina laadt alleen eigen inhoud
        s.setTextZoom(textZoom(this));
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        web.setWebViewClient(new MediaClient(this));
        web.setWebChromeClient(new Chrome(this));
        web.addJavascriptInterface(new Bridge(this), "Android");
        web.setBackgroundColor(bg0);

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, (OnBackInvokedCallback) this::handleBack);
        }

        pendingOpen = openExtra(this, getIntent());
        boolean fromHistory = getIntent() != null && (getIntent().getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0;
        if (b == null && !fromHistory && takeShare(getIntent())) pendingOpen = "share";
        Shortcuts.refreshPinned(this);
        if (!Kluis.isOpen()) Kluis.clearViews(this); // tijdelijk ontsleutelde documenten van een eerdere keer opruimen
        if (b == null && !fromHistory) playFromShortcut(getIntent()); // niet opnieuw afspelen bij terugkomen via Recents
        if (b == null && !fromHistory) RedialPlan.startMissed(this, getIntent());
        // Met app-slot: niets van de inhoud laten zien voordat het slotscherm er staat (anders flitst het startscherm).
        if (isSolo() || Lock.active(this)) { // losse app: afdekken tot zijn eigen tool getekend is
            coverOn();
            h.postDelayed(() -> { if (!uiReady) showUi(); }, 4000); // vangnet als de pagina het niet meldt
        }
        loadUi();
    }

    /** Pagina klaar (slotscherm al getekend als de app op slot is): nu pas tonen. */
    void showUi() {
        uiReady = true;
        if (web == null) return;
        coverOff(!isSolo() && Lock.locked(this));
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
        if (isSolo()) { soloRevoked(); return; } // losse app: gewoon verder waar je was (tenzij intussen uitgezet)
        if (i == null) return;
        if (ACTION_INSTALL_STATUS.equals(i.getAction())) {
            if (!App.tokenOk(this, i)) return; // niet van onze eigen installatiesessie
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm == null) { js("onUpdateStatus", jsonErr("Installeren kon niet starten")); return; }
                if (!systemInstaller(confirm)) { js("onUpdateStatus", jsonErr("Installeren kon niet starten (onbekend installatiescherm)")); return; }
                js("onUpdateStatus", "{\"state\":\"confirm\",\"kind\":\"apk\"}"); // Android vraagt om te bevestigen
                try { startActivity(confirm); } catch (Exception e) { js("onUpdateStatus", "{\"state\":\"error\",\"kind\":\"apk\",\"error\":\"Installeren kon niet starten\"}"); }
            } else if (st != PackageInstaller.STATUS_SUCCESS) {
                String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                js("onUpdateStatus", jsonErr("Installatie afgebroken" + (msg != null ? ": " + msg : "")));
            }
            return;
        }
        playFromShortcut(i);
        RedialPlan.startMissed(this, i);
        if (takeShare(i)) { js("openTool", "\"share\""); return; }
        String open = openExtra(this, i);
        if (open != null) js("openTool", JSONObject.quote(open));
    }

    /**
     * Welke tool openen. "music-now" begint meteen op te nemen en stuurt geluid naar AudD: alleen vanuit eigen
     * knoppen (met het geheim van deze installatie). Een andere app krijgt gewoon het muziekscherm.
     */
    static String openExtra(Context c, Intent i) {
        String open = i == null ? null : i.getStringExtra("open");
        if ("music-now".equals(open) && !App.tokenOk(c, i)) return "music";
        return open;
    }

    /** Alleen het installatiescherm van Android zelf starten, zonder doorgegeven bestandsrechten. */
    @SuppressWarnings("deprecation")
    private boolean systemInstaller(Intent confirm) {
        try {
            confirm.setFlags(confirm.getFlags() & ~(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION));
            android.content.pm.ResolveInfo ri = getPackageManager().resolveActivity(confirm, 0);
            if (ri == null || ri.activityInfo == null) return false;
            String pkg = ri.activityInfo.packageName;
            if (getPackageName().equals(pkg)) return false;
            // Het installatiescherm is altijd een systeemapp (de naam verschilt per merk, bijv. bij Oppo)
            return (ri.activityInfo.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (Exception e) { return false; }
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
            // VPN aan/uit (alleen onze eigen snelkoppeling)
            if (s.has("vpn")) {
                if (App.tokenOk(this, i) && "toggle".equals(s.optString("vpn"))) {
                    boolean wasOn = VpnSvc.on();
                    String r = Vpn.toggle(getApplicationContext(), "snelkoppeling");
                    if (r.equals(Vpn.NEED_CONSENT)) {
                        vpnPending = "";
                        try { startActivityForResult(android.net.VpnService.prepare(this), VpnBridge.REQ_VPN); } catch (Exception ignored) { }
                    } else if (r.isEmpty()) Toast.makeText(this, Taal.tx(this, wasOn ? "VPN uit" : "VPN gaat aan…"), Toast.LENGTH_SHORT).show();
                }
                i.removeExtra("play");
                return;
            }
            // Speler-snelkoppelingen (alleen onze eigen, met het geheim van deze installatie)
            if (s.has("pod") || s.optBoolean("resume")) {
                if (App.tokenOk(this, i)) {
                    final Context app = getApplicationContext();
                    if (s.optBoolean("resume")) new Thread(() -> { Player.Now n = Player.now(app); if (n != null && !n.playing) Player.playPause(app); }, "resume").start();
                    else {
                        final JSONObject pod = s.getJSONObject("pod");
                        new Thread(() -> { String err = Podcasts.playFromShortcut(app, pod); if (err != null) js("toast", JSONObject.quote(err)); }, "pod-shortcut").start();
                    }
                }
                i.removeExtra("play");
                return;
            }
            String url = s.optString("url").toLowerCase();
            if (!url.startsWith("http://") && !url.startsWith("https://")) return;
            // Alleen onze eigen snelkoppelingen, of (oude snelkoppelingen) een zender uit je favorieten
            if (!App.tokenOk(this, i) && !Radio.isFavoriteUrl(this, s.optString("url"))) { i.removeExtra("play"); return; }
            Radio.prefs(this).edit().putString("last", play).apply();
            RadioService.send(this, RadioService.PLAY, play);
        } catch (Exception ignored) { }
        i.removeExtra("play"); // niet opnieuw starten bij draaien van het scherm
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (soloRevoked()) return;
        // Losse kluis zonder app-slot: niet meeliften op een kluis die in de app (of eerder) is geopend.
        if (isSoloKluis() && Kluis.isOpen() && !kluisMine()) Kluis.close(this);
        applySecure();
        if (web == null) return;
        boolean slot = slotCover;
        if (ToolSlot.teLang(wegIntern, wegOp, android.os.SystemClock.elapsedRealtime())) {
            js("onToolSlotWeg", "");
            if (toolScreen) { coverOn(); slot = true; }
        }
        wegIntern = false; eigenScherm = false; slotCover = false;
        boolean lock = !isSolo() && Lock.onShown(this); // losse app: geen app-slot (en het slot van de app blijft zoals het was)
        Notes.applyRepeats(this); // terugkerende lijstjes die weer leeg moeten
        if (uiReady) js("onNotesMaybeChanged", ""); // misschien afgestreept in de widget
        themeChanged(); // automatisch thema kan intussen gewisseld zijn: afdeklaag en achtergrond in de kleur van nu
        if (!uiReady && !isSolo() && Lock.active(this)) return; // eerste keer: showUi() toont hem zodra de pagina (met slotscherm) klaar is
        coverOff(lock, slot); // eerst het slotscherm tekenen, dan pas de afdekking weg (geen flits van de inhoud)
    }

    @Override
    protected void onStop() {
        // Extra ontgrendelen: uit beeld = tools weer op slot, behalve voor een eigen scherm of de vraag om te ontgrendelen
        // (die laatste alleen met het scherm aan; de interface beslist of die vraag de open tool openhoudt)
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        boolean vraag = !eigenScherm && Lock.busy() && (pm == null || pm.isInteractive());
        wegIntern = eigenScherm || vraag;
        wegOp = android.os.SystemClock.elapsedRealtime();
        if (!eigenScherm) js("onToolSlotWeg", vraag ? "'vraag'" : "");
        if (!wegIntern && toolScreen && web != null) { themeChanged(); coverOn(); slotCover = true; } // bij terugkomen geen flits van de tool
        if (!isSolo()) {
            Lock.onHidden();
            // Met app-slot de inhoud alvast verbergen; bij terugkomen beslist onStart of hij op slot moet.
            if (web != null && Lock.active(this) && !Lock.internalNav && !Lock.busy()) { themeChanged(); coverOn(); }
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (live == this) live = null;
        synchronized (ALL) { ALL.remove(this); }
        if (isFinishing() && !isSolo()) Bridge.stopRecordingPlayback(this); // een opname niet zonder app laten doorspelen
        bankAuth.cancel();bankAfterAuth=null;
        masterSession.close();masterAfterAuth=null;masterAuthSerial.incrementAndGet();h.removeCallbacks(masterExpiry);
        final IdCopy closingId = idCopy;
        new Thread(() -> { try { closingId.clear(); } catch (Throwable ignored) { } }, "id-copy-close").start();
        super.onDestroy();
    }

    /** Elk scherm dat de app zelf opent (behalve links naar andere apps) telt als "binnen de app". */
    @Override
    public void startActivityForResult(Intent intent, int requestCode, android.os.Bundle options) {
        boolean external = intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null
                && ("http".equals(intent.getData().getScheme()) || "https".equals(intent.getData().getScheme()));
        // De eigen PDF-viewer is ook "binnen de app", ook met startActivity (-1); hij houdt het app-slot zelf bij
        boolean viewer = !isSolo() && intent != null && intent.getComponent() != null && getPackageName().equals(intent.getComponent().getPackageName())
                && PdfViewActivity.class.getName().equals(intent.getComponent().getClassName());
        if (viewer) intent.putExtra(PdfViewActivity.EXTRA_IN_APP, PdfViewActivity.TOKEN);
        if (!external && (requestCode >= 0 || viewer) && !isSolo()) Lock.internalNav = true; // startActivity naar een andere app (delen) heeft -1: dat is weggaan
        boolean wasEigen = eigenScherm;
        if (!external && (requestCode >= 0 || viewer) && requestCode != Lock.REQ_CONFIRM) eigenScherm = true; // de vraag om te ontgrendelen: zie onStop
        try { super.startActivityForResult(intent, requestCode, options); }
        catch (RuntimeException e) { eigenScherm = wasEigen; throw e; }
    }

    /** Bankzaken in beeld: geen automatisch invullen (Autofill-diensten) en het toetsenbord leert niets van wat je typt. */
    void bankPrive(boolean on) {
        if (web instanceof PrivWeb) ((PrivWeb) web).prive = on;
        if (web != null && Build.VERSION.SDK_INT >= 26) web.setImportantForAutofill(on ? View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS : View.IMPORTANT_FOR_AUTOFILL_AUTO);
        if (web != null) try { ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).restartInput(web); } catch (Throwable ignored) { }
    }

    /** De WebView van de app; met prive aan vraagt hij het toetsenbord niets te leren (incognito-toetsenbord). */
    static final class PrivWeb extends WebView {
        volatile boolean prive = false;
        PrivWeb(Context c) { super(c); }
        @Override public android.view.inputmethod.InputConnection onCreateInputConnection(android.view.inputmethod.EditorInfo out) {
            android.view.inputmethod.InputConnection ic = super.onCreateInputConnection(out);
            if (prive && out != null && Build.VERSION.SDK_INT >= 26) out.imeOptions |= android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
            return ic;
        }
    }

    void scheduleMasterExpiry(){h.removeCallbacks(masterExpiry);long left=masterSession.remaining();if(left>0)h.postDelayed(masterExpiry,left);}
    void suspendMaster(boolean explicit){
        if(explicit)masterPhotos.cancel();
        masterContacts.cancel();
        pdfFormAi.cancel(null);
        synchronized(masterSession){masterSession.close();masterGrants.clear();if(explicit||!masterAuthPending)masterAuthSerial.incrementAndGet();}
        h.removeCallbacks(masterExpiry);
        long generation;
        synchronized(masterSecurityGeneration){generation=masterSecurityGeneration.incrementAndGet();if(masterScreen){masterCover=true;coverOn();}}
        js("onMasterLocked",Long.toString(generation));
    }
    static void appIdentityChanged(String json){synchronized(ALL){for(MainActivity a:ALL)a.js("onAppIdentity",json);}}
    static void masterResetAll(boolean complete){appIdentityChanged("{}");synchronized(ALL){for(MainActivity a:ALL)a.h.post(()->{a.suspendMaster(true);if(complete)a.js("onMasterReset","");});}}
    void masterSafeFrame(String view,long generation){if(web!=null&&generation==masterSecurityGeneration.get())web.postVisualStateCallback(generation,new MasterDrawn(this,view,generation,false));}
    void finishMasterScreen(long generation){
        synchronized(masterSecurityGeneration){if(isDestroyed()||generation!=masterSecurityGeneration.get()||masterWanted)return;}
        if(web!=null)web.postVisualStateCallback(generation,new MasterDrawn(this,"",generation,true));
    }
    static final class MasterDrawn extends WebView.VisualStateCallback{
        final java.lang.ref.WeakReference<MainActivity> owner;final String view;final long generation;final boolean hide;
        MasterDrawn(MainActivity a,String view,long generation,boolean hide){owner=new java.lang.ref.WeakReference<>(a);this.view=view;this.generation=generation;this.hide=hide;}
        @Override public void onComplete(long request){MainActivity a=owner.get();if(a==null||a.isDestroyed())return;
            synchronized(a.masterSecurityGeneration){
                if(generation!=a.masterSecurityGeneration.get())return;
                if(hide){if(a.masterWanted)return;a.masterScreen=false;a.applySecure();a.bankPrive(a.bankScreen||a.idScreen);}
                else{if(!view.equals(a.masterView))return;if(!a.masterWanted){a.masterScreen=false;a.applySecure();a.bankPrive(a.bankScreen||a.idScreen);}}
                a.masterCover=false;a.coverOff(!a.isSolo()&&Lock.locked(a));
            }
        }
    }

    /** Houd de bescherming vast totdat de verborgen ID-pagina ook uit het getekende frame verdwenen is. */
    void finishIdScreen(long generation) {
        synchronized (idSecurityGeneration) {
            if (isDestroyed() || generation != idSecurityGeneration.get()) return;
            if (web == null) { if (idSession.isEmpty()) { idScreen = false; applySecure(); bankPrive(bankScreen || masterScreen); } return; }
        }
        web.postVisualStateCallback(System.nanoTime(), new IdHidden(this, generation));
    }

    static final class IdHidden extends WebView.VisualStateCallback {
        final java.lang.ref.WeakReference<MainActivity> owner;
        final long generation;
        IdHidden(MainActivity a, long generation) { owner = new java.lang.ref.WeakReference<>(a); this.generation = generation; }
        @Override public void onComplete(long request) {
            MainActivity a = owner.get();
            if (a != null) synchronized (a.idSecurityGeneration) {
                if (!a.isDestroyed() && a.idSession.isEmpty() && a.idSecurityGeneration.get() == generation) { a.idScreen = false; a.applySecure(); a.bankPrive(a.bankScreen || a.masterScreen); }
            }
        }
    }

    /** Met app-slot of een gevoelige pagina: inhoud niet tonen in recente apps of schermafdrukken. */
    void applySecure() {
        // losse app: zonder slot, dus ook zichtbaar in "recente apps"; behalve de losse kluis (documentnamen)
        boolean on = (isSolo() ? isSoloKluis() : Lock.enabled(this)) || toolScreen;
        // Bankzaken in beeld: ook geen schermafdrukken of schermopnames (FLAG_SECURE), op elke Android-versie
        boolean block = bankScreen || idScreen || masterScreen || (Build.VERSION.SDK_INT < 33 && on);
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!on && !bankScreen && !idScreen && !masterScreen);
        if (block) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
    }

    /** Weinig geheugen: een warme, ongebruikte voorleesmotor sluiten (±60 MB). */
    @Override public void onTrimMemory(int level) { super.onTrimMemory(level); if (level >= TRIM_MEMORY_RUNNING_LOW) ReadAloud.dropEngine(); }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false; masterForeground=false; if(!masterPicking())suspendMaster(false);
        // Bankzaken gaat op slot zodra de app uit beeld gaat (niet tijdens bestanden kiezen of de vraag om te ontgrendelen)
        synchronized (bankAuth) {
            bankAuth.pause(Lock.authBusy);
            if (!Bank.kiest() && !Bank.linking && !Lock.authBusy && Bank.isOpen()) Bank.close();
        }
        if ("recording".equals(VoiceNote.state)) VoiceNote.cancel(); // inspreken alleen met de app open
        ReadAloud.leaving(speechOwner); // een bewust gestart boek mag in de voorgronddienst verdergaan
        SystemSpeech.release(); // terugvalstem van de assistent: alleen met de app open
        AiTalk.bargeStop(); // gesprek met de assistent: niet doorluisteren
        try { getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); } catch (Throwable ignored) { }
        js("onPauseApp", "");
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true; masterForeground=true; eigenScherm = false; // een doorzichtig eigen scherm (zonder onStop) telt daarna niet meer
        if(masterPickUntil!=0){
            boolean back=masterPickReturned&&masterPicking();String kind=masterPickKind;masterPickUntil=0;masterPickReturned=false;masterPickKind="";
            if(!back){masterContacts.cancel();suspendMaster(false);}
            else if("contact".equals(kind)||"perm".equals(kind))masterContacts.continueAfterPick();
            else if(!"sig".equals(kind))masterPhotos.continueAfterPick();
        }
        runMasterAfterAuth();bankAuth.resume();runBankAfterAuth();
        js("onResumeApp", "");
        try { GebruikLive.start(getApplicationContext()); } catch (Throwable ignored) { }
        if (isSolo()) return; // updates regelt de gewone app
        // Automatisch op updates controleren, hooguit eens per 30 minuten (de Play-versie krijgt ze via Google Play).
        long last = Updater.prefs(this).getLong("lastCheck", 0);
        if (!Updater.viaPlay(this) && System.currentTimeMillis() - last > 30 * 60 * 1000L) checkUpdates(false);
        // Een onderbroken download van een AI-model vanzelf laten verdergaan (als het netwerk nu mag)
        final Context appCtx = getApplicationContext();
        new Thread(() -> { try { LocalAi.resumeDownloads(appCtx); } catch (Throwable ignored) { } }, "ai-resume").start();
    }

    private void checkUpdates(final boolean manual) { UpdateController.check(this, manual); }

    /** Tekstgrootte: zelf gekozen percentage, of (0) die van de telefoon volgen. */
    static int textZoom(Context c) {
        int z = c.getSharedPreferences("ui", MODE_PRIVATE).getInt("textZoom", 0);
        if (z >= 80 && z <= 200) return z;
        float fs = c.getResources().getConfiguration().fontScale;
        return Math.max(85, Math.min(200, Math.round(fs * 100)));
    }

    /** Een map op de telefoon kiezen voor Facturen (begint in Documenten). */
    void pickFacturenFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (android.os.Build.VERSION.SDK_INT >= 26)
            i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents"));
        try { startActivityForResult(i, REQ_FACT_TREE); }
        catch (ActivityNotFoundException e) { Toast.makeText(this, Taal.tx(this, "Mappenkiezer niet beschikbaar"), Toast.LENGTH_SHORT).show(); }
    }

    /** Roept window[fn](arg) aan in de pagina als die functie bestaat. */
    void js(String fn, String arg) {
        if (web == null) return;
        String code = "window." + fn + "&&window." + fn + "(" + arg + ")";
        h.post(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    /**
     * Voortgang van een update naar de pagina (onUpdateStatus met state "progress"), hooguit 5 keer per seconde.
     * Naar de app zoals hij nu draait (live): wordt het venster tussendoor opnieuw gemaakt, dan komt het daar aan.
     * Elke aanroep heeft zijn eigen Throttle, gebruikt door één thread.
     */
    static Updater.Progress progressTo(String kind) {
        return new Updater.Throttle((ph, done, total) -> {
            try { updateStatus(new JSONObject().put("state", "progress").put("kind", kind).put("phase", ph).put("done", done).put("total", total).toString()); }
            catch (Exception ignored) { }
        }, 200, System::currentTimeMillis);
    }

    /** Uitkomst van een update naar de app zoals hij nu draait. */
    static void updateStatus(String json) { MainActivity m = live; if (m != null) m.js("onUpdateStatus", json); }

    private static String jsonErr(String m) {
        return "{\"state\":\"error\",\"error\":" + JSONObject.quote(m) + "}";
    }

    private void handleBack() {
        if (web == null) { finish(); return; }
        web.evaluateJavascript("window.goBack?window.goBack():false", v -> {
            if ("true".equals(v)) return;
            if (isSolo()) moveTaskToBack(true); else finish(); // losse app: blijft in "recente apps" staan
        });
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() { handleBack(); }

    /** E-reader: volumeknoppen bladeren (alleen terwijl een boek open is; tijdens voorlezen zet de interface dit uit: dan zijn ze voor het volume). */
    volatile boolean ereaderKeys;

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        int k = e == null ? 0 : e.getKeyCode();
        if (ereaderKeys && visible && (k == android.view.KeyEvent.KEYCODE_VOLUME_DOWN || k == android.view.KeyEvent.KEYCODE_VOLUME_UP)) {
            if (e.getAction() == android.view.KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) js("onEreaderKey", k == android.view.KeyEvent.KEYCODE_VOLUME_DOWN ? "1" : "-1");
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** E-reader: lezen op het volle scherm, scherm aan en eigen helderheid; alles terug zodra het boek dicht gaat. */
    @SuppressWarnings("deprecation")
    void ereaderWindow(boolean full, boolean awake, double bright) {
        Window w = getWindow();
        if (awake) w.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        android.view.WindowManager.LayoutParams lp = w.getAttributes();
        float b = Double.isNaN(bright) || bright < 0 ? android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : (float) Math.max(0.01, Math.min(1, bright));
        if (lp.screenBrightness != b) { lp.screenBrightness = b; w.setAttributes(lp); }
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                // Vegen vanaf de rand laat de balken even zien; de inhoud blijft buiten de inkeping (cutout)
                if (full) { c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); c.hide(WindowInsets.Type.systemBars()); }
                else c.show(WindowInsets.Type.systemBars());
            }
        } else {
            View d = w.getDecorView();
            int imm = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
            int f = d.getSystemUiVisibility();
            d.setSystemUiVisibility(full ? f | imm : f & ~imm);
        }
        ereaderCutout(full);
    }

    /** Volle scherm boven een inkeping: Android 9–14 laat anders een zwarte strook; nu tekent het papier tot de rand
     *  en houden de balkjes (in de kleur van het papier) de tekst buiten de inkeping. Vanaf 15 doet de insets-listener dit al. */
    private boolean ereaderFull;
    private int barTop;
    void ereaderCutout(boolean full) {
        if (Build.VERSION.SDK_INT < 28 || Build.VERSION.SDK_INT >= 35 || topBar == null || full == ereaderFull) return;
        ereaderFull = full;
        Window w = getWindow();
        android.view.WindowManager.LayoutParams lp = w.getAttributes();
        lp.layoutInDisplayCutoutMode = full ? android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                : android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
        w.setAttributes(lp);
        View root = (View) topBar.getParent();
        if (full) {
            root.setBackgroundColor(barTop);
            root.setOnApplyWindowInsetsListener((v, ins) -> {
                android.view.DisplayCutout c = ins.getDisplayCutout();
                topBar.getLayoutParams().height = c == null ? 0 : c.getSafeInsetTop();
                bottomBar.getLayoutParams().height = c == null ? 0 : c.getSafeInsetBottom();
                v.setPadding(c == null ? 0 : c.getSafeInsetLeft(), 0, c == null ? 0 : c.getSafeInsetRight(), 0);
                topBar.requestLayout(); bottomBar.requestLayout();
                return ins;
            });
        } else {
            root.setOnApplyWindowInsetsListener(null);
            root.setBackground(null);
            topBar.getLayoutParams().height = 0; bottomBar.getLayoutParams().height = 0;
            root.setPadding(0, 0, 0, 0);
            topBar.requestLayout(); bottomBar.requestLayout();
        }
        root.requestApplyInsets();
    }

    /** Thema gewisseld (in de interface): achtergrond van venster, WebView en afdeklaag mee (op de hoofdthread). */
    void themeChanged() {
        int c = Thema.bg(this);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(c));
        if (web != null) web.setBackgroundColor(c);
        if (cover != null) cover.setBackgroundColor(c);
    }

    void setBars(String top, String bottom, boolean lightBottom) {
        int t = Color.parseColor(top), b = Color.parseColor(bottom);
        barTop = t;
        if (ereaderFull && topBar.getParent() instanceof View) ((View) topBar.getParent()).setBackgroundColor(t); // zijkanten bij de inkeping
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
        if (!PlayPolicy.allows(this, "redial")) return;
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
        if (req == MasterContacts.PERMISSION) { masterPickReturned = masterPickUntil != 0 && "perm".equals(masterPickKind); masterContacts.permissionResult(res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED); return; }
        if (req == REQ_STORAGE || req == REQ_CONTACTS) js("onWaChanged", "");
        if (req == REQ_SMS) js("onSmsChanged", "");
        if (req == REQ_CALLS) js("onCallsChanged", "");
        if (req == REQ_CONTACTS_RW) js("onContactsChanged", "");
        if (req == REQ_TR) js("onTrChanged", "");
        if (req == REQ_NOTIF) js("onSettingsChanged", "");
        if (req == REQ_MIC) js("onMusicChanged", "");
        if (req == REQ_SETTINGS) js("onSettingsChanged", "\"\"");
        if (req == REQ_PERMS) js("onPermissions", permissionState());
        if (req == ApparatenBridge.REQ_PERM) { ApparatenJob.ensure(this); js("onApChanged", ""); }
        if (req == REQ_AUTO) { Auto.armPlaces(this); Vpn.armAuto(this); js("onAutoChanged", ""); js("onVpnChanged", ""); }
        if (req == VpnBridge.REQ_CAM) {
            camAllowed = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
            js("onVpnCamera", camAllowed ? "true" : "false");
        }
    }

    /** WebView: alleen camera (voor de QR-code van de VPN) en alleen als de app daar net zelf om vroeg. */
    static final class Chrome extends WebChromeClient {
        private final MainActivity a;
        Chrome(MainActivity a) { this.a = a; }
        @Override public void onPermissionRequest(android.webkit.PermissionRequest r) {
            boolean ours = r.getOrigin() != null && "app.renes-tools.local".equals(r.getOrigin().getHost());
            boolean onlyVideo = true;
            for (String res : r.getResources()) onlyVideo &= android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res);
            if (ours && onlyVideo && a.camAllowed) r.grant(new String[]{android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            else r.deny();
        }
    }

    void pickContact() {
        Intent i = new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
        try { startActivityForResult(i, REQ_CONTACT); }
        catch (ActivityNotFoundException e) { Toast.makeText(this, Taal.tx(this, "Geen contacten-app gevonden"), Toast.LENGTH_SHORT).show(); }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if(req==MasterPhotos.REQUEST){masterPickReturned=masterPickUntil!=0&&res==RESULT_OK&&data!=null&&data.getData()!=null;masterPhotos.result(res,data);return;}
        if(req==MasterContacts.REQUEST){masterPickReturned=masterPickUntil!=0&&"contact".equals(masterPickKind)&&masterContacts.result(res,data);return;}
        if (req == REQ_SIG_IMG) {
            // Terug uit de eigen kiezer (ook bij annuleren): Stamgegevens hoeft dan niet op slot.
            if ("sig".equals(masterPickKind)) masterPickReturned = masterPickUntil != 0;
            final Uri u = res == RESULT_OK && data != null ? data.getData() : null;
            if (u == null) { js("onSignatureImage", "{\"cancelled\":true}"); return; }
            new Thread(() -> {
                String r;
                try (Voortgang v = Voortgang.van(this, "handtekening", 1)) { r = new JSONObject().put("image", PdfSignatureImage.load(this, u, v)).toString(); }
                catch (Throwable e) { r = "{\"error\":" + JSONObject.quote(e instanceof PdfSignatureImage.Fout ? e.getMessage() : "Deze afbeelding kan niet worden geopend") + "}"; }
                js("onSignatureImage", r);
            }, "sig-image").start();
            return;
        }
        if (req == REQ_FACT_PDF) {
            final Uri u = res == RESULT_OK && data != null ? data.getData() : null;
            if (u == null) { js("onFacturenToegevoegd", "{\"error\":\"\"}"); return; }
            final String levId = factLevId, levNaam = factLevNaam;
            new Thread(() -> {
                String r;
                try {
                    String naam = "";
                    try (android.database.Cursor cu = getContentResolver().query(u, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                        if (cu != null && cu.moveToFirst()) naam = cu.getString(0);
                        if (naam == null) naam = "";
                    } catch (Exception ignored) { }
                    byte[] b;
                    try (Voortgang v = Voortgang.van(this, "factuur", 1)) {
                        try (java.io.InputStream in = v.open(this, u)) { b = Facturen.lees(in, Facturen.MAX_PDF); }
                        v.stap("verwerken", 0, 0);
                        r = new org.json.JSONObject().put("sha", FacturenDossier.voegToe(this, b, naam, levId, levNaam)).toString();
                    }
                } catch (Throwable e) { r = "{\"error\":" + org.json.JSONObject.quote(Facturen.kort(e)) + "}"; }
                js("onFacturenToegevoegd", r);
            }, "facturen-toevoegen").start();
            return;
        }
        if (req == REQ_GOOGLE_ACC) {
            String naam = res == RESULT_OK && data != null ? data.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME) : null;
            if (naam == null || naam.isEmpty()) { js("onFacturenDrive", "{\"error\":\"Geen account gekozen\"}"); return; }
            FacturenGoogleToestel.koppel(this, naam, googleRechten, (err, wie) -> {
                try { js("onFacturenDrive", (err != null ? new org.json.JSONObject().put("error", err) : new org.json.JSONObject().put("naam", wie)).toString()); } catch (Exception ignored) { }
            });
            return;
        }
        if (req == REQ_FACT_TREE) {
            String fout = "";
            if (res == RESULT_OK && data != null && data.getData() != null) {
                try {
                    getContentResolver().takePersistableUriPermission(data.getData(), Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    Uri oud = Facturen.lokaleMap(this);
                    Facturen.prefs(this).edit().putString("lokaal", data.getData().toString()).apply();
                    if (oud != null && !oud.equals(data.getData())) Facturen.laatMapLos(this, oud);
                } catch (Exception e) { fout = "Deze map kan niet worden gebruikt"; }
            } else fout = "geen";
            js("onFacturenMap", JSONObject.quote(fout));
            return;
        }
        if (req == REQ_TREE || req == REQ_HERSTEL_TREE) {
            final boolean herstel = req == REQ_HERSTEL_TREE;
            if (res == RESULT_OK && data != null && data.getData() != null) {
                Uri t = data.getData();
                String tid = "";
                try { tid = DocumentsContract.getTreeDocumentId(t); } catch (Exception ignored) { }
                if (tid.startsWith("primary:Android/media/com.whatsapp")) {
                    Toast.makeText(this, Taal.tx(this, "Kies een map buiten de WhatsApp-map"), Toast.LENGTH_LONG).show();
                    js(herstel ? "onHerstelFolder" : "onWaChanged", "");
                    return;
                }
                try {
                    getContentResolver().takePersistableUriPermission(t,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    WaBackup.prefs(this).edit().putString("dest", t.toString()).apply();
                    AllBackupJob.ensureScheduled(this); // elke nacht (standaard aan) kan nu echt lopen
                } catch (Exception e) {
                    Toast.makeText(this, Taal.tx(this, "Deze map kan niet worden gebruikt"), Toast.LENGTH_LONG).show();
                }
            }
            js(herstel ? "onHerstelFolder" : "onWaChanged", "");
            return;
        }
        if (req == Lock.REQ_CONFIRM) { Lock.onConfirmResult(res == RESULT_OK); return; }
        if (req == VpnBridge.REQ_VPN) {
            if (res == RESULT_OK) {
                String r = Vpn.start(getApplicationContext(), vpnPending, "app");
                js("onVpnChanged", r.isEmpty() ? "" : JSONObject.quote(r.equals(Vpn.NO_TUNNEL) ? "Stel eerst een tunnel in" : "Starten lukt niet"));
            } else js("onVpnChanged", JSONObject.quote("Zonder toestemming van Android kan de VPN niet aan"));
            return;
        }
        if (req == VpnBridge.REQ_VPN_FILE || req == VpnBridge.REQ_VPN_IMG) {
            if (res != RESULT_OK || data == null || data.getData() == null) return;
            if (req == VpnBridge.REQ_VPN_FILE) VpnBridge.onFile(this, data.getData()); else VpnBridge.onImage(this, data.getData());
            return;
        }
        if (req == REQ_HOME) { js("onHomeRole", ""); return; }
        if (req == REQ_IMPORT) {
            final String kind = importKind;
            if (res != RESULT_OK || data == null || data.getData() == null || kind == null) return;
            final Uri u = data.getData();
            if ("archive".equals(kind)) { pendingArchive = u; Secure.forget(); js("onArchivePicked", ""); return; }
            new Thread(() -> {
                String r;
                try {
                    if (HerstelLos.BUSY.get()) throw new Exception("Er wordt al teruggezet; even geduld");
                    r = Restore.preview(getApplicationContext(), kind, u).toString();
                }
                catch (Throwable e) {
                    try { r = new JSONObject().put("error", e.getMessage() == null ? "Bestand niet te lezen" : e.getMessage()).toString(); }
                    catch (Exception e2) { r = "{\"error\":\"Bestand niet te lezen\"}"; }
                }
                js("onRestorePreview", r);
            }, "restore-preview").start();
            return;
        }
        if (req == REQ_PDF_VIEW) {
            if (res != RESULT_OK || data == null || data.getData() == null) return;
            try { startActivity(new Intent(this, PdfViewActivity.class).setData(data.getData()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); } catch (Exception ignored) { }
            return;
        }
        if (req == REQ_ID_COPY) {
            final IdCopy store = idPickStore; final String session = idPickSession; idPickStore = null;
            if (store == null || store != idCopy || !idScreen) return;
            final java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
            if (res == RESULT_OK && data != null) {
                if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount() && i <= IdCopy.MAX_PAGES; i++) uris.add(data.getClipData().getItemAt(i).getUri());
                else if (data.getData() != null) uris.add(data.getData());
            }
            new Thread(() -> {
                String result;
                try (Voortgang v = Voortgang.van(this, "idkopie", uris.size())) { result = store.add(getApplicationContext(), uris, v).put("session", session).toString(); }
                catch (Throwable e) { result = PdfTools.err(e); }
                try { result = new org.json.JSONObject(result).put("session", session).toString(); } catch (Exception ignored) { }
                if (store == idCopy && idScreen) js("onIdCopyPicked", result);
            }, "id-copy-pick").start();
            return;
        }
        if (req == REQ_PDF_IMG || req == REQ_PDF_FILE || req == REQ_PDF_SAVE || req == REQ_PDF_DOC) {
            if (res != RESULT_OK || data == null) return;
            final java.util.ArrayList<Uri> us = new java.util.ArrayList<>();
            if (data.getClipData() != null) for (int k = 0; k < data.getClipData().getItemCount(); k++) us.add(data.getClipData().getItemAt(k).getUri());
            else if (data.getData() != null) us.add(data.getData());
            if (us.isEmpty()) return;
            final Context app = getApplicationContext();
            final String saveName = pdfSaveName != null ? pdfSaveName : getSharedPreferences("pdf", MODE_PRIVATE).getString("saveName", null);
            new Thread(() -> {
                // Opslaan als schrijft alleen weg; bij toevoegen de balk onderin (bestand 2 van 5 …).
                try (Voortgang v = req == REQ_PDF_SAVE ? Voortgang.GEEN : Voortgang.van(this, "pdf", us.size())) { js("onPdf", FeatureBridge.pdfResult(app, req, us, saveName, v)); }
                catch (Throwable e) { js("onPdf", PdfTools.err(e)); }
            }, "pdf-pick").start();
            return;
        }
        if (req == EreaderBridge.REQ_BOOK) {
            if (res == RESULT_OK && data != null && data.getData() != null) EreaderBridge.picked(this, data.getData());
            else js("onEreaderImport", "{\"cancelled\":true}");
            return;
        }
        if (req == BankBridge.REQ_BANK) {
            Bank.picking = false;
            java.util.List<Uri> us = new java.util.ArrayList<>();
            if (res == RESULT_OK && data != null) {
                if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount() && i < 50; i++) us.add(data.getClipData().getItemAt(i).getUri());
                else if (data.getData() != null) us.add(data.getData());
            }
            if (us.isEmpty()) { js("onBankImport", "{\"cancelled\":true}"); return; }
            BankBridge.onPicked(this, us, bankWis);
            return;
        }
        if (req == REQ_KLUIS) {
            if (res != RESULT_OK || data == null || data.getData() == null) return;
            final Uri u = data.getData();
            new Thread(() -> {
                String r;
                try (Voortgang v = Voortgang.van(this, "kluis", 1)) { if (!kluisOk()) throw new Exception("De kluis is weer op slot"); Kluis.add(getApplicationContext(), u, v); r = "ok"; }
                catch (Throwable e) { r = e instanceof OutOfMemoryError ? "Bestand is te groot" : e.getMessage() == null ? "Toevoegen lukt niet" : e.getMessage(); }
                js("onKluisChanged", JSONObject.quote(r));
            }, "kluis-add").start();
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
                    else Toast.makeText(this, Taal.tx(this, "Deze map kan niet worden gebruikt"), Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(this, Taal.tx(this, "Deze map kan niet worden gebruikt"), Toast.LENGTH_LONG).show();
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
            Toast.makeText(this, Taal.tx(this, "Contact lezen lukt niet"), Toast.LENGTH_SHORT).show();
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
        catch (ActivityNotFoundException e) { Toast.makeText(this, Taal.tx(this, "Mappenkiezer niet beschikbaar"), Toast.LENGTH_SHORT).show(); }
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

    /** Alle functies voor de interface. Nieuwere onderdelen staan in FeatureBridge (eigen bestand). */
    static final class Bridge extends ReadAloudBridge {
        private final MainActivity a;
        private final Context ctx;
        static volatile boolean smsBusy = false;
        static volatile boolean callsBusy = false;
        Bridge(MainActivity act) { super(act); a = act; ctx = act.getApplicationContext(); }

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
                o.put("play", Updater.viaPlay(ctx));
                o.put("playTools", new JSONArray(java.util.Arrays.asList(PlayFeatures.TOOLS)));
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface public String lastManifest() {
            return Updater.prefs(ctx).getString("manifest", "null");
        }

        @JavascriptInterface public void checkUpdates() { a.h.post(() -> a.checkUpdates(true)); }

        /** De pagina beslist zelf of alle bewerkingen bewaard zijn, en blokkeert invoer tot de nieuwe pagina er is. */
        @JavascriptInterface public void reloadUi() {
            a.h.post(() -> {
                if (a.web == null) return;
                a.web.evaluateJavascript("!!(window.prepareUiReload&&window.prepareUiReload())", ready -> {
                    if (!"true".equals(ready) || a.web == null) return;
                    a.coverOn(); a.uiReady = false; a.loadUi();
                });
            });
        }

        @JavascriptInterface public void installUpdate() { UpdateController.install(ctx); }

        @JavascriptInterface public String permissions() { return a.permissionState(); }
        @JavascriptInterface public void requestPermissions() { a.h.post(a::requestRedialPermissions); }
        @JavascriptInterface public void openAppSettings() { a.h.post(a::openAppSettings); }
        @JavascriptInterface public void pickContact() { a.h.post(a::pickContact); }

        /** Material You: de kleuren die Android uit je achtergrond haalt (Android 12+), anders leeg. */
        @JavascriptInterface public String themeColors() {
            if (Build.VERSION.SDK_INT < 31) return "";
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                String[] pals = {"accent1", "accent2", "accent3", "neutral1", "neutral2"};
                int[] tones = {0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000};
                android.content.res.Resources r = ctx.getResources();
                for (String p : pals) {
                    org.json.JSONObject t = new org.json.JSONObject();
                    for (int tone : tones) {
                        int id = r.getIdentifier("system_" + p + "_" + tone, "color", "android");
                        if (id == 0) return "";
                        t.put(String.valueOf(tone), String.format(java.util.Locale.US, "#%06X", 0xFFFFFF & ctx.getColor(id)));
                    }
                    o.put(p, t);
                }
                return o.toString();
            } catch (Exception e) { return ""; }
        }

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
            a.h.post(() -> Toast.makeText(ctx, Taal.tx(ctx, m), Toast.LENGTH_SHORT).show());
        }

        /** Zoals redialStart, maar met willekeurige wachttijd tussen randomMin en randomMax seconden (0 = vast). */
        @JavascriptInterface public String redialStart2(String number, String name, int attempts, int interval,
                                                        int randomMin, int randomMax,
                                                        boolean stopWhenAnswered, boolean speaker) {
            if (!PlayPolicy.allows(ctx, "redial")) return PlayPolicy.unavailable(ctx);
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
        @JavascriptInterface public void waPickFolder() { if (!a.isSolo() || "backup".equals(a.solo)) a.h.post(a::pickBackupFolder); } // de backup-tool beheert zijn eigen map

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
            if (Tracks.recoverInterrupted(ctx)) return "Er staat nog een route klaar om op te slaan";
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
                p.edit().remove("justStopped").remove("interrupted").apply();
                return "";
            } catch (Exception e) { return "Opslaan mislukt: " + e.getMessage(); }
        }

        @JavascriptInterface public void trackDiscard() {
            Tracks.liveFile(ctx).delete();
            Tracks.prefs(ctx).edit().remove("justStopped").remove("interrupted").apply();
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
                    Toast.makeText(ctx, Taal.tx(ctx, "Geen app gevonden om dit te openen"), Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface public String trackJustStopped() {
            android.content.SharedPreferences p = Tracks.prefs(ctx);
            Tracks.recoverInterrupted(ctx);
            if (!p.getBoolean("justStopped", false)) return "";
            try {
                JSONObject o = new JSONObject();
                o.put("points", p.getInt("lastPoints", 0)).put("interrupted", p.getBoolean("interrupted", false));
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
                    Toast.makeText(ctx, Taal.tx(ctx, "Delen lukt niet: " + e.getMessage()), Toast.LENGTH_LONG).show();
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
                o.put("destName", WaBackup.destName(ctx));
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
                    err = e instanceof SecurityException ? WaBackup.NO_ACCESS : e.getMessage() != null ? e.getMessage() : "Exporteren lukt niet. Controleer de vrije ruimte en kies je backup-map opnieuw.";
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

        // ----- Meldingsgeschiedenis -----
        @JavascriptInterface public String historyList(String query) {
            try { return NotificationHistory.list(ctx, query); } catch (Exception e) { return errJson(e); }
        }
        // ----- meldingen van deze app -----
        @JavascriptInterface public String notifState() { return NotifCenter.state(ctx); }
        /** Android-instelling van één soort melding (of alle meldingen van de app bij een lege id). */
        @JavascriptInterface public void notifOpen(String id) {
            a.h.post(() -> { try { a.startActivity(NotifCenter.settingsIntent(ctx, id)); } catch (Exception e) { a.openAppSettings(); } });
        }
        /** Meldingen aanzetten: eerst de vraag van Android, kan dat niet (meer), dan de instelling. */
        @JavascriptInterface public void notifAsk() {
            a.h.post(() -> {
                if (!NotifCenter.permissionOk(ctx) && Build.VERSION.SDK_INT >= 33 && !ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("notifAsked", false)) {
                    ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("notifAsked", true).apply();
                    a.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
                } else {
                    try { a.startActivity(NotifCenter.settingsIntent(ctx, "")); } catch (Exception e) { a.openAppSettings(); }
                }
            });
        }

        // ----- onderhoud: sinds gisteravond, backup-controle -----
        @JavascriptInterface public String overnight() { return Care.overnight(ctx); }
        @JavascriptInterface public void overnightSeen() {
            Care.prefs(ctx).edit().putLong("seen", System.currentTimeMillis()).putLong("seenBattery", Care.batteryProblem(ctx) != null ? System.currentTimeMillis() : 0).apply();
        }
        @JavascriptInterface public String backupCheckLast() { return Care.prefs(ctx).getString("check", "{}"); }
        @JavascriptInterface public void backupCheckRun() {
            new Thread(() -> a.js("onBackupCheck", Care.backupCheck(ctx).toString()), "backup-check").start();
        }
        @JavascriptInterface public String historyStamp() { return NotificationHistory.stamp(ctx); }
        @JavascriptInterface public String historyListBy(String query, String pkg, int limit) {
            try { return NotificationHistory.list(ctx, query, pkg, limit); } catch (Exception e) { return errJson(e); }
        }
        @JavascriptInterface public String historyApps() { return NotificationHistory.apps(ctx); }
        @JavascriptInterface public void historySetEnabled(boolean enabled) {
            NotificationHistory.prefs(ctx).edit().putBoolean("enabled", enabled).apply();
            if (enabled) HistoryListener.importNow(); // wat nog in het meldingenpaneel staat meteen bewaren
            if (enabled && NotificationHistory.allowed(ctx))
                android.service.notification.NotificationListenerService.requestRebind(new android.content.ComponentName(ctx, HistoryListener.class));
        }
        @JavascriptInterface public void historyRequestPermission() {
            a.h.post(() -> {
                try { a.startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
                catch (Exception e) { a.js("toast", JSONObject.quote("Meldingentoegang openen lukt niet")); }
            });
        }
        @JavascriptInterface public String historyClear() {
            try { NotificationHistory.clear(ctx); return ""; } catch (Exception e) { return "Geschiedenis wissen lukt niet"; }
        }
        @JavascriptInterface public String historyExport() {
            if (WaBackup.destUri(ctx) == null) return "Kies eerst een backup-map";
            if (NotificationHistory.exporting.get()) return "Er loopt al een meldingen-export";
            new Thread(() -> {
                String result;
                try { int n = NotificationHistory.export(ctx); result = n < 0 ? "Nog geen meldingen om te exporteren" : "✓ " + n + " meldingen geëxporteerd naar Meldingen backup"; }
                catch (Exception e) { result = e.getMessage() == null ? "Meldingen exporteren mislukt" : e.getMessage(); }
                a.js("onHistoryExport", JSONObject.quote(result));
            }, "history-export").start();
            return "";
        }

        // ----- Notities -----

        @JavascriptInterface public String notesLoad() { return Notes.load(ctx); }

        @JavascriptInterface public String notesSave(String json) {
            return Notes.save(ctx, json);
        }
        /** Opslaan alleen als niemand anders (de widget) intussen iets veranderde: {ok, ver} of {conflict} of {err}. */
        @JavascriptInterface public String notesSaveIf(String json, double expected) {
            try {
                // Alleen een gewoon geheel versienummer: NaN (zou de controle overslaan) of iets buiten bereik weigeren
                if (!(expected >= 0 && expected <= 9_007_199_254_740_992.0) || expected != Math.floor(expected)) return "{\"err\":\"Opslaan lukt niet\"}";
                Notes.SaveResult r = Notes.saveIf(ctx, json, (long) expected);
                if (r.conflict) return new JSONObject().put("conflict", true).toString();
                if (!r.error.isEmpty()) return new JSONObject().put("err", r.error).toString();
                return new JSONObject().put("ok", true).put("ver", r.version).toString();
            } catch (Exception e) { return "{\"err\":\"Opslaan lukt niet\"}"; }
        }
        @JavascriptInterface public long notesVersion() { return Notes.version(ctx); }

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
                    Toast.makeText(a, Taal.tx(a, "Contacten-app openen lukt niet"), Toast.LENGTH_LONG).show();
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
                    Toast.makeText(a, Taal.tx(a, "Contacten-app openen lukt niet"), Toast.LENGTH_LONG).show();
                }
            });
        }

        // ----- Handmatige microfoonopname -----
        @JavascriptInterface public String recorderState() { return CallRecordings.state(ctx); }
        @JavascriptInterface public void recorderCallAccessSettings() {
            a.h.post(() -> {
                try { a.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
                catch (Exception e) { a.openAppSettings(); }
            });
        }
        @JavascriptInterface public void recorderRequestPermissions() {
            a.h.post(() -> {
                java.util.ArrayList<String> permissions = new java.util.ArrayList<>();
                permissions.add(Manifest.permission.RECORD_AUDIO);
                permissions.add(Manifest.permission.READ_PHONE_STATE);
                if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS);
                a.requestPermissions(permissions.toArray(new String[0]), REQ_MIC);
            });
        }
        @JavascriptInterface public String recorderSetAuto(boolean on) { return AutoCallRecording.setEnabled(ctx, on); }
        @JavascriptInterface public void recorderStart() {
            if (!PlayPolicy.allows(ctx, "recorder")) { a.js("onRecorderMessage", JSONObject.quote(PlayPolicy.unavailable(ctx))); return; }
            a.h.post(() -> {
                if (!a.hasWindowFocus()) { a.js("onRecorderMessage", JSONObject.quote("Start de opname vanuit het geopende opnamescherm")); return; }
                if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
                        (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) {
                    a.js("onRecorderMessage", JSONObject.quote("Geef eerst toestemming voor de microfoon en opnamemelding")); return;
                }
                if ("recording".equals(Music.state)) { a.js("onRecorderMessage", JSONObject.quote("Stop eerst muziekherkenning via de microfoon")); return; }
                if ("luistert".equals(SleepService.phase)) { a.js("onRecorderMessage", JSONObject.quote("Stop eerst Slaappraat")); return; }
                if (!CallRecorderService.reserve()) { a.js("onRecorderMessage", JSONObject.quote("Er loopt al een opname")); return; }
                recorderStopPlayback();
                try {
                    Intent intent = new Intent(a, CallRecorderService.class);
                    if (Build.VERSION.SDK_INT >= 26) a.startForegroundService(intent); else a.startService(intent);
                } catch (Exception e) {
                    CallRecorderService.busy = false;
                    a.js("onRecorderMessage", JSONObject.quote("Android kon de opnameservice niet starten. Open de app en probeer opnieuw."));
                }
            });
        }
        @JavascriptInterface public void recorderStop() {
            a.h.post(() -> { if (CallRecorderService.busy) {
                try { a.startService(new Intent(a, CallRecorderService.class).setAction("stop")); }
                catch (Exception e) { a.js("onRecorderMessage", JSONObject.quote("Stop via de opnamemelding of open de app opnieuw")); }
            } });
        }
        private static android.media.MediaPlayer recordingPlayer;
        @JavascriptInterface public String recorderPlay(String id) {
            synchronized (Bridge.class) {
                recorderStopPlayback();
                if (CallRecorderService.busy) return "Stop eerst de opname";
                try {
                    File file = CallRecordings.resolve(ctx, id);
                    recordingPlayer = new android.media.MediaPlayer();
                    recordingPlayer.setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build());
                    recordingPlayer.setDataSource(file.getPath()); recordingPlayer.prepare();
                    // Even voorrang op ander geluid (de radio pauzeert en gaat daarna verder); klaar = alles weer vrijgeven
                    android.media.AudioManager am = (android.media.AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                    if (am != null && Build.VERSION.SDK_INT >= 26) {
                        // Gesprek of andere app neemt het geluid over: stoppen
                        recFocus = new android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                                .setOnAudioFocusChangeListener(ch -> { if (ch == android.media.AudioManager.AUDIOFOCUS_LOSS || ch == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stopRecordingPlayback(ctx); }).build();
                        am.requestAudioFocus(recFocus);
                    }
                    recordingPlayer.setOnCompletionListener(mp -> recorderStopPlayback());
                    if (recNoisy == null) {
                        recNoisy = new NoisyStop();
                        android.content.IntentFilter f = new android.content.IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY);
                        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(recNoisy, f, Context.RECEIVER_NOT_EXPORTED); else ctx.registerReceiver(recNoisy, f);
                    }
                    recordingPlayer.start(); return "";
                } catch (Exception e) { recorderStopPlayback(); return "Afspelen lukt niet"; }
            }
        }
        private static android.media.AudioFocusRequest recFocus;
        private static NoisyStop recNoisy;
        /** Koptelefoon eruit: stoppen in plaats van ineens over de luidspreker. */
        static final class NoisyStop extends android.content.BroadcastReceiver {
            @Override public void onReceive(Context c, Intent i) { stopRecordingPlayback(c); }
        }
        @JavascriptInterface public void recorderStopPlayback() { stopRecordingPlayback(ctx); }

        static void stopRecordingPlayback(Context c) {
            synchronized (Bridge.class) {
                if (recordingPlayer != null) { try { recordingPlayer.release(); } catch (Exception ignored) { } recordingPlayer = null; }
                android.media.AudioManager am = (android.media.AudioManager) c.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
                if (recFocus != null && am != null && Build.VERSION.SDK_INT >= 26) am.abandonAudioFocusRequest(recFocus);
                recFocus = null;
                if (recNoisy != null) { try { c.getApplicationContext().unregisterReceiver(recNoisy); } catch (Exception ignored) { } recNoisy = null; }
            }
        }
        @JavascriptInterface public void recorderShare(String id) {
            a.h.post(() -> { try {
                CallRecordings.resolve(ctx, id);
                Uri uri = new Uri.Builder().scheme("content").authority("nl.rene.tools.recordings").appendPath(id).build();
                Intent share = new Intent(Intent.ACTION_SEND).setType("audio/mp4").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                share.setClipData(android.content.ClipData.newUri(ctx.getContentResolver(), "Gespreksopname", uri));
                a.startActivity(Intent.createChooser(share, "Opname delen"));
            } catch (Exception e) { a.js("onRecorderMessage", JSONObject.quote("Delen lukt niet")); } });
        }
        @JavascriptInterface public String recorderDelete(String id) {
            try { recorderStopPlayback(); if (!CallRecordings.resolve(ctx, id).delete()) return "Opname verwijderen lukt niet"; return ""; }
            catch (Exception e) { return "Opname niet gevonden"; }
        }
        @JavascriptInterface public String recorderExport(String id) {
            try { CallRecordings.resolve(ctx, id); } catch (Exception e) { return "Opname niet gevonden"; }
            if (WaBackup.destUri(ctx) == null) return "Kies eerst een backup-map";
            new Thread(() -> {
                String result;
                try { CallRecordings.export(ctx, id); result = "✓ Opname geëxporteerd naar Gespreksopnames"; }
                catch (Exception e) { result = "Opname exporteren mislukt; controleer de backup-map"; }
                a.js("onRecorderMessage", JSONObject.quote(result));
            }, "recording-export").start();
            return "";
        }

        // ----- Gesprekken uitschrijven -----

        @JavascriptInterface public String txInfo() {
            try {
                JSONObject o = new JSONObject();
                o.put("supported", Transcribe.supported());
                o.put("files", hasFilesAccess(ctx) || CallRecordings.hasRecordings(ctx));
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
                catch (ActivityNotFoundException e) { Toast.makeText(a, Taal.tx(a, "Mappenkiezer niet beschikbaar"), Toast.LENGTH_SHORT).show(); }
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
            Transcribe.prefs(ctx).edit().putString("status", "{\"running\":true,\"phase\":\"download\",\"pct\":0}").putString("dlModel", id).apply();
            TranscribeService.download(ctx, id);
            return "";
        }

        @JavascriptInterface public void txDeleteModel(String id) {
            Transcribe.Model m = Transcribe.model(id);
            if (m != null && !TranscribeService.busy) { Transcribe.modelFile(ctx, m).delete(); ModelUse.forget(ctx, "spraak:" + m.id); }
        }

        @JavascriptInterface public String txList() {
            if (!hasFilesAccess(ctx) && !CallRecordings.hasRecordings(ctx)) return "{\"error\":\"Geef eerst toegang tot bestanden\"}";
            try { return Transcribe.listJson(ctx); } catch (Exception e) { return errJson(e); }
        }

        /** paths: JSON-array met paden van opnames. */
        @JavascriptInterface public String txStart(String paths) {
            if (!Transcribe.supported()) return "Deze telefoon wordt niet ondersteund (64-bit ARM nodig)";
            if (Transcribe.activeModel(ctx) == null) return "Download eerst een spraakmodel";
            try {
                JSONArray a2 = new JSONArray(paths);
                java.util.ArrayList<String> l = new java.util.ArrayList<>();
                for (int i = 0; i < a2.length(); i++) if (Transcribe.allowedRecording(ctx, a2.getString(i))) l.add(a2.getString(i));
                if (l.isEmpty()) return "Geen opnames gekozen";
                Transcribe.prefs(ctx).edit().putString("status", "{\"running\":true,\"phase\":\"decode\",\"pct\":0}").apply();
                TranscribeService.run(ctx, l);
                return "";
            } catch (Exception e) { return e.getMessage() != null ? e.getMessage() : "Starten mislukt"; }
        }

        @JavascriptInterface public void txCancel() { Transcribe.stopCalls(); }

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
            if (path == null || !Transcribe.allowedRecording(ctx, path)) return "De opname is niet meer op de telefoon";
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
                p.put(perm("phone", Taal.T("Bellen"), Taal.T("Auto redial"), has(Manifest.permission.CALL_PHONE)));
                p.put(perm("calllog", Taal.T("Oproepgeschiedenis"), Taal.T("Auto redial, Oproepen, Gesprekken"), has(Manifest.permission.READ_CALL_LOG)));
                p.put(perm("contacts", Taal.T("Contacten"), Taal.T("Namen, Contacten-tool"), has(Manifest.permission.READ_CONTACTS) && has(Manifest.permission.WRITE_CONTACTS)));
                p.put(perm("sms", Taal.T("Sms"), Taal.T("SMS-backup"), has(Manifest.permission.READ_SMS)));
                p.put(perm("history", Taal.T("Meldingentoegang"), Taal.T("Meldingsgeschiedenis"), NotificationHistory.allowed(ctx)));
                p.put(perm("location", Taal.T("Locatie"), Taal.T("Mijn routes"), has(Manifest.permission.ACCESS_FINE_LOCATION)));
                p.put(perm("mic", Taal.T("Microfoon"), Taal.T("Muziek herkennen"), has(Manifest.permission.RECORD_AUDIO)));
                p.put(perm("camera", Taal.T("Camera"), Taal.T("QR-code scannen (VPN), foto bij ontgrendelen (Gebruik)"), has(Manifest.permission.CAMERA)));
                p.put(perm("nearby", Taal.T("Apparaten in de buurt"), Taal.T("Apparaten (Bluetooth en wifi)"), ApparatenScan.btPerm(ctx)));
                if (Build.VERSION.SDK_INT >= 33) p.put(perm("notif", Taal.T("Meldingen"), Taal.T("Voortgang en bediening"), has(Manifest.permission.POST_NOTIFICATIONS)));
                p.put(perm("files", Taal.T("Alle bestanden"), Taal.T("WhatsApp backup, Gesprekken"), hasFilesAccess(ctx)));
                if (Build.VERSION.SDK_INT >= 26 && !Updater.viaPlay(ctx)) p.put(perm("install", Taal.T("Updates installeren"), Taal.T("Nieuwe app-versies"), ctx.getPackageManager().canRequestPackageInstalls()));
                if (Updater.viaPlay(ctx)) {
                    JSONArray filtered = new JSONArray();
                    for (int i = 0; i < p.length(); i++) if ("notif".equals(p.getJSONObject(i).optString("id"))) filtered.put(p.getJSONObject(i));
                    p = filtered;
                }
                o.put("perms", p);
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }

        private JSONObject perm(String id, String name, String used, boolean ok) throws Exception {
            return new JSONObject().put("id", id).put("name", name).put("used", used).put("ok", ok);
        }

        @JavascriptInterface public void permRequest(String id) {
            if (Updater.viaPlay(ctx) && !"notif".equals(id)) return;
            a.h.post(() -> {
                String[] p = null;
                switch (id == null ? "" : id) {
                    case "phone": p = new String[]{Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE}; break;
                    case "calllog": p = new String[]{Manifest.permission.READ_CALL_LOG}; break;
                    case "contacts": p = new String[]{Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS}; break;
                    case "contactsread": p = new String[]{Manifest.permission.READ_CONTACTS}; break; // alleen lezen (lokale AI)
                    case "history": historyRequestPermission(); return;
                    case "sms": p = new String[]{Manifest.permission.READ_SMS}; break;
                    case "location": p = new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}; break;
                    case "mic": p = new String[]{Manifest.permission.RECORD_AUDIO}; break;
                    case "camera": p = new String[]{Manifest.permission.CAMERA}; break; // Gebruik: foto bij ontgrendelen; QR-code scannen
                    case "notif": if (Build.VERSION.SDK_INT >= 33) p = new String[]{Manifest.permission.POST_NOTIFICATIONS}; break;
                    case "calendar": p = new String[]{Manifest.permission.READ_CALENDAR}; break; // alleen lezen (Check-in)
                    case "nearby": p = ApparatenBridge.nearby(); break; // Apparaten: Bluetooth en wifi in de buurt
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
                        .putExtra(Intent.EXTRA_SUBJECT, "Foutrapport Tools " + Version.NAME)
                        .putExtra(Intent.EXTRA_TEXT, App.report(ctx));
                try { a.startActivity(Intent.createChooser(i, "Foutrapport delen")); }
                catch (Exception e) { Toast.makeText(a, Taal.tx(a, "Delen lukt niet"), Toast.LENGTH_SHORT).show(); }
            });
        }

        /** Foutrapport naar het klembord (om bijv. in een chat te plakken). */
        @JavascriptInterface public void crashCopy() {
            a.h.post(() -> {
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Foutrapport Tools", App.report(ctx)));
                    if (Build.VERSION.SDK_INT < 33) Toast.makeText(a, Taal.tx(a, "Foutrapport gekopieerd"), Toast.LENGTH_SHORT).show(); // nieuwere Android meldt het zelf
                } catch (Exception e) { Toast.makeText(a, Taal.tx(a, "Kopiëren lukt niet"), Toast.LENGTH_SHORT).show(); }
            });
        }

        /** Eerste keer na installeren (niet na een update): dan geen "Wat is er nieuw". */
        @JavascriptInterface public boolean freshInstall() {
            try {
                android.content.pm.PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
                return pi.firstInstallTime == pi.lastUpdateTime;
            } catch (Exception e) { return false; }
        }

        // ----- Statistieken voor verbeteringen (Statistiek): tellingen en fouten, naar een privé GitHub-repository -----

        @JavascriptInterface public String statState() { return Statistiek.state(ctx).toString(); }
        @JavascriptInterface public void statOn(boolean on) { if (!a.isSolo()) Statistiek.setOn(ctx, on); } // losse app: zonder app-slot
        /** "" bij succes, anders de reden. Een lege sleutel laat de bewaarde staan; beide leeg = bestemming weg. */
        @JavascriptInterface public String statDest(String repo, String token) { return a.isSolo() ? "Kan alleen vanuit de gewone app" : Statistiek.setDest(ctx, repo, token); }
        /** Precies wat er verstuurd zou worden (om te bekijken). */
        @JavascriptInterface public String statPreview() {
            try { return Statistiek.preview(ctx); } catch (Exception e) { return ""; }
        }
        /** Nu versturen; de uitkomst komt via onStatSent("" of de reden). */
        @JavascriptInterface public void statSend() {
            if (a.isSolo()) return;
            new Thread(() -> {
                String r;
                try { r = Statistiek.send(ctx); } catch (Throwable t) { r = "Versturen lukte niet"; }
                a.js("onStatSent", JSONObject.quote(r));
            }, "statistiek").start();
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
        /** Losse app: welke tool ("" in de gewone app). */
        @JavascriptInterface public String soloTool() { return a.solo == null ? "" : a.solo; }
        /** Welke tools als losse app mogen. */
        @JavascriptInterface public String soloList() { return new org.json.JSONArray(Solo.list(ctx)).toString(); }
        @JavascriptInterface public void soloSet(String tool, boolean on) { if (!a.isSolo()) Solo.set(ctx, tool, on); }
        @JavascriptInterface public String soloPin(String tool, String label, String color, String glyph) {
            if (a.isSolo()) return "Kan alleen vanuit de gewone app";
            return Solo.pin(ctx, tool, label, color, glyph);
        }

        @JavascriptInterface public String lockState() {
            if (a.isSolo()) return "{\"on\":false,\"locked\":false}"; // losse app: zonder app-slot
            try {
                return new JSONObject().put("on", Lock.enabled(ctx)).put("locked", Lock.locked(ctx))
                        .put("timeout", Lock.timeout(ctx)).put("secure", Lock.deviceSecure(ctx))
                        .put("code", Lock.hasCode(ctx)).put("decoy", Lock.decoy(ctx)).put("bio", Lock.bioWithCode(ctx) && Lock.deviceSecure(ctx))
                        .put("codeBio", Lock.bioWithCode(ctx)).put("wait", Lock.waitSeconds(ctx)).toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface public void lockUnlock() {
            if (a.isSolo()) return; // losse app: het slot van de app niet aanraken
            if (Lock.hasCode(ctx)) { lockBio(); return; } // met app-code: alleen vingerafdruk/gezicht, nooit de pincode van de telefoon
            a.h.post(() -> Lock.prompt(a, "Tools ontgrendelen", (ok, msg) -> {
                if (ok) { Lock.unlocked = true; a.js("onUnlocked", ""); }
                else a.js("onLockFail", JSONObject.quote(msg == null ? "" : msg));
            }));
        }

        /** Vingerafdruk/gezicht naast de app-code (als dat aan staat). */
        @JavascriptInterface public void lockBio() {
            if (a.isSolo()) return; // losse app: het slot van de app niet aanraken
            if (!Lock.hasCode(ctx) || !Lock.bioWithCode(ctx) || !Lock.deviceSecure(ctx)) return;
            a.h.post(() -> Lock.promptBio(a, (ok, msg) -> {
                if (ok) { Lock.unlocked = true; a.js("onUnlocked", ""); }
                else if (msg != null) a.js("onLockFail", JSONObject.quote(msg));
            }));
        }

        /**
         * App-code proberen. Geeft "" (open), "neutral" (de neutrale versie gaat open) of een melding. Met de neutrale
         * versie aan gaat elke verkeerde code (en tijdens een wachttijd ook de juiste) zonder melding naar de neutrale versie.
         */
        @JavascriptInterface public void lockTryCode(String code) {
            if (a.isSolo()) return;
            final String cd = code == null ? "" : code;
            new Thread(() -> { // het rekenwerk (PBKDF2) duurt even: niet de pagina laten wachten
                String r;
                if (!Lock.hasCode(ctx)) r = "Er is geen app-code ingesteld";
                else {
                    int t = Lock.tryCode(ctx, cd);
                    if (t == Lock.OK) { Lock.unlocked = true; r = ""; a.js("onUnlocked", ""); }
                    else if (Lock.decoy(ctx)) {
                        r = "neutral";
                        a.h.post(() -> {
                            Lock.unlocked = false;
                            try { a.startActivity(new Intent(a, NeutralActivity.class)); } catch (Exception ignored) { }
                            a.finish();
                            a.overridePendingTransition(0, 0);
                        });
                    } else {
                        long w = Lock.waitSeconds(ctx);
                        r = w > 0 ? "Te vaak een verkeerde code. Probeer het over " + (w >= 120 ? (w + 59) / 60 + " minuten" : w + " seconden") + " opnieuw." : "Verkeerde code";
                    }
                }
                a.js("onLockResult", JSONObject.quote(r));
            }, "lock-code").start();
        }

        /**
         * App-code instellen of wijzigen. Eerst bevestigen met de schermvergrendeling van de telefoon (als die er is),
         * anders met de huidige app-code (old). Antwoord via onSettingsChanged.
         */
        @JavascriptInterface public void lockCodeSet(String code, String old) {
            if (!Lock.validCode(code)) { a.js("onSettingsChanged", JSONObject.quote("Gebruik 4 tot 12 cijfers")); return; }
            confirmThen(old, "App-code instellen", () -> {
                try { Lock.setCode(ctx, code); return ""; } catch (Exception e) { return e.getMessage() == null ? "Instellen lukt niet" : e.getMessage(); }
            });
        }

        @JavascriptInterface public void lockCodeClear(String old) {
            confirmThen(old, "App-code verwijderen", () -> { Lock.clearCode(ctx); return ""; });
        }

        @JavascriptInterface public void lockDecoySet(boolean on) {
            if (!Lock.hasCode(ctx)) return;
            Lock.prefs(ctx).edit().putBoolean("decoy", on).apply();
        }

        @JavascriptInterface public void lockCodeBio(boolean on) {
            String r = Lock.enableBio(ctx, on);
            a.js("onSettingsChanged", JSONObject.quote(r.isEmpty() ? (on ? "Vingerafdruk aan (komt er een vingerafdruk bij, dan eerst één keer je app-code)" : "Vingerafdruk uit") : r));
        }

        interface Change { String run(); }

        /** Na bevestiging (schermvergrendeling, of de huidige app-code als de telefoon geen vergrendeling heeft) iets wijzigen. */
        private void confirmThen(String old, String title, Change ch) {
            if (Lock.deviceSecure(ctx)) {
                a.h.post(() -> Lock.prompt(a, title, (ok, msg) -> {
                    if (!ok) { a.js("onSettingsChanged", JSONObject.quote(msg == null ? "Niet bevestigd" : msg)); return; }
                    Lock.unlocked = true;
                    new Thread(() -> a.js("onSettingsChanged", JSONObject.quote(ch.run())), "lock-set").start(); // rekenwerk niet op de hoofdthread
                }));
                return;
            }
            new Thread(() -> {
                String r;
                if (Lock.hasCode(ctx) && Lock.tryCode(ctx, old == null ? "" : old) != Lock.OK) r = "De huidige app-code klopt niet (of even wachten na te vaak fout)";
                else r = ch.run();
                a.js("onSettingsChanged", JSONObject.quote(r));
            }, "lock-set").start();
        }

        /** App-slot aan/uit op een telefoon zonder schermvergrendeling: bevestigen met de app-code. */
        @JavascriptInterface public void lockSetCode(boolean on, String code) {
            if (Lock.deviceSecure(ctx)) { lockSet(on); return; }
            new Thread(() -> { // rekenwerk niet op de brug
                if (!Lock.hasCode(ctx) || Lock.tryCode(ctx, code == null ? "" : code) != Lock.OK) { a.js("onSettingsChanged", JSONObject.quote("De app-code klopt niet (of even wachten na te vaak fout)")); return; }
                Lock.prefs(ctx).edit().putBoolean("on", on).apply();
                Lock.unlocked = true;
                a.h.post(a::applySecure);
                a.js("onSettingsChanged", JSONObject.quote(""));
            }, "lock-set").start();
        }

        /** App-slot aan- of uitzetten; vraagt eerst de schermvergrendeling ter bevestiging. */
        @JavascriptInterface public void lockSet(boolean on) {
            a.h.post(() -> Lock.prompt(a, on ? "App-slot aanzetten" : "App-slot uitzetten", (ok, msg) -> {
                if (ok) {
                    Lock.prefs(ctx).edit().putBoolean("on", on).apply();
                    Lock.unlocked = true;
                    a.applySecure();
                    // Widgets tonen met app-slot minder: meteen bijwerken
                    HomeWidgets.refresh(ctx, HomeWidgets.Overview.class);
                    HomeWidgets.refresh(ctx, HomeWidgets.Agenda.class);
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

        // ----- Extra ontgrendelen per tool -----

        /** Welke tools extra ontgrendelen vragen, en of de telefoon een schermvergrendeling heeft (zonder werkt het niet). */
        @JavascriptInterface public String toolSlotState() {
            try { return new JSONObject().put("aan", new JSONArray(ToolSlot.aan(ctx))).put("secure", Lock.deviceSecure(ctx)).toString(); }
            catch (Exception e) { return "{\"aan\":[],\"secure\":false}"; }
        }

        /** Aanzetten kan meteen (met schermvergrendeling); uitzetten pas na bevestigen. Antwoord via onToolSlot. */
        @JavascriptInterface public void toolSlotSet(String tool, boolean on, String naam) {
            final String id = tool == null ? "" : tool;
            if (!Solo.known(id)) { toolSlotAntwoord(id, on ? "aan" : "uit", false, "Onbekende tool"); return; }
            if (on) {
                if (!Lock.deviceSecure(ctx)) { toolSlotAntwoord(id, "aan", false, "Stel eerst een schermvergrendeling in op je telefoon (pincode, patroon of vingerafdruk)"); return; }
                ToolSlot.zet(ctx, id, true);
                toolSlotAntwoord(id, "aan", true, null);
                return;
            }
            if (!ToolSlot.aan(ctx).contains(id)) { toolSlotAntwoord(id, "uit", true, null); return; }
            if (!Lock.deviceSecure(ctx)) { ToolSlot.zet(ctx, id, false); toolSlotAntwoord(id, "uit", true, null); return; } // zonder schermvergrendeling werkt het slot toch niet
            final String titel = Taal.t(ctx, "{naam}: extra ontgrendelen uitzetten", "naam", ToolSlot.naam(naam));
            a.h.post(() -> Lock.prompt(a, titel, (ok, msg) -> {
                if (ok) ToolSlot.zet(ctx, id, false);
                toolSlotAntwoord(id, "uit", ok, ok ? null : msg == null ? "Niet bevestigd" : msg);
            }));
        }

        /** Een tool ontgrendelen met de schermvergrendeling van de telefoon. Antwoord via onToolSlot. */
        @JavascriptInterface public void toolSlotUnlock(String tool, String naam) {
            final String id = tool == null ? "" : tool;
            if (!Solo.known(id)) return;
            final String titel = Taal.t(ctx, "{naam} ontgrendelen", "naam", ToolSlot.naam(naam));
            a.h.post(() -> Lock.prompt(a, titel, (ok, msg) -> toolSlotAntwoord(id, "open", ok, ok ? null : msg == null ? "Niet ontgrendeld" : msg)));
        }

        /** Een tool met extra ontgrendelen in beeld: geen voorbeeld in "recente apps" (onder Android 13 ook geen schermafdrukken). */
        @JavascriptInterface public void toolSlotScherm(boolean on) {
            a.h.post(() -> { if (a.toolScreen != on) { a.toolScreen = on; a.applySecure(); } });
        }

        private void toolSlotAntwoord(String tool, String soort, boolean ok, String msg) {
            try {
                JSONObject o = new JSONObject().put("tool", tool).put("soort", soort).put("ok", ok);
                if (msg != null) o.put("msg", msg);
                a.js("onToolSlot", o.toString());
            } catch (Exception ignored) { }
        }

        // ----- Alles back-uppen en terugzetten -----

        /** Overal zoeken; uitkomst via onSearchAll(json met id). Geeft het id terug. */
        @JavascriptInterface public int searchAll(String q) {
            final int id = Search.latest.incrementAndGet();
            final String query = q == null ? "" : q;
            if (Updater.viaPlay(ctx)) return id;
            Search.pool.execute(() -> Search.run(ctx, query, id, r -> a.js("onSearchAll", r.toString())));
            return id;
        }

        @JavascriptInterface public String backupState(boolean full) { return AllBackup.stateJson(ctx, full); }

        // ----- Versleutelen, opruimen, ruimtegebruik -----
        /** Wachtwoord instellen (sleutel afleiden duurt even); uitkomst via onSecure("" of foutmelding). */
        @JavascriptInterface public void backupSetPassword(String pw) {
            new Thread(() -> {
                String r = "";
                try { Secure.setPassword(ctx, pw); AllBackupJob.ensureScheduled(ctx); } // (elke nacht: standaard aan met versleutelen)
                catch (Exception e) { r = e.getMessage() != null ? e.getMessage() : "Instellen mislukt"; App.log(ctx, "SECURE", "wachtwoord: " + e); }
                a.js("onSecure", JSONObject.quote(r));
            }, "secure").start();
        }

        /** Versleutelen uit: de privé-onderdelen (assistent, Slaappraat, Check-in) gaan dan ook uit; weer aanzetten vraagt eerst. */
        @JavascriptInterface public void backupEncryptOff() {
            Secure.off(ctx);
            try { AllBackupJob.ensureScheduled(ctx); } catch (Exception ignored) { }
            android.content.SharedPreferences.Editor e = AllBackup.prefs(ctx).edit();
            for (String p : AllBackup.PRIVATE) { e.putBoolean("part_" + p, false); AllBackup.setPlain(ctx, p, false); }
            e.apply();
        }

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
                catch (ActivityNotFoundException e) { Toast.makeText(a, Taal.tx(a, "Bestandskiezer niet beschikbaar"), Toast.LENGTH_SHORT).show(); }
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
            if (!java.util.Arrays.asList("contacts", "notes", "launcher", "settings", "tooldata", "transcripts", "music", "ai", "sleep", "checkin").contains(kind)) return;
            new Thread(() -> {
                String r;
                try {
                    if (HerstelLos.BUSY.get()) throw new Exception("Er wordt al teruggezet; even geduld");
                    String text = Secure.readEntry(ctx, entry);
                    // Slaappraat: het geluid komt ook uit het archief (nu, want daarna is de sleutel weg)
                    r = ("sleep".equals(kind) ? Restore.previewSleepArchive(ctx, text) : Restore.previewText(ctx, kind, text)).toString();
                }
                catch (OutOfMemoryError e) { r = "{\"error\":\"Dit onderdeel is te groot voor deze telefoon\"}"; }
                catch (Exception e) { r = errJson(e); }
                catch (Throwable e) { r = "{\"error\":\"Terugzetten mislukt\"}"; }
                finally { Secure.forget(); a.pendingArchive = null; } // sleutel niet langer in het geheugen dan nodig
                a.js("onRestorePreview", r);
            }, "archive-restore").start();
        }

        @JavascriptInterface public void archiveUnpack() {
            new Thread(() -> {
                String r;
                try { r = "ok:" + Secure.unpack(ctx); }
                catch (Exception e) { r = e.getMessage() != null ? e.getMessage() : "Uitpakken mislukt"; App.log(ctx, "SECURE", "uitpakken: " + e); }
                finally { Secure.forget(); a.pendingArchive = null; }
                a.js("onArchiveUnpacked", JSONObject.quote(r));
            }, "archive-unpack").start();
        }

        @JavascriptInterface public void archiveClose() {
            Secure.forget(); a.pendingArchive = null;
            new Thread(() -> { try { AiSleepBackup.cancelStaged(ctx); } catch (Throwable ignored) { } }, "archive-close").start();
        }

        // ----- Gegevens terugzetten na herinstallatie -----

        /** {state, pending, after, hasData}: nieuwe installatie (welkom), of net teruggezet (lijstje). */
        @JavascriptInterface public String herstelState() {
            try {
                String st = Herstel.state(ctx);
                String bl = Herstel.blocked(ctx);
                return new JSONObject().put("state", st).put("pending", Herstel.NEW.equals(st)).put("after", Herstel.showList(ctx))
                        .put("dest", WaBackup.destUri(ctx) != null).put("hasData", AppBackup.hasLocalData(ctx)).put("blocked", bl == null ? "" : bl).toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface public void herstelSkip() {
            if (Herstel.NEW.equals(Herstel.state(ctx))) Herstel.setState(ctx, Herstel.SKIPPED);
            AllBackupJob.ensureScheduled(ctx);
        }

        /** De oude backup-map kiezen (wordt ook weer de backup-map); daarna onHerstelFolder. */
        @JavascriptInterface public void herstelPickFolder() {
            a.h.post(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                try { a.startActivityForResult(i, REQ_HERSTEL_TREE); }
                catch (ActivityNotFoundException e) { Toast.makeText(a, Taal.tx(a, "Mappenkiezer niet beschikbaar"), Toast.LENGTH_SHORT).show(); }
            });
        }

        /** Versleutelde backups in de backup-map; uitkomst via onHerstelScan. */
        @JavascriptInterface public void herstelScan() {
            new Thread(() -> {
                String r;
                try { r = Herstel.scan(ctx).toString(); } catch (Throwable e) { r = "{\"error\":" + JSONObject.quote(WaBackup.folderMsg(ctx, e instanceof Exception ? (Exception) e : new Exception("Map niet te lezen"))) + "}"; }
                a.js("onHerstelScan", r);
            }, "herstel-scan").start();
        }

        // ----- Backup-map controleren en opruimen -----

        @JavascriptInterface public String backupMapState() { return BackupMap.stateJson(ctx); }

        @JavascriptInterface public void backupMapAuto(boolean on) { BackupMap.prefs(ctx).edit().putBoolean("auto", on).apply(); }

        @JavascriptInterface public void backupMapStop() { BackupMap.stop = true; }

        /** Controleren (repair false) of opruimen (true); uitkomst via onBackupMap. */
        @JavascriptInterface public void backupMapRun(boolean repair) {
            new Thread(() -> {
                String r;
                try { r = BackupMap.run(ctx, repair, false).toString(); }
                catch (Throwable e) { r = "{\"error\":" + JSONObject.quote(e instanceof Exception && WaBackup.accessErr((Exception) e) ? WaBackup.folderMsg(ctx, (Exception) e) : AppData.safeMsg(e, "Controleren mislukt")) + "}"; }
                a.js("onBackupMap", r);
            }, "backupmap").start();
        }

        /** Alle gewone onderdelen in de backup-map opzoeken en tellen wat er nieuw is (er verandert niets); uitkomst via onHerstelLosScan. */
        @JavascriptInterface public void herstelLosScan() {
            new Thread(() -> {
                String r;
                try { r = HerstelLos.scan(ctx).toString(); }
                catch (Throwable e) { r = "{\"error\":" + JSONObject.quote(e instanceof Exception && WaBackup.accessErr((Exception) e) ? WaBackup.folderMsg(ctx, (Exception) e) : AppData.safeMsg(e, "Map niet te lezen")) + "}"; }
                a.js("onHerstelLosScan", r);
            }, "herstel-los-scan").start();
        }

        /** Alles uit de gewone onderdelen terugzetten (alleen wat ontbreekt); voortgang via onHerstelLosStep, uitkomst via onHerstelLosDone. */
        @JavascriptInterface public void herstelLosApply() {
            new Thread(() -> {
                String r;
                try {
                    r = HerstelLos.apply(ctx, (kind, done, total) -> a.js("onHerstelLosStep", JSONObject.quote(kind + "," + done + "," + total))).toString();
                }
                catch (Throwable e) { r = "{\"error\":" + JSONObject.quote(AppData.safeMsg(e, "Terugzetten mislukt")) + "}"; }
                a.js("onHerstelLosDone", r);
            }, "herstel-los").start();
        }

        /** Backup openen, helemaal controleren en klaarzetten (er verandert nog niets); voortgang via onHerstelProgress, uitkomst via onHerstelOpened. */
        @JavascriptInterface public void herstelOpen(String name, String pw) {
            new Thread(() -> {
                String r;
                try {
                    final long[] last = {0};
                    r = Herstel.open(ctx, name, pw, (done, total, step) -> {
                        long now = System.currentTimeMillis();
                        if (now - last[0] < 400) return;
                        last[0] = now;
                        a.js("onHerstelProgress", JSONObject.quote(done + "," + total + "," + step));
                    }).toString();
                }
                catch (OutOfMemoryError e) { r = "{\"error\":\"Te weinig geheugen om deze backup te openen\"}"; }
                catch (Throwable e) { r = "{\"error\":" + JSONObject.quote(AppData.safeMsg(e, "Openen mislukt: de backup is niet te lezen")) + "}"; }
                a.js("onHerstelOpened", r);
            }, "herstel-open").start();
        }

        /** Losse onderdelen uit een backup zonder appgegevens (de bestaande manier, alleen toevoegen). */
        @JavascriptInterface public void herstelOldArchive(String name, String pw) {
            new Thread(() -> {
                String r;
                try { Uri u = Herstel.archiveUri(ctx, name); a.pendingArchive = u; r = Secure.open(ctx, u, pw).toString(); }
                catch (Throwable e) { r = "{\"error\":" + JSONObject.quote(AppData.safeMsg(e, "Openen mislukt: de backup is niet te lezen")) + "}"; }
                a.js("onArchive", r);
            }, "herstel-old").start();
        }

        /** Alles terugzetten (vervangt wat er staat) en de app opnieuw starten; bij een fout onHerstelDone(melding). */
        @JavascriptInterface public void herstelApply() {
            new Thread(() -> {
                String r = Herstel.busyReason();
                boolean restart = false;
                if (r != null) { a.js("onHerstelDone", JSONObject.quote(r)); return; }
                r = "";
                if (!AllBackup.tryBegin()) r = "Er loopt een backup; probeer het zo opnieuw";
                else try { String w = AppBackup.apply(ctx); restart = true; if (!w.isEmpty()) a.js("toast", JSONObject.quote(w)); }
                catch (Throwable e) { r = AppData.safeMsg(e, "Terugzetten mislukt; er is niets veranderd"); App.log(ctx, "HERSTEL", "terugzetten: " + r); }
                finally { AllBackup.running.set(false); AllBackup.busy = false; } // (hoort bij de try: alleen na een gelukte tryBegin)
                a.js("onHerstelDone", JSONObject.quote(r));
                if (restart) a.h.postDelayed(() -> Herstel.restart(a), 1200);
            }, "herstel-apply").start();
        }

        @JavascriptInterface public void herstelCancel() {
            new Thread(() -> { try { AppBackup.cancel(ctx); } catch (Throwable ignored) { } }, "herstel-cancel").start();
        }

        /** Wat je na terugzetten opnieuw moet toestaan (en de modellen die weer binnenkomen). */
        @JavascriptInterface public String herstelChecklist() {
            try { return Herstel.checklist(ctx).toString(); } catch (Throwable e) { return "{\"items\":[]}"; }
        }

        @JavascriptInterface public void herstelChecklistDone() { Herstel.checklistDone(ctx); }

        /** Na terugzetten zonder backup-sleutel: bewust zonder versleutelen verder (na de vraag in de interface). */
        @JavascriptInterface public void herstelPlainOk() { Herstel.plainOk(ctx); }

        /** Een knop in het lijstje: de juiste instelling van Android openen. */
        @JavascriptInterface public void herstelFix(String id) {
            if (id == null) return;
            switch (id) {
                case "battery": batterySettings(); return;
                case "exact": alarmExactSettings(); return;
                case "a11y": autoA11ySettings(); return;
                case "home": homeMakeDefault(); return;
                case "overlay":
                    a.h.post(() -> { try { a.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.getPackageName()))); } catch (Exception e) { a.openAppSettings(); } });
                    return;
                default: permRequest(id);
            }
        }

        @JavascriptInterface public void backupSetPart(String part, boolean on) {
            if ("sleepAudio".equals(part)) { AllBackup.prefs(ctx).edit().putBoolean("sleep_audio", on).apply(); return; }
            // Toestemming voor onversleuteld (alleen na "Onversleuteld meenemen?"); uitvinken trekt hem weer in
            if (part != null && part.startsWith("plain:") && AllBackup.priv(part.substring(6))) { AllBackup.setPlain(ctx, part.substring(6), on); return; }
            if (!on && AllBackup.priv(part)) AllBackup.setPlain(ctx, part, false);
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
            if (!java.util.Arrays.asList("contacts", "notes", "launcher", "settings", "tooldata", "transcripts", "music", "route", "ai", "sleep", "checkin").contains(kind)) return;
            a.importKind = kind;
            a.h.post(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                if ("contacts".equals(kind)) i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/x-vcard", "text/vcard", "text/directory", "application/octet-stream", "text/plain"});
                else if ("route".equals(kind)) i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/gpx+xml", "application/xml", "text/xml", "application/octet-stream"});
                else i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "application/octet-stream", "text/plain"});
                try { a.startActivityForResult(i, REQ_IMPORT); }
                catch (ActivityNotFoundException e) { Toast.makeText(a, Taal.tx(a, "Bestandskiezer niet beschikbaar"), Toast.LENGTH_SHORT).show(); }
            });
        }

        /** Overzicht van terugzetten weggetikt: niets meer klaar laten staan (en ontsleuteld geluid uit de cache weg). */
        @JavascriptInterface public void restoreCancel() {
            Restore.clear();
            CheckinStore.cancel();
            new Thread(() -> { try { AiSleepBackup.cancel(ctx); } catch (Throwable ignored) { } }, "restore-cancel").start();
        }

        /** Terugzetten op de achtergrond; uitkomst via window.onRestoreDone("ok:N" of foutmelding). */
        @JavascriptInterface public String restoreApply() {
            if (HerstelLos.BUSY.get()) return "Er wordt al teruggezet; even geduld";
            if ("contacts".equals(Restore.pendingKind) && ctx.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED)
                return "Geef eerst toestemming om contacten te wijzigen";
            new Thread(() -> {
                String r;
                try { r = "ok:" + Restore.apply(ctx); }
                catch (Throwable e) { r = e instanceof OutOfMemoryError ? "Bestand is te groot voor deze telefoon" : e.getMessage() != null ? e.getMessage() : "Terugzetten mislukt"; }
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
                catch (Exception e) { Toast.makeText(a, Taal.tx(a, "Delen lukt niet"), Toast.LENGTH_SHORT).show(); }
            });
        }

        /** Tekst die vanuit een andere app met Delen naar Tools is gestuurd (één keer ophalen). */
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
        /** Vraagt Android om Tools als startscherm te gebruiken (of opent de instelling ervoor). */
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


        // ----- Automatiseringen -----

        @JavascriptInterface public String autoState() { return Auto.stateJson(ctx); }
        @JavascriptInterface public String autoSave(String json) { return Auto.save(ctx, json); }
        @JavascriptInterface public void autoDelete(String id) { Auto.delete(ctx, id); }
        @JavascriptInterface public void autoSetOn(String id, boolean on) { Auto.setOn(ctx, id, on); }
        @JavascriptInterface public String autoBt() { return Auto.btDevices(ctx); }

        @JavascriptInterface public String autoApps() { return Auto.apps(ctx); }

        /** Nu uitvoeren (zonder trigger en voorwaarden), om te testen. */
        @JavascriptInterface public String autoTest(String id) {
            if (!PlayPolicy.allows(ctx, "auto")) return PlayPolicy.unavailable(ctx);
            JSONObject r = Auto.rule(ctx, id);
            if (r == null) return "Sla de automatisering eerst op";
            if (!Auto.notifOk(ctx) && !"auto".equals(r.optString("mode"))) return "Geef eerst toestemming voor meldingen";
            Auto.run(ctx, r, "Test", true);
            return "";
        }

        /** Huidige plek bepalen; antwoord via window.onAutoHere({lat,lng,acc} of {error}). */
        @JavascriptInterface public void autoHere() {
            if (!Auto.locOk(ctx)) { a.js("onAutoHere", "{\"error\":\"perm\"}"); return; }
            Auto.locate(ctx, 15_000, l -> {
                try {
                    a.js("onAutoHere", l == null ? "{\"error\":\"Geen locatie gevonden. Staat de locatie (gps) aan?\"}"
                            : new JSONObject().put("lat", l.getLatitude()).put("lng", l.getLongitude()).put("acc", l.hasAccuracy() ? Math.round(l.getAccuracy()) : 0).toString());
                } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface public String autoRecord(String pkg) {
            if (!a.isSolo()) Lock.internalNav = true;
            return AutoA11y.record(ctx, pkg);
        }

        /** Opgenomen stappen ophalen (en wissen): {pkg, steps} of null. */
        @JavascriptInterface public String autoRecTake() {
            String s = Auto.prefs(ctx).getString("rec", null);
            Auto.prefs(ctx).edit().remove("rec").apply();
            return s == null ? "null" : s;
        }

        @JavascriptInterface public void autoA11ySettings() {
            a.h.post(() -> {
                try { a.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Exception e) { a.openAppSettings(); }
            });
        }

        @JavascriptInterface public void autoPerm(String kind) {
            a.h.post(() -> {
                String[] p = null;
                switch (kind == null ? "" : kind) {
                    case "loc": p = new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}; break;
                    case "bgloc":
                        if (!Auto.fineOk(ctx)) p = new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};
                        else if (Build.VERSION.SDK_INT >= 29) p = new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION};
                        break;
                    case "bt": if (Build.VERSION.SDK_INT >= 31) p = new String[]{Manifest.permission.BLUETOOTH_CONNECT}; break;
                    case "notif": if (Build.VERSION.SDK_INT >= 33) p = new String[]{Manifest.permission.POST_NOTIFICATIONS}; break;
                    default: a.openAppSettings(); return;
                }
                if (p == null) { a.js("onAutoChanged", ""); return; }
                android.content.SharedPreferences sp = ctx.getSharedPreferences("perm", Context.MODE_PRIVATE);
                String key = "auto_" + kind;
                if (sp.getBoolean(key, false) && !a.shouldShowRequestPermissionRationale(p[0])
                        && ctx.checkSelfPermission(p[0]) != PackageManager.PERMISSION_GRANTED) { a.openAppSettings(); return; }
                sp.edit().putBoolean(key, true).apply();
                a.requestPermissions(p, REQ_AUTO);
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

        /** Een podcast op het startscherm: tikken = verder waar je was, of de nieuwste aflevering. */
        @JavascriptInterface public String shortcutPinPodcast(String podJson) {
            try {
                JSONObject p = new JSONObject(podJson);
                String feed = p.optString("feed");
                if (!Podcasts.httpUrl(feed)) return "Geen geldige podcast";
                JSONObject slim = new JSONObject().put("feed", feed).put("title", PodcastFeed.cut(p.optString("title", "Podcast"), 120))
                        .put("id", Podcasts.podId(feed)).put("image", Podcasts.httpUrl(p.optString("image")) ? p.optString("image") : "")
                        .put("author", PodcastFeed.cut(p.optString("author"), 120));
                return Shortcuts.pin(ctx, "pod-" + Podcasts.podId(feed), slim.optString("title"), "podcasts", new JSONObject().put("pod", slim).toString(), "#6C3483", "🎧");
            } catch (Exception e) { return "Snelkoppeling maken lukt niet"; }
        }

        /** "Verder luisteren" op het startscherm: wat het laatst speelde (radio of podcast) gaat verder. */
        @JavascriptInterface public String shortcutPinPlayer() {
            return Shortcuts.pin(ctx, "player", "Verder luisteren", "player", "{\"resume\":true}", "#6C3483", "▶");
        }

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

        @JavascriptInterface public void radioPlay(String station) {
            try {
                JSONObject s = new JSONObject(station);
                String url = s.optString("url").toLowerCase();
                if (!url.startsWith("http://") && !url.startsWith("https://")) return;
                Radio.prefs(ctx).edit().putString("last", station).apply();
                if (Cast.active()) { final JSONObject st = s; PodcastBridge.castBg(() -> Cast.load(Cast.fromStation(st))); } // er wordt gecast: daar afspelen
                else RadioService.send(ctx, RadioService.PLAY, station);
                final String id = s.optString("id");
                new Thread(() -> Radio.click(ctx, id), "radio-click").start();
            } catch (Exception ignored) { }
        }

        @JavascriptInterface public void radioPause() { if (Cast.active()) PodcastBridge.castBg(() -> Cast.setPlaying(false)); else RadioService.send(ctx, RadioService.PAUSE, null); }
        /** Terugspoelen (rew), vooruit (fwd) of terug naar live (live) in de buffer. */
        @JavascriptInterface public void radioShift(String what) {
            if (RadioService.REW.equals(what) || RadioService.FWD.equals(what) || RadioService.LIVE.equals(what)) RadioService.send(ctx, what, null);
        }
        @JavascriptInterface public boolean radioTimeshift() { return Radio.prefs(ctx).getBoolean("timeshift", true); }
        @JavascriptInterface public void radioSetTimeshift(boolean on) { Radio.prefs(ctx).edit().putBoolean("timeshift", on).apply(); }

        @JavascriptInterface public void radioResume() {
            if (Cast.active()) { PodcastBridge.castBg(() -> Cast.setPlaying(true)); return; }
            if (RadioService.station == null) {
                String last = Radio.prefs(ctx).getString("last", null);
                if (last != null) radioPlay(last);
                return;
            }
            RadioService.send(ctx, RadioService.RESUME, null);
        }

        @JavascriptInterface public void radioStop() { if (Cast.active()) PodcastBridge.castBg(Cast::stop); else RadioService.send(ctx, RadioService.STOP, null); }

        @JavascriptInterface public void radioSleep(int minutes) { if (Cast.active()) Cast.sleep(minutes); else RadioService.send(ctx, RadioService.SLEEP, String.valueOf(minutes)); }

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

        /** De app-pagina mag nooit naar een andere site: die zou de Android-brug krijgen. Externe links gaan naar de browser. */
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (u != null && "app.renes-tools.local".equals(u.getHost()) && "https".equals(u.getScheme())) return false;
            if (u != null && req.isForMainFrame() && ("https".equals(u.getScheme()) || "http".equals(u.getScheme()))) {
                try { act.startActivity(new Intent(Intent.ACTION_VIEW, u).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
            }
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
                String mime = ext == null ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase(java.util.Locale.ROOT));
                // Alleen plaatjes (geen svg): een html-bestand uit WhatsApp mag nooit als pagina met de app-brug openen
                if (mime == null || !mime.startsWith("image/") || mime.contains("svg")) return notFound();
                WebResourceResponse r = new WebResourceResponse(mime, null, new java.io.FileInputStream(f));
                java.util.Map<String, String> hd = new java.util.HashMap<>();
                hd.put("X-Content-Type-Options", "nosniff");
                hd.put("Content-Security-Policy", "default-src 'none'; sandbox");
                r.setResponseHeaders(hd);
                return r;
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
