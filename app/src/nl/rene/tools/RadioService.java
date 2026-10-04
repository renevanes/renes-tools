package nl.rene.tools;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONObject;

/**
 * Speelt een radiozender af als voorgrondservice (mediaPlayback): doorspelen met het scherm uit,
 * bediening in de melding en op het vergrendelscherm (MediaSession), pauzeren als de koptelefoon
 * eruit gaat, onderbreken tijdens een gesprek en daarna vanzelf verder (audiofocus), opnieuw
 * verbinden bij netwerkhaperingen en een slaaptimer.
 *
 * Bediening vanuit de app gaat via send(); knoppen van de MediaSession en de koptelefoon worden
 * direct in de lopende service afgehandeld (geen startService vanaf de achtergrond).
 */
public class RadioService extends Service implements AudioManager.OnAudioFocusChangeListener {

    static final String CHANNEL = "radio";
    static final int NOTIF_ID = 4401;
    static final String PLAY = "play", PAUSE = "pause", RESUME = "resume", STOP = "stop", SLEEP = "sleep";

    // Toestand voor de interface
    static volatile String station = null;     // JSON van de zender
    static volatile String status = "stopped"; // connecting, playing, paused, interrupted, error, stopped
    static volatile String title = "";         // nu op de radio (ICY)
    static volatile String error = null;
    static volatile long sleepAt = 0;

    /** De lopende service (alleen op de hoofdthread gebruiken). */
    static RadioService inst;

    private MediaPlayer player;
    private MediaSession session;
    private AudioManager am;
    private AudioFocusRequest focusReq;
    private final Handler h = new Handler(Looper.getMainLooper());
    private NoisyReceiver noisy;
    private int retries = 0, lastStartId = 0;
    private boolean pausedByFocus = false, foreground = false;
    private Thread metaThread;
    private volatile boolean alive = true;

    /** Opdracht vanuit de app. Pauze/stop/slaaptimer starten de service nooit opnieuw. */
    static void send(Context c, String action, String extra) {
        RadioService s = inst;
        try {
            if (PLAY.equals(action) || RESUME.equals(action)) {
                Intent i = new Intent(c, RadioService.class).setAction(action);
                if (extra != null) i.putExtra("x", extra);
                c.startForegroundService(i);
            } else if (s != null) {
                s.h.post(() -> s.handle(action, extra));
            } else if (STOP.equals(action)) {
                status = "stopped"; title = ""; sleepAt = 0;
                ((NotificationManager) c.getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIF_ID);
            }
        } catch (Exception e) {
            error = "Radio starten lukt nu niet; open de app en probeer opnieuw";
            status = "error";
        }
    }

    static String stateJson() {
        try {
            JSONObject o = new JSONObject().put("status", "interrupted".equals(status) ? "paused" : status)
                    .put("title", title).put("sleepAt", sleepAt);
            if (station != null) o.put("station", new JSONObject(station));
            if (error != null) o.put("error", error);
            return o.toString();
        } catch (Exception e) { return "{\"status\":\"stopped\"}"; }
    }

    /** Pauzeert als de koptelefoon wordt losgekoppeld. */
    static final class NoisyReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            RadioService s = inst;
            if (s != null && AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction())) s.handle(PAUSE, null);
        }
    }

    /** Knoppen op het vergrendelscherm, de koptelefoon en in de melding (via de MediaSession). */
    static final class SessionCallback extends MediaSession.Callback {
        @Override public void onPlay() { RadioService s = inst; if (s != null) s.handle(RESUME, null); }
        @Override public void onPause() { RadioService s = inst; if (s != null) s.handle(PAUSE, null); }
        @Override public void onStop() { RadioService s = inst; if (s != null) s.handle(STOP, null); }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        session = new MediaSession(this, "RenesRadio");
        session.setCallback(new SessionCallback());
        noisy = new NoisyReceiver();
        IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisy, f);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        String a = intent == null ? null : intent.getAction();
        // Na startForegroundService moet altijd startForeground volgen, ook als er niets te doen is.
        if (PLAY.equals(a) || RESUME.equals(a)) startFg();
        handle(a, intent == null ? null : intent.getStringExtra("x"));
        return START_NOT_STICKY;
    }

    /** Verwerkt een opdracht in de lopende service (hoofdthread). */
    void handle(String a, String x) {
        if (PLAY.equals(a)) {
            if (x != null) { station = x; retries = 0; start(); } else if (station == null) stopAll();
        } else if (RESUME.equals(a)) {
            if (station != null) { if (!foreground) startFg(); retries = 0; start(); } else stopAll();
        } else if (PAUSE.equals(a)) {
            pausedByFocus = false;
            if (player == null && !"connecting".equals(status)) { if (!"paused".equals(status)) stopAll(); return; }
            pause();
        } else if (SLEEP.equals(a)) {
            int min = 0;
            try { min = Integer.parseInt(x); } catch (Exception ignored) { }
            h.removeCallbacks(sleepRun);
            sleepAt = min > 0 ? System.currentTimeMillis() + min * 60_000L : 0;
            if (min > 0) h.postDelayed(sleepRun, min * 60_000L);
            update();
        } else if (STOP.equals(a)) {
            stopAll();
        } else if (station == null || player == null) {
            stopAll(); // onbekende of lege opdracht zonder lopende radio
        }
    }

    private final Runnable sleepRun = () -> { sleepAt = 0; stopAll(); };

    private void startFg() {
        createChannel(this);
        try {
            Notification n = notif();
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTIF_ID, n);
            foreground = true;
        } catch (Exception e) {
            // Android staat een voorgrondservice nu niet toe (bijv. vanaf de achtergrond).
            foreground = false;
        }
    }

    private boolean requestFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusReq == null) focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setOnAudioFocusChangeListener(this, h).build();
            return am.requestAudioFocus(focusReq) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        return am.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) { if (focusReq != null) am.abandonAudioFocusRequest(focusReq); }
        else am.abandonAudioFocus(this);
    }

    @Override
    public void onAudioFocusChange(int change) {
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            pausedByFocus = false;
            if (player != null) pause();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            // Bijvoorbeeld een telefoongesprek: onderbreken maar op de voorgrond blijven, zodat we daarna verder kunnen.
            if ("playing".equals(status) || "connecting".equals(status)) { pausedByFocus = true; interrupt(); }
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            if (player != null) player.setVolume(0.3f, 0.3f);
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            if (player != null) player.setVolume(1f, 1f);
            if (pausedByFocus) { pausedByFocus = false; if (station != null) { retries = 0; start(); } }
        }
    }

    private void release() {
        if (player != null) { try { player.reset(); player.release(); } catch (Exception ignored) { } player = null; }
    }

    private void start() {
        release();
        alive = true;
        session.setActive(true);
        error = null;
        if (!requestFocus()) { error = "Geluid is nu in gebruik (bijvoorbeeld door een gesprek)"; status = "error"; update(); return; }
        String url;
        try { url = new JSONObject(station).optString("url"); } catch (Exception e) { url = ""; }
        if (url.isEmpty()) { error = "Geen stream-adres"; status = "error"; update(); return; }
        status = "connecting";
        title = "";
        update();
        try {
            MediaPlayer p = new MediaPlayer();
            player = p;
            p.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            p.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            java.util.Map<String, String> hdr = new java.util.HashMap<>();
            hdr.put("User-Agent", Radio.UA);
            p.setDataSource(this, android.net.Uri.parse(url), hdr);
            p.setOnPreparedListener(mp -> {
                if (mp != player) return;
                mp.start();
                status = "playing";
                retries = 0;
                update();
                startMeta();
            });
            p.setOnErrorListener((mp, what, extra) -> {
                if (mp != player) return true;
                onStreamError("Zender niet te bereiken (" + what + "/" + extra + ")");
                return true;
            });
            p.setOnCompletionListener(mp -> { if (mp == player) onStreamError("Stream gestopt"); });
            p.prepareAsync();
        } catch (Exception e) {
            onStreamError("Afspelen lukt niet: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    /** Netwerkhapering: een paar keer opnieuw proberen voordat we opgeven. */
    private void onStreamError(String msg) {
        release();
        if (station != null && retries < 3) {
            retries++;
            status = "connecting";
            update();
            h.postDelayed(() -> { if (alive && "connecting".equals(status) && station != null) start(); }, 3000L * retries);
            return;
        }
        status = "error";
        error = msg;
        update();
        detach();
    }

    /** Onderbreken voor een gesprek: speler weg, service blijft op de voorgrond. */
    private void interrupt() {
        release();
        status = "interrupted";
        update();
    }

    /** Pauze door de gebruiker: de melding blijft staan, de service mag weg. */
    private void pause() {
        release();
        if (station != null) status = "paused";
        update();
        detach();
    }

    private void detach() {
        if (foreground) { stopForeground(STOP_FOREGROUND_DETACH); foreground = false; }
    }

    private void stopAll() {
        alive = false;
        pausedByFocus = false;
        h.removeCallbacksAndMessages(null);
        release();
        abandonFocus();
        status = "stopped";
        title = "";
        sleepAt = 0;
        session.setActive(false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        foreground = false;
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIF_ID);
        stopSelf(lastStartId); // een PLAY die intussen binnenkwam, houdt de service in leven
    }

    /** Leest elke 20 seconden wat er nu speelt (als de zender dat meestuurt). */
    private void startMeta() {
        if (metaThread != null && metaThread.isAlive()) return;
        metaThread = new Thread(() -> {
            while (alive && ("playing".equals(status) || "connecting".equals(status))) {
                String url;
                try { url = new JSONObject(station).optString("url"); } catch (Exception e) { break; }
                String t = Radio.nowPlaying(url);
                if (alive && !t.equals(title)) { title = t; h.post(() -> { if (alive) update(); }); }
                try { Thread.sleep(20_000); } catch (InterruptedException e) { break; }
            }
        }, "radio-meta");
        metaThread.start();
    }

    private void update() {
        if (!alive || inst != this) return;
        String name = "Radio";
        try { name = new JSONObject(station).optString("name", "Radio"); } catch (Exception ignored) { }
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title.isEmpty() ? name : title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, name).build());
        int st = "playing".equals(status) ? PlaybackState.STATE_PLAYING : "connecting".equals(status) ? PlaybackState.STATE_BUFFERING
                : "paused".equals(status) || "interrupted".equals(status) ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_STOPPED;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_STOP | PlaybackState.ACTION_PLAY_PAUSE)
                .setState(st, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f).build());
        if (!"stopped".equals(status))
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, notif());
    }

    private PendingIntent action(String a, int code) {
        Intent i = new Intent(this, RadioService.class).setAction(a);
        if (RESUME.equals(a) && Build.VERSION.SDK_INT >= 26) return PendingIntent.getForegroundService(this, code, i, PendingIntent.FLAG_IMMUTABLE);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification notif() {
        String name = "Radio";
        try { name = new JSONObject(station).optString("name", "Radio"); } catch (Exception ignored) { }
        String text = "connecting".equals(status) ? "Verbinden…" : "error".equals(status) ? (error == null ? "Fout" : error)
                : "paused".equals(status) ? "Gepauzeerd" : "interrupted".equals(status) ? "Onderbroken, gaat zo verder"
                : title.isEmpty() ? "Live" : title;
        if (sleepAt > 0) text += " · stopt om " + new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date(sleepAt));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        boolean playing = "playing".equals(status) || "connecting".equals(status) || "interrupted".equals(status);
        Intent open = new Intent(this, MainActivity.class).putExtra("open", "radio").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        b.setSmallIcon(R.drawable.ic_radio).setContentTitle(name).setContentText(text)
                .setContentIntent(PendingIntent.getActivity(this, 22, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setDeleteIntent(action(STOP, 33))
                .setOngoing(playing).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(null, playing ? "Pauze" : "Afspelen", action(playing ? PAUSE : RESUME, 31)).build())
                .addAction(new Notification.Action.Builder(null, "Stoppen", action(STOP, 32)).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1));
        return b.build();
    }

    static void createChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Radio", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ((NotificationManager) c.getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
    }

    @Override
    public void onDestroy() {
        alive = false;
        if (inst == this) inst = null;
        h.removeCallbacksAndMessages(null);
        release();
        abandonFocus();
        try { unregisterReceiver(noisy); } catch (Exception ignored) { }
        session.release();
        if ("interrupted".equals(status) || "connecting".equals(status) || "playing".equals(status)) status = "paused";
        if (!"error".equals(status) && !"paused".equals(status)) status = "stopped";
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
