package nl.rene.tools;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Slaappraat: luistert de hele nacht zuinig naar de microfoon (AudioRecord, 16 kHz mono) en bewaart alleen de
 * stukjes waarin iets te horen is (SleepDetect). Voorgronddienst (microfoon) met een melding en een stopknop; stopt
 * vanzelf op de stoptijd, na 13 uur, of als de accu bijna leeg is (en niet laadt). Tijdens een telefoongesprek
 * luistert hij niet (dat is van de beller, en de microfoon is dan voor het gesprek). Daarna (als je dat wilt) worden
 * de stukjes uitgeschreven op de telefoon (whisper), in dezelfde dienst.
 *
 * Meteen uitschrijven (standaard aan): terwijl hij luistert schrijft één eigen thread (lage voorrang, hooguit 2
 * rekenkernen) elk bewaard stukje kort daarna al uit, het oudste eerst. Wanneer is instelbaar (standaard: aan de lader,
 * of zonder lader vanaf 40% accu en zonder batterijbesparing); nooit tijdens een gesprek of als de telefoon warm is. Het luisteren en het
 * opslaan wachten daar nooit op. Stopt het luisteren, dan stopt dit ook (een stukje dat half was blijft open) en doet
 * het gewone uitschrijven daarna alleen wat er over is.
 *
 * Zuinig: twee keer per seconde een blok van de microfoon, per 20 ms een paar optellingen; wegschrijven op een
 * eigen thread; niets naar internet.
 *
 * Alles wat beslist of er een nieuwe ronde begint of de dienst stopt, gebeurt op de hoofdthread (main), zodat een
 * tik op Start precies op het moment dat het werk klaar is nooit verloren gaat of de nieuwe ronde stopt.
 */
public final class SleepService extends Service {

    static final String CHANNEL = "sleeptalk";
    /** Onopvallend: laagste belangrijkheid (ingeklapt, geen icoon in de statusbalk, niet op het vergrendelscherm). */
    static final String CHANNEL_QUIET = "sleeptalk-stil";
    private static final int NOTIFICATION = 4801;

    /** Voor de interface. */
    static volatile String phase = "";            // "", "luistert", "uitschrijven"
    static volatile String night = "";
    static volatile long since, stopAt;
    static volatile int heard, txDone, txTotal;
    static volatile double levelDb = -90, floorDb = -90;
    static volatile String message = "";
    static volatile boolean inCall;
    /** Meteen uitschrijven: gepraat, geluiden, nog uit te schrijven, het laatste wat je zei, en waarom het wacht ("" = niet). */
    static volatile int liveTalk, liveNoise, liveOpen;
    static volatile String liveText = "", directWait = "";
    static volatile long liveAt;
    static volatile boolean directBusy;
    /** Proefronde: hoe vaak iets opgepikt, en of hij nu iets hoort. */
    static volatile boolean hearingNow;
    static volatile int probeHits;
    static volatile long probeUntil;
    static final long PROBE_MS = 30_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean stop, destroyed;
    private volatile String stopReason;
    private volatile Thread worker;           // alleen op main gezet of gewist
    private boolean again;                    // alleen op main
    private PowerManager.WakeLock wake;
    private int lastStartId;
    private ExecutorService writer;
    private volatile Thread direct;
    private volatile boolean directStop;
    /** Elke ronde een eigen nummer: een oude thread die nog ergens op wachtte, gaat nooit verder met een nieuwe nacht. */
    private volatile int directGen;
    private final Object directLock = new Object();
    private boolean directKick;               // onder directLock

    static boolean running() { return !phase.isEmpty(); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        String action = intent == null ? "" : intent.getAction();
        if ("stop".equals(action)) {
            // Via de melding (startService): geen voorgrond nodig
            again = false; // ook een Start die nog wachtte
            if ("luistert".equals(phase) || "proef".equals(phase)) { stopReason = "Gestopt"; stop = true; }
            else if ("uitschrijven".equals(phase)) SleepTalk.stopNow();
            if (worker == null) quit();
            return START_NOT_STICKY;
        }
        if ("refresh".equals(action)) {
            // Instelling Onopvallend veranderd terwijl hij loopt (startService vanuit de app): melding meteen omzetten
            // Alleen een instantie die zelf luistert of uitschrijft heeft een melding om om te zetten
            if (worker != null) notifyNow(); else quit();
            return START_NOT_STICKY;
        }
        // Na startForegroundService altijd eerst voorgrond, ook als er al iets loopt
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean fg = mic && goForeground();
        if (worker != null) {
            // Nog aan het uitschrijven en je wilt luisteren: dat stukje stopt, daarna begint het luisteren
            if ("start".equals(action) && "uitschrijven".equals(phase) && fg) { again = true; SleepTalk.stopNow(); }
            return START_NOT_STICKY;
        }
        if (!mic) { message = "Geef eerst microfoontoegang"; quit(); return START_NOT_STICKY; }
        if (!fg) { message = "Android staat het luisteren nu niet toe. Probeer het opnieuw."; quit(); return START_NOT_STICKY; }
        if ("probe".equals(action)) beginProbe();
        else if ("transcribe".equals(action)) {
            String n = intent.getStringExtra("night");
            if (!SleepTalk.safeId(n)) { quit(); return START_NOT_STICKY; }
            startTranscribe(n);
        } else if (!beginListening()) quit();
        return START_NOT_STICKY;
    }

    /** Op main. */
    private boolean beginListening() {
        stop = false; stopReason = null; message = ""; SleepTalk.stopTx = false;
        long t0 = System.currentTimeMillis();
        int[] bat = battery();
        charged = bat[1] == 1;
        params = SleepTalk.detectParams(this);
        try { night = SleepTalk.begin(this, t0, SleepTalk.sens(this), params, bat[0], charged); } catch (Exception e) { message = "Opslaan lukt niet"; return false; }
        since = t0; heard = 0; levelDb = -90; floorDb = -90; inCall = false;
        liveTalk = 0; liveNoise = 0; liveOpen = 0; liveText = ""; liveAt = 0; directWait = ""; directBusy = false;
        stopAt = SleepTalk.stopTime(since, SleepTalk.stopAt(this), java.util.TimeZone.getDefault());
        phase = "luistert";
        SleepReminder.dismiss(this);
        SleepTalk.cleanup(this, night);
        // Vers voor deze nacht (in de "opnieuw"-route liep hij al een tijd)
        if (wake != null && wake.isHeld()) try { wake.release(); } catch (Throwable ignored) { }
        holdWake();
        notifyNow();
        if (writer == null) writer = Executors.newSingleThreadExecutor(r -> new Thread(r, "slaappraat-opslaan"));
        startDirect(night); // eerst: dan ziet het luisteren hem altijd als het (meteen) weer stopt
        Thread t = new Thread(this::listenThenWork, "slaappraat");
        worker = t;
        t.start();
        return true;
    }

    /** Op main: proefronde van 30 seconden. Niets wordt bewaard; het scherm laat zien wat hij oppikt. */
    private void beginProbe() {
        stop = false; stopReason = null; message = ""; probe = true; night = "";
        since = System.currentTimeMillis(); probeUntil = since + PROBE_MS; probeHits = 0; heard = 0; hearingNow = false;
        levelDb = -90; floorDb = -90; inCall = false; stopAt = 0;
        params = SleepTalk.detectParams(this);
        phase = "proef";
        holdWake();
        notifyNow();
        Thread t = new Thread(() -> {
            try { listen("", since); } catch (Throwable e) { App.log(this, "SLAAP", "proef: " + e); }
            probe = false; hearingNow = false; levelDb = -90;
            // Een fout (microfoon, accu) laten staan; anders de uitkomst van de proef
            if (stopReason == null || "Gestopt".equals(stopReason)) message = probeHits == 0 ? "Proef klaar: niets opgepikt. Praat wat harder, leg de telefoon dichterbij of kies Ook gefluister."
                    : "Proef klaar: " + probeHits + " keer iets opgepikt. Zo pakt hij het vannacht ook op.";
            main.post(this::afterWork);
        }, "slaappraat-proef");
        worker = t;
        t.start();
    }
    private volatile boolean probe, charged;
    /** Opname-instellingen van deze ronde (bij het begin vastgelegd; voor de proef de huidige). */
    private volatile SleepDetect.Params params = SleepDetect.Params.DEFAULT;

    /** Op main: alleen uitschrijven (een nacht van eerder). */
    private void startTranscribe(String n) {
        SleepTalk.stopTx = false; message = "";
        night = n; phase = "uitschrijven"; txDone = 0; txTotal = SleepTalk.open(this, n);
        holdWake();
        notifyNow();
        Thread t = new Thread(() -> {
            try { transcribe(n); } catch (Throwable e) { App.log(this, "SLAAP", "uitschrijven: " + e); }
            main.post(this::afterWork);
        }, "slaappraat-uitschrijven");
        worker = t;
        t.start();
    }

    private void holdWake() {
        if (wake == null) wake = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "renestools:slaappraat");
        if (!wake.isHeld()) wake.acquire(14 * 3_600_000L);
    }

    private void listenThenWork() {
        final String n = night;
        try {
            listen(n, since);
            stopDirect();
            refreshLive(n);
            int[] bat = battery();
            SleepTalk.end(this, n, System.currentTimeMillis(), stopReason == null ? "Gestopt" : stopReason, bat[0], charged || bat[1] == 1);
            levelDb = -90;
            if (!destroyed && SleepTalk.transcribe(this) && SleepTalk.model(this) != null && SleepTalk.open(this, n) > 0) {
                phase = "uitschrijven"; txDone = 0; txTotal = SleepTalk.open(this, n);
                notifyNow();
                transcribe(n);
            }
        } catch (Throwable t) {
            App.log(this, "SLAAP", "dienst: " + t);
            stopDirect();
            SleepTalk.end(this, n, System.currentTimeMillis(), "Onderbroken");
        }
        doneNotice();
        main.post(this::afterWork);
    }

    private void transcribe(String n) {
        try {
            SleepTalk.transcribeNight(this, n, (d, t) -> { txDone = d; txTotal = t; notifyNow(); });
        } catch (Throwable t) {
            message = "Uitschrijven lukte niet: " + (t.getMessage() == null ? "onbekende fout" : t.getMessage());
            App.log(this, "SLAAP", "uitschrijven: " + t);
        }
    }

    /** Op main: verder luisteren (als je intussen op Start tikte) of stoppen. */
    private void afterWork() {
        worker = null;
        // De dienst is al voorgrond (microfoon, gestart toen je op Start tikte): gewoon verder
        if (again && !destroyed) { again = false; if (beginListening()) return; }
        again = false;
        phase = "";
        if (wake != null && wake.isHeld()) try { wake.release(); } catch (Throwable ignored) { }
        quit();
    }

    /** Het luisteren zelf (eigen thread). */
    private void listen(final String n, final long start) {
        AudioRecord rec = null;
        final SleepDetect det = new SleepDetect(SleepTalk.sens(this), params, k -> {
            if (probe) { probeHits++; return; } // proef: niets bewaren
            heard++;
            final ExecutorService w = writer;
            // Wegschrijven op een eigen thread: de microfoon wordt ondertussen gewoon verder gelezen
            if (w != null) try { w.execute(() -> {
                try { SleepTalk.addClip(this, n, start, k); refreshLive(n); } catch (Throwable t) { App.log(this, "SLAAP", "opslaan: " + t); }
                kickDirect();
            }); } catch (Throwable ignored) { }
            notifyNow();
        });
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        short[] block = new short[SleepDetect.FRAME * 25]; // 500 ms per keer lezen: maar twee keer per seconde wakker
        short[] frame = new short[SleepDetect.FRAME];
        int restarts = 0;
        try {
            rec = openMic();
            while (!stop) {
                // Telefoongesprek (ook via internet): niet luisteren en de microfoon vrijgeven
                int mode = am == null ? AudioManager.MODE_NORMAL : am.getMode();
                if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) {
                    if (!inCall) { inCall = true; pausedAt = System.currentTimeMillis(); det.flush(); notifyNow(); try { rec.stop(); } catch (Throwable ignored) { } }
                    Thread.sleep(1000);
                    checkLimits(start);
                    continue;
                }
                if (inCall) {
                    // Na het gesprek: de microfoon weer pakken (lukt het nog niet, dan zo meteen opnieuw)
                    try {
                        rec.startRecording();
                        if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) { try { rec.release(); } catch (Throwable ignored) { } rec = openMic(); }
                    } catch (Throwable e) { Thread.sleep(5000); checkLimits(start); continue; }
                    inCall = false;
                    det.gap((System.currentTimeMillis() - pausedAt) / 20);
                    notifyNow();
                }
                int got = 0;
                while (got < block.length && !stop) {
                    int r = rec.read(block, got, block.length - got);
                    if (r == AudioRecord.ERROR_DEAD_OBJECT && restarts < 5) {
                        // De audiodienst van Android is herstart: opnieuw openen
                        restarts++;
                        long t0 = System.currentTimeMillis();
                        try { rec.release(); } catch (Throwable ignored) { }
                        rec = null;
                        Thread.sleep(2000);
                        rec = openMic();
                        det.gap((System.currentTimeMillis() - t0) / 20 + got / SleepDetect.FRAME);
                        got = 0;
                        continue;
                    }
                    if (r < 0) throw new Exception("De microfoon gaf een fout (" + r + ")");
                    if (r == 0) { Thread.sleep(50); continue; }
                    got += r;
                }
                double sq = 0;
                for (int i = 0; i + SleepDetect.FRAME <= got; i += SleepDetect.FRAME) {
                    System.arraycopy(block, i, frame, 0, SleepDetect.FRAME);
                    det.feed(frame);
                }
                for (int i = 0; i < got; i++) sq += (double) block[i] * block[i];
                if (got > 0) levelDb = SleepDetect.db(sq / got);
                floorDb = det.floorDb();
                hearingNow = det.inClip();
                checkLimits(start);
            }
            det.flush();
        } catch (Throwable t) {
            try { det.flush(); } catch (Throwable ignored) { }
            stopReason = t.getMessage() == null ? "Het luisteren is gestopt" : t.getMessage();
            message = stopReason;
            App.log(this, "SLAAP", "luisteren: " + t);
        } finally {
            if (rec != null) { try { rec.stop(); } catch (Throwable ignored) { } try { rec.release(); } catch (Throwable ignored) { } }
            // Wat nog weggeschreven moet worden, eerst afmaken
            ExecutorService w = writer;
            if (w != null) { w.shutdown(); try { w.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS); } catch (Throwable ignored) { } writer = null; }
        }
    }

    // ---------- meteen uitschrijven ----------

    /** Op main (beginListening): de thread die tijdens het luisteren stukje voor stukje uitschrijft. */
    private void startDirect(String n) {
        directStop = false;
        synchronized (directLock) { directKick = false; }
        final int g = ++directGen;
        Thread t = new Thread(() -> directLoop(n, g), "slaappraat-meteen");
        direct = t;
        t.start();
    }

    /** Er is een stukje bij: niet wachten tot de volgende ronde. */
    private void kickDirect() { synchronized (directLock) { directKick = true; directLock.notifyAll(); } }

    /** Stoppen en wachten tot hij weg is (een stukje dat half was, blijft open voor straks). Nooit op main. */
    private void stopDirect() {
        Thread t = direct;
        if (t == null) return;
        directStop = true;
        directGen++;
        kickDirect();
        Transcribe.stopOwnedBy(t);
        // Wacht hij op een ander whisper (Gesprekken uitschrijven)? Dan niet eindeloos: het vastleggen controleert zelf
        // of een stukje al gedaan is, en zijn eerste voortgang stopt het proces meteen.
        try { t.join(20_000); } catch (Throwable ignored) { }
        direct = null; directBusy = false; directWait = "";
    }

    private boolean directHalt(int g) { return g != directGen || directStop || stop || destroyed || inCall; }

    private void directLoop(String n, int g) {
        try {
            // Lage voorrang; het whisper-proces dat deze thread start, erft die (nice)
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); } catch (Throwable ignored) { }
            int fails = 0;
            while (g == directGen && !directStop && !stop && !destroyed) {
                synchronized (directLock) {
                    if (!directKick) directLock.wait(30_000);
                    directKick = false;
                }
                if (g != directGen || directStop || stop || destroyed) break;
                if (!SleepTalk.transcribe(this) || !SleepTalk.direct(this)) { setWait(""); continue; }
                Transcribe.Model m = SleepTalk.model(this);
                if (m == null) { setWait(""); continue; }
                String clip = SleepTalk.nextOpen(this, n);
                if (clip == null) { setWait(""); continue; }
                String why = directReason();
                setWait(why);
                if (!why.isEmpty()) continue;
                int r;
                directBusy = true;
                try { r = SleepTalk.transcribeClip(this, n, clip, m, () -> directHalt(g), 2); fails = 0; }
                catch (Throwable e) {
                    App.log(this, "SLAAP", "meteen uitschrijven: " + e);
                    // Lukt het steeds niet, dan deze nacht niet meer (straks volgt het gewone uitschrijven, met een melding)
                    if (++fails >= 3) { setWait("het lukte steeds niet"); break; }
                    continue;
                } finally { directBusy = false; }
                refreshLive(n);
                notifyNow();
                // Klaar met een stukje: meteen door met het volgende (gestopt of overgeslagen: rustig wachten)
                if (r == SleepTalk.TX_DONE) synchronized (directLock) { directKick = true; }
            }
        } catch (Throwable t) {
            App.log(this, "SLAAP", "meteen uitschrijven: " + t);
        }
    }

    private void setWait(String why) { directWait = why; }

    /** Waarom meteen uitschrijven nu wacht (zie SleepTalk.directWait), of "". */
    private String directReason() {
        int[] bat = battery();
        boolean hot = false, saver = false;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            saver = pm.isPowerSaveMode();
            if (Build.VERSION.SDK_INT >= 29) hot = pm.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_MODERATE;
        } catch (Throwable ignored) { }
        return SleepTalk.directWait(SleepTalk.directWhen(this), SleepTalk.directBat(this), bat[1] == 1, bat[0], inCall, hot, saver);
    }

    /** De tellers voor het scherm en de melding (na elk opgeslagen of uitgeschreven stukje). */
    private void refreshLive(String n) {
        try {
            SleepTalk.Live l = SleepTalk.live(this, n);
            liveTalk = l.talk; liveNoise = l.noise; liveOpen = l.open; liveText = l.text; liveAt = l.at;
        } catch (Throwable ignored) { }
    }

    private long nextLimitCheck, pausedAt;
    private void checkLimits(long start) {
        long now = System.currentTimeMillis();
        if (probe && now >= probeUntil) { stop = true; return; } // proef klaar (ook tijdens een gesprek)
        if (now < nextLimitCheck) return;
        nextLimitCheck = now + 60_000;
        if (stopAt > 0 && now >= stopAt) { stopReason = "Stoptijd bereikt"; stop = true; return; }
        if (now - start > 13 * 3_600_000L) { stopReason = "Na 13 uur vanzelf gestopt"; stop = true; return; }
        int[] bat = battery();
        if (bat[1] == 1) charged = true;
        if (bat[0] >= 0 && bat[0] <= SleepTalk.minBattery(this) && bat[1] == 0) { stopReason = "Gestopt: de accu was bijna leeg (" + bat[0] + "%)"; stop = true; }
    }

    private AudioRecord openMic() throws Exception {
        int min = AudioRecord.getMinBufferSize(SleepDetect.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int buf = Math.max(min, SleepDetect.RATE * 2 * 4); // 4 s: ruim, ook als het opslaan even duurt
        AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.MIC, SleepDetect.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf);
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) { rec.release(); throw new Exception("De microfoon is niet beschikbaar"); }
        rec.startRecording();
        if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) { rec.release(); throw new Exception("De microfoon is in gebruik door een andere app"); }
        return rec;
    }

    /** {percentage, laadt (1/0)}; -1 als onbekend. */
    private int[] battery() {
        try {
            Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) return new int[]{-1, 0};
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            return new int[]{level < 0 ? -1 : level * 100 / Math.max(1, scale), b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 ? 1 : 0};
        } catch (Throwable t) { return new int[]{-1, 0}; }
    }

    private Notification build() {
        boolean quiet = SleepTalk.quiet(this);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (quiet) quietChannel(this);
            else {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "Slaappraat", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Zichtbaar zolang Slaappraat luistert of uitschrijft, met een stopknop; 's ochtends wat er gehoord is");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
        Intent open = new Intent(this, MainActivity.class).putExtra("open", "sleep").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, NOTIFICATION, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopI = PendingIntent.getService(this, NOTIFICATION, new Intent(this, SleepService.class).setAction("stop"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, quiet ? CHANNEL_QUIET : CHANNEL) : new Notification.Builder(this);
        if (Build.VERSION.SDK_INT >= 31 && !quiet) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        if (quiet) quietStyle(b); // niet op het vergrendelscherm, zonder tijdteller (hoe groot bepaalt Android)
        boolean tx = "uitschrijven".equals(phase);
        if ("proef".equals(phase)) return b.setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Slaappraat: proef van 30 seconden")
                .setContentText("Praat eens zoals je in je slaap zou doen").setOngoing(true).setOnlyAlertOnce(true).setContentIntent(content)
                .addAction(android.R.drawable.ic_media_pause, "Stoppen", stopI).build();
        // Nooit wat je zei in de melding (die kan op het vergrendelscherm staan): alleen tellen
        String text = tx ? "Uitschrijven: " + txDone + " van " + txTotal
                : inCall ? "Luistert niet tijdens het gesprek" : liveTalk + liveNoise > 0 ? SleepTalk.liveLine(liveTalk, liveNoise)
                : heard == 0 ? "Nog niets gehoord" : heard + " keer iets gehoord";
        if (!tx && stopAt > 0) text += " · stopt om " + new java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(new java.util.Date(stopAt));
        return b.setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle(tx ? "Slaappraat schrijft uit" : "Slaappraat luistert")
                .setContentText(text).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(!tx && !quiet).setWhen(since).setUsesChronometer(!tx && !quiet)
                .setContentIntent(content).addAction(android.R.drawable.ic_media_pause, "Stoppen", stopI).build();
    }

    /**
     * Het stille kanaal, één keer aangemaakt (ook door NotifCenter.ensureAll, met dezelfde instellingen). Android zet een
     * voorgrondmelding op minimaal zelf op Laag; wat je daarna zelf in Android instelt (bijv. Minimaliseren) blijft staan.
     */
    static void quietChannel(android.content.Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm.getNotificationChannel(CHANNEL_QUIET) != null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL_QUIET, "Slaappraat (onopvallend)", NotificationManager.IMPORTANCE_MIN);
            ch.setDescription(QUIET_INFO);
            ch.setSound(null, null); ch.enableVibration(false); ch.enableLights(false); ch.setShowBadge(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            nm.createNotificationChannel(ch);
        } catch (Throwable ignored) { }
    }

    static final String QUIET_INFO = "Als Onopvallend luisteren aan staat: stil, niet op het vergrendelscherm, zonder tijdteller en 's ochtends geen melding. Zet hier Minimaliseren aan om hem nog kleiner te maken";

    @SuppressWarnings("deprecation")
    private static void quietStyle(Notification.Builder b) {
        b.setPriority(Notification.PRIORITY_MIN).setVisibility(Notification.VISIBILITY_SECRET).setShowWhen(false);
    }

    /** Altijd type microfoon (ook tijdens het uitschrijven): geen dagelijkse tijdslimiet zoals bij dataSync. */
    private boolean goForeground() { return Fg.start(this, NOTIFICATION, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, 30); }

    private void notifyNow() {
        try { getSystemService(NotificationManager.class).notify(NOTIFICATION, build()); } catch (Throwable ignored) { }
    }

    /** Na afloop: een rustige melding met wat er gehoord is (alleen als er iets was). */
    private void doneNotice() {
        try {
            if (heard == 0 || SleepTalk.quiet(this)) return; // onopvallend: wat er gehoord is staat in de app
            Intent open = new Intent(this, MainActivity.class).putExtra("open", "sleep").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent content = PendingIntent.getActivity(this, NOTIFICATION + 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            String ch = NotifCenter.ch(this, CHANNEL);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, ch) : new Notification.Builder(this);
            Notification nn = b.setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Slaappraat")
                    .setContentText(heard + " keer iets gehoord vannacht. Tik om te kijken.").setAutoCancel(true).setContentIntent(content).build();
            getSystemService(NotificationManager.class).notify(NOTIFICATION + 1, nn);
        } catch (Throwable ignored) { }
    }

    private void quit() {
        if (worker == null) phase = ""; // ook als het niet kon starten: niet "bezig" blijven
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Throwable ignored) { }
        stopSelfResult(lastStartId);
    }

    @Override public void onDestroy() {
        destroyed = true; stop = true; directStop = true; SleepTalk.stopNow();
        Transcribe.stopOwnedBy(direct);
        kickDirect();
        if (!"".equals(phase) && worker == null) phase = "";
        if (wake != null && wake.isHeld()) try { wake.release(); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
