package nl.rene.tools;

import android.Manifest;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/**
 * Legt een paar keer per dag vast of de contacten veranderd zijn, zodat de versiegeschiedenis
 * ook wijzigingen bevat die buiten de app zijn gedaan. Een versie komt er alleen bij een wijziging.
 */
public class ContactsJob extends JobService {

    static final int JOB_ID = 4202;
    static final long PERIOD = 6 * 60 * 60 * 1000L;

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context ctx = getApplicationContext();
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return false;
        new Thread(() -> {
            try { Contacts.snapshot(ctx); } catch (Throwable ignored) { }
            jobFinished(params, false);
        }, "contacts-snapshot").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return true; }

    static void ensureScheduled(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(JOB_SCHEDULER_SERVICE);
        if (js == null || js.getPendingJob(JOB_ID) != null) return;
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, ContactsJob.class))
                .setPeriodic(PERIOD)
                .setPersisted(true)
                .build());
    }
}
