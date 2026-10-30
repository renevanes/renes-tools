package nl.rene.tools;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;

/**
 * Taak die geregeld (elke paar uur) de gebeurtenissen uit UsageStatsManager overneemt naar de eigen opslag, zodat er een
 * lange geschiedenis blijft ook al houdt Android de details maar een paar dagen bij. Alles gebeurt op de telefoon.
 */
public class GebruikJob extends JobService {

    static final int JOB_ID = 4320;
    static final long EVERY = 3L * 3600_000L; // elke 3 uur

    @Override public boolean onStartJob(final JobParameters params) {
        final Context ctx = getApplicationContext();
        new Thread(() -> {
            try { GebruikStore.collect(ctx); } catch (Throwable t) { App.log(ctx, "GEBRUIK", "taak: " + t); }
            try { jobFinished(params, false); } catch (Throwable ignored) { }
        }, "gebruik-job").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) { return true; }

    static void ensureScheduled(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            if (!GebruikStore.collecting(c)) { js.cancel(JOB_ID); return; }
            if (js.getPendingJob(JOB_ID) != null) return;
            JobInfo.Builder b = new JobInfo.Builder(JOB_ID, new ComponentName(c, GebruikJob.class)).setPeriodic(EVERY).setPersisted(true);
            if (Build.VERSION.SDK_INT >= 26) b.setRequiresBatteryNotLow(false);
            js.schedule(b.build());
        } catch (Exception ignored) { }
    }
}
