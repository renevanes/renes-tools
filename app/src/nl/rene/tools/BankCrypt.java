package nl.rene.tools;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Versleuteling van Bankzaken. Puur Java (op een gewone JVM getest met een eigen sleutel); op de telefoon komt de sleutel
 * uit de beveiligde hardware (Bank.key()).
 *
 * Formaat: "RTBZ" + versie (1) + blokken van [12 bytes IV][4 bytes lengte][AES-256-GCM + tag]. Extra geauthenticeerde
 * gegevens per blok = "RTBZ" + bloknummer + "laatste blok": omwisselen, weglaten of afkappen valt altijd op.
 * De inhoud wordt eerst ingepakt (gzip), dan versleuteld.
 */
final class BankCrypt {

    private BankCrypt() { }

    static final byte[] MAGIC = {'R', 'T', 'B', 'Z'};
    static final int VERSION = 1, CHUNK = 512 * 1024, TAG = 16;
    static final int MAX_PLAIN = 120 * 1024 * 1024;

    static byte[] aad(int n, boolean last) {
        return new byte[]{'R', 'T', 'B', 'Z', (byte) (n >>> 24), (byte) (n >>> 16), (byte) (n >>> 8), (byte) n, (byte) (last ? 1 : 0)};
    }

    static byte[] gzip(byte[] plain) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream(Math.max(64, plain.length / 4));
        try (GZIPOutputStream z = new GZIPOutputStream(b)) { z.write(plain); }
        return b.toByteArray();
    }

    static byte[] gunzip(byte[] packed) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream(packed.length * 4);
        try (GZIPInputStream z = new GZIPInputStream(new ByteArrayInputStream(packed))) {
            byte[] buf = new byte[1 << 16]; int r;
            while ((r = z.read(buf)) > 0) { b.write(buf, 0, r); if (b.size() > MAX_PLAIN) throw new Exception("Gegevens zijn te groot"); }
        }
        return b.toByteArray();
    }

    static byte[] encrypt(byte[] plain, SecretKey k) throws Exception {
        byte[] data = gzip(plain);
        ByteArrayOutputStream raw = new ByteArrayOutputStream(data.length + 64);
        DataOutputStream out = new DataOutputStream(raw);
        out.write(MAGIC);
        out.writeByte(VERSION);
        int n = 0, off = 0;
        do {
            int len = Math.min(CHUNK, data.length - off);
            boolean last = off + len >= data.length;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, k); // de IV maakt de sleutel/het systeem zelf (willekeurig, nooit hergebruikt)
            c.updateAAD(aad(n, last));
            byte[] ct = c.doFinal(data, off, len);
            byte[] iv = c.getIV();
            if (iv == null || iv.length != 12) throw new Exception("Versleutelen lukt niet (onverwachte IV)");
            out.write(iv);
            out.writeInt(ct.length);
            out.write(ct);
            off += len; n++;
        } while (off < data.length);
        out.flush();
        return raw.toByteArray();
    }

    static byte[] decrypt(byte[] blob, SecretKey k) throws Exception {
        if (blob == null || blob.length < 5 + 12 + 4 + TAG) throw new Exception("Bankgegevens zijn beschadigd");
        if (!Arrays.equals(Arrays.copyOf(blob, 4), MAGIC)) throw new Exception("Geen bestand van Bankzaken");
        if (blob[4] != VERSION) throw new Exception("Onbekende versie van de bankgegevens");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(blob, 5, blob.length - 5));
        ByteArrayOutputStream out = new ByteArrayOutputStream(blob.length);
        int n = 0, left = blob.length - 5;
        while (true) {
            if (left < 12 + 4 + TAG) throw new Exception("Bankgegevens zijn beschadigd (afgekapt)");
            byte[] iv = new byte[12];
            in.readFully(iv);
            int len = in.readInt();
            left -= 16;
            if (len < TAG || len > CHUNK + TAG || len > left) throw new Exception("Bankgegevens zijn beschadigd");
            byte[] ct = new byte[len];
            in.readFully(ct);
            left -= len;
            boolean last = left == 0;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(TAG * 8, iv));
            c.updateAAD(aad(n, last));
            out.write(c.doFinal(ct)); // tag of volgorde klopt niet: fout, nooit iets halfs gebruiken
            if (last) break;
            n++;
        }
        return gunzip(out.toByteArray());
    }
}
