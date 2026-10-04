package nl.rene.tools;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Voorgrondservice voor het downloaden van het spraakmodel en het uitschrijven van opnames,
 * zodat dat doorgaat als je de app verlaat. Voortgang staat in de meldingenbalk en in de
 * voorkeuren ("status"), die de interface uitleest.
 */
public class TranscribeService extends Service {

    static final String CHANNEL = "transcribe";
    static final int NOTIF_ID = 4301, DONE_ID = 4302;
    static volatile boolean busy = false;

    private final List<String> queue = new ArrayList<>();
    private String modelToGet;
    private Thread worker;

    static void download(Context c, String model) {
        Intent i = new Intent(c, TranscribeService.class).putExtra("download", model);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    static void run(Context c, ArrayList<String> paths) {
        Intent i = new Intent(c, TranscribeService.class).putStringArrayListExtra("paths", paths);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createChannel(this);
        Notification n = notif("Gesprekken uitschrijven", "Bezig…", -1);
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTIF_ID, n);
        synchronized (queue) {
            if (intent != null && intent.getStringExtra("download") != null) modelToGet = intent.getStringExtra("download");
            if (intent != null && intent.getStringArrayListExtra("paths") != null)
                for (String p : intent.getStringArrayListExtra("paths")) if (!queue.contains(p)) queue.add(p);
            if (worker == null) {
                busy = true;
                Transcribe.cancel = false;
                worker = new Thread(this::work, "transcribe");
                worker.start();
            }
        }
        return START_NOT_STICKY;
    }

    /** Android 15+: een dataSync-service mag maximaal 6 uur per dag lopen. Netjes stoppen. */
    @Override
    public void onTimeout(int startId, int fgsType) {
        Transcribe.cancel = true;
        Process p = Transcribe.proc;
        if (p != null) p.destroy();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void status(JSONObject o) {
        Transcribe.prefs(this).edit().putString("status", o.toString()).apply();
    }

    private void work() {
        PowerManager.WakeLock wl = ((PowerManager) getSystemService(POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "renestools:transcribe");
        wl.acquire(6 * 60 * 60 * 1000L);
        int done = 0, failed = 0;
        String lastError = null;
        try {
            while (true) {
                String model, path;
                int left;
                synchronized (queue) {
                    // Leeg of gestopt: in hetzelfde slot afmelden, zodat een nieuwe opdracht een nieuwe worker start.
                    if (Transcribe.cancel || (modelToGet == null && queue.isEmpty())) {
                        if (Transcribe.cancel) queue.clear();
                        worker = null;
                        busy = false;
                        break;
                    }
                    model = modelToGet; modelToGet = null;
                    path = model == null ? queue.remove(0) : null;
                    left = queue.size();
                }
                if (model != null) {
                    Transcribe.Model m = Transcribe.model(model);
                    try {
                        if (m == null) throw new Exception("Onbekend model");
                        Transcribe.download(this, m, (phase, pct) -> progress("download", null, pct, 0));
                        Transcribe.prefs(this).edit().putString("model", m.id).apply();
                    } catch (Throwable e) {
                        if (!Transcribe.cancel) { lastError = "Model downloaden: " + msg(e); failed++; }
                    }
                    continue;
                }
                final File f = new File(path);
                final int rest = left;
                try {
                    Transcribe.transcribeOne(this, f, (phase, pct) -> progress(phase, f.getName(), pct, rest));
                    done++;
                } catch (Throwable e) {
                    if (!Transcribe.cancel) { lastError = f.getName() + ": " + msg(e); failed++; }
                }
            }
        } finally {
            boolean stopped = Transcribe.cancel;
            if (wl.isHeld()) wl.release();
            synchronized (queue) {
                if (worker == Thread.currentThread()) worker = null; // onverwacht afgebroken
                if (worker == null) {
                    busy = false;
                    try {
                        JSONObject o = new JSONObject().put("running", false).put("done", done).put("failed", failed).put("stopped", stopped);
                        if (lastError != null) o.put("error", lastError);
                        status(o);
                    } catch (Exception ignored) { }
                    finished(done, failed, lastError, stopped);
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                }
                // Anders is er intussen een nieuwe worker gestart; die rondt zelf af.
            }
        }
    }

    private long lastUpdate = 0;

    private void progress(String phase, String file, int pct, int left) {
        long now = System.currentTimeMillis();
        if (now - lastUpdate < 500 && pct < 100) return;
        lastUpdate = now;
        try {
            JSONObject o = new JSONObject().put("running", true).put("phase", phase).put("pct", pct).put("left", left);
            if (file != null) o.put("file", file);
            status(o);
        } catch (Exception ignored) { }
        String text = "download".equals(phase) ? "Spraakmodel downloaden" : "decode".equals(phase) ? "Opname voorbereiden" : "Uitschrijven";
        if (left > 0) text += " · nog " + left + " in de wachtrij";
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID,
                notif(file == null ? "Gesprekken uitschrijven" : file, text, pct));
    }

    private static String msg(Throwable e) {
        if (e instanceof OutOfMemoryError) return "onvoldoende geheugen";
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private void finished(int done, int failed, String err, boolean stopped) {
        if (stopped || (done == 0 && failed == 0)) return;
        String title = failed == 0 ? done + (done == 1 ? " gesprek uitgeschreven" : " gesprekken uitgeschreven")
                : "Uitschrijven: " + failed + " mislukt";
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(DONE_ID,
                b.setSmallIcon(R.drawable.ic_notif).setContentTitle(title).setContentText(err == null ? "Tik om te bekijken" : err)
                        .setAutoCancel(true).setContentIntent(open(this)).build());
    }

    private Notification notif(String title, String text, int pct) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_notif).setContentTitle(title).setContentText(text).setOngoing(true)
                .setOnlyAlertOnce(true).setContentIntent(open(this));
        if (pct >= 0) b.setProgress(100, pct, false); else b.setProgress(0, 0, true);
        return b.build();
    }

    static android.app.PendingIntent open(Context c) {
        Intent i = new Intent(c, MainActivity.class).putExtra("open", "transcripts").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return android.app.PendingIntent.getActivity(c, 21, i, android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void createChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Gesprekken uitschrijven", NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager) c.getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
