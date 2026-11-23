package nl.rene.tools;

import java.util.List;

/** Een document voor Stamgegevens: tegels over een PDF-pagina en het formaat van een Google-document. */
public final class MasterDocumentTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    public static void main(String[] args) {
        int max = MrzOcr.MAX_SIDE;
        // Een A4 op anderhalf keer de werkmaat: eerst de hele pagina, dan tegels van hooguit max bij max, binnen de pagina.
        int w = MasterDocumentImages.RENDER_SIDE * 210 / 297, h = MasterDocumentImages.RENDER_SIDE;
        List<int[]> s = MasterDocumentImages.stukken(w, h);
        check(s.get(0)[0] == 0 && s.get(0)[1] == 0 && s.get(0)[2] == w && s.get(0)[3] == h, "eerst de hele pagina");
        check(s.size() > 1 && s.size() <= 12, "een paar tegels, niet honderden: " + s.size());
        for (int i = 1; i < s.size(); i++) {
            int[] r = s.get(i);
            check(r[2] <= max && r[3] <= max, "tegel niet groter dan de herkenning aankan");
            check(r[0] >= 0 && r[1] >= 0 && r[0] + r[2] <= w && r[1] + r[3] <= h, "tegel binnen de pagina");
        }
        // Een ID-kaart op een A4 (85,6 × 54 mm, ook gedraaid) staat waar ook altijd helemaal op één tegel.
        for (boolean turned : new boolean[]{false, true}) {
            int cw = (int) Math.ceil(h * (turned ? 54 : 85.6) / 297), ch = (int) Math.ceil(h * (turned ? 85.6 : 54) / 297);
            for (int x = 0; x + cw <= w; x += 37) for (int y = 0; y + ch <= h; y += 41) {
                boolean on = false;
                for (int i = 1; i < s.size() && !on; i++) {
                    int[] r = s.get(i);
                    on = x >= r[0] && y >= r[1] && x + cw <= r[0] + r[2] && y + ch <= r[1] + r[3];
                }
                check(on, "kaart op " + x + "," + y + (turned ? " (gedraaid)" : "") + " valt op geen enkele tegel");
            }
        }
        // Een kleine pagina (zoals vroeger) heeft geen tegels nodig.
        check(MasterDocumentImages.stukken(1200, 1700).size() == 1, "kleine pagina: alleen de pagina zelf");
        check(MasterDocumentImages.tegels(max, 3000, max).size() >= 3, "smal en lang: tegels onder elkaar");

        // Volgorde: eerst overal de snelle strook (ook op de tegels), dan rijbewijs op de hele pagina's, dan op de tegels.
        List<int[]> v = MasterDocumentImages.volgorde(new int[]{4, 0, 4});
        check(v.size() == (5 + 1 + 5) + 3 + (4 + 4), "elk stuk één keer per ronde: " + v.size());
        int last = 0;
        for (int[] x : v) { check(x[0] >= last, "rondes op volgorde"); last = x[0]; }
        check(v.get(0)[0] == 0 && v.get(0)[1] == 0 && v.get(0)[2] == 0, "begin: hele eerste pagina");
        check(v.get(10)[0] == 0 && v.get(10)[1] == 2 && v.get(10)[2] == 4, "strook ook op de laatste tegel van pagina 3");
        check(v.get(11)[0] == 1 && v.get(11)[2] == 0 && v.get(13)[1] == 2, "rijbewijs eerst op de hele pagina's");
        for (int[] x : v) if (x[0] == 2) check(x[2] > 0 && x[1] != 1, "ronde 2 alleen tegels, niet van een pagina zonder tegels");
        check(MasterDocumentImages.volgorde(new int[0]).isEmpty(), "geen pagina's");

        // Google-document: liefst Word (foto's op volle grootte), anders PDF; iets anders niet.
        check(MasterPhotos.DOCX.equals(MasterPhotos.exportType(new String[]{"application/pdf", MasterPhotos.DOCX, "text/plain"})), "Word eerst");
        check(MasterPhotos.PDF.equals(MasterPhotos.exportType(new String[]{"text/plain", "application/pdf"})), "anders PDF");
        check(MasterPhotos.exportType(new String[]{"text/plain", "text/html"}) == null, "geen tekst of HTML");
        check(MasterPhotos.exportType(null) == null, "geen formaten");
        System.out.println("MasterDocumentTest ok");
    }
}
