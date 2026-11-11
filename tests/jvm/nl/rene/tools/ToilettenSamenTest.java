package nl.rene.tools;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** Toiletten en douches: bronnen samenvoegen (25 m, zelfde groep), de eigen laag eroverheen en dichtbij zoeken via het raster. */
public class ToilettenSamenTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }
    static final double M = 1 / 111_320.0; // ongeveer één meter in breedtegraad

    static ToilettenSamen.Plek p(String e, double la, double lo, String s, int f, String n) { return new ToilettenSamen.Plek(e, la, lo, s, f, n, ""); }

    public static void main(String[] a) throws Exception {
        int T = Toiletten.TOILET, D = Toiletten.DOUCHE, MS = Toiletten.MEESTAL, G = Toiletten.GRATIS, B = Toiletten.BETAALD, R = Toiletten.ROLSTOEL;
        double la = 51.9225, lo = 4.4792;
        List<ToilettenSamen.Plek> osm = Arrays.asList(
                p("n1", la, lo, "toilet", T | B, ""),
                p("n2", la + 300 * M, lo, "tank", T | MS | Toiletten.KLANTEN, "Shell"),
                p("n3", la + 600 * M, lo, "toilet", T, "Park"),
                p("n4", la + 900 * M, lo, "douche", D, ""),
                p("n5", la + 1200 * M, lo, "toilet", T | G | R, "Station"));
        List<ToilettenSamen.Plek> gem = Arrays.asList(
                p("g1", la + 10 * M, lo, "toilet", T | G | R | Toiletten.VERSCHOON, "Toilet Markt"),
                p("g2", la + 640 * M, lo, "toilet", T, "Ander toilet"),
                p("g3", la + 905 * M, lo, "toilet", T, "Toilet bij de douche"));
        List<ToilettenSamen.Bron> bronnen = Arrays.asList(new ToilettenSamen.Bron("osm-nl", "OSM", osm), new ToilettenSamen.Bron("dh", "Den Haag", gem));

        // ---------- samenvoegen ----------
        ToilettenSamen.Index ix = ToilettenSamen.bouw(bronnen, null);
        ToilettenSamen.Punt m = ix.opSleutel.get("osm-nl:n1");
        check(ix.punten.size() == 7, "dezelfde plek uit twee bronnen telt één keer (" + ix.punten.size() + ")");
        check(m == ix.opSleutel.get("dh:g1") && m.bronnen.equals(Arrays.asList("osm-nl", "dh")), "binnen 25 m en zelfde groep: samen, beide bronnen erbij");
        check((m.f & B) != 0 && (m.f & G) == 0 && (m.f & R) != 0 && (m.f & Toiletten.VERSCHOON) != 0, "eerste bron gaat voor (betaald), de tweede vult aan (rolstoel, verschoontafel)");
        check(m.n.equals("Toilet Markt"), "naam uit de tweede bron als de eerste geen naam heeft");
        check(ix.opSleutel.get("dh:g2") != ix.opSleutel.get("osm-nl:n3"), "40 m uit elkaar: twee plekken");
        check(ix.opSleutel.get("dh:g3") != ix.opSleutel.get("osm-nl:n4"), "toilet naast een douche: andere groep, niet samen");
        check(ToilettenSamen.samenBits(T | MS, T) == T && ToilettenSamen.samenBits(T | Toiletten.ROLSTOEL_BEPERKT, R) == (T | R), "bevestigd wint van meestal; rolstoel wint van beperkt");
        List<ToilettenSamen.Plek> zelf = Arrays.asList(p("x1", la, lo, "toilet", T, "A"), p("x2", la + 5 * M, lo, "toilet", T, "B"));
        check(ToilettenSamen.bouw(Arrays.asList(new ToilettenSamen.Bron("x", "X", zelf)), null).punten.size() == 2, "binnen één bron niet samenvoegen");

        // ---------- zoeken ----------
        JSONObject geen = new JSONObject();
        List<ToilettenSamen.Treffer> z = ToilettenSamen.zoek(ix, la, lo, geen, 25, 50_000);
        check(z.size() == 6 && z.get(0).p == m && z.get(0).d < 1, "dichtstbijzijnde eerst; 'meestal' standaard niet");
        boolean opVolgorde = true;
        for (int i = 1; i < z.size(); i++) opVolgorde &= z.get(i - 1).d <= z.get(i).d;
        check(opVolgorde, "op afstand gesorteerd");
        check(ToilettenSamen.zoek(ix, la, lo, new JSONObject().put("meestal", true), 25, 50_000).size() == 7, "met 'ook tankstations e.d.' erbij");
        check(ToilettenSamen.zoek(ix, la, lo, new JSONObject().put("douche", true), 25, 50_000).size() == 1, "filter douche");
        check(ToilettenSamen.zoek(ix, la, lo, new JSONObject().put("gratis", true).put("rolstoel", true), 25, 50_000).size() == 1, "filter gratis en rolstoel");
        check(ToilettenSamen.zoek(ix, la, lo, geen, 2, 50_000).size() == 2, "hooguit max");
        check(ToilettenSamen.zoek(ix, la, lo, geen, 25, 650).size() == 3, "binnen de straal");
        check(ToilettenSamen.zoek(ix, -33.86, 151.2, geen, 25, 50_000).isEmpty(), "ver weg: niets");

        Random r = new Random(7);
        List<ToilettenSamen.Plek> veel = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) veel.add(p("v" + i, 50.8 + r.nextDouble() * 2.6, 3.4 + r.nextDouble() * 3.8, i % 5 == 0 ? "douche" : "toilet", i % 5 == 0 ? D : T, ""));
        long t0 = System.nanoTime();
        ToilettenSamen.Index groot = ToilettenSamen.bouw(Arrays.asList(new ToilettenSamen.Bron("v", "V", veel)), null);
        long bouwMs = (System.nanoTime() - t0) / 1_000_000;
        boolean klopt = true;
        long zoekNs = 0;
        for (int q = 0; q < 50; q++) {
            double qa = 50.8 + r.nextDouble() * 2.6, qo = 3.4 + r.nextDouble() * 3.8;
            long s = System.nanoTime();
            List<ToilettenSamen.Treffer> res = ToilettenSamen.zoek(groot, qa, qo, geen, 25, 50_000);
            zoekNs += System.nanoTime() - s;
            double[] alle = new double[veel.size()];
            for (int i = 0; i < veel.size(); i++) alle[i] = ToilettenSamen.meters(qa, qo, veel.get(i).la, veel.get(i).lo);
            Arrays.sort(alle);
            for (int i = 0; i < res.size(); i++) klopt &= Math.abs(res.get(i).d - alle[i]) < 1e-6;
            klopt &= res.size() == 25;
        }
        check(klopt, "raster geeft precies de 25 dichtstbijzijnde (vergeleken met alles nalopen)");
        check(bouwMs < 5000 && zoekNs / 50 / 1_000_000 < 50, "snel genoeg: bouwen " + bouwMs + " ms, zoeken " + zoekNs / 50 / 1000 + " µs");
        check(ToilettenSamen.cel(-0.001, -0.001) != ToilettenSamen.cel(0.001, 0.001) && ToilettenSamen.cel(52.0, 4.0) == ToilettenSamen.cel(52.005, 4.005), "rastercellen (ook rond 0)");

        // ---------- eigen laag ----------
        JSONObject e = Toiletten.leegEigen();
        e.getJSONObject("afgewezen").put("osm-nl:n3", new JSONObject().put("la", la + 600 * M).put("lo", lo).put("s", "toilet").put("reden", "weg"));
        e.getJSONObject("afgewezen").put("osm-nl:n77", new JSONObject().put("la", la + 1205 * M).put("lo", lo).put("s", "toilet").put("reden", "dicht"));
        e.getJSONObject("wijzig").put("osm-nl:n1", new JSONObject().put("la", la).put("lo", lo).put("s", "toilet")
                .put("v", new JSONObject().put("s", "toilet").put("f", T | G).put("n", "Markt (gratis na 18:00)").put("u", "").put("notitie", "code 1234"))
                .put("was", new JSONObject().put("s", "toilet").put("f", T | B | R | Toiletten.VERSCHOON).put("n", "Toilet Markt").put("u", "")));
        e.getJSONObject("wijzig").put("dh:g9", new JSONObject().put("la", 52.5).put("lo", 4.9).put("s", "toilet")
                .put("v", new JSONObject().put("s", "toilet").put("f", T).put("n", "Weg").put("u", ""))
                .put("was", new JSONObject().put("s", "toilet").put("f", T).put("n", "Weg").put("u", "")));
        e.getJSONObject("toegevoegd").put("eigen:aaaaaaaaaaaa", new JSONObject().put("la", la + 905 * M).put("lo", lo).put("s", "douche").put("f", D).put("n", "Strand"));
        e.getJSONObject("toegevoegd").put("eigen:bbbbbbbbbbbb", new JSONObject().put("la", la + 2000 * M).put("lo", lo).put("s", "toilet").put("f", T).put("n", "Kerk"));
        ToilettenSamen.Index ie = ToilettenSamen.bouw(bronnen, e);
        check(ie.opSleutel.get("osm-nl:n3").afgewezen && ie.opSleutel.get("dh:g2").afgewezen == false, "afgewezen plek is weg, de buurman niet");
        check(ie.opSleutel.get("osm-nl:n5").afgewezen, "afwijzing blijft na een nieuwe id in de bron (zelfde groep binnen 25 m)");
        check(ie.afgewezen.size() == 2 && ie.afgewezen.get(0).getBoolean("gevonden"), "lijst met afgewezen plekken");
        ToilettenSamen.Punt aang = ie.opSleutel.get("osm-nl:n1");
        check(aang.eigen.equals("aangepast") && aang.n.equals("Markt (gratis na 18:00)") && aang.f == (T | G) && aang.notitie.equals("code 1234"), "bewerking eroverheen");
        check(aang.bronNu == null && aang.uitBron.getString("n").equals("Toilet Markt"), "bron zegt nog hetzelfde: geen melding");
        List<ToilettenSamen.Plek> osm2 = new ArrayList<>(osm);
        osm2.set(0, p("n1", la, lo, "toilet", T | G, "Openbaar toilet Markt"));
        ToilettenSamen.Index ie2 = ToilettenSamen.bouw(Arrays.asList(new ToilettenSamen.Bron("osm-nl", "OSM", osm2), new ToilettenSamen.Bron("dh", "Den Haag", gem)), e);
        check(ie2.opSleutel.get("osm-nl:n1").bronNu != null && ie2.opSleutel.get("osm-nl:n1").bronNu.getString("n").equals("Openbaar toilet Markt"), "bron zegt nu iets anders: 'bron zegt nu' met de nieuwe waarden");
        ToilettenSamen.Punt weg = ie.opSleutel.get("dh:g9");
        check(weg != null && weg.weg && weg.eigen.equals("aangepast"), "aangepaste plek die uit de bron verdween blijft zichtbaar (staat niet meer in de bron)");
        ToilettenSamen.Punt strand = ie.opSleutel.get("eigen:aaaaaaaaaaaa"), kerk = ie.opSleutel.get("eigen:bbbbbbbbbbbb");
        check(strand.eigen.equals("toegevoegd") && strand.inBron.equals("osm-nl:n4"), "eigen toevoeging die nu ook in een bron staat: dat ziet de app");
        check(kerk.inBron.isEmpty(), "eigen toevoeging die alleen bij jou staat");
        List<ToilettenSamen.Treffer> ze = ToilettenSamen.zoek(ie, la, lo, geen, 25, 50_000);
        boolean geenAfgewezen = true;
        for (ToilettenSamen.Treffer t : ze) geenAfgewezen &= !t.p.afgewezen;
        check(geenAfgewezen && ze.size() == 6 && ze.contains(null) == false, "zoeken: zonder afgewezen plekken, met eigen toevoegingen (" + ze.size() + ")");
        boolean eigenErin = false;
        for (ToilettenSamen.Treffer t : ze) eigenErin |= t.p == kerk;
        check(eigenErin, "eigen toevoeging komt in de lijst");
        JSONObject kort = ToilettenSamen.kort(aang, 12.4);
        check(kort.getString("e").equals("aangepast") && kort.getLong("d") == 12 && kort.getJSONArray("b").length() == 2, "korte vorm voor de lijst");

        File tmp = File.createTempFile("wc-index", ".json");
        try {
            ToilettenSamen.schrijf(tmp, "s1", ie);
            ToilettenSamen.Index terug = ToilettenSamen.leesAls(tmp, "s1");
            check(terug != null && ToilettenSamen.leesAls(tmp, "ander") == null, "index van schijf alleen bij dezelfde stempel");
            check(terug != null && terug.opSleutel.keySet().equals(ie.opSleutel.keySet()), "teruggelezen index: dezelfde sleutels");
            ToilettenSamen.Punt na = terug == null ? null : terug.opSleutel.get("osm-nl:n1");
            check(na != null && na.notitie.equals("code 1234") && na.n.equals("Markt (gratis na 18:00)"), "bewerking blijft in de bewaarde index");
            check(terug != null && terug.afgewezen.size() == ie.afgewezen.size(), "afgewezen plekken blijven in de bewaarde index");
            List<ToilettenSamen.Treffer> zt = ToilettenSamen.zoek(terug, la, lo, geen, 25, 50_000);
            java.util.Set<String> ka = new java.util.HashSet<>(), kb = new java.util.HashSet<>();
            for (ToilettenSamen.Treffer t : ze) ka.add(t.p.keys.isEmpty() ? t.p.k : t.p.keys.get(0));
            for (ToilettenSamen.Treffer t : zt) kb.add(t.p.keys.isEmpty() ? t.p.k : t.p.keys.get(0));
            check(zt.size() == ze.size() && ka.equals(kb), "teruggelezen index zoekt dezelfde plekken (" + zt.size() + ")");
            Contacts.writeText(tmp, "geen json", false);
            check(ToilettenSamen.leesAls(tmp, "s1") == null, "kapotte index wordt genegeerd");
        } finally { tmp.delete(); }

        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
    }
}
