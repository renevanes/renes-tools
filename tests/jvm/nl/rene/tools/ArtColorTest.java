package nl.rene.tools;

/** Kleur uit een hoes: tint klopt, en witte tekst blijft altijd leesbaar. */
public class ArtColorTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    static int[] fill(int n, int... colors) {
        int[] px = new int[n];
        for (int i = 0; i < n; i++) px[i] = 0xff000000 | colors[i % colors.length];
        return px;
    }

    static float hue(int c) { float[] h = new float[3]; ArtColor.hsv((c >> 16) & 0xff, (c >> 8) & 0xff, c & 0xff, h); return h[0]; }

    public static void main(String[] a) {
        int red = ArtColor.of(fill(400, 0xE53935));
        check(hue(red) < 15 || hue(red) > 345, "rode hoes → rode tint (" + ArtColor.hex(red) + ")");
        check(ArtColor.contrastWithWhite(red) >= 4.5, "rood: wit leesbaar (" + String.format("%.1f", ArtColor.contrastWithWhite(red)) + ")");

        int yellow = ArtColor.of(fill(400, 0xFFEB3B));
        check(hue(yellow) > 40 && hue(yellow) < 70, "gele hoes → gele tint (" + ArtColor.hex(yellow) + ")");
        check(ArtColor.contrastWithWhite(yellow) >= 4.5, "geel: wit leesbaar (" + String.format("%.1f", ArtColor.contrastWithWhite(yellow)) + ")");

        // Veel wit met een blauw logo: blauw wint (kleur telt zwaarder dan grijs/wit)
        int[] px = new int[1000];
        for (int i = 0; i < px.length; i++) px[i] = 0xff000000 | (i < 850 ? 0xFFFFFF : 0x1565C0);
        int blue = ArtColor.of(px);
        check(hue(blue) > 200 && hue(blue) < 230, "wit met blauw logo → blauw (" + ArtColor.hex(blue) + ")");

        int gray = ArtColor.of(fill(500, 0x808080, 0x202020, 0xEEEEEE));
        float[] h = new float[3]; ArtColor.hsv((gray >> 16) & 0xff, (gray >> 8) & 0xff, gray & 0xff, h);
        check(h[1] < 0.15f && ArtColor.contrastWithWhite(gray) >= 4.5, "grijze hoes → rustig grijs (" + ArtColor.hex(gray) + ")");

        check(ArtColor.of(new int[0]) == ArtColor.FALLBACK && ArtColor.of(fill(10, 0)) != 0, "leeg en zwart geven een bruikbare kleur");
        int[] clear = new int[100];
        check(ArtColor.of(clear) == ArtColor.FALLBACK, "helemaal doorzichtig → standaardkleur");
        // Elke tint: altijd leesbaar
        boolean all = true;
        for (int hh = 0; hh < 360; hh += 15) for (float v : new float[]{0.3f, 0.7f, 1f}) {
            int c = ArtColor.of(fill(100, ArtColor.rgb(hh, 0.9f, v)));
            if (ArtColor.contrastWithWhite(c) < 4.5) { all = false; System.out.println("  te licht: " + hh + "/" + v + " → " + ArtColor.hex(c)); }
        }
        check(all, "alle tinten: contrast met wit ≥ 4,5");
        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
        System.out.println("Alle kleurtests geslaagd");
    }
}
