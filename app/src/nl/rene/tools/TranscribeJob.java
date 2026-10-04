package nl.rene.tools;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 's Nachts automatisch nieuwe gespreksopnames uitschrijven, alleen tijdens het opladen.
 * Het werk gebeurt in TranscribeService (voortgangsmelding, geen tijdslimiet). Dat mag Android 12+ op de achtergrond
 * alleen met een batterij-uitzondering; anders komt er een melding om het met één tik te starten.
 */
public class TranscribeJob extends JobService {

    static final int JOB_ID = 4304;
    static final int MAX_PER_NIGHT = 30;
    static final long MAX_AGE = 14L * 24 * 60 * 60 * 1000;

    static SharedPreferences prefs(Context c) { return Transcribe.prefs(c); }

    static boolean enabled(Context c) { return prefs(c).getBoolean("auto", false); }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("auto", on).apply();
        schedule(c, false);
    }

    /** Ids van opnames die mislukten; die worden niet elke nacht opnieuw geprobeerd. */
    static Set<String> failed(Context c) { return new HashSet<>(prefs(c).getStringSet("failedIds", new HashSet<String>())); }

    static synchronized void markFailed(Context c, String id) {
        Set<String> s = failed(c);
        if (s.size() > 500) s.clear();
        s.add(id);
        prefs(c).edit().putStringSet("failedIds", s).apply();
    }

    /** Nieuwe opnames (laatste 14 dagen) die nog niet uitgeschreven zijn, oudste eerst. */
    static List<File> todo(Context c) {
        List<File> out = new ArrayList<>();
        Set<String> bad = failed(c);
        long since = System.currentTimeMillis() - MAX_AGE;
        for (File f : Transcribe.findRecordings(c)) {
            if (f.lastModified() < since) continue;
            String id = Transcribe.id(f);
            if (bad.contains(id) || Transcribe.transcriptFile(c, id).isFile()) continue;
            out.add(f);
        }
        java.util.Collections.sort(out, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        return out.size() > MAX_PER_NIGHT ? out.subList(0, MAX_PER_NIGHT) : out;
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context ctx = getApplicationContext();
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        // Alleen 's nachts (een herhaalde poging kan anders overdag aan de lader starten).
        if (!enabled(ctx) || hour >= 7 || Transcribe.activeModel(ctx) == null || TranscribeService.busy) { schedule(ctx, false); return false; }
        new Thread(() -> {
            try {
                List<File> list = todo(ctx);
                prefs(ctx).edit().putLong("autoLast", System.currentTimeMillis()).putInt("autoFound", list.size()).apply();
                if (list.isEmpty()) return;
                ArrayList<String> paths = new ArrayList<>();
                for (File f : list) paths.add(f.getAbsolutePath());
                try {
                    // Voorgrondservice: met voortgang, zonder tijdslimiet. Mag op de achtergrond alleen met batterij-uitzondering.
                    TranscribeService.run(ctx, paths);
                } catch (Exception e) {
                    App.log(ctx, "TRANSCRIBE", "nachtelijk niet gestart: " + e.getClass().getSimpleName());
                    prefs(ctx).edit().putBoolean("autoBlocked", true).apply();
                    notifyWaiting(ctx, list.size());
                    return;
                }
                prefs(ctx).edit().putBoolean("autoBlocked", false).apply();
            } catch (Throwable e) {
                App.log(ctx, "TRANSCRIBE", "nachtelijk: " + e);
            } finally {
                try { jobFinished(params, false); } catch (Exception ignored) { }
                schedule(ctx, false);
            }
        }, "transcribe-auto").start();
        return true;
    }

    /** Android liet het niet op de achtergrond starten: melding om het met één tik te doen. */
    static void notifyWaiting(Context c, int n) {
        TranscribeService.createChannel(c);
        android.app.Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new android.app.Notification.Builder(c, TranscribeService.CHANNEL) : new android.app.Notification.Builder(c);
        android.app.NotificationManager nm = (android.app.NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(TranscribeService.DONE_ID, b.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(n + (n == 1 ? " nieuwe opname" : " nieuwe opnames") + " om uit te schrijven")
                .setContentText("Tik om te openen. Geef de app een batterij-uitzondering om dit 's nachts vanzelf te laten gaan.")
                .setAutoCancel(true).setContentIntent(TranscribeService.open(c)).build());
    }

    @Override
    public boolean onStopJob(JobParameters params) { return false; } // het echte werk loopt in de voorgrondservice

    /** Volgende run: vanaf de eerstvolgende 01:00, alleen tijdens opladen. */
    static void schedule(Context c, boolean ranToday) {
        JobScheduler js = (JobScheduler) c.getSystemService(JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        if (!enabled(c)) { js.cancel(JOB_ID); return; }
        Calendar cal = Calendar.getInstance();
        long now = cal.getTimeInMillis();
        cal.set(Calendar.HOUR_OF_DAY, 1); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= now) cal.add(Calendar.DAY_OF_MONTH, 1);
        JobInfo.Builder b = new JobInfo.Builder(JOB_ID, new ComponentName(c, TranscribeJob.class))
                .setMinimumLatency(Math.max(0, cal.getTimeInMillis() - now))
                .setRequiresCharging(true)
                .setPersisted(true)
                .setBackoffCriteria(15 * 60 * 1000L, JobInfo.BACKOFF_POLICY_LINEAR);
        if (Build.VERSION.SDK_INT >= 26) b.setRequiresBatteryNotLow(true);
        try { js.schedule(b.build()); prefs(c).edit().putLong("autoNext", cal.getTimeInMillis()).apply(); }
        catch (Exception e) { App.log(c, "TRANSCRIBE", "plannen: " + e); }
    }

    static void ensureScheduled(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        if (enabled(c) == (js.getPendingJob(JOB_ID) != null)) return;
        schedule(c, false);
    }
}
