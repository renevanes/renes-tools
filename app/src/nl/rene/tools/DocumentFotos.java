package nl.rene.tools;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Een document omzetten naar losse foto's (JPEG), op de telefoon. Wens van Rene: eerst zien welke foto's er in een document
 * staan (een goede controle), en die foto's daarna gebruiken. Gebruikt door de PDF-tool "Foto's uit PDF" en door
 * Stamgegevens (een ID-bewijs uit een document).
 * <ul>
 * <li>PDF: per pagina de ingesloten foto's op volle grootte (JPEG zoals ze in de PDF staan); een pagina zonder bruikbare foto
 * (tekst, tekening, of een foto die Android niet kan openen) wordt zelf één foto.</li>
 * <li>Word (.docx, ook een Google-document dat als Word is opgehaald): de foto's uit het document, in volgorde.</li>
 * <li>Een foto (JPEG, PNG, …): die foto zelf.</li>
 * </ul>
 * Kleine plaatjes (logo's, pictogrammen) tellen niet. Alles blijft in het geheugen; alleen PdfRenderer krijgt even een
 * tijdelijk bestand dat meteen weer weg is (zoekbare descriptor).
 */
final class DocumentFotos {
    private DocumentFotos() { }

    /** Kleinste zijde van een foto die meetelt (een logo, pictogram of miniatuur niet). */
    static final int MIN_ZIJDE = 300;
    /** Een pagina als foto: langste zijde in pixels (scherp genoeg voor de strook en kleine letters). */
    static final int PAGINA_ZIJDE = 2400;
    private static final int MAX_UITGEPAKT = 60 * 1024 * 1024, MAX_ZIP_ITEMS = 512;

    static final class Foto {
        final byte[] jpeg;
        final int breed, hoog, pagina;
        /** true = een hele pagina als foto (er kan meer op staan dan één foto). */
        final boolean heleBladzijde;
        Foto(byte[] jpeg, int breed, int hoog, int pagina, boolean heleBladzijde) {
            this.jpeg = jpeg; this.breed = breed; this.hoog = hoog; this.pagina = pagina; this.heleBladzijde = heleBladzijde;
        }
    }

    static final class Uitkomst {
        final List<Foto> fotos = new ArrayList<>();
        /** pdf, word of foto. */
        String soort = "";
        int paginas;
        /** Meer foto's of pagina's dan de grens: de rest is overgeslagen. */
        boolean afgekapt;
        java.util.function.Consumer<Foto> elk;
        void neem(Foto f) {
            if (elk == null) { fotos.add(f); return; }
            elk.accept(f);
            fotos.add(new Foto(null, f.breed, f.hoog, f.pagina, f.heleBladzijde));
        }
    }

    static boolean pdf(byte[] b) { return b != null && b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-'; }
    static boolean zip(byte[] b) { return b != null && b.length >= 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4; }

    /**
     * Zet het document om. max: hoeveel foto's hooguit (de rest valt af, met afgekapt). active: stoppen als dat onwaar wordt.
     * IOException als het document niet te openen is.
     */
    static Uitkomst zet(Context c, byte[] bytes, int max, BooleanSupplier active) throws IOException { return zet(c, bytes, max, active, null); }
    /**
     * elk (mag null): krijgt elke foto meteen, en de uitkomst houdt dan alleen de maten bij (jpeg null). Zo staan bij een grote
     * PDF niet alle foto's tegelijk in het geheugen (Foto's uit PDF schrijft ze meteen weg).
     */
    static Uitkomst zet(Context c, byte[] bytes, int max, BooleanSupplier active, java.util.function.Consumer<Foto> elk) throws IOException {
        Uitkomst u = new Uitkomst();
        u.elk = elk;
        if (pdf(bytes)) { u.soort = "pdf"; pdfFotos(c, bytes, max, active, u); }
        else if (zip(bytes)) { u.soort = "word"; wordFotos(bytes, max, active, u); }
        else {
            u.soort = "foto";
            Foto f = alsJpeg(bytes, 0, false);
            if (f == null) throw new IOException("geen foto");
            u.neem(f);
        }
        return u;
    }

    // ---------- PDF ----------

    private static void pdfFotos(Context c, byte[] bytes, int max, BooleanSupplier active, Uitkomst u) throws IOException {
        PdfSplit split = null;
        try { split = new PdfSplit(bytes); } catch (Throwable e) { split = null; } // dan alleen de pagina's als foto
        try (ParcelFileDescriptor fd = descriptor(c, bytes); PdfRenderer renderer = new PdfRenderer(fd)) {
            int n = renderer.getPageCount();
            u.paginas = n;
            java.util.Set<String> gezien = new java.util.HashSet<>();
            for (int i = 0; i < n; i++) {
                if (!active.getAsBoolean()) return;
                if (u.fotos.size() >= max) { u.afgekapt = true; return; }
                List<byte[]> opPagina = new ArrayList<>();
                if (split != null && i < split.pages.size()) {
                    try { opPagina = jpegsOpPagina(split, split.pages.get(i), gezien); } catch (Throwable e) { opPagina = new ArrayList<>(); }
                }
                int erbij = 0;
                for (byte[] jpg : opPagina) {
                    Foto f = maat(jpg, i + 1);
                    if (f == null) continue;
                    // Een ingesloten foto zo groot als een pagina (een scan): ook de kaarten die erop staan
                    if (paginaVorm(f.breed, f.hoog)) for (Foto k : kaartenUit(f.jpeg, i + 1)) { if (u.fotos.size() >= max) { u.afgekapt = true; return; } u.neem(k); erbij++; }
                    if (u.fotos.size() >= max) { u.afgekapt = true; return; }
                    u.neem(f); erbij++;
                }
                if (erbij > 0) continue;
                // Geen bruikbare foto op deze pagina: de pagina zelf, en de kaarten die erop staan (een scan met voor- en achterkant)
                Bitmap b;
                try (PdfRenderer.Page p = renderer.openPage(i)) { b = render(p); }
                try {
                    for (Foto k : kaartenOp(b, i + 1)) { if (u.fotos.size() >= max) { u.afgekapt = true; return; } u.neem(k); }
                    if (u.fotos.size() >= max) { u.afgekapt = true; return; }
                    u.neem(new Foto(jpeg(b), b.getWidth(), b.getHeight(), i + 1, true));
                } finally { b.recycle(); }
            }
        }
    }

    /**
     * Ingesloten JPEG-foto's op één pagina (DCTDecode, grijs of RGB, geen /Decode-reeks: die kleuren klopt Android niet zonder de
     * PDF-regels). Elk stuk data één keer, ook als het op meer pagina's staat.
     */
    private static List<byte[]> jpegsOpPagina(PdfSplit pdf, PdfSplit.Dict page, java.util.Set<String> gezien) throws IOException {
        List<byte[]> uit = new ArrayList<>();
        Object resources = pdf.resolve(page.get("Resources"));
        if (!(resources instanceof PdfSplit.Dict)) return uit;
        Object objects = pdf.resolve(((PdfSplit.Dict) resources).get("XObject"));
        if (!(objects instanceof PdfSplit.Dict)) return uit;
        for (Object value : ((PdfSplit.Dict) objects).m.values()) {
            Object resolved = pdf.resolve(value);
            if (!(resolved instanceof PdfSplit.Stream)) continue;
            PdfSplit.Stream image = (PdfSplit.Stream) resolved;
            if (!new PdfSplit.Name("Image").equals(image.dict.get("Subtype"))) continue;
            if (!dct(pdf.resolve(image.dict.get("Filter"))) || image.dict.get("Decode") != null || !kleurenOk(pdf, image)) continue;
            if (getal(pdf.resolve(image.dict.get("Width"))) < MIN_ZIJDE || getal(pdf.resolve(image.dict.get("Height"))) < MIN_ZIJDE) continue;
            if (!gezien.add(image.off + ":" + image.len)) continue;
            uit.add(image.raw());
        }
        return uit;
    }

    private static boolean dct(Object filter) {
        PdfSplit.Name n = new PdfSplit.Name("DCTDecode");
        return n.equals(filter) || filter instanceof List && ((List<?>) filter).size() == 1 && n.equals(((List<?>) filter).get(0));
    }

    private static boolean kleurenOk(PdfSplit pdf, PdfSplit.Stream image) throws IOException {
        Object cs = pdf.resolve(image.dict.get("ColorSpace"));
        if (new PdfSplit.Name("DeviceRGB").equals(cs) || new PdfSplit.Name("DeviceGray").equals(cs)) return true;
        if (cs instanceof List && ((List<?>) cs).size() == 2 && new PdfSplit.Name("ICCBased").equals(((List<?>) cs).get(0))) {
            Object icc = pdf.resolve(((List<?>) cs).get(1));
            int n = icc instanceof PdfSplit.Stream ? getal(pdf.resolve(((PdfSplit.Stream) icc).dict.get("N"))) : 0;
            return n == 1 || n == 3;
        }
        return false;
    }

    /** Een getal uit een PDF (PdfSplit.Num); anders 0. Zuiver, getest. */
    static int getal(Object o) { return o instanceof PdfSplit.Num ? ((PdfSplit.Num) o).i() : o instanceof Number ? ((Number) o).intValue() : 0; }

    /** Maten van een JPEG die Android kan openen (minstens MIN_ZIJDE); anders null. */
    private static Foto maat(byte[] jpg, int pagina) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try { BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o); } catch (Throwable e) { return null; }
        if (o.outWidth < MIN_ZIJDE || o.outHeight < MIN_ZIJDE || !"image/jpeg".equals(o.outMimeType)) return null;
        return new Foto(jpg, o.outWidth, o.outHeight, pagina, false);
    }

    private static Bitmap render(PdfRenderer.Page p) {
        try { return render(p, PAGINA_ZIJDE); } catch (OutOfMemoryError e) { return render(p, PAGINA_ZIJDE / 2); }
    }

    static Bitmap render(PdfRenderer.Page page, int max) {
        int side = Math.max(page.getWidth(), page.getHeight());
        int w = Math.max(1, (int) ((long) page.getWidth() * max / side)), h = Math.max(1, (int) ((long) page.getHeight() * max / side));
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        try {
            bitmap.eraseColor(Color.WHITE);
            Matrix scale = new Matrix();
            scale.setScale((float) w / page.getWidth(), (float) h / page.getHeight());
            page.render(bitmap, null, scale, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return bitmap;
        } catch (RuntimeException | Error e) { bitmap.recycle(); throw e; }
    }

    /** PdfRenderer vraagt een zoekbare descriptor. Het pad is weg vóór er inhoud in komt. */
    static ParcelFileDescriptor descriptor(Context context, byte[] bytes) throws IOException {
        File temp = File.createTempFile("doc-foto-", ".tmp", context.getCacheDir());
        try (FileOutputStream out = new FileOutputStream(temp)) {
            ParcelFileDescriptor fd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY);
            try {
                if (!temp.delete()) throw new IOException();
                out.write(bytes);
                return fd;
            } catch (Throwable e) { fd.close(); throw e; }
        } finally { temp.delete(); }
    }

    // ---------- kaarten op een pagina ----------

    /** Groot en in de vorm van een A-pagina of Letter (geen kaart, ≈1,59). Zuiver, getest. */
    static boolean paginaVorm(int w, int h) {
        if (w < 1 || h < 1 || Math.max(w, h) < 1000) return false;
        double r = (double) Math.max(w, h) / Math.min(w, h);
        return r > 1.25 && r < 1.50;
    }

    /** Werkmaat voor het zoeken naar kaarten (langste zijde). */
    static final int ZOEK_ZIJDE = 600;

    /**
     * Rechthoeken zo groot als een kaart op een pagina (in de coördinaten van het raster): vlakken van "inkt" (niet-wit of
     * gekleurd) in de verhouding van een ID-kaart (1,59; tussen 1,3 en 1,9, staand of liggend), goed gevuld en dicht (een kaart
     * heeft een gekleurde achtergrond, een alinea tekst niet). Hooguit 4, van boven naar beneden en van links naar rechts.
     * inkt: wat donker of gekleurd is (rij voor rij). Zuiver, getest.
     */
    static List<int[]> kaartVlakken(boolean[] inkt, int w, int h) {
        // Kleine gaten dichten (de tekst op een kaart wordt één vlak): 3 px verbreden, eerst per rij en dan per kolom
        boolean[] rij = new boolean[w * h], dicht = new boolean[w * h];
        int r = 3;
        for (int y = 0; y < h; y++) {
            int laatst = -1000;
            for (int x = 0; x < w; x++) if (inkt[y * w + x]) laatst = x; else if (x - laatst <= r) rij[y * w + x] = true;
            laatst = 100000;
            for (int x = w - 1; x >= 0; x--) { if (inkt[y * w + x]) { laatst = x; rij[y * w + x] = true; } else if (laatst - x <= r) rij[y * w + x] = true; }
        }
        for (int x = 0; x < w; x++) {
            int laatst = -1000;
            for (int y = 0; y < h; y++) if (rij[y * w + x]) { laatst = y; dicht[y * w + x] = true; } else if (y - laatst <= r) dicht[y * w + x] = true;
            laatst = 100000;
            for (int y = h - 1; y >= 0; y--) if (rij[y * w + x]) laatst = y; else if (laatst - y <= r) dicht[y * w + x] = true;
        }
        int[] label = new int[w * h], wachtrij = new int[w * h];
        List<int[]> uit = new ArrayList<>();
        int n = 0;
        for (int start = 0; start < w * h; start++) {
            if (!dicht[start] || label[start] != 0) continue;
            n++;
            int kop = 0, staart = 0, x0 = w, y0 = h, x1 = -1, y1 = -1, punten = 0;
            wachtrij[staart++] = start; label[start] = n;
            while (kop < staart) {
                int p = wachtrij[kop++], x = p % w, y = p / w; punten++;
                if (x < x0) x0 = x;
                if (x > x1) x1 = x;
                if (y < y0) y0 = y;
                if (y > y1) y1 = y;
                if (x > 0 && dicht[p - 1] && label[p - 1] == 0) { label[p - 1] = n; wachtrij[staart++] = p - 1; }
                if (x < w - 1 && dicht[p + 1] && label[p + 1] == 0) { label[p + 1] = n; wachtrij[staart++] = p + 1; }
                if (y > 0 && dicht[p - w] && label[p - w] == 0) { label[p - w] = n; wachtrij[staart++] = p - w; }
                if (y < h - 1 && dicht[p + w] && label[p + w] == 0) { label[p + w] = n; wachtrij[staart++] = p + w; }
            }
            int bw = x1 - x0 + 1, bh = y1 - y0 + 1;
            double verhouding = (double) Math.max(bw, bh) / Math.min(bw, bh);
            long vlak = (long) bw * bh, pagina = (long) w * h;
            if (verhouding < 1.3 || verhouding > 1.9) continue;
            if (vlak < pagina * 0.03 || vlak > pagina * 0.6) continue;
            if (punten < vlak * 0.8) continue; // een rechthoek, geen losse vorm
            int echt = 0;
            for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) if (inkt[y * w + x]) echt++;
            if (echt < vlak * 0.35) continue; // een kaart met een achtergrond, geen alinea tekst
            uit.add(new int[]{x0, y0, bw, bh});
        }
        if (uit.size() > 4) return new ArrayList<>();
        uit.sort((a, b) -> Math.abs(a[1] - b[1]) > Math.min(a[3], b[3]) / 2 ? Integer.compare(a[1], b[1]) : Integer.compare(a[0], b[0]));
        return uit;
    }

    /** De kaarten op een pagina (bitmap) als losse foto's, met een klein randje. Lukt het niet: geen (dan alleen de pagina). */
    static List<Foto> kaartenOp(Bitmap b, int pagina) {
        List<Foto> uit = new ArrayList<>();
        try {
            int side = Math.max(b.getWidth(), b.getHeight());
            double s = side > ZOEK_ZIJDE ? (double) ZOEK_ZIJDE / side : 1;
            int w = Math.max(1, (int) (b.getWidth() * s)), h = Math.max(1, (int) (b.getHeight() * s));
            Bitmap klein = Bitmap.createScaledBitmap(b, w, h, true);
            boolean[] inkt = new boolean[w * h];
            try {
                int[] px = new int[w * h];
                klein.getPixels(px, 0, w, 0, 0, w, h);
                for (int i = 0; i < px.length; i++) {
                    int c = px[i], rr = (c >> 16) & 255, gg = (c >> 8) & 255, bb = c & 255;
                    int luma = (rr * 30 + gg * 59 + bb * 11) / 100, kleur = Math.max(rr, Math.max(gg, bb)) - Math.min(rr, Math.min(gg, bb));
                    inkt[i] = luma < 238 || kleur > 25;
                }
            } finally { if (klein != b) klein.recycle(); }
            for (int[] v : kaartVlakken(inkt, w, h)) {
                int rand = Math.max(2, Math.min(v[2], v[3]) / 30);
                int x = Math.max(0, (int) ((v[0] - rand) / s)), y = Math.max(0, (int) ((v[1] - rand) / s));
                int cw = Math.min((int) ((v[2] + 2 * rand) / s), b.getWidth() - x), ch = Math.min((int) ((v[3] + 2 * rand) / s), b.getHeight() - y);
                if (Math.max(cw, ch) < MIN_ZIJDE) continue;
                Bitmap stuk = Bitmap.createBitmap(b, x, y, cw, ch);
                try { uit.add(new Foto(jpeg(stuk), cw, ch, pagina, false)); } finally { if (stuk != b) stuk.recycle(); }
            }
        } catch (Throwable e) { return new ArrayList<>(); }
        return uit;
    }

    /** Zoals kaartenOp, voor een JPEG (een ingesloten foto zo groot als een pagina). */
    static List<Foto> kaartenUit(byte[] jpg, int pagina) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > PAGINA_ZIJDE) sample *= 2;
            BitmapFactory.Options lees = new BitmapFactory.Options(); lees.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeByteArray(jpg, 0, jpg.length, lees);
            if (b == null) return new ArrayList<>();
            try { return kaartenOp(b, pagina); } finally { b.recycle(); }
        } catch (Throwable e) { return new ArrayList<>(); }
    }

    // ---------- Word ----------

    private static void wordFotos(byte[] bytes, int max, BooleanSupplier active, Uitkomst u) throws IOException {
        int uitgepakt = 0, items = 0;
        boolean document = false;
        List<Object[]> media = new ArrayList<>();
        byte[] buf = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while (active.getAsBoolean() && (e = zip.getNextEntry()) != null) {
                if (++items > MAX_ZIP_ITEMS) throw new IOException("te veel onderdelen");
                String name = e.getName();
                if ("word/document.xml".equals(name)) document = true;
                // Alleen foto's (geen tekeningen zoals EMF/WMF/SVG)
                boolean foto = !e.isDirectory() && name.startsWith("word/media/") && name.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(jpe?g|png|webp|gif|bmp)$");
                ByteArrayOutputStream out = foto ? new ByteArrayOutputStream() : null;
                int n;
                while ((n = zip.read(buf)) != -1) {
                    uitgepakt += n;
                    if (uitgepakt > MAX_UITGEPAKT) throw new IOException("te groot");
                    if (out != null) out.write(buf, 0, n);
                }
                if (out != null) media.add(new Object[]{name, out.toByteArray()});
            }
        }
        if (!document) throw new IOException("geen Word-document");
        // In de volgorde van de bestandsnamen (image1, image2, … image10): zo staan ze meestal in het document
        media.sort((x, y) -> volgorde((String) x[0], (String) y[0]));
        for (Object[] m : media) {
            byte[] b = (byte[]) m[1];
            if (!active.getAsBoolean()) return;
            Foto f = alsJpeg(b, 0, false);
            if (f == null) continue;
            if (paginaVorm(f.breed, f.hoog)) for (Foto k : kaartenUit(f.jpeg, 0)) { if (u.fotos.size() >= max) { u.afgekapt = true; return; } u.neem(k); }
            if (u.fotos.size() >= max) { u.afgekapt = true; return; }
            u.neem(f);
        }
    }

    /** image2 vóór image10 (getallen als getal), verder op naam. Zuiver, getest. */
    static int volgorde(String a, String b) {
        // Sleutel: de naam zonder het getal vlak voor de extensie, dan dat getal (zonder getal: -1), dan de hele naam. Een vaste
        // volgorde (ook voor gemengde namen), dus sorteren gaat nooit mis.
        Object[] x = sleutel(a), y = sleutel(b);
        int c = ((String) x[0]).compareTo((String) y[0]);
        if (c != 0) return c;
        c = Long.compare((Long) x[1], (Long) y[1]);
        return c != 0 ? c : a.compareTo(b);
    }
    private static final java.util.regex.Pattern GETAL = java.util.regex.Pattern.compile("(\\d{1,9})(\\.[^.]*)?$");
    private static Object[] sleutel(String n) {
        java.util.regex.Matcher m = GETAL.matcher(n);
        if (m.find()) return new Object[]{n.substring(0, m.start()), Long.parseLong(m.group(1))};
        return new Object[]{n, -1L};
    }

    // ---------- foto's ----------

    /** Een foto als JPEG (een JPEG blijft zoals hij is); kleiner dan MIN_ZIJDE of niet te openen: null. */
    static Foto alsJpeg(byte[] b, int pagina, boolean heleBladzijde) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try { BitmapFactory.decodeByteArray(b, 0, b.length, o); } catch (Throwable e) { return null; }
        if (o.outWidth < MIN_ZIJDE || o.outHeight < MIN_ZIJDE) return null;
        if ("image/jpeg".equals(o.outMimeType)) return new Foto(b, o.outWidth, o.outHeight, pagina, heleBladzijde);
        // Hooguit ongeveer 4000 pixels: groter heeft geen zin en kost te veel geheugen
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / sample > 4000) sample *= 2;
        BitmapFactory.Options lees = new BitmapFactory.Options(); lees.inSampleSize = sample;
        Bitmap bm;
        try { bm = BitmapFactory.decodeByteArray(b, 0, b.length, lees); } catch (Throwable e) { return null; }
        if (bm == null) return null;
        try {
            // Doorzichtig (PNG): op wit
            Bitmap wit = Bitmap.createBitmap(bm.getWidth(), bm.getHeight(), Bitmap.Config.ARGB_8888);
            try {
                wit.eraseColor(Color.WHITE);
                new Canvas(wit).drawBitmap(bm, 0, 0, null);
                return new Foto(jpeg(wit), wit.getWidth(), wit.getHeight(), pagina, heleBladzijde);
            } finally { wit.recycle(); }
        } catch (OutOfMemoryError e) { return null; } finally { bm.recycle(); }
    }

    static byte[] jpeg(Bitmap bitmap) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out);
        return out.toByteArray();
    }

    /** Een kleine versie om te tonen (data:image/jpeg;base64,…), langste zijde hooguit max. */
    static String voorbeeld(byte[] jpg, int max) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= max) sample *= 2;
        o = new BitmapFactory.Options(); o.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
        if (b == null) return "";
        try {
            int side = Math.max(b.getWidth(), b.getHeight());
            Bitmap s = side > max ? Bitmap.createScaledBitmap(b, Math.max(1, b.getWidth() * max / side), Math.max(1, b.getHeight() * max / side), true) : b;
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                s.compress(Bitmap.CompressFormat.JPEG, 80, out);
                return "data:image/jpeg;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP);
            } finally { if (s != b) s.recycle(); }
        } finally { b.recycle(); }
    }
}
