package nl.rene.tools;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.CallLog;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Onderhoud op de achtergrond (één keer per dag): elke maand een backup-controle met een proef-terugzetten
 * (is de backup echt leesbaar?), en elke week kijken of Android Rene's Tools op de achtergrond beperkt
 * (ColorOS doet dat graag na een update). Ook: het overzicht "sinds gisteravond" op het startscherm van de app.
 */
public class Care extends JobService {

    static final int JOB_ID = 4310;
    static final String CHANNEL = "care";
    static final long DAY = 86_400_000L;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("care", Context.MODE_PRIVATE); }

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context ctx = getApplicationContext();
        new Thread(() -> {
            try { daily(ctx); } catch (Throwable t) { App.log(ctx, "ONDERHOUD", String.valueOf(t)); }
            jobFinished(params, false);
        }, "care").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) { return true; }

    static void ensureScheduled(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null || js.getPendingJob(JOB_ID) != null) return;
            JobInfo.Builder b = new JobInfo.Builder(JOB_ID, new ComponentName(c, Care.class)).setPeriodic(DAY).setPersisted(true);
            if (Build.VERSION.SDK_INT >= 26) b.setRequiresBatteryNotLow(true);
            js.schedule(b.build());
        } catch (Exception ignored) { }
    }

    static void daily(Context c) {
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis();
        // Elke week: wordt de app op de achtergrond beperkt?
        if (now - p.getLong("batteryAt", 0) > 7 * DAY) {
            p.edit().putLong("batteryAt", now).apply();
            String b = batteryProblem(c);
            // Gewone batterijoptimalisatie: één keer melden; echte beperking: elke week
            boolean plain = b != null && b.startsWith("Batterijoptimalisatie");
            if (plain && p.getBoolean("optNotified", false)) b = null;
            if (plain) p.edit().putBoolean("optNotified", true).apply();
            if (b != null) notify(c, 4311, "Rene's Tools wordt beperkt door Android", b + " Tik om dit op te lossen; anders kunnen backups, herinneringen en de radiowekker uitblijven.", "selftest");
        }
        // Elke maand: backup-controle
        if (now - p.getLong("checkAt", 0) > 30 * DAY && WaBackup.destUri(c) != null && !AllBackup.running.get()) {
            JSONObject r = backupCheck(c);
            String title = r.optBoolean("ok") ? "Backup-controle: alles in orde ✓" : "Backup-controle: er is iets mis";
            notify(c, 4312, title, r.optString("summary"), "backup");
        }
    }

    // ---------- batterij ----------

    /** Null als alles goed is, anders een korte uitleg. */
    static String batteryProblem(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null && am.isBackgroundRestricted()) return "Achtergrondgebruik staat op 'beperkt'.";
                android.app.usage.UsageStatsManager us = (android.app.usage.UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
                if (us != null && us.getAppStandbyBucket() >= 45) return "Android zet de app in de zwaarst beperkte groep.";
            }
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(c.getPackageName())) return "Batterijoptimalisatie staat aan voor Rene's Tools.";
        } catch (Exception ignored) { }
        return null;
    }

    // ---------- backup-controle met proef-terugzetten ----------

    static final java.util.concurrent.atomic.AtomicBoolean checking = new java.util.concurrent.atomic.AtomicBoolean(false);

    static JSONObject backupCheck(Context c) {
        if (!checking.compareAndSet(false, true)) {
            try { return new JSONObject(prefs(c).getString("check", "{}")); } catch (Exception e) { return new JSONObject(); }
        }
        try { return backupCheckNow(c); } finally { checking.set(false); }
    }

    private static JSONObject backupCheckNow(Context c) {
        JSONObject o = new JSONObject();
        JSONArray items = new JSONArray();
        boolean ok = true;
        StringBuilder sum = new StringBuilder();
        try {
            Uri tree = WaBackup.destUri(c);
            if (tree == null) { add(items, "Backup-map", false, "Er is geen backup-map gekozen"); ok = false; }
            else {
                String dn = WaBackup.destName(c);
                boolean reach = dn != null && !dn.startsWith("(map niet");
                add(items, "Backup-map", reach, reach ? dn : "Niet meer bereikbaar (USB-stick of SD-kaart weg? Kies de map opnieuw)");
                ok &= reach;
                // Laatste volledige backup
                String last = AllBackup.prefs(c).getString("last", null);
                if (last == null) { add(items, "Laatste backup", false, "Nog nooit een volledige backup gemaakt"); ok = false; }
                else {
                    JSONObject l = new JSONObject(last);
                    long days = (System.currentTimeMillis() - l.optLong("t")) / DAY;
                    int failed = 0;
                    java.util.Iterator<String> it = l.keys();
                    while (it.hasNext()) { JSONObject r = l.optJSONObject(it.next()); if (r != null && r.has("ok") && !r.optBoolean("ok")) failed++; }
                    boolean fine = days <= 7 && failed == 0;
                    add(items, "Laatste backup", fine, days + (days == 1 ? " dag" : " dagen") + " geleden" + (failed > 0 ? ", " + failed + " onderdeel mislukt" : ""));
                    ok &= fine;
                    sum.append("Laatste backup ").append(days).append(days == 1 ? " dag" : " dagen").append(" geleden. ");
                }
                if (reach) {
                    String t = Secure.on(c) ? testEncrypted(c, tree) : testPlain(c, tree);
                    boolean good = t.startsWith("✓");
                    add(items, "Proef-terugzetten", good, t.replaceFirst("^[✓✗] ", ""));
                    ok &= good;
                    sum.append(t);
                }
            }
        } catch (Throwable e) { add(items, "Controle", false, "Controle mislukt: " + e.getMessage()); ok = false; }
        try {
            o.put("t", System.currentTimeMillis()).put("ok", ok).put("items", items).put("summary", sum.toString().trim());
            prefs(c).edit().putLong("checkAt", System.currentTimeMillis()).putString("check", o.toString()).apply();
        } catch (Exception ignored) { }
        return o;
    }

    static void add(JSONArray a, String what, boolean ok, String msg) {
        try { a.put(new JSONObject().put("what", what).put("ok", ok).put("msg", msg)); } catch (Exception ignored) { }
    }

    /** Zonder versleuteling: notities.json teruglezen en de nieuwste contacten-vCard openen (zonder iets toe te voegen). */
    static String testPlain(Context c, Uri tree) throws Exception {
        WaBackup.Dest d = new WaBackup.Dest(c.getContentResolver(), tree);
        StringBuilder r = new StringBuilder();
        WaBackup.DestDir nd = d.dir(Notes.DIR, false);
        WaBackup.Child nf = nd == null ? null : nd.kids.get("notities.json");
        if (nf != null) {
            JSONArray a = new JSONArray(read(c, tree, nf.docId));
            r.append("Notities leesbaar (").append(a.length()).append("). ");
        } else r.append("Geen notities-backup gevonden. ");
        WaBackup.DestDir cd = d.dir(Contacts.DIR, false);
        String newest = null; WaBackup.Child cf = null;
        if (cd != null) for (java.util.Map.Entry<String, WaBackup.Child> e : cd.kids.entrySet())
            if (e.getKey().matches("contacten-\\d{4}-\\d{2}-\\d{2}\\.vcf") && (newest == null || e.getKey().compareTo(newest) > 0)) { newest = e.getKey(); cf = e.getValue(); }
        if (cf != null) {
            int n = countCards(c, tree, cf.docId);
            if (n == 0) return "✗ De contacten-backup (" + newest + ") is leeg of onleesbaar";
            r.append("Contacten leesbaar (").append(n).append(" in ").append(newest).append(").");
        }
        if (nf == null && cf == null) return "✗ Geen notities- of contacten-backup gevonden om te testen";
        return "✓ " + r.toString().trim();
    }

    /** Met versleuteling: het nieuwste archief openen met de bewaarde sleutel en de notities eruit lezen. */
    static String testEncrypted(Context c, Uri tree) throws Exception {
        WaBackup.Dest d = new WaBackup.Dest(c.getContentResolver(), tree);
        WaBackup.DestDir vd = d.dir(Secure.DIR, false);
        String newest = null; WaBackup.Child f = null;
        if (vd != null) for (java.util.Map.Entry<String, WaBackup.Child> e : vd.kids.entrySet())
            if (e.getKey().endsWith(".rtb") && (newest == null || e.getKey().compareTo(newest) > 0)) { newest = e.getKey(); f = e.getValue(); }
        if (f == null) return "✗ Geen versleuteld archief gevonden in " + Secure.DIR;
        if (Secure.broken(c)) return "✗ De sleutel voor versleutelde backups is kwijt; stel versleutelen opnieuw in (Alles back-uppen)";
        Uri u = DocumentsContract.buildDocumentUriUsingTree(tree, f.docId);
        try {
            String[] r = Secure.verify(c, u).split("\\|");
            int notes = Integer.parseInt(r[1]);
            return "✓ Archief " + newest + " geopend: " + r[0] + " bestanden" + (notes >= 0 ? ", notities leesbaar (" + notes + ")" : "");
        } catch (Exception e) { return "✗ " + newest + ": " + (e.getMessage() == null ? "niet te openen" : e.getMessage()); }
    }

    /** Aantal contacten in een vCard, regel voor regel (zonder alles in het geheugen te laden). */
    static int countCards(Context c, Uri tree, String docId) throws Exception {
        Uri u = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
        int n = 0, end = 0;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getContentResolver().openInputStream(u), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) { if (line.trim().equalsIgnoreCase("BEGIN:VCARD")) n++; else if (line.trim().equalsIgnoreCase("END:VCARD")) end++; }
        }
        return n == end ? n : 0; // afgebroken bestand telt als onleesbaar
    }

    static String read(Context c, Uri tree, String docId) throws Exception {
        Uri u = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
        try (InputStream in = c.getContentResolver().openInputStream(u)) {
            if (in == null) throw new Exception("niet te openen");
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) { b.write(buf, 0, n); if (b.size() > 20_000_000) throw new Exception("bestand is te groot om te testen"); }
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    // ---------- "sinds gisteravond" ----------

    /** Wat er gebeurde sinds het vorige "gezien" (hoogstens 18 uur terug): gemiste oproepen, backup, automatiseringen, Auto redial. */
    static String overnight(Context c) {
        JSONObject o = new JSONObject();
        try {
            long now = System.currentTimeMillis();
            long since = Math.max(prefs(c).getLong("seen", 0), now - 18 * 3_600_000L);
            o.put("since", since);
            // Gemiste oproepen
            JSONArray missed = new JSONArray();
            if (c.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
                try (Cursor cur = c.getContentResolver().query(CallLog.Calls.CONTENT_URI, new String[]{CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE},
                        CallLog.Calls.TYPE + "=? AND " + CallLog.Calls.DATE + ">?", new String[]{String.valueOf(CallLog.Calls.MISSED_TYPE), String.valueOf(since)}, CallLog.Calls.DATE + " DESC")) {
                    while (cur != null && cur.moveToNext() && missed.length() < 5) {
                        String name = cur.getString(1);
                        missed.put(new JSONObject().put("who", name == null || name.isEmpty() ? cur.getString(0) : name).put("t", cur.getLong(2)));
                    }
                }
            }
            o.put("missed", missed);
            // Backup
            String last = AllBackup.prefs(c).getString("last", null);
            if (last != null) {
                JSONObject l = new JSONObject(last);
                if (l.optLong("t") > since) {
                    int failed = 0;
                    java.util.Iterator<String> it = l.keys();
                    while (it.hasNext()) { JSONObject r = l.optJSONObject(it.next()); if (r != null && r.has("ok") && !r.optBoolean("ok")) failed++; }
                    o.put("backup", new JSONObject().put("t", l.optLong("t")).put("failed", failed));
                }
            }
            // Automatiseringen
            JSONArray auto = new JSONArray(), log = new JSONArray(Auto.prefs(c).getString("log", "[]"));
            for (int i = 0; i < log.length() && auto.length() < 4; i++) {
                JSONObject e = log.optJSONObject(i);
                if (e != null && e.optLong("t") > since) auto.put(e);
            }
            o.put("auto", auto);
            // Batterij en backup-controle
            // Batterij: alleen als echte beperking (niet de gewone batterijoptimalisatie), en tot "Gezien"
            String bat = batteryProblem(c);
            if (bat != null && !bat.startsWith("Batterijoptimalisatie") && prefs(c).getLong("seenBattery", 0) == 0) o.put("battery", bat);
            if (bat == null) prefs(c).edit().remove("seenBattery").apply();
            String chk = prefs(c).getString("check", null);
            if (chk != null) { JSONObject ch = new JSONObject(chk); if (!ch.optBoolean("ok") && ch.optLong("t") > since) o.put("checkBad", ch.optString("summary")); }
        } catch (Exception ignored) { }
        return o.toString();
    }

    // ---------- melding ----------

    static void notify(Context c, int id, String title, String text, String tool) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Onderhoud (backup-controle, batterij)", NotificationManager.IMPORTANCE_DEFAULT));
            if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
            Intent open = new Intent(c, MainActivity.class).putExtra("open", tool).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .setData(Uri.parse("renestools://care/" + id));
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
            b.setSmallIcon(R.drawable.ic_notif).setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true).setContentIntent(PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            nm.notify(id, b.build());
        } catch (Exception ignored) { }
    }
}
