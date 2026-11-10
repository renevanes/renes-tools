package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

/** Documenten vergelijken: diff (Myers), tekst per regel en woord, en het verschilbeeld per pixel. */
public class PdfCompareTest {
    static void check(boolean value, String why) { if (!value) throw new AssertionError(why); }

    static String apply(int[][] ops, String[] a, String[] b, boolean side) {
        StringBuilder s = new StringBuilder();
        for (int[] o : ops) { if (o[0] == 0) s.append(a[o[1]]).append('|'); else if (o[0] < 0 && !side) s.append(a[o[1]]).append('|'); else if (o[0] > 0 && side) s.append(b[o[2]]).append('|'); }
        return s.toString();
    }

    public static void main(String[] args) throws Exception {
        // diff: elke uitkomst geeft A (zonder erbij) en B (zonder weg) precies terug, en is minimaal
        String[][][] cases = {
                {{"a", "b", "c", "a", "b", "b", "a"}, {"c", "b", "a", "b", "a", "c"}},
                {{}, {"x", "y"}}, {{"x", "y"}, {}}, {{}, {}},
                {{"de", "huur", "is", "800", "euro"}, {"de", "huur", "is", "850", "euro", "per", "maand"}},
                {{"zelfde"}, {"zelfde"}}};
        for (String[][] c : cases) {
            int[][] ops = PdfCompare.diff(c[0], c[1], 1000);
            StringBuilder ea = new StringBuilder(), eb = new StringBuilder();
            for (String x : c[0]) ea.append(x).append('|');
            for (String x : c[1]) eb.append(x).append('|');
            check(apply(ops, c[0], c[1], false).equals(ea.toString()), "A terug: " + java.util.Arrays.toString(c[0]));
            check(apply(ops, c[0], c[1], true).equals(eb.toString()), "B terug: " + java.util.Arrays.toString(c[1]));
        }
        int[][] klassiek = PdfCompare.diff(cases[0][0], cases[0][1], 1000);
        int d = 0; for (int[] o : klassiek) if (o[0] != 0) d++;
        check(d == 5, "Myers-voorbeeld heeft 5 bewerkingen, kreeg " + d);
        int[][] huur = PdfCompare.diff(cases[4][0], cases[4][1], 1000);
        d = 0; for (int[] o : huur) if (o[0] != 0) d++;
        check(d == 4, "800 → 850 en twee woorden erbij = 4 bewerkingen");

        // te veel verschillen: grof (alles weg en alles erbij), maar nog steeds juist
        String[] x = new String[300], y = new String[300];
        for (int i = 0; i < 300; i++) { x[i] = "a" + i; y[i] = "b" + i; }
        int[][] grof = PdfCompare.diff(x, y, 10);
        check(grof.length == 600, "grof: alles weg en alles erbij");

        // tekst: per regel, dan per woord; witruimte telt niet
        JSONObject r = PdfCompare.diffText("Huurprijs:   800 euro\nIngang 1 januari\n\nGelijk blijft dit", "Huurprijs: 850 euro\nIngang 1 januari\nGelijk blijft dit\nNieuwe regel");
        JSONArray ops = r.getJSONArray("ops");
        StringBuilder weg = new StringBuilder(), erbij = new StringBuilder(), same = new StringBuilder();
        for (int i = 0; i < ops.length(); i++) { JSONArray o = ops.getJSONArray(i); (o.getInt(0) < 0 ? weg : o.getInt(0) > 0 ? erbij : same).append(o.getString(1)); }
        check(weg.toString().trim().equals("800"), "weg: " + weg);
        check(erbij.toString().contains("850") && erbij.toString().contains("Nieuwe regel"), "erbij: " + erbij);
        check(same.toString().contains("Ingang 1 januari") && same.toString().contains("Huurprijs:"), "gelijk: " + same);
        check(r.getInt("weg") == 1 && r.getInt("erbij") == 3, "tellingen: " + r);
        for (int i = 1; i < ops.length(); i++) check(ops.getJSONArray(i).getInt(0) != ops.getJSONArray(i - 1).getInt(0), "aaneengesloten stukken samengevoegd");
        check(PdfCompare.diffText("a b c", "a b c").getInt("weg") == 0, "gelijke tekst");

        // pixels: een verschuiving van één pixel is geen verschil; een nieuw blok wel (groen), een weggehaald blok rood
        int w = 20, h = 10, white = 0xFFFFFFFF, black = 0xFF000000;
        int[] a = new int[w * h], b = new int[w * h], out = new int[w * h];
        java.util.Arrays.fill(a, white); java.util.Arrays.fill(b, white);
        for (int yy = 2; yy < 6; yy++) for (int xx = 2; xx < 6; xx++) { a[yy * w + xx] = black; b[yy * w + xx + 1] = black; }
        check(PdfCompare.pixels(a, b, w, h, out) == 0, "één pixel verschoven telt niet");
        for (int yy = 2; yy < 6; yy++) for (int xx = 12; xx < 16; xx++) b[yy * w + xx] = black;
        int n = PdfCompare.pixels(a, b, w, h, out);
        check(n == 16 && out[3 * w + 13] == PdfCompare.ERBIJ, "nieuw blok is groen");
        n = PdfCompare.pixels(b, a, w, h, out);
        check(n == 16 && out[3 * w + 13] == PdfCompare.WEG, "weggehaald blok is rood");
        check((out[0] & 0xFFFFFF) == 0xFFFFFF, "wit blijft wit");
        System.out.println("Vergelijken: diff, tekst en verschilbeeld kloppen");
    }
}
