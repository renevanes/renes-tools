package nl.rene.tools;

/** OpenVPN: .ovpn inlezen (inline blokken, fouten), PEM-sleutels en de instellingen van de server (PUSH_REPLY). */
public class OvpnConfigTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }
    static String err(String conf) { try { OvpnConfig.parse(conf); return ""; } catch (IllegalArgumentException e) { return e.getMessage(); } }

    // wegwerp-testsleutels (alleen voor deze test gemaakt)
    static final String CA = "-----BEGIN CERTIFICATE-----\nMIIBeTCCAR+gAwIBAgIUIgkA3nc02T+xaqW2R+rJoKhrWDwwCgYIKoZIzj0EAwIw\nEjEQMA4GA1UEAwwHVGVzdCBDQTAeFw0yNjEwMTExODEyMjJaFw0yNjExMTAxODEy\nMjJaMBIxEDAOBgNVBAMMB1Rlc3QgQ0EwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNC\nAAS5tl9o3wq+t3RcT0UZyytRsQITfqYmjb6BPYcBSd9k7HepwJKs7+htM21FNwXQ\n0XlDi4pgCDaSro16Bh+UE6XZo1MwUTAdBgNVHQ4EFgQUmhv8HDGYk2u7bAyfTE58\n7+oyJVUwHwYDVR0jBBgwFoAUmhv8HDGYk2u7bAyfTE587+oyJVUwDwYDVR0TAQH/\nBAUwAwEB/zAKBggqhkjOPQQDAgNIADBFAiEAmvy2TgYsyM5wFzLAGMknt7NfvB1e\nArL8vInjcdhZZF8CICAOf+BGIGq5SVvIthP82PgEpzgmVXWEtjx1f5V502jK\n-----END CERTIFICATE-----\n";
    static final String CERT = "-----BEGIN CERTIFICATE-----\nMIIBlzCCATygAwIBAgIUChuswSiv+SCzujMf9Op3eOJ3KM4wCgYIKoZIzj0EAwIw\nEjEQMA4GA1UEAwwHVGVzdCBDQTAeFw0yNjEwMTExODEyMjNaFw0yNjExMTAxODEy\nMjNaMBMxETAPBgNVBAMMCHRlbGVmb29uMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcD\nQgAEFasFaqCgzFLvj7SfIGnd8zhnGkagO4fSE9oZiXekiqt2srXh/cl758hn076s\n7rlg/vVzmy+6wD70dr+c52kw16NvMG0wCQYDVR0TBAIwADALBgNVHQ8EBAMCA4gw\nEwYDVR0lBAwwCgYIKwYBBQUHAwIwHQYDVR0OBBYEFIepTZYKAOg3CTsYfySbmyYp\nVLkNMB8GA1UdIwQYMBaAFJob/BwxmJNru2wMn0xOfO/qMiVVMAoGCCqGSM49BAMC\nA0kAMEYCIQCGh8ZhQVTY1ZYvUCQp/YJdtcLIJ0KTxeIITf6PhT98HgIhALBHAS/K\nS5xtXGu0unEbEClR9BNiN9D/zo1XzyUtXalN\n-----END CERTIFICATE-----\n";
    static final String KEY8 = "-----BEGIN PRIVATE KEY-----\nMIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgMoSXsJx/ZBu4AyqR\nSDqRC5Hx9A3P6KNZ8YepdzSQrKihRANCAAQVqwVqoKDMUu+PtJ8gad3zOGcaRqA7\nh9IT2hmJd6SKq3ayteH9yXvnyGfTvqzuuWD+9XObL7rAPvR2v5znaTDX\n-----END PRIVATE KEY-----\n";
    static final String KEYSEC1 = "-----BEGIN EC PRIVATE KEY-----\nMHcCAQEEIDKEl7Ccf2QbuAMqkUg6kQuR8fQNz+ijWfGHqXc0kKyooAoGCCqGSM49\nAwEHoUQDQgAEFasFaqCgzFLvj7SfIGnd8zhnGkagO4fSE9oZiXekiqt2srXh/cl7\n58hn076s7rlg/vVzmy+6wD70dr+c52kw1w==\n-----END EC PRIVATE KEY-----\n";
    static final String TA = "#\n# 2048 bit OpenVPN static key\n#\n-----BEGIN OpenVPN Static key V1-----\n6e90d02093facb308899b92d97908f25\nc8a55dd04273ab9d6b70d180ed38eab5\n4c6b97e4bef177659de7edb9fbc8b6f6\n27dd3b2a7da578a0b9529fc2d75b95d9\n474b2e41ffc1d250d3c0f1efddd9c335\n08a0e79b4502d89afc1265e3a118ef3e\n4bc0190ffe1236bb670e411272f0f3b7\n002aee86cdf181c665a8118ecbc382e5\n1098733c298902b9e2335deff3feb6ed\n6d7c143e3f7d6b710432d4dee78e6677\n414b0759bc68838b3d44b8d959c15d9a\ne82e3cab6a646882811a0168dcc99aef\n1df519586aab0036a89b1287718dd157\nd825967cba66b1d297029a24d97ea0cf\n79428866787b6f1abc4ba8a2a8a7a47c\n08a8e96f90846c993d262ae015545b3f\n-----END OpenVPN Static key V1-----\n";

    public static void main(String[] a) throws Exception {
        String base = "client\ndev tun\nproto udp\nremote vpn.voorbeeld.nl 1194\nremote 203.0.113.9 443 tcp\nremote-random\nremote-cert-tls server\nverify-x509-name vpn.test name\ndata-ciphers AES-256-GCM:CHACHA20-POLY1305\nauth SHA256\n";
        OvpnConfig c = OvpnConfig.parse(base + "<ca>\n" + CA + "</ca>\n<cert>\n" + CERT + "</cert>\n<key>\n" + KEY8 + "</key>\nkey-direction 1\n<tls-auth>\n" + TA + "</tls-auth>\n");
        check(c.remotes.size() == 2 && !c.remotes.get(0).tcp && c.remotes.get(1).tcp && c.remotes.get(1).port == 443, "twee servers, UDP en TCP 443");
        check(c.tlsAuth != null && c.tlsAuth.length == 256 && c.keyDirection == 1 && c.remoteCertTls && "vpn.test".equals(c.verifyName) && c.verifyType.equals("name"), "tls-auth, key-direction, controles");
        check(c.dataCiphers.size() == 2 && c.auth.equals("SHA256") && c.remoteRandom, "versleuteling en auth");
        check(OvpnConfig.privateKey(KEYSEC1) != null && OvpnConfig.privateKey(KEY8) != null, "EC-sleutel als PKCS#8 en SEC1");
        OvpnConfig u = OvpnConfig.parse("client\nremote x.nl 1194\nauth-user-pass\n<ca>\n" + CA + "</ca>\n");
        check(u.authUserPass && u.cert == null && u.remotes.get(0).port == 1194, "alleen gebruikersnaam/wachtwoord");
        // fouten
        check(err("remote x 1\n<ca>\n" + CA + "</ca>\n").contains("geen OpenVPN-client"), "zonder 'client'");
        check(err("client\ndev tap\nremote x 1\n").contains("dev tun"), "dev tap kan niet");
        check(err("client\nremote x 1\nca ca.crt\n").contains("los bestand"), "ca als los bestand");
        check(err("client\nremote x 1\nauth-user-pass\n").contains("CA-certificaat"), "zonder <ca>");
        check(err("client\nauth-user-pass\n<ca>\n" + CA + "</ca>\n").contains("remote"), "zonder remote");
        check(err("client\nremote x 1\n<ca>\n" + CA + "</ca>\n").contains("herkennen"), "zonder certificaat en zonder wachtwoord");
        check(err("client\nremote x 1\nauth-user-pass\n<ca>\n" + CA + "</ca>\n<key>\n" + KEY8 + "</key>\n").contains("<cert> ontbreekt"), "sleutel zonder certificaat");
        check(err("client\nremote x 1\nauth-user-pass\n<ca>\n" + CA + "</ca>\n<tls-auth>\nonzin\n</tls-auth>\n").contains("tls-auth"), "kapotte tls-auth");
        check(err("client\nremote x 1\nauth-user-pass\n<ca>\n" + CA + "\n").contains("afgesloten"), "<ca> niet afgesloten");
        check(err("client\nremote x 1\nauth-user-pass\n<ca>\n" + CA + "</ca>\n<key>\n-----BEGIN ENCRYPTED PRIVATE KEY-----\nAAAA\n-----END ENCRYPTED PRIVATE KEY-----\n</key>\n<cert>\n" + CERT + "</cert>\n").contains("wachtwoord"), "sleutel met wachtwoord");
        check(OvpnConfig.tokens("remote \"mijn server\" 1194 # commentaar").toString().equals("[remote, mijn server, 1194]"), "woorden met aanhalingstekens en commentaar");
        // PUSH_REPLY
        OvpnDevice.Push p = OvpnDevice.Push.parse("redirect-gateway def1 bypass-dhcp,dhcp-option DNS 10.8.0.1,dhcp-option DOMAIN thuis.lan,route 192.168.10.0 255.255.255.0,route 10.10.0.0 255.255.0.0 net_gateway,route-gateway 10.8.0.1,topology subnet,ping 10,ping-restart 60,ifconfig 10.8.0.6 255.255.255.0,ifconfig-ipv6 fd00::1000/64 fd00::1,route-ipv6 2000::/3,peer-id 3,cipher AES-256-GCM,dns server 0 address 9.9.9.9", false);
        check(p.addresses.toString().equals("[10.8.0.6/24, fd00::1000/64]"), "adressen: " + p.addresses);
        check(p.routes.toString().equals("[192.168.10.0/24, 2000::/3, 0.0.0.0/0]") && p.excluded.toString().equals("[10.10.0.0/16]"), "routes: " + p.routes + " uitgezonderd " + p.excluded);
        check(p.dns.toString().equals("[10.8.0.1, 9.9.9.9]") && p.domains.toString().equals("[thuis.lan]") && p.peerId == 3 && p.ping == 10 && p.pingRestart == 60 && "AES-256-GCM".equals(p.cipher), "DNS, peer-id, ping, cipher");
        OvpnDevice.Push n30 = OvpnDevice.Push.parse("ifconfig 10.8.0.6 10.8.0.5,route 10.8.0.1", false);
        check(n30.addresses.toString().equals("[10.8.0.6/32]") && n30.routes.toString().equals("[10.8.0.1/32, 10.8.0.5/32]"), "net30: " + n30.addresses + " " + n30.routes);
        OvpnDevice.Push np = OvpnDevice.Push.parse("redirect-gateway def1,route 1.2.3.0 255.255.255.0,ifconfig 10.8.0.6 255.255.255.0,topology subnet", true);
        check(np.routes.isEmpty() && np.addresses.size() == 1, "route-nopull: geen routes");
        // PRF: deterministisch en afhankelijk van alle invoer
        byte[] m1 = OvpnCrypto.prf(new byte[48], "OpenVPN master secret", 48, new byte[32], new byte[32]);
        byte[] m2 = OvpnCrypto.prf(new byte[48], "OpenVPN master secret", 48, new byte[32], new byte[]{1});
        check(m1.length == 48 && !java.util.Arrays.equals(m1, m2), "PRF");
        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
        System.out.println("Alle OpenVPN-configuratietests geslaagd");
    }
}
