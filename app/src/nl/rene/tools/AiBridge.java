package nl.rene.tools;

import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Brugfuncties voor de lokale AI (assistent en slim zoeken). Zit tussen FeatureBridge en PodcastBridge, zodat de
 * interface ze als Android.aiXxx ziet. Het echte werk gebeurt op de AI-thread (LocalAi); antwoorden komen
 * terug via js-functies (onAiToken, onAiDone, onAiError, onAiStatus, onAiSources, onAiSearch, onAiIndex, onAiModels).
 */
public class AiBridge extends FeatureBridge {

    AiBridge(MainActivity act) { super(act); }

    private static String q(String s) { return JSONObject.quote(s == null ? "" : s); }

    /** Alles over modellen, downloads en de zoekindex. */
    @JavascriptInterface public String aiState() {
        try { return LocalAi.info(ctx).toString(); } catch (Exception e) { return "{}"; }
    }

    @JavascriptInterface public String aiDownload(String id) {
        LocalAi.Model m = LocalAi.model(id);
        if (m == null) return "Onbekend model";
        if (!LocalAi.supported()) return "De lokale AI werkt alleen op telefoons met een 64-bits ARM-processor";
        return LocalAi.download(ctx, m);
    }

    @JavascriptInterface public void aiDelete(String id) {
        LocalAi.Model m = LocalAi.model(id);
        if (m != null) LocalAi.delete(ctx, m);
    }

    /** Welk taalmodel de assistent gebruikt. */
    @JavascriptInterface public void aiUse(String id) {
        LocalAi.Model m = LocalAi.model(id);
        if (m == null || m.embed) return;
        LocalAi.prefs(ctx).edit().putString("chat", m.id).apply();
    }

    @JavascriptInterface public void aiPref(String name, boolean on) {
        if (!"wifiOnly".equals(name) && !"notes".equals(name)) return;
        LocalAi.prefs(ctx).edit().putBoolean(name, on).apply();
    }

    /** Een vraag stellen: json = {messages:[{role, content}], notes:bool}. Geeft "" of een melding; de rest komt via onAi…. */
    @JavascriptInterface public String aiChat(String json) {
        final JSONObject req;
        try { req = new JSONObject(json); } catch (Exception e) { return "Ongeldige vraag"; }
        if (LocalAi.busy()) return "De assistent is nog bezig";
        LocalAi.chat(ctx, req, new JsSink(a));
        return "";
    }

    @JavascriptInterface public void aiCancel() { LocalAi.cancel(); }

    /** Een antwoord naar het klembord. */
    @JavascriptInterface public void aiCopy(final String text) {
        if (text == null) return;
        a.h.post(() -> {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Assistent", text.length() > 100_000 ? text.substring(0, 100_000) : text));
            } catch (Exception ignored) { }
        });
    }

    /** Een antwoord of gesprek delen met een andere app (die jij kiest). */
    @JavascriptInterface public void aiShare(final String text) {
        if (text == null) return;
        a.h.post(() -> {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_TEXT, text.length() > 100_000 ? text.substring(0, 100_000) : text);
            try { a.startActivity(android.content.Intent.createChooser(i, "Delen")); } catch (Exception ignored) { }
        });
    }

    @JavascriptInterface public String aiChats() { try { return LocalAi.chatList(ctx); } catch (Exception e) { return "[]"; } }

    @JavascriptInterface public String aiChatGet(String id) { return id == null ? "" : LocalAi.chatGet(ctx, id); }

    /** Bewaart een gesprek; geeft het id. */
    @JavascriptInterface public String aiChatSave(String json) {
        try { return LocalAi.chatSave(ctx, new JSONObject(json)); } catch (Exception e) { return ""; }
    }

    /** id "*" = alle gesprekken. */
    @JavascriptInterface public void aiChatDelete(String id) { try { if (id != null) LocalAi.chatDelete(ctx, id); } catch (Exception ignored) { } }

    private static int searchSeq = 0;

    /** Zoeken op betekenis; antwoord via onAiSearch({id, q, items | need | error}). */
    @JavascriptInterface public int aiSearch(final String query) {
        final int id;
        synchronized (AiBridge.class) { id = ++searchSeq; }
        final String qq = query == null ? "" : query.trim();
        if (qq.length() < 2) return id;
        LocalAi.search(ctx, qq, o -> {
            try { o.put("id", id); } catch (Exception ignored) { }
            a.js("onAiSearch", o.toString());
        });
        return id;
    }

    /** Zoekindex bijwerken (force = helemaal opnieuw); voortgang via onAiIndex. */
    @JavascriptInterface public void aiIndex(boolean force) {
        LocalAi.index(ctx, force, o -> a.js("onAiIndex", o.toString()));
    }

    /** Stuurt alles van de AI-thread naar de interface. */
    static final class JsSink implements LocalAi.Sink {
        final MainActivity a;
        private final StringBuilder pend = new StringBuilder();
        private long last;
        JsSink(MainActivity a) { this.a = a; }
        // Woordjes samen versturen (hooguit ±12 keer per seconde): rustiger voor de interface
        private void flush() { if (pend.length() > 0) { MainActivity.jsAll("onAiToken", q(pend.toString())); pend.setLength(0); } last = System.currentTimeMillis(); }
        @Override public void status(String text) { MainActivity.jsAll("onAiStatus", q(text)); }
        @Override public void sources(JSONArray items) { MainActivity.jsAll("onAiSources", items.toString()); }
        @Override public synchronized void token(String t) { pend.append(t); if (System.currentTimeMillis() - last > 80) flush(); }
        @Override public synchronized void done(JSONObject info) { flush(); MainActivity.jsAll("onAiDone", info.toString()); }
        @Override public synchronized void error(String msg) { flush(); MainActivity.jsAll("onAiError", q(msg)); }
    }
}
