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

/**
 * Bronnen van Toiletten en douches vanzelf scannen (elke dag, week of maand per bron; standaard alleen via wifi).
 * Pas gepland als Rene de gegevens een keer zelf heeft opgehaald (Toiletten.ingesteld). Geen meldingen: het resultaat
 * staat bij de bron (laatste scan, nieuw, gewijzigd, weg).
 */
public class ToilettenJob extends JobService {

    static final int JOB_ID = 4701, JOB_TIJD = 4702, JOB_WIFI = 4703;

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context ctx = getApplicationContext();
        if (!App.unlocked(ctx)) return false;
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        if (ToilettenScan.BEZIG.get()) { h.post(() -> { try { schedule(ctx); } catch (Throwable ignored) { } }); return false; }
        new Thread(() -> {
            try { ToilettenScan.scan(ctx, "", true); }
            catch (Throwable ignored) { }
            finally {
                jobFinished(params, false);
                h.post(() -> { try { schedule(ctx); } catch (Throwable ignored) { } });
            }
        }, "toiletten-scan").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return true; }

    /** Het eerstvolgende moment: {met elk internet, alleen wifi}; na een fout de volgende poging na "opnieuw" uur. */
    static long[] volgende(Context c, long nu) { return volgende(Toiletten.bronnen(c), Toiletten.toestand(c), nu, Regio.zone(c)); }

    /** Zonder Android (getest in ToilettenTest): bronnen, toestand per bron, nu en de app-zone. */
    static long[] volgende(JSONArray l, JSONObject st, long nu, java.util.TimeZone tz) {
        long any = Long.MAX_VALUE, wifi = Long.MAX_VALUE;
        for (int i = 0; i < l.length(); i++) {
            JSONObject b = l.optJSONObject(i);
            if (b == null || !b.optBoolean("aan", true) || "uit".equals(b.optString("schema"))) continue;
            long m = Facturen.gepland(b, nu, true, tz);
            JSONObject t = st.optJSONObject(b.optString("id"));
            if (t == null) t = new JSONObject();
            if (t.optLong("lastOk") < Facturen.gepland(b, nu, false, tz))
                m = Math.min(m, Math.max(nu + 60_000L, t.optLong("lastTry") + b.optInt("opnieuw", 6) * 3_600_000L));
            if (b.optBoolean("wifi")) wifi = Math.min(wifi, m); else any = Math.min(any, m);
        }
        return new long[]{any, wifi};
    }

    static boolean nodig(Context c) {
        if (!Toiletten.ingesteld(c)) return false;
        JSONArray l = Toiletten.bronnen(c);
        for (int i = 0; i < l.length(); i++) {
            JSONObject b = l.optJSONObject(i);
            if (b != null && b.optBoolean("aan", true) && !"uit".equals(b.optString("schema"))) return true;
        }
        return false;
    }

    private static void plan(JobScheduler js, Context c, int id, long op, long nu, int net) {
        if (op == Long.MAX_VALUE) { js.cancel(id); return; }
        JobInfo.Builder b = new JobInfo.Builder(id, new ComponentName(c, ToilettenJob.class))
                .setMinimumLatency(Math.max(0, op - nu)).setOverrideDeadline(Math.max(0, op - nu) + 12 * 3_600_000L)
                .setRequiredNetworkType(net).setPersisted(true);
        if (Build.VERSION.SDK_INT >= 26) b.setRequiresBatteryNotLow(true);
        js.schedule(b.build());
    }

    /** Plannen (niet aanroepen terwijl een van deze taken loopt). Elke dag ook een controle die gemiste momenten inhaalt. */
    static void schedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        if (!nodig(c)) { js.cancel(JOB_ID); js.cancel(JOB_TIJD); js.cancel(JOB_WIFI); return; }
        long nu = System.currentTimeMillis();
        long[] v = volgende(c, nu);
        plan(js, c, JOB_TIJD, v[0], nu, JobInfo.NETWORK_TYPE_ANY);
        plan(js, c, JOB_WIFI, v[1], nu, JobInfo.NETWORK_TYPE_UNMETERED);
        if (js.getPendingJob(JOB_ID) != null) return;
        JobInfo.Builder b = new JobInfo.Builder(JOB_ID, new ComponentName(c, ToilettenJob.class))
                .setPeriodic(86_400_000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true);
        if (Build.VERSION.SDK_INT >= 26) b.setRequiresBatteryNotLow(true);
        js.schedule(b.build());
    }
}
