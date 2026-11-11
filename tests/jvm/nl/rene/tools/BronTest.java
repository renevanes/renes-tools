package nl.rene.tools;

import java.util.Collections;
import java.util.List;

/** Bron: een bestand uit de app zelf voor de volgende bestandskiezer (controle van de invoer, soort, naam, tokens). */
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
        check("application/pdf".equals(Bron.mime("A.PDF")) && "image/jpeg".equals(Bron.mime("foto.jfif")) && "image/tiff".equals(Bron.mime("scan.tif")) && "application/epub+zip".equals(Bron.mime("boek.epub")), "mime");
        check(Bron.past("application/pdf", "application/pdf", null) && !Bron.past("image/jpeg", "application/pdf", null), "alleen pdf");
        check(Bron.past("image/png", "image/*", null) && !Bron.past("application/pdf", "image/*", null), "beeld");
        String[] pdfBeeld = {"application/pdf", "image/*"};
        check(Bron.past("application/pdf", "*/*", pdfBeeld) && Bron.past("image/avif", "*/*", pdfBeeld) && !Bron.past("application/epub+zip", "*/*", pdfBeeld), "lijst soorten");
        check(Bron.past("", "*/*", null) && !Bron.past("", "application/pdf", null), "onbekende soort alleen bij alles");

        // naam: geen mappen of stuurtekens
        check("a_b".equals(Bron.naam("a/b")), "geen map: " + Bron.naam("a/b"));
        check("document".equals(Bron.naam("..")), "lege naam");
        check(!Bron.naam("x\u0000y.pdf").contains("\u0000"), "geen stuurteken");
        check(Bron.naam("z".repeat(300) + ".pdf").length() <= 120 && Bron.naam("z".repeat(300) + ".pdf").endsWith(".pdf"), "kort met extensie");

        // tokens: onraadbaar, alleen met de juiste naam en binnen de tijd; kluis vervalt bij weggaan
        java.io.File f = java.io.File.createTempFile("bron", ".pdf"); f.deleteOnExit();
        check(Bron.token().matches("[0-9a-f]{32}") && !Bron.token().equals(Bron.token()), "token");
        Bron.Bestand g = new Bron.Bestand(Bron.token(), "gemaakt", "x.pdf", 0, "x.pdf", "application/pdf", 1, f, 1000 + Bron.BEWAAR_MS);
        Bron.Bestand k = new Bron.Bestand(Bron.token(), "kluis", "k2abcdef", 0, "y.pdf", "application/pdf", 1, null, 1000 + Bron.BEWAAR_MS);
        java.lang.reflect.Field reg = Bron.class.getDeclaredField("REG"); reg.setAccessible(true);
        @SuppressWarnings("unchecked") java.util.Map<String, Bron.Bestand> m = (java.util.Map<String, Bron.Bestand>) reg.get(null);
        m.put(g.token, g); m.put(k.token, k);
        check(Bron.zoek(g.token, "x.pdf", 1000) == g, "gevonden");
        check(Bron.zoek(g.token, "../x.pdf", 1000) == null && Bron.zoek(g.token, "ander.pdf", 1000) == null, "alleen met de eigen naam");
        check(Bron.zoek("onbekend", "x.pdf", 1000) == null, "onbekend token");
        check(Bron.zoek(g.token, "x.pdf", 1001 + Bron.BEWAAR_MS) == null, "verlopen");
        Bron.vergeetKluis();
        check(Bron.zoek(k.token, "y.pdf", 1000) == null && Bron.zoek(g.token, "x.pdf", 1000) == g, "kluis vergeten, de rest niet");
        Bron.vergeet(Collections.singletonList(g));
        check(Bron.zoek(g.token, "x.pdf", 1000) == null, "vergeten");
        System.out.println("BronTest OK");
    }
}
