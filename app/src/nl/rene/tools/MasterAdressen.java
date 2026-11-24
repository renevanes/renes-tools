package nl.rene.tools;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Een persoon woont op een adres uit Adressen (veld {@code woonadres} = de id van dat adres). Straat, postcode, plaats en land
 * staan daarnaast als kopie bij de persoon, zodat formulieren, de assistent en exports ze zonder omweg vinden; de kopie volgt
 * het adres altijd (MasterStore). Wens van Rene: geen losse, dubbele adressen meer bij mensen. Zuiver, getest in MasterAdressenTest.
 */
final class MasterAdressen {
    private MasterAdressen() { }

    /** De velden die van het adres naar de persoon gaan. */
    static final String[] GEDEELD = {"adres", "postcode", "plaats", "land"};

    /** Heeft deze persoon (of dit adres) een adres ingevuld? */
    static boolean heeftAdres(JSONObject f) {
        return f != null && (!f.optString("adres").trim().isEmpty() || !f.optString("postcode").trim().isEmpty());
    }

    /**
     * Wanneer twee adressen hetzelfde zijn: straat en huisnummer (zonder hoofdletters, spaties en leestekens) en de postcode
     * (zonder spatie), of zonder postcode de plaats. Leeg als er geen straat is.
     */
    static String sleutel(JSONObject f) {
        if (f == null) return "";
        String straat = schoon(f.optString("adres"));
        if (straat.isEmpty()) return "";
        String pc = f.optString("postcode").replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        return straat + "|" + (pc.isEmpty() ? "p:" + schoon(f.optString("plaats")) : pc);
    }

    private static String schoon(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[\\s.,'\"\\-/]+", "");
    }

    /** Naam van een nieuw adres in het overzicht: straat en huisnummer, met de plaats erbij. */
    static String label(JSONObject f) {
        String straat = f.optString("adres").trim(), plaats = f.optString("plaats").trim();
        String l = straat.isEmpty() ? (plaats.isEmpty() ? f.optString("postcode").trim() : plaats) : plaats.isEmpty() ? straat : straat + ", " + plaats;
        if (l.isEmpty()) l = "Adres"; // taal-vast: naam in de opgeslagen gegevens
        return l.length() > 120 ? l.substring(0, 120) : l;
    }

    /** Neemt straat, postcode, plaats en land van het adres over in de persoon. Geeft true als er iets veranderde. */
    static boolean kopieer(JSONObject adres, JSONObject persoon) throws Exception {
        boolean anders = false;
        for (String k : GEDEELD) {
            String v = adres.optString(k);
            if (!v.equals(persoon.optString(k))) { anders = true; }
            if (v.isEmpty()) persoon.remove(k); else persoon.put(k, v);
        }
        return anders;
    }

    /** De velden van een nieuw adres, uit wat er bij een persoon stond. */
    static JSONObject nieuwAdres(JSONObject persoon) throws Exception {
        JSONObject f = new JSONObject();
        for (String k : GEDEELD) { String v = persoon.optString(k).trim(); if (!v.isEmpty()) f.put(k, v); }
        return f;
    }
}
