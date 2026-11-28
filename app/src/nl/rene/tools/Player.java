package nl.rene.tools;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

/**
 * De ene speler voor radio en podcasts. Er speelt er altijd hooguit één: wie begint, neemt het over (en ook een
 * lopende slaaptimer). Onthoudt wat het laatst speelde, en geeft de widget en het zwevende venster één beeld van
 * wat er speelt (Now) en één set knoppen (playPause, step, skip, stop).
 */
final class Player {
    private Player() { }

    static final String RADIO = "radio", PODCAST = "podcast";

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("player", Context.MODE_PRIVATE); }

    static String src(Context c) { return prefs(c).getString("src", ""); }

    /**
     * src begint te spelen: de andere stoppen. Geeft de slaaptimer van de andere terug (tijdstip in ms, 0 = geen),
     * zodat die gewoon doorloopt.
     */
    static long takeOver(Context c, String src) {
        if (!src.equals(src(c))) prefs(c).edit().putString("src", src).apply();
        if (Cast.active()) new Thread(App.veilig(Cast::stop), "cast-stop").start(); // weer op de telefoon: de Chromecast stopt
        long now = System.currentTimeMillis(), sleep = 0;
        if (PODCAST.equals(src)) {
            String st = RadioService.status;
            if (RadioService.inst != null && !"stopped".equals(st)) {
                if (RadioService.sleepAt > now + 30_000) sleep = RadioService.sleepAt;
                RadioService.send(c, RadioService.STOP, null); // de radio laat je niet op de achtergrond doorbufferen
            }
        } else if (RADIO.equals(src)) {
            if (PodcastService.inst != null) {
                if (PodcastService.sleepAt > now + 30_000) sleep = PodcastService.sleepAt;
                else if (PodcastService.sleepEnd && PodcastService.dur > 0) {
                    // "Na deze aflevering" kan de radio niet: dan stopt hij op het moment dat de aflevering af zou zijn
                    long left = (long) ((PodcastService.dur - PodcastService.pos) / Math.max(0.5f, PodcastService.speed));
                    if (left > 30_000) sleep = now + left;
                }
                PodcastService.send(c, PodcastService.YIELD, null); // pauzeren, plek bewaren, melding weg
            }
        }
        changed(c);
        return sleep;
    }

    // ---------- wat er speelt ----------

    /** Eén beeld van de speler (voor de widget en het zwevende venster). */
    static final class Now {
        String src = "", status = "stopped", title = "", sub = "", art = "", key = "";
        boolean playing, active, shift, atLive = true, sleepEnd;
        /** Speelt op een Chromecast: de naam (anders null). */
        String cast;
        String castState;
        double volume = -1, rate = 1;
        /** De service draait nog (gepauzeerd kan dan meteen verder); false = opnieuw opgebouwd uit wat bewaard is. */
        boolean live;
        long pos, dur, sleepAt;
        JSONObject ep, pod, station;
    }

    static Now now(Context c) {
        if (Cast.active()) { Now k = Cast.now(); if (k != null) return k; } // speelt op een Chromecast
        String src = src(c);
        Now p = podcastNow(c), r = radioNow(c);
        if (p != null && p.playing) return p; // wat speelt, gaat voor
        if (r != null && r.playing) return r;
        // Anders wat het laatst speelde (ook als dat gestopt is: de widget toont het, ▶ start het weer)
        if (RADIO.equals(src)) return r != null ? r : p;
        return p != null ? p : r;
    }

    static Now podcastNow(Context c) {
        try {
            Now n = new Now();
            n.src = PODCAST;
            String e = PodcastService.ep, pd = PodcastService.pod;
            if (e != null) {
                n.ep = new JSONObject(e); n.pod = pd == null ? new JSONObject() : new JSONObject(pd);
                n.status = PodcastService.status; n.pos = PodcastService.pos; n.dur = PodcastService.dur; n.sleepAt = PodcastService.sleepAt;
                if (PodcastService.inst == null && ("playing".equals(n.status) || "connecting".equals(n.status))) n.status = "paused";
                n.live = PodcastService.inst != null;
            } else {
                String last = Podcasts.prefs(c).getString("last", null);
                if (last == null) return null;
                JSONObject o = new JSONObject(last);
                n.ep = o.getJSONObject("ep"); n.pod = o.optJSONObject("pod") == null ? new JSONObject() : o.getJSONObject("pod");
                JSONObject pr = Podcasts.progressOf(c, n.ep.optString("key"));
                if (pr != null && pr.optBoolean("done")) return null;
                n.status = "paused"; n.pos = pr == null ? 0 : pr.optLong("p"); n.dur = pr == null ? n.ep.optLong("dur") * 1000 : pr.optLong("d");
            }
            n.playing = "playing".equals(n.status) || "connecting".equals(n.status);
            n.active = !"stopped".equals(n.status) && !"ended".equals(n.status);
            n.title = n.ep.optString("title", "Aflevering");
            n.sub = "connecting".equals(n.status) ? "Laden…" : "error".equals(n.status) ? (PodcastService.error == null ? "Fout" : PodcastService.error) : n.pod.optString("title");
            n.art = n.ep.optString("image").isEmpty() ? n.pod.optString("image") : n.ep.optString("image");
            n.key = n.ep.optString("key");
            return n;
        } catch (Exception e) { return null; }
    }

    static Now radioNow(Context c) {
        try {
            Now n = new Now();
            n.src = RADIO;
            String st = RadioService.station;
            boolean live = st != null;
            if (st == null) st = Radio.prefs(c).getString("last", null);
            if (st == null) return null;
            n.station = new JSONObject(st);
            String status = live ? RadioService.status : "stopped";
            if ("interrupted".equals(status)) status = "paused";
            n.status = status;
            n.playing = "playing".equals(status) || "connecting".equals(status);
            n.active = live && !"stopped".equals(status);
            n.live = live && RadioService.inst != null;
            String name = n.station.optString("name", "Radio"), song = "playing".equals(status) ? RadioService.title : "";
            n.title = song == null || song.isEmpty() ? name : song;
            n.sub = "connecting".equals(status) ? "Verbinden…" : "paused".equals(status) ? "Gepauzeerd · " + name : "error".equals(status) ? "Zender niet te bereiken"
                    : "stopped".equals(status) ? "Tik op ▶ om te luisteren" : song == null || song.isEmpty() ? "Live" : name;
            n.art = n.station.optString("logo");
            n.key = n.station.optString("url");
            n.sleepAt = RadioService.sleepAt;
            n.shift = RadioService.shiftOn;
            n.atLive = !RadioService.shiftOn || RadioService.shiftBehind <= 8;
            return n;
        } catch (Exception e) { return null; }
    }

    // ---------- knoppen (app, widget, zwevend venster) ----------

    static void playPause(Context c) {
        if (Cast.active()) { Cast.playPause(); return; }
        Now n = now(c);
        if (n == null) return;
        if (RADIO.equals(n.src)) {
            if (n.playing) RadioService.send(c, RadioService.PAUSE, null);
            else if (RadioService.station == null) { RadioService.send(c, RadioService.PLAY, n.station.toString()); }
            else RadioService.send(c, RadioService.RESUME, null);
        } else {
            PodcastService.send(c, n.playing ? PodcastService.PAUSE : PodcastService.RESUME, null);
        }
    }

    static void stop(Context c) {
        if (Cast.active()) { Cast.stop(); changed(c); return; }
        Now n = now(c);
        if (n == null) return;
        if (RADIO.equals(n.src)) RadioService.send(c, RadioService.STOP, null); else PodcastService.send(c, PodcastService.STOP, null);
    }

    /** −/+ seconden: podcast 15 terug / 30 vooruit, radio 30 in de buffer. */
    static void skip(Context c, int dir) {
        if (Cast.active()) { Cast.seekBy(dir < 0 ? -PodcastService.BACK_S : PodcastService.FWD_S); return; }
        Now n = now(c);
        if (n == null) return;
        if (RADIO.equals(n.src)) { if (RadioService.inst != null) RadioService.send(c, dir < 0 ? RadioService.REW : RadioService.FWD, null); return; }
        if (PodcastService.inst != null) PodcastService.send(c, PodcastService.SKIP, String.valueOf(dir < 0 ? -PodcastService.BACK_S : PodcastService.FWD_S));
        else podSeek(c, Math.max(0, n.pos + (dir < 0 ? -PodcastService.BACK_S : PodcastService.FWD_S) * 1000L));
    }

    /** Naar een plek in de podcast; als er niets speelt, wordt de plek bewaard voor straks. */
    static void podSeek(Context c, long ms) {
        if (Cast.active()) { Cast.seek(ms); return; }
        if (PodcastService.inst != null) { PodcastService.send(c, PodcastService.SEEK, String.valueOf(Math.max(0, ms))); return; }
        String cur = PodcastService.ep, last = Podcasts.prefs(c).getString("last", null);
        try {
            JSONObject e = cur != null ? new JSONObject(cur) : last != null ? new JSONObject(last).getJSONObject("ep") : null;
            if (e == null) return;
            JSONObject p = Podcasts.progressOf(c, e.optString("key"));
            long d = PodcastService.dur > 0 && cur != null ? PodcastService.dur : p != null && p.optLong("d") > 0 ? p.optLong("d") : e.optLong("dur") * 1000;
            long at = Math.max(0, d > 1000 ? Math.min(ms, d - 1000) : ms);
            // De service is weg maar weet de aflevering nog: ook daar de plek bijwerken (anders springt de schuif terug)
            if (cur != null) PodcastService.pos = at;
            Podcasts.saveProgress(c, e.optString("key"), at, d, false);
            changed(c);
        } catch (Exception ignored) { }
    }

    /**
     * Een stap terug (dir −1) of weer vooruit (1) door wat er gespeeld is: {ep, pod} of null. De stand (PodcastTerug)
     * staat in de instellingen van Podcasts, zodat het ook na het herstarten van de app klopt.
     */
    private static String stapKey;
    private static long stapT;

    static synchronized JSONObject stap(Context c, String nu, int dir) {
        try {
            org.json.JSONArray a = Podcasts.recent(c);
            java.util.List<String> keys = new java.util.ArrayList<>();
            java.util.Map<String, JSONObject> bij = new java.util.HashMap<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject r = a.optJSONObject(i), e = r == null ? null : r.optJSONObject("ep");
                String k = e == null ? "" : e.optString("key");
                if (k.isEmpty() || bij.containsKey(k)) continue;
                keys.add(k); bij.put(k, r);
            }
            android.content.SharedPreferences pr = Podcasts.prefs(c);
            long nuT = System.currentTimeMillis(), was = pr.getLong("terugT", 0);
            // Een rij van lang geleden telt niet meer (dan begint terug opnieuw en kiest de speler zelf wat er komt)
            PodcastTerug.Stand s = was > nuT || nuT - was > PodcastTerug.GELDIG_MS ? null : PodcastTerug.lees(pr.getString("terugRij", ""));
            // Snel achter elkaar: de speler is nog niet bij de vorige stap; reken vanaf daar
            if (s != null && stapKey != null && android.os.SystemClock.elapsedRealtime() - stapT < 2500 && s.klopt(stapKey)) nu = stapKey;
            PodcastTerug.Stap st = dir < 0 ? PodcastTerug.terug(keys, nu, s) : PodcastTerug.vooruit(keys, nu, s);
            if (st.key == null || bij.get(st.key) == null) return null;
            pr.edit().putString("terugRij", PodcastTerug.json(st.stand)).putLong("terugT", nuT).apply();
            stapKey = st.key; stapT = android.os.SystemClock.elapsedRealtime();
            JSONObject r = bij.get(st.key);
            return new JSONObject().put("ep", r.getJSONObject("ep")).put("pod", r.optJSONObject("pod") == null ? new JSONObject() : r.optJSONObject("pod")).put("nav", true);
        } catch (Exception e) { return null; }
    }

    /**
     * Terugspoelen aan het begin van een aflevering: verder in het einde van wat hiervoor speelde. Geeft false als dat
     * niet kan (niets eerder, of de lengte is onbekend); dan gebeurt er niets.
     */
    static synchronized boolean naarEindeVorige(Context c, String nu, long terugMs) {
        try {
            android.content.SharedPreferences pr = Podcasts.prefs(c);
            String was = pr.getString("terugRij", "");
            long wasT = pr.getLong("terugT", 0);
            JSONObject r = stap(c, nu, -1);
            if (r == null) return false;
            JSONObject e = r.getJSONObject("ep"), p = Podcasts.progressOf(c, e.optString("key"));
            long duur = p != null && p.optLong("d") > 0 ? p.optLong("d") : e.optLong("dur") * 1000L;
            long at = PodcastTerug.plekAanEinde(duur, terugMs);
            if (at < 0 || !Podcasts.httpUrl(e.optString("url"))) { pr.edit().putString("terugRij", was).putLong("terugT", wasT).apply(); stapKey = null; return false; }
            PodcastService.send(c, PodcastService.PLAY, r.put("at", at).toString());
            return true;
        } catch (Exception e) { return false; }
    }

    /** Volgende (dir 1) of vorige (−1) zender of aflevering. Geeft "" of een melding. */
    static synchronized String step(Context c, String src, int dir) {
        try {
            if (src == null || src.isEmpty()) { Now n = now(c); src = n == null ? src(c) : n.src; }
            boolean cast = Cast.active();
            if (RADIO.equals(src)) {
                JSONObject s = RadioService.neighbour(c, dir);
                if (s == null) return "Zet zenders bij je favorieten (☆) om te wisselen";
                Radio.prefs(c).edit().putString("last", s.toString()).apply();
                if (cast) Cast.load(Cast.fromStation(s)); else RadioService.send(c, RadioService.PLAY, s.toString());
                return "";
            }
            Now n = cast ? Cast.now() : podcastNow(c);
            JSONObject ep = n == null ? null : n.ep, pod = n == null ? null : n.pod;
            if (dir < 0) {
                // Elke keer ⏮ een stap verder terug in wat er gespeeld is; de plek in deze aflevering blijft bewaard, en ⏭
                // gaat dezelfde weg weer vooruit (PodcastTerug). Is er niets eerder: terug naar het begin.
                JSONObject r = stap(c, ep == null ? "" : ep.optString("key"), -1);
                if (r == null) { if (ep != null) podSeek(c, 0); return ep == null ? "Er speelde nog niets" : ""; }
                if (cast) Cast.load(Cast.fromEpisode(c, r.getJSONObject("ep"), r.optJSONObject("pod"), false));
                else PodcastService.send(c, PodcastService.PLAY, r.toString());
                return "";
            }
            JSONObject nx = stap(c, ep == null ? "" : ep.optString("key"), 1); // na ⏮: eerst weer vooruit door wat er speelde
            if (nx == null) nx = Podcasts.next(c, ep, pod);
            if (nx == null) return "Geen volgende aflevering. Zet er een in Hierna via ⋯ bij een aflevering.";
            JSONObject ne = nx.optJSONObject("ep");
            if (ne != null) Podcasts.queueRemove(c, ne.optString("key"));
            if (cast && ne != null) Cast.load(Cast.fromEpisode(c, ne, nx.optJSONObject("pod"), false));
            else PodcastService.send(c, PodcastService.PLAY, nx.toString());
            return "";
        } catch (Exception e) { return "Dat lukt nu niet"; }
    }

    /** Korte melding buiten de app (bijv. na een knop op de widget). */
    static void toast(Context c, String msg) {
        final Context app = c.getApplicationContext();
        MAIN.post(() -> { try { android.widget.Toast.makeText(app, Taal.tx(app, msg), android.widget.Toast.LENGTH_SHORT).show(); } catch (Exception ignored) { } });
    }

    // ---------- bijwerken ----------

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable[] PENDING = new Runnable[1];

    /** Iets aan de speler veranderd: widget en zwevend venster bijwerken (kort samengevoegd). */
    static void changed(Context c) {
        final Context app = c.getApplicationContext();
        if (!App.unlocked(app)) return; // vóór de eerste ontgrendeling zijn de instellingen onleesbaar
        synchronized (PENDING) {
            if (PENDING[0] != null) return;
            PENDING[0] = () -> {
                synchronized (PENDING) { PENDING[0] = null; }
                try { RadioWidget.refresh(app); } catch (Throwable ignored) { }
                try { FloatPlayer.refresh(app); } catch (Throwable ignored) { }
                try { CastService.refresh(); } catch (Throwable ignored) { }
            };
            MAIN.postDelayed(PENDING[0], 250);
        }
    }
}
