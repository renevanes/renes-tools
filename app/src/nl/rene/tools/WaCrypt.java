package nl.rene.tools;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Ontsleutelt een WhatsApp-backup (msgstore.db.crypt15) met de sleutel van 64 tekens die
 * WhatsApp toont bij de end-to-end versleutelde back-up. Gebaseerd op het openbare formaat
 * zoals beschreven door wa-crypt-tools: header (varint-lengte + protobuf met IV), daarna
 * AES-256-GCM-versleutelde, met zlib gecomprimeerde SQLite-database, dan 16 bytes GCM-tag
 * en 16 bytes MD5-controlegetal.
 *
 * Alleen standaard Java (geen Android), zodat het ook op een pc te testen is.
 */
final class WaCrypt {

    private WaCrypt() { }

    static final class Header {
        int dataOffset;     // begin van de versleutelde gegevens
        byte[] iv;          // 16 bytes
        boolean e2e;        // true = crypt15 met eigen sleutel; false = crypt14 (sleutel van WhatsApp)
        String appVersion = "";
    }

    /** "1a2b 3c4d ..." -> 32 bytes. Gooit een fout met een duidelijke melding. */
    static byte[] parseKey(String s) throws Exception {
        String h = s == null ? "" : s.toLowerCase().replaceAll("[^0-9a-f]", "");
        if (h.length() != 64) throw new Exception("De sleutel moet 64 tekens hebben (0-9 en a-f); je hebt er " + h.length());
        byte[] b = new byte[32];
        for (int i = 0; i < 32; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    /** Afgeleide AES-sleutel: HMAC(HMAC(0^32, root), "backup encryption" || 0x01). */
    static byte[] deriveKey(byte[] root) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
        byte[] priv = m.doFinal(root);
        m.init(new SecretKeySpec(priv, "HmacSHA256"));
        m.update("backup encryption".getBytes("UTF-8"));
        m.update((byte) 1);
        return m.doFinal();
    }

    // ---------- header (protobuf zonder bibliotheek) ----------

    static Header readHeader(File f) throws Exception {
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            byte[] start = new byte[4096];
            int n = readFully(in, start);
            int[] pos = {0};
            long size = varint(start, pos, n);
            if (size <= 0 || size > 3000) throw new Exception("Dit is geen bekende WhatsApp-backup");
            if (pos[0] < n && start[pos[0]] == 0x01) pos[0]++; // oudere bestanden: vlag "feature table"
            int pbStart = pos[0];
            int pbEnd = pbStart + (int) size;
            if (pbEnd > n) throw new Exception("Header onvolledig");
            Header h = new Header();
            h.dataOffset = pbEnd;
            // Doorloop BackupPrefix: veld 2 = crypt14 (sleutel van WhatsApp), 3 = crypt15 (eigen sleutel), 4 = metadata
            int p = pbStart;
            while (p < pbEnd) {
                int[] q = {p};
                long tag = varint(start, q, pbEnd);
                int field = (int) (tag >>> 3), wt = (int) (tag & 7);
                p = q[0];
                if (wt == 2) {
                    int len = (int) varint(start, q, pbEnd);
                    int s = q[0];
                    if (field == 3) {
                        byte[] iv = findBytesField(start, s, s + len, 1);
                        if (iv != null && iv.length == 16) { h.iv = iv; h.e2e = true; }
                    } else if (field == 2 && h.iv == null) {
                        h.iv = new byte[16]; h.e2e = false; // crypt14: sleutel staat afgeschermd bij WhatsApp
                    } else if (field == 4) {
                        byte[] v = findBytesField(start, s, s + len, 1);
                        if (v != null) h.appVersion = new String(v, "UTF-8");
                    }
                    p = s + len;
                } else if (wt == 0) {
                    varint(start, q, pbEnd); p = q[0];
                } else if (wt == 5) { p += 4; } else if (wt == 1) { p += 8; } else break;
            }
            if (h.iv == null) throw new Exception("Dit backupformaat wordt niet ondersteund (oud crypt12 of nieuwer)");
            return h;
        }
    }

    /** Zoekt in een protobuf-bericht het eerste lengte-veld met nummer 'want'. */
    private static byte[] findBytesField(byte[] b, int p, int end, int want) {
        while (p < end) {
            int[] q = {p};
            long tag = varint(b, q, end);
            int field = (int) (tag >>> 3), wt = (int) (tag & 7);
            if (wt == 2) {
                int len = (int) varint(b, q, end);
                if (q[0] + len > end) return null;
                if (field == want) {
                    byte[] r = new byte[len];
                    System.arraycopy(b, q[0], r, 0, len);
                    return r;
                }
                p = q[0] + len;
            } else if (wt == 0) { varint(b, q, end); p = q[0]; }
            else if (wt == 5) p = q[0] + 4;
            else if (wt == 1) p = q[0] + 8;
            else return null;
        }
        return null;
    }

    private static long varint(byte[] b, int[] pos, int end) {
        long r = 0;
        for (int shift = 0; shift < 64 && pos[0] < end; shift += 7) {
            int x = b[pos[0]++] & 0xff;
            r |= (long) (x & 0x7f) << shift;
            if ((x & 0x80) == 0) return r;
        }
        return -1;
    }

    // ---------- ontsleutelen ----------

    /**
     * Ontsleutelt 'crypt' naar een SQLite-bestand 'out'. Controleert het resultaat op de
     * SQLite-kop; een verkeerde sleutel geeft dus een duidelijke fout.
     */
    static Header decrypt(File crypt, byte[] rootKey, File out) throws Exception {
        Header h = readHeader(crypt);
        if (!h.e2e) throw new NotE2EException();
        byte[] key = deriveKey(rootKey);
        long len = crypt.length();
        // Controlegetal: md5 over alles behalve de laatste 16 bytes. Klopt het niet, dan is het
        // een "multifile"-backup en horen de 16 bytes vóór het einde ook bij de gegevens.
        long dataEnd = len - 32;
        if (!md5Matches(crypt, len)) dataEnd = len - 16;
        if (dataEnd <= h.dataOffset) throw new Exception("Backup is te klein of beschadigd");

        GcmCtrInputStream dec = new GcmCtrInputStream(crypt, h.dataOffset, dataEnd, key, h.iv);
        BufferedInputStream peek = new BufferedInputStream(dec, 1 << 16);
        peek.mark(16);
        byte[] first = new byte[16];
        int got = readFully(peek, first);
        peek.reset();
        InputStream db;
        if (got >= 2 && (first[0] & 0xff) == 0x78) {
            db = new InflaterInputStream(peek, new java.util.zip.Inflater(), 1 << 16);
        } else if (got >= 2 && first[0] == 'P' && first[1] == 'K') {
            ZipInputStream z = new ZipInputStream(peek);
            ZipEntry e;
            InputStream found = null;
            while ((e = z.getNextEntry()) != null) {
                if (!e.isDirectory()) { found = z; break; }
            }
            if (found == null) throw new Exception("Lege backup");
            db = found;
        } else if (startsWithSqlite(first, got)) {
            db = peek;
        } else {
            throw new WrongKeyException();
        }
        File tmp = new File(out.getParentFile(), out.getName() + ".tmp");
        try (InputStream in = db; OutputStream o = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            int n, total = 0;
            byte[] head = new byte[16];
            while ((n = in.read(buf)) > 0) {
                if (total < 16) System.arraycopy(buf, 0, head, total, Math.min(16 - total, n));
                total += n;
                o.write(buf, 0, n);
            }
            if (!startsWithSqlite(head, Math.min(total, 16))) throw new WrongKeyException();
        } catch (java.util.zip.ZipException ze) {
            tmp.delete();
            throw new WrongKeyException();
        } catch (Exception e) {
            tmp.delete();
            throw e;
        }
        if (out.exists() && !out.delete()) throw new IOException("Oud bestand kan niet worden vervangen");
        if (!tmp.renameTo(out)) throw new IOException("Opslaan mislukt");
        return h;
    }

    static final class WrongKeyException extends Exception {
        WrongKeyException() { super("De sleutel klopt niet bij deze backup"); }
    }

    static final class NotE2EException extends Exception {
        NotE2EException() { super("Deze backup is niet versleuteld met een eigen sleutel van 64 tekens"); }
    }

    private static boolean startsWithSqlite(byte[] b, int n) {
        byte[] s = "SQLite format 3".getBytes();
        if (n < s.length) return false;
        for (int i = 0; i < s.length; i++) if (b[i] != s[i]) return false;
        return true;
    }

    private static boolean md5Matches(File f, long len) throws Exception {
        if (len < 32) return false;
        MessageDigest md = MessageDigest.getInstance("MD5");
        try (InputStream in = new BufferedInputStream(new FileInputStream(f), 1 << 16)) {
            byte[] buf = new byte[1 << 16];
            long left = len - 16;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new EOFException();
                md.update(buf, 0, n);
                left -= n;
            }
            byte[] want = new byte[16];
            if (readFully(in, want) != 16) return false;
            return MessageDigest.isEqual(md.digest(), want);
        }
    }

    static int readFully(InputStream in, byte[] b) throws IOException {
        int t = 0, n;
        while (t < b.length && (n = in.read(b, t, b.length - t)) > 0) t += n;
        return t;
    }

    /**
     * AES-GCM ontsleutelen als tellermodus (CTR), in stukken, zodat ook backups van
     * honderden MB passen zonder alles in het geheugen te laden. GCM met een IV van 16 bytes:
     * J0 = GHASH_H(IV || 0^64 || [128]_64), H = AES_K(0^128); de teller begint bij inc32(J0)
     * en alleen de laatste 32 bits tellen op. De echtheid wordt gecontroleerd via de
     * SQLite-kop van het resultaat in plaats van de GCM-tag.
     */
    static final class GcmCtrInputStream extends InputStream {
        private final RandomAccessFile raf;
        private long pos;
        private final long end;
        private final Cipher ecb;
        private final byte[] ctr = new byte[16];
        private final byte[] inBuf = new byte[1 << 16];
        private final byte[] ksIn = new byte[1 << 16];
        private byte[] ks = new byte[0];
        private final byte[] outBuf = new byte[1 << 16];
        private int outPos = 0, outLen = 0;

        GcmCtrInputStream(File f, long start, long end, byte[] key, byte[] iv) throws Exception {
            raf = new RandomAccessFile(f, "r");
            pos = start;
            this.end = end;
            ecb = Cipher.getInstance("AES/ECB/NoPadding");
            ecb.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            byte[] h = ecb.doFinal(new byte[16]);
            byte[] lenBlock = new byte[16];
            lenBlock[15] = (byte) 0x80; // 128 bits IV-lengte
            byte[] y = gfMul(xor(new byte[16], iv), h);
            y = gfMul(xor(y, lenBlock), h);
            System.arraycopy(y, 0, ctr, 0, 16);
            inc32(ctr);
        }

        private boolean fill() throws IOException {
            if (pos >= end) return false;
            int want = (int) Math.min(inBuf.length, end - pos);
            raf.seek(pos);
            raf.readFully(inBuf, 0, want);
            pos += want;
            int blocks = (want + 15) / 16;
            for (int i = 0; i < blocks; i++) {
                System.arraycopy(ctr, 0, ksIn, i * 16, 16);
                inc32(ctr);
            }
            try {
                ks = ecb.update(ksIn, 0, blocks * 16);
            } catch (Exception e) { throw new IOException(e); }
            for (int i = 0; i < want; i++) outBuf[i] = (byte) (inBuf[i] ^ ks[i]);
            outPos = 0;
            outLen = want;
            return true;
        }

        @Override public int read() throws IOException {
            if (outPos >= outLen && !fill()) return -1;
            return outBuf[outPos++] & 0xff;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (outPos >= outLen && !fill()) return -1;
            int n = Math.min(len, outLen - outPos);
            System.arraycopy(outBuf, outPos, b, off, n);
            outPos += n;
            return n;
        }

        @Override public void close() throws IOException { raf.close(); }

        private static void inc32(byte[] c) {
            for (int i = 15; i >= 12; i--) { if (++c[i] != 0) break; }
        }

        private static byte[] xor(byte[] a, byte[] b) {
            byte[] r = new byte[16];
            for (int i = 0; i < 16; i++) r[i] = (byte) (a[i] ^ b[i]);
            return r;
        }

        /** Vermenigvuldigen in GF(2^128) zoals GCM het definieert. */
        private static byte[] gfMul(byte[] x, byte[] y) {
            byte[] z = new byte[16];
            byte[] v = y.clone();
            for (int i = 0; i < 128; i++) {
                if (((x[i >> 3] >> (7 - (i & 7))) & 1) != 0) for (int k = 0; k < 16; k++) z[k] ^= v[k];
                boolean lsb = (v[15] & 1) != 0;
                for (int k = 15; k > 0; k--) v[k] = (byte) (((v[k] & 0xff) >>> 1) | ((v[k - 1] & 1) << 7));
                v[0] = (byte) ((v[0] & 0xff) >>> 1);
                if (lsb) v[0] ^= (byte) 0xe1;
            }
            return z;
        }
    }

    /** Schrijft een backup (voor tests): het omgekeerde van decrypt. */
    static void encryptForTest(byte[] sqlite, byte[] rootKey, byte[] iv, File out) throws Exception {
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        try (java.util.zip.DeflaterOutputStream d = new java.util.zip.DeflaterOutputStream(z)) { d.write(sqlite); }
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(deriveKey(rootKey), "AES"), new javax.crypto.spec.GCMParameterSpec(128, iv));
        byte[] enc = c.doFinal(z.toByteArray()); // gegevens + tag
        ByteArrayOutputStream pb = new ByteArrayOutputStream();
        pb.write(0x08); pb.write(0x01);                 // key_type = 1
        pb.write(0x1a); pb.write(18); pb.write(0x0a); pb.write(16); pb.write(iv); // e2ee_key_data { encryption_iv }
        byte[] ver = "2.26.1.1".getBytes();
        pb.write(0x22); pb.write(ver.length + 2); pb.write(0x0a); pb.write(ver.length); pb.write(ver);
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.write(pb.size());
        all.write(pb.toByteArray());
        all.write(enc);
        byte[] md5 = MessageDigest.getInstance("MD5").digest(all.toByteArray());
        all.write(md5);
        try (FileOutputStream o = new FileOutputStream(out)) { o.write(all.toByteArray()); }
    }
}
