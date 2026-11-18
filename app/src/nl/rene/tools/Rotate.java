package nl.rene.tools;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opruimen van oude backups, per soort bestand (sms-, oproepen-, contacten-, backup-) met een datum in de naam.
 * Standaard blijven de laatste {@link #KEEP_DEFAULT} volledige bestanden van elke soort staan (instelbaar: {@link #KEEP_CHOICES});
 * met keep = 0 de oude regel: alle bestanden van de laatste 14 dagen, en van daarvoor de nieuwste per maand.
 * De allernieuwste blijft altijd staan. Bestanden zonder datum, selecties en contactversies worden nooit verwijderd.
 * Een onvolledig archief (backup-…_deels.rtb: de nachtelijke backup werd halverwege gestopt) telt nooit als
 * "nieuwste" of als die van een maand; het gaat weg zodra er een nieuwer archief is, of na 14 dagen.
 */
final class Rotate {

    private Rotate() { }

    static final int DAYS = 14;
    static final Pattern NAME = Pattern.compile("^(sms|oproepen|contacten|backup|meldingen)-(\\d{4})-(\\d{2})-(\\d{2})(?:_(\\d{2})(\\d{2})(\\d{2})?)?(_deels)?(\\.[A-Za-z0-9]+)$");
    static final String PARTIAL = "_deels";
    /** Hoeveel volledige backups er standaard blijven; 0 = de laatste 14 dagen en de nieuwste per maand. */
    static final int KEEP_DEFAULT = 7;
    static final int[] KEEP_CHOICES = {0, 3, 7, 14, 30};

    /** Een bewaarde keuze die (nog) niet bestaat, wordt de standaard. */
    static int keep(int n) { for (int k : KEEP_CHOICES) if (k == n) return n; return KEEP_DEFAULT; }

    static final class F {
        final String name; final String family; final long when; final String month; final boolean partial;
        F(String n, String f, long w, String m, boolean p) { name = n; family = f; when = w; month = m; partial = p; }
    }

    static F parse(String name) {
        Matcher m = NAME.matcher(name);
        if (!m.matches()) return null;
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)) - 1, Integer.parseInt(m.group(4)),
                m.group(5) == null ? 0 : Integer.parseInt(m.group(5)), m.group(6) == null ? 0 : Integer.parseInt(m.group(6)), m.group(7) == null ? 0 : Integer.parseInt(m.group(7)));
        return new F(name, m.group(1) + m.group(9).toLowerCase(), c.getTimeInMillis(), m.group(2) + "-" + m.group(3), m.group(8) != null);
    }

    /** Namen die weg mogen, uitgaande van "nu", met de oude regel (14 dagen + de nieuwste per maand). */
    static List<String> toDelete(Collection<String> names, long now) { return toDelete(names, now, 0); }

    /** Namen die weg mogen, uitgaande van "nu": keep > 0 = per soort de nieuwste keep volledige bestanden houden. */
    static List<String> toDelete(Collection<String> names, long now, int keep) {
        if (keep > 0) return keepNewest(names, now, keep);
        Calendar cut = Calendar.getInstance();
        cut.setTimeInMillis(now);
        cut.set(Calendar.HOUR_OF_DAY, 0); cut.set(Calendar.MINUTE, 0); cut.set(Calendar.SECOND, 0); cut.set(Calendar.MILLISECOND, 0);
        cut.add(Calendar.DAY_OF_MONTH, -DAYS);
        long limit = cut.getTimeInMillis();
        List<String> out = stale(names, limit);
        Map<String, F> newest = new HashMap<>();      // per soort
        Map<String, F> perMonth = new HashMap<>();    // soort + maand → nieuwste
        List<F> all = new ArrayList<>();
        for (String n : names) {
            F f = parse(n);
            if (f == null || f.partial) continue; // onvolledige archieven: zie stale()
            all.add(f);
            F a = newest.get(f.family);
            if (a == null || f.when > a.when || (f.when == a.when && f.name.compareTo(a.name) > 0)) newest.put(f.family, f);
            String k = f.family + "|" + f.month;
            F b = perMonth.get(k);
            if (b == null || f.when > b.when || (f.when == b.when && f.name.compareTo(b.name) > 0)) perMonth.put(k, f);
        }
        for (F f : all) {
            if (f.when >= limit) continue;
            if (newest.get(f.family) == f) continue;
            if (perMonth.get(f.family + "|" + f.month) == f) continue;
            out.add(f.name);
        }
        return out;
    }

    private static List<String> keepNewest(Collection<String> names, long now, int keep) {
        List<String> out = stalePartials(names, now);
        Map<String, List<F>> per = new HashMap<>();
        for (String n : names) {
            F f = parse(n);
            if (f == null || f.partial) continue;
            List<F> l = per.get(f.family);
            if (l == null) { l = new ArrayList<>(); per.put(f.family, l); }
            l.add(f);
        }
        for (List<F> l : per.values()) {
            // nieuwste eerst; bij dezelfde tijd de grootste naam (met seconden) eerst, zoals hierboven
            java.util.Collections.sort(l, (a, b) -> a.when != b.when ? (a.when > b.when ? -1 : 1) : b.name.compareTo(a.name));
            for (int i = keep; i < l.size(); i++) out.add(l.get(i).name);
        }
        return out;
    }

    /** Onvolledige archieven die weg mogen: er is een nieuwer archief (volledig of onvolledig), of ouder dan 14 dagen. */
    static List<String> stalePartials(Collection<String> names, long now) {
        Calendar cut = Calendar.getInstance();
        cut.setTimeInMillis(now);
        cut.add(Calendar.DAY_OF_MONTH, -DAYS);
        return stale(names, cut.getTimeInMillis());
    }

    private static List<String> stale(Collection<String> names, long limit) {
        List<F> all = new ArrayList<>();
        for (String n : names) { F f = parse(n); if (f != null) all.add(f); }
        List<String> out = new ArrayList<>();
        for (F f : all) {
            if (!f.partial) continue;
            boolean newer = f.when < limit;
            for (F g : all) if (!newer && g != f && g.family.equals(f.family) && (g.when > f.when || (g.when == f.when && !g.partial))) newer = true;
            if (newer) out.add(f.name);
        }
        return out;
    }
}
