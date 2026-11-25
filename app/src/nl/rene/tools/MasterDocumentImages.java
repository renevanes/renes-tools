package nl.rene.tools;

import android.content.Context;
import android.graphics.Bitmap;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/**
 * Een ID-bewijs herkennen op een foto die uit een document komt (DocumentFotos): de kaart kan klein op een grote foto of
 * pagina staan, dus ook op stukken van het beeld. En bij twee foto's: welke de voorkant is en welke de achterkant.
 */
final class MasterDocumentImages {
    private MasterDocumentImages() { }

    /** Hoe lang herkennen op gekozen foto's uit een document hooguit duurt. */
    static final long SEARCH_MS = 60_000;

    /**
     * Anderhalf keer de werkmaat van de herkenning: een ID-kaart op een A4 is dan ruim 750 pixels breed (de tekens van de strook
     * goed leesbaar) en past nog op één tegel van een halve overlap.
     */
    static final int RENDER_SIDE = MrzOcr.MAX_SIDE * 3 / 2;

    static final class Pair {
        final byte[] front, back;
        final JSONObject fields;

        Pair(byte[] front, byte[] back, JSONObject fields) {
            this.front = front;
            this.back = back;
            this.fields = fields;
        }
    }

    /**
     * Een foto uit een document herkennen: eerst de snelle strook (ook op stukken van het beeld), en alleen als die er niet staat
     * de tekst van een rijbewijs (langzaam). null = niets herkend.
     */
    static JSONObject herkenFoto(Context context, byte[] image, String today, BooleanSupplier active) {
        JSONObject f = herken(context, image, today, active, false);
        return f != null || !active.getAsBoolean() ? f : herken(context, image, today, active, true);
    }

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

    /**
     * Eén zijde herkennen, ook als de kaart klein op een grote foto of pagina staat: het hele beeld en stukken ervan
     * (zoals bij een PDF-pagina). rijbewijs = de tekst van een rijbewijs (langzaam), anders alleen de strook (snel).
     */
    static JSONObject herken(Context context, byte[] image, String today, BooleanSupplier active, boolean rijbewijs) {
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

    private static byte[] jpeg(Bitmap bitmap) { return DocumentFotos.jpeg(bitmap); }

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

}
