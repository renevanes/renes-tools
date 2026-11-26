package nl.rene.tools;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Brug voor Foto bewerken (Android.foto…): een foto kiezen (of via Bron uit de kluis of Gemaakt), een voorbeeld naar de
 * interface (hooguit VOORBEELD px; daar wordt bijgesneden, gedraaid en verbeterd) en bewaren: dezelfde bewerking op de
 * foto zelf (hooguit UIT px), als JPG onder Gemaakt (daarna vanzelf de kluis in, zoals elk resultaat van de PDF-tools).
 * De gekozen foto staat alleen zolang het scherm open is in cache/foto-bewerken; weg bij verlaten, een nieuwe keuze of opstarten.
 */
public class FotoBridge extends FeatureBridge {
    static final int REQ_FOTO = 84;
    static final long MAX_BESTAND = 40L * 1024 * 1024;
    static final int VOORBEELD = 1600, UIT = 3200;
    private static final Object LOCK = new Object();
    private static String sessie = "";
    private static File bron;

    FotoBridge(MainActivity act) { super(act); }

    static File map(Context c) { return new File(c.getCacheDir(), "foto-bewerken"); }

    /** Alles weg (verlaten, nieuwe keuze, opstarten). */
    static void wis(Context c) {
        synchronized (LOCK) { sessie = ""; bron = null; }
        File[] fs = map(c).listFiles();
        if (fs != null) for (File f : fs) { if (!f.delete()) f.deleteOnExit(); }
    }

    /** Bij het starten: wat van een vorige keer bleef staan (een ander venster dat nu bewerkt, blijft ongemoeid). */
    static void wisBijStart(Context c) { synchronized (LOCK) { if (!sessie.isEmpty()) return; } wis(c); }

    private static String fout(Context c, Throwable e) {
        String m = e instanceof FotoOps.Fout ? e.getMessage() : e instanceof OutOfMemoryError ? Taal.T("De foto is te groot voor het geheugen van de telefoon") : Taal.T("Deze foto kan niet worden geopend");
        return "{\"error\":" + JSONObject.quote(Taal.tx(c, m)) + "}";
    }

    @JavascriptInterface public void fotoKies() {
        if (!PlayPolicy.allows(ctx, "foto")) return;
        a.h.post(() -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
            try { a.startActivityForResult(i, REQ_FOTO); }
            catch (Exception e) { a.js("onFotoGekozen", "{\"error\":" + JSONObject.quote(Taal.tx(ctx, Taal.T("Bestanden kiezen lukt niet op deze telefoon"))) + "}"); }
        });
    }

    /** Gekozen (vanuit MainActivity.onActivityResult); u == null = geannuleerd. Antwoord onFotoGekozen {sessie, w, h, image, naam}. */
    static void opGekozen(MainActivity a, Uri u) {
        if (u == null) { a.js("onFotoGekozen", "{\"cancelled\":true}"); return; }
        final Context ctx = a.getApplicationContext();
        new Thread(Doorwerken.door("foto-kiezen", () -> {
            String r;
            try (Voortgang v = Voortgang.van(a, "foto", 1)) {
                wis(ctx);
                byte[] b;
                try (java.io.InputStream in = v.open(a, u)) { b = Facturen.lees(in, MAX_BESTAND); }
                v.stap("verwerken", 0, 0);
                String naam = PdfTools.base(PdfTools.displayName(ctx, u));
                Bitmap voorbeeld = FotoBewerk.lees(b, VOORBEELD);
                File d = map(ctx); if (!d.isDirectory() && !d.mkdirs()) throw new FotoOps.Fout(Taal.T("Werkbestand maken lukt niet"));
                File f = new File(d, java.util.UUID.randomUUID().toString());
                try (FileOutputStream o = new FileOutputStream(f)) { o.write(b); }
                java.util.Arrays.fill(b, (byte) 0);
                String s = java.util.UUID.randomUUID().toString();
                synchronized (LOCK) { sessie = s; bron = f; }
                try {
                    r = new JSONObject().put("sessie", s).put("w", voorbeeld.getWidth()).put("h", voorbeeld.getHeight())
                            .put("naam", naam).put("image", FotoBewerk.dataUrl(voorbeeld, 90)).toString();
                } finally { voorbeeld.recycle(); }
            } catch (Throwable t) { r = fout(ctx, t); }
            a.js("onFotoGekozen", r);
        }), "foto-kiezen").start();
    }

    /** De bewerking op de foto zelf toepassen en onder Gemaakt zetten. Antwoord onFotoBewaard {name} of {error}. */
    @JavascriptInterface public void fotoBewaar(String s, String ops, String naam) {
        if (!PlayPolicy.allows(ctx, "foto")) return;
        new Thread(Doorwerken.door("foto-bewaren", () -> {
            String r; Bitmap b = null, uit = null;
            try {
                File f;
                synchronized (LOCK) { if (s == null || s.isEmpty() || !s.equals(sessie) || bron == null) throw new FotoOps.Fout(Taal.T("Kies de foto opnieuw")); f = bron; }
                FotoOps o = FotoOps.lees(ops);
                byte[] bytes; try (java.io.FileInputStream in = new java.io.FileInputStream(f)) { bytes = Facturen.lees(in, MAX_BESTAND); }
                try { b = FotoBewerk.lees(bytes, UIT); uit = FotoBewerk.toepassen(b, o, UIT); }
                catch (OutOfMemoryError e) { if (b != null) b.recycle(); if (uit != null) uit.recycle(); b = FotoBewerk.lees(bytes, UIT / 2); uit = FotoBewerk.toepassen(b, o, UIT / 2); }
                String basis = PdfTools.base(naam == null || naam.trim().isEmpty() ? Taal.t(ctx, "Foto") : naam);
                File out = PdfTools.outFile(ctx, basis, ".jpg");
                File tmp = new File(out.getParentFile(), out.getName() + ".tmp");
                try (FileOutputStream o2 = new FileOutputStream(tmp)) { o2.write(FotoBewerk.jpeg(uit, 92)); }
                if (!tmp.renameTo(out)) { tmp.delete(); throw new FotoOps.Fout(Taal.T("Opslaan lukte niet.")); }
                r = new JSONObject().put("name", out.getName()).put("w", uit.getWidth()).put("h", uit.getHeight()).toString();
            } catch (Throwable t) { r = fout(ctx, t); }
            finally { if (b != null) b.recycle(); if (uit != null) uit.recycle(); }
            a.js("onFotoBewaard", r);
        }), "foto-bewaren").start();
    }

    @JavascriptInterface public void fotoWis() { new Thread(App.veilig(() -> wis(ctx)), "foto-wissen").start(); }
}
