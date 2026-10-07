package nl.rene.tools;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * Spraaknotitie: inspreken in de app (alleen zolang het scherm open is), daarna op de telefoon uitschrijven
 * met het spraakmodel van Gesprekken uitschrijven. Er gaat geen geluid of tekst de telefoon uit;
 * de opname wordt na het uitschrijven weggegooid.
 */
final class VoiceNote {

    private VoiceNote() { }

    static final long MAX_MS = 5 * 60_000L;
    private static MediaRecorder rec;
    private static File file;
    private static long since;
    private static int run = 0;
    static volatile String state = "idle"; // idle, recording, working

    static synchronized String start(Context c, Done onAutoStop) {
        if (rec != null) return "";
        if ("working".equals(state)) return "Even wachten: de vorige spraaknotitie wordt nog uitgeschreven";
        if (TranscribeService.busy) return "Er worden nu gesprekken uitgeschreven; probeer het zo opnieuw";
        if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return "mic";
        if (!Transcribe.supported()) return "Uitschrijven werkt niet op deze telefoon";
        if (Transcribe.activeModel(c) == null) return "model";
        if (!"idle".equals(Music.state) && !"done".equals(Music.state) && !"error".equals(Music.state)) return "De microfoon wordt gebruikt voor muziek herkennen";
        if (CallRecorderService.busy) return "De microfoon wordt gebruikt voor een gespreksopname";
        try {
            run++;
            file = new File(c.getCacheDir(), "spraaknotitie-" + run + ".m4a");
            file.delete();
            rec = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(c) : new MediaRecorder();
            rec.setAudioSource(MediaRecorder.AudioSource.MIC);
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            rec.setAudioChannels(1); rec.setAudioSamplingRate(16000); rec.setAudioEncodingBitRate(48000);
            rec.setMaxDuration((int) MAX_MS);
            rec.setOutputFile(file.getPath());
            final Context app = c.getApplicationContext();
            // Na 5 minuten stopt Android de opname zelf: dan meteen uitschrijven
            rec.setOnInfoListener((mr, what, extra) -> { if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stop(app, onAutoStop); });
            rec.prepare(); rec.start();
            since = System.currentTimeMillis();
            state = "recording";
            return "";
        } catch (Exception e) {
            release();
            return "Opnemen lukt niet";
        }
    }

    static synchronized void release() {
        if (rec != null) { try { rec.release(); } catch (Exception ignored) { } rec = null; }
        state = "idle";
    }

    static synchronized void cancel() {
        if (rec == null) return; // tijdens het uitschrijven niets afbreken (dat loopt vanzelf af)
        try { rec.stop(); } catch (Exception ignored) { }
        release();
        if (file != null) file.delete();
    }

    static long elapsed() { return "recording".equals(state) ? System.currentTimeMillis() - since : 0; }

    interface Done { void done(String json); }

    /** Stoppen en uitschrijven op de achtergrond; done krijgt {text} of {error}. */
    static void stop(Context c, Done done) {
        final File f;
        final int myRun;
        synchronized (VoiceNote.class) {
            if (rec == null) { done.done("{\"error\":\"Er wordt niet opgenomen\"}"); return; }
            try { rec.stop(); } catch (Exception ignored) { }
            try { rec.release(); } catch (Exception ignored) { }
            rec = null;
            f = file;
            myRun = run;
            state = "working";
        }
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            String r;
            File wav = new File(app.getCacheDir(), "spraaknotitie-" + myRun + ".wav");
            try {
                Transcribe.cancel = false; // een eerder "Stoppen" bij Gesprekken uitschrijven mag dit niet afbreken
                if (f == null || f.length() < 2000) throw new Exception("Te kort: houd de knop ingedrukt terwijl je praat");
                long dur = Transcribe.durationMs(app, f);
                if (dur < 600) throw new Exception("Te kort: houd de knop ingedrukt terwijl je praat");
                Transcribe.toWav(f, wav, dur, (ph, pct) -> { });
                JSONObject out = Transcribe.whisper(app, wav, Transcribe.activeModel(app), (ph, pct) -> { });
                JSONArray tr = out.optJSONArray("transcription");
                StringBuilder all = new StringBuilder();
                if (tr != null) for (int i = 0; i < tr.length(); i++) {
                    String t = tr.getJSONObject(i).optString("text").trim();
                    if (!t.isEmpty() && !t.startsWith("[") && !t.startsWith("(")) all.append(all.length() > 0 ? " " : "").append(t);
                }
                if (all.length() == 0) throw new Exception("Er is geen spraak herkend");
                r = new JSONObject().put("text", all.toString()).toString();
            } catch (Throwable e) {
                String m = e instanceof OutOfMemoryError ? "Onvoldoende geheugen" : e.getMessage() == null ? "Uitschrijven lukt niet" : e.getMessage();
                r = "{\"error\":" + JSONObject.quote(m) + "}";
            } finally {
                if (f != null) f.delete();
                wav.delete();
                synchronized (VoiceNote.class) { if (run == myRun && rec == null) state = "idle"; }
            }
            done.done(r);
        }, "spraaknotitie").start();
    }
}
