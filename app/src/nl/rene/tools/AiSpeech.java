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

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Voorlezen van antwoorden van de assistent met de tekst-naar-spraak van de telefoon (Android TextToSpeech), met een
 * Nederlandse stem. Alles op de hoofdthread; de interface hoort via onAiSpeak({state: start|done|stop|error, id, error}).
 * Bij weggaan uit de app (MainActivity.onPause) stopt het en wordt de spraakmotor vrijgegeven.
 */
final class AiSpeech {
    private AiSpeech() { }

    interface Sink { void event(JSONObject o); }

    private static Handler h;
    /** Pas bij het eerste gebruik (de zuivere functies hierboven werken zo ook buiten Android, in de tests). */
    private static synchronized Handler h() { if (h == null) h = new Handler(Looper.getMainLooper()); return h; }
    private static TextToSpeech tts;
    private static boolean ready, initing;
    private static String pendText, pendId, curId = "";
    private static int curParts, gen; // gen: welke spraakmotor (een late melding van een vrijgegeven motor telt niet)
    private static Sink sink;
    private static Context app;
    private static AudioFocusRequest focusReq;
    private static AudioManager.OnAudioFocusChangeListener focusL;

    static final String NO_ENGINE = "Je telefoon heeft geen voorleesfunctie (tekst-naar-spraak). Installeer bijvoorbeeld Spraakservices van Google uit de Play Store.";
    static final String NO_DUTCH = "Er is geen Nederlandse stem op je telefoon. Voeg er een toe bij Instellingen → Systeem → Talen → Tekst-naar-spraak (bijvoorbeeld bij Spraakservices van Google).";

    // ---------- zuiver (getest in AiActionsTest) ----------

    private static final Pattern CITE = Pattern.compile("\\s*\\[\\d+\\]");

    /** Tekst zonder opmaak om voor te lezen: geen sterretjes, kopjes, opsommingstekens of verwijzingen als [1]. */
    static String plain(String md) {
        if (md == null) return "";
        StringBuilder out = new StringBuilder();
        for (String raw : md.split("\n")) {
            String l = raw.trim();
            if (l.isEmpty() || l.matches("[-*_=]{3,}")) continue;
            l = l.replaceAll("^#{1,6}\\s+", "").replaceAll("^[-*•]\\s+", "").replaceAll("^\\d+[.)]\\s+", "").replaceAll("^>\\s*", "");
            l = l.replaceAll("\\*\\*([^*]+)\\*\\*", "$1").replaceAll("__([^_]+)__", "$1").replaceAll("(^|[\\s(])\\*([^*\\s][^*]*)\\*", "$1$2").replaceAll("`([^`]*)`", "$1");
            l = l.replaceAll("\\[([^\\]]+)\\]\\((?:https?://)?[^)]*\\)", "$1"); // [tekst](link) → tekst
            l = CITE.matcher(l).replaceAll("");
            l = l.replaceAll("[*_`#|]", " ").replaceAll("\\s+", " ").replaceAll("\\s+([.,!?;:])", "$1").trim();
            if (l.isEmpty()) continue;
            if (!l.matches(".*[.!?:;…]$")) l += ".";
            if (out.length() > 0) out.append('\n');
            out.append(l);
        }
        return out.toString();
    }

    private static final Pattern SENT = Pattern.compile("[^.!?…\\n]*[.!?…\\n]+|[^.!?…\\n]+$");

    /** In stukken die de spraakmotor aankan (bij voorkeur op een zinsgrens). */
    static List<String> chunks(String text, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        Matcher m = SENT.matcher(text == null ? "" : text);
        while (m.find()) {
            String s = m.group();
            if (s.isEmpty()) continue;
            while (s.length() > max) { // één enorme zin: hard knippen
                if (cur.length() > 0) { out.add(cur.toString().trim()); cur.setLength(0); }
                int n = max; if (Character.isHighSurrogate(s.charAt(n - 1))) n--;
                out.add(s.substring(0, n).trim()); s = s.substring(n);
            }
            if (cur.length() + s.length() > max) { out.add(cur.toString().trim()); cur.setLength(0); }
            cur.append(s);
        }
        if (cur.toString().trim().length() > 0) out.add(cur.toString().trim());
        out.removeIf(String::isEmpty);
        return out;
    }

    // ---------- de spraakmotor ----------

    private static void send(String state, String id, String err) {
        Sink s = sink;
        if (s == null) return;
        try {
            JSONObject o = new JSONObject().put("state", state).put("id", id == null ? "" : id);
            if (err != null) o.put("error", err);
            s.event(o);
        } catch (Throwable ignored) { }
    }

    /** Voorlezen (het vorige stopt). id komt terug in de meldingen, zodat de interface weet welk antwoord het is. */
    static void speak(Context c, String text, String id, Sink s) {
        final String t = plain(text);
        h().post(() -> {
            try {
                app = c.getApplicationContext(); sink = s;
                if (t.isEmpty()) { send("done", id, null); return; }
                stopNow(false);
                pendText = t; pendId = id == null ? "" : id;
                if (tts != null && ready) { flush(); return; }
                if (initing) return;
                initing = true; ready = false;
                final int g = ++gen;
                try { tts = new TextToSpeech(app, st -> onInit(st, g)); }
                catch (Throwable e) { initing = false; tts = null; String pid = pendId; pendText = null; send("error", pid, NO_ENGINE); }
            } catch (Throwable e) { send("error", id, "Voorlezen lukte niet"); }
        });
    }

    private static void onInit(int status, int g) {
        h().post(() -> {
            try {
                if (g != gen || tts == null) return; // intussen vrijgegeven (of een nieuwe motor)
                initing = false;
                String pid = pendId;
                if (status != TextToSpeech.SUCCESS) { pendText = null; release(); send("error", pid, NO_ENGINE); return; }
                Locale nl = null;
                for (Locale l : new Locale[]{new Locale("nl", "NL"), new Locale("nl", "BE"), new Locale("nl")}) {
                    int r = tts.isLanguageAvailable(l);
                    if (r >= TextToSpeech.LANG_AVAILABLE) { nl = l; break; }
                }
                if (nl == null || tts.setLanguage(nl) < TextToSpeech.LANG_AVAILABLE) { pendText = null; release(); send("error", pid, NO_DUTCH); return; }
                tts.setOnUtteranceProgressListener(new Progress());
                tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                ready = true;
                flush();
            } catch (Throwable e) { pendText = null; release(); send("error", pendId, NO_ENGINE); }
        });
    }

    private static void flush() {
        String t = pendText, id = pendId;
        pendText = null;
        if (t == null || tts == null) return;
        List<String> parts = chunks(t, Math.max(500, Math.min(3900, TextToSpeech.getMaxSpeechInputLength() - 100)));
        if (parts.isEmpty()) { send("done", id, null); return; }
        focus(true);
        curId = id; curParts = parts.size();
        for (int i = 0; i < parts.size(); i++) {
            int r = tts.speak(parts.get(i), i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, new Bundle(), id + "#" + i);
            if (r != TextToSpeech.SUCCESS) { curId = ""; tts.stop(); focus(false); send("error", id, "Voorlezen lukte niet"); return; }
        }
    }

    /** Stoppen (knop, nieuwe vraag, weg uit de assistent). */
    static void stop() { h().post(() -> { try { stopNow(true); } catch (Throwable ignored) { } }); }

    private static void stopNow(boolean tell) {
        String id = curId;
        curId = ""; pendText = null;
        if (tts != null) try { tts.stop(); } catch (Throwable ignored) { }
        focus(false);
        if (tell && !id.isEmpty()) send("stop", id, null);
    }

    /** Weg uit de app: stoppen en de spraakmotor vrijgeven (hij start de volgende keer opnieuw). */
    static void release() {
        Runnable r = () -> {
            try {
                String id = curId;
                curId = ""; pendText = null; ready = false; initing = false; gen++;
                if (tts != null) { try { tts.stop(); } catch (Throwable ignored) { } try { tts.shutdown(); } catch (Throwable ignored) { } }
                tts = null;
                focus(false);
                if (!id.isEmpty()) send("stop", id, null);
            } catch (Throwable ignored) { }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run(); else h().post(r);
    }

    /** Geluid van andere apps (muziek, radio) even zachter zolang er voorgelezen wordt. */
    private static void focus(boolean on) {
        try {
            AudioManager am = app == null ? null : (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            if (focusL == null) focusL = ch -> { if (ch == AudioManager.AUDIOFOCUS_LOSS || ch == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop(); };
            if (Build.VERSION.SDK_INT >= 26) {
                if (on) {
                    focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                            .setOnAudioFocusChangeListener(focusL, h()).build();
                    am.requestAudioFocus(focusReq);
                } else if (focusReq != null) { am.abandonAudioFocusRequest(focusReq); focusReq = null; }
            } else {
                if (on) am.requestAudioFocus(focusL, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
                else am.abandonAudioFocus(focusL);
            }
        } catch (Throwable ignored) { }
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
