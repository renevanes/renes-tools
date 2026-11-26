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
 * een nieuwe melding: hooguit eens per UUR, na een melding over iets wat misging ({@link #probleem}) hooguit eens per
 * KWARTIER (na een vastloper na een paar minuten), of bij het starten van de app; alleen met internet. Zo staat een
 * probleem er snel als Claude het nazoekt (AGENTS.md, "Foutlogboek nalopen").
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
    /** Na een melding over iets wat misging hooguit eens per KWARTIER: Rene probeert iets, het lukt niet, en het staat er snel. */
    static final long KWARTIER = 15 * 60_000L;

    /** Woorden in een melding die zeggen dat iets misging (vaste woorden uit de eigen code, zie App.log). */
    private static final Pattern MIS = Pattern.compile("(?i)mislukt|niet gelukt|lukt(?:e)? niet|niet herkend|niet te (?:openen|lezen)|niet op te halen"
            + "|(?<!\\b0 )fout|exception|error|vastgelopen|liep vast|kapot|geweigerd|weigert|onleesbaar|beschadigd|ontbreekt|verlopen|teruggedraaid");

    /** Gaat deze melding over iets wat misging? Dan sneller versturen (KWARTIER in plaats van UUR). */
    static boolean probleem(String soort, String tekst) {
        if ("CRASH".equals(soort) || "DRAAD".equals(soort)) return true;
        if (tekst == null || tekst.isEmpty()) return false;
        return MIS.matcher(tekst.length() > 2000 ? tekst.substring(0, 2000) : tekst).find();
    }

    /** Staat er in deze stukken logboek (zoals nieuw() ze geeft) een melding over iets wat misging? */
    static boolean probleemIn(String stuk) {
        for (String m : meldingen(stuk)) {
            int a = m.indexOf('['), b = m.indexOf(']'), nl = m.indexOf('\n');
            if (probleem(a >= 0 && b > a ? m.substring(a + 1, b) : "", nl < 0 ? "" : m.substring(nl + 1))) return true;
        }
        return false;
    }

    /**
     * Wanneer de taak die deze app zelf plande begint (System.currentTimeMillis; 0 = onbekend, -1 = nog niet gelezen). Ook
     * bewaard (logPlan), zodat het na een herstart van de app bekend blijft. Scheelt bij elke logregel een aanroep van JobScheduler.
     */
    private static volatile long geplandOp = -1;
    /** Tot wanneer (elapsedRealtime) niet opnieuw aan JobScheduler vragen of de taak er nog is (die wacht op internet of loopt). */
    private static volatile long gevraagdTot;

    static void plan(Context c) { plan(c, "", ""); }

    /** Na een nieuwe melding (App.log, met de soort en de tekst): over een paar minuten versturen, met internet. Eén taak tegelijk. */
    static void plan(Context c, String soort, String tekst) {
        try {
            boolean dringend = "CRASH".equals(soort); // een vastloper stopt de app: zeldzaam; DRAAD kan zich herhalen en volgt het kwartier
            boolean mis = !dringend && probleem(soort, tekst);
            SharedPreferences p = Statistiek.prefs(c);
            long nu = System.currentTimeMillis(), tik = android.os.SystemClock.elapsedRealtime(), op = geplandOp;
            if (op < 0) geplandOp = op = p.getLong("logPlan", 0);
            String tempo = FoutInstellingen.tempo(c);
            if (op > nu + FoutInstellingen.per(tempo, false) + 60_000) op = 0; // klok teruggezet: het bewaarde moment klopt niet meer
            if (!"snel".equals(tempo)) { mis = mis || dringend; dringend = false; } // eens per uur of per dag: ook een vastloper wacht
            long wacht = dringend ? WACHT_MS : wachtTijd(laatstGeprobeerd(p), nu, mis, tempo);
            boolean opTijd = op != 0 && op <= nu + wacht + 60_000;
            if (opTijd && nu < op - 30_000) return; // de taak die er staat komt nog, en op tijd: deze melding gaat mee
            if (!dringend && tik < gevraagdTot && (op == 0 || opTijd)) return; // net nagevraagd: er staat een taak (wacht op internet of loopt)
            if (!PlayPolicy.allows(c, "statistiek") || !aan(c)) return;
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            long w = besluit(nu, op, js.getPendingJob(JOB_ID) != null, wacht, dringend);
            if (w < 0) { gevraagdTot = tik + 60_000; return; }
            if (js.schedule(taak(c, w)) == JobScheduler.RESULT_SUCCESS) zetPlan(c, nu + w);
        } catch (Throwable ignored) { }
    }

    /**
     * Wat plan() doet (zuiver, getest in LogboekTest): -1 = niets (de taak die er staat gaat op tijd, wacht op internet of
     * loopt: nooit een lopende verzending afbreken of een wachtende steeds verder vooruit schuiven), anders de wachttijd
     * voor een nieuwe taak (vervangt de oude). op = begin van de taak die er staat (0 = onbekend), staat = Android heeft hem nog.
     */
    static long besluit(long nu, long op, boolean staat, long wacht, boolean dringend) {
        if (!staat) return wacht;                     // niets gepland (of de vorige is klaar): plannen
        if (op == 0) return dringend ? wacht : -1;    // onbekend wanneer: laten, behalve na een vastloper
        if (op <= nu + wacht + 60_000) return -1;     // op tijd, al aan de beurt, wacht op internet of bezig
        return wacht;                                 // te laat (bijv. een gewone taak over een uur): vervroegen
    }

    /** De laatste poging, gelukt of niet: een fout die blijft (sleutel verlopen) geeft geen poging om de paar minuten. */
    private static long laatstGeprobeerd(SharedPreferences p) { return Math.max(p.getLong("logLast", 0), p.getLong("logTry", 0)); }

    private static JobInfo taak(Context c, long wacht) {
        return new JobInfo.Builder(JOB_ID, new ComponentName(c, Care.class)).setMinimumLatency(wacht)
                .setRequiredNetworkType(FoutInstellingen.wifi(c) ? JobInfo.NETWORK_TYPE_UNMETERED : JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build();
    }

    /** Tempo of "alleen via wifi" veranderd: de taak die er staat opnieuw plannen met de nieuwe keuze. */
    static void herplan(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js != null) js.cancel(JOB_ID);
            zetPlan(c, 0);
            if (aan(c)) {
                String n = nieuw(lees(c), Statistiek.prefs(c).getString("logTot", ""))[0];
                if (!n.isEmpty() && js != null) {
                    long wacht = wachtTijd(laatstGeprobeerd(Statistiek.prefs(c)), System.currentTimeMillis(), probleemIn(n), FoutInstellingen.tempo(c));
                    if (js.schedule(taak(c, wacht)) == JobScheduler.RESULT_SUCCESS) zetPlan(c, System.currentTimeMillis() + wacht);
                }
            }
        } catch (Throwable ignored) { }
    }

    private static void zetPlan(Context c, long op) {
        geplandOp = op;
        gevraagdTot = 0;
        Statistiek.prefs(c).edit().putLong("logPlan", op).apply();
    }

    /** Hoe lang wachten na een melding: minstens WACHT_MS, en hooguit eens per UUR versturen (iets wat misging: per KWARTIER). */
    static long wachtTijd(long laatst, long nu, boolean mis) { return wachtTijd(laatst, nu, mis, FoutInstellingen.TEMPO); }

    /** Zo ook bij een ander tempo (FoutInstellingen.tempo: snel, uur of dag). */
    static long wachtTijd(long laatst, long nu, boolean mis, String tempo) {
        long per = FoutInstellingen.per(tempo, mis);
        long w = laatst <= 0 || laatst > nu ? WACHT_MS : laatst + per - nu;
        return Math.max(WACHT_MS, Math.min(per, w));
    }

    /** Na een geslaagde ronde: kwam er tijdens het versturen iets bij, dan opnieuw plannen (de lopende taak telt nog als gepland). */
    static void naRonde(Context c) {
        try {
            zetPlan(c, 0);
            if (!aan(c)) return;
            String n = nieuw(lees(c), Statistiek.prefs(c).getString("logTot", ""))[0];
            if (n.isEmpty()) return;
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            long wacht = wachtTijd(laatstGeprobeerd(Statistiek.prefs(c)), System.currentTimeMillis(), probleemIn(n), FoutInstellingen.tempo(c));
            // Rechtstreeks: de taak die net klaar is kan nog als gepland tellen; dezelfde id vervangt hem
            if (js.schedule(taak(c, wacht)) == JobScheduler.RESULT_SUCCESS) zetPlan(c, System.currentTimeMillis() + wacht);
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
    private static final Pattern JSON_TYPE = Pattern.compile(" (?:at \\S{1,200} )?of type [\\w.$]{1,200} cannot be converted");
    private static final Pattern INVOER = Pattern.compile("(For input string: )\"[^\"\\n]{0,400}\"");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]{1,64}@[\\w-]{1,63}(?:\\.[\\w-]{1,63}){1,8}");
    private static final Pattern URI = Pattern.compile("(?i)\\b(?:content|file|https?|wss?|ftp|intent|market|blob|data):\\S+");
    private static final Pattern WEB = Pattern.compile("(?i)\\b(?:www\\.\\S+|(?:[a-z0-9-]{1,63}\\.){1,6}(?:nl|com|org|net|eu|be|de|fr|io|ai|app|dev|co|uk|fm|info|online|cloud|me)(?:/\\S*)?(?![\\w.$})]))");
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
            // Eerst het begin van de foutmelding zelf ("JSONException: Value "): "Value " kan ook in de waarde staan
            for (int k = m.start() - 21; k >= ondergrens; k--) if (t.startsWith("JSONException: Value ", k)) { v = k + 15; break; }
            if (v < 0) for (int k = m.start() - 6; k >= ondergrens; k--) if (t.startsWith("Value ", k)) { v = k; break; }
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
        int klaar = 0, vanaf = 0;
        while (vanaf < t.length() && m.find(vanaf)) {
            String kand = m.group(); // het langste stuk dat klopt (een woord erachter hoort er niet bij)
            boolean raak = false;
            for (int eind = kand.length(); eind >= 15; eind--) {
                if (eind < kand.length() && kand.charAt(eind) != ' ') continue;
                if (ibanOk(kand.substring(0, eind))) { b.append(t, klaar, m.start()).append("[rekening]"); klaar = m.start() + eind; raak = true; break; }
            }
            // Niets gevonden: één woord verder opnieuw (er kan een woord als "ab12" voor het IBAN staan)
            vanaf = raak ? klaar : nextWoord(t, m.start());
        }
        return b.append(t, klaar, t.length()).toString();
    }

    private static int nextWoord(String t, int i) {
        int k = i;
        while (k < t.length() && t.charAt(k) != ' ' && t.charAt(k) != '\n') k++;
        return k + 1;
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
