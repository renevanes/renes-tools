package nl.rene.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * OpenVPN-client (OvpnDevice) tegen een echte OpenVPN 2.6-server (tests/ovpn/run-interop.sh start de servers).
 * Pingt het tunneladres van de server (de Linux-kernel antwoordt) en kijkt of het antwoord terugkomt.
 */
public class OvpnInterop {
    static int fails = 0;
    static String dir;
    static void check(boolean ok, String what) { System.out.println((ok ? "OK   " : "FOUT ") + what); if (!ok) fails++; }
    static String f(String name) throws Exception { return new String(Files.readAllBytes(Paths.get(dir, name)), StandardCharsets.UTF_8); }

    static final class QTun implements WgDevice.Tun {
        final LinkedBlockingQueue<byte[]> toVpn = new LinkedBlockingQueue<>(), fromVpn = new LinkedBlockingQueue<>();
        public int read(byte[] buf) throws java.io.IOException {
            try { byte[] p = toVpn.take(); if (p.length == 0) return -1; System.arraycopy(p, 0, buf, 0, p.length); return p.length; }
            catch (InterruptedException e) { throw new java.io.IOException("gestopt"); }
        }
        public void write(byte[] b, int off, int len) { fromVpn.add(java.util.Arrays.copyOfRange(b, off, off + len)); }
    }

    static final class JvmNet implements OvpnDevice.Net {
        public boolean protect(java.net.DatagramSocket s) { return true; }
        public boolean protect(java.net.Socket s) { return true; }
        public java.net.InetAddress[] resolve(String h) throws java.io.IOException { return java.net.InetAddress.getAllByName(h); }
    }

    static int csum(byte[] b, int off, int len, long start) {
        long s = start;
        for (int i = 0; i < len - 1; i += 2) s += ((b[off + i] & 0xff) << 8) | (b[off + i + 1] & 0xff);
        if ((len & 1) == 1) s += (b[off + len - 1] & 0xff) << 8;
        while ((s >> 16) != 0) s = (s & 0xffff) + (s >> 16);
        return (int) (~s & 0xffff);
    }

    static byte[] ping4(byte[] src, byte[] dst, int seq, int size) {
        byte[] p = new byte[28 + size];
        p[0] = 0x45; p[2] = (byte) (p.length >> 8); p[3] = (byte) p.length; p[8] = 64; p[9] = 1;
        System.arraycopy(src, 0, p, 12, 4); System.arraycopy(dst, 0, p, 16, 4);
        int c = csum(p, 0, 20, 0); p[10] = (byte) (c >> 8); p[11] = (byte) c;
        p[20] = 8; p[24] = 0x12; p[25] = 0x34; p[26] = (byte) (seq >> 8); p[27] = (byte) seq;
        for (int i = 0; i < size; i++) p[28 + i] = (byte) (i + seq);
        c = csum(p, 20, 8 + size, 0); p[22] = (byte) (c >> 8); p[23] = (byte) c;
        return p;
    }

    static byte[] ping6(byte[] src, byte[] dst, int seq) {
        byte[] p = new byte[40 + 8 + 16];
        int pl = 8 + 16;
        p[0] = 0x60; p[4] = 0; p[5] = (byte) pl; p[6] = 58; p[7] = 64;
        System.arraycopy(src, 0, p, 8, 16); System.arraycopy(dst, 0, p, 24, 16);
        p[40] = (byte) 128; p[44] = 0x12; p[45] = 0x34; p[46] = (byte) (seq >> 8); p[47] = (byte) seq;
        long s = 0;
        for (int i = 8; i < 40; i += 2) s += ((p[i] & 0xff) << 8) | (p[i + 1] & 0xff);
        s += pl + 58;
        int c = csum(p, 40, pl, s); p[42] = (byte) (c >> 8); p[43] = (byte) c;
        return p;
    }

    static final class Run {
        OvpnDevice dev; QTun tun = new QTun(); volatile OvpnDevice.Push push; final StringBuilder events = new StringBuilder();
        volatile boolean connected, authFailed;
        Run(String conf, String user, String pass) {
            OvpnConfig c = OvpnConfig.parse(conf);
            dev = new OvpnDevice(c, user, pass, new JvmNet(), (w, d) -> {
                synchronized (events) { events.append(w).append(d.isEmpty() ? "" : "(" + d + ")").append(' '); }
                if (w.equals("connected")) connected = true;
                if (w.equals("auth-failed")) authFailed = true;
            }, p -> { push = p; dev.setTun(tun); });
            dev.start();
        }
        boolean waitConnected(int ms) throws Exception { long end = System.currentTimeMillis() + ms; while (!connected && System.currentTimeMillis() < end) Thread.sleep(50); return connected; }
        byte[] myAddr4() { for (WgConfig.Cidr c : push.addresses) if (!c.v6()) return c.addr; return null; }
        byte[] myAddr6() { for (WgConfig.Cidr c : push.addresses) if (c.v6()) return c.addr; return null; }
        /** ping en wacht op het antwoord (echo reply). */
        boolean ping(byte[] dst, int seq, int size) throws Exception {
            tun.toVpn.add(ping4(myAddr4(), dst, seq, size));
            long end = System.currentTimeMillis() + 4000;
            while (System.currentTimeMillis() < end) {
                byte[] r = tun.fromVpn.poll(200, TimeUnit.MILLISECONDS);
                if (r != null && r.length >= 28 && r[9] == 1 && r[20] == 0 && (((r[26] & 0xff) << 8) | (r[27] & 0xff)) == seq) return r.length == 28 + size;
            }
            return false;
        }
        boolean ping6(byte[] dst, int seq) throws Exception {
            tun.toVpn.add(OvpnInterop.ping6(myAddr6(), dst, seq));
            long end = System.currentTimeMillis() + 4000;
            while (System.currentTimeMillis() < end) {
                byte[] r = tun.fromVpn.poll(200, TimeUnit.MILLISECONDS);
                if (r != null && r.length >= 48 && (r[0] & 0xf0) == 0x60 && (r[40] & 0xff) == 129) return true;
            }
            return false;
        }
        void stop() { dev.stop(); tun.toVpn.add(new byte[0]); }
    }

    static String conf(String remote, String extra, boolean cert, String keyFile) throws Exception {
        StringBuilder b = new StringBuilder("client\ndev tun\nnobind\npersist-key\nremote-cert-tls server\nverb 3\n").append(remote).append('\n').append(extra).append('\n');
        b.append("<ca>\n").append(f("ca.crt")).append("</ca>\n");
        if (cert) b.append("<cert>\n").append(f(keyFile.startsWith("client-rsa") ? "client-rsa.crt" : "client.crt")).append("</cert>\n<key>\n").append(f(keyFile)).append("</key>\n");
        return b.toString();
    }

    public static void main(String[] a) throws Exception {
        dir = a[0];
        String only = a.length > 1 ? a[1] : "";
        byte[] s1 = {10, 81, 0, 1}, s2 = {10, 82, 0, 1}, s3 = {10, 83, 0, 1}, s4 = {10, 84, 0, 1}, s5 = {10, 85, 0, 1};

        if (only.isEmpty() || only.equals("1")) {
            // 1. UDP, tls-crypt, AES-256-GCM, certificaat (EC, PKCS#8), IPv6, redirect-gateway, DNS
            Run r = new Run(conf("remote 127.0.0.1 11941 udp", "<tls-crypt>\n" + f("tc.key") + "</tls-crypt>\n", true, "client.key"), null, null);
            check(r.waitConnected(15000), "1 UDP + tls-crypt: verbonden (" + r.events + ")");
            if (r.connected) {
                check(r.dev.cfg != null && r.push.cipher != null && r.push.cipher.equals("AES-256-GCM"), "1 versleuteling AES-256-GCM (" + r.push.cipher + ")");
                check(r.push.routes.toString().contains("0.0.0.0/0") && r.push.dns.contains("10.81.0.53"), "1 routes en DNS: " + r.push.routes + " " + r.push.dns);
                check(r.ping(s1, 1, 56), "1 ping naar de server");
                boolean sizes = true;
                for (int n = 0; n <= 1372 && sizes; n += 98) sizes = r.ping(s1, 100 + n, n);
                check(sizes, "1 pings van 0 tot 1372 bytes");
                if (r.myAddr6() != null) check(r.ping6(new byte[]{(byte) 0xfd, 0, 0, (byte) 0x81, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1}, 7), "1 IPv6-ping");
                r.dev.networkChanged();
                Thread.sleep(300);
                r.connected = false;
                check(r.waitConnected(15000) && r.ping(s1, 9, 10), "1 na netwerkwissel opnieuw verbonden");
            }
            r.stop();
            // zelfde server met RSA (PKCS#1) en EC (SEC1) sleutels
            Run r2 = new Run(conf("remote 127.0.0.1 11941 udp", "<tls-crypt>\n" + f("tc.key") + "</tls-crypt>\n", true, "client-sec1.key"), null, null);
            check(r2.waitConnected(15000) && r2.ping(s1, 2, 20), "1 EC-sleutel (SEC1)");
            r2.stop();
            Run r3 = new Run(conf("remote 127.0.0.1 11941 udp", "<tls-crypt>\n" + f("tc.key") + "</tls-crypt>\n", true, "client-rsa-pkcs1.key"), null, null);
            check(r3.waitConnected(15000) && r3.ping(s1, 3, 20), "1 RSA-sleutel (PKCS#1)");
            r3.stop();
            // verkeerde CA: weigeren
            String bad = conf("remote 127.0.0.1 11941 udp", "<tls-crypt>\n" + f("tc.key") + "</tls-crypt>\n", true, "client.key").replace(f("ca.crt"), f("other-ca.crt"));
            Run r4 = new Run(bad, null, null);
            Thread.sleep(6000);
            check(!r4.connected && String.valueOf(r4.dev.lastError).contains("TLS"), "1 server met ander certificaat geweigerd (" + r4.dev.lastError + ")");
            r4.stop();
        }
        if (only.isEmpty() || only.equals("2")) {
            // 2. TCP, tls-auth, gebruikersnaam/wachtwoord zonder clientcertificaat, ChaCha20-Poly1305
            String c2 = conf("remote 127.0.0.1 11942 tcp-client", "auth-user-pass\nkey-direction 1\n<tls-auth>\n" + f("ta.key") + "</tls-auth>\ndata-ciphers CHACHA20-POLY1305\n", false, null);
            Run r = new Run(c2, "rene", "geheim123");
            check(r.waitConnected(15000), "2 TCP + tls-auth + wachtwoord: verbonden (" + r.events + ")");
            if (r.connected) {
                check("CHACHA20-POLY1305".equals(r.push.cipher), "2 ChaCha20-Poly1305 (" + r.push.cipher + ")");
                boolean ok = true;
                for (int i = 0; i < 20 && ok; i++) ok = r.ping(s2, 200 + i, 300);
                check(ok, "2 20 pings over TCP");
            }
            r.stop();
            Run bad = new Run(c2, "rene", "fout");
            Thread.sleep(8000);
            check(bad.authFailed && !bad.connected, "2 verkeerd wachtwoord: AUTH_FAILED (" + bad.events + ")");
            bad.stop();
        }
        if (only.isEmpty() || only.equals("3")) {
            // 3. UDP zonder tls-auth, oud AES-256-CBC met SHA256, compress stub-v2
            Run r = new Run(conf("remote 127.0.0.1 11943 udp", "data-ciphers AES-256-CBC\ncipher AES-256-CBC\nauth SHA256\n", true, "client.key"), null, null);
            check(r.waitConnected(15000), "3 AES-256-CBC: verbonden (" + r.events + ")");
            if (r.connected) {
                check("AES-256-CBC".equals(r.push.cipher) || r.push.cipher == null, "3 cipher " + r.push.cipher);
                boolean ok = true;
                for (int n = 0; n <= 1300 && ok; n += 130) ok = r.ping(s3, 300 + n, n);
                check(ok, "3 pings in allerlei maten");
            }
            r.stop();
        }
        if (only.isEmpty() || only.equals("4")) {
            // 4. comp-lzo no (LZO-kader) en AES-128-GCM
            Run r = new Run(conf("remote 127.0.0.1 11944 udp", "comp-lzo no\ndata-ciphers AES-128-GCM\n", true, "client.key"), null, null);
            check(r.waitConnected(15000), "4 comp-lzo no + AES-128-GCM: verbonden (" + r.events + ")");
            if (r.connected) {
                check("AES-128-GCM".equals(r.push.cipher), "4 cipher " + r.push.cipher);
                check(r.ping(s4, 400, 100) && r.ping(s4, 401, 1000), "4 pings");
            }
            r.stop();
        }
        if (only.isEmpty() || only.equals("5")) {
            // 5. heronderhandelen door de server elke 8 s: verkeer loopt door
            Run r = new Run(conf("remote 127.0.0.1 11945 udp", "<tls-crypt>\n" + f("tc.key") + "</tls-crypt>\n", true, "client.key"), null, null);
            check(r.waitConnected(15000), "5 verbonden");
            int lost = 0;
            if (r.connected) for (int i = 0; i < 30; i++) { if (!r.ping(s5, 500 + i, 64)) lost++; Thread.sleep(700); }
            check(r.connected && lost <= 1 && r.events.toString().contains("renegotiated"), "5 30 s met nieuwe sleutels: " + lost + " kwijt (" + r.events + ")");
            r.stop();
        }
        if (only.isEmpty() || only.equals("6")) {
            // 6. eerste server onbereikbaar: door naar de volgende remote
            Run r = new Run(conf("remote 127.0.0.1 11999 udp\nremote 127.0.0.1 11942 tcp-client", "auth-user-pass\nkey-direction 1\n<tls-auth>\n" + f("ta.key") + "</tls-auth>\nconnect-retry 1\n", false, null), "rene", "geheim123");
            check(r.waitConnected(30000) && r.dev.remoteText().contains("11942"), "6 tweede server (TCP) na een onbereikbare UDP-server (" + r.events + ")");
            r.stop();
        }
        if (only.isEmpty() || only.equals("7")) {
            // 7. de client begint zelf opnieuw onderhandelen (reneg-sec 6), over TCP
            Run r = new Run(conf("remote 127.0.0.1 11946 tcp-client", "reneg-sec 6\n", true, "client.key"), null, null);
            check(r.waitConnected(15000), "7 verbonden");
            int lost = 0;
            byte[] s6 = {10, 86, 0, 1};
            if (r.connected) for (int i = 0; i < 25; i++) { if (!r.ping(s6, 700 + i, 64)) lost++; Thread.sleep(700); }
            check(r.connected && lost == 0 && r.events.toString().contains("renegotiated"), "7 zelf heronderhandeld: " + lost + " kwijt (" + r.events + ")");
            r.stop();
        }
        if (only.isEmpty() || only.equals("8")) {
            // 8. oude datapakketten (P_DATA_V1, geen peer-id) met comp-lzo no; pings moeten ook het kader hebben
            System.setProperty("ovpn.ivproto", "4");
            Run r = new Run(conf("remote 127.0.0.1 11944 udp", "comp-lzo no\ndata-ciphers AES-256-GCM\n", true, "client.key"), null, null);
            check(r.waitConnected(15000) && r.push.peerId < 0, "8 verbonden zonder peer-id (P_DATA_V1)");
            if (r.connected) {
                check(r.ping(s4, 800, 200), "8 ping met P_DATA_V1");
                Thread.sleep(25000); // stil: alleen pings
                check(!r.events.toString().contains("reconnecting") && r.ping(s4, 801, 20), "8 25 s stil zonder opnieuw verbinden (" + r.events + ")");
            }
            r.stop();
            System.clearProperty("ovpn.ivproto");
            String log = f("srv4.log");
            check(!log.contains("Bad compression"), "8 server klaagt niet over het compressiekader");
        }
        if (only.isEmpty() || only.equals("9")) {
            // 9. server valt weg en komt terug: vanzelf opnieuw verbonden (TCP)
            Run r = new Run(conf("remote 127.0.0.1 11946 tcp-client", "connect-retry 1\n", true, "client.key"), null, null);
            check(r.waitConnected(15000), "9 verbonden");
            byte[] s6 = {10, 86, 0, 1};
            Runtime.getRuntime().exec(new String[]{"sh", "-c", "kill $(cat " + dir + "/srv6.pid)"}).waitFor();
            Thread.sleep(4000);
            r.connected = false;
            Runtime.getRuntime().exec(new String[]{"sh", "-c", "openvpn --config " + dir + "/srv6.conf --log-append " + dir + "/srv6.log --writepid " + dir + "/srv6.pid --daemon"}).waitFor();
            check(r.waitConnected(45000) && r.ping(s6, 900, 30), "9 na herstart van de server weer verbonden (" + r.events + ")");
            r.stop();
        }
        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
        System.out.println("Alle OpenVPN-interoptests geslaagd");
        System.exit(0);
    }
}
