package nl.rene.tools;

import org.json.JSONObject;

/** Een persoon woont op een adres uit Adressen: wanneer twee adressen hetzelfde zijn en wat er naar de persoon gaat. */
public final class MasterAdressenTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static JSONObject f(String adres, String postcode, String plaats) throws Exception {
        JSONObject o = new JSONObject();
        if (adres != null) o.put("adres", adres);
        if (postcode != null) o.put("postcode", postcode);
        if (plaats != null) o.put("plaats", plaats);
        return o;
    }
    public static void main(String[] args) throws Exception {
        // Zelfde adres, anders geschreven
        check(MasterAdressen.sleutel(f("Kade 12", "3011 AB", "Rotterdam")).equals(MasterAdressen.sleutel(f("kade  12", "3011ab", "ROTTERDAM"))), "hoofdletters en spaties tellen niet");
        check(MasterAdressen.sleutel(f("Kade 12-a", "3011AB", null)).equals(MasterAdressen.sleutel(f("Kade 12a", "3011 ab", null))), "streepje in het huisnummer telt niet");
        check(!MasterAdressen.sleutel(f("Kade 12", "3011 AB", null)).equals(MasterAdressen.sleutel(f("Kade 14", "3011 AB", null))), "ander huisnummer = ander adres");
        check(!MasterAdressen.sleutel(f("Kade 12", "3011 AB", null)).equals(MasterAdressen.sleutel(f("Kade 12", "3012 AB", null))), "andere postcode = ander adres");
        check(MasterAdressen.sleutel(f("Dorpsstraat 1", null, "Ede")).equals(MasterAdressen.sleutel(f("dorpsstraat 1", "", "ede"))), "zonder postcode telt de plaats");
        check(!MasterAdressen.sleutel(f("Dorpsstraat 1", null, "Ede")).equals(MasterAdressen.sleutel(f("Dorpsstraat 1", null, "Epe"))), "andere plaats = ander adres");
        check(!MasterAdressen.sleutel(f("Kade 12-1", "3011AB", null)).equals(MasterAdressen.sleutel(f("Kade 121", "3011AB", null))), "12-1 is niet 121");
        check(!MasterAdressen.sleutel(f("Kade 12/1", "3011AB", null)).equals(MasterAdressen.sleutel(f("Kade 121", "3011AB", null))), "12/1 is niet 121");
        check(MasterAdressen.sleutel(f("Kade 12 - 1", "3011AB", null)).equals(MasterAdressen.sleutel(f("kade 12-1", "3011AB", null))), "spaties rond het streepje tellen niet");
        check(MasterAdressen.sleutel(f("Rue de l'Église 3", null, "Liège")).equals(MasterAdressen.sleutel(f("rue de l’église 3", null, "LIÈGE"))), "letters met accent blijven, apostrof telt niet");
        check(!MasterAdressen.sleutel(f("Église 3", null, "Liège")).equals(MasterAdressen.sleutel(f("Eglise 3", null, "Liege"))), "een accent is een andere letter");
        check(MasterAdressen.sleutel(f(null, "3011 AB", "Rotterdam")).equals(MasterAdressen.sleutel(f(null, "3011ab", null))), "zonder straat: de postcode");
        check(MasterAdressen.sleutel(f(null, null, "Rotterdam")).equals(MasterAdressen.sleutel(f(null, " ", "rotterdam"))) && !MasterAdressen.sleutel(f(null, null, "Rotterdam")).isEmpty(), "alleen een plaats");
        check(!MasterAdressen.sleutel(f(null, null, "Rotterdam")).equals(MasterAdressen.sleutel(f(null, "3011AB", null))), "plaats is geen postcode");
        check(MasterAdressen.sleutel(f(null, null, null)).isEmpty() && MasterAdressen.sleutel(null).isEmpty(), "leeg zonder adres");
        // Heeft een adres
        check(MasterAdressen.heeftAdres(f("Kade 12", null, null)) && MasterAdressen.heeftAdres(f(null, "3011 AB", null)) && MasterAdressen.heeftAdres(f(null, null, "Rotterdam")), "straat, postcode of plaats is een adres");
        check(!MasterAdressen.heeftAdres(f(" ", null, null)) && !MasterAdressen.heeftAdres(new JSONObject().put("land", "Nederland")) && !MasterAdressen.heeftAdres(null), "leeg of alleen een land is geen adres");
        // Naam in het overzicht
        check("Kade 12, Rotterdam".equals(MasterAdressen.label(f("Kade 12", "3011 AB", "Rotterdam"))), "straat en plaats");
        check("Kade 12".equals(MasterAdressen.label(f("Kade 12", null, null))), "alleen straat");
        check("3011 AB".equals(MasterAdressen.label(f(null, "3011 AB", null))), "alleen postcode");
        StringBuilder lang = new StringBuilder(); for (int i = 0; i < 50; i++) lang.append("abcde");
        check(MasterAdressen.label(f(lang.toString(), null, "X")).length() == 120, "naam hooguit 120 tekens");
        // Kopiëren naar de persoon
        JSONObject adres = f("Kade 12", "3011 AB", "Rotterdam").put("land", "Nederland").put("telefoon", "010-1234567").put("naam", "Thuis");
        JSONObject persoon = f("Oude straat 1", "1234 AB", "Ede").put("telefoon", "06-12345678").put("naam", "Es").put("land", "België");
        check(MasterAdressen.kopieer(adres, persoon), "verandering gemeld");
        check("Kade 12".equals(persoon.getString("adres")) && "3011 AB".equals(persoon.getString("postcode")) && "Rotterdam".equals(persoon.getString("plaats")) && "Nederland".equals(persoon.getString("land")), "straat, postcode, plaats en land over");
        check("06-12345678".equals(persoon.getString("telefoon")) && "Es".equals(persoon.getString("naam")), "eigen telefoon en naam blijven");
        check(!MasterAdressen.kopieer(adres, persoon), "geen verandering bij een tweede keer");
        JSONObject zonderLand = f("Kade 12", "3011 AB", "Rotterdam");
        check(MasterAdressen.kopieer(zonderLand, persoon) && !persoon.has("land"), "leeg veld bij het adres = weg bij de persoon");
        JSONObject p2 = f("kade 12", "3011AB", "Rotterdam");
        check(MasterAdressen.veranderd(f("Kade 12", "3011 AB", "Rotterdam"), p2).equals(java.util.Arrays.asList("adres", "postcode")), "precies de veranderde velden");
        check(MasterAdressen.veranderd(f("Kade 12", "3011 AB", "Rotterdam"), p2).isEmpty(), "daarna niets meer");
        // Nieuw adres uit een persoon
        JSONObject p = f("Kade 12", "3011 AB", "Rotterdam").put("land", "Nederland").put("telefoon", "06-12345678").put("bsn", "123456782").put("naam", "Es");
        JSONObject n = MasterAdressen.nieuwAdres(p);
        check("Kade 12".equals(n.getString("adres")) && "Nederland".equals(n.getString("land")), "adresvelden mee");
        check(!n.has("bsn") && !n.has("telefoon") && !n.has("naam"), "niets anders van de persoon mee naar het adres");
        // Een adres is alleen een adres: geen naam of telefoon meer (MasterSchema)
        JSONObject adresIn = new JSONObject().put("type", "adres").put("label", "Thuis").put("fields", f("Kade 12", "3011 AB", "Rotterdam"));
        check(MasterSchema.clean(adresIn).getJSONObject("fields").length() == 3, "adresvelden mogen");
        for (String veld : new String[]{"naam", "telefoon"}) {
            boolean geweigerd = false;
            try { MasterSchema.clean(new JSONObject(adresIn.toString()).put("fields", f("Kade 12", null, null).put(veld, "x"))); } catch (MasterError e) { geweigerd = true; }
            check(geweigerd, veld + " bij een adres wordt geweigerd");
        }
        java.util.Set<String> bestemming = MasterSchema.permitted("formulieren", "bestemming", "adres");
        check(bestemming.contains("adres") && bestemming.contains("plaats") && !bestemming.contains("telefoon"), "bestemming: adres zonder telefoon");
        JSONObject eigen = new JSONObject().put("type", "adres").put("label", "Camping De Zon").put("fields", f("Duinweg 1", "1234 AB", "Ouddorp"));
        check("Camping De Zon".equals(MasterSchema.projection(eigen, "formulieren", "bestemming").optString("accommodatie")), "eigen naam = accommodatie");
        JSONObject auto = new JSONObject().put("type", "adres").put("label", "Duinweg 1, Ouddorp").put("fields", f("Duinweg 1", "1234 AB", "Ouddorp"));
        check(!MasterSchema.projection(auto, "formulieren", "bestemming").has("accommodatie"), "automatische naam is geen accommodatie");
        JSONObject org = new JSONObject().put("type", "organisatie").put("label", "Hotel").put("fields", new JSONObject().put("naam", "Hotel Zee").put("telefoon", "0101234567"));
        JSONObject op = MasterSchema.projection(org, "formulieren", "bestemming");
        check("Hotel Zee".equals(op.optString("accommodatie")) && "0101234567".equals(op.optString("telefoon")), "organisatie: naam en telefoon");
        System.out.println("MasterAdressenTest ok");
    }
}
