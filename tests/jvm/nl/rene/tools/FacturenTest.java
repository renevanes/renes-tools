package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;

/** Facturen ophalen: instellingen controleren, sjablonen, inlogcode vinden, bestandsnamen en wanneer het aan de beurt is. */
public class FacturenTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }
    static String fout(JSONObject o) { try { Facturen.normaal(o); return ""; } catch (Exception e) { return e.getMessage(); } }

    public static void main(String[] a) throws Exception {
        // ---------- sjablonen ----------
        JSONObject sj = new JSONObject(new String(Files.readAllBytes(Paths.get("web/facturen-leveranciers.json")), StandardCharsets.UTF_8));
        JSONArray lijst = sj.getJSONArray("sjablonen");
        check(lijst.length() >= 2 && lijst.getJSONObject(0).getString("sjabloon").equals("kpn-zakelijk"), "eerste sjabloon is KPN Zakelijk");
        HashSet<String> ids = new HashSet<>();
        for (int i = 0; i < lijst.length(); i++) {
            JSONObject s = lijst.getJSONObject(i);
            check(ids.add(s.getString("sjabloon")), "sjabloon uniek: " + s.getString("sjabloon"));
            JSONObject t = new JSONObject(s.toString());
            if (t.optString("naam").isEmpty()) t.put("naam", "Test");
            if (t.optString("startUrl").isEmpty()) t.put("startUrl", "https://voorbeeld.nl/login");
            check(fout(t).isEmpty(), "sjabloon geldig: " + s.getString("sjabloon") + " " + fout(t));
        }
        JSONObject kpn = Facturen.normaal(lijst.getJSONObject(0));
        check(kpn.getString("id").equals("kpn-zakelijk") && kpn.getJSONObject("code").getString("bron").equals("sms"), "KPN: id en code per sms");
        check(kpn.getString("schema").equals("week") && kpn.getBoolean("map") && !kpn.getBoolean("eigenCloud"), "standaard: elke week, backup-map aan, Eigen Cloud uit");
        check(kpn.getString("submap").equals("KPN Zakelijk"), "mapnaam = naam");

        // ---------- controleren ----------
        JSONObject basis = new JSONObject().put("naam", "Ziggo").put("startUrl", "https://www.ziggo.nl/mijn-ziggo");
        check(fout(basis).isEmpty(), "naam + https-adres is genoeg");
        check(fout(new JSONObject(basis.toString()).put("naam", "")).contains("naam"), "naam verplicht");
        check(fout(new JSONObject(basis.toString()).put("startUrl", "http://ziggo.nl")).contains("https://"), "alleen https");
        check(fout(new JSONObject(basis.toString()).put("startUrl", "javascript:alert(1)")).contains("https://"), "geen javascript:-adres");
        check(fout(new JSONObject(basis.toString()).put("facturenUrl", "file:///sdcard/x")).contains("https://"), "facturenpagina ook https");
        check(fout(new JSONObject(basis.toString()).put("knoppen", "download(")).contains("geen geldig zoekpatroon"), "kapot patroon geweigerd");
        check(fout(new JSONObject(basis.toString()).put("schema", "uur")).contains("hoe vaak"), "onbekend schema geweigerd");
        check(fout(new JSONObject(basis.toString()).put("code", new JSONObject().put("bron", "telepathie"))).contains("bron"), "onbekende codebron geweigerd");
        check(fout(new JSONObject(basis.toString()).put("velden", new JSONObject().put("knop", "button{color:red}"))).contains("veldaanwijzing"), "rare veldaanwijzing geweigerd");
        JSONObject n = Facturen.normaal(new JSONObject(basis.toString()).put("max", 500).put("submap", "a/b:c").put("id", "Mijn Ziggo!!"));
        check(n.getInt("max") == 50, "hooguit 50 per keer");
        check(n.getString("submap").equals("a_b_c"), "mapnaam veilig: " + n.getString("submap"));
        check(n.getString("id").equals("mijn-ziggo"), "id veilig: " + n.getString("id"));
        check(Facturen.normaal(new JSONObject(basis.toString()).put("knoppen", "")).getString("knoppen").contains("download"), "leeg patroon: standaard");
        check(Facturen.uniekId("KPN Zakelijk", new HashSet<>(Arrays.asList("kpn-zakelijk", "kpn-zakelijk-2"))).equals("kpn-zakelijk-3"), "tweede en derde KPN-account");
        check(Facturen.veiligId("").equals("leverancier") && Facturen.veiligId("---").equals("leverancier"), "lege id");

        // ---------- inlogcode ----------
        check("482913".equals(Facturen.vindCode("Je KPN verificatiecode is: 482913. Deel deze code met niemand.", "")), "code uit KPN-sms");
        check("7731".equals(Facturen.vindCode("Toegangscode 7731 (geldig 10 min) voor klantnummer 12345678", "")), "korte code bij het woord code, niet het klantnummer");
        check("123456".equals(Facturen.vindCode("Gebruik 123456 om in te loggen", "")), "losse 6 cijfers");
        check(Facturen.vindCode("Je factuur staat klaar", "") == null, "geen code");
        check("AB12CD".equals(Facturen.vindCode("Code: AB12CD", "Code: ([A-Z0-9]{6})")), "eigen patroon met groep");
        check("9999".equals(Facturen.vindCode("x 9999 y", "\\d{4}")), "eigen patroon zonder groep");
        check(Facturen.vindCode("code 123456", "(") == null, "kapot patroon: geen code, geen crash");
        check(Facturen.komtVan("KPN", "x", "kpn") && Facturen.komtVan("+316", "Uw KPN code", "KPN") && !Facturen.komtVan("Bank", "code 1234", "KPN") && Facturen.komtVan("x", "y", ""), "afzender herkennen");

        // ---------- bestandsnamen ----------
        check(Facturen.naamUitDisposition("attachment; filename=\"Factuur 2026-10.pdf\"").equals("Factuur 2026-10.pdf"), "filename=\"…\"");
        check(Facturen.naamUitDisposition("attachment; filename*=UTF-8''Factuur%20oktober%20%E2%82%AC.pdf; filename=\"x.pdf\"").equals("Factuur oktober €.pdf"), "filename* gaat voor");
        check(Facturen.naamUitDisposition("inline; filename=F123.pdf").equals("F123.pdf"), "zonder aanhalingstekens");
        check(Facturen.naamUitDisposition(null).isEmpty(), "geen kop");
        String sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        check(Facturen.bestandsnaam("Factuur 2026-10.pdf", null, "KPN Zakelijk", sha).equals("KPN Zakelijk Factuur 2026-10.pdf"), "naam van de leverancier ervoor");
        check(Facturen.bestandsnaam("KPN_factuur_123.pdf", null, "KPN Zakelijk", sha).equals("KPN_factuur_123.pdf"), "begint al met KPN: zo laten");
        check(Facturen.bestandsnaam("", "https://x.nl/api/invoices/F-2026-0042.pdf?token=abc", "Ziggo", sha).equals("Ziggo F-2026-0042.pdf"), "naam uit het adres");
        check(Facturen.bestandsnaam("", "https://x.nl/download/3f2a9c1e-77aa-4b1c-9d2e-0a1b2c3d4e5f", "Ziggo", sha).equals("Ziggo factuur 01234567.pdf"), "nietszeggend adres: factuur + code");
        check(Facturen.bestandsnaam("../../geheim.pdf", null, "X", sha).equals("X .._.._geheim.pdf"), "geen mappen in de naam: " + Facturen.bestandsnaam("../../geheim.pdf", null, "X", sha));
        check(!Facturen.bestandsnaam("a:b*c?.pdf", null, "", sha).matches(".*[:*?].*"), "FAT-veilig");

        // ---------- PDF herkennen ----------
        check(Facturen.isPdf("%PDF-1.7\n...".getBytes(StandardCharsets.UTF_8)), "PDF");
        check(Facturen.isPdf(("\n\n%PDF-1.4").getBytes(StandardCharsets.UTF_8)), "PDF met wat ervoor");
        check(!Facturen.isPdf("<!doctype html><html>".getBytes(StandardCharsets.UTF_8)) && !Facturen.isPdf(null) && !Facturen.isPdf(new byte[2]), "geen PDF");

        // ---------- aan de beurt ----------
        long dag = 86_400_000L, nu = 100 * dag;
        check(Facturen.aanDeBeurt("week", 0, 0, nu), "nooit opgehaald: aan de beurt");
        check(!Facturen.aanDeBeurt("week", nu - 3 * dag, nu - 3 * dag, nu), "week: 3 dagen geleden nog niet");
        check(Facturen.aanDeBeurt("week", nu - 7 * dag, nu - 7 * dag, nu), "week: na 7 dagen wel");
        check(Facturen.aanDeBeurt("week", nu - 7 * dag + 3_600_000L, nu - 7 * dag + 3_600_000L, nu), "dagelijkse taak iets eerder: toch aan de beurt");
        check(!Facturen.aanDeBeurt("week", nu - 9 * dag, nu - 2 * 3_600_000L, nu), "net mislukt: niet meteen weer (geen meldingenregen)");
        check(Facturen.aanDeBeurt("week", nu - 9 * dag, nu - dag, nu), "mislukt gisteren: vandaag opnieuw");
        check(Facturen.aanDeBeurt("dag", nu - dag, nu - dag, nu) && Facturen.aanDeBeurt("maand", nu - 30 * dag, nu - 30 * dag, nu) && !Facturen.aanDeBeurt("maand", nu - 20 * dag, nu - 20 * dag, nu), "dag en maand");
        check(!Facturen.aanDeBeurt("uit", 0, 0, nu), "uit: nooit vanzelf");

        // ---------- evaluateJavascript-uitkomst ----------
        check(FacturenRun.str("\"{\\\"a\\\":1}\"").equals("{\"a\":1}") && FacturenRun.str("null").isEmpty() && FacturenRun.str(null).isEmpty() && FacturenRun.str("12").equals("12"), "uitkomst van de robot lezen");

        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
    }
}
