package nl.rene.tools;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** VPN: configuratie inlezen/wegschrijven, adressen, het herhalingsvenster en twee tunnels die met elkaar praten. */
public class WgConfigTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }
    static String err(String conf) { try { WgConfig.parse(conf); return ""; } catch (IllegalArgumentException e) { return e.getMessage(); } }

    public static void main(String[] a) throws Exception {
        byte[] priv = WgCrypto.newPrivateKey(), peer = WgCrypto.publicKey(WgCrypto.newPrivateKey()), psk = WgCrypto.newPrivateKey();
        String conf = "# van mijn server\n[Interface]\nPrivateKey = " + WgConfig.b64(priv) + "  \nAddress = 10.8.0.2/24, fd86:ea04:1115::2/64\nDNS = 1.1.1.1, 2606:4700:4700::1111, thuis.lan\nMTU = 1380\nPostUp = iptables -A x\n\n"
                + "[Peer]\nPublicKey = " + WgConfig.b64(peer) + "\nPresharedKey = " + WgConfig.b64(psk) + "\nAllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = vpn.Voorbeeld.nl:51820\nPersistentKeepalive = 25\n";
        WgConfig c = WgConfig.parse(conf);
        check(c.addresses.size() == 2 && c.addresses.get(0).toString().equals("10.8.0.2/24") && c.addresses.get(1).toString().equals("fd86:ea04:1115::2/64"), "adressen: " + c.addresses);
        check(c.dns.size() == 2 && c.dns.get(1).equals("2606:4700:4700::1111") && c.dnsSearch.size() == 1 && c.dnsSearch.get(0).equals("thuis.lan"), "DNS en zoekdomein");
        check(c.mtu == 1380 && c.peers.size() == 1 && c.peers.get(0).keepalive == 25 && c.peers.get(0).endpoint().equals("vpn.voorbeeld.nl:51820"), "MTU, keepalive, endpoint");
        check(c.fullTunnel(), "0.0.0.0/0 = alles door de tunnel");
        WgConfig c2 = WgConfig.parse(c.toText());
        check(c2.toText().equals(c.toText()) && java.util.Arrays.equals(c2.peers.get(0).presharedKey, psk), "wegschrijven en opnieuw inlezen geeft hetzelfde");
        WgConfig v6 = WgConfig.parse("[Interface]\nPrivateKey=" + WgConfig.b64(priv) + "\nAddress=10.0.0.2\n[Peer]\nPublicKey=" + WgConfig.b64(peer) + "\nAllowedIPs=10.0.0.0/24\nEndpoint=[2001:db8::1]:443\n");
        check(v6.peers.get(0).endpoint().equals("[2001:db8::1]:443") && v6.addresses.get(0).prefix == 32 && !v6.fullTunnel(), "IPv6-endpoint, adres zonder /32");
        // fouten
        check(err("[Interface]\nAddress = 10.0.0.2/32\n[Peer]\nPublicKey = " + WgConfig.b64(peer) + "\nEndpoint = a.nl:1\n").contains("PrivateKey ontbreekt"), "zonder PrivateKey");
        check(err("[Interface]\nPrivateKey = abc\n").contains("geen geldige sleutel"), "kapotte sleutel");
        check(err("[Interface]\nPrivateKey = " + WgConfig.b64(priv) + "\nAddress = 10.0.0.2/33\n").contains("Ongeldig voorvoegsel"), "/33");
        check(err("[Interface]\nPrivateKey = " + WgConfig.b64(priv) + "\nAddress = 10.0.0.2/32\n[Peer]\nPublicKey = " + WgConfig.b64(peer) + "\nAllowedIPs = 0.0.0.0/0\n").contains("Endpoint"), "zonder Endpoint");
        check(err("[Interface]\nPrivateKey = " + WgConfig.b64(priv) + "\nAddress = 10.0.0.2/32\n[Peer]\nPublicKey = " + WgConfig.b64(peer) + "\nEndpoint = vpn.nl\n").contains("poort"), "Endpoint zonder poort");
        check(err("[Interface]\nPrivateKey = " + WgConfig.b64(priv) + "\nAddress = 300.0.0.2/32\n").contains("IP-adres"), "ongeldig IP");
        check(err("[Interface]\nPrivateKey = " + WgConfig.b64(priv) + "\nAddress = 10.0.0.2/32\n[Peer]\nPublicKey = " + WgConfig.b64(WgCrypto.publicKey(priv)) + "\nEndpoint = a.nl:1\n").contains("eigen sleutel"), "eigen sleutel als peer");
        check(err("dit is geen config").length() > 0 && err("").contains("Lege"), "onzin en leeg");
        // adressen
        WgConfig.Cidr n = WgConfig.cidr("10.1.2.3/8", false);
        check(WgConfig.ipString(n.network()).equals("10.0.0.0") && n.contains(new byte[]{10, 9, 9, 9}, 0, 4) && !n.contains(new byte[]{11, 0, 0, 1}, 0, 4), "netwerkdeel en bevat");
        WgConfig.Cidr m = WgConfig.cidr("192.168.1.0/23", false);
        check(m.contains(new byte[]{(byte) 192, (byte) 168, 0, 5}, 0, 4) && !m.contains(new byte[]{(byte) 192, (byte) 168, 2, 5}, 0, 4), "/23 bevat .0.5 niet .2.5");
        check(WgConfig.ipString(WgConfig.ip("2001:0db8:0000:0000:0000:0000:0000:0001")).equals("2001:db8::1") && WgConfig.ipString(WgConfig.ip("::")).equals("::") && WgConfig.ipString(WgConfig.ip("::ffff:1.2.3.4")).equals("::ffff:102:304"), "IPv6 kort schrijven");
        check(!WgConfig.isIp("1.2.3") && !WgConfig.isIp("1::2::3") && !WgConfig.isIp("example.com") && WgConfig.isIp("fe80::1"), "isIp");
        byte[] r = new byte[50]; new java.util.Random(3).nextBytes(r);
        boolean b64ok = true;
        for (int l = 0; l < 50; l++) { byte[] x = java.util.Arrays.copyOf(r, l); b64ok &= java.util.Arrays.equals(WgConfig.unb64(WgConfig.b64(x)), x) && WgConfig.b64(x).equals(java.util.Base64.getEncoder().encodeToString(x)); }
        check(b64ok && WgConfig.unb64("ab=c") == null && WgConfig.unb64("abc") == null, "base64");
        // herhalingsvenster
        WgDevice.Replay rp = new WgDevice.Replay();
        check(rp.check(0) && !rp.check(0) && rp.check(2) && rp.check(1) && !rp.check(1), "venster: dubbel geweigerd, te laat maar nieuw goed");
        check(rp.check(5000) && !rp.check(2000) && rp.check(4000) && !rp.check(4000), "venster: te oud geweigerd, binnen venster goed");
        check(!rp.check(-1), "venster: te grote teller geweigerd");

        // twee tunnels die met elkaar praten (de één begint, de ander antwoordt)
        byte[] aPriv = WgCrypto.newPrivateKey(), bPriv = WgCrypto.newPrivateKey();
        WgConfig ca = WgConfig.parse("[Interface]\nPrivateKey=" + WgConfig.b64(aPriv) + "\nAddress=10.0.0.2/32\n[Peer]\nPublicKey=" + WgConfig.b64(WgCrypto.publicKey(bPriv)) + "\nPresharedKey=" + WgConfig.b64(psk) + "\nAllowedIPs=10.0.0.0/24\nEndpoint=127.0.0.1:52101\n");
        WgConfig cb = WgConfig.parse("[Interface]\nPrivateKey=" + WgConfig.b64(bPriv) + "\nAddress=10.0.0.1/32\nListenPort=52101\n[Peer]\nPublicKey=" + WgConfig.b64(WgCrypto.publicKey(aPriv)) + "\nPresharedKey=" + WgConfig.b64(psk) + "\nAllowedIPs=10.0.0.2/32\nEndpoint=127.0.0.1:1\n");
        cb.peers.get(0).endpointHost = null; // B kent A's adres niet: leert het uit de handshake
        Q ta = new Q(), tb = new Q();
        WgDevice db = new WgDevice(cb, tb, null, null, null), da = new WgDevice(ca, ta, null, null, null);
        db.start(); da.start();
        ta.in.add(pkt(new byte[]{10, 0, 0, 2}, new byte[]{10, 0, 0, 1}, "ping"));
        byte[] atB = tb.out.poll(5, TimeUnit.SECONDS);
        check(atB != null && new String(atB, 20, atB.length - 20, "UTF-8").equals("ping"), "A → B over de tunnel (met PresharedKey)");
        tb.in.add(pkt(new byte[]{10, 0, 0, 1}, new byte[]{10, 0, 0, 2}, "pong"));
        byte[] atA = ta.out.poll(5, TimeUnit.SECONDS);
        check(atA != null && new String(atA, 20, atA.length - 20, "UTF-8").equals("pong"), "B → A terug (B antwoordde op de handshake)");
        tb.in.add(pkt(new byte[]{10, 0, 1, 9}, new byte[]{10, 0, 0, 2}, "vals"));
        check(ta.out.poll(1500, TimeUnit.MILLISECONDS) == null, "afzender buiten AllowedIPs: niet doorgelaten");
        da.stop(); db.stop(); ta.in.add(new byte[0]); tb.in.add(new byte[0]);
        // server nog niet te vinden (geen DNS): starten lukt toch, en hij blijft het proberen
        WgConfig cn = WgConfig.parse("[Interface]\nPrivateKey=" + WgConfig.b64(aPriv) + "\nAddress=10.0.0.2/32\n[Peer]\nPublicKey=" + WgConfig.b64(WgCrypto.publicKey(bPriv)) + "\nAllowedIPs=0.0.0.0/0\nEndpoint=vpn.voorbeeld.invalid:51820\n");
        int[] tries = {0};
        Q tn = new Q();
        WgDevice dn = new WgDevice(cn, tn, null, host -> { tries[0]++; throw new java.io.IOException("geen DNS"); }, null);
        boolean started = true;
        try { dn.start(); } catch (Exception e) { started = false; }
        check(started && dn.lastError != null && dn.lastError.contains("niet vinden"), "zonder DNS: start toch, met melding");
        dn.tick(WgDevice.now() + 6000);
        check(tries[0] >= 2, "zoekt de server later opnieuw op (" + tries[0] + "×)");
        dn.stop(); tn.in.add(new byte[0]);

        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
        System.out.println("Alle VPN-configuratietests geslaagd");
        System.exit(0);
    }

    static byte[] pkt(byte[] src, byte[] dst, String s) throws Exception {
        byte[] pl = s.getBytes("UTF-8"), p = new byte[20 + pl.length];
        p[0] = 0x45; p[2] = (byte) (p.length >> 8); p[3] = (byte) p.length; p[8] = 64; p[9] = (byte) 253;
        System.arraycopy(src, 0, p, 12, 4); System.arraycopy(dst, 0, p, 16, 4); System.arraycopy(pl, 0, p, 20, pl.length);
        return p;
    }

    static final class Q implements WgDevice.Tun {
        final LinkedBlockingQueue<byte[]> in = new LinkedBlockingQueue<>(), out = new LinkedBlockingQueue<>();
        public int read(byte[] buf) throws java.io.IOException {
            try { byte[] p = in.take(); if (p.length == 0) return -1; System.arraycopy(p, 0, buf, 0, p.length); return p.length; }
            catch (InterruptedException e) { throw new java.io.IOException("gestopt"); }
        }
        public void write(byte[] b, int off, int len) { out.add(java.util.Arrays.copyOfRange(b, off, off + len)); }
    }
}
