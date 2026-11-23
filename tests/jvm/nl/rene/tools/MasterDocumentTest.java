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
        List<int[]> v = MasterDocumentImages.volgorde(new int[]{4, 0, 4}, false);
        check(v.size() == (5 + 1 + 5) + 3 + (4 + 4), "elk stuk één keer per ronde: " + v.size());
        int last = 0;
        for (int[] x : v) { check(x[0] >= last, "rondes op volgorde"); last = x[0]; }
        check(v.get(0)[0] == 0 && v.get(0)[1] == 0 && v.get(0)[2] == 0, "begin: hele eerste pagina");
        check(v.get(10)[0] == 0 && v.get(10)[1] == 2 && v.get(10)[2] == 4, "strook ook op de laatste tegel van pagina 3");
        check(v.get(11)[0] == 1 && v.get(11)[2] == 0 && v.get(13)[1] == 2, "rijbewijs eerst op de hele pagina's");
        for (int[] x : v) if (x[0] == 2) check(x[2] > 0 && x[1] != 1, "ronde 2 alleen tegels, niet van een pagina zonder tegels");
        check(MasterDocumentImages.volgorde(new int[0], false).isEmpty(), "geen pagina's");
        List<int[]> rb = MasterDocumentImages.volgorde(new int[]{4, 0, 4}, true);
        check(rb.size() == v.size() && rb.get(0)[0] == 1 && rb.get(rb.size() - 1)[0] == 0, "rijbewijs gekozen: eerst rijbewijs, de strook als laatste");

        // Beide zijden: het nummer moet kloppen; wat herkend is gaat voor de gekozen soort; niets herkend = toch twee zijden.
        byte[] x = {1}, y = {2};
        org.json.JSONObject td1 = new org.json.JSONObject();
        try {
            td1.put("soort", "TD1").put("document", new org.json.JSONObject().put("documentsoort", "identiteitskaart").put("document", "NX1"));
            MasterDocumentImages.Pair p1 = MasterDocumentImages.pair(x, td1, y, null, "identiteitskaart");
            check(p1 != null && p1.back == x && p1.front == y && p1.fields == td1, "strook op het eerste beeld: dat is de achterkant (ID-kaart)");
            MasterDocumentImages.Pair p2 = MasterDocumentImages.pair(x, td1, y, null, "verblijfsdocument");
            check(p2 != null && p2.back == x, "een andere gekozen soort: wat herkend is gaat voor");
            org.json.JSONObject ander = new org.json.JSONObject().put("soort", "TD1").put("document", new org.json.JSONObject().put("documentsoort", "identiteitskaart").put("document", "NX2"));
            check(MasterDocumentImages.pair(x, td1, y, ander, "identiteitskaart") == null, "twee verschillende nummers: niet samen");
            MasterDocumentImages.Pair p3 = MasterDocumentImages.pair(x, null, y, null, "rijbewijs");
            check(p3 != null && p3.front == x && p3.back == y && p3.fields == null, "niets herkend: de twee beelden, zonder gegevens");
        } catch (org.json.JSONException e) { throw new AssertionError(e); }
        // De twee grootste foto's (in hun volgorde); een effen achterkant is klein in bytes maar telt wel.
        byte[] klein = new byte[2000], a1 = new byte[40_000], a2 = new byte[90_000], a3 = new byte[60_000];
        List<byte[]> k = MasterDocumentImages.kies2(java.util.Arrays.asList(klein, a1, a2, a3));
        check(k.size() == 2 && k.get(0) == a2 && k.get(1) == a3, "de twee grootste, in volgorde");
        check(MasterDocumentImages.kies2(java.util.Arrays.asList(klein, a1)).size() == 2, "twee foto's: allebei");
        check(MasterDocumentImages.kies2(java.util.Arrays.asList(a1)).isEmpty(), "één foto: niets");
        // Een pagina in tweeën langs de lange kant.
        List<int[]> hf = MasterDocumentImages.helften(1000, 1414);
        check(hf.get(0)[3] + hf.get(1)[3] == 1414 && hf.get(1)[1] == 707 && hf.get(0)[2] == 1000, "staand: boven en onder");
        List<int[]> l = MasterDocumentImages.helften(1414, 1000);
        check(l.get(0)[2] + l.get(1)[2] == 1414 && l.get(1)[0] == 707, "liggend: links en rechts");

        // Breedte en hoogte uit een PDF zijn PdfSplit.Num
        check(MasterDocumentImages.getal(new PdfSplit.Num("1500")) == 1500 && MasterDocumentImages.getal(null) == 0, "getal uit een PDF");
        // Een gescande pagina (A-vorm, groot) is geen kaartfoto
        check(MasterDocumentImages.paginaVorm(2480, 3508) && MasterDocumentImages.paginaVorm(3508, 2480), "A4 staand en liggend");
        check(MasterDocumentImages.paginaVorm(1275, 1650), "Letter op 150 dpi");
        check(!MasterDocumentImages.paginaVorm(1600, 1009) && !MasterDocumentImages.paginaVorm(700, 990), "kaart, of klein");

        // Google-document: liefst Word (foto's op volle grootte), anders PDF; iets anders niet.
        check(MasterPhotos.DOCX.equals(MasterPhotos.exportType(new String[]{"application/pdf", MasterPhotos.DOCX, "text/plain"})), "Word eerst");
        check(MasterPhotos.PDF.equals(MasterPhotos.exportType(new String[]{"text/plain", "application/pdf"})), "anders PDF");
        check(MasterPhotos.exportType(new String[]{"text/plain", "text/html"}) == null, "geen tekst of HTML");
        check(MasterPhotos.exportType(null) == null, "geen formaten");
        System.out.println("MasterDocumentTest ok");
    }
}
