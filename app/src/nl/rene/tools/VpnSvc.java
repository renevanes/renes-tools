package nl.rene.tools;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * De VPN zelf: maakt de tunnel bij Android aan (VpnService) en laat {@link WgDevice} de pakketten versleutelen.
 * Werkt ook met "Altijd-aan VPN" van Android (dan start Android hem zelf), en schakelt over bij wifi ↔ mobiel.
 */
public class VpnSvc extends VpnService {

    static final String START = "nl.rene.tools.VPN_START", STOP = "nl.rene.tools.VPN_STOP";
    static final int NOTIF_ID = 4701;

    static volatile VpnSvc inst;
    static volatile String status = "off", tunnelId, tunnelName, error, reason;
    static volatile long since;
    static volatile boolean autoStarted;

    private final Handler h = new Handler(Looper.getMainLooper());
    private ParcelFileDescriptor tunFd;
    private NetCb netCb;
    private volatile Network underlying;
    private final Object lock = new Object();
    private int generation;
    private int lastStartId;

    static boolean on() { return inst != null && !"off".equals(status); }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        String a = intent == null ? null : intent.getAction();
        if (STOP.equals(a)) { startFg(); Vpn.stop(this, "melding"); if (inst == this && "off".equals(status)) finish(); return START_NOT_STICKY; }
        String id, why;
        if (START.equals(a)) { id = intent.getStringExtra("id"); why = intent.getStringExtra("reason"); }
        else { id = Vpn.activeId(this); why = "altijd"; } // Android start ons zelf (Altijd-aan VPN)
        if (id == null || id.isEmpty()) id = Vpn.activeId(this);
        if (why == null) why = "app";
        startFg();
        if (id.isEmpty() || Vpn.tunnel(this, id) == null) { error = "Geen tunnel ingesteld"; finish(); return START_NOT_STICKY; }
        boolean restart = intent != null && intent.getBooleanExtra("restart", false);
        if (on() && id.equals(tunnelId) && !restart) { // draait al
            if (!"wifi".equals(why) && !"mobiel".equals(why)) autoStarted = false;
            return START_STICKY;
        }
        connect(id, why);
        return START_STICKY;
    }

    private void startFg() {
        createChannel(this);
        Fg.start(this, NOTIF_ID, notif(), 0x40000000 /* FOREGROUND_SERVICE_TYPE_SPECIAL_USE */, 34);
    }

    static void createChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(Vpn.CHANNEL, "VPN", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setDescription("Of de VPN aan staat, en een vraag als hij vanzelf aan moet maar Android dat niet toeliet");
        c.getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    // ---------- verbinden: de gekozen tunnel, en bij een blokkade de reserve-tunnels ----------

    static volatile VpnBackend backend;
    static volatile String activeId, activeName, activeKind;
    private java.util.List<String> chain = new java.util.ArrayList<>();
    private int chainIdx, failedInRow;
    private final java.util.Set<String> refused = new java.util.HashSet<>(); // inloggen geweigerd: niet steeds opnieuw proberen
    private long attemptStart, attemptWall, healthyAt;

    private void connect(String id, String why) {
        tunnelId = id;
        org.json.JSONObject t = Vpn.tunnel(this, id);
        tunnelName = t == null ? "VPN" : t.optString("name", "VPN");
        reason = why;
        autoStarted = "wifi".equals(why) || "mobiel".equals(why);
        status = "connecting"; error = null; since = System.currentTimeMillis();
        Vpn.cancelAsk(this);
        chain = Vpn.chain(this, id);
        chainIdx = 0; failedInRow = 0; refused.clear();
        startAttempt();
    }

    /** Volgende poging met chain[chainIdx] (op de hoofdthread aanroepen). */
    private void startAttempt() {
        final int gen;
        synchronized (lock) { gen = ++generation; }
        final String tid = chain.get(chainIdx);
        org.json.JSONObject t = Vpn.tunnel(this, tid);
        activeId = tid;
        activeName = t == null ? "VPN" : t.optString("name", "VPN");
        activeKind = t != null && "ovpn".equals(t.optString("type")) ? "OpenVPN" : "WireGuard";
        attemptStart = now(); attemptWall = System.currentTimeMillis();
        status = "connecting";
        changed();
        new Thread(() -> {
            VpnBackend b;
            try {
                b = Vpn.backend(this, tid, gen);
                VpnBackend old;
                synchronized (lock) {
                    if (gen != generation) { return; }
                    old = backend;
                    backend = b;
                }
                if (old != null) old.stop(); // de oude tunnel bij Android blijft tot de nieuwe er is (geen lek)
                watchNetwork();
                b.start();
            } catch (Exception e) {
                String err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                h.post(() -> attemptFailed(gen, err));
                return;
            }
            final VpnBackend fb = b;
            h.postDelayed(() -> watchdog(gen, fb), b.timeoutMs());
        }, "vpn-start").start();
    }

    private static long now() { return android.os.SystemClock.elapsedRealtime(); }

    /** Poging mislukt meteen (fout in de instellingen, Android weigerde): volgende, of stoppen als niets werkt. */
    private void attemptFailed(int gen, String err) {
        synchronized (lock) { if (gen != generation) return; }
        App.log(this, "VPN", "poging " + activeName + " mislukt: " + err);
        error = err;
        failedInRow++;
        if (chain.size() > 1 && failedInRow < chain.size()) {
            for (int i = 0; i < chain.size(); i++) { chainIdx = (chainIdx + 1) % chain.size(); if (!refused.contains(chain.get(chainIdx))) { startAttempt(); return; } }
        }
        stopTunnel("mislukt");
        error = err;
    }

    /** Na de wachttijd: verbonden? Anders de volgende uit de rij (bij een blokkade). */
    private void watchdog(int gen, VpnBackend b) {
        synchronized (lock) { if (gen != generation || backend != b) return; }
        if (b.lastOkWall() >= attemptWall && b.healthy()) { failedInRow = 0; healthyAt = now(); return; }
        if (b.fatal() && chain.size() == 1) { error = b.error(); stopTunnel("mislukt"); error = b.error(); return; }
        if (chain.size() > 1) { next("geen verbinding met " + activeName); return; }
        error = b.error(); changed();
        h.postDelayed(() -> watchdog(gen, b), 20_000);
    }

    private void next(String why) {
        App.log(this, "VPN", why + ": reserve proberen");
        for (int i = 0; i < chain.size(); i++) {
            chainIdx = (chainIdx + 1) % chain.size();
            if (!refused.contains(chain.get(chainIdx))) { startAttempt(); return; }
        }
        String e = error;
        stopTunnel("mislukt"); // alle tunnels weigeren het inloggen
        error = e;
    }

    /** Builder met wat voor elke tunnel geldt. */
    VpnService.Builder builder(String session) {
        Builder b = new Builder();
        b.setSession(session == null ? "VPN" : session);
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false);
        Network base = underlying != null ? underlying : bestNetwork();
        underlying = base;
        if (base != null && Build.VERSION.SDK_INT >= 22) b.setUnderlyingNetworks(new Network[]{base});
        b.setBlocking(true);
        Intent open = new Intent(this, MainActivity.class).putExtra("open", "vpn").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        b.setConfigureIntent(PendingIntent.getActivity(this, 4705, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return b;
    }

    /** De tunnel bij Android (opnieuw) aanmaken; de vorige gaat pas dicht als de nieuwe er is. */
    WgDevice.Tun establish(VpnService.Builder b, int gen) throws Exception {
        ParcelFileDescriptor fd, old;
        synchronized (lock) {
            if (gen != generation) throw new Exception("afgebroken");
            try { fd = b.establish(); }
            catch (Exception e) { throw new Exception("Android liet de VPN niet toe: " + e.getClass().getSimpleName()); }
            if (fd == null) throw new Exception("Android gaf geen toestemming voor de VPN (open de VPN in de app)");
            old = tunFd;
            tunFd = fd;
        }
        if (old != null) try { old.close(); } catch (Exception ignored) { }
        return new TunFd(fd);
    }

    /** De tunnel van Android als Tun voor WgDevice/OvpnDevice. */
    static final class TunFd implements WgDevice.Tun {
        private final FileInputStream in;
        private final FileOutputStream out;
        TunFd(ParcelFileDescriptor fd) { in = new FileInputStream(fd.getFileDescriptor()); out = new FileOutputStream(fd.getFileDescriptor()); }
        @Override public int read(byte[] buf) throws IOException { return in.read(buf); }
        @Override public void write(byte[] buf, int off, int len) throws IOException { synchronized (out) { out.write(buf, off, len); } }
    }

    /** Server opzoeken via het gewone netwerk (niet via de tunnel, die werkt dan nog niet). */
    InetAddress[] resolve(String host) throws IOException {
        Network n = underlying;
        if (n == null) n = bestNetwork();
        if (n != null) return n.getAllByName(host);
        if (tunFd == null) return InetAddress.getAllByName(host); // tunnel nog niet aan: gewoon opzoeken
        throw new java.net.UnknownHostException("Geen netwerk"); // anders zou het via onze eigen tunnel gaan
    }

    /** Het beste gewone netwerk (geen VPN) met internet. */
    private Network bestNetwork() {
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            Network act = cm.getActiveNetwork();
            NetworkCapabilities ac = act == null ? null : cm.getNetworkCapabilities(act);
            if (ac != null && !ac.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && ac.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return act;
            Network best = null; int score = -1;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc == null || nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                int s = (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? 4 : 0) + (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? 2 : 0) + 1;
                if (s > score) { score = s; best = n; }
            }
            return best;
        } catch (Exception e) { return null; }
    }

    // ---------- netwerkwissel ----------

    static final class NetCb extends ConnectivityManager.NetworkCallback {
        private final VpnSvc s;
        NetCb(VpnSvc s) { this.s = s; }
        @Override public void onAvailable(Network n) { s.netMaybeChanged(); }
        @Override public void onLost(Network n) { s.netMaybeChanged(); }
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities nc) { s.netMaybeChanged(); }
    }

    private void watchNetwork() {
        h.post(() -> {
            if (netCb != null || "off".equals(status)) return;
            try {
                ConnectivityManager cm = getSystemService(ConnectivityManager.class);
                netCb = new NetCb(this);
                NetworkRequest req = new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build();
                if (Build.VERSION.SDK_INT >= 31) cm.registerBestMatchingNetworkCallback(req, netCb, h);
                else if (Build.VERSION.SDK_INT >= 26) cm.registerNetworkCallback(req, netCb, h);
                else cm.registerNetworkCallback(req, netCb);
            } catch (Exception e) { netCb = null; }
        });
    }

    private final Runnable netCheck = this::netCheck;

    private void netMaybeChanged() { h.removeCallbacks(netCheck); h.postDelayed(netCheck, 1500); }

    private void netCheck() {
        Network n = bestNetwork();
        Network old = underlying;
        if (n == null || n.equals(old)) return;
        underlying = n;
        try { setUnderlyingNetworks(new Network[]{n}); } catch (Exception ignored) { }
        if ("off".equals(status)) return;
        // nieuw netwerk: eerst weer de voorkeur (misschien werkt WireGuard hier wel), anders de huidige laten herverbinden
        if (chain.size() > 1 && chainIdx != 0 && !refused.contains(chain.get(0))) { chainIdx = 0; failedInRow = 0; startAttempt(); return; }
        VpnBackend b = backend;
        if (b != null) {
            attemptStart = now(); attemptWall = System.currentTimeMillis();
            new Thread(b::networkChanged, "vpn-net").start();
            if (chain.size() > 1) { synchronized (lock) { final int gen = generation; h.postDelayed(() -> watchdog(gen, b), b.timeoutMs()); } }
        }
    }

    // ---------- toestand ----------

    private long lastNotif;

    void onEvent(String what) {
        if ("off".equals(status)) return;
        if ("handshake".equals(what) || "connected".equals(what) || "renegotiated".equals(what)) { if (!"connected".equals(status) || error != null) { status = "connected"; error = null; healthyAt = now(); changed(); } return; }
        if ("handshake-failed".equals(what) || "error".equals(what) || "reconnecting".equals(what) || "auth-failed".equals(what)) {
            VpnBackend b = backend;
            status = b != null && b.healthy() ? "connected" : "connecting";
            error = b == null ? null : b.error();
            if ("auth-failed".equals(what)) h.post(() -> { VpnBackend x = backend; if (x != null && x.fatal()) { refused.add(x.tunnelId()); if (chain.size() > 1) { error = x.error(); next("inloggen geweigerd"); } else { String e = x.error(); stopTunnel("mislukt"); error = e; } } });
        } else if ("tun-closed".equals(what)) { h.post(() -> stopTunnel("android")); return; }
        long t = System.currentTimeMillis();
        if (!what.equals("handshake-sent") && !what.equals("connecting") || t - lastNotif > 30_000) changed();
    }

    private final Runnable tick = new Ticker(this);

    static final class Ticker implements Runnable {
        private final VpnSvc s;
        Ticker(VpnSvc s) { this.s = s; }
        @Override public void run() {
            VpnBackend b = backend;
            if (b == null || "off".equals(status)) return;
            if (b.healthy()) { s.healthyAt = now(); status = "connected"; error = null; }
            else {
                status = "connecting";
                error = b.error();
                // werkte hij wel, maar nu al een tijd niet meer? Dan de reserve
                long since = Math.max(s.healthyAt, s.attemptStart);
                if (s.chain.size() > 1 && now() - since > Math.max(45_000, b.timeoutMs())) { s.next("verbinding weggevallen"); return; }
            }
            s.updateNotif();
            s.h.postDelayed(this, 20_000);
        }
    }

    /** Melding, tegel en scherm bijwerken. */
    private void changed() {
        h.post(() -> {
            updateNotif();
            Vpn.refreshTile(this);
            h.removeCallbacks(tick); h.postDelayed(tick, 20_000);
        });
        MainActivity.jsAll("onVpnChanged", "");
    }

    private void updateNotif() {
        if (inst != this || "off".equals(status)) return;
        lastNotif = System.currentTimeMillis();
        try { getSystemService(NotificationManager.class).notify(NOTIF_ID, notif()); } catch (Exception ignored) { }
    }

    private Notification notif() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, NotifCenter.ch(this, Vpn.CHANNEL)) : new Notification.Builder(this);
        VpnBackend d = backend;
        String name = tunnelName == null ? "VPN" : tunnelName;
        if (activeName != null && !activeName.equals(name)) name += " → " + activeName + " (reserve)";
        String text;
        if ("connected".equals(status) && d != null) text = "Verbonden via " + d.kind() + " · ↓ " + size(d.rx()) + " · ↑ " + size(d.tx());
        else if (error != null) text = error + " · probeert het opnieuw";
        else text = "Verbinden…";
        Intent open = new Intent(this, MainActivity.class).putExtra("open", "vpn").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        b.setSmallIcon(R.drawable.ic_vpn).setContentTitle("VPN aan · " + name).setContentText(text)
                .setContentIntent(PendingIntent.getActivity(this, 4706, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false);
        PendingIntent stop = PendingIntent.getService(this, 4707, new Intent(this, VpnSvc.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        b.addAction(new Notification.Action.Builder(null, "Uitzetten", stop).build());
        return b.build();
    }

    static String size(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return (b / 1024) + " kB";
        if (b < 1024L * 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f MB", b / 1048576.0).replace('.', ',');
        return String.format(java.util.Locale.ROOT, "%.2f GB", b / 1073741824.0).replace('.', ',');
    }

    // ---------- stoppen ----------

    private void closeTunnel() {
        VpnBackend d = backend;
        backend = null;
        if (d != null) d.stop();
        ParcelFileDescriptor fd = tunFd;
        tunFd = null;
        if (fd != null) try { fd.close(); } catch (Exception ignored) { }
    }

    /** Uitzetten (van elke thread). */
    void stopTunnel(String why) {
        synchronized (lock) { generation++; closeTunnel(); }
        App.log(this, "VPN", "uit (" + why + ")");
        status = "off"; autoStarted = false;
        h.post(this::finish);
    }

    private void finish() {
        if (!"off".equals(status)) return; // intussen weer aangezet (wordt nog opgebouwd of draait)
        status = "off";
        if (netCb != null) { try { getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCb); } catch (Exception ignored) { } netCb = null; }
        h.removeCallbacks(tick);
        try { if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true); } catch (Exception ignored) { }
        Vpn.refreshTile(this);
        MainActivity.jsAll("onVpnChanged", "");
        stopSelf(lastStartId); // niet als er intussen een nieuwe opdracht kwam
    }

    @Override
    public void onRevoke() { // een andere VPN-app nam het over, of je zette hem uit in de instellingen van Android
        error = "Uitgezet door Android of een andere VPN-app";
        stopTunnel("ingetrokken");
    }

    @Override
    public void onDestroy() {
        synchronized (lock) { generation++; closeTunnel(); }
        if (netCb != null) { try { getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCb); } catch (Exception ignored) { } netCb = null; }
        h.removeCallbacksAndMessages(null);
        status = "off";
        if (inst == this) inst = null;
        Vpn.refreshTile(this);
        super.onDestroy();
    }
}
