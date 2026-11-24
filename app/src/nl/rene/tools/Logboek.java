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
 * Statistieken zijn ingesteld; uit te zetten bij Instellingen → Foutrapport en statistieken. Gaat een paar minuten na
 * een nieuwe melding, hooguit eens per UUR (na een vastloper na een paar minuten), of bij het starten van de app; alleen met internet.
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

    /** Hooguit eens per UUR versturen (een fout die steeds terugkomt geeft geen stroom bestanden), behalve na een vastloper. */
    static final long UUR = 60 * 60_000L;

    /** Vroegste moment (elapsedRealtime) van de taak die deze app zelf plande; 0 = onbekend. Scheelt bij elke logregel een aanroep van JobScheduler. */
    private static volatile long geplandOp;

    static void plan(Context c) { plan(c, ""); }

    /** Na een nieuwe melding (App.log, met de soort): over een paar minuten versturen, met internet. Eén taak tegelijk. */
    static void plan(Context c, String soort) {
        try {
            boolean dringend = "CRASH".equals(soort); // een vastloper stopt de app: zeldzaam; DRAAD kan zich herhalen en volgt het uur
            long nu = android.os.SystemClock.elapsedRealtime(), op = geplandOp;
            if (op != 0 && nu < op - 30_000 && (!dringend || op <= nu + WACHT_MS + 60_000)) return; // er staat al een taak die op tijd is
            if (!PlayPolicy.allows(c, "statistiek") || !aan(c)) return;
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            if (!dringend && js.getPendingJob(JOB_ID) != null) { geplandOp = nu + 60_000; return; } // wacht nog (bijv. op internet): niet bij elke logregel opnieuw vragen
            long wacht = dringend ? WACHT_MS : wachtTijd(Statistiek.prefs(c).getLong("logLast", 0), System.currentTimeMillis());
            JobInfo.Builder b = new JobInfo.Builder(JOB_ID, new ComponentName(c, Care.class))
                    .setMinimumLatency(wacht).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true);
            if (js.schedule(b.build()) == JobScheduler.RESULT_SUCCESS) geplandOp = nu + wacht;
        } catch (Throwable ignored) { }
    }

    /** Hoe lang wachten na een gewone melding: minstens WACHT_MS, en hooguit eens per UUR versturen. */
    static long wachtTijd(long laatst, long nu) {
        long w = laatst <= 0 || laatst > nu ? WACHT_MS : laatst + UUR - nu;
        return Math.max(WACHT_MS, Math.min(UUR, w));
    }

    /** Na een geslaagde ronde: kwam er tijdens het versturen iets bij, dan opnieuw plannen (de lopende taak telt nog als gepland). */
    static void naRonde(Context c) {
        geplandOp = 0;
        try {
            if (!aan(c) || nieuw(lees(c), Statistiek.prefs(c).getString("logTot", ""))[0].isEmpty()) return;
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            long wacht = wachtTijd(Statistiek.prefs(c).getLong("logLast", 0), System.currentTimeMillis());
            // Rechtstreeks: de taak die net klaar is kan nog als gepland tellen; dezelfde id vervangt hem
            if (js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, Care.class)).setMinimumLatency(wacht)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build()) == JobScheduler.RESULT_SUCCESS)
                geplandOp = android.os.SystemClock.elapsedRealtime() + wacht;
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
        try { log = lees(c); } catch (Exception e) { return "Lezen lukte niet"; }
        String[] n = nieuw(log, p.getString("logTot", ""));
        if (n[0].isEmpty()) { p.edit().putLong("logLast", System.currentTimeMillis()).apply(); return ""; }
        String tekst = kop(c) + schoon(n[0]);
        long nu = System.currentTimeMillis();
        String pad = pad(nu, Statistiek.installId(c));
        String r = Statistiek.zet(c, pad, tekst, "Logboek");
        SharedPreferences.Editor e = p.edit().putString("logResult", r.isEmpty() ? "ok" : r).putLong("logTry", nu);
        if (r.isEmpty()) e.putString("logTot", n[1]).putLong("logLast", nu);
        e.apply();
        if (r.isEmpty()) Diagnose.noteer(c, nu, "logboek", tekst.length()); // zichtbaar bij "Wat heeft de telefoon verlaten?"
        // Bewust geen App.log hier: dat zou een nieuwe melding zijn en weer een verzending plannen.
        return r;
    }

    /** Precies wat er de volgende keer gaat (opgeschoond), om te bekijken in de app. */
    static String voorbeeld(Context c) {
        String log;
        try { log = lees(c); } catch (Exception e) { log = ""; }
        String[] n = nieuw(log, Statistiek.prefs(c).getString("logTot", ""));
        return n[0].isEmpty() ? "" : kop(c) + schoon(n[0]);
    }

    /** Waar het in de repository komt: logboek/<tijd UTC>-<installatie>.txt (Statistiek.zet laat alleen zo'n pad toe). */
    static String pad(long nu, String installId) {
        String naam = Statistiek.path(nu, installId);
        return "logboek/" + naam.substring(naam.lastIndexOf('/') + 1).replace(".json", ".txt");
    }

    /** Het logboek lezen terwijl niemand schrijft (App.log is synchronized op App.class): nooit een halve melding. */
    private static String lees(Context c) throws Exception {
        synchronized (App.class) { return App.file(c).isFile() ? Contacts.readText(App.file(c), false) : ""; }
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

    /** Merktekens per melding; dezelfde melding twee keer in dezelfde seconde krijgt een volgnummer (#2), zodat de tweede niet als verstuurd telt. */
    static List<String> merken(List<String> m) {
        List<String> uit = new ArrayList<>();
        java.util.Map<String, Integer> tel = new java.util.HashMap<>();
        for (String x : m) {
            String k = merk(x);
            Integer n = tel.get(k); n = n == null ? 1 : n + 1; tel.put(k, n);
            uit.add(n == 1 ? k : k + "#" + n);
        }
        return uit;
    }

    /**
     * {wat nieuw is (meldingen achter elkaar), merkteken van de laatste}. Nieuw = na de laatst verstuurde melding; staat
     * die er niet meer (het logboek is ingekort), dan alles vanaf het tijdstip ervan. Hooguit MAX tekens (de nieuwste).
     */
    static String[] nieuw(String log, String tot) {
        List<String> m = meldingen(log);
        if (m.isEmpty()) return new String[]{"", tot == null ? "" : tot};
        List<String> mk = merken(m);
        int vanaf = 0;
        if (tot != null && !tot.isEmpty()) {
            int i = m.size() - 1;
            while (i >= 0 && !mk.get(i).equals(tot)) i--;
            if (i >= 0) vanaf = i + 1;
            else {
                String tijd = tot.length() >= 19 ? tot.substring(0, 19) : "";
                while (vanaf < m.size() && m.get(vanaf).substring(0, 19).compareTo(tijd) < 0) vanaf++;
            }
        }
        String laatste = mk.get(m.size() - 1);
        if (vanaf >= m.size()) return new String[]{"", laatste};
        List<String> stukken = new ArrayList<>();
        int lengte = 0;
        boolean afgekapt = false;
        for (int i = m.size() - 1; i >= vanaf; i--) { // van nieuw naar oud tot de grens, daarna weer op volgorde
            String x = m.get(i);
            if (x.length() > MAX) x = x.substring(0, MAX / 2) + "\n…(melding ingekort)";
            if (lengte + x.length() + 2 > MAX && lengte > 0) { afgekapt = true; break; }
            stukken.add(x); lengte += x.length() + 2;
        }
        StringBuilder b = new StringBuilder();
        if (afgekapt) b.append("…(oudere meldingen weggelaten)\n\n");
        for (int i = stukken.size() - 1; i >= 0; i--) b.append(stukken.get(i)).append("\n\n");
        return new String[]{b.toString(), laatste};
    }

    /** Het eigen adres van de interface (MainActivity): weg, zodat stapelregels van JavaScript hun regel en kolom houden. */
    private static final String EIGEN_ADRES = "https://app.renes-tools.local/";
    /** Android: JSONException noemt de hele gelezen tekst ("… at character 12 of {…}"): die inhoud eruit. */
    private static final Pattern JSON_INHOUD = Pattern.compile("( at character \\d+ of )(?s:.*?)(?=\\n\\tat |\\nCaused by|\\n\\n\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d \\[|\\z)");
    /** Android: "Value {…} of type org.json.JSONObject cannot be converted …" en "For input string: "…"": ook gelezen inhoud. */
    private static final Pattern JSON_TYPE = Pattern.compile(" (?:at \\d+ )?of type org\\.json\\.\\w+ cannot be converted");
    private static final Pattern INVOER = Pattern.compile("(For input string: )\"[^\"\\n]{0,400}\"");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]{1,64}@[\\w-]{1,63}(?:\\.[\\w-]{1,63}){1,8}");
    private static final Pattern URI = Pattern.compile("(?i)\\b(?:content|file|https?|wss?|ftp|intent|market|blob|data):\\S+");
    private static final Pattern WEB = Pattern.compile("(?i)\\b(?:www\\.\\S+|(?:[a-z0-9-]{1,63}\\.){1,6}(?:nl|com|org|net|eu|be|de|fr|io|ai|app|dev|co|uk|fm|info|online|cloud|me)(?:/\\S*)?(?![\\w.$]))");
    private static final Pattern PAD = Pattern.compile("(?<![\\w.])/(?:storage|sdcard|data|mnt|Download|DCIM|Documents)/[^\\n:()\"',;]+");
    /** Een bestandsnaam van Rene (pdf, foto, document …): gezocht vanaf de extensie, daarna begrensd terug (lineair, ook bij lange tekst). */
    private static final Pattern EXTENSIE = Pattern.compile("(?i)\\.(?:pdf|jpe?g|png|heic|heif|webp|gif|tiff?|docx?|odt|xlsx?|ods|pptx?|epub|mp3|m4a|aac|ogg|opus|wav|mp4|txt|csv|ics|vcf|zip|rtb|ovpn|conf)(?![\\w])");
    /** Kandidaat; vervangen alleen als het controlegetal klopt (mod 97), dus geen hashes of woorden erachter. */
    private static final Pattern IBAN = Pattern.compile("(?i)\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,4})?\\b");
    private static final Pattern IP4 = Pattern.compile("(?<![\\w.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\w.])");
    private static final Pattern IP6 = Pattern.compile("(?i)(?<![\\w:])(?:(?:[0-9a-f]{1,4}:){3,7}[0-9a-f]{1,4}|(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){0,6})?::(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){0,6})?)(?![\\w:])");
    private static final Pattern POSTCODE = Pattern.compile("\\b[1-9]\\d{3} ?(?!(?:KB|MB|GB|TB|HD|PX|HZ|MS|SA|SD|SS)\\b)[A-Z]{2}\\b");
    private static final Pattern TELEFOON = Pattern.compile("(?<![\\w.])(?:\\+|00|0)\\d(?:[ -]?\\d){7,13}(?!\\w)");
    private static final Pattern NUMMER = Pattern.compile("(?<![\\w.])\\d{7,}(?![\\w])");
    private static final Pattern GEHEIM = Pattern.compile("\\b(?:github_pat_\\w+|gh[opsu]_\\w{20,}|[A-Za-z0-9+/_=-]{40,})");
    /** Een regel die geen stapelregel is, hooguit zo lang: een lange regel is meestal inhoud, geen foutmelding. */
    static final int MAX_REGEL = 400;

    /** "Value {…} of type org.json.X cannot be converted": de waarde ertussen eruit (vanaf het eind terugzoeken: lineair). */
    static String jsonWaardeWeg(String t) {
        Matcher m = JSON_TYPE.matcher(t);
        StringBuilder b = new StringBuilder(t.length());
        int klaar = 0;
        while (m.find()) {
            int v = -1, ondergrens = Math.max(klaar, m.start() - 20_000);
            for (int k = m.start() - 6; k >= ondergrens; k--) if (t.startsWith("Value ", k)) { v = k; break; }
            if (v < 0) continue;
            b.append(t, klaar, v).append("Value [inhoud]");
            klaar = m.start();
        }
        return b.append(t, klaar, t.length()).toString();
    }

    /** Klopt het controlegetal van dit IBAN (spaties en hoofdletters maken niet uit)? */
    static boolean ibanOk(String iban) {
        String s = iban.replace(" ", "").toUpperCase(java.util.Locale.ROOT);
        if (s.length() < 15 || s.length() > 34) return false;
        String r = s.substring(4) + s.substring(0, 4);
        int rest = 0;
        for (int i = 0; i < r.length(); i++) {
            char ch = r.charAt(i);
            int v = ch >= '0' && ch <= '9' ? ch - '0' : ch >= 'A' && ch <= 'Z' ? ch - 'A' + 10 : -1;
            if (v < 0) return false;
            rest = (v >= 10 ? rest * 100 + v : rest * 10 + v) % 97;
        }
        return rest == 1;
    }

    private static String rekeningenWeg(String t) {
        Matcher m = IBAN.matcher(t);
        StringBuilder b = new StringBuilder(t.length());
        int klaar = 0;
        while (m.find()) {
            String kand = m.group(); // het langste stuk dat klopt (een woord erachter hoort er niet bij)
            for (int eind = kand.length(); eind >= 15; eind--) {
                if (eind < kand.length() && kand.charAt(eind) != ' ') continue;
                if (ibanOk(kand.substring(0, eind))) { b.append(t, klaar, m.start()).append("[rekening]"); klaar = m.start() + eind; break; }
            }
        }
        return b.append(t, klaar, t.length()).toString();
    }

    private static boolean naamTeken(char ch) { return !Character.isWhitespace(ch) && "/:'\"()[]".indexOf(ch) < 0; }

    /** Bestandsnamen (tot drie woorden met spaties ervoor, elk hooguit 80 tekens) worden [bestand]. */
    static String bestandenWeg(String t) {
        Matcher m = EXTENSIE.matcher(t);
        StringBuilder b = new StringBuilder(t.length());
        int klaar = 0;
        while (m.find()) {
            int begin = m.start(), woorden = 0;
            while (true) {
                int w = begin;
                while (w > klaar && begin - w < 80 && naamTeken(t.charAt(w - 1))) w--;
                if (w == begin) break;
                begin = w; woorden++;
                if (woorden >= 3 || begin - 1 <= klaar || t.charAt(begin - 1) != ' ' || !naamTeken(t.charAt(begin - 2))) break;
                begin--; // de spatie tussen twee woorden van de naam
            }
            if (woorden == 0) continue; // ".pdf" los: niets te verbergen
            b.append(t, klaar, begin).append("[bestand]");
            klaar = m.end();
        }
        return b.append(t, klaar, t.length()).toString();
    }

    /**
     * Tweede vangnet vóór het versturen: wat op een persoonlijk gegeven lijkt eruit. Stapelregels van de code
     * (nl.rene.tools.X.y(X.java:12)) en datums blijven staan.
     */
    static String schoon(String tekst) {
        if (tekst == null) return "";
        String t = tekst.replace(EIGEN_ADRES, "");
        t = JSON_INHOUD.matcher(t).replaceAll("$1[inhoud]");
        t = jsonWaardeWeg(t);
        t = INVOER.matcher(t).replaceAll("$1\"[inhoud]\"");
        t = URI.matcher(t).replaceAll("[adres]");
        t = EMAIL.matcher(t).replaceAll("[e-mail]");
        t = WEB.matcher(t).replaceAll("[adres]");
        t = PAD.matcher(t).replaceAll("[pad]");
        t = bestandenWeg(t);
        t = GEHEIM.matcher(t).replaceAll("[geheim]");
        t = rekeningenWeg(t);
        t = IP4.matcher(t).replaceAll("[ip]");
        t = IP6.matcher(t).replaceAll("[ip]");
        t = POSTCODE.matcher(t).replaceAll("[postcode]");
        t = TELEFOON.matcher(t).replaceAll("[nummer]");
        t = NUMMER.matcher(t).replaceAll("[nummer]");
        StringBuilder b = new StringBuilder(t.length());
        for (String regel : t.split("\n", -1)) {
            boolean stapel = regel.startsWith("\tat ") || regel.startsWith("    at ");
            if (b.length() > 0) b.append('\n');
            b.append(!stapel && regel.length() > MAX_REGEL ? regel.substring(0, MAX_REGEL) + "…[ingekort]" : regel);
        }
        return b.toString();
    }
}
