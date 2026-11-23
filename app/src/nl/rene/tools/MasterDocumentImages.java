package nl.rene.tools;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONObject;

/** Zoekt lokaal naar een leesbare ID-kaart, paspoort of rijbewijs in een PDF of Word-document. */
final class MasterDocumentImages {
    static final long SEARCH_MS = 60_000;
    private static final int MAX_CANDIDATES = 12, MAX_EXPANDED_BYTES = 40 * 1024 * 1024;

    static final class Match {
        final byte[] image;
        final JSONObject fields;

        Match(byte[] image, JSONObject fields) {
            this.image = image;
            this.fields = fields;
        }
    }

    static final class Pair {
        final byte[] front, back;
        final JSONObject fields;

        Pair(byte[] front, byte[] back, JSONObject fields) {
            this.front = front;
            this.back = back;
            this.fields = fields;
        }
    }

    /** De gebruiker heeft bevestigd dat het document twee losse foto's van hetzelfde bewijs bevat. */
    static Pair findPair(Context context, byte[] bytes, String today, String type, BooleanSupplier active) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + SEARCH_MS;
        BooleanSupplier searching = () -> active.getAsBoolean() && SystemClock.elapsedRealtime() < deadline;
        List<byte[]> images = pdf(bytes) ? pdfImages(bytes, searching) : wordPairImages(bytes, searching);
        if (images.size() != 2 || Arrays.equals(images.get(0), images.get(1))) return null;

        Match first = recognize(context, images.get(0), today, searching);
        if (!searching.getAsBoolean()) return null;
        Match second = recognize(context, images.get(1), today, searching);
        return searching.getAsBoolean() ? pair(images.get(0), fields(first), images.get(1), fields(second), type) : null;
    }

    private static JSONObject fields(Match match) { return match == null ? null : match.fields; }

    static Pair pair(byte[] first, JSONObject a, byte[] second, JSONObject b, String type) {
        if (a == null && b == null) return null;
        JSONObject recognized = a == null ? b : a;
        JSONObject document = recognized.optJSONObject("document");
        if (document == null || !type.equals(document.optString("documentsoort"))) return null;
        String number = document.optString("document");
        if (number.isEmpty()) return null;
        if (a != null && b != null) {
            JSONObject other = b.optJSONObject("document");
            if (other == null || !type.equals(other.optString("documentsoort")) ||
                !number.equals(other.optString("document"))) return null;
        }
        boolean firstIsFront = "front".equals(MasterSchema.scanSide(type, recognized)) == (a != null);
        return new Pair(firstIsFront ? first : second, firstIsFront ? second : first, recognized);
    }

    /** Haal alleen losse JPEG-objecten op. Een hele PDF-pagina kan ook andere gegevens bevatten. */
    private static List<byte[]> pdfImages(byte[] bytes, BooleanSupplier active) throws IOException {
        PdfSplit pdf = new PdfSplit(bytes);
        List<PdfSplit.Stream> images = new ArrayList<>();
        if (pdf.pageCount() > MAX_CANDIDATES) return new ArrayList<>();
        for (PdfSplit.Dict page : pdf.pages) {
            if (!active.getAsBoolean()) return new ArrayList<>();
            Object resources = pdf.resolve(page.get("Resources"));
            if (!(resources instanceof PdfSplit.Dict)) continue;
            Object objects = pdf.resolve(((PdfSplit.Dict) resources).get("XObject"));
            if (!(objects instanceof PdfSplit.Dict)) continue;
            for (Object value : ((PdfSplit.Dict) objects).m.values()) {
                Object resolved = pdf.resolve(value);
                if (!(resolved instanceof PdfSplit.Stream)) continue;
                PdfSplit.Stream image = (PdfSplit.Stream) resolved;
                if (!new PdfSplit.Name("Image").equals(image.dict.get("Subtype")) || images.contains(image)) continue;
                if (!new PdfSplit.Name("DCTDecode").equals(pdf.resolve(image.dict.get("Filter"))) || images.size() == 2) {
                    return new ArrayList<>();
                }
                images.add(image);
            }
        }
        List<byte[]> result = new ArrayList<>();
        for (PdfSplit.Stream image : images) result.add(image.raw());
        return result;
    }

    static boolean pdf(byte[] bytes) {
        return bytes.length >= 5 && bytes[0] == '%' && bytes[1] == 'P'
            && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-';
    }

    static boolean zip(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4;
    }

    static Match find(Context context, byte[] bytes, String today, BooleanSupplier active) throws Exception { return find(context, bytes, today, active, null); }

    /**
     * Zoek het ID-bewijs. Een PDF-pagina is vaak een A4 met de kaart klein erop (een scan of kopie): dan zijn de tekens van
     * de strook op een verkleinde pagina te klein. Daarom eerst de hele pagina, daarna stukken van de pagina op hogere
     * resolutie (tegels, zie {@link #tegels}). stats (mag null): alleen aantallen en ja/nee, voor de foutmelding en Diagnose.
     */
    static Match find(Context context, byte[] bytes, String today, BooleanSupplier active, JSONObject stats) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + SEARCH_MS;
        BooleanSupplier searching = () -> active.getAsBoolean() && SystemClock.elapsedRealtime() < deadline;
        if (pdf(bytes)) {
            int pages = 0, pieces = 0;
            try (ParcelFileDescriptor fd = pdfDescriptor(context, bytes);
                 PdfRenderer renderer = new PdfRenderer(fd)) {
                int count = Math.min(renderer.getPageCount(), MAX_CANDIDATES);
                if (stats != null) stats.put("paginas", renderer.getPageCount());
                for (int i = 0; i < count && searching.getAsBoolean(); i++) {
                    Bitmap page;
                    try (PdfRenderer.Page p = renderer.openPage(i)) { page = renderPage(p); }
                    pages++;
                    try {
                        for (int[] r : stukken(page.getWidth(), page.getHeight())) {
                            if (!searching.getAsBoolean()) break;
                            pieces++;
                            Match match = recognize(context, jpeg(page, r), today, searching);
                            // Een PDF-pagina kan meer bevatten dan de kaart en hoort daarom niet in ID-kopie.
                            if (match != null) { put(stats, pages, pieces, false); return new Match(null, match.fields); }
                        }
                    } finally { page.recycle(); }
                }
            }
            put(stats, pages, pieces, SystemClock.elapsedRealtime() >= deadline);
        } else {
            return word(context, bytes, today, searching);
        }
        return null;
    }

    private static void put(JSONObject stats, int pages, int pieces, boolean late) {
        if (stats == null) return;
        try { stats.put("bekeken", pages).put("stukken", pieces).put("teLang", late); } catch (Exception ignored) { }
    }

    /**
     * Anderhalf keer zo groot renderen: een ID-kaart op een A4 is dan ruim 750 pixels breed (de tekens van de strook goed
     * leesbaar) en past nog op één tegel van een halve overlap (vier tegels per pagina). Te weinig geheugen: zoals vroeger.
     */
    static final int RENDER_SIDE = MrzOcr.MAX_SIDE * 3 / 2;

    private static Bitmap renderPage(PdfRenderer.Page page) {
        try { return renderBitmap(page, RENDER_SIDE); }
        catch (OutOfMemoryError e) { return renderBitmap(page, MrzOcr.MAX_SIDE); }
    }

    private static Bitmap renderBitmap(PdfRenderer.Page page, int max) {
        int side = Math.max(page.getWidth(), page.getHeight());
        int width = Math.max(1, (int) ((long) page.getWidth() * max / side));
        int height = Math.max(1, (int) ((long) page.getHeight() * max / side));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE);
        Matrix scale = new Matrix();
        scale.setScale((float) width / page.getWidth(), (float) height / page.getHeight());
        page.render(bitmap, null, scale, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        return bitmap;
    }

    /** Een stuk van de pagina als JPEG ({x, y, breedte, hoogte}; de hele pagina = het eerste stuk). */
    private static byte[] jpeg(Bitmap page, int[] r) {
        Bitmap part = r[0] == 0 && r[1] == 0 && r[2] == page.getWidth() && r[3] == page.getHeight() ? page : Bitmap.createBitmap(page, r[0], r[1], r[2], r[3]);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            part.compress(Bitmap.CompressFormat.JPEG, 92, out);
            return out.toByteArray();
        } finally {
            if (part != page) part.recycle();
        }
    }

    /** De hele pagina en daarna de tegels (zuiver, getest). */
    static java.util.List<int[]> stukken(int w, int h) {
        java.util.List<int[]> l = new ArrayList<>();
        l.add(new int[]{0, 0, w, h});
        if (Math.max(w, h) > MrzOcr.MAX_SIDE) l.addAll(tegels(w, h, MrzOcr.MAX_SIDE));
        return l;
    }

    /**
     * Tegels van hooguit {@code max} bij {@code max} die elkaar ruim overlappen (minstens de helft), zodat een kaart die
     * kleiner is dan een halve tegel altijd helemaal op één tegel staat. Zuiver, getest.
     */
    static java.util.List<int[]> tegels(int w, int h, int max) {
        java.util.List<int[]> l = new ArrayList<>();
        int tw = Math.min(w, max), th = Math.min(h, max);
        for (int y : posities(h, th)) for (int x : posities(w, tw)) l.add(new int[]{x, y, tw, th});
        return l;
    }

    private static int[] posities(int total, int size) {
        if (size >= total) return new int[]{0};
        int n = (int) Math.ceil((total - size) / (size / 2.0)) + 1; // stap hooguit een halve tegel
        int[] p = new int[n];
        for (int i = 0; i < n; i++) p[i] = (int) Math.round((double) (total - size) * i / (n - 1));
        return p;
    }

    /** PdfRenderer vraagt een zoekbare descriptor. Verwijder het pad vóór er gevoelige inhoud in komt. */
    private static ParcelFileDescriptor pdfDescriptor(Context context, byte[] bytes) throws IOException {
        File temp = File.createTempFile("stam-pdf-", ".tmp", context.getCacheDir());
        try (FileOutputStream out = new FileOutputStream(temp)) {
            ParcelFileDescriptor fd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY);
            try {
                if (!temp.delete()) throw new IOException();
                out.write(bytes);
                return fd;
            } catch (Throwable e) {
                fd.close();
                throw e;
            }
        } finally {
            temp.delete();
        }
    }

    private static Match word(Context context, byte[] bytes, String today, BooleanSupplier active) throws Exception {
        int expanded = 0, candidates = 0, entries = 0;
        boolean document = false;
        Match match = null;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while (entries++ < 512 && active.getAsBoolean() && (entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if ("word/document.xml".equals(name)) document = true;
                boolean media = !entry.isDirectory() && name.startsWith("word/media/")
                    && match == null && candidates < MAX_CANDIDATES;
                ByteArrayOutputStream out = media ? new ByteArrayOutputStream() : null;
                int n;
                while ((n = zip.read(buffer)) != -1) {
                    expanded += n;
                    if (expanded > MAX_EXPANDED_BYTES || !active.getAsBoolean()) return null;
                    if (out != null) out.write(buffer, 0, n);
                }
                if (out != null) {
                    candidates++;
                    match = recognize(context, out.toByteArray(), today, active);
                }
                if (document && match != null) return match;
            }
        }
        return null;
    }

    private static List<byte[]> wordPairImages(byte[] bytes, BooleanSupplier active) throws IOException {
        int expanded = 0, entries = 0;
        boolean document = false;
        List<byte[]> images = new ArrayList<>();
        byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while (active.getAsBoolean() && (entry = zip.getNextEntry()) != null) {
                if (++entries > 512) return new ArrayList<>();
                String name = entry.getName();
                if ("word/document.xml".equals(name)) document = true;
                boolean media = !entry.isDirectory() && name.startsWith("word/media/");
                if (media && images.size() == 2) return new ArrayList<>();
                ByteArrayOutputStream out = media ? new ByteArrayOutputStream() : null;
                int n;
                while ((n = zip.read(buffer)) != -1) {
                    expanded += n;
                    if (expanded > MAX_EXPANDED_BYTES || !active.getAsBoolean()) return new ArrayList<>();
                    if (out != null) out.write(buffer, 0, n);
                }
                if (out != null) images.add(out.toByteArray());
            }
        }
        return document ? images : new ArrayList<>();
    }

    private static Match recognize(Context context, byte[] image, String today, BooleanSupplier active) {
        JSONObject fields = MasterPhotos.recognize(context, image, today, active);
        return fields == null ? null : new Match(image, fields);
    }
}
