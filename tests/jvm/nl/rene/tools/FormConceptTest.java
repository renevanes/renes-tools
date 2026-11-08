package nl.rene.tools;

import org.json.JSONObject;

/** Concept van een formulier: alleen bekende vorm, korte waarden, nette id's (een vreemd bestand mag niets breken). */
public class FormConceptTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    public static void main(String[] a) throws Exception {
        check(FormConcept.validId("reistoestemming") && !FormConcept.validId("../x") && !FormConcept.validId("") && !FormConcept.validId(null), "alleen nette formulier-id's");
        StringBuilder lang = new StringBuilder(); for (int i = 0; i < 3000; i++) lang.append('x');
        JSONObject in = new JSONObject().put("values", new JSONObject().put("land", "Frankrijk").put("van", "").put("kind-naam", lang.toString())
                .put("raar veld", "x").put("getal", 5)).put("step", 99).put("naam", "Reis").put("stam", true).put("extra", "weg");
        JSONObject o = FormConcept.clean("reistoestemming", in);
        JSONObject v = o.getJSONObject("values");
        check("Frankrijk".equals(v.optString("land")) && v.has("van") && "".equals(v.optString("van")), "lege verplichte velden mogen in een concept");
        check(v.optString("kind-naam").length() == FormConcept.MAX_VALUE, "te lange waarde ingekort");
        check(!v.has("raar veld") && !v.has("getal"), "vreemde namen en geen tekst vallen weg");
        check(o.optInt("step") == 50 && !o.has("extra") && o.optBoolean("stam"), "stap begrensd, onbekende velden weg");
        check(FormConcept.FORMAT.equals(o.optString("format")) && o.optInt("version") == 1 && "reistoestemming".equals(o.optString("id")), "formaat en versie erin");
        try { FormConcept.clean("x", new JSONObject().put("step", 1)); check(false, "zonder invoer geweigerd"); } catch (Exception e) { check(true, "zonder invoer geweigerd"); }
        check(AppData.fileRule("formulieren/reistoestemming.concept") != null, "concepten bewust niet in de backup (versleuteld met de sleutel van deze telefoon)");
        System.out.println(fails == 0 ? "Alle FormConcept-tests geslaagd" : fails + " FormConcept-tests mislukt");
        System.exit(fails == 0 ? 0 : 1);
    }
}
