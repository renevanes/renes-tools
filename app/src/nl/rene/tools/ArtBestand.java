package nl.rene.tools;

/** Rekenwerk voor de hoesjes van Art, zonder Android (getest in ArtCacheTest). */
final class ArtBestand {
    private ArtBestand() { }

    /** In het geheugen (melding, widget, zwevend venster, de kleur). */
    static final int MAX_PX = 512;
    /** Op schijf: ook voor de grote hoes in de speler (tot ±1000 beeldpunten breed op een telefoon). */
    static final int DISK_PX = 1024;

    /** Welke inSampleSize: zo groot mogelijk, maar het plaatje blijft minstens max beeldpunten aan de lange kant. */
    static int sample(int w, int h, int max) {
        int s = 1;
        while (Math.max(w, h) / (s * 2) >= max) s *= 2;
        return s;
    }

    /** Soort plaatje aan de eerste bytes (alleen wat Art zelf schrijft: PNG of JPEG), anders null. */
    static String mime(byte[] head, int n) {
        if (head == null) return null;
        if (n >= 4 && (head[0] & 0xff) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') return "image/png";
        if (n >= 3 && (head[0] & 0xff) == 0xFF && (head[1] & 0xff) == 0xD8 && (head[2] & 0xff) == 0xFF) return "image/jpeg";
        return null;
    }

    /** Alleen http(s) en niet absurd lang (zoals Art.usable). */
    static boolean usable(String url) { return url != null && (url.startsWith("https://") || url.startsWith("http://")) && url.length() < 2000; }
}
