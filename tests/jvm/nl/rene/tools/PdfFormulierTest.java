package nl.rene.tools;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/** De werkelijk meegeleverde sjablonen moeten zonder verlies door dezelfde export als de editor kunnen. */
public final class PdfFormulierTest {
    public static void main(String[] args) throws Exception {
        for (String bad : new String[]{"../reistoestemming", "", "/etc/passwd", "reistoestemming.pdf", "https://example.org"}) {
            boolean rejected = false;
            try { PdfFormulier.asset(bad); } catch (Exception e) { rejected = true; }
            if (!rejected) throw new AssertionError("Ongeldig formulier-id geaccepteerd");
        }
        File[] files = new File("app/formulieren").listFiles((dir, name) -> name.endsWith(".pdf"));
        if (files == null || files.length == 0) throw new AssertionError("Geen formulier meegeleverd");
        for (File file : files) {
            byte[] bytes = Files.readAllBytes(file.toPath()); PdfSplit pdf = new PdfSplit(bytes);
            if (file.getName().equals("reistoestemming.pdf") && pdf.pageCount() != 2) throw new AssertionError("Beide pagina's vereist");
            PdfOverlay.previous(pdf);
            Map<Integer, PdfOverlay.Image> layers = new TreeMap<>();
            for (int page = 0; page < pdf.pageCount(); page++) {
                PdfOverlay.Box box = PdfOverlay.editableBox(pdf, page);
                if (Math.abs(box.width() - 595.276) > .001 || Math.abs(box.height() - 841.89) > .001) throw new AssertionError("Veldposities passen niet bij het sjabloon");
                int width = 595, height = 842;
                int[] pixels = new int[width * height]; pixels[200 * width + 208] = 0xFF000000;
                layers.put(page, new PdfOverlay.Image(width, height, pixels));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(); PdfOverlay.write(pdf, layers, out);
            byte[] result = out.toByteArray();
            if (!Arrays.equals(bytes, Arrays.copyOf(result, bytes.length))) throw new AssertionError("Bron niet intact");
            PdfSplit filled = new PdfSplit(result);
            if (filled.pageCount() != pdf.pageCount()) throw new AssertionError("Pagina verloren");
            for (int page = 0; page < filled.pageCount(); page++) PdfOverlay.editableBox(filled, page);
            Files.write(new File("build/formulier-" + file.getName()).toPath(), result);
        }
        System.out.println("Alle formulier-id's en echte PDF-sjablonen gecontroleerd");
    }
}
