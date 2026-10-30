package nl.rene.tools;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import org.json.JSONObject;

/**
 * Gesprek met de assistent (handsfree): luisteren, merken dat je uitgesproken bent, het antwoord voorlezen en dan weer
 * luisteren. Het luisteren zelf is de live-opname van VoiceNote (whisper op de telefoon); hier zit:
 *  - End: aan het geluidsniveau zien dat je klaar bent met praten (eerst spraak, dan een tijdje stil). Zuiver (getest).
 *  - Barge: tijdens het voorlezen merken dat je begint te praten (onderbreken), met voorzichtigheid voor de eigen stem
 *    van de telefoon die de microfoon ook hoort: eerst leren hoe hard die echo is, dan pas reageren op duidelijk harder
 *    geluid dat even aanhoudt. Zuiver (getest).
 *  - de luisteraar tijdens het voorlezen (AudioRecord met de echo-onderdrukking van de telefoon), en of er een
 *    telefoongesprek is. Er wordt niets opgeslagen en er gaat niets van de telefoon af.
 */
final class AiTalk {
    private AiTalk() { }

    /** Einde van spreken: 0 = nog bezig, 1 = klaar (na spraak stil), 2 = niets gehoord, 3 = te lang (ook klaar). */
    static final int BUSY = 0, DONE = 1, NONE = 2, LONG = 3;

    static final class End {
        final long silenceMs, noSpeechMs, maxMs;
        static final float MIN_THR = 0.33f, MARGIN = 0.15f, HYST = 0.05f;
        static final long START_MS = 200, MIN_SPEECH_MS = 300;
        float floor = -1f;
        long t, above, quiet, speechMs;
        boolean speech;
        int state = BUSY;

        End(long silenceMs) { this(silenceMs, 8_000, 60_000); }
        End(long silenceMs, long noSpeechMs, long maxMs) {
            this.silenceMs = Math.max(500, Math.min(4000, silenceMs)); this.noSpeechMs = noSpeechMs; this.maxMs = maxMs;
        }

        float threshold() { return Math.max(MIN_THR, Math.min(0.75f, (floor < 0 ? 0 : floor) + MARGIN)); }

        /** Eén stukje geluid: niveau 0–1 (VoiceNote.level), duur in ms. */
        int feed(float lv, long dt) {
            if (state != BUSY) return state;
            t += dt;
            float thr = threshold();
            boolean loud = lv >= thr, still = lv < thr - HYST;
            // Achtergrondgeluid: snel omlaag, langzaam omhoog, en niet tijdens praten
            if (floor < 0) floor = Math.min(lv, 0.35f);
            else if (lv < floor) floor = floor * 0.6f + lv * 0.4f;
            else if (!loud) floor = floor * 0.97f + lv * 0.03f;
            if (loud) {
                above += dt; quiet = 0;
                if (!speech && above >= START_MS) speech = true;
                if (speech) speechMs += dt;
            } else {
                above = Math.max(0, above - dt);
                if (speech && still) quiet += dt; else if (speech) quiet += dt / 2;
            }
            if (speech && speechMs >= MIN_SPEECH_MS && quiet >= silenceMs) return state = DONE;
            if (!speech && t >= noSpeechMs) return state = NONE;
            if (t >= maxMs) return state = speech ? LONG : NONE;
            return BUSY;
        }
    }

    static final class Barge {
        static final long LEARN_MS = 600, HOLD_MS = 350;
        static final float MIN_THR = 0.5f, MARGIN = 0.2f, MAX_THR = 0.93f;
        float echo;
        long t, above;
        boolean hit;

        float threshold() { return Math.max(MIN_THR, Math.min(MAX_THR, echo + MARGIN)); }

        /** Tijdens het voorlezen: true zodra je duidelijk door de stem van de telefoon heen praat. */
        boolean feed(float lv, long dt) {
            if (hit) return true;
            t += dt;
            if (t <= LEARN_MS) { echo = Math.max(echo, lv); return false; } // eerst: hoe hard klinkt de eigen stem
            float thr = threshold();
            if (lv >= thr) above += dt;
            else { above = Math.max(0, above - dt / 2); echo = lv > echo ? echo + (lv - echo) * 0.05f : echo * 0.995f + lv * 0.005f; }
            return hit = above >= HOLD_MS;
        }
    }

    // ---------- tijdens het voorlezen luisteren (onderbreken) ----------

    /** De opname met zijn echo-onderdrukking en ruisonderdrukking: samen opruimen. */
    static final class Rec {
        final AudioRecord r;
        android.media.audiofx.AudioEffect aec, ns;
        Rec(AudioRecord r) { this.r = r; }
        void release() {
            try { r.stop(); } catch (Throwable ignored) { }
            if (aec != null) try { aec.release(); } catch (Throwable ignored) { }
            if (ns != null) try { ns.release(); } catch (Throwable ignored) { }
            try { r.release(); } catch (Throwable ignored) { }
        }
    }

    /** Hooguit zo lang luisteren (een antwoord voorlezen duurt korter), en alleen zolang de interface kijkt (aiBarge). */
    static final long BARGE_MAX_MS = 120_000, BARGE_POLL_MS = 3_000;
    /** Moet het luisteren stoppen? Zuiver (getest): te lang bezig, of de interface vroeg al even niets meer. */
    static boolean bargeExpired(long startMs, long lastPollMs, long nowMs) {
        return nowMs - startMs >= BARGE_MAX_MS || nowMs - lastPollMs >= BARGE_POLL_MS;
    }

    private static final java.util.concurrent.atomic.AtomicReference<Rec> AR = new java.util.concurrent.atomic.AtomicReference<>();
    private static volatile Thread th;
    private static volatile boolean on, hitNow;
    private static volatile float lvNow;
    private static volatile String why = "";
    private static volatile long lastPoll;
    private static long nowMs() { return System.nanoTime() / 1_000_000L; }

    /** "" als het luisteren loopt; anders waarom niet (dan werkt alleen de knop Onderbreken). */
    static synchronized String bargeStart(Context c) {
        bargeStopNow();
        hitNow = false; lvNow = 0; why = "";
        if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return why = "mic";
        if (!"idle".equals(VoiceNote.state) || conflict(c) != null) return why = "bezet";
        AudioRecord r = null;
        Rec rec = null;
        try {
            int rate = VoiceNote.RATE;
            int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            // Spraakbron voor bellen: daar zet de telefoon zijn echo-onderdrukking op (de eigen stem telt dan minder mee)
            r = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate));
            if (r.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException();
            rec = new Rec(r);
            try { if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) { rec.aec = android.media.audiofx.AcousticEchoCanceler.create(r.getAudioSessionId()); if (rec.aec != null) rec.aec.setEnabled(true); } } catch (Throwable ignored) { }
            try { if (android.media.audiofx.NoiseSuppressor.isAvailable()) { rec.ns = android.media.audiofx.NoiseSuppressor.create(r.getAudioSessionId()); if (rec.ns != null) rec.ns.setEnabled(true); } } catch (Throwable ignored) { }
            r.startRecording();
            if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException();
        } catch (Throwable t) {
            if (rec != null) rec.release(); else if (r != null) try { r.release(); } catch (Throwable ignored) { }
            return why = "Onderbreken door te praten lukt niet op deze telefoon";
        }
        lastPoll = nowMs();
        AR.set(rec); on = true;
        final Rec my = rec;
        th = new Thread(() -> loop(my), "gesprek-onderbreken");
        th.start();
        return "";
    }

    private static void loop(Rec rec) {
        AudioRecord r = rec.r;
        try {
            short[] buf = new short[VoiceNote.RATE / 20]; // 50 ms
            Barge b = new Barge();
            long start = nowMs();
            while (on) {
                // Nooit eindeloos de microfoon open: na 2 minuten, of als de interface niet meer kijkt, stoppen
                if (bargeExpired(start, lastPoll, nowMs())) { why = "tijd"; break; }
                int n = r.read(buf, 0, buf.length);
                if (n < 0) break;
                if (n == 0) continue;
                float lv = VoiceNote.level(buf, n);
                lvNow = lv;
                if (b.feed(lv, n * 1000L / VoiceNote.RATE)) { hitNow = true; break; }
            }
        } catch (Throwable ignored) {
        } finally {
            // Zelf gestopt (onderbroken, of de microfoon viel weg): zelf opruimen; anders doet bargeStop dat
            if (AR.compareAndSet(rec, null)) { on = false; rec.release(); }
        }
    }

    static synchronized void bargeStop() { bargeStopNow(); }

    private static void bargeStopNow() {
        on = false;
        Thread t = th; th = null;
        Rec rec = AR.getAndSet(null);
        if (rec != null) { try { rec.r.stop(); } catch (Throwable ignored) { } } // read() komt dan meteen terug
        if (t != null && t != Thread.currentThread()) try { t.join(400); } catch (Throwable ignored) { }
        if (rec != null) rec.release();
    }

    static String bargeJson() {
        lastPoll = nowMs(); // de interface kijkt nog
        try { return new JSONObject().put("on", on).put("hit", hitNow).put("lv", Math.round(lvNow * 100) / 100.0).put("why", why).toString(); } catch (Exception e) { return "{}"; }
    }

    // ---------- wat er verder speelt ----------

    /** Een telefoongesprek (gaat over, of loopt), ook van WhatsApp en dergelijke. */
    static boolean inCall(Context c) {
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            int m = am == null ? AudioManager.MODE_NORMAL : am.getMode();
            if (m == AudioManager.MODE_RINGTONE || m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION) return true;
        } catch (Throwable ignored) { }
        try {
            if (c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
                @SuppressWarnings("deprecation") int st = tm == null ? 0 : tm.getCallState();
                return st != android.telephony.TelephonyManager.CALL_STATE_IDLE;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /** Wat de microfoon nu gebruikt (dan geen gesprek met de assistent), of null. */
    static String conflict(Context c) {
        if (VoiceNote.micForSleep()) return "Slaappraat luistert nu; stop dat eerst";
        if (CallRecorderService.busy) return "Er wordt een gesprek opgenomen";
        if (!"idle".equals(Music.state) && !"done".equals(Music.state) && !"error".equals(Music.state)) return "De microfoon wordt gebruikt voor muziek herkennen";
        if (inCall(c)) return "Je bent aan het bellen";
        return null;
    }

    static String pollJson(Context c) {
        try {
            String cf = conflict(c);
            return new JSONObject().put("call", inCall(c)).put("conflict", cf == null ? "" : cf).toString();
        } catch (Throwable e) { return "{}"; }
    }
}
