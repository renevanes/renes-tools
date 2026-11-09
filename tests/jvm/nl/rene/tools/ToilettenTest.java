package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.zip.GZIPInputStream;

/** Toiletten en douches: sjablonen en bronnen controleren, de vier soorten bronnen lezen, cache, verschil en de eigen laag. */
public class ToilettenTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }
    static String fout(JSONObject o) { try { Toiletten.normaal(o); return ""; } catch (Exception e) { return e.getMessage(); } }
    static ToilettenSamen.Plek eerste(List<ToilettenSamen.Plek> l, String e) { for (ToilettenSamen.Plek p : l) if (p.e.equals(e)) return p; return null; }

    public static void main(String[] a) throws Exception {
        // ---------- sjablonen ----------
        JSONArray sj = new JSONObject(new String(Files.readAllBytes(Paths.get("web/toiletten-bronnen.json")), StandardCharsets.UTF_8)).getJSONArray("sjablonen");
        HashSet<String> ids = new HashSet<>();
        int standaard = 0;
        for (int i = 0; i < sj.length(); i++) {
            JSONObject s = sj.getJSONObject(i);
            check(ids.add(s.getString("sjabloon")), "sjabloon uniek: " + s.getString("sjabloon"));
            check(fout(s).isEmpty(), "sjabloon geldig: " + s.getString("sjabloon") + " " + fout(s));
            check(s.getString("url").startsWith("https://") && !s.optString("licentie").isEmpty(), "sjabloon met https en licentie: " + s.getString("sjabloon"));
            if (s.optBoolean("standaard")) standaard++;
        }
        check(standaard == 1 && sj.getJSONObject(0).getString("sjabloon").equals("osm-nl"), "alleen OpenStreetMap Nederland staat standaard aan");
        JSONArray std = Toiletten.standaard(sj);
        JSONObject osm = std.getJSONObject(0);
        check(std.length() == 1 && osm.getString("id").equals("osm-nl") && osm.getString("soort").equals("tools") && osm.getString("schema").equals("week") && osm.getBoolean("wifi"), "OSM: elke week, alleen via wifi");
        check(osm.getString("metaUrl").endsWith("/meta.json") && osm.getString("url").endsWith(".json.gz"), "OSM: momentopname met meta.json");
        for (int i = 0; i < sj.length(); i++) {
            JSONObject s = sj.getJSONObject(i);
            if (s.getString("licentie").contains("geen open licentie")) check(!s.optBoolean("aan", true) && !s.optString("tip").isEmpty(), "zonder open licentie staat uit, met uitleg: " + s.getString("sjabloon"));
        }

        // ---------- normaal ----------
        JSONObject b = new JSONObject().put("naam", "Mijn gemeente").put("soort", "geojson").put("url", "https://voorbeeld.nl/toiletten.geojson").put("licentie", "CC0");
        JSONObject n = Toiletten.normaal(b);
        check(n.getString("id").equals("mijn-gemeente") && n.getString("schema").equals("week") && n.getString("tijd").equals("04:00") && n.getBoolean("wifi") && n.getBoolean("aan"), "nieuwe bron: id uit de naam, elke week om 04:00, alleen wifi");
        check(fout(new JSONObject(b.toString()).put("url", "http://voorbeeld.nl/x")).contains("https://"), "alleen https");
        check(fout(new JSONObject(b.toString()).put("url", "https://gebruiker@voorbeeld.nl/x")).contains("https://"), "geen inloggegevens in het adres");
        check(fout(new JSONObject(b.toString()).put("licentie", "")).contains("licentie"), "licentie of bronvermelding is verplicht");
        check(fout(new JSONObject(b.toString()).put("soort", "ftp")).contains("Onbekende soort"), "onbekende soort bron");
        check(fout(new JSONObject(b.toString()).put("schema", "uur")).contains("hoe vaak"), "onbekende planning");
        check(fout(new JSONObject(b.toString()).put("tijd", "25:00")).contains("tijd"), "tijd controleren");
        check(fout(new JSONObject(b.toString()).put("plek", "kasteel")).contains("soort plek"), "onbekende soort plek");
        check(fout(new JSONObject(b.toString()).put("regels", new JSONArray().put(new JSONObject().put("veld", "x").put("patroon", "(").put("zet", "klanten")))).contains("zoekpatroon"), "ongeldig zoekpatroon");
        check(fout(new JSONObject(b.toString()).put("regels", new JSONArray().put(new JSONObject().put("veld", "x").put("patroon", "a").put("zet", "wissen")))).contains("Onbekende regel"), "onbekende regel");
        JSONObject ex = Toiletten.normaal(new JSONObject(b.toString()).put("extra", new JSONArray().put("rolstoel").put("raket")));
        check(ex.getJSONArray("extra").length() == 1, "alleen bekende vaste eigenschappen");
        check(Toiletten.normaal(new JSONObject(b.toString()).put("naam", "Eigen")).getString("id").equals("eigen-bron"), "een bron heet nooit eigen (dat zijn je eigen plekken)");
        JSONArray l2 = new JSONArray().put(new JSONObject().put("id", "mijn-gemeente")).put(new JSONObject().put("id", "mijn-gemeente-2"));
        String lang = "a234567890123456789012345678901234567890";
        check(Toiletten.vrijId(l2, "mijn-gemeente").equals("mijn-gemeente-3") && Toiletten.vrijId(l2, "ander").equals("ander")
                && Toiletten.vrijId(new JSONArray().put(new JSONObject().put("id", lang)), lang).length() <= 40, "nieuwe bron met dezelfde naam: eigen id (hooguit 40 tekens)");
        JSONObject csvB = Toiletten.normaal(new JSONObject(b.toString()).put("soort", "csv"));
        check(csvB.getJSONObject("velden").getString("lat").contains("latitude"), "CSV: standaard kolommen voor de coördinaten");
        String ag = Toiletten.normaal(new JSONObject(b.toString()).put("soort", "arcgis").put("url", "https://gis.voorbeeld.nl/arcgis/rest/services/Toilet/MapServer/0")).getString("url");
        check(ag.equals("https://gis.voorbeeld.nl/arcgis/rest/services/Toilet/MapServer/0/query?where=1%3D1&outFields=*&outSR=4326&f=geojson"), "ArcGIS: /query in graden als GeoJSON (" + ag + ")");
        String ag2 = Toiletten.arcgisUrl("https://x.nl/MapServer/2/query?where=type%3D1&f=json");
        check(ag2.equals("https://x.nl/MapServer/2/query?where=type%3D1&f=json&outFields=*&outSR=4326"), "ArcGIS: eigen keuzes blijven staan (" + ag2 + ")");
        JSONArray dubbel = new JSONArray().put(b).put(b);
        check(Toiletten.checkLijst(dubbel).length() == 1, "dubbele bron telt één keer");

        // ---------- momentopname (tools) ----------
        ProcessBuilder pb = new ProcessBuilder("python3", "tools/toiletten/ophalen.py", "--voorbeeld", "tools/toiletten/voorbeeld.json", "--uit", "build/test/toiletten").redirectErrorStream(true);
        Process pr = pb.start();
        byte[] uit = Facturen.lees(pr.getInputStream(), 1_000_000);
        check(pr.waitFor() == 0, "ophalen.py --voorbeeld slaagt " + (pr.exitValue() == 0 ? "" : new String(uit, StandardCharsets.UTF_8)));
        byte[] gz = Files.readAllBytes(Paths.get("build/test/toiletten/toiletten-nl.json.gz"));
        JSONObject meta = new JSONObject(new String(Files.readAllBytes(Paths.get("build/test/toiletten/meta.json")), StandardCharsets.UTF_8));
        check(meta.getString("sha256").equals(Facturen.sha(gz)) && meta.getInt("formaat") == Toiletten.FORMAAT, "meta.json: SHA-256 en formaat kloppen");
        List<ToilettenSamen.Plek> tl = ToilettenScan.leesTools(new String(ToilettenScan.gunzip(gz), StandardCharsets.UTF_8));
        check(tl.size() == meta.getInt("aantal"), "alle plekken uit de momentopname gelezen (" + tl.size() + ")");
        ToilettenSamen.Plek shell = eerste(tl, "n5");
        check(shell != null && shell.s.equals("tank") && (shell.f & Toiletten.MEESTAL) != 0 && (shell.f & Toiletten.KLANTEN) != 0 && shell.n.equals("Shell Ridderkerk"), "tankstation: meestal, voor klanten");
        check(eerste(tl, "n6") == null, "onbemand tankstation (Tango) niet");
        JSONObject regels = new JSONObject(new String(Files.readAllBytes(Paths.get("tools/toiletten/regels.json")), StandardCharsets.UTF_8));
        JSONObject bits = regels.getJSONObject("bits");
        boolean zelfde = true;
        for (int i = 0; i < Toiletten.BIT_NAAM.length; i++) zelfde &= bits.optInt(Toiletten.BIT_NAAM[i], -1) == (1 << i);
        check(zelfde && bits.length() == Toiletten.BIT_NAAM.length && regels.getInt("formaat") == Toiletten.FORMAAT, "bits en formaat gelijk aan tools/toiletten/regels.json");
        boolean soorten = true;
        JSONArray rs = regels.getJSONArray("soorten");
        for (int i = 0; i < rs.length(); i++) soorten &= Facturen.in(rs.getJSONObject(i).getString("soort"), Toiletten.SOORTEN);
        check(soorten, "elke soort uit regels.json kent de app");
        check(err(() -> ToilettenScan.leesTools("{\"formaat\":99,\"plekken\":[]}")).contains("werk de app bij"), "nieuwer formaat: eerst de app bijwerken");
        List<ToilettenSamen.Plek> raar = ToilettenScan.leesTools("{\"formaat\":1,\"plekken\":[[\"a\",91,4,\"toilet\",1,\"\",\"\"],[\"b\",52,4,\"kasteel\",99999,\"" + lang(300) + "\",\"\"],[\"\",52,4,\"toilet\",1,\"\",\"\"],[\"c\",0,0,\"toilet\",1,\"\",\"\"]]}");
        check(raar.size() == 1 && raar.get(0).s.equals("overig") && raar.get(0).f == (99999 & Toiletten.ALLE_BITS) && raar.get(0).n.length() <= 80, "plekken buiten bereik weg, onbekende soort = overig, teksten kort");

        // ---------- GeoJSON (Den Haag) ----------
        JSONObject dh = null;
        for (int i = 0; i < sj.length(); i++) if (sj.getJSONObject(i).getString("sjabloon").equals("den-haag-openbaar")) dh = Toiletten.normaal(sj.getJSONObject(i));
        String geo = "{\"type\":\"FeatureCollection\",\"features\":["
                + "{\"type\":\"Feature\",\"geometry\":{\"coordinates\":[4.3192668,52.0733352],\"type\":\"Point\"},\"properties\":{\"omschrijving\":\"Toilet Huijgenspark\",\"globalid\":\"{8494}\",\"zelfreinigend\":\"ja\",\"rolstoel_toegankelijk\":\"ja\",\"alleen_mannen\":\"nee\",\"openingstijden\":\"24/7\",\"verschoontafel\":\"nee\"}},"
                + "{\"type\":\"Feature\",\"geometry\":{\"coordinates\":[4.28796,52.1139],\"type\":\"Point\"},\"properties\":{\"omschrijving\":null,\"adres\":\"Zwolsestraat\",\"globalid\":\"{A718}\",\"rolstoel_toegankelijk\":\"nee\",\"alleen_mannen\":\"ja\",\"openingstijden\":\"vrijdag, zaterdag, zondag\"}},"
                + "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[4.30,52.00],[4.32,52.00],[4.32,52.02],[4.30,52.02],[4.30,52.00]]]},\"properties\":{\"globalid\":\"{P}\"}},"
                + "{\"type\":\"Feature\",\"geometry\":null,\"properties\":{\"globalid\":\"{X}\"}}]}";
        List<ToilettenSamen.Plek> gl = ToilettenScan.leesGeojson(geo, dh);
        ToilettenSamen.Plek hp = eerste(gl, "{8494}"), zw = eerste(gl, "{A718}"), pg = eerste(gl, "{P}");
        check(gl.size() == 3, "GeoJSON: plekken zonder geometrie overgeslagen");
        check(hp != null && hp.n.equals("Toilet Huijgenspark") && (hp.f & Toiletten.ROLSTOEL) != 0 && (hp.f & Toiletten.ALTIJD) != 0 && (hp.f & Toiletten.VERSCHOON) == 0 && hp.s.equals("toilet"), "GeoJSON: naam, rolstoel, 24/7 uit de velden");
        check(zw != null && zw.n.equals("Zwolsestraat") && (zw.f & Toiletten.URINOIR) != 0 && (zw.f & Toiletten.ROLSTOEL) == 0, "GeoJSON: tweede veld als de naam leeg is; regel zet urinoir");
        check(pg != null && Math.abs(pg.la - 52.008) < 0.001 && Math.abs(pg.lo - 4.308) < 0.001, "GeoJSON: vlak = midden van de rand");
        check(err(() -> ToilettenScan.leesGeojson("{\"features\":[{\"geometry\":{\"x\":92000,\"y\":437000},\"attributes\":{}}]}", dh2())).contains("niet in graden"), "ArcGIS in RD: duidelijke fout (outSR=4326)");
        List<ToilettenSamen.Plek> esri = ToilettenScan.leesGeojson("{\"features\":[{\"geometry\":{\"x\":4.47,\"y\":51.92},\"attributes\":{\"NAAM\":\"Strand\",\"OBJECTID\":7}}]}",
                Toiletten.normaal(new JSONObject(b.toString()).put("soort", "arcgis").put("plek", "douche").put("velden", new JSONObject().put("naam", "naam").put("id", "OBJECTID"))));
        check(esri.size() == 1 && esri.get(0).e.equals("7") && esri.get(0).n.equals("Strand") && (esri.get(0).f & Toiletten.DOUCHE) != 0 && esri.get(0).s.equals("douche"), "ArcGIS (f=json): x/y, attributen, veldnaam zonder hoofdletters, soort douche");
        check(err(() -> ToilettenScan.leesGeojson("{\"error\":{\"code\":400,\"message\":\"Invalid query\"}}", dh2())).contains("Invalid query"), "fout van de bron wordt getoond");
        List<ToilettenSamen.Plek> rec = ToilettenScan.leesGeojson("[{\"name\":\"AH\",\"kosten\":\"€ 0,50\",\"geo_point_2d\":{\"lon\":4.26,\"lat\":52.04}},{\"name\":\"Museum\",\"kosten\":\"0,00 euro\",\"geo_point_2d\":{\"lon\":4.27,\"lat\":52.05}}]",
                Toiletten.normaal(new JSONObject(b.toString()).put("velden", new JSONObject().put("naam", "name").put("prijs", "kosten"))));
        check(rec.size() == 2 && (rec.get(0).f & Toiletten.BETAALD) != 0 && (rec.get(1).f & Toiletten.GRATIS) != 0, "JSON-records (Opendatasoft): geo_point_2d en prijs");
        JSONObject amsB = null;
        for (int i = 0; i < sj.length(); i++) if (sj.getJSONObject(i).getString("sjabloon").equals("amsterdam")) amsB = Toiletten.normaal(sj.getJSONObject(i));
        List<ToilettenSamen.Plek> ams = ToilettenScan.leesGeojson("{\"features\":[{\"id\":1,\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[4.89,52.36]},\"properties\":{\"Soort\":\"Amsterdamse krul\",\"Prijs_per_gebruik\":0,\"Openingstijden\":\"24 uur\",\"Omschrijving\":\"Singel\",\"SELECTIE\":\"KRUL\"}},"
                + "{\"id\":2,\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[4.90,52.37]},\"properties\":{\"Soort\":\"Toilet in parkeergarage\",\"Prijs_per_gebruik\":0.5,\"Omschrijving\":\"\",\"SELECTIE\":\"PARKEER\"}}]}", amsB);
        check(ams.size() == 2 && ams.get(0).e.equals("1") && (ams.get(0).f & (Toiletten.URINOIR | Toiletten.GRATIS | Toiletten.ALTIJD)) == (Toiletten.URINOIR | Toiletten.GRATIS | Toiletten.ALTIJD), "Amsterdam: krul = urinoir, gratis, 24 uur");
        check(ams.get(1).n.equals("Toilet in parkeergarage") && (ams.get(1).f & (Toiletten.KLANTEN | Toiletten.BETAALD)) == (Toiletten.KLANTEN | Toiletten.BETAALD), "Amsterdam: parkeergarage = alleen klanten, betaald");
        JSONObject overslaan = Toiletten.normaal(new JSONObject(b.toString()).put("regels", new JSONArray().put(new JSONObject().put("veld", "status").put("patroon", "gesloten").put("zet", "overslaan"))));
        check(ToilettenScan.leesGeojson("{\"features\":[{\"geometry\":{\"type\":\"Point\",\"coordinates\":[4,52]},\"properties\":{\"status\":\"Gesloten\"}}]}", overslaan).isEmpty(), "regel 'overslaan' haalt een plek weg");

        // ---------- CSV ----------
        JSONObject cb = Toiletten.normaal(new JSONObject(b.toString()).put("soort", "csv").put("velden", new JSONObject().put("naam", "Naam").put("gratis", "Gratis")));
        List<ToilettenSamen.Plek> cl = ToilettenScan.leesCsv("\uFEFFNaam;Latitude;Longitude;Gratis\r\n\"Toilet; Markt\";51,9225;4,4792;ja\r\n\"Met \"\"aanhalingstekens\"\"\nen een regel\";51.93;4.48;nee\r\n;geen;getal;ja\r\n", cb);
        check(cl.size() == 2 && cl.get(0).n.equals("Toilet; Markt") && Math.abs(cl.get(0).la - 51.9225) < 1e-6 && (cl.get(0).f & Toiletten.GRATIS) != 0, "CSV: puntkomma, komma als decimaalteken, ja = gratis");
        check(cl.get(1).n.equals("Met \"aanhalingstekens\" en een regel") && (cl.get(1).f & Toiletten.BETAALD) != 0, "CSV: aanhalingstekens en een regel in een veld");
        List<ToilettenSamen.Plek> kl = ToilettenScan.leesCsv("name,geo_point_2d\nA,\"52.07, 4.30\"\n", Toiletten.normaal(new JSONObject(b.toString()).put("soort", "csv").put("velden", new JSONObject().put("naam", "name").put("punt", "geo_point_2d"))));
        check(kl.size() == 1 && Math.abs(kl.get(0).lo - 4.30) < 1e-6, "CSV: komma als scheidingsteken en coördinaten in één veld");
        check(err(() -> ToilettenScan.leesCsv("a;b\n1;2\n", cb)).contains("coördinaten"), "CSV zonder coördinaten: uitleg");

        // ---------- velden, verschil, cache ----------
        check(Boolean.TRUE.equals(ToilettenScan.jaNee("Ja")) && Boolean.FALSE.equals(ToilettenScan.jaNee("nee")) && ToilettenScan.jaNee("misschien") == null, "ja/nee lezen");
        check(Boolean.TRUE.equals(ToilettenScan.gratisPrijs("€ 0,00")) && Boolean.FALSE.equals(ToilettenScan.gratisPrijs("1,00")) && Boolean.TRUE.equals(ToilettenScan.gratisPrijs("Gratis")) && ToilettenScan.gratisPrijs("") == null, "prijs lezen");
        List<ToilettenSamen.Plek> oud = new ArrayList<>(), nieuw = new ArrayList<>();
        oud.add(new ToilettenSamen.Plek("a", 52, 4, "toilet", 1, "A", "")); oud.add(new ToilettenSamen.Plek("b", 52, 4, "toilet", 1, "B", "")); oud.add(new ToilettenSamen.Plek("c", 52, 4, "toilet", 1, "C", ""));
        nieuw.add(new ToilettenSamen.Plek("a", 52, 4, "toilet", 1, "A", "")); nieuw.add(new ToilettenSamen.Plek("b", 52, 4, "toilet", 9, "B", "")); nieuw.add(new ToilettenSamen.Plek("d", 52, 4, "toilet", 1, "D", ""));
        int[] v = ToilettenScan.verschil(oud, nieuw);
        check(v[0] == 1 && v[1] == 1 && v[2] == 1, "verschil: 1 nieuw, 1 gewijzigd, 1 weg");
        File cf = new File("build/test/toiletten/cache.json.gz");
        ToilettenScan.schrijfCache(cf, "2026-11-06", nieuw);
        List<ToilettenSamen.Plek> terug = ToilettenScan.leesCache(cf);
        check(terug.size() == 3 && terug.get(1).f == 9 && terug.get(2).n.equals("D"), "cache: schrijven en lezen");
        try (GZIPInputStream in = new GZIPInputStream(Files.newInputStream(cf.toPath()))) { check(new JSONObject(new String(Facturen.lees(in, 1_000_000), StandardCharsets.UTF_8)).getString("datum").equals("2026-11-06"), "cache: datum bewaard"); }
        check(ToilettenScan.leesCache(new File("build/test/toiletten/bestaat-niet.gz")).isEmpty(), "geen cache = leeg");

        // ---------- eigen laag ----------
        JSONObject e = Toiletten.leegEigen();
        JSONObject bronW = new JSONObject().put("s", "toilet").put("f", Toiletten.TOILET | Toiletten.BETAALD).put("n", "Markt").put("u", "").put("la", 51.92).put("lo", 4.48);
        String k = Toiletten.bewaarPlek(e, new JSONObject().put("k", "osm-nl:n1").put("s", "toilet").put("f", Toiletten.TOILET | Toiletten.GRATIS | Toiletten.BETAALD).put("n", "Markt").put("notitie", "sleutel bij de kiosk"), bronW, 1000);
        JSONObject w = e.getJSONObject("wijzig").getJSONObject("osm-nl:n1");
        check(k.equals("osm-nl:n1") && (w.getJSONObject("v").getInt("f") & Toiletten.BETAALD) == 0 && w.getJSONObject("was").getInt("f") == (Toiletten.TOILET | Toiletten.BETAALD), "bewerken: gratis wint van betaald, de bron blijft bewaard als 'was'");
        Toiletten.bewaarPlek(e, new JSONObject().put("k", "osm-nl:n1").put("s", "toilet").put("f", Toiletten.TOILET | Toiletten.BETAALD).put("n", "Markt"), new JSONObject(bronW.toString()).put("f", 99), 2000);
        check(!e.getJSONObject("wijzig").has("osm-nl:n1"), "bewerken terug naar wat de bron toen zei: bewerking weg");
        JSONObject e2 = Toiletten.leegEigen(), bronNu = new JSONObject(bronW.toString()).put("n", "Markt (nieuw)");
        Toiletten.bewaarPlek(e2, new JSONObject().put("k", "osm-nl:n1").put("s", "toilet").put("f", Toiletten.TOILET).put("n", "Mijn naam"), bronW, 1000);
        Toiletten.bewaarPlek(e2, new JSONObject().put("k", "osm-nl:n1").put("s", "toilet").put("f", Toiletten.TOILET).put("n", "Mijn naam"), bronNu, 2000);
        check(e2.getJSONObject("wijzig").getJSONObject("osm-nl:n1").getJSONObject("was").getString("n").equals("Markt"), "opnieuw bewerken: 'was' blijft wat de bron eerst zei");
        Toiletten.bewaarPlek(e2, new JSONObject().put("k", "osm-nl:n1").put("s", "toilet").put("f", Toiletten.TOILET).put("n", "Mijn naam").put("houden", true), bronNu, 3000);
        check(e2.getJSONObject("wijzig").getJSONObject("osm-nl:n1").getJSONObject("was").getString("n").equals("Markt (nieuw)"), "mijn versie houden: 'was' wordt wat de bron nu zegt (geen melding meer)");
        String eigenK = Toiletten.bewaarPlek(e, new JSONObject().put("s", "douche").put("f", 0).put("la", 52.1).put("lo", 4.3).put("n", "Strandpaviljoen"), null, 3000);
        JSONObject t = e.getJSONObject("toegevoegd").getJSONObject(eigenK);
        check(eigenK.matches("eigen:[0-9a-f]{12}") && (t.getInt("f") & Toiletten.DOUCHE) != 0 && t.getDouble("la") == 52.1, "toevoegen: eigen sleutel, douche als niets aangevinkt");
        check(err(() -> Toiletten.bewaarPlek(Toiletten.leegEigen(), new JSONObject().put("la", 95).put("lo", 4), null, 1)).contains("plek"), "toevoegen buiten de kaart kan niet");
        check(err(() -> Toiletten.bewaarPlek(Toiletten.leegEigen(), new JSONObject().put("k", "eigen:ffffffffffff").put("la", 52).put("lo", 4), null, 1)).contains("bestaat niet"), "onbekende eigen plek");
        check(err(() -> Toiletten.bewaarPlek(Toiletten.leegEigen(), new JSONObject().put("k", "osm-nl:n9"), null, 1)).contains("niet meer in de bron"), "bewerken van een plek die weg is");
        Toiletten.afwijzen(e, "osm-nl:n2", "dicht", "al maanden", bronW, 4000);
        check(e.getJSONObject("afgewezen").getJSONObject("osm-nl:n2").getString("reden").equals("dicht"), "afwijzen met reden");
        check(err(() -> { Toiletten.afwijzen(Toiletten.leegEigen(), "osm-nl:n2", "saai", "", bronW, 1); return null; }).contains("reden"), "afwijzen zonder geldige reden kan niet");
        check(err(() -> { Toiletten.afwijzen(Toiletten.leegEigen(), eigenK, "weg", "", bronW, 1); return null; }).contains("weghalen"), "eigen plek afwijzen: weghalen");
        check(Toiletten.terug(e, "osm-nl:n2", "afgewezen") && !e.getJSONObject("afgewezen").has("osm-nl:n2"), "afwijzing terugzetten");
        check(Toiletten.terug(e, eigenK, "toegevoegd") && Toiletten.aantal(e) == 0, "eigen plek weghalen");
        check(!Toiletten.terug(e, "x", "alles"), "onbekend terugzetten doet niets");
        check(err(() -> { Toiletten.checkEigen("{\"wijzig\":[]}"); return null; }).contains("Onverwacht"), "eigen laag in een vreemde vorm wordt niet gelezen");

        // Een "meestal"-plek (tankstation) aanpassen: blijft "meestal", tenzij Rene aanvinkt dat er zeker een toilet is
        int M = Toiletten.MEESTAL;
        JSONObject tank = new JSONObject().put("s", "tank").put("f", Toiletten.TOILET | M | Toiletten.KLANTEN).put("n", "Shell").put("u", "").put("la", 52.0).put("lo", 4.0);
        JSONObject e3 = Toiletten.leegEigen();
        Toiletten.bewaarPlek(e3, new JSONObject().put("k", "osm-nl:n3").put("s", "tank").put("f", Toiletten.TOILET | Toiletten.KLANTEN).put("n", "Shell").put("notitie", "naast de shop"), tank, 1);
        check((e3.getJSONObject("wijzig").getJSONObject("osm-nl:n3").getJSONObject("v").getInt("f") & M) != 0, "notitie bij een tankstation: blijft 'meestal een toilet'");
        Toiletten.bewaarPlek(e3, new JSONObject().put("k", "osm-nl:n3").put("s", "tank").put("f", Toiletten.TOILET | Toiletten.KLANTEN).put("n", "Shell"), tank, 2);
        check(!e3.getJSONObject("wijzig").has("osm-nl:n3"), "zonder notitie en verder gelijk: geen aanpassing meer");
        Toiletten.bewaarPlek(e3, new JSONObject().put("k", "osm-nl:n3").put("s", "tank").put("f", Toiletten.TOILET | Toiletten.KLANTEN).put("n", "Shell").put("zeker", true), tank, 3);
        check(e3.getJSONObject("wijzig").has("osm-nl:n3") && (e3.getJSONObject("wijzig").getJSONObject("osm-nl:n3").getJSONObject("v").getInt("f") & M) == 0, "zeker een toilet: niet meer 'meestal', en dat is een aanpassing");
        JSONObject tank2 = new JSONObject(tank.toString()).put("n", "Shell Express");
        Toiletten.bewaarPlek(e3, new JSONObject().put("k", "osm-nl:n3").put("s", "tank").put("f", Toiletten.TOILET | Toiletten.KLANTEN).put("n", "Shell").put("zeker", true).put("houden", true), tank2, 5);
        JSONObject w3 = e3.getJSONObject("wijzig").getJSONObject("osm-nl:n3");
        check((w3.getJSONObject("v").getInt("f") & M) == 0 && w3.getJSONObject("was").getString("n").equals("Shell Express"), "mijn versie houden na 'zeker een toilet': blijft zeker");
        Toiletten.bewaarPlek(e3, new JSONObject().put("k", "osm-nl:n4").put("s", "toilet").put("f", Toiletten.TOILET | M).put("n", "x"), bronW, 4);
        check((e3.getJSONObject("wijzig").getJSONObject("osm-nl:n4").getJSONObject("v").getInt("f") & M) == 0, "'meestal' komt nooit uit wat Rene invult");

        // Bronnen lezen: één bron die niet klopt valt weg, de rest blijft
        JSONArray gemengd = new JSONArray().put(new JSONObject().put("naam", "Goed").put("url", "https://a.nl/x.geojson").put("licentie", "CC0"))
                .put(new JSONObject().put("naam", "Kapot").put("url", "http://b.nl").put("licentie", "CC0"))
                .put(new JSONObject().put("naam", "Ook goed").put("url", "https://c.nl/x.geojson").put("licentie", "CC0"));
        check(Toiletten.leesLijst(gemengd).length() == 2 && Toiletten.leesLijst(null).length() == 0, "lezen: een kapotte bron valt weg, de andere blijven");
        check(err(() -> Toiletten.checkLijst(gemengd)).contains("https://"), "bewaren: een kapotte bron wordt geweigerd");
        check(err(() -> Toiletten.normaal(new JSONObject().put("naam", "x").put("url", "https://a.nl").put("licentie", "CC0").put("info", "ftp://a.nl"))).equals("Het adres met uitleg moet beginnen met https://")
                && err(() -> Toiletten.normaal(new JSONObject().put("naam", "x").put("licentie", "CC0"))).equals("Vul het adres van de gegevens in"), "meldingen als hele zinnen (vertaalbaar)");

        // Een zoekpatroon dat eindeloos kan duren, wordt niet bewaard (Android kan een zoekopdracht niet afbreken)
        for (String p : new String[]{"(a+)+$", "(.*a){20}$", "(a|aa)*$", "(\\w+\\s?)*$", "((ab)*c)+", "a*b*c*d*", "(x+)*", "(a?){25}a{25}",
                "a?a?a?a?a?a?a?a?a?a?a?a?a?a?a?a?a?a?a?a?a?a?a?a?a?!", ".{0,9}.{0,9}.{0,9}.{0,9}.{0,9}.{0,9}.{0,9}.{0,9}!", ".{3,}.{3,}.{3,}.{3,}!"})
            check(Toiletten.riskant(p) && err(() -> Toiletten.normaal(new JSONObject().put("naam", "x").put("soort", "geojson").put("url", "https://a.nl").put("licentie", "CC0")
                    .put("regels", new JSONArray().put(new JSONObject().put("veld", "naam").put("patroon", p).put("zet", "klanten"))))).contains("traag"), "riskant zoekpatroon geweigerd: " + p);
        for (String p : new String[]{"^ja$", "^(heren|dames)$", "gratis|free", "\\d+[.,]\\d+", "(ja)?", "[(+*]+", "\\(a+\\)+", "^(a|b)c{2}$", "(wc|toilet)s?", "(heren|dames)?", "^(\\d{1,2}):(\\d{2})", "(?:wc)+",
                "(?i)colou?r", "^[0-9]{4} ?[a-z]{2}$", "a+?b*?c+?", "x{0,99999999999999999999}"})
            check(!Toiletten.riskant(p), "gewoon zoekpatroon mag: " + p);
        check(ToilettenScan.past("^ja$", "JA") && !ToilettenScan.past("^ja$", "nee"), "gewoon zoekpatroon werkt (hoofdletters maken niet uit)");

        // Planning: eerste keer meteen, daarna het volgende moment; na een fout opnieuw na "opnieuw" uur; wifi apart
        java.util.TimeZone tz = java.util.TimeZone.getTimeZone("Europe/Amsterdam");
        long nu = 1_793_000_000_000L;
        JSONObject dag = Toiletten.normaal(new JSONObject().put("id", "a").put("naam", "a").put("url", "https://a.nl").put("licentie", "CC0").put("schema", "dag").put("tijd", "04:00").put("wifi", false).put("opnieuw", 6));
        JSONObject wifi = new JSONObject(dag.toString()).put("id", "b").put("wifi", true);
        long[] v0 = ToilettenJob.volgende(new JSONArray().put(dag), new JSONObject(), nu, tz);
        check(v0[0] == nu + 60_000L && v0[1] == Long.MAX_VALUE, "nog nooit gescand: over een minuut, met elk netwerk");
        long[] v1 = ToilettenJob.volgende(new JSONArray().put(wifi), new JSONObject(), nu, tz);
        check(v1[0] == Long.MAX_VALUE && v1[1] == nu + 60_000L, "alleen via wifi: in de wifi-planning");
        long[] v2 = ToilettenJob.volgende(new JSONArray().put(dag), new JSONObject().put("a", new JSONObject().put("lastOk", nu).put("lastTry", nu)), nu, tz);
        check(v2[0] == Facturen.gepland(dag, nu, true, tz) && v2[0] > nu, "net gelukt: het volgende moment (04:00 in de app-zone)");
        long[] v3 = ToilettenJob.volgende(new JSONArray().put(dag), new JSONObject().put("a", new JSONObject().put("lastOk", nu - 3 * 86_400_000L).put("lastTry", nu - 3_600_000L)), nu, tz);
        check(v3[0] == Math.min(Facturen.gepland(dag, nu, true, tz), nu + 5 * 3_600_000L), "mislukt een uur geleden: opnieuw na 6 uur (of eerder als het moment eerder is)");
        long[] v4 = ToilettenJob.volgende(new JSONArray().put(new JSONObject(dag.toString()).put("aan", false)).put(new JSONObject(dag.toString()).put("id", "c").put("schema", "uit")), new JSONObject(), nu, tz);
        check(v4[0] == Long.MAX_VALUE && v4[1] == Long.MAX_VALUE, "uit of zonder planning: niets gepland");

        check(new File("app/src/nl/rene/tools/ToilettenScan.java").exists(), "klaar");
        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
    }

    interface Doe { Object doe() throws Exception; }
    static String err(Doe d) { try { d.doe(); return ""; } catch (Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); } }
    static String lang(int n) { StringBuilder b = new StringBuilder(); for (int i = 0; i < n; i++) b.append('x'); return b.toString(); }
    static JSONObject dh2() throws Exception { return Toiletten.normaal(new JSONObject().put("naam", "x").put("soort", "arcgis").put("url", "https://x.nl/MapServer/0").put("licentie", "CC0")); }
}
