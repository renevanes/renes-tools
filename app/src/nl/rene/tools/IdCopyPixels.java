package nl.rene.tools;

/** Opaque pixels vervangen, met naar buiten afgeronde randen. Geen transparante PDF-laag. */
final class IdCopyPixels {
    private IdCopyPixels() { }

    static int[] bounds(double x, double y, double w, double h, int width, int height) {
        for (double n : new double[]{x, y, w, h})
            if (Double.isNaN(n) || Double.isInfinite(n)) throw new IllegalArgumentException(Taal.T("Ongeldig afschermvlak"));
        if (width < 1 || height < 1 || x < 0 || y < 0 || w <= 0 || h <= 0 || x + w > 1.0000001 || y + h > 1.0000001)
            throw new IllegalArgumentException(Taal.T("Ongeldig afschermvlak"));
        return new int[]{Math.max(0, (int) Math.floor(x * width) - 2), Math.max(0, (int) Math.floor(y * height) - 2),
                Math.min(width, (int) Math.ceil((x + w) * width) + 2), Math.min(height, (int) Math.ceil((y + h) * height) + 2)};
    }

    static void blacken(int[] pixels, int width, int height, double x, double y, double w, double h) {
        if (pixels == null || (long) width * height > pixels.length) throw new IllegalArgumentException();
        int[] b = bounds(x, y, w, h, width, height);
        for (int row = b[1]; row < b[3]; row++) java.util.Arrays.fill(pixels, row * width + b[0], row * width + b[2], 0xff000000);
    }
}
