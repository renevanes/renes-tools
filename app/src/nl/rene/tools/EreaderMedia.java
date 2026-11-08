package nl.rene.tools;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.json.JSONObject;
import org.w3c.dom.Element;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Alleen bytes uit het gekozen boek. Rasterbeelden worden begrensd en opnieuw gecodeerd, SVG's opgeschoond. */
final class EreaderMedia {
    private EreaderMedia() { }
    static JSONObject image(File archive, byte[] bytes, String mime, String base) throws Exception {
        if (mime.equals("image/svg+xml")) {
            try (ZipFile zip = new ZipFile(archive)) {
                String svg = vector(zip, bytes, base, new HashSet<>(), 0);
                Element root = EreaderBook.xml(svg.getBytes(StandardCharsets.UTF_8)).getDocumentElement();
                double width = 800, height = 600;
                String[] view = root.getAttribute("viewBox").trim().split("[\\s,]+");
                if (view.length == 4) { try { width = Double.parseDouble(view[2]); height = Double.parseDouble(view[3]); } catch (Exception ignored) { } }
                if (!(width > 0 && height > 0) || Double.isInfinite(width) || Double.isInfinite(height) || Double.isNaN(width) || Double.isNaN(height)) { width = 800; height = 600; }
                return new JSONObject().put("src", "data:image/svg+xml;base64," + EreaderBytes.encode(svg.getBytes(StandardCharsets.UTF_8)))
                        .put("width", width).put("height", height);
            }
        }
        return raster(bytes, mime);
    }
    private static String vector(ZipFile zip, byte[] bytes, String base, Set<String> active, int depth) throws Exception {
        if (depth > 4 || !active.add(base)) throw new Exception("svg");
        try {
            return EreaderSvg.clean(bytes, source -> {
                if (source.startsWith("data:")) {
                    int comma = source.indexOf(','); String head = comma < 0 ? "" : source.substring(0, comma);
                    if (!head.matches("data:image/(?:png|jpeg|gif|webp);base64") || source.length() > 6 * 1024 * 1024) return "";
                    byte[] data = EreaderBytes.decode(source.substring(comma + 1));
                    return raster(data, EreaderBytes.mime(data, "")).getString("src");
                }
                String path = EreaderBook.path(base, source); ZipEntry entry = zip.getEntry(path); if (entry == null || entry.isDirectory()) return "";
                byte[] data; try (InputStream in = zip.getInputStream(entry)) { data = EreaderBook.read(in, EreaderBook.MAX_ENTRY); }
                String mime = EreaderBytes.mime(data, path);
                if (mime.equals("image/svg+xml")) return "data:image/svg+xml;base64," + EreaderBytes.encode(vector(zip, data, path, active, depth + 1).getBytes(StandardCharsets.UTF_8));
                return raster(data, mime).getString("src");
            });
        } finally { active.remove(base); }
    }
    private static JSONObject raster(byte[] bytes, String mime) throws Exception {
        if (!mime.matches("image/(png|jpeg|gif|webp)")) throw new Exception(Taal.T("Deze afbeelding kan niet worden weergegeven"));
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth < 1 || options.outHeight < 1 || (long)options.outWidth * options.outHeight > 32_000_000) throw new Exception(Taal.T("Deze afbeelding is te groot of beschadigd"));
        int width = options.outWidth, height = options.outHeight; options.inSampleSize = 1;
        while (Math.max(width, height) / options.inSampleSize > 2400) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false; Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null) throw new Exception(Taal.T("Deze afbeelding kan niet worden weergegeven"));
        try {
            boolean alpha = bitmap.hasAlpha(); ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!bitmap.compress(alpha ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 90, out) || out.size() > 8 * 1024 * 1024) throw new Exception(Taal.T("Deze afbeelding is te groot of beschadigd"));
            return new JSONObject().put("src", "data:image/" + (alpha ? "png" : "jpeg") + ";base64," + EreaderBytes.encode(out.toByteArray()))
                    .put("width", width).put("height", height);
        } finally { bitmap.recycle(); }
    }
}
