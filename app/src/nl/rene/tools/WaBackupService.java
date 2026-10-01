package nl.rene.tools;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/** Voorgrondservice voor een handmatige backup of het terugzetten, met voortgang in de meldingenbalk. */
public class WaBackupService extends Service {

    static final String ACTION_BACKUP = "nl.rene.tools.wa.BACKUP";
    static final String ACTION_RESTORE = "nl.rene.tools.wa.RESTORE";
    static final String ACTION_CANCEL = "nl.rene.tools.wa.CANCEL";
    static final String CHANNEL = "wabackup";
    static final int NOTIF_ID = 2001;
    static final int DONE_ID = 2002;

    private PowerManager.WakeLock wake;
    private volatile boolean mine = false; // draait deze service zelf een backup?
    private long lastNotif = 0;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        if (ACTION_CANCEL.equals(a)) {
            WaBackup.cancel = true;
            return START_NOT_STICKY;
        }
        if (!ACTION_BACKUP.equals(a) && !ACTION_RESTORE.equals(a)) { if (!WaBackup.busy) stopSelf(); return START_NOT_STICKY; }
        // Altijd direct startForeground: Android eist dat na startForegroundService.
        createChannel(this);
        Notification n = build("Voorbereiden…", 0, 0, true, ACTION_RESTORE.equals(a));
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTIF_ID, n);
        if (mine) return START_NOT_STICKY; // loopt al
        if (WaBackup.busy) { // de automatische backup is bezig
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        mine = true;

        final boolean restore = ACTION_RESTORE.equals(a);
        final String mode = intent.getStringExtra("mode");
        final String cats = intent.getStringExtra("cats");
        WaBackup.busy = true;
        WaBackup.cancel = false;
        WaBackup.cancelMsg = null;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RenesTools:wabackup");
        wake.setReferenceCounted(false);
        wake.acquire(12 * 60 * 60 * 1000L);
        final Context ctx = getApplicationContext();
        new Thread(() -> {
            WaBackup.Status st;
            WaBackup.Listener l = s -> {
                WaBackup.saveStatus(ctx, s);
                long now = System.currentTimeMillis();
                if (s.running && now - lastNotif > 1000) { lastNotif = now; progressNotif(s, restore); }
            };
            try {
                st = restore ? WaBackup.restore(ctx, l) : WaBackup.backup(ctx, mode, WaBackup.parseCats(cats), l);
            } finally {
                WaBackup.busy = false;
            }
            done(st, restore);
        }, "wabackup").start();
        return START_NOT_STICKY;
    }

    private void progressNotif(WaBackup.Status s, boolean restore) {
        String t;
        int max = 1000, prog = 0;
        boolean ind = true;
        if ("copy".equals(s.phase) && s.bytesTotal > 0) {
            ind = false;
            prog = (int) (max * s.bytesDone / Math.max(1, s.bytesTotal));
            t = s.filesDone + " van " + s.filesTotal + " · " + fmt(s.bytesDone) + " van " + fmt(s.bytesTotal);
        } else {
            t = "compare".equals(s.phase) ? "Vergelijken met de backup…" : "Bestanden zoeken…";
        }
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, build(t, max, prog, ind, restore));
    }

    private void done(WaBackup.Status st, boolean restore) {
        mine = false;
        if (wake != null && wake.isHeld()) wake.release();
        stopForeground(true);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        String title = restore ? "Terugzetten" : "WhatsApp backup";
        title += "ok".equals(st.result) ? " klaar" : "partial".equals(st.result) ? " klaar met fouten" :
                "cancelled".equals(st.result) ? " gestopt" : " mislukt";
        nm.notify(DONE_ID, b.setSmallIcon(R.drawable.ic_backup).setContentTitle(title)
                .setContentText(st.message + (st.bytesDone > 0 ? " (" + fmt(st.bytesDone) + ")" : ""))
                .setAutoCancel(true).setContentIntent(open(this)).build());
        stopSelf();
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        // Android 15+: dataSync mag maximaal 6 uur per dag. Netjes stoppen; de volgende keer gaat hij verder.
        WaBackup.cancelMsg = "Tijdslimiet van Android bereikt. Start opnieuw om verder te gaan.";
        WaBackup.cancel = true;
        stopForeground(true);
        stopSelf();
    }

    private Notification build(String text, int max, int prog, boolean ind, boolean restore) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_backup)
                .setContentTitle(restore ? "WhatsApp terugzetten" : "WhatsApp backup")
                .setContentText(text)
                .setProgress(max, prog, ind)
                .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
                .setContentIntent(open(this))
                .addAction(new Notification.Action.Builder(null, "Stoppen",
                        PendingIntent.getService(this, 21, new Intent(this, WaBackupService.class).setAction(ACTION_CANCEL),
                                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    static PendingIntent open(Context c) {
        Intent i = new Intent(c, MainActivity.class).putExtra("open", "wa").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 20, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void createChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "WhatsApp backup", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Voortgang en resultaat van de WhatsApp-backup");
        ((NotificationManager) c.getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
    }

    static String fmt(long b) {
        if (b < 1024 * 1024) return Math.max(1, b / 1024) + " kB";
        if (b < 1024L * 1024 * 1024) return String.format(java.util.Locale.GERMANY, "%.1f MB", b / 1048576.0);
        return String.format(java.util.Locale.GERMANY, "%.2f GB", b / 1073741824.0);
    }
}
