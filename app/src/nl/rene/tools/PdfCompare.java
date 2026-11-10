package nl.rene.tools;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Twee documenten vergelijken (PDF of foto): de tekst (verschillen per woord) en het beeld per pagina (wat er weg is en wat
 * erbij kwam). Alles op de telefoon: de twee bestanden staan in de cache (pdf-compare/), en gaan weg bij sluiten of een
 * nieuwe keuze; uit de kluis worden ze daar alleen tijdelijk ontsleuteld. Tekst komt uit de PDF (Android 15+) of anders van
 * de lokale tekstherkenning (RijbewijsOcr.pageText). Het zuivere deel (tokens, diff, pixels) is getest in PdfCompareTest.
 */
final class PdfCompare {
    static final int MAX_PAGES = 60;
    static final long MAX_BYTES = 100L * 1024 * 1024;
    static final int EDGE = 1400, SCAN_EDGE = 360;

    static final class Slot {
        final File file; final String name; final int pages; final boolean pdf; final String kluis; final int version;
        Slot(File file, String name, int pages, boolean pdf, String kluis, int version) { this.file = file; this.name = name; this.pages = pages; this.pdf = pdf; this.kluis = kluis; this.version = version; }
    }

    private final Slot[] slots = new Slot[2];
    private File dir;
    private volatile int gen;

    int gen() { return gen; }

    synchronized File work(Context c) throws Exception {
        if (dir == null) {
            File root = new File(c.getCacheDir(), "pdf-compare");
            dir = new File(root, java.util.UUID.randomUUID().toString());
            if (!dir.mkdirs()) throw new Exception("Werkbestand maken lukt niet");
        }
        return new File(dir, java.util.UUID.randomUUID().toString());
    }

    /** Een bestand (al in de werkmap van work()) als document A (0) of B (1). */
    synchronized JSONObject set(int slot, File f, String name, String kluis, int version) throws Exception {
        if (slot < 0 || slot > 1) throw new Exception("Ongeldige keuze");
        try {
            if (f.length() > MAX_BYTES) throw new Exception("Bestand is te groot");
            boolean pdf = isPdf(f); int pages;
            if (pdf) {
                try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY); PdfRenderer r = new PdfRenderer(fd)) { pages = r.getPageCount(); }
                catch (SecurityException e) { throw new Exception("Deze PDF is beveiligd met een wachtwoord"); }
            } else {
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options(); o.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
                if (o.outWidth <= 0 || o.outHeight <= 0) throw new Exception("Kies een PDF of een foto");
                pages = 1;
            }
            if (pages < 1) throw new Exception("Dit document heeft geen pagina's");
            if (slots[slot] != null) slots[slot].file.delete();
            slots[slot] = new Slot(f, name == null || name.isEmpty() ? "document" : name, pages, pdf, kluis == null ? "" : kluis, version);
            gen++;
            return info();
        } catch (Exception e) { f.delete(); throw e; }
    }

    synchronized void swap() { Slot s = slots[0]; slots[0] = slots[1]; slots[1] = s; gen++; }

    synchronized JSONObject info() throws Exception {
        JSONObject o = new JSONObject();
        for (int i = 0; i < 2; i++) if (slots[i] != null) o.put(i == 0 ? "a" : "b", new JSONObject().put("name", slots[i].name).put("pages", slots[i].pages)
                .put("pdf", slots[i].pdf).put("kluis", slots[i].kluis).put("version", slots[i].version));
        return o;
    }

    synchronized void clear() {
        gen++; slots[0] = null; slots[1] = null;
        if (dir != null) { PdfTools.clear(dir); dir.delete(); dir = null; }
    }

    private static boolean cleaned;
    /** Werkmappen van een eerdere keer (bijv. na een crash): weg. Eén keer per proces (losse vensters delen de cache). */
    static synchronized void cleanStale(Context c) {
        if (cleaned) return;
        cleaned = true;
        File[] ds = new File(c.getCacheDir(), "pdf-compare").listFiles();
        if (ds != null) for (File d : ds) { PdfTools.clear(d); d.delete(); }
    }

    private synchronized Slot[] both() throws Exception {
        if (slots[0] == null || slots[1] == null) throw new Exception("Kies eerst twee documenten");
        return new Slot[]{slots[0], slots[1]};
    }

    static boolean isPdf(File f) throws Exception {
        byte[] h = new byte[5];
        try (FileInputStream in = new FileInputStream(f)) { return in.read(h) == 5 && "%PDF-".equals(new String(h, java.nio.charset.StandardCharsets.US_ASCII)); }
    }

    // ---------- tekst ----------

    /** Verschillen in de tekst. {ops:[[type,tekst]…] (0 gelijk, -1 alleen in A, 1 alleen in B), weg, erbij, ocr, pagesA, pagesB}. */
    JSONObject text(Context c, BooleanSupplier active, Voortgang v) throws Exception {
        Slot[] s = both(); int g = gen;
        BooleanSupplier run = () -> active.getAsBoolean() && g == gen;
        int total = Math.min(s[0].pages, MAX_PAGES) + Math.min(s[1].pages, MAX_PAGES);
        boolean[] ocr = {false};
        int[] done = {0};
        String a = pagesText(c, s[0], run, ocr, v, done, total), b = pagesText(c, s[1], run, ocr, v, done, total);
        if (!run.getAsBoolean()) throw new Exception("Gestopt");
        JSONObject r = diffText(a, b);
        return r.put("ocr", ocr[0]).put("pagesA", s[0].pages).put("pagesB", s[1].pages).put("limited", s[0].pages > MAX_PAGES || s[1].pages > MAX_PAGES);
    }

    private static String pagesText(Context c, Slot s, BooleanSupplier run, boolean[] ocr, Voortgang v, int[] done, int total) throws Exception {
        StringBuilder out = new StringBuilder();
        if (s.pdf) {
            PdfDoc doc = PdfDoc.canText() ? new PdfDoc(s.file, s.name, null) : null;
            try {
                for (int i = 0; i < Math.min(s.pages, MAX_PAGES) && run.getAsBoolean(); i++) {
                    String t = doc == null ? "" : doc.text(i);
                    if (t.trim().isEmpty()) {
                        Bitmap b = render(s, i, 2000);
                        try { t = RijbewijsOcr.pageText(c, b, run); ocr[0] = true; } finally { b.recycle(); }
                    }
                    out.append(t).append('\n');
                    v.stap("bezig", ++done[0], total);
                }
            } finally { if (doc != null) doc.close(); }
        } else {
            Bitmap b = render(s, 0, 2400);
            try { out.append(RijbewijsOcr.pageText(c, b, run)); ocr[0] = true; } finally { b.recycle(); }
            v.stap("bezig", ++done[0], total);
        }
        return out.toString();
    }

    // ---------- beeld ----------

    /** Per pagina hoeveel er anders is (grof, snel), om de pagina's met verschillen te vinden. {pages:[{i,score}], a, b}. */
    JSONObject scan(BooleanSupplier active, Voortgang v) throws Exception {
        Slot[] s = both(); int g = gen;
        int n = Math.min(Math.min(s[0].pages, s[1].pages), MAX_PAGES);
        JSONArray pages = new JSONArray();
        for (int i = 0; i < n; i++) {
            if (!active.getAsBoolean() || g != gen) throw new Exception("Gestopt");
            int[] r = pair(s, i, SCAN_EDGE, null);
            pages.put(new JSONObject().put("i", i).put("score", r[0]));
            v.stap("bezig", i + 1, n);
        }
        return new JSONObject().put("pages", pages).put("a", s[0].pages).put("b", s[1].pages);
    }

    /** Eén pagina groot: mode verschil (B lichtgrijs, rood = alleen in A, groen = alleen in B), a of b (de pagina zelf). */
    JSONObject page(int i, String mode) throws Exception {
        Slot[] s = both();
        if (i < 0 || i >= Math.max(s[0].pages, s[1].pages)) throw new Exception("Ongeldige pagina");
        Bitmap[] out = new Bitmap[1];
        JSONObject o = new JSONObject().put("i", i).put("mode", mode);
        try {
            if ("a".equals(mode) || "b".equals(mode) || i >= Math.min(s[0].pages, s[1].pages)) {
                Slot one = "b".equals(mode) || i >= s[0].pages ? s[1] : s[0];
                if (i >= one.pages) throw new Exception("Deze pagina bestaat alleen in het andere document");
                out[0] = render(one, i, EDGE);
                o.put("only", i >= Math.min(s[0].pages, s[1].pages));
            } else {
                int[] r = pair(s, i, EDGE, out);
                o.put("score", r[0]);
            }
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            out[0].compress(Bitmap.CompressFormat.JPEG, 85, b);
            return o.put("w", out[0].getWidth()).put("h", out[0].getHeight())
                    .put("image", "data:image/jpeg;base64," + android.util.Base64.encodeToString(b.toByteArray(), android.util.Base64.NO_WRAP));
        } finally { if (out[0] != null) out[0].recycle(); }
    }

    /** Pagina i van A en B op dezelfde maat; geeft {score (promille anders)}; met keep: het verschilbeeld in keep[0]. */
    private static int[] pair(Slot[] s, int i, int edge, Bitmap[] keep) throws Exception {
        Bitmap a = render(s[0], i, edge), b = null, sb = null;
        try {
            b = render(s[1], i, edge);
            sb = b.getWidth() == a.getWidth() && b.getHeight() == a.getHeight() ? b : Bitmap.createScaledBitmap(b, a.getWidth(), a.getHeight(), true);
            int w = a.getWidth(), h = a.getHeight();
            int[] pa = new int[w * h], pb = new int[w * h], po = new int[w * h];
            a.getPixels(pa, 0, w, 0, 0, w, h); sb.getPixels(pb, 0, w, 0, 0, w, h);
            int changed = pixels(pa, pb, w, h, po);
            if (keep != null) { Bitmap o = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); o.setPixels(po, 0, w, 0, 0, w, h); keep[0] = o; }
            return new int[]{(int) Math.min(1000, Math.round(changed * 1000.0 / Math.max(1, w * h)))};
        } finally { a.recycle(); if (b != null) b.recycle(); if (sb != null && sb != b) sb.recycle(); }
    }

    static Bitmap render(Slot s, int i, int edge) throws Exception {
        Bitmap image;
        if (!s.pdf) {
            image = PdfTools.decode(s.file, edge);
            if (image == null) throw new Exception("Deze foto is niet te lezen");
        } else try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(s.file, ParcelFileDescriptor.MODE_READ_ONLY); PdfRenderer r = new PdfRenderer(fd); PdfRenderer.Page p = r.openPage(i)) {
            float scale = (float) edge / Math.max(p.getWidth(), p.getHeight());
            image = Bitmap.createBitmap(Math.max(1, Math.round(p.getWidth() * scale)), Math.max(1, Math.round(p.getHeight() * scale)), Bitmap.Config.ARGB_8888);
            image.eraseColor(Color.WHITE); p.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        }
        if (image.getConfig() == Bitmap.Config.ARGB_8888 && image.isMutable()) return image;
        Bitmap flat = Bitmap.createBitmap(image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);
        flat.eraseColor(Color.WHITE); new Canvas(flat).drawBitmap(image, 0, 0, null); image.recycle();
        return flat;
    }

    // ---------- zuiver (PdfCompareTest) ----------

    static final int WEG = 0xFFD32F2F, ERBIJ = 0xFF2E7D32;
    static final int DARK = 150;

    static int gray(int p) { return (((p >> 16) & 255) * 30 + ((p >> 8) & 255) * 59 + (p & 255) * 11) / 100; }

    /**
     * Verschilbeeld: B lichtgrijs als achtergrond; donker in A maar in B (ook een pixel ernaast) licht = weg (rood),
     * omgekeerd = erbij (groen). Een verschuiving van één pixel telt zo niet als verschil. Geeft het aantal verschillende pixels.
     */
    static int pixels(int[] a, int[] b, int w, int h, int[] out) {
        boolean[] da = new boolean[w * h], db = new boolean[w * h];
        int[] gb = new int[w * h];
        for (int k = 0; k < w * h; k++) { da[k] = gray(a[k]) < DARK; gb[k] = gray(b[k]); db[k] = gb[k] < DARK; }
        int changed = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int k = y * w + x;
            if (da[k] && !db[k] && !near(db, w, h, x, y)) { out[k] = WEG; changed++; }
            else if (db[k] && !da[k] && !near(da, w, h, x, y)) { out[k] = ERBIJ; changed++; }
            else { int v = 255 - (255 - gb[k]) * 35 / 100; out[k] = 0xFF000000 | (v << 16) | (v << 8) | v; }
        }
        return changed;
    }

    static boolean near(boolean[] dark, int w, int h, int x, int y) {
        for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
            int xx = x + dx, yy = y + dy;
            if (xx >= 0 && yy >= 0 && xx < w && yy < h && dark[yy * w + xx]) return true;
        }
        return false;
    }

    /** Regels zonder dubbele spaties en lege regels (tekst uit een PDF of van de herkenning heeft daar veel ruis in). */
    static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        for (String l : text.replace('\r', '\n').split("\n")) { String t = l.replaceAll("[\\s\\u00a0]+", " ").trim(); if (!t.isEmpty()) out.add(t); }
        return out;
    }

    static final int MAX_D = 2000, MAX_WORDS = 6000;

    /** Eerst per regel, dan binnen elk gewijzigd stuk per woord. */
    static JSONObject diffText(String a, String b) throws Exception {
        List<String> la = lines(a), lb = lines(b);
        int[][] ops = diff(la.toArray(new String[0]), lb.toArray(new String[0]), MAX_D);
        Out o = new Out();
        int i = 0;
        while (i < ops.length) {
            if (ops[i][0] == 0) { o.add(0, la.get(ops[i][1]) + "\n"); i++; continue; }
            List<String> wa = new ArrayList<>(), wb = new ArrayList<>();
            while (i < ops.length && ops[i][0] != 0) {
                if (ops[i][0] < 0) words(la.get(ops[i][1]), wa); else words(lb.get(ops[i][2]), wb);
                i++;
            }
            if (wa.size() > MAX_WORDS || wb.size() > MAX_WORDS) { for (String t : wa) o.add(-1, t); for (String t : wb) o.add(1, t); continue; }
            int[][] w = diff(wa.toArray(new String[0]), wb.toArray(new String[0]), MAX_D);
            for (int[] op : w) o.add(op[0], op[0] > 0 ? wb.get(op[2]) : wa.get(op[1]));
        }
        return new JSONObject().put("ops", o.ops).put("weg", o.weg).put("erbij", o.erbij).put("same", o.same);
    }

    /** Woorden van een regel, met een regeleinde als laatste "woord" (zodat de opmaak blijft). */
    static void words(String line, List<String> out) {
        String[] ws = line.split(" ");
        for (int k = 0; k < ws.length; k++) out.add(ws[k] + (k == ws.length - 1 ? "\n" : " "));
    }

    static final class Out {
        final JSONArray ops = new JSONArray(); int weg, erbij, same; int last = 99; StringBuilder cur;
        void add(int type, String text) throws Exception {
            String t = text.trim();
            if (!t.isEmpty()) { if (type < 0) weg++; else if (type > 0) erbij++; else same++; }
            if (type != last || cur == null) { cur = new StringBuilder(); last = type; ops.put(new JSONArray().put(type).put("")); }
            cur.append(text);
            ops.getJSONArray(ops.length() - 1).put(1, cur.toString());
        }
    }

    /**
     * Myers-diff (O((N+M)·D)). Geeft stappen {type, i, j}: 0 = gelijk (a[i] = b[j]), -1 = a[i] weg, 1 = b[j] erbij.
     * Zijn er meer dan maxD verschillen, dan alles weg en alles erbij (grof, maar nooit traag of te veel geheugen).
     */
    static int[][] diff(String[] a, String[] b, int maxD) {
        int n = a.length, m = b.length, max = n + m, off = max + 1;
        int lim = Math.min(max, maxD);
        int[] v = new int[2 * max + 3];
        List<int[]> trace = new ArrayList<>();
        int found = -1;
        for (int d = 0; d <= lim; d++) {
            trace.add(java.util.Arrays.copyOfRange(v, off - d - 1, off + d + 2)); // alleen k = -d-1 … d+1: geheugen ~D²
            for (int k = -d; k <= d; k += 2) {
                int x = k == -d || (k != d && v[off + k - 1] < v[off + k + 1]) ? v[off + k + 1] : v[off + k - 1] + 1;
                int y = x - k;
                while (x < n && y < m && a[x].equals(b[y])) { x++; y++; }
                v[off + k] = x;
                if (x >= n && y >= m) { found = d; break; }
            }
            if (found >= 0) break;
        }
        List<int[]> steps = new ArrayList<>();
        if (found < 0) {
            for (int i = 0; i < n; i++) steps.add(new int[]{-1, i, -1});
            for (int j = 0; j < m; j++) steps.add(new int[]{1, -1, j});
            return steps.toArray(new int[0][]);
        }
        int x = n, y = m;
        for (int d = found; d > 0; d--) {
            int[] pv = trace.get(d); // v zoals hij was vóór ronde d (de uitkomst van d - 1), vanaf k = -d-1
            int k = x - y, o = d + 1;
            int pk = k == -d || (k != d && pv[o + k - 1] < pv[o + k + 1]) ? k + 1 : k - 1;
            int px = pv[o + pk], py = px - pk;
            while (x > px && y > py) { x--; y--; steps.add(new int[]{0, x, y}); }
            if (x == px) { y--; steps.add(new int[]{1, -1, y}); } else { x--; steps.add(new int[]{-1, x, -1}); }
        }
        while (x > 0 && y > 0) { x--; y--; steps.add(new int[]{0, x, y}); }
        java.util.Collections.reverse(steps);
        return steps.toArray(new int[0][]);
    }
}
