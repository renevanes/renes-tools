package nl.rene.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.DeflaterOutputStream;

/** Voegt transparante tekenlagen toe met een incrementele PDF-update; het origineel blijft byte voor byte behouden. */
final class PdfOverlay {
    private PdfOverlay() { }

    static final class Box {
        final double x, y, w, h, unit; final int rotation;
        Box(double x, double y, double w, double h, int rotation, double unit) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.rotation = rotation; this.unit = unit;
        }
        double width() { return (rotation % 180 == 0 ? w : h) * unit; }
        double height() { return (rotation % 180 == 0 ? h : w) * unit; }
        // PDF-plaatjes hebben hun oorsprong linksonder; de PNG-rijen lopen van boven naar beneden.
        double[] matrix() {
            switch (rotation) {
                case 90: return new double[]{0, h, -w, 0, x + w, y};
                case 180: return new double[]{-w, 0, 0, -h, x + w, y + h};
                case 270: return new double[]{0, -h, w, 0, x, y + h};
                default: return new double[]{w, 0, 0, h, x, y};
            }
        }
    }

    static Box box(PdfSplit pdf, int page) throws IOException {
        if (page < 0 || page >= pdf.pageCount()) throw new IOException("Pagina bestaat niet");
        PdfSplit.Dict d = pdf.pages.get(page);
        double[] media = rect(pdf, d.get("MediaBox"));
        double[] crop = d.get("CropBox") == null ? media : rect(pdf, d.get("CropBox"));
        double x = Math.max(media[0], crop[0]), y = Math.max(media[1], crop[1]);
        double w = Math.min(media[2], crop[2]) - x, h = Math.min(media[3], crop[3]) - y;
        double rot = number(pdf, d.get("Rotate"), 0), unit = number(pdf, d.get("UserUnit"), 1);
        if (w <= 0 || h <= 0 || unit <= 0 || unit > 75000 || rot % 90 != 0 || Math.abs(rot) > 360000)
            throw new IOException("Deze pagina heeft een niet ondersteund formaat");
        return new Box(x, y, w, h, ((int) rot % 360 + 360) % 360, unit);
    }
    static Box editableBox(PdfSplit pdf, int page) throws IOException {
        Box box = box(pdf, page);
        Object annotations = pdf.resolve(pdf.pages.get(page).get("Annots"));
        // Annotaties/widgets worden door lezers bóven pagina-inhoud getekend, dus kunnen toevoegingen bedekken.
        if (annotations != null && annotations != PdfSplit.NULL && (!(annotations instanceof List) || !((List<?>) annotations).isEmpty()))
            throw new IOException("Deze pagina bevat annotaties of formuliervelden. Maak eerst via Afdrukken → Opslaan als PDF een vlakke kopie en bewerk die.");
        return box;
    }
    private static double[] rect(PdfSplit pdf, Object o) throws IOException {
        Object r = pdf.resolve(o);
        if (!(r instanceof List) || ((List<?>) r).size() != 4) throw new IOException("Paginaformaat ontbreekt");
        double[] v = new double[4];
        for (int i = 0; i < 4; i++) v[i] = number(pdf, ((List<?>) r).get(i), Double.NaN);
        if (v[0] >= v[2] || v[1] >= v[3]) throw new IOException("Ongeldig paginaformaat");
        return v;
    }
    private static double number(PdfSplit p, Object o, double fallback) throws IOException {
        if (o == null) { if (Double.isNaN(fallback)) throw new IOException("Paginaformaat ontbreekt"); return fallback; }
        Object r = p.resolve(o);
        double n = r instanceof PdfSplit.Num ? ((PdfSplit.Num) r).v() : Double.NaN;
        if (Double.isNaN(n) || Double.isInfinite(n) || Math.abs(n) > 1e9) throw new IOException("Ongeldig paginaformaat");
        return n;
    }

    static final class Image {
        final int w, h; final byte[] rgb, alpha;
        Image(int w, int h, int[] argb) throws IOException {
            if (w < 1 || h < 1 || w > 4096 || h > 4096 || (long) w * h > 6_000_000 || argb.length != w * h)
                throw new IOException("De tekenlaag is te groot");
            this.w = w; this.h = h;
            ByteArrayOutputStream colors = new ByteArrayOutputStream(), mask = new ByteArrayOutputStream();
            try (DeflaterOutputStream c = new DeflaterOutputStream(colors); DeflaterOutputStream a = new DeflaterOutputStream(mask)) {
                byte[] row = new byte[w * 3], al = new byte[w];
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int p = argb[y * w + x];
                        row[x * 3] = (byte) (p >> 16); row[x * 3 + 1] = (byte) (p >> 8); row[x * 3 + 2] = (byte) p; al[x] = (byte) (p >>> 24);
                    }
                    c.write(row); a.write(al);
                }
            }
            rgb = colors.toByteArray(); alpha = mask.toByteArray();
        }
    }

    /** Een kapotte xref niet stilzwijgend als geldige basis voor een incrementele update gebruiken. */
    static long previous(PdfSplit pdf) throws IOException {
        try {
            if (pdf.repaired) throw new IOException();
            int at = pdf.lastIndexOf("startxref");
            if (at < 0) throw new IOException();
            pdf.p = at + 9; long off = Long.parseLong(pdf.token());
            long current = off;
            java.util.Set<Long> seen = new java.util.HashSet<>();
            while (true) {
                if (current <= 0 || current >= pdf.d.length || !seen.add(current) || seen.size() > 1000) throw new IOException();
                PdfSplit.Dict trailer = pdf.readXrefAt(current);
                Object hybrid = trailer.get("XRefStm");
                if (hybrid != null) {
                    pdf.hybridXref = true;
                    long companion = offset(hybrid, pdf.d.length);
                    if (companion == current) throw new IOException();
                    pdf.readXrefAt(companion);
                }
                Object prev = trailer.get("Prev");
                if (prev == null) break;
                current = offset(prev, pdf.d.length);
            }
            for (PdfSplit.Ref ref : pdf.pageRefs) if (ref == null) throw new IOException();
            return off;
        } catch (Exception e) { throw new IOException("Deze PDF kan niet veilig bewerkt worden. Bewaar hem eerst opnieuw als PDF."); }
    }
    private static long offset(Object o, int length) throws IOException {
        if (!(o instanceof PdfSplit.Num)) throw new IOException();
        double value = ((PdfSplit.Num) o).v();
        if (Double.isNaN(value) || value <= 0 || value >= length || value != Math.floor(value)) throw new IOException();
        return (long) value;
    }

    static void write(PdfSplit pdf, Map<Integer, Image> images, OutputStream output) throws IOException {
        if (images.isEmpty() || images.size() > 30) throw new IOException("Kies 1 tot 30 pagina's om te bewerken");
        long prev = previous(pdf);
        int next = Math.max(1, pdf.trailer.get("Size") instanceof PdfSplit.Num ? ((PdfSplit.Num) pdf.trailer.get("Size")).i() : 1);
        for (int n : pdf.offsets.keySet()) next = Math.max(next, n + 1);
        for (int n : pdf.inStream.keySet()) next = Math.max(next, n + 1);
        if (next < 1 || next > 2_000_000) throw new IOException("Te veel PDF-objecten");
        TreeMap<Integer, Object> updates = new TreeMap<>();
        Map<Integer, Integer> generations = new java.util.HashMap<>();
        // Sommige lezers volgen de XRefStm van een oudere hybride tabel niet na een gewone nieuwe xref.
        // Materialiseer die objecten in de nieuwe sectie, met dezelfde nummers en inhoud.
        if (pdf.hybridXref) for (int n : pdf.inStream.keySet()) {
            Object value = pdf.get(n);
            if (value == PdfSplit.NULL) throw new IOException("Deze PDF bevat onleesbare objecten");
            updates.put(n, value);
        }
        for (Map.Entry<Integer, Image> edit : images.entrySet()) {
            int pageIndex = edit.getKey(); Box b = editableBox(pdf, pageIndex); Image im = edit.getValue();
            if (Math.abs((double) im.w / im.h / (b.width() / b.height()) - 1) > .02) throw new IOException("De tekenlaag past niet bij de pagina");
            PdfSplit.Dict page = cloneDict(pdf, pdf.pages.get(pageIndex));
            PdfSplit.Dict resources = cloneDict(pdf, page.get("Resources"));
            PdfSplit.Dict objects = cloneDict(pdf, resources.get("XObject"));
            String name = "RTEdit"; while (objects.m.containsKey(name)) name += "X";
            int mask = next++, image = next++, before = next++, after = next++;
            PdfSplit.Dict md = imageDict(im.w, im.h, "DeviceGray"), id = imageDict(im.w, im.h, "DeviceRGB");
            id.m.put("SMask", new PdfSplit.Ref(mask, 0));
            updates.put(mask, stream(md, im.alpha)); updates.put(image, stream(id, im.rgb));
            objects.m.put(name, new PdfSplit.Ref(image, 0)); resources.m.put("XObject", objects); page.m.put("Resources", resources);
            updates.put(before, stream(new PdfSplit.Dict(), "q\n".getBytes(StandardCharsets.US_ASCII)));
            StringBuilder command = new StringBuilder("Q\nq\n");
            for (double v : b.matrix()) command.append(String.format(java.util.Locale.ROOT, "%.6f ", v));
            command.append("cm /").append(name).append(" Do\nQ\n");
            updates.put(after, stream(new PdfSplit.Dict(), command.toString().getBytes(StandardCharsets.US_ASCII)));
            List<Object> contents = new ArrayList<>(); contents.add(new PdfSplit.Ref(before, 0));
            Object old = page.get("Contents"), resolved = pdf.resolve(old);
            if (resolved instanceof List) contents.addAll((List<?>) resolved);
            else if (resolved instanceof PdfSplit.Stream) contents.add(old);
            else if (old != null && resolved != PdfSplit.NULL) throw new IOException("Pagina-inhoud is niet ondersteund");
            contents.add(new PdfSplit.Ref(after, 0)); page.m.put("Contents", contents);
            PdfSplit.Ref ref = pdf.pageRefs.get(pageIndex); updates.put(ref.num, page); generations.put(ref.num, ref.gen);
        }
        // Transparantie vanaf PDF 1.4; de catalogus blijft verder behouden, ook formulieren en metadata.
        Object rootRef = pdf.trailer.get("Root");
        if (!(rootRef instanceof PdfSplit.Ref)) throw new IOException("PDF-catalogus ontbreekt");
        PdfSplit.Ref root = (PdfSplit.Ref) rootRef; PdfSplit.Dict catalog = cloneDict(pdf, root);
        catalog.m.put("Version", new PdfSplit.Name("1.7")); updates.put(root.num, catalog); generations.put(root.num, root.gen);
        PdfWriter.Out out = new PdfWriter.Out(output); out.bytes(pdf.d); out.ascii("\n");
        Map<Integer, Long> offsets = new TreeMap<>();
        for (Map.Entry<Integer, Object> e : updates.entrySet()) {
            int gen = generations.containsKey(e.getKey()) ? generations.get(e.getKey()) : 0;
            offsets.put(e.getKey(), out.pos); out.ascii(e.getKey() + " " + gen + " obj\n");
            Object o = e.getValue();
            if (o instanceof PdfSplit.Stream) {
                PdfSplit.Stream s = (PdfSplit.Stream) o; PdfSplit.ser(out, s.dict); out.ascii("\nstream\n"); out.bytes(s.src); out.ascii("\nendstream");
            } else PdfSplit.ser(out, o);
            out.ascii("\nendobj\n");
        }
        long xref = out.pos; out.ascii("xref\n");
        for (Map.Entry<Integer, Long> e : offsets.entrySet()) out.ascii(e.getKey() + " 1\n" + String.format(java.util.Locale.ROOT, "%010d %05d n \n", e.getValue(), generations.containsKey(e.getKey()) ? generations.get(e.getKey()) : 0));
        PdfSplit.Dict trailer = new PdfSplit.Dict();
        for (String key : new String[]{"Root", "Info", "ID"}) if (pdf.trailer.get(key) != null) trailer.m.put(key, pdf.trailer.get(key));
        trailer.m.put("Size", new PdfSplit.Num(String.valueOf(next))); trailer.m.put("Prev", new PdfSplit.Num(String.valueOf(prev)));
        out.ascii("trailer\n"); PdfSplit.ser(out, trailer); out.ascii("\nstartxref\n" + xref + "\n%%EOF\n"); out.flush();
    }
    private static PdfSplit.Dict cloneDict(PdfSplit p, Object o) throws IOException {
        Object r = p.resolve(o); PdfSplit.Dict d = new PdfSplit.Dict();
        if (r instanceof PdfSplit.Dict) d.m.putAll(((PdfSplit.Dict) r).m);
        else if (o != null && r != PdfSplit.NULL) throw new IOException("PDF-woordenlijst is beschadigd");
        return d;
    }
    private static PdfSplit.Dict imageDict(int w, int h, String space) {
        PdfSplit.Dict d = new PdfSplit.Dict();
        d.m.put("Type", new PdfSplit.Name("XObject")); d.m.put("Subtype", new PdfSplit.Name("Image")); d.m.put("ColorSpace", new PdfSplit.Name(space));
        d.m.put("Width", new PdfSplit.Num("" + w)); d.m.put("Height", new PdfSplit.Num("" + h)); d.m.put("BitsPerComponent", new PdfSplit.Num("8")); d.m.put("Filter", new PdfSplit.Name("FlateDecode"));
        return d;
    }
    private static PdfSplit.Stream stream(PdfSplit.Dict d, byte[] data) { d.m.put("Length", new PdfSplit.Num("" + data.length)); return new PdfSplit.Stream(d, data, 0, data.length); }
}
