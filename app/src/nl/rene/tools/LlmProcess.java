package nl.rene.tools;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * De lokale AI-motor (lib/arm64-v8a/libllm.so, llama.cpp) als kindproces. De app praat ermee via stdin/stdout,
 * één JSON-regel per bericht (zie tools/llm/llm.cpp). Er is geen netwerk of server: alleen deze app kan erbij.
 */
final class LlmProcess {

    interface Listener { void line(JSONObject o); }

    final File model;
    final boolean embed;
    final int ctx;
    /** Met hoeveel rekenkernen hij draait (bij een andere Rekenkracht-instelling start hij de volgende keer opnieuw). */
    int threads;
    private final Process proc;
    private final BufferedWriter in;
    private final StringBuilder errTail = new StringBuilder();
    private volatile Listener listener;
    private volatile boolean alive = true;
    volatile long lastUse = System.currentTimeMillis();
    private JSONObject ready;

    static File binary(Context c) { return new File(c.getApplicationInfo().nativeLibraryDir, "libllm.so"); }

    private LlmProcess(Process p, File model, boolean embed, int ctx) {
        this.proc = p; this.model = model; this.embed = embed; this.ctx = ctx;
        this.in = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
    }

    /** Start de motor en wacht tot het model geladen is (of geeft de fout). */
    static LlmProcess start(Context c, File model, int ctx, int threads, boolean embed, boolean nothink) throws Exception {
        return start(binary(c), c.getFilesDir(), model, ctx, threads, embed, nothink);
    }

    /** Zonder Context (ook voor de test op de computer). */
    static LlmProcess start(File bin, File home, File model, int ctx, int threads, boolean embed, boolean nothink) throws Exception {
        if (!bin.isFile()) throw new Exception("De AI-motor ontbreekt in deze app-versie");
        if (!model.isFile()) throw new Exception("Het model is er niet (meer); download het opnieuw");
        List<String> cmd = new ArrayList<>();
        cmd.add(bin.getPath());
        cmd.add("-m"); cmd.add(model.getPath());
        cmd.add("-c"); cmd.add(String.valueOf(ctx));
        cmd.add("-t"); cmd.add(String.valueOf(threads));
        if (embed) cmd.add("--embed");
        if (nothink) cmd.add("--nothink");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("HOME", home.getPath());
        final LlmProcess lp = new LlmProcess(pb.start(), model, embed, ctx);
        lp.threads = threads;
        final CountDownLatch up = new CountDownLatch(1);
        Thread out = new Thread(() -> lp.readOut(up), embed ? "llm-embed-out" : "llm-chat-out");
        out.setDaemon(true); out.start();
        Thread err = new Thread(lp::readErr, "llm-err");
        err.setDaemon(true); err.start();
        // Een groot model laden kan op een telefoon even duren
        if (!up.await(180, TimeUnit.SECONDS)) { lp.destroy(); throw new Exception("Het model laden duurde te lang"); }
        JSONObject r = lp.ready;
        if (r == null || !r.optBoolean("ready")) {
            String e = r != null ? r.optString("error") : "";
            lp.destroy();
            int code = lp.exitCode();
            // 132 = SIGILL: de processor kent een instructie niet
            if (code == 132 || code == 128 + 4 || lp.errTail().contains("Illegal instruction")) throw new Exception("Deze processor wordt niet ondersteund door de AI-motor");
            if (code == 137 || code == 128 + 9) throw new Exception("Te weinig geheugen voor dit model; probeer het kleine model");
            throw new Exception(e.isEmpty() ? "De AI-motor startte niet" + (code > 0 ? " (code " + code + ")" : "") : e);
        }
        return lp;
    }

    private void readOut(CountDownLatch up) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                JSONObject o;
                try { o = new JSONObject(line); } catch (Exception e) { continue; }
                if (up.getCount() > 0) { ready = o; up.countDown(); continue; }
                Listener l = listener;
                if (l != null) try { l.line(o); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) {
        } finally {
            alive = false;
            up.countDown();
            Listener l = listener;
            if (l != null) {
                try {
                    int code = exitCode();
                    String m = code == 137 || code == 128 + 9 ? "Te weinig geheugen: Android heeft de AI-motor gestopt" : "De AI-motor is gestopt";
                    l.line(new JSONObject().put("error", m).put("gone", true));
                } catch (Throwable ignored) { }
            }
        }
    }

    private void readErr() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) synchronized (errTail) {
                errTail.append(line).append('\n');
                if (errTail.length() > 4000) errTail.delete(0, errTail.length() - 4000);
            }
        } catch (Throwable ignored) { }
    }

    String errTail() { synchronized (errTail) { return errTail.toString(); } }

    int exitCode() { return waitExit(proc, 2000); }

    /** Wachten tot het proces stopt (Process.waitFor met tijd bestaat pas vanaf Android 8). -1 = loopt nog. */
    static int waitExit(Process p, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (true) {
            try { return p.exitValue(); } catch (IllegalThreadStateException e) { /* loopt nog */ }
            if (System.currentTimeMillis() >= end) return -1;
            try { Thread.sleep(40); } catch (InterruptedException e) { return -1; }
        }
    }

    boolean alive() { return alive; }

    void listen(Listener l) { listener = l; }

    /** Eén opdracht sturen (een JSON-object op één regel). */
    synchronized void send(JSONObject o) throws Exception {
        if (!alive) throw new Exception("De AI-motor is gestopt");
        lastUse = System.currentTimeMillis();
        in.write(o.toString());
        in.write('\n');
        in.flush();
    }

    /** Stoppen: stdin dicht (de motor stopt netjes), anders na even wachten hard. */
    void destroy() {
        alive = false;
        // Wie nog op een antwoord wacht, hoort het meteen (in plaats van te wachten tot de tijd om is)
        Listener l = listener;
        listener = null;
        if (l != null) try { l.line(new JSONObject().put("error", "De AI-motor is gestopt").put("gone", true)); } catch (Throwable ignored) { }
        try { in.close(); } catch (Throwable ignored) { }
        try { if (waitExit(proc, 2000) < 0) proc.destroy(); } catch (Throwable t) { proc.destroy(); }
    }
}
