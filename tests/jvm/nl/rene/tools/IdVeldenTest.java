package nl.rene.tools;

import java.util.ArrayList;
import java.util.List;

/** ID-kopie afschermen: velden herkennen uit gelezen woorden en gezichten (IdVelden). Alle gegevens hier zijn verzonnen. */
public class IdVeldenTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }
    static IdVelden.Woord w(String t, double x, double y, double b, int regel) { return new IdVelden.Woord(t, x, y, x + b, y + 0.04, regel); }
    static IdVelden.Veld een(List<IdVelden.Veld> l, String veld) { for (IdVelden.Veld v : l) if (v.veld.equals(veld)) return v; return null; }
    static int tel(List<IdVelden.Veld> l, String veld) { int n = 0; for (IdVelden.Veld v : l) if (v.veld.equals(veld)) n++; return n; }

    public static void main(String[] a) {
        check(IdVelden.elfproef("111222333") && !IdVelden.elfproef("111222334") && !IdVelden.elfproef("12345678") && !IdVelden.elfproef("000000000") && !IdVelden.elfproef("12345678A"), "elfproef");
        check(IdVelden.strookRegel("P<NLDDE<BRUIJN<<WILLEKE<LISELOTTE<<<<<<<<<<<") && IdVelden.strookRegel("SPECI20142NLD6503101F2403096999999990<<<<<84")
            && IdVelden.strookRegel("I«NLDSPECI20142«««««««««««««««") && !IdVelden.strookRegel("Koninkrijk der Nederlanden") && !IdVelden.strookRegel("DE BRUIJN WILLEKE LISELOTTE 10 MAA 1965"), "regels van de strook");
        check(IdVelden.nummer("SPECI2014") && IdVelden.nummer("1234567890") && !IdVelden.nummer("NEDERLAND") && !IdVelden.nummer("ROTTERDAM") && !IdVelden.nummer("12345678"), "documentnummer");

        // Fotopagina van een paspoort: opschriften, nummer rechtsboven, persoonsnummer, twee regels strook, een gezicht links
        List<IdVelden.Woord> p = new ArrayList<>();
        p.add(w("PASPOORT", 0.05, 0.03, 0.2, 1)); p.add(w("PASSPORT", 0.27, 0.03, 0.2, 1)); p.add(w("SPECI2014", 0.75, 0.03, 0.2, 1));
        p.add(w("De", 0.36, 0.2, 0.04, 2)); p.add(w("Bruijn", 0.41, 0.2, 0.12, 2));
        p.add(w("Persoonsnummer", 0.36, 0.55, 0.25, 3)); p.add(w("111222333", 0.36, 0.6, 0.18, 4));
        p.add(w("P<NLDDE<BRUIJN<<WILLEKE<LISELOTTE<<<<<<<<<<<", 0.03, 0.82, 0.94, 5));
        p.add(w("SPECI20142NLD6503101F2403096999999990<<<<<84", 0.03, 0.9, 0.94, 6));
        List<IdVelden.Veld> v = IdVelden.uit(p, new double[][]{{0.17, 0.4, 0.08}}, 1.42);
        IdVelden.Veld s = een(v, IdVelden.STROOK), b = een(v, IdVelden.BSN), n = een(v, IdVelden.NUMMER), f = een(v, IdVelden.FOTO);
        check(s != null && s.x == 0 && s.w == 1 && s.y < 0.82 && s.y > 0.7 && s.y + s.h == 1, "strook: beide regels, hele breedte, tot de onderrand");
        check(b != null && tel(v, IdVelden.BSN) == 1 && b.x < 0.36 && b.x + b.w > 0.54 && b.y < 0.6 && b.y + b.h > 0.64, "persoonsnummer ruim afgedekt, één keer (niet ook via het opschrift)");
        check(n != null && tel(v, IdVelden.NUMMER) == 1 && n.x < 0.75 && n.y + n.h < 0.2, "documentnummer rechtsboven; het nummer in de strook telt niet apart");
        check(f != null && f.x < 0.09 && f.x + f.w > 0.25 && f.y < 0.25 && f.y + f.h > 0.6 && f.y + f.h <= s.y + 1e-9, "pasfoto ruim om het gezicht, niet over de strook");
        check("paspoort".equals(IdVelden.soort(p)), "soort: paspoort");

        // Achterkant van een identiteitskaart: burgerservicenummer met een leesfout achter het opschrift, in stukjes, en drie regels strook
        List<IdVelden.Woord> k = new ArrayList<>();
        k.add(w("Burgerservicenummer", 0.05, 0.05, 0.3, 1)); k.add(w("11122233B", 0.4, 0.05, 0.18, 1));
        k.add(w("1112", 0.05, 0.2, 0.08, 2)); k.add(w("22", 0.14, 0.2, 0.04, 2)); k.add(w("333", 0.19, 0.2, 0.06, 2));
        k.add(w("I<NLDSPECI20142<<<<<<<<<<<<<<<", 0.03, 0.62, 0.94, 3)); k.add(w("6503101F2403096NLD<<<<<<<<<<<8", 0.03, 0.72, 0.94, 4)); k.add(w("DE<BRUIJN<<WILLEKE<LISELOTTE<<", 0.03, 0.82, 0.94, 5));
        v = IdVelden.uit(k, null, 1.58);
        check(tel(v, IdVelden.BSN) == 2 && een(v, IdVelden.STROOK) != null && een(v, IdVelden.STROOK).y < 0.62 && een(v, IdVelden.FOTO) == null, "achterkant: nummer achter het opschrift (ook met leesfout), nummer in stukjes, strook van drie regels");
        check("idkaart".equals(IdVelden.soort(k)), "soort: identiteitskaart (uit de strook)");

        // Geen velden: gewone tekst, namen in hoofdletters en een datum zijn geen nummer
        List<IdVelden.Woord> g = new ArrayList<>();
        g.add(w("KONINKRIJK", 0.1, 0.1, 0.3, 1)); g.add(w("NEDERLAND", 0.5, 0.1, 0.3, 1)); g.add(w("10", 0.1, 0.3, 0.04, 2)); g.add(w("03", 0.15, 0.3, 0.04, 2)); g.add(w("1965", 0.2, 0.3, 0.08, 2)); g.add(w("123456789", 0.5, 0.3, 0.2, 2));
        check(IdVelden.uit(g, new double[0][], 1.5).isEmpty() && IdVelden.soort(g).isEmpty(), "gewone tekst, een datum en negen cijfers zonder elfproef: geen velden");
        check("rijbewijs".equals(IdVelden.soort(java.util.Arrays.asList(w("RIJBEWIJS", 0, 0, 0.2, 1)))), "soort: rijbewijs");
        // Grenzen: alles blijft binnen de pagina
        v = IdVelden.uit(java.util.Arrays.asList(w("111222333", 0.9, 0.97, 0.1, 1)), new double[][]{{0.02, 0.02, 0.1}}, 1.5);
        boolean binnen = true; for (IdVelden.Veld x : v) if (x.x < 0 || x.y < 0 || x.x + x.w > 1 + 1e-9 || x.y + x.h > 1 + 1e-9) binnen = false;
        check(v.size() == 2 && binnen, "vlakken blijven binnen de pagina");
        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
    }
}
