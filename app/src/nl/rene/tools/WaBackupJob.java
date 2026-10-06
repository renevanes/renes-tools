package nl.rene.tools;

import android.app.NotificationManager;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.Calendar;

/**
 * Automatische nachtelijke backup. Draait na 03:00 (WhatsApp maakt om 02:00 zijn
 * eigen backup), eventueel alleen tijdens het opladen. Android geeft een taak
 * maar beperkte tijd; wordt hij onderbroken, dan gaat hij later verder waar hij was.
 */
public class WaBackupJob extends JobService {

    static final int JOB_ID = 4201;
    static final long RETRY_MS = 60 * 60 * 1000L;
    private volatile boolean stopped = false;

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context ctx = getApplicationContext();
        SharedPreferences p = WaBackup.prefs(ctx);
        if (!p.getBoolean("auto", false)) return false;
        if (WaBackup.busy || !MainActivity.hasFilesAccess(ctx) || WaBackup.destUri(ctx) == null) {
            schedule(ctx, RETRY_MS); // over een uur opnieuw proberen
            return false;
        }
        final String cats = "selection".equals(p.getString("autoMode", "all")) ? p.getString("sel", WaBackup.ALL) : WaBackup.ALL;
        WaBackup.busy = true;
        WaBackup.cancel = false;
        WaBackup.cancelMsg = null;
        new Thread(() -> {
            WaBackup.Status st;
            try {
                st = WaBackup.backup(ctx, "auto", WaBackup.parseCats(cats), s -> WaBackup.saveStatus(ctx, s));
                if (!stopped && ("ok".equals(st.result) || "partial".equals(st.result)) && WaChats.autoReadable(ctx))
                    WaChats.makeReadable(ctx, s -> WaBackup.saveStatus(ctx, s));
            } finally {
                WaBackup.busy = false;
            }
            if (stopped) return; // Android herstart de taak zelf (onStopJob gaf true)
            boolean userStopped = "cancelled".equals(st.result);
            if ("ok".equals(st.result) || "partial".equals(st.result) || userStopped)
                WaBackup.prefs(ctx).edit().putLong("lastAutoDay", System.currentTimeMillis()).apply(); // klaar voor vandaag
            if ("error".equals(st.result) || "partial".equals(st.result)) notifyProblem(ctx, st);
            jobFinished(params, false); // eerst afmelden; opnieuw plannen met hetzelfde id stopt anders deze taak
            schedule(ctx, "error".equals(st.result) ? RETRY_MS : 0);
        }, "wabackup-auto").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        stopped = true;
        WaBackup.cancelMsg = "Onderbroken door Android, gaat later vanzelf verder";
        WaBackup.cancel = true;
        return true; // later opnieuw proberen
    }

    private static void notifyProblem(Context c, WaBackup.Status st) {
        WaBackupService.createChannel(c);
        android.app.Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new android.app.Notification.Builder(c, NotifCenter.ch(c, WaBackupService.CHANNEL)) : new android.app.Notification.Builder(c);
        NotifCenter.post(c, WaBackupService.DONE_ID,
                b.setSmallIcon(R.drawable.ic_backup).setContentTitle("Automatische WhatsApp-backup: probleem")
                        .setContentText(st.message).setAutoCancel(true).setContentIntent(WaBackupService.open(c)).build());
    }

    /** Zorgt dat er een automatische backup gepland staat, zonder een lopende te onderbreken. */
    static void ensureScheduled(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(JOB_SCHEDULER_SERVICE);
        boolean auto = WaBackup.prefs(c).getBoolean("auto", false);
        if (auto == (js.getPendingJob(JOB_ID) != null)) return;
        schedule(c);
    }

    /** Plant de volgende automatische backup (na 03:00), of annuleert hem als automatisch uit staat. */
    static void schedule(Context c) { schedule(c, 0); }

    static void schedule(Context c, long minDelay) {
        JobScheduler js = (JobScheduler) c.getSystemService(JOB_SCHEDULER_SERVICE);
        SharedPreferences p = WaBackup.prefs(c);
        if (!p.getBoolean("auto", false)) { js.cancel(JOB_ID); p.edit().remove("nextAuto").apply(); return; }
        long next = Math.max(nextRun(p.getLong("lastAutoDay", 0)), System.currentTimeMillis() + minDelay);
        JobInfo.Builder b = new JobInfo.Builder(JOB_ID, new ComponentName(c, WaBackupJob.class))
                .setMinimumLatency(Math.max(0, next - System.currentTimeMillis()))
                .setRequiresCharging(p.getBoolean("autoCharging", true))
                .setPersisted(true)
                .setBackoffCriteria(5 * 60 * 1000L, JobInfo.BACKOFF_POLICY_LINEAR);
        if (Build.VERSION.SDK_INT >= 26) b.setRequiresBatteryNotLow(true);
        js.schedule(b.build());
        p.edit().putLong("nextAuto", next).apply();
    }

    /**
     * Eerstvolgende run: vandaag 03:00 als die nog moet komen; zo snel mogelijk als 03:00
     * vandaag voorbij is maar er sinds 03:00 nog geen backup gelukt is; anders morgen 03:00.
     */
    static long nextRun(long lastOk) {
        Calendar c = Calendar.getInstance();
        long now = c.getTimeInMillis();
        c.set(Calendar.HOUR_OF_DAY, 3);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long today3 = c.getTimeInMillis();
        if (now < today3) {
            c.add(Calendar.DAY_OF_MONTH, -1);
            return lastOk >= c.getTimeInMillis() ? today3 : now; // gisternacht gemist: nu inhalen
        }
        if (lastOk >= today3) { c.add(Calendar.DAY_OF_MONTH, 1); return c.getTimeInMillis(); }
        return now;
    }
}
