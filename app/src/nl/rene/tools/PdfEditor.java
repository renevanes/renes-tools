package nl.rene.tools;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.TreeMap;

/** Preview en export van lokaal getekende PDF-toevoegingen; geen handtekeningen bewaren buiten het document. */
final class PdfEditor {
    private PdfEditor() { }
    static void check(String revision) throws Exception {
        if (revision == null || revision.isEmpty() || !revision.equals(PdfTools.inRevision) || PdfTools.inPages <= 0)
            throw new Exception("De gekozen PDF is veranderd. Kies het document opnieuw.");
        if (!PdfTools.inLossless) throw new Exception("Deze PDF kan niet veilig bewerkt worden. Bewaar hem eerst opnieuw als PDF.");
    }
    static JSONObject preview(Context c, String revision, int page) throws Exception {
        PdfTools.IN.lock();
        try {
            check(revision);
            PdfSplit pdf = new PdfSplit(PdfTools.readAll(PdfTools.inFile(c), PdfTools.LOSSLESS_MAX));
            PdfOverlay.previous(pdf); PdfOverlay.Box box = PdfOverlay.editableBox(pdf, page);
            try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(PdfTools.inFile(c), ParcelFileDescriptor.MODE_READ_ONLY);
                 PdfRenderer renderer = new PdfRenderer(fd); PdfRenderer.Page p = renderer.openPage(page)) {
                if (Math.abs((double) p.getWidth() / p.getHeight() / (box.width() / box.height()) - 1) > .02)
                    throw new Exception("De pagina kan niet nauwkeurig worden bewerkt op dit toestel.");
                float scale = Math.min(1000f / p.getWidth(), 2400f / p.getHeight());
                int w = Math.max(1, Math.round(p.getWidth() * scale)), h = Math.max(1, Math.round(p.getHeight() * scale));
                Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                try {
                    b.eraseColor(Color.WHITE); p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    ByteArrayOutputStream out = new ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, 85, out);
                    return new JSONObject().put("revision", revision).put("page", page).put("width", box.width()).put("height", box.height())
                            .put("preview", "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP));
                } finally { b.recycle(); }
            }
        } finally { PdfTools.IN.unlock(); }
    }
    static File save(Context c, String revision, String json, String name) throws Exception {
        if (json == null || json.length() > 12_000_000) throw new Exception("Te veel toevoegingen om tegelijk op te slaan");
        PdfTools.IN.lock(); File part = null;
        try {
            check(revision); JSONArray layers = new JSONArray(json);
            if (layers.length() < 1 || layers.length() > 30) throw new Exception("Bewerk maximaal 30 pagina's tegelijk");
            PdfSplit pdf = new PdfSplit(PdfTools.readAll(PdfTools.inFile(c), PdfTools.LOSSLESS_MAX));
            Map<Integer, PdfOverlay.Image> images = new TreeMap<>();
            for (int i = 0; i < layers.length(); i++) {
                JSONObject item = layers.getJSONObject(i); int page = item.getInt("page");
                if (images.containsKey(page)) throw new Exception("Een pagina staat dubbel in de bewerkingen");
                PdfOverlay.editableBox(pdf, page);
                String png = item.getString("png");
                if (!png.startsWith("data:image/png;base64,") || png.length() > 3_000_000) throw new Exception("Ongeldige tekenlaag");
                byte[] bytes = Base64.decode(png.substring(22), Base64.DEFAULT);
                BitmapFactory.Options size = new BitmapFactory.Options(); size.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, size);
                if (size.outWidth < 1 || size.outHeight < 1 || size.outWidth > 4096 || size.outHeight > 4096 || (long) size.outWidth * size.outHeight > 6_000_000)
                    throw new Exception("De tekenlaag is te groot");
                Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (b == null) throw new Exception("De tekenlaag is niet te lezen");
                try {
                    int[] pixels = new int[b.getWidth() * b.getHeight()]; b.getPixels(pixels, 0, b.getWidth(), 0, 0, b.getWidth(), b.getHeight());
                    images.put(page, new PdfOverlay.Image(b.getWidth(), b.getHeight(), pixels));
                } finally { b.recycle(); }
            }
            File out = PdfTools.outFile(c, PdfTools.base(name == null || name.trim().isEmpty() ? PdfTools.inName + " bewerkt" : name));
            part = new File(out.getParentFile(), out.getName() + "." + System.nanoTime() + ".part");
            try (java.io.OutputStream stream = new java.io.BufferedOutputStream(new FileOutputStream(part))) { PdfOverlay.write(pdf, images, stream); }
            if (!part.renameTo(out)) throw new Exception("De bewerkte PDF kan niet worden opgeslagen");
            return out;
        } finally { if (part != null) part.delete(); PdfTools.IN.unlock(); }
    }
}
