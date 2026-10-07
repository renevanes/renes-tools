package nl.rene.tools;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import java.io.File;
import java.util.UUID;

/** Handmatige of expliciet ingeschakelde automatische microfoonopname. Geen toegang tot afgeschermde telefoon-audiokanalen. */
public final class CallRecorderService extends Service {
    private static final int NOTIFICATION = 4701;
    static final String CHANNEL = "call-recording";
    static volatile boolean busy, recording, watchingCall;
    static volatile long autoSession;
    private static volatile long since;
    private static volatile boolean heard;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaRecorder recorder;
    private File partial, output;
    private PowerManager.WakeLock wakeLock;
    private TelephonyManager phone;
    private PhoneStateListener phoneListener;
    private boolean wasCall, finishing;

    static long elapsed() { return recording ? Math.max(0, SystemClock.elapsedRealtime() - since) : 0; }
    static boolean silent() { return recording && !heard && elapsed() > 5000; }
    static synchronized boolean reserve() { if (busy) return false; busy = true; autoSession = 0; return true; }
    static synchronized boolean reserveAutomatic(long session) { if (!reserve()) return false; autoSession = session; return true; }
    static synchronized void cancelAutomatic(long session) {
        if (autoSession == session && !recording) { busy = false; autoSession = 0; }
    }

    private final Runnable meter = new Meter(this);
    private static final class Meter implements Runnable {
        final CallRecorderService owner;
        Meter(CallRecorderService service) { owner = service; }
        @Override public void run() {
            if (!recording || owner.recorder == null) return;
            try {
                if (owner.recorder.getMaxAmplitude() > 100) heard = true;
                owner.handler.postDelayed(this, 1000);
            } catch (Exception e) { owner.finish("De microfoon is niet meer beschikbaar. Controleer de opgeslagen opname."); }
        }
    }
    private static final class CallsListener extends PhoneStateListener {
        final CallRecorderService owner;
        CallsListener(CallRecorderService service) { owner = service; }
        @Override public void onCallStateChanged(int state, String number) {
            if (state == TelephonyManager.CALL_STATE_OFFHOOK) owner.wasCall = true;
            else if (state == TelephonyManager.CALL_STATE_IDLE && owner.wasCall && recording) owner.finish("Het gesprek is beëindigd.");
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if ("stop".equals(intent.getAction())) { finish(null); return START_NOT_STICKY; }
        if (recorder != null) return START_NOT_STICKY;
        long requestedSession = intent.getLongExtra("autoSession", 0);
        if (requestedSession != 0 && (!AutoCallRecording.sessionActive(this, requestedSession) || !AutoCallRecording.ready(this))) {
            cancelAutomatic(requestedSession); stopSelf(startId); return START_NOT_STICKY;
        }
        autoSession = requestedSession;
        busy = true; finishing = false; wasCall = false; watchingCall = false;
        try {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) throw new Exception("Geef eerst microfoontoegang");
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = new NotificationChannel(CHANNEL, "Gespreksopname", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Zichtbare melding zolang de microfoonopname loopt, met een stopknop");
                getSystemService(NotificationManager.class).createNotificationChannel(channel);
            }
            Intent open = new Intent(this, MainActivity.class).putExtra("open", "recorder").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent content = PendingIntent.getActivity(this, NOTIFICATION, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            PendingIntent stop = PendingIntent.getService(this, NOTIFICATION, new Intent(this, CallRecorderService.class).setAction("stop"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
            if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
            Notification n = builder.setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Microfoonopname actief")
                    .setContentText("Rene’s Tools neemt geluid via de microfoon op").setOngoing(true).setOnlyAlertOnce(true)
                    .setWhen(System.currentTimeMillis()).setUsesChronometer(true).setContentIntent(content).addAction(android.R.drawable.ic_media_pause, "Stoppen en opslaan", stop).build();
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(NOTIFICATION, n);
            File folder = CallRecordings.dir(this);
            File[] leftovers = folder.listFiles((d, name) -> name.endsWith(".m4a.part"));
            if (leftovers != null) for (File file : leftovers) file.delete();
            output = new File(folder, "opname-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().replace("-", "") + ".m4a");
            partial = new File(folder, output.getName() + ".part");
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioChannels(1); recorder.setAudioSamplingRate(44100); recorder.setAudioEncodingBitRate(96000);
            recorder.setOutputFile(partial.getPath());
            recorder.setMaxDuration(2 * 60 * 60 * 1000);
            recorder.setOnErrorListener((r, what, extra) -> { if (r == recorder) finish("Android heeft de opname gestopt. Controleer de opgeslagen opname."); });
            recorder.setOnInfoListener((r, what, extra) -> { if (r == recorder && what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) finish("De maximale opnameduur van 2 uur is bereikt."); });
            recorder.prepare();
            if (requestedSession != 0 && !AutoCallRecording.sessionActive(this, requestedSession)) throw new Exception("Oproep beëindigd");
            recorder.start();
            AutoCallRecording.cancelNotice(this);
            since = SystemClock.elapsedRealtime(); heard = false; recording = true;
            CallRecordings.prefs(this).edit().putBoolean("active", true).putString("message", "").apply();
            wakeLock = ((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "renestools:recording");
            wakeLock.acquire(2 * 60 * 60 * 1000L + 10000);
            handler.post(meter);
            watchCall();
        } catch (Exception e) {
            if (recorder != null) { try { recorder.release(); } catch (Exception ignored) { } recorder = null; }
            if (partial != null) partial.delete();
            recording = false; busy = false; autoSession = 0;
            String message = requestedSession == 0 ? "Opnemen starten lukt niet. Android kan de microfoon tijdens een telefoongesprek blokkeren; gebruik dan opname in de telefoon-app." :
                    AutoCallRecording.sessionActive(this, requestedSession) ? "Android blokkeert automatisch opnemen of de microfoon. Open de app om handmatig te starten." : "Automatische opname geannuleerd of het gesprek is al beëindigd.";
            CallRecordings.prefs(this).edit().putBoolean("active", false).putString("message", message).apply();
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
            if (requestedSession != 0) AutoCallRecording.blocked(this, requestedSession, message);
        }
        return START_NOT_STICKY;
    }
    private void watchCall() {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return;
        try {
            phone = getSystemService(TelephonyManager.class);
            if (phone == null) return;
            wasCall = phone.getCallState() == TelephonyManager.CALL_STATE_OFFHOOK;
            phoneListener = new CallsListener(this);
            phone.listen(phoneListener, PhoneStateListener.LISTEN_CALL_STATE);
            watchingCall = true;
        } catch (Exception ignored) { }
    }
    private void finish(String reason) {
        if (finishing) return;
        finishing = true;
        handler.removeCallbacks(meter);
        boolean wasRecording = recording;
        recording = false;
        String message = "";
        try {
            if (recorder != null && wasRecording) {
                recorder.setOnErrorListener(null); recorder.setOnInfoListener(null);
                // Bij de duurgrens kan Android al gestopt zijn. Bewaar ook dan een
                // geldig audiobestand, maar presenteer geen kapot bestand als opname.
                try { recorder.stop(); } catch (Exception ignored) { }
                recorder.release(); recorder = null;
                if (partial == null || partial.length() < 1000 || Transcribe.durationMs(this, partial) <= 0 || !partial.renameTo(output)) throw new Exception("Geen geldige opname");
                message = "Opname opgeslagen." + (!heard ? " Geen duidelijk microfoongeluid gemeten: luister de opname terug." : "") + (reason == null ? "" : " " + reason);
            }
        } catch (Exception e) { message = "Opname niet opgeslagen: te kort of de microfoon werd geblokkeerd."; }
        finally {
            if (recorder != null) { try { recorder.release(); } catch (Exception ignored) { } recorder = null; }
            if (partial != null && partial.exists()) partial.delete();
            if (phone != null && phoneListener != null) try { phone.listen(phoneListener, PhoneStateListener.LISTEN_NONE); } catch (Exception ignored) { }
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            busy = false; watchingCall = false; autoSession = 0;
            CallRecordings.prefs(this).edit().remove("autoSession").apply();
            AutoCallRecording.cancelNotice(this);
            CallRecordings.prefs(this).edit().putBoolean("active", false).putString("message", message).apply();
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        }
    }
    @Override public void onDestroy() { if (recorder != null) finish("Opnameservice gestopt."); busy = false; super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
