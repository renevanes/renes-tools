package nl.rene.tools;

/** Hoesjes op schijf: hoe ver er verkleind wordt, en welke bestanden de interface krijgt (alleen PNG en JPEG). */
public class ArtCacheTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    public static void main(String[] a) {
        // Verkleinen in stappen van 2, maar nooit kleiner dan de gevraagde maat
        check(ArtBestand.sample(3000, 3000, ArtBestand.DISK_PX) == 2, "3000 px naar schijf: halveren (1500, daarna 1024)");
        check(ArtBestand.sample(1400, 1400, ArtBestand.DISK_PX) == 1, "1400 px naar schijf: niet halveren (700 is te klein)");
        check(ArtBestand.sample(4096, 2000, ArtBestand.DISK_PX) == 4, "4096 px breed: vier keer kleiner (1024)");
        check(ArtBestand.sample(1024, 1024, ArtBestand.MAX_PX) == 2, "1024 px van schijf naar het geheugen: 512");
        check(ArtBestand.sample(300, 300, ArtBestand.MAX_PX) == 1, "klein plaatje blijft zoals het is");
        check(ArtBestand.DISK_PX >= 1000, "op schijf scherp genoeg voor de grote hoes (" + ArtBestand.DISK_PX + " px)");
        for (int w : new int[]{ 600, 1023, 1024, 2047, 2048, 5000, 8000 }) {
            int s = ArtBestand.sample(w, w, ArtBestand.DISK_PX);
            check(w / s >= Math.min(w, ArtBestand.DISK_PX) && (s == 1 || w / (s * 2) < ArtBestand.DISK_PX), "sample " + w + " px → " + s);
        }

        // Alleen wat Art zelf schrijft gaat naar de interface
        byte[] png = { (byte) 0x89, 'P', 'N', 'G' };
        byte[] jpg = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0 };
        byte[] svg = "<svg".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] html = "<!DO".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        check("image/png".equals(ArtBestand.mime(png, 4)), "PNG herkend");
        check("image/jpeg".equals(ArtBestand.mime(jpg, 4)), "JPEG herkend");
        check(ArtBestand.mime(svg, 4) == null, "svg niet (kan script bevatten)");
        check(ArtBestand.mime(html, 4) == null, "html niet");
        check(ArtBestand.mime(png, 2) == null && ArtBestand.mime(jpg, -1) == null, "te kort of leeg bestand: niet");

        check(ArtBestand.mime(null, 4) == null, "niets gelezen: niet");

        // Alleen http(s): de interface stuurt het adres mee, nooit een pad
        check(!ArtBestand.usable("javascript:alert(1)"), "javascript: niet");
        check(!ArtBestand.usable("file:///data/data/nl.rene.tools/shared_prefs/x.xml"), "file:// niet");
        check(!ArtBestand.usable("") && !ArtBestand.usable(null), "leeg adres niet");
        check(ArtBestand.usable("https://voorbeeld.nl/hoes.jpg"), "https wel");

        // Opruimen: niets zolang het binnen de grenzen blijft; anders de oudste, tot onder de lagere grens
        long[] t = { 50, 10, 40, 20, 30 }, len = { 10, 10, 10, 10, 10 };
        check(ArtBestand.weg(t, len, 5, 100, 3, 80).length == 0, "binnen de grenzen: niets weg");
        int[] w = ArtBestand.weg(t, len, 4, 100, 3, 100);
        check(java.util.Arrays.equals(w, new int[]{ 1, 3 }), "te veel bestanden: de twee oudste weg " + java.util.Arrays.toString(w));
        long[] groot = { 30_000_000L, 1_000L, 30_000_000L, 1_000L, 5_000_000L };
        w = ArtBestand.weg(t, groot, 300, 60_000_000L, 250, 45_000_000L);
        check(java.util.Arrays.equals(w, new int[]{ 1, 3, 4, 2 }), "te veel MB: oudste eerst tot onder 45 MB " + java.util.Arrays.toString(w));
        check(ArtBestand.weg(new long[0], new long[0], 300, 60_000_000L, 250, 45_000_000L).length == 0, "lege map: niets");

        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
    }
}
