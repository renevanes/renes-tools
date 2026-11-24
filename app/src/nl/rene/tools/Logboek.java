package nl.rene.tools;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Het foutlogboek (App.log, crash.log) vanzelf naar Rene's eigen privé GitHub-repository, map logboek/, zodat een agent
 * kan meekijken bij een probleem (wens van Rene). Alleen nieuwe meldingen sinds de vorige keer, opgeschoond
 * ({@link #schoon}: e-mailadressen, telefoonnummers, rekeningnummers, lange nummers, sleutels, webadressen en paden eruit),
 * en alleen als GitHub de repository als privé meldt (Statistiek.zet). Staat aan zolang de repository en sleutel van
 * Statistieken zijn ingesteld; uit te zetten bij Instellingen → Foutrapport en statistieken. Gaat ongeveer 5 minuten na
 * een nieuwe melding (of bij het starten van de app, bijv. na een vastloper), alleen met internet.
 * Logregels zelf horen al geen namen, nummers of berichten te bevatten (AGENTS.md); dit is een tweede vangnet.
 */
final class Logboek {
    private Logboek() { }

    static final int JOB_ID = 4312;
    static final long WACHT_MS = 5 * 60_000L;
    /** Hooguit zoveel tekens per keer (het logboek zelf is hooguit ~120 kB). */
    static final int MAX = 100_000;

    static boolean aan(Context c) {
        SharedPreferences p = Statistiek.prefs(c);
        return p.getBoolean("logAuto", true) && !p.getString("repo", "").isEmpty() && !p.getString("token", "").isEmpty();
    }

    static void setAan(Context c, boolean on) { Statistiek.prefs(c).edit().putBoolean("logAuto", on).apply(); }

    /** Na een nieuwe melding (App.log): over een paar minuten versturen, met internet. Eén taak tegelijk. */
    /** Wanneer deze app de taak zelf plande: tot WACHT_MS daarna kan hij nog niet gelopen hebben, dus is opnieuw kijken overbodig. */
    private static volatile long gepland;

    static void plan(Context c) {
        try {
            long nu = android.os.SystemClock.elapsedRealtime();
            if (gepland != 0 && nu - gepland < WACHT_MS - 30_000) return; // bij elke logregel: geen aanroep van JobScheduler nodig
            if (!PlayPolicy.allows(c, "statistiek") || !aan(c)) return;
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null || js.getPendingJob(JOB_ID) != null) return;
            JobInfo.Builder b = new JobInfo.Builder(JOB_ID, new ComponentName(c, Care.class))
                    .setMinimumLatency(WACHT_MS).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true);
            if (js.schedule(b.build()) == JobScheduler.RESULT_SUCCESS) gepland = nu;
        } catch (Throwable ignored) { }
    }

    /** Bij het starten: is er iets dat nog niet weg is (bijv. een vastloper vlak voor het stoppen)? */
    static void bijStart(Context c) {
        try {
            java.io.File f = App.file(c);
            if (f.isFile() && f.lastModified() > Statistiek.prefs(c).getLong("logLast", 0)) plan(c);
        } catch (Throwable ignored) { }
    }

    /** Verstuurt wat nieuw is. "" bij succes of als er niets nieuws is, anders de reden. Niet op de hoofdthread. */
    static synchronized String stuur(Context c) {
        if (!PlayPolicy.allows(c, "statistiek")) return PlayPolicy.unavailable(c);
        if (!aan(c)) return "Staat uit";
        SharedPreferences p = Statistiek.prefs(c);
        String log;
        try { log = App.file(c).isFile() ? Contacts.readText(App.file(c), false) : ""; } catch (Exception e) { return "Lezen lukte niet"; }
        String[] n = nieuw(log, p.getString("logTot", ""));
        if (n[0].isEmpty()) { p.edit().putLong("logLast", System.currentTimeMillis()).apply(); return ""; }
        String tekst = kop(c) + schoon(n[0]);
        long nu = System.currentTimeMillis();
        String naam = Statistiek.path(nu, Statistiek.installId(c));
        String pad = "logboek/" + naam.substring(naam.lastIndexOf('/') + 1).replace(".json", ".txt");
        String r = Statistiek.zet(c, pad, tekst, "Logboek");
        synchronized (Diagnose.class) {
            SharedPreferences.Editor e = p.edit().putString("logResult", r.isEmpty() ? "ok" : r).putLong("logTry", nu);
            if (r.isEmpty()) {
                e.putString("logTot", n[1]).putLong("logLast", nu)
                 .putString("diagSent", Diagnose.erbij(Diagnose.verstuurd(p.getString("diagSent", "")), nu, "logboek", tekst.length()).toString());
            }
            e.apply();
        }
        // Bewust geen App.log hier: dat zou een nieuwe melding zijn en weer een verzending plannen.
        return r;
    }

    /** Precies wat er de volgende keer gaat (opgeschoond), om te bekijken in de app. */
    static String voorbeeld(Context c) {
        String log;
        try { log = App.file(c).isFile() ? Contacts.readText(App.file(c), false) : ""; } catch (Exception e) { log = ""; }
        String[] n = nieuw(log, Statistiek.prefs(c).getString("logTot", ""));
        return n[0].isEmpty() ? "" : kop(c) + schoon(n[0]);
    }

    private static String kop(Context c) {
        return "Logboek Tools " + Version.NAME + " (" + Version.CODE + "), onderdeel " + Version.NATIVE_LEVEL
                + "\nToestel: " + Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                + "\nInstallatie: " + Statistiek.installId(c) + "\n\n";
    }

    // ---------- zuiver (getest in LogboekTest) ----------

    private static final Pattern KOP = Pattern.compile("(?m)^\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d \\[[^\\]\\n]{1,40}\\] v[^\\n]*$");

    /** Het logboek in losse meldingen (kopregel en tekst). Tekst vóór de eerste kopregel telt niet. */
    static List<String> meldingen(String log) {
        List<String> uit = new ArrayList<>();
        if (log == null) return uit;
        Matcher m = KOP.matcher(log);
        List<Integer> begin = new ArrayList<>();
        while (m.find()) begin.add(m.start());
        for (int i = 0; i < begin.size(); i++) {
            String s = log.substring(begin.get(i), i + 1 < begin.size() ? begin.get(i + 1) : log.length()).trim();
            if (!s.isEmpty()) uit.add(s);
        }
        return uit;
    }

    /** Merkteken van een melding: de kopregel en een controlegetal van de tekst. */
    static String merk(String melding) {
        int nl = melding.indexOf('\n');
        return (nl < 0 ? melding : melding.substring(0, nl)) + "|" + Integer.toHexString(melding.hashCode());
    }

    /**
     * {wat nieuw is (meldingen achter elkaar), merkteken van de laatste}. Nieuw = na de laatst verstuurde melding; staat
     * die er niet meer (het logboek is ingekort), dan alles vanaf het tijdstip ervan. Hooguit MAX tekens (de nieuwste).
     */
    static String[] nieuw(String log, String tot) {
        List<String> m = meldingen(log);
        if (m.isEmpty()) return new String[]{"", tot == null ? "" : tot};
        int vanaf = 0;
        if (tot != null && !tot.isEmpty()) {
            int i = m.size() - 1;
            while (i >= 0 && !merk(m.get(i)).equals(tot)) i--;
            if (i >= 0) vanaf = i + 1;
            else {
                String tijd = tot.length() >= 19 ? tot.substring(0, 19) : "";
                while (vanaf < m.size() && m.get(vanaf).substring(0, 19).compareTo(tijd) < 0) vanaf++;
            }
        }
        if (vanaf >= m.size()) return new String[]{"", merk(m.get(m.size() - 1))};
        StringBuilder b = new StringBuilder();
        for (int i = m.size() - 1; i >= vanaf; i--) { // van nieuw naar oud tot de grens, daarna weer op volgorde
            if (b.length() + m.get(i).length() + 2 > MAX && b.length() > 0) { vanaf = i + 1; break; }
            b.insert(0, m.get(i) + "\n\n");
        }
        String tekst = b.toString();
        if (vanaf > 0 && tot != null && tot.isEmpty() && tekst.length() >= MAX) tekst = "…(oudere meldingen weggelaten)\n\n" + tekst;
        return new String[]{tekst, merk(m.get(m.size() - 1))};
    }

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]{1,64}@[\\w-]{1,63}(?:\\.[\\w-]{1,63}){1,8}");
    private static final Pattern URI = Pattern.compile("(?i)\\b(?:content|file|https?)://\\S+");
    private static final Pattern PAD = Pattern.compile("(?<![\\w.])/(?:storage|sdcard|data|mnt|Download|DCIM|Documents)/\\S+");
    private static final Pattern IBAN = Pattern.compile("\\b[A-Z]{2}\\d{2} ?(?:[A-Z0-9]{4} ?){2,7}[A-Z0-9]{1,4}\\b");
    private static final Pattern TELEFOON = Pattern.compile("(?<![\\w.])(?:\\+|00|0)\\d(?:[ -]?\\d){7,13}(?!\\w)");
    private static final Pattern NUMMER = Pattern.compile("(?<![\\w.])\\d{7,}(?![\\w])");
    private static final Pattern GEHEIM = Pattern.compile("\\b(?:github_pat_\\w+|gh[opsu]_\\w{20,}|[A-Za-z0-9+/_=-]{40,})");

    /**
     * Tweede vangnet vóór het versturen: wat op een persoonlijk gegeven lijkt eruit. Stapelregels van de code
     * (nl.rene.tools.X.y(X.java:12)) en datums blijven staan.
     */
    static String schoon(String tekst) {
        if (tekst == null) return "";
        String t = tekst;
        t = URI.matcher(t).replaceAll("[adres]");
        t = EMAIL.matcher(t).replaceAll("[e-mail]");
        t = PAD.matcher(t).replaceAll("[pad]");
        t = GEHEIM.matcher(t).replaceAll("[geheim]");
        t = IBAN.matcher(t).replaceAll("[rekening]");
        t = TELEFOON.matcher(t).replaceAll("[nummer]");
        t = NUMMER.matcher(t).replaceAll("[nummer]");
        return t;
    }
}
