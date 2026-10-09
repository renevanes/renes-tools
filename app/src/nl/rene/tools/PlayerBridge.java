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
    @JavascriptInterface public String playerNext(String src) { return step(src, 1); }

    /** Vorige: bij een podcast na 10 s eerst terug naar het begin. Geeft "" of een melding. */
    @JavascriptInterface public String playerPrev(String src) { return step(src, -1); }

    private static long prevAt;
    private static int prevDepth;

    private String step(String src, int dir) {
        try {
            if (Player.RADIO.equals(src == null || src.isEmpty() ? Player.src(ctx) : src)) {
                JSONObject s = RadioService.neighbour(ctx, dir);
                if (s == null) return "Zet zenders bij je favorieten (☆) om te wisselen";
                Radio.prefs(ctx).edit().putString("last", s.toString()).apply();
                RadioService.send(ctx, RadioService.PLAY, s.toString());
                return "";
            }
            JSONObject st = new JSONObject(podState());
            JSONObject ep = st.optJSONObject("ep"), pod = st.optJSONObject("pod");
            if (dir < 0) {
                if (ep != null && st.optLong("pos") > 10_000) { podSeek(0); return ""; }
                // Vlak na elkaar ⏮: steeds een stap verder terug in wat je eerder luisterde
                long now = System.currentTimeMillis();
                prevDepth = now - prevAt < 30_000 ? prevDepth + 1 : 1;
                prevAt = now;
                JSONObject r = Podcasts.previous(ctx, ep == null ? "" : ep.optString("key"), prevDepth);
                if (r == null) { if (ep != null) podSeek(0); return ep == null ? "Er speelde nog niets" : ""; }
                PodcastService.send(ctx, PodcastService.PLAY, new JSONObject().put("ep", r.getJSONObject("ep")).put("pod", r.optJSONObject("pod")).toString());
                return "";
            }
            JSONObject n = Podcasts.next(ctx, ep, pod);
            if (n == null) return "Geen volgende aflevering. Zet er een in Hierna via ⋯ bij een aflevering.";
            PodcastService.send(ctx, PodcastService.PLAY, n.toString());
            return "";
        } catch (Exception e) { return "Dat lukt nu niet"; }
    }

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

    /** Radio: naar een plek in de buffer (seconden achter live). */
    @JavascriptInterface public void radioShiftTo(int behindSec) { RadioService.send(ctx, RadioService.TO, String.valueOf(Math.max(0, behindSec))); }
}
