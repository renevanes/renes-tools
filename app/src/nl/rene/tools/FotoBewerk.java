package nl.rene.tools;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.ExifInterface;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Een bewerking (FotoOps) op de pixels toepassen: kwartslag, vlak rechttrekken (projectie, Matrix.setPolyToPoly), licht en
 * kleur (ColorMatrix) en zwarte vlakken. Alles in het geheugen; de uitkomst is een nieuwe bitmap met een witte achtergrond.
 */
final class FotoBewerk {
    private FotoBewerk() { }

    /** Een foto lezen met de langste kant hooguit max (ongeveer: een macht van twee kleiner, daarna precies), rechtop volgens EXIF. */
    static Bitmap lees(byte[] bytes, int max) throws FotoOps.Fout {
        BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        if (o.outWidth < 1 || o.outHeight < 1 || o.outWidth > 20000 || o.outHeight > 20000 || (long) o.outWidth * o.outHeight > 120_000_000L)
            throw new FotoOps.Fout(Taal.T("Deze foto kan niet worden geopend"));
        // Niet veel groter inlezen dan nodig (geheugen): hooguit anderhalf keer max, liever iets kleiner
        int sample = 1; while (Math.max(o.outWidth, o.outHeight) / sample > max * 3 / 2) sample *= 2;
        o = new BitmapFactory.Options(); o.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        if (b == null) throw new FotoOps.Fout(Taal.T("Deze foto kan niet worden geopend"));
        int side = Math.max(b.getWidth(), b.getHeight());
        if (side > max) { Bitmap s = Bitmap.createScaledBitmap(b, Math.max(1, b.getWidth() * max / side), Math.max(1, b.getHeight() * max / side), true); if (s != b) { b.recycle(); b = s; } }
        Matrix m = new Matrix();
        try {
            switch (new ExifInterface(new ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                case 2: m.setScale(-1, 1); break; case 3: m.setRotate(180); break; case 4: m.setScale(1, -1); break;
                case 5: m.setRotate(90); m.postScale(-1, 1); break; case 6: m.setRotate(90); break;
                case 7: m.setRotate(270); m.postScale(-1, 1); break; case 8: m.setRotate(270); break; default: break;
            }
        } catch (Exception ignored) { }
        if (!m.isIdentity()) { Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true); if (r != b) { b.recycle(); b = r; } }
        // Altijd eigen, ondoorzichtige pixels (transparante plaatjes krijgen wit)
        Bitmap flat = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.ARGB_8888);
        flat.eraseColor(Color.WHITE); new Canvas(flat).drawBitmap(b, 0, 0, null); b.recycle();
        return flat;
    }

    /** De bewerking toepassen; src blijft ongemoeid. De langste kant van de uitkomst hooguit max. */
    static Bitmap toepassen(Bitmap src, FotoOps o, int max) throws FotoOps.Fout {
        Bitmap r = src;
        if (o.draai != 0) { Matrix m = new Matrix(); m.postRotate(o.draai); r = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true); }
        try {
            int w = r.getWidth(), h = r.getHeight(); int[] wh = o.maat(w, h, max); int W = wh[0], H = wh[1];
            float[] van = new float[8];
            for (int i = 0; i < 4; i++) { van[i * 2] = (float) (o.q[i * 2] * w); van[i * 2 + 1] = (float) (o.q[i * 2 + 1] * h); }
            float[] naar = {0, 0, W, 0, W, H, 0, H};
            Matrix m = new Matrix();
            if (!m.setPolyToPoly(van, 0, naar, 0, 4)) throw new FotoOps.Fout(Taal.T("Dit vlak lukt niet. Zet de hoeken op de hoeken van wat je wilt houden."));
            Bitmap uit = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); uit.eraseColor(Color.WHITE);
            Canvas c = new Canvas(uit);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            if (!o.kleurLeeg()) p.setColorFilter(new ColorMatrixColorFilter(kleur(o)));
            c.drawBitmap(r, m, p);
            if (o.vlakken.length > 0) {
                Paint zwart = new Paint(); zwart.setColor(Color.BLACK); zwart.setStyle(Paint.Style.FILL);
                for (double[] v : o.vlakken) { int[] p2 = vlak(v, W, H); c.drawRect(p2[0], p2[1], p2[2], p2[3], zwart); }
            }
            return uit;
        } finally { if (r != src) r.recycle(); }
    }

    /** Zwart vlak in pixels met een kleine marge, op dezelfde manier als fbVlakPix: liever iets te veel zwart. */
    static final double MARGE = 0.002;
    static int[] vlak(double[] v, int W, int H) {
        return new int[]{Math.max(0, (int) Math.floor((v[0] - MARGE) * W)), Math.max(0, (int) Math.floor((v[1] - MARGE) * H)),
                Math.min(W, (int) Math.ceil((v[0] + v[2] + MARGE) * W)), Math.min(H, (int) Math.ceil((v[1] + v[3] + MARGE) * H))};
    }

    /** ColorMatrix met dezelfde rekenwijze als FotoOps.kleur (bij grijs eerst de helderheid). */
    static ColorMatrix kleur(FotoOps o) {
        double[] ab = o.kleur(); float a = (float) ab[0], b = (float) ab[1];
        if (o.grijs) {
            float r = .299f * a, g = .587f * a, bl = .114f * a;
            return new ColorMatrix(new float[]{r, g, bl, 0, b, r, g, bl, 0, b, r, g, bl, 0, b, 0, 0, 0, 1, 0});
        }
        return new ColorMatrix(new float[]{a, 0, 0, 0, b, 0, a, 0, 0, b, 0, 0, a, 0, b, 0, 0, 0, 1, 0});
    }

    static byte[] jpeg(Bitmap b, int kwaliteit) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, kwaliteit, out); return out.toByteArray();
    }
    static String dataUrl(Bitmap b, int kwaliteit) {
        return "data:image/jpeg;base64," + android.util.Base64.encodeToString(jpeg(b, kwaliteit), android.util.Base64.NO_WRAP);
    }
}
