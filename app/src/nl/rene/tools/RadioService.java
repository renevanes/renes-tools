package nl.rene.tools;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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
import android.media.browse.MediaBrowser;
import android.media.MediaDescription;
import android.service.media.MediaBrowserService;
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
public class RadioService extends MediaBrowserService implements AudioManager.OnAudioFocusChangeListener {

    static final String CHANNEL = "radio";
    static final int NOTIF_ID = 4401;
    static final String PLAY = "play", PAUSE = "pause", RESUME = "resume", STOP = "stop", SLEEP = "sleep", SNOOZE = "snooze";
    static final String REW = "rew", FWD = "fwd", LIVE = "live";
    /** Wekkermodus: geluid via het wekkervolume, zacht beginnen, en bij geen verbinding de wekkertoon. */
    static volatile boolean alarm = false;
    private android.media.Ringtone ring;
    private PowerManager.WakeLock alarmWl;   // houdt de telefoon wakker tot de wekker klinkt
    private boolean ducked = false;
    static final long ACTIONS = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_STOP
            | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_PLAY_FROM_MEDIA_ID
            | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
            | PlaybackState.ACTION_REWIND | PlaybackState.ACTION_FAST_FORWARD;

    // Toestand voor de interface
    static volatile String station = null;     // JSON van de zender
    static volatile String status = "stopped"; // connecting, playing, paused, interrupted, error, stopped
    static volatile String title = "";         // nu op de radio (ICY)
    static volatile String error = null;
    static volatile long sleepAt = 0;
    static volatile String info = "{}";        // wat de zender meestuurt (Radio.streamInfo)
    /** Pauzeren/terugspoelen: aan, seconden achter live, seconden die nog terug kunnen. */
    static volatile boolean shiftOn = false;
    static volatile int shiftBehind = 0, shiftBack = 0;
    /** Eerder gehoorde titels op deze zender (nieuwste eerst). */
    static final java.util.LinkedList<String[]> recent = new java.util.LinkedList<>();

    /** De lopende service (alleen op de hoofdthread gebruiken). */
    static RadioService inst;

    private MediaPlayer player;
    private Timeshift ts;            // buffer voor pauzeren en terugspoelen (null = direct afspelen)
    private long tsStart = 0;        // bufferpositie waar de huidige speler begon
    private long pausedPos = -1;     // bufferpositie bij pauze/onderbreking
    private long lastPos = -1;       // laatst bekende afspeelplek (elke seconde bijgewerkt)
    private int rateSnap = 16000;    // bytes/s waarmee de huidige speler gerekend wordt
    private boolean noShiftOnce = false; // na mislukken via de buffer: deze keer direct
    private PowerManager.WakeLock shiftWl;     // tijdens pauze met buffer: blijven opnemen met scherm uit
    private android.net.wifi.WifiManager.WifiLock shiftWifi;
    static final int LIVE_LAG = 6;   // seconden achter live beginnen, zodat de speler een voorraadje heeft
    private MediaSession session;
    private AudioManager am;
    private AudioFocusRequest focusReq;
    private final Handler h = new Handler(Looper.getMainLooper());
    private NoisyReceiver noisy;
    private int retries = 0, lastStartId = 0;
    private boolean pausedByFocus = false, foreground = false;
    private Thread metaThread;
    private volatile boolean alive = true;

    /**
     * Geheim per installatie. De service is bereikbaar voor Android Auto; een PLAY-opdracht met een
     * zelfgekozen stream wordt alleen uitgevoerd als hij van de app zelf komt.
     */
    static String secret(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("radio", Context.MODE_PRIVATE);
        String s = p.getString("secret", null);
        if (s == null) { s = java.util.UUID.randomUUID().toString(); p.edit().putString("secret", s).apply(); }
        return s;
    }

    /** Opdracht vanuit de app. Pauze/stop/slaaptimer starten de service nooit opnieuw. */
    static void send(Context c, String action, String extra) {
        RadioService s = inst;
        try {
            if (PLAY.equals(action) || RESUME.equals(action)) {
                Intent i = new Intent(c, RadioService.class).setAction(action).putExtra("k", secret(c));
                if (extra != null) i.putExtra("x", extra);
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
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
            o.put("info", new JSONObject(info));
            if (shiftOn) o.put("shift", new JSONObject().put("behind", shiftBehind).put("back", shiftBack));
            org.json.JSONArray r = new org.json.JSONArray();
            synchronized (recent) { for (String[] x : recent) r.put(new JSONObject().put("t", Long.parseLong(x[0])).put("title", x[1])); }
            o.put("recent", r);
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
        @Override public void onPlay() { RadioService s = inst; if (s != null) { alarm = false; s.startCmd(RESUME, null); } }
        @Override public void onPause() { RadioService s = inst; if (s != null) s.handle(PAUSE, null); }
        @Override public void onStop() { RadioService s = inst; if (s != null) s.handle(STOP, null); }
        @Override public void onPlayFromMediaId(String id, android.os.Bundle extras) { RadioService s = inst; if (s != null) s.playMedia(id); }
        @Override public void onSkipToNext() { RadioService s = inst; if (s != null) s.skip(1); }
        @Override public void onSkipToPrevious() { RadioService s = inst; if (s != null) s.skip(-1); }
        @Override public void onRewind() { RadioService s = inst; if (s != null) s.handle(REW, null); }
        @Override public void onFastForward() { RadioService s = inst; if (s != null) s.handle(FWD, null); }
    }

    // ---------- Android Auto / mediabrowser ----------

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, android.os.Bundle rootHints) {
        return new BrowserRoot("root", null);
    }

    /** Zenders voor in de auto: favorieten, anders de populaire zenders uit de laatste lijst. */
    static org.json.JSONArray browseList(Context c) {
        org.json.JSONArray f = Radio.favorites(c);
        if (f.length() > 0) return f;
        try {
            org.json.JSONArray all = new org.json.JSONArray(Radio.prefs(c).getString("cache", "[]")), out = new org.json.JSONArray();
            for (int i = 0; i < all.length() && i < 25; i++) out.put(all.get(i));
            return out;
        } catch (Exception e) { return f; }
    }

    @Override
    public void onLoadChildren(String parentId, Result<java.util.List<MediaBrowser.MediaItem>> result) {
        java.util.List<MediaBrowser.MediaItem> items = new java.util.ArrayList<>();
        if ("root".equals(parentId)) {
            org.json.JSONArray l = browseList(this);
            for (int i = 0; i < l.length(); i++) {
                JSONObject s = l.optJSONObject(i);
                if (s == null) continue;
                MediaDescription d = new MediaDescription.Builder().setMediaId("u:" + s.optString("url")).setTitle(s.optString("name"))
                        .setSubtitle(s.optString("tags").replace(",", ", ")).build();
                items.add(new MediaBrowser.MediaItem(d, MediaBrowser.MediaItem.FLAG_PLAYABLE));
            }
        }
        result.sendResult(items);
    }

    /** Alleen zenders uit de eigen lijst (favorieten/populair) kunnen vanuit de auto gestart worden. */
    void playMedia(String id) {
        if (id == null || !id.startsWith("u:")) return;
        String url = id.substring(2);
        org.json.JSONArray l = browseList(this);
        for (int i = 0; i < l.length(); i++) {
            JSONObject s = l.optJSONObject(i);
            if (s != null && url.equals(s.optString("url"))) {
                Radio.prefs(this).edit().putString("last", s.toString()).apply();
                alarm = false;
                startCmd(PLAY, s.toString());
                return;
            }
        }
    }

    /** Via een echte startopdracht, zodat de radio blijft spelen als de auto of het slotscherm loskoppelt. */
    void startCmd(String action, String x) {
        try {
            Intent it = new Intent(this, RadioService.class).setAction(action).putExtra("k", secret(this));
            if (x != null) it.putExtra("x", x);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(it); else startService(it);
        } catch (Exception e) {
            if (!foreground) startFg();
            handle(action, x);
        }
    }

    /** Volgende/vorige favoriet (stuurknoppen in de auto, koptelefoon). */
    void skip(int dir) {
        org.json.JSONArray l = browseList(this);
        if (l.length() == 0) return;
        int cur = -1;
        try {
            String url = new JSONObject(station).optString("url");
            for (int i = 0; i < l.length(); i++) if (url.equals(l.getJSONObject(i).optString("url"))) cur = i;
        } catch (Exception ignored) { }
        JSONObject nx = l.optJSONObject((cur + dir + l.length()) % l.length());
        if (nx != null) playMedia("u:" + nx.optString("url"));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        session = new MediaSession(this, "RenesRadio");
        session.setCallback(new SessionCallback());
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        setSessionToken(session.getSessionToken()); // voor Android Auto en andere mediabedieningen
        session.setPlaybackState(new PlaybackState.Builder().setActions(ACTIONS)
                .setState(PlaybackState.STATE_STOPPED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f).build());
        // Buffers van een vorige keer (bijv. na een crash) opruimen.
        java.io.File[] old = getCacheDir().listFiles();
        if (old != null) for (java.io.File f : old) if (f.getName().startsWith("radio-") && f.getName().endsWith(".buf")) f.delete();
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
        String x = intent == null ? null : intent.getStringExtra("x");
        boolean trusted = intent != null && secret(this).equals(intent.getStringExtra("k"));
        if (PLAY.equals(a) || RESUME.equals(a)) startFg(); // verplicht na startForegroundService
        if (!trusted) {
            // Niet van de app zelf (de service is zichtbaar voor Android Auto): niets doen.
            if (player == null && !"paused".equals(status)) stopAll();
            return START_NOT_STICKY;
        }
        if (PLAY.equals(a) || RESUME.equals(a)) {
            alarm = intent.getBooleanExtra("alarm", false) && x != null;
            if (alarm && alarmWl == null) {
                alarmWl = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "renestools:radioalarm");
                alarmWl.acquire(120_000L);
            }
        }
        handle(a, x);
        return START_NOT_STICKY;
    }

    /** Verwerkt een opdracht in de lopende service (hoofdthread). */
    void handle(String a, String x) {
        if (PLAY.equals(a)) {
            if (x != null) {
                if (!x.equals(station)) { info = "{}"; synchronized (recent) { recent.clear(); } }
                station = x; retries = 0; start();
            } else if (station == null) stopAll();
        } else if (RESUME.equals(a)) {
            if (station == null) station = Radio.prefs(this).getString("last", null); // bijv. vanaf de widget
            if (station != null) { if (!foreground) startFg(); retries = 0; resumeOrStart(); } else stopAll();
        } else if (SNOOZE.equals(a)) {
            stopAll();
            RadioAlarm.snooze(this, 10);
        } else if (PAUSE.equals(a)) {
            pausedByFocus = false;
            alarm = false;
            stopRing();
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
        } else if (REW.equals(a) || FWD.equals(a) || LIVE.equals(a)) {
            if (ts == null || !shiftOn) return;
            alarm = false;
            long cur = curPos(), r = rateSnap;
            long target = LIVE.equals(a) ? ts.live() : cur + (REW.equals(a) ? -30 : 30) * r;
            if ("interrupted".equals(status)) { pausedPos = clampShift(target); tick(); update(); } // tijdens een gesprek: alleen de plek verzetten
            else if ("paused".equals(status)) { pausedPos = clampShift(target); resumeOrStart(); }
            else seekShift(target);
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
            ducked = true;
            if (player != null) player.setVolume(0.3f, 0.3f);
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            ducked = false;
            if (player != null) player.setVolume(1f, 1f);
            if (pausedByFocus) { pausedByFocus = false; if (station != null) { retries = 0; resumeOrStart(); } }
        }
    }

    private void release() {
        if (player != null) { try { player.reset(); player.release(); } catch (Exception ignored) { } player = null; }
    }

    private void start() {
        release();
        closeShift();
        alive = true;
        session.setActive(true);
        error = null;
        stopRing();
        if (!requestFocus() && !alarm) { error = "Geluid is nu in gebruik (bijvoorbeeld door een gesprek)"; status = "error"; update(); return; }
        String url;
        try { url = new JSONObject(station).optString("url"); } catch (Exception e) { url = ""; }
        if (url.isEmpty()) { error = "Geen stream-adres"; status = "error"; update(); return; }
        status = "connecting";
        title = "";
        update();
        boolean direct0 = noShiftOnce;
        noShiftOnce = false;
        if (!alarm && !direct0 && Radio.prefs(this).getBoolean("timeshift", true)) {
            int kbps = 0;
            try { kbps = new JSONObject(station).optInt("bitrate"); } catch (Exception ignored) { }
            final Timeshift t = new Timeshift(new java.io.File(getCacheDir(), "radio-" + System.nanoTime() + ".buf"), url, Radio.UA, kbps, Timeshift.CAP_DEFAULT);
            final String direct = url;
            ts = t;
            t.begin((ok, why) -> h.post(() -> {
                if (ts != t || !alive) { if (ts != t) t.close(); return; }
                if (!"connecting".equals(status)) {
                    // Intussen gepauzeerd of onderbroken (gesprek): niet gaan spelen; de buffer neemt wel op.
                    if (ok) { shiftOn = true; if (pausedPos < 0) pausedPos = t.live(); rateSnap = t.rate(); tick(); }
                    else closeShift();
                    return;
                }
                if (ok) { shiftOn = true; long p0 = Math.max(0, t.live() - LIVE_LAG * (long) t.rate()); startPlayer(t.url(p0), p0); tick(); }
                else { closeShift(); startPlayer(direct, -1); } // formaat dat niet midden in kan beginnen: direct afspelen
            }));
            return;
        }
        startPlayer(url, -1);
    }

    /** Speler starten op een adres; startPos = plek in de buffer (of -1 bij direct afspelen). */
    private void startPlayer(String url, long startPos) {
        release();
        tsStart = Math.max(0, startPos);
        lastPos = startPos;
        pausedPos = -1;
        if (ts != null) rateSnap = ts.rate();
        releaseShiftLocks();
        try {
            MediaPlayer p = new MediaPlayer();
            player = p;
            p.setAudioAttributes(new AudioAttributes.Builder().setUsage(alarm ? AudioAttributes.USAGE_ALARM : AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            p.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            if (alarm) p.setVolume(0.15f, 0.15f);
            java.util.Map<String, String> hdr = new java.util.HashMap<>();
            hdr.put("User-Agent", Radio.UA);
            p.setDataSource(this, android.net.Uri.parse(url), hdr);
            p.setOnPreparedListener(mp -> {
                if (mp != player) return;
                mp.start();
                releaseAlarmWl();
                if (alarm) ramp(0.15f);
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
        if (ts != null && shiftOn && !ts.dead && station != null && retries < 3) {
            // Hapering tussen speler en buffer: verder op dezelfde plek (de speler zelf weet die na een fout niet meer).
            final long at = lastPos >= 0 ? lastPos : tsStart;
            lastPos = at;
            release();
            retries++;
            status = "connecting";
            update();
            h.postDelayed(() -> { if (alive && ts != null && "connecting".equals(status)) startPlayer(ts.url(at), at); }, 1500L * retries);
            return;
        }
        if (ts != null && shiftOn) { retries = 0; noShiftOnce = true; } // via de buffer lukt het niet: zender direct proberen
        closeShift();
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
        if (alarm) { playRing(); return; } // geen internet: dan de gewone wekkertoon
        update();
        detach();
    }

    /** Wekker: in 30 seconden zacht naar vol. */
    private void ramp(float v) {
        MediaPlayer p = player;
        if (p == null || !alarm) return;
        float nv = Math.min(1f, v + 0.05f);
        if (!ducked) { try { p.setVolume(nv, nv); } catch (Exception ignored) { } }
        if (nv < 1f) h.postDelayed(() -> ramp(nv), 1700);
    }

    private void releaseAlarmWl() {
        if (alarmWl != null) { try { if (alarmWl.isHeld()) alarmWl.release(); } catch (Exception ignored) { } alarmWl = null; }
    }

    private void playRing() {
        releaseAlarmWl();
        h.postDelayed(this::stopAll, 10 * 60_000L); // wekkertoon stopt vanzelf na 10 minuten
        try {
            android.net.Uri u = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM);
            if (u == null) u = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE);
            ring = android.media.RingtoneManager.getRingtone(this, u);
            if (ring != null) {
                ring.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
                if (Build.VERSION.SDK_INT >= 28) ring.setLooping(true);
                ring.play();
            }
        } catch (Exception ignored) { }
        error = "Geen verbinding met de zender; wekkertoon";
        status = "playing";
        update();
    }

    private void stopRing() {
        if (ring != null) { try { ring.stop(); } catch (Exception ignored) { } ring = null; }
    }

    /** Onderbreken voor een gesprek: speler weg, service blijft op de voorgrond. */
    private void interrupt() {
        stopRing();
        if (ts != null) {
            if (shiftOn) pausedPos = curPos();
            holdShiftLocks();
            h.removeCallbacks(shiftExpire);
            h.postDelayed(shiftExpire, pauseLimit());
        }
        release();
        status = "interrupted";
        update();
    }

    /** Pauze door de gebruiker: de melding blijft staan, de service mag weg. */
    private void pause() {
        stopRing();
        if (ts != null && !ts.dead) {
            // Met buffer: de radio loopt op de achtergrond door; verder gaan kan straks precies hier.
            if (shiftOn) pausedPos = curPos();
            release();
            status = "paused";
            holdShiftLocks();
            h.removeCallbacks(shiftExpire);
            h.postDelayed(shiftExpire, pauseLimit());
            update();
            return;
        }
        release();
        if (station != null) status = "paused";
        update();
        detach();
    }

    /** Na een uur pauze (of zodra de buffer vol is) stopt de buffer (scheelt data); daarna begint afspelen gewoon live. */
    private final Runnable shiftExpire = () -> {
        if ("paused".equals(status)) { closeShift(); update(); detach(); }
        else if ("interrupted".equals(status)) { closeShift(); update(); }
    };

    /** Zo lang mag pauzeren met buffer duren: een uur, of korter als de buffer eerder vol is. */
    private long pauseLimit() {
        Timeshift t = ts;
        long buf = t == null ? 3600 : Math.max(300, t.seconds());
        return Math.min(3600, buf) * 1000L;
    }

    private void holdShiftLocks() {
        try {
            if (shiftWl == null) {
                shiftWl = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "renestools:radiobuffer");
                shiftWl.acquire(61 * 60_000L);
            }
            if (shiftWifi == null) {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wm != null) { shiftWifi = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "renestools:radiobuffer"); shiftWifi.acquire(); }
            }
        } catch (Exception ignored) { }
    }

    private void releaseShiftLocks() {
        try { if (shiftWl != null && shiftWl.isHeld()) shiftWl.release(); } catch (Exception ignored) { }
        shiftWl = null;
        try { if (shiftWifi != null && shiftWifi.isHeld()) shiftWifi.release(); } catch (Exception ignored) { }
        shiftWifi = null;
    }

    /** Verder na pauze of onderbreking: uit de buffer als die er is, anders opnieuw verbinden. */
    private void resumeOrStart() {
        if (("playing".equals(status) || "connecting".equals(status)) && player != null) return; // speelt al
        h.removeCallbacks(shiftExpire);
        // Ook als de opname intussen gestopt is (bijv. geen netwerk): wat in de buffer zit, kan nog afgespeeld worden.
        if (ts != null && shiftOn && pausedPos >= 0) {
            if (!requestFocus()) { error = "Geluid is nu in gebruik (bijvoorbeeld door een gesprek)"; status = "error"; closeShift(); update(); return; }
            session.setActive(true);
            seekShift(pausedPos);
        } else start();
    }

    /** Huidige plek in de buffer. */
    private long curPos() {
        Timeshift t = ts;
        if (t == null) return -1;
        if (pausedPos >= 0) return pausedPos;
        MediaPlayer p = player;
        if (p == null || !"playing".equals(status)) return lastPos >= 0 ? lastPos : tsStart;
        long ms = 0;
        try { ms = p.getCurrentPosition(); } catch (Exception ignored) { }
        return Math.min(t.live(), tsStart + ms * rateSnap / 1000);
    }

    private long clampShift(long pos) {
        Timeshift t = ts;
        if (t == null) return pos;
        long live = t.live(), lag = LIVE_LAG * (long) rateSnap;
        pos = Math.max(t.oldest(), Math.min(pos, live));
        if (live - pos < lag + 2L * rateSnap) pos = Math.max(t.oldest(), live - lag); // (bijna) live = live, met een voorraadje
        return pos;
    }

    /** Naar een plek in de buffer springen (begrensd tussen het oudste en live). */
    private void seekShift(long pos) {
        Timeshift t = ts;
        if (t == null) return;
        pos = clampShift(pos);
        if (!requestFocus()) { error = "Geluid is nu in gebruik (bijvoorbeeld door een gesprek)"; status = "error"; closeShift(); update(); return; }
        session.setActive(true);
        h.removeCallbacks(shiftExpire);
        stopRing();
        status = "connecting";
        update();
        startPlayer(t.url(pos), pos);
    }

    private void closeShift() {
        if (ts != null) { final Timeshift t = ts; ts = null; new Thread(t::close, "timeshift-close").start(); }
        shiftOn = false; shiftBehind = 0; shiftBack = 0; pausedPos = -1; lastPos = -1;
        h.removeCallbacks(tickRun);
        h.removeCallbacks(shiftExpire);
        releaseShiftLocks();
    }

    private final Runnable tickRun = this::tick;

    /** Elke seconde: hoever achter live en hoever terug kan nog (voor de interface en de melding). */
    private void tick() {
        h.removeCallbacks(tickRun);
        Timeshift t = ts;
        if (t == null || !alive) { shiftOn = false; return; }
        long cur = curPos(), r = Math.max(1, rateSnap);
        if ("playing".equals(status)) lastPos = cur;
        int before = shiftBehind;
        shiftBehind = (int) Math.max(0, (t.live() - cur) / r);
        shiftBack = (int) Math.max(0, (cur - t.oldest()) / r);
        boolean was = before > LIVE_LAG + 2, now = shiftBehind > LIVE_LAG + 2;
        if ("playing".equals(status) && (was != now || (now && before / 10 != shiftBehind / 10))) update();
        h.postDelayed(tickRun, 1000);
    }

    private void detach() {
        if (foreground) { stopForeground(STOP_FOREGROUND_DETACH); foreground = false; }
    }

    private void stopAll() {
        stopRing();
        releaseAlarmWl();
        alarm = false;
        try {
            session.setPlaybackState(new PlaybackState.Builder().setActions(ACTIONS)
                    .setState(PlaybackState.STATE_STOPPED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f).build());
        } catch (Exception ignored) { }
        alive = false;
        pausedByFocus = false;
        h.removeCallbacksAndMessages(null);
        closeShift();
        release();
        abandonFocus();
        status = "stopped";
        title = "";
        sleepAt = 0;
        session.setActive(false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        foreground = false;
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIF_ID);
        RadioWidget.refresh(this);
        stopSelf(lastStartId); // een PLAY die intussen binnenkwam, houdt de service in leven
    }

    /** Leest elke 20 seconden wat er nu speelt (als de zender dat meestuurt). */
    private void startMeta() {
        if (metaThread != null && metaThread.isAlive()) return;
        metaThread = new Thread(() -> {
            while (alive && ("playing".equals(status) || "connecting".equals(status))) {
                String url;
                try { url = new JSONObject(station).optString("url"); } catch (Exception e) { break; }
                JSONObject in = Radio.streamInfo(url);
                if (!alive) break;
                info = in.toString();
                String t = in.optString("title");
                if (!t.equals(title)) {
                    title = t;
                    if (!t.isEmpty()) synchronized (recent) {
                        if (recent.isEmpty() || !recent.getFirst()[1].equals(t)) recent.addFirst(new String[]{String.valueOf(System.currentTimeMillis()), t});
                        while (recent.size() > 20) recent.removeLast();
                    }
                    h.post(() -> { if (alive) update(); });
                }
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
                .setActions(shiftOn ? ACTIONS : ACTIONS & ~(PlaybackState.ACTION_REWIND | PlaybackState.ACTION_FAST_FORWARD))
                .setState(st, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f).build());
        if (!"stopped".equals(status))
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, notif());
        RadioWidget.refresh(this);
    }

    private PendingIntent action(String a, int code) {
        Intent i = new Intent(this, RadioService.class).setAction(a).putExtra("k", secret(this));
        if (RESUME.equals(a) && Build.VERSION.SDK_INT >= 26) return PendingIntent.getForegroundService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification notif() {
        String name = "Radio";
        try { name = new JSONObject(station).optString("name", "Radio"); } catch (Exception ignored) { }
        String text = "connecting".equals(status) ? "Verbinden…" : "error".equals(status) ? (error == null ? "Fout" : error)
                : "paused".equals(status) ? (ts != null ? "Gepauzeerd · gaat straks verder waar je was" : "Gepauzeerd")
                : "interrupted".equals(status) ? "Onderbroken, gaat zo verder"
                : ts != null && shiftBehind > LIVE_LAG + 2 ? behindText(shiftBehind) + " achter live" // de ICY-titel is die van live: niet tonen
                : (title.isEmpty() ? "Live" : title);
        if (alarm) text = "⏰ Wekker · " + text;
        if (sleepAt > 0) text += " · stopt om " + new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date(sleepAt));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        boolean playing = "playing".equals(status) || "connecting".equals(status) || "interrupted".equals(status);
        boolean shift = ts != null && shiftOn && !alarm && !"error".equals(status);
        Intent open = new Intent(this, MainActivity.class).putExtra("open", "radio").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        b.setSmallIcon(R.drawable.ic_radio).setContentTitle(name).setContentText(text)
                .setContentIntent(PendingIntent.getActivity(this, 22, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setDeleteIntent(action(STOP, 33))
                .setOngoing(playing || shift).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PUBLIC);
        if (shift) b.addAction(new Notification.Action.Builder(null, "−30 s", action(REW, 35)).build());
        b.addAction(alarm ? new Notification.Action.Builder(null, "Snooze 10 min", action(SNOOZE, 34)).build()
                        : new Notification.Action.Builder(null, playing ? "Pauze" : "Afspelen", action(playing ? PAUSE : RESUME, 31)).build())
                .addAction(new Notification.Action.Builder(null, "Stoppen", action(STOP, 32)).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(shift ? new int[]{0, 1, 2} : new int[]{0, 1}));
        return b.build();
    }

    static String behindText(int s) { return s >= 3600 ? (s / 3600) + ":" + String.format(java.util.Locale.US, "%02d:%02d", s / 60 % 60, s % 60) : (s / 60) + ":" + String.format(java.util.Locale.US, "%02d", s % 60); }

    static void createChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Radio", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ((NotificationManager) c.getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
    }

    @Override
    public void onDestroy() {
        stopRing();
        releaseAlarmWl();
        alive = false;
        if (inst == this) inst = null;
        h.removeCallbacksAndMessages(null);
        closeShift();
        release();
        abandonFocus();
        try { unregisterReceiver(noisy); } catch (Exception ignored) { }
        session.release();
        if ("interrupted".equals(status) || "connecting".equals(status) || "playing".equals(status)) status = "paused";
        if (!"error".equals(status) && !"paused".equals(status)) status = "stopped";
        RadioWidget.refresh(this);
        super.onDestroy();
    }
}
