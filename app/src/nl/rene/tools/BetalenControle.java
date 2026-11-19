package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Betalen: klopt de ontvanger? Zuiver (getest in BetalenTest), alles op de telefoon. Kijkt naar de bank achter het
 * IBAN, de officiële rekeningnummers van organisaties die vaak nagemaakt worden (lijst "ontvangers" in
 * web/bank-banken.json, alleen van de eigen website van die organisatie) en eerdere betalingen vanuit Tools.
 * Zoeken op internet doet Rene zelf (een knop op het scherm); de bank doet bij het overboeken ook de IBAN-Naam Check.
 */
final class BetalenControle {
    private BetalenControle() { }

    static volatile JSONObject lijstCache;

    /** De lijst uit de app (ontvangers en bankcodes). */
    static JSONObject lijst(android.content.Context c) {
        JSONObject l = lijstCache;
        if (l != null) return l;
        try (java.io.InputStream in = c.getAssets().open("bank-banken.json")) {
            l = new JSONObject(new String(Facturen.lees(in, 200_000), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) { l = new JSONObject(); }
        lijstCache = l;
        return l;
    }

    static String plat(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** De organisatie uit de lijst waarbij dit IBAN hoort, of null. */
    static JSONObject bekend(String iban, JSONObject lijst) { return zoek(iban, lijst, "iban"); }
    /** Een oud nummer van een organisatie uit de lijst (wordt nog aangenomen), of null. */
    static JSONObject oud(String iban, JSONObject lijst) { return zoek(iban, lijst, "oud"); }

    private static JSONObject zoek(String iban, JSONObject lijst, String veld) {
        JSONArray o = lijst == null ? null : lijst.optJSONArray("ontvangers");
        if (o == null || iban == null || iban.isEmpty()) return null;
        for (int i = 0; i < o.length(); i++) {
            JSONObject x = o.optJSONObject(i);
            JSONArray ib = x == null ? null : x.optJSONArray(veld);
            if (ib != null) for (int k = 0; k < ib.length(); k++) if (iban.equals(ib.optString(k))) return x;
        }
        return null;
    }

    /** Waar een organisatie als afzender staat: de naam en de bovenste regels van de rekening (niet de lopende tekst). */
    static String kop(String naam, String tekst) {
        StringBuilder b = new StringBuilder(naam == null ? "" : naam);
        if (tekst != null) {
            int n = 0;
            for (String r : tekst.split("\n", 40)) { if (r.trim().isEmpty()) continue; b.append('\n').append(r); if (++n >= 6) break; }
        }
        return b.toString();
    }

    /** Een organisatie uit de lijst die in deze tekst genoemd wordt (naam of een van de namen), of null. */
    static JSONObject genoemd(String tekst, JSONObject lijst) {
        JSONArray o = lijst == null ? null : lijst.optJSONArray("ontvangers");
        // losse letters ("C.J.I.B.") aan elkaar, zodat ook een afkorting met punten gevonden wordt
        String t = " " + plat(tekst).replaceAll("(?<=\\b[a-z]) (?=[a-z]\\b)", "") + " ";
        if (o == null || t.trim().isEmpty()) return null;
        for (int i = 0; i < o.length(); i++) {
            JSONObject x = o.optJSONObject(i);
            if (x == null) continue;
            JSONArray n = x.optJSONArray("namen");
            if (t.contains(" " + plat(x.optString("naam")) + " ")) return x;
            if (n != null) for (int k = 0; k < n.length(); k++) { String w = plat(n.optString(k)); if (!w.isEmpty() && t.contains(" " + w + " ")) return x; }
        }
        return null;
    }

    /** De bank achter een Nederlands IBAN (uit "bankcodes"), of "". */
    static String bank(String iban, JSONObject lijst) {
        if (iban == null || iban.length() < 8 || !iban.startsWith("NL")) return "";
        JSONObject c = lijst == null ? null : lijst.optJSONObject("bankcodes");
        return c == null ? "" : c.optString(iban.substring(4, 8), "");
    }

    /**
     * De controle van één betaling: {land, bank, buitenland, bekend (naam van de organisatie), bron, verdacht (een organisatie
     * uit de lijst die genoemd wordt terwijl het IBAN niet van haar is), eerder (aantal betalingen die Rene zelf op Betaald
     * zette), eerderNaam (de naam bij de laatste daarvan), naam (voorgestelde naam: de officiële, anders de eerdere)}.
     * tekst = wat er verder over de rekening bekend is: de gelezen tekst (alleen de bovenste regels tellen, als afzender) of de
     * bewaarde naam van de genoemde organisatie. oud = een oud nummer van die organisatie (wordt nog aangenomen).
     */
    static JSONObject controle(JSONObject b, JSONArray alle, JSONObject lijst, String tekst) {
        JSONObject uit = new JSONObject();
        try {
            String iban = b == null ? "" : b.optString("iban");
            if (!FacturenHerken.ibanOk(iban)) return uit;
            uit.put("land", iban.substring(0, 2)).put("buitenland", !iban.startsWith("NL")).put("bank", bank(iban, lijst));
            JSONObject k = bekend(iban, lijst), ou = k == null ? oud(iban, lijst) : null;
            if (ou != null) { k = ou; uit.put("oud", true); }
            if (k != null) uit.put("bekend", k.optString("naam")).put("bron", k.optString("bron")).put("naam", k.optString("naam"));
            else {
                JSONObject g = genoemd(kop(b.optString("naam"), tekst), lijst);
                if (g != null) uit.put("verdacht", g.optString("naam")).put("bron", g.optString("bron"));
            }
            int n = 0; long laatst = -1; String eerderNaam = "";
            if (alle != null) for (int i = 0; i < alle.length(); i++) {
                JSONObject x = alle.optJSONObject(i);
                if (x == null || x.optString("id").equals(b.optString("id")) || !iban.equals(x.optString("iban"))) continue;
                if (!"betaald".equals(x.optString("status")) || !x.optBoolean("bevestigd")) continue;
                n++;
                long t = x.optLong("gewijzigd", x.optLong("gemaakt", 0));
                if (t >= laatst && !x.optString("naam").isEmpty()) { laatst = t; eerderNaam = x.optString("naam"); }
            }
            uit.put("eerder", n);
            if (!eerderNaam.isEmpty()) { uit.put("eerderNaam", eerderNaam); if (!uit.has("naam")) uit.put("naam", eerderNaam); }
        } catch (Exception ignored) { }
        return uit;
    }
}
