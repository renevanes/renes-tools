package nl.rene.tools;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Radio pauzeren en terugspoelen: de stream wordt doorlopend opgenomen in een ringbuffer (bestand van max. CAP bytes)
 * en via een klein HTTP-servertje op 127.0.0.1 aan de speler gegeven, vanaf elke gewenste plek in de buffer.
 * Werkt voor MP3- en AAC-streams (daar kan je midden in beginnen); bij andere formaten valt de radio terug op direct afspelen.
 * Puur Java (geen Android), zodat het op een gewone JVM getest kan worden.
 */
final class Timeshift {

    interface Ready { void ready(boolean ok, String why); }

    static final int CAP_DEFAULT = 48 * 1024 * 1024;

    final int cap;
    final String upstream, ua;
    final File file;
    final Object lock = new Object();
    volatile long written = 0;            // totaal opgenomen bytes (absolute positie van "live")
    volatile boolean dead = false, closed = false;
    volatile String contentType = "audio/mpeg";
    volatile int bitrate = 0;             // kbps volgens de zender (icy-br) of de zenderlijst
    private long firstT = 0, rateT0 = 0, rateB0 = 0; // voor het meten van de bytesnelheid
    private volatile int measured = 0;    // gemeten bytes per seconde (zonder de eerste burst)
    private volatile ServerSocket server;
    private volatile Thread rec, srv;
    private volatile HttpURLConnection conn;
    private volatile RandomAccessFile out;
    private final java.util.concurrent.atomic.AtomicInteger clients = new java.util.concurrent.atomic.AtomicInteger();

    Timeshift(File f, String url, String userAgent, int knownKbps, int capBytes) {
        file = f; upstream = url; ua = userAgent; bitrate = knownKbps; cap = capBytes;
    }

    /**
     * Bytes per seconde. Gemeten (na de eerste burst) gaat voor; de opgegeven bitrate (zenderlijst/icy-br) klopt
     * vaak niet. Tot er gemeten is: opgegeven bitrate, anders 128 kbps.
     */
    int rate() {
        int m = measured;
        if (m > 2000) return m;
        if (bitrate >= 16 && bitrate <= 512) return bitrate * 125;
        return 16000;
    }

    /** Hoeveel seconden de buffer bevat (bij de huidige snelheid). */
    int seconds() { return (cap - margin()) / Math.max(1, rate()); }

    long live() { return written; }

    /** Oudste plek die nog in de buffer zit (met wat marge voor de schrijver). */
    long oldest() { return Math.max(0, written - cap + margin()); }

    /** Afstand tot de schrijver, zodat we nooit lezen wat net overschreven wordt. */
    int margin() { return Math.min(256 * 1024, cap / 4); }

    int port() { return server == null ? 0 : server.getLocalPort(); }

    String url(long pos) { return "http://127.0.0.1:" + port() + "/s?pos=" + pos + "&r=" + System.nanoTime(); }

    static boolean supported(String contentType, String url) {
        String t = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        String u = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (u.contains(".m3u8") || t.contains("mpegurl") || t.contains("ogg") || t.contains("flac") || t.contains("html") || t.contains("text")) return false;
        return t.contains("mpeg") || t.contains("mp3") || t.contains("aac") || t.contains("aacp");
    }

    /** Start opnemen en de server. ready() wordt één keer aangeroepen (op de opnamethread) zodra bekend is of het lukt. */
    void begin(Ready ready) {
        rec = new Thread(() -> record(ready), "timeshift-rec");
        rec.start();
    }

    private HttpURLConnection open(String u) throws IOException {
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
            h.setInstanceFollowRedirects(false);
            h.setConnectTimeout(10_000);
            h.setReadTimeout(20_000);
            h.setRequestProperty("User-Agent", ua);
            h.setRequestProperty("Icy-MetaData", "0");
            int code = h.getResponseCode();
            String loc = h.getHeaderField("Location");
            if (code >= 300 && code < 400 && loc != null) { h.disconnect(); u = new URL(new URL(u), loc).toString(); continue; }
            if (code >= 400) { h.disconnect(); throw new IOException("HTTP " + code); }
            return h;
        }
        throw new IOException("te veel doorverwijzingen");
    }

    private void record(Ready ready) {
        boolean told = false;
        int failures = 0;
        try {
            out = new RandomAccessFile(file, "rw");
            server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            srv = new Thread(this::serve, "timeshift-srv");
            srv.start();
            byte[] buf = new byte[16 * 1024];
            while (!closed) {
                try {
                    HttpURLConnection h = open(upstream);
                    conn = h;
                    if (!told) {
                        String ct = h.getContentType();
                        if (ct != null) contentType = ct;
                        String br = h.getHeaderField("icy-br");
                        if (br != null) try { int b = Integer.parseInt(br.split(",")[0].trim()); if (b > 0) bitrate = b; } catch (Exception ignored) { }
                        if (!supported(ct, upstream)) { told = true; ready.ready(false, "formaat " + ct); close(); return; }
                        told = true;
                        ready.ready(true, null);
                    }
                    try (InputStream in = h.getInputStream()) {
                        int n;
                        while (!closed && (n = in.read(buf)) > 0) { append(buf, n); failures = 0; }
                    } finally { h.disconnect(); }
                } catch (IOException e) {
                    if (closed) break;
                    if (!told) { told = true; ready.ready(false, e.getMessage()); close(); return; }
                    if (++failures > 5) break;
                    try { Thread.sleep(2000L * failures); } catch (InterruptedException ie) { break; }
                    continue;
                }
                // De zender sloot de stream: even wachten en opnieuw (steeds direct afgelopen = opgeven).
                if (closed || ++failures > 5) break;
                try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
            }
        } catch (Exception e) {
            if (!told) ready.ready(false, e.getMessage());
        } finally {
            dead = true;
            synchronized (lock) { lock.notifyAll(); }
            if (closed) cleanup(); // close() kwam tijdens het opstarten: niets laten slingeren
        }
    }

    private void append(byte[] b, int n) throws IOException {
        long w = written;
        int off = 0;
        while (off < n) {
            int at = (int) ((w + off) % cap);
            int k = Math.min(n - off, cap - at);
            synchronized (this) { out.seek(at); out.write(b, off, k); }
            off += k;
        }
        long now = System.nanoTime() / 1_000_000;
        synchronized (lock) {
            written = w + n;
            if (firstT == 0) firstT = now;
            else if (rateT0 == 0) { if (now - firstT > 5000) { rateT0 = now; rateB0 = written; } } // de eerste burst telt niet mee
            else if (now - rateT0 > 10_000) measured = (int) ((written - rateB0) * 1000 / (now - rateT0));
            lock.notifyAll();
        }
    }

    private void serve() {
        while (!closed) {
            try {
                Socket s = server.accept();
                if (clients.get() >= 4) { try { s.close(); } catch (IOException ignored) { } continue; }
                Thread t = new Thread(() -> { clients.incrementAndGet(); try { client(s); } finally { clients.decrementAndGet(); } }, "timeshift-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (closed) return;
                try { Thread.sleep(100); } catch (InterruptedException ie) { return; }
            }
        }
    }

    private void client(Socket s) {
        try (Socket sock = s; RandomAccessFile in = new RandomAccessFile(file, "r")) {
            sock.setSoTimeout(15_000);
            InputStream is = sock.getInputStream();
            StringBuilder req = new StringBuilder();
            int c;
            while ((c = is.read()) >= 0 && req.length() < 8192) {
                req.append((char) c);
                int L = req.length();
                if (c == '\n' && L >= 2 && (req.charAt(L - 2) == '\n' || (L >= 4 && req.charAt(L - 2) == '\r' && req.charAt(L - 3) == '\n'))) break;
            }
            String head = req.toString();
            String first = head.split("\r?\n")[0];
            long pos = written;
            int i = first.indexOf("pos=");
            if (i >= 0) pos = number(first, i + 4, pos);
            // Speler verbindt opnieuw met Range: verder vanaf daar (anders hoor je een stuk dubbel).
            int r = head.toLowerCase(Locale.ROOT).indexOf("\nrange: bytes=");
            if (r >= 0) pos += number(head, r + 14, 0);
            OutputStream os = sock.getOutputStream();
            os.write(("HTTP/1.0 200 OK\r\nContent-Type: " + contentType + "\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            sock.setSoTimeout(0);
            byte[] buf = new byte[32 * 1024];
            while (!closed) {
                long w;
                synchronized (lock) {
                    while (!closed && !dead && pos >= written) lock.wait(1000);
                    w = written;
                }
                if (closed || (dead && pos >= w)) break;
                if (pos < w - cap + margin() / 4) pos = Math.max(0, w - cap + margin()); // te ver achter: al overschreven
                if (pos > w) pos = w;
                int at = (int) (pos % cap);
                int k = (int) Math.min(Math.min(w - pos, cap - at), buf.length);
                if (k <= 0) continue;
                synchronized (this) { in.seek(at); in.readFully(buf, 0, k); }
                if (written - cap + margin() / 4 > pos) continue; // intussen overschreven: opnieuw (springt naar het oudste)
                os.write(buf, 0, k);
                pos += k;
            }
        } catch (Exception ignored) { }
    }

    /**
     * Kopieert [from, to) uit de buffer naar os (bijv. "de laatste 10 minuten bewaren"). Begint bij het oudste
     * dat er nog is als 'from' al overschreven is. Geeft het aantal geschreven bytes.
     */
    long copy(long from, long to, OutputStream os) throws IOException {
        long w = written;
        to = Math.min(to, w);
        long pos = Math.max(from, oldest());
        byte[] buf = new byte[64 * 1024];
        long n = 0;
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            while (pos < to && !closed) {
                int at = (int) (pos % cap);
                int k = (int) Math.min(Math.min(to - pos, cap - at), buf.length);
                synchronized (this) { in.seek(at); in.readFully(buf, 0, k); }
                if (written - cap + margin() / 4 > pos) { pos = oldest(); continue; } // intussen overschreven
                os.write(buf, 0, k);
                pos += k; n += k;
            }
        }
        return n;
    }

    /** Bestandsextensie voor het formaat van de stream. */
    String ext() { String t = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT); return t.contains("aac") ? "aac" : "mp3"; }

    static long number(String s, int from, long def) {
        int j = from;
        while (j < s.length() && Character.isDigit(s.charAt(j))) j++;
        try { return j > from ? Long.parseLong(s.substring(from, j)) : def; } catch (Exception e) { return def; }
    }

    void close() {
        closed = true;
        HttpURLConnection h = conn;
        if (h != null) try { h.disconnect(); } catch (Exception ignored) { }
        Thread t = rec;
        if (t != null) t.interrupt();
        synchronized (lock) { lock.notifyAll(); }
        cleanup();
    }

    private void cleanup() {
        try { ServerSocket sv = server; if (sv != null) sv.close(); } catch (Exception ignored) { }
        synchronized (this) {
            try { if (out != null) out.close(); } catch (Exception ignored) { }
        }
        file.delete();
    }
}
