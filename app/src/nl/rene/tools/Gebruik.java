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
 * app-sessies, naar blokken per ontgrendeling (met pincode / anders / mislukt), schermtijd per uur en per app, en de
 * bewaartermijnen. De opslag in bestanden en het samenstellen van JSON voor de interface staan in GebruikStore.
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

    /** Een blok: ofwel een ontgrendeling (how "pincode"/"anders") met de apps erna, ofwel alleen mislukte pogingen (how "mislukt"). */
    public static final class Unlock {
        public final long t; public final String how; public final int fails; public long end;
        public final List<AppUse> apps = new ArrayList<>();
        public Unlock(long t, String how, int fails, long end) { this.t = t; this.how = how; this.fails = fails; this.end = end; }
    }

    /**
     * App-sessies uit de gebeurtenissen. Een sessie loopt van APP_START tot de eerstvolgende APP_STOP van diezelfde app,
     * een APP_START van een andere app, of tot het scherm uit gaat (SCREEN_OFF) of de telefoon opnieuw opstart (BOOT).
     * Gebeurtenissen hoeven niet gesorteerd te zijn; ze worden op tijd gezet. Sessies zonder duur vallen weg.
     */
    public static List<Session> sessions(List<Ev> events) {
        List<Ev> evs = new ArrayList<>(events);
        Collections.sort(evs, (a, b) -> Long.compare(a.t, b.t));
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
        return out;
    }

    /**
     * Blokken per ontgrendeling. Bij elke UNLOCKED: "pincode" als er vlak daarvoor (binnen pinWindowMs) een gelukte
     * poging was, anders "anders" (vingerafdruk, gezicht, smart lock of geen slot — dat kan de app niet uit elkaar houden).
     * fails = de mislukte pogingen vlak vóór die ontgrendeling. Mislukte pogingen zonder ontgrendeling erna worden een
     * los blok "mislukt". Bij een blok horen de app-sessies vanaf de ontgrendeling tot het scherm weer uit ging.
     */
    public static List<Unlock> unlocks(List<Ev> events, List<Pw> pwds, long pinWindowMs) {
        List<Ev> evs = new ArrayList<>(events);
        Collections.sort(evs, (a, b) -> Long.compare(a.t, b.t));
        List<Pw> pw = new ArrayList<>(pwds);
        Collections.sort(pw, (a, b) -> Long.compare(a.t, b.t));
        List<Session> sess = sessions(events);

        List<Unlock> out = new ArrayList<>();
        // Mislukte pogingen die (nog) niet bij een ontgrendeling horen.
        int pending = 0; long pendingLast = 0;
        int pi = 0;
        List<long[]> unlockTimes = new ArrayList<>(); // [t]
        for (Ev e : evs) if (e.type == UNLOCKED) unlockTimes.add(new long[]{e.t});

        // Loop door pogingen en ontgrendelingen samen op tijd.
        int ui = 0;
        while (pi < pw.size() || ui < unlockTimes.size()) {
            boolean takePw = ui >= unlockTimes.size() || (pi < pw.size() && pw.get(pi).t <= unlockTimes.get(ui)[0]);
            if (takePw) {
                Pw p = pw.get(pi++);
                // Mislukte pogingen blijven staan tot de ontgrendeling ze meetelt; een gelukte poging wist ze niet zelf.
                if (!p.ok) { pending++; pendingLast = p.t; }
            } else {
                long t = unlockTimes.get(ui++)[0];
                // Was er vlak hiervoor een gelukte poging?
                boolean pin = false;
                for (int k = pw.size() - 1; k >= 0; k--) { Pw p = pw.get(k); if (p.t <= t && p.ok && t - p.t <= pinWindowMs) { pin = true; break; } if (p.t <= t && p.ok) break; }
                int fails = pending;
                pending = 0; pendingLast = 0;
                out.add(new Unlock(t, pin ? "pincode" : "anders", fails, t));
            }
        }
        // Mislukte pogingen die nergens bij hoorden: een los blok.
        if (pending > 0) out.add(new Unlock(pendingLast, "mislukt", pending, pendingLast));

        Collections.sort(out, (a, b) -> Long.compare(a.t, b.t));

        // Einde van elk ontgrendelingsblok: de eerstvolgende SCREEN_OFF/LOCKED na de ontgrendeling (vóór de volgende ontgrendeling).
        for (int i = 0; i < out.size(); i++) {
            Unlock u = out.get(i);
            if ("mislukt".equals(u.how)) continue;
            long next = i + 1 < out.size() ? out.get(i + 1).t : Long.MAX_VALUE;
            long end = next;
            for (Ev e : evs) if ((e.type == SCREEN_OFF || e.type == LOCKED) && e.t > u.t && e.t < next) { end = e.t; break; }
            u.end = end == Long.MAX_VALUE ? u.t : end;
            for (Session s : sess) if (s.start >= u.t && s.start < u.end) u.apps.add(new AppUse(s.pkg, s.start, s.dur()));
        }
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

    /** Hoe vaak een app werd geopend (aantal sessies per app). */
    public static Map<String, Integer> counts(List<Session> sess) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (Session s : sess) { Integer v = m.get(s.pkg); m.put(s.pkg, (v == null ? 0 : v) + 1); }
        return m;
    }

    /** Schermtijd per uur van een dag (24 emmers, milliseconden), een sessie die over uren loopt wordt verdeeld. */
    public static long[] hourly(List<Session> sess, long dayStart) {
        long[] h = new long[24];
        long dayEnd = dayStart + 24L * 3600_000L;
        for (Session s : sess) {
            long a = Math.max(s.start, dayStart), b = Math.min(s.end, dayEnd);
            if (b <= a) continue;
            long t = a;
            while (t < b) {
                int hour = (int) ((t - dayStart) / 3600_000L);
                if (hour < 0 || hour > 23) break;
                long bucketEnd = dayStart + (hour + 1L) * 3600_000L;
                long until = Math.min(b, bucketEnd);
                h[hour] += until - t;
                t = until;
            }
        }
        return h;
    }

    /** Schermtijd per dag binnen een reeks (aantal dagen vanaf dayStart, elk 1 dag), milliseconden. */
    public static long[] daily(List<Session> sess, long firstDayStart, int days, TimeZone tz) {
        long[] out = new long[Math.max(0, days)];
        for (int i = 0; i < out.length; i++) {
            long ds = dayStartAfter(firstDayStart, i, tz);
            long de = dayStartAfter(firstDayStart, i + 1, tz);
            for (Session s : sess) { long a = Math.max(s.start, ds), b = Math.min(s.end, de); if (b > a) out[i] += b - a; }
        }
        return out;
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
        long cutoff = dayStartAfter(now, -days + 1, tz); // alles vóór het begin van die dag mag weg
        return t < cutoff;
    }

    /** Bewaartermijn in dagen → oudste tijd die nog bewaard blijft (begin van die dag). */
    public static long keepSince(long now, int days, TimeZone tz) {
        return dayStartAfter(now, -Math.max(0, days) + 1, tz);
    }
}
