package nl.rene.tools;

import java.util.List;

/** Een document voor Stamgegevens en Foto's uit PDF: tegels over een foto, voor- en achterkant, volgorde en het formaat van een Google-document. */
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
        // Breedte en hoogte uit een PDF zijn PdfSplit.Num
        check(DocumentFotos.getal(new PdfSplit.Num("1500")) == 1500 && DocumentFotos.getal(null) == 0, "getal uit een PDF");
        // Foto's uit een Word-document: image2 vóór image10
        List<String> namen = new java.util.ArrayList<>(java.util.Arrays.asList("word/media/image10.png", "word/media/image2.jpeg", "word/media/image1.png", "word/media/foto.jpg"));
        namen.sort(DocumentFotos::volgorde);
        check(namen.equals(java.util.Arrays.asList("word/media/foto.jpg", "word/media/image1.png", "word/media/image2.jpeg", "word/media/image10.png")), "volgorde: " + namen);
        // Gemengde namen: een vaste volgorde (sorteren met veel namen gaat nooit mis)
        List<String> veel = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) veel.add("word/media/" + (i % 3 == 0 ? "x" + i + ".png" : i % 3 == 1 ? "x" + i + "z.png" : "image" + i + ".jpeg"));
        java.util.Collections.shuffle(veel, new java.util.Random(7));
        veel.sort(DocumentFotos::volgorde);
        for (int i = 1; i < veel.size(); i++) check(DocumentFotos.volgorde(veel.get(i - 1), veel.get(i)) <= 0, "vaste volgorde");
        // Kaarten op een pagina (een scan met voor- en achterkant): twee gekleurde vlakken in kaartverhouding, geen alinea tekst
        int pw = 600, ph = 848;
        boolean[] pag = new boolean[pw * ph];
        java.util.function.BiConsumer<int[], Double> vul = (r, dicht) -> { java.util.Random rnd = new java.util.Random(r[0] * 31 + r[1]);
            for (int yy = r[1]; yy < r[1] + r[3]; yy++) for (int xx = r[0]; xx < r[0] + r[2]; xx++) pag[yy * pw + xx] = rnd.nextDouble() < dicht; };
        vul.accept(new int[]{60, 80, 260, 164}, 0.9);   // voorkant
        vul.accept(new int[]{80, 420, 254, 160}, 0.8);  // achterkant (iets anders geplaatst)
        for (int l = 0; l < 6; l++) for (int xx = 360; xx < 560; xx++) for (int yy = 650 + l * 14; yy < 653 + l * 14; yy++) pag[yy * pw + xx] = xx % 7 != 0; // regels tekst
        List<int[]> kaarten = DocumentFotos.kaartVlakken(pag, pw, ph);
        check(kaarten.size() == 2, "twee kaarten: " + kaarten.size());
        check(Math.abs(kaarten.get(0)[1] - 80) < 6 && Math.abs(kaarten.get(1)[1] - 420) < 6, "van boven naar beneden");
        check(Math.abs(kaarten.get(0)[2] - 260) < 10 && Math.abs(kaarten.get(0)[3] - 164) < 10, "maat van de kaart");
        // Een gewone tekstpagina: geen kaarten
        boolean[] tekst = new boolean[pw * ph];
        for (int l = 0; l < 50; l++) for (int xx = 50; xx < 550; xx++) for (int yy = 60 + l * 15; yy < 63 + l * 15; yy++) tekst[yy * pw + xx] = xx % 9 != 0;
        check(DocumentFotos.kaartVlakken(tekst, pw, ph).isEmpty(), "tekst is geen kaart");
        check(DocumentFotos.kaartVlakken(new boolean[pw * ph], pw, ph).isEmpty(), "lege pagina");
        check(DocumentFotos.paginaVorm(2480, 3508) && DocumentFotos.paginaVorm(3508, 2480) && DocumentFotos.paginaVorm(1275, 1650), "A4 en Letter");
        check(!DocumentFotos.paginaVorm(1600, 1009) && !DocumentFotos.paginaVorm(700, 990), "kaart, of klein");
        // Is het een PDF of een zip (Word)?
        check(DocumentFotos.pdf("%PDF-1.7".getBytes()) && !DocumentFotos.pdf("PK".getBytes()) && !DocumentFotos.pdf(null), "PDF herkend");
        check(DocumentFotos.zip(new byte[]{'P', 'K', 3, 4}) && !DocumentFotos.zip("%PDF-".getBytes()), "zip herkend");
        check(MasterPhotos.DOCX.equals(MasterPhotos.exportType(new String[]{"application/pdf", MasterPhotos.DOCX, "text/plain"})), "Word eerst");
        check(MasterPhotos.PDF.equals(MasterPhotos.exportType(new String[]{"text/plain", "application/pdf"})), "anders PDF");
        check(MasterPhotos.exportType(new String[]{"text/plain", "text/html"}) == null, "geen tekst of HTML");
        check(MasterPhotos.exportType(null) == null, "geen formaten");
        System.out.println("MasterDocumentTest ok");
    }
}
