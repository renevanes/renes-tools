package nl.rene.tools;

/**
 * De kleur van een hoes, zoals Spotify die achter de speler zet. Puur Java (alleen pixels), zodat het ook op een
 * gewone JVM te testen is.
 *
 * Werkwijze: pixels per tint in 24 bakjes, verzadigde pixels wegen zwaarder; het zwaarste bakje wint en daarvan
 * het gemiddelde. Grijs/zwart/wit telt alleen als bijna de hele hoes zo is. Daarna donker genoeg gemaakt voor
 * witte tekst erop.
 */
final class ArtColor {
    private ArtColor() { }

    static final int FALLBACK = 0x2A3A4F;

    /** Kleur (0xRRGGBB) uit ARGB-pixels. */
    static int of(int[] px) {
        if (px == null || px.length == 0) return FALLBACK;
        final int B = 24;
        double[] w = new double[B + 1], r = new double[B + 1], g = new double[B + 1], b = new double[B + 1];
        int seen = 0;
        float[] hsv = new float[3];
        for (int p : px) {
            if ((p >>> 24) < 128) continue; // doorzichtig
            int pr = (p >> 16) & 0xff, pg = (p >> 8) & 0xff, pb = p & 0xff;
            hsv(pr, pg, pb, hsv);
            seen++;
            int k;
            double wt;
            if (hsv[1] < 0.18f || hsv[2] < 0.12f) { k = B; wt = 1; } // grijs of (bijna) zwart
            else { k = Math.min(B - 1, (int) (hsv[0] / 360f * B)); wt = 1 + hsv[1] * 3 + hsv[2]; }
            w[k] += wt; r[k] += pr * wt; g[k] += pg * wt; b[k] += pb * wt;
        }
        if (seen == 0) return FALLBACK;
        int best = -1;
        double total = 0;
        for (int k = 0; k <= B; k++) total += w[k];
        // Buurbakjes samen tellen (een rode hoes valt anders uiteen in rood en oranje)
        double bestW = 0;
        for (int k = 0; k < B; k++) {
            double s = w[k] + 0.5 * (w[(k + B - 1) % B] + w[(k + 1) % B]);
            if (s > bestW) { bestW = s; best = k; }
        }
        // Grijs wint alleen als er nauwelijks kleur is
        if (best < 0 || w[best] < total * 0.08) best = B;
        if (w[best] <= 0) return FALLBACK;
        int cr = (int) Math.round(r[best] / w[best]), cg = (int) Math.round(g[best] / w[best]), cb = (int) Math.round(b[best] / w[best]);
        return forBackground(cr, cg, cb);
    }

    /** Donker en rustig genoeg voor witte tekst (contrast ≥ 4,5:1), maar met de tint van de hoes. */
    static int forBackground(int cr, int cg, int cb) {
        float[] hsv = new float[3];
        hsv(cr, cg, cb, hsv);
        float s = Math.min(hsv[1], 0.78f), v = Math.max(0.16f, Math.min(hsv[2], 0.50f));
        if (s < 0.18f) s = Math.min(s, 0.12f); // grijs blijft grijs
        int c = rgb(hsv[0], s, v);
        while (contrastWithWhite(c) < 4.5 && v > 0.1f) { v -= 0.03f; c = rgb(hsv[0], s, v); }
        return c;
    }

    static double luminance(int c) {
        double[] ch = { ((c >> 16) & 0xff) / 255.0, ((c >> 8) & 0xff) / 255.0, (c & 0xff) / 255.0 };
        for (int i = 0; i < 3; i++) ch[i] = ch[i] <= 0.03928 ? ch[i] / 12.92 : Math.pow((ch[i] + 0.055) / 1.055, 2.4);
        return 0.2126 * ch[0] + 0.7152 * ch[1] + 0.0722 * ch[2];
    }

    static double contrastWithWhite(int c) { return 1.05 / (luminance(c) + 0.05); }

    static String hex(int c) { return String.format(java.util.Locale.ROOT, "#%06x", c & 0xffffff); }

    static void hsv(int r, int g, int b, float[] out) {
        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf)), min = Math.min(rf, Math.min(gf, bf)), d = max - min;
        float h;
        if (d == 0) h = 0;
        else if (max == rf) h = 60 * (((gf - bf) / d) % 6);
        else if (max == gf) h = 60 * (((bf - rf) / d) + 2);
        else h = 60 * (((rf - gf) / d) + 4);
        if (h < 0) h += 360;
        out[0] = h; out[1] = max == 0 ? 0 : d / max; out[2] = max;
    }

    static int rgb(float h, float s, float v) {
        float c = v * s, x = c * (1 - Math.abs((h / 60f) % 2 - 1)), m = v - c, r, g, b;
        if (h < 60) { r = c; g = x; b = 0; } else if (h < 120) { r = x; g = c; b = 0; } else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; } else if (h < 300) { r = x; g = 0; b = c; } else { r = c; g = 0; b = x; }
        int ri = Math.round((r + m) * 255), gi = Math.round((g + m) * 255), bi = Math.round((b + m) * 255);
        return (clamp(ri) << 16) | (clamp(gi) << 8) | clamp(bi);
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
}
