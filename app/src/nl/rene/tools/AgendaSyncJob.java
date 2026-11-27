package nl.rene.tools;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import org.json.JSONObject;

/**
 * Agenda synchroniseren op een vaste tijd (elk kwartier tot elke dag, of alleen met de hand), eventueel alleen tijdens
 * het laden. Geen internet nodig: Tools leest en schrijft de agenda van de telefoon; die stuurt het zelf naar Google.
 */
public class AgendaSyncJob extends JobService {

    static final int JOB_ID = 4901;

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context ctx = getApplicationContext();
        if (!App.unlocked(ctx)) return false;
        new Thread(App.veilig(() -> {
            try { AgendaSync.ronde(ctx, "plan", ""); }
            finally { jobFinished(params, false); }
        }), "agenda-sync").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return true; }

    /** Hoe vaak, in minuten; 0 = niet vanzelf. */
    static int elke(JSONObject cfg) {
        if (!AgendaSyncRegels.aan(cfg)) return 0;
        return Math.max(0, cfg.optInt("elke", 60));
    }

    /** Plannen of weghalen, volgens de instellingen. Laat een taak met dezelfde planning staan. */
    static void schedule(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            JSONObject cfg = AgendaSync.instellingen(c);
            int min = elke(cfg);
            if (min <= 0 || !AgendaCal.lezen(c)) { js.cancel(JOB_ID); return; }
            long ms = Math.max(15, min) * 60_000L;
            boolean laden = cfg.optBoolean("laden", false);
            JobInfo nu = js.getPendingJob(JOB_ID);
            if (nu != null && nu.isPeriodic() && nu.getIntervalMillis() == ms && nu.isRequireCharging() == laden) return;
            JobInfo.Builder b = new JobInfo.Builder(JOB_ID, new ComponentName(c, AgendaSyncJob.class))
                    .setPeriodic(ms).setRequiresCharging(laden).setPersisted(true);
            js.schedule(b.build());
        } catch (Throwable t) {
            App.log(c, "AGENDA", "sync plannen: " + t.getClass().getSimpleName());
        }
    }
}
