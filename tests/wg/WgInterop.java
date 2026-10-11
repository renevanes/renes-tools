package nl.rene.tools;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * VPN tegen de echte wireguard-go (tests/wg/server.go): handshake, pakketten heen en terug in allerlei maten,
 * en een server die zelf de handshake begint. Draai met tests/wg/run-interop.sh.
 */
public class WgInterop {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "OK   " : "FOUT ") + what); if (!ok) fails++; }

    static final class QTun implements WgDevice.Tun {
        final LinkedBlockingQueue<byte[]> toWg = new LinkedBlockingQueue<>(), fromWg = new LinkedBlockingQueue<>();
        public int read(byte[] buf) throws java.io.IOException {
            try { byte[] p = toWg.take(); if (p.length == 0) return -1; System.arraycopy(p, 0, buf, 0, p.length); return p.length; }
            catch (InterruptedException e) { throw new java.io.IOException("gestopt"); }
        }
        public void write(byte[] b, int off, int len) { fromWg.add(java.util.Arrays.copyOfRange(b, off, off + len)); }
    }

    static byte[] udp(byte[] src, byte[] dst, int sport, int dport, byte[] payload) {
        byte[] p = new byte[28 + payload.length];
        p[0] = 0x45; p[2] = (byte) (p.length >> 8); p[3] = (byte) p.length; p[8] = 64; p[9] = 17;
        System.arraycopy(src, 0, p, 12, 4); System.arraycopy(dst, 0, p, 16, 4);
        p[20] = (byte) (sport >> 8); p[21] = (byte) sport; p[22] = (byte) (dport >> 8); p[23] = (byte) dport;
        int ul = 8 + payload.length; p[24] = (byte) (ul >> 8); p[25] = (byte) ul;
        System.arraycopy(payload, 0, p, 28, payload.length);
        return p;
    }

    static Process server(String bin, int port, byte[] priv, byte[] peerPub, String extra) throws Exception {
        ProcessBuilder pb = extra == null ? new ProcessBuilder(bin, "" + port, WgConfig.b64(priv), WgConfig.b64(peerPub))
                : new ProcessBuilder(bin, "" + port, WgConfig.b64(priv), WgConfig.b64(peerPub), extra);
        pb.redirectErrorStream(true);
        Process pr = pb.start();
        BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()));
        String l = r.readLine();
        if (!"ready".equals(l)) throw new Exception("server start niet: " + l);
        new Thread(() -> { try { while (r.readLine() != null) { } } catch (Exception ignored) { } }).start();
        return pr;
    }

    public static void main(String[] a) throws Exception {
        String bin = a[0];
        // AllowedIPs met IPv6 voor de IPv6-test
        byte[] sPriv = WgCrypto.newPrivateKey(), sPub = WgCrypto.publicKey(sPriv);
        byte[] cPriv = WgCrypto.newPrivateKey();
        byte[] me = {10, 0, 0, 2}, srv = {10, 0, 0, 1};

        // ---- 1. wij beginnen (gewone client) ----
        Process pr = server(bin, 51999, sPriv, WgCrypto.publicKey(cPriv), null);
        WgConfig cfg = WgConfig.parse("[Interface]\nPrivateKey = " + WgConfig.b64(cPriv) + "\nAddress = 10.0.0.2/32\n\n[Peer]\nPublicKey = " + WgConfig.b64(sPub)
                + "\nAllowedIPs = 10.0.0.0/24, fd00::/64\nEndpoint = 127.0.0.1:51999\n");
        QTun tun = new QTun();
        WgDevice dev = new WgDevice(cfg, tun, null, null, null);
        dev.start();
        tun.toWg.add(udp(me, srv, 1000, 2000, "hallo server".getBytes("UTF-8")));
        byte[] back = tun.fromWg.poll(5, TimeUnit.SECONDS);
        check(back != null && new String(back, 28, back.length - 28, "UTF-8").equals("hallo server") && back[15] == 1 && back[19] == 2, "handshake + eerste pakket terug");
        check(dev.connected() && dev.lastHandshakeWall() > 0, "verbonden, handshake-tijd bekend");
        // allerlei maten (opvulling moet er weer af)
        boolean sizes = true;
        for (int n = 0; n <= 1392 && sizes; n += 29) {
            byte[] pl = new byte[n]; for (int i = 0; i < n; i++) pl[i] = (byte) (i * 7 + n);
            tun.toWg.add(udp(me, srv, 1000, 2000, pl));
            byte[] r = tun.fromWg.poll(3, TimeUnit.SECONDS);
            sizes = r != null && r.length == 28 + n && java.util.Arrays.equals(java.util.Arrays.copyOfRange(r, 28, r.length), pl);
            if (!sizes) System.out.println("  mis bij " + n + " bytes: " + (r == null ? "niets" : r.length + " bytes"));
        }
        check(sizes, "pakketten van 0 tot 1392 bytes heen en terug, precies gelijk");
        // veel tegelijk
        for (int i = 0; i < 500; i++) tun.toWg.add(udp(me, srv, 1000, 2000, ("pakket " + i).getBytes("UTF-8")));
        int got = 0;
        while (tun.fromWg.poll(2, TimeUnit.SECONDS) != null) got++;
        check(got >= 450, "500 pakketten snel achter elkaar: " + got + " terug");
        // IPv6
        byte[] v6 = new byte[48 + 5];
        v6[0] = 0x60; v6[4] = 0; v6[5] = 13; v6[6] = 17; v6[7] = 64;
        v6[8] = (byte) 0xfd; v6[23] = 2; v6[24] = (byte) 0xfd; v6[39] = 1;
        v6[41] = 1; v6[43] = 2; v6[45] = 13;
        System.arraycopy("zes!!".getBytes("UTF-8"), 0, v6, 48, 5);
        tun.toWg.add(v6);
        back = tun.fromWg.poll(3, TimeUnit.SECONDS);
        check(back != null && back.length == 53 && back[23] == 1 && back[39] == 2 && new String(back, 48, 5, "UTF-8").equals("zes!!"), "IPv6-pakket heen en terug");
        // lang: over de 2 minuten heen (nieuwe sleutels) zonder verlies
        if (a.length > 1 && a[1].equals("lang")) {
            int lost = 0;
            long hs0 = dev.lastHandshakeWall();
            for (int i = 0; i < 300; i++) {
                tun.toWg.add(udp(me, srv, 1000, 2000, ("tik " + i).getBytes("UTF-8")));
                if (tun.fromWg.poll(2, TimeUnit.SECONDS) == null) lost++;
                Thread.sleep(1000);
            }
            check(lost <= 1 && dev.lastHandshakeWall() > hs0, "5 minuten verkeer met nieuwe sleutels onderweg: " + lost + " kwijt");
        }
        // niet toegestane afzender wordt niet in de tunnel gezet: server stuurt alleen voor 10.0.0.2 → test met ander doel
        tun.toWg.add(udp(me, new byte[]{8, 8, 8, 8}, 1000, 53, "buiten".getBytes("UTF-8")));
        check(tun.fromWg.poll(1500, TimeUnit.MILLISECONDS) == null, "pakket buiten AllowedIPs gaat niet de tunnel in");
        // netwerkwissel: nieuwe socket + nieuwe handshake, en het werkt nog
        long hs = dev.lastHandshakeWall();
        Thread.sleep(20);
        dev.networkChanged();
        Thread.sleep(800);
        tun.toWg.add(udp(me, srv, 1000, 2000, "na wissel".getBytes("UTF-8")));
        back = tun.fromWg.poll(5, TimeUnit.SECONDS);
        check(back != null && dev.lastHandshakeWall() > hs, "na netwerkwissel opnieuw verbonden");
        System.out.println("  ontvangen " + dev.rx() + " bytes, verstuurd " + dev.tx() + " bytes");
        dev.stop(); tun.toWg.add(new byte[0]);
        pr.destroy(); pr.waitFor();

        // ---- 2. de server begint ----
        byte[] c2Priv = WgCrypto.newPrivateKey();
        Process pr2 = null;
        WgConfig cfg2 = WgConfig.parse("[Interface]\nPrivateKey = " + WgConfig.b64(c2Priv) + "\nAddress = 10.0.0.2/32\nListenPort = 52001\n\n[Peer]\nPublicKey = " + WgConfig.b64(sPub)
                + "\nAllowedIPs = 10.0.0.0/24\nEndpoint = 127.0.0.1:52000\n");
        QTun tun2 = new QTun();
        WgDevice dev2 = new WgDevice(cfg2, tun2, null, null, null);
        // eerst de server laten beginnen: Java luistert al, maar stuurt zelf nog niets (endpoint pas later bereikbaar)
        dev2.start();
        pr2 = server(bin, 52000, sPriv, WgCrypto.publicKey(c2Priv), "initiate:52001");
        byte[] fromSrv = tun2.fromWg.poll(8, TimeUnit.SECONDS);
        check(fromSrv != null && new String(fromSrv, 28, fromSrv.length - 28, "UTF-8").equals("hallo van de server"), "server begint de handshake, pakket komt aan");
        tun2.toWg.add(udp(me, srv, 9, 7, "antwoord".getBytes("UTF-8")));
        back = tun2.fromWg.poll(5, TimeUnit.SECONDS);
        check(back != null && new String(back, 28, back.length - 28, "UTF-8").equals("antwoord"), "antwoord terug over de door de server begonnen sessie");
        dev2.stop(); tun2.toWg.add(new byte[0]);
        pr2.destroy(); pr2.waitFor();

        // ---- 3. verkeerde sleutel: geen verbinding ----
        Process pr3 = server(bin, 52002, sPriv, WgCrypto.publicKey(WgCrypto.newPrivateKey()), null);
        WgConfig cfg3 = WgConfig.parse("[Interface]\nPrivateKey = " + WgConfig.b64(cPriv) + "\nAddress = 10.0.0.2/32\n\n[Peer]\nPublicKey = " + WgConfig.b64(sPub)
                + "\nAllowedIPs = 0.0.0.0/0\nEndpoint = 127.0.0.1:52002\n");
        QTun tun3 = new QTun();
        WgDevice dev3 = new WgDevice(cfg3, tun3, null, null, null);
        dev3.start();
        tun3.toWg.add(udp(me, srv, 1, 2, "x".getBytes("UTF-8")));
        check(tun3.fromWg.poll(3, TimeUnit.SECONDS) == null && !dev3.connected(), "server kent onze sleutel niet: geen verbinding");
        dev3.stop(); tun3.toWg.add(new byte[0]);
        pr3.destroy(); pr3.waitFor();

        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
        System.out.println("Alle interop-tests met wireguard-go geslaagd");
        System.exit(0);
    }
}
