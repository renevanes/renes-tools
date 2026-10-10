package nl.rene.tools;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Automatische Spotify-backup (elke dag of elke week), alleen met internet. */
public class SpotifyJob extends JobService {

    static final int JOB_ID = 4301;

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context ctx = getApplicationContext();
        if (!App.unlocked(ctx) || !Spotify.linked(ctx) || "off".equals(mode(ctx))) return false;
        new Thread(() -> {
            try { Spotify.backup(ctx); } catch (Throwable ignored) { } // de fout staat bij "laatste backup" in de app
            jobFinished(params, false);
        }, "spotify-backup").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) { Spotify.cancel = true; return true; } // netjes stoppen; later nog eens

    static String mode(Context c) { return Spotify.prefs(c).getString("auto", "day"); }

    /** Plannen (of weghalen) volgens de keuze: off, day of week. */
    static void schedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        String m = mode(c);
        if (!Spotify.linked(c) || "off".equals(m)) { js.cancel(JOB_ID); return; }
        long period = "week".equals(m) ? 7 * 86_400_000L : 86_400_000L;
        JobInfo cur = js.getPendingJob(JOB_ID);
        if (cur != null && cur.getIntervalMillis() == period) return;
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, SpotifyJob.class))
                .setPeriodic(period)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build());
    }
}
