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
        /** De zijden zijn (halve) pagina's, geen losse kaartfoto's: er kan meer op staan dan het ID-bewijs. */
        boolean pagina;

        Pair(byte[] front, byte[] back, JSONObject fields) {
            this.front = front;
            this.back = back;
            this.fields = fields;
        }
    }

    /**
     * Een document met beide zijden. Eerst twee beelden zoeken ({@link #kandidaten}), dan herkennen: eerst overal de snelle
     * strook (ook op stukken van het beeld), alleen als die nergens staat de tekst van een rijbewijs. stats (mag null):
     * alleen aantallen, ja/nee en een vaste reden, voor de foutmelding en het logboek.
     */
    static Pair findPair(Context context, byte[] bytes, String today, String type, BooleanSupplier active) throws Exception {
        return findPair(context, bytes, today, type, active, null);
    }

    static Pair findPair(Context context, byte[] bytes, String today, String type, BooleanSupplier active, JSONObject stats) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + SEARCH_MS;
        BooleanSupplier searching = () -> active.getAsBoolean() && SystemClock.elapsedRealtime() < deadline;
        List<byte[]> images = kandidaten(context, bytes, searching, stats);
        if (images.size() != 2 || Arrays.equals(images.get(0), images.get(1))) { reden(stats, "beelden"); return null; }
        JSONObject a = herken(context, images.get(0), today, searching, false);
        JSONObject b = herken(context, images.get(1), today, searching, false);
        if (a == null && b == null && searching.getAsBoolean()) {
            a = herken(context, images.get(0), today, searching, true);
            if (a == null) b = herken(context, images.get(1), today, searching, true);
        }
        if (stats != null) stats.put("herkendA", a != null).put("herkendB", b != null);
        if (!active.getAsBoolean()) { reden(stats, "gestopt"); return null; }
        Pair p = pair(images.get(0), a, images.get(1), b, type);
        if (p != null) p.pagina = stats != null && stats.optBoolean("pagina");
        if (p == null) reden(stats, a != null && b != null ? "verschillend" : "onduidelijk");
        else if (p.fields == null) reden(stats, SystemClock.elapsedRealtime() >= deadline ? "tijd op" : "niets herkend");
        return p;
    }

    private static void reden(JSONObject stats, String r) { if (stats != null) try { stats.put("reden", r); } catch (Exception ignored) { } }

    /**
     * Voor- en achterkant bij elkaar zoeken (zuiver, getest). Wat herkend is gaat voor de soort die Rene koos (een
     * verblijfsdocument of identiteitskaart wordt vaak verkeerd gekozen). Twee herkende zijden moeten hetzelfde nummer hebben.
     * Niets herkend: toch de twee beelden (Rene kijkt ze na en kan ze wisselen), zonder gegevens.
     */
    static Pair pair(byte[] first, JSONObject a, byte[] second, JSONObject b, String type) {
        if (a == null && b == null) return new Pair(first, second, null); // volgorde zoals in het document
        JSONObject recognized = a == null ? b : a;
        JSONObject document = recognized.optJSONObject("document");
        if (document == null) return null;
        String soort = document.optString("documentsoort", "");
        if (soort.isEmpty()) soort = type;
        String number = document.optString("document");
        if (a != null && b != null) {
            JSONObject other = b.optJSONObject("document");
            String n2 = other == null ? "" : other.optString("document"), s2 = other == null ? "" : other.optString("documentsoort", "");
            if (!number.isEmpty() && !n2.isEmpty() && !number.equals(n2)) return null;
            if (!document.optString("documentsoort", "").isEmpty() && !s2.isEmpty() && !document.optString("documentsoort").equals(s2)) return null;
        }
        boolean firstIsFront = "front".equals(MasterSchema.scanSide(soort, recognized)) == (a != null);
        return new Pair(firstIsFront ? first : second, firstIsFront ? second : first, recognized);
    }

    /** Kleinste foto die als zijde telt (een logo, pictogram of miniatuur niet). */
    static final int MIN_ZIJDE = 300;

    /**
     * Twee beelden voor de zijden. PDF: twee ingesloten JPEG-foto's van een redelijke grootte; anders de pagina's zelf
     * (twee pagina's: elk een zijde; één pagina: in tweeën, langs de lange kant). Word (ook een Google-document): de twee
     * grootste foto's.
     */
    private static List<byte[]> kandidaten(Context context, byte[] bytes, BooleanSupplier active, JSONObject stats) throws Exception {
        if (pdf(bytes)) {
            List<byte[]> jpg = new ArrayList<>();
            int[] aantal = {0};
            try { jpg = pdfJpegs(bytes, active, aantal, 2); } catch (IOException | RuntimeException ignored) { }
            if (stats != null) stats.put("bron", "pdf").put("fotos", aantal[0]);
            if (jpg.size() == 2) {
                // Een scan met één foto per pagina: dat zijn hele pagina's (A-formaat), geen kaartfoto's
                if (stats != null && (paginaVorm(jpg.get(0)) || paginaVorm(jpg.get(1)))) stats.put("pagina", true);
                return jpg;
            }
            List<byte[]> p = pdfPaginas(context, bytes, active, stats);
            if (stats != null && !p.isEmpty()) stats.put("pagina", true);
            return p;
        }
        List<byte[]> media = new ArrayList<>();
        for (byte[] b : wordPairImages(bytes, active)) if (teOpenen(b)) media.add(b);
        if (stats != null) stats.put("bron", "word").put("fotos", media.size());
        return kies2(media);
    }

    /**
     * De twee grootste beelden (in hun volgorde in het bestand). Kleine plaatjes (logo's) zijn al weg via teOpenen (pixels);
     * op bytes filteren niet: een effen achterkant comprimeert tot heel weinig. Zuiver, getest.
     */
    static List<byte[]> kies2(List<byte[]> beelden) {
        List<byte[]> groot = new ArrayList<>();
        for (byte[] b : beelden) if (b != null && b.length > 0) groot.add(b);
        if (groot.size() <= 2) return groot.size() == 2 ? groot : new ArrayList<>();
        List<byte[]> op = new ArrayList<>(groot);
        op.sort((x, y) -> Integer.compare(y.length, x.length));
        List<byte[]> uit = new ArrayList<>();
        for (byte[] b : groot) if (b == op.get(0) || b == op.get(1)) uit.add(b);
        return uit;
    }

    /** Ingesloten JPEG-foto's (geen logo's of kleine plaatjes); meer dan {@code max}: niets. Voor de twee zijden. */
    private static List<byte[]> pdfJpegs(byte[] bytes, BooleanSupplier active, int[] aantal, int max) throws IOException {
        List<byte[]> result = new ArrayList<>();
        for (PdfSplit.Stream image : pdfFotos(new PdfSplit(bytes), active, aantal, max)) result.add(image.raw());
        return result;
    }

    /**
     * Ingesloten JPEG-foto's (DCTDecode, geen logo's of kleine plaatjes), zonder ze te kopiëren; elk stuk data één keer.
     * Een hele PDF-pagina kan ook andere gegevens bevatten. Meer dan {@code max}: niets. CMYK of een /Decode-reeks niet:
     * die kleuren klopt Android niet zonder de PDF-regels, en dan is de strook onleesbaar (de pagina's lezen het wel).
     */
    private static List<PdfSplit.Stream> pdfFotos(PdfSplit pdf, BooleanSupplier active, int[] aantal, int max) throws IOException {
        List<PdfSplit.Stream> images = new ArrayList<>();
        java.util.Set<String> gezien = new java.util.HashSet<>();
        if (pdf.pageCount() > MAX_CANDIDATES) return images;
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
                if (!new PdfSplit.Name("Image").equals(image.dict.get("Subtype"))) continue;
                if (!jpeg(pdf.resolve(image.dict.get("Filter"))) || image.dict.get("Decode") != null || !kleurenOk(pdf, image)) continue;
                if (getal(pdf.resolve(image.dict.get("Width"))) < MIN_ZIJDE || getal(pdf.resolve(image.dict.get("Height"))) < MIN_ZIJDE) continue;
                if (!gezien.add(image.off + ":" + image.len)) continue;
                images.add(image);
                aantal[0] = images.size();
                if (images.size() > max) return new ArrayList<>();
            }
        }
        return images;
    }

    /** /DCTDecode, ook als lijst van één. */
    private static boolean jpeg(Object filter) {
        PdfSplit.Name dct = new PdfSplit.Name("DCTDecode");
        if (dct.equals(filter)) return true;
        return filter instanceof List && ((List<?>) filter).size() == 1 && dct.equals(((List<?>) filter).get(0));
    }

    /** Grijs of RGB (ook ICCBased met 1 of 3 kanalen); geen CMYK. */
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

    /** De ingesloten foto's van een PDF (hooguit MAX_CANDIDATES); bij een fout: geen. stats "fotos": hoeveel. */
    private static List<PdfSplit.Stream> pdfFotos(byte[] bytes, BooleanSupplier searching, JSONObject stats) {
        int[] aantal = {0};
        List<PdfSplit.Stream> fotos = new ArrayList<>();
        try { fotos = pdfFotos(new PdfSplit(bytes), searching, aantal, MAX_CANDIDATES); } catch (Throwable ignored) { } // dan alleen de pagina's
        try { if (stats != null) stats.put("fotos", aantal[0]); } catch (Exception ignored) { }
        return fotos;
    }

    /**
     * Herkennen op de ingesloten foto's zelf, één tegelijk in het geheugen: rijbewijs = false de snelle strook (ook op tegels
     * van een grote foto), true de tekst van een rijbewijs (langzaam). Een foto in de vorm van een A-pagina (een scan), of
     * met een masker (de PDF toont er maar een deel van), wordt geen kaartfoto; de gegevens wel. stats "uitFoto": ja.
     */
    private static Match opFotos(Context context, List<PdfSplit.Stream> fotos, String today, BooleanSupplier searching, JSONObject stats, boolean rijbewijs) {
        for (PdfSplit.Stream stream : fotos) {
            if (!searching.getAsBoolean()) return null;
            byte[] foto = stream.raw();
            if (!teOpenen(foto)) continue;
            JSONObject fields = herken(context, foto, today, searching, rijbewijs);
            if (fields == null) continue;
            try { if (stats != null) stats.put("uitFoto", true); } catch (Exception ignored) { }
            boolean masker = stream.dict.get("SMask") != null || stream.dict.get("Mask") != null;
            return paginaVorm(foto) || masker ? new Match(null, fields) : new Match(foto, fields);
        }
        return null;
    }

    /** Een getal uit een PDF (PdfSplit.Num); anders 0. Zuiver, getest. */
    static int getal(Object o) { return o instanceof PdfSplit.Num ? ((PdfSplit.Num) o).i() : o instanceof Number ? ((Number) o).intValue() : 0; }

    /** Is dit een foto die Android kan openen, van minstens MIN_ZIJDE pixels? */
    private static boolean teOpenen(byte[] b) {
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try { android.graphics.BitmapFactory.decodeByteArray(b, 0, b.length, o); } catch (Throwable e) { return false; }
        return o.outWidth >= MIN_ZIJDE && o.outHeight >= MIN_ZIJDE;
    }

    /** Groot en in de vorm van een A-pagina (√2), niet van een kaart (≈1,59): een gescande pagina. */
    private static boolean paginaVorm(byte[] b) {
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try { android.graphics.BitmapFactory.decodeByteArray(b, 0, b.length, o); } catch (Throwable e) { return false; }
        return paginaVorm(o.outWidth, o.outHeight);
    }
    /** Zuiver, getest. */
    static boolean paginaVorm(int w, int h) {
        if (w < 1 || h < 1 || Math.max(w, h) < 1000) return false;
        double r = (double) Math.max(w, h) / Math.min(w, h);
        return r > 1.25 && r < 1.50; // A4/A5 (1,41) en Letter (1,29); een kaart is 1,58
    }

    /** De pagina's zelf: twee pagina's = elk een zijde; één pagina (een scan met beide zijden) = in tweeën. */
    private static List<byte[]> pdfPaginas(Context context, byte[] bytes, BooleanSupplier active, JSONObject stats) throws Exception {
        List<byte[]> out = new ArrayList<>();
        try (ParcelFileDescriptor fd = pdfDescriptor(context, bytes); PdfRenderer renderer = new PdfRenderer(fd)) {
            int n = renderer.getPageCount();
            if (stats != null) stats.put("paginas", n);
            if (n < 1 || n > 2 || !active.getAsBoolean()) return out;
            if (n == 2) {
                for (int i = 0; i < 2; i++) {
                    Bitmap b;
                    try (PdfRenderer.Page p = renderer.openPage(i)) { b = renderPage(p); }
                    try { out.add(jpeg(b)); } finally { b.recycle(); }
                }
                return out;
            }
            Bitmap b;
            try (PdfRenderer.Page p = renderer.openPage(0)) { b = renderPage(p); }
            try {
                for (int[] r : helften(b.getWidth(), b.getHeight())) {
                    Bitmap part = Bitmap.createBitmap(b, r[0], r[1], r[2], r[3]);
                    try { out.add(jpeg(part)); } finally { if (part != b) part.recycle(); }
                }
            } finally { b.recycle(); }
            if (stats != null) stats.put("gesplitst", true);
        }
        return out;
    }

    /** Een pagina in tweeën langs de lange kant (boven en onder, of links en rechts). Zuiver, getest. */
    static List<int[]> helften(int w, int h) {
        List<int[]> l = new ArrayList<>();
        if (h >= w) { int half = h / 2; l.add(new int[]{0, 0, w, half}); l.add(new int[]{0, half, w, h - half}); }
        else { int half = w / 2; l.add(new int[]{0, 0, half, h}); l.add(new int[]{half, 0, w - half, h}); }
        return l;
    }

    private static Bitmap renderPage(PdfRenderer.Page p) {
        try { return renderBitmap(p, RENDER_SIDE); } catch (OutOfMemoryError e) { return renderBitmap(p, MrzOcr.MAX_SIDE); }
    }

    /**
     * Eén zijde herkennen, ook als de kaart klein op een grote foto of pagina staat: het hele beeld en stukken ervan
     * (zoals bij een PDF-pagina). rijbewijs = de tekst van een rijbewijs (langzaam), anders alleen de strook (snel).
     */
    private static JSONObject herken(Context context, byte[] image, String today, BooleanSupplier active, boolean rijbewijs) {
        if (!active.getAsBoolean()) return null;
        List<byte[]> stukken;
        try { stukken = stukkenVan(image); } catch (Throwable e) { stukken = new ArrayList<>(); stukken.add(image); }
        for (byte[] stuk : stukken) {
            if (!active.getAsBoolean()) return null;
            JSONObject f = rijbewijs ? RijbewijsOcr.read(context, stuk, today, active, false, null) : MasterPhotos.recognize(stuk, today);
            if (f != null) return f;
        }
        return null;
    }

    /** Een beeld als JPEG's: het hele beeld (op de werkmaat) en, als het groot is, de tegels op hogere resolutie. */
    private static List<byte[]> stukkenVan(byte[] image) {
        List<byte[]> out = new ArrayList<>();
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeByteArray(image, 0, image.length, o);
        if (o.outWidth < 1 || o.outHeight < 1 || o.outWidth > 16000 || o.outHeight > 16000) { out.add(image); return out; }
        if (Math.max(o.outWidth, o.outHeight) <= MrzOcr.MAX_SIDE) { out.add(image); return out; }
        if ((long) o.outWidth * o.outHeight > 80_000_000L) { out.add(image); return out; } // zoals normalize: te groot om te openen
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= RENDER_SIDE) sample *= 2;
        o = new android.graphics.BitmapFactory.Options(); o.inSampleSize = sample;
        Bitmap big = android.graphics.BitmapFactory.decodeByteArray(image, 0, image.length, o);
        if (big == null) { out.add(image); return out; }
        try {
            int side = Math.max(big.getWidth(), big.getHeight());
            if (side > RENDER_SIDE) {
                Bitmap s = Bitmap.createScaledBitmap(big, Math.max(1, big.getWidth() * RENDER_SIDE / side), Math.max(1, big.getHeight() * RENDER_SIDE / side), true);
                if (s != big) { big.recycle(); big = s; }
            }
            out.addAll(stukkenVanBitmap(big));
        } catch (OutOfMemoryError e) {
            out.clear(); out.add(image);
        } finally { big.recycle(); }
        return out;
    }

    /** Het hele beeld verkleind tot de werkmaat, daarna de tegels (de bitmap blijft van de aanroeper). */
    private static List<byte[]> stukkenVanBitmap(Bitmap big) {
        List<byte[]> out = new ArrayList<>();
        int side = Math.max(big.getWidth(), big.getHeight());
        if (side <= MrzOcr.MAX_SIDE) { out.add(jpeg(big)); return out; }
        Bitmap small = Bitmap.createScaledBitmap(big, Math.max(1, big.getWidth() * MrzOcr.MAX_SIDE / side), Math.max(1, big.getHeight() * MrzOcr.MAX_SIDE / side), true);
        try { out.add(jpeg(small)); } finally { if (small != big) small.recycle(); }
        List<int[]> parts = stukken(big.getWidth(), big.getHeight());
        for (int i = 1; i < parts.size(); i++) {
            int[] r = parts.get(i);
            Bitmap part = Bitmap.createBitmap(big, r[0], r[1], r[2], r[3]);
            try { out.add(jpeg(part)); } finally { if (part != big) part.recycle(); }
        }
        return out;
    }

    static boolean pdf(byte[] bytes) {
        return bytes.length >= 5 && bytes[0] == '%' && bytes[1] == 'P'
            && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-';
    }

    static boolean zip(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4;
    }

    static Match find(Context context, byte[] bytes, String today, BooleanSupplier active) throws Exception { return find(context, bytes, today, active, null, false); }

    /**
     * Zoek het ID-bewijs. Een PDF-pagina is vaak een A4 met de kaart klein erop (een scan of kopie): dan zijn de tekens van
     * de strook op een verkleinde pagina te klein. Daarom naast de hele pagina ook stukken van de pagina op hogere resolutie
     * (tegels, zie {@link #tegels}), in de volgorde van {@link #volgorde}: eerst overal de snelle strook (ID-kaart,
     * paspoort), daarna met de tijd die over is de tekst van een rijbewijs (langzaam). stats (mag null): alleen aantallen
     * en ja/nee, voor de foutmelding en het logboek.
     */
    static Match find(Context context, byte[] bytes, String today, BooleanSupplier active, JSONObject stats, boolean rijbewijs) throws Exception {
        MrzOcr.Spoor spoor = new MrzOcr.Spoor();
        MrzOcr.volg(spoor);
        try { return zoek(context, bytes, today, active, stats, rijbewijs); }
        finally {
            MrzOcr.volg(null);
            try { if (stats != null) stats.put("strook", spoor.tekst()); } catch (Exception ignored) { }
        }
    }

    private static Match zoek(Context context, byte[] bytes, String today, BooleanSupplier active, JSONObject stats, boolean rijbewijs) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + SEARCH_MS;
        BooleanSupplier searching = () -> active.getAsBoolean() && SystemClock.elapsedRealtime() < deadline;
        if (!pdf(bytes)) return word(context, bytes, today, searching);
        // Eerst de foto's in de PDF op hun eigen resolutie (zoals bij Word): een foto van het ID-bewijs in een document
        // (Google Docs, Word als PDF) staat vaak klein op de pagina, en dan zijn de tekens op de getekende pagina te klein.
        // Eerst alleen de snelle strook; de langzame tekst van een rijbewijs pas na de pagina's (zoals volgorde), of eerst
        // als Rene zelf rijbewijs koos: anders gaat de tijd op aan foto's voordat de pagina's aan de beurt zijn.
        List<PdfSplit.Stream> fotos = pdfFotos(bytes, searching, stats);
        Match uitFoto = opFotos(context, fotos, today, searching, stats, false);
        if (uitFoto == null && rijbewijs) uitFoto = opFotos(context, fotos, today, searching, stats, true);
        if (uitFoto != null) return uitFoto;
        int pages = 0, pieces = 0;
        boolean all = false;
        try (ParcelFileDescriptor fd = pdfDescriptor(context, bytes);
             PdfRenderer renderer = new PdfRenderer(fd)) {
            int count = Math.min(renderer.getPageCount(), MAX_CANDIDATES);
            if (stats != null) stats.put("paginas", renderer.getPageCount());
            int[] tiles = new int[count];
            for (int i = 0; i < count; i++) {
                try (PdfRenderer.Page p = renderer.openPage(i)) {
                    int side = Math.max(p.getWidth(), p.getHeight());
                    tiles[i] = Math.max(0, stukken(Math.max(1, (int) ((long) p.getWidth() * RENDER_SIDE / side)),
                        Math.max(1, (int) ((long) p.getHeight() * RENDER_SIDE / side))).size() - 1);
                }
            }
            List<int[]> plan = volgorde(tiles, rijbewijs);
            int cachedPage = -1;
            boolean cachedTiles = false;
            List<byte[]> cache = null;
            int k = 0;
            for (; k < plan.size() && searching.getAsBoolean(); k++) {
                int round = plan.get(k)[0], page = plan.get(k)[1], piece = plan.get(k)[2];
                boolean needTiles = piece > 0 || round == 0;
                if (cache == null || cachedPage != page || needTiles && !cachedTiles) {
                    cache = null; // eerst loslaten: hooguit één pagina in het geheugen
                    try (PdfRenderer.Page p = renderer.openPage(page)) { cache = pieces(p, needTiles); }
                    cachedPage = page; cachedTiles = needTiles;
                    pages = Math.max(pages, page + 1);
                }
                if (piece >= cache.size()) continue; // te weinig geheugen voor de tegels: alleen de pagina
                pieces++;
                byte[] image = cache.get(piece);
                JSONObject fields = round == 0 ? MasterPhotos.recognize(image, today)
                    : RijbewijsOcr.read(context, image, today, searching, false, null);
                // Een PDF-pagina kan meer bevatten dan de kaart en hoort daarom niet in ID-kopie.
                if (fields != null) { put(stats, pages, pieces, false); return new Match(null, fields); }
            }
            all = k >= plan.size();
        }
        if (!rijbewijs) {
            Match laat = opFotos(context, fotos, today, searching, stats, true);
            if (laat != null) { put(stats, pages, pieces, false); return laat; }
            all = all && searching.getAsBoolean();
        }
        put(stats, pages, pieces, !all && active.getAsBoolean());
        return null;
    }

    /**
     * Volgorde van zoeken over de pagina's ({ronde, pagina, stuk}; stuk 0 = de hele pagina, daarna de tegels). Ronde 0: de
     * strook op elk stuk van elke pagina (snel). Ronde 1: rijbewijs op de hele pagina's. Ronde 2: rijbewijs op de tegels.
     * Koos Rene zelf "rijbewijs", dan de strook pas als laatste. Zuiver, getest.
     */
    static List<int[]> volgorde(int[] tilesPerPage, boolean rijbewijs) {
        List<int[]> strook = new ArrayList<>(), l = new ArrayList<>();
        for (int p = 0; p < tilesPerPage.length; p++) for (int s = 0; s <= tilesPerPage[p]; s++) strook.add(new int[]{0, p, s});
        if (!rijbewijs) l.addAll(strook);
        for (int p = 0; p < tilesPerPage.length; p++) l.add(new int[]{1, p, 0});
        for (int p = 0; p < tilesPerPage.length; p++) for (int s = 1; s <= tilesPerPage[p]; s++) l.add(new int[]{2, p, s});
        if (rijbewijs) l.addAll(strook);
        return l;
    }

    private static void put(JSONObject stats, int pages, int pieces, boolean late) {
        if (stats == null) return;
        try { stats.put("bekeken", pages).put("stukken", pieces).put("teLang", late); } catch (Exception ignored) { }
    }

    /**
     * Anderhalf keer zo groot renderen: een ID-kaart op een A4 is dan ruim 750 pixels breed (de tekens van de strook goed
     * leesbaar) en past nog op één tegel van een halve overlap (vier tegels per pagina).
     */
    static final int RENDER_SIDE = MrzOcr.MAX_SIDE * 3 / 2;

    /**
     * Een pagina als JPEG's: eerst de hele pagina op de werkmaat van de herkenning, met tiles ook de tegels op de hogere
     * resolutie. De grote bitmap staat alleen kort in het geheugen. Te weinig geheugen voor de tegels: alleen de pagina.
     */
    private static List<byte[]> pieces(PdfRenderer.Page page, boolean tiles) {
        List<byte[]> out = new ArrayList<>();
        if (tiles) {
            Bitmap big = null;
            try {
                big = renderBitmap(page, RENDER_SIDE);
                out.addAll(stukkenVanBitmap(big));
                return out;
            } catch (OutOfMemoryError e) {
                out.clear();
            } finally {
                if (big != null) big.recycle();
            }
        }
        Bitmap bitmap = renderBitmap(page, MrzOcr.MAX_SIDE);
        try { out.add(jpeg(bitmap)); } finally { bitmap.recycle(); }
        return out;
    }

    private static Bitmap renderBitmap(PdfRenderer.Page page, int max) {
        int side = Math.max(page.getWidth(), page.getHeight());
        int width = Math.max(1, (int) ((long) page.getWidth() * max / side));
        int height = Math.max(1, (int) ((long) page.getHeight() * max / side));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            bitmap.eraseColor(Color.WHITE);
            Matrix scale = new Matrix();
            scale.setScale((float) width / page.getWidth(), (float) height / page.getHeight());
            page.render(bitmap, null, scale, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return bitmap;
        } catch (RuntimeException | Error e) {
            bitmap.recycle();
            throw e;
        }
    }

    private static byte[] jpeg(Bitmap bitmap) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out);
        return out.toByteArray();
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
                // Alleen foto's (geen tekeningen zoals EMF/WMF/SVG); hooguit een handvol
                boolean media = !entry.isDirectory() && name.startsWith("word/media/") && name.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(jpe?g|png|webp|gif|bmp)$");
                if (media && images.size() == MAX_CANDIDATES) return new ArrayList<>();
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
        return document && active.getAsBoolean() ? images : new ArrayList<>();
    }

    private static Match recognize(Context context, byte[] image, String today, BooleanSupplier active) {
        JSONObject fields = MasterPhotos.recognize(context, image, today, active);
        return fields == null ? null : new Match(image, fields);
    }
}
