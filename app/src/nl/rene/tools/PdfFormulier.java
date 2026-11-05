package nl.rene.tools;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Map;

/** Vult gebundelde, vlakke formulieren met dezelfde tekenlagen als de PDF-editor. Geen netwerk of conceptopslag. */
final class PdfFormulier {
    private PdfFormulier() { }

    static String asset(String id) throws Exception {
        if (id == null || !id.matches("[a-z][a-z0-9-]{0,49}")) throw new Exception("Onbekend formulier");
        return "formulieren/" + id + ".pdf";
    }

    static File template(Context c, String id) throws Exception {
        String path = asset(id);
        File file = File.createTempFile("formulier-", ".pdf", c.getCacheDir());
        try (InputStream in = c.getAssets().open(path); FileOutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[65536]; int count; long size = 0;
            while ((count = in.read(buffer)) != -1) {
                size += count;
                if (size > PdfTools.LOSSLESS_MAX) throw new Exception("Formulier is te groot");
                out.write(buffer, 0, count);
            }
            return file;
        } catch (Throwable e) { file.delete(); throw e; }
    }

    static JSONObject preview(Context c, String id, int page) throws Exception {
        File file = template(c, id);
        try { return PdfEditor.preview(file, page); }
        finally { file.delete(); }
    }

    static File make(Context c, String id, String json, String name) throws Exception {
        if (json == null || json.length() > 12_000_000) throw new Exception("Te veel toevoegingen om tegelijk op te slaan");
        File template = template(c, id), part = null;
        try {
            PdfSplit pdf = new PdfSplit(PdfTools.readAll(template, PdfTools.LOSSLESS_MAX));
            JSONArray layers = new JSONArray(json);
            if (layers.length() != pdf.pageCount()) throw new Exception("Het formulier is niet volledig");
            Map<Integer, PdfOverlay.Image> images = PdfEditor.layers(pdf, layers);
            File out = PdfTools.outFile(c, PdfTools.base(name));
            part = File.createTempFile("formulier-", ".part", out.getParentFile());
            try (java.io.OutputStream stream = new java.io.BufferedOutputStream(new FileOutputStream(part))) {
                PdfOverlay.write(pdf, images, stream);
            }
            if (!part.renameTo(out)) throw new Exception("De ingevulde PDF kan niet worden opgeslagen");
            return out;
        } finally { template.delete(); if (part != null) part.delete(); }
    }
}
