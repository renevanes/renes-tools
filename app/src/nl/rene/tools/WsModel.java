package nl.rene.tools;

import java.util.ArrayList;
import java.util.List;

/**
 * Indeling van het launcher-werkblad: pagina's met een raster (cols × rows) waarop apps, mappen en widgets staan,
 * plus het dock. Puur Java (zonder Android), zodat de plaatsingsregels op een gewone JVM getest kunnen worden.
 */
final class WsModel {

    static final String APP = "app", FOLDER = "folder", WIDGET = "widget";
    static final int MAX_PAGES = 12, DOCK_MAX = 6;

    static final class Item {
        String type = APP;
        String key = "";            // app: pakket/activiteit
        String name = "";           // map: naam
        List<String> apps = new ArrayList<>(); // map: inhoud
        int widgetId = -1;          // widget: id bij AppWidgetHost
        String provider = "";       // widget: component
        int x, y, w = 1, h = 1;
        long uid;                   // vast nummer voor de interface (menu's, slepen)

        Item copy() {
            Item i = new Item();
            i.type = type; i.key = key; i.name = name; i.apps = new ArrayList<>(apps); i.widgetId = widgetId; i.provider = provider;
            i.x = x; i.y = y; i.w = w; i.h = h; i.uid = uid;
            return i;
        }
        boolean isApp() { return APP.equals(type); }
        boolean isFolder() { return FOLDER.equals(type); }
        boolean isWidget() { return WIDGET.equals(type); }
    }

    static final class Page { final List<Item> items = new ArrayList<>(); }

    int cols = 4, rows = 5;
    final List<Page> pages = new ArrayList<>();
    final List<Item> dock = new ArrayList<>(); // apps of mappen; x = volgorde
    long nextUid = 1;
    /** Items die bij een ander raster nergens meer pasten (widgets moeten dan ook bij de widget-host weg). */
    final List<Item> lost = new ArrayList<>();

    WsModel() { pages.add(new Page()); }

    Item newItem(String type) { Item i = new Item(); i.type = type; i.uid = nextUid++; return i; }

    // ---------- raster ----------

    boolean fits(int x, int y, int w, int h) { return x >= 0 && y >= 0 && w >= 1 && h >= 1 && x + w <= cols && y + h <= rows; }

    /** Vrij op deze plek (eventueel zonder één item mee te tellen, bijv. het item dat versleept wordt)? */
    boolean isFree(Page p, int x, int y, int w, int h, Item ignore) {
        if (!fits(x, y, w, h)) return false;
        for (Item i : p.items) {
            if (i == ignore) continue;
            if (x < i.x + i.w && i.x < x + w && y < i.y + i.h && i.y < y + h) return false;
        }
        return true;
    }

    /** Item op een cel (of null). */
    Item at(Page p, int x, int y) {
        for (Item i : p.items) if (x >= i.x && x < i.x + i.w && y >= i.y && y < i.y + i.h) return i;
        return null;
    }

    /** Eerste vrije plek (van links naar rechts, van boven naar beneden), of null. */
    int[] findFree(Page p, int w, int h, Item ignore) {
        for (int y = 0; y + h <= rows; y++) for (int x = 0; x + w <= cols; x++) if (isFree(p, x, y, w, h, ignore)) return new int[]{x, y};
        return null;
    }

    /** Vrije plek zo dicht mogelijk bij (cx, cy), of null. */
    int[] findNearest(Page p, int cx, int cy, int w, int h, Item ignore) {
        int[] best = null;
        double bd = Double.MAX_VALUE;
        for (int y = 0; y + h <= rows; y++) for (int x = 0; x + w <= cols; x++) {
            if (!isFree(p, x, y, w, h, ignore)) continue;
            double d = Math.hypot(x - cx, y - cy);
            if (d < bd) { bd = d; best = new int[]{x, y}; }
        }
        return best;
    }

    /** Ergens plaatsen: eerst op de gewenste pagina, dan de volgende; anders een nieuwe pagina. Geeft de pagina-index of -1. */
    int place(Item it, int preferPage) {
        it.w = Math.max(1, Math.min(it.w, cols));
        it.h = Math.max(1, Math.min(it.h, rows));
        int start = Math.max(0, Math.min(preferPage, pages.size() - 1));
        for (int k = 0; k < pages.size(); k++) {
            int pi = (start + k) % pages.size();
            int[] f = findFree(pages.get(pi), it.w, it.h, null);
            if (f != null) { it.x = f[0]; it.y = f[1]; pages.get(pi).items.add(it); return pi; }
        }
        if (pages.size() >= MAX_PAGES) return -1;
        Page np = new Page();
        pages.add(np);
        it.x = 0; it.y = 0;
        np.items.add(it);
        return pages.size() - 1;
    }

    /** Waar staat dit item? {pagina, -1} of {-1, dock-index}; null als nergens. */
    int[] locate(Item it) {
        for (int i = 0; i < pages.size(); i++) if (pages.get(i).items.contains(it)) return new int[]{i, -1};
        int d = dock.indexOf(it);
        return d >= 0 ? new int[]{-1, d} : null;
    }

    Item byUid(long uid) {
        for (Page p : pages) for (Item i : p.items) if (i.uid == uid) return i;
        for (Item i : dock) if (i.uid == uid) return i;
        return null;
    }

    void remove(Item it) {
        for (Page p : pages) p.items.remove(it);
        dock.remove(it);
    }

    /**
     * Verplaats naar (page, x, y). Bezet? Dan de dichtstbijzijnde vrije plek op die pagina; lukt dat niet, dan blijft
     * het item waar het was. Geeft true als het verplaatst is.
     */
    boolean moveTo(Item it, int page, int x, int y) {
        if (page < 0 || page >= pages.size()) return false;
        Page p = pages.get(page);
        x = Math.max(0, Math.min(x, cols - it.w));
        y = Math.max(0, Math.min(y, rows - it.h));
        int[] f = isFree(p, x, y, it.w, it.h, it) ? new int[]{x, y} : findNearest(p, x, y, it.w, it.h, it);
        if (f == null) return false;
        remove(it);
        it.x = f[0]; it.y = f[1];
        p.items.add(it);
        return true;
    }

    /** Naar het dock op positie idx (apps en mappen; max. DOCK_MAX). */
    boolean moveToDock(Item it, int idx) {
        if (it.isWidget()) return false;
        int old = dock.indexOf(it);
        if (old < 0 && dock.size() >= DOCK_MAX) return false;
        if (old >= 0 && old < idx) idx--; // na het weghalen schuift alles rechts ervan één plek op
        remove(it);
        it.w = 1; it.h = 1;
        dock.add(Math.max(0, Math.min(idx, dock.size())), it);
        return true;
    }

    /** App op een app of map laten vallen: samen in een map. Geeft de map, of null als het niet kan. */
    Item dropOnto(Item dragged, Item target, String newName) {
        if (dragged == target || !dragged.isApp()) return null;
        if (target.isFolder()) {
            if (!target.apps.contains(dragged.key)) target.apps.add(dragged.key);
            remove(dragged);
            return target;
        }
        if (!target.isApp()) return null;
        Item f = newItem(FOLDER);
        f.name = newName == null || newName.isEmpty() ? "Map" : newName;
        f.apps.add(target.key);
        if (!target.key.equals(dragged.key)) f.apps.add(dragged.key);
        f.x = target.x; f.y = target.y;
        int[] loc = locate(target);
        remove(dragged);
        if (loc != null && loc[0] >= 0) { Page p = pages.get(loc[0]); p.items.set(p.items.indexOf(target), f); }
        else if (loc != null) dock.set(dock.indexOf(target), f);
        else return null;
        return f;
    }

    /** Een map met nog één app wordt weer gewoon die app; een lege map verdwijnt. */
    void tidyFolder(Item f) {
        if (!f.isFolder() || f.apps.size() > 1) return;
        int[] loc = locate(f);
        if (loc == null) return;
        if (f.apps.isEmpty()) { remove(f); return; }
        Item a = newItem(APP);
        a.key = f.apps.get(0); a.x = f.x; a.y = f.y;
        if (loc[0] >= 0) { Page p = pages.get(loc[0]); p.items.set(p.items.indexOf(f), a); }
        else dock.set(dock.indexOf(f), a);
    }

    /** Formaat van een widget: alleen als de nieuwe ruimte vrij is. */
    boolean resize(Item it, int w, int h) {
        int[] loc = locate(it);
        if (loc == null || loc[0] < 0) return false;
        if (!isFree(pages.get(loc[0]), it.x, it.y, w, h, it)) return false;
        it.w = w; it.h = h;
        return true;
    }

    /** Lege pagina's opruimen (er blijft er altijd minstens één). Geeft de index die de gegeven pagina nu heeft. */
    int dropEmptyPages(int keep) {
        int newKeep = keep;
        for (int i = pages.size() - 1; i >= 0 && pages.size() > 1; i--) {
            if (!pages.get(i).items.isEmpty() || i == keep) continue;
            pages.remove(i);
            if (i < newKeep) newKeep--;
        }
        return Math.max(0, Math.min(newKeep, pages.size() - 1));
    }

    /** Ander raster (bijv. 5 i.p.v. 4 kolommen): wat niet meer past, krijgt een nieuwe plek. */
    void setGrid(int c, int r) {
        c = Math.max(3, Math.min(6, c)); r = Math.max(4, Math.min(8, r));
        if (c == cols && r == rows) return;
        cols = c; rows = r;
        List<Item> homeless = new ArrayList<>();
        for (Page p : pages) {
            List<Item> keep = new ArrayList<>();
            for (Item i : p.items) {
                i.w = Math.min(i.w, cols); i.h = Math.min(i.h, rows);
                boolean ok = fits(i.x, i.y, i.w, i.h);
                if (ok) for (Item k : keep) if (i.x < k.x + k.w && k.x < i.x + i.w && i.y < k.y + k.h && k.y < i.y + i.h) { ok = false; break; }
                if (ok) keep.add(i); else homeless.add(i);
            }
            p.items.clear();
            p.items.addAll(keep);
        }
        // Wat nergens meer past (alle pagina's vol) valt weg; apps blijven gewoon in alle apps staan.
        for (Item i : homeless) if (place(i, 0) < 0) lost.add(i);
    }

    /** Alle app-sleutels die ergens op het werkblad of in het dock staan. */
    List<String> appKeys() {
        List<String> out = new ArrayList<>();
        for (Page p : pages) for (Item i : p.items) if (i.isApp()) out.add(i.key);
        for (Item i : dock) if (i.isApp()) out.add(i.key);
        return out;
    }

    /** Alle widget-ids (om bij het opstarten te controleren of ze nog bestaan). */
    List<Item> widgets() {
        List<Item> out = new ArrayList<>();
        for (Page p : pages) for (Item i : p.items) if (i.isWidget()) out.add(i);
        return out;
    }
}
