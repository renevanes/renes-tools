package nl.rene.tools;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * Vanzelf downloaden van nieuwe podcast-afleveringen: een paar keer per dag de gevolgde podcasts met "vanzelf
 * downloaden" bijwerken en de nieuwste afleveringen downloaden (met "alleen via wifi" alleen op wifi).
 */
public class PodcastDownloadJob extends JobService {

    static final int JOB_ID = 4311;
    static final long PERIOD = 6 * 3_600_000L;

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context ctx = getApplicationContext();
        if (!App.unlocked(ctx) || !PodcastDownloads.anyAuto(ctx)) return false;
        new Thread(() -> {
            try {
                Podcasts.refreshAll(ctx);
                PodcastDownloads.autoQueue(ctx);
            } catch (Throwable t) { App.log(ctx, "PODCAST", "vanzelf downloaden: " + t); }
            jobFinished(params, false);
        }, "podcast-auto-dl").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return false; } // de volgende keer weer

    /** Plannen (of weghalen): alleen als er een podcast is met "vanzelf downloaden". */
    static void schedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        if (!PodcastDownloads.anyAuto(c)) { js.cancel(JOB_ID); return; }
        int net = PodcastDownloads.wifiOnly(c) ? JobInfo.NETWORK_TYPE_UNMETERED : JobInfo.NETWORK_TYPE_ANY;
        JobInfo cur = js.getPendingJob(JOB_ID);
        if (cur != null && cur.getIntervalMillis() == PERIOD && cur.getNetworkType() == net) return;
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, PodcastDownloadJob.class))
                .setPeriodic(PERIOD)
                .setRequiredNetworkType(net)
                .setPersisted(true)
                .build());
    }
}
