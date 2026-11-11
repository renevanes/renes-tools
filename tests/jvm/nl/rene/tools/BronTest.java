package nl.rene.tools;

import java.util.List;

/** Bron: een bestand uit de app zelf voor de volgende bestandskiezer (controle van de invoer, soort, naam, één keer geldig). */
public class BronTest {
    static void check(boolean value, String why) { if (!value) throw new AssertionError(why); }
    static void fout(String json, String why) {
        try { Bron.lees(json); throw new AssertionError(why); } catch (AssertionError e) { throw e; } catch (Exception expected) { }
    }

    public static void main(String[] args) throws Exception {
        // lees: alleen bekende bronnen met een veilig id
        List<Bron.Item> l = Bron.lees("[{\"bron\":\"kluis\",\"id\":\"k2abcdef\"},{\"bron\":\"gemaakt\",\"id\":\"Contract.pdf\"},{\"bron\":\"factuur\",\"id\":\"" + "a".repeat(64) + "\"}]");
        check(l.size() == 3 && "kluis".equals(l.get(0).bron) && l.get(0).versie == 0, "drie bronnen");
        fout("[]", "leeg");
        fout("[{\"bron\":\"stamgegevens\",\"id\":\"x\"}]", "onbekende bron");
        fout("[{\"bron\":\"kluis\",\"id\":\"../x\"}]", "kluis-id met pad");
        fout("[{\"bron\":\"gemaakt\",\"id\":\"../geheim.pdf\"}]", "gemaakt met pad");
        fout("[{\"bron\":\"gemaakt\",\"id\":\".verborgen\"}]", "gemaakt verborgen bestand");
        fout("[{\"bron\":\"factuur\",\"id\":\"abc\"}]", "factuur zonder sha");
        fout("[{\"bron\":\"kluis\",\"id\":\"k2\",\"versie\":-1}]", "negatieve versie");
        StringBuilder veel = new StringBuilder("[");
        for (int i = 0; i <= Bron.MAX; i++) veel.append(i > 0 ? "," : "").append("{\"bron\":\"kluis\",\"id\":\"k").append(i).append("\"}");
        fout(veel.append("]").toString(), "te veel");

        // soort en passen bij de kiezer
        check("application/pdf".equals(Bron.mime("A.PDF")) && "image/jpeg".equals(Bron.mime("foto.jpeg")) && "application/epub+zip".equals(Bron.mime("boek.epub")), "mime");
        check(Bron.past("a.pdf", "application/pdf", null) && !Bron.past("a.jpg", "application/pdf", null), "alleen pdf");
        check(Bron.past("a.jpg", "image/*", null) && !Bron.past("a.pdf", "image/*", null), "beeld");
        check(Bron.past("a.pdf", "*/*", new String[]{"application/pdf", "image/*"}) && Bron.past("a.png", "*/*", new String[]{"application/pdf", "image/*"}), "lijst soorten");
        check(!Bron.past("a.epub", "*/*", new String[]{"application/pdf", "image/*"}), "niet in de lijst");
        check(Bron.past("x.bin", "*/*", null), "alles");

        // naam: geen mappen of stuurtekens, extensie erbij
        check("a_b.pdf".equals(Bron.naam("a/b", ".pdf")), "geen map: " + Bron.naam("a/b", ".pdf"));
        check("document".equals(Bron.naam("..", "")), "lege naam");
        check(!Bron.naam("x\u0000y.pdf", "").contains("\u0000"), "geen stuurteken");
        check(Bron.naam("z".repeat(300) + ".pdf", "").length() <= 120 && Bron.naam("z".repeat(300) + ".pdf", "").endsWith(".pdf"), "kort met extensie");

        // klaarzetten: één keer, en alleen binnen de geldigheid
        Bron.zet(l, 1000, true);
        Bron.Wacht w = Bron.neem(1000 + Bron.GELDIG_MS);
        check(w != null && w.items.size() == 3 && w.kluisOk, "binnen de tijd");
        check(Bron.neem(1001) == null, "maar één keer");
        Bron.zet(l, 1000, false);
        check(Bron.neem(1001 + Bron.GELDIG_MS) == null, "verlopen");
        check(Bron.neem(1000) == null, "verlopen is ook weg");
        System.out.println("BronTest OK");
    }
}
