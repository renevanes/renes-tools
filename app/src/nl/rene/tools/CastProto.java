package nl.rene.tools;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Het berichtformaat van Chromecast (Cast v2): elk bericht is een "CastMessage" (protobuf) met een lengte van
 * 4 bytes ervoor. We gebruiken alleen tekstberichten (JSON). Puur Java, getest met CastProtoTest.
 *
 * CastMessage: 1 protocol_version (0), 2 source_id, 3 destination_id, 4 namespace, 5 payload_type (0 = tekst),
 * 6 payload_utf8, 7 payload_binary.
 */
final class CastProto {
    private CastProto() { }

    static final int MAX = 64 * 1024; // groter stuurt een Chromecast niet; groter = kapot of iets anders

    static final class Msg {
        String source = "", dest = "", ns = "", payload = "";
        Msg() { }
        Msg(String source, String dest, String ns, String payload) { this.source = source; this.dest = dest; this.ns = ns; this.payload = payload; }
    }

    static byte[] encode(Msg m) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        varintField(b, 1, 0);
        stringField(b, 2, m.source);
        stringField(b, 3, m.dest);
        stringField(b, 4, m.ns);
        varintField(b, 5, 0);
        stringField(b, 6, m.payload);
        return b.toByteArray();
    }

    static Msg decode(byte[] d) throws IOException {
        Msg m = new Msg();
        int i = 0;
        while (i < d.length) {
            long[] t = varint(d, i); i = (int) t[1];
            int field = (int) (t[0] >>> 3), wire = (int) (t[0] & 7);
            if (wire == 0) { long[] v = varint(d, i); i = (int) v[1]; }
            else if (wire == 2) {
                long[] l = varint(d, i); i = (int) l[1];
                int len = (int) l[0];
                if (len < 0 || i + len > d.length) throw new IOException("Kapot bericht");
                String s = new String(d, i, len, StandardCharsets.UTF_8);
                i += len;
                if (field == 2) m.source = s; else if (field == 3) m.dest = s; else if (field == 4) m.ns = s; else if (field == 6) m.payload = s;
            } else if (wire == 5) i += 4;
            else if (wire == 1) i += 8;
            else throw new IOException("Onbekend veld");
        }
        return m;
    }

    static void write(OutputStream out, Msg m) throws IOException {
        byte[] b = encode(m);
        byte[] all = new byte[4 + b.length];
        all[0] = (byte) (b.length >>> 24); all[1] = (byte) (b.length >>> 16); all[2] = (byte) (b.length >>> 8); all[3] = (byte) b.length;
        System.arraycopy(b, 0, all, 4, b.length);
        out.write(all); // in één keer (andere threads schrijven ook)
        out.flush();
    }

    static Msg read(InputStream in) throws IOException {
        DataInputStream d = new DataInputStream(in);
        int len = d.readInt();
        if (len < 0 || len > MAX) throw new IOException("Bericht te groot");
        byte[] b = new byte[len];
        d.readFully(b);
        return decode(b);
    }

    private static void varintField(ByteArrayOutputStream b, int field, long v) { putVarint(b, (field << 3)); putVarint(b, v); }

    private static void stringField(ByteArrayOutputStream b, int field, String s) {
        byte[] d = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        putVarint(b, (field << 3) | 2);
        putVarint(b, d.length);
        b.write(d, 0, d.length);
    }

    private static void putVarint(ByteArrayOutputStream b, long v) {
        while ((v & ~0x7FL) != 0) { b.write((int) ((v & 0x7F) | 0x80)); v >>>= 7; }
        b.write((int) v);
    }

    private static long[] varint(byte[] d, int i) throws IOException {
        long v = 0;
        int shift = 0;
        while (true) {
            if (i >= d.length || shift > 63) throw new IOException("Kapot getal");
            int x = d[i++] & 0xff;
            v |= (long) (x & 0x7f) << shift;
            if ((x & 0x80) == 0) return new long[]{v, i};
            shift += 7;
        }
    }

    /** Het soort bestand voor de Chromecast (die moet weten wat hij afspeelt). */
    static String contentType(String url, String hint, String codec) {
        String h = hint == null ? "" : hint.toLowerCase(java.util.Locale.ROOT), c = codec == null ? "" : codec.toLowerCase(java.util.Locale.ROOT);
        String u = url == null ? "" : url.toLowerCase(java.util.Locale.ROOT);
        if (h.startsWith("audio/") || h.startsWith("video/") || h.contains("mpegurl")) return h;
        if (c.contains("hls") || u.contains(".m3u8")) return "application/x-mpegurl";
        if (c.contains("aac") || u.contains(".aac")) return "audio/aac";
        if (c.contains("ogg") || u.contains(".ogg")) return "audio/ogg";
        if (u.contains(".m4a") || u.contains(".mp4")) return "audio/mp4";
        return "audio/mpeg";
    }
}
