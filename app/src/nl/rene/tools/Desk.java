package nl.rene.tools;

import android.app.WallpaperManager;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.OverScroller;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Het echte launcher-werkblad (native): pagina's om opzij te vegen, een raster met apps, mappen en widgets,
 * het dock, slepen en neerzetten, mappen openen en widgets plaatsen. HomeActivity bouwt dit op; de pagina
 * "Vandaag" (links) en alle apps / look / menu's blijven de webpagina (start.html).
 */
final class Desk {

    static final int HOST_ID = 4711;
    static final int REQ_BIND = 61, REQ_CONFIG = 62;

    final HomeActivity act;
    final Context ctx;
    final float dp;
    WsModel model;
    int home = 0;                 // index van de "thuis"-pagina binnen model.pages
    int cols = 4, rows = 5;
    boolean labels = true, light = false, rowsAuto = true;
    float iconScale = 1f;

    DragLayer root;
    Workspace ws;
    LinearLayout dock;
    Dots dots;
    LinearLayout dropBar;
    TextView dropRemove, dropInfo;
    View todayPage;               // WebView "Vandaag" (pagina 0 van het werkblad)
    FolderPopup folder;
    ResizeFrame resizer;
    int insTop, insBottom;

    final AppWidgetManager awm;
    final WHost host;
    final Map<Integer, AppWidgetHostView> widgetViews = new HashMap<>();
    final LruCache<String, Bitmap> icons = new LruCache<>(220);
    final Map<String, String> labelCache = new HashMap<>();

    Desk(HomeActivity a, View today) {
        act = a;
        ctx = a;
        dp = a.getResources().getDisplayMetrics().density;
        todayPage = today;
        awm = AppWidgetManager.getInstance(a);
        host = new WHost(a.getApplicationContext(), HOST_ID);
        pendingWidget = WsStore.prefs(a).getInt("pendingWidget", -1);
    }

    int px(float v) { return Math.round(v * dp); }

    // ---------- opbouw ----------

    View build() {
        readCfg();
        int[] h = new int[1];
        model = WsStore.load(ctx, cols, rows, rowsAuto, h);
        rows = model.rows;
        home = h[0];
        if (!model.lost.isEmpty()) save();
        if (pendingWidget >= 0) { // overgebleven van een vorige keer en nergens geplaatst: vrijgeven
            boolean placed = false;
            for (WsModel.Item w : model.widgets()) if (w.widgetId == pendingWidget) placed = true;
            if (placed) setPending(-1);
        }
        act.wsCache = appsJson();
        root = new DragLayer(ctx, this);
        ws = new Workspace(ctx, this);
        root.addView(ws, new FrameLayout.LayoutParams(-1, -1));

        dots = new Dots(ctx, this);
        root.addView(dots, new FrameLayout.LayoutParams(-1, px(18), Gravity.BOTTOM));

        dock = new LinearLayout(ctx);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x40FFFFFF);
        bg.setCornerRadius(px(26));
        dock.setBackground(bg);
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-1, px(84), Gravity.BOTTOM);
        dl.leftMargin = px(12); dl.rightMargin = px(12);
        root.addView(dock, dl);

        dropBar = new LinearLayout(ctx);
        dropBar.setOrientation(LinearLayout.HORIZONTAL);
        dropBar.setGravity(Gravity.CENTER);
        dropRemove = dropTarget("✕  Weghalen");
        dropInfo = dropTarget("ⓘ  App-info");
        dropBar.addView(dropRemove, new LinearLayout.LayoutParams(0, -1, 1));
        dropBar.addView(dropInfo, new LinearLayout.LayoutParams(0, -1, 1));
        dropBar.setVisibility(View.GONE);
        root.addView(dropBar, new FrameLayout.LayoutParams(-1, px(64), Gravity.TOP));

        rebuild();
        ws.snapTo(home + 1, false);
        return root;
    }

    TextView dropTarget(String t) {
        TextView v = new TextView(ctx);
        v.setText(t);
        v.setTextColor(Color.WHITE);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        v.setGravity(Gravity.CENTER);
        v.setShadowLayer(6, 0, 1, 0xAA000000);
        return v;
    }

    /** Instellingen uit de skin (start.html bewaart ze als JSON): kolommen, pictogramgrootte, namen. */
    void readCfg() {
        try {
            JSONObject c = new JSONObject(Launcher.prefs(ctx).getString("cfg", "{}"));
            cols = Math.max(3, Math.min(6, c.optInt("cols", 4)));
            labels = c.optBoolean("labels", true);
            String ic = c.optString("icon", "m");
            iconScale = "s".equals(ic) ? 0.84f : "l".equals(ic) ? 1.16f : 1f;
            int r = c.optInt("rows", 0);
            rows = r >= 4 && r <= 8 ? r : 0;
            String bg = c.optString("bg");
            light = "g5".equals(bg) || "s2".equals(bg);
        } catch (Exception e) { cols = 4; labels = true; iconScale = 1f; rows = 0; light = false; }
        rowsAuto = rows == 0;
        if (rows == 0) {
            // Rijen naar de schermhoogte: cellen ongeveer zo hoog als breed (+ ruimte voor de naam)
            android.util.DisplayMetrics m = act.getResources().getDisplayMetrics();
            float cellW = m.widthPixels / (float) cols;
            float avail = m.heightPixels - px(84 + 18 + 48 + 40);
            rows = Math.max(4, Math.min(8, (int) (avail / (cellW * 1.12f))));
        }
    }

    void applyCfg() {
        int oc = cols, or = rows;
        readCfg();
        if (rowsAuto && model != null) rows = model.rows; // automatisch bepaalde rijen niet steeds opnieuw uitrekenen
        if (oc != cols || or != rows) { model.setGrid(cols, rows); save(); }
        rebuild();
    }

    void setInsets(int top, int bottom) {
        insTop = top; insBottom = bottom;
        FrameLayout.LayoutParams dl = (FrameLayout.LayoutParams) dock.getLayoutParams();
        dl.bottomMargin = bottom + px(10);
        dock.setLayoutParams(dl);
        FrameLayout.LayoutParams il = (FrameLayout.LayoutParams) dots.getLayoutParams();
        il.bottomMargin = bottom + px(10 + 84 + 4);
        dots.setLayoutParams(il);
        FrameLayout.LayoutParams bl = (FrameLayout.LayoutParams) dropBar.getLayoutParams();
        bl.topMargin = top;
        dropBar.setLayoutParams(bl);
        ws.requestLayout();
        for (int i = 0; i < ws.getChildCount(); i++) ws.getChildAt(i).requestLayout();
    }

    /** Boven en onder van het raster (onder de statusbalk, boven puntjes en dock). */
    int gridTop() { return insTop + px(12); }
    int gridBottom() { return insBottom + px(10 + 84 + 4 + 18 + 6); }

    /**
     * Widgets van apps die echt verwijderd zijn weghalen. Is de widget even onbekend (vlak na het opstarten,
     * tijdens een update van de app), dan blijft hij bewaard en verschijnt hij weer zodra hij er is.
     */
    void cleanWidgets() {
        boolean changed = false;
        for (WsModel.Item w : model.widgets()) {
            if (awm.getAppWidgetInfo(w.widgetId) != null) continue;
            ComponentName cn = w.provider.isEmpty() ? null : ComponentName.unflattenFromString(w.provider);
            if (cn != null && packageInstalled(cn.getPackageName())) continue;
            model.remove(w);
            widgetViews.remove(w.widgetId);
            try { host.deleteAppWidgetId(w.widgetId); } catch (Exception ignored) { }
            changed = true;
        }
        if (changed) save();
    }

    boolean packageInstalled(String pkg) {
        try { ctx.getPackageManager().getPackageInfo(pkg, 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
        catch (Exception e) { return true; } // bij twijfel bewaren
    }

    /** Widgets die bij een ander raster nergens meer pasten ook bij de widget-host opruimen. */
    void dropLost() {
        for (WsModel.Item i : model.lost) if (i.isWidget()) { widgetViews.remove(i.widgetId); try { host.deleteAppWidgetId(i.widgetId); } catch (Exception ignored) { } }
        model.lost.clear();
    }

    void save() {
        dropLost();
        WsStore.save(ctx, model, home);
        act.wsCache = appsJson();
    }

    /** Alle pagina's en het dock opnieuw opbouwen vanuit het model (widgets houden hun view). */
    void rebuild() {
        int cur = ws.page;
        for (AppWidgetHostView v : widgetViews.values()) { ViewGroup p = (ViewGroup) v.getParent(); if (p != null) p.removeView(v); }
        if (todayPage.getParent() != ws || ws.getChildAt(0) != todayPage) {
            if (todayPage.getParent() != null) ((ViewGroup) todayPage.getParent()).removeView(todayPage);
            ws.removeAllViews();
            ws.addView(todayPage);
        } else if (ws.getChildCount() > 1) ws.removeViews(1, ws.getChildCount() - 1);
        for (WsModel.Page p : model.pages) {
            CellLayout cl = new CellLayout(ctx, this);
            for (WsModel.Item i : p.items) {
                View v = viewFor(i);
                if (v != null) cl.addView(v, new CellLayout.LP(i));
            }
            ws.addView(cl);
        }
        ws.page = Math.max(0, Math.min(cur, ws.getChildCount() - 1));
        dock.removeAllViews();
        for (WsModel.Item i : model.dock) {
            View v = viewFor(i);
            if (v != null) dock.addView(v, new LinearLayout.LayoutParams(0, -1, 1));
        }
        if (model.dock.isEmpty()) {
            TextView t = dropTarget("Sleep apps hierheen");
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            t.setAlpha(.8f);
            dock.addView(t, new LinearLayout.LayoutParams(-1, -1));
        }
        ws.requestLayout();
        dots.invalidate();
        // Ongebruikte widget-views opruimen
        List<Integer> live = new ArrayList<>();
        for (WsModel.Item w : model.widgets()) live.add(w.widgetId);
        List<Integer> dead = new ArrayList<>();
        for (Integer id : widgetViews.keySet()) if (!live.contains(id)) dead.add(id);
        for (Integer id : dead) widgetViews.remove(id);
    }

    View viewFor(WsModel.Item i) {
        if (i.isWidget()) {
            AppWidgetHostView v = widgetViews.get(i.widgetId);
            if (v == null) {
                AppWidgetProviderInfo info = awm.getAppWidgetInfo(i.widgetId);
                if (info == null) return null;
                v = host.createView(ctx, i.widgetId, info);
                widgetViews.put(i.widgetId, v);
            }
            v.setTag(i);
            v.setOnLongClickListener(this::onLongPress);
            return v;
        }
        ItemView v = new ItemView(ctx, this, i);
        v.setTag(i);
        v.setOnClickListener(x -> onTap(i));
        v.setOnLongClickListener(this::onLongPress);
        v.setContentDescription(i.isFolder() ? "Map " + i.name : label(i.key));
        return v;
    }

    // ---------- apps: pictogrammen en namen ----------

    Bitmap icon(String key) {
        Bitmap b = icons.get(key);
        if (b != null) return b;
        ComponentName cn = Launcher.component(key);
        int size = px(56);
        b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        try {
            PackageManager pm = ctx.getPackageManager();
            Drawable d;
            try { d = pm.getActivityIcon(cn); } catch (Exception e) { d = pm.getApplicationIcon(cn.getPackageName()); }
            d.setBounds(0, 0, size, size);
            d.draw(c);
        } catch (Exception e) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFF64748B);
            c.drawRoundRect(new RectF(0, 0, size, size), size * .26f, size * .26f, p);
            p.setColor(Color.WHITE);
            p.setTextSize(size * .45f);
            p.setTextAlign(Paint.Align.CENTER);
            String l = label(key);
            c.drawText(l.isEmpty() ? "?" : l.substring(0, 1).toUpperCase(), size / 2f, size * .64f, p);
        }
        icons.put(key, b);
        return b;
    }

    String label(String key) {
        String l = labelCache.get(key);
        if (l != null) return l;
        try {
            PackageManager pm = ctx.getPackageManager();
            ComponentName cn = Launcher.component(key);
            l = pm.getActivityInfo(cn, 0).loadLabel(pm).toString().trim();
        } catch (Exception e) { l = ""; }
        labelCache.put(key, l);
        return l;
    }

    /**
     * Wat er nu bij deze sleutel hoort: dezelfde sleutel, de nieuwe hoofdactiviteit als een update die hernoemd heeft,
     * of null als het pakket echt weg is. Bij twijfel (uitgeschakeld, midden in een update) blijft de sleutel staan.
     */
    String resolveKey(String key) {
        ComponentName cn = Launcher.component(key);
        if (cn == null) return null;
        PackageManager pm = ctx.getPackageManager();
        try { pm.getActivityInfo(cn, 0); return key; } catch (Exception ignored) { }
        if (!packageInstalled(cn.getPackageName())) return null;
        try {
            Intent li = pm.getLaunchIntentForPackage(cn.getPackageName());
            if (li != null && li.getComponent() != null) return li.getComponent().getPackageName() + "/" + li.getComponent().getClassName();
        } catch (Exception ignored) { }
        return key;
    }

    /** Na installeren/verwijderen: caches leeg, verdwenen apps weghalen, hernoemde activiteiten bijwerken. */
    void appsChanged() {
        icons.evictAll();
        labelCache.clear();
        boolean changed = false;
        List<WsModel.Item> all = new ArrayList<>();
        for (WsModel.Page p : model.pages) all.addAll(p.items);
        all.addAll(model.dock);
        for (WsModel.Item i : all) {
            if (i.isApp()) {
                String k = resolveKey(i.key);
                if (k == null) { model.remove(i); changed = true; }
                else if (!k.equals(i.key)) { i.key = k; changed = true; }
            } else if (i.isFolder()) {
                List<String> keep = new ArrayList<>();
                for (String k : i.apps) { String r = resolveKey(k); if (r != null && !keep.contains(r)) keep.add(r); }
                if (!keep.equals(i.apps)) { i.apps.clear(); i.apps.addAll(keep); changed = true; model.tidyFolder(i); }
            }
        }
        int before = model.widgets().size();
        cleanWidgets();
        if (changed || model.widgets().size() != before) save();
        rebuild();
    }

    // ---------- tikken ----------

    void onTap(WsModel.Item i) {
        if (root.dragging) return;
        if (i.isApp()) {
            String e = Launcher.launch(act, i.key);
            if (!e.isEmpty()) act.toast(e);
        } else if (i.isFolder()) openFolder(i);
    }

    boolean onLongPress(View v) {
        Object t = v.getTag();
        if (!(t instanceof WsModel.Item)) return false;
        act.vibrate(18);
        root.startDrag((WsModel.Item) t, v);
        return true;
    }

    // ---------- slepen ----------

    /** Waar zou het item landen? Vult de doelcel van de huidige pagina; geeft false als het niet kan. */
    void dragMove(float x, float y, float fingerX, float fingerY) {
        WsModel.Item it = root.dragItem;
        dropRemove.setBackgroundColor(hit(dropRemove, fingerX, fingerY) ? 0x66EF4444 : 0);
        dropInfo.setBackgroundColor(hit(dropInfo, fingerX, fingerY) ? 0x663B82F6 : 0);
        CellLayout cl = ws.currentCells();
        if (cl != null) {
            int[] c = cl.cellAt(x, y, it.w, it.h);
            cl.setHint(c[0], c[1], it.w, it.h);
        }
        // Rand van het scherm: naar de vorige/volgende pagina
        float edge = px(28);
        int dir = fingerX < edge ? -1 : fingerX > root.getWidth() - edge ? 1 : 0;
        ws.edgeHover(dir);
    }

    boolean hit(View v, float x, float y) {
        if (v.getVisibility() != View.VISIBLE || dropBar.getVisibility() != View.VISIBLE) return false;
        int[] l = new int[2], r = new int[2];
        v.getLocationOnScreen(l); root.getLocationOnScreen(r);
        float lx = x + r[0], ly = y + r[1];
        return lx >= l[0] && lx < l[0] + v.getWidth() && ly >= l[1] && ly < l[1] + v.getHeight();
    }

    boolean overDock(float x, float y) {
        return y >= dock.getTop() - px(6) && y <= dock.getBottom() + px(6);
    }

    /** Loslaten: weghalen, app-info, dock, op een app (map) of op een vrije plek. */
    void drop(WsModel.Item it, float x, float y, float fx, float fy, boolean fromFolder) {
        CellLayout cl = ws.currentCells();
        if (cl != null) cl.setHint(-1, -1, 0, 0);
        if (hit(dropRemove, fx, fy)) {
            model.remove(it);
            if (it.isWidget()) { widgetViews.remove(it.widgetId); try { host.deleteAppWidgetId(it.widgetId); } catch (Exception ignored) { } }
            act.toast(it.isWidget() ? "Widget weggehaald" : "Van het startscherm gehaald (staat nog bij alle apps)");
            done();
            return;
        }
        if (hit(dropInfo, fx, fy)) {
            if (it.isApp()) Launcher.appInfo(act, it.key);
            else if (it.isWidget()) widgetInfo(it);
            if (fromFolder && model.locate(it) == null) model.place(it, ws.cellPage());
            done();
            return;
        }
        if (!it.isWidget() && overDock(x, y)) {
            int idx = dockIndex(x);
            WsModel.Item target = idx < model.dock.size() && model.dock.indexOf(it) < 0 ? dockItemAt(x) : null;
            if (target != null && it.isApp() && target != it && centerHit(x, dock, model.dock.indexOf(target))) {
                WsModel.Item f = model.dropOnto(it, target, "Map");
                if (f != null) { done(); return; }
            }
            if (!model.moveToDock(it, idx)) {
                act.toast("Het dock heeft plaats voor " + WsModel.DOCK_MAX + " apps");
                if (model.locate(it) == null) model.place(it, ws.cellPage());
            }
            done();
            return;
        }
        int page = ws.cellPage();
        if (page < 0) { // op de Vandaag-pagina: naar de eerste gewone pagina
            if (model.locate(it) == null) model.place(it, 0);
            done();
            return;
        }
        if (cl != null) {
            int[] c = cl.cellAt(x, y, it.w, it.h);
            WsModel.Page p = model.pages.get(page);
            WsModel.Item under = model.at(p, Math.min(c[0] + it.w / 2, model.cols - 1), Math.min(c[1] + it.h / 2, model.rows - 1));
            if (under != null && under != it && it.isApp() && (under.isApp() || under.isFolder()) && under.w == 1 && under.h == 1) {
                if (model.dropOnto(it, under, "Map") != null) { done(); return; }
            }
            if (!model.moveTo(it, page, c[0], c[1])) {
                act.toast("Hier is geen plek");
                if (model.locate(it) == null) model.place(it, page);
            }
        }
        done();
    }

    int dockIndex(float x) {
        int n = model.dock.size();
        if (n == 0) return 0;
        float w = dock.getWidth() / (float) Math.max(n, 1);
        return Math.max(0, Math.min(n, Math.round((x - dock.getLeft()) / w)));
    }

    WsModel.Item dockItemAt(float x) {
        int n = model.dock.size();
        if (n == 0) return null;
        int i = (int) ((x - dock.getLeft()) / (dock.getWidth() / (float) n));
        return i >= 0 && i < n ? model.dock.get(i) : null;
    }

    boolean centerHit(float x, View parent, int idx) {
        if (idx < 0) return false;
        float w = parent.getWidth() / (float) Math.max(1, model.dock.size());
        float cx = parent.getLeft() + w * idx + w / 2;
        return Math.abs(x - cx) < w * .28f;
    }

    void done() {
        // Lege pagina's (bijv. na slepen naar een nieuwe pagina en weer terug) opruimen; het hoofdscherm blijft.
        int cur = ws.cellPage();
        WsModel.Page curP = cur >= 0 && cur < model.pages.size() ? model.pages.get(cur) : null;
        home = model.dropEmptyPages(Math.min(home, model.pages.size() - 1));
        save();
        rebuild();
        if (cur < 0) return;
        int ni = curP == null ? -1 : model.pages.indexOf(curP);
        int target = (ni >= 0 ? ni : Math.min(cur, model.pages.size() - 1)) + 1;
        if (target != ws.page) ws.snapTo(target, false);
    }

    /** Slepen afbreken (scherm uit, terug-knop): een app uit een map komt dan op het startscherm i.p.v. te verdwijnen. */
    void cancelDrag() {
        if (root == null || !root.dragging) return;
        WsModel.Item it = root.dragItem;
        boolean folder = root.fromFolder;
        root.end();
        if (folder && it != null && model.locate(it) == null) model.place(it, Math.max(0, ws.cellPage()));
        done(); // ook een lege pagina die tijdens het slepen aan de rand ontstond weer weg
    }

    // ---------- mappen ----------

    void openFolder(WsModel.Item f) {
        closeFolder();
        folder = new FolderPopup(ctx, this, f);
        root.addView(folder, new FrameLayout.LayoutParams(-1, -1));
    }

    boolean closeFolder() {
        if (folder == null) return false;
        root.removeView(folder);
        folder = null;
        return true;
    }

    /** Lang drukken in een open map: eruit slepen. */
    void dragOutOfFolder(WsModel.Item f, String key, View v) {
        f.apps.remove(key);
        WsModel.Item a = model.newItem(WsModel.APP);
        a.key = key;
        closeFolder();
        model.tidyFolder(f);
        save();
        rebuild();
        root.startDragNew(a, v);
    }

    // ---------- menu's (in de webpagina-overlay) ----------

    /** Menu na lang drukken zonder slepen. */
    void itemMenu(WsModel.Item it) {
        try {
            JSONArray acts = new JSONArray();
            String title;
            if (it.isWidget()) {
                AppWidgetProviderInfo info = awm.getAppWidgetInfo(it.widgetId);
                title = info == null ? "Widget" : info.loadLabel(ctx.getPackageManager());
                if (info == null || info.resizeMode != AppWidgetProviderInfo.RESIZE_NONE) acts.put(new JSONArray().put("Formaat aanpassen").put("resize"));
                if (info != null && info.configure != null) acts.put(new JSONArray().put("Instellen").put("config"));
                acts.put(new JSONArray().put("Weghalen").put("remove"));
            } else if (it.isFolder()) {
                title = "Map " + it.name;
                acts.put(new JSONArray().put("Openen").put("open"));
                acts.put(new JSONArray().put("Naam wijzigen").put("rename"));
                acts.put(new JSONArray().put("Map opheffen (apps op het startscherm)").put("ungroup"));
                acts.put(new JSONArray().put("Weghalen").put("remove"));
            } else {
                title = label(it.key);
                acts.put(new JSONArray().put("App-info").put("info"));
                acts.put(new JSONArray().put("Van het startscherm halen").put("remove"));
                acts.put(new JSONArray().put("App verwijderen…").put("uninstall"));
            }
            act.sheet(new JSONObject().put("title", title).put("key", it.isApp() ? it.key : "").put("uid", it.uid)
                    .put("glyph", it.isWidget() ? "▦" : it.isFolder() ? "📁" : "").put("acts", acts));
        } catch (Exception ignored) { }
    }

    void itemAction(long uid, String a) {
        WsModel.Item it = model.byUid(uid);
        if (it == null) return;
        switch (a) {
            case "info": Launcher.appInfo(act, it.key); break;
            case "uninstall": Launcher.uninstall(act, it.key); break;
            case "remove":
                model.remove(it);
                if (it.isWidget()) { widgetViews.remove(it.widgetId); try { host.deleteAppWidgetId(it.widgetId); } catch (Exception ignored) { } }
                done();
                break;
            case "open": openFolder(it); break;
            case "rename": act.askFolderName(it.uid, it.name); break;
            case "ungroup": {
                int[] loc = model.locate(it);
                int pg = loc != null && loc[0] >= 0 ? loc[0] : 0;
                model.remove(it);
                for (String k : it.apps) { WsModel.Item a2 = model.newItem(WsModel.APP); a2.key = k; model.place(a2, pg); }
                done();
                break;
            }
            case "resize": startResize(it); break;
            case "config": configure(it.widgetId); break;
            default: break;
        }
    }

    void renameFolder(long uid, String name) {
        WsModel.Item it = model.byUid(uid);
        if (it == null || !it.isFolder() || name == null || name.trim().isEmpty()) return;
        it.name = name.trim();
        boolean wasOpen = folder != null;
        done();
        if (wasOpen) openFolder(it); // open map toont meteen de nieuwe naam
    }

    /** Lang drukken op een lege plek. */
    void homeMenu() {
        try {
            JSONArray acts = new JSONArray()
                    .put(new JSONArray().put("Widgets toevoegen").put("widgets"))
                    .put(new JSONArray().put("Look aanpassen").put("look"))
                    .put(new JSONArray().put("Achtergrondfoto kiezen").put("wallpaper"))
                    .put(new JSONArray().put("Pagina toevoegen").put("addpage"));
            int pg = ws.cellPage();
            if (pg >= 0 && model.pages.get(pg).items.isEmpty() && model.pages.size() > 1)
                acts.put(new JSONArray().put("Deze pagina verwijderen").put("delpage"));
            if (pg >= 0 && pg != home) acts.put(new JSONArray().put("Dit als hoofdscherm").put("sethome"));
            acts.put(new JSONArray().put("Rene's Tools openen").put("tools"));
            act.sheet(new JSONObject().put("title", "Startscherm").put("uid", -1).put("glyph", "⚙︎").put("acts", acts));
        } catch (Exception ignored) { }
    }

    void homeAction(String a) {
        int pg = ws.cellPage();
        switch (a) {
            case "widgets": act.openWidgetPicker(); break;
            case "look": act.openLook(); break;
            case "wallpaper": act.wallpaper(); break;
            case "addpage":
                if (model.pages.size() >= WsModel.MAX_PAGES) { act.toast("Maximaal " + WsModel.MAX_PAGES + " pagina's"); break; }
                model.pages.add(new WsModel.Page()); save(); rebuild(); ws.snapTo(model.pages.size(), true);
                act.toast("Nieuwe pagina: lang drukken om iets te plaatsen");
                break;
            case "delpage":
                if (pg >= 0 && model.pages.get(pg).items.isEmpty() && model.pages.size() > 1) {
                    model.pages.remove(pg);
                    if (home >= pg && home > 0) home--;
                    save(); rebuild(); ws.snapTo(Math.max(1, pg), true);
                }
                break;
            case "sethome": if (pg >= 0) { home = pg; save(); dots.invalidate(); act.toast("Dit is nu je hoofdscherm"); } break;
            case "tools": act.openTool(""); break;
            default: break;
        }
    }

    // ---------- apps vanuit alle apps / pickers ----------

    /** "Op het startscherm zetten" vanuit alle apps: op de huidige pagina, anders de eerste plek die vrij is. */
    String addApp(String key) {
        if (Launcher.component(key) == null) return "Onbekende app";
        WsModel.Item i = model.newItem(WsModel.APP);
        i.key = key;
        int pg = ws.cellPage();
        int at = model.place(i, pg < 0 ? home : pg);
        if (at < 0) return "Alle pagina's zijn vol";
        save(); rebuild();
        return "";
    }

    String toDock(String key, boolean on) {
        if (on) {
            for (WsModel.Item i : model.dock) if (i.isApp() && key.equals(i.key)) return "";
            if (model.dock.size() >= WsModel.DOCK_MAX) return "Het dock heeft plaats voor " + WsModel.DOCK_MAX + " apps";
            WsModel.Item i = model.newItem(WsModel.APP); i.key = key; model.dock.add(i);
        } else model.dock.removeIf(i -> i.isApp() && key.equals(i.key));
        save(); rebuild();
        return "";
    }

    /** Overzicht voor de app-kiezers in de webpagina. */
    String appsJson() {
        try {
            JSONArray pinned = new JSONArray(), dk = new JSONArray();
            for (WsModel.Page p : model.pages) for (WsModel.Item i : p.items) if (i.isApp()) pinned.put(i.key);
            for (WsModel.Item i : model.dock) if (i.isApp()) dk.put(i.key);
            return new JSONObject().put("pinned", pinned).put("dock", dk).put("pages", model.pages.size()).toString();
        } catch (Exception e) { return "{}"; }
    }

    /** Uitkomst van de app-kiezers: wat er niet meer bij staat weghalen, nieuwe apps plaatsen; dock in de gekozen volgorde. */
    void applyApps(String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONArray pin = o.optJSONArray("pinned"), dk = o.optJSONArray("dock");
            if (pin != null) {
                List<String> want = new ArrayList<>();
                for (int k = 0; k < pin.length(); k++) want.add(pin.optString(k));
                for (WsModel.Page p : model.pages) p.items.removeIf(i -> i.isApp() && !want.contains(i.key));
                List<String> have = new ArrayList<>();
                for (WsModel.Page p : model.pages) for (WsModel.Item i : p.items) if (i.isApp()) have.add(i.key);
                for (String k : want) if (!have.contains(k) && Launcher.component(k) != null) {
                    WsModel.Item i = model.newItem(WsModel.APP); i.key = k; model.place(i, home);
                }
            }
            if (dk != null) {
                List<WsModel.Item> keepFolders = new ArrayList<>();
                for (WsModel.Item i : model.dock) if (i.isFolder()) keepFolders.add(i);
                model.dock.clear();
                for (int k = 0; k < dk.length() && model.dock.size() < WsModel.DOCK_MAX; k++) {
                    WsModel.Item i = model.newItem(WsModel.APP); i.key = dk.optString(k); model.dock.add(i);
                }
                for (WsModel.Item f : keepFolders) if (model.dock.size() < WsModel.DOCK_MAX) model.dock.add(f);
            }
            save(); rebuild();
        } catch (Exception ignored) { }
    }

    // ---------- widgets ----------

    /** Alle widgets die op deze telefoon te plaatsen zijn (voor de kiezer). */
    /** Celmaat (op de hoofdthread opvragen, vóór widgetsJson op de achtergrond). */
    float[] cellSize() {
        CellLayout cl = ws.firstCells();
        float cw = cl != null && cl.cellW > 0 ? cl.cellW : act.getResources().getDisplayMetrics().widthPixels / (float) cols;
        float ch = cl != null && cl.cellH > 0 ? cl.cellH : cw * 1.1f;
        return new float[]{cw, ch};
    }

    volatile List<AppWidgetProviderInfo> providers;

    String widgetsJson(float[] cell) {
        JSONArray out = new JSONArray();
        try {
            PackageManager pm = ctx.getPackageManager();
            List<AppWidgetProviderInfo> list = awm.getInstalledProviders();
            providers = list;
            for (AppWidgetProviderInfo i : list) {
                String app;
                try { app = pm.getApplicationLabel(pm.getApplicationInfo(i.provider.getPackageName(), 0)).toString(); } catch (Exception e) { app = i.provider.getPackageName(); }
                int[] span = spanFor(i, cell);
                out.put(new JSONObject().put("p", i.provider.flattenToString()).put("label", i.loadLabel(pm)).put("app", app)
                        .put("pkg", i.provider.getPackageName()).put("w", span[0]).put("h", span[1]).put("own", ctx.getPackageName().equals(i.provider.getPackageName())));
            }
        } catch (Exception e) { App.log(ctx, "START", "widgets: " + e); }
        return out.toString();
    }

    /** Hoeveel cellen een widget minstens nodig heeft. */
    int[] spanFor(AppWidgetProviderInfo i, float[] cell) {
        float cw = cell[0], ch = cell[1];
        int w = (int) Math.ceil(Math.max(i.minWidth, 40) / cw), h = (int) Math.ceil(Math.max(i.minHeight, 40) / ch);
        if (android.os.Build.VERSION.SDK_INT >= 31 && i.targetCellWidth > 0) { w = i.targetCellWidth; h = Math.max(1, i.targetCellHeight); }
        return new int[]{Math.max(1, Math.min(w, cols)), Math.max(1, Math.min(h, rows))};
    }

    /** Voorbeeldplaatje van een widget voor de kiezer (PNG). */
    byte[] widgetPreview(String provider, int size) {
        try {
            ComponentName cn = ComponentName.unflattenFromString(provider);
            List<AppWidgetProviderInfo> list = providers;
            if (list == null) { list = awm.getInstalledProviders(); providers = list; }
            for (AppWidgetProviderInfo i : list) {
                if (!i.provider.equals(cn)) continue;
                Drawable d = i.loadPreviewImage(ctx, 0);
                if (d == null) d = i.loadIcon(ctx, 0);
                if (d == null) return null;
                int w = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : size, h = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : size;
                float s = Math.min(1f, size / (float) Math.max(w, h));
                w = Math.max(1, Math.round(w * s)); h = Math.max(1, Math.round(h * s));
                Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                d.setBounds(0, 0, w, h);
                d.draw(new Canvas(b));
                java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                b.compress(Bitmap.CompressFormat.PNG, 90, o);
                b.recycle();
                return o.toByteArray();
            }
        } catch (Exception ignored) { }
        return null;
    }

    int pendingWidget = -1;
    String pendingProvider = null;

    /** Widget plaatsen: id aanvragen, toestemming (eenmalig per app), dan eventueel instellen, dan neerzetten. */
    void addWidget(String provider) {
        ComponentName cn = ComponentName.unflattenFromString(provider);
        if (cn == null) return;
        int id = host.allocateAppWidgetId();
        setPending(id);
        pendingProvider = provider;
        boolean ok;
        try { ok = awm.bindAppWidgetIdIfAllowed(id, cn); } catch (Exception e) { ok = false; }
        if (ok) afterBind(id);
        else {
            Intent i = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, cn);
            try { act.startActivityForResult(i, REQ_BIND); }
            catch (Exception e) { cancelPending(); act.toast("Widget toevoegen lukt niet op deze telefoon"); }
        }
    }

    void afterBind(int id) {
        AppWidgetProviderInfo info = awm.getAppWidgetInfo(id);
        if (info == null) { cancelPending(); act.toast("Deze widget is niet beschikbaar"); return; }
        boolean optional = android.os.Build.VERSION.SDK_INT >= 28 && (info.widgetFeatures & AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) != 0;
        if (info.configure != null && !optional) {
            try { host.startAppWidgetConfigureActivityForResult(act, id, 0, REQ_CONFIG, null); return; }
            catch (Exception e) { App.log(ctx, "START", "widget instellen: " + e); }
        }
        placeWidget(id);
    }

    void placeWidget(int id) {
        AppWidgetProviderInfo info = awm.getAppWidgetInfo(id);
        if (info == null) { cancelPending(); return; }
        WsModel.Item w = model.newItem(WsModel.WIDGET);
        w.widgetId = id;
        w.provider = info.provider.flattenToString();
        int[] span = spanFor(info, cellSize());
        w.w = span[0]; w.h = span[1];
        int pg = ws.cellPage();
        int at = model.place(w, pg < 0 ? home : pg);
        setPending(-1); pendingProvider = null;
        if (at < 0) { try { host.deleteAppWidgetId(id); } catch (Exception ignored) { } act.toast("Geen plek meer voor deze widget"); return; }
        save(); rebuild();
        ws.snapTo(at + 1, true);
        act.toast("Widget geplaatst. Lang indrukken om te verslepen of het formaat aan te passen.");
    }

    void cancelPending() {
        if (pendingWidget >= 0) { try { host.deleteAppWidgetId(pendingWidget); } catch (Exception ignored) { } }
        setPending(-1); pendingProvider = null;
    }

    /** Bewaard, zodat een widget die wordt ingesteld niet verloren gaat als Android het scherm intussen opnieuw opbouwt. */
    void setPending(int id) {
        pendingWidget = id;
        WsStore.prefs(ctx).edit().putInt("pendingWidget", id).apply();
    }

    void onActivityResult(int req, int res, Intent data) {
        int id = data != null ? data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidget) : pendingWidget;
        if (req == REQ_BIND) {
            if (res == android.app.Activity.RESULT_OK && id >= 0) afterBind(id);
            else cancelPending();
        } else if (req == REQ_CONFIG) {
            if (pendingWidget >= 0 && id == pendingWidget) {
                if (res == android.app.Activity.RESULT_OK) placeWidget(id); else cancelPending();
            }
            // Opnieuw instellen van een bestaande widget: de widget werkt zichzelf bij.
        }
    }

    void configure(int id) {
        try { host.startAppWidgetConfigureActivityForResult(act, id, 0, REQ_CONFIG, null); }
        catch (Exception e) { act.toast("Deze widget heeft geen instellingen"); }
    }

    void widgetInfo(WsModel.Item it) {
        AppWidgetProviderInfo info = awm.getAppWidgetInfo(it.widgetId);
        if (info != null) Launcher.appInfo(act, info.provider.getPackageName() + "/x");
    }

    /** Doorgeven hoe groot de widget is, zodat hij zijn indeling daarop kiest. */
    void updateWidgetSize(AppWidgetHostView v, int wPx, int hPx) {
        try {
            int wd = Math.round(wPx / dp), hd = Math.round(hPx / dp);
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                ArrayList<android.util.SizeF> sizes = new ArrayList<>();
                sizes.add(new android.util.SizeF(wd, hd));
                v.updateAppWidgetSize(new Bundle(), sizes);
            } else {
                v.updateAppWidgetSize(null, wd, hd, wd, hd);
            }
        } catch (Exception ignored) { }
    }

    void startResize(WsModel.Item it) {
        AppWidgetHostView v = widgetViews.get(it.widgetId);
        CellLayout cl = (v != null && v.getParent() instanceof CellLayout) ? (CellLayout) v.getParent() : null;
        if (cl == null) return;
        int[] loc = model.locate(it);
        if (loc == null || loc[0] < 0) return;
        ws.snapTo(loc[0] + 1, true);
        stopResize();
        resizer = new ResizeFrame(ctx, this, it, cl);
        root.addView(resizer, new FrameLayout.LayoutParams(-1, -1));
    }

    boolean stopResize() {
        if (resizer == null) return false;
        root.removeView(resizer);
        resizer = null;
        done();
        return true;
    }

    /** Achtergrond van het werkblad als de skin een eigen kleur/verloop gebruikt i.p.v. de achtergrondfoto. */
    static Drawable background(Context c) {
        String bg = "wallpaper";
        try { bg = new JSONObject(Launcher.prefs(c).getString("cfg", "{}")).optString("bg", "wallpaper"); } catch (Exception ignored) { }
        int[] cols;
        switch (bg) {
            case "g1": cols = new int[]{0xFF1E3A8A, 0xFF7C3AED, 0xFFDB2777}; break;
            case "g2": cols = new int[]{0xFF0F766E, 0xFF0369A1, 0xFF1E3A8A}; break;
            case "g3": cols = new int[]{0xFFF59E0B, 0xFFEF4444, 0xFF7C2D12}; break;
            case "g4": cols = new int[]{0xFF14532D, 0xFF166534, 0xFF0F172A}; break;
            case "g5": cols = new int[]{0xFFE2E8F0, 0xFFCBD5E1, 0xFF94A3B8}; break;
            case "s1": return new android.graphics.drawable.ColorDrawable(0xFF0B1020);
            case "s2": return new android.graphics.drawable.ColorDrawable(0xFFEEF2F7);
            default: return null;
        }
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR, cols);
    }

    static boolean lightBackground(Context c) {
        HomeActivity a = HomeActivity.inst;
        if (a != null && a.desk != null) return a.desk.light; // zonder elke keer de instellingen te lezen
        try { String bg = new JSONObject(Launcher.prefs(c).getString("cfg", "{}")).optString("bg"); return "g5".equals(bg) || "s2".equals(bg); }
        catch (Exception e) { return false; }
    }

    // =====================================================================================
    //  Views
    // =====================================================================================

    /** Widget-host die onze eigen view maakt (zodat lang drukken op een widget werkt). */
    static final class WHost extends AppWidgetHost {
        WHost(Context c, int id) { super(c, id); }
        @Override protected AppWidgetHostView onCreateView(Context c, int id, AppWidgetProviderInfo info) { return new WView(c); }
    }

    /** Widget-view: lang drukken onderscheppen (anders krijgt de widget alle aanrakingen). */
    static final class WView extends AppWidgetHostView {
        private float sx, sy;
        private boolean longDone;
        private final Runnable lp = this::fireLong;
        WView(Context c) { super(c); }

        private void fireLong() { if (getParent() != null && isAttachedToWindow() && !longDone) { longDone = true; performLongClick(); } }

        @Override
        public boolean dispatchTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    sx = e.getX(); sy = e.getY(); longDone = false;
                    removeCallbacks(lp);
                    postDelayed(lp, ViewConfiguration.getLongPressTimeout());
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (Math.hypot(e.getX() - sx, e.getY() - sy) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) removeCallbacks(lp);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_POINTER_DOWN:
                    removeCallbacks(lp);
                    break;
                default: break;
            }
            return super.dispatchTouchEvent(e);
        }

        @Override protected void onDetachedFromWindow() { removeCallbacks(lp); super.onDetachedFromWindow(); }

        @Override public boolean onInterceptTouchEvent(MotionEvent e) { return longDone; }

        /** Altijd het hele gebaar houden (ook op delen zonder knop), zodat lang drukken netjes afgebroken wordt. */
        @Override public boolean onTouchEvent(MotionEvent e) { return true; } // geen eigen lang-drukken van View (anders twee keer)
    }

    /** App of map op het werkblad of in het dock. */
    static final class ItemView extends View {
        final Desk d;
        final WsModel.Item it;
        final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final RectF r = new RectF();

        ItemView(Context c, Desk desk, WsModel.Item item) {
            super(c);
            d = desk; it = item;
            tp.setTextSize(12 * desk.dp);
            tp.setColor(Desk.lightBackground(c) ? 0xFF0F172A : Color.WHITE);
            if (!Desk.lightBackground(c)) tp.setShadowLayer(5 * desk.dp, 0, desk.dp, 0xB0000000);
            tp.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
            setClickable(true); setLongClickable(true); setFocusable(true);
        }

        boolean inDock() { return getParent() == d.dock; }

        @Override
        protected void onDraw(Canvas c) {
            float size = Math.min(getWidth() * .62f, 56 * d.dp * d.iconScale);
            boolean label = d.labels && !inDock();
            float textH = label ? 18 * d.dp : 0;
            float top = Math.max(0, (getHeight() - size - textH) / 2f);
            float left = (getWidth() - size) / 2f;
            r.set(left, top, left + size, top + size);
            if (it.isFolder()) {
                p.setColor(0x66FFFFFF);
                c.drawRoundRect(r, size * .26f, size * .26f, p);
                float pad = size * .12f, cell = (size - pad * 3) / 2f;
                for (int k = 0; k < Math.min(4, it.apps.size()); k++) {
                    float x = left + pad + (k % 2) * (cell + pad), y = top + pad + (k / 2) * (cell + pad);
                    c.drawBitmap(d.icon(it.apps.get(k)), null, new RectF(x, y, x + cell, y + cell), p);
                }
            } else c.drawBitmap(d.icon(it.key), null, r, p);
            if (label) {
                String l = it.isFolder() ? it.name : d.label(it.key);
                CharSequence t = TextUtils.ellipsize(l, tp, getWidth() - 6 * d.dp, TextUtils.TruncateAt.END);
                float tw = tp.measureText(t, 0, t.length());
                c.drawText(t, 0, t.length(), (getWidth() - tw) / 2f, top + size + 15 * d.dp, tp);
            }
        }
    }

    /** Raster van één pagina. */
    static final class CellLayout extends ViewGroup {
        final Desk d;
        float cellW, cellH;
        int hx = -1, hy = -1, hw, hh;
        final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);

        static final class LP extends ViewGroup.LayoutParams {
            int x, y, w, h;
            LP(WsModel.Item i) { super(-1, -1); x = i.x; y = i.y; w = i.w; h = i.h; }
        }

        CellLayout(Context c, Desk desk) {
            super(c);
            d = desk;
            setWillNotDraw(false);
            hint.setColor(0x40FFFFFF);
            setOnLongClickListener(v -> { d.homeMenu(); return true; });
            setClickable(true);
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws), h = MeasureSpec.getSize(hs);
            setMeasuredDimension(w, h);
            int top = d.gridTop(), bottom = d.gridBottom();
            cellW = (w - d.px(8)) / (float) d.model.cols;
            cellH = Math.max(d.px(40), (h - top - bottom) / (float) d.model.rows);
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                LP lp = (LP) v.getLayoutParams();
                v.measure(MeasureSpec.makeMeasureSpec(Math.round(cellW * lp.w), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(Math.round(cellH * lp.h), MeasureSpec.EXACTLY));
            }
        }

        float ox() { return d.px(4); }
        float oy() { return d.gridTop(); }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                LP lp = (LP) v.getLayoutParams();
                int x = Math.round(ox() + lp.x * cellW), y = Math.round(oy() + lp.y * cellH);
                v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
                if (v instanceof AppWidgetHostView && changed) d.updateWidgetSize((AppWidgetHostView) v, v.getMeasuredWidth(), v.getMeasuredHeight());
            }
        }

        /** Cel onder een punt (in coördinaten van het scherm/werkblad), voor een item van w × h. */
        int[] cellAt(float x, float y, int w, int h) {
            int cx = (int) Math.floor((x - ox()) / cellW - (w - 1) / 2f);
            int cy = (int) Math.floor((y - oy()) / cellH - (h - 1) / 2f);
            return new int[]{Math.max(0, Math.min(cx, d.model.cols - w)), Math.max(0, Math.min(cy, d.model.rows - h))};
        }

        void setHint(int x, int y, int w, int h) { hx = x; hy = y; hw = w; hh = h; invalidate(); }

        @Override
        protected void onDraw(Canvas c) {
            if (hx < 0) return;
            float m = d.px(4);
            c.drawRoundRect(new RectF(ox() + hx * cellW + m, oy() + hy * cellH + m, ox() + (hx + hw) * cellW - m, oy() + (hy + hh) * cellH - m), d.px(14), d.px(14), hint);
        }
    }

    /** Pagina's naast elkaar; vegen met de vinger, verend naar de dichtstbijzijnde pagina. */
    static final class Workspace extends ViewGroup {
        final Desk d;
        final OverScroller sc;
        final int slop, minFling;
        int page = 1;
        private float lx, ly, sx, sy;
        private boolean dragX, dragY;
        private VelocityTracker vt;
        private int edgeDir = 0;
        private final Runnable edgeTick = this::edgeFlip;

        Workspace(Context c, Desk desk) {
            super(c);
            d = desk;
            sc = new OverScroller(c);
            ViewConfiguration vc = ViewConfiguration.get(c);
            slop = vc.getScaledTouchSlop();
            minFling = vc.getScaledMinimumFlingVelocity() * 3;
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws), h = MeasureSpec.getSize(hs);
            setMeasuredDimension(w, h);
            for (int i = 0; i < getChildCount(); i++)
                getChildAt(i).measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }

        @Override
        protected void onLayout(boolean ch, int l, int t, int r, int b) {
            int w = r - l;
            for (int i = 0; i < getChildCount(); i++) getChildAt(i).layout(i * w, 0, (i + 1) * w, b - t);
            if (sc.isFinished()) scrollTo(page * w, 0);
        }

        int pages() { return getChildCount(); }

        /** Pagina-index in het model (−1 = Vandaag-pagina). */
        int cellPage() { return page - 1; }

        CellLayout currentCells() { View v = getChildAt(page); return v instanceof CellLayout ? (CellLayout) v : null; }

        CellLayout firstCells() { View v = getChildAt(1); return v instanceof CellLayout ? (CellLayout) v : null; }

        void snapTo(int p, boolean anim) {
            p = Math.max(0, Math.min(p, pages() - 1));
            page = p;
            int w = getWidth();
            if (w == 0) { requestLayout(); return; }
            if (!anim) { sc.forceFinished(true); scrollTo(p * w, 0); }
            else { sc.startScroll(getScrollX(), 0, p * w - getScrollX(), 0, 320); postInvalidateOnAnimation(); }
            d.dots.invalidate();
            d.act.onPageChanged(p);
        }

        @Override
        public void computeScroll() {
            if (sc.computeScrollOffset()) {
                scrollTo(sc.getCurrX(), 0);
                postInvalidateOnAnimation();
                if (sc.isFinished() && page == 0 && d.todayPage != null) d.todayPage.invalidate();
            }
        }

        @Override
        protected void onScrollChanged(int l, int t, int ol, int ot) {
            super.onScrollChanged(l, t, ol, ot);
            int w = getWidth();
            if (w > 0 && pages() > 1) {
                try {
                    WallpaperManager wm = WallpaperManager.getInstance(getContext());
                    wm.setWallpaperOffsetSteps(1f / (pages() - 1), 0);
                    wm.setWallpaperOffsets(getWindowToken(), Math.max(0, Math.min(1, l / (float) (w * (pages() - 1)))), 0.5f);
                } catch (Exception ignored) { }
            }
            // De Vandaag-pagina (webpagina) tekent alleen het stuk dat hij zichtbaar denkt; bij opzij schuiven van
            // het werkblad wordt hij niet vanzelf opnieuw getekend en blijft hij half leeg. Dus: zelf laten tekenen.
            if (w > 0 && l < w && d.todayPage != null) d.todayPage.invalidate();
            d.dots.invalidate();
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            if (d.root.dragging) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    sx = lx = e.getX(); sy = ly = e.getY(); dragX = dragY = false;
                    if (!sc.isFinished()) { sc.abortAnimation(); dragX = true; }
                    break;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getX() - sx, dy = e.getY() - sy;
                    if (!dragX && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.2f) { dragX = true; lx = e.getX(); }
                    // Verticaal vegen alleen op de gewone pagina's (de Vandaag-pagina scrolt zelf)
                    else if (!dragX && page > 0 && Math.abs(dy) > slop * 2 && Math.abs(dy) > Math.abs(dx) * 1.5f) dragY = true;
                    break;
                }
                default: break;
            }
            if (dragX || dragY) { trackStart(e); return true; }
            return false;
        }

        private void trackStart(MotionEvent e) {
            if (vt == null) vt = VelocityTracker.obtain();
            vt.addMovement(e);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (vt == null) vt = VelocityTracker.obtain();
            vt.addMovement(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: sx = lx = e.getX(); sy = ly = e.getY(); return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragX) {
                        float dx = lx - e.getX();
                        int max = (pages() - 1) * getWidth();
                        int nx = (int) Math.max(-getWidth() * .15f, Math.min(max + getWidth() * .15f, getScrollX() + dx));
                        scrollTo(nx, 0);
                    } else if (!dragY) {
                        float dx = e.getX() - sx, dy = e.getY() - sy;
                        if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) dragX = true;
                        else if (page > 0 && Math.abs(dy) > slop * 2) dragY = true;
                    }
                    lx = e.getX(); ly = e.getY();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    vt.computeCurrentVelocity(1000);
                    float vx = vt.getXVelocity(), vy = vt.getYVelocity();
                    if (dragX) {
                        int w = getWidth(), target = Math.round(getScrollX() / (float) w);
                        if (Math.abs(vx) > minFling) target = vx < 0 ? (int) Math.ceil(getScrollX() / (float) w) : (int) Math.floor(getScrollX() / (float) w);
                        snapTo(target, true);
                    } else if (dragY && e.getActionMasked() == MotionEvent.ACTION_UP) {
                        float dy = e.getY() - sy;
                        if (dy < -d.px(60) || vy < -minFling) d.act.openDrawer();
                        else if (dy > d.px(60) || vy > minFling) d.act.notificationsDown();
                    }
                    dragX = dragY = false;
                    vt.recycle(); vt = null;
                    return true;
                }
                default: return true;
            }
        }

        /** Tijdens slepen aan de rand: na een halve seconde naar de buurpagina (rechts eventueel een nieuwe). */
        void edgeHover(int dir) {
            if (dir == edgeDir) return;
            edgeDir = dir;
            removeCallbacks(edgeTick);
            if (dir != 0) postDelayed(edgeTick, 600);
        }

        /** Vinger blijft aan de rand (ook zonder te bewegen): elke ~0,9 s een pagina verder. */
        private void edgeFlip() {
            if (edgeDir == 0 || !d.root.dragging) return;
            int target = page + edgeDir;
            if (target >= 1) { // niet naar de Vandaag-pagina slepen
                boolean ok = true;
                if (target >= pages()) {
                    // Rechts een nieuwe pagina, maar alleen als de laatste niet al leeg is
                    List<WsModel.Page> ps = d.model.pages;
                    if (ps.size() >= WsModel.MAX_PAGES || ps.get(ps.size() - 1).items.isEmpty()) ok = false;
                    else { ps.add(new WsModel.Page()); addView(new CellLayout(getContext(), d)); }
                }
                if (ok) {
                    CellLayout cur = currentCells();
                    if (cur != null) cur.setHint(-1, -1, 0, 0);
                    snapTo(target, true);
                }
            }
            postDelayed(edgeTick, 900);
        }
    }

    /** Pagina-puntjes; de Vandaag-pagina is een klein zonnetje, het hoofdscherm iets groter. */
    static final class Dots extends View {
        final Desk d;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Dots(Context c, Desk desk) { super(c); d = desk; }
        @Override
        protected void onDraw(Canvas c) {
            int n = d.ws == null ? 0 : d.ws.pages();
            if (n <= 1) return;
            float gap = d.px(14), r = d.px(3.2f), cx = getWidth() / 2f - gap * (n - 1) / 2f, cy = getHeight() / 2f;
            float pos = d.ws.getWidth() > 0 ? d.ws.getScrollX() / (float) d.ws.getWidth() : d.ws.page;
            boolean light = Desk.lightBackground(getContext());
            for (int i = 0; i < n; i++) {
                float a = Math.max(.35f, 1f - Math.min(1f, Math.abs(pos - i)));
                p.setColor(light ? 0xFF0F172A : Color.WHITE);
                p.setAlpha(Math.round(255 * a));
                if (i == 0) { // zonnetje voor Vandaag
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(d.dp * 1.5f);
                    c.drawCircle(cx, cy, r * 1.1f, p);
                    p.setStyle(Paint.Style.FILL);
                } else c.drawCircle(cx + i * gap, cy, i - 1 == d.home ? r * 1.25f : r, p);
            }
        }
    }

    /** Open map: donkere achtergrond met de apps; lang drukken sleept een app eruit. */
    static final class FolderPopup extends FrameLayout {
        FolderPopup(Context c, Desk d, WsModel.Item f) {
            super(c);
            setBackgroundColor(0x99000000);
            setOnClickListener(v -> d.closeFolder());
            LinearLayout box = new LinearLayout(c);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(d.px(16), d.px(16), d.px(16), d.px(16));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Desk.lightBackground(c) ? 0xF0FFFFFF : 0xE6202634);
            bg.setCornerRadius(d.px(26));
            box.setBackground(bg);
            box.setClickable(true);
            TextView title = new TextView(c);
            title.setText(f.name + "  ✎");
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            title.setTextColor(Desk.lightBackground(c) ? 0xFF0F172A : Color.WHITE);
            title.setGravity(Gravity.CENTER);
            title.setPadding(0, 0, 0, d.px(12));
            title.setOnClickListener(v -> d.act.askFolderName(f.uid, f.name));
            box.addView(title);
            int per = 4;
            LinearLayout row = null;
            for (int i = 0; i < f.apps.size(); i++) {
                if (i % per == 0) {
                    row = new LinearLayout(c);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    box.addView(row, new LinearLayout.LayoutParams(-1, d.px(96)));
                }
                String key = f.apps.get(i);
                WsModel.Item a = d.model.newItem(WsModel.APP);
                a.key = key;
                ItemView iv = new ItemView(c, d, a);
                iv.setContentDescription(d.label(key));
                iv.setOnClickListener(v -> { d.closeFolder(); String e = Launcher.launch(d.act, key); if (!e.isEmpty()) d.act.toast(e); });
                iv.setOnLongClickListener(v -> { d.act.vibrate(18); d.dragOutOfFolder(f, key, v); return true; });
                row.addView(iv, new LinearLayout.LayoutParams(0, -1, 1));
            }
            if (row != null) for (int k = f.apps.size() % per; k > 0 && k < per; k++) row.addView(new View(c), new LinearLayout.LayoutParams(0, -1, 1));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
            lp.leftMargin = lp.rightMargin = d.px(22);
            addView(box, lp);
        }
    }

    /** Formaat van een widget aanpassen met grepen aan de randen (vast op het raster). */
    static final class ResizeFrame extends View {
        final Desk d;
        final WsModel.Item it;
        final CellLayout cl;
        final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), knob = new Paint(Paint.ANTI_ALIAS_FLAG);
        int x, y, w, h;
        int grab = -1; // 0 links, 1 boven, 2 rechts, 3 onder
        float gx, gy;
        boolean canH = true, canV = true;
        int minW = 1, minH = 1, maxW, maxH;

        ResizeFrame(Context c, Desk desk, WsModel.Item item, CellLayout layout) {
            super(c);
            d = desk; it = item; cl = layout;
            x = it.x; y = it.y; w = it.w; h = it.h;
            maxW = d.model.cols; maxH = d.model.rows;
            // Grenzen van de widget zelf: in welke richting, en hoe klein/groot
            AppWidgetProviderInfo info = d.awm.getAppWidgetInfo(it.widgetId);
            if (info != null && cl.cellW > 0 && cl.cellH > 0) {
                canH = (info.resizeMode & AppWidgetProviderInfo.RESIZE_HORIZONTAL) != 0;
                canV = (info.resizeMode & AppWidgetProviderInfo.RESIZE_VERTICAL) != 0;
                int mw = info.minResizeWidth > 0 && info.minResizeWidth <= info.minWidth ? info.minResizeWidth : info.minWidth;
                int mh = info.minResizeHeight > 0 && info.minResizeHeight <= info.minHeight ? info.minResizeHeight : info.minHeight;
                minW = Math.max(1, Math.min(w, (int) Math.ceil(mw / cl.cellW - .05f)));
                minH = Math.max(1, Math.min(h, (int) Math.ceil(mh / cl.cellH - .05f)));
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    if (info.maxResizeWidth > 0) maxW = Math.max(Math.max(minW, w), Math.min(maxW, (int) Math.floor(info.maxResizeWidth / cl.cellW + .05f)));
                    if (info.maxResizeHeight > 0) maxH = Math.max(Math.max(minH, h), Math.min(maxH, (int) Math.floor(info.maxResizeHeight / cl.cellH + .05f)));
                }
            }
            line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(d.dp * 2.5f); line.setColor(0xFF60A5FA);
            knob.setColor(Color.WHITE);
            setBackgroundColor(0x33000000);
        }

        RectF rect() {
            float ox = cl.ox(), oy = cl.oy();
            return new RectF(ox + x * cl.cellW, oy + y * cl.cellH, ox + (x + w) * cl.cellW, oy + (y + h) * cl.cellH);
        }

        @Override
        protected void onDraw(Canvas c) {
            RectF r = rect();
            c.drawRoundRect(r, d.px(16), d.px(16), line);
            float k = d.px(9);
            if (canH) { c.drawCircle(r.left, r.centerY(), k, knob); c.drawCircle(r.right, r.centerY(), k, knob); }
            if (canV) { c.drawCircle(r.centerX(), r.top, k, knob); c.drawCircle(r.centerX(), r.bottom, k, knob); }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            RectF r = rect();
            float ex = e.getX(), ey = e.getY(), tol = d.px(32);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (canH && Math.hypot(ex - r.left, ey - r.centerY()) < tol) grab = 0;
                    else if (canV && Math.hypot(ex - r.centerX(), ey - r.top) < tol) grab = 1;
                    else if (canH && Math.hypot(ex - r.right, ey - r.centerY()) < tol) grab = 2;
                    else if (canV && Math.hypot(ex - r.centerX(), ey - r.bottom) < tol) grab = 3;
                    else { grab = -1; d.stopResize(); return true; } // buiten de grepen tikken = klaar
                    gx = ex; gy = ey;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (grab < 0) return true;
                    int nx = x, ny = y, nw = w, nh = h;
                    if (grab == 0) { int c = Math.round((ex - cl.ox()) / cl.cellW); c = Math.max(0, Math.min(c, x + w - 1)); nw = x + w - c; nx = c; }
                    if (grab == 2) { int c = Math.round((ex - cl.ox()) / cl.cellW); nw = Math.max(1, Math.min(c - x, d.model.cols - x)); }
                    if (grab == 1) { int c = Math.round((ey - cl.oy()) / cl.cellH); c = Math.max(0, Math.min(c, y + h - 1)); nh = y + h - c; ny = c; }
                    if (grab == 3) { int c = Math.round((ey - cl.oy()) / cl.cellH); nh = Math.max(1, Math.min(c - y, d.model.rows - y)); }
                    WsModel.Page p = d.model.pages.get(Math.max(0, d.ws.cellPage()));
                    if (nw < minW || nw > maxW || nh < minH || nh > maxH) return true;
                    if ((nx != x || ny != y || nw != w || nh != h) && d.model.isFree(p, nx, ny, nw, nh, it)) {
                        x = nx; y = ny; w = nw; h = nh;
                        invalidate();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (grab >= 0) {
                        it.x = x; it.y = y; it.w = w; it.h = h;
                        d.save();
                        AppWidgetHostView v = d.widgetViews.get(it.widgetId);
                        if (v != null) { v.setLayoutParams(new CellLayout.LP(it)); d.updateWidgetSize(v, Math.round(w * cl.cellW), Math.round(h * cl.cellH)); }
                    }
                    grab = -1;
                    return true;
                default: return true;
            }
        }
    }

    /** Bovenste laag: houdt bij waar de vinger is en neemt het over zodra er gesleept wordt. */
    static final class DragLayer extends FrameLayout {
        final Desk d;
        boolean dragging;
        WsModel.Item dragItem;
        View origin;
        ImageView ghost;
        float lastX, lastY, startX, startY, offX, offY;
        boolean moved, fromFolder;

        DragLayer(Context c, Desk desk) { super(c); d = desk; }

        private boolean childCancelled, sendingCancel;

        /**
         * Tijdens slepen krijgt deze laag alle aanrakingen zelf: de view waarop lang gedrukt werd krijgt één keer
         * ACTION_CANCEL, daarna gaat alles (ook het loslaten zonder te bewegen) naar onTouchEvent.
         */
        @Override
        public boolean dispatchTouchEvent(MotionEvent e) {
            lastX = e.getX(); lastY = e.getY();
            if (dragging) {
                if (!childCancelled) {
                    childCancelled = true;
                    MotionEvent c = MotionEvent.obtain(e);
                    c.setAction(MotionEvent.ACTION_CANCEL);
                    // Zonder doel (bijv. de map is net gesloten) stuurt ViewGroup dit naar onze eigen onTouchEvent: negeren.
                    sendingCancel = true;
                    try { super.dispatchTouchEvent(c); } finally { sendingCancel = false; }
                    c.recycle();
                }
                return onTouchEvent(e);
            }
            return super.dispatchTouchEvent(e);
        }

        void startDrag(WsModel.Item it, View v) { begin(it, v, false); }

        void startDragNew(WsModel.Item it, View v) { begin(it, v, true); }

        private void begin(WsModel.Item it, View v, boolean folder) {
            if (dragging) return;
            dragging = true; dragItem = it; origin = v; moved = false; fromFolder = folder; childCancelled = false;
            startX = lastX; startY = lastY;
            Bitmap b = Bitmap.createBitmap(Math.max(1, v.getWidth()), Math.max(1, v.getHeight()), Bitmap.Config.ARGB_8888);
            try { v.draw(new Canvas(b)); } catch (Exception ignored) { }
            ghost = new ImageView(getContext());
            ghost.setImageBitmap(b);
            ghost.setAlpha(.9f);
            ghost.setScaleX(1.08f); ghost.setScaleY(1.08f);
            int[] vl = new int[2], me = new int[2];
            v.getLocationOnScreen(vl); getLocationOnScreen(me);
            offX = lastX - (vl[0] - me[0]); offY = lastY - (vl[1] - me[1]);
            addView(ghost, new FrameLayout.LayoutParams(v.getWidth(), v.getHeight()));
            ghost.setTranslationX(lastX - offX); ghost.setTranslationY(lastY - offY);
            if (!folder) v.setVisibility(View.INVISIBLE);
            d.dropInfo.setText(it.isWidget() ? "ⓘ  Widget-app" : "ⓘ  App-info");
            d.dropInfo.setVisibility(it.isFolder() ? View.GONE : View.VISIBLE);
            d.dropBar.setVisibility(View.VISIBLE);
            // Ouders (de activiteit) niets laten onderscheppen; deze laag zelf regelt het in dispatchTouchEvent.
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (sendingCancel) return true;
            if (!dragging) return super.onTouchEvent(e);
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    if (Math.hypot(x - startX, y - startY) > d.px(10)) moved = true;
                    if (ghost != null) { ghost.setTranslationX(x - offX); ghost.setTranslationY(y - offY); }
                    if (moved) d.dragMove(x - offX + (origin == null ? 0 : origin.getWidth() / 2f), y - offY + (origin == null ? 0 : origin.getHeight() / 2f), x, y);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) { d.cancelDrag(); return true; }
                    WsModel.Item it = dragItem;
                    boolean wasMoved = moved, folder = fromFolder;
                    float cx = x - offX + (origin == null ? 0 : origin.getWidth() / 2f), cy = y - offY + (origin == null ? 0 : origin.getHeight() / 2f);
                    end();
                    if (!wasMoved && !folder) d.itemMenu(it);         // lang drukken zonder slepen: menu
                    else d.drop(it, cx, cy, x, y, folder);
                    return true;
                }
                default: return true;
            }
        }

        void end() {
            dragging = false;
            if (ghost != null) { removeView(ghost); ghost = null; }
            if (origin != null) origin.setVisibility(View.VISIBLE);
            d.dropBar.setVisibility(View.GONE);
            d.dropRemove.setBackgroundColor(0); d.dropInfo.setBackgroundColor(0);
            CellLayout cl = d.ws.currentCells();
            if (cl != null) cl.setHint(-1, -1, 0, 0);
            d.ws.edgeHover(0);
            dragItem = null;
        }
    }
}
