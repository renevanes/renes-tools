package nl.rene.tools;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Brief scannen in de Documentenkluis: de interface maakt de foto's met de camera van de app, zet ze recht en maakt er een
 * scan van (49c-brief-scannen.js); hier komen de pagina's als JPEG binnen, alleen in het geheugen, en worden ze één PDF die
 * meteen versleuteld de kluis in gaat. Geen bestand op schijf, niets in de galerij of onder Gemaakt.
 * Het zuivere deel (JPEG-maat, PDF maken, pagina's bijhouden) is zonder Android te testen (KluisScanTest).
 */
final class KluisScan {
    private KluisScan() { }

    /** Hooguit zoveel pagina's in één brief, en zo groot per pagina (een scan is ±0,2–1 MB). */
    static final int MAX_PAGINAS = 30, MAX_BYTES = 6_000_000;
    /** Een pagina met (bijna) de verhouding van A4 komt op A4; een andere (een bonnetje) krijgt zijn eigen maat. */
    static final double A4 = 841.89 / 595.28, SPELING = 0.12;

    /** Eén pagina: de JPEG en zijn maat in pixels. */
    static final class Pagina {
        final byte[] jpeg; final int w, h; final boolean grijs;
        Pagina(byte[] jpeg, int w, int h, boolean grijs) { this.jpeg = jpeg; this.w = w; this.h = h; this.grijs = grijs; }
    }

    private static final List<Pagina> PAGINAS = new ArrayList<>();

    /** Opnieuw beginnen (en na bewaren of annuleren): de pagina's uit het geheugen. */
    static void wis() { synchronized (PAGINAS) { PAGINAS.clear(); } }
    static int aantal() { synchronized (PAGINAS) { return PAGINAS.size(); } }

    /** Een pagina erbij; "" als het lukte, anders de reden (Nederlands, vertaald door de brug). */
    static String voegToe(byte[] jpeg) {
        Pagina p = pagina(jpeg);
        if (p == null) return Taal.T("Deze foto is niet te lezen");
        synchronized (PAGINAS) {
            if (PAGINAS.size() >= MAX_PAGINAS) return Taal.T("Te veel pagina's in één brief");
            PAGINAS.add(p);
        }
        return "";
    }

    /** De pagina's tot nu toe, en daarna leeg (wat de PDF wordt). */
    static List<Pagina> neem() { synchronized (PAGINAS) { List<Pagina> l = new ArrayList<>(PAGINAS); PAGINAS.clear(); return l; } }

    /** Een JPEG controleren en zijn maat lezen; null als het geen bruikbare JPEG is. */
    static Pagina pagina(byte[] b) {
        if (b == null || b.length < 4 || b.length > MAX_BYTES) return null;
        int[] m = maat(b);
        if (m == null || m[0] < 16 || m[1] < 16 || m[0] > 10000 || m[1] > 10000) return null;
        return new Pagina(b, m[0], m[1], m[2] == 1);
    }

    /** Breedte, hoogte en aantal kleurkanalen uit de kop van een JPEG (SOF-marker), of null. Lineair, zonder het beeld te lezen. */
    static int[] maat(byte[] b) {
        if (b == null || b.length < 4 || (b[0] & 0xff) != 0xFF || (b[1] & 0xff) != 0xD8) return null;
        int i = 2;
        while (i + 3 < b.length) {
            if ((b[i] & 0xff) != 0xFF) return null;
            int m = b[i + 1] & 0xff;
            if (m == 0xFF) { i++; continue; } // opvulling
            if (m == 0xD8 || m == 0x01 || (m >= 0xD0 && m <= 0xD7)) { i += 2; continue; } // zonder lengte
            if (m == 0xD9 || m == 0xDA) return null; // einde of beeldgegevens vóór een SOF
            int len = ((b[i + 2] & 0xff) << 8) | (b[i + 3] & 0xff);
            if (len < 2 || i + 2 + len > b.length) return null;
            boolean sof = m >= 0xC0 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC;
            if (sof) {
                if (len < 8) return null;
                int h = ((b[i + 5] & 0xff) << 8) | (b[i + 6] & 0xff), w = ((b[i + 7] & 0xff) << 8) | (b[i + 8] & 0xff), n = b[i + 9] & 0xff;
                if (n != 1 && n != 3) return null; // geen CMYK: PDF-lezers tonen dat verkeerd
                return new int[]{w, h, n};
            }
            i += 2 + len;
        }
        return null;
    }

    /** De pagina's als PDF: op A4 zonder marge als de verhouding erbij past, anders even groot als de scan (150 dpi). */
    static byte[] pdf(List<Pagina> paginas, String titel) throws Exception {
        if (paginas == null || paginas.isEmpty()) throw new Exception(Taal.T("Nog geen pagina's"));
        List<PdfWriter.Page> pages = new ArrayList<>();
        for (Pagina p : paginas) pages.add(PdfWriter.layout(p.jpeg, p.w, p.h, p.grijs, opA4(p.w, p.h), 0));
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        PdfWriter.write(o, pages, titel == null ? "Brief" : titel);
        return o.toByteArray();
    }

    static boolean opA4(int w, int h) {
        double r = (double) Math.max(w, h) / Math.max(1, Math.min(w, h));
        return Math.abs(r - A4) / A4 <= SPELING;
    }
}
