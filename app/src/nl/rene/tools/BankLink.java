package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Automatisch importeren bij de bank (BankLinkActivity): de banken uit web/bank-banken.json en de regels die bepalen
 * waar de website heen mag. Zuiver (getest in BankTest). Geen derde partij: alleen de telefoon en de bank zelf.
 */
final class BankLink {

    private BankLink() { }

    static volatile JSONArray cache;

    static JSONArray banken(android.content.Context c) {
        JSONArray b = cache;
        if (b != null) return b;
        try (java.io.InputStream in = c.getAssets().open("bank-banken.json")) {
            b = banken(new String(Facturen.lees(in, 200_000), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) { b = new JSONArray(); }
        cache = b;
        return b;
    }

    /** Leest en controleert de lijst; een bank met een ongeldige robot-instelling krijgt robot=false (alleen handmatig). */
    static JSONArray banken(String json) throws Exception {
        JSONArray in = new JSONObject(json).getJSONArray("banken"), out = new JSONArray();
        for (int i = 0; i < in.length(); i++) {
            JSONObject b = new JSONObject(in.getJSONObject(i).toString());
            if (!b.optString("id").matches("[a-z0-9-]{2,30}") || b.optString("naam").isEmpty()) continue;
            if (b.optBoolean("robot") && !geldig(b)) b.put("robot", false);
            out.put(b);
        }
        return out;
    }

    static boolean geldig(JSONObject b) {
        JSONArray d = b.optJSONArray("domeinen");
        if (d == null || d.length() == 0) return false;
        for (int i = 0; i < d.length(); i++) if (!d.optString(i).matches("[a-z0-9-]+(\\.[a-z0-9-]+)+")) return false;
        if (!toegestaan(b.optString("startUrl"), b)) return false;
        for (String k : new String[]{"inlog", "menu", "naarDownload", "formaat", "knop"}) {
            try { java.util.regex.Pattern.compile(b.optString(k)); } catch (Exception e) { return false; }
            if (b.optString(k).isEmpty()) return false;
        }
        return true;
    }

    static JSONObject bank(JSONArray l, String id) {
        for (int i = 0; i < l.length(); i++) if (l.optJSONObject(i) != null && l.optJSONObject(i).optString("id").equals(id)) return l.optJSONObject(i);
        return null;
    }

    /** Mag de website hierheen? Alleen https en alleen de domeinen van deze bank (en hun subdomeinen). */
    static boolean toegestaan(String url, JSONObject b) {
        if (url == null || !url.trim().toLowerCase(Locale.ROOT).startsWith("https://")) return false;
        String h = Facturen.host(url);
        if (h.isEmpty()) return false;
        JSONArray d = b.optJSONArray("domeinen");
        if (d == null) return false;
        for (int i = 0; i < d.length(); i++) { String x = d.optString(i); if (!x.isEmpty() && (h.equals(x) || h.endsWith("." + x))) return true; }
        return false;
    }

    /** Mag deze app geopend worden (om in te loggen met de app van de bank)? Alleen de genoemde. */
    static boolean appToegestaan(String pkg, JSONObject b) {
        JSONArray a = b.optJSONArray("apps");
        if (pkg == null || a == null) return false;
        for (int i = 0; i < a.length(); i++) if (pkg.equals(a.optString(i))) return true;
        return false;
    }

    /** Vanaf welke datum ophalen: een paar dagen vóór de laatste boeking van deze bank (overlap valt weg als dubbel),
     *  zonder eerdere boekingen 13 maanden terug. vandaag/laatst als jjjj-mm-dd. */
    static String vanaf(String vandaag, String laatst) {
        java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"), Locale.ROOT);
        String basis = laatst == null || laatst.isEmpty() ? vandaag : laatst;
        c.clear();
        c.set(Integer.parseInt(basis.substring(0, 4)), Integer.parseInt(basis.substring(5, 7)) - 1, Integer.parseInt(basis.substring(8, 10)));
        if (laatst == null || laatst.isEmpty()) c.add(java.util.Calendar.MONTH, -13);
        else c.add(java.util.Calendar.DAY_OF_MONTH, -5);
        return String.format(Locale.ROOT, "%04d-%02d-%02d", c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH));
    }

    /** Instellingen voor de robot in de pagina (alleen patronen en de begindatum, geen gegevens). */
    static String robotCfg(JSONObject b, String van) throws Exception {
        JSONObject o = new JSONObject();
        for (String k : new String[]{"inlog", "menu", "naarDownload", "formaat", "formaatReserve", "alleRekeningen", "knop"}) o.put(k, b.optString(k));
        o.put("van", van == null ? "" : van);
        return o.toString();
    }
}
