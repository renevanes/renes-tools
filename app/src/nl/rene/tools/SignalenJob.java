package nl.rene.tools;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Build;

import org.json.JSONArray;

/**
 * Signalen op de achtergrond: bronnen nalopen als ze aan de beurt zijn (per bron elk uur tot elke week, standaard alleen
 * via wifi) en melden op het goede moment (na de stille uren, het dagoverzicht, een herinnering voor later).
 */
public class SignalenJob extends JobService {

    static final int JOB_ID = 4801, JOB_NET = 4802, JOB_WIFI = 4803, JOB_MELD = 4804;

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context ctx = getApplicationContext();
        if (!App.unlocked(ctx)) return false;
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final int job = params.getJobId();
        new Thread(() -> {
            try {
                // zonder internet (de taak loopt dan door zijn uiterste tijd) niet nakijken: dat is geen fout van de bron
                if (job != JOB_MELD) { laatste = System.currentTimeMillis(); if (online(ctx) && !SignalenScan.BEZIG.get()) SignalenScan.scan(ctx, "", true, !metered(ctx)); }
                SignalenScan.meld(ctx);
            } catch (Throwable ignored) { }
            finally {
                jobFinished(params, false);
                h.post(() -> { try { schedule(ctx); } catch (Throwable ignored) { } });
            }
        }, "signalen-scan").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return true; }

    /** Wanneer een taak voor het laatst ging nakijken: daarna minstens een kwartier rust, wat er ook gebeurde. */
    private static volatile long laatste;
    static final long RUST = 15 * 60_000L;

    @SuppressWarnings("deprecation")
    private static boolean online(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Throwable t) { return false; }
    }

    private static boolean metered(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm == null || cm.isActiveNetworkMetered();
        } catch (Throwable t) { return true; }
    }

    static boolean nodig(Context c) {
        JSONArray l = Signalen.bronnen(c);
        for (int i = 0; i < l.length(); i++) if (l.optJSONObject(i) != null && l.optJSONObject(i).optBoolean("aan", true)) return true;
        JSONArray items = Signalen.items(c);
        return items != null && Signalen.volgendeMelding(items, Signalen.perId(l), Signalen.instellingen(c), Signalen.overzichtDag(c), System.currentTimeMillis(), Regio.zone(c)) != Long.MAX_VALUE;
    }

    private static void plan(JobScheduler js, Context c, int id, long op, long nu, int net) {
        if (op == Long.MAX_VALUE) { js.cancel(id); return; }
        if (id != JOB_MELD) op = Math.max(op, laatste + RUST); // nooit in een lus: ook niet als er intussen met de hand wordt nagekeken
        long wacht = Math.max(0, op - nu);
        JobInfo.Builder b = new JobInfo.Builder(id, new ComponentName(c, SignalenJob.class))
                .setMinimumLatency(wacht).setOverrideDeadline(wacht + (id == JOB_MELD ? 15 * 60_000L : 6 * 3_600_000L))
                .setRequiredNetworkType(net).setPersisted(true);
        if (id != JOB_MELD && Build.VERSION.SDK_INT >= 26) b.setRequiresBatteryNotLow(true);
        js.schedule(b.build());
    }

    /** Plannen (niet aanroepen terwijl een van deze taken loopt). Elke dag ook een controle die gemiste momenten inhaalt. */
    static void schedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        if (!nodig(c)) { js.cancel(JOB_ID); js.cancel(JOB_NET); js.cancel(JOB_WIFI); js.cancel(JOB_MELD); return; }
        long nu = System.currentTimeMillis();
        JSONArray bronnen = Signalen.bronnen(c);
        long[] v = Signalen.volgendeScan(bronnen, Signalen.toestand(c), nu);
        plan(js, c, JOB_NET, v[0], nu, JobInfo.NETWORK_TYPE_ANY);
        plan(js, c, JOB_WIFI, v[1], nu, JobInfo.NETWORK_TYPE_UNMETERED);
        JSONArray items = Signalen.items(c);
        plan(js, c, JOB_MELD, items == null ? Long.MAX_VALUE : Signalen.volgendeMelding(items, Signalen.perId(bronnen), Signalen.instellingen(c), Signalen.overzichtDag(c), nu, Regio.zone(c)), nu, JobInfo.NETWORK_TYPE_NONE);
        if (js.getPendingJob(JOB_ID) != null) return;
        JobInfo.Builder b = new JobInfo.Builder(JOB_ID, new ComponentName(c, SignalenJob.class))
                .setPeriodic(86_400_000L).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true);
        if (Build.VERSION.SDK_INT >= 26) b.setRequiresBatteryNotLow(true);
        js.schedule(b.build());
    }
}
