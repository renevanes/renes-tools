package nl.rene.tools;

import java.nio.charset.StandardCharsets;
import java.util.Random;

/** VPN (WireGuard): de cryptografie tegen RFC-testvectoren en tegen de ingebouwde Java-versies. */
public class WgCryptoTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }
    static byte[] hx(String s) { byte[] b = new byte[s.length() / 2]; for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16); return b; }
    static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }
    static byte[] seq(int from, int n) { byte[] b = new byte[n]; for (int i = 0; i < n; i++) b[i] = (byte) (from + i); return b; }

    public static void main(String[] a) throws Exception {
        // BLAKE2s
        check(hex(WgCrypto.hash("abc".getBytes(StandardCharsets.US_ASCII))).equals("508c5e8c327c14e2e1a72ba34eeb452f37458b209ed63a294d999b4c86675982"), "BLAKE2s(\"abc\")");
        check(hex(new WgCrypto.Blake2s(32, seq(0, 32)).digest()).equals("48a8997da407876b3d79c0d92325ad3b89cbb754d86ab71aee047ad345fd2c49"), "BLAKE2s met sleutel, lege invoer");
        check(hex(new WgCrypto.Blake2s(32, seq(0, 32)).update(new byte[]{0}).digest()).equals("40d15fee7c328830166ac3f918650f807e7e01e177258cdc0a39b11f598066f1"), "BLAKE2s met sleutel, invoer 00");
        byte[] big = seq(0, 200);
        WgCrypto.Blake2s inParts = new WgCrypto.Blake2s(32, null); inParts.update(big, 0, 64); inParts.update(big, 64, 1); inParts.update(big, 65, 135);
        check(hex(inParts.digest()).equals(hex(WgCrypto.hash(big))), "BLAKE2s in stukjes = in één keer");
        // X25519 (RFC 7748 §6.1)
        byte[] ap = hx("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"), bp = hx("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb");
        check(hex(WgCrypto.publicKey(ap)).equals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"), "X25519 publieke sleutel Alice");
        check(hex(WgCrypto.publicKey(bp)).equals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"), "X25519 publieke sleutel Bob");
        check(hex(WgCrypto.dh(ap, WgCrypto.publicKey(bp))).equals("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"), "X25519 gedeeld geheim");
        check(WgCrypto.dh(ap, new byte[32]) == null, "X25519 met nulpunt: geweigerd");
        // X25519 tegen de ingebouwde Java-versie
        boolean xok = true;
        for (int i = 0; i < 20 && xok; i++) {
            byte[] p1 = WgCrypto.newPrivateKey(), p2 = WgCrypto.newPrivateKey();
            xok = hex(WgCrypto.dh(p1, WgCrypto.publicKey(p2))).equals(hex(WgCrypto.dh(p2, WgCrypto.publicKey(p1)))) && hex(WgCrypto.publicKey(p1)).equals(hex(jdkPub(p1)));
        }
        check(xok, "X25519: 20 willekeurige sleutels gelijk aan Java (XDH)");
        // ChaCha20-Poly1305 (RFC 8439 §2.8.2)
        byte[] key = hx("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f"), aad = hx("50515253c0c1c2c3c4c5c6c7");
        byte[] pt = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.".getBytes(StandardCharsets.US_ASCII);
        byte[] ct = new byte[pt.length + 16];
        WgCrypto.Aead ae = new WgCrypto.Aead();
        ae.seal(WgCrypto.Aead.keyWords(key), 7, 0x43424140, 0x47464544, aad, pt, 0, pt.length, ct, 0);
        check(hex(ct).startsWith("d31a8d34648e60db7b86afbc53ef7ec2") && hex(ct).endsWith("1ae10b594f09e26a7e902ecbd0600691"), "ChaCha20-Poly1305 RFC 8439");
        byte[] back = new byte[pt.length];
        check(ae.open(WgCrypto.Aead.keyWords(key), 7, 0x43424140, 0x47464544, aad, ct, 0, ct.length, back, 0) && new String(back, StandardCharsets.US_ASCII).startsWith("Ladies"), "ChaCha20-Poly1305 openen");
        ct[5] ^= 1;
        check(!ae.open(WgCrypto.Aead.keyWords(key), 7, 0x43424140, 0x47464544, aad, ct, 0, ct.length, back, 0), "gewijzigd bericht: geweigerd");
        // tegen de ingebouwde Java-versie, alle lengtes 0..300
        Random r = new Random(1);
        boolean cok = true;
        for (int len = 0; len <= 300 && cok; len++) {
            byte[] k = new byte[32], p = new byte[len], ad = new byte[len % 3 == 0 ? 0 : len % 37]; r.nextBytes(k); r.nextBytes(p); r.nextBytes(ad);
            long n = r.nextLong();
            byte[] mine = new byte[len + 16];
            ae.seal(k, n, ad, p, 0, len, mine, 0);
            cok = hex(mine).equals(hex(jdkSeal(k, n, ad, p)));
            if (!cok) System.out.println("  verschil bij lengte " + len);
        }
        check(cok, "ChaCha20-Poly1305 gelijk aan Java voor lengtes 0–300");
        // HChaCha20 en XChaCha20-Poly1305 (draft-irtf-cfrg-xchacha)
        int[] sub = ae.hchacha(WgCrypto.Aead.keyWords(seq(0, 32)), hx("000000090000004a0000000031415927"));
        byte[] sb = new byte[32]; for (int i = 0; i < 8; i++) WgCrypto.put32(sb, i * 4, sub[i]);
        check(hex(sb).equals("82413b4227b27bfed30e42508a877d73a0f9e4d58a74a853c12ec41326d3ecdc"), "HChaCha20");
        byte[] xct = new byte[pt.length + 16];
        ae.xseal(key, hx("404142434445464748494a4b4c4d4e4f5051525354555657"), aad, pt, xct);
        check(hex(xct).startsWith("bd6d179d3e83d43b9576579493c0e939") && hex(xct).endsWith("c0875924c1c7987947deafd8780acf49"), "XChaCha20-Poly1305");
        byte[] xback = new byte[pt.length];
        check(ae.xopen(key, hx("404142434445464748494a4b4c4d4e4f5051525354555657"), aad, xct, 0, xct.length, xback), "XChaCha20-Poly1305 openen");
        // KDF: vorm en afhankelijkheid
        byte[][] k3 = WgCrypto.kdf(3, seq(1, 32), seq(5, 7));
        check(k3.length == 3 && k3[0].length == 32 && !hex(k3[0]).equals(hex(k3[1])) && hex(WgCrypto.kdf(2, seq(1, 32), seq(5, 7))[1]).equals(hex(k3[1])), "KDF1/2/3 consistent");
        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
        System.out.println("Alle VPN-cryptotests geslaagd");
    }

    static byte[] jdkSeal(byte[] k, long n, byte[] aad, byte[] p) throws Exception {
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("ChaCha20-Poly1305");
        byte[] nonce = new byte[12]; WgCrypto.put64(nonce, 4, n);
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(k, "ChaCha20"), new javax.crypto.spec.IvParameterSpec(nonce));
        if (aad.length > 0) c.updateAAD(aad);
        return c.doFinal(p);
    }

    static byte[] jdkPub(byte[] priv) throws Exception {
        // PKCS#8 voor X25519: vaste kop + 32 bytes
        byte[] der = new byte[48];
        System.arraycopy(hx("302e020100300506032b656e04220420"), 0, der, 0, 16);
        System.arraycopy(priv, 0, der, 16, 32);
        java.security.KeyFactory kf = java.security.KeyFactory.getInstance("XDH");
        java.security.PrivateKey pk = kf.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(der));
        javax.crypto.KeyAgreement ka = javax.crypto.KeyAgreement.getInstance("XDH");
        ka.init(pk);
        byte[] base = new byte[32]; base[0] = 9;
        java.security.spec.XECPublicKeySpec spec = new java.security.spec.XECPublicKeySpec(java.security.spec.NamedParameterSpec.X25519, new java.math.BigInteger(1, reverse(base)));
        ka.doPhase(kf.generatePublic(spec), true);
        return ka.generateSecret();
    }

    static byte[] reverse(byte[] b) { byte[] r = new byte[b.length]; for (int i = 0; i < b.length; i++) r[i] = b[b.length - 1 - i]; return r; }
}
