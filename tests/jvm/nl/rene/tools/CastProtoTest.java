package nl.rene.tools;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/** Chromecast-berichten: heen en terug, lengte ervoor, kapotte invoer, soort bestand. */
public class CastProtoTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    public static void main(String[] a) throws Exception {
        CastProto.Msg m = new CastProto.Msg("sender-rt", "receiver-0", "urn:x-cast:com.google.cast.tp.connection", "{\"type\":\"CONNECT\",\"t\":\"Café ☕\"}");
        byte[] enc = CastProto.encode(m);
        // Bekende opbouw: veld 1 = 0 (08 00), veld 2 = "sender-rt" (12 09 …)
        check(enc[0] == 0x08 && enc[1] == 0x00 && enc[2] == 0x12 && enc[3] == 9, "protobuf-opbouw klopt");
        CastProto.Msg d = CastProto.decode(enc);
        check(d.source.equals(m.source) && d.dest.equals(m.dest) && d.ns.equals(m.ns) && d.payload.equals(m.payload), "heen en terug, met UTF-8");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CastProto.write(out, m);
        CastProto.write(out, new CastProto.Msg("a", "b", "c", "x".replace("x", new String(new char[300]).replace('\0', 'y')))); // lengte > 127: varint van 2 bytes
        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        CastProto.Msg r1 = CastProto.read(in), r2 = CastProto.read(in);
        check(r1.payload.equals(m.payload) && r2.payload.length() == 300 && r2.ns.equals("c"), "twee berichten achter elkaar (lengte ervoor)");
        // Onbekende velden (bijv. 7 = binair) worden overgeslagen
        byte[] extra = new byte[enc.length + 4];
        System.arraycopy(enc, 0, extra, 0, enc.length);
        extra[enc.length] = 0x3a; extra[enc.length + 1] = 2; extra[enc.length + 2] = 1; extra[enc.length + 3] = 2;
        check(CastProto.decode(extra).payload.equals(m.payload), "onbekend veld overgeslagen");
        boolean bad = false;
        try { CastProto.decode(new byte[]{0x32, 0x7f, 0x41}); } catch (java.io.IOException e) { bad = true; }
        check(bad, "te korte tekst geweigerd");
        bad = false;
        try { CastProto.read(new ByteArrayInputStream(new byte[]{0x7f, 0, 0, 0})); } catch (java.io.IOException e) { bad = true; }
        check(bad, "te groot bericht geweigerd");
        check(CastProto.contentType("https://x/a.mp3", "audio/mpeg", "").equals("audio/mpeg"), "soort uit de feed");
        check(CastProto.contentType("https://x/live.m3u8", "", "").equals("application/x-mpegurl"), "HLS herkend");
        check(CastProto.contentType("https://x/stream", "", "AAC+").equals("audio/aac"), "AAC uit de codec");
        check(CastProto.contentType("https://x/stream", "", "MP3").equals("audio/mpeg"), "standaard mp3");
        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
        System.out.println("Alle cast-tests geslaagd");
    }
}
