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
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Voorlezen in de e-reader met de tekst-naar-spraak van Android (alleen stemmen op de telefoon, nooit via internet).
 * Leest zin voor zin (EreaderSpeech.split), gaat door naar het volgende hoofdstuk, bewaart de leesplek en leest door
 * met het scherm uit of de app op de achtergrond (voorgrondservice met mediamelding, zoals de podcastspeler).
 * Neemt het geluid over van radio en podcast (die pauzeren); pauzeert zelf als iets anders geluid wil, of als de
 * koptelefoon eruit gaat. De interface hoort het via onEreaderSpeak (stateJson).
 */
public class EreaderSpeakService extends Service implements AudioManager.OnAudioFocusChangeListener {

    static final String CHANNEL = "voorlezen";
    static final int NOTIF_ID = 4511;
    static final String START = "start", PAUSE = "pause", RESUME = "resume", TOGGLE = "toggle", STOP = "stop", PREV = "prev", NEXT = "next",
            RATE = "rate", SLEEP = "sleep";
    static final long ACTIONS = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
            | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS;

    static final String NO_VOICE = "nolang", NO_ENGINE = "noengine", BUSY = "busy";

    // Toestand voor de interface
    static volatile String status = "stopped"; // starting, playing, paused, stopped, error
    static volatile String bookId = "", title = "", chapterName = "", error = "", code = "", lang = "";
    static volatile int chapter = 0, start = 0, end = 0;
    static volatile float rate = 1f;
    static volatile long sleepAt = 0;
    static volatile boolean sleepChapter = false;
    static volatile EreaderSpeakService inst;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private final Handler h = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private boolean ready, initing, foreground, resumeOnFocus, noisyOn;
    private int engineGen, gen, queued = -1, idx, lastStartId, chapterCount;
    private int[] sentences = new int[0];
    private String text = "", voiceName = "";
    private JSONArray linear, chapterNames;
    private MediaSession session;
    private AudioManager am;
    private AudioFocusRequest focusReq;
    private NoisyReceiver noisy;
    private long savedAt;
    /** Wat er na het laden van de spraakmotor moet gebeuren (anders -1). */
    private int pendingIdx = -1;

    // ---------- vanuit de app ----------

    /** Opdracht vanuit de app, melding of koptelefoon. Beginnen start de service; de rest gaat naar de lopende service. */
    static void send(Context c, String action, String extra) {
        EreaderSpeakService s = inst;
        try {
            if (START.equals(action) || (s == null && (RESUME.equals(action) || TOGGLE.equals(action)) && !bookId.isEmpty())) {
                Intent i = new Intent(c, EreaderSpeakService.class).setAction(START.equals(action) ? START : RESUME);
                if (extra != null) i.putExtra("x", extra);
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
            } else if (s != null) {
                s.h.post(() -> s.safe(action, extra));
            } else if (RATE.equals(action)) {
                try { rate = EreaderSpeech.clampRate(Float.parseFloat(extra)); } catch (Throwable ignored) { }
            }
        } catch (Throwable e) {
            status = "error"; error = Taal.tx(c, Taal.T("Voorlezen starten lukt nu niet; probeer het opnieuw")); code = "";
            tell();
        }
    }

    static String stateJson() {
        try {
            return new JSONObject().put("state", status).put("id", bookId).put("title", title).put("chapter", chapter).put("chapterName", chapterName)
                    .put("start", start).put("end", end).put("rate", rate).put("sleepAt", sleepAt).put("sleepChapter", sleepChapter)
                    .put("error", error).put("code", code).put("lang", lang).toString();
        } catch (Throwable e) { return "{\"state\":\"stopped\"}"; }
    }

    private static void tell() { MainActivity.jsAll("onEreaderSpeak", stateJson()); }

    static boolean speaking() { return "playing".equals(status) || "starting".equals(status); }

    // ---------- service ----------

    static final class NoisyReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            EreaderSpeakService s = inst;
            if (s != null && i != null && AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction())) s.h.post(() -> s.safe(PAUSE, null));
        }
    }

    /** Vergrendelscherm, koptelefoon en auto: afspelen/pauze, vorige en volgende zin, stoppen. */
    static final class SessionCallback extends MediaSession.Callback {
        private static void go(String a) { EreaderSpeakService s = inst; if (s != null) s.safe(a, null); }
        @Override public void onPlay() { go(RESUME); }
        @Override public void onPause() { go(PAUSE); }
        @Override public void onStop() { go(STOP); }
        @Override public void onSkipToNext() { go(NEXT); }
        @Override public void onSkipToPrevious() { go(PREV); }
    }

    /** Meldingen van de spraakmotor (eigen thread) naar de hoofdthread; een melding van een eerdere ronde telt niet. */
    static final class Progress extends UtteranceProgressListener {
        private static int[] parse(String u) {
            try { int k = u.indexOf(':'); return new int[]{Integer.parseInt(u.substring(0, k)), Integer.parseInt(u.substring(k + 1))}; }
            catch (Throwable e) { return null; }
        }
        @Override public void onStart(String u) { post(u, 0); }
        @Override public void onDone(String u) { post(u, 1); }
        @Override @SuppressWarnings("deprecation") public void onError(String u) { post(u, 2); }
        @Override public void onError(String u, int errorCode) { post(u, 2); }
        private static void post(String u, int kind) {
            final int[] p = parse(u);
            final EreaderSpeakService s = inst;
            if (p == null || s == null) return;
            s.h.post(() -> {
                try {
                    if (inst != s || p[0] != s.gen) return;
                    if (kind == 0) s.started(p[1]); else if (kind == 1) s.done(p[1]); else s.fail("", Taal.T("Voorlezen lukte niet"));
                } catch (Throwable ignored) { }
            });
        }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        session = new MediaSession(this, "RenesVoorlezen");
        session.setCallback(new SessionCallback());
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        noisy = new NoisyReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        String a = intent == null ? null : intent.getAction();
        String x = intent == null ? null : intent.getStringExtra("x");
        if (START.equals(a) || RESUME.equals(a)) startFg(); // na startForegroundService verplicht
        if (a == null) { if (!speaking() && !"paused".equals(status)) stopAll(); return START_NOT_STICKY; }
        safe(a, x);
        if (inst == this && "stopped".equals(status)) stopSelfResult(startId);
        return START_NOT_STICKY;
    }

    /** Een opdracht uitvoeren zonder dat een fout de app laat stoppen. */
    void safe(String a, String x) {
        try { handle(a, x); } catch (Throwable t) { try { fail("", Taal.T("Voorlezen lukte niet")); } catch (Throwable ignored) { } }
    }

    private void handle(String a, String x) {
        switch (a) {
            case START: begin(x); break;
            case RESUME: if (sentences.length == 0 && !bookId.isEmpty()) reload(); else speakFrom(idx); break;
            case TOGGLE: if (speaking()) pause(true); else handle(RESUME, null); break;
            case PAUSE: pause(true); break;
            case STOP: stopAll(); break;
            case PREV: step(-1); break;
            case NEXT: step(1); break;
            case RATE:
                try { rate = EreaderSpeech.clampRate(Float.parseFloat(x)); } catch (Throwable ignored) { }
                if (tts != null && ready) tts.setSpeechRate(rate);
                if (speaking()) speakFrom(idx); else update();
                break;
            case SLEEP:
                sleepChapter = "chapter".equals(x);
                sleepAt = EreaderSpeech.sleepUntil(System.currentTimeMillis(), x);
                update();
                break;
            default: break;
        }
    }

    // ---------- beginnen: boek en hoofdstuk laden ----------

    private void begin(String json) {
        final JSONObject o;
        try { o = new JSONObject(json == null ? "{}" : json); } catch (Throwable e) { fail("", Taal.T("Voorlezen lukte niet")); return; }
        final String id = o.optString("id");
        final int ch = o.optInt("chapter", 0), anchor = o.optInt("anchor", 0);
        rate = EreaderSpeech.clampRate((float) o.optDouble("rate", rate));
        String newLang = o.optString("lang", "").trim(), newVoice = o.optString("voice", "").trim();
        if (!newLang.equals(lang) || !newVoice.equals(voiceName)) shutEngine(); // andere taal of stem: de motor opnieuw instellen
        lang = newLang; voiceName = newVoice;
        yieldOthers();
        gen++; if (tts != null) try { tts.stop(); } catch (Throwable ignored) { }
        status = "starting"; error = ""; code = ""; bookId = id;
        tell();
        load(id, ch, anchor, false);
    }

    /** Hoofdstuk laden (op de achtergrond: een rijk hoofdstuk kan groot zijn), daarna verder op de hoofdthread. */
    private void load(final String id, final int ch, final int anchor, final boolean last) {
        final int g = gen;
        final Context app = getApplicationContext();
        IO.execute(() -> {
            try {
                JSONObject meta = Ereader.meta(app, id);
                String t = Ereader.chapter(app, id, ch).optString("text", "");
                h.post(() -> {
                    try {
                        if (inst != this || g != gen || !id.equals(bookId)) return;
                        chapterNames = meta.optJSONArray("chapters");
                        chapterCount = chapterNames == null ? 0 : chapterNames.length();
                        linear = meta.optJSONArray("linear");
                        title = meta.optString("title", "");
                        chapter = ch; chapterName = chapterNames == null ? "" : chapterNames.optString(ch, "");
                        text = t; sentences = EreaderSpeech.split(t);
                        int n = EreaderSpeech.count(sentences);
                        if (n == 0) { // een hoofdstuk zonder tekst (alleen een afbeelding): door naar het volgende
                            int next = EreaderSpeech.neighbor(linear, chapterCount, ch, last ? -1 : 1);
                            if (next < 0) { finished(); return; }
                            load(id, next, last ? Integer.MAX_VALUE : 0, last); return;
                        }
                        idx = last ? n - 1 : EreaderSpeech.at(sentences, anchor);
                        mark(idx);
                        if ("paused".equals(status)) { save(); update(); tell(); return; }
                        speakFrom(idx);
                    } catch (Throwable e) { fail("", Taal.T("Voorlezen lukte niet")); }
                });
            } catch (Throwable e) {
                h.post(() -> { if (inst == this && g == gen) fail("", Taal.T("Dit hoofdstuk kan niet worden voorgelezen")); });
            }
        });
    }

    private void reload() { status = "starting"; tell(); load(bookId, chapter, start, false); }

    // ---------- de spraakmotor ----------

    private void speakFrom(int i) {
        if (EreaderSpeech.count(sentences) == 0) { reload(); return; }
        idx = Math.max(0, Math.min(EreaderSpeech.count(sentences) - 1, i));
        gen++; queued = -1;
        if (tts == null || !ready) { pendingIdx = idx; engine(); return; }
        if (!focus(true)) { status = "paused"; code = BUSY; error = Taal.tx(this, AiSpeech.BUSY_AUDIO); update(); tell(); return; }
        resumeOnFocus = false;
        status = "playing"; error = ""; code = "";
        noisy(true);
        try { tts.stop(); } catch (Throwable ignored) { }
        if (!queue(idx, TextToSpeech.QUEUE_FLUSH) || !queue(idx + 1, TextToSpeech.QUEUE_ADD)) return;
        mark(idx);
        update(); tell();
    }

    private boolean queue(int i, int mode) {
        if (i >= EreaderSpeech.count(sentences) || i <= queued && mode == TextToSpeech.QUEUE_ADD) return true;
        String part = text.substring(sentences[2 * i], sentences[2 * i + 1]);
        if (tts.speak(part, mode, new Bundle(), gen + ":" + i) != TextToSpeech.SUCCESS) { fail("", Taal.T("Voorlezen lukte niet")); return false; }
        queued = i;
        return true;
    }

    private void engine() {
        if (initing) return;
        if (tts != null) shutEngine();
        initing = true; ready = false;
        status = "starting"; tell();
        final int g = ++engineGen;
        try {
            tts = new TextToSpeech(getApplicationContext(), st -> h.post(() -> {
                try {
                    if (inst != this || g != engineGen || tts == null) return;
                    initing = false;
                    if (st != TextToSpeech.SUCCESS) { shutEngine(); fail(NO_ENGINE, AiSpeech.NO_ENGINE); return; }
                    tts.setOnUtteranceProgressListener(new Progress());
                    tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                    String why = configure(lang, voiceName);
                    if (!why.isEmpty()) { fail(why, Taal.T("Er is geen stem voor deze taal op je telefoon.")); return; }
                    tts.setSpeechRate(rate);
                    ready = true;
                    int p = pendingIdx; pendingIdx = -1;
                    if (p >= 0) speakFrom(p);
                } catch (Throwable e) { fail(NO_ENGINE, Taal.T("Voorlezen lukte niet")); }
            }));
        } catch (Throwable e) { initing = false; tts = null; fail(NO_ENGINE, AiSpeech.NO_ENGINE); }
    }

    /** Taal (BCP 47, "" = die van de app) en eventueel een gekozen stem; alleen stemmen op de telefoon. "" of NO_VOICE. */
    private String configure(String tag, String voice) {
        if (tts == null) return NO_ENGINE;
        Locale want = locale(this, tag);
        java.util.Set<Voice> all = null;
        try { all = tts.getVoices(); } catch (Throwable ignored) { }
        if (all != null && !voice.isEmpty()) for (Voice v : all)
            if (v.getName().equals(voice) && local(v) && sameLanguage(v.getLocale(), want) && tts.setVoice(v) == TextToSpeech.SUCCESS) return "";
        int r = TextToSpeech.LANG_NOT_SUPPORTED;
        try { r = tts.isLanguageAvailable(want); } catch (Throwable ignored) { }
        if (r < TextToSpeech.LANG_AVAILABLE) return NO_VOICE;
        Voice best = best(all, want);
        if (best != null && tts.setVoice(best) == TextToSpeech.SUCCESS) return "";
        if (all != null && !all.isEmpty() && best == null) return NO_VOICE; // er zijn alleen stemmen via internet
        return tts.setLanguage(want) >= TextToSpeech.LANG_AVAILABLE ? "" : NO_VOICE;
    }

    static Locale locale(Context c, String tag) {
        Locale l = tag == null || tag.isEmpty() ? null : Locale.forLanguageTag(tag);
        if (l == null || l.getLanguage().isEmpty()) l = Taal.en(c) ? Locale.UK : new Locale("nl", "NL");
        if (l.getCountry().isEmpty()) {
            if ("nl".equals(l.getLanguage())) l = new Locale("nl", "NL");
            else if ("en".equals(l.getLanguage())) l = Locale.UK;
        }
        return l;
    }

    static boolean local(Voice v) {
        try { return !v.isNetworkConnectionRequired() && !v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED); }
        catch (Throwable e) { return false; }
    }

    private static boolean sameLanguage(Locale a, Locale b) { return a != null && b != null && a.getLanguage().equals(b.getLanguage()); }

    /** Beste stem op de telefoon voor deze taal: zelfde land, dan kwaliteit, dan snelheid. */
    static Voice best(java.util.Set<Voice> all, Locale want) {
        if (all == null) return null;
        Voice best = null;
        for (Voice v : all) {
            if (!local(v) || !sameLanguage(v.getLocale(), want)) continue;
            if (best == null) { best = v; continue; }
            boolean country = v.getLocale().getCountry().equals(want.getCountry()), bestCountry = best.getLocale().getCountry().equals(want.getCountry());
            if (country != bestCountry) { if (country) best = v; continue; }
            if (v.getQuality() != best.getQuality()) { if (v.getQuality() > best.getQuality()) best = v; continue; }
            if (v.getLatency() < best.getLatency()) best = v;
        }
        return best;
    }

    private void shutEngine() {
        engineGen++; ready = false; initing = false;
        if (tts != null) { try { tts.stop(); } catch (Throwable ignored) { } try { tts.shutdown(); } catch (Throwable ignored) { } }
        tts = null;
    }

    // ---------- voortgang ----------

    private void mark(int i) {
        if (i < 0 || i >= EreaderSpeech.count(sentences)) return;
        start = sentences[2 * i]; end = sentences[2 * i + 1];
    }

    private void started(int i) {
        idx = i; mark(i);
        queue(i + 1, TextToSpeech.QUEUE_ADD); // altijd één zin vooruit, zonder pauze ertussen
        long now = System.currentTimeMillis();
        if (now - savedAt > 10_000) save();
        tell();
    }

    private void done(int i) {
        int n = EreaderSpeech.count(sentences);
        if (EreaderSpeech.sleepDue(System.currentTimeMillis(), sleepAt)) { sleepAt = 0; idx = Math.min(n - 1, i + 1); if (i + 1 >= n) nextChapter(true); else { mark(idx); pause(true); } return; }
        if (i + 1 < n) return; // de volgende zin staat al klaar
        nextChapter(sleepChapter);
    }

    /** Einde van het hoofdstuk: door naar het volgende (of daar gepauzeerd blijven staan bij de slaaptimer). */
    private void nextChapter(boolean thenPause) {
        int next = EreaderSpeech.neighbor(linear, chapterCount, chapter, 1);
        if (next < 0) { finished(); return; }
        if (thenPause) { sleepChapter = false; pause(true); status = "paused"; }
        gen++;
        load(bookId, next, 0, false);
    }

    private void finished() {
        save();
        gen++;
        if (tts != null) try { tts.stop(); } catch (Throwable ignored) { }
        status = "paused"; code = "end"; error = "";
        focus(false); noisy(false);
        update(); tell();
        detach();
    }

    private void step(int dir) {
        int n = EreaderSpeech.count(sentences), to = idx + dir;
        boolean playing = speaking();
        if (to >= 0 && to < n) {
            if (playing) speakFrom(to); else { idx = to; mark(to); update(); tell(); }
            return;
        }
        int ch = EreaderSpeech.neighbor(linear, chapterCount, chapter, dir);
        if (ch < 0) return;
        if (!playing) status = "paused";
        gen++; if (tts != null) try { tts.stop(); } catch (Throwable ignored) { }
        load(bookId, ch, 0, dir < 0);
    }

    private void pause(boolean user) {
        gen++;
        if (tts != null) try { tts.stop(); } catch (Throwable ignored) { }
        if ("stopped".equals(status)) return;
        status = "paused";
        save();
        if (user) { resumeOnFocus = false; focus(false); }
        noisy(false);
        update(); tell();
        detach();
    }

    private void fail(String why, String message) {
        gen++;
        if (tts != null) try { tts.stop(); } catch (Throwable ignored) { }
        if (NO_VOICE.equals(why) || NO_ENGINE.equals(why)) shutEngine();
        status = "error"; code = why; error = Taal.tx(this, message);
        focus(false); noisy(false);
        tell();
        stopFgRemove();
    }

    /** Leesplek bewaren (op de achtergrond), zodat de e-reader daar verdergaat. */
    private void save() {
        savedAt = System.currentTimeMillis();
        final String id = bookId; final int ch = chapter, at = start, len = text.length();
        if (id.isEmpty()) return;
        final Context app = getApplicationContext();
        IO.execute(() -> { try { Ereader.position(app, id, ch, EreaderSpeech.offset(at, len), at); } catch (Throwable ignored) { } });
    }

    /** Radio en podcast pauzeren (plek bewaard), en de assistent stoppen: er klinkt er altijd één. */
    private void yieldOthers() {
        try {
            if (PodcastService.inst != null && ("playing".equals(PodcastService.status) || "connecting".equals(PodcastService.status))) PodcastService.send(this, PodcastService.YIELD, null);
            if (RadioService.inst != null && ("playing".equals(RadioService.status) || "connecting".equals(RadioService.status))) RadioService.send(this, RadioService.PAUSE, null);
            AiSpeech.stop();
        } catch (Throwable ignored) { }
    }

    // ---------- audiofocus en koptelefoon ----------

    private boolean focus(boolean on) {
        try {
            if (am == null) return true;
            if (Build.VERSION.SDK_INT >= 26) {
                if (on) {
                    if (focusReq == null) focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                            .setWillPauseWhenDucked(true).setOnAudioFocusChangeListener(this, h).build();
                    return am.requestAudioFocus(focusReq) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                }
                if (focusReq != null) am.abandonAudioFocusRequest(focusReq);
            } else {
                if (on) return am.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                am.abandonAudioFocus(this);
            }
        } catch (Throwable ignored) { }
        return true;
    }

    @Override
    public void onAudioFocusChange(int change) {
        try {
            if (change == AudioManager.AUDIOFOCUS_LOSS) { if (speaking()) pause(true); }
            else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                if (speaking()) { pause(false); resumeOnFocus = true; }
            } else if (change == AudioManager.AUDIOFOCUS_GAIN && resumeOnFocus && "paused".equals(status)) { resumeOnFocus = false; speakFrom(idx); }
        } catch (Throwable ignored) { }
    }

    private void noisy(boolean on) {
        try {
            if (on && !noisyOn) {
                IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
                if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, f, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(noisy, f);
                noisyOn = true;
            } else if (!on && noisyOn) { unregisterReceiver(noisy); noisyOn = false; }
        } catch (Throwable ignored) { }
    }

    // ---------- melding en mediasessie ----------

    private void startFg() {
        createChannel(this);
        try {
            Notification n = Taal.n(this, notif());
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTIF_ID, n);
            foreground = true;
        } catch (Throwable e) { foreground = false; }
    }

    /** Gepauzeerd: de melding mag weggeveegd worden (en de service stoppen), maar blijft voorlopig staan. */
    private void detach() { if (foreground) { try { stopForeground(STOP_FOREGROUND_DETACH); } catch (Throwable ignored) { } foreground = false; } }

    private void stopFgRemove() {
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Throwable ignored) { }
        foreground = false;
        try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIF_ID); } catch (Throwable ignored) { }
    }

    private void update() {
        if (inst != this) return;
        try {
            session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, chapterName).putString(MediaMetadata.METADATA_KEY_ALBUM, title).build());
            int st = "playing".equals(status) ? PlaybackState.STATE_PLAYING : "starting".equals(status) ? PlaybackState.STATE_BUFFERING
                    : "paused".equals(status) ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_STOPPED;
            session.setPlaybackState(new PlaybackState.Builder().setActions(ACTIONS).setState(st, PlaybackState.PLAYBACK_POSITION_UNKNOWN, speaking() ? rate : 0f).build());
            session.setActive(!"stopped".equals(status));
            if (speaking() && !foreground) startFg();
            else if (!"stopped".equals(status)) ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, Taal.n(this, notif()));
        } catch (Throwable ignored) { }
    }

    private PendingIntent action(String a, int code) {
        Intent i = new Intent(this, EreaderSpeakService.class).setAction(a);
        if ((RESUME.equals(a) || TOGGLE.equals(a)) && Build.VERSION.SDK_INT >= 26) return PendingIntent.getForegroundService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private android.graphics.drawable.Icon icon(int res) { return android.graphics.drawable.Icon.createWithResource(this, res); }

    private Notification notif() {
        boolean playing = speaking();
        String sub = chapterName;
        if (sleepAt > 0) sub += " · " + Taal.t(this, "stopt om {tijd}", "tijd", Regio.time(this, sleepAt));
        else if (sleepChapter) sub += " · " + Taal.t(this, "stopt aan het einde van het hoofdstuk");
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, NotifCenter.ch(this, CHANNEL)) : new Notification.Builder(this);
        Intent open = new Intent(this, MainActivity.class).putExtra("open", "ereader-lezen").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Taal.keep(b, Notification.EXTRA_TITLE, Notification.EXTRA_TEXT); // boektitel en hoofdstuk zijn gegevens
        b.setSmallIcon(R.drawable.ic_notif).setContentTitle(title.isEmpty() ? Taal.t(this, "Voorlezen") : title).setContentText(sub)
                .setContentIntent(PendingIntent.getActivity(this, 52, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setDeleteIntent(action(STOP, 53))
                .setOngoing(playing).setOnlyAlertOnce(true).setShowWhen(false).setVisibility(Notification.VISIBILITY_PUBLIC);
        b.addAction(new Notification.Action.Builder(icon(R.drawable.ic_prev), Taal.t(this, "Vorige zin"), action(PREV, 54)).build())
                .addAction(new Notification.Action.Builder(icon(playing ? R.drawable.ic_pause : R.drawable.ic_play), playing ? Taal.t(this, "Pauzeren") : Taal.t(this, "Verder voorlezen"), action(playing ? PAUSE : RESUME, 55)).build())
                .addAction(new Notification.Action.Builder(icon(R.drawable.ic_next), Taal.t(this, "Volgende zin"), action(NEXT, 56)).build())
                .addAction(new Notification.Action.Builder(icon(R.drawable.ic_stop), Taal.t(this, "Stoppen"), action(STOP, 57)).build());
        b.setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    static void createChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, Taal.t(c, "Voorlezen (e-reader)"), NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ((NotificationManager) c.getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
    }

    private void stopAll() {
        if (!"stopped".equals(status) && !bookId.isEmpty() && sentences.length > 0) save();
        gen++;
        shutEngine();
        h.removeCallbacksAndMessages(null);
        focus(false); noisy(false);
        status = "stopped"; error = ""; code = ""; sleepAt = 0; sleepChapter = false;
        try { session.setPlaybackState(new PlaybackState.Builder().setActions(ACTIONS).setState(PlaybackState.STATE_STOPPED, 0, 0f).build()); session.setActive(false); } catch (Throwable ignored) { }
        stopFgRemove();
        tell();
        stopSelfResult(lastStartId);
    }

    @Override
    public void onDestroy() {
        if (inst == this) inst = null;
        gen++;
        h.removeCallbacksAndMessages(null);
        shutEngine();
        focus(false); noisy(false);
        try { session.release(); } catch (Throwable ignored) { }
        if (!foreground) try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIF_ID); } catch (Throwable ignored) { }
        if (speaking() || "paused".equals(status)) { status = "stopped"; tell(); }
        super.onDestroy();
    }

    // ---------- stemmen kiezen ----------

    interface Sink { void done(String json); }

    /**
     * Talen en stemmen op de telefoon (alleen stemmen zonder internet) voor de keuzelijst; antwoord via sink met
     * {state: ok|noengine, lang, langs:[{tag,label}], voices:[{name,label}]}. Gebruikt de lopende motor of een tijdelijke.
     */
    static void voices(Context c, String tag, Sink sink) {
        final Context app = c.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                EreaderSpeakService s = inst;
                if (s != null && s.tts != null && s.ready) { sink.done(voicesJson(app, s.tts, tag)); return; }
                final TextToSpeech[] probe = new TextToSpeech[1];
                probe[0] = new TextToSpeech(app, st -> new Handler(Looper.getMainLooper()).post(() -> {
                    try { sink.done(st == TextToSpeech.SUCCESS ? voicesJson(app, probe[0], tag) : "{\"state\":\"noengine\"}"); }
                    catch (Throwable e) { sink.done("{\"state\":\"noengine\"}"); }
                    finally { try { probe[0].shutdown(); } catch (Throwable ignored) { } }
                }));
            } catch (Throwable e) { sink.done("{\"state\":\"noengine\"}"); }
        });
    }

    private static String voicesJson(Context c, TextToSpeech t, String tag) throws Exception {
        Locale want = locale(c, tag), shown = Taal.en(c) ? Locale.ENGLISH : new Locale("nl");
        java.util.Set<Voice> all = t.getVoices();
        java.util.TreeMap<String, String> langs = new java.util.TreeMap<>();
        JSONArray voices = new JSONArray();
        int n = 0;
        if (all != null) {
            java.util.List<Voice> list = new java.util.ArrayList<>(all);
            java.util.Collections.sort(list, (x, y) -> x.getName().compareTo(y.getName()));
            for (Voice v : list) {
                if (!local(v) || v.getLocale() == null) continue;
                String lt = v.getLocale().getLanguage();
                if (!lt.isEmpty()) langs.put(lt, cap(new Locale(lt).getDisplayLanguage(shown)));
                if (!sameLanguage(v.getLocale(), want)) continue;
                n++;
                voices.put(new JSONObject().put("name", v.getName()).put("label", Taal.t(c, "{taal}, stem {n}", "taal", cap(v.getLocale().getDisplayName(shown)), "n", n)));
            }
        }
        JSONArray l = new JSONArray();
        for (java.util.Map.Entry<String, String> e : langs.entrySet()) l.put(new JSONObject().put("tag", e.getKey()).put("label", e.getValue()));
        return new JSONObject().put("state", "ok").put("lang", want.getLanguage()).put("langs", l).put("voices", voices).toString();
    }

    private static String cap(String s) { return s == null || s.isEmpty() ? "" : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1); }
}
