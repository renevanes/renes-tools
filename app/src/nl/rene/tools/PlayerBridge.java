package nl.rene.tools;

import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Brugfuncties voor de ene speler (radio en podcasts samen): wat er speelt, volgende/vorige, de kleur van de hoes
 * en Hierna. Zit tussen PodcastBridge en MainActivity.Bridge.
 */
public class PlayerBridge extends PodcastBridge {

    PlayerBridge(MainActivity act) { super(act); }

    /** "radio", "podcast" of "" (wat het laatst speelde). */
    @JavascriptInterface public String playerSrc() { return Player.src(ctx); }

    /** Kleur van een hoes; antwoord via onPlayerArt({url, color}) (color "" = geen plaatje). */
    @JavascriptInterface public void playerArt(final String url) {
        if (!Art.usable(url)) return;
        new Thread(() -> {
            int c = Art.color(ctx, url);
            try { a.js("onPlayerArt", new JSONObject().put("url", url).put("color", c < 0 ? "" : ArtColor.hex(c)).toString()); } catch (Exception ignored) { }
        }, "player-art").start();
    }

    /** Volgende zender of aflevering (src = wat de speler toont: "radio" of "podcast"). Geeft "" of een melding. */
    @JavascriptInterface public String playerNext(String src) { return Player.step(ctx, src, 1); }

    /** Vorige: bij een podcast na 10 s eerst terug naar het begin. Geeft "" of een melding. */
    @JavascriptInterface public String playerPrev(String src) { return Player.step(ctx, src, -1); }

    // ---------- Hierna ----------

    @JavascriptInterface public String podQueue() { return Podcasts.queue(ctx).toString(); }

    /** {ep, pod}; next = als volgende. Geeft "" of een melding. */
    @JavascriptInterface public String podQueueAdd(String json, boolean next) {
        try {
            JSONObject o = new JSONObject(json), e = o.getJSONObject("ep");
            if (!Podcasts.validKey(e.optString("key"))) e.put("key", Podcasts.epKey(e.optString("url")));
            JSONObject cur = PodcastService.ep == null ? null : new JSONObject(PodcastService.ep);
            if (cur != null && cur.optString("key").equals(e.optString("key")) && !"ended".equals(PodcastService.status)) return "Deze aflevering speelt al";
            Podcasts.queueAdd(ctx, e, o.optJSONObject("pod"), next);
            return "";
        } catch (Exception e) { return e.getMessage() == null ? "Toevoegen lukt niet" : e.getMessage(); }
    }

    @JavascriptInterface public void podQueueRemove(String key) { Podcasts.queueRemove(ctx, key); }

    /** Verplaatsen op sleutel (een index kan intussen verschoven zijn). */
    @JavascriptInterface public void podQueueMove(String key, int to) { Podcasts.queueMoveKey(ctx, key, to); }

    @JavascriptInterface public void podQueueClear() { Podcasts.queueClear(ctx); }

    /** Een aflevering uit Hierna nu afspelen (op sleutel). */
    @JavascriptInterface public String podQueuePlay(String key) {
        JSONArray q = Podcasts.queue(ctx);
        for (int i = 0; i < q.length(); i++) {
            JSONObject r = q.optJSONObject(i), e = r == null ? null : r.optJSONObject("ep");
            if (e != null && e.optString("key").equals(key)) return podPlay(r.toString()); // haalt hem ook uit Hierna
        }
        return "Staat niet meer in Hierna";
    }

    // ---------- zwevende speler ----------

    /** {on, allowed}: aan gezet, en mag hij van Android boven andere apps staan. */
    @JavascriptInterface public String floatState() {
        try { return new JSONObject().put("on", FloatPlayer.enabled(ctx)).put("allowed", FloatPlayer.allowed(ctx)).toString(); }
        catch (Exception e) { return "{}"; }
    }

    /** Aan/uit. Geeft "perm" als je eerst toestemming moet geven (dan gaat het systeemscherm open). */
    @JavascriptInterface public String floatSet(boolean on) {
        FloatPlayer.setEnabled(ctx, on);
        if (on && !FloatPlayer.allowed(ctx)) {
            a.h.post(() -> { try { a.startActivity(FloatPlayer.permissionIntent(ctx)); } catch (Exception ignored) { } });
            return "perm";
        }
        return "";
    }

    /** Radio: naar een plek in de buffer (seconden achter live). */
    @JavascriptInterface public void radioShiftTo(int behindSec) { RadioService.send(ctx, RadioService.TO, String.valueOf(Math.max(0, behindSec))); }
}
