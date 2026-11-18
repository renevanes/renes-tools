package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Betalen, klaarzetten bij de bank (BetaalLinkActivity): de instellingen per bank uit web/bank-banken.json (het blok
 * "betalen") en de domeinen van betaaldiensten ("betaallinks"). Zuiver (getest in BetalenTest). Waar de website heen mag
 * regelt BankLink.toegestaan (alleen https en alleen de domeinen van de bank).
 */
final class BetaalLink {

    private BetaalLink() { }

    static final String[] PATRONEN = {"naar", "menu", "naam", "iban", "bedrag", "omschrijving", "kenmerk"};
    static final String[] VERPLICHT = {"naar", "naam", "iban", "bedrag", "omschrijving"};

    static volatile List<String> hostsCache;

    /** De domeinen van betaaldiensten die Tools kent (voor een betaallink uit een QR-code). */
    static List<String> hosts(android.content.Context c) {
        List<String> h = hostsCache;
        if (h != null) return h;
        try (java.io.InputStream in = c.getAssets().open("bank-banken.json")) {
            h = hosts(new String(Facturen.lees(in, 200_000), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) { h = new ArrayList<>(); }
        hostsCache = h;
        return h;
    }

    static List<String> hosts(String json) throws Exception {
        List<String> uit = new ArrayList<>();
        JSONArray a = new JSONObject(json).optJSONArray("betaallinks");
        if (a != null) for (int i = 0; i < a.length(); i++) { String s = a.optString(i); if (s.matches("[a-z0-9-]+(\\.[a-z0-9-]+)+")) uit.add(s); }
        return uit;
    }

    /** Kan Tools bij deze bank een overboeking klaarzetten? Dan staan de bank (domeinen, startadres, inlog) en alle patronen er goed in. */
    static boolean kan(JSONObject bank) {
        if (bank == null || !BankLink.toegestaan(bank.optString("startUrl"), bank) || bank.optString("inlog").isEmpty()) return false;
        JSONObject b = bank.optJSONObject("betalen");
        if (b == null) return false;
        for (String k : VERPLICHT) if (b.optString(k).isEmpty()) return false;
        for (String k : PATRONEN) {
            try { java.util.regex.Pattern.compile(b.optString(k)); } catch (Exception e) { return false; }
        }
        try { java.util.regex.Pattern.compile(bank.optString("inlog")); } catch (Exception e) { return false; }
        return true;
    }

    /** Instellingen voor de robot in de pagina: alleen de zoekpatronen. */
    static String robotCfg(JSONObject bank) throws Exception {
        JSONObject o = new JSONObject().put("inlog", bank.optString("inlog")), b = bank.getJSONObject("betalen");
        for (String k : PATRONEN) o.put(k, b.optString(k));
        return o.toString();
    }

    /** Wat de robot invult: alleen deze velden van de betaling (het bedrag met een komma, zoals op de website van de bank). */
    static String robotBetaling(JSONObject b) throws Exception {
        if (!Betalen.compleet(b)) throw new Betalen.Fout(Taal.T("Vul eerst de naam, het IBAN en het bedrag in."));
        return new JSONObject().put("naam", b.optString("naam")).put("iban", b.optString("iban")).put("bedrag", b.optString("bedrag"))
                .put("kenmerk", b.optString("kenmerk")).put("omschrijving", b.optString("omschrijving")).toString();
    }
}
