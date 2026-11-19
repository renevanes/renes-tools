package nl.rene.tools;

import android.webkit.JavascriptInterface;
import org.json.JSONObject;

public class CpapBridge extends ReadAloudBridge {
    CpapBridge(MainActivity a) { super(a); }

    @JavascriptInterface public String cpapList() {
        try { return new JSONObject().put("nights", CpapLog.list(ctx)).toString(); }
        catch (Exception e) { return "{}"; }
    }

    @JavascriptInterface public String cpapSave(String night, String expected) {
        try { return CpapLog.save(ctx, night, expected == null ? "" : expected); }
        catch (Exception e) { return "error"; }
    }

    @JavascriptInterface public String cpapDelete(String day, String expected) {
        try { return CpapLog.delete(ctx, day, expected); }
        catch (Exception e) { return "error"; }
    }
}
