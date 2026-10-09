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
 * Opruimen van oude backups: van elk soort bestand (sms-, oproepen-, contacten-, backup-) met een datum in de naam
 * blijven alle bestanden van de laatste 14 dagen staan, en van daarvoor de nieuwste per maand.
 * De allernieuwste blijft altijd staan. Bestanden zonder datum, selecties en contactversies worden nooit verwijderd.
 */
final class Rotate {

    private Rotate() { }

    static final int DAYS = 14;
    static final Pattern NAME = Pattern.compile("^(sms|oproepen|contacten|backup|meldingen)-(\\d{4})-(\\d{2})-(\\d{2})(?:_(\\d{2})(\\d{2})(\\d{2})?)?(\\.[A-Za-z0-9]+)$");

    static final class F {
        final String name; final String family; final long when; final String month;
        F(String n, String f, long w, String m) { name = n; family = f; when = w; month = m; }
    }

    static F parse(String name) {
        Matcher m = NAME.matcher(name);
        if (!m.matches()) return null;
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)) - 1, Integer.parseInt(m.group(4)),
                m.group(5) == null ? 0 : Integer.parseInt(m.group(5)), m.group(6) == null ? 0 : Integer.parseInt(m.group(6)), m.group(7) == null ? 0 : Integer.parseInt(m.group(7)));
        return new F(name, m.group(1) + m.group(8).toLowerCase(), c.getTimeInMillis(), m.group(2) + "-" + m.group(3));
    }

    /** Namen die weg mogen, uitgaande van "nu". */
    static List<String> toDelete(Collection<String> names, long now) {
        Calendar cut = Calendar.getInstance();
        cut.setTimeInMillis(now);
        cut.set(Calendar.HOUR_OF_DAY, 0); cut.set(Calendar.MINUTE, 0); cut.set(Calendar.SECOND, 0); cut.set(Calendar.MILLISECOND, 0);
        cut.add(Calendar.DAY_OF_MONTH, -DAYS);
        long limit = cut.getTimeInMillis();
        Map<String, F> newest = new HashMap<>();      // per soort
        Map<String, F> perMonth = new HashMap<>();    // soort + maand → nieuwste
        List<F> all = new ArrayList<>();
        for (String n : names) {
            F f = parse(n);
            if (f == null) continue;
            all.add(f);
            F a = newest.get(f.family);
            if (a == null || f.when > a.when || (f.when == a.when && f.name.compareTo(a.name) > 0)) newest.put(f.family, f);
            String k = f.family + "|" + f.month;
            F b = perMonth.get(k);
            if (b == null || f.when > b.when || (f.when == b.when && f.name.compareTo(b.name) > 0)) perMonth.put(k, f);
        }
        List<String> out = new ArrayList<>();
        for (F f : all) {
            if (f.when >= limit) continue;
            if (newest.get(f.family) == f) continue;
            if (perMonth.get(f.family + "|" + f.month) == f) continue;
            out.add(f.name);
        }
        return out;
    }
}
