package nl.rene.tools;

import java.security.SecureRandom;

/**
 * De bouwstenen van WireGuard, puur Java (geen Android, geen bibliotheken; getest met WgCryptoTest tegen de
 * RFC-testvectoren): BLAKE2s (RFC 7693), HMAC/HKDF daarop, X25519 (RFC 7748, naar TweetNaCl),
 * ChaCha20-Poly1305 (RFC 8439) en XChaCha20-Poly1305 (voor cookies).
 */
final class WgCrypto {
    private WgCrypto() { }

    static final SecureRandom RNG = new SecureRandom();

    // ================= BLAKE2s =================

    private static final int[] IV = {0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A, 0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19};
    private static final byte[][] SIGMA = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15}, {14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3},
            {11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4}, {7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8},
            {9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13}, {2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9},
            {12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11}, {13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10},
            {6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5}, {10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0}};

    /** Een BLAKE2s-berekening die je stukje voor stukje voert. */
    static final class Blake2s {
        private final int[] h = new int[8], m = new int[16], v = new int[16];
        private final byte[] buf = new byte[64];
        private int bufLen;
        private long t;
        private final int outLen;

        Blake2s(int outLen, byte[] key) {
            this.outLen = outLen;
            int kl = key == null ? 0 : key.length;
            System.arraycopy(IV, 0, h, 0, 8);
            h[0] ^= 0x01010000 ^ (kl << 8) ^ outLen;
            if (kl > 0) { System.arraycopy(key, 0, buf, 0, kl); bufLen = 64; }
        }

        Blake2s update(byte[] in) { return update(in, 0, in.length); }

        Blake2s update(byte[] in, int off, int len) {
            while (len > 0) {
                if (bufLen == 64) { t += 64; compress(false); bufLen = 0; }
                int n = Math.min(len, 64 - bufLen);
                System.arraycopy(in, off, buf, bufLen, n);
                bufLen += n; off += n; len -= n;
            }
            return this;
        }

        byte[] digest() {
            t += bufLen;
            for (int i = bufLen; i < 64; i++) buf[i] = 0;
            compress(true);
            byte[] out = new byte[outLen];
            for (int i = 0; i < outLen; i++) out[i] = (byte) (h[i >> 2] >>> (8 * (i & 3)));
            return out;
        }

        private void compress(boolean last) {
            for (int i = 0; i < 16; i++) m[i] = le32(buf, i * 4);
            System.arraycopy(h, 0, v, 0, 8);
            System.arraycopy(IV, 0, v, 8, 8);
            v[12] ^= (int) t; v[13] ^= (int) (t >>> 32);
            if (last) v[14] = ~v[14];
            for (int r = 0; r < 10; r++) {
                byte[] s = SIGMA[r];
                g(0, 4, 8, 12, m[s[0]], m[s[1]]); g(1, 5, 9, 13, m[s[2]], m[s[3]]);
                g(2, 6, 10, 14, m[s[4]], m[s[5]]); g(3, 7, 11, 15, m[s[6]], m[s[7]]);
                g(0, 5, 10, 15, m[s[8]], m[s[9]]); g(1, 6, 11, 12, m[s[10]], m[s[11]]);
                g(2, 7, 8, 13, m[s[12]], m[s[13]]); g(3, 4, 9, 14, m[s[14]], m[s[15]]);
            }
            for (int i = 0; i < 8; i++) h[i] ^= v[i] ^ v[i + 8];
        }

        private void g(int a, int b, int c, int d, int x, int y) {
            v[a] += v[b] + x; v[d] = Integer.rotateRight(v[d] ^ v[a], 16);
            v[c] += v[d]; v[b] = Integer.rotateRight(v[b] ^ v[c], 12);
            v[a] += v[b] + y; v[d] = Integer.rotateRight(v[d] ^ v[a], 8);
            v[c] += v[d]; v[b] = Integer.rotateRight(v[b] ^ v[c], 7);
        }
    }

    /** HASH(a || b || …) = BLAKE2s-256 */
    static byte[] hash(byte[]... parts) { Blake2s b = new Blake2s(32, null); for (byte[] p : parts) b.update(p); return b.digest(); }

    /** MAC(key, input) = BLAKE2s-128 met sleutel */
    static byte[] mac(byte[] key, byte[] in, int len) { return new Blake2s(16, key).update(in, 0, len).digest(); }

    /** HMAC(key, input) met BLAKE2s-256 (blok 64) */
    static byte[] hmac(byte[] key, byte[]... parts) {
        byte[] k = new byte[64], kk = key.length > 64 ? hash(key) : key;
        System.arraycopy(kk, 0, k, 0, kk.length);
        byte[] ipad = new byte[64], opad = new byte[64];
        for (int i = 0; i < 64; i++) { ipad[i] = (byte) (k[i] ^ 0x36); opad[i] = (byte) (k[i] ^ 0x5c); }
        Blake2s in = new Blake2s(32, null).update(ipad);
        for (byte[] p : parts) in.update(p);
        return new Blake2s(32, null).update(opad).update(in.digest()).digest();
    }

    /** KDF_n(key, input): n uitkomsten van 32 bytes (HKDF met HMAC-BLAKE2s). */
    static byte[][] kdf(int n, byte[] key, byte[] input) {
        byte[] t0 = hmac(key, input);
        byte[][] out = new byte[n][];
        byte[] prev = new byte[0];
        for (int i = 0; i < n; i++) { prev = hmac(t0, prev, new byte[]{(byte) (i + 1)}); out[i] = prev; }
        return out;
    }

    // ================= X25519 (naar TweetNaCl) =================

    private static final long[] C121665 = {0xDB41, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};

    private static void car(long[] o) {
        for (int i = 0; i < 16; i++) {
            o[i] += 1L << 16;
            long c = o[i] >> 16;
            if (i < 15) o[i + 1] += c - 1; else o[0] += 38 * (c - 1);
            o[i] -= c << 16;
        }
    }

    private static void sel(long[] p, long[] q, int b) {
        long c = ~(b - 1L);
        for (int i = 0; i < 16; i++) { long t = c & (p[i] ^ q[i]); p[i] ^= t; q[i] ^= t; }
    }

    private static void pack(byte[] o, long[] n) {
        long[] m = new long[16], t = n.clone();
        car(t); car(t); car(t);
        for (int j = 0; j < 2; j++) {
            m[0] = t[0] - 0xffed;
            for (int i = 1; i < 15; i++) { m[i] = t[i] - 0xffff - ((m[i - 1] >> 16) & 1); m[i - 1] &= 0xffff; }
            m[15] = t[15] - 0x7fff - ((m[14] >> 16) & 1);
            int b = (int) ((m[15] >> 16) & 1);
            m[14] &= 0xffff;
            sel(t, m, 1 - b);
        }
        for (int i = 0; i < 16; i++) { o[2 * i] = (byte) t[i]; o[2 * i + 1] = (byte) (t[i] >> 8); }
    }

    private static void unpack(long[] o, byte[] n) {
        for (int i = 0; i < 16; i++) o[i] = (n[2 * i] & 0xff) + ((long) (n[2 * i + 1] & 0xff) << 8);
        o[15] &= 0x7fff;
    }

    private static void add(long[] o, long[] a, long[] b) { for (int i = 0; i < 16; i++) o[i] = a[i] + b[i]; }
    private static void sub(long[] o, long[] a, long[] b) { for (int i = 0; i < 16; i++) o[i] = a[i] - b[i]; }

    private static void mul(long[] o, long[] a, long[] b) {
        long[] t = new long[31];
        for (int i = 0; i < 16; i++) for (int j = 0; j < 16; j++) t[i + j] += a[i] * b[j];
        for (int i = 0; i < 15; i++) t[i] += 38 * t[i + 16];
        System.arraycopy(t, 0, o, 0, 16);
        car(o); car(o);
    }

    private static void inv(long[] o, long[] in) {
        long[] c = in.clone();
        for (int a = 253; a >= 0; a--) { mul(c, c, c); if (a != 2 && a != 4) mul(c, c, in); }
        System.arraycopy(c, 0, o, 0, 16);
    }

    /** X25519(scalar, point) → 32 bytes. */
    static byte[] x25519(byte[] scalar, byte[] point) {
        byte[] z = scalar.clone();
        z[31] = (byte) ((z[31] & 127) | 64); z[0] &= (byte) 248;
        long[] x = new long[16], a = new long[16], b = new long[16], c = new long[16], d = new long[16], e = new long[16], f = new long[16];
        unpack(x, point);
        System.arraycopy(x, 0, b, 0, 16);
        a[0] = 1; d[0] = 1;
        for (int i = 254; i >= 0; --i) {
            int r = (z[i >>> 3] >>> (i & 7)) & 1;
            sel(a, b, r); sel(c, d, r);
            add(e, a, c); sub(a, a, c); add(c, b, d); sub(b, b, d); mul(d, e, e); mul(f, a, a); mul(a, c, a); mul(c, b, e);
            add(e, a, c); sub(a, a, c); mul(b, a, a); sub(c, d, f); mul(a, c, C121665); add(a, a, d); mul(c, c, a); mul(a, d, f);
            mul(d, b, x); mul(b, e, e);
            sel(a, b, r); sel(c, d, r);
        }
        inv(c, c); mul(a, a, c);
        byte[] q = new byte[32];
        pack(q, a);
        return q;
    }

    static final byte[] BASE = new byte[32];
    static { BASE[0] = 9; }

    static byte[] newPrivateKey() {
        byte[] k = new byte[32];
        RNG.nextBytes(k);
        k[0] &= (byte) 248; k[31] = (byte) ((k[31] & 127) | 64);
        return k;
    }

    static byte[] publicKey(byte[] priv) { return x25519(priv, BASE); }

    /** DH; geeft null bij een ongeldig (nul-)resultaat. */
    static byte[] dh(byte[] priv, byte[] pub) {
        byte[] s = x25519(priv, pub);
        int acc = 0;
        for (byte x : s) acc |= x;
        return acc == 0 ? null : s;
    }

    // ================= ChaCha20-Poly1305 =================

    static int le32(byte[] b, int o) { return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | (b[o + 3] & 0xff) << 24; }
    static void put32(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >>> 8); b[o + 2] = (byte) (v >>> 16); b[o + 3] = (byte) (v >>> 24); }
    static long le64(byte[] b, int o) { return (le32(b, o) & 0xffffffffL) | ((long) le32(b, o + 4) << 32); }
    static void put64(byte[] b, int o, long v) { put32(b, o, (int) v); put32(b, o + 4, (int) (v >>> 32)); }

    /**
     * Eén AEAD-object per thread (hergebruikt zijn werkgeheugen; niet gelijktijdig gebruiken).
     * Nonce zoals in WireGuard: 4 nullen en dan de teller (64 bit, little endian).
     */
    static final class Aead {
        private final int[] st = new int[16], x = new int[16];
        private final byte[] ks = new byte[64];
        private final byte[] tag = new byte[16], polyKey = new byte[32];

        static int[] keyWords(byte[] key) { int[] k = new int[8]; for (int i = 0; i < 8; i++) k[i] = le32(key, i * 4); return k; }

        private void init(int[] k, int counter, int n0, int n1, int n2) {
            st[0] = 0x61707865; st[1] = 0x3320646e; st[2] = 0x79622d32; st[3] = 0x6b206574;
            System.arraycopy(k, 0, st, 4, 8);
            st[12] = counter; st[13] = n0; st[14] = n1; st[15] = n2;
        }

        private void block() {
            System.arraycopy(st, 0, x, 0, 16);
            for (int i = 0; i < 10; i++) {
                qr(0, 4, 8, 12); qr(1, 5, 9, 13); qr(2, 6, 10, 14); qr(3, 7, 11, 15);
                qr(0, 5, 10, 15); qr(1, 6, 11, 12); qr(2, 7, 8, 13); qr(3, 4, 9, 14);
            }
            for (int i = 0; i < 16; i++) put32(ks, i * 4, x[i] + st[i]);
        }

        private void qr(int a, int b, int c, int d) {
            x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] ^ x[a], 16);
            x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] ^ x[c], 12);
            x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] ^ x[a], 8);
            x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] ^ x[c], 7);
        }

        /** XOR met de sleutelstroom vanaf blok 1 (in, out mogen hetzelfde zijn). */
        private void xor(byte[] in, int inOff, byte[] out, int outOff, int len) {
            st[12] = 1;
            for (int p = 0; p < len; p += 64) {
                block();
                st[12]++;
                int n = Math.min(64, len - p);
                for (int i = 0; i < n; i++) out[outOff + p + i] = (byte) (in[inOff + p + i] ^ ks[i]);
            }
        }

        /** Seal: out[outOff..] = versleuteld (len) + tag (16). */
        void seal(byte[] key, long nonce, byte[] aad, byte[] in, int inOff, int len, byte[] out, int outOff) {
            seal(keyWords(key), 0, (int) nonce, (int) (nonce >>> 32), aad, in, inOff, len, out, outOff);
        }

        void seal(int[] k, int n0, int n1, int n2, byte[] aad, byte[] in, int inOff, int len, byte[] out, int outOff) {
            init(k, 0, n0, n1, n2);
            block();
            System.arraycopy(ks, 0, polyKey, 0, 32);
            xor(in, inOff, out, outOff, len);
            poly(polyKey, aad, out, outOff, len, tag);
            System.arraycopy(tag, 0, out, outOff + len, 16);
        }

        /** Open: in[inOff..] = versleuteld + tag (len inclusief tag). Geeft false als de tag niet klopt. */
        boolean open(byte[] key, long nonce, byte[] aad, byte[] in, int inOff, int len, byte[] out, int outOff) {
            return open(keyWords(key), 0, (int) nonce, (int) (nonce >>> 32), aad, in, inOff, len, out, outOff);
        }

        boolean open(int[] k, int n0, int n1, int n2, byte[] aad, byte[] in, int inOff, int len, byte[] out, int outOff) {
            if (len < 16) return false;
            int n = len - 16;
            init(k, 0, n0, n1, n2);
            block();
            poly(ks, aad, in, inOff, n, tag);
            int diff = 0;
            for (int i = 0; i < 16; i++) diff |= tag[i] ^ in[inOff + n + i];
            if (diff != 0) return false;
            xor(in, inOff, out, outOff, n);
            return true;
        }

        /** XChaCha20-Poly1305 (voor de cookie): HChaCha20 met de eerste 16 bytes van de nonce. */
        boolean xopen(byte[] key, byte[] nonce24, byte[] aad, byte[] in, int inOff, int len, byte[] out) {
            int[] sub = hchacha(keyWords(key), nonce24);
            return open(sub, 0, le32(nonce24, 16), le32(nonce24, 20), aad, in, inOff, len, out, 0);
        }

        void xseal(byte[] key, byte[] nonce24, byte[] aad, byte[] in, byte[] out) {
            int[] sub = hchacha(keyWords(key), nonce24);
            seal(sub, 0, le32(nonce24, 16), le32(nonce24, 20), aad, in, 0, in.length, out, 0);
        }

        int[] hchacha(int[] k, byte[] n) {
            init(k, le32(n, 0), le32(n, 4), le32(n, 8), le32(n, 12));
            System.arraycopy(st, 0, x, 0, 16);
            for (int i = 0; i < 10; i++) {
                qr(0, 4, 8, 12); qr(1, 5, 9, 13); qr(2, 6, 10, 14); qr(3, 7, 11, 15);
                qr(0, 5, 10, 15); qr(1, 6, 11, 12); qr(2, 7, 8, 13); qr(3, 4, 9, 14);
            }
            return new int[]{x[0], x[1], x[2], x[3], x[12], x[13], x[14], x[15]};
        }

        // ----- Poly1305 (26-bit limbs, naar poly1305-donna) -----
        private final byte[] pad = new byte[16];

        private void poly(byte[] key, byte[] aad, byte[] ct, int ctOff, int ctLen, byte[] out) {
            final long M = 0x3ffffff;
            long r0 = le32(key, 0) & 0x3ffffff, r1 = (le32(key, 3) >>> 2) & 0x3ffff03, r2 = (le32(key, 6) >>> 4) & 0x3ffc0ff,
                    r3 = (le32(key, 9) >>> 6) & 0x3f03fff, r4 = (le32(key, 12) >>> 8) & 0x00fffff;
            long s1 = r1 * 5, s2 = r2 * 5, s3 = r3 * 5, s4 = r4 * 5;
            long h0 = 0, h1 = 0, h2 = 0, h3 = 0, h4 = 0;
            int aadLen = aad == null ? 0 : aad.length;
            // de invoer: aad, opvulling, ct, opvulling, lengtes — steeds in blokken van 16
            int total = ((aadLen + 15) & ~15) + ((ctLen + 15) & ~15) + 16;
            byte[] blk = pad;
            for (int pos = 0; pos < total; pos += 16) {
                // blok samenstellen
                byte[] src; int so; int avail;
                int aadEnd = (aadLen + 15) & ~15, ctEnd = aadEnd + ((ctLen + 15) & ~15);
                if (pos < aadEnd) { src = aad; so = pos; avail = aadLen - pos; }
                else if (pos < ctEnd) { src = ct; so = ctOff + pos - aadEnd; avail = ctLen - (pos - aadEnd); }
                else { src = null; so = 0; avail = 0; }
                if (src != null && avail >= 16) { blk = src; }
                else {
                    blk = pad;
                    for (int i = 0; i < 16; i++) pad[i] = 0;
                    if (src != null) System.arraycopy(src, so, pad, 0, avail);
                    else { put64(pad, 0, aadLen); put64(pad, 8, ctLen); }
                    so = 0;
                }
                h0 += le32(blk, so) & M;
                h1 += (le32(blk, so + 3) >>> 2) & M;
                h2 += (le32(blk, so + 6) >>> 4) & M;
                h3 += (le32(blk, so + 9) >>> 6) & M;
                h4 += ((le32(blk, so + 12) & 0xffffffffL) >>> 8) | (1L << 24);
                long d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1;
                long d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2;
                long d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3;
                long d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4;
                long d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0;
                long c = d0 >>> 26; h0 = d0 & M; d1 += c;
                c = d1 >>> 26; h1 = d1 & M; d2 += c;
                c = d2 >>> 26; h2 = d2 & M; d3 += c;
                c = d3 >>> 26; h3 = d3 & M; d4 += c;
                c = d4 >>> 26; h4 = d4 & M; h0 += c * 5;
                c = h0 >>> 26; h0 &= M; h1 += c;
            }
            long c = h1 >>> 26; h1 &= M; h2 += c;
            c = h2 >>> 26; h2 &= M; h3 += c;
            c = h3 >>> 26; h3 &= M; h4 += c;
            c = h4 >>> 26; h4 &= M; h0 += c * 5;
            c = h0 >>> 26; h0 &= M; h1 += c;
            long g0 = h0 + 5; c = g0 >>> 26; g0 &= M;
            long g1 = h1 + c; c = g1 >>> 26; g1 &= M;
            long g2 = h2 + c; c = g2 >>> 26; g2 &= M;
            long g3 = h3 + c; c = g3 >>> 26; g3 &= M;
            long g4 = h4 + c - (1L << 26);
            long mask = (g4 >>> 63) - 1; // g4 < 0: h houden (mask 0); anders g
            h0 = (h0 & ~mask) | (g0 & mask); h1 = (h1 & ~mask) | (g1 & mask); h2 = (h2 & ~mask) | (g2 & mask);
            h3 = (h3 & ~mask) | (g3 & mask); h4 = (h4 & ~mask) | (g4 & mask & M);
            long f0 = (h0 | (h1 << 26)) & 0xffffffffL, f1 = ((h1 >>> 6) | (h2 << 20)) & 0xffffffffL,
                    f2 = ((h2 >>> 12) | (h3 << 14)) & 0xffffffffL, f3 = ((h3 >>> 18) | (h4 << 8)) & 0xffffffffL;
            long f = f0 + (le32(key, 16) & 0xffffffffL); put32(out, 0, (int) f);
            f = f1 + (le32(key, 20) & 0xffffffffL) + (f >>> 32); put32(out, 4, (int) f);
            f = f2 + (le32(key, 24) & 0xffffffffL) + (f >>> 32); put32(out, 8, (int) f);
            f = f3 + (le32(key, 28) & 0xffffffffL) + (f >>> 32); put32(out, 12, (int) f);
        }
    }

    /** Constant-time vergelijken. */
    static boolean eq(byte[] a, int ao, byte[] b, int bo, int n) {
        int d = 0;
        for (int i = 0; i < n; i++) d |= a[ao + i] ^ b[bo + i];
        return d == 0;
    }
}
