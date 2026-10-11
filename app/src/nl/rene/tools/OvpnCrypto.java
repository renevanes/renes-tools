package nl.rene.tools;

import java.nio.charset.StandardCharsets;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cryptografie van OpenVPN: de PRF voor de sleutels, de bescherming van het controlekanaal (tls-auth, tls-crypt) en
 * het datakanaal (AES-GCM, ChaCha20-Poly1305, of het oude AES-CBC met HMAC). Getest tegen een echte OpenVPN-server.
 */
final class OvpnCrypto {
    private OvpnCrypto() { }

    static String macAlg(String digest) { return "Hmac" + (digest.equals("SHA1") ? "SHA1" : digest); }

    static int macLen(String digest) {
        switch (digest) { case "SHA256": return 32; case "SHA384": return 48; case "SHA512": return 64; default: return 20; }
    }

    static byte[] hmac(String alg, byte[] key, int keyLen, byte[]... parts) {
        try {
            Mac m = Mac.getInstance(alg);
            m.init(new SecretKeySpec(key, 0, keyLen, alg));
            for (byte[] p : parts) m.update(p);
            return m.doFinal();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    // ---------- TLS 1.0-PRF (P_MD5 xor P_SHA1), zoals OpenVPN zijn sleutels afleidt ----------

    static byte[] prf(byte[] secret, String label, int len, byte[]... seeds) {
        int total = label.length(); for (byte[] s : seeds) total += s.length;
        byte[] seed = new byte[total];
        byte[] lb = label.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(lb, 0, seed, 0, lb.length);
        int o = lb.length;
        for (byte[] s : seeds) { System.arraycopy(s, 0, seed, o, s.length); o += s.length; }
        int half = (secret.length + 1) / 2;
        byte[] s1 = java.util.Arrays.copyOfRange(secret, 0, half), s2 = java.util.Arrays.copyOfRange(secret, secret.length - half, secret.length);
        byte[] a = pHash("HmacMD5", s1, seed, len), b = pHash("HmacSHA1", s2, seed, len);
        for (int i = 0; i < len; i++) a[i] ^= b[i];
        return a;
    }

    private static byte[] pHash(String alg, byte[] secret, byte[] seed, int len) {
        byte[] out = new byte[len];
        byte[] a = seed;
        int o = 0;
        while (o < len) {
            a = hmac(alg, secret, secret.length, a);
            byte[] r = hmac(alg, secret, secret.length, a, seed);
            int n = Math.min(r.length, len - o);
            System.arraycopy(r, 0, out, o, n);
            o += n;
        }
        return out;
    }

    // ---------- controlekanaal: tls-auth / tls-crypt ----------

    /** Bescherming van de controlepakketten. Niet thread-safe: gebruik onder het slot van het apparaat. */
    static final class Wrap {
        static final int NONE = 0, AUTH = 1, CRYPT = 2;
        final int mode;
        private final String alg;
        private final int macLen;
        private final byte[] outCipher, outMac, inCipher, inMac;
        private int sendPid;
        final WgDevice.Replay replay = new WgDevice.Replay();

        Wrap(OvpnConfig c) {
            byte[] k;
            int dir; // 1: uit = sleutel 1, in = sleutel 0 (zo staat het bij een client)
            if (c.tlsCrypt != null) { mode = CRYPT; k = c.tlsCrypt; dir = 1; alg = "HmacSHA256"; macLen = 32; }
            else if (c.tlsAuth != null) { mode = AUTH; k = c.tlsAuth; dir = c.keyDirection; alg = macAlg(c.auth); macLen = OvpnCrypto.macLen(c.auth); }
            else { mode = NONE; k = new byte[256]; dir = -1; alg = null; macLen = 0; }
            int outK = dir == 1 ? 1 : 0, inK = dir == 1 ? 0 : dir == 0 ? 1 : 0;
            outCipher = java.util.Arrays.copyOfRange(k, outK * 128, outK * 128 + 64);
            outMac = java.util.Arrays.copyOfRange(k, outK * 128 + 64, outK * 128 + 128);
            inCipher = java.util.Arrays.copyOfRange(k, inK * 128, inK * 128 + 64);
            inMac = java.util.Arrays.copyOfRange(k, inK * 128 + 64, inK * 128 + 128);
        }

        /** Extra ruimte die de bescherming per pakket kost. */
        int overhead() { return mode == NONE ? 0 : mode == AUTH ? macLen + 8 : 8 + 32; }

        /** Pakket maken: op, sessie-id en de rest (acks, berichtnummer, inhoud). */
        byte[] wrap(int op, byte[] sid, byte[] body) {
            if (mode == NONE) {
                byte[] p = new byte[9 + body.length];
                p[0] = (byte) op; System.arraycopy(sid, 0, p, 1, 8); System.arraycopy(body, 0, p, 9, body.length);
                return p;
            }
            byte[] pt = new byte[8];
            put32be(pt, 0, ++sendPid);
            put32be(pt, 4, (int) (System.currentTimeMillis() / 1000));
            byte[] opsid = new byte[9];
            opsid[0] = (byte) op; System.arraycopy(sid, 0, opsid, 1, 8);
            if (mode == AUTH) {
                byte[] h = hmac(alg, outMac, macLen, pt, opsid, body);
                byte[] p = new byte[9 + macLen + 8 + body.length];
                System.arraycopy(opsid, 0, p, 0, 9);
                System.arraycopy(h, 0, p, 9, macLen);
                System.arraycopy(pt, 0, p, 9 + macLen, 8);
                System.arraycopy(body, 0, p, 17 + macLen, body.length);
                return p;
            }
            byte[] hdr = new byte[17];
            System.arraycopy(opsid, 0, hdr, 0, 9); System.arraycopy(pt, 0, hdr, 9, 8);
            byte[] tag = hmac(alg, outMac, 32, hdr, body);
            byte[] ct = ctr(outCipher, tag, body);
            byte[] p = new byte[17 + 32 + ct.length];
            System.arraycopy(hdr, 0, p, 0, 17); System.arraycopy(tag, 0, p, 17, 32); System.arraycopy(ct, 0, p, 49, ct.length);
            return p;
        }

        /** Pakket controleren en uitpakken: geeft de rest na op en sessie-id, of null. */
        byte[] unwrap(byte[] p, int len) {
            if (len < 9) return null;
            if (mode == NONE) return java.util.Arrays.copyOfRange(p, 9, len);
            if (mode == AUTH) {
                if (len < 17 + macLen) return null;
                byte[] pt = java.util.Arrays.copyOfRange(p, 9 + macLen, 17 + macLen), opsid = java.util.Arrays.copyOfRange(p, 0, 9),
                        body = java.util.Arrays.copyOfRange(p, 17 + macLen, len);
                byte[] h = hmac(alg, inMac, macLen, pt, opsid, body);
                if (!WgCrypto.eq(h, 0, p, 9, macLen)) return null;
                if (!replay.check(get32be(pt, 0) & 0xffffffffL)) return null;
                return body;
            }
            if (len < 49) return null;
            byte[] hdr = java.util.Arrays.copyOfRange(p, 0, 17), tag = java.util.Arrays.copyOfRange(p, 17, 49);
            byte[] body = ctr(inCipher, tag, java.util.Arrays.copyOfRange(p, 49, len));
            byte[] h = hmac(alg, inMac, 32, hdr, body);
            if (!WgCrypto.eq(h, 0, tag, 0, 32)) return null;
            if (!replay.check(get32be(hdr, 9) & 0xffffffffL)) return null;
            return body;
        }

        private static byte[] ctr(byte[] key, byte[] iv, byte[] in) {
            try {
                Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, 0, 32, "AES"), new IvParameterSpec(iv, 0, 16));
                return c.doFinal(in);
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
    }

    static void put32be(byte[] b, int o, int v) { b[o] = (byte) (v >>> 24); b[o + 1] = (byte) (v >>> 16); b[o + 2] = (byte) (v >>> 8); b[o + 3] = (byte) v; }
    static int get32be(byte[] b, int o) { return (b[o] & 0xff) << 24 | (b[o + 1] & 0xff) << 16 | (b[o + 2] & 0xff) << 8 | (b[o + 3] & 0xff); }

    // ---------- datakanaal ----------

    /** Sleutels van één sessie (key-id) in één richting-paar. */
    static final class DataKeys {
        static final int GCM = 1, CHACHA = 2, CBC = 3;
        final int type, keyId;
        final String name;
        private final byte[] encKey, decKey, encImplicit, decImplicit, encMac, decMac;
        private final String macAlg;
        private final int macLen;
        private int sendPid;
        final WgDevice.Replay replay = new WgDevice.Replay();
        final long created = System.nanoTime() / 1_000_000L;
        private final WgCrypto.Aead aeadOut = new WgCrypto.Aead(), aeadIn = new WgCrypto.Aead();

        /** keyBlock: 256 bytes uit de PRF; de client versleutelt met sleutel 0 en ontsleutelt met sleutel 1. */
        DataKeys(String cipher, String digest, byte[] keyBlock, int keyId) {
            this.keyId = keyId;
            this.name = cipher;
            int keyLen;
            switch (cipher) {
                case "AES-256-GCM": type = GCM; keyLen = 32; break;
                case "AES-128-GCM": type = GCM; keyLen = 16; break;
                case "AES-192-GCM": type = GCM; keyLen = 24; break;
                case "CHACHA20-POLY1305": type = CHACHA; keyLen = 32; break;
                case "AES-256-CBC": type = CBC; keyLen = 32; break;
                case "AES-128-CBC": type = CBC; keyLen = 16; break;
                case "AES-192-CBC": type = CBC; keyLen = 24; break;
                default: throw new IllegalArgumentException("Versleuteling " + cipher + " wordt niet ondersteund");
            }
            encKey = java.util.Arrays.copyOfRange(keyBlock, 0, keyLen);
            decKey = java.util.Arrays.copyOfRange(keyBlock, 128, 128 + keyLen);
            encImplicit = java.util.Arrays.copyOfRange(keyBlock, 64, 72);
            decImplicit = java.util.Arrays.copyOfRange(keyBlock, 192, 200);
            macAlg = macAlg(digest);
            macLen = OvpnCrypto.macLen(digest);
            encMac = java.util.Arrays.copyOfRange(keyBlock, 64, 64 + macLen);
            decMac = java.util.Arrays.copyOfRange(keyBlock, 192, 192 + macLen);
        }

        int overhead() { return type == CBC ? macLen + 16 + 16 + 4 : 4 + 16; }

        private final Object encLock = new Object(), decLock = new Object();
        private Cipher encC, decC; // hergebruikt (per richting)

        /** Versleutelen: hdr (1 of 4 bytes) + beveiligd pakket. */
        byte[] encrypt(byte[] hdr, byte[] pl, int off, int len) throws Exception {
            synchronized (encLock) { return encryptLocked(hdr, pl, off, len); }
        }

        private byte[] encryptLocked(byte[] hdr, byte[] pl, int off, int len) throws Exception {
            int pid = ++sendPid;
            if (pid == 0) throw new IllegalStateException("pakketteller vol");
            if (type == CBC) {
                byte[] iv = new byte[16];
                WgCrypto.RNG.nextBytes(iv);
                byte[] pt = new byte[4 + len];
                put32be(pt, 0, pid);
                System.arraycopy(pl, off, pt, 4, len);
                if (encC == null) encC = Cipher.getInstance("AES/CBC/PKCS5Padding");
                encC.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encKey, "AES"), new IvParameterSpec(iv));
                byte[] ct = encC.doFinal(pt);
                byte[] h = hmac(macAlg, encMac, macLen, iv, ct);
                byte[] out = new byte[hdr.length + macLen + 16 + ct.length];
                System.arraycopy(hdr, 0, out, 0, hdr.length);
                System.arraycopy(h, 0, out, hdr.length, macLen);
                System.arraycopy(iv, 0, out, hdr.length + macLen, 16);
                System.arraycopy(ct, 0, out, hdr.length + macLen + 16, ct.length);
                return out;
            }
            // geauthenticeerd: bij P_DATA_V2 de kop (opcode + peer-id) en het pakketnummer, bij V1 alleen het pakketnummer
            int ah = hdr.length == 4 ? 4 : 0;
            byte[] aad = new byte[ah + 4];
            System.arraycopy(hdr, 0, aad, 0, ah);
            put32be(aad, ah, pid);
            byte[] nonce = new byte[12];
            put32be(nonce, 0, pid);
            System.arraycopy(encImplicit, 0, nonce, 4, 8);
            int head = hdr.length + 4;
            byte[] out = new byte[head + 16 + len];
            System.arraycopy(hdr, 0, out, 0, hdr.length);
            put32be(out, hdr.length, pid);
            byte[] ctTag = new byte[len + 16];
            if (type == GCM) {
                if (encC == null) encC = Cipher.getInstance("AES/GCM/NoPadding");
                encC.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encKey, "AES"), new GCMParameterSpec(128, nonce));
                encC.updateAAD(aad);
                ctTag = encC.doFinal(pl, off, len);
            } else {
                aeadOut.seal(WgCrypto.Aead.keyWords(encKey), WgCrypto.le32(nonce, 0), WgCrypto.le32(nonce, 4), WgCrypto.le32(nonce, 8), aad, pl, off, len, ctTag, 0);
            }
            System.arraycopy(ctTag, len, out, head, 16);       // tag eerst
            System.arraycopy(ctTag, 0, out, head + 16, len);   // dan de versleutelde inhoud
            return out;
        }

        /** Ontsleutelen (vanaf hdrLen); geeft de inhoud of null. */
        byte[] decrypt(byte[] p, int hdrLen, int len) {
            synchronized (decLock) { return decryptLocked(p, hdrLen, len); }
        }

        private byte[] decryptLocked(byte[] p, int hdrLen, int len) {
            try {
                if (type == CBC) {
                    if (len < hdrLen + macLen + 16 + 16) return null;
                    int ivOff = hdrLen + macLen;
                    byte[] h = hmac(macAlg, decMac, macLen, java.util.Arrays.copyOfRange(p, ivOff, len));
                    if (!WgCrypto.eq(h, 0, p, hdrLen, macLen)) return null;
                    if (decC == null) decC = Cipher.getInstance("AES/CBC/PKCS5Padding");
                    decC.init(Cipher.DECRYPT_MODE, new SecretKeySpec(decKey, "AES"), new IvParameterSpec(p, ivOff, 16));
                    byte[] pt = decC.doFinal(p, ivOff + 16, len - ivOff - 16);
                    if (pt.length < 4) return null;
                    if (!replay.check(get32be(pt, 0) & 0xffffffffL)) return null;
                    return java.util.Arrays.copyOfRange(pt, 4, pt.length);
                }
                if (len < hdrLen + 4 + 16) return null;
                byte[] aad = java.util.Arrays.copyOfRange(p, hdrLen == 4 ? 0 : hdrLen, hdrLen + 4); // V1: alleen het pakketnummer
                int pid = get32be(p, hdrLen);
                byte[] nonce = new byte[12];
                put32be(nonce, 0, pid);
                System.arraycopy(decImplicit, 0, nonce, 4, 8);
                int ctOff = hdrLen + 4 + 16, n = len - ctOff;
                byte[] ctTag = new byte[n + 16];
                System.arraycopy(p, ctOff, ctTag, 0, n);
                System.arraycopy(p, hdrLen + 4, ctTag, n, 16);
                byte[] pt;
                if (type == GCM) {
                    if (decC == null) decC = Cipher.getInstance("AES/GCM/NoPadding");
                    decC.init(Cipher.DECRYPT_MODE, new SecretKeySpec(decKey, "AES"), new GCMParameterSpec(128, nonce));
                    decC.updateAAD(aad);
                    pt = decC.doFinal(ctTag);
                } else {
                    pt = new byte[n];
                    if (!aeadIn.open(WgCrypto.Aead.keyWords(decKey), WgCrypto.le32(nonce, 0), WgCrypto.le32(nonce, 4), WgCrypto.le32(nonce, 8), aad, ctTag, 0, n + 16, pt, 0)) return null;
                }
                if (!replay.check(pid & 0xffffffffL)) return null;
                return pt;
            } catch (Exception e) {
                return null; // verkeerde tag of kapot pakket
            }
        }
    }
}
