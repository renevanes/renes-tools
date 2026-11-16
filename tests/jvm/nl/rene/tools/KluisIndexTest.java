package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

/** Kluisindex: herkomst en koppeling, schoonmaken uit een backup (met versies) en welke bestanden bij een document horen. */
public class KluisIndexTest {
    static void check(boolean value, String why) { if (!value) throw new AssertionError(why); }

    public static void main(String[] args) throws Exception {
        // meta: alleen bekende herkomst en een koppeling naar een identiteitsdocument; onbekende velden vallen weg
        JSONObject in = new JSONObject().put("source", "idkopie").put("geheim", "x").put("name", "a.pdf")
                .put("link", new JSONObject().put("kind", "id").put("ref", "0b6a2c4e-1111-2222-3333-444455556666").put("ontvanger", "Verhuurder")
                        .put("doel", "Huurcontract").put("datum", "2026-11-10").put("soort", "paspoort").put("bsn", "123456782"));
        JSONObject m = KluisIndex.meta(in);
        check("idkopie".equals(m.optString("source")), "herkomst blijft");
        check(!m.has("geheim") && !m.has("name"), "alleen herkomst en koppeling");
        check(!m.getJSONObject("link").has("bsn"), "geen onbekende koppelvelden (zoals een BSN)");
        check("Verhuurder".equals(m.getJSONObject("link").optString("ontvanger")), "ontvanger blijft");
        check(!KluisIndex.meta(new JSONObject().put("source", "iets")).has("source"), "onbekende herkomst valt weg");
        check(!KluisIndex.meta(new JSONObject().put("link", new JSONObject().put("kind", "contact").put("ref", "1"))).has("link"), "alleen kind id");
        check(!KluisIndex.meta(new JSONObject().put("link", new JSONObject().put("kind", "id").put("ref", "../x"))).has("link"), "ref zonder vreemde tekens");
        try { KluisIndex.meta(new JSONObject().put("link", new JSONObject().put("kind", "id").put("ref", "abc").put("ontvanger", "a\u0000b"))); throw new AssertionError("stuurteken toegestaan"); }
        catch (IllegalArgumentException expected) { }

        // blobs: document eerst, dan versies; ongeldige en dubbele namen vallen weg
        JSONObject d = new JSONObject().put("id", "aaaaaaaaaaaa").put("name", "Contract.pdf").put("mime", "application/pdf").put("size", 10).put("t", 5)
                .put("category", "Wonen").put("source", "bewerkt").put("vnote", "Bewerkt")
                .put("versions", new JSONArray()
                        .put(new JSONObject().put("blob", "bbbbbbbbbbbb").put("t", 1).put("size", 8).put("name", "Contract.pdf").put("note", ""))
                        .put(new JSONObject().put("blob", "../../etc").put("t", 2))
                        .put(new JSONObject().put("blob", "bbbbbbbbbbbb").put("t", 3))
                        .put(new JSONObject().put("blob", "cccccccccccc").put("t", 4).put("size", 9)));
        java.util.List<String> b = KluisIndex.blobs(d);
        check(b.equals(java.util.Arrays.asList("aaaaaaaaaaaa", "bbbbbbbbbbbb", "cccccccccccc")), "blobs: " + b);

        // clean: alleen versies waarvan het bestand er is; het document zelf moet er zijn
        java.util.Set<String> present = new java.util.HashSet<>(java.util.Arrays.asList("aaaaaaaaaaaa", "cccccccccccc"));
        JSONObject c = KluisIndex.clean(d, present::contains);
        check(c != null && c.getJSONArray("versions").length() == 1 && "cccccccccccc".equals(c.getJSONArray("versions").getJSONObject(0).getString("blob")), "versie zonder bestand valt weg");
        check("bewerkt".equals(c.optString("source")) && "Bewerkt".equals(c.optString("vnote")) && "Wonen".equals(c.optString("category")), "velden blijven");
        check(KluisIndex.clean(d, x -> false) == null, "zonder documentbestand geen regel");
        check(KluisIndex.clean(new JSONObject().put("id", "AB"), x -> true) == null, "ongeldige id");
        JSONObject self = new JSONObject(d.toString()).put("versions", new JSONArray().put(new JSONObject().put("blob", "aaaaaaaaaaaa")));
        check(!KluisIndex.clean(self, x -> true).has("versions"), "een versie mag niet naar het document zelf wijzen");

        // nooit meer dan MAX_VERSIONS versies (de oudste vallen weg)
        JSONArray many = new JSONArray();
        for (int i = 0; i < KluisIndex.MAX_VERSIONS + 5; i++) many.put(new JSONObject().put("blob", String.format("v%011d", i)).put("t", i));
        JSONObject lots = KluisIndex.clean(new JSONObject(d.toString()).put("versions", many), x -> true);
        check(lots.getJSONArray("versions").length() == KluisIndex.MAX_VERSIONS, "maximum aantal versies");
        check(lots.getJSONArray("versions").getJSONObject(0).getLong("t") == 5, "de oudste vallen weg");
        // props: eigenschappen voor documentbeheer; alleen bekende velden, lege vallen weg, labels zonder dubbele
        JSONObject pr = KluisIndex.props(new JSONObject().put("soort", " Polis ").put("partij", "Verzekeraar").put("datum", "2026-02-29".replace("29", "28"))
                .put("verloopt", "2031-12-01").put("bedrag", "1234.50").put("bsn", "123456782").put("labels", new JSONArray().put("auto").put("Auto").put("").put("2026")));
        check("Polis".equals(pr.optString("soort")) && "1234.50".equals(pr.optString("bedrag")) && !pr.has("bsn"), "bekende velden, netjes: " + pr);
        check(pr.getJSONArray("labels").length() == 2, "labels zonder lege en dubbele");
        check(KluisIndex.props(new JSONObject().put("soort", "")).length() == 0 && KluisIndex.props(null).length() == 0, "leeg blijft leeg");
        for (JSONObject fout : new JSONObject[]{new JSONObject().put("datum", "2026-02-30"), new JSONObject().put("verloopt", "morgen"), new JSONObject().put("bedrag", "12,50"),
                new JSONObject().put("bedrag", "=1+1"), new JSONObject().put("partij", "a\u0000b"), new JSONObject().put("labels", new JSONArray().put("een label dat veel te lang is om nog een label te zijn"))}) {
            try { KluisIndex.props(fout); throw new AssertionError("toegestaan: " + fout); } catch (IllegalArgumentException expected) { }
        }
        check(KluisIndex.datum("2028-02-29") && !KluisIndex.datum("2027-02-29") && !KluisIndex.datum("2026-13-01") && !KluisIndex.datum("0026-01-01"), "datums");
        java.util.Set<String> er = new java.util.HashSet<>(java.util.Arrays.asList("dddddddddddd"));
        JSONObject metProps = KluisIndex.clean(new JSONObject().put("id", "dddddddddddd").put("name", "Polis.pdf").put("props", new JSONObject().put("soort", "Polis").put("verloopt", "2031-12-01").put("x", "y")), er::contains);
        check("Polis".equals(metProps.getJSONObject("props").optString("soort")) && !metProps.getJSONObject("props").has("x"), "eigenschappen overleven een backup");
        JSONObject kapot = KluisIndex.clean(new JSONObject().put("id", "dddddddddddd").put("name", "Polis.pdf").put("props", new JSONObject().put("datum", "kapot")), er::contains);
        check(kapot != null && !kapot.has("props"), "kapotte eigenschappen kosten niet het document");
        System.out.println("Kluisindex: herkomst, koppeling, versies en schoonmaken kloppen");
    }
}
