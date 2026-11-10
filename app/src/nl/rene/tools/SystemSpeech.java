package nl.rene.tools;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Terugval voor de assistent zolang er geen lokale stem (Ronnie/LJ Speech) gedownload is: de tekst-naar-spraak van
 * Android, maar alleen met een stem die op de telefoon zelf werkt (geen netwerk, geen netwerksynthese; ingebouwd
 * voorkeur). Is er een lokale stem, dan gebruikt de assistent altijd ReadAloud. Alles op de hoofdthread; de interface
 * hoort het via onAiSpeak({state: start|done|stop|error, id, error, system: true}). Geen tekst in logs of op schijf.
 */
final class SystemSpeech {
    private SystemSpeech() { }
    interface Sink { void event(JSONObject o); }
    static final String NO_ENGINE = Taal.T("Je telefoon heeft geen voorleesstem die zonder internet werkt. Download een lokale stem bij Instellingen → Spraak.");
    static final String BUSY_AUDIO = "Voorlezen kan nu niet: er is iets anders bezig met geluid (bijvoorbeeld een gesprek).";
    private static Handler h;
    private static synchronized Handler h() { if (h == null) h = new Handler(Looper.getMainLooper()); return h; }
    private static TextToSpeech tts;
    private static boolean ready, initing;
    private static String pendText, pendId, curId = "";
    private static int curParts, gen;
    private static Sink sink;
    private static Context app;
    private static AudioFocusRequest focusReq;
    private static final AudioManager.OnAudioFocusChangeListener FOCUS = ch -> { if (ch == AudioManager.AUDIOFOCUS_LOSS || ch == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop(); };

    // ---------- zuiver (getest in SystemSpeechTest) ----------

    /**
     * Hoe geschikt een stem is; -1 = niet gebruiken. Alleen de gevraagde taal, nooit een stem die internet nodig heeft
     * (ook niet "eerst downloaden"), ingebouwde stemmen eerst, dan kwaliteit en snelheid.
     */
    static int score(String lang, String want, boolean network, boolean networkFeature, boolean notInstalled, boolean embedded, int quality, int latency) {
        if (lang == null || !lang.equalsIgnoreCase(want) || network || networkFeature || notInstalled) return -1;
        return (embedded ? 10_000 : 0) + Math.max(0, Math.min(500, quality)) * 10 + Math.max(0, 500 - Math.max(0, latency));
    }
    /** Tekst in stukken voor de motor (hele zinnen, hooguit max tekens). */
    static List<String> parts(String text, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int[] s : ReadAloud.segments(text == null ? "" : text, 0, max)) {
            String part = text.substring(s[0], s[1]);
            if (cur.length() > 0 && cur.length() + 1 + part.length() > max) { out.add(cur.toString()); cur.setLength(0); }
            if (cur.length() > 0) cur.append(' ');
            cur.append(part);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    // ---------- de spraakmotor van Android ----------

    private static void send(String state, String id, String err) {
        Sink s = sink;
        if (s == null) return;
        try {
            JSONObject o = new JSONObject().put("state", state).put("id", id == null ? "" : id).put("system", true);
            if (err != null) o.put("error", Taal.t(app, err)).put("code", NO_ENGINE.equals(err) ? "voice_missing" : "speech_error");
            s.event(o);
        } catch (Throwable ignored) { }
    }
    static boolean active() { return !curId.isEmpty() || pendText != null; }
    /** Voorlezen (het vorige stopt). */
    static void speak(Context c, String text, String id, Sink s) {
        h().post(() -> {
            try {
                app = c.getApplicationContext(); sink = s;
                if (text == null || text.trim().isEmpty()) { send("done", id, null); return; }
                if (!MainActivity.visible) { send("stop", id, null); return; }
                stopNow(false);
                pendText = text; pendId = id == null ? "" : id;
                if (tts != null && ready) { flush(); return; }
                if (initing) return;
                initing = true; ready = false;
                final int g = ++gen;
                try { tts = new TextToSpeech(app, st -> h().post(() -> onInit(st, g))); }
                catch (Throwable e) { initing = false; tts = null; String pid = pendId; pendText = null; send("error", pid, NO_ENGINE); }
            } catch (Throwable e) { send("error", id, "Voorlezen lukte niet"); }
        });
    }
    private static void onInit(int status, int g) {
        try {
            if (g != gen || tts == null) return;
            initing = false;
            String pid = pendId;
            if (status != TextToSpeech.SUCCESS || !pickVoice(Taal.en(app) ? "en" : "nl")) { pendText = null; release(); send("error", pid, NO_ENGINE); return; }
            tts.setOnUtteranceProgressListener(new Progress());
            tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            ready = true;
            flush();
        } catch (Throwable e) { pendText = null; release(); send("error", pendId, NO_ENGINE); }
    }
    /** De beste stem die zonder internet werkt; false = geen. */
    private static boolean pickVoice(String want) {
        Voice best = null; int top = -1;
        java.util.Set<Voice> all = null;
        try { all = tts.getVoices(); } catch (Throwable ignored) { }
        if (all == null) return false;
        for (Voice v : all) {
            try {
                java.util.Set<String> f = v.getFeatures();
                int sc = score(v.getLocale() == null ? null : v.getLocale().getLanguage(), want, v.isNetworkConnectionRequired(),
                        f != null && f.contains(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS), f != null && f.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED),
                        f != null && f.contains(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS), v.getQuality(), v.getLatency());
                if (sc > top) { top = sc; best = v; }
            } catch (Throwable ignored) { }
        }
        return best != null && tts.setVoice(best) == TextToSpeech.SUCCESS;
    }
    private static void flush() {
        String t = pendText, id = pendId;
        pendText = null;
        if (t == null || tts == null) return;
        List<String> parts = parts(t, Math.max(500, Math.min(3900, TextToSpeech.getMaxSpeechInputLength() - 100)));
        if (parts.isEmpty()) { send("done", id, null); return; }
        if (!MainActivity.visible) { send("stop", id, null); return; }
        if (!focus(true)) { send("error", id, BUSY_AUDIO); return; }
        curId = id; curParts = parts.size();
        for (int i = 0; i < parts.size(); i++) {
            // De stem is al gekozen op "werkt zonder internet" (pickVoice)
            int r = tts.speak(parts.get(i), i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, new Bundle(), id + "#" + i);
            if (r != TextToSpeech.SUCCESS) { curId = ""; tts.stop(); focus(false); send("error", id, "Voorlezen lukte niet"); return; }
        }
    }
    /** Stoppen (knop, nieuwe vraag, weg uit de app). */
    static void stop() { h().post(() -> { try { stopNow(true); } catch (Throwable ignored) { } }); }
    private static void stopNow(boolean tell) {
        String id = curId; curId = "";
        String pid = pendText != null ? pendId : null; pendText = null;
        try { if (tts != null && ready) tts.stop(); } catch (Throwable ignored) { }
        focus(false);
        if (tell && !id.isEmpty()) send("stop", id, null);
        if (tell && pid != null && !pid.isEmpty()) send("stop", pid, null);
    }
    /** Motor vrijgeven (app naar de achtergrond). */
    static void release() {
        h().post(() -> {
            try { stopNow(true); } catch (Throwable ignored) { }
            try { if (tts != null) tts.shutdown(); } catch (Throwable ignored) { }
            tts = null; ready = false; initing = false; gen++;
        });
    }
    private static boolean focus(boolean on) {
        try {
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                if (on) {
                    focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                            .setOnAudioFocusChangeListener(FOCUS, h()).build();
                    if (am.requestAudioFocus(focusReq) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { focusReq = null; return false; }
                } else if (focusReq != null) { am.abandonAudioFocusRequest(focusReq); focusReq = null; }
            } else {
                if (on) return am.requestAudioFocus(FOCUS, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                else am.abandonAudioFocus(FOCUS);
            }
        } catch (Throwable ignored) { }
        return true;
    }

    /** Meldingen van de spraakmotor (op een eigen thread): naar de hoofdthread. */
    static final class Progress extends UtteranceProgressListener {
        private static String idOf(String u) { int i = u == null ? -1 : u.lastIndexOf('#'); return i < 0 ? "" : u.substring(0, i); }
        private static int partOf(String u) { try { return Integer.parseInt(u.substring(u.lastIndexOf('#') + 1)); } catch (Throwable e) { return -1; } }
        @Override public void onStart(String u) { h().post(() -> { if (partOf(u) == 0 && idOf(u).equals(curId)) send("start", curId, null); }); }
        @Override public void onDone(String u) {
            h().post(() -> {
                try {
                    if (!idOf(u).equals(curId) || partOf(u) != curParts - 1) return;
                    String id = curId; curId = ""; focus(false); send("done", id, null);
                } catch (Throwable ignored) { }
            });
        }
        @Override @SuppressWarnings("deprecation") public void onError(String u) {
            h().post(() -> {
                try {
                    if (!idOf(u).equals(curId)) return;
                    String id = curId; curId = ""; focus(false); send("error", id, "Voorlezen lukte niet");
                } catch (Throwable ignored) { }
            });
        }
    }
}
