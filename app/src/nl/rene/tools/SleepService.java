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
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Slaappraat: luistert de hele nacht zuinig naar de microfoon (AudioRecord, 16 kHz mono) en bewaart alleen de
 * stukjes waarin iets te horen is (SleepDetect). Voorgronddienst met een melding en een stopknop; stopt vanzelf
 * op de stoptijd, na 13 uur, of als de accu bijna leeg is (en niet laadt). Daarna (als je dat wilt) worden de
 * stukjes uitgeschreven op de telefoon (whisper), als gewone voorgronddienst voor gegevens.
 *
 * Zuinig: één keer per 100 ms een blok van de microfoon, per 20 ms een paar optellingen; niets naar internet.
 */
public final class SleepService extends Service {

    static final String CHANNEL = "sleeptalk";
    private static final int NOTIFICATION = 4801;

    /** Voor de interface. */
    static volatile String phase = "";            // "", "luistert", "uitschrijven"
    static volatile String night = "";
    static volatile long since, stopAt;
    static volatile int heard, txDone, txTotal;
    static volatile double levelDb = -90, floorDb = -90;
    static volatile String message = "";

    private volatile boolean stop;
    private volatile String stopReason;
    private Thread worker;
    private PowerManager.WakeLock wake;
    private int lastStartId;

    static boolean running() { return !phase.isEmpty(); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        String action = intent == null ? "" : intent.getAction();
        if ("stop".equals(action)) {
            if ("luistert".equals(phase)) { stopReason = "Gestopt"; stop = true; }
            else if ("uitschrijven".equals(phase)) SleepTalk.stopTx = true;
            if (worker == null) { goForeground(false); quit(); }
            return START_NOT_STICKY;
        }
        // Altijd eerst voorgrond (verplicht na startForegroundService), ook als er al iets loopt
        boolean fg = goForeground(true);
        if (worker != null) {
            // Nog aan het uitschrijven: dat stopt, en daarna begint het luisteren
            if ("uitschrijven".equals(phase) && fg) { again = true; SleepTalk.stopTx = true; }
            return START_NOT_STICKY;
        }
        if (!fg) { message = "Android staat het luisteren nu niet toe. Open de app en probeer het opnieuw."; quit(); return START_NOT_STICKY; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { message = "Geef eerst microfoontoegang"; quit(); return START_NOT_STICKY; }
        if (!beginListening()) quit();
        return START_NOT_STICKY;
    }

    private volatile boolean again;

    private boolean beginListening() {
        stop = false; stopReason = null; message = ""; SleepTalk.stopTx = false;
        phase = "luistert"; since = System.currentTimeMillis(); heard = 0; levelDb = -90; floorDb = -90;
        stopAt = SleepTalk.stopTime(since, SleepTalk.stopAt(this), java.util.TimeZone.getDefault());
        try { night = SleepTalk.begin(this, since, SleepTalk.sens(this)); } catch (Exception e) { message = "Opslaan lukt niet"; phase = ""; return false; }
        SleepTalk.cleanup(this, night);
        if (wake == null) wake = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "renestools:slaappraat");
        if (!wake.isHeld()) wake.acquire(14 * 3_600_000L);
        notifyNow();
        worker = new Thread(this::run, "slaappraat");
        worker.start();
        return true;
    }

    private void run() {
        try { listen(); } catch (Throwable t) { App.log(this, "SLAAP", "dienst: " + t); phase = ""; worker = null; quit(); }
    }

    private void listen() {
        final String n = night; final long start = since;
        AudioRecord rec = null;
        try {
            int min = AudioRecord.getMinBufferSize(SleepDetect.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int buf = Math.max(min, SleepDetect.RATE * 2); // ±1 s buffer: zelden wakker worden is zuinig
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, SleepDetect.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf);
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new Exception("De microfoon is niet beschikbaar");
            SleepDetect det = new SleepDetect(SleepTalk.sens(this), k -> {
                try { SleepTalk.addClip(this, n, start, k); heard++; notifyNow(); } catch (Throwable t) { App.log(this, "SLAAP", "opslaan: " + t); }
            });
            rec.startRecording();
            short[] block = new short[SleepDetect.FRAME * 5]; // 100 ms
            short[] frame = new short[SleepDetect.FRAME];
            long nextCheck = 0, failSince = 0;
            while (!stop) {
                int got = 0;
                while (got < block.length && !stop) {
                    int r = rec.read(block, got, block.length - got);
                    if (r < 0) throw new Exception("De microfoon gaf een fout (" + r + ")");
                    if (r == 0) { if (failSince == 0) failSince = System.currentTimeMillis(); else if (System.currentTimeMillis() - failSince > 60_000) throw new Exception("De microfoon geeft geen geluid meer"); Thread.sleep(50); continue; }
                    failSince = 0; got += r;
                }
                double peak = -90;
                for (int i = 0; i + SleepDetect.FRAME <= got; i += SleepDetect.FRAME) {
                    System.arraycopy(block, i, frame, 0, SleepDetect.FRAME);
                    det.feed(frame);
                }
                long now = System.currentTimeMillis();
                // Voor het scherm: hoe hard het nu is (blokgemiddelde)
                double sq = 0; for (int i = 0; i < got; i++) sq += (double) block[i] * block[i];
                if (got > 0) peak = SleepDetect.db(sq / got);
                levelDb = peak; floorDb = det.floorDb();
                if (now >= nextCheck) {
                    nextCheck = now + 60_000;
                    if (stopAt > 0 && now >= stopAt) { stopReason = "Stoptijd bereikt"; break; }
                    if (now - start > 13 * 3_600_000L) { stopReason = "Na 13 uur vanzelf gestopt"; break; }
                    int[] bat = battery();
                    if (bat[0] >= 0 && bat[0] <= SleepTalk.minBattery(this) && bat[1] == 0) { stopReason = "Gestopt: de accu was bijna leeg (" + bat[0] + "%)"; break; }
                }
            }
            det.flush();
        } catch (Throwable t) {
            stopReason = t.getMessage() == null ? "Het luisteren is gestopt" : t.getMessage();
            App.log(this, "SLAAP", "luisteren: " + t);
        } finally {
            if (rec != null) { try { rec.stop(); } catch (Throwable ignored) { } try { rec.release(); } catch (Throwable ignored) { } }
        }
        SleepTalk.end(this, n, System.currentTimeMillis(), stopReason == null ? "Gestopt" : stopReason);
        levelDb = -90;
        // Daarna uitschrijven (als je dat wilt en er een spraakmodel is)
        try {
            if (SleepTalk.transcribe(this) && Transcribe.activeModel(this) != null && SleepTalk.open(this, n) > 0) {
                phase = "uitschrijven"; txDone = 0; txTotal = SleepTalk.open(this, n); SleepTalk.stopTx = false;
                goForegroundData();
                SleepTalk.transcribeNight(this, n, (d, t) -> { txDone = d; txTotal = t; notifyNow(); });
            }
        } catch (Throwable t) {
            message = "Uitschrijven lukte niet: " + (t.getMessage() == null ? "onbekende fout" : t.getMessage());
            App.log(this, "SLAAP", "uitschrijven: " + t);
        }
        doneNotice();
        if (again) { again = false; if (beginListening()) return; }
        phase = ""; worker = null;
        if (wake != null && wake.isHeld()) try { wake.release(); } catch (Throwable ignored) { }
        quit();
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
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Slaappraat", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Zichtbaar zolang Slaappraat luistert of uitschrijft, met een stopknop");
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class).putExtra("open", "sleep").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, NOTIFICATION, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopI = PendingIntent.getService(this, NOTIFICATION, new Intent(this, SleepService.class).setAction("stop"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        boolean tx = "uitschrijven".equals(phase);
        String text = tx ? "Uitschrijven: " + txDone + " van " + txTotal
                : heard == 0 ? "Nog niets gehoord" : heard + (heard == 1 ? " keer iets gehoord" : " keer iets gehoord");
        if (!tx && stopAt > 0) text += " · stopt om " + new java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(new java.util.Date(stopAt));
        return b.setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle(tx ? "Slaappraat schrijft uit" : "Slaappraat luistert")
                .setContentText(text).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(!tx).setWhen(since).setUsesChronometer(!tx)
                .setContentIntent(content).addAction(android.R.drawable.ic_media_pause, tx ? "Stoppen" : "Stoppen", stopI).build();
    }

    private boolean goForeground(boolean mic) {
        return Fg.start(this, NOTIFICATION, build(), mic ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0, 30);
    }
    private void goForegroundData() { Fg.start(this, NOTIFICATION, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, 29); }

    private void notifyNow() {
        try { getSystemService(NotificationManager.class).notify(NOTIFICATION, build()); } catch (Throwable ignored) { }
    }

    /** Na afloop: een rustige melding met wat er gehoord is (alleen als er iets was). */
    private void doneNotice() {
        try {
            if (heard == 0) return;
            Intent open = new Intent(this, MainActivity.class).putExtra("open", "sleep").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent content = PendingIntent.getActivity(this, NOTIFICATION + 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            String ch = NotifCenter.ch(this, CHANNEL);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, ch) : new Notification.Builder(this);
            Notification nn = b.setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Slaappraat")
                    .setContentText(heard + (heard == 1 ? " keer" : " keer") + " iets gehoord vannacht. Tik om te kijken.").setAutoCancel(true).setContentIntent(content).build();
            getSystemService(NotificationManager.class).notify(NOTIFICATION + 1, nn);
        } catch (Throwable ignored) { }
    }

    private void quit() {
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Throwable ignored) { }
        stopSelfResult(lastStartId);
    }

    @Override public void onDestroy() {
        stop = true; SleepTalk.stopTx = true;
        if (wake != null && wake.isHeld()) try { wake.release(); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
