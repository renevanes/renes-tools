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

    /** Heeft deze persoon (of dit adres) een adres ingevuld (straat, postcode of plaats; een land alleen telt niet)? */
    static boolean heeftAdres(JSONObject f) {
        return f != null && (!f.optString("adres").trim().isEmpty() || !f.optString("postcode").trim().isEmpty() || !f.optString("plaats").trim().isEmpty());
    }

    /**
     * Wanneer twee adressen hetzelfde zijn: straat en huisnummer en de postcode (zonder spatie), of zonder postcode de plaats.
     * Hoofdletters, spaties en leestekens tellen niet, behalve tussen twee cijfers (Kade 12-1 is niet Kade 121; Kade 12-a wel
     * Kade 12a). Zonder straat: alleen de postcode, of alleen de plaats. Leeg als er niets is. Zo ook in de testbrug.
     */
    static String sleutel(JSONObject f) {
        if (f == null) return "";
        String straat = schoon(f.optString("adres")), plaats = schoon(f.optString("plaats"));
        String pc = f.optString("postcode").replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (!straat.isEmpty()) return straat + "|" + (pc.isEmpty() ? "p:" + plaats : pc);
        if (!pc.isEmpty()) return "|" + pc;
        return plaats.isEmpty() ? "" : "|p:" + plaats;
    }

    /** Kleine letters, alleen letters en cijfers; een scheiding blijft alleen tussen twee cijfers (als "_"). */
    static String schoon(String s) {
        if (s == null) return "";
        String t = s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "_");
        StringBuilder b = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch == '_' && !(i > 0 && i + 1 < t.length() && Character.isDigit(t.charAt(i - 1)) && Character.isDigit(t.charAt(i + 1)))) continue;
            b.append(ch);
        }
        return b.toString();
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
        return !veranderd(adres, persoon).isEmpty();
    }

    /** Zoals kopieer, maar geeft welke velden bij de persoon veranderden (voor het logboek van Stamgegevens). */
    static java.util.List<String> veranderd(JSONObject adres, JSONObject persoon) throws Exception {
        java.util.List<String> anders = new java.util.ArrayList<>();
        for (String k : GEDEELD) {
            String v = adres.optString(k);
            if (!v.equals(persoon.optString(k))) anders.add(k);
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
