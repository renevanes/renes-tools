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

    static final int JOB_ID = 4401, JOB_NOG = 4402, JOB_TIJD = 4403, JOB_WIFI = 4404;
    static volatile boolean gestopt;

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context ctx = getApplicationContext();
        if (!App.unlocked(ctx)) return false;
        final List<String> beurt = aanDeBeurt(ctx, System.currentTimeMillis());
        // Opnieuw plannen pas ná deze taak (een taak met dezelfde id herplannen terwijl hij loopt, stopt hem)
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        if (beurt.isEmpty()) { h.post(() -> schedule(ctx)); return false; }
        gestopt = false;
        // Eén leverancier per keer (Android geeft een taak ±10 minuten); de volgende komt een minuut later
        String id = beurt.get(0);
        String err = FacturenRun.begin(ctx, id, null, r -> {
            try {
                JSONObject lev = Facturen.leverancier(ctx, id);
                if (lev != null && (r.optBoolean("ok") || eersteFout(ctx, lev))) Facturen.melding(ctx, lev, r);
            } catch (Throwable ignored) {
            } finally {
                final boolean verder = !gestopt;
                jobFinished(params, false);
                h.post(() -> { try { schedule(ctx); if (verder) nog(ctx); } catch (Throwable ignored) { } });
            }
        });
        if (!err.isEmpty()) { h.post(() -> schedule(ctx)); return false; } // er loopt al iets (met de hand gestart): later opnieuw
        FacturenRun r = FacturenRun.LOPEND.get();
        if (r != null) r.klaarAuto = true;
        return true;
    }

    /** Alleen bij de eerste mislukte poging van een periode een melding (niet bij elke nieuwe poging). */
    static boolean eersteFout(Context c, JSONObject lev) {
        long g = Facturen.gepland(lev, System.currentTimeMillis(), false, java.util.TimeZone.getDefault());
        JSONArray log = Facturen.toestand(c, lev.optString("id")).optJSONArray("log");
        int fouten = 0;
        for (int i = 0; log != null && i < log.length(); i++) { JSONObject r = log.optJSONObject(i); if (r != null && !r.optBoolean("ok") && r.optLong("t") >= g) fouten++; }
        return fouten <= 1;
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
        boolean betaald = true; // mobiel internet (kan geld kosten)?
        try { android.net.ConnectivityManager cm = (android.net.ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE); betaald = cm == null || cm.isActiveNetworkMetered(); } catch (Throwable ignored) { }
        for (int i = 0; i < l.length(); i++) {
            JSONObject lev = l.optJSONObject(i);
            if (lev == null) continue;
            if (lev.optBoolean("wifi") && betaald) continue; // wacht op wifi (de dagelijkse controle haalt het in)
            JSONObject t = Facturen.toestand(c, lev.optString("id"));
            if (Facturen.aanDeBeurt(lev, t.optLong("lastOk"), t.optLong("lastTry"), nu, java.util.TimeZone.getDefault())) out.add(lev.optString("id"));
        }
        return out;
    }

    static boolean nodig(Context c) {
        JSONArray l = Facturen.lijst(c);
        for (int i = 0; i < l.length(); i++) if (!"uit".equals(l.optJSONObject(i) == null ? "uit" : l.optJSONObject(i).optString("schema"))) return true;
        return false;
    }

    /**
     * Het eerstvolgende moment om te kijken: {met elk internet, alleen wifi}. Per leverancier het volgende vaste moment,
     * of (staat er nog iets open van deze periode) de volgende poging na "opnieuw" uur.
     */
    static long[] volgende(Context c, long nu) {
        long any = Long.MAX_VALUE, wifi = Long.MAX_VALUE;
        java.util.TimeZone tz = java.util.TimeZone.getDefault();
        JSONArray l = Facturen.lijst(c);
        for (int i = 0; i < l.length(); i++) {
            JSONObject lev = l.optJSONObject(i);
            if (lev == null || "uit".equals(lev.optString("schema"))) continue;
            long m = Facturen.gepland(lev, nu, true, tz);
            JSONObject t = Facturen.toestand(c, lev.optString("id"));
            if (t.optLong("lastOk") < Facturen.gepland(lev, nu, false, tz)) // nog open: opnieuw proberen
                m = Math.min(m, Math.max(nu + 60_000L, t.optLong("lastTry") + lev.optInt("opnieuw", 3) * 3_600_000L));
            if (lev.optBoolean("wifi")) wifi = Math.min(wifi, m); else any = Math.min(any, m);
        }
        return new long[]{any, wifi};
    }

    private static void plan(JobScheduler js, Context c, int id, long op, long nu, int net) {
        if (op == Long.MAX_VALUE) { js.cancel(id); return; }
        js.schedule(new JobInfo.Builder(id, new ComponentName(c, FacturenJob.class))
                .setMinimumLatency(Math.max(0, op - nu)).setOverrideDeadline(Math.max(0, op - nu) + 6 * 3_600_000L)
                .setRequiredNetworkType(net).setPersisted(true).build());
    }

    /**
     * Plannen: een taak op het eerstvolgende moment (met internet, of alleen wifi voor leveranciers die dat willen;
     * Android kan hem bij een slapende telefoon iets later starten), plus elke dag een controle die gemiste momenten
     * inhaalt. Alles uit: niets gepland. Niet aanroepen terwijl een van deze taken loopt (dan stopt Android hem).
     */
    static void schedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        if (!nodig(c)) { js.cancel(JOB_ID); js.cancel(JOB_NOG); js.cancel(JOB_TIJD); js.cancel(JOB_WIFI); return; }
        long nu = System.currentTimeMillis();
        long[] v = volgende(c, nu);
        plan(js, c, JOB_TIJD, v[0], nu, JobInfo.NETWORK_TYPE_ANY);
        plan(js, c, JOB_WIFI, v[1], nu, JobInfo.NETWORK_TYPE_UNMETERED);
        if (js.getPendingJob(JOB_ID) != null) return;
        JobInfo.Builder b = new JobInfo.Builder(JOB_ID, new ComponentName(c, FacturenJob.class))
                .setPeriodic(86_400_000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true);
        if (Build.VERSION.SDK_INT >= 26) b.setRequiresBatteryNotLow(true);
        js.schedule(b.build());
    }
}
