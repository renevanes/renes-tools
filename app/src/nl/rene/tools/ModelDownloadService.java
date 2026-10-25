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
        for (String id : RUN.keySet()) stop(id);
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
            synchronized (queue) {
                if (worker == Thread.currentThread()) worker = null;
                if (worker == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelfResult(lastStartId);
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static WifiManager.WifiLock wifiLock(WifiManager wm) {
        return wm.createWifiLock(Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF, "renestools:model-download");
    }

    private long lastNotif;

    private void one(String id) {
        LocalAi.Model m = LocalAi.model(id);
        if (m == null) return;
        if (LocalAi.wifiOnly(this) && metered(this)) {
            LocalAi.ownFailed(this, id, "Wacht op wifi (of zet Alleen via wifi uit)");
            return;
        }
        RUN.put(id, new long[]{LocalAi.partFile(this, m).length(), m.size});
        try {
            ModelFetch.fetch(m.url(), LocalAi.partFile(this, m), m.size, (done, total) -> {
                RUN.put(id, new long[]{done, total});
                long now = System.currentTimeMillis();
                if (now - lastNotif > 1000 || done >= total) {
                    lastNotif = now;
                    int pct = total > 0 ? (int) (done * 100 / total) : -1;
                    ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID,
                            notif("AI-model: " + m.label, (pct >= 0 ? pct + "% · " : "") + LocalAi.gb(done) + " van " + LocalAi.gb(total), pct));
                }
            }, () -> STOP.contains(id));
            RUN.remove(id);
            LocalAi.ownDone(this, m);
        } catch (ModelFetch.Stopped e) {
            RUN.remove(id);
            STOP.remove(id);
        } catch (Throwable e) {
            RUN.remove(id);
            LocalAi.ownFailed(this, id, e instanceof OutOfMemoryError ? "Onvoldoende geheugen" : e.getMessage() == null ? "Downloaden mislukt" : e.getMessage());
        }
    }

    /** Zit de telefoon op een netwerk met een databundel (mobiel internet of een hotspot)? */
    static boolean metered(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(CONNECTIVITY_SERVICE);
            return cm != null && cm.isActiveNetworkMetered();
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
