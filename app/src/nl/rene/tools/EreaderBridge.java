package nl.rene.tools;

import android.content.Intent;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import org.json.JSONObject;

public class EreaderBridge extends ToilettenBridge {
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
    @JavascriptInterface public String ereaderPosition(String id, int chapter, double offset) {
        try { Ereader.position(ctx, id, chapter, offset); return ""; } catch (Throwable e) { return Taal.t(ctx, "Leespositie bewaren lukt niet"); }
    }
    @JavascriptInterface public String ereaderDelete(String id) {
        try { Ereader.delete(ctx, id); return ""; } catch (Throwable e) { return Taal.t(ctx, "Boek verwijderen lukt niet"); }
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
            try { a.js("onEreaderImport", new JSONObject().put("book", Ereader.add(a.getApplicationContext(), uri)).toString()); }
            catch (Throwable e) { a.js("onEreaderImport", "{\"error\":" + JSONObject.quote(Taal.t(a, "Dit boek kan niet worden ingelezen. Kies een geldig EPUB zonder versleutelde onderdelen of een UTF-8-tekstbestand van maximaal 20 MB.")) + "}"); }
        }, "ereader-import").start();
    }
}
