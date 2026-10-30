package nl.rene.tools;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * Spraaknotitie: inspreken in de app (alleen zolang het scherm open is), daarna op de telefoon uitschrijven
 * met het spraakmodel van Gesprekken uitschrijven. Er gaat geen geluid of tekst de telefoon uit;
 * de opname wordt na het uitschrijven weggegooid.
 *
 * De assistent gebruikt de live-versie (startLive): opnemen als 16 kHz PCM (AudioRecord), met het geluidsniveau voor
 * de golf in beeld en terwijl je praat steeds een tussentekst (whisper op de telefoon, op het stuk tot nu toe). Bij
 * stoppen wordt de hele opname nog één keer uitgeschreven met het gekozen spraakmodel: die tekst komt in het invoerveld.
 * Lukt AudioRecord niet, dan gewoon de gewone opname (MediaRecorder) zonder tussentekst.
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
        if (!Transcribe.supported()) return "Uitschrijven werkt niet op deze telefoon";
        if (Transcribe.activeModel(c) == null) return "model"; // eerst: dan kan de app het spraakmodel aanbieden
        if (TranscribeService.busy) return "Er worden nu gesprekken uitgeschreven; probeer het zo opnieuw";
        if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return "mic";
        if (!"idle".equals(Music.state) && !"done".equals(Music.state) && !"error".equals(Music.state)) return "De microfoon wordt gebruikt voor muziek herkennen";
        if (CallRecorderService.busy) return "De microfoon wordt gebruikt voor een gespreksopname";
        if (micForSleep()) return "De microfoon wordt gebruikt door Slaappraat";
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
        if (ar != null || liveOn || liveFinal != null) { cancelLive(); return; }
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
            if (liveOn) { stopLive(c, done); return; }
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
                if (f == null || f.length() < 2000) throw new Exception("Te kort: houd de knop ingedrukt terwijl je praat");
                long dur = Transcribe.durationMs(app, f);
                if (dur < 600) throw new Exception("Te kort: houd de knop ingedrukt terwijl je praat");
                Transcribe.toWav(f, wav, dur, (ph, pct) -> { }, true);
                String all = textOf(Transcribe.whisper(app, wav, Transcribe.activeModel(app), (ph, pct) -> { }, true));
                if (all.isEmpty()) throw new Exception("Er is geen spraak herkend");
                r = new JSONObject().put("text", all).toString();
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

    /** Alleen echte spraak: geen "[BLANK_AUDIO]", "(muziek)" en dergelijke. Zuiver (getest). */
    static boolean keepSeg(String t) { return t != null && !t.trim().isEmpty() && !t.trim().startsWith("[") && !t.trim().startsWith("("); }

    /** De tekst uit de uitvoer van whisper (de stukjes achter elkaar). */
    static String textOf(JSONObject out) throws Exception {
        JSONArray tr = out == null ? null : out.optJSONArray("transcription");
        StringBuilder all = new StringBuilder();
        if (tr != null) for (int i = 0; i < tr.length(); i++) {
            String t = tr.getJSONObject(i).optString("text").trim();
            if (keepSeg(t)) all.append(all.length() > 0 ? " " : "").append(t);
        }
        return all.toString();
    }

    /** Luistert Slaappraat nu (dan is de microfoon bezet)? Uitschrijven door Slaappraat houdt de microfoon niet bezet. */
    static boolean micForSleep() { String p = SleepService.phase; return "luistert".equals(p) || "proef".equals(p); }

    // ---------- live: voor de assistent ----------

    static final int RATE = 16000;
    /** Tussentekst over hooguit de laatste 25 seconden (whisper werkt in stukken van 30 s). */
    static final long WINDOW_BYTES = 25L * RATE * 2;
    /** Een nieuwe tussentekst pas als er minstens zoveel nieuw geluid is, en niet vaker dan om de PART_MS. */
    static final long PART_NEW_BYTES = RATE * 2 * 6 / 5, PART_MS = 1800;

    private static AudioRecord ar;
    private static Thread arThread, partThread, liveFinal;
    private static volatile boolean liveOn, liveCancelled;
    private static File liveWav;
    private static volatile long liveBytes;
    private static volatile String partial = "";
    private static final float[] lv = new float[40];
    private static int lvN = 0;
    private static volatile boolean liveMode;

    /** Geluidsniveau van een stuk (0 = stil, 1 = hard), op een dB-schaal van -60 tot -12 dBFS. Zuiver (getest). */
    static float level(short[] b, int n) {
        if (b == null || n <= 0) return 0f;
        double sum = 0;
        for (int i = 0; i < n; i++) sum += (double) b[i] * b[i];
        double rms = Math.sqrt(sum / n);
        if (rms < 1) return 0f;
        double db = 20 * Math.log10(rms / 32768.0);
        return (float) Math.max(0, Math.min(1, (db + 60) / 48.0));
    }

    /** Welk stuk van de opname (in bytes PCM, na de kop) de tussentekst krijgt: {begin, lengte}; even, hooguit max. Zuiver (getest). */
    static long[] window(long total, long max) {
        if (total <= 0) return new long[]{0, 0};
        long len = Math.min(total, max) & ~1L;
        return new long[]{(total - len) & ~1L, len};
    }

    /** Voor de interface (elke 0,1 s): {state, ms, live, lv:[niveaus sinds de vorige keer], partial}. */
    static synchronized String liveJson() {
        try {
            JSONObject o = new JSONObject().put("state", state).put("ms", elapsed()).put("live", liveMode).put("partial", partial);
            JSONArray a = new JSONArray();
            for (int i = 0; i < lvN; i++) a.put(Math.round(lv[i] * 100) / 100.0);
            lvN = 0;
            return o.put("lv", a).toString();
        } catch (Exception e) { return "{}"; }
    }

    private static synchronized void addLevel(float f) {
        if (lvN >= lv.length) { System.arraycopy(lv, 1, lv, 0, lv.length - 1); lvN = lv.length - 1; }
        lv[lvN++] = f;
    }

    /** Zoals start, maar live (niveau en tussentekst). Valt terug op start als AudioRecord niet lukt. */
    static synchronized String startLive(Context c, Done onAutoStop) {
        if (rec != null || liveOn) return "";
        if ("working".equals(state)) return "Even wachten: de vorige spraaknotitie wordt nog uitgeschreven";
        if (!Transcribe.supported()) return "Uitschrijven werkt niet op deze telefoon";
        if (Transcribe.activeModel(c) == null) return "model";
        if (TranscribeService.busy) return "Er worden nu gesprekken uitgeschreven; probeer het zo opnieuw";
        if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return "mic";
        if (!"idle".equals(Music.state) && !"done".equals(Music.state) && !"error".equals(Music.state)) return "De microfoon wordt gebruikt voor muziek herkennen";
        if (CallRecorderService.busy) return "De microfoon wordt gebruikt voor een gespreksopname";
        if (micForSleep()) return "De microfoon wordt gebruikt door Slaappraat";
        AudioRecord r = null;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min > 0) r = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE * 2 * 2));
            if (r == null || r.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("AudioRecord");
            run++;
            liveWav = new File(c.getCacheDir(), "spraak-live-" + run + ".wav");
            liveWav.delete();
            r.startRecording();
            if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException("AudioRecord");
        } catch (Throwable t) {
            if (r != null) try { r.release(); } catch (Throwable ignored) { }
            liveMode = false;
            return start(c, onAutoStop); // gewone opname, zonder tussentekst
        }
        ar = r; liveOn = true; liveCancelled = false; liveBytes = 0; partial = ""; lvN = 0; liveMode = true;
        since = System.currentTimeMillis();
        state = "recording";
        final Context app = c.getApplicationContext();
        final AudioRecord myAr = r;
        final File out = liveWav;
        arThread = new Thread(() -> recordLoop(app, myAr, out, onAutoStop), "spraak-live");
        arThread.start();
        partThread = new Thread(() -> partialLoop(app, out), "spraak-tussentekst");
        partThread.start();
        return "";
    }

    private static void recordLoop(Context app, AudioRecord r, File out, Done onAutoStop) {
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO); } catch (Throwable ignored) { }
        long bytes = 0;
        boolean auto = false;
        try (RandomAccessFile w = new RandomAccessFile(out, "rw")) {
            w.setLength(0);
            Transcribe.writeWavHeader(w, 0);
            short[] buf = new short[RATE / 10];
            byte[] bb = new byte[buf.length * 2];
            while (liveOn) {
                int n = r.read(buf, 0, buf.length);
                if (n < 0) break;
                if (n == 0) continue;
                for (int i = 0; i < n; i++) { bb[2 * i] = (byte) buf[i]; bb[2 * i + 1] = (byte) (buf[i] >> 8); }
                w.write(bb, 0, n * 2);
                bytes += n * 2L;
                liveBytes = bytes;
                addLevel(level(buf, n));
                if (System.currentTimeMillis() - since >= MAX_MS) { auto = true; break; }
            }
            Transcribe.writeWavHeader(w, (int) Math.min(Integer.MAX_VALUE, bytes));
        } catch (Throwable ignored) {
        }
        // Na 5 minuten: zelf stoppen en uitschrijven (zoals bij de gewone opname)
        if (auto && liveOn) new Thread(() -> { try { stopLive(app, onAutoStop); } catch (Throwable ignored) { } }, "spraak-live-stop").start();
    }

    private static void partialLoop(Context app, File src) {
        long lastBytes = 0, lastAt = 0;
        File part = new File(app.getCacheDir(), "spraak-tussen-" + run + ".wav");
        try {
            while (liveOn) {
                Thread.sleep(300);
                long total = liveBytes;
                if (!liveOn || total - lastBytes < PART_NEW_BYTES || System.currentTimeMillis() - lastAt < PART_MS) continue;
                Transcribe.Model m = Transcribe.model("base");
                if (m == null || !Transcribe.modelFile(app, m).isFile()) m = Transcribe.activeModel(app);
                if (m == null) return;
                long[] win = window(total, WINDOW_BYTES);
                byte[] data = new byte[(int) win[1]];
                try (RandomAccessFile r = new RandomAccessFile(src, "r")) { r.seek(44 + win[0]); r.readFully(data); }
                try (RandomAccessFile w = new RandomAccessFile(part, "rw")) { w.setLength(0); Transcribe.writeWavHeader(w, data.length); w.write(data); }
                lastBytes = total; lastAt = System.currentTimeMillis();
                String t;
                try { t = textOf(Transcribe.whisper(app, part, m, (ph, pct) -> { }, true)); } catch (Exception e) { t = null; }
                if (liveOn && t != null && !t.isEmpty()) partial = (win[0] > 0 ? "… " : "") + t;
                lastAt = System.currentTimeMillis();
            }
        } catch (InterruptedException ignored) {
        } catch (Throwable ignored) {
        } finally { part.delete(); }
    }

    /** Stoppen en het geheel uitschrijven met het gekozen spraakmodel; done krijgt {text}, {error} of {cancelled}. */
    static void stopLive(Context c, Done done) {
        final Thread rt, pt;
        final File f;
        final int myRun;
        synchronized (VoiceNote.class) {
            if (!liveOn) { done.done("{\"error\":\"Er wordt niet opgenomen\"}"); return; }
            liveOn = false;
            state = "working";
            rt = arThread; pt = partThread; f = liveWav; myRun = run;
            try { if (ar != null) ar.stop(); } catch (Throwable ignored) { }
        }
        Transcribe.stopOwnedBy(pt); // een lopende tussentekst is niet meer nodig
        if (pt != null) pt.interrupt();
        final Context app = c.getApplicationContext();
        Thread fin = new Thread(() -> {
            String r;
            try {
                try { if (rt != null) rt.join(3000); } catch (InterruptedException ignored) { }
                synchronized (VoiceNote.class) { if (ar != null) { try { ar.release(); } catch (Throwable ignored) { } ar = null; } }
                try { if (pt != null) pt.join(5000); } catch (InterruptedException ignored) { }
                if (liveCancelled) throw new InterruptedException();
                if (f == null || f.length() < 44 + RATE * 2 * 6 / 10) throw new Exception("Te kort: tik op de microfoon, praat, en tik dan op stoppen");
                String all = textOf(Transcribe.whisper(app, f, Transcribe.activeModel(app), (ph, pct) -> { }, true));
                if (liveCancelled) throw new InterruptedException();
                if (all.isEmpty()) throw new Exception("Er is geen spraak herkend");
                r = new JSONObject().put("text", all).toString();
            } catch (InterruptedException e) {
                r = "{\"cancelled\":true}";
            } catch (Throwable e) {
                String m = liveCancelled ? null : e instanceof OutOfMemoryError ? "Onvoldoende geheugen" : e.getMessage() == null ? "Uitschrijven lukt niet" : e.getMessage();
                try { r = m == null ? "{\"cancelled\":true}" : new JSONObject().put("error", m).toString(); } catch (Exception x) { r = "{}"; }
            } finally {
                if (f != null) f.delete();
                synchronized (VoiceNote.class) { if (run == myRun && !liveOn) { state = "idle"; liveFinal = null; partial = ""; } }
            }
            done.done(r);
        }, "spraak-live-uitschrijven");
        synchronized (VoiceNote.class) { liveFinal = fin; }
        fin.start();
    }

    /** Annuleren (ook tijdens het uitschrijven): niets uitschrijven, alles weg. */
    static synchronized void cancelLive() {
        liveCancelled = true;
        boolean was = liveOn;
        liveOn = false;
        try { if (ar != null) ar.stop(); } catch (Throwable ignored) { }
        Transcribe.stopOwnedBy(partThread);
        if (partThread != null) partThread.interrupt();
        Transcribe.stopOwnedBy(liveFinal);
        if (was) {
            // Nog niet aan het uitschrijven: zelf opruimen (het opnemen stopt vanzelf door liveOn)
            final Thread rt = arThread;
            final File f = liveWav;
            final int myRun = run;
            new Thread(() -> {
                try { if (rt != null) rt.join(3000); } catch (Throwable ignored) { }
                synchronized (VoiceNote.class) { if (ar != null) { try { ar.release(); } catch (Throwable ignored) { } ar = null; } if (run == myRun && !liveOn) { state = "idle"; partial = ""; } }
                if (f != null) f.delete();
            }, "spraak-live-weg").start();
        }
    }
}
