package nl.rene.tools;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.json.JSONObject;

/** Brief scannen: de maat uit de kop van een JPEG, alleen echte JPEG's, hooguit MAX_PAGINAS, en de pagina's als één PDF. */
public class KluisScanTest {
    static void check(boolean value, String why) { if (!value) throw new AssertionError(why); }

    static byte[] jpeg(int w, int h, int type) throws Exception {
        BufferedImage b = new BufferedImage(w, h, type);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        check(javax.imageio.ImageIO.write(b, "jpg", o), "testbeeld maken");
        return o.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        byte[] kleur = jpeg(1240, 1754, BufferedImage.TYPE_INT_RGB), grijs = jpeg(600, 1800, BufferedImage.TYPE_BYTE_GRAY);
        int[] m = KluisScan.maat(kleur);
        check(m != null && m[0] == 1240 && m[1] == 1754 && m[2] == 3, "maat en kanalen van een kleuren-JPEG: " + Arrays.toString(m));
        m = KluisScan.maat(grijs);
        check(m != null && m[0] == 600 && m[1] == 1800 && m[2] == 1, "maat van een grijze JPEG");

        // Geen JPEG, afgekapt of onzin: geen pagina
        check(KluisScan.pagina(null) == null, "niets");
        check(KluisScan.pagina("%PDF-1.4 hallo".getBytes(StandardCharsets.US_ASCII)) == null, "geen JPEG");
        check(KluisScan.pagina(Arrays.copyOf(kleur, 30)) == null, "afgekapt vóór de maat");
        check(KluisScan.pagina(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xDA, 0, 2}) == null, "beeldgegevens zonder maat");
        byte[] cmyk = kleur.clone();
        int sof = -1; for (int i = 2; i + 1 < cmyk.length; i++) if ((cmyk[i] & 0xff) == 0xFF && ((cmyk[i + 1] & 0xff) == 0xC0 || (cmyk[i + 1] & 0xff) == 0xC2)) { sof = i; break; }
        check(sof > 0, "SOF gevonden"); cmyk[sof + 9] = 4;
        check(KluisScan.pagina(cmyk) == null, "geen CMYK (PDF-lezers tonen dat verkeerd)");
        byte[] lang = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, (byte) 0xFF, (byte) 0xFF};
        check(KluisScan.maat(lang) == null, "een lengte voorbij het einde");
        check(KluisScan.pagina(new byte[KluisScan.MAX_BYTES + 1]) == null, "te groot");
        // Snel bij een kop vol opvulling (lineair)
        byte[] vol = new byte[3_000_000]; Arrays.fill(vol, (byte) 0xFF); vol[1] = (byte) 0xD8;
        long t = System.nanoTime(); check(KluisScan.maat(vol) == null, "alleen opvulling"); check(System.nanoTime() - t < 2_000_000_000L, "lineair");

        // Pagina's bijhouden in het geheugen, met een grens
        KluisScan.wis();
        check(KluisScan.voegToe(kleur).isEmpty() && KluisScan.voegToe(grijs).isEmpty(), "twee pagina's erbij");
        check(!KluisScan.voegToe(new byte[]{1, 2, 3}).isEmpty(), "onzin geweigerd");
        check(KluisScan.aantal() == 2, "twee pagina's");
        List<KluisScan.Pagina> l = KluisScan.neem();
        check(l.size() == 2 && KluisScan.aantal() == 0, "neem geeft ze en maakt leeg");
        for (int i = 0; i < KluisScan.MAX_PAGINAS; i++) check(KluisScan.voegToe(grijs).isEmpty(), "pagina " + i);
        check(!KluisScan.voegToe(grijs).isEmpty(), "niet meer dan MAX_PAGINAS");
        KluisScan.wis(); check(KluisScan.aantal() == 0, "wis maakt leeg");

        // A4 of eigen maat
        check(KluisScan.opA4(1240, 1754) && KluisScan.opA4(1754, 1240) && KluisScan.opA4(1300, 1754), "(bijna) A4, ook liggend");
        check(!KluisScan.opA4(600, 1800), "een bonnetje niet op A4");

        // De PDF: twee pagina's, de eerste op A4 zonder marge, de tweede zo groot als de scan; de JPEG's ongewijzigd erin
        byte[] pdf = KluisScan.pdf(l, "Brief");
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        check(s.startsWith("%PDF-1.4") && s.trim().endsWith("%%EOF"), "een PDF");
        check(s.contains("/Count 2"), "twee pagina's");
        check(s.contains("/MediaBox [0 0 595.28 841.89]"), "eerste pagina A4: " + s.substring(0, Math.min(800, s.length())));
        check(s.contains("/MediaBox [0 0 288 864]"), "tweede pagina 600×1800 bij 150 dpi");
        check(s.contains("/DeviceGray") && s.contains("/DeviceRGB"), "grijs blijft grijs, kleur kleur");
        check(s.contains(new String(kleur, StandardCharsets.ISO_8859_1)), "JPEG ongewijzigd in de PDF");
        boolean geweigerd = false;
        try { KluisScan.pdf(new ArrayList<>(), "x"); } catch (Exception verwacht) { geweigerd = true; }
        check(geweigerd, "lege brief geweigerd");

        // Herkomst "scan" in de kluisindex
        check("scan".equals(KluisIndex.meta(new JSONObject().put("source", "scan")).optString("source")), "herkomst scan blijft");
        System.out.println("KluisScanTest: alles goed");
    }
}
