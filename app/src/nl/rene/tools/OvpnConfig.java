package nl.rene.tools;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Een OpenVPN-clientconfiguratie (.ovpn), puur Java (getest met OvpnConfigTest). Alleen wat een telefoon-client nodig
 * heeft: certificaten en sleutels moeten in het bestand zelf staan (&lt;ca&gt;…&lt;/ca&gt;), niet als los bestand.
 */
final class OvpnConfig {

    static final class Remote {
        final String host; final int port; final boolean tcp;
        Remote(String host, int port, boolean tcp) { this.host = host; this.port = port; this.tcp = tcp; }
        @Override public String toString() { return host + ":" + port + (tcp ? " (TCP)" : " (UDP)"); }
    }

    final List<Remote> remotes = new ArrayList<>();
    boolean remoteRandom;
    String ca, cert, key;               // PEM
    byte[] tlsAuth, tlsCrypt;           // 256 bytes statische sleutel
    int keyDirection = -1;              // -1 = beide kanten dezelfde
    boolean authUserPass;
    String cipher = "";                 // oude 'cipher' (bijv. AES-256-CBC)
    final List<String> dataCiphers = new ArrayList<>();
    String auth = "SHA1";               // HMAC voor tls-auth en het oude datakanaal
    boolean remoteCertTls, nsCertType;
    String verifyName, verifyType = "subject";
    String compress;                    // null, "lzo" (comp-lzo), "stub", "stub-v2", "lz4", "lz4-v2"
    int renegSec = 3600;
    int pingSec, pingRestart;           // 0 = niet ingesteld (de server stuurt ze meestal)
    int tunMtu;
    boolean routeNoPull;
    int connectRetry = 5;
    final List<String> warnings = new ArrayList<>();

    // ---------- inlezen ----------

    static OvpnConfig parse(String text) {
        if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("Lege configuratie");
        OvpnConfig c = new OvpnConfig();
        String defProto = "udp";
        int defPort = 1194;
        List<String[]> rawRemotes = new ArrayList<>();
        String[] lines = text.replace("\r", "").split("\n");
        boolean client = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
            if (line.startsWith("<") && line.endsWith(">") && !line.startsWith("</")) { // inline blok
                String tag = line.substring(1, line.length() - 1).trim().toLowerCase(Locale.ROOT);
                StringBuilder b = new StringBuilder();
                int j = i + 1;
                for (; j < lines.length; j++) { if (lines[j].trim().equalsIgnoreCase("</" + tag + ">")) break; b.append(lines[j]).append('\n'); }
                if (j >= lines.length) throw new IllegalArgumentException("<" + tag + "> wordt niet afgesloten");
                i = j;
                String v = b.toString();
                switch (tag) {
                    case "ca": c.ca = v; break;
                    case "cert": c.cert = v; break;
                    case "key": c.key = v; break;
                    case "tls-auth": c.tlsAuth = staticKey(v, "tls-auth"); break;
                    case "tls-crypt": c.tlsCrypt = staticKey(v, "tls-crypt"); break;
                    case "tls-crypt-v2": throw new IllegalArgumentException("tls-crypt-v2 wordt (nog) niet ondersteund");
                    case "pkcs12": throw new IllegalArgumentException("Een pkcs12-blok wordt niet ondersteund; gebruik <ca>, <cert> en <key>");
                    case "connection": throw new IllegalArgumentException("<connection>-blokken worden niet ondersteund; gebruik remote-regels");
                    default: c.warnings.add("<" + tag + "> overgeslagen");
                }
                continue;
            }
            List<String> t = tokens(line);
            if (t.isEmpty()) continue;
            String k = t.get(0).toLowerCase(Locale.ROOT);
            String a1 = t.size() > 1 ? t.get(1) : "";
            switch (k) {
                case "client": client = true; break;
                case "tls-client": client = true; break;
                case "dev":
                    if (!a1.toLowerCase(Locale.ROOT).startsWith("tun")) throw new IllegalArgumentException("Alleen 'dev tun' kan op Android (dit is " + a1 + ")");
                    break;
                case "dev-type": if (!a1.equalsIgnoreCase("tun")) throw new IllegalArgumentException("Alleen 'dev tun' kan op Android"); break;
                case "proto": defProto = protoOf(a1); break;
                case "port": defPort = port(a1); break;
                case "remote": rawRemotes.add(new String[]{a1, t.size() > 2 ? t.get(2) : null, t.size() > 3 ? t.get(3) : null}); break;
                case "remote-random": c.remoteRandom = true; break;
                case "key-direction": c.keyDirection = a1.equals("1") ? 1 : a1.equals("0") ? 0 : -1; break;
                case "tls-auth":
                    if (!a1.equals("[inline]") && !a1.isEmpty()) throw new IllegalArgumentException("tls-auth verwijst naar een los bestand; zet de sleutel in het bestand (<tls-auth>)");
                    if (t.size() > 2) c.keyDirection = t.get(2).equals("1") ? 1 : 0;
                    break;
                case "ca": case "cert": case "key": case "tls-crypt":
                    if (!a1.equals("[inline]")) throw new IllegalArgumentException(k + " verwijst naar een los bestand (" + a1 + "); gebruik een .ovpn waarin alles staat");
                    break;
                case "pkcs12": throw new IllegalArgumentException("pkcs12 wordt niet ondersteund; gebruik <ca>, <cert> en <key>");
                case "auth-user-pass":
                    if (t.size() > 1 && !a1.isEmpty()) c.warnings.add("auth-user-pass-bestand genegeerd: vul gebruikersnaam en wachtwoord in de app in");
                    c.authUserPass = true; break;
                case "cipher": c.cipher = a1.toUpperCase(Locale.ROOT); break;
                case "data-ciphers": case "ncp-ciphers":
                    c.dataCiphers.clear();
                    for (String s : a1.split(":")) if (!s.isEmpty()) c.dataCiphers.add(s.toUpperCase(Locale.ROOT));
                    break;
                case "auth": c.auth = a1.toUpperCase(Locale.ROOT).replace("-", ""); break;
                case "remote-cert-tls": c.remoteCertTls = a1.equalsIgnoreCase("server"); break;
                case "ns-cert-type": c.nsCertType = a1.equalsIgnoreCase("server"); break;
                case "data-ciphers-fallback": c.cipher = a1.toUpperCase(Locale.ROOT); break;
                case "verify-x509-name": c.verifyName = a1; if (t.size() > 2) c.verifyType = t.get(2).toLowerCase(Locale.ROOT); break;
                case "comp-lzo": c.compress = "lzo"; if (!"no".equalsIgnoreCase(a1)) c.warnings.add("comp-lzo: deze app comprimeert niet; vraagt de server echt om compressie, dan werkt het niet"); break;
                case "compress": c.compress = a1.isEmpty() ? "stub" : a1.toLowerCase(Locale.ROOT); break;
                case "reneg-sec": c.renegSec = Math.max(0, intOr(a1, 3600)); break;
                case "ping": c.pingSec = intOr(a1, 0); break;
                case "ping-restart": c.pingRestart = intOr(a1, 0); break;
                case "keepalive": c.pingSec = intOr(a1, 0); c.pingRestart = t.size() > 2 ? intOr(t.get(2), 0) : 0; break;
                case "tun-mtu": c.tunMtu = intOr(a1, 0); break;
                case "route-nopull": c.routeNoPull = true; break;
                case "connect-retry": c.connectRetry = Math.max(1, intOr(a1, 5)); break;
                case "fragment": throw new IllegalArgumentException("'fragment' wordt niet ondersteund");
                case "secret": throw new IllegalArgumentException("Statische-sleutelmodus ('secret') wordt niet ondersteund; gebruik een TLS-configuratie");
                case "http-proxy": case "socks-proxy": throw new IllegalArgumentException("Een proxy wordt niet ondersteund");
                default: break; // nobind, persist-key, verb, resolv-retry, mute, … horen bij de pc-versie
            }
        }
        if (!client) throw new IllegalArgumentException("Dit is geen OpenVPN-clientconfiguratie ('client' ontbreekt)");
        if (rawRemotes.isEmpty()) throw new IllegalArgumentException("Er staat geen 'remote' (server) in");
        for (String[] r : rawRemotes) {
            String host = r[0];
            if (host.isEmpty() || !host.matches("[A-Za-z0-9.:-]+")) throw new IllegalArgumentException("Ongeldige server: " + host);
            int p = r[1] == null ? defPort : port(r[1]);
            String proto = r[2] == null ? defProto : protoOf(r[2]);
            c.remotes.add(new Remote(host.toLowerCase(Locale.ROOT), p, proto.equals("tcp")));
        }
        if (c.ca == null) throw new IllegalArgumentException("Het CA-certificaat (<ca>) ontbreekt; zonder kan de server niet gecontroleerd worden");
        if ((c.cert == null) != (c.key == null)) throw new IllegalArgumentException(c.cert == null ? "<cert> ontbreekt bij <key>" : "<key> ontbreekt bij <cert>");
        if (c.cert == null && !c.authUserPass) throw new IllegalArgumentException("Geen eigen certificaat en geen auth-user-pass: zo kan de server je niet herkennen");
        if (c.tlsAuth != null && c.tlsCrypt != null) throw new IllegalArgumentException("tls-auth en tls-crypt samen kan niet");
        if (!c.remoteCertTls && !c.nsCertType && c.verifyName == null)
            c.warnings.add("geen remote-cert-tls: een ander apparaat met een certificaat van dezelfde CA zou zich als server kunnen voordoen");
        if (!digestOk(c.auth)) throw new IllegalArgumentException("auth " + c.auth + " wordt niet ondersteund (SHA1, SHA256, SHA384, SHA512)");
        // certificaten en sleutel alvast controleren
        try { certs(c.ca); } catch (Exception e) { throw new IllegalArgumentException("<ca> is niet te lezen"); }
        if (c.cert != null) {
            try { certs(c.cert); } catch (Exception e) { throw new IllegalArgumentException("<cert> is niet te lezen"); }
            privateKey(c.key);
        }
        return c;
    }

    static boolean digestOk(String d) { return d.equals("SHA1") || d.equals("SHA256") || d.equals("SHA384") || d.equals("SHA512"); }

    private static int intOr(String s, int d) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return d; } }

    private static int port(String s) {
        int p = intOr(s, -1);
        if (p < 1 || p > 65535) throw new IllegalArgumentException("Ongeldige poort: " + s);
        return p;
    }

    private static String protoOf(String s) {
        String p = s.toLowerCase(Locale.ROOT);
        if (p.startsWith("tcp")) return "tcp";
        if (p.startsWith("udp")) return "udp";
        throw new IllegalArgumentException("Onbekend protocol: " + s);
    }

    /** Woorden van een regel (aanhalingstekens houden spaties bij elkaar). */
    static List<String> tokens(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        boolean any = false;
        char q = 0; // het aanhalingsteken waarmee de waarde begon
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (q == 0 && (ch == '"' || ch == '\'' && !any)) { q = ch; any = true; continue; }
            if (q != 0 && ch == q) { q = 0; continue; }
            if (q == 0 && (ch == ' ' || ch == '\t')) { if (any) { out.add(b.toString()); b.setLength(0); any = false; } continue; }
            if (q == 0 && (ch == '#' || ch == ';') && !any) break;
            b.append(ch); any = true;
        }
        if (any) out.add(b.toString());
        return out;
    }

    /** "-----BEGIN OpenVPN Static key V1-----" met 256 bytes hex. */
    static byte[] staticKey(String v, String what) {
        StringBuilder hex = new StringBuilder();
        boolean in = false;
        for (String l : v.split("\n")) {
            l = l.trim();
            if (l.startsWith("-----BEGIN")) { in = true; continue; }
            if (l.startsWith("-----END")) break;
            if (in && !l.startsWith("#")) hex.append(l);
        }
        String h = hex.toString();
        if (h.length() != 512 || !h.matches("[0-9a-fA-F]+")) throw new IllegalArgumentException("De " + what + "-sleutel is niet te lezen");
        byte[] k = new byte[256];
        for (int i = 0; i < 256; i++) k[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return k;
    }

    // ---------- PEM ----------

    static List<X509Certificate> certs(String pem) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<X509Certificate> out = new ArrayList<>();
        for (byte[] der : pemBlocks(pem, "CERTIFICATE")) out.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der)));
        if (out.isEmpty()) throw new Exception("geen certificaat");
        return out;
    }

    static List<byte[]> pemBlocks(String pem, String type) {
        List<byte[]> out = new ArrayList<>();
        String begin = "-----BEGIN " + type + "-----", end = "-----END " + type + "-----";
        int i = 0;
        while ((i = pem.indexOf(begin, i)) >= 0) {
            int e = pem.indexOf(end, i);
            if (e < 0) break;
            String body = pem.substring(i + begin.length(), e).replaceAll("\\s", "");
            byte[] der = WgConfig.unb64(body);
            if (der != null) out.add(der);
            i = e + end.length();
        }
        return out;
    }

    /** Privésleutel uit PEM: PKCS#8, PKCS#1 (RSA) of SEC1 (EC). Met wachtwoord beveiligde sleutels niet. */
    static PrivateKey privateKey(String pem) {
        if (pem.contains("ENCRYPTED")) throw new IllegalArgumentException("De sleutel is met een wachtwoord beveiligd; dat wordt niet ondersteund");
        try {
            List<byte[]> p8 = pemBlocks(pem, "PRIVATE KEY");
            if (!p8.isEmpty()) {
                for (String alg : new String[]{"RSA", "EC"}) try { return KeyFactory.getInstance(alg).generatePrivate(new PKCS8EncodedKeySpec(p8.get(0))); } catch (Exception ignored) { }
                throw new Exception();
            }
            List<byte[]> rsa = pemBlocks(pem, "RSA PRIVATE KEY");
            if (!rsa.isEmpty()) return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8(RSA_OID, null, rsa.get(0))));
            List<byte[]> ec = pemBlocks(pem, "EC PRIVATE KEY");
            if (!ec.isEmpty()) return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(pkcs8(EC_OID, ecCurve(ec.get(0)), ec.get(0))));
        } catch (Exception e) { /* hieronder */ }
        throw new IllegalArgumentException("<key> is niet te lezen (RSA- of EC-sleutel verwacht)");
    }

    private static final byte[] RSA_OID = {0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01};
    private static final byte[] EC_OID = {0x06, 0x07, 0x2a, (byte) 0x86, 0x48, (byte) 0xce, 0x3d, 0x02, 0x01};

    /** PrivateKeyInfo ::= SEQ { INTEGER 0, SEQ { oid, params }, OCTET STRING key } */
    static byte[] pkcs8(byte[] algOid, byte[] params, byte[] key) {
        ByteArrayOutputStream alg = new ByteArrayOutputStream();
        alg.write(algOid, 0, algOid.length);
        if (params != null) alg.write(params, 0, params.length); else { alg.write(0x05); alg.write(0x00); }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0x02); body.write(0x01); body.write(0x00);
        byte[] a = der(0x30, alg.toByteArray());
        body.write(a, 0, a.length);
        byte[] o = der(0x04, key);
        body.write(o, 0, o.length);
        return der(0x30, body.toByteArray());
    }

    static byte[] der(int tag, byte[] content) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(tag);
        int n = content.length;
        if (n < 128) b.write(n);
        else if (n < 256) { b.write(0x81); b.write(n); }
        else if (n < 65536) { b.write(0x82); b.write(n >> 8); b.write(n); }
        else { b.write(0x83); b.write(n >> 16); b.write(n >> 8); b.write(n); }
        b.write(content, 0, n);
        return b.toByteArray();
    }

    /** De curve-OID uit een SEC1 ECPrivateKey ([0] parameters). */
    static byte[] ecCurve(byte[] sec1) throws Exception {
        int[] p = {0};
        Der top = Der.read(sec1, p);
        int[] q = {top.start};
        while (q[0] < top.start + top.len) {
            Der d = Der.read(sec1, q);
            if (d.tag == 0xa0) { int[] r = {d.start}; Der oid = Der.read(sec1, r); return java.util.Arrays.copyOfRange(sec1, oid.hdrStart, oid.start + oid.len); }
            q[0] = d.start + d.len;
        }
        throw new Exception("geen curve");
    }

    /** Minimale DER-lezer. */
    static final class Der {
        int tag, hdrStart, start, len;
        static Der read(byte[] b, int[] pos) throws Exception {
            Der d = new Der();
            d.hdrStart = pos[0];
            d.tag = b[pos[0]++] & 0xff;
            int l = b[pos[0]++] & 0xff;
            if (l >= 0x80) { int n = l & 0x7f; if (n > 3) throw new Exception("te lang"); l = 0; for (int i = 0; i < n; i++) l = (l << 8) | (b[pos[0]++] & 0xff); }
            d.start = pos[0]; d.len = l;
            if (d.start + l > b.length) throw new Exception("kapot");
            pos[0] = d.start + l;
            return d;
        }
    }

    // ---------- samenvatting ----------

    String summary() {
        StringBuilder b = new StringBuilder();
        Remote r = remotes.get(0);
        b.append(r.host).append(':').append(r.port).append(r.tcp ? " TCP" : " UDP");
        if (remotes.size() > 1) b.append(" (+").append(remotes.size() - 1).append(" reserve)");
        return b.toString();
    }

    boolean hasTcp() { for (Remote r : remotes) if (r.tcp) return true; return false; }
}
