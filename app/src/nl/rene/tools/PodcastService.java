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
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONObject;

/**
 * Speelt een podcast-aflevering af (stream van de maker): doorspelen met het scherm uit, bediening in de melding,
 * op het vergrendelscherm en met de koptelefoon (15 s terug / 30 s vooruit), snelheid, onthouden waar je was,
 * pauzeren bij een gesprek of als de koptelefoon eruit gaat, en een slaaptimer die zacht uitfadet.
 */
public class PodcastService extends Service implements AudioManager.OnAudioFocusChangeListener {

    static final String CHANNEL = "podcast";
    static final int NOTIF_ID = 4501;
    static final String PLAY = "play", PAUSE = "pause", RESUME = "resume", STOP = "stop", SEEK = "seek", SKIP = "skip", SPEED = "speed",
            SLEEP = "sleep", SLEEP_ADD = "sleepAdd", YIELD = "yield", HANDOFF = "handoff";
    static final int BACK_S = 15, FWD_S = 30;
    static final long FADE_MS = 30_000;
    static final long ACTIONS = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
            | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_REWIND | PlaybackState.ACTION_FAST_FORWARD
            | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS;

    // Toestand voor de interface
    static volatile String status = "stopped"; // connecting, playing, paused, ended, error, stopped
    static volatile String ep = null, pod = null; // JSON van de aflevering en de podcast
    static volatile String error = null;
    static volatile long pos = 0, dur = 0;        // ms
    static volatile float speed = 1f;
    static volatile long sleepAt = 0;             // 0 = geen slaaptimer
    static volatile boolean sleepEnd = false;     // stoppen aan het einde van de aflevering
    static volatile PodcastService inst;
    /** Stoppen gevraagd terwijl de service nog moest starten: dan niet alsnog gaan spelen. */
    static volatile boolean stopPending;

    private MediaPlayer player;
    private MediaSession session;
    private AudioManager am;
    private AudioFocusRequest focusReq;
    private final Handler h = new Handler(Looper.getMainLooper());
    private NoisyReceiver noisy;
    private android.net.wifi.WifiManager.WifiLock wifi;
    private boolean foreground, resumeOnFocus, prepared;
    private int lastStartId, retries;
    private long saveAt, shownAt;
    private Bitmap art;
    private String artFor, metaSig;

    /** Opdracht vanuit de app. Afspelen start de service; de rest gaat naar de lopende service. */
    static void send(Context c, String action, String extra) {
        PodcastService s = inst;
        try {
            if (PLAY.equals(action) || (RESUME.equals(action) && s == null)) {
                stopPending = false;
                Intent i = new Intent(c, PodcastService.class).setAction(action);
                if (extra != null) i.putExtra("x", extra);
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
            } else if (s != null) {
                s.h.post(() -> s.handle(action, extra));
            } else if (STOP.equals(action)) {
                stopPending = true; status = "stopped"; ep = null; pod = null;
                Podcasts.prefs(c).edit().remove("last").apply();
            } else if (SPEED.equals(action)) {
                try { speed = clampSpeed(Float.parseFloat(extra)); Podcasts.prefs(c).edit().putFloat("speed", speed).apply(); } catch (Exception ignored) { }
            }
        } catch (Exception e) {
            error = "Afspelen starten lukt nu niet; probeer het opnieuw";
            status = "error";
        }
    }

    static float clampSpeed(float v) { return Math.max(0.5f, Math.min(3f, v)); }

    static String stateJson() {
        try {
            JSONObject o = new JSONObject().put("status", status).put("pos", pos).put("dur", dur).put("speed", speed)
                    .put("sleepAt", sleepAt).put("sleepEnd", sleepEnd);
            if (ep != null) o.put("ep", new JSONObject(ep));
            if (pod != null) o.put("pod", new JSONObject(pod));
            if (error != null) o.put("error", error);
            return o.toString();
        } catch (Exception e) { return "{\"status\":\"stopped\"}"; }
    }

    static final class NoisyReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            PodcastService s = inst;
            if (s != null && AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction())) s.handle(PAUSE, null);
        }
    }

    /** Vergrendelscherm, koptelefoon, auto: vooruit/volgende = 30 s verder, terug/vorige = 15 s terug. */
    static final class SessionCallback extends MediaSession.Callback {
        @Override public void onPlay() { PodcastService s = inst; if (s != null) s.handle(RESUME, null); }
        @Override public void onPause() { PodcastService s = inst; if (s != null) s.handle(PAUSE, null); }
        @Override public void onStop() { PodcastService s = inst; if (s != null) s.handle(STOP, null); }
        @Override public void onSeekTo(long p) { PodcastService s = inst; if (s != null) s.handle(SEEK, String.valueOf(p)); }
        @Override public void onFastForward() { PodcastService s = inst; if (s != null) s.handle(SKIP, String.valueOf(FWD_S)); }
        @Override public void onSkipToNext() { onFastForward(); }
        @Override public void onRewind() { PodcastService s = inst; if (s != null) s.handle(SKIP, String.valueOf(-BACK_S)); }
        @Override public void onSkipToPrevious() { onRewind(); }
        @Override public void onCustomAction(String action, android.os.Bundle extras) {
            PodcastService s = inst;
            if (s == null) return;
            if (SLEEP_ADD.equals(action)) s.handle(SLEEP_ADD, "10");
            else if (STOP.equals(action)) s.handle(STOP, null);
        }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        speed = clampSpeed(Podcasts.prefs(this).getFloat("speed", 1f));
        session = new MediaSession(this, "RenesPodcast");
        session.setCallback(new SessionCallback());
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        noisy = new NoisyReceiver();
        IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, f, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(noisy, f);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        String a = intent == null ? null : intent.getAction();
        String x = intent == null ? null : intent.getStringExtra("x");
        boolean start = PLAY.equals(a) || RESUME.equals(a);
        if (start) startFg(); // na startForegroundService verplicht (alleen afspelen gaat zo)
        if (start && stopPending) { stopPending = false; stopAll(); return START_NOT_STICKY; }
        if (a == null) { if (player == null) stopAll(); return START_NOT_STICKY; }
        handle(a, x);
        // Een knop uit een oude melding terwijl er niets speelt: niet als lege voorgrondservice blijven hangen
        if (!start && player == null && !foreground && !"paused".equals(status)) stopSelfResult(startId);
        return START_NOT_STICKY;
    }

    void handle(String a, String x) {
        if (inst != this) return; // intussen afgesloten
        switch (a) {
            case PLAY: {
                try {
                    JSONObject o = new JSONObject(x), e = o.getJSONObject("ep");
                    if (!Podcasts.httpUrl(e.optString("url"))) throw new Exception("Geen geldig adres");
                    saveNow(); // de vorige aflevering
                    ep = e.toString(); pod = o.optJSONObject("pod") == null ? "{}" : o.getJSONObject("pod").toString();
                    // Vanaf de bewaarde plek (die is actueler dan wat het scherm nog weet), tenzij al beluisterd of "vanaf het begin"
                    JSONObject saved = Podcasts.progressOf(this, e.optString("key"));
                    long start = o.optBoolean("fromStart") || saved == null || saved.optBoolean("done") ? 0 : Math.max(0, saved.optLong("p"));
                    retries = 0;
                    Podcasts.touchRecent(this, e, new JSONObject(pod));
                    final String qk = e.optString("key");
                    final Context app = getApplicationContext();
                    Podcasts.IO.execute(() -> Podcasts.queueRemove(app, qk)); // speelt nu: niet meer in Hierna
                    Podcasts.prefs(this).edit().putString("last", x).apply();
                    begin(start);
                } catch (Exception e) { error = "Deze aflevering kan niet worden afgespeeld"; status = "error"; releaseAutoWake(); releaseWifi(); abandonFocus(); update(); detach(); }
                break;
            }
            case RESUME:
                if (player != null && prepared) { if (!"playing".equals(status)) play(); }
                else if (ep != null) begin(pos);
                else {
                    // Na herstarten van de app: de laatste aflevering
                    String last = Podcasts.prefs(this).getString("last", null);
                    if (last == null) { stopAll(); break; }
                    try {
                        JSONObject o = new JSONObject(last), e = o.getJSONObject("ep");
                        JSONObject p = Podcasts.progressOf(this, e.optString("key"));
                        ep = e.toString(); pod = o.optJSONObject("pod") == null ? "{}" : o.getJSONObject("pod").toString();
                        begin(p == null ? 0 : p.optLong("p"));
                    } catch (Exception e) { stopAll(); }
                }
                break;
            case PAUSE: pause(false); break;
            case HANDOFF: // de Chromecast neemt het over: plek bewaren, dan helemaal los (de Chromecast bewaart verder)
                if ("playing".equals(status) || "connecting".equals(status)) pause(false); else saveNow();
                ep = null; pod = null; status = "stopped";
                // valt door naar YIELD: melding weg en stoppen
            case YIELD: // de radio neemt het over: pauzeren (plek bewaard) en de melding weg
                if ("playing".equals(status) || "connecting".equals(status)) pause(false);
                h.removeCallbacks(idleRun); h.removeCallbacks(sleepTick);
                sleepAt = 0; sleepEnd = false;
                abandonFocus();
                stopForeground(STOP_FOREGROUND_REMOVE); foreground = false;
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIF_ID);
                stopSelfResult(lastStartId);
                break;
            case STOP:
                saveNow();
                ep = null; pod = null; // uit beeld; in Verder luisteren staat hij nog
                Podcasts.prefs(this).edit().remove("last").apply();
                stopAll();
                break;
            case SEEK: try { seekTo(Long.parseLong(x)); } catch (Exception ignored) { } break;
            case SKIP: try { seekTo(curPos() + Integer.parseInt(x) * 1000L); } catch (Exception ignored) { } break;
            case SPEED:
                try {
                    speed = clampSpeed(Float.parseFloat(x));
                    Podcasts.prefs(this).edit().putFloat("speed", speed).apply();
                    if ("playing".equals(status)) applySpeed();
                    update();
                } catch (Exception ignored) { }
                break;
            case SLEEP: {
                // minuten > 0: na zoveel minuten; -1: aan het einde van de aflevering; 0: uit
                int m = 0;
                try { m = Integer.parseInt(x); } catch (Exception ignored) { }
                h.removeCallbacks(sleepTick);
                sleepEnd = m == -1;
                sleepAt = m > 0 ? System.currentTimeMillis() + m * 60_000L : 0;
                setVolume(1f);
                if (sleepAt > 0) h.post(sleepTick);
                update();
                break;
            }
            case SLEEP_ADD: {
                int m = 0;
                try { m = Integer.parseInt(x); } catch (Exception ignored) { }
                if (m <= 0) break;
                long base = sleepAt > System.currentTimeMillis() ? sleepAt : System.currentTimeMillis();
                sleepAt = base + m * 60_000L; sleepEnd = false;
                setVolume(1f);
                h.removeCallbacks(sleepTick); h.post(sleepTick);
                update();
                break;
            }
            default: break;
        }
    }

    // ---------- afspelen ----------

    private void begin(long startMs) {
        h.removeCallbacks(idleRun);
        // Vanaf nu is dit wat er speelt: de radio stopt; een slaaptimer daarvan loopt hier door
        long other = Player.takeOver(this, Player.PODCAST);
        if (other > 0 && sleepAt == 0 && !sleepEnd) { sleepAt = other; h.removeCallbacks(sleepTick); h.post(sleepTick); }
        release();
        prepared = false;
        error = null;
        JSONObject e;
        try { e = new JSONObject(ep); } catch (Exception x) { stopAll(); return; }
        pos = startMs;
        dur = e.optLong("dur") * 1000L;
        if (!foreground) startFg(); // eerst voorgrond: Android 15+ geeft anders geen audiofocus vanaf de achtergrond
        if (!requestFocus()) { error = "Geluid is nu in gebruik (bijvoorbeeld door een gesprek)"; status = "error"; releaseAutoWake(); releaseWifi(); update(); detach(); return; }
        status = "connecting";
        session.setActive(true);
        update();
        loadArt(e.optString("image").isEmpty() ? optPod("image") : e.optString("image"));
        try {
            MediaPlayer p = new MediaPlayer();
            player = p;
            p.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            p.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            java.util.Map<String, String> hdr = new java.util.HashMap<>();
            hdr.put("User-Agent", Radio.UA);
            p.setDataSource(this, android.net.Uri.parse(e.optString("url")), hdr);
            p.setOnPreparedListener(mp -> {
                if (mp != player) return;
                prepared = true;
                long d = mp.getDuration();
                if (d > 0) dur = d;
                long at = pos; // ook als er tijdens het laden geschoven is
                if (at > 5000 && (dur <= 0 || at < dur - 5000)) mp.seekTo((int) at);
                play();
            });
            p.setOnCompletionListener(mp -> { if (mp == player) onEnded(); });
            p.setOnErrorListener((mp, what, extra) -> {
                if (mp != player) return true;
                onError("Aflevering niet te bereiken (" + what + "/" + extra + ")");
                return true;
            });
            p.prepareAsync();
        } catch (Exception x) {
            onError("Afspelen lukt niet: " + (x.getMessage() == null ? x.getClass().getSimpleName() : x.getMessage()));
        }
    }

    private void play() {
        MediaPlayer p = player;
        if (p == null || !prepared) return;
        if (!foreground) startFg(); // eerst voorgrond (zie begin)
        if (!requestFocus()) { error = "Geluid is nu in gebruik"; pause(false); return; }
        h.removeCallbacks(idleRun);
        try { p.start(); applySpeed(); } catch (Exception e) { onError("Afspelen lukt niet"); return; }
        releaseAutoWake(); // de speler houdt de telefoon nu zelf wakker
        if (sleepAt == 0) setVolume(1f);
        resumeOnFocus = false;
        status = "playing";
        retries = 0;
        holdWifi();
        h.removeCallbacks(tickRun); h.post(tickRun);
        if (sleepAt > 0) { h.removeCallbacks(sleepTick); h.post(sleepTick); }
        update();
    }

    private void applySpeed() {
        MediaPlayer p = player;
        if (p == null || Build.VERSION.SDK_INT < 23) return;
        try { p.setPlaybackParams(p.getPlaybackParams().setSpeed(speed)); } catch (Exception ignored) { }
    }

    private void setVolume(float v) { MediaPlayer p = player; if (p != null) try { p.setVolume(v, v); } catch (Exception ignored) { } }

    /** byFocus = door een gesprek of ander geluid (dan straks vanzelf verder). */
    private void pause(boolean byFocus) {
        MediaPlayer p = player;
        if (p != null && prepared && "playing".equals(status)) { try { p.pause(); } catch (Exception ignored) { } }
        if (p == null && !"connecting".equals(status) && ep == null) { stopAll(); return; }
        pos = curPos();
        resumeOnFocus = byFocus;
        if ("connecting".equals(status) && !prepared) { release(); prepared = false; }
        status = "paused";
        saveNow();
        releaseWifi();
        h.removeCallbacks(tickRun);
        update();
        if (!byFocus) {
            abandonFocus();
            // Nog een tijdje op de voorgrond blijven: dan blijven de knoppen op het vergrendelscherm werken
            h.removeCallbacks(idleRun); h.postDelayed(idleRun, PAUSE_KEEP_MS);
        }
    }

    /** Lang genoeg gepauzeerd: de voorgrond loslaten en stoppen (verder luisteren kan in de app). */
    private static final long PAUSE_KEEP_MS = 15 * 60_000L;
    private final Runnable idleRun = this::idle;

    private void idle() {
        if (!"paused".equals(status) || resumeOnFocus) return;
        detach();
        stopSelfResult(lastStartId);
    }

    private void seekTo(long ms) {
        long d = dur > 0 ? dur : Long.MAX_VALUE;
        ms = Math.max(0, Math.min(ms, d - 1000));
        pos = ms;
        MediaPlayer p = player;
        if (p != null && prepared) { try { p.seekTo((int) ms); } catch (Exception ignored) { } }
        else if (ep != null && !"connecting".equals(status)) { saveNow(); }
        update();
    }

    private long curPos() {
        MediaPlayer p = player;
        if (p != null && prepared) { try { return p.getCurrentPosition(); } catch (Exception ignored) { } }
        return pos;
    }

    private void onEnded() {
        try { JSONObject e = new JSONObject(ep); Podcasts.saveProgress(this, e.optString("key"), 0, dur, true); } catch (Exception ignored) { }
        pos = 0;
        release(); prepared = false;
        status = "ended";
        h.removeCallbacks(tickRun);
        if (!sleepEnd) {
            // Hierna: meteen de volgende (een slaaptimer op tijd loopt gewoon door). Het bestand lezen gebeurt op
            // de achtergrond; zolang houdt een korte wakelock de telefoon wakker (het scherm staat vaak uit).
            holdAutoWake();
            final Context app = getApplicationContext();
            Podcasts.IO.execute(() -> {
                final JSONObject nx = Podcasts.queuePop(app);
                h.post(() -> {
                    if (inst != this || !"ended".equals(status)) { releaseAutoWake(); return; } // intussen iets anders gekozen
                    if (nx != null) handle(PLAY, nx.toString()); // de wakelock gaat los zodra het speelt (of mislukt)
                    else { releaseAutoWake(); endedDone(); }
                });
            });
            return;
        }
        endedDone();
    }

    /** Afgelopen en er komt niets meer: loslaten (bij een slaaptimer ook de melding weg). */
    private void endedDone() {
        boolean wasSleep = sleepEnd || sleepAt > 0;
        sleepEnd = false; sleepAt = 0;
        h.removeCallbacks(sleepTick);
        releaseWifi();
        abandonFocus();
        update();
        detach();
        if (wasSleep) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIF_ID); }
        stopSelfResult(lastStartId);
    }

    private PowerManager.WakeLock autoWl;

    private void holdAutoWake() {
        try {
            if (autoWl == null) { autoWl = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "renestools:podcast-next"); autoWl.setReferenceCounted(false); }
            autoWl.acquire(60_000L);
        } catch (Exception ignored) { }
    }

    private void releaseAutoWake() { try { if (autoWl != null && autoWl.isHeld()) autoWl.release(); } catch (Exception ignored) { } }

    /** Netwerkhapering: één keer opnieuw op dezelfde plek, daarna melden. */
    private void onError(String msg) {
        long at = curPos();
        release(); prepared = false;
        if ("paused".equals(status)) { // gepauzeerd: niet vanzelf weer gaan spelen; bij Afspelen opnieuw laden
            pos = at; saveNow(); update();
            return;
        }
        if (retries < 2 && ep != null) {
            retries++;
            status = "connecting";
            update();
            final long back = at;
            h.postDelayed(() -> { if ("connecting".equals(status) && player == null) begin(back); }, 2500L * retries);
            return;
        }
        pos = at;
        saveNow();
        releaseAutoWake();
        status = "error";
        error = msg;
        releaseWifi();
        abandonFocus();
        update();
        detach();
    }

    private final Runnable tickRun = this::tick;

    /** Elke seconde de plek bijwerken; elke 10 seconden bewaren. */
    private void tick() {
        if (!"playing".equals(status)) return;
        pos = curPos();
        long now = SystemClock.elapsedRealtime();
        if (now - saveAt > 10_000) { saveAt = now; saveNow(); }
        if (now - shownAt > 30_000) { shownAt = now; Player.changed(this); } // voortgang in de widget
        h.postDelayed(tickRun, 1000);
    }

    /** Slaaptimer: de laatste 30 seconden zachter, dan pauzeren (niet stoppen: morgen verder waar je was). */
    private final Runnable sleepTick = this::sleepTick;

    private void sleepTick() {
        if (sleepAt <= 0) return;
        long left = sleepAt - System.currentTimeMillis();
        if (left <= 0) {
            sleepAt = 0;
            pause(false);
            h.removeCallbacks(idleRun);
            setVolume(1f);
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIF_ID);
            stopSelfResult(lastStartId);
            return;
        }
        if ("playing".equals(status)) setVolume(left < FADE_MS ? Math.max(0.05f, left / (float) FADE_MS) : 1f);
        h.postDelayed(sleepTick, left < FADE_MS + 1000 ? 500 : Math.min(30_000, left - FADE_MS));
    }

    private void saveNow() {
        if (ep == null || "ended".equals(status)) return; // afgelopen: de markering "beluisterd" niet overschrijven
        try {
            JSONObject e = new JSONObject(ep);
            long p = curPos();
            boolean done = dur > 0 && p > dur - 30_000 && "ended".equals(status);
            Podcasts.saveProgress(this, e.optString("key"), p, dur, done);
        } catch (Exception ignored) { }
    }

    private void release() {
        if (player != null) { try { player.reset(); player.release(); } catch (Exception ignored) { } player = null; }
    }

    // ---------- audiofocus ----------

    private boolean requestFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusReq == null) focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setWillPauseWhenDucked(true) // gesproken woord: liever even pauzeren dan zachter
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
        if (change == AudioManager.AUDIOFOCUS_LOSS) { if ("playing".equals(status) || "connecting".equals(status) || resumeOnFocus) pause(false); }
        else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            if ("playing".equals(status)) pause(true);
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            if (resumeOnFocus && "paused".equals(status)) { resumeOnFocus = false; if (player != null && prepared) play(); else begin(pos); }
        }
    }

    private void holdWifi() {
        try {
            if (wifi == null) {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wm != null) { wifi = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "renestools:podcast"); wifi.setReferenceCounted(false); }
            }
            if (wifi != null && !wifi.isHeld()) wifi.acquire();
        } catch (Exception ignored) { }
    }

    private void releaseWifi() { try { if (wifi != null && wifi.isHeld()) wifi.release(); } catch (Exception ignored) { } }

    // ---------- melding en mediasessie ----------

    private String optPod(String k) { try { return new JSONObject(pod).optString(k); } catch (Exception e) { return ""; } }
    private String optEp(String k) { try { return new JSONObject(ep).optString(k); } catch (Exception e) { return ""; } }

    private void startFg() {
        createChannel(this);
        try {
            Notification n = notif();
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTIF_ID, n);
            foreground = true;
        } catch (Exception e) { foreground = false; }
    }

    private void detach() { if (foreground) { stopForeground(STOP_FOREGROUND_DETACH); foreground = false; } }

    private void update() {
        if (inst != this) return;
        Player.changed(this); // widget en zwevend venster
        String title = optEp("title"), podTitle = optPod("title");
        String sig = title + "|" + podTitle + "|" + dur + "|" + System.identityHashCode(art);
        if (!sig.equals(metaSig)) { // alleen bij verandering (het hoesje gaat elke keer mee naar andere processen)
            metaSig = sig;
            MediaMetadata.Builder mb = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title.isEmpty() ? "Podcast" : title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, podTitle).putString(MediaMetadata.METADATA_KEY_ALBUM, podTitle);
            if (dur > 0) mb.putLong(MediaMetadata.METADATA_KEY_DURATION, dur);
            if (art != null) mb.putBitmap(MediaMetadata.METADATA_KEY_ART, art);
            session.setMetadata(mb.build());
        }
        int st = "playing".equals(status) ? PlaybackState.STATE_PLAYING : "connecting".equals(status) ? PlaybackState.STATE_BUFFERING
                : "paused".equals(status) ? PlaybackState.STATE_PAUSED : "error".equals(status) ? PlaybackState.STATE_ERROR : PlaybackState.STATE_STOPPED;
        // Android 13+ bouwt de knoppen in de melding uit de mediasessie: extra knoppen als eigen acties
        PlaybackState.Builder pb = new PlaybackState.Builder().setActions(ACTIONS)
                .setState(st, curPos(), "playing".equals(status) ? speed : 0f, SystemClock.elapsedRealtime());
        if (sleepAt > 0) pb.addCustomAction(new PlaybackState.CustomAction.Builder(SLEEP_ADD, "+10 min slaaptimer", R.drawable.ic_bedtime).build());
        pb.addCustomAction(new PlaybackState.CustomAction.Builder(STOP, "Stoppen", R.drawable.ic_stop).build());
        session.setPlaybackState(pb.build());
        if (!"stopped".equals(status)) ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, notif());
    }

    private PendingIntent action(String a, String x, int code) {
        Intent i = new Intent(this, PodcastService.class).setAction(a);
        if (x != null) i.putExtra("x", x);
        if (RESUME.equals(a) && Build.VERSION.SDK_INT >= 26) return PendingIntent.getForegroundService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600 ? (s / 3600) + ":" + String.format(java.util.Locale.US, "%02d:%02d", s / 60 % 60, s % 60) : (s / 60) + ":" + String.format(java.util.Locale.US, "%02d", s % 60);
    }

    private Notification notif() {
        String title = optEp("title"), podTitle = optPod("title");
        String text = "connecting".equals(status) ? "Laden…" : "error".equals(status) ? (error == null ? "Fout" : error)
                : "ended".equals(status) ? "Afgelopen" : podTitle;
        if (sleepAt > 0) text += " · stopt om " + new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date(sleepAt));
        else if (sleepEnd) text += " · stopt na deze aflevering";
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, NotifCenter.ch(this, CHANNEL)) : new Notification.Builder(this);
        boolean playing = "playing".equals(status) || "connecting".equals(status);
        Intent open = new Intent(this, MainActivity.class).putExtra("open", "podcasts").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        b.setSmallIcon(R.drawable.ic_podcast).setContentTitle(title.isEmpty() ? "Podcast" : title).setContentText(text)
                .setContentIntent(PendingIntent.getActivity(this, 45, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setDeleteIntent(action(STOP, null, 46))
                .setOngoing(playing).setOnlyAlertOnce(true).setShowWhen(false).setVisibility(Notification.VISIBILITY_PUBLIC);
        if (art != null) b.setLargeIcon(art);
        b.addAction(new Notification.Action.Builder(null, "−" + BACK_S + " s", action(SKIP, String.valueOf(-BACK_S), 47)).build())
                .addAction(new Notification.Action.Builder(null, playing ? "Pauze" : "Afspelen", action(playing ? PAUSE : RESUME, null, 48)).build())
                .addAction(new Notification.Action.Builder(null, "+" + FWD_S + " s", action(SKIP, String.valueOf(FWD_S), 49)).build());
        if (sleepAt > 0) b.addAction(new Notification.Action.Builder(null, "+10 min", action(SLEEP_ADD, "10", 50)).build());
        b.addAction(new Notification.Action.Builder(null, "Stoppen", action(STOP, null, 51)).build());
        b.setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    /** Hoesje op de achtergrond ophalen (voor de melding en het vergrendelscherm). */
    private void loadArt(String url) {
        if (url == null || !Podcasts.httpUrl(url) || url.equals(artFor)) return;
        artFor = url;
        final String u = url;
        new Thread(() -> {
            final Bitmap bm = Art.small(Art.load(getApplicationContext(), u), 320); // klein: gaat via de mediasessie naar andere processen
            h.post(() -> { if (u.equals(artFor) && inst == this) { art = bm; if (!"stopped".equals(status)) update(); } });
        }, "podcast-art").start();
    }

    static void createChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Podcasts", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ((NotificationManager) c.getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
    }

    private void stopAll() {
        saveNow();
        releaseAutoWake();
        h.removeCallbacksAndMessages(null);
        release(); prepared = false;
        releaseWifi();
        abandonFocus();
        if (!"ended".equals(status)) status = "stopped";
        sleepAt = 0; sleepEnd = false;
        try { session.setPlaybackState(new PlaybackState.Builder().setActions(ACTIONS).setState(PlaybackState.STATE_STOPPED, 0, 0f).build()); } catch (Exception ignored) { }
        session.setActive(false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        foreground = false;
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIF_ID);
        stopSelfResult(lastStartId);
    }

    @Override
    public void onDestroy() {
        saveNow();
        if (inst == this) inst = null;
        h.removeCallbacksAndMessages(null);
        releaseAutoWake();
        release();
        releaseWifi();
        abandonFocus();
        try { unregisterReceiver(noisy); } catch (Exception ignored) { }
        session.release();
        // Een melding met knoppen naar een gestopte service doet niets meer: weghalen (verder luisteren kan in de app)
        if (!foreground) ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIF_ID);
        if ("playing".equals(status) || "connecting".equals(status)) status = "paused";
        sleepAt = 0; sleepEnd = false;
        Player.changed(this);
        super.onDestroy();
    }
}
