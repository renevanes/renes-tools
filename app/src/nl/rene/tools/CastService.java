package nl.rene.tools;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Houdt de verbinding met de Chromecast open zolang daar iets speelt (voorgrondservice met melding), en geeft
 * het vergrendelscherm en de volumeknoppen van de telefoon de bediening van de Chromecast.
 */
public class CastService extends Service {

    static final String CHANNEL = "cast";
    static final int NOTIF_ID = 4601;
    static final String PP = "pp", STOP = "stop", BACK = "back", FWD = "fwd";

    static volatile CastService inst;
    private final Handler h = new Handler(Looper.getMainLooper());
    private MediaSession session;
    private Volume volume;
    private String metaSig = "";
    private Bitmap art;
    private String artFor;

    static void start(Context c) {
        try {
            Intent i = new Intent(c, CastService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception ignored) { }
    }

    static void stop(Context c) {
        CastService s = inst;
        if (s != null) s.h.post(() -> { if (!Cast.active()) s.finish(); }); // een nieuwe sessie houdt hem aan
    }

    /** Melding en vergrendelscherm bijwerken (van elke thread). */
    static void refresh() { CastService s = inst; if (s != null) { s.h.removeCallbacks(s.upd); s.h.post(s.upd); } }

    private final Runnable upd = this::update;

    static final class Callback extends MediaSession.Callback {
        @Override public void onPlay() { run(() -> Cast.setPlaying(true)); }
        @Override public void onPause() { run(() -> Cast.setPlaying(false)); }
        @Override public void onStop() { run(Cast::stop); }
        @Override public void onSeekTo(long p) { run(() -> Cast.seek(p)); }
        @Override public void onFastForward() { run(() -> Cast.seekBy(30)); }
        @Override public void onRewind() { run(() -> Cast.seekBy(-15)); }
        @Override public void onSkipToNext() { CastService s = inst; if (s != null) run(() -> Player.step(s, null, 1)); }
        @Override public void onSkipToPrevious() { CastService s = inst; if (s != null) run(() -> Player.step(s, null, -1)); }
        private static void run(Runnable r) { new Thread(r, "cast-cmd").start(); } // netwerk niet op de hoofdthread
    }

    /** Volumeknoppen van de telefoon = volume van de Chromecast (in 20 stappen). */
    static final class Volume extends VolumeProvider {
        Volume(int cur) { super(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, 20, cur); }
        @Override public void onSetVolumeTo(int v) {
            Player.Now n = Cast.now();
            if (n == null || n.volume < 0) return; // nog niet bekend hoe hard de tv staat: niet ineens verspringen
            setCurrentVolume(v); final double l = v / 20.0; new Thread(() -> Cast.setVolume(l), "cast-vol").start();
        }
        @Override public void onAdjustVolume(int dir) { onSetVolumeTo(Math.max(0, Math.min(20, getCurrentVolume() + dir))); }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        session = new MediaSession(this, "RenesCast");
        session.setCallback(new Callback());
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        volume = new Volume(10);
        session.setPlaybackToRemote(volume);
        session.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startFg(); // verplicht na startForegroundService
        String a = intent == null ? null : intent.getAction();
        if (a != null) {
            Runnable r = PP.equals(a) ? (Runnable) Cast::playPause : STOP.equals(a) ? (Runnable) Cast::stop
                    : BACK.equals(a) ? (Runnable) () -> Cast.seekBy(-15) : FWD.equals(a) ? (Runnable) () -> Cast.seekBy(30) : null;
            if (r != null) new Thread(r, "cast-cmd").start();
        }
        if (!Cast.active()) finish(); // geen sessie (meer): geen melding laten staan
        return START_NOT_STICKY;
    }

    private void startFg() {
        createChannel(this);
        try {
            Notification n = notif(Cast.now());
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTIF_ID, n);
        } catch (Exception ignored) { }
    }

    static void createChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Casten", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ((NotificationManager) c.getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
    }

    private PendingIntent action(String a, int code) {
        return PendingIntent.getService(this, code, new Intent(this, CastService.class).setAction(a), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification notif(Player.Now n) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, NotifCenter.ch(this, CHANNEL)) : new Notification.Builder(this);
        boolean playing = n != null && n.playing;
        Intent open = new Intent(this, MainActivity.class).putExtra("open", "player").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        b.setSmallIcon(R.drawable.ic_podcast).setContentTitle(n == null ? "Casten" : n.title).setContentText(n == null ? "Verbinden…" : n.sub)
                .setContentIntent(PendingIntent.getActivity(this, 46, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).setVisibility(Notification.VISIBILITY_PUBLIC);
        if (art != null) b.setLargeIcon(art);
        boolean live = n == null || Player.RADIO.equals(n.src);
        if (!live) b.addAction(new Notification.Action.Builder(null, "−15 s", action(BACK, 71)).build());
        b.addAction(new Notification.Action.Builder(null, playing ? "Pauze" : "Afspelen", action(PP, 72)).build());
        if (!live) b.addAction(new Notification.Action.Builder(null, "+30 s", action(FWD, 73)).build());
        b.addAction(new Notification.Action.Builder(null, "Stoppen", action(STOP, 74)).build());
        b.setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(live ? 0 : 1));
        return b.build();
    }

    private void update() {
        if (inst != this) return;
        Player.Now n = Cast.now();
        if (n == null || !n.active) { finish(); return; }
        String sig = n.title + "|" + n.sub + "|" + n.dur + "|" + System.identityHashCode(art);
        if (!sig.equals(metaSig)) {
            metaSig = sig;
            MediaMetadata.Builder mb = new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, n.title).putString(MediaMetadata.METADATA_KEY_ARTIST, n.sub);
            if (n.dur > 0) mb.putLong(MediaMetadata.METADATA_KEY_DURATION, n.dur);
            if (art != null) mb.putBitmap(MediaMetadata.METADATA_KEY_ART, art);
            session.setMetadata(mb.build());
        }
        int st = n.playing ? ("connecting".equals(n.status) ? PlaybackState.STATE_BUFFERING : PlaybackState.STATE_PLAYING) : "error".equals(n.status) ? PlaybackState.STATE_ERROR : PlaybackState.STATE_PAUSED;
        long acts = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        if (n.dur > 0) acts |= PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_REWIND | PlaybackState.ACTION_FAST_FORWARD;
        session.setPlaybackState(new PlaybackState.Builder().setActions(acts)
                .setState(st, n.pos, n.playing ? (float) Math.max(0.5, n.rate) : 0f, android.os.SystemClock.elapsedRealtime()).build());
        if (n.volume >= 0) volume.setCurrentVolume((int) Math.round(n.volume * 20));
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, notif(n));
        final String url = n.art;
        if (url != null && !url.equals(artFor)) {
            artFor = url;
            new Thread(() -> { final Bitmap b = Art.small(Art.load(getApplicationContext(), url), 320); h.post(() -> { if (url.equals(artFor) && inst == this) { art = b; update(); } }); }, "cast-art").start();
        }
    }

    private void finish() {
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) { }
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIF_ID);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (inst == this) inst = null;
        h.removeCallbacksAndMessages(null);
        try { session.release(); } catch (Exception ignored) { }
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIF_ID);
        super.onDestroy();
    }
}
