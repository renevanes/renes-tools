package nl.rene.tools;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Facturen ophalen op de achtergrond: één keer per dag kijken welke leveranciers aan de beurt zijn (elke dag, week of
 * maand) en die na elkaar ophalen, alleen met internet. Meldingen bij nieuwe facturen of als inloggen niet lukt.
 */
public class FacturenJob extends JobService {

    static final int JOB_ID = 4401, JOB_NOG = 4402;
    static volatile boolean gestopt;

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context ctx = getApplicationContext();
        if (!App.unlocked(ctx)) return false;
        final List<String> beurt = aanDeBeurt(ctx, System.currentTimeMillis());
        if (beurt.isEmpty()) return false;
        gestopt = false;
        // Eén leverancier per keer (Android geeft een taak ±10 minuten); de volgende komt een minuut later
        String id = beurt.get(0);
        String err = FacturenRun.begin(ctx, id, null, r -> {
            try {
                JSONObject lev = Facturen.leverancier(ctx, id);
                if (lev != null) Facturen.melding(ctx, lev, r);
            } catch (Throwable ignored) {
            } finally {
                if (!gestopt) nog(ctx);
                jobFinished(params, false);
            }
        });
        if (!err.isEmpty()) return false; // er loopt al iets (met de hand gestart): de volgende dag weer
        return true;
    }

    /** Zijn er na deze nog leveranciers aan de beurt? Dan over een minuut nog een keer. */
    static void nog(Context c) {
        if (aanDeBeurt(c, System.currentTimeMillis()).isEmpty()) return;
        JobScheduler js = (JobScheduler) c.getSystemService(JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        js.schedule(new JobInfo.Builder(JOB_NOG, new ComponentName(c, FacturenJob.class))
                .setMinimumLatency(60_000L).setOverrideDeadline(6 * 3_600_000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build());
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        gestopt = true;
        FacturenRun r = FacturenRun.LOPEND.get();
        if (r != null && !r.zichtbaar) r.stoppen();
        return true; // later opnieuw
    }

    /** Leveranciers waarvoor het nu tijd is (en die niet al bezig zijn). */
    static List<String> aanDeBeurt(Context c, long nu) {
        List<String> out = new ArrayList<>();
        JSONArray l = Facturen.lijst(c);
        for (int i = 0; i < l.length(); i++) {
            JSONObject lev = l.optJSONObject(i);
            if (lev == null) continue;
            JSONObject t = Facturen.toestand(c, lev.optString("id"));
            if (Facturen.aanDeBeurt(lev.optString("schema", "week"), t.optLong("lastOk"), t.optLong("lastTry"), nu)) out.add(lev.optString("id"));
        }
        return out;
    }

    static boolean nodig(Context c) {
        JSONArray l = Facturen.lijst(c);
        for (int i = 0; i < l.length(); i++) if (!"uit".equals(l.optJSONObject(i) == null ? "uit" : l.optJSONObject(i).optString("schema"))) return true;
        return false;
    }

    /** Elke dag kijken (of niet, als alles uit staat). */
    static void schedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        if (!nodig(c)) { js.cancel(JOB_ID); js.cancel(JOB_NOG); return; }
        if (js.getPendingJob(JOB_ID) != null) return;
        JobInfo.Builder b = new JobInfo.Builder(JOB_ID, new ComponentName(c, FacturenJob.class))
                .setPeriodic(86_400_000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true);
        if (Build.VERSION.SDK_INT >= 26) b.setRequiresBatteryNotLow(true);
        js.schedule(b.build());
    }
}
