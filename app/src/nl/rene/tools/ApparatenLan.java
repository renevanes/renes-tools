package nl.rene.tools;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Apparaten op je eigen wifi. Alleen op wifi (nooit via mobiele data: elke verbinding gaat via het wifi-netwerk zelf),
 * alleen binnen je eigen /24 (nooit daarbuiten) en niets naar internet:
 *  1. mDNS/NSD: apparaten die zichzelf aankondigen (Chromecast, AirPlay, printers, HomeKit, Sonos, computers …);
 *  2. SSDP/UPnP: M-SEARCH, daarna de beschrijving (XML) van het apparaat op het eigen netwerk;
 *  3. een voorzichtige rondgang langs de adressen (ping, anders één TCP-poging), 8 tegelijk met korte wachttijden;
 *  4. van wat antwoordde een handvol gangbare poorten (alleen kijken of er iets luistert: nooit inloggen of proberen);
 *  5. NetBIOS-naam (Windows-computers en NAS'en).
 * Het MAC-adres (en dus de maker) is vanaf Android 10 niet meer te lezen; dan komt de maker uit wat het apparaat zelf zegt.
 * Stoppen kan altijd (cancel).
 */
final class ApparatenLan {

    private ApparatenLan() { }

    static final class Host {
        final String ip;
        String name = "", mdns = "", fn = "", model = "", maker = "", upnpType = "", uuid = "", nb = "", mac = "";
        final Set<String> svcs = new TreeSet<>();
        final Set<Integer> ports = new TreeSet<>();
        boolean gateway, self, alive;
        Host(String ip) { this.ip = ip; }
    }

    /** Het wifi-netwerk waarop gezocht wordt. */
    static final class Net { Network net; int ip, prefix, gw; String ssid = ""; }

    static final Object L = new Object();
    /** Eén zoekronde met een eigen lijst en eigen stopknop: een oude ronde (of een draad die nog naloopt) schrijft nooit in de volgende. */
    static final class Scan {
        final Map<String, Host> hosts = new LinkedHashMap<>();
        final Net net;
        volatile boolean cancel;
        Scan(Net n) { net = n; }
    }
    static volatile Scan scan;
    static volatile boolean busy;
    static volatile int done, total;
    static volatile String phase = "", error = "", netName = "";
    static volatile long at;

    /** Gestopt, of al vervangen door een nieuwe ronde. */
    static boolean stopped(Scan sc) { return sc.cancel || sc != scan; }

    static final String[] TYPES = {"_googlecast._tcp", "_airplay._tcp", "_raop._tcp", "_ipp._tcp", "_printer._tcp", "_pdl-datastream._tcp",
            "_hap._tcp", "_spotify-connect._tcp", "_sonos._tcp", "_workstation._tcp", "_smb._tcp", "_companion-link._tcp",
            "_androidtvremote2._tcp", "_amzn-wplay._tcp", "_matter._tcp", "_http._tcp", "_ssh._tcp"};

    // ---------- het netwerk ----------

    /** Het wifi-netwerk (alleen wifi, nooit mobiel) met het eigen IPv4-adres, het voorvoegsel en de router. null = geen wifi. */
    static Net wifi(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc == null || !nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp == null) continue;
                Net r = new Net();
                r.net = n;
                for (LinkAddress la : lp.getLinkAddresses()) {
                    if (la.getAddress() instanceof Inet4Address) { r.ip = Apparaten.ipInt(la.getAddress().getHostAddress()); r.prefix = la.getPrefixLength(); }
                }
                for (RouteInfo ri : lp.getRoutes()) {
                    if (ri.isDefaultRoute() && ri.getGateway() instanceof Inet4Address) r.gw = Apparaten.ipInt(ri.getGateway().getHostAddress());
                }
                if (r.ip != 0) { r.ssid = ApparatenScan.ssid(c); return r; }
            }
        } catch (Throwable e) { App.log(c, "APPARATEN", "netwerk: " + e); }
        return null;
    }

    // ---------- starten en stoppen ----------

    /** Zoeken starten. "" of: "wifi" (geen wifi), "prive" (geen thuisnetwerk), "bezig". */
    static String start(Context c) {
        final Context ac = c.getApplicationContext();
        Net n = wifi(ac);
        if (n == null) return "wifi";
        if (!Apparaten.privateIp(n.ip)) return "prive";
        final Scan sc = new Scan(n);
        synchronized (L) {
            if (busy) return "bezig";
            busy = true; done = 0; total = 0; error = ""; phase = "Aankondigingen beluisteren"; scan = sc;
            netName = n.ssid;
        }
        new Thread(() -> {
            try { run(ac, sc); }
            catch (Throwable e) { error = "Zoeken ging mis"; App.log(ac, "APPARATEN", "netwerk zoeken: " + e); }
            finally { busy = false; phase = ""; at = System.currentTimeMillis(); }
        }, "apparaten-lan").start();
        return "";
    }

    static void stop() { Scan sc = scan; if (sc != null) sc.cancel = true; }

    private static Host host(Scan sc, String ip) {
        synchronized (L) {
            Host h = sc.hosts.get(ip);
            if (h == null) { h = new Host(ip); sc.hosts.put(ip, h); }
            return h;
        }
    }

    private static boolean own(Net n, String ip) {
        int v = Apparaten.ipInt(ip);
        return v != 0 && Apparaten.sameNet(n.ip, v, n.prefix);
    }

    private static void run(Context c, Scan sc) throws Exception {
        final Net n = sc.net;
        Host me = host(sc, Apparaten.ipStr(n.ip)); me.self = true; me.alive = true; me.name = "Deze telefoon";
        if (n.gw != 0 && Apparaten.sameNet(n.ip, n.gw, n.prefix)) { Host g = host(sc, Apparaten.ipStr(n.gw)); g.gateway = true; g.alive = true; }
        // 1 + 2: mDNS en SSDP tegelijk
        Thread ssdp = new Thread(() -> { try { ssdp(sc); } catch (Throwable e) { App.log(c, "APPARATEN", "ssdp: " + e); } }, "apparaten-ssdp");
        ssdp.start();
        mdns(c, sc);
        ssdp.join(10_000);
        if (stopped(sc)) return;
        // 3: rondgang
        phase = "Adressen langsgaan";
        int[] all = Apparaten.hosts(n.ip, n.prefix);
        total = all.length; done = 0;
        ExecutorService ex = Executors.newFixedThreadPool(8);
        for (int ip : all) {
            final int x = ip;
            ex.execute(() -> {
                try { if (!stopped(sc)) probe(sc, Apparaten.ipStr(x)); } catch (Throwable ignored) { }
                finally { done++; }
            });
        }
        ex.shutdown();
        while (!ex.awaitTermination(300, TimeUnit.MILLISECONDS)) if (stopped(sc)) { ex.shutdownNow(); return; }
        if (stopped(sc)) return;
        // 4: poorten van wat er is
        phase = "Diensten bekijken";
        List<Host> alive = new ArrayList<>();
        synchronized (L) { for (Host h : sc.hosts.values()) if (h.alive && !h.self) alive.add(h); }
        total = alive.size() * Apparaten.PORTS.length; done = 0;
        ExecutorService px = Executors.newFixedThreadPool(8);
        for (Host h : alive) for (int p : Apparaten.PORTS) {
            px.execute(() -> {
                try { if (!stopped(sc) && open(n, h.ip, p, 400)) synchronized (L) { h.ports.add(p); } } catch (Throwable ignored) { }
                finally { done++; }
            });
        }
        px.shutdown();
        while (!px.awaitTermination(300, TimeUnit.MILLISECONDS)) if (stopped(sc)) { px.shutdownNow(); return; }
        if (stopped(sc)) return;
        // 5: NetBIOS-namen en (alleen vóór Android 10) MAC-adressen
        phase = "Namen opvragen";
        netbios(sc, alive);
        arp(sc);
        if (!stopped(sc)) remember(c, sc);
    }

    // ---------- mDNS / NSD ----------

    /** Luisteraar voor één soort dienst (benoemde klasse). */
    static final class Disc implements NsdManager.DiscoveryListener {
        final List<NsdServiceInfo> found;
        Disc(List<NsdServiceInfo> f) { found = f; }
        @Override public void onStartDiscoveryFailed(String t, int code) { }
        @Override public void onStopDiscoveryFailed(String t, int code) { }
        @Override public void onDiscoveryStarted(String t) { }
        @Override public void onDiscoveryStopped(String t) { }
        @Override public void onServiceFound(NsdServiceInfo s) { synchronized (found) { if (found.size() < 200) found.add(s); } }
        @Override public void onServiceLost(NsdServiceInfo s) { }
    }

    /** Uitkomst van één keer opzoeken (benoemde klasse). */
    static final class Res implements NsdManager.ResolveListener {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile NsdServiceInfo info;
        volatile int error;
        @Override public void onResolveFailed(NsdServiceInfo s, int code) { error = code; latch.countDown(); }
        @Override public void onServiceResolved(NsdServiceInfo s) { info = s; latch.countDown(); }
    }

    /**
     * Eén dienst opzoeken. Vóór Android 14 mag er maar één tegelijk lopen (anders fout 3, FAILURE_ALREADY_ACTIVE):
     * daarom altijd op het antwoord wachten, en bij fout 3 even wachten en opnieuw. Vanaf Android 14 wordt een
     * opzoeking die te lang duurt netjes gestopt.
     */
    @SuppressWarnings("deprecation")
    private static NsdServiceInfo resolve(NsdManager nsd, NsdServiceInfo s, Scan sc) throws InterruptedException {
        for (int tries = 0; tries < 4 && !stopped(sc); tries++) {
            Res r = new Res();
            try { nsd.resolveService(s, r); } catch (Throwable e) { return null; }
            boolean answered = r.latch.await(3000, TimeUnit.MILLISECONDS);
            if (!answered) {
                if (Build.VERSION.SDK_INT >= 34) { try { nsd.stopServiceResolution(r); } catch (Throwable ignored) { } }
                else r.latch.await(4000, TimeUnit.MILLISECONDS); // de vorige moet klaar zijn voor de volgende mag
                return r.info;
            }
            if (r.info != null) return r.info;
            if (r.error != NsdManager.FAILURE_ALREADY_ACTIVE) return null;
            Thread.sleep(400);
        }
        return null;
    }

    /** Het IPv4-adres van een opgezochte dienst (vanaf Android 14 uit de lijst met adressen; die begint vaak met IPv6). */
    @SuppressWarnings("deprecation")
    private static String ipv4(NsdServiceInfo x) {
        try {
            if (Build.VERSION.SDK_INT >= 34) { for (InetAddress a : x.getHostAddresses()) if (a instanceof Inet4Address) return a.getHostAddress(); return null; }
            InetAddress a = x.getHost();
            return a instanceof Inet4Address ? a.getHostAddress() : null;
        } catch (Throwable e) { return null; }
    }

    private static void mdns(Context c, Scan sc) throws InterruptedException {
        final Net n = sc.net;
        NsdManager nsd = (NsdManager) c.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return;
        List<NsdServiceInfo> found = new ArrayList<>();
        // In groepjes van vier (Android staat maar een paar tegelijk toe)
        for (int i = 0; i < TYPES.length && !stopped(sc); i += 4) {
            List<Disc> ds = new ArrayList<>();
            for (int k = i; k < Math.min(TYPES.length, i + 4); k++) {
                Disc d = new Disc(found);
                try { nsd.discoverServices(TYPES[k], NsdManager.PROTOCOL_DNS_SD, d); ds.add(d); } catch (Throwable ignored) { }
            }
            Thread.sleep(2200);
            for (Disc d : ds) try { nsd.stopServiceDiscovery(d); } catch (Throwable ignored) { }
        }
        List<NsdServiceInfo> copy;
        synchronized (found) { copy = new ArrayList<>(found); }
        phase = "Gevonden apparaten opvragen";
        total = copy.size(); done = 0;
        for (NsdServiceInfo s : copy) {
            if (stopped(sc)) return;
            NsdServiceInfo x = resolve(nsd, s, sc);
            done++;
            String ip = x == null ? null : ipv4(x);
            if (ip == null || !own(n, ip)) continue; // nooit iets buiten het eigen netwerk
            Map<String, String> txt = new HashMap<>();
            try { for (Map.Entry<String, byte[]> e : x.getAttributes().entrySet()) if (e.getValue() != null) txt.put(e.getKey().toLowerCase(Locale.ROOT), new String(e.getValue(), StandardCharsets.UTF_8)); } catch (Throwable ignored) { }
            String type = s.getServiceType() == null ? "" : s.getServiceType().replaceAll("\\.$", "").replaceAll("^\\.", "");
            Host h = host(sc, ip);
            synchronized (L) {
                h.alive = true;
                h.svcs.add(type);
                if (x.getPort() > 0 && x.getPort() < 65536) h.ports.add(x.getPort());
                String sn = Apparaten.clean(x.getServiceName());
                if (h.mdns.isEmpty() && !sn.isEmpty()) h.mdns = sn;
                String fn = Apparaten.txtName(txt), md = Apparaten.txtModel(txt);
                if (!fn.isEmpty()) h.fn = fn;
                if (h.model.isEmpty() && !md.isEmpty()) h.model = md;
                if (type.startsWith("_googlecast") && h.maker.isEmpty()) h.maker = "Google";
                if ((type.startsWith("_airplay") || type.startsWith("_companion-link")) && h.maker.isEmpty()) h.maker = "Apple";
                if (type.startsWith("_sonos") && h.maker.isEmpty()) h.maker = "Sonos";
            }
        }
    }

    // ---------- SSDP / UPnP ----------

    private static void ssdp(Scan sc) throws Exception {
        final Net n = sc.net;
        String req = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n";
        Map<String, String> locs = new LinkedHashMap<>();
        try (DatagramSocket s = new DatagramSocket()) {
            n.net.bindSocket(s);
            s.setSoTimeout(400);
            byte[] b = req.getBytes(StandardCharsets.US_ASCII);
            InetAddress grp = InetAddress.getByName("239.255.255.250");
            s.send(new DatagramPacket(b, b.length, grp, 1900));
            long end = System.currentTimeMillis() + 3500;
            byte[] buf = new byte[2048];
            int sent = 1;
            while (System.currentTimeMillis() < end && !stopped(sc)) {
                if (sent < 2 && System.currentTimeMillis() > end - 2500) { s.send(new DatagramPacket(b, b.length, grp, 1900)); sent++; }
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try { s.receive(p); } catch (SocketTimeoutException t) { continue; }
                String ip = p.getAddress().getHostAddress();
                if (!own(n, ip)) continue;
                Map<String, String> hd = Apparaten.ssdpHeaders(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                Host h = host(sc, ip);
                synchronized (L) {
                    h.alive = true;
                    String st = hd.containsKey("st") ? hd.get("st") : "";
                    if (st.contains(":device:") && h.upnpType.isEmpty()) h.upnpType = st;
                    String usn = hd.containsKey("usn") ? hd.get("usn") : "";
                    if (usn.startsWith("uuid:") && h.uuid.isEmpty()) h.uuid = usn.substring(5).replaceAll("::.*$", "").toLowerCase(Locale.ROOT);
                }
                String loc = hd.get("location");
                if (loc != null && ip.equals(Apparaten.urlHost(loc)) && !locs.containsKey(ip)) locs.put(ip, loc);
            }
        }
        // De beschrijving van elk apparaat, van het apparaat zelf (alleen een adres op het eigen netwerk, kort en klein)
        for (Map.Entry<String, String> e : locs.entrySet()) {
            if (stopped(sc)) return;
            String xml = fetch(sc, e.getValue());
            if (xml == null || stopped(sc)) continue;
            Host h = host(sc, e.getKey());
            synchronized (L) {
                String fn = Apparaten.xmlTag(xml, "friendlyName"), mf = Apparaten.xmlTag(xml, "manufacturer"), md = Apparaten.xmlTag(xml, "modelName"),
                        dt = Apparaten.xmlTag(xml, "deviceType"), udn = Apparaten.xmlTag(xml, "UDN");
                if (!fn.isEmpty() && h.fn.isEmpty()) h.fn = fn;
                if (!mf.isEmpty()) h.maker = mf;
                if (!md.isEmpty() && h.model.isEmpty()) h.model = md;
                if (!dt.isEmpty()) h.upnpType = dt;
                if (udn.startsWith("uuid:")) h.uuid = udn.substring(5).toLowerCase(Locale.ROOT);
            }
        }
    }

    /** De beschrijving ophalen: hooguit 3 seconden in totaal en 64 KB, stopt meteen als de ronde stopt. */
    private static String fetch(Scan sc, String url) {
        final Net n = sc.net;
        final long deadline = System.currentTimeMillis() + 3000;
        HttpURLConnection h = null;
        try {
            if (!own(n, Apparaten.urlHost(url))) return null;
            h = (HttpURLConnection) n.net.openConnection(new URL(url), java.net.Proxy.NO_PROXY); // nooit via een proxy: alleen het eigen netwerk
            h.setConnectTimeout(1000); h.setReadTimeout(1000); h.setInstanceFollowRedirects(false); h.setUseCaches(false);
            if (h.getResponseCode() != 200) return null;
            try (InputStream in = h.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[4096]; int r;
                while ((r = in.read(buf)) > 0 && b.size() < 65536) {
                    if (stopped(sc) || System.currentTimeMillis() > deadline) return null;
                    b.write(buf, 0, r);
                }
                return new String(b.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Throwable e) { return null; }
        finally { if (h != null) h.disconnect(); }
    }

    // ---------- rondgang en poorten ----------

    /** Poorten om te kijken of er iemand is: web, https, iPhone/iPad en AirPlay. */
    static final int[] ALIVE_PORTS = {80, 443, 62078, 7000};

    /**
     * Is er iemand op dit adres? Alleen TCP-pogingen via het wifi-netwerk zelf (geen ping: die kan via VPN of mobiel
     * lopen). Een weigering betekent ook: er is iemand.
     */
    private static void probe(Scan sc, String ip) throws Exception {
        final Net n = sc.net;
        if (!own(n, ip)) return;
        InetAddress a = InetAddress.getByName(ip);
        boolean alive = false;
        for (int port : ALIVE_PORTS) {
            if (alive || stopped(sc)) break;
            try (Socket s = n.net.getSocketFactory().createSocket()) { s.connect(new InetSocketAddress(a, port), 250); alive = true; }
            catch (ConnectException refused) { alive = true; }
            catch (Throwable ignored) { }
        }
        if (alive && !stopped(sc)) { Host h = host(sc, ip); synchronized (L) { h.alive = true; } }
    }

    private static boolean open(Net n, String ip, int port, int ms) {
        if (!own(n, ip)) return false;
        try (Socket s = n.net.getSocketFactory().createSocket()) { s.connect(new InetSocketAddress(InetAddress.getByName(ip), port), ms); return true; }
        catch (Throwable e) { return false; }
    }

    // ---------- NetBIOS en ARP ----------

    private static void netbios(Scan sc, List<Host> alive) {
        final Net n = sc.net;
        if (alive.isEmpty()) return;
        try (DatagramSocket s = new DatagramSocket()) {
            n.net.bindSocket(s);
            s.setSoTimeout(300);
            int id = 0x5000;
            for (Host h : alive) {
                if (stopped(sc) || !own(n, h.ip)) continue;
                byte[] q = Apparaten.nbstatQuery(id++);
                s.send(new DatagramPacket(q, q.length, InetAddress.getByName(h.ip), 137));
                Thread.sleep(15); // rustig aan
            }
            long end = System.currentTimeMillis() + 1500;
            byte[] buf = new byte[1024];
            while (System.currentTimeMillis() < end && !stopped(sc)) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try { s.receive(p); } catch (SocketTimeoutException t) { continue; }
                String name = Apparaten.nbstatName(p.getData(), p.getLength()), ip = p.getAddress().getHostAddress();
                if (!name.isEmpty() && own(n, ip)) { Host h = host(sc, ip); synchronized (L) { h.nb = name; } }
            }
        } catch (Throwable ignored) { }
    }

    /** MAC-adressen uit de ARP-tabel: kan alleen tot en met Android 9; daarna houdt Android die voor apps verborgen. */
    private static void arp(Scan sc) {
        if (Build.VERSION.SDK_INT >= 29) return;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/net/arp"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.trim().split("\\s+");
                if (p.length < 4 || !p[3].matches("[0-9a-fA-F:]{17}") || p[3].equals("00:00:00:00:00:00")) continue;
                synchronized (L) { Host h = sc.hosts.get(p[0]); if (h != null) { h.mac = p[3].toLowerCase(Locale.ROOT); if (h.maker.isEmpty()) h.maker = Apparaten.ouiMaker(h.mac); } }
            }
        } catch (Throwable ignored) { }
    }

    // ---------- herkennen en nieuw ----------

    /** Vaste sleutel van een apparaat op het netwerk: UPnP-UUID, anders de mDNS-naam, NetBIOS-naam of (zwak) het adres. */
    static String key(Host h) {
        if (!h.uuid.isEmpty()) return "l:u:" + h.uuid;
        if (!h.mdns.isEmpty()) return "l:m:" + Apparaten.norm(h.mdns);
        if (!h.nb.isEmpty()) return "l:b:" + Apparaten.norm(h.nb);
        if (h.gateway) return "l:gw";
        return "l:i:" + h.ip;
    }

    static String display(Host h) {
        if (h.self) return "Deze telefoon";
        if (!h.fn.isEmpty()) return h.fn;
        if (!h.mdns.isEmpty()) return h.mdns;
        if (!h.nb.isEmpty()) return h.nb;
        if (h.gateway) return "Router";
        return "";
    }

    /** Wat er gevonden is bijschrijven bij dit netwerk; het eerste onderzoek van een netwerk is de basis (niets is dan "nieuw"). */
    static String netId(Net n) { return n.ssid.isEmpty() ? "net:" + Apparaten.ipStr(n.ip & 0xFFFFFF00) : n.ssid; }

    private static void remember(Context c, Scan sc) {
        String netId = netId(sc.net);
        long now = System.currentTimeMillis(), keepMs = ApparatenStore.keep(c) * 86_400_000L;
        synchronized (ApparatenStore.class) {
            JSONObject all = ApparatenStore.network(c);
            try {
                JSONObject net = all.optJSONObject(netId);
                boolean baseline = net == null;
                if (net == null) { net = new JSONObject().put("first", now).put("devs", new JSONObject()); all.put(netId, net); }
                JSONObject devs = net.optJSONObject("devs");
                if (devs == null) { devs = new JSONObject(); net.put("devs", devs); }
                synchronized (L) {
                    for (Host h : sc.hosts.values()) {
                        if (!h.alive || h.self) continue;
                        String k = key(h);
                        JSONObject d = devs.optJSONObject(k);
                        if (d == null) { d = new JSONObject().put("first", now).put("new", !baseline); devs.put(k, d); }
                        d.put("last", now).put("name", display(h)).put("ip", h.ip);
                    }
                }
                net.put("last", now);
                for (Iterator<String> it = devs.keys(); it.hasNext(); ) { JSONObject d = devs.optJSONObject(it.next()); if (d == null || now - d.optLong("last") > keepMs) it.remove(); }
                for (Iterator<String> it = all.keys(); it.hasNext(); ) { JSONObject x = all.optJSONObject(it.next()); if (x == null || now - x.optLong("last") > keepMs) it.remove(); }
            } catch (Exception ignored) { }
            ApparatenStore.networkSave(c, all);
        }
    }

    /** Stand en de lijst voor de interface. */
    static JSONObject json(Context c) {
        JSONObject o = new JSONObject();
        try {
            JSONObject known = ApparatenStore.known(c);
            Scan sc = scan;
            JSONObject nets = ApparatenStore.network(c);
            JSONObject net = sc == null ? null : nets.optJSONObject(netId(sc.net));
            JSONObject devs = net == null ? null : net.optJSONObject("devs");
            long now = System.currentTimeMillis();
            JSONArray a = new JSONArray();
            synchronized (L) {
                if (sc != null) for (Host h : sc.hosts.values()) {
                    if (!h.alive) continue;
                    String k = key(h);
                    JSONObject d = devs == null ? null : devs.optJSONObject(k), kn = known.optJSONObject(k);
                    JSONArray svc = new JSONArray(), ports = new JSONArray();
                    TreeSet<String> labels = new TreeSet<>();
                    for (String s : h.svcs) labels.add(Apparaten.serviceLabel(s));
                    for (String s : labels) svc.put(s);
                    for (int p : h.ports) ports.put(new JSONObject().put("p", p).put("label", Apparaten.portLabel(p)));
                    boolean isNew = d != null && d.optBoolean("new") && now - d.optLong("first") < 7 * 86_400_000L && kn == null;
                    a.put(new JSONObject().put("key", k).put("ip", h.ip).put("name", display(h)).put("model", h.model).put("maker", h.maker)
                            .put("kind", h.self ? "telefoon" : Apparaten.lanKind(h.svcs, h.ports, h.upnpType, h.gateway, h.model))
                            .put("services", svc).put("ports", ports).put("gateway", h.gateway).put("self", h.self).put("mac", h.mac)
                            .put("nieuw", isNew).put("first", d == null ? now : d.optLong("first")).put("nick", kn == null ? "" : kn.optString("nick")).put("known", kn != null)
                            .put("stable", !k.startsWith("l:i:")));
                }
            }
            o.put("devices", a).put("busy", busy).put("phase", phase).put("done", done).put("total", total).put("error", error)
                    .put("net", netName).put("at", at).put("macHidden", Build.VERSION.SDK_INT >= 29);
            Net w = wifi(c);
            o.put("wifi", w != null).put("own", w == null ? "" : Apparaten.ipStr(w.ip) + "/" + Apparaten.effPrefix(w.prefix));
            // Vertrouwd: je wifi thuis, of een netwerk dat je al eens liet onderzoeken. Anders (hotel, werk) eerst vragen.
            String home = ApparatenStore.prefs(c).getString("homeSsid", "");
            o.put("trusted", w != null && ((!w.ssid.isEmpty() && w.ssid.equals(home)) || nets.has(netId(w))));
            o.put("cur", w == null ? "" : w.ssid);
        } catch (Throwable e) { App.log(c, "APPARATEN", "netwerk lijst: " + e); }
        return o;
    }

}
