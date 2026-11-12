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
    static final long ACTIONS = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
            | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_REWIND | PlaybackState.ACTION_FAST_FORWARD;
    // Bewust zonder "vorige/volgende": dan zet Android 13+ in de mediaspeler (meldingen, snelle instellingen,
    // vergrendelscherm) onze eigen knoppen 15 s terug en 30 s vooruit op die plekken. De vorige/volgende-knop van
    // een koptelefoon of auto werkt nog steeds (zie onMediaButtonEvent en onSkipToNext/onSkipToPrevious).
    static final String BACK = "back", FWD = "fwd";

    // Toestand voor de interface
    static volatile String status = "stopped"; // connecting, playing, paused, ended, error, stopped
    static volatile String ep = null, pod = null; // JSON van de aflevering en de podcast
    static volatile String error = null;
    static volatile long pos = 0, dur = 0;        // ms
    static volatile float speed = 1f;
    static volatile long sleepAt = 0;             // 0 = geen slaaptimer
    static volatile boolean buffering;            // speelt, maar wacht op gegevens (de interface toont "Bufferen…")
    static volatile boolean sleepEnd = false;     // stoppen aan het einde van de aflevering
    private long sleepFadeMs = SleepFade.MAX_MS;  // hoe lang het uitfaden duurt (hangt af van de lengte van de timer)
    static volatile PodcastService inst;

    /** Sleutel van de aflevering die nu speelt (of laadt), anders "". */
    static String playingKey() {
        if (inst == null || !("playing".equals(status) || "connecting".equals(status))) return "";
        try { return ep == null ? "" : new JSONObject(ep).optString("key"); } catch (Exception e) { return ""; }
    }
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
    // Stabiliteit (zie PodcastNa): sprongen samenvoegen, vastlopen herkennen en opnieuw verbinden
    private final PodcastNa.Seek sk = new PodcastNa.Seek();
    private final PodcastNa.Stall stall = new PodcastNa.Stall();
    private int beginSeq;
    private boolean local, endRetried;
    private final Runnable prepWatch = this::prepTimeout, seekWatch = this::seekTimeout;
    /** Netwerk (adres opzoeken) en een speler loslaten: nooit op de hoofdthread (dat kan seconden blokkeren). */
    static final java.util.concurrent.ExecutorService NET = java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "podcast-net"));
    /** Elke speler een eigen draad: blijft er één hangen in reset(), dan wachten de andere er niet op. */
    static final java.util.concurrent.ExecutorService RELEASER = java.util.concurrent.Executors.newCachedThreadPool(r -> new Thread(r, "podcast-release"));

    /** Wat er na deze aflevering gebeurt (instelling bij Podcasts → Instellingen; zie PodcastNa). */
    static String naMode(Context c) { return naMode(c, sleepAt > 0, sleepEnd); }

    static String naMode(Context c, boolean sleepTimer, boolean stopAfter) {
        android.content.SharedPreferences pr = Podcasts.prefs(c);
        return PodcastNa.effective(pr.getString("na", PodcastNa.STANDAARD), sleepTimer, stopAfter, pr.getBoolean("naSlaap", true));
    }

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
                    .put("sleepAt", sleepAt).put("sleepEnd", sleepEnd).put("buffering", buffering && "playing".equals(status));
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
        private static Runnable pendingTap; // alleen op de hoofdthread
        @Override public boolean onMediaButtonEvent(Intent i) {
            // Android stuurt vorige/volgende alleen door als die acties aan staan: hier zelf afhandelen
            android.view.KeyEvent k = i == null ? null : (android.view.KeyEvent) i.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            PodcastService s = inst;
            // Koptelefoon met één knop: twee keer drukken = 30 s vooruit (zoals Android dat alleen met "volgende" doet)
            if (s != null && k != null && (k.getKeyCode() == android.view.KeyEvent.KEYCODE_HEADSETHOOK || k.getKeyCode() == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
                if (k.getAction() == android.view.KeyEvent.ACTION_DOWN && k.getRepeatCount() == 0) {
                    if (pendingTap != null) { s.h.removeCallbacks(pendingTap); pendingTap = null; onFastForward(); }
                    else {
                        pendingTap = () -> {
                            pendingTap = null;
                            PodcastService t = inst;
                            if (t != null) t.handle("playing".equals(status) || "connecting".equals(status) ? PAUSE : RESUME, null);
                        };
                        s.h.postDelayed(pendingTap, android.view.ViewConfiguration.getDoubleTapTimeout());
                    }
                }
                return true;
            }
            if (k != null && (k.getKeyCode() == android.view.KeyEvent.KEYCODE_MEDIA_NEXT || k.getKeyCode() == android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)) {
                if (k.getAction() == android.view.KeyEvent.ACTION_DOWN && k.getRepeatCount() == 0) {
                    if (k.getKeyCode() == android.view.KeyEvent.KEYCODE_MEDIA_NEXT) onFastForward(); else onRewind();
                }
                return true;
            }
            return super.onMediaButtonEvent(i);
        }
        @Override public void onCustomAction(String action, android.os.Bundle extras) {
            PodcastService s = inst;
            if (s == null) return;
            if (BACK.equals(action)) onRewind();
            else if (FWD.equals(action)) onFastForward();
            else if (SLEEP_ADD.equals(action)) s.handle(SLEEP_ADD, "10");
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
                    retries = 0; stall.reconnects = 0; endRetried = false; // een nieuwe aflevering: opnieuw alle kansen
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
                stall.reconnects = 0; // Rene kiest zelf weer Afspelen (bijv. na "blijft haperen"): opnieuw proberen mag
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
                sleepFadeMs = SleepFade.length(m * 60_000L);
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
                sleepFadeMs = SleepFade.length(sleepAt - System.currentTimeMillis());
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
        if (other > 0 && sleepAt == 0 && !sleepEnd) { sleepAt = other; sleepFadeMs = SleepFade.length(other - System.currentTimeMillis()); h.removeCallbacks(sleepTick); h.post(sleepTick); }
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
        holdAutoWake(); // laden en opzoeken met het scherm uit: de telefoon wakker houden tot hij speelt
        stall.reset(SystemClock.elapsedRealtime());
        final int seq = ++beginSeq;
        final String url = e.optString("url"), file = PodcastDownloads.localPath(this, e.optString("key")); // gedownload: offline afspelen
        local = file != null;
        if (file != null) { startPlayer(file); return; }
        String quick = PodcastUrl.cached(url);
        if (quick != null) { startPlayer(quick); return; }
        // Het echte adres eerst opzoeken (doorverwijzingen van tellers; hooguit een paar seconden), dan rechtstreeks:
        // sneller springen en herverbinden
        NET.execute(() -> {
            if (seq != beginSeq) return; // intussen iets anders gekozen
            String u;
            try { u = PodcastUrl.resolve(url); } catch (Throwable t) { u = url; }
            final String src = u;
            h.post(() -> { if (seq == beginSeq && inst == this && "connecting".equals(status) && player == null) startPlayer(src); });
        });
    }

    private void startPlayer(String src) {
        try {
            MediaPlayer p = new MediaPlayer();
            player = p;
            prepared = false;
            p.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            p.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            java.util.Map<String, String> hdr = new java.util.HashMap<>();
            hdr.put("User-Agent", Radio.UA);
            if (src.startsWith("/")) p.setDataSource(src);
            else p.setDataSource(this, android.net.Uri.parse(src), hdr);
            p.setOnPreparedListener(mp -> {
                if (mp != player) return;
                h.removeCallbacks(prepWatch);
                prepared = true;
                long d = mp.getDuration();
                if (d > 0) dur = d;
                long at = pos; // ook als er tijdens het laden geschoven is
                if (at > 5000 && (dur <= 0 || at < dur - 5000) && sk.ask(at)) doSeek(mp, at);
                play();
            });
            p.setOnCompletionListener(mp -> {
                if (mp != player) return;
                long at = pos;
                // Verbinding afgebroken (Android meldt dat soms als "afgelopen"): één keer per aflevering opnieuw op die
                // plek. Niet bij een bestand op de telefoon, en niet als het herverbinden op is: dan gewoon de volgende.
                if (!local && !endRetried && stall.reconnects < PodcastNa.MAX_RECONNECT && PodcastNa.prematureEnd(at, dur)) { endRetried = true; reconnect(at, "afgebroken"); return; }
                onEnded();
            });
            p.setOnSeekCompleteListener(mp -> {
                if (mp != player) return;
                h.removeCallbacks(seekWatch);
                stall.reset(SystemClock.elapsedRealtime());
                long n = sk.done(); // nog een keer gedrukt intussen: alleen de laatste plek
                if (n >= 0) doSeek(mp, n); else update();
            });
            p.setOnInfoListener((mp, what, extra) -> {
                if (mp != player) return false;
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START && !buffering) { buffering = true; update(); }
                else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END && buffering) { buffering = false; update(); }
                return false;
            });
            p.setOnErrorListener((mp, what, extra) -> {
                if (mp == player && !local && PodcastUrl.cached(optEp("url")) != null) PodcastUrl.forget(optEp("url")); // opgezocht adres verlopen?
                if (mp != player) return true;
                onError("Aflevering niet te bereiken (" + what + "/" + extra + ")");
                return true;
            });
            p.prepareAsync();
            h.removeCallbacks(prepWatch); h.postDelayed(prepWatch, PodcastNa.PREPARE_MS);
        } catch (Exception x) {
            onError("Afspelen lukt niet: " + (x.getMessage() == null ? x.getClass().getSimpleName() : x.getMessage()));
        }
    }

    /** Laden duurt te lang (de verbinding hangt): als een fout behandelen (dat probeert het nog twee keer). */
    private void prepTimeout() { if (player != null && !prepared && "connecting".equals(status)) onError(Taal.T("De aflevering laadt niet")); }

    /**
     * Een sprong blijft hangen: opnieuw verbinden op de plek waar je heen wilde. Is hij er stilletjes toch al (sommige
     * toestellen melden een sprong naar bijna dezelfde plek niet), dan alleen verder.
     */
    private void seekTimeout() {
        MediaPlayer p = player;
        if (p == null || !sk.busy()) return;
        long want = sk.where();
        long now = -1;
        try { now = p.getCurrentPosition(); } catch (Throwable ignored) { }
        if (now >= 0 && Math.abs(now - sk.target) < 3000) { long n = sk.done(); if (n >= 0) doSeek(p, n); else update(); return; }
        reconnect(want, "sprong");
    }

    /**
     * Opnieuw verbinden op een plek (vastgelopen, sprong blijft hangen, verbinding afgebroken). Hooguit een paar keer
     * achter elkaar; daarna een melding, en Afspelen gaat verder waar hij was.
     */
    private void reconnect(long at, String why) {
        App.log(this, "PODCAST", "speler: opnieuw verbinden (" + why + ")");
        if (stall.reconnects >= PodcastNa.MAX_RECONNECT) { retries = 2; pos = at; onError(Taal.T("De verbinding met de aflevering blijft haperen")); return; }
        stall.reconnects++;
        PodcastUrl.forget(optEp("url"));
        pos = at;
        if ("paused".equals(status)) { release(); prepared = false; saveNow(); update(); return; } // Afspelen laadt hem opnieuw, op deze plek
        begin(at);
    }

    private void play() {
        MediaPlayer p = player;
        if (p == null || !prepared) return;
        if (!foreground) startFg(); // eerst voorgrond (zie begin)
        if (!requestFocus()) { error = "Geluid is nu in gebruik"; pause(false); return; }
        h.removeCallbacks(idleRun);
        setVolume(targetVolume()); // vóór het starten: tijdens het uitfaden geen klap op vol volume
        try { p.start(); applySpeed(); } catch (Exception e) { onError("Afspelen lukt niet"); return; }
        releaseAutoWake(); // de speler houdt de telefoon nu zelf wakker
        resumeOnFocus = false;
        status = "playing";
        retries = 0;
        holdWifi();
        stall.reset(SystemClock.elapsedRealtime());
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
        buffering = false;
        saveNow();
        releaseWifi();
        releaseAutoWake();
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
        if (p != null && prepared) {
            // Loopt er nog een sprong (een paar keer snel terug): alleen de laatste plek onthouden, die volgt daarna
            if (sk.ask(ms)) doSeek(p, ms);
        }
        else if (ep != null && !"connecting".equals(status)) { saveNow(); }
        update();
    }

    /** Echt springen (sk.target is al ms). */
    private void doSeek(MediaPlayer p, long ms) {
        try { p.seekTo((int) ms); } catch (Exception e) { sk.clear(); return; }
        h.removeCallbacks(seekWatch); h.postDelayed(seekWatch, PodcastNa.SEEK_MS);
    }

    /** De plek: tijdens een sprong de plek waar hij heen gaat (zo telt nog eens 15 s terug vanaf daar). */
    private long curPos() {
        if (sk.busy()) return sk.where();
        MediaPlayer p = player;
        if (p != null && prepared) { try { return p.getCurrentPosition(); } catch (Exception ignored) { } }
        return pos;
    }

    private void onEnded() {
        pos = 0;
        release(); prepared = false;
        status = "ended"; // eerst: dan telt hij niet meer als "speelt nog" en kan een download weg
        try { JSONObject e = new JSONObject(ep); Podcasts.saveProgress(this, e.optString("key"), 0, dur, true); } catch (Exception ignored) { }
        h.removeCallbacks(tickRun);
        final String mode = naMode(this);
        if (!PodcastNa.STOP.equals(mode)) {
            // De volgende (Hierna, en volgens de instelling de volgende van deze podcast of een nieuwe aflevering); een
            // slaaptimer op tijd loopt gewoon door. Het bestand lezen gebeurt op de achtergrond; zolang houdt een korte
            // wakelock de telefoon wakker (het scherm staat vaak uit).
            holdAutoWake();
            final Context app = getApplicationContext();
            final JSONObject curEp = jsonOr(ep), curPod = jsonOr(pod);
            Podcasts.IO.execute(() -> {
                JSONObject found = null;
                try { found = Podcasts.nextFor(app, mode, curEp, curPod, true); } catch (Throwable ignored) { }
                final JSONObject nx = found;
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

    private static JSONObject jsonOr(String s) { try { return s == null ? null : new JSONObject(s); } catch (Exception e) { return null; } }

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
            PodcastUrl.forget(optEp("url")); // het opgezochte adres kan verlopen zijn: opnieuw via het gewone
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
        // Staat de plek te lang stil (hapering, verbinding hangt): opnieuw verbinden op dezelfde plek
        if (prepared && stall.check(now, pos, true, sk.busy(), buffering)) {
            if (dur > 0 && pos >= dur - 5000) { onEnded(); return; } // stil aan het einde: gewoon afgelopen
            reconnect(pos, "vastgelopen"); return;
        }
        if (now - saveAt > 10_000) { saveAt = now; saveNow(); }
        if (now - shownAt > 30_000) { shownAt = now; Player.changed(this); } // voortgang in de widget
        // Slaaptimer "aan het einde van deze aflevering": de laatste minuut langzaam zachter
        if (sleepEnd && sleepAt == 0 && dur > 0) {
            setVolume(targetVolume());
            if (endLeft() < endFade() + 1000) { h.postDelayed(tickRun, SleepFade.STEP_MS); return; }
        }
        h.postDelayed(tickRun, 1000);
    }

    /** Hoeveel speeltijd de aflevering nog heeft (rekening houdend met de snelheid). */
    private long endLeft() { return (long) ((dur - pos) / Math.max(0.5f, speed)); }
    /** Uitfaden aan het eind van de aflevering: een minuut, bij een heel korte aflevering hooguit een derde. */
    private long endFade() { return Math.min(END_FADE_MS, dur / 3); }

    /** Het volume dat nu hoort: slaaptimer op tijd, of aan het einde van de aflevering, of vol. */
    private float targetVolume() {
        if (sleepAt > 0) return SleepFade.gain(sleepAt - System.currentTimeMillis(), sleepFadeMs);
        if (sleepEnd && dur > 0) {
            // Niet helemaal stil: de lengte van een aflevering is soms een schatting, het laatste stukje moet nog hoorbaar zijn
            return Math.max(SleepFade.gain(SleepFade.STEP_MS, SleepFade.MAX_MS), SleepFade.gain(endLeft(), endFade()));
        }
        return 1f;
    }

    /** Uitfaden aan het einde van de aflevering (bij de slaaptimer "aan het einde van deze aflevering"). */
    static final long END_FADE_MS = 60_000;

    /** Slaaptimer: de laatste minuten langzaam zachter (zie SleepFade), dan pauzeren (niet stoppen: morgen verder waar je was). */
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
        if ("playing".equals(status)) setVolume(SleepFade.gain(left, sleepFadeMs));
        h.postDelayed(sleepTick, SleepFade.nextDelay(left, sleepFadeMs));
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

    /** De speler loslaten op een eigen draad: reset() op een speler die nog laadt kan seconden blokkeren. */
    private void release() {
        MediaPlayer p = player;
        boolean started = prepared;
        player = null;
        sk.clear(); buffering = false;
        h.removeCallbacks(prepWatch); h.removeCallbacks(seekWatch);
        if (p == null) return;
        try { p.setVolume(0f, 0f); } catch (Throwable ignored) { } // meteen stil, ook als loslaten even duurt
        // Een geladen speler eerst pauzeren (snel): stopt het netwerk en laat zijn eigen wakelock los
        if (started) try { p.pause(); } catch (Throwable ignored) { }
        RELEASER.execute(() -> { try { p.reset(); } catch (Throwable ignored) { } try { p.release(); } catch (Throwable ignored) { } });
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
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, Taal.n(this, n), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTIF_ID, Taal.n(this, n));
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
        int st = "playing".equals(status) && buffering ? PlaybackState.STATE_BUFFERING : "playing".equals(status) ? PlaybackState.STATE_PLAYING : "connecting".equals(status) ? PlaybackState.STATE_BUFFERING
                : "paused".equals(status) ? PlaybackState.STATE_PAUSED : "error".equals(status) ? PlaybackState.STATE_ERROR : PlaybackState.STATE_STOPPED;
        // Android 13+ bouwt de knoppen in de melding uit de mediasessie: de eerste twee eigen acties komen op de
        // plekken van vorige/volgende (links en rechts van afspelen), de rest ernaast
        PlaybackState.Builder pb = new PlaybackState.Builder().setActions(ACTIONS)
                .setState(st, curPos(), "playing".equals(status) && !buffering ? speed : 0f, SystemClock.elapsedRealtime());
        pb.addCustomAction(new PlaybackState.CustomAction.Builder(BACK, BACK_S + " s terug", R.drawable.ic_back).build());
        pb.addCustomAction(new PlaybackState.CustomAction.Builder(FWD, FWD_S + " s vooruit", R.drawable.ic_fwd).build());
        if (sleepAt > 0) pb.addCustomAction(new PlaybackState.CustomAction.Builder(SLEEP_ADD, "+10 min slaaptimer", R.drawable.ic_bedtime).build());
        pb.addCustomAction(new PlaybackState.CustomAction.Builder(STOP, "Stoppen", R.drawable.ic_stop).build());
        session.setPlaybackState(pb.build());
        if (!"stopped".equals(status)) ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, Taal.n(this, notif()));
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

    private android.graphics.drawable.Icon icon(int res) { return android.graphics.drawable.Icon.createWithResource(this, res); }

    private Notification notif() {
        String title = optEp("title"), podTitle = optPod("title");
        String text = "connecting".equals(status) ? Taal.t(this, "Laden…") : "error".equals(status) ? (error == null ? Taal.t(this, "Fout") : Taal.tx(this, error))
                : "ended".equals(status) ? Taal.t(this, "Afgelopen") : podTitle;
        if (sleepAt > 0) text += " · " + Taal.t(this, "stopt om {tijd}", "tijd", Regio.time(this, sleepAt));
        else if (sleepEnd) text += " · " + Taal.t(this, "stopt na deze aflevering");
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, NotifCenter.ch(this, CHANNEL)) : new Notification.Builder(this);
        boolean playing = "playing".equals(status) || "connecting".equals(status);
        Intent open = new Intent(this, MainActivity.class).putExtra("open", "podcasts").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Taal.keep(b, Notification.EXTRA_TITLE, Notification.EXTRA_TEXT); // titels zijn gegevens; de vaste tekst staat er al vertaald in
        b.setSmallIcon(R.drawable.ic_podcast).setContentTitle(title.isEmpty() ? "Podcast" : title).setContentText(text)
                .setContentIntent(PendingIntent.getActivity(this, 45, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setDeleteIntent(action(STOP, null, 46))
                .setOngoing(playing).setOnlyAlertOnce(true).setShowWhen(false).setVisibility(Notification.VISIBILITY_PUBLIC);
        if (art != null) b.setLargeIcon(art);
        // Met pictogrammen: oudere Android-versies tonen in de kleine mediaspeler alleen het pictogram
        b.addAction(new Notification.Action.Builder(icon(R.drawable.ic_back), BACK_S + " s terug", action(SKIP, String.valueOf(-BACK_S), 47)).build())
                .addAction(new Notification.Action.Builder(icon(playing ? R.drawable.ic_pause : R.drawable.ic_play), playing ? "Pauze" : "Afspelen", action(playing ? PAUSE : RESUME, null, 48)).build())
                .addAction(new Notification.Action.Builder(icon(R.drawable.ic_fwd), FWD_S + " s vooruit", action(SKIP, String.valueOf(FWD_S), 49)).build());
        if (sleepAt > 0) b.addAction(new Notification.Action.Builder(icon(R.drawable.ic_bedtime), "+10 min", action(SLEEP_ADD, "10", 50)).build());
        b.addAction(new Notification.Action.Builder(icon(R.drawable.ic_stop), "Stoppen", action(STOP, null, 51)).build());
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
        NotificationChannel ch = new NotificationChannel(CHANNEL, Taal.t(c, "Podcasts"), NotificationManager.IMPORTANCE_LOW);
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
