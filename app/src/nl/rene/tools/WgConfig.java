package nl.rene.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Een WireGuard-configuratie in het gewone wg-quick-formaat ([Interface] + [Peer]), puur Java (getest met WgConfigTest).
 * Onbekende regels (PostUp, Table, …) worden overgeslagen; fouten geven een Nederlandse melding.
 */
final class WgConfig {

    static final int DEFAULT_MTU = 1420;

    /** Adresblok, bijv. 10.0.0.2/32 of ::/0. */
    static final class Cidr {
        final byte[] addr;
        final int prefix;

        Cidr(byte[] addr, int prefix) { this.addr = addr; this.prefix = prefix; }

        boolean v6() { return addr.length == 16; }

        /** Het netwerkdeel (hostbits op 0), zoals Android het bij een route wil. */
        byte[] network() {
            byte[] n = addr.clone();
            for (int i = 0; i < n.length; i++) {
                int bits = Math.max(0, Math.min(8, prefix - i * 8));
                n[i] &= (byte) (0xff << (8 - bits));
            }
            return n;
        }

        boolean contains(byte[] ip, int off, int len) {
            if (len != addr.length) return false;
            int full = prefix / 8, rest = prefix % 8;
            for (int i = 0; i < full; i++) if (ip[off + i] != addr[i]) return false;
            if (rest == 0) return true;
            int m = 0xff << (8 - rest);
            return ((ip[off + full] ^ addr[full]) & m) == 0;
        }

        String host() { return ipString(addr); }

        @Override public String toString() { return ipString(addr) + "/" + prefix; }
    }

    static final class Peer {
        byte[] publicKey, presharedKey;
        final List<Cidr> allowed = new ArrayList<>();
        String endpointHost;
        int endpointPort;
        int keepalive;

        String endpoint() { return endpointHost == null ? "" : (endpointHost.contains(":") ? "[" + endpointHost + "]" : endpointHost) + ":" + endpointPort; }
    }

    byte[] privateKey;
    final List<Cidr> addresses = new ArrayList<>();
    final List<String> dns = new ArrayList<>(), dnsSearch = new ArrayList<>();
    final List<String> excludedApps = new ArrayList<>(), includedApps = new ArrayList<>();
    int mtu;
    int listenPort;
    final List<Peer> peers = new ArrayList<>();

    int mtuOrDefault() { return mtu > 0 ? mtu : DEFAULT_MTU; }

    /** Alle verkeer door de tunnel (0.0.0.0/0 of ::/0)? */
    boolean fullTunnel() {
        for (Peer p : peers) for (Cidr c : p.allowed) if (c.prefix == 0) return true;
        return false;
    }

    // ---------- inlezen ----------

    static WgConfig parse(String text) {
        if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("Lege configuratie");
        WgConfig c = new WgConfig();
        String section = "";
        Peer peer = null;
        int lineNo = 0;
        for (String raw : text.split("\r?\n")) {
            lineNo++;
            String line = raw;
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).trim().toLowerCase(Locale.ROOT);
                if (section.equals("peer")) { peer = new Peer(); c.peers.add(peer); }
                else if (!section.equals("interface")) throw new IllegalArgumentException("Onbekend onderdeel [" + section + "] op regel " + lineNo);
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) throw new IllegalArgumentException("Regel " + lineNo + " is niet te lezen: " + cut(line));
            String key = line.substring(0, eq).trim().toLowerCase(Locale.ROOT), val = line.substring(eq + 1).trim();
            try {
                if (section.equals("interface")) {
                    switch (key) {
                        case "privatekey": c.privateKey = key32(val, "PrivateKey"); break;
                        case "address": for (String s : list(val)) c.addresses.add(cidr(s, true)); break;
                        case "dns": for (String s : list(val)) { if (isIp(s)) c.dns.add(ipString(ip(s))); else if (s.matches("[A-Za-z0-9.-]{1,253}")) c.dnsSearch.add(s); else throw new IllegalArgumentException("DNS: " + cut(s)); } break;
                        case "mtu": c.mtu = Integer.parseInt(val); if (c.mtu < 576 || c.mtu > 9000) throw new IllegalArgumentException("MTU moet tussen 576 en 9000 liggen"); break;
                        case "listenport": c.listenPort = Integer.parseInt(val); if (c.listenPort < 0 || c.listenPort > 65535) throw new IllegalArgumentException("ListenPort klopt niet"); break;
                        case "excludedapplications": for (String s : list(val)) if (pkg(s)) c.excludedApps.add(s); break;
                        case "includedapplications": for (String s : list(val)) if (pkg(s)) c.includedApps.add(s); break;
                        default: break; // PostUp, Table, SaveConfig, … horen bij Linux; overslaan
                    }
                } else if (section.equals("peer") && peer != null) {
                    switch (key) {
                        case "publickey": peer.publicKey = key32(val, "PublicKey"); break;
                        case "presharedkey": peer.presharedKey = key32(val, "PresharedKey"); break;
                        case "allowedips": for (String s : list(val)) peer.allowed.add(cidr(s, false)); break;
                        case "endpoint": endpoint(peer, val); break;
                        case "persistentkeepalive": peer.keepalive = val.equalsIgnoreCase("off") ? 0 : Integer.parseInt(val); if (peer.keepalive < 0 || peer.keepalive > 65535) throw new IllegalArgumentException("PersistentKeepalive klopt niet"); break;
                        default: break;
                    }
                } else throw new IllegalArgumentException("Regel " + lineNo + " staat buiten [Interface] of [Peer]");
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Getal op regel " + lineNo + " klopt niet");
            }
        }
        if (c.privateKey == null) throw new IllegalArgumentException("PrivateKey ontbreekt bij [Interface]");
        if (c.addresses.isEmpty()) throw new IllegalArgumentException("Address ontbreekt bij [Interface]");
        if (c.peers.isEmpty()) throw new IllegalArgumentException("Er staat geen [Peer] in");
        if (!c.excludedApps.isEmpty() && !c.includedApps.isEmpty()) throw new IllegalArgumentException("Kies óf ExcludedApplications óf IncludedApplications, niet allebei");
        for (int i = 0; i < c.peers.size(); i++) {
            Peer p = c.peers.get(i);
            String w = c.peers.size() > 1 ? " bij peer " + (i + 1) : "";
            if (p.publicKey == null) throw new IllegalArgumentException("PublicKey ontbreekt" + w);
            if (java.util.Arrays.equals(p.publicKey, WgCrypto.publicKey(c.privateKey))) throw new IllegalArgumentException("De PublicKey van de peer is je eigen sleutel");
            for (int j = 0; j < i; j++) if (java.util.Arrays.equals(c.peers.get(j).publicKey, p.publicKey)) throw new IllegalArgumentException("Dezelfde peer staat er twee keer in");
        }
        boolean anyEndpoint = false;
        for (Peer p : c.peers) anyEndpoint |= p.endpointHost != null;
        if (!anyEndpoint) throw new IllegalArgumentException("Endpoint (adres van de server) ontbreekt");
        return c;
    }

    private static String cut(String s) { return s.length() > 40 ? s.substring(0, 40) + "…" : s; }

    private static List<String> list(String v) {
        List<String> out = new ArrayList<>();
        for (String s : v.split(",")) { s = s.trim(); if (!s.isEmpty()) out.add(s); }
        return out;
    }

    private static boolean pkg(String s) { return s.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"); }

    static byte[] key32(String b64, String what) {
        byte[] k = unb64(b64.trim());
        if (k == null || k.length != 32) throw new IllegalArgumentException(what + " is geen geldige sleutel");
        return k;
    }

    private static void endpoint(Peer p, String v) {
        String host; int port;
        if (v.startsWith("[")) {
            int e = v.indexOf("]:");
            if (e < 0) throw new IllegalArgumentException("Endpoint klopt niet: " + cut(v));
            host = v.substring(1, e); port = Integer.parseInt(v.substring(e + 2));
            if (!isIp(host) || !host.contains(":")) throw new IllegalArgumentException("Endpoint klopt niet: " + cut(v));
        } else {
            int e = v.lastIndexOf(':');
            if (e <= 0) throw new IllegalArgumentException("Endpoint mist de poort (bijv. vpn.voorbeeld.nl:51820)");
            host = v.substring(0, e); port = Integer.parseInt(v.substring(e + 1));
            if (!isIp(host) && !host.matches("[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?")) throw new IllegalArgumentException("Endpoint klopt niet: " + cut(v));
        }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Poort van Endpoint klopt niet");
        p.endpointHost = isIp(host) ? ipString(ip(host)) : host.toLowerCase(Locale.ROOT);
        p.endpointPort = port;
    }

    static Cidr cidr(String s, boolean hostDefault) {
        int sl = s.indexOf('/');
        String a = sl < 0 ? s : s.substring(0, sl);
        if (!isIp(a)) throw new IllegalArgumentException("Geen geldig IP-adres: " + cut(s));
        byte[] b = ip(a);
        int max = b.length * 8, pre = max;
        if (sl >= 0) {
            try { pre = Integer.parseInt(s.substring(sl + 1)); } catch (NumberFormatException e) { pre = -1; }
            if (pre < 0 || pre > max) throw new IllegalArgumentException("Ongeldig voorvoegsel: " + cut(s));
        } else if (!hostDefault) pre = max;
        return new Cidr(b, pre);
    }

    // ---------- IP-adressen (alleen letterlijke adressen; nooit DNS) ----------

    static boolean isIp(String s) {
        if (s == null || s.isEmpty()) return false;
        if (s.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            for (String p : s.split("\\.")) if (Integer.parseInt(p) > 255) return false;
            return true;
        }
        return s.contains(":") && s.matches("[0-9A-Fa-f:.]+") && s.length() <= 45 && ipv6(s) != null;
    }

    static byte[] ip(String s) {
        if (s.contains(":")) { byte[] b = ipv6(s); if (b == null) throw new IllegalArgumentException("Geen geldig IP-adres: " + cut(s)); return b; }
        String[] p = s.split("\\.");
        byte[] b = new byte[4];
        for (int i = 0; i < 4; i++) b[i] = (byte) Integer.parseInt(p[i]);
        return b;
    }

    /** Letterlijk IPv6-adres (ook met :: en een IPv4-staart), of null. */
    static byte[] ipv6(String s) {
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length() - 1);
        int pct = s.indexOf('%');
        if (pct >= 0) s = s.substring(0, pct);
        byte[] out = new byte[16];
        String head = s, tail = null;
        int dc = s.indexOf("::");
        if (dc >= 0) { if (s.indexOf("::", dc + 1) >= 0) return null; head = s.substring(0, dc); tail = s.substring(dc + 2); }
        List<Integer> h = groups(head), t = tail == null ? new ArrayList<>() : groups(tail);
        if (h == null || t == null) return null;
        int n = h.size() + t.size();
        if (tail == null ? n != 8 : n > 7) return null;
        for (int i = 0; i < h.size(); i++) { out[2 * i] = (byte) (h.get(i) >> 8); out[2 * i + 1] = (byte) (int) h.get(i); }
        for (int i = 0; i < t.size(); i++) { int k = 8 - t.size() + i; out[2 * k] = (byte) (t.get(i) >> 8); out[2 * k + 1] = (byte) (int) t.get(i); }
        return out;
    }

    private static List<Integer> groups(String s) {
        List<Integer> g = new ArrayList<>();
        if (s.isEmpty()) return g;
        String[] parts = s.split(":", -1);
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (i == parts.length - 1 && p.contains(".")) { // IPv4 aan het eind
                if (!p.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return null;
                String[] q = p.split("\\.");
                int[] v = new int[4];
                for (int j = 0; j < 4; j++) { v[j] = Integer.parseInt(q[j]); if (v[j] > 255) return null; }
                g.add(v[0] << 8 | v[1]); g.add(v[2] << 8 | v[3]);
                continue;
            }
            if (!p.matches("[0-9A-Fa-f]{1,4}")) return null;
            g.add(Integer.parseInt(p, 16));
        }
        return g;
    }

    // ---------- base64 (eigen versie: java.util.Base64 bestaat pas vanaf Android 8) ----------

    private static final String B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    static String b64(byte[] k) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < k.length; i += 3) {
            int v = (k[i] & 0xff) << 16 | (i + 1 < k.length ? (k[i + 1] & 0xff) << 8 : 0) | (i + 2 < k.length ? k[i + 2] & 0xff : 0);
            b.append(B64.charAt(v >> 18 & 63)).append(B64.charAt(v >> 12 & 63))
                    .append(i + 1 < k.length ? B64.charAt(v >> 6 & 63) : '=').append(i + 2 < k.length ? B64.charAt(v & 63) : '=');
        }
        return b.toString();
    }

    /** Streng: alleen standaard-base64 met juiste opvulling; anders null. */
    static byte[] unb64(String s) {
        if (s == null || s.length() % 4 != 0) return null;
        int pad = s.endsWith("==") ? 2 : s.endsWith("=") ? 1 : 0;
        byte[] out = new byte[s.length() / 4 * 3 - pad];
        int o = 0;
        for (int i = 0; i < s.length(); i += 4) {
            int v = 0;
            for (int j = 0; j < 4; j++) {
                char ch = s.charAt(i + j);
                int d = ch == '=' && i + j >= s.length() - pad ? 0 : B64.indexOf(ch);
                if (d < 0) return null;
                v = v << 6 | d;
            }
            if (o < out.length) out[o++] = (byte) (v >> 16);
            if (o < out.length) out[o++] = (byte) (v >> 8);
            if (o < out.length) out[o++] = (byte) v;
        }
        return out;
    }

    static String ipString(byte[] b) {
        if (b.length == 4) return (b[0] & 0xff) + "." + (b[1] & 0xff) + "." + (b[2] & 0xff) + "." + (b[3] & 0xff);
        // kortste vorm (langste reeks nullen wordt ::)
        int bestS = -1, bestL = 0;
        for (int i = 0; i < 8; ) {
            if (b[2 * i] == 0 && b[2 * i + 1] == 0) { int j = i; while (j < 8 && b[2 * j] == 0 && b[2 * j + 1] == 0) j++; if (j - i > bestL && j - i > 1) { bestS = i; bestL = j - i; } i = j; }
            else i++;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i == bestS) { sb.append("::"); i += bestL - 1; continue; }
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ':') sb.append(':');
            sb.append(Integer.toHexString(((b[2 * i] & 0xff) << 8) | (b[2 * i + 1] & 0xff)));
        }
        return sb.toString();
    }

    // ---------- wegschrijven ----------

    String toText() {
        StringBuilder b = new StringBuilder("[Interface]\n");
        b.append("PrivateKey = ").append(b64(privateKey)).append('\n');
        b.append("Address = ").append(join(addresses)).append('\n');
        if (!dns.isEmpty() || !dnsSearch.isEmpty()) { List<String> d = new ArrayList<>(dns); d.addAll(dnsSearch); b.append("DNS = ").append(String.join(", ", d)).append('\n'); }
        if (mtu > 0) b.append("MTU = ").append(mtu).append('\n');
        if (listenPort > 0) b.append("ListenPort = ").append(listenPort).append('\n');
        if (!excludedApps.isEmpty()) b.append("ExcludedApplications = ").append(String.join(", ", excludedApps)).append('\n');
        if (!includedApps.isEmpty()) b.append("IncludedApplications = ").append(String.join(", ", includedApps)).append('\n');
        for (Peer p : peers) {
            b.append("\n[Peer]\nPublicKey = ").append(b64(p.publicKey)).append('\n');
            if (p.presharedKey != null) b.append("PresharedKey = ").append(b64(p.presharedKey)).append('\n');
            if (!p.allowed.isEmpty()) b.append("AllowedIPs = ").append(join(p.allowed)).append('\n');
            if (p.endpointHost != null) b.append("Endpoint = ").append(p.endpoint()).append('\n');
            if (p.keepalive > 0) b.append("PersistentKeepalive = ").append(p.keepalive).append('\n');
        }
        return b.toString();
    }

    private static String join(List<Cidr> l) {
        StringBuilder b = new StringBuilder();
        for (Cidr c : l) { if (b.length() > 0) b.append(", "); b.append(c); }
        return b.toString();
    }
}
