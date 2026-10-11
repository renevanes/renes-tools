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
    static volatile WgDevice device;
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

    // ---------- verbinden ----------

    private void connect(String id, String why) {
        final int gen;
        synchronized (lock) { gen = ++generation; closeTunnel(); }
        tunnelId = id;
        org.json.JSONObject t = Vpn.tunnel(this, id);
        tunnelName = t == null ? "VPN" : t.optString("name", "VPN");
        reason = why;
        autoStarted = "wifi".equals(why) || "mobiel".equals(why);
        status = "connecting"; error = null; since = System.currentTimeMillis();
        Vpn.cancelAsk(this);
        changed();
        new Thread(() -> {
            String err = null;
            try { setUp(id, gen); }
            catch (Exception e) { err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
            if (err != null) {
                synchronized (lock) { if (gen != generation) return; closeTunnel(); }
                error = err; status = "off";
                App.log(this, "VPN", "starten mislukt: " + err);
                h.post(this::finish);
            }
        }, "vpn-start").start();
    }

    private void setUp(String id, int gen) throws Exception {
        WgConfig cfg = WgConfig.parse(Vpn.configText(this, id));
        Network base = bestNetwork();
        underlying = base;
        WgDevice dev = new WgDevice(cfg, null, this::protectSocket, host -> resolve(host), this::onEvent);
        // Builder: adressen, DNS, routes
        Builder b = new Builder();
        b.setSession(tunnelName == null ? "VPN" : tunnelName);
        b.setMtu(cfg.mtuOrDefault());
        for (WgConfig.Cidr c : cfg.addresses) b.addAddress(InetAddress.getByAddress(c.addr), c.prefix);
        for (String d : cfg.dns) b.addDnsServer(InetAddress.getByAddress(WgConfig.ip(d)));
        for (String s : cfg.dnsSearch) b.addSearchDomain(s);
        for (WgConfig.Peer p : cfg.peers) for (WgConfig.Cidr c : p.allowed) b.addRoute(InetAddress.getByAddress(c.network()), c.prefix);
        for (String app : cfg.excludedApps) try { b.addDisallowedApplication(app); } catch (PackageManager.NameNotFoundException ignored) { }
        int included = 0;
        for (String app : cfg.includedApps) try { b.addAllowedApplication(app); included++; } catch (PackageManager.NameNotFoundException ignored) { }
        if (!cfg.includedApps.isEmpty() && included == 0) throw new Exception("Geen van de apps bij IncludedApplications staat op deze telefoon");
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false);
        if (base != null && Build.VERSION.SDK_INT >= 22) b.setUnderlyingNetworks(new Network[]{base});
        b.setBlocking(true);
        Intent open = new Intent(this, MainActivity.class).putExtra("open", "vpn").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        b.setConfigureIntent(PendingIntent.getActivity(this, 4705, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        // eerst de server opzoeken en de socket openen (buiten de tunnel, en zonder slot: DNS kan lang duren)
        dev.start();
        ParcelFileDescriptor fd;
        synchronized (lock) {
            if (gen != generation) { dev.stop(); return; } // intussen uitgezet of een andere tunnel gekozen
            try { fd = b.establish(); }
            catch (Exception e) { dev.stop(); throw new Exception("Android liet de VPN niet toe: " + e.getClass().getSimpleName()); }
            if (fd == null) { dev.stop(); throw new Exception("Android gaf geen toestemming voor de VPN (open de VPN in de app)"); }
            tunFd = fd;
            dev.setTun(new TunFd(fd));
            device = dev;
            status = dev.healthy() ? "connected" : "connecting";
        }
        watchNetwork(gen);
        changed();
    }

    /** De tunnel van Android als Tun voor WgDevice. */
    static final class TunFd implements WgDevice.Tun {
        private final FileInputStream in;
        private final FileOutputStream out;
        TunFd(ParcelFileDescriptor fd) { in = new FileInputStream(fd.getFileDescriptor()); out = new FileOutputStream(fd.getFileDescriptor()); }
        @Override public int read(byte[] buf) throws IOException { return in.read(buf); }
        @Override public void write(byte[] buf, int off, int len) throws IOException { synchronized (out) { out.write(buf, off, len); } }
    }

    private boolean protectSocket(DatagramSocket s) { return protect(s); }

    /** Server opzoeken via het gewone netwerk (niet via de tunnel, die werkt dan nog niet). */
    private InetAddress[] resolve(String host) throws IOException {
        Network n = underlying;
        if (n == null) n = bestNetwork();
        if (n != null) return n.getAllByName(host);
        if (device == null) return InetAddress.getAllByName(host); // tunnel nog niet aan: gewoon opzoeken
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

    private void watchNetwork(int gen) {
        h.post(() -> {
            if (netCb != null || gen != generation || "off".equals(status)) return;
            try {
                ConnectivityManager cm = getSystemService(ConnectivityManager.class);
                netCb = new NetCb(this);
                NetworkRequest req = new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build();
                if (Build.VERSION.SDK_INT >= 31) cm.registerBestMatchingNetworkCallback(req, netCb, h);
                else cm.registerNetworkCallback(req, netCb, h);
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
        WgDevice d = device;
        if (d != null) new Thread(d::networkChanged, "vpn-net").start();
    }

    // ---------- toestand ----------

    private long lastNotif;

    private void onEvent(String what) {
        WgDevice d = device;
        if ("off".equals(status)) return;
        if ("handshake".equals(what)) { status = "connected"; error = null; }
        else if ("handshake-failed".equals(what)) { status = "connecting"; error = "Geen antwoord van de server"; }
        else if ("tun-closed".equals(what)) { h.post(() -> stopTunnel("android")); return; }
        long t = System.currentTimeMillis();
        if (!what.equals("handshake-sent") || t - lastNotif > 30_000) changed();
    }

    private final Runnable tick = new Ticker(this);

    static final class Ticker implements Runnable {
        private final VpnSvc s;
        Ticker(VpnSvc s) { this.s = s; }
        @Override public void run() {
            WgDevice d = device;
            if (d != null && !"off".equals(status)) {
                status = d.healthy() ? "connected" : "connecting";
                error = status.equals("connecting") ? d.lastError : null;
                s.updateNotif();
                s.h.postDelayed(this, 20_000);
            }
        }
    }

    /** Melding, tegel en scherm bijwerken. */
    private void changed() {
        h.post(() -> {
            updateNotif();
            Vpn.refreshTile(this);
            h.removeCallbacks(tick); h.postDelayed(tick, 20_000);
        });
        MainActivity m = MainActivity.live;
        if (m != null) m.js("onVpnChanged", "");
    }

    private void updateNotif() {
        if (inst != this || "off".equals(status)) return;
        lastNotif = System.currentTimeMillis();
        try { getSystemService(NotificationManager.class).notify(NOTIF_ID, notif()); } catch (Exception ignored) { }
    }

    private Notification notif() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, NotifCenter.ch(this, Vpn.CHANNEL)) : new Notification.Builder(this);
        WgDevice d = device;
        String name = tunnelName == null ? "VPN" : tunnelName;
        String text;
        if ("connected".equals(status) && d != null) text = "Verbonden · ↓ " + size(d.rx()) + " · ↑ " + size(d.tx());
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
        WgDevice d = device;
        device = null;
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
        MainActivity m = MainActivity.live;
        if (m != null) m.js("onVpnChanged", "");
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
