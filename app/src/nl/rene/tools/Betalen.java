package nl.rene.tools;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Betalen: betalingen die Rene klaarzet uit een foto, een bestand, het facturendossier of met de hand. Tools betaalt
 * nooit zelf: een betaling is hier alleen een voorstel (ontvanger, IBAN, bedrag, kenmerk) dat Rene controleert en daarna
 * zelf bij zijn bank ondertekent. Opslag: betalen/betalingen.json (mee in "Alle appgegevens", niet in de Android-backup
 * en niet in de leesbare Toolgegevens). De foto of het bestand zelf wordt niet bewaard. Het zuivere deel (normaal,
 * compleet) is getest in BetalenTest. Logsoort BETALEN: alleen vaste woorden en aantallen, nooit namen, nummers of bedragen.
 */
final class Betalen {

    private Betalen() { }

    static final String DIR = "betalen";
    static final int FORMAAT = 1, MAX = 500;
    static final List<String> STATUS = Arrays.asList("open", "klaargezet", "betaald");
    static final List<String> BRON = Arrays.asList("foto", "bestand", "dossier", "hand");

    /** Een fout met een Nederlandse tekst die Rene mag zien. */
    static class Fout extends Exception { Fout(String nl) { super(nl); } }

    // ---------------------------------------------------------------- controleren (zuiver)

    /**
     * Maakt van wat de interface stuurt een nette betaling; alles wordt gecontroleerd, onbekende velden vervallen.
     * Een betaling mag onaf bewaard worden (nog geen IBAN of bedrag); compleet() zegt of hij klaargezet kan worden.
     */
    static JSONObject normaal(JSONObject in, List<String> bekendeHosts) throws Exception {
        JSONObject o = new JSONObject();
        String id = in.optString("id");
        o.put("id", id.matches("b[0-9a-f]{12}") ? id : nieuwId());
        o.put("naam", BetalenHerken.netjes(in.optString("naam"), 70));
        String iban = FacturenHerken.nummer(in.optString("iban"));
        if (!iban.isEmpty() && !FacturenHerken.ibanOk(iban)) throw new Fout(Taal.T("Dit IBAN klopt niet. Kijk het na op de rekening."));
        o.put("iban", iban);
        String bedrag = in.optString("bedrag").trim();
        if (!bedrag.isEmpty()) {
            String b = FacturenDossier.bedrag(bedrag);
            if (b == null || !BetalenHerken.bedragOk(b)) throw new Fout(Taal.T("Vul het bedrag in als 123,45."));
            bedrag = b;
        }
        o.put("bedrag", bedrag);
        String kenmerk = in.optString("kenmerk").replace(" ", "").toUpperCase(Locale.ROOT);
        if (!kenmerk.isEmpty() && !kenmerk.matches("[A-Z0-9]{1,35}")) throw new Fout(Taal.T("Een betalingskenmerk heeft alleen cijfers en letters."));
        // Een kenmerk van 16 cijfers wordt hier niet op zijn controlecijfer geweigerd: dat doet de bank; Tools gebruikt die proef alleen bij het herkennen.
        if (kenmerk.startsWith("RF") && !BetalenHerken.rfOk(kenmerk)) throw new Fout(Taal.T("Dit betalingskenmerk klopt niet. Kijk het na op de rekening."));
        o.put("kenmerk", kenmerk);
        o.put("omschrijving", BetalenHerken.netjes(in.optString("omschrijving"), 140));
        String verval = in.optString("vervaldatum");
        o.put("vervaldatum", verval.matches("20\\d{2}-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])") ? verval : "");
        String link = in.optString("link");
        if (!link.isEmpty()) {
            link = BetalenHerken.link(link);
            if (link == null) throw new Fout(Taal.T("Deze betaallink is geen veilig adres (https)."));
            o.put("link", link).put("linkHost", Facturen.host(link)).put("linkBekend", BetalenHerken.hostBekend(Facturen.host(link), bekendeHosts));
        } else o.put("link", "");
        String status = in.optString("status", "open");
        o.put("status", STATUS.contains(status) ? status : "open");
        String bron = in.optString("bron", "hand");
        o.put("bron", BRON.contains(bron) ? bron : "hand");
        String sha = in.optString("sha");
        o.put("sha", sha.matches("[0-9a-f]{64}") ? sha : "");
        o.put("gemaakt", Math.max(0, in.optLong("gemaakt")));
        o.put("gewijzigd", Math.max(0, in.optLong("gewijzigd")));
        o.put("betaaldOp", Math.max(0, in.optLong("betaaldOp")));
        return o;
    }

    static String nieuwId() { return "b" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12); }

    /** Kan deze betaling bij de bank klaargezet worden? Dan zijn naam, IBAN en bedrag er. */
    static boolean compleet(JSONObject b) {
        return b != null && !b.optString("naam").isEmpty() && FacturenHerken.ibanOk(b.optString("iban")) && BetalenHerken.bedragOk(b.optString("bedrag"));
    }

    /** Is dit IBAN nieuw (nog niet eerder gebruikt in een andere betaling)? Dan vraagt het scherm om extra controle. */
    static boolean nieuweOntvanger(JSONArray alle, JSONObject b) {
        String iban = b.optString("iban");
        if (iban.isEmpty()) return false;
        for (int i = 0; i < alle.length(); i++) {
            JSONObject x = alle.optJSONObject(i);
            if (x != null && !x.optString("id").equals(b.optString("id")) && iban.equals(x.optString("iban")) && "betaald".equals(x.optString("status"))) return false;
        }
        return true;
    }

    /** Instellingen: {bank, eigen[] (eigen rekeningnummers), naKlaar ("vragen"|"betaald"|"niets")}. */
    static JSONObject instNormaal(JSONObject in) throws Exception {
        JSONObject o = new JSONObject();
        String bank = in == null ? "" : in.optString("bank", "rabobank");
        o.put("bank", bank.matches("[a-z0-9-]{2,30}") ? bank : "rabobank");
        JSONArray eigen = new JSONArray(), a = in == null ? null : in.optJSONArray("eigen");
        if (a != null) for (int i = 0; i < a.length() && eigen.length() < 20; i++) {
            String n = FacturenHerken.nummer(a.optString(i));
            if (n.isEmpty()) continue;
            if (!FacturenHerken.ibanOk(n)) throw new Fout(Taal.T("Een van je eigen rekeningnummers klopt niet."));
            eigen.put(n);
        }
        o.put("eigen", eigen);
        String na = in == null ? "" : in.optString("naKlaar", "vragen");
        o.put("naKlaar", Arrays.asList("vragen", "betaald", "niets").contains(na) ? na : "vragen");
        return o;
    }

    // ---------------------------------------------------------------- opslag

    static File map(Context c) {
        File d = new File(c.getFilesDir(), DIR);
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /** null = het bestand is er wel maar niet te lezen (dan nooit overschrijven). */
    static synchronized JSONObject lees(Context c) {
        File f = new File(map(c), "betalingen.json");
        try {
            if (!f.isFile()) return new JSONObject().put("format", FORMAAT);
            JSONObject o = new JSONObject(Contacts.readText(f, false));
            return o.optInt("format", FORMAAT) > FORMAAT ? null : o;
        } catch (Exception e) { return null; }
    }

    private static void schrijf(Context c, JSONObject o) throws Exception {
        o.put("format", FORMAAT);
        Contacts.writeText(new File(map(c), "betalingen.json"), o.toString(), false); // eerst een .tmp, dan hernoemen
    }

    private static JSONObject open(Context c) throws Fout {
        JSONObject o = lees(c);
        if (o == null) throw new Fout(Taal.T("De gegevens van Betalen zijn niet leesbaar. Er is niets overschreven."));
        return o;
    }

    static JSONArray lijst(JSONObject bestand) {
        JSONArray a = bestand == null ? null : bestand.optJSONArray("betalingen");
        return a == null ? new JSONArray() : a;
    }

    static JSONObject inst(JSONObject bestand) {
        try { return instNormaal(bestand == null ? null : bestand.optJSONObject("inst")); }
        catch (Exception e) { try { return instNormaal(null); } catch (Exception x) { return new JSONObject(); } }
    }

    static JSONObject vind(JSONArray a, String id) {
        for (int i = 0; i < a.length(); i++) { JSONObject b = a.optJSONObject(i); if (b != null && b.optString("id").equals(id)) return b; }
        return null;
    }

    static synchronized JSONObject bewaar(Context c, JSONObject in, List<String> bekendeHosts) throws Exception {
        JSONObject bestand = open(c), b = normaal(in, bekendeHosts);
        JSONArray oud = lijst(bestand), nieuw = new JSONArray();
        long nu = System.currentTimeMillis();
        JSONObject was = vind(oud, b.optString("id"));
        b.put("gemaakt", was != null ? was.optLong("gemaakt") : nu).put("gewijzigd", nu);
        if ("betaald".equals(b.optString("status"))) b.put("betaaldOp", was != null && was.optLong("betaaldOp") > 0 ? was.optLong("betaaldOp") : nu);
        else b.put("betaaldOp", 0);
        if (was == null && oud.length() >= MAX) throw new Fout(Taal.T("Er staan te veel betalingen. Verwijder eerst oude betalingen."));
        boolean gezet = false;
        for (int i = 0; i < oud.length(); i++) {
            JSONObject x = oud.optJSONObject(i);
            if (x == null) continue;
            if (x.optString("id").equals(b.optString("id"))) { nieuw.put(b); gezet = true; } else nieuw.put(x);
        }
        if (!gezet) nieuw.put(b);
        schrijf(c, bestand.put("betalingen", nieuw));
        return b;
    }

    static synchronized void status(Context c, String id, String status) throws Exception {
        if (!STATUS.contains(status)) return;
        JSONObject bestand = open(c);
        JSONObject b = vind(lijst(bestand), id);
        if (b == null) return;
        b.put("status", status).put("gewijzigd", System.currentTimeMillis()).put("betaaldOp", "betaald".equals(status) ? System.currentTimeMillis() : 0);
        schrijf(c, bestand);
    }

    static synchronized void wis(Context c, String id) throws Exception {
        JSONObject bestand = open(c);
        JSONArray oud = lijst(bestand), nieuw = new JSONArray();
        for (int i = 0; i < oud.length(); i++) { JSONObject x = oud.optJSONObject(i); if (x != null && !x.optString("id").equals(id)) nieuw.put(x); }
        schrijf(c, bestand.put("betalingen", nieuw));
    }

    static synchronized JSONObject bewaarInst(Context c, JSONObject in) throws Exception {
        JSONObject bestand = open(c), i = instNormaal(in);
        schrijf(c, bestand.put("inst", i));
        return i;
    }
}
