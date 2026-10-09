package nl.rene.tools;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Hoesjes en zenderlogo's voor de speler (melding, vergrendelscherm, widget, zwevend venster) en de kleur
 * daarvan. Klein gehouden (hooguit 512 px), in het geheugen en op schijf bewaard. Altijd op een achtergrondthread
 * aanroepen (netwerk).
 */
final class Art {
    private Art() { }

    static final int MAX_PX = 512;
    /** Hooguit 12 plaatjes van ≤ 1 MB in het geheugen (de rest staat op schijf). */
    private static final android.util.LruCache<String, Bitmap> MEM = new android.util.LruCache<>(12);
    private static final java.util.Map<String, Integer> COLORS = java.util.Collections.synchronizedMap(new java.util.HashMap<String, Integer>());

    static File dir(Context c) { File d = new File(c.getCacheDir(), "art"); d.mkdirs(); return d; }

    static boolean usable(String url) { return url != null && (url.startsWith("https://") || url.startsWith("http://")) && url.length() < 2000; }

    /** Zoals de interface het toont: altijd via https (één sleutel voor hetzelfde plaatje). */
    static String norm(String url) { return url != null && url.startsWith("http://") ? "https://" + url.substring(7) : url; }

    private static final java.util.concurrent.ConcurrentHashMap<String, Object> LOCKS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Het plaatje (verkleind), of null. Hetzelfde plaatje wordt nooit twee keer tegelijk opgehaald. */
    static Bitmap load(Context c, String url) {
        if (!usable(url)) return null;
        url = norm(url);
        Object lock = LOCKS.computeIfAbsent(url, k -> new Object());
        try { synchronized (lock) { return loadLocked(c, url); } }
        finally { LOCKS.remove(url, lock); }
    }

    private static Bitmap loadLocked(Context c, String url) {
        Bitmap b = MEM.get(url);
        if (b != null) return b;
        File f = new File(dir(c), Podcasts.hash(url) + ".img");
        if (f.isFile()) {
            b = BitmapFactory.decodeFile(f.getPath());
            if (b != null) { f.setLastModified(System.currentTimeMillis()); MEM.put(url, b); return b; }
        }
        try {
            java.net.HttpURLConnection h = Podcasts.open(url, 15000);
            byte[] data;
            try (InputStream in = new PodcastFeed.Limited(h.getInputStream(), 8_000_000)) { data = SelfTest.readAll(in); }
            finally { h.disconnect(); }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0 || (long) o.outWidth * o.outHeight > 40_000_000L) return null;
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= MAX_PX) s *= 2;
            o = new BitmapFactory.Options(); o.inSampleSize = s;
            b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (b == null) return null;
            if (Math.max(b.getWidth(), b.getHeight()) > MAX_PX) {
                float k = MAX_PX / (float) Math.max(b.getWidth(), b.getHeight());
                b = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)), Math.max(1, Math.round(b.getHeight() * k)), true);
            }
            File tmp = new File(f.getPath() + ".tmp"); // eerst apart schrijven: een half bestand komt nooit in de cache
            try (FileOutputStream os = new FileOutputStream(tmp)) { b.compress(b.hasAlpha() ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 88, os); }
            catch (Exception ignored) { }
            if (tmp.length() > 0) tmp.renameTo(f); else tmp.delete();
            trim(c);
            MEM.put(url, b);
            return b;
        } catch (Throwable e) { return null; }
    }

    /** Kleiner exemplaar (voor de mediasessie: gaat naar andere processen). */
    static Bitmap small(Bitmap b, int max) {
        if (b == null || Math.max(b.getWidth(), b.getHeight()) <= max) return b;
        float k = max / (float) Math.max(b.getWidth(), b.getHeight());
        return Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)), Math.max(1, Math.round(b.getHeight() * k)), true);
    }

    /** Kleur voor achter de speler (0xRRGGBB), of -1 als er geen plaatje is. */
    static int color(Context c, String url) {
        if (!usable(url)) return -1;
        url = norm(url);
        Integer k = COLORS.get(url);
        if (k != null) return k;
        Bitmap b = load(c, url);
        if (b == null) return -1;
        int col = color(b);
        COLORS.put(url, col);
        return col;
    }

    static int color(Bitmap b) {
        Bitmap s = Bitmap.createScaledBitmap(b, 48, 48, true);
        int[] px = new int[48 * 48];
        s.getPixels(px, 0, 48, 0, 0, 48, 48);
        return ArtColor.of(px);
    }

    /** Hooguit 300 plaatjes op schijf; de oudste gaan weg. */
    private static void trim(Context c) {
        File[] l = dir(c).listFiles();
        if (l == null || l.length <= 300) return;
        final long[] t = new long[l.length]; // eerst de tijden vastleggen: ze kunnen intussen veranderen
        Integer[] idx = new Integer[l.length];
        for (int i = 0; i < l.length; i++) { t[i] = l[i].lastModified(); idx[i] = i; }
        java.util.Arrays.sort(idx, (x, y) -> Long.compare(t[x], t[y]));
        for (int i = 0; i < l.length - 250; i++) l[idx[i]].delete();
    }
}
