package nl.rene.tools;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * OpenVPN-client in gewoon Java: TLS (SSLEngine) over het betrouwbare controlekanaal, key-method 2, PUSH_REPLY,
 * het datakanaal (AES-GCM, ChaCha20-Poly1305, AES-CBC), UDP en TCP, ping/ping-restart, heronderhandelen en opnieuw
 * verbinden. Geen Android-code: getest tegen een echte OpenVPN 2.6-server (tests/ovpn).
 */
final class OvpnDevice {

    /** Sockets buiten de VPN houden en hostnamen opzoeken via het gewone netwerk. */
    interface Net {
        boolean protect(DatagramSocket s);
        boolean protect(Socket s);
        InetAddress[] resolve(String host) throws IOException;
    }

    interface Listener { void onEvent(String what, String detail); }

    /** De server stuurde de instellingen (adres, routes, DNS): tunnel (opnieuw) opbouwen. */
    interface Setup { void apply(Push p) throws Exception; }

    static final int P_CONTROL_SOFT_RESET_V1 = 3, P_CONTROL_V1 = 4, P_ACK_V1 = 5, P_DATA_V1 = 6,
            P_CONTROL_HARD_RESET_CLIENT_V2 = 7, P_CONTROL_HARD_RESET_SERVER_V2 = 8, P_DATA_V2 = 9;
    static final byte[] PING = {0x2a, 0x18, 0x7b, (byte) 0xf3, 0x64, 0x1e, (byte) 0xb4, (byte) 0xcb, 0x07, (byte) 0xed, 0x2d, 0x0a, (byte) 0x98, 0x1f, (byte) 0xc7, 0x48};
    static final byte[] OCC = {0x28, 0x7f, 0x34, 0x6b, (byte) 0xd4, (byte) 0xef, 0x7a, (byte) 0x81, 0x2d, 0x56, (byte) 0xb8, (byte) 0xd3, (byte) 0xaf, (byte) 0xc5, 0x45, (byte) 0x9c};
    static final int WINDOW = 4, MAX_ACKS = 4, TLS_CHUNK = 1100;
    static final long NO_ANSWER_MS = 10_000;

    static long now() { return System.nanoTime() / 1_000_000L; }

    // ================= de pushed instellingen =================

    static final class Push {
        final List<WgConfig.Cidr> addresses = new ArrayList<>(), routes = new ArrayList<>(), excluded = new ArrayList<>();
        final List<String> dns = new ArrayList<>(), domains = new ArrayList<>();
        int mtu, ping, pingRestart, peerId = -1;
        String cipher, authToken, compress, topology = "net30";
        String raw = "";

        /** Kenmerk om te zien of de tunnel opnieuw opgebouwd moet worden. */
        String sig() { return addresses + "|" + routes + "|" + excluded + "|" + dns + "|" + domains + "|" + mtu; }

        static Push parse(String reply, boolean noPull) {
            Push p = new Push();
            p.raw = reply;
            String ifLocal = null, ifRemote = null;
            boolean redirect4 = false, redirect6 = false;
            for (String opt : reply.split(",")) {
                List<String> t = OvpnConfig.tokens(opt.trim());
                if (t.isEmpty()) continue;
                String k = t.get(0).toLowerCase(Locale.ROOT), a = t.size() > 1 ? t.get(1) : "", b = t.size() > 2 ? t.get(2) : "";
                try {
                    switch (k) {
                        case "topology": p.topology = a.toLowerCase(Locale.ROOT); break;
                        case "ifconfig": ifLocal = a; ifRemote = b; break;
                        case "ifconfig-ipv6": p.addresses.add(WgConfig.cidr(a, true)); break;
                        case "route":
                            if (noPull) break;
                            if (WgConfig.isIp(a)) {
                                WgConfig.Cidr c = new WgConfig.Cidr(WgConfig.ip(a), b.isEmpty() ? 32 : maskBits(b));
                                if (t.size() > 3 && t.get(3).equalsIgnoreCase("net_gateway")) p.excluded.add(c); else p.routes.add(c);
                            }
                            break;
                        case "route-ipv6":
                            if (noPull) break;
                            if (t.size() > 2 && b.equalsIgnoreCase("net_gateway")) p.excluded.add(WgConfig.cidr(a, false)); else p.routes.add(WgConfig.cidr(a, false));
                            break;
                        case "redirect-gateway": case "redirect-private":
                            if (noPull || !k.equals("redirect-gateway")) break;
                            boolean no4 = false;
                            for (String f : t.subList(1, t.size())) { if (f.equalsIgnoreCase("!ipv4")) no4 = true; if (f.equalsIgnoreCase("ipv6")) redirect6 = true; }
                            if (!no4) redirect4 = true;
                            break;
                        case "dhcp-option":
                            if (a.equalsIgnoreCase("DNS") || a.equalsIgnoreCase("DNS6")) { if (WgConfig.isIp(b)) p.dns.add(WgConfig.ipString(WgConfig.ip(b))); }
                            else if ((a.equalsIgnoreCase("DOMAIN") || a.equalsIgnoreCase("DOMAIN-SEARCH")) && b.matches("[A-Za-z0-9.-]{1,253}")) p.domains.add(b);
                            break;
                        case "dns": // dns server <n> address <ip> [ip…] / dns search-domains <d…>
                            if (a.equalsIgnoreCase("server") && t.size() > 4 && t.get(3).equalsIgnoreCase("address"))
                                for (String ip : t.subList(4, t.size())) {
                                    String x = ip.replaceAll("#.*$", "");
                                    if (x.startsWith("[") && x.contains("]")) x = x.substring(1, x.indexOf(']'));          // [v6]:poort
                                    else if (x.matches("\\d+\\.\\d+\\.\\d+\\.\\d+:\\d+")) x = x.substring(0, x.indexOf(':')); // v4:poort
                                    if (WgConfig.isIp(x)) p.dns.add(WgConfig.ipString(WgConfig.ip(x)));
                                }
                            else if (a.equalsIgnoreCase("search-domains")) for (String d : t.subList(2, t.size())) if (d.matches("[A-Za-z0-9.-]{1,253}")) p.domains.add(d);
                            break;
                        case "tun-mtu": p.mtu = Integer.parseInt(a); break;
                        case "ping": p.ping = Integer.parseInt(a); break;
                        case "ping-restart": p.pingRestart = Integer.parseInt(a); break;
                        case "peer-id": p.peerId = Integer.parseInt(a); break;
                        case "cipher": p.cipher = a.toUpperCase(Locale.ROOT); break;
                        case "auth-token": p.authToken = a; break;
                        case "comp-lzo": p.compress = "lzo"; break;
                        case "compress": p.compress = a.isEmpty() ? "stub" : a.toLowerCase(Locale.ROOT); break;
                        default: break;
                    }
                } catch (Exception ignored) { /* één vreemde optie: overslaan */ }
            }
            if (ifLocal != null && WgConfig.isIp(ifLocal)) {
                if (p.topology.equals("subnet") && WgConfig.isIp(ifRemote)) p.addresses.add(0, new WgConfig.Cidr(WgConfig.ip(ifLocal), maskBits(ifRemote)));
                else {
                    p.addresses.add(0, new WgConfig.Cidr(WgConfig.ip(ifLocal), 32));
                    if (ifRemote != null && WgConfig.isIp(ifRemote) && !noPull) p.routes.add(new WgConfig.Cidr(WgConfig.ip(ifRemote), 32));
                }
            }
            if (redirect4) p.routes.add(new WgConfig.Cidr(new byte[4], 0));
            if (redirect6) p.routes.add(new WgConfig.Cidr(new byte[16], 0));
            return p;
        }

        static int maskBits(String mask) {
            if (!WgConfig.isIp(mask) || mask.contains(":")) return 32;
            byte[] m = WgConfig.ip(mask);
            int bits = 0;
            for (byte x : m) bits += Integer.bitCount(x & 0xff);
            return bits;
        }
    }

    // ================= sleuteltoestand (key-id) =================

    static final class Out { final int id; final int op; final byte[] payload; long sentAt = -1; int tries; Out(int id, int op, byte[] p) { this.id = id; this.op = op; payload = p; } }

    static final class Ks {
        final int keyId;
        SSLEngine eng;
        ByteBuffer netIn = ByteBuffer.allocate(1 << 16), appIn = ByteBuffer.allocate(1 << 16);
        final ByteArrayOutputStream app = new ByteArrayOutputStream();
        int sendId, recvNext;
        final TreeMap<Integer, Out> unacked = new TreeMap<>();
        final ArrayDeque<Out> waiting = new ArrayDeque<>();
        final TreeMap<Integer, byte[]> recvBuf = new TreeMap<>();
        final ArrayList<Integer> acks = new ArrayList<>();
        boolean tlsStarted, kmSent, kmDone;
        byte[] preMaster, r1, r2, keyBlock;
        OvpnCrypto.DataKeys data;
        final long started = now();
        byte[] pendingApp;

        Ks(int keyId) { this.keyId = keyId; }
    }

    // ================= toestand =================

    final OvpnConfig cfg;
    private final Net net;
    private final Listener listener;
    private final Setup setup;
    private final String user, pass;
    private volatile WgDevice.Tun tun;
    private volatile boolean running;
    private Thread tTun, tTimer;

    /** Eén verbindingspoging (socket, sessie, sleutels). Vervangen bij opnieuw verbinden. */
    static final class Conn {
        final OvpnConfig.Remote remote;
        DatagramSocket udp;
        Socket tcp;
        OutputStream tcpOut;
        InetSocketAddress addr;
        final byte[] localSid = new byte[8];
        byte[] remoteSid;
        final OvpnCrypto.Wrap wrap;
        final Ks[] ks = new Ks[8];
        volatile OvpnCrypto.DataKeys primary;
        volatile OvpnCrypto.DataKeys lameDuck;
        int nextKeyId = 1;
        Push push;
        StringBuilder pushParts = new StringBuilder();
        long lastPushReq = -1, lastRecv = now(), lastSent = now(), established = -1, lastReneg = now();
        long started = now();
        volatile boolean dead, applied;
        String authToken;
        long authPendingUntil = -1;
        java.util.concurrent.LinkedBlockingQueue<byte[]> tcpQueue;

        Conn(OvpnConfig cfg, OvpnConfig.Remote r) { remote = r; wrap = new OvpnCrypto.Wrap(cfg); WgCrypto.RNG.nextBytes(localSid); }
    }

    private volatile Conn conn;
    private int remoteIdx;
    private int failures;
    private long retryAt = -1;
    private Push applied;
    volatile String lastError;
    volatile boolean authFailed, halted;
    private volatile boolean stopped;
    final java.util.concurrent.atomic.AtomicLong rx = new java.util.concurrent.atomic.AtomicLong(), tx = new java.util.concurrent.atomic.AtomicLong();
    volatile long connectedWall;

    OvpnDevice(OvpnConfig cfg, String user, String pass, Net net, Listener listener, Setup setup) {
        this.cfg = cfg; this.user = user == null ? "" : user; this.pass = pass == null ? "" : pass;
        this.net = net; this.listener = listener; this.setup = setup;
        if (cfg.remoteRandom) remoteIdx = WgCrypto.RNG.nextInt(cfg.remotes.size());
    }

    private void event(String what, String detail) { if (listener != null) try { listener.onEvent(what, detail); } catch (Exception ignored) { } }

    /** Starten: verbindt op de achtergrond (meldingen via Listener). */
    void start() {
        synchronized (this) { if (stopped) return; running = true; } // al gestopt (bijv. meteen weer uitgezet): niet meer starten
        tTimer = new Thread(this::timerLoop, "ovpn-timer"); tTimer.setDaemon(true); tTimer.start();
        new Thread(this::connectNext, "ovpn-connect").start();
    }

    void setTun(WgDevice.Tun t) {
        tun = t;
        if (running && tTun == null) { tTun = new Thread(this::tunLoop, "ovpn-tun"); tTun.setDaemon(true); tTun.start(); }
    }

    void stop() {
        Conn c;
        synchronized (this) { stopped = true; running = false; c = conn; }
        if (c != null) {
            final Conn fc = c;
            new Thread(() -> { try { exitNotify(fc); } catch (Throwable ignored) { } fc.dead = true; close(fc); }, "ovpn-exit").start(); // netwerk niet op de hoofdthread
        }
        if (tTimer != null) tTimer.interrupt();
        if (tTun != null) tTun.interrupt();
    }

    boolean connected() { Conn c = conn; return c != null && c.primary != null && c.established >= 0 && c.applied && !c.dead; }

    String remoteText() { Conn c = conn; return c == null ? "" : c.remote.toString(); }

    /** Na een netwerkwissel: (UDP) opnieuw verbinden over het nieuwe netwerk. */
    void networkChanged() {
        Conn c = conn;
        if (!running || c == null) return;
        if (c.dead) { synchronized (this) { if (!authFailed && !halted) { failures = 0; retryAt = now() + 500; } } return; } // wachtte op een nieuwe poging: nu meteen
        restart(c, "netwerk gewisseld", false);
    }

    // ================= verbinden =================

    private boolean connecting; // er loopt al een verbindingspoging (nooit twee tegelijk)

    private void connectNext() {
        synchronized (this) { if (connecting) return; connecting = true; }
        try { connectNextInner(); }
        catch (Throwable e) { lastError = "Verbinden lukt niet: " + e.getClass().getSimpleName(); synchronized (this) { if (running) retryAt = now() + 5000; } }
        finally { synchronized (this) { connecting = false; } }
    }

    private void connectNextInner() {
        if (!running) return;
        OvpnConfig.Remote r = cfg.remotes.get(remoteIdx % cfg.remotes.size());
        Conn c = new Conn(cfg, r);
        event("connecting", r.toString());
        try {
            InetAddress[] all = WgConfig.isIp(r.host) ? new InetAddress[]{InetAddress.getByAddress(WgConfig.ip(r.host))} : net.resolve(r.host);
            if (all == null || all.length == 0) throw new IOException("Kan " + r.host + " niet vinden");
            InetAddress pick = all[0];
            for (InetAddress a : all) if (a.getAddress().length == 4) { pick = a; break; }
            c.addr = new InetSocketAddress(pick, r.port);
            if (r.tcp) {
                Socket s = new Socket();
                s.setTcpNoDelay(true); // maakt de socket echt aan (Android doet dat pas laat), nodig voor protect
                if (!net.protect(s)) { s.close(); throw new IOException("Verbinding kon niet buiten de VPN gehouden worden"); }
                s.connect(c.addr, 10_000);
                c.tcp = s; c.tcpOut = s.getOutputStream();
                c.tcpQueue = new java.util.concurrent.LinkedBlockingQueue<>();
            } else {
                DatagramSocket s = new DatagramSocket(null);
                try { s.setReceiveBufferSize(1 << 20); s.setSendBufferSize(1 << 20); } catch (Exception ignored) { }
                if (!net.protect(s)) { s.close(); throw new IOException("Verbinding kon niet buiten de VPN gehouden worden"); }
                s.bind(new InetSocketAddress(0));
                s.connect(c.addr);
                c.udp = s;
            }
        } catch (Exception e) {
            fail(c, e.getMessage() == null ? "Verbinden lukt niet" : e.getMessage());
            return;
        }
        synchronized (this) {
            if (!running || stopped || conn != null && conn != c && !conn.dead) { close(c); return; }
            conn = c;
            c.started = now(); // de 10 s zonder antwoord tellen vanaf nu (na DNS en TCP-verbinding)
            Ks k = new Ks(0);
            c.ks[0] = k;
            queue(c, k, P_CONTROL_HARD_RESET_CLIENT_V2, new byte[0]);
            flush(c, k);
        }
        Thread t = new Thread(() -> recvLoop(c), "ovpn-recv");
        t.setDaemon(true);
        t.start();
        if (c.tcp != null) { Thread w = new Thread(() -> tcpWriter(c), "ovpn-tcp-uit"); w.setDaemon(true); w.start(); }
    }

    /** TCP: schrijven in een eigen thread, zodat een vastgelopen verbinding de timers niet blokkeert. */
    private void tcpWriter(Conn c) {
        try {
            while (running && !c.dead) {
                byte[] f = c.tcpQueue.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                if (f != null) { c.tcpOut.write(f); if (c.tcpQueue.isEmpty()) c.tcpOut.flush(); }
            }
        } catch (Throwable e) {
            if (running && !c.dead) restart(c, "Verbinding met de server verbroken", c.established < 0);
        }
    }

    private void fail(Conn c, String why) {
        lastError = why;
        event("error", why);
        c.dead = true;
        close(c);
        synchronized (this) {
            if (conn != null && conn != c && !conn.dead) return; // er loopt al een nieuwere poging
            failures++;
            remoteIdx++;
            long wait = Math.min(60_000, cfg.connectRetry * 1000L * (1L << Math.min(4, Math.max(0, failures - cfg.remotes.size()))));
            retryAt = authFailed || halted ? -1 : now() + (failures <= cfg.remotes.size() ? 300 : wait);
        }
    }

    private void restart(Conn c, String why, boolean countFailure) {
        if (c.dead) return;
        lastError = why;
        event("reconnecting", why);
        c.dead = true;
        close(c);
        synchronized (this) {
            if (conn != c) return;
            if (countFailure) { failures++; remoteIdx++; }
            retryAt = now() + 500;
        }
    }

    private void close(Conn c) {
        try { if (c.udp != null) c.udp.close(); } catch (Exception ignored) { }
        try { if (c.tcp != null) c.tcp.close(); } catch (Exception ignored) { }
    }

    private void exitNotify(Conn c) {
        OvpnCrypto.DataKeys k = c.primary;
        if (k == null || c.remote.tcp) return;
        byte[] m = new byte[OCC.length + 1];
        System.arraycopy(OCC, 0, m, 0, OCC.length);
        m[OCC.length] = 6; // OCC_EXIT
        try { sendData(c, k, m, 0, m.length, true); sendData(c, k, m, 0, m.length, true); } catch (Exception ignored) { } // met compressiekader, net als gewone pakketten
    }

    // ================= versturen =================

    private void sendRaw(Conn c, byte[] p) throws IOException {
        if (c.dead) return;
        if (c.tcp != null) {
            byte[] f = new byte[p.length + 2];
            f[0] = (byte) (p.length >> 8); f[1] = (byte) p.length;
            System.arraycopy(p, 0, f, 2, p.length);
            if (c.tcpQueue.size() > 2000) { int op = (p[0] & 0xff) >> 3; if (op == P_DATA_V1 || op == P_DATA_V2) return; } // vol: data laten vallen, controle niet
            c.tcpQueue.add(f);
        } else c.udp.send(new DatagramPacket(p, p.length));
        c.lastSent = now();
        tx.addAndGet(p.length);
    }

    /** Bericht in de rij voor het controlekanaal (met het slot vastgehouden). */
    private void queue(Conn c, Ks k, int op, byte[] payload) {
        k.waiting.add(new Out(k.sendId++, op, payload));
    }

    /** Wachtende berichten versturen (binnen het venster) en acks meesturen; anders een losse ack. */
    private void flush(Conn c, Ks k) {
        while (!k.waiting.isEmpty() && k.unacked.size() < WINDOW) {
            Out o = k.waiting.poll();
            k.unacked.put(o.id, o);
            sendControl(c, k, o);
        }
        if (!k.acks.isEmpty()) sendAck(c, k);
    }

    private byte[] ackPart(Conn c, Ks k, boolean consume) {
        int n = Math.min(MAX_ACKS, k.acks.size());
        if (n == 0 || c.remoteSid == null) return new byte[]{0};
        byte[] a = new byte[1 + 4 * n + 8];
        a[0] = (byte) n;
        for (int i = 0; i < n; i++) OvpnCrypto.put32be(a, 1 + 4 * i, k.acks.get(i));
        System.arraycopy(c.remoteSid, 0, a, 1 + 4 * n, 8);
        if (consume) for (int i = 0; i < n; i++) k.acks.remove(0);
        return a;
    }

    private void sendControl(Conn c, Ks k, Out o) {
        byte[] ack = ackPart(c, k, true);
        byte[] body = new byte[ack.length + 4 + o.payload.length];
        System.arraycopy(ack, 0, body, 0, ack.length);
        OvpnCrypto.put32be(body, ack.length, o.id);
        System.arraycopy(o.payload, 0, body, ack.length + 4, o.payload.length);
        o.sentAt = now(); o.tries++;
        try { sendRaw(c, c.wrap.wrap(o.op << 3 | k.keyId, c.localSid, body)); } catch (IOException e) { lastError = e.getMessage(); }
    }

    private void sendAck(Conn c, Ks k) {
        while (!k.acks.isEmpty()) {
            byte[] body = ackPart(c, k, true);
            if (body.length == 1) return;
            try { sendRaw(c, c.wrap.wrap(P_ACK_V1 << 3 | k.keyId, c.localSid, body)); } catch (IOException e) { lastError = e.getMessage(); return; }
        }
    }

    // ================= ontvangen =================

    private void recvLoop(Conn c) {
        byte[] buf = new byte[65536 + 2];
        try {
            if (c.tcp != null) {
                DataInputStream in = new DataInputStream(c.tcp.getInputStream());
                while (running && !c.dead) {
                    int n = in.readUnsignedShort();
                    in.readFully(buf, 0, n);
                    handle(c, buf, n);
                }
            } else {
                DatagramPacket dp = new DatagramPacket(buf, buf.length);
                while (running && !c.dead) {
                    dp.setLength(buf.length);
                    try { c.udp.receive(dp); }
                    catch (java.net.PortUnreachableException e) { lastError = "De server antwoordt niet (poort dicht?)"; continue; } // de timers beslissen
                    handle(c, buf, dp.getLength());
                }
            }
        } catch (Throwable e) {
            if (System.getProperty("ovpn.debug") != null) e.printStackTrace();
            if (running && !c.dead) restart(c, c.tcp != null ? "Verbinding met de server verbroken" : "Netwerkfout", c.established < 0);
        }
    }

    private void handle(Conn c, byte[] p, int len) {
        if (len < 1) return;
        int op = (p[0] & 0xff) >> 3, keyId = p[0] & 7;
        rx.addAndGet(len);
        if (op == P_DATA_V1 || op == P_DATA_V2) { receiveData(c, p, len, op, keyId); return; }
        Push toApply = null;
        synchronized (this) {
            if (c.dead || conn != c) return;
            byte[] body = c.wrap.unwrap(p, len);
            if (body == null) return; // niet van de server (of herhaald)
            byte[] sid = java.util.Arrays.copyOfRange(p, 1, 9);
            if (c.remoteSid == null) { if (op != P_CONTROL_HARD_RESET_SERVER_V2) return; c.remoteSid = sid; }
            else if (!java.util.Arrays.equals(c.remoteSid, sid)) return;
            c.lastRecv = now();
            // acks
            int pos = 0;
            int nAck = body[pos++] & 0xff;
            Ks k = c.ks[keyId];
            if (nAck > 0) {
                if (body.length < 1 + 4 * nAck + 8) return;
                if (!WgCrypto.eq(body, 1 + 4 * nAck, c.localSid, 0, 8)) return;
                for (int i = 0; i < nAck; i++) { int id = OvpnCrypto.get32be(body, 1 + 4 * i); if (k != null) k.unacked.remove(id); }
                pos = 1 + 4 * nAck + 8;
            }
            if (op == P_ACK_V1) { if (k != null) flush(c, k); return; }
            if (body.length < pos + 4) return;
            int msgId = OvpnCrypto.get32be(body, pos);
            byte[] payload = java.util.Arrays.copyOfRange(body, pos + 4, body.length);
            boolean stale = k != null && (k.data != null ? k.data != c.primary && k.data != c.lameDuck : now() - k.started > 60_000);
            if (op == P_CONTROL_SOFT_RESET_V1 && msgId == 0 && keyId != 0 && (k == null || stale)) {
                // de server begint opnieuw onderhandelen met een nieuwe key-id: wij antwoorden met onze eigen reset
                k = newKeyState(c, keyId, false);
                queue(c, k, P_CONTROL_SOFT_RESET_V1, new byte[0]);
                c.lastReneg = now();
            }
            if (k == null) return;
            if (op != P_CONTROL_V1 && op != P_CONTROL_HARD_RESET_SERVER_V2 && op != P_CONTROL_SOFT_RESET_V1) return;
            k.acks.add(msgId);
            if (msgId >= k.recvNext && msgId < k.recvNext + 8 && !k.recvBuf.containsKey(msgId)) k.recvBuf.put(msgId, payload);
            // op volgorde verwerken
            while (k.recvBuf.containsKey(k.recvNext)) {
                byte[] pl = k.recvBuf.remove(k.recvNext);
                k.recvNext++;
                if (pl.length > 0) {
                    if (k.netIn.remaining() < pl.length) k.netIn = grow(k.netIn, pl.length);
                    k.netIn.put(pl);
                }
            }
            if (!k.tlsStarted && k.recvNext > 0) startTls(c, k); // na de reset van de server
            try { toApply = pump(c, k); } catch (Exception e) {
                String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                lastError = "TLS-fout: " + m;
                event("error", lastError);
                c.dead = true; close(c); failures++; remoteIdx++; retryAt = now() + 2000;
                return;
            }
            flush(c, k);
        }
        if (toApply != null) applyPush(c, toApply);
    }

    private static ByteBuffer grow(ByteBuffer b, int need) {
        ByteBuffer n = ByteBuffer.allocate(Math.max(b.capacity() * 2, b.position() + need + 1024));
        b.flip(); n.put(b);
        return n;
    }

    private Ks newKeyState(Conn c, int keyId, boolean weStart) {
        Ks k = new Ks(keyId);
        Ks old = c.ks[keyId];
        c.ks[keyId] = k;
        if (old != null && old.data != null && old.data == c.lameDuck) c.lameDuck = null;
        return k;
    }

    // ================= TLS =================

    private void startTls(Conn c, Ks k) throws IllegalStateException {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(keyManagers(), new TrustManager[]{new Trust(cfg)}, WgCrypto.RNG);
            SSLEngine e = ctx.createSSLEngine();
            e.setUseClientMode(true);
            List<String> prots = new ArrayList<>();
            for (String s : e.getSupportedProtocols()) if (s.equals("TLSv1.2") || s.equals("TLSv1.3")) prots.add(s);
            if (!prots.isEmpty()) e.setEnabledProtocols(prots.toArray(new String[0]));
            e.beginHandshake();
            k.eng = e;
            k.tlsStarted = true;
        } catch (Exception ex) {
            throw new IllegalStateException("TLS starten lukt niet: " + ex.getMessage());
        }
    }

    private KeyManager[] keyManagers() throws Exception {
        if (cfg.cert == null) return null;
        List<X509Certificate> chain = OvpnConfig.certs(cfg.cert);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        char[] pw = "x".toCharArray();
        ks.setKeyEntry("client", OvpnConfig.privateKey(cfg.key), pw, chain.toArray(new X509Certificate[0]));
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pw);
        return kmf.getKeyManagers();
    }

    /** Servercertificaat controleren: van de CA uit de configuratie, plus remote-cert-tls en verify-x509-name. */
    static final class Trust implements X509TrustManager {
        private final X509TrustManager base;
        private final OvpnConfig cfg;

        Trust(OvpnConfig cfg) throws Exception {
            this.cfg = cfg;
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            int i = 0;
            for (X509Certificate ca : OvpnConfig.certs(cfg.ca)) ks.setCertificateEntry("ca" + (i++), ca);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            X509TrustManager t = null;
            for (TrustManager tm : tmf.getTrustManagers()) if (tm instanceof X509TrustManager) t = (X509TrustManager) tm;
            if (t == null) throw new Exception("geen TrustManager");
            base = t;
        }

        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException { throw new CertificateException("niet gebruikt"); }

        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            base.checkServerTrusted(chain, authType);
            X509Certificate leaf = chain[0];
            if (cfg.remoteCertTls || cfg.nsCertType) {
                List<String> eku = null;
                try { eku = leaf.getExtendedKeyUsage(); } catch (Exception ignored) { }
                boolean ok = eku != null && eku.contains("1.3.6.1.5.5.7.3.1");
                if (!ok && cfg.nsCertType && !cfg.remoteCertTls) ok = nsServer(leaf); // oud: Netscape cert type "SSL server"
                if (!ok) throw new CertificateException("Het certificaat van de server is niet voor een server (remote-cert-tls)");
            }
            if (cfg.verifyName != null && !nameOk(leaf, cfg.verifyName, cfg.verifyType))
                throw new CertificateException("De naam van de server klopt niet (verify-x509-name " + cfg.verifyName + ")");
        }

        /** Netscape cert type (2.16.840.1.113730.1.1) met het bit "SSL server". */
        static boolean nsServer(X509Certificate c) {
            byte[] v = c.getExtensionValue("2.16.840.1.113730.1.1");
            if (v == null || v.length < 5) return false;
            // OCTET STRING { BIT STRING { unused, bits } }
            int i = 0;
            if ((v[i] & 0xff) == 0x04) i += 2;
            if ((v[i] & 0xff) != 0x03 || v.length < i + 4) return false;
            return (v[i + 3] & 0x40) != 0;
        }

        static String cn(X509Certificate c) {
            for (String part : c.getSubjectX500Principal().getName("RFC2253").split("(?<!\\\\),")) {
                if (part.regionMatches(true, 0, "CN=", 0, 3)) return part.substring(3).replace("\\,", ",");
            }
            return "";
        }

        static boolean nameOk(X509Certificate c, String want, String type) {
            String cn = cn(c);
            if (type.equals("name")) return cn.equals(want);
            if (type.equals("name-prefix")) return cn.startsWith(want);
            // subject: "C=NL, CN=vpn" of "/C=NL/CN=vpn" (precies dezelfde onderdelen), of alleen een naam
            if (!want.contains("=")) return cn.equals(want);
            java.util.List<String> have = new java.util.ArrayList<>(), need = new java.util.ArrayList<>();
            for (String part : c.getSubjectX500Principal().getName("RFC2253").split("(?<!\\\\),")) have.add(norm(part));
            for (String part : want.split(want.startsWith("/") ? "/" : ",")) if (!part.trim().isEmpty()) need.add(norm(part));
            java.util.Collections.sort(have); java.util.Collections.sort(need);
            return have.equals(need);
        }

        /** "cn = Naam" → "CN=Naam" (sleutel in hoofdletters, waarde precies). */
        static String norm(String part) {
            String p = part.trim();
            int e = p.indexOf('=');
            if (e < 0) return p;
            return p.substring(0, e).trim().toUpperCase(java.util.Locale.ROOT) + "=" + p.substring(e + 1).trim().replace("\\,", ",");
        }

        @Override public X509Certificate[] getAcceptedIssuers() { return base.getAcceptedIssuers(); }
    }

    /** TLS laten draaien: ontvangen bytes ontsleutelen, te versturen bytes in controlepakketten. Met het slot. */
    private Push pump(Conn c, Ks k) throws Exception {
        if (k.eng == null) return null;
        SSLEngine e = k.eng;
        Push result = null;
        for (int guard = 0; guard < 200; guard++) {
            boolean progress = false;
            SSLEngineResult.HandshakeStatus hs = e.getHandshakeStatus();
            if (hs == SSLEngineResult.HandshakeStatus.NEED_TASK) { Runnable r; while ((r = e.getDelegatedTask()) != null) r.run(); continue; }
            // versturen: handshake-gegevens, of onze eigen berichten (key-method, PUSH_REQUEST)
            boolean hsDone = hs == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING || hs == SSLEngineResult.HandshakeStatus.FINISHED;
            if (hsDone && !k.kmSent) { k.pendingApp = concat(k.pendingApp, keyMethod(c, k)); k.kmSent = true; }
            if (hs == SSLEngineResult.HandshakeStatus.NEED_WRAP || (hsDone && k.pendingApp != null)) {
                ByteBuffer src = k.pendingApp != null && hsDone ? ByteBuffer.wrap(k.pendingApp) : ByteBuffer.allocate(0);
                ByteBuffer out = ByteBuffer.allocate(e.getSession().getPacketBufferSize() + 1024);
                SSLEngineResult r = e.wrap(src, out);
                if (hsDone && k.pendingApp != null) k.pendingApp = src.hasRemaining() ? java.util.Arrays.copyOfRange(k.pendingApp, src.position(), k.pendingApp.length) : null;
                out.flip();
                if (out.hasRemaining()) {
                    byte[] bytes = new byte[out.remaining()];
                    out.get(bytes);
                    for (int i = 0; i < bytes.length; i += TLS_CHUNK) queue(c, k, P_CONTROL_V1, java.util.Arrays.copyOfRange(bytes, i, Math.min(bytes.length, i + TLS_CHUNK)));
                    progress = true;
                }
                if (r.getStatus() == SSLEngineResult.Status.CLOSED) throw new Exception("verbinding gesloten");
                if (r.bytesConsumed() > 0) progress = true;
            }
            if (k.netIn.position() > 0) {
                k.netIn.flip();
                SSLEngineResult r = e.unwrap(k.netIn, k.appIn);
                k.netIn.compact();
                if (r.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) { ByteBuffer n = ByteBuffer.allocate(k.appIn.capacity() * 2); k.appIn.flip(); n.put(k.appIn); k.appIn = n; progress = true; }
                else if (r.getStatus() == SSLEngineResult.Status.CLOSED) throw new Exception("de server sloot de verbinding");
                else if (r.bytesConsumed() > 0 || r.bytesProduced() > 0) progress = true;
                if (k.appIn.position() > 0) {
                    k.appIn.flip();
                    byte[] got = new byte[k.appIn.remaining()];
                    k.appIn.get(got);
                    k.appIn.clear();
                    k.app.write(got, 0, got.length);
                    if (k.app.size() > 256 * 1024) throw new Exception("te veel gegevens van de server zonder einde");
                    Push p = appData(c, k);
                    if (p != null) result = p;
                }
            }
            if (!progress) break;
        }
        return result;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        if (a == null) return b;
        byte[] r = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    // ================= key-method 2 en berichten =================

    String peerInfo() {
        List<String> ciphers = new ArrayList<>();
        String[] ours = {"AES-256-GCM", "AES-128-GCM", "CHACHA20-POLY1305", "AES-256-CBC", "AES-128-CBC"};
        if (cfg.dataCiphers.isEmpty()) { ciphers.add("AES-256-GCM"); ciphers.add("AES-128-GCM"); ciphers.add("CHACHA20-POLY1305"); }
        else for (String s : cfg.dataCiphers) for (String o : ours) if (o.equals(s)) ciphers.add(s);
        if (!cfg.cipher.isEmpty() && !ciphers.contains(cfg.cipher)) for (String o : ours) if (o.equals(cfg.cipher)) ciphers.add(cfg.cipher);
        return "IV_VER=2.6.12\nIV_PLAT=android\nIV_PROTO=" + System.getProperty("ovpn.ivproto", "6") + "\nIV_NCP=2\nIV_CIPHERS=" + String.join(":", ciphers)
                + "\nIV_LZO_STUB=1\nIV_COMP_STUB=1\nIV_COMP_STUBv2=1\nIV_TCPNL=1\nIV_GUI_VER=RenesTools\n";
    }

    private byte[] keyMethod(Conn c, Ks k) {
        k.preMaster = new byte[48]; k.r1 = new byte[32]; k.r2 = new byte[32];
        WgCrypto.RNG.nextBytes(k.preMaster); WgCrypto.RNG.nextBytes(k.r1); WgCrypto.RNG.nextBytes(k.r2);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0); b.write(0); b.write(0); b.write(0);
        b.write(2);
        b.write(k.preMaster, 0, 48); b.write(k.r1, 0, 32); b.write(k.r2, 0, 32);
        String opts = "V4,dev-type tun,link-mtu 1559,tun-mtu 1500,proto " + (c.remote.tcp ? "TCPv4_CLIENT" : "UDPv4")
                + ",cipher " + (cfg.cipher.isEmpty() ? "AES-256-GCM" : cfg.cipher) + ",auth " + cfg.auth + ",keysize 256,key-method 2,tls-client";
        writeString(b, opts);
        if (cfg.authUserPass) {
            writeString(b, user);
            writeString(b, c.authToken != null ? c.authToken : pass);
        } else { b.write(0); b.write(0); b.write(0); b.write(0); }
        writeString(b, peerInfo());
        return b.toByteArray();
    }

    private static void writeString(ByteArrayOutputStream b, String s) {
        byte[] x = s.getBytes(StandardCharsets.UTF_8);
        int n = x.length + 1;
        b.write(n >> 8); b.write(n);
        b.write(x, 0, x.length);
        b.write(0);
    }

    /** Ontvangen klare tekst op het TLS-kanaal: eerst het key-method-antwoord, daarna tekstberichten. */
    private Push appData(Conn c, Ks k) throws Exception {
        byte[] a = k.app.toByteArray();
        int pos = 0;
        if (!k.kmDone) {
            // 4 nullen, methode 2, random1 (32), random2 (32), opties (lengte + tekst)
            if (a.length < 4 + 1 + 64 + 2) return null;
            if (a[4] != 2) throw new Exception("onbekende key-method van de server");
            int optLen = (a[69] & 0xff) << 8 | (a[70] & 0xff);
            if (a.length < 71 + optLen) return null;
            byte[] sr1 = java.util.Arrays.copyOfRange(a, 5, 37), sr2 = java.util.Arrays.copyOfRange(a, 37, 69);
            pos = 71 + optLen;
            // de server kan lege gebruikersnaam/wachtwoord meesturen: overslaan als ze er staan
            for (int i = 0; i < 2 && a.length >= pos + 2; i++) { int l = (a[pos] & 0xff) << 8 | (a[pos + 1] & 0xff); if (a.length < pos + 2 + l) break; pos += 2 + l; }
            byte[] master = OvpnCrypto.prf(k.preMaster, "OpenVPN master secret", 48, k.r1, sr1);
            k.keyBlock = OvpnCrypto.prf(master, "OpenVPN key expansion", 256, k.r2, sr2, c.localSid, c.remoteSid);
            k.kmDone = true;
            java.util.Arrays.fill(k.preMaster, (byte) 0);
            if (k.keyId != 0 && c.push == null) { /* heronderhandeld nog voor de instellingen: overslaan, sleutel 0 blijft */ }
            else if (k.keyId != 0 || c.push != null) { // heronderhandeld: meteen in gebruik met de afgesproken versleuteling
                k.data = new OvpnCrypto.DataKeys(cipherFor(c.push), cfg.auth, k.keyBlock, k.keyId);
                if (c.primary != null && c.primary != k.data) c.lameDuck = c.primary;
                c.primary = k.data;
                c.lastReneg = now();
                event("renegotiated", "" + k.keyId);
            } else {
                // eerst de instellingen vragen
                k.pendingApp = concat(k.pendingApp, "PUSH_REQUEST\0".getBytes(StandardCharsets.US_ASCII));
                c.lastPushReq = now();
            }
            a = java.util.Arrays.copyOfRange(a, Math.min(pos, a.length), a.length);
            k.app.reset();
            k.app.write(a, 0, a.length);
            pos = 0;
        }
        Push result = null;
        // tekstberichten, elk afgesloten met \0
        while (true) {
            int z = -1;
            for (int i = pos; i < a.length; i++) if (a[i] == 0) { z = i; break; }
            if (z < 0) break;
            String msg = new String(a, pos, z - pos, StandardCharsets.UTF_8);
            pos = z + 1;
            Push p = message(c, k, msg);
            if (p != null) result = p;
        }
        k.app.reset();
        k.app.write(a, pos, a.length - pos);
        return result;
    }

    private String cipherFor(Push p) {
        if (p != null && p.cipher != null) return p.cipher;
        if (!cfg.cipher.isEmpty()) return cfg.cipher;
        throw new IllegalArgumentException("De server noemt geen versleuteling (oude server?); zet 'cipher' in de configuratie");
    }

    private Push message(Conn c, Ks k, String msg) throws Exception {
        if (msg.startsWith("PUSH_REPLY")) {
            String rest = msg.length() > 10 ? msg.substring(11) : "";
            c.pushParts.append(rest).append(',');
            if (c.pushParts.length() > 256 * 1024) throw new Exception("te veel instellingen van de server");
            if (rest.contains("push-continuation 2")) return null; // er komt nog meer
            Push p = Push.parse(c.pushParts.toString(), cfg.routeNoPull);
            c.pushParts.setLength(0);
            if (p.authToken != null) c.authToken = p.authToken;
            if (c.push != null) return null; // al ingesteld (dubbel antwoord)
            c.push = p;
            Ks k0 = c.ks[0];
            k0.data = new OvpnCrypto.DataKeys(cipherFor(p), cfg.auth, k0.keyBlock, 0);
            c.primary = k0.data;
            c.established = now();
            c.lastReneg = now();
            failures = 0;
            authFailed = false;
            lastError = null;
            return p;
        }
        if (msg.startsWith("AUTH_FAILED")) {
            String why = msg.length() > 12 ? msg.substring(12).trim() : "";
            c.dead = true; close(c);
            if (c.authToken != null && (why.startsWith("SESSION") || c.established >= 0)) { // verlopen token: opnieuw met het wachtwoord
                c.authToken = null; lastError = "Opnieuw inloggen"; retryAt = now() + 1000; event("reconnecting", lastError); return null;
            }
            if (why.startsWith("TEMP")) { lastError = "De server is even niet beschikbaar (" + why + ")"; retryAt = now() + 30_000; event("reconnecting", lastError); return null; }
            authFailed = true;
            lastError = "Inloggen geweigerd" + (why.isEmpty() ? "" : ": " + why) + " (gebruikersnaam of wachtwoord?)";
            event("auth-failed", lastError);
            retryAt = -1;
            return null;
        }
        if (msg.startsWith("HALT")) {
            halted = true;
            lastError = "De server wil geen verbinding meer (HALT)";
            c.dead = true; close(c);
            retryAt = -1;
            event("auth-failed", lastError);
            return null;
        }
        if (msg.startsWith("RESTART") || msg.startsWith("EXIT")) {
            lastError = "De server vroeg om opnieuw te verbinden";
            c.dead = true; close(c);
            retryAt = now() + 2000;
            event("reconnecting", lastError);
            return null;
        }
        if (msg.startsWith("AUTH_PENDING")) { c.lastPushReq = now() + 30_000; c.authPendingUntil = now() + 180_000; event("auth-pending", msg); return null; }
        return null; // INFO, … overslaan
    }

    private void applyPush(Conn c, Push p) {
        if (c.push != p) return;
        if (applied == null || !applied.sig().equals(p.sig())) {
            try { setup.apply(p); applied = p; c.applied = true; }
            catch (Exception e) {
                lastError = "Tunnel instellen lukt niet: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                event("error", lastError);
                restart(c, lastError, true); // over een tijdje opnieuw (misschien liet Android het even niet toe)
                synchronized (this) { if (conn == c) retryAt = now() + 10_000; }
                return;
            }
        }
        c.applied = true;
        connectedWall = System.currentTimeMillis();
        event("connected", c.remote.toString());
    }

    // ================= datakanaal =================

    private int compressMode(Conn c) {
        String m = c.push != null && c.push.compress != null ? c.push.compress : cfg.compress;
        if (m == null) return 0;
        if (m.equals("lzo")) return 1;
        if (m.endsWith("-v2")) return 3;
        return 2; // stub, lz4 (v1)
    }

    private void sendData(Conn c, OvpnCrypto.DataKeys k, byte[] pkt, int off, int len, boolean frame) throws Exception {
        byte[] pl = pkt; int po = off, pn = len;
        int mode = frame ? compressMode(c) : 0;
        if (mode == 1 || mode == 2 || (mode == 3 && len > 0 && pkt[off] == 0x50)) {
            byte[] f = new byte[len + (mode == 3 ? 2 : 1)];
            if (mode == 1) { f[0] = (byte) 0xFA; System.arraycopy(pkt, off, f, 1, len); }
            else if (mode == 2) { f[0] = (byte) 0xFB; if (len > 0) { System.arraycopy(pkt, off + 1, f, 1, len - 1); f[len] = pkt[off]; } }
            else { f[0] = 0x50; f[1] = 0; System.arraycopy(pkt, off, f, 2, len); }
            pl = f; po = 0; pn = f.length;
        }
        Push p = c.push;
        byte[] hdr;
        if (p != null && p.peerId >= 0) hdr = new byte[]{(byte) (P_DATA_V2 << 3 | k.keyId), (byte) (p.peerId >> 16), (byte) (p.peerId >> 8), (byte) p.peerId};
        else hdr = new byte[]{(byte) (P_DATA_V1 << 3 | k.keyId)};
        sendRaw(c, k.encrypt(hdr, pl, po, pn));
    }

    private void receiveData(Conn c, byte[] p, int len, int op, int keyId) {
        if (c.dead) return;
        OvpnCrypto.DataKeys k = c.primary;
        if (k == null || k.keyId != keyId) { k = c.lameDuck; if (k == null || k.keyId != keyId) { Ks s = c.ks[keyId]; k = s == null ? null : s.data; } }
        if (k == null) return;
        byte[] pt = k.decrypt(p, op == P_DATA_V2 ? 4 : 1, len);
        if (pt == null) return;
        c.lastRecv = now();
        if (pt.length == PING.length && java.util.Arrays.equals(pt, PING)) return;
        // compressie-kader eraf
        int mode = compressMode(c);
        byte[] ip = pt; int off = 0, n = pt.length;
        if (n > 0) {
            int b0 = pt[0] & 0xff;
            if (mode == 1) { if (b0 == 0xFA) { off = 1; n--; } else if (b0 == 0x66) return; }
            else if (mode == 2) {
                if (b0 == 0xFB && n > 1) { ip = new byte[n - 1]; ip[0] = pt[n - 1]; System.arraycopy(pt, 1, ip, 1, n - 2); off = 0; n = n - 1; }
                else if (b0 == 0xFA) { off = 1; n--; }
                else if (b0 == 0x66 || b0 == 0x69 || b0 == 0x2A) { if (b0 != 0x2A) return; }
            } else if (mode == 3 && b0 == 0x50 && n >= 2) { if (pt[1] != 0) return; off = 2; n -= 2; }
        }
        if (n == PING.length && regionEq(ip, off, PING)) return;
        if (n >= OCC.length && regionEq(ip, off, OCC)) return;
        if (n < 20) return;
        WgDevice.Tun t = tun;
        if (t == null) return;
        int v = (ip[off] & 0xff) >> 4;
        int real = v == 4 ? ((ip[off + 2] & 0xff) << 8 | (ip[off + 3] & 0xff)) : v == 6 && n >= 40 ? 40 + ((ip[off + 4] & 0xff) << 8 | (ip[off + 5] & 0xff)) : -1;
        if (real < 20 || real > n) return;
        try { t.write(ip, off, real); } catch (IOException e) { lastError = e.getMessage(); }
    }

    private static boolean regionEq(byte[] a, int off, byte[] b) {
        if (a.length - off < b.length) return false;
        for (int i = 0; i < b.length; i++) if (a[off + i] != b[i]) return false;
        return true;
    }

    private void tunLoop() {
        byte[] buf = new byte[65536];
        while (running) {
            WgDevice.Tun t = tun;
            try {
                int n = t == null ? -1 : t.read(buf);
                if (n < 0) {
                    if (t != tun) continue; // tunnel opnieuw opgebouwd (andere instellingen van de server): met de nieuwe verder
                    if (running) { lastError = "Tunnel gesloten"; event("tun-closed", ""); }
                    return;
                }
                Conn c = conn;
                if (c == null || c.dead) continue;
                OvpnCrypto.DataKeys k = c.primary;
                if (k == null || n == 0) continue;
                sendData(c, k, buf, 0, n, true);
            } catch (Throwable e) {
                if (!running) return;
                if (t == tun) { lastError = e.getMessage(); try { Thread.sleep(50); } catch (InterruptedException ignored) { } }
            }
        }
    }

    /** Is de verbinding definitief mislukt (wachtwoord fout)? Dan heeft opnieuw proberen geen zin. */
    boolean fatal() { return authFailed || halted; }

    /** Laatste keer (wandklok) dat de verbinding tot stand kwam. */
    long lastOkWall() { return connectedWall; }

    // ================= timers =================

    private void timerLoop() {
        while (running) {
            try { Thread.sleep(250); } catch (InterruptedException e) { if (!running) return; }
            try { tick(); } catch (Throwable e) { lastError = String.valueOf(e); }
        }
    }

    private void tick() throws Exception {
        long t = now();
        boolean reconnect = false;
        Conn c;
        Push toApply = null;
        synchronized (this) {
            c = conn;
            if (retryAt >= 0 && t >= retryAt && running && (c == null || c.dead)) { retryAt = -1; reconnect = true; }
        }
        if (reconnect) { new Thread(this::connectNext, "ovpn-connect").start(); return; }
        if (c == null || c.dead) return;
        OvpnCrypto.DataKeys ping = null;
        synchronized (this) {
            // 1. controlepakketten opnieuw sturen (alleen UDP; TCP is zelf betrouwbaar)
            for (Ks k : c.ks) {
                if (k == null) continue;
                if (c.udp != null) for (Out o : k.unacked.values()) {
                    long wait = Math.min(8000, 1000L << Math.min(3, o.tries - 1));
                    if (o.sentAt >= 0 && t - o.sentAt >= wait) sendControl(c, k, o);
                }
                flush(c, k);
            }
            // 2. instellingen opnieuw vragen
            Ks k0 = c.ks[0];
            if (c.push == null && k0 != null && k0.kmDone && c.lastPushReq >= 0 && t - c.lastPushReq > 2000) {
                c.lastPushReq = t;
                k0.pendingApp = concat(k0.pendingApp, "PUSH_REQUEST\0".getBytes(StandardCharsets.US_ASCII));
                try { toApply = pump(c, k0); } catch (Exception e) { lastError = e.getMessage(); }
                flush(c, k0);
            }
            // 3. geen enkel antwoord binnen 10 s (UDP geblokkeerd?), of verbinden duurt te lang (60 s): volgende server
            if (c.established < 0 && (t - c.started > (c.authPendingUntil > t ? 180_000 : 60_000) || c.remoteSid == null && t - c.started > NO_ANSWER_MS)) {
                lastError = "De server reageert niet (" + c.remote + ")";
                c.dead = true; close(c); failures++; remoteIdx++; retryAt = t + 1000;
                event("error", lastError);
                return;
            }
            // 4. ping-restart: niets meer gehoord
            int pr = c.push != null && c.push.pingRestart > 0 ? c.push.pingRestart : cfg.pingRestart > 0 ? cfg.pingRestart : 120;
            if (c.established >= 0 && t - c.lastRecv > pr * 1000L) {
                lastError = "Geen contact meer met de server";
                c.dead = true; close(c); retryAt = t + 500;
                event("reconnecting", lastError);
                return;
            }
            // 5. heronderhandelen (als de server het niet zelf doet)
            if (c.established >= 0 && cfg.renegSec > 0 && t - c.lastReneg > cfg.renegSec * 1000L + Math.min(30_000L, cfg.renegSec * 250L)) {
                int id = c.primary.keyId % 7 + 1; // na de nieuwste sleutel (ook als de server die begon)
                Ks k = newKeyState(c, id, true);
                c.lastReneg = t;
                queue(c, k, P_CONTROL_SOFT_RESET_V1, new byte[0]); // TLS begint zodra de reset van de server binnen is
                flush(c, k);
            }
            // 6. onderhandelingen die na een minuut niet klaar zijn: weg (anders blijven ze herhaald worden)
            for (int i = 1; i < 8; i++) { Ks x = c.ks[i]; if (x != null && x.data == null && t - x.started > 60_000) c.ks[i] = null; }
            // de oude sleutel na een minuut weg
            if (c.lameDuck != null && t - c.lastReneg > 60_000) c.lameDuck = null;
            int pi = c.push != null && c.push.ping > 0 ? c.push.ping : cfg.pingSec > 0 ? cfg.pingSec : 10;
            if (c.primary != null && t - c.lastSent > pi * 1000L) ping = c.primary;
        }
        if (toApply != null) applyPush(c, toApply);
        if (ping != null) try { sendData(c, ping, PING, 0, PING.length, true); } catch (Exception ignored) { } // ook met compressiekader
    }
}
