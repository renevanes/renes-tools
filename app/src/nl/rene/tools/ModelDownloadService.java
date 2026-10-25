package nl.rene.tools;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Een AI-model door de app zelf laten downloaden (in plaats van de DownloadManager van Android), als voorgronddienst
 * met de voortgang in de meldingenbalk, zodat het doorgaat als je de app verlaat. Hervat waar hij gebleven was.
 * Daarna controleert LocalAi de SHA-256 en zet het model in de privémap, net als na de DownloadManager.
 */
public class ModelDownloadService extends Service {

    static final String CHANNEL = "model-download";
    static final int NOTIF_ID = 4901;

    /** Wat er nu binnenkomt: id → {binnen, totaal}. */
    static final Map<String, long[]> RUN = new ConcurrentHashMap<>();
    /** Wacht nog op zijn beurt. */
    static final Set<String> QUEUED = java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    /** Wacht even op een netwerk dat mag (bijv. wifi terug): id → waarom. */
    static final Map<String, String> WAIT_NET = new ConcurrentHashMap<>();
    /** Stoppen (of verwijderd): de download van dit model afbreken. */
    static final Set<String> STOP = java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private final List<String> queue = new ArrayList<>();
    private Thread worker;
    private volatile int lastStartId;

    static void start(Context c, String id) {
        STOP.remove(id);
        QUEUED.add(id);
        Intent i = new Intent(c, ModelDownloadService.class).putExtra("id", id);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception e) {
            QUEUED.remove(id);
            LocalAi.ownFailed(c, id, "Android staat het downloaden nu niet toe; open de app en tik op Verder");
        }
    }

    /** Afbreken (bij Stoppen of Verwijderen); een model in de wachtrij komt niet meer aan de beurt. */
    static void stop(String id) { STOP.add(id); QUEUED.remove(id); }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createChannel(this);
        if (!Fg.start(this, NOTIF_ID, notif("Model downloaden", "Bezig…", -1), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, 29)) {
            boolean running; synchronized (queue) { running = worker != null; }
            if (!running) {
                String id = intent == null ? null : intent.getStringExtra("id");
                if (id != null) { QUEUED.remove(id); LocalAi.ownFailed(this, id, "Android staat het downloaden nu niet toe; open de app en tik op Verder"); }
                stopSelfResult(startId);
                return START_NOT_STICKY;
            }
        }
        synchronized (queue) {
            lastStartId = startId;
            String id = intent == null ? null : intent.getStringExtra("id");
            if (id != null && LocalAi.model(id) != null && !queue.contains(id)) queue.add(id);
            if (worker == null) { worker = new Thread(this::work, "model-download"); worker.start(); }
        }
        return START_NOT_STICKY;
    }

    /** Android 15+: een dataSync-dienst mag hooguit 6 uur per dag; netjes stoppen (Verder kan later). */
    @Override
    public void onTimeout(int startId, int fgsType) {
        synchronized (queue) { for (String id : queue) stop(id); }
        for (String id : RUN.keySet()) { LocalAi.ownFailed(this, id, "Android stopte het downloaden na 6 uur; tik later op Verder"); stop(id); }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void work() {
        PowerManager.WakeLock wl = null;
        WifiManager.WifiLock wifi = null;
        try {
            wl = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "renestools:model-download");
            wl.acquire(6 * 60 * 60 * 1000L);
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null) { wifi = wifiLock(wm); wifi.acquire(); }
        } catch (Throwable ignored) { }
        try {
            while (true) {
                String id;
                synchronized (queue) {
                    if (queue.isEmpty()) { worker = null; break; }
                    id = queue.remove(0);
                }
                QUEUED.remove(id);
                if (STOP.remove(id)) continue;
                one(id);
            }
        } catch (Throwable t) {
            App.log(this, "AI", "model downloaden: " + t);
        } finally {
            try { if (wl != null && wl.isHeld()) wl.release(); } catch (Throwable ignored) { }
            try { if (wifi != null && wifi.isHeld()) wifi.release(); } catch (Throwable ignored) { }
            try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIF_ID); } catch (Throwable ignored) { }
            synchronized (queue) {
                if (worker == Thread.currentThread()) worker = null;
                if (worker == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelfResult(lastStartId);
                }
            }
        }
    }

    /** Wifi wakker houden tijdens het downloaden (ook met het scherm uit). */
    @SuppressWarnings("deprecation")
    private static WifiManager.WifiLock wifiLock(WifiManager wm) {
        return wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "renestools:model-download");
    }

    /**
     * Waarom het netwerk nu niet mag (null = mag): roaming nooit, en mobiel internet niet bij Alleen via wifi.
     * Even helemaal geen netwerk (wifi wisselt, verbinding hapert) telt niet: dat lost het opnieuw proberen op.
     */
    static String netBlocked(Context c) {
        if (roaming(c)) return "Wacht op een netwerk zonder roaming";
        if (LocalAi.wifiOnly(c) && metered(c)) return "Wacht op wifi (of zet Alleen via wifi uit)";
        return null;
    }

    /** Hooguit 5 minuten wachten tot het netwerk weer mag (wifi terug); geeft de reden als het nog steeds niet mag. */
    private String waitForNet(String id) {
        String why = netBlocked(this);
        long until = System.currentTimeMillis() + 5 * 60_000L;
        try {
            while (why != null && System.currentTimeMillis() < until && !STOP.contains(id)) {
                WAIT_NET.put(id, why);
                try { Thread.sleep(3000); } catch (InterruptedException e) { break; }
                why = netBlocked(this);
            }
        } finally { WAIT_NET.remove(id); }
        return why;
    }

    private long lastNotif;

    private void one(String id) {
        LocalAi.Model m = LocalAi.model(id);
        if (m == null) return;
        String blocked = netBlocked(this);
        if (blocked != null) {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, notif("AI-model: " + m.label, blocked, -1));
            blocked = waitForNet(id);
            if (STOP.remove(id)) return;
            if (blocked != null) { LocalAi.ownFailed(this, id, blocked); return; }
        }
        RUN.put(id, new long[]{LocalAi.partFile(this, m).length(), m.size});
        // Tijdens het downloaden blijven kijken (hooguit eens per 2 s): valt wifi weg, dan niet over mobiel internet verder
        final String[] net = {null};
        final long[] netAt = {0};
        java.util.function.BooleanSupplier stopNow = () -> {
            if (STOP.contains(id)) return true;
            long now = System.currentTimeMillis();
            if (now - netAt[0] > 2000) {
                netAt[0] = now; net[0] = netBlocked(this);
                // Mag het netwerk niet meer (wifi weg, nu mobiel internet): even wachten of wifi terugkomt
                if (net[0] != null) {
                    ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, notif("AI-model: " + m.label, net[0], -1));
                    net[0] = waitForNet(id);
                    netAt[0] = System.currentTimeMillis();
                }
            }
            return STOP.contains(id) || net[0] != null;
        };
        try {
            ModelFetch.fetch(m.url(), LocalAi.partFile(this, m), m.size, (done, total) -> {
                RUN.put(id, new long[]{done, total});
                long now = System.currentTimeMillis();
                if (!STOP.contains(id) && (now - lastNotif > 1000 || done >= total)) {
                    lastNotif = now;
                    int pct = total > 0 ? (int) (done * 100 / total) : -1;
                    ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID,
                            notif("AI-model: " + m.label, (pct >= 0 ? pct + "% · " : "") + LocalAi.gb(done) + " van " + LocalAi.gb(total), pct));
                }
            }, stopNow);
            RUN.remove(id);
            if (STOP.remove(id)) return; // net gestopt of verwijderd: niet meer controleren
            LocalAi.ownDone(this, m);
        } catch (ModelFetch.Stopped e) {
            RUN.remove(id);
            if (!STOP.remove(id) && net[0] != null) LocalAi.ownFailed(this, id, net[0]); // wifi weg: wachten, Verder kan
        } catch (Throwable e) {
            RUN.remove(id);
            App.log(this, "AI", "model downloaden: " + e + (e.getCause() != null ? " (" + e.getCause() + ")" : ""));
            LocalAi.ownFailed(this, id, why(e));
        }
    }

    /** Een foutmelding in gewone taal (de echte staat in het logboek). Zuiver (getest). */
    static String why(Throwable e) {
        if (e instanceof OutOfMemoryError) return "Onvoldoende geheugen";
        if (e instanceof ModelFetch.Fatal || e instanceof ModelFetch.Offline) return e.getMessage();
        String m = String.valueOf(e.getMessage());
        if (m.contains("ENOSPC") || m.toLowerCase(java.util.Locale.ROOT).contains("no space")) return "Te weinig opslag";
        return "Downloaden mislukt; tik op Verder om het opnieuw te proberen";
    }

    /** Zit de telefoon op een netwerk met een databundel (mobiel internet of een hotspot)? */
    static boolean metered(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(CONNECTIVITY_SERVICE);
            android.net.Network n = cm == null ? null : cm.getActiveNetwork();
            if (cm != null && n == null) return false; // geen netwerk: ook geen databundel (opnieuw proberen lost dat op)
            android.net.NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            return nc == null || !nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        } catch (Throwable t) { return true; } // niet te zien: voorzichtig (geen databundel gebruiken)
    }

    /** Zit de telefoon in het buitenland op een netwerk met roaming? (Android 9+; daarvoor niet te zien.) */
    static boolean roaming(Context c) {
        if (Build.VERSION.SDK_INT < 28) return false;
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(CONNECTIVITY_SERVICE);
            android.net.Network n = cm == null ? null : cm.getActiveNetwork();
            android.net.NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            return nc != null && !nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING);
        } catch (Throwable t) { return false; }
    }

    /** Heeft de app zelf nu internet (een netwerk dat Android als werkend heeft gecontroleerd)? */
    static boolean online(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(CONNECTIVITY_SERVICE);
            android.net.Network n = cm == null ? null : cm.getActiveNetwork();
            android.net.NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            return nc != null && nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Throwable t) { return false; }
    }

    private Notification notif(String title, String text, int pct) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, NotifCenter.ch(this, CHANNEL)) : new Notification.Builder(this);
        Intent i = new Intent(this, MainActivity.class).putExtra("open", "models").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        b.setSmallIcon(R.drawable.ic_notif).setContentTitle(title).setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
                .setContentIntent(android.app.PendingIntent.getActivity(this, 49, i, android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT));
        if (pct >= 0) b.setProgress(100, pct, false); else b.setProgress(0, 0, true);
        return b.build();
    }

    static void createChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Modellen downloaden", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Voortgang als de app zelf een AI-model downloadt");
        ((NotificationManager) c.getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
