package nl.rene.tools;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * PDF's maken en splitsen. Met argumenten (bestanden) alleen: elk bestand openen en elke pagina apart uitschrijven
 * naar build/pdftest/ (voor controle met qpdf/pdfinfo buiten de test).
 */
public class PdfTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }
    static byte[] read(String p) throws Exception { return Files.readAllBytes(new File(p).toPath()); }

    public static void main(String[] a) throws Exception {
        if (a.length > 0) { stress(a); return; }
        String dir = "tests/jvm/pdf/";
        for (String f : new String[]{"gewoon.pdf", "compact.pdf", "kapot.pdf"}) {
            PdfSplit s = new PdfSplit(read(dir + f));
            check(s.pageCount() == 5, f + ": 5 pagina's (" + s.pageCount() + ")");
            byte[] out = s.extract(Arrays.asList(4, 0));
            PdfSplit o = new PdfSplit(out);
            check(o.pageCount() == 2, f + ": uittreksel heeft 2 pagina's");
            // inhoud van de pagina is ongewijzigd (zelfde stream-bytes)
            check(Arrays.equals(contents(s, 4), contents(o, 0)) && Arrays.equals(contents(s, 0), contents(o, 1)), f + ": pagina-inhoud byte voor byte gelijk, volgorde 5,1");
            check(new String(out, "ISO-8859-1").contains("/Parent"), f + ": nieuwe paginaboom");
            Files.write(new File("build/pdftest-" + f).toPath(), out);
        }
        // Liggende pagina blijft liggend (MediaBox)
        PdfSplit g = new PdfSplit(read(dir + "gewoon.pdf"));
        PdfSplit p3 = new PdfSplit(g.extract(Arrays.asList(2)));
        Object mb = p3.resolve(p3.pages.get(0).get("MediaBox"));
        check(mb instanceof List && ((PdfSplit.Num) p3.resolve(((List<?>) mb).get(2))).v() > 800, "liggende pagina blijft liggend");
        // Beveiligd
        boolean enc = false;
        try { new PdfSplit(read(dir + "beveiligd.pdf")); } catch (PdfSplit.Encrypted e) { enc = true; }
        check(enc, "beveiligde PDF wordt herkend en geweigerd");
        boolean notPdf = false;
        try { new PdfSplit("hallo".getBytes()); } catch (PdfSplit.PdfException e) { notPdf = true; }
        check(notPdf, "geen PDF wordt geweigerd");
        // Hybride xref (tabel + XRefStm, zoals Word maakt): objecten in een object-stream worden gevonden
        PdfSplit h = new PdfSplit(read(dir + "hybride.pdf"));
        check(h.pageCount() == 1 && new PdfSplit(h.extract(Arrays.asList(0))).pageCount() == 1, "hybride xref: catalogus in object-stream gevonden");
        // Kwaadaardige xref met 2 miljard regels: snel geweigerd of hersteld, niet vastlopen
        long t0 = System.currentTimeMillis();
        try { new PdfSplit(read(dir + "grote-xref.pdf")); } catch (PdfSplit.PdfException e) { }
        check(System.currentTimeMillis() - t0 < 3000, "enorme xref-telling loopt niet vast");
        // Paginabereik
        check(PdfSplit.parseRange("1-3, 5", 10).equals(Arrays.asList(0, 1, 2, 4)), "bereik 1-3, 5");
        check(PdfSplit.parseRange("1 - 3", 10).equals(Arrays.asList(0, 1, 2)), "bereik met spaties 1 - 3");
        check(PdfSplit.parseRange("8-", 10).equals(Arrays.asList(7, 8, 9)), "bereik 8- tot het einde");
        check(PdfSplit.parseRange("0", 10) == null && PdfSplit.parseRange("3-1", 10) == null && PdfSplit.parseRange("11", 10) == null && PdfSplit.parseRange("x", 10) == null, "ongeldige bereiken geweigerd");
        // Plaatjes → PDF
        byte[] jpg = read(dir + "klein.jpg");
        List<PdfWriter.Page> pages = new ArrayList<>();
        pages.add(PdfWriter.layout(jpg, 40, 30, false, true, 28));
        pages.add(PdfWriter.layout(jpg, 40, 30, false, false, 0));
        byte[] pdf = PdfWriter.toBytes(pages, "Test €");
        Files.write(new File("build/pdftest-plaatjes.pdf").toPath(), pdf);
        PdfSplit back = new PdfSplit(pdf);
        check(back.pageCount() == 2, "plaatjes-PDF heeft 2 pagina's");
        check(indexOf(pdf, jpg) > 0, "JPEG ongewijzigd in de PDF");
        PdfWriter.Page l = PdfWriter.layout(jpg, 4000, 3000, false, true, 28);
        check(l.pw > l.ph && l.iw <= l.pw - 56 + 0.01 && Math.abs(l.x - (l.pw - l.iw) / 2) < 0.01, "breed plaatje: liggende A4, gecentreerd binnen de marge");
        System.out.println(fails == 0 ? "Alle PDF-tests geslaagd" : fails + " PDF-tests mislukt");
        System.exit(fails == 0 ? 0 : 1);
    }

    static byte[] contents(PdfSplit s, int page) throws Exception {
        Object c = s.resolve(s.pages.get(page).get("Contents"));
        if (c instanceof List) c = s.resolve(((List<?>) c).get(0));
        return ((PdfSplit.Stream) c).raw();
    }

    static int indexOf(byte[] h, byte[] n) {
        outer: for (int i = 0; i <= h.length - n.length; i++) { for (int k = 0; k < n.length; k++) if (h[i + k] != n[k]) continue outer; return i; }
        return -1;
    }

    static void stress(String[] files) throws Exception {
        new File("build/pdfstress").mkdirs();
        for (String f : files) {
            try {
                PdfSplit s = new PdfSplit(read(f));
                int n = s.pageCount();
                List<Integer> all = new ArrayList<>();
                for (int i = 0; i < n; i++) all.add(i);
                byte[] out = s.extract(Arrays.asList(n - 1, 0));
                String name = new File(f).getName();
                Files.write(new File("build/pdfstress/" + name).toPath(), out);
                System.out.println("✓ " + name + ": " + n + " pagina's, uittreksel " + out.length + " bytes (origineel " + new File(f).length() + ")");
            } catch (Exception e) { System.out.println("✗ " + f + ": " + e); }
        }
    }
}
