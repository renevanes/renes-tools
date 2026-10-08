package nl.rene.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Maakt een PDF van JPEG-plaatjes, één plaatje per pagina. Het JPEG-bestand gaat ongewijzigd de PDF in
 * (DCTDecode), dus geen kwaliteitsverlies en een klein bestand. Puur Java, zodat het op een gewone JVM te testen is.
 */
final class PdfWriter {

    /** Eén pagina: JPEG-bytes met afmetingen in pixels, de pagina in punten (1/72 inch), en de plek van het plaatje. */
    static final class Page {
        final byte[] jpeg; final int w, h; final boolean gray;
        final float pw, ph, x, y, iw, ih;
        Page(byte[] jpeg, int w, int h, boolean gray, float pw, float ph, float x, float y, float iw, float ih) {
            this.jpeg = jpeg; this.w = w; this.h = h; this.gray = gray; this.pw = pw; this.ph = ph; this.x = x; this.y = y; this.iw = iw; this.ih = ih;
        }
    }

    static final float A4W = 595.28f, A4H = 841.89f;

    /**
     * Plaatje op A4 (staand of liggend, net als het plaatje), passend binnen de marge en gecentreerd.
     * margin in punten (bijv. 28 ≈ 1 cm). fit = false: pagina even groot als het plaatje (bij 150 dpi).
     */
    static Page layout(byte[] jpeg, int w, int h, boolean gray, boolean a4, float margin) {
        if (!a4) {
            float pw = w * 72f / 150f, ph = h * 72f / 150f;
            return new Page(jpeg, w, h, gray, pw, ph, 0, 0, pw, ph);
        }
        boolean land = w > h;
        float pw = land ? A4H : A4W, ph = land ? A4W : A4H;
        float aw = pw - 2 * margin, ah = ph - 2 * margin;
        float s = Math.min(aw / w, ah / h);
        float iw = w * s, ih = h * s;
        return new Page(jpeg, w, h, gray, pw, ph, (pw - iw) / 2, (ph - ih) / 2, iw, ih);
    }

    static String num(float f) {
        String s = String.format(Locale.ROOT, "%.2f", f);
        return s.endsWith(".00") ? s.substring(0, s.length() - 3) : s;
    }

    /** Schrijft de PDF. */
    static void write(OutputStream out, List<Page> pages, String title) throws IOException {
        if (pages.isEmpty()) throw new IOException("Geen pagina's");
        Out o = new Out(out);
        o.ascii("%PDF-1.4\n%âãÏÓ\n");
        int n = pages.size();
        // Objecten: 1 catalogus, 2 paginaboom, 3 info, dan per pagina: pagina, inhoud, plaatje
        int first = 4;
        List<Long> offs = new ArrayList<>();
        offs.add(0L); // object 0
        offs.add(o.pos);
        o.ascii("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        offs.add(o.pos);
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < n; i++) kids.append(first + i * 3).append(" 0 R ");
        o.ascii("2 0 obj\n<< /Type /Pages /Count " + n + " /Kids [" + kids.toString().trim() + "] >>\nendobj\n");
        offs.add(o.pos);
        o.ascii("3 0 obj\n<< /Producer (Rene's Tools) /Title " + pdfString(title == null ? "" : title) + " >>\nendobj\n");
        for (int i = 0; i < n; i++) {
            Page p = pages.get(i);
            int po = first + i * 3, co = po + 1, io = po + 2;
            offs.add(o.pos);
            o.ascii(po + " 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + num(p.pw) + " " + num(p.ph) + "] /Resources << /XObject << /Im0 "
                    + io + " 0 R >> /ProcSet [/PDF /ImageC] >> /Contents " + co + " 0 R >>\nendobj\n");
            byte[] cs = ("q " + num(p.iw) + " 0 0 " + num(p.ih) + " " + num(p.x) + " " + num(p.y) + " cm /Im0 Do Q\n").getBytes(StandardCharsets.US_ASCII);
            offs.add(o.pos);
            o.ascii(co + " 0 obj\n<< /Length " + cs.length + " >>\nstream\n");
            o.bytes(cs);
            o.ascii("\nendstream\nendobj\n");
            offs.add(o.pos);
            o.ascii(io + " 0 obj\n<< /Type /XObject /Subtype /Image /Width " + p.w + " /Height " + p.h + " /ColorSpace /" + (p.gray ? "DeviceGray" : "DeviceRGB")
                    + " /BitsPerComponent 8 /Filter /DCTDecode /Length " + p.jpeg.length + " >>\nstream\n");
            o.bytes(p.jpeg);
            o.ascii("\nendstream\nendobj\n");
        }
        writeXref(o, offs, 1, 3);
        o.flush();
    }

    /** Kruisverwijzingstabel en trailer (offs.get(i) = plek van object i; object 0 is de vrije lijst). */
    static void writeXref(Out o, List<Long> offs, int root, int info) throws IOException {
        long xref = o.pos;
        StringBuilder b = new StringBuilder("xref\n0 " + offs.size() + "\n0000000000 65535 f \n");
        for (int i = 1; i < offs.size(); i++) b.append(String.format(Locale.ROOT, "%010d 00000 n \n", offs.get(i)));
        b.append("trailer\n<< /Size ").append(offs.size()).append(" /Root ").append(root).append(" 0 R");
        if (info > 0) b.append(" /Info ").append(info).append(" 0 R");
        b.append(" >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        o.ascii(b.toString());
    }

    /** Tekst als PDF-string (hex, UTF-16BE met BOM: werkt voor alle tekens). */
    static String pdfString(String s) {
        StringBuilder b = new StringBuilder("<FEFF");
        for (char ch : s.toCharArray()) b.append(String.format(Locale.ROOT, "%04X", (int) ch));
        return b.append('>').toString();
    }

    /** Uitvoer die bijhoudt waar hij is (voor de kruisverwijzingen). */
    static final class Out {
        final OutputStream out; long pos = 0;
        Out(OutputStream o) { out = o; }
        void ascii(String s) throws IOException { bytes(s.getBytes(StandardCharsets.ISO_8859_1)); }
        void bytes(byte[] b) throws IOException { out.write(b); pos += b.length; }
        void bytes(byte[] b, int off, int len) throws IOException { out.write(b, off, len); pos += len; }
        void flush() throws IOException { out.flush(); }
    }

    static byte[] toBytes(List<Page> pages, String title) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        write(b, pages, title);
        return b.toByteArray();
    }
}
