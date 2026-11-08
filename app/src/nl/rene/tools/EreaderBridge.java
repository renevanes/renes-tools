package nl.rene.tools;

import android.content.Intent;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import org.json.JSONObject;

public class EreaderBridge extends ToilettenBridge {
    private final java.util.concurrent.atomic.AtomicInteger searchGeneration = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger imageGeneration = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.ExecutorService IMAGE_THREADS = java.util.concurrent.Executors.newFixedThreadPool(2);
    static final int REQ_BOOK = 66;
    EreaderBridge(MainActivity a) { super(a); }
    private String error(Throwable e) {
        return "{\"error\":" + JSONObject.quote(Taal.tx(ctx, e.getMessage() == null ? Taal.T("Boek openen lukt niet") : e.getMessage())) + "}";
    }
    @JavascriptInterface public String ereaderList() {
        try { return new JSONObject().put("books", Ereader.list(ctx)).toString(); } catch (Throwable e) { return error(e); }
    }
    @JavascriptInterface public String ereaderChapter(String id, int chapter) {
        try { return Ereader.chapter(ctx, id, chapter).toString(); } catch (Throwable e) { return error(e); }
    }
    @JavascriptInterface public String ereaderDetails(String id) {
        try { return Ereader.details(ctx, id).toString(); } catch (Throwable e) { return error(e); }
    }
    @JavascriptInterface public String ereaderCover(String id) {
        try { return Ereader.cover(ctx, id); } catch (Throwable e) { return ""; }
    }
    @JavascriptInterface public void ereaderImagesCancel() { imageGeneration.incrementAndGet(); }
    @JavascriptInterface public void ereaderImage(String id, int asset, String request) {
        final int generation = imageGeneration.get();
        if (request == null || request.length() > 80 || asset < 0 || asset >= 2000) return;
        IMAGE_THREADS.execute(() -> {
            try {
                if (generation != imageGeneration.get()) return;
                JSONObject image;
                try { image = Ereader.image(ctx, id, asset); }
                catch (Throwable e) { image = new JSONObject().put("error", Taal.t(ctx, "Deze afbeelding kan niet worden weergegeven")); }
                if (generation == imageGeneration.get()) a.js("onEreaderImage", image.put("id", id).put("asset", asset).put("request", request).toString());
            } catch (Throwable ignored) { }
        });
    }
    @JavascriptInterface public String ereaderUpdate(String id, String json) {
        try { return Ereader.update(ctx, id, json).toString(); } catch (Throwable e) { return error(e); }
    }
    @JavascriptInterface public String ereaderAnnotation(String id, String json) {
        try { return Ereader.annotate(ctx, id, json).toString(); } catch (Throwable e) { return error(e); }
    }
    @JavascriptInterface public String ereaderReadPosition(String id, int chapter, double offset, int anchor) {
        try { Ereader.position(ctx, id, chapter, offset, anchor); return ""; } catch (Throwable e) { return Taal.t(ctx, "Leespositie bewaren lukt niet"); }
    }
    @JavascriptInterface public void ereaderSearchCancel() { searchGeneration.incrementAndGet(); }
    @JavascriptInterface public void ereaderSearch(String id, String query, String request) {
        final int generation = searchGeneration.incrementAndGet();
        if (query == null || query.trim().length() < 2 || query.length() > 80 || request == null || request.length() > 80) return;
        new Thread(() -> {
            try {
                JSONObject meta = Ereader.meta(ctx, id); org.json.JSONArray hits = new org.json.JSONArray();
                for (int i = 0; i < meta.getJSONArray("chapters").length() && hits.length() < 101; i++) {
                    if (generation != searchGeneration.get()) return;
                    String text = Ereader.chapter(ctx, id, i).getString("text");
                    for (EreaderSearch.Hit hit : EreaderSearch.find(text, query.trim(), 101 - hits.length()))
                        hits.put(new JSONObject().put("chapter", i).put("start", hit.start).put("end", hit.end).put("excerpt", hit.excerpt));
                }
                if (generation == searchGeneration.get()) a.js("onEreaderSearch", new JSONObject().put("request", request).put("id", id).put("hits", hits).toString());
            } catch (Throwable e) {
                if (generation == searchGeneration.get()) {
                    try { a.js("onEreaderSearch", new JSONObject(error(e)).put("request", request).put("id", id).toString()); } catch (Throwable ignored) { }
                }
            }
        }, "ereader-search").start();
    }
    @JavascriptInterface public String ereaderPosition(String id, int chapter, double offset) {
        try { Ereader.position(ctx, id, chapter, offset); return ""; } catch (Throwable e) { return Taal.t(ctx, "Leespositie bewaren lukt niet"); }
    }
    @JavascriptInterface public String ereaderDelete(String id) {
        try { Ereader.delete(ctx, id); return ""; } catch (Throwable e) { return Taal.t(ctx, "Boek verwijderen lukt niet"); }
    }
    /**
     * Het venster tijdens het lezen: {full (systeembalken weg), awake (scherm aan), bright (0..1, -1 = zoals de telefoon),
     * keys (volumeknoppen bladeren)}; alles uit/terug met {}. Antwoord {talkback}: dan blijven de knoppen zichtbaar.
     */
    @JavascriptInterface public String ereaderWindow(String json) {
        boolean talkback = false;
        try {
            android.view.accessibility.AccessibilityManager am = (android.view.accessibility.AccessibilityManager) ctx.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE);
            talkback = am != null && am.isEnabled() && am.isTouchExplorationEnabled();
        } catch (Throwable ignored) { }
        try {
            JSONObject o = new JSONObject(json == null ? "{}" : json);
            final boolean full = o.optBoolean("full"), awake = o.optBoolean("awake");
            final double bright = o.optDouble("bright", -1);
            a.ereaderKeys = o.optBoolean("keys");
            a.h.post(() -> { try { a.ereaderWindow(full, awake, bright); } catch (Throwable ignored) { } });
        } catch (Throwable ignored) { }
        return "{\"talkback\":" + talkback + "}";
    }
    @JavascriptInterface public void ereaderPick() {
        a.h.post(() -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/epub+zip", "text/plain", "application/octet-stream", "application/zip"});
            try { a.startActivityForResult(i, REQ_BOOK); }
            catch (Throwable e) { a.js("onEreaderImport", error(new Exception(Taal.T("Bestandskiezer niet beschikbaar")))); }
        });
    }
    static void picked(MainActivity a, Uri uri) {
        new Thread(() -> {
            try (Voortgang v = Voortgang.van(a, "ereader", 1)) { a.js("onEreaderImport", new JSONObject().put("book", Ereader.add(a.getApplicationContext(), uri, v)).toString()); }
            catch (Throwable e) { a.js("onEreaderImport", "{\"error\":" + JSONObject.quote(Taal.t(a, "Dit boek kan niet worden ingelezen. Kies een geldig EPUB zonder versleutelde onderdelen of een UTF-8-tekstbestand van maximaal 20 MB.")) + "}"); }
        }, "ereader-import").start();
    }
}
