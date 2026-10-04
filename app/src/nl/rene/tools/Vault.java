package nl.rene.tools;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Versleuteld backup-archief (.rtb). Puur Java, zodat het ook op een gewone JVM getest kan worden.
 *
 * Opbouw: kop van 33 bytes = "RTB1" | kdf (1 = PBKDF2-HMAC-SHA256, 2 = PBKDF2-HMAC-SHA1) | iteraties (4, big-endian)
 * | zout (16) | nonce-begin (8). Daarna blokken van max. 64 kB: lengte (4) + AES-256-GCM-versleutelde gegevens (incl. 16 bytes tag).
 * Nonce per blok = nonce-begin + bloknummer (4). Extra geauthenticeerde gegevens = de kop + 1 byte (1 = laatste blok),
 * zodat een afgekapt of aangepast bestand altijd wordt opgemerkt.
 * Ontsleutelen kan ook zonder de app met ontsleutelen.html (Web Crypto) in de map Versleuteld.
 */
final class Vault {

    private Vault() { }

    static final byte[] MAGIC = {'R', 'T', 'B', '1'};
    static final int HEADER = 33, CHUNK = 64 * 1024, TAG = 16;
    static final int ITER = 210_000;

    /** Afgeleide sleutel met de bijbehorende instellingen (dezelfde voor alle archieven met hetzelfde wachtwoord). */
    static final class Key {
        final byte[] key; final byte[] salt; final int iter; final int kdf;
        Key(byte[] k, byte[] s, int i, int d) { key = k; salt = s; iter = i; kdf = d; }
    }

    static Key derive(char[] pw, byte[] salt, int iter, int kdf) throws Exception {
        String alg = kdf == 1 ? "PBKDF2WithHmacSHA256" : "PBKDF2WithHmacSHA1";
        PBEKeySpec spec = new PBEKeySpec(pw, salt, iter, 256);
        try { return new Key(SecretKeyFactory.getInstance(alg).generateSecret(spec).getEncoded(), salt, iter, kdf); }
        finally { spec.clearPassword(); }
    }

    /** Nieuwe sleutel met nieuw zout; SHA-256 als het toestel dat kent (Android 8+), anders SHA-1. */
    static Key create(char[] pw) throws Exception {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        try { return derive(pw, salt, ITER, 1); }
        catch (java.security.NoSuchAlgorithmException e) { return derive(pw, salt, ITER, 2); }
    }

    static void int32(byte[] b, int off, int v) { b[off] = (byte) (v >>> 24); b[off + 1] = (byte) (v >>> 16); b[off + 2] = (byte) (v >>> 8); b[off + 3] = (byte) v; }
    static int int32(byte[] b, int off) { return ((b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16) | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff); }

    static byte[] nonce(byte[] header, int n) {
        byte[] iv = new byte[12];
        System.arraycopy(header, 25, iv, 0, 8);
        int32(iv, 8, n);
        return iv;
    }

    static byte[] aad(byte[] header, boolean last) {
        byte[] a = Arrays.copyOf(header, HEADER + 1);
        a[HEADER] = (byte) (last ? 1 : 0);
        return a;
    }

    /** Schrijft versleuteld naar out. close() schrijft het laatste blok; zonder close() is het archief ongeldig. */
    static final class Out extends OutputStream {
        private final OutputStream out;
        private final byte[] header = new byte[HEADER];
        private final SecretKeySpec key;
        private final byte[] buf = new byte[CHUNK];
        private int len = 0, n = 0;
        private boolean closed = false, failed = false;

        Out(OutputStream o, Key k) throws IOException {
            out = o;
            key = new SecretKeySpec(k.key, "AES");
            System.arraycopy(MAGIC, 0, header, 0, 4);
            header[4] = (byte) k.kdf;
            int32(header, 5, k.iter);
            System.arraycopy(k.salt, 0, header, 9, 16);
            byte[] pre = new byte[8];
            new SecureRandom().nextBytes(pre);
            System.arraycopy(pre, 0, header, 25, 8);
            out.write(header);
        }

        private void chunk(boolean last) throws IOException {
            if (failed) throw new IOException("Versleutelen is eerder mislukt");
            failed = true;
            try {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG * 8, nonce(header, n++)));
                c.updateAAD(aad(header, last));
                byte[] ct = c.doFinal(buf, 0, len);
                byte[] l = new byte[4];
                int32(l, 0, ct.length);
                out.write(l);
                out.write(ct);
                len = 0;
                failed = false;
            } catch (IOException e) { throw e; }
            catch (Exception e) { throw new IOException("Versleutelen mislukt: " + e.getMessage()); }
        }

        @Override public void write(int b) throws IOException { if (failed) throw new IOException("Versleutelen is eerder mislukt"); buf[len++] = (byte) b; if (len == CHUNK) chunk(false); }

        @Override public void write(byte[] b, int off, int l) throws IOException {
            while (l > 0) {
                int k = Math.min(l, CHUNK - len);
                System.arraycopy(b, off, buf, len, k);
                len += k; off += k; l -= k;
                if (len == CHUNK) chunk(false);
            }
        }

        @Override public void flush() throws IOException { out.flush(); }

        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            try { chunk(true); out.flush(); } finally { out.close(); }
        }
    }

    /** Leest de kop; gooit een begrijpelijke fout als het geen archief van Rene's Tools is. */
    static byte[] readHeader(InputStream in) throws IOException {
        byte[] h = new byte[HEADER];
        try { readFully(in, h); } catch (EOFException e) { throw new IOException("Dit is geen versleutelde backup van Rene's Tools"); }
        for (int i = 0; i < 4; i++) if (h[i] != MAGIC[i]) throw new IOException("Dit is geen versleutelde backup van Rene's Tools");
        if (h[4] != 1 && h[4] != 2) throw new IOException("Onbekende versie van het archief");
        return h;
    }

    static final int MAX_ITER = 10_000_000;

    static Key keyFor(byte[] header, char[] pw) throws Exception {
        int it = int32(header, 5);
        if (it < 1000 || it > MAX_ITER) throw new IOException("Het archief is beschadigd");
        return derive(pw, Arrays.copyOfRange(header, 9, 25), int32(header, 5), header[4]);
    }

    /** Sleutel past bij dit archief (zelfde zout, iteraties en methode)? */
    static boolean matches(Key k, byte[] header) {
        return k != null && k.kdf == header[4] && k.iter == int32(header, 5) && Arrays.equals(k.salt, Arrays.copyOfRange(header, 9, 25));
    }

    static void readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int r = in.read(b, off, b.length - off);
            if (r < 0) throw new EOFException();
            off += r;
        }
    }

    /** Ontsleutelt blok voor blok. Fout wachtwoord → "Verkeerd wachtwoord"; afgekapt → "onvolledig". */
    static final class In extends InputStream {
        private final InputStream in;
        private final byte[] header;
        private final SecretKeySpec key;
        private byte[] plain = new byte[0];
        private int pos = 0, n = 0;
        private boolean done = false;

        In(InputStream src, byte[] hdr, Key k) {
            in = src;
            header = hdr;
            key = new SecretKeySpec(k.key, "AES");
        }

        private boolean next() throws IOException {
            if (done) return false;
            byte[] l = new byte[4];
            try { readFully(in, l); } catch (EOFException e) { throw new IOException("Het archief is onvolledig (afgebroken backup?)"); }
            int len = int32(l, 0);
            if (len < TAG || len > CHUNK + TAG) throw new IOException("Het archief is beschadigd");
            byte[] ct = new byte[len];
            try { readFully(in, ct); } catch (EOFException e) { throw new IOException("Het archief is onvolledig (afgebroken backup?)"); }
            int idx = n++;
            boolean last = len < CHUNK + TAG; // Out schrijft een vol blok nooit als laatste: daarna komt altijd een kleiner slotblok
            try {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG * 8, nonce(header, idx)));
                c.updateAAD(aad(header, last));
                plain = c.doFinal(ct);
                pos = 0;
                if (last) {
                    done = true;
                    if (in.read() >= 0) throw new IOException("Het archief is beschadigd (extra gegevens na het einde)");
                }
                return true;
            } catch (AEADBadTagException e) {
                throw new IOException(idx == 0 ? "Verkeerd wachtwoord" : "Het archief is beschadigd");
            } catch (Exception e) { throw new IOException("Ontsleutelen mislukt: " + e.getMessage()); }
        }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            int r = read(one, 0, 1);
            return r < 0 ? -1 : one[0] & 0xff;
        }

        @Override public int read(byte[] b, int off, int l) throws IOException {
            while (pos >= plain.length) if (!next()) return -1;
            int k = Math.min(l, plain.length - pos);
            System.arraycopy(plain, pos, b, off, k);
            pos += k;
            return k;
        }

        @Override public void close() throws IOException { in.close(); }
    }

    /** Hulpje voor tests: alles in het geheugen versleutelen. */
    static byte[] seal(byte[] data, Key k) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (Out o = new Out(b, k)) { o.write(data); }
        return b.toByteArray();
    }
}
