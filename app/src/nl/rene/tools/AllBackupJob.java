package nl.rene.tools;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.Iterator;

/** Nachtelijke backup van alles (na 03:30, na de WhatsApp-backup van 03:00). */
public class AllBackupJob extends JobService {

    static final int JOB_ID = 4203;

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context ctx = getApplicationContext();
        if (!AllBackup.prefs(ctx).getBoolean("auto", false)) return false;
        if (!AllBackup.tryBegin()) {
            // Er loopt al een backup: over een uur opnieuw (pas plannen nadat deze taak is afgemeld).
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> schedule(ctx, 60 * 60 * 1000L));
            return false;
        }
        new Thread(() -> {
            JSONObject r = AllBackup.run(ctx, true);
            if (r.optBoolean("stopped")) return; // Android stopte de taak; die herhaalt hem later zelf
            AllBackup.prefs(ctx).edit().putLong("lastAutoDay", System.currentTimeMillis()).apply();
            notifyIfFailed(ctx, r);
            jobFinished(params, false);
            schedule(ctx, 0);
        }, "allbackup-auto").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        AllBackup.stop = true; // stopt na het lopende onderdeel
        return true;
    }

    /** Alleen een melding als er iets misging; een geslaagde nachtelijke backup is stil. */
    static void notifyIfFailed(Context c, JSONObject r) {
        int failed = 0;
        String first = null;
        for (Iterator<String> it = r.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject o = r.optJSONObject(k);
            if (o != null && !o.optBoolean("ok", true)) { failed++; if (first == null) first = o.optString("msg"); }
        }
        if (failed == 0) return;
        WaBackupService.createChannel(c);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, WaBackupService.CHANNEL) : new Notification.Builder(c);
        android.content.Intent open = new android.content.Intent(c, MainActivity.class).putExtra("open", "backup")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
        ((NotificationManager) c.getSystemService(NOTIFICATION_SERVICE)).notify(4204,
                b.setSmallIcon(R.drawable.ic_backup).setContentTitle("Nachtelijke backup: " + failed + (failed == 1 ? " onderdeel" : " onderdelen") + " mislukt")
                        .setContentText(first == null ? "" : first).setAutoCancel(true)
                        .setContentIntent(android.app.PendingIntent.getActivity(c, 24, open,
                                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT)).build());
    }

    static void schedule(Context c, long minDelay) {
        JobScheduler js = (JobScheduler) c.getSystemService(JOB_SCHEDULER_SERVICE);
        SharedPreferences p = AllBackup.prefs(c);
        if (!p.getBoolean("auto", false)) { js.cancel(JOB_ID); p.edit().remove("next").apply(); return; }
        long next = Math.max(nextRun(p.getLong("lastAutoDay", 0)), System.currentTimeMillis() + minDelay);
        JobInfo.Builder b = new JobInfo.Builder(JOB_ID, new ComponentName(c, AllBackupJob.class))
                .setMinimumLatency(Math.max(0, next - System.currentTimeMillis()))
                .setRequiresCharging(p.getBoolean("charging", true))
                .setPersisted(true)
                .setBackoffCriteria(10 * 60 * 1000L, JobInfo.BACKOFF_POLICY_LINEAR);
        if (Build.VERSION.SDK_INT >= 26) b.setRequiresBatteryNotLow(true);
        js.schedule(b.build());
        p.edit().putLong("next", next).apply();
    }

    static void ensureScheduled(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(JOB_SCHEDULER_SERVICE);
        boolean auto = AllBackup.prefs(c).getBoolean("auto", false);
        if (auto == (js.getPendingJob(JOB_ID) != null)) return;
        schedule(c, 0);
    }

    /** Eerstvolgende 03:30; is de backup van vannacht gemist, dan zo snel mogelijk. */
    static long nextRun(long lastOk) {
        Calendar c = Calendar.getInstance();
        long now = c.getTimeInMillis();
        c.set(Calendar.HOUR_OF_DAY, 3);
        c.set(Calendar.MINUTE, 30);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long today = c.getTimeInMillis();
        if (now < today) {
            c.add(Calendar.DAY_OF_MONTH, -1);
            return lastOk >= c.getTimeInMillis() ? today : now;
        }
        if (lastOk >= today) { c.add(Calendar.DAY_OF_MONTH, 1); return c.getTimeInMillis(); }
        return now;
    }
}
