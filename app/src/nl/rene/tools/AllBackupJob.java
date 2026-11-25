package nl.rene.tools;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.Iterator;

/** Nachtelijke backup van alles (na 03:30, na de WhatsApp-backup van 03:00). */
public class AllBackupJob extends JobService {

    static final int JOB_ID = 4203;

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context ctx = getApplicationContext();
        if (!wanted(ctx)) return false;
        gestopt = false;
        // Android geeft een taak hooguit zo'n tien minuten; het opruimen daarna stopt ruim op tijd (anders stopt de telefoon
        // de app soms hard, en komt het opruimen nooit af)
        final long tot = System.currentTimeMillis() + TAAK_MS;
        // Intussen met de hand een volledige backup gemaakt (na een mislukte nacht): deze nacht is klaar
        if (nextRun(AllBackup.prefs(ctx).getLong("lastAutoDay", 0)) > System.currentTimeMillis() + 60_000L) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> schedule(ctx, 0));
            return false;
        }
        if (!AllBackup.tryBegin()) {
            // Er loopt al een backup: over een uur opnieuw (pas plannen nadat deze taak is afgemeld).
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> schedule(ctx, 60 * 60 * 1000L));
            return false;
        }
        new Thread(App.veilig(() -> {
            JSONObject r = null;
            long wacht = 0;
            try {
                r = AllBackup.run(ctx, true);
                if (r.optBoolean("stopped")) return; // Android stopte de taak; die herhaalt hem later zelf
                SharedPreferences p = AllBackup.prefs(ctx);
                long nu = System.currentTimeMillis();
                // De backup kon niet beginnen of niet afmaken (Google Drive was 's nachts nog aan het laden, geen
                // verbinding): stil opnieuw over een half uur, een uur, anderhalf uur; pas daarna een melding.
                if (nietGelukt(r)) {
                    int poging = poging(p.getLong("pogingSinds", 0), p.getInt("pogingen", 0), nu);
                    long sinds = poging == 1 ? nu : p.getLong("pogingSinds", nu);
                    boolean klaar = opgeven(sinds, poging, nu);
                    App.log(ctx, "BACKUP", "nachtelijke backup mislukt, poging " + poging + (klaar ? ", geen nieuwe poging meer" : ", straks opnieuw"));
                    if (!klaar) {
                        p.edit().putInt("pogingen", poging).putLong("pogingSinds", sinds).commit(); // vóór Signalen of het scherm het lezen
                        wacht = OPNIEUW_MS * poging;
                        return;
                    }
                }
                p.edit().putLong("lastAutoDay", nu).remove("pogingen").remove("pogingSinds").apply();
                notifyIfFailed(ctx, r);
                // Daarna, nog binnen de tijd van de taak, de backup-map netjes houden: dubbele mappen en bestanden,
                // achtergebleven tijdelijke bestanden. Buiten de taak legde Android de app stil en kwam het nooit af; nu
                // gaat het tot Android de taak stopt, en de volgende nacht verder waar het bleef.
                opruimen = true;
                try { if (!gestopt && !nietGelukt(r)) BackupMap.nightly(ctx, tot); } catch (Throwable ignored) { }
            } finally {
                opruimen = false;
                // Eerst afmelden, dan pas de volgende plannen: plannen met hetzelfde id stopt een taak die nog loopt
                // Mislukt (uitzondering): niet meteen opnieuw, maar over een uur
                if (r == null || !r.optBoolean("stopped")) { jobFinished(params, false); schedule(ctx, r == null ? 60 * 60 * 1000L : wacht); }
            }
        }), "allbackup-auto").start();
        return true;
    }

    /** Zo vaak per nacht proberen als de hele backup mislukte, met steeds OPNIEUW_MS langer ertussen. */
    static final int MAX_POGINGEN = 4;
    /** Hoe lang deze taak met opruimen bezig mag zijn, gerekend vanaf het begin (Android stopt een taak na ±10 minuten). */
    static final long TAAK_MS = 8 * 60 * 1000L;
    static final long OPNIEUW_MS = 30 * 60 * 1000L;

    /** Na zoveel tijd sinds de eerste mislukte poging niet meer stil opnieuw, maar een melding (ook als het opladen ertussen stopte). */
    static final long OPGEVEN_MS = 3 * 60 * 60 * 1000L;

    /**
     * Mislukte de hele backup zo dat opnieuw proberen zin heeft (niet begonnen, of het archief niet afgemaakt)? Eén
     * onderdeel dat misging telt niet, een archief dat er al staat ook niet, en zonder toegang tot de map helpt het niet.
     */
    static boolean nietGelukt(JSONObject r) {
        JSONObject a = r == null ? null : r.optJSONObject("all");
        if (a == null || a.optBoolean("ok", true) || r.has("archive")) return false;
        return !a.optString("msg").startsWith(WaBackup.NO_ACCESS);
    }

    /** De hoeveelste mislukte poging dit is (klok teruggezet: opnieuw beginnen). */
    static int poging(long sinds, int eerder, long nu) {
        if (eerder <= 0 || sinds <= 0 || sinds > nu) return 1;
        return eerder + 1;
    }

    /** Niet meer stil opnieuw: genoeg pogingen, of het duurt al te lang sinds de eerste (dan een melding). */
    static boolean opgeven(long sinds, int poging, long nu) {
        return poging >= MAX_POGINGEN || (sinds > 0 && sinds <= nu && nu - sinds >= OPGEVEN_MS);
    }

    /** De backup is klaar en de backup-map wordt opgeruimd (stoppen = niet opnieuw back-uppen). */
    static volatile boolean opruimen = false;
    /** Android stopte deze taak al (dan ook niet meer aan het opruimen beginnen). */
    static volatile boolean gestopt = false;

    @Override
    public boolean onStopJob(JobParameters params) {
        gestopt = true;
        AllBackup.stop = true; // stopt na het lopende onderdeel (of bij het opruimen na het lopende bestand)
        BackupMap.stop = true;
        return !opruimen; // tijdens het opruimen was de backup al gelukt: niet opnieuw plannen
    }

    /** Alleen een melding als er iets misging; een geslaagde nachtelijke backup is stil. */
    static void notifyIfFailed(Context c, JSONObject r) {
        int failed = 0;
        String first = null;
        for (Iterator<String> it = r.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject o = r.optJSONObject(k);
            if (o != null && !o.optBoolean("ok", true)) { failed++; if (first == null) first = o.optString("msg"); }
        }
        if (failed == 0) return;
        WaBackupService.createChannel(c);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, NotifCenter.ch(c, WaBackupService.CHANNEL)) : new Notification.Builder(c);
        android.content.Intent open = new android.content.Intent(c, MainActivity.class).putExtra("open", "backup")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
        NotifCenter.post(c, 4204,
                b.setSmallIcon(R.drawable.ic_backup).setContentTitle("Nachtelijke backup: " + failed + (failed == 1 ? " onderdeel" : " onderdelen") + " mislukt")
                        .setContentText(first == null ? "" : first).setAutoCancel(true)
                        .setContentIntent(android.app.PendingIntent.getActivity(c, 24, open,
                                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT)).build());
    }

    /**
     * Elke nacht (standaard aan), maar alleen met een backup-map (anders elke nacht een melding dat het mislukte)
     * en niet op een nieuwe installatie waarop je gegevens nog terug moeten.
     */
    static boolean wanted(Context c) {
        try { return AllBackup.auto(c) && WaBackup.destUri(c) != null && Herstel.blocked(c) == null; } catch (Exception e) { return false; }
    }

    static void schedule(Context c, long minDelay) {
        JobScheduler js = (JobScheduler) c.getSystemService(JOB_SCHEDULER_SERVICE);
        SharedPreferences p = AllBackup.prefs(c);
        if (!wanted(c)) { js.cancel(JOB_ID); p.edit().remove("next").apply(); return; }
        long next = Math.max(nextRun(p.getLong("lastAutoDay", 0)), System.currentTimeMillis() + minDelay);
        JobInfo.Builder b = new JobInfo.Builder(JOB_ID, new ComponentName(c, AllBackupJob.class))
                .setMinimumLatency(Math.max(0, next - System.currentTimeMillis()))
                .setRequiresCharging(p.getBoolean("charging", true))
                .setPersisted(true)
                .setBackoffCriteria(10 * 60 * 1000L, JobInfo.BACKOFF_POLICY_LINEAR);
        if (Build.VERSION.SDK_INT >= 26) b.setRequiresBatteryNotLow(true);
        // Een backup-map in de cloud (Google Drive): alleen met een verbinding, anders blijft de map "laden"
        if (cloud(c)) b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
        js.schedule(b.build());
        p.edit().putLong("next", next).apply();
    }

    /** Staat de backup-map bij een bekende clouddienst (Google Drive e.d.)? Dan wacht de taak op een verbinding. */
    static boolean cloud(Context c) {
        Uri map = WaBackup.destUri(c);
        return map != null && WaBackup.cloud(map.getAuthority());
    }

    static void ensureScheduled(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(JOB_SCHEDULER_SERVICE);
        boolean auto = wanted(c);
        JobInfo j = js.getPendingJob(JOB_ID);
        // Een andere backup-map (bijv. van Google Drive naar een USB-stick): ook de voorwaarde voor een verbinding bijwerken
        @SuppressWarnings("deprecation") boolean netGoed = j == null || (j.getNetworkType() != JobInfo.NETWORK_TYPE_NONE) == cloud(c);
        if (auto == (j != null) && netGoed) return;
        schedule(c, 0);
    }

    /** Eerstvolgende 03:30; is de backup van vannacht gemist, dan zo snel mogelijk. */
    static long nextRun(long lastOk) {
        Calendar c = Calendar.getInstance();
        long now = c.getTimeInMillis();
        c.set(Calendar.HOUR_OF_DAY, 3);
        c.set(Calendar.MINUTE, 30);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long today = c.getTimeInMillis();
        if (now < today) {
            c.add(Calendar.DAY_OF_MONTH, -1);
            return lastOk >= c.getTimeInMillis() ? today : now;
        }
        if (lastOk >= today) { c.add(Calendar.DAY_OF_MONTH, 1); return c.getTimeInMillis(); }
        return now;
    }
}
