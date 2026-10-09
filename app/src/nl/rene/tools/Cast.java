package nl.rene.tools;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Eigen Chromecast-koppeling (zonder Google-bibliotheek): zoeken in het eigen wifi-netwerk (mDNS,
 * "_googlecast._tcp") en afspelen via de standaard mediaspeler van de Chromecast (Cast v2, zie CastProto).
 *
 * Wat er naar de Chromecast gaat: het adres van de stream of aflevering, de titel en het hoesje. De Chromecast
 * haalt de audio zelf op. Er wordt alleen verbonden met apparaten die in het eigen netwerk gevonden zijn
 * (privé-adressen); hun certificaat is door het apparaat zelf ondertekend en wordt daarom niet gecontroleerd.
 */
final class Cast {
    private Cast() { }

    static final String NS_CONN = "urn:x-cast:com.google.cast.tp.connection", NS_HEART = "urn:x-cast:com.google.cast.tp.heartbeat",
            NS_RECV = "urn:x-cast:com.google.cast.receiver", NS_MEDIA = "urn:x-cast:com.google.cast.media";
    static final String APP_DEFAULT = "CC1AD845"; // de standaard mediaspeler van Google
    static final String SENDER = "sender-rt", RECEIVER = "receiver-0";

    // ---------- zoeken ----------

    static final class Device {
        String id = "", name = "", model = "", host = "";
        int port = 8009, seen;
        JSONObject json() {
            try { return new JSONObject().put("id", id).put("name", name).put("model", model); } catch (Exception e) { return new JSONObject(); }
        }
    }

    /** Gevonden apparaten (op id). */
    static final Map<String, Device> FOUND = new ConcurrentHashMap<>();

    /** Zoek ms milliseconden; daarna done (op een achtergrondthread) met wat er gevonden is. */
    static volatile int scan;

    static void discover(Context c, int ms, Runnable done) {
        scan++;
        final NsdManager nsd = (NsdManager) c.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        if (nsd == null) { done.run(); return; }
        final Finder f = new Finder(nsd);
        try { nsd.discoverServices("_googlecast._tcp", NsdManager.PROTOCOL_DNS_SD, f); }
        catch (Exception e) { done.run(); return; }
        new Thread(() -> {
            try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
            try { nsd.stopServiceDiscovery(f); } catch (Exception ignored) { }
            // Nog even wachten op lopende adres-opvragingen
            try { Thread.sleep(400); } catch (InterruptedException ignored) { }
            done.run();
        }, "cast-scan").start();
    }

    /** mDNS-zoeker: elk gevonden apparaat wordt (één tegelijk) opgevraagd voor adres en naam. */
    static final class Finder implements NsdManager.DiscoveryListener {
        final NsdManager nsd;
        final List<NsdServiceInfo> todo = new ArrayList<>();
        boolean busy;
        Finder(NsdManager nsd) { this.nsd = nsd; }

        @Override public void onDiscoveryStarted(String t) { }
        @Override public void onDiscoveryStopped(String t) { }
        @Override public void onStartDiscoveryFailed(String t, int e) { }
        @Override public void onStopDiscoveryFailed(String t, int e) { }
        @Override public void onServiceLost(NsdServiceInfo i) { }
        @Override public void onServiceFound(NsdServiceInfo i) { synchronized (this) { todo.add(i); } next(); }

        @SuppressWarnings("deprecation")
        void next() {
            NsdServiceInfo i;
            synchronized (this) {
                if (busy || todo.isEmpty()) return;
                busy = true;
                i = todo.remove(0);
            }
            try { nsd.resolveService(i, new Resolver(this)); }
            catch (Exception e) { synchronized (this) { busy = false; } next(); }
        }

        void resolved() { synchronized (this) { busy = false; } next(); }
    }

    static final class Resolver implements NsdManager.ResolveListener {
        final Finder f;
        Resolver(Finder f) { this.f = f; }
        @Override public void onResolveFailed(NsdServiceInfo i, int e) { f.resolved(); }
        @SuppressWarnings("deprecation")
        @Override public void onServiceResolved(NsdServiceInfo i) {
            try {
                InetAddress a = null;
                java.util.List<InetAddress> all = new ArrayList<>();
                if (android.os.Build.VERSION.SDK_INT >= 34) all.addAll(i.getHostAddresses());
                if (i.getHost() != null) all.add(i.getHost());
                for (InetAddress x : all) { // het liefst een privé IPv4-adres
                    if (!localAddress(x)) continue;
                    if (a == null || (x instanceof java.net.Inet4Address && !(a instanceof java.net.Inet4Address))) a = x;
                }
                if (a != null) {
                    Device d = new Device();
                    Map<String, byte[]> at = i.getAttributes();
                    d.id = attr(at, "id");
                    if (d.id.isEmpty()) d.id = i.getServiceName();
                    d.name = attr(at, "fn");
                    if (d.name.isEmpty()) d.name = i.getServiceName();
                    d.model = attr(at, "md");
                    d.host = a.getHostAddress();
                    d.port = i.getPort() > 0 ? i.getPort() : 8009;
                    d.seen = scan;
                    // Alleen apparaten die audio/video kunnen (ca = capabilities; bit 0 = video-uit, bit 2 = audio-uit)
                    FOUND.put(d.id, d);
                }
            } catch (Exception ignored) { }
            f.resolved();
        }
    }

    private static String attr(Map<String, byte[]> m, String k) {
        byte[] v = m == null ? null : m.get(k);
        return v == null ? "" : new String(v, java.nio.charset.StandardCharsets.UTF_8).trim();
    }

    /** Alleen het eigen netwerk (privé-adressen), nooit internet. */
    static boolean localAddress(InetAddress a) {
        return a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isLoopbackAddress()
                || (a instanceof java.net.Inet6Address && (a.getAddress()[0] & 0xfe) == 0xfc); // fc00::/7
    }

    static JSONArray devicesJson() {
        JSONArray a = new JSONArray();
        for (Device d : FOUND.values()) if (d.seen == scan) a.put(d.json()); // alleen wat er nu (nog) is
        return a;
    }

    // ---------- de sessie ----------

    /** Wat er op de Chromecast speelt (radio of aflevering). */
    static final class Item {
        String src = Player.PODCAST, url = "", type = "", title = "", sub = "", art = "", key = "";
        long startMs, durMs;
        boolean live;
        JSONObject ep, pod, station;
    }

    static Item fromEpisode(Context c, JSONObject ep, JSONObject pod, boolean fromStart) {
        Item it = new Item();
        it.src = Player.PODCAST; it.ep = ep; it.pod = pod == null ? new JSONObject() : pod;
        it.url = ep.optString("url"); it.type = CastProto.contentType(it.url, ep.optString("type"), "");
        it.title = ep.optString("title", "Aflevering"); it.sub = it.pod.optString("title");
        it.art = ep.optString("image").isEmpty() ? it.pod.optString("image") : ep.optString("image");
        it.key = ep.optString("key");
        it.durMs = ep.optLong("dur") * 1000;
        JSONObject p = Podcasts.progressOf(c, it.key);
        it.startMs = fromStart || p == null || p.optBoolean("done") ? 0 : p.optLong("p");
        if (p != null && p.optLong("d") > 0) it.durMs = p.optLong("d");
        return it;
    }

    static Item fromStation(JSONObject st) {
        Item it = new Item();
        it.src = Player.RADIO; it.station = st; it.live = true;
        it.url = st.optString("url"); it.type = CastProto.contentType(it.url, "", st.optString("codec"));
        it.title = st.optString("name", "Radio"); it.sub = "Live"; it.art = st.optString("logo"); it.key = it.url;
        return it;
    }

    static volatile Session cur;

    static boolean active() { Session s = cur; return s != null && !s.closed; }

    /** Start op een apparaat; wat er nu op de telefoon speelt, gaat daarheen. Geeft "" of een melding. */
    static synchronized String start(Context c, String deviceId, Item it) {
        Device d = FOUND.get(deviceId);
        if (d == null) return "Dit apparaat is niet (meer) gevonden; zoek opnieuw";
        if (it == null || !Podcasts.httpUrl(it.url)) return "Er is niets om te casten";
        Session old = cur;
        if (old != null && !old.closed && old.transport != null && old.dev.id.equals(d.id)) {
            // Al verbonden met dit apparaat: alleen het nieuwe erheen
            if (Player.PODCAST.equals(it.src)) PodcastService.send(c, PodcastService.HANDOFF, null);
            else RadioService.send(c, RadioService.STOP, null);
            final Item fit = it;
            new Thread(() -> load(fit), "cast-load").start();
            return "";
        }
        Session s = new Session(c.getApplicationContext(), d, it);
        cur = s; // eerst de nieuwe, dan pas de oude dicht (de service blijft dan staan)
        if (old != null) { final Session o = old; new Thread(o::stopApp, "cast-stop").start(); } // het andere apparaat stopt
        // De telefoon zelf stopt (plek bewaard); vanaf nu bedient de speler de Chromecast
        if (Player.PODCAST.equals(it.src)) PodcastService.send(c, PodcastService.HANDOFF, null);
        else RadioService.send(c, RadioService.STOP, null);
        Player.prefs(c).edit().putString("src", it.src).apply();
        CastService.start(c);
        new Thread(s, "cast-" + d.name).start();
        return "";
    }

    static final class Session implements Runnable {
        final Context c;
        final Device dev;
        final long id = System.currentTimeMillis();
        volatile Item item;
        volatile boolean closed;
        volatile String state = "connecting", error = null; // connecting, buffering, playing, paused, idle, ended, error
        volatile String transport = null, sessionId = null;
        volatile int mediaSession = -1, loadReq = -1, finishedId = -1;
        volatile double time, dur, volume = -1, rate = 1;
        volatile long timeAt, lastMsg, sleepAt, savedAt;
        private final java.util.Set<Integer> stale = java.util.Collections.synchronizedSet(new java.util.HashSet<Integer>());
        private SSLSocket sock;
        private OutputStream out;
        private int req = 1;
        private android.os.PowerManager.WakeLock wl;
        private android.net.wifi.WifiManager.WifiLock wifi;
        private final java.util.concurrent.ScheduledExecutorService beat = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "cast-beat"));

        Session(Context c, Device d, Item it) { this.c = c; this.dev = d; this.item = it; }

        @Override public void run() {
            SSLSocket s = null;
            try {
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, new TrustManager[]{new TrustDevice()}, new java.security.SecureRandom());
                s = (SSLSocket) ctx.getSocketFactory().createSocket();
                s.connect(new InetSocketAddress(dev.host, dev.port), 8000);
                s.setSoTimeout(0);
                s.startHandshake();
                synchronized (this) {
                    if (closed) { s.close(); return; } // intussen gestopt: niets meer op de tv starten
                    sock = s; out = s.getOutputStream();
                    hold();
                }
                lastMsg = System.currentTimeMillis();
                send(RECEIVER, NS_CONN, new JSONObject().put("type", "CONNECT").put("userAgent", "RenesTools").put("origin", new JSONObject()));
                send(RECEIVER, NS_RECV, new JSONObject().put("type", "LAUNCH").put("appId", APP_DEFAULT).put("requestId", nextReq()));
                try {
                    beat.scheduleWithFixedDelay(this::heartbeat, 5, 5, java.util.concurrent.TimeUnit.SECONDS);
                    // Geen antwoord op het starten: niet eeuwig "Verbinden…"
                    beat.schedule(() -> { if (!closed && transport == null) fail(dev.name + " reageert niet"); }, 15, java.util.concurrent.TimeUnit.SECONDS);
                } catch (java.util.concurrent.RejectedExecutionException e) { return; }
                InputStream in = s.getInputStream();
                while (!closed) {
                    CastProto.Msg m = CastProto.read(in);
                    lastMsg = System.currentTimeMillis();
                    onMessage(m);
                }
            } catch (Exception e) {
                if (!closed) fail("Verbinding met " + dev.name + " verbroken");
            } finally {
                if (closed) { if (s != null) try { s.close(); } catch (Exception ignored) { } release(); }
            }
        }

        private void fail(String msg) { error = msg; state = "error"; close(false); }

        /**
         * Telefoon en wifi wakker houden zolang er op de Chromecast iets speelt of een slaaptimer loopt (Hierna, de
         * timer en de verbinding zelf). Gepauzeerd of afgelopen: los (scheelt accu).
         */
        private synchronized void hold() {
            if (closed) return;
            try {
                if (wl == null) { wl = ((android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE)).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "renestools:cast"); wl.setReferenceCounted(false); }
                if (!wl.isHeld()) wl.acquire(3 * 3600_000L);
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) c.getSystemService(Context.WIFI_SERVICE);
                if (wifi == null && wm != null) { wifi = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "renestools:cast"); wifi.setReferenceCounted(false); }
                if (wifi != null && !wifi.isHeld()) wifi.acquire();
            } catch (Exception ignored) { }
        }

        private synchronized void release() {
            try { if (wl != null && wl.isHeld()) wl.release(); } catch (Exception ignored) { }
            try { if (wifi != null && wifi.isHeld()) wifi.release(); } catch (Exception ignored) { }
        }

        private volatile long quietSince;

        /** Na elke verandering: sloten vast of los; na een half uur stil de verbinding loslaten. */
        private void syncLocks() {
            boolean busy = "playing".equals(state) || "buffering".equals(state) || "connecting".equals(state) || sleepAt > 0;
            if (busy) { quietSince = 0; hold(); }
            else { release(); if (quietSince == 0) quietSince = System.currentTimeMillis(); }
        }

        synchronized int nextReq() { return ++req; }

        void send(String dest, String ns, JSONObject payload) throws IOException {
            OutputStream o;
            synchronized (this) { o = out; }
            if (o == null || (closed && !NS_CONN.equals(ns) && !NS_RECV.equals(ns))) throw new IOException("Niet verbonden");
            synchronized (o) { CastProto.write(o, new CastProto.Msg(SENDER, dest, ns, payload.toString())); }
        }

        private void heartbeat() {
            try {
                if (System.currentTimeMillis() - lastMsg > 20_000) throw new IOException("Geen antwoord");
                send(RECEIVER, NS_HEART, new JSONObject().put("type", "PING"));
                if (transport != null && mediaSession >= 0) media(new JSONObject().put("type", "GET_STATUS"));
                tickSleep();
                saveProgress(false);
                syncLocks();
                if (quietSince > 0 && System.currentTimeMillis() - quietSince > 30 * 60_000L) { close(true); return; } // half uur niets: loslaten
            } catch (Exception e) {
                if (!closed) fail("Verbinding met " + dev.name + " verbroken");
            }
        }

        private void onMessage(CastProto.Msg m) throws Exception {
            JSONObject p;
            try { p = new JSONObject(m.payload); } catch (Exception e) { return; }
            String type = p.optString("type");
            if (NS_HEART.equals(m.ns)) { if ("PING".equals(type)) send(m.source, NS_HEART, new JSONObject().put("type", "PONG")); return; }
            if (NS_CONN.equals(m.ns) && "CLOSE".equals(type)) {
                if (m.source.equals(transport) || RECEIVER.equals(m.source)) { saveProgress(true); error = "De Chromecast is gestopt"; state = "ended"; close(false); }
                return;
            }
            if (NS_RECV.equals(m.ns) && ("LAUNCH_ERROR".equals(type) || ("INVALID_REQUEST".equals(type) && transport == null))) {
                fail(dev.name + " kan de speler niet starten" + (p.optString("reason").isEmpty() ? "" : " (" + p.optString("reason") + ")"));
                return;
            }
            if (NS_RECV.equals(m.ns) && "RECEIVER_STATUS".equals(type)) {
                JSONObject st = p.optJSONObject("status");
                if (st == null) return;
                JSONObject vol = st.optJSONObject("volume");
                if (vol != null) volume = vol.optDouble("level", volume);
                JSONArray apps = st.optJSONArray("applications");
                JSONObject our = null;
                boolean other = false;
                if (apps != null) for (int i = 0; i < apps.length(); i++) {
                    JSONObject a = apps.optJSONObject(i);
                    if (a == null) continue;
                    if (APP_DEFAULT.equals(a.optString("appId"))) our = a;
                    else if (!a.optBoolean("isIdleScreen") && !"E8C28D3C".equals(a.optString("appId"))) other = true; // Backdrop = rustscherm
                }
                if (our == null) {
                    if (transport != null) { saveProgress(true); error = other ? "Op " + dev.name + " is iets anders gestart" : "De Chromecast is gestopt"; state = "ended"; close(false); }
                    return;
                }
                String t = our.optString("transportId");
                if (transport == null || !t.equals(transport)) {
                    // Eerste keer, of een andere app/telefoon heeft de speler opnieuw gestart: daarmee verbinden
                    boolean first = transport == null;
                    transport = t;
                    sessionId = our.optString("sessionId");
                    send(transport, NS_CONN, new JSONObject().put("type", "CONNECT").put("userAgent", "RenesTools").put("origin", new JSONObject()));
                    if (first) load(item);
                    else {
                        if (mediaSession >= 0) stale.add(mediaSession);
                        mediaSession = -1;
                        send(transport, NS_MEDIA, new JSONObject().put("type", "GET_STATUS").put("requestId", nextReq()));
                    }
                }
                Player.changed(c);
                return;
            }
            if (NS_MEDIA.equals(m.ns)) {
                if ("MEDIA_STATUS".equals(type)) {
                    JSONArray a = p.optJSONArray("status");
                    JSONObject s = a == null ? null : a.optJSONObject(0);
                    if (s == null) return;
                    int id = s.optInt("mediaSessionId", -1);
                    if (id >= 0 && stale.contains(id)) return; // nog over wat hiervoor speelde
                    if (id >= 0) mediaSession = id;
                    if (s.has("currentTime")) { time = s.optDouble("currentTime"); timeAt = System.currentTimeMillis(); }
                    JSONObject media = s.optJSONObject("media");
                    if (media != null && media.optDouble("duration", 0) > 0) dur = media.optDouble("duration");
                    rate = s.optDouble("playbackRate", rate);
                    String ps = s.optString("playerState");
                    if ("PLAYING".equals(ps)) { state = "playing"; error = null; }
                    else if ("PAUSED".equals(ps)) state = "paused";
                    else if ("BUFFERING".equals(ps) || "LOADING".equals(ps)) state = "buffering";
                    else if ("IDLE".equals(ps)) onIdle(s.optString("idleReason"), id);
                    syncLocks();
                    Player.changed(c);
                } else if ("LOAD_FAILED".equals(type) || (("INVALID_REQUEST".equals(type) || "LOAD_CANCELLED".equals(type)) && p.optInt("requestId", -2) == loadReq)) {
                    if ("LOAD_CANCELLED".equals(type)) return; // vervangen door een nieuwere
                    error = "Deze stream kan " + dev.name + " niet afspelen"; state = "error";
                    Player.changed(c);
                }
                // Andere fouten (bijv. pauze bij live radio) negeren: het geluid speelt gewoon door
            }
        }

        private void onIdle(String reason, int id) {
            if ("FINISHED".equals(reason)) {
                if (id >= 0 && id == finishedId) return; // al afgehandeld
                finishedId = id;
                Item it = item;
                state = "ended";
                if (id >= 0) stale.add(id);
                mediaSession = -1;
                if (Player.PODCAST.equals(it.src)) {
                    Podcasts.saveProgress(c, it.key, 0, Math.round(dur * 1000), true);
                    if (sleepAt == -1) { sleepAt = 0; return; } // "na deze aflevering"
                    JSONObject nx = Podcasts.queuePop(c); // Hierna: de volgende op de Chromecast
                    if (nx != null && nx.optJSONObject("ep") != null) { try { load(fromEpisode(c, nx.getJSONObject("ep"), nx.optJSONObject("pod"), false)); } catch (Exception ignored) { } }
                }
            } else if ("ERROR".equals(reason)) { error = "Afspelen op " + dev.name + " lukt niet"; state = "error"; }
            else if ("CANCELLED".equals(reason) || "INTERRUPTED".equals(reason)) { if (!"connecting".equals(state) && !"buffering".equals(state)) state = "idle"; }
        }

        /** Iets nieuws afspelen in deze sessie. */
        void load(Item it) throws Exception {
            saveProgress(true);
            if (mediaSession >= 0) stale.add(mediaSession);
            item = it;
            mediaSession = -1; time = it.startMs / 1000.0; timeAt = System.currentTimeMillis(); dur = it.durMs / 1000.0; state = "buffering"; error = null;
            JSONObject meta = new JSONObject().put("metadataType", it.live ? 0 : 3).put("title", it.title);
            if (!it.live) meta.put("artist", it.sub).put("albumName", it.sub); else meta.put("subtitle", "Radio");
            String art = Art.norm(it.art);
            if (Art.usable(art)) meta.put("images", new JSONArray().put(new JSONObject().put("url", art)));
            JSONObject media = new JSONObject().put("contentId", it.url).put("contentUrl", it.url).put("contentType", it.type)
                    .put("streamType", it.live ? "LIVE" : "BUFFERED").put("metadata", meta);
            loadReq = nextReq();
            JSONObject o = new JSONObject().put("type", "LOAD").put("requestId", loadReq).put("sessionId", sessionId).put("media", media)
                    .put("autoplay", true).put("currentTime", it.live ? 0 : it.startMs / 1000.0);
            if (!it.live) o.put("playbackRate", (double) PodcastService.clampSpeed(Podcasts.prefs(c).getFloat("speed", 1f))); // zelfde snelheid als op de telefoon
            send(transport, NS_MEDIA, o);
            if (Player.PODCAST.equals(it.src) && it.ep != null) {
                // Zo kan je straks op de telefoon verder (Verder luisteren, de mini-speler)
                Podcasts.prefs(c).edit().putString("last", new JSONObject().put("ep", it.ep).put("pod", it.pod).toString()).apply();
                Podcasts.touchRecent(c, it.ep, it.pod);
                Podcasts.queueRemove(c, it.key);
            } else if (it.station != null) Radio.prefs(c).edit().putString("last", it.station.toString()).apply();
            Player.prefs(c).edit().putString("src", it.src).apply();
            Player.changed(c);
        }

        void media(JSONObject o) throws Exception {
            if (transport == null || mediaSession < 0) return;
            o.put("requestId", nextReq()).put("mediaSessionId", mediaSession);
            send(transport, NS_MEDIA, o);
        }

        /** Geschatte plek nu (de Chromecast meldt hem niet elke seconde). */
        long posMs() {
            double t = time;
            if ("playing".equals(state)) t += (System.currentTimeMillis() - timeAt) / 1000.0 * (rate > 0 ? rate : 1);
            if (dur > 0) t = Math.min(t, dur);
            return Math.max(0, Math.round(t * 1000));
        }

        void saveProgress(boolean now) {
            Item it = item;
            if (it == null || !Player.PODCAST.equals(it.src) || it.key.isEmpty() || mediaSession < 0) return;
            if (!now && System.currentTimeMillis() - savedAt < 10_000) return;
            if ("ended".equals(state)) return;
            savedAt = System.currentTimeMillis();
            Podcasts.saveProgress(c, it.key, posMs(), Math.round(dur * 1000), false);
        }

        private void tickSleep() throws Exception {
            long s = sleepAt;
            if (s > 0 && System.currentTimeMillis() >= s) {
                sleepAt = 0;
                if (item != null && item.live) stopApp(); else { media(new JSONObject().put("type", "PAUSE")); state = "paused"; saveProgress(true); }
                Player.changed(c);
            }
        }

        void stopApp() {
            saveProgress(true); // eerst de plek (daarna telt "afgelopen")
            try { if (sessionId != null) send(RECEIVER, NS_RECV, new JSONObject().put("type", "STOP").put("sessionId", sessionId).put("requestId", nextReq())); }
            catch (Exception ignored) { }
            state = "ended";
            close(true);
        }

        /** Verbinding dicht. fromUser: netjes afgesloten (geen foutmelding). */
        void close(boolean fromUser) {
            SSLSocket s;
            synchronized (this) {
                if (closed) return;
                closed = true;
                s = sock;
            }
            if (!"ended".equals(state)) { Item it = item; if (it != null && Player.PODCAST.equals(it.src) && mediaSession >= 0 && !it.key.isEmpty())
                Podcasts.saveProgress(c, it.key, posMs(), Math.round(dur * 1000), false); }
            try { if (transport != null) send(transport, NS_CONN, new JSONObject().put("type", "CLOSE")); } catch (Exception ignored) { }
            try { send(RECEIVER, NS_CONN, new JSONObject().put("type", "CLOSE")); } catch (Exception ignored) { }
            beat.shutdownNow();
            try { if (s != null) s.close(); } catch (Exception ignored) { }
            release();
            synchronized (Cast.class) { if (cur == this && fromUser) cur = null; }
            CastService.stop(c); // stopt alleen als er geen andere sessie actief is
            Player.changed(c);
        }
    }

    /** Chromecasts hebben een eigen, door het apparaat zelf ondertekend certificaat (alleen in het eigen netwerk). */
    static final class TrustDevice implements X509TrustManager {
        @Override public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String auth) { }
        @Override public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String auth) { }
        @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
    }

    // ---------- bediening ----------

    static Session session() { Session s = cur; return s != null && !s.closed ? s : null; }

    static void playPause() {
        Session s = session(); if (s == null) return;
        try {
            if ("ended".equals(s.state) || "idle".equals(s.state) || "error".equals(s.state)) {
                Item it = s.item;
                it.startMs = it.live || "ended".equals(s.state) ? 0 : s.posMs(); // afgelopen: opnieuw vanaf het begin
                s.load(it); return;
            }
            if (s.mediaSession < 0) return; // nog aan het laden/verbinden: niets te pauzeren
            boolean playing = "playing".equals(s.state) || "buffering".equals(s.state);
            s.media(new JSONObject().put("type", playing ? "PAUSE" : "PLAY"));
            s.state = playing ? "paused" : "buffering";
            s.time = s.posMs() / 1000.0; s.timeAt = System.currentTimeMillis();
            s.saveProgress(true);
        } catch (Exception ignored) { }
        Player.changed(s.c);
    }

    /** Expliciet pauzeren (false) of verder (true). */
    static void setPlaying(boolean play) {
        Session s = session(); if (s == null) return;
        boolean playing = "playing".equals(s.state) || "buffering".equals(s.state);
        if (playing != play) playPause();
    }

    /** Na een verbroken verbinding: de melding is gezien, de speler gaat terug naar de telefoon. */
    static void forget() { Session s = cur; if (s != null && s.closed) cur = null; }

    static void seek(long ms) {
        Session s = session(); if (s == null || s.item.live) return;
        try {
            double t = Math.max(0, ms / 1000.0);
            if (s.dur > 0) t = Math.min(t, Math.max(0, s.dur - 1));
            s.media(new JSONObject().put("type", "SEEK").put("currentTime", t));
            s.time = t; s.timeAt = System.currentTimeMillis();
            s.saveProgress(true);
        } catch (Exception ignored) { }
        Player.changed(s.c);
    }

    static void seekBy(int sec) { Session s = session(); if (s != null) seek(s.posMs() + sec * 1000L); }

    static void load(Item it) { Session s = session(); if (s == null) return; try { s.load(it); } catch (Exception e) { s.error = "Afspelen op " + s.dev.name + " lukt niet"; } }

    static void setVolume(double level) {
        Session s = session(); if (s == null) return;
        double v = Math.max(0, Math.min(1, level));
        try { s.send(RECEIVER, NS_RECV, new JSONObject().put("type", "SET_VOLUME").put("volume", new JSONObject().put("level", v)).put("requestId", s.nextReq())); s.volume = v; }
        catch (Exception ignored) { }
    }

    static void setRate(double r) {
        Session s = session(); if (s == null || s.item.live) return;
        try { s.media(new JSONObject().put("type", "SET_PLAYBACK_RATE").put("playbackRate", r)); s.rate = r; } catch (Exception ignored) { }
    }

    /** minuten > 0, -1 = einde van de aflevering, 0 = uit. */
    static void sleep(int minutes) {
        Session s = session(); if (s == null) return;
        s.sleepAt = minutes > 0 ? System.currentTimeMillis() + minutes * 60_000L : minutes == -1 ? -1 : 0;
        Player.changed(s.c);
    }

    /** Stoppen op de Chromecast. */
    static void stop() {
        Session s = cur;
        if (s == null) return;
        s.stopApp();
        synchronized (Cast.class) { if (cur == s) cur = null; } // een intussen gestarte nieuwe sessie blijft
    }

    /** Voor de speler (mini-balk, widget, zwevend venster). */
    static Player.Now now() {
        Session s = cur;
        if (s == null) return null;
        Item it = s.item;
        Player.Now n = new Player.Now();
        n.src = it.src; n.cast = s.dev.name; n.castState = s.closed ? "closed" : "open";
        n.status = s.closed ? ("error".equals(s.state) ? "error" : "stopped") : "buffering".equals(s.state) || "connecting".equals(s.state) ? "connecting" : s.state;
        n.playing = !s.closed && ("playing".equals(s.state) || "buffering".equals(s.state) || "connecting".equals(s.state));
        n.active = !s.closed;
        n.live = !s.closed;
        n.title = it.title;
        n.sub = s.error != null ? s.error : "connecting".equals(s.state) ? "Verbinden met " + s.dev.name + "…" : "Op " + s.dev.name + (it.sub.isEmpty() ? "" : " · " + it.sub);
        n.art = it.art; n.key = it.key;
        n.pos = it.live ? 0 : s.posMs(); n.dur = it.live ? 0 : Math.round(s.dur * 1000);
        n.sleepAt = Math.max(0, s.sleepAt);
        n.sleepEnd = s.sleepAt == -1;
        n.ep = it.ep; n.pod = it.pod; n.station = it.station;
        n.volume = s.volume; n.rate = s.rate;
        return n;
    }
}
