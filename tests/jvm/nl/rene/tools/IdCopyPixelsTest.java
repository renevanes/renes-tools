package nl.rene.tools;

/** Controleert dat pixels echt worden vervangen, ook aan randen en bij ongeldige invoer. */
public class IdCopyPixelsTest {
    static void check(boolean value, String why) { if (!value) throw new AssertionError(why); }
    public static void main(String[] args) throws Exception {
        int[] pixels = new int[80 * 40]; java.util.Arrays.fill(pixels, 0xff12ab34);
        IdCopyPixels.blacken(pixels, 80, 40, .2, .25, .4, .5);
        for (int y = 0; y < 40; y++) for (int x = 0; x < 80; x++) {
            boolean masked = x >= 14 && x < 51 && y >= 8 && y < 32;
            check(pixels[y * 80 + x] == (masked ? 0xff000000 : 0xff12ab34), "opaque gebied of buitengebied klopt niet");
        }
        int[] edge = IdCopyPixels.bounds(0, 0, 1, 1, 80, 40);
        check(edge[0] == 0 && edge[1] == 0 && edge[2] == 80 && edge[3] == 40, "volledige pagina");
        int rejected = 0;
        for (double[] bad : new double[][]{{Double.NaN,0,.1,.1},{0,Double.POSITIVE_INFINITY,.1,.1},{-.1,0,.1,.1},{0,0,0,.1},{.9,0,.2,.1},{0,0,.1,-.1}}) {
            try { IdCopyPixels.bounds(bad[0],bad[1],bad[2],bad[3],80,40); } catch (IllegalArgumentException expected) { rejected++; }
        }
        check(rejected == 6, "ongeldige invoer weigeren");
        IdCopy closed = new IdCopy(); check(closed.clear(), "lege bron sluiten");
        try { closed.add(null, java.util.Collections.emptyList(), Voortgang.GEEN); throw new AssertionError("late import na sluiten toegestaan"); }
        catch (Exception expected) { check(expected.getMessage().contains("opnieuw"), "late import vóór context/cachetoegang afgewezen"); }
        check(closed.clear(), "nogmaals wissen blijft geldig");
        System.out.println("ID-kopie pixel- en sluitcontroles geslaagd");
    }
}
