package nl.rene.tools;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * Puur rekenwerk voor "Gebruik" (geen Android, zodat het op een gewone JVM getest kan worden — tests/jvm/…/GebruikTest.java):
 * van losse gebeurtenissen (uit UsageStatsManager, aan- en uitgaan van het scherm, ont- en vergrendelen) naar
 * app-sessies, naar blokken per ontgrendeling (met pincode / anders / mislukt / onbekend), schermtijd per uur/dag en per
 * app, en de bewaartermijnen. De opslag in bestanden en het samenstellen van JSON voor de interface staan in GebruikStore.
 *
 * Hoe wordt "met pincode" bepaald? Alleen uit twee booleans met een tijdstip, nooit uit wat er gettypt wordt:
 *  - "bouncer getoond": de toegankelijkheidsdienst zag dat het invoerscherm van het slot (pincode/patroon/wachtwoord)
 *     verscheen. ALLEEN of het verscheen en wanneer — nooit toetsaanslagen, cijfers of tekst (zie AutoA11y/GebruikStore).
 *  - een gelukte poging van Apparaatbeheer (die komt alleen ná een mislukte poging): dan óók "met pincode".
 * Een ontgrendeling binnen een symmetrisch venster rond zo'n signaal heet "met pincode"; elk signaal telt één keer mee.
 * Staat de toegankelijkheidsdienst uit, dan is de manier niet te bepalen: "onbekend".
 */
public final class Gebruik {

    private Gebruik() { }

    // Eigen, stabiele nummers (dezelfde betekenis als UsageEvents, maar los van Android zodat het te testen is).
    public static final int APP_START = 1;   // een app komt op de voorgrond (ACTIVITY_RESUMED)
    public static final int APP_STOP = 2;    // een app verdwijnt van de voorgrond (ACTIVITY_PAUSED)
    public static final int SCREEN_ON = 3;   // scherm aan (SCREEN_INTERACTIVE)
    public static final int SCREEN_OFF = 4;  // scherm uit (SCREEN_NON_INTERACTIVE)
    public static final int UNLOCKED = 5;    // vergrendelscherm weg / in gebruik (KEYGUARD_HIDDEN of USER_PRESENT)
    public static final int LOCKED = 6;      // vergrendelscherm ervoor (KEYGUARD_SHOWN)
    public static final int BOOT = 7;        // telefoon opnieuw opgestart (DEVICE_STARTUP)

    public static final long DEFAULT_WINDOW = 12_000;       // symmetrisch venster rond een ontgrendeling voor het pincode-signaal
    public static final long DEFAULT_FAIL_WINDOW = 300_000; // mislukte pogingen horen bij een ontgrendeling tot 5 min ervoor

    /** Eén gebeurtenis. pkg alleen bij APP_START/APP_STOP. */
    public static final class Ev {
        public final int type; public final String pkg; public final long t;
        public Ev(int type, String pkg, long t) { this.type = type; this.pkg = pkg; this.t = t; }
    }

    /** Een poging bij het vergrendelscherm (van Apparaatbeheer): gelukt (ok) of mislukt. */
    public static final class Pw {
        public final long t; public final boolean ok;
        public Pw(long t, boolean ok) { this.t = t; this.ok = ok; }
    }

    /** Eén keer dat een app op de voorgrond stond. */
    public static final class Session {
        public final String pkg; public final long start; public long end;
        public Session(String pkg, long start, long end) { this.pkg = pkg; this.start = start; this.end = end; }
        public long dur() { return Math.max(0, end - start); }
    }

    /** Een app binnen een ontgrendelingsblok. */
    public static final class AppUse {
        public final String pkg; public final long start; public final long dur;
        public AppUse(String pkg, long start, long dur) { this.pkg = pkg; this.start = start; this.dur = dur; }
    }

    /** Een blok: een ontgrendeling (how "pincode"/"anders"/"onbekend") met de apps erna, of alleen mislukte pogingen ("mislukt"). */
    public static final class Unlock {
        public final long t; public final String how; public final int fails; public long end;
        public final List<AppUse> apps = new ArrayList<>();
        public Unlock(long t, String how, int fails, long end) { this.t = t; this.how = how; this.fails = fails; this.end = end; }
    }

    /**
     * App-sessies uit de gebeurtenissen. Een sessie loopt van APP_START tot de eerstvolgende APP_STOP van diezelfde app,
     * een APP_START van een andere app, of tot het scherm uit gaat (SCREEN_OFF), het slot ervoor komt (LOCKED) of de
     * telefoon opnieuw opstart/afsluit (BOOT). Gebeurtenissen worden op tijd gezet. Sessies zonder duur vallen weg.
     * Een sessie die nog niet is afgesloten (het scherm staat nog aan) krijgt eindtijd 'now' als die > 0 is.
     */
    public static List<Session> sessions(List<Ev> events) { return sessions(events, 0); }

    public static List<Session> sessions(List<Ev> events, long now) {
        List<Ev> evs = new ArrayList<>(events);
        Collections.sort(evs, (a, b) -> a.t != b.t ? Long.compare(a.t, b.t) : Integer.compare(order(a.type), order(b.type)));
        List<Session> out = new ArrayList<>();
        Session open = null;
        for (Ev e : evs) {
            if (e.type == APP_START) {
                if (open != null) { open.end = e.t; if (open.dur() > 0) out.add(open); }
                open = e.pkg == null || e.pkg.isEmpty() ? null : new Session(e.pkg, e.t, e.t);
            } else if (e.type == APP_STOP) {
                if (open != null && (e.pkg == null || e.pkg.equals(open.pkg))) { open.end = e.t; if (open.dur() > 0) out.add(open); open = null; }
            } else if (e.type == SCREEN_OFF || e.type == BOOT || e.type == LOCKED) {
                if (open != null) { open.end = e.t; if (open.dur() > 0) out.add(open); open = null; }
            }
        }
        // De nu nog lopende app (scherm staat nog aan): meetellen tot nu.
        if (open != null && now > open.start) { open.end = now; out.add(open); }
        return out;
    }

    // Bij een gelijke tijd: eerst een app stoppen/scherm uit, dan pas een nieuwe app starten (nette sessiegrenzen).
    private static int order(int type) { return type == APP_START ? 1 : 0; }

    /**
     * Blokken per ontgrendeling (near-lineair, lijsten mogen ongesorteerd binnenkomen). Classificatie, zie de uitleg
     * bovenaan: "pincode" bij een bouncer-signaal of een gelukte Apparaatbeheer-poging binnen het symmetrische venster
     * (elk signaal één keer); anders "anders" als de manier te bepalen is (a11yOn), anders "onbekend". fails = mislukte
     * pogingen tot failWindowMs vóór de ontgrendeling. Mislukte pogingen die bij geen ontgrendeling horen, worden eigen
     * "mislukt"-blokken (opeenvolgende pogingen binnen failWindowMs samen). Bij een blok horen de apps tot het scherm uit.
     */
    public static List<Unlock> unlocks(List<Ev> events, List<Pw> pwds, List<Long> bouncer, boolean a11yOn, long windowMs, long failWindowMs) {
        List<Ev> evs = new ArrayList<>(events);
        Collections.sort(evs, (a, b) -> Long.compare(a.t, b.t));
        List<Long> ut = new ArrayList<>();
        List<Long> offs = new ArrayList<>();
        for (Ev e : evs) { if (e.type == UNLOCKED) ut.add(e.t); else if (e.type == SCREEN_OFF || e.type == LOCKED || e.type == BOOT) offs.add(e.t); }

        List<Long> bnc = new ArrayList<>(bouncer == null ? Collections.emptyList() : bouncer);
        Collections.sort(bnc);
        List<Long> succ = new ArrayList<>(), fail = new ArrayList<>();
        for (Pw p : pwds) (p.ok ? succ : fail).add(p.t);
        Collections.sort(succ); Collections.sort(fail);

        List<Unlock> out = new ArrayList<>();
        int bi = 0, si = 0;            // pointers voor bouncer- en gelukte-poging-signalen (elk één keer)
        int fi = 0;                    // pointer voor mislukte pogingen (toegekend of los)
        boolean[] failUsed = new boolean[fail.size()];
        int so = 0;                    // pointer voor scherm-uit (einde blok)
        int ss = 0;                    // pointer voor sessies (apps in een blok)
        List<Session> sess = sessions(events);
        Collections.sort(sess, (a, b) -> Long.compare(a.start, b.start));

        for (int u = 0; u < ut.size(); u++) {
            long t = ut.get(u);
            long next = u + 1 < ut.size() ? ut.get(u + 1) : Long.MAX_VALUE;
            // pincode-signaal: bouncer of gelukte poging binnen [t-window, t+window], elk één keer
            boolean pin = false;
            while (bi < bnc.size() && bnc.get(bi) < t - windowMs) bi++;
            if (bi < bnc.size() && bnc.get(bi) <= t + windowMs) { pin = true; bi++; }
            while (si < succ.size() && succ.get(si) < t - windowMs) si++;
            if (si < succ.size() && succ.get(si) <= t + windowMs) { pin = true; si++; }
            // mislukte pogingen tot failWindow vóór deze ontgrendeling
            int fails = 0;
            for (int k = fi; k < fail.size() && fail.get(k) <= t; k++) {
                if (failUsed[k]) continue;
                if (fail.get(k) >= t - failWindowMs) { fails++; failUsed[k] = true; }
            }
            while (fi < fail.size() && fail.get(fi) <= t) fi++;
            String how = pin ? "pincode" : a11yOn ? "anders" : "onbekend";
            Unlock blk = new Unlock(t, how, fails, t);
            // einde: eerstvolgende scherm-uit/slot ná t en vóór de volgende ontgrendeling
            while (so < offs.size() && offs.get(so) <= t) so++;
            long end = next;
            if (so < offs.size() && offs.get(so) < next) end = offs.get(so);
            blk.end = end == Long.MAX_VALUE ? t : end;
            // apps in dit blok (sessies starten op volgorde; pointer loopt mee)
            while (ss < sess.size() && sess.get(ss).start < t) ss++;
            int k = ss;
            while (k < sess.size() && sess.get(k).start < blk.end) { Session s = sess.get(k); blk.apps.add(new AppUse(s.pkg, s.start, s.dur())); k++; }
            out.add(blk);
        }
        // Overgebleven mislukte pogingen: eigen "mislukt"-blokken, opeenvolgende binnen failWindow samen.
        int run = 0; long runStart = 0, runLast = 0;
        for (int k = 0; k < fail.size(); k++) {
            if (failUsed[k]) { if (run > 0) { out.add(new Unlock(runStart, "mislukt", run, runLast)); run = 0; } continue; }
            long ft = fail.get(k);
            if (run == 0) { runStart = ft; run = 1; runLast = ft; }
            else if (ft - runLast <= failWindowMs) { run++; runLast = ft; }
            else { out.add(new Unlock(runStart, "mislukt", run, runLast)); runStart = ft; run = 1; runLast = ft; }
        }
        if (run > 0) out.add(new Unlock(runStart, "mislukt", run, runLast));

        Collections.sort(out, (a, b) -> Long.compare(a.t, b.t));
        return out;
    }

    // ---------- schermtijd ----------

    /** Totaal per app (pkg → milliseconden), hoogste eerst. */
    public static Map<String, Long> totals(List<Session> sess) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (Session s : sess) { Long v = m.get(s.pkg); m.put(s.pkg, (v == null ? 0 : v) + s.dur()); }
        List<Map.Entry<String, Long>> l = new ArrayList<>(m.entrySet());
        Collections.sort(l, (a, b) -> Long.compare(b.getValue(), a.getValue()));
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : l) out.put(e.getKey(), e.getValue());
        return out;
    }

    /** Hoe vaak een app geopend werd: opeenvolgende sessies van dezelfde app met een gaatje korter dan gapMs tellen als één keer. */
    public static Map<String, Integer> counts(List<Session> sess, long gapMs) {
        List<Session> l = new ArrayList<>(sess);
        Collections.sort(l, (a, b) -> Long.compare(a.start, b.start));
        Map<String, Integer> m = new LinkedHashMap<>();
        Map<String, Long> lastEnd = new java.util.HashMap<>();
        for (Session s : l) {
            Long le = lastEnd.get(s.pkg);
            if (le == null || s.start - le > gapMs) { Integer v = m.get(s.pkg); m.put(s.pkg, (v == null ? 0 : v) + 1); }
            lastEnd.put(s.pkg, s.end);
        }
        return m;
    }

    /** Schermtijd per uur van een dag (24 emmers, milliseconden), met echte lokale-tijd-uurgrenzen (ook rond zomer-/wintertijd). */
    public static long[] hourly(List<Session> sess, long dayStart, TimeZone tz) {
        long[] h = new long[24];
        long dayEnd = dayStartAfter(dayStart, 1, tz);
        Calendar k = Calendar.getInstance(tz);
        for (Session s : sess) {
            long a = Math.max(s.start, dayStart), b = Math.min(s.end, dayEnd);
            long t = a;
            while (t < b) {
                k.setTimeInMillis(t);
                int hour = k.get(Calendar.HOUR_OF_DAY);
                k.set(Calendar.MINUTE, 0); k.set(Calendar.SECOND, 0); k.set(Calendar.MILLISECOND, 0);
                k.add(Calendar.HOUR_OF_DAY, 1);
                long bound = k.getTimeInMillis();
                long until = Math.min(b, bound);
                if (until <= t) break; // vangnet
                if (hour >= 0 && hour < 24) h[hour] += until - t;
                t = until;
            }
        }
        return h;
    }

    /** Schermtijd per dag binnen een reeks (aantal dagen vanaf firstDayStart), milliseconden; sessies over middernacht worden geknipt. */
    public static long[] daily(List<Session> sess, long firstDayStart, int days, TimeZone tz) {
        long[] out = new long[Math.max(0, days)];
        for (int i = 0; i < out.length; i++) {
            long ds = dayStartAfter(firstDayStart, i, tz);
            long de = dayStartAfter(firstDayStart, i + 1, tz);
            for (Session s : sess) { long a = Math.max(s.start, ds), b = Math.min(s.end, de); if (b > a) out[i] += b - a; }
        }
        return out;
    }

    /** Schermtijd per dag (sleutel → milliseconden), sessies over middernacht netjes verdeeld over beide dagen. */
    public static Map<String, long[]> perDay(List<Session> sess, TimeZone tz) {
        // waarde: [totaal]; apps apart via perDayApp als nodig
        Map<String, long[]> m = new LinkedHashMap<>();
        for (Session s : sess) {
            long t = s.start;
            while (t < s.end) {
                long ds = dayStart(t, tz), de = dayStartAfter(ds, 1, tz);
                long until = Math.min(s.end, de);
                String key = dateKey(t, tz);
                long[] v = m.get(key); if (v == null) { v = new long[1]; m.put(key, v); }
                v[0] += until - t;
                t = until;
            }
        }
        return m;
    }

    // ---------- dag-indeling en bewaartermijn ----------

    /** Begin van de dag (00:00 lokale tijd) waarin t valt. */
    public static long dayStart(long t, TimeZone tz) {
        Calendar k = Calendar.getInstance(tz);
        k.setTimeInMillis(t);
        k.set(Calendar.HOUR_OF_DAY, 0); k.set(Calendar.MINUTE, 0); k.set(Calendar.SECOND, 0); k.set(Calendar.MILLISECOND, 0);
        return k.getTimeInMillis();
    }

    /** Begin van de dag n dagen na het begin van de dag van base (goed rond zomer-/wintertijd). */
    public static long dayStartAfter(long base, int n, TimeZone tz) {
        Calendar k = Calendar.getInstance(tz);
        k.setTimeInMillis(dayStart(base, tz));
        k.add(Calendar.DAY_OF_MONTH, n);
        return k.getTimeInMillis();
    }

    /** Sleutel "JJJJ-MM-DD" van de dag waarin t valt (lokale tijd). */
    public static String dateKey(long t, TimeZone tz) {
        Calendar k = Calendar.getInstance(tz);
        k.setTimeInMillis(t);
        return String.format("%04d-%02d-%02d", k.get(Calendar.YEAR), k.get(Calendar.MONTH) + 1, k.get(Calendar.DAY_OF_MONTH));
    }

    /** Is t ouder dan 'days' dagen vóór now (grens op het begin van de dag)? Gebruikt voor opruimen. */
    public static boolean olderThan(long t, long now, int days, TimeZone tz) {
        if (days <= 0) return false;
        return t < dayStartAfter(now, -days + 1, tz);
    }

    /** Bewaartermijn in dagen → oudste tijd die nog bewaard blijft (begin van die dag). */
    public static long keepSince(long now, int days, TimeZone tz) {
        return dayStartAfter(now, -Math.max(0, days) + 1, tz);
    }
}
