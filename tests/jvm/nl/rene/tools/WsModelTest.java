package nl.rene.tools;

/** Launcher-werkblad: plaatsen, verplaatsen, mappen, dock, widgets, raster wijzigen. */
public class WsModelTest {
    static int failed = 0;
    static void check(String what, boolean ok) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) failed++; }

    static WsModel.Item app(WsModel m, String k) { WsModel.Item i = m.newItem(WsModel.APP); i.key = k; return i; }

    public static void main(String[] a) {
        WsModel m = new WsModel(); m.cols = 4; m.rows = 5;
        for (int i = 0; i < 20; i++) m.place(app(m, "app" + i), 0);
        check("20 apps vullen pagina 1 precies", m.pages.size() == 1 && m.pages.get(0).items.size() == 20);
        WsModel.Item extra = app(m, "extra");
        check("21e app gaat naar een nieuwe pagina", m.place(extra, 0) == 1 && m.pages.size() == 2);
        WsModel.Item w = m.newItem(WsModel.WIDGET); w.widgetId = 7; w.w = 4; w.h = 2;
        check("widget 4×2 past op pagina 2 onder de app", m.place(w, 1) == 1 && w.y == 1 && w.x == 0);
        check("bezette plek is niet vrij", !m.isFree(m.pages.get(1), 0, 1, 1, 1, null));
        WsModel.Item a0 = m.pages.get(0).items.get(0);
        check("verplaatsen naar bezette plek kiest de dichtstbijzijnde vrije", m.moveTo(a0, 1, 0, 1) && m.locate(a0)[0] == 1 && !(a0.x == 0 && a0.y == 1));
        WsModel.Item a1 = m.pages.get(0).items.get(0), a2 = m.pages.get(0).items.get(1);
        WsModel.Item f = m.dropOnto(a1, a2, "Reizen");
        check("app op app wordt een map op de plek van het doel", f != null && f.isFolder() && f.apps.size() == 2 && m.locate(f)[0] == 0 && m.locate(a1) == null);
        WsModel.Item a3 = m.pages.get(0).items.get(2);
        check("app op map gaat erin", m.dropOnto(a3, f, "x") == f && f.apps.size() == 3);
        f.apps.remove(0); f.apps.remove(0); m.tidyFolder(f);
        check("map met één app wordt weer een app", m.locate(f) == null && m.at(m.pages.get(0), f.x, f.y).isApp());
        for (int i = 0; i < 6; i++) m.moveToDock(m.pages.get(0).items.get(0), i);
        check("dock maximaal 6", m.dock.size() == 6 && !m.moveToDock(m.pages.get(0).items.get(0), 0));
        check("widget mag niet in het dock", !m.moveToDock(w, 0));
        WsModel.Item d0 = m.dock.get(0), d1 = m.dock.get(1), d2 = m.dock.get(2);
        m.moveToDock(d0, 2); // tussen de 2e en 3e laten vallen
        check("dock: naar rechts slepen landt tussen de buren", m.dock.get(0) == d1 && m.dock.get(1) == d0 && m.dock.get(2) == d2);
        check("widget groter maken kan alleen als het vrij is", m.resize(w, 4, 3) && !m.resize(w, 4, 6));
        m.pages.add(new WsModel.Page()); m.pages.add(new WsModel.Page());
        int home = m.dropEmptyPages(3);
        check("lege pagina's weg, de bewaarde blijft (index klopt)", m.pages.size() == 3 && home == 2);
        WsModel.Item big = m.newItem(WsModel.WIDGET); big.widgetId = 9; big.w = 5; big.h = 1; big.x = 0; big.y = 4;
        m.cols = 5; m.pages.get(2).items.add(big); m.cols = 4;
        m.setGrid(5, 5); m.setGrid(4, 5);
        check("raster kleiner: niets overlapt of valt erbuiten", noOverlap(m));
        check("uid uniek en terug te vinden", m.byUid(w.uid) == w);
        System.out.println(failed == 0 ? "Alle werkblad-tests geslaagd" : failed + " test(s) mislukt");
        System.exit(failed == 0 ? 0 : 1);
    }

    static boolean noOverlap(WsModel m) {
        for (WsModel.Page p : m.pages) for (WsModel.Item i : p.items) {
            if (!m.fits(i.x, i.y, i.w, i.h)) return false;
            for (WsModel.Item j : p.items) if (i != j && i.x < j.x + j.w && j.x < i.x + i.w && i.y < j.y + j.h && j.y < i.y + i.h) return false;
        }
        return true;
    }
}
