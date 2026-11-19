package nl.rene.tools;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Hoesjes en zenderlogo's voor de speler (melding, vergrendelscherm, widget, zwevend venster, de interface) en de kleur
 * daarvan. In het geheugen klein (hooguit 512 px); op schijf hooguit 1024 px, scherp genoeg voor de grote hoes in de
 * speler: de interface toont dat bestand (MainActivity, /art) in plaats van het plaatje van internet (vaak 3000 px en
 * een paar MB). Altijd op een achtergrondthread aanroepen (netwerk).
 */
final class Art {
    private Art() { }

    static final int MAX_PX = ArtBestand.MAX_PX;
    static final int DISK_PX = ArtBestand.DISK_PX;
    /** Hooguit 12 plaatjes van ≤ 1 MB in het geheugen (de rest staat op schijf). */
    private static final android.util.LruCache<String, Bitmap> MEM = new android.util.LruCache<>(12);
    private static final java.util.Map<String, Integer> COLORS = java.util.Collections.synchronizedMap(new java.util.HashMap<String, Integer>());

    static File dir(Context c) { File d = new File(c.getCacheDir(), "art"); d.mkdirs(); return d; }

    /** Het bestand op schijf (hooguit DISK_PX). Een nieuwe naam (.art): de oude .img-bestanden waren maar 512 px. */
    private static File cacheFile(Context c, String url) { return new File(dir(c), Podcasts.hash(url) + ".art"); }

    /** Het bewaarde bestand voor de interface (zonder netwerk), of null als het er (nog) niet is. */
    static File file(Context c, String url) {
        if (!usable(url)) return null;
        File f = cacheFile(c, norm(url));
        return f.isFile() && f.length() > 0 ? f : null;
    }

    private static int sample(int w, int h, int max) { return ArtBestand.sample(w, h, max); }

    /** Bestand van schijf, verkleind tot hooguit MAX_PX (voor het geheugen), of null. */
    private static Bitmap decodeSmall(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int s = sample(o.outWidth, o.outHeight, MAX_PX);
        o = new BitmapFactory.Options(); o.inSampleSize = s;
        return fit(BitmapFactory.decodeFile(f.getPath(), o), MAX_PX);
    }

    static boolean usable(String url) { return ArtBestand.usable(url); }

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

    /** Mislukte adressen: tien minuten niet opnieuw proberen (anders wacht de widget steeds op een time-out). */
    private static final java.util.Map<String, Long> FAILED = java.util.Collections.synchronizedMap(new java.util.HashMap<String, Long>());

    /** Alleen uit het geheugen of van schijf (geen netwerk), of null. */
    static Bitmap peek(Context c, String url) {
        if (!usable(url)) return null;
        url = norm(url);
        Bitmap b = MEM.get(url);
        if (b != null) return b;
        File f = cacheFile(c, url);
        if (!f.isFile()) return null;
        try { b = decodeSmall(f); } catch (Throwable e) { return null; } // te weinig geheugen: dan maar zonder
        if (b != null) MEM.put(url, b);
        return b;
    }

    /** Wat peek niet heeft: op de achtergrond ophalen; daarna done (alleen als het lukte). */
    static void fetch(Context c, String url, Runnable done) {
        if (!usable(url) || peek(c, url) != null) return;
        Long t = FAILED.get(norm(url));
        if (t != null && System.currentTimeMillis() - t < 600_000) return;
        final Context app = c.getApplicationContext();
        FETCH.execute(() -> { if (load(app, url) != null && done != null) done.run(); });
    }

    private static final java.util.concurrent.ExecutorService FETCH =
            java.util.concurrent.Executors.newFixedThreadPool(2, r -> new Thread(App.veilig(r), "art-fetch"));

    private static Bitmap loadLocked(Context c, String url) {
        Long failedAt = FAILED.get(url);
        if (failedAt != null && System.currentTimeMillis() - failedAt < 600_000) return null;
        Bitmap b = loadNow(c, url);
        if (b == null) FAILED.put(url, System.currentTimeMillis()); else FAILED.remove(url);
        return b;
    }

    private static Bitmap loadNow(Context c, String url) {
        File f = cacheFile(c, url);
        Bitmap mem = MEM.get(url);
        if (mem != null && f.isFile()) return mem;
        // Staat hij wel in het geheugen maar niet (meer) op schijf (opgeruimd, of nog een .img van vroeger): opnieuw
        // ophalen voor de interface; lukt dat niet, dan blijft het exemplaar uit het geheugen bruikbaar.
        try {
            if (mem == null && f.isFile()) {
                Bitmap b = decodeSmall(f);
                if (b != null) { f.setLastModified(System.currentTimeMillis()); MEM.put(url, b); return b; }
            }
            java.net.HttpURLConnection h = Podcasts.open(url, 15000);
            byte[] data;
            try (InputStream in = new PodcastFeed.Limited(h.getInputStream(), 8_000_000)) { data = SelfTest.readAll(in); }
            finally { h.disconnect(); }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0 || (long) o.outWidth * o.outHeight > 40_000_000L) return mem;
            int s = sample(o.outWidth, o.outHeight, DISK_PX); // eerst uitrekenen: daarna is o een nieuw, leeg object
            o = new BitmapFactory.Options(); o.inSampleSize = s;
            Bitmap big = fit(BitmapFactory.decodeByteArray(data, 0, data.length, o), DISK_PX);
            data = null; // het origineel (soms een paar MB) niet langer vasthouden
            if (big == null) return mem;
            File tmp = new File(f.getPath() + ".tmp"); // eerst apart schrijven: een half bestand komt nooit in de cache
            try (FileOutputStream os = new FileOutputStream(tmp)) { big.compress(big.hasAlpha() ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 85, os); }
            catch (Exception ignored) { }
            if (tmp.length() > 0) tmp.renameTo(f); else tmp.delete();
            trim(c);
            if (mem != null) { big.recycle(); return mem; }
            Bitmap b = small(big, MAX_PX);
            if (b != big) big.recycle();
            MEM.put(url, b);
            return b;
        } catch (Throwable e) { return mem; }
    }

    private static final android.util.LruCache<String, Bitmap> SMALL = new android.util.LruCache<>(8);

    /** Klein exemplaar zonder netwerk (widget, zwevend venster); steeds hetzelfde object, of null. */
    static Bitmap smallCached(Context c, String url, int max) {
        if (!usable(url)) return null;
        String k = norm(url) + "|" + max;
        Bitmap s = SMALL.get(k);
        if (s != null) return s;
        s = small(peek(c, url), max);
        if (s != null) SMALL.put(k, s);
        return s;
    }

    /** Zoals small, maar het grotere tussenresultaat wordt meteen vrijgegeven (alleen voor een eigen, net gemaakte bitmap). */
    private static Bitmap fit(Bitmap b, int max) {
        Bitmap s = small(b, max);
        if (b != null && s != b) b.recycle();
        return s;
    }

    /** Kleiner exemplaar (voor de mediasessie: gaat naar andere processen). */
    static Bitmap small(Bitmap b, int max) {
        if (b == null || Math.max(b.getWidth(), b.getHeight()) <= max) return b;
        float k = max / (float) Math.max(b.getWidth(), b.getHeight());
        return Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)), Math.max(1, Math.round(b.getHeight() * k)), true);
    }

    /** Kleur zonder netwerk (alleen als het plaatje al bewaard is), of -1. */
    static int colorCached(Context c, String url) {
        if (!usable(url)) return -1;
        Integer k = COLORS.get(norm(url));
        if (k != null) return k;
        Bitmap b = peek(c, url);
        if (b == null) return -1;
        int col = color(b);
        COLORS.put(norm(url), col);
        return col;
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

    /** Hooguit 300 plaatjes en 60 MB op schijf; de oudste gaan weg (tot 250 en 45 MB). Bestanden van het oude formaat
     *  (.img, 512 px) meteen. */
    private static void trim(Context c) {
        File[] oud = dir(c).listFiles((d, n) -> n.endsWith(".img"));
        if (oud != null) for (File f : oud) f.delete();
        File[] l = dir(c).listFiles();
        if (l == null) return;
        final long[] t = new long[l.length], len = new long[l.length]; // eerst vastleggen: het kan intussen veranderen
        for (int i = 0; i < l.length; i++) { t[i] = l[i].lastModified(); len[i] = l[i].length(); }
        for (int i : ArtBestand.weg(t, len, 300, 60_000_000L, 250, 45_000_000L)) l[i].delete();
    }
}
