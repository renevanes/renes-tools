package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Podcasts: afgespeeld met de slaaptimer (het zuivere deel van PodcastSlaap). */
public class PodcastSlaapTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    static JSONObject regel(String key, long van, long d, long t, long sessie, String st) throws Exception {
        return new JSONObject().put("key", key).put("van", van).put("d", d).put("t", t).put("sessie", sessie).put("st", st)
                .put("ep", new JSONObject().put("key", key).put("title", "Aflevering " + key).put("url", "https://example.org/" + key + ".mp3"))
                .put("pod", new JSONObject().put("id", "p1").put("title", "Podcast"));
    }

    public static void main(String[] a) throws Exception {
        long nu = 100L * 24 * 3600_000L, min = 60_000L;
        String k1 = "e0000000000000001", k2 = "e0000000000000002", k3 = "e0000000000000003";

        // Instellingen: alleen bekende waarden
        check(PodcastSlaap.norm("vanzelf").equals("vanzelf") && PodcastSlaap.norm("uit").equals("uit") && PodcastSlaap.norm("onzin").equals("vragen") && PodcastSlaap.norm(null).equals("vragen"),
                "wijze: vragen is de standaard, onbekend wordt vragen");
        check(PodcastSlaap.normPlek("begin").equals("begin") && PodcastSlaap.normPlek("x").equals("wakker"), "plek: standaard waar je nog wakker was");

        // Erbij: dezelfde aflevering vervangt, nieuwste achteraan, hooguit MAX
        JSONArray l = PodcastSlaap.erbij(new JSONArray(), regel(k1, 0, 60 * min, nu, 1, "open"));
        l = PodcastSlaap.erbij(l, regel(k2, 0, 60 * min, nu, 1, "open"));
        l = PodcastSlaap.erbij(l, regel(k1, 5 * min, 60 * min, nu + 1, 2, "open"));
        check(l.length() == 2 && l.getJSONObject(1).optString("key").equals(k1) && l.getJSONObject(1).optLong("van") == 5 * min,
                "dezelfde aflevering één keer, de nieuwste regel telt");
        JSONArray vol = new JSONArray();
        for (int i = 0; i < PodcastSlaap.MAX + 5; i++) vol = PodcastSlaap.erbij(vol, regel(String.format("e%016x", i), 0, 0, nu, 1, "open"));
        check(vol.length() == PodcastSlaap.MAX && vol.getJSONObject(0).optString("key").equals(String.format("e%016x", 5)), "hooguit " + PodcastSlaap.MAX + ", de oudste gaan eruit");

        List<String> eruit = new ArrayList<>();
        JSONArray vol2 = new JSONArray();
        for (int i = 0; i < PodcastSlaap.MAX; i++) vol2 = PodcastSlaap.erbij(vol2, regel(String.format("e%016x", i), 0, 0, nu, 1, i == 0 ? "open" : "gehoord"));
        PodcastSlaap.erbij(vol2, regel("e1000000000000000", 0, 0, nu, 1, "open"), eruit);
        check(eruit.size() == 1 && eruit.get(0).equals(String.format("e%016x", 0)), "valt een open regel eruit, dan telt hij als gehoord (download mag weg)");

        // Wakker vlak voor het einde: hij hoorde het einde, dus niet bijhouden
        check(PodcastSlaap.telt(10 * min, 60 * min) && PodcastSlaap.telt(0, 0), "gewoon in slaap gevallen: telt");
        check(!PodcastSlaap.telt(59 * min + 30_000, 60 * min), "in de laatste minuut nog wakker: telt niet");

        // Opschonen: na twee weken weg; wat toen nog open stond telt als gehoord
        JSONArray oud = new JSONArray().put(regel(k1, 0, 0, nu - PodcastSlaap.BEWAAR_MS - 1, 1, "open"))
                .put(regel(k2, 0, 0, nu - PodcastSlaap.BEWAAR_MS - 1, 1, "terug")).put(regel(k3, 0, 0, nu - 1000, 1, "open"));
        List<String> weg = new ArrayList<>();
        JSONArray schoon = PodcastSlaap.opschonen(oud, nu, weg);
        check(schoon.length() == 1 && schoon.getJSONObject(0).optString("key").equals(k3), "te oud gaat eruit, recent blijft");
        check(weg.size() == 1 && weg.get(0).equals(k1), "alleen wat nog open stond mag zijn download kwijt");

        // De plek om naar terug te gaan
        check(PodcastSlaap.terugPlek(regel(k1, 23 * min, 60 * min, nu, 1, "open"), "wakker") == 23 * min, "terug naar waar je nog wakker was");
        check(PodcastSlaap.terugPlek(regel(k1, 23 * min, 60 * min, nu, 1, "open"), "begin") == 0, "of het begin, als dat zo ingesteld is");
        check(PodcastSlaap.terugPlek(regel(k1, 59 * min + 50_000, 60 * min, nu, 1, "open"), "wakker") == 59 * min, "nooit vlak bij het einde (dan liep hij meteen weer af)");
        check(PodcastSlaap.terugPlek(regel(k1, 30_000, 40_000, nu, 1, "open"), "wakker") == 0, "heel kort: het begin");
        check(PodcastSlaap.terugPlek(regel(k1, -5, 0, nu, 1, "open"), "wakker") == 0, "lengte onbekend en een rare plek: het begin");

        // Vanzelf: alleen wat in deze sessie afliep en nog open staat
        JSONArray s = new JSONArray().put(regel(k1, 0, 0, nu, 7, "open")).put(regel(k2, 0, 0, nu, 8, "open")).put(regel(k3, 0, 0, nu, 7, "gehoord"));
        List<String> v = PodcastSlaap.openVan(s, 7);
        check(v.size() == 1 && v.get(0).equals(k1), "vanzelf: alleen open regels van deze slaaptimer");
        check(PodcastSlaap.openVan(s, 0).isEmpty(), "geen slaaptimer: niets vanzelf");

        // Schoon: wat uit een bestand (of een backup) komt en naar het scherm gaat
        JSONObject r = PodcastSlaap.schoon(regel(k1, 10, 20, 30, 40, "raar").put("extra", "<script>"));
        check(r != null && r.optString("st").equals("open") && !r.has("extra") && r.getJSONObject("ep").optString("title").equals("Aflevering " + k1), "onbekende stand wordt open, onbekende velden weg");
        check(PodcastSlaap.schoon(regel("x", 0, 0, 0, 0, "open")) == null, "ongeldige sleutel: weg");
        JSONObject ander = regel(k1, 0, 0, 0, 0, "open"); ander.getJSONObject("ep").put("key", k2);
        check(PodcastSlaap.schoon(ander) == null, "sleutel van de regel en van de aflevering moeten gelijk zijn");
        JSONObject geenUrl = regel(k1, 0, 0, 0, 0, "open"); geenUrl.getJSONObject("ep").put("url", "javascript:alert(1)");
        check(PodcastSlaap.schoon(geenUrl) == null, "aflevering zonder geldig adres: weg");
        check(PodcastSlaap.schoon(new JSONArray().put(regel(k1, 0, 0, 0, 0, "terug")).put("onzin").put(regel("x", 0, 0, 0, 0, "open"))).length() == 1, "lijst: alleen geldige regels");

        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
        System.out.println("alles goed");
    }
}
