package nl.rene.tools;

import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URL;

/** Timeshift: opnemen van een (nep)stream in de ringbuffer en afspelen vanaf een gekozen plek, ook na het rondgaan. */
public class TimeshiftTest {
    static int failed = 0;
    static void check(String what, boolean ok) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) failed++; }

    static byte at(long i) { return (byte) (i * 31 + 7); }

    public static void main(String[] a) throws Exception {
        HttpServer hs = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        hs.createContext("/mp3", x -> {
            x.getResponseHeaders().add("Content-Type", "audio/mpeg");
            x.getResponseHeaders().add("icy-br", "128");
            x.sendResponseHeaders(200, 0);
            try (OutputStream o = x.getResponseBody()) {
                byte[] b = new byte[4000];
                for (long i = 0; i < 200_000; i += b.length) {
                    for (int k = 0; k < b.length; k++) b[k] = at(i + k);
                    o.write(b); o.flush();
                    Thread.sleep(2);
                }
            } catch (Exception ignored) { }
        });
        hs.createContext("/ogg", x -> { x.getResponseHeaders().add("Content-Type", "application/ogg"); x.sendResponseHeaders(200, 0); x.getResponseBody().write(new byte[100]); x.close(); });
        hs.start();
        String base = "http://127.0.0.1:" + hs.getAddress().getPort();

        java.io.File f = java.io.File.createTempFile("tsbuf", ".bin");
        Timeshift ts = new Timeshift(f, base + "/mp3", "test", 0, 64 * 1024);
        final boolean[] ok = {false};
        final Object w = new Object();
        ts.begin((good, why) -> { synchronized (w) { ok[0] = good; w.notifyAll(); } });
        synchronized (w) { w.wait(5000); }
        check("mp3-stream wordt geaccepteerd", ok[0]);
        check("bitrate uit icy-br", ts.rate() == 16000);
        while (ts.live() < 120_000 && !ts.dead) Thread.sleep(20);
        long live = ts.live();
        long from = ts.oldest() + 1000;
        byte[] got = read(ts.url(from), 5000);
        boolean same = got.length == 5000;
        for (int i = 0; same && i < got.length; i++) if (got[i] != at(from + i)) same = false;
        check("afspelen vanaf een oude plek na rondgaan van de buffer klopt byte voor byte", same);
        long tooOld = 10;
        byte[] g2 = read(ts.url(tooOld), 100);
        check("te oude plek levert gewoon audio (vanaf het oudste dat er nog is)", g2.length == 100);
        check("buffer niet groter dan het maximum", f.length() <= 64 * 1024);
        check("oldest ligt binnen de buffer", ts.live() - ts.oldest() <= 64 * 1024);
        ts.close();
        check("na sluiten is het bestand weg", !f.exists());

        java.io.File f2 = java.io.File.createTempFile("tsbuf", ".bin");
        Timeshift t2 = new Timeshift(f2, base + "/ogg", "test", 0, 64 * 1024);
        final String[] res = {null};
        t2.begin((good, why) -> { synchronized (w) { res[0] = good + ":" + why; w.notifyAll(); } });
        synchronized (w) { w.wait(5000); }
        check("ogg wordt geweigerd (terugval op direct afspelen)", res[0] != null && res[0].startsWith("false"));
        hs.stop(0);
        System.out.println(failed == 0 ? "Alle timeshift-tests geslaagd" : failed + " test(s) mislukt");
        System.exit(failed == 0 ? 0 : 1);
    }

    static byte[] read(String u, int n) throws Exception {
        try (InputStream in = new URL(u).openStream()) {
            byte[] b = new byte[n];
            int off = 0, r;
            while (off < n && (r = in.read(b, off, n - off)) > 0) off += r;
            return java.util.Arrays.copyOf(b, off);
        }
    }
}
