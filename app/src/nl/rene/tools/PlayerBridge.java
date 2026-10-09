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

    // ---------- waar het speelt: telefoon, Bluetooth (via Android) of Chromecast ----------

    /** Afspelen/pauze van wat er nu in de speler staat (ook op een Chromecast). */
    @JavascriptInterface public void playerPlayPause() { PodcastBridge.castBg(() -> Player.playPause(ctx)); }

    /** Chromecasts zoeken (ca. 4 s); antwoord via onCastDevices({devices:[{id,name,model}]}). */
    @JavascriptInterface public void castScan() {
        Cast.discover(ctx, 4000, () -> {
            try { a.js("onCastDevices", new JSONObject().put("devices", Cast.devicesJson()).toString()); } catch (Exception ignored) { }
        });
    }

    /** Wat er nu op de telefoon in de speler staat, naar dit apparaat. Geeft "" of een melding. */
    @JavascriptInterface public String castStart(String id) {
        try {
            Player.Now n = Player.now(ctx);
            if (n == null) return "Kies eerst een zender of aflevering";
            Cast.Item it;
            if (Player.RADIO.equals(n.src)) it = Cast.fromStation(n.station);
            else {
                it = Cast.fromEpisode(ctx, n.ep, n.pod, false);
                boolean atEnd = "ended".equals(n.status) || (n.dur > 0 && n.pos > n.dur - 5000);
                if (n.pos > 0 && !atEnd) it.startMs = n.pos; // de plek van nu (net nog op de telefoon)
                if (atEnd) it.startMs = 0;
            }
            return Cast.start(ctx, id, it);
        } catch (Exception e) { return "Casten lukt niet"; }
    }

    /** Stoppen met casten; local = op de telefoon verder (waar het was). */
    @JavascriptInterface public void castStop(boolean local) {
        final Player.Now n = Cast.now();
        PodcastBridge.castBg(() -> {
            Cast.stop();
            if (!local || n == null) return;
            try {
                if (Player.RADIO.equals(n.src) && n.station != null) RadioService.send(ctx, RadioService.PLAY, n.station.toString());
                else if (n.ep != null) {
                    // Precies waar de Chromecast was (net bewaard bij het stoppen)
                    if (n.pos > 0 && n.dur > 0 && n.pos < n.dur - 5000) Podcasts.saveProgress(ctx, n.ep.optString("key"), n.pos, n.dur, false);
                    PodcastService.send(ctx, PodcastService.PLAY, new JSONObject().put("ep", n.ep).put("pod", n.pod).toString());
                }
            } catch (Exception ignored) { }
        });
    }

    /** {active, device, status, error, …} van de Chromecast-sessie (ook na een verbroken verbinding, tot castForget). */
    @JavascriptInterface public String castState() {
        try {
            Cast.Session s = Cast.cur;
            if (s == null) return "{\"active\":false}";
            Player.Now n = Cast.now();
            JSONObject o = new JSONObject().put("active", !s.closed).put("id", s.id).put("device", s.dev.name).put("status", n.status).put("playing", n.playing)
                    .put("src", n.src).put("title", n.title).put("sub", n.sub).put("art", n.art).put("key", n.key)
                    .put("pos", n.pos).put("dur", n.dur).put("sleepAt", n.sleepAt).put("sleepEnd", n.sleepEnd).put("volume", n.volume).put("rate", n.rate);
            if (s.error != null) o.put("error", s.error);
            if (n.ep != null) o.put("ep", n.ep);
            if (n.pod != null) o.put("pod", n.pod);
            if (n.station != null) o.put("station", n.station);
            return o.toString();
        } catch (Exception e) { return "{\"active\":false}"; }
    }

    @JavascriptInterface public void castForget() { Cast.forget(); }

    @JavascriptInterface public void castVolume(double level) { PodcastBridge.castBg(() -> Cast.setVolume(level)); }

    /** De keuze van Android (Bluetooth, koptelefoon, speaker). Geeft "" of een melding. */
    @JavascriptInterface public String outputSwitcher() {
        a.h.post(() -> {
            try {
                if (android.os.Build.VERSION.SDK_INT >= 34 && android.media.MediaRouter2.getInstance(ctx).showSystemOutputSwitcher()) return;
            } catch (Throwable ignored) { }
            try { // oudere Android-versies: het mediavenster van het systeem
                ctx.sendBroadcast(new android.content.Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG")
                        .setPackage("com.android.systemui").putExtra("package_name", ctx.getPackageName()));
                return;
            } catch (Throwable ignored) { }
            try { a.startActivity(new android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)); } catch (Exception ignored) { }
        });
        return "";
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
