package nl.rene.tools;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WireGuard in gewoon Java: de handshake (Noise IKpsk2), het versleutelde verkeer, de timers en het rondsturen van
 * pakketten tussen de tunnel (Tun) en het internet (UDP). Geen Android-code, zodat hij op de pc getest wordt tegen de
 * officiële wireguard-go (tests/wg). VpnSvc koppelt hem aan Android.
 *
 * Volgt het WireGuard-whitepaper (Donenfeld, 2017) en het gedrag van wireguard-go waar het whitepaper vrijlaat.
 */
final class WgDevice {

    /** De tunnel-kant: één IP-pakket per read/write. */
    interface Tun {
        int read(byte[] buf) throws IOException;
        void write(byte[] buf, int off, int len) throws IOException;
    }

    /** Wat er met de UDP-socket moet gebeuren voordat hij gebruikt wordt (Android: buiten de VPN houden). */
    interface Protector { boolean protect(DatagramSocket s); }

    /** Hostnaam opzoeken (Android: via het gewone netwerk, niet via de tunnel). */
    interface Resolver { InetAddress[] resolve(String host) throws IOException; }

    interface Listener { void onEvent(String what); }

    // ----- constanten uit het whitepaper -----
    static final long REKEY_AFTER_MESSAGES = 1L << 60;
    static final long REJECT_AFTER_MESSAGES = -1L - (1L << 13); // 2^64 − 2^13 − 1 (unsigned)
    static final long REKEY_AFTER_TIME = 120_000, REJECT_AFTER_TIME = 180_000, REKEY_ATTEMPT_TIME = 90_000,
            REKEY_TIMEOUT = 5_000, KEEPALIVE_TIMEOUT = 10_000, COOKIE_LIFETIME = 120_000;
    static final int MAX_STAGED = 128;

    static final byte[] CONSTRUCTION = "Noise_IKpsk2_25519_ChaChaPoly_BLAKE2s".getBytes(StandardCharsets.US_ASCII);
    static final byte[] IDENTIFIER = "WireGuard v1 zx2c4 Jason@zx2c4.com".getBytes(StandardCharsets.US_ASCII);
    static final byte[] LABEL_MAC1 = "mac1----".getBytes(StandardCharsets.US_ASCII), LABEL_COOKIE = "cookie--".getBytes(StandardCharsets.US_ASCII);
    static final byte[] C0 = WgCrypto.hash(CONSTRUCTION), H0 = WgCrypto.hash(C0, IDENTIFIER);

    static final int T_INIT = 1, T_RESP = 2, T_COOKIE = 3, T_DATA = 4;

    static long now() { return System.nanoTime() / 1_000_000L; }

    /** Sleutels van één sessie. */
    static final class Keypair {
        final int[] send, recv;
        final int localIndex, remoteIndex;
        final boolean initiator;
        final long created = now();
        final AtomicLong sendCounter = new AtomicLong();
        final Replay replay = new Replay();
        final Peer peer;

        Keypair(Peer peer, byte[] send, byte[] recv, int localIndex, int remoteIndex, boolean initiator) {
            this.peer = peer;
            this.send = WgCrypto.Aead.keyWords(send); this.recv = WgCrypto.Aead.keyWords(recv);
            this.localIndex = localIndex; this.remoteIndex = remoteIndex; this.initiator = initiator;
        }

        boolean expired(long t) { return t - created >= REJECT_AFTER_TIME; }
    }

    /** Tegen herhaalde pakketten: schuivend venster van 2048 tellers. */
    static final class Replay {
        private static final int W = 2048;
        private final long[] bits = new long[W / 64];
        private long last = -1;

        synchronized boolean check(long ctr) {
            if (ctr < 0 || ucmp(ctr, REJECT_AFTER_MESSAGES) >= 0) return false;
            if (ctr > last) {
                long from = last + 1;
                if (ctr - from >= W) java.util.Arrays.fill(bits, 0);
                else for (long c = from; c <= ctr; c++) bits[(int) ((c % W) >>> 6)] &= ~(1L << (c & 63));
                last = ctr;
            } else if (last - ctr >= W) return false;
            int i = (int) ((ctr % W) >>> 6);
            long m = 1L << (ctr & 63);
            if ((bits[i] & m) != 0) return false;
            bits[i] |= m;
            return true;
        }
    }

    /** De andere kant (meestal je server). */
    static final class Peer {
        final WgConfig.Peer cfg;
        final byte[] remotePub, psk, ssStatic, mac1Key, cookieKey;
        volatile InetSocketAddress endpoint;
        // handshake die wij begonnen
        int hsIndex;
        byte[] hsEphPriv, hsC, hsH, lastMac1;
        long hsStarted = -1, hsLastSent = -1;
        // cookie (als de server het druk heeft)
        byte[] cookie;
        long cookieAt;
        // sessies
        volatile Keypair current, previous, next;
        byte[] lastTimestamp = new byte[12];
        // timers
        volatile long lastSent = -1, lastRecv = -1, lastDataRecv = -1, lastDataSent = -1, lastHandshake = -1;
        volatile long lastHandshakeWall;
        volatile boolean keepaliveDue;
        volatile long sentWithoutReply = -1;
        byte[] lastRespMac1; // van ons antwoord op een handshake van de server (voor een cookie daarop)
        long lastResolve = -1;
        int attempts;
        final ArrayDeque<byte[]> staged = new ArrayDeque<>();
        final AtomicLong rx = new AtomicLong(), tx = new AtomicLong();

        Peer(WgConfig.Peer cfg, byte[] privKey) {
            this.cfg = cfg;
            remotePub = cfg.publicKey;
            psk = cfg.presharedKey == null ? new byte[32] : cfg.presharedKey;
            ssStatic = WgCrypto.dh(privKey, remotePub);
            mac1Key = WgCrypto.hash(LABEL_MAC1, remotePub);
            cookieKey = WgCrypto.hash(LABEL_COOKIE, remotePub);
        }

    }

    // ----- toestand van het apparaat -----
    final WgConfig cfg;
    final byte[] privKey, pubKey, localMac1Key;
    final List<Peer> peers = new ArrayList<>();
    final Map<Integer, Object> index = new ConcurrentHashMap<>(); // lokale index → Peer (handshake) of Keypair
    private volatile Tun tun;
    private final Protector protector;
    private final Resolver resolver;
    private final Listener listener;
    private volatile DatagramSocket udp;
    private volatile boolean running;
    private final Object udpLock = new Object();
    private final int mtu;
    private Thread tTun, tUdp, tTimer;
    private byte[] lastTai = new byte[12];
    volatile String lastError;
    /** Laatste handshakepoging gaf geen antwoord (tot de volgende lukt). */
    volatile boolean failing;

    WgDevice(WgConfig cfg, Tun tun, Protector protector, Resolver resolver, Listener listener) {
        this.cfg = cfg; this.tun = tun; this.protector = protector; this.resolver = resolver; this.listener = listener;
        privKey = cfg.privateKey;
        pubKey = WgCrypto.publicKey(privKey);
        localMac1Key = WgCrypto.hash(LABEL_MAC1, pubKey);
        mtu = cfg.mtuOrDefault();
        for (WgConfig.Peer p : cfg.peers) {
            Peer peer = new Peer(p, privKey);
            if (peer.ssStatic == null) throw new IllegalArgumentException("Ongeldige publieke sleutel van de server");
            peers.add(peer);
        }
    }

    private void event(String s) { if (listener != null) try { listener.onEvent(s); } catch (Exception ignored) { } }

    // ================= starten en stoppen =================

    /**
     * Adressen opzoeken, socket openen en de threads starten. Lukt het opzoeken van de server nog niet (wifi net
     * verbonden, inlogpagina, nog geen netwerk na het opstarten), dan blijft hij het op de achtergrond proberen.
     */
    void start() throws IOException {
        for (Peer p : peers) resolve(p);
        openSocket();
        running = true;
        tUdp = new Thread(this::udpLoop, "wg-udp"); tUdp.setDaemon(true); tUdp.start();
        if (tun != null) startTun();
        tTimer = new Thread(this::timerLoop, "wg-timer"); tTimer.setDaemon(true); tTimer.start();
        // meteen verbinden (dan zie je direct of het werkt)
        for (Peer p : peers) if (p.endpoint != null) synchronized (p) { sendInitiation(p, false); }
    }

    /** De tunnel later koppelen (Android: pas na establish(), en die moet na het openen van de socket). */
    void setTun(Tun t) { tun = t; if (running) startTun(); }

    private void startTun() { tTun = new Thread(this::tunLoop, "wg-tun"); tTun.setDaemon(true); tTun.start(); }

    void stop() {
        running = false;
        DatagramSocket u = udp;
        if (u != null) try { u.close(); } catch (Exception ignored) { }
        if (tTimer != null) tTimer.interrupt();
        if (tTun != null) tTun.interrupt();
        for (Peer p : peers) synchronized (p) { p.current = p.previous = p.next = null; p.staged.clear(); p.hsEphPriv = null; }
        index.clear();
    }

    boolean running() { return running; }

    private void openSocket() throws IOException {
        synchronized (udpLock) {
            DatagramSocket old = udp;
            // vaste poort (ListenPort): de oude eerst dicht, anders is de poort nog bezet
            if (old != null && cfg.listenPort > 0) { try { old.close(); } catch (Exception ignored) { } old = null; }
            DatagramSocket ch = new DatagramSocket(null);
            try {
                try { ch.setReceiveBufferSize(1 << 20); ch.setSendBufferSize(1 << 20); } catch (Exception ignored) { } // ruimte voor pieken
                if (cfg.listenPort > 0) ch.setReuseAddress(true);
                if (protector != null && !protector.protect(ch)) throw new IOException("De verbinding kon niet buiten de VPN gehouden worden");
                ch.bind(new InetSocketAddress(cfg.listenPort > 0 ? cfg.listenPort : 0));
            } catch (IOException e) { ch.close(); throw e; }
            udp = ch;
            if (old != null) try { old.close(); } catch (Exception ignored) { }
        }
    }

    /** Na een netwerkwissel (wifi ↔ mobiel): nieuwe socket en meteen opnieuw handshaken. */
    void networkChanged() {
        if (!running) return;
        try { openSocket(); } catch (IOException e) { lastError = e.getMessage(); }
        for (Peer p : peers) if (p.cfg.endpointHost != null && !WgConfig.isIp(p.cfg.endpointHost)) resolve(p); // nieuw netwerk, misschien ander adres
        for (Peer p : peers) synchronized (p) { if (p.endpoint != null) sendInitiation(p, false); }
    }

    /** Server opzoeken. Nooit met de peer vergrendeld aanroepen (DNS kan lang duren). Geeft true als het adres bekend is. */
    private boolean resolve(Peer p) {
        String h = p.cfg.endpointHost;
        if (h == null) return p.endpoint != null;
        p.lastResolve = now();
        InetAddress[] all = null;
        try {
            if (WgConfig.isIp(h)) all = new InetAddress[]{InetAddress.getByAddress(WgConfig.ip(h))};
            else all = resolver != null ? resolver.resolve(h) : InetAddress.getAllByName(h);
        } catch (IOException e) { /* hieronder */ }
        if (all == null || all.length == 0) { if (p.endpoint == null) lastError = "Kan " + h + " niet vinden (geen internet?)"; return p.endpoint != null; }
        InetAddress pick = all[0];
        for (InetAddress a : all) if (a.getAddress().length == 4) { pick = a; break; } // IPv4 eerst: werkt bijna overal
        p.endpoint = new InetSocketAddress(pick, p.cfg.endpointPort);
        return true;
    }

    // ================= versturen =================

    private void sendRaw(Peer p, byte[] buf, int len) {
        InetSocketAddress ep = p.endpoint;
        DatagramSocket u = udp;
        if (ep == null || u == null) return;
        try {
            u.send(new DatagramPacket(buf, 0, len, ep));
            p.lastSent = now();
            p.tx.addAndGet(len);
        } catch (IOException e) {
            lastError = e.getMessage();
        }
    }

    /** Een IP-pakket uit de tunnel versturen (of bewaren tot de handshake klaar is). */
    void sendPacket(byte[] pkt, int len) {
        Peer p = route(pkt, len);
        if (p == null) return;
        long t = now();
        Keypair k = p.current;
        if (k == null || k.expired(t) || ucmp(k.sendCounter.get(), REJECT_AFTER_MESSAGES) >= 0) {
            synchronized (p) {
                if (p.staged.size() >= MAX_STAGED) p.staged.poll();
                p.staged.add(java.util.Arrays.copyOf(pkt, len));
                if (!handshakeInProgress(p, t)) sendInitiation(p, false);
            }
            return;
        }
        encryptAndSend(p, k, pkt, len, t);
        if (k.initiator && (t - k.created >= REKEY_AFTER_TIME || k.sendCounter.get() >= REKEY_AFTER_MESSAGES))
            synchronized (p) { if (!handshakeInProgress(p, t)) sendInitiation(p, false); }
    }

    /** Werkgeheugen per thread (ThreadLocal.withInitial bestaat pas vanaf Android 8). */
    static final class AeadLocal extends ThreadLocal<WgCrypto.Aead> { @Override protected WgCrypto.Aead initialValue() { return new WgCrypto.Aead(); } }
    static final class BufLocal extends ThreadLocal<byte[]> { @Override protected byte[] initialValue() { return new byte[65536 + 64]; } }
    private final AeadLocal aead = new AeadLocal();
    private final BufLocal sendBuf = new BufLocal();

    /** Vergelijken zonder teken (Long.compareUnsigned bestaat pas vanaf Android 8). */
    static int ucmp(long a, long b) { return Long.compare(a ^ Long.MIN_VALUE, b ^ Long.MIN_VALUE); }

    private void encryptAndSend(Peer p, Keypair k, byte[] pkt, int len, long t) {
        long ctr = k.sendCounter.getAndIncrement();
        if (ucmp(ctr, REJECT_AFTER_MESSAGES) >= 0) return;
        int padded = len == 0 ? 0 : Math.min((len + 15) & ~15, Math.max(len, mtu));
        byte[] out = sendBuf.get();
        out[0] = T_DATA; out[1] = 0; out[2] = 0; out[3] = 0;
        WgCrypto.put32(out, 4, k.remoteIndex);
        WgCrypto.put64(out, 8, ctr);
        // klare tekst met opvulling (nullen) in out zetten en ter plekke versleutelen
        System.arraycopy(pkt, 0, out, 16, len);
        for (int i = len; i < padded; i++) out[16 + i] = 0;
        aead.get().seal(k.send, 0, (int) ctr, (int) (ctr >>> 32), null, out, 16, padded, out, 16);
        sendRaw(p, out, 16 + padded + 16);
        if (len > 0) { p.lastDataSent = t; if (p.sentWithoutReply < 0) p.sentWithoutReply = t; }
        p.keepaliveDue = false;
    }

    void sendKeepalive(Peer p) {
        Keypair k = p.current;
        long t = now();
        if (k == null || k.expired(t)) { synchronized (p) { if (!handshakeInProgress(p, t)) sendInitiation(p, false); } return; }
        encryptAndSend(p, k, new byte[0], 0, t);
    }

    /** Welke peer hoort bij het doeladres (langste overeenkomst). */
    private Peer route(byte[] pkt, int len) {
        if (len < 20) return null;
        int v = (pkt[0] & 0xff) >> 4;
        if (v == 4) return owner(pkt, 16, 4);
        if (v == 6 && len >= 40) return owner(pkt, 24, 16);
        return null;
    }

    /** De peer met de langste overeenkomende AllowedIPs voor dit adres. */
    private Peer owner(byte[] a, int off, int alen) {
        Peer best = null; int bestLen = -1;
        for (Peer p : peers) for (WgConfig.Cidr c : p.cfg.allowed)
            if (c.prefix > bestLen && c.contains(a, off, alen)) { best = p; bestLen = c.prefix; }
        return best;
    }

    // ================= handshake: wij beginnen =================

    private boolean handshakeInProgress(Peer p, long t) { return p.hsEphPriv != null && p.hsLastSent >= 0 && t - p.hsLastSent < REKEY_TIMEOUT; }

    private int newIndex(Object owner) {
        while (true) {
            int i = WgCrypto.RNG.nextInt();
            if (index.putIfAbsent(i, owner) == null) return i;
        }
    }

    /** TAI64N, altijd oplopend. */
    private synchronized byte[] tai64n() {
        long ms = System.currentTimeMillis();
        byte[] b = new byte[12];
        long secs = 0x400000000000000aL + ms / 1000;
        for (int i = 0; i < 8; i++) b[i] = (byte) (secs >>> (56 - 8 * i));
        int nanos = (int) (ms % 1000) * 1_000_000;
        b[8] = (byte) (nanos >>> 24); b[9] = (byte) (nanos >>> 16); b[10] = (byte) (nanos >>> 8); b[11] = (byte) nanos;
        if (cmp(b, lastTai) <= 0) { b = lastTai.clone(); for (int i = 11; i >= 0; i--) if (++b[i] != 0) break; }
        lastTai = b;
        return b;
    }

    static int cmp(byte[] a, byte[] b) {
        for (int i = 0; i < a.length; i++) { int d = (a[i] & 0xff) - (b[i] & 0xff); if (d != 0) return d; }
        return 0;
    }

    /** Handshake-begin versturen. Aanroepen met de peer vergrendeld. retry: door de timer (telt pogingen). */
    void sendInitiation(Peer p, boolean retry) {
        long t = now();
        if (p.endpoint == null) return; // nog niet gevonden (de timer zoekt hem op), of wachten tot de server zelf iets stuurt
        if (!retry) { p.hsStarted = t; p.attempts = 0; }
        p.attempts++;
        if (p.hsIndex != 0) index.remove(p.hsIndex);
        WgCrypto.Aead ae = aead.get();
        byte[] msg = new byte[148];
        msg[0] = T_INIT;
        int idx = newIndex(p);
        WgCrypto.put32(msg, 4, idx);
        byte[] c = C0, h = WgCrypto.hash(H0, p.remotePub);
        byte[] ephPriv = WgCrypto.newPrivateKey(), ephPub = WgCrypto.publicKey(ephPriv);
        c = WgCrypto.kdf(1, c, ephPub)[0];
        System.arraycopy(ephPub, 0, msg, 8, 32);
        h = WgCrypto.hash(h, ephPub);
        byte[] ss = WgCrypto.dh(ephPriv, p.remotePub);
        if (ss == null) return;
        byte[][] ck = WgCrypto.kdf(2, c, ss); c = ck[0];
        ae.seal(ck[1], 0, h, pubKey, 0, 32, msg, 40);
        h = WgCrypto.hash(h, java.util.Arrays.copyOfRange(msg, 40, 88));
        ck = WgCrypto.kdf(2, c, p.ssStatic); c = ck[0];
        byte[] ts = tai64n();
        ae.seal(ck[1], 0, h, ts, 0, 12, msg, 88);
        h = WgCrypto.hash(h, java.util.Arrays.copyOfRange(msg, 88, 116));
        addMacs(p, msg, 116);
        p.hsIndex = idx; p.hsEphPriv = ephPriv; p.hsC = c; p.hsH = h; p.hsLastSent = t;
        sendRaw(p, msg, msg.length);
        event("handshake-sent");
    }

    private void addMacs(Peer p, byte[] msg, int macOff) {
        byte[] m1 = WgCrypto.mac(p.mac1Key, msg, macOff);
        System.arraycopy(m1, 0, msg, macOff, 16);
        if (msg[0] == T_INIT) p.lastMac1 = m1; else p.lastRespMac1 = m1;
        if (p.cookie != null && now() - p.cookieAt < COOKIE_LIFETIME - REKEY_TIMEOUT) {
            byte[] m2 = WgCrypto.mac(p.cookie, msg, macOff + 16);
            System.arraycopy(m2, 0, msg, macOff + 16, 16);
        }
    }

    private void consumeResponse(byte[] m, int len, InetSocketAddress from) {
        if (len != 92) return;
        if (!WgCrypto.eq(WgCrypto.mac(localMac1Key, m, 60), 0, m, 60, 16)) return;
        int recvIdx = WgCrypto.le32(m, 8), senderIdx = WgCrypto.le32(m, 4);
        Object o = index.get(recvIdx);
        if (!(o instanceof Peer)) return;
        Peer p = (Peer) o;
        Keypair kp;
        synchronized (p) {
            if (p.hsEphPriv == null || p.hsIndex != recvIdx) return;
            byte[] ephR = java.util.Arrays.copyOfRange(m, 12, 44);
            byte[] c = WgCrypto.kdf(1, p.hsC, ephR)[0], h = WgCrypto.hash(p.hsH, ephR);
            byte[] ee = WgCrypto.dh(p.hsEphPriv, ephR), se = WgCrypto.dh(privKey, ephR);
            if (ee == null || se == null) return;
            c = WgCrypto.kdf(1, c, ee)[0];
            c = WgCrypto.kdf(1, c, se)[0];
            byte[][] ctk = WgCrypto.kdf(3, c, p.psk);
            c = ctk[0];
            h = WgCrypto.hash(h, ctk[1]);
            if (!aead.get().open(ctk[2], 0, h, m, 44, 16, new byte[0], 0)) return;
            byte[][] keys = WgCrypto.kdf(2, c, new byte[0]);
            index.remove(recvIdx);
            p.hsEphPriv = null; p.hsC = null; p.hsH = null; p.hsIndex = 0; p.hsLastSent = -1;
            kp = new Keypair(p, keys[0], keys[1], recvIdx, senderIdx, true);
            index.put(recvIdx, kp);
            install(p, kp);
            p.endpoint = from;
            p.lastRecv = now();
            p.rx.addAndGet(len);
        }
        handshakeDone(p);
        // bevestigen: de bewaarde pakketten, anders een keepalive
        flushStaged(p, true);
    }

    /** Nieuwe sessie als huidige; de vorige blijft nog even geldig voor binnenkomende pakketten. */
    private void install(Peer p, Keypair kp) {
        Keypair old = p.previous;
        if (old != null) index.remove(old.localIndex);
        if (p.next != null && p.next != kp) { index.remove(p.next.localIndex); p.next = null; }
        p.previous = p.current;
        p.current = kp;
    }

    private void handshakeDone(Peer p) {
        failing = false;
        p.lastHandshake = now();
        p.lastHandshakeWall = System.currentTimeMillis();
        p.attempts = 0;
        p.sentWithoutReply = -1;
        lastError = null;
        event("handshake");
    }

    private void flushStaged(Peer p, boolean keepaliveIfEmpty) {
        List<byte[]> l;
        synchronized (p) { l = new ArrayList<>(p.staged); p.staged.clear(); }
        Keypair k = p.current;
        if (k == null) return;
        long t = now();
        for (byte[] b : l) encryptAndSend(p, k, b, b.length, t);
        if (l.isEmpty() && keepaliveIfEmpty) encryptAndSend(p, k, new byte[0], 0, t);
    }

    // ================= handshake: de server begint =================

    private long initTokensAt;
    private double initTokens = 20;

    /** Hooguit ~20 handshakes per seconde van de andere kant verwerken (elk kost rekenwerk): tegen overspoelen. */
    private boolean initAllowed() {
        long t = now();
        initTokens = Math.min(20, initTokens + (t - initTokensAt) * 0.02);
        initTokensAt = t;
        if (initTokens < 1) return false;
        initTokens -= 1;
        return true;
    }

    private void consumeInitiation(byte[] m, int len, InetSocketAddress from) {
        if (len != 148) return;
        if (!WgCrypto.eq(WgCrypto.mac(localMac1Key, m, 116), 0, m, 116, 16)) return;
        if (!initAllowed()) return;
        WgCrypto.Aead ae = aead.get();
        byte[] c = C0, h = WgCrypto.hash(H0, pubKey);
        byte[] ephI = java.util.Arrays.copyOfRange(m, 8, 40);
        c = WgCrypto.kdf(1, c, ephI)[0];
        h = WgCrypto.hash(h, ephI);
        byte[] es = WgCrypto.dh(privKey, ephI);
        if (es == null) return;
        byte[][] ck = WgCrypto.kdf(2, c, es); c = ck[0];
        byte[] spub = new byte[32];
        if (!ae.open(ck[1], 0, h, m, 40, 48, spub, 0)) return;
        h = WgCrypto.hash(h, java.util.Arrays.copyOfRange(m, 40, 88));
        Peer p = null;
        for (Peer q : peers) if (WgCrypto.eq(q.remotePub, 0, spub, 0, 32)) { p = q; break; }
        if (p == null) return;
        ck = WgCrypto.kdf(2, c, p.ssStatic); c = ck[0];
        byte[] ts = new byte[12];
        if (!ae.open(ck[1], 0, h, m, 88, 28, ts, 0)) return;
        h = WgCrypto.hash(h, java.util.Arrays.copyOfRange(m, 88, 116));
        synchronized (p) {
            if (cmp(ts, p.lastTimestamp) <= 0) return; // herhaald
            p.lastTimestamp = ts;
            // antwoord maken
            byte[] r = new byte[92];
            r[0] = T_RESP;
            int idx = newIndex(p);
            WgCrypto.put32(r, 4, idx);
            WgCrypto.put32(r, 8, WgCrypto.le32(m, 4));
            byte[] ephPriv = WgCrypto.newPrivateKey(), ephPub = WgCrypto.publicKey(ephPriv);
            c = WgCrypto.kdf(1, c, ephPub)[0];
            System.arraycopy(ephPub, 0, r, 12, 32);
            h = WgCrypto.hash(h, ephPub);
            byte[] ee = WgCrypto.dh(ephPriv, ephI), se = WgCrypto.dh(ephPriv, p.remotePub);
            if (ee == null || se == null) { index.remove(idx); return; }
            c = WgCrypto.kdf(1, c, ee)[0];
            c = WgCrypto.kdf(1, c, se)[0];
            byte[][] ctk = WgCrypto.kdf(3, c, p.psk);
            c = ctk[0];
            h = WgCrypto.hash(h, ctk[1]);
            ae.seal(ctk[2], 0, h, new byte[0], 0, 0, r, 44);
            byte[][] keys = WgCrypto.kdf(2, c, new byte[0]);
            Keypair kp = new Keypair(p, keys[1], keys[0], idx, WgCrypto.le32(m, 4), false);
            index.put(idx, kp);
            if (p.next != null) index.remove(p.next.localIndex);
            p.next = kp; // pas in gebruik zodra het eerste pakket ermee binnenkomt
            p.endpoint = from;
            p.lastRecv = now();
            p.rx.addAndGet(len);
            addMacs(p, r, 60);
            sendRaw(p, r, r.length);
        }
        event("handshake-answered");
    }

    private void consumeCookie(byte[] m, int len) {
        if (len != 64) return;
        Object o = index.get(WgCrypto.le32(m, 4));
        Peer p = o instanceof Peer ? (Peer) o : o instanceof Keypair ? ((Keypair) o).peer : null;
        if (p == null) return;
        synchronized (p) {
            byte[] cookie = new byte[16], nonce = java.util.Arrays.copyOfRange(m, 8, 32);
            boolean ok = p.lastMac1 != null && aead.get().xopen(p.cookieKey, nonce, p.lastMac1, m, 32, 32, cookie);
            if (!ok && p.lastRespMac1 != null) ok = aead.get().xopen(p.cookieKey, nonce, p.lastRespMac1, m, 32, 32, cookie);
            if (!ok) return;
            p.cookie = cookie;
            p.cookieAt = now();
        }
        event("cookie");
    }

    // ================= ontvangen =================

    private final byte[] recvPlain = new byte[65536 + 64];

    void receive(byte[] m, int len, InetSocketAddress from) {
        if (len < 4) return;
        int type = m[0] & 0xff;
        if (m[1] != 0 || m[2] != 0 || m[3] != 0) return;
        switch (type) {
            case T_DATA: receiveData(m, len, from); break;
            case T_INIT: consumeInitiation(m, len, from); break;
            case T_RESP: consumeResponse(m, len, from); break;
            case T_COOKIE: consumeCookie(m, len); break;
            default: break;
        }
    }

    private void receiveData(byte[] m, int len, InetSocketAddress from) {
        if (len < 32) return;
        Object o = index.get(WgCrypto.le32(m, 4));
        if (!(o instanceof Keypair)) return;
        Keypair k = (Keypair) o;
        Peer p = k.peer;
        long t = now();
        if (k.expired(t)) return;
        long ctr = WgCrypto.le64(m, 8);
        if (!aead.get().open(k.recv, 0, (int) ctr, (int) (ctr >>> 32), null, m, 16, len - 16, recvPlain, 0)) return;
        if (!k.replay.check(ctr)) return;
        int n = len - 32;
        p.endpoint = from; // de server mag van adres wisselen
        p.lastRecv = t;
        p.rx.addAndGet(len);
        p.sentWithoutReply = -1;
        if (k == p.next) { // eerste pakket met de nieuwe sessie (de server begon): nu pas in gebruik
            synchronized (p) { if (k == p.next) { p.next = null; install(p, k); handshakeDone(p); } }
            flushStaged(p, false);
        }
        if (k.initiator && k == p.current && t - k.created >= REJECT_AFTER_TIME - KEEPALIVE_TIMEOUT - REKEY_TIMEOUT)
            synchronized (p) { if (!handshakeInProgress(p, t) && p.hsEphPriv == null) sendInitiation(p, false); }
        if (n == 0) return; // keepalive
        // echte lengte uit de IP-kop (de rest is opvulling) en afzender controleren
        int v = (recvPlain[0] & 0xff) >> 4, real, srcOff, alen;
        if (v == 4 && n >= 20) { real = ((recvPlain[2] & 0xff) << 8) | (recvPlain[3] & 0xff); srcOff = 12; alen = 4; }
        else if (v == 6 && n >= 40) { real = 40 + (((recvPlain[4] & 0xff) << 8) | (recvPlain[5] & 0xff)); srcOff = 8; alen = 16; }
        else return;
        if (real > n || real < (v == 4 ? 20 : 40)) return;
        if (owner(recvPlain, srcOff, alen) != p) return; // afzender hoort niet bij deze peer
        p.lastDataRecv = t;
        p.keepaliveDue = true;
        Tun tn = tun;
        if (tn == null) return;
        try { tn.write(recvPlain, 0, real); } catch (IOException e) { lastError = e.getMessage(); }
    }

    // ================= threads =================

    private void udpLoop() {
        byte[] buf = new byte[65536];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);
        while (running) {
            DatagramSocket u = udp;
            try {
                dp.setLength(buf.length);
                u.receive(dp);
                SocketAddress from = dp.getSocketAddress();
                if (from instanceof InetSocketAddress) receive(buf, dp.getLength(), (InetSocketAddress) from);
            } catch (Exception e) {
                if (!running) return;
                sleep(u == udp ? 100 : 0); // gesloten voor een netwerkwissel: meteen met de nieuwe verder
            }
        }
    }

    private void tunLoop() {
        byte[] buf = new byte[65536];
        while (running) {
            try {
                Tun tn = tun;
                int n = tn == null ? -1 : tn.read(buf);
                if (n < 0) { if (running) { lastError = "Tunnel gesloten"; event("tun-closed"); } return; }
                if (n > 0) sendPacket(buf, n);
            } catch (IOException e) {
                if (!running) return;
                lastError = e.getMessage();
                sleep(50);
            }
        }
    }

    private void timerLoop() {
        while (running) {
            sleep(500);
            if (!running) return;
            try { tick(now()); } catch (Exception e) { lastError = String.valueOf(e); }
        }
    }

    /** De timers van het whitepaper (ongeveer elke halve seconde). */
    void tick(long t) {
        for (Peer p : peers) {
            boolean keepalive = false;
            // server opzoeken buiten de vergrendeling: nog geen adres (elke 5 s), of geen antwoord meer (hooguit elke minuut)
            String host = p.cfg.endpointHost;
            if (host != null && !WgConfig.isIp(host)) {
                boolean wasNull = p.endpoint == null;
                if (wasNull ? t - p.lastResolve >= 5_000 : p.hsEphPriv != null && p.attempts >= 3 && t - p.lastResolve > 60_000) {
                    if (resolve(p) && wasNull) synchronized (p) { sendInitiation(p, false); }
                }
            } else if (host != null && p.endpoint == null) resolve(p);
            synchronized (p) {
                // 1. handshake opnieuw proberen als er geen antwoord kwam
                if (p.hsEphPriv != null && p.hsLastSent >= 0 && t - p.hsLastSent >= REKEY_TIMEOUT + (p.attempts * 37 % 333)) {
                    if (t - p.hsStarted >= REKEY_ATTEMPT_TIME) { // opgeven tot er weer verkeer is
                        p.hsEphPriv = null; index.remove(p.hsIndex); p.hsIndex = 0; p.staged.clear(); p.hsLastSent = -1;
                        lastError = "Geen antwoord van de server";
                        failing = true;
                        event("handshake-failed");
                    } else sendInitiation(p, true);
                }
                // 2. data verstuurd maar niets terug: nieuwe handshake
                if (p.sentWithoutReply >= 0 && t - p.sentWithoutReply >= KEEPALIVE_TIMEOUT + REKEY_TIMEOUT && p.hsEphPriv == null) {
                    p.sentWithoutReply = -1;
                    sendInitiation(p, false);
                }
                // 3. sessies na lange stilte wissen
                if (p.lastHandshake >= 0 && t - p.lastHandshake >= REJECT_AFTER_TIME * 3) {
                    for (Keypair k : new Keypair[]{p.current, p.previous, p.next}) if (k != null) index.remove(k.localIndex);
                    p.current = p.previous = p.next = null;
                    p.lastHandshake = -1;
                }
                // 4. stille keepalive na ontvangen data
                if (p.keepaliveDue && p.lastDataRecv >= 0 && t - p.lastDataRecv >= KEEPALIVE_TIMEOUT && (p.lastSent < p.lastDataRecv)) {
                    p.keepaliveDue = false; keepalive = true;
                }
                // 5. vaste keepalive (PersistentKeepalive)
                int ka = p.cfg.keepalive;
                if (ka > 0 && (p.lastSent < 0 || t - p.lastSent >= ka * 1000L)) keepalive = true;
            }
            if (keepalive) sendKeepalive(p);
        }
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    // ================= status =================

    long rx() { long s = 0; for (Peer p : peers) s += p.rx.get(); return s; }
    long tx() { long s = 0; for (Peer p : peers) s += p.tx.get(); return s; }

    /** Laatste geslaagde handshake (wandklok, ms) van wie dan ook; 0 = nog nooit. */
    long lastHandshakeWall() { long m = 0; for (Peer p : peers) m = Math.max(m, p.lastHandshakeWall); return m; }

    /** Voor het scherm: werkt de tunnel (laatste handshake lukte; ook als hij even stil is zonder keepalive)? */
    boolean healthy() { return !failing && lastHandshakeWall() > 0; }

    boolean connected() {
        long t = now();
        for (Peer p : peers) { Keypair k = p.current; if (k != null && !k.expired(t)) return true; }
        return false;
    }

    String endpointText() {
        for (Peer p : peers) { InetSocketAddress e = p.endpoint; if (e != null) return e.getAddress().getHostAddress() + ":" + e.getPort(); }
        return "";
    }
}
