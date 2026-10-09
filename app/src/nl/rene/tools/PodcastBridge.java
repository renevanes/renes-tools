package nl.rene.tools;

import android.webkit.JavascriptInterface;

import org.json.JSONObject;

/**
 * Brugfuncties voor Podcasts. Zit tussen FeatureBridge en MainActivity.Bridge, zodat de interface ze gewoon als
 * Android.podXxx ziet. Netwerkwerk gebeurt op een eigen thread; het antwoord komt terug via een js-functie.
 */
public class PodcastBridge extends FeatureBridge {

    PodcastBridge(MainActivity act) { super(act); }

    private static String err(Exception e, String fallback) {
        String m = e instanceof java.net.UnknownHostException || e instanceof java.net.SocketTimeoutException ? "Geen internetverbinding"
                : e.getMessage() == null ? fallback : e.getMessage();
        return m;
    }

    private void bg(String name, Runnable r) { new Thread(r, name).start(); }

    /** Zoeken; antwoord via onPodSearch({q, results} of {q, error}). */
    @JavascriptInterface public void podSearch(final String q) {
        bg("pod-search", () -> {
            JSONObject o = new JSONObject();
            try { o.put("q", q); o.put("results", Podcasts.search(q == null ? "" : q)); }
            catch (Exception e) { try { o.put("error", err(e, "Zoeken lukt niet")); } catch (Exception ignored) { } }
            a.js("onPodSearch", o.toString());
        });
    }

    /** Populair in Nederland; antwoord via onPodTop. */
    @JavascriptInterface public void podTop() {
        bg("pod-top", () -> {
            JSONObject o = new JSONObject();
            try { o.put("results", Podcasts.top(ctx)); }
            catch (Exception e) { try { o.put("error", err(e, "Laden lukt niet")); } catch (Exception ignored) { } }
            a.js("onPodTop", o.toString());
        });
    }

    /** Een podcast openen: info en afleveringen (met waar je was); antwoord via onPodFeed. */
    @JavascriptInterface public void podOpen(final String feedUrl, final boolean force) {
        bg("pod-feed", () -> {
            JSONObject o;
            try { o = Podcasts.withProgress(ctx, Podcasts.feed(ctx, feedUrl, force)); o.put("feedUrl", feedUrl); }
            catch (Exception e) {
                o = new JSONObject();
                try { o.put("feedUrl", feedUrl).put("error", err(e, "Deze podcast kan niet worden geladen")); } catch (Exception ignored) { }
            }
            a.js("onPodFeed", o.toString());
        });
    }

    @JavascriptInterface public String podSubscribe(String podJson) {
        try { Podcasts.subscribe(ctx, new JSONObject(podJson)); return ""; } catch (Exception e) { return err(e, "Abonneren lukt niet"); }
    }

    @JavascriptInterface public void podUnsubscribe(String id) { try { Podcasts.unsubscribe(ctx, id); } catch (Exception ignored) { } }

    @JavascriptInterface public String podSubs() { return Podcasts.subs(ctx).toString(); }

    @JavascriptInterface public void podSeen(String id) { try { Podcasts.seen(ctx, id); } catch (Exception ignored) { } }

    /** Alle abonnementen bijwerken; antwoord via onPodRefreshed({fresh}). */
    @JavascriptInterface public void podRefresh() {
        bg("pod-refresh", () -> {
            int n = Podcasts.refreshAll(ctx);
            a.js("onPodRefreshed", "{\"fresh\":" + n + "}");
        });
    }

    @JavascriptInterface public long podRefreshedAt() { return Podcasts.prefs(ctx).getLong("refreshedAt", 0); }

    @JavascriptInterface public String podContinue() {
        try { return Podcasts.continueList(ctx).toString(); } catch (Exception e) { return "[]"; }
    }

    @JavascriptInterface public void podForget(String key) { Podcasts.removeRecent(ctx, key); }

    @JavascriptInterface public void podMarkPlayed(String key, boolean played) {
        if (key == null || !key.matches("e[0-9a-f]{16}")) return;
        Podcasts.saveProgress(ctx, key, 0, 0, played);
    }

    /** Afspelen: {ep, pod, pos?}. */
    @JavascriptInterface public String podPlay(String json) {
        try {
            JSONObject o = new JSONObject(json), e = o.getJSONObject("ep");
            if (!Podcasts.httpUrl(e.optString("url"))) return "Deze aflevering heeft geen geldig adres";
            if (!Podcasts.validKey(e.optString("key"))) e.put("key", Podcasts.epKey(e.optString("url")));
            Podcasts.queueRemove(ctx, e.optString("key")); // speelt nu: meteen uit Hierna (de lijst klopt dan direct)
            PodcastService.send(ctx, PodcastService.PLAY, o.toString());
            return "";
        } catch (Exception e) { return "Afspelen lukt niet"; }
    }

    @JavascriptInterface public void podPause() { PodcastService.send(ctx, PodcastService.PAUSE, null); }
    @JavascriptInterface public void podResume() { PodcastService.send(ctx, PodcastService.RESUME, null); }
    @JavascriptInterface public void podStop() { PodcastService.send(ctx, PodcastService.STOP, null); }
    @JavascriptInterface public void podSeek(long ms) { Player.podSeek(ctx, Math.max(0, ms)); } // ook als er niets speelt (dan bewaard)
    @JavascriptInterface public void podSkip(int sec) {
        if (PodcastService.inst == null) { try { JSONObject st = new JSONObject(podState()); podSeek(Math.max(0, st.optLong("pos") + sec * 1000L)); } catch (Exception ignored) { } return; }
        PodcastService.send(ctx, PodcastService.SKIP, String.valueOf(Math.max(-600, Math.min(600, sec))));
    }
    @JavascriptInterface public void podSpeed(String v) { PodcastService.send(ctx, PodcastService.SPEED, v); }
    /** minuten > 0, -1 = einde van de aflevering, 0 = uit. */
    /** Geeft "" of een melding (de slaaptimer hoort bij wat er nu speelt). */
    @JavascriptInterface public String podSleep(int minutes) {
        if (PodcastService.inst == null) return minutes == 0 ? "" : "Start eerst een aflevering";
        PodcastService.send(ctx, PodcastService.SLEEP, String.valueOf(Math.max(-1, Math.min(24 * 60, minutes))));
        return "";
    }
    @JavascriptInterface public String podSleepAdd(int minutes) {
        if (PodcastService.inst == null) return "Start eerst een aflevering";
        PodcastService.send(ctx, PodcastService.SLEEP_ADD, String.valueOf(Math.max(1, Math.min(240, minutes))));
        return "";
    }

    @JavascriptInterface public String podState() {
        String s = PodcastService.stateJson();
        if (PodcastService.inst == null && PodcastService.ep == null) {
            // Na herstarten van de app: de laatste aflevering tonen (gepauzeerd), zodat je verder kunt
            String last = Podcasts.prefs(ctx).getString("last", null);
            if (last != null) try {
                JSONObject o = new JSONObject(last), e = o.getJSONObject("ep");
                JSONObject p = Podcasts.progressOf(ctx, e.optString("key"));
                if (p != null && p.optBoolean("done")) return s;
                return new JSONObject().put("status", "idle").put("ep", e).put("pod", o.optJSONObject("pod"))
                        .put("pos", p == null ? 0 : p.optLong("p")).put("dur", p == null ? e.optLong("dur") * 1000 : p.optLong("d"))
                        .put("speed", Podcasts.prefs(ctx).getFloat("speed", 1f)).toString();
            } catch (Exception ignored) { }
        }
        return s;
    }

    /** Deel de aflevering (titel + link naar de podcast of het audiobestand). */
    @JavascriptInterface public void podShare(String title, String link) {
        if (link == null || !Podcasts.httpUrl(link)) return;
        android.content.Intent s = new android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                .putExtra(android.content.Intent.EXTRA_TEXT, (title == null || title.isEmpty() ? "" : title + "\n") + link);
        a.h.post(() -> { try { a.startActivity(android.content.Intent.createChooser(s, "Delen")); } catch (Exception ignored) { } });
    }
}
