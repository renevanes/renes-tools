package nl.rene.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Contactversies vergelijken (pure Java, los te testen). Een contact is een sleutel (lookup key),
 * een naam en een vaste set velden met tekst; meervoudige velden (telefoon, e-mail, adres ...)
 * staan gesorteerd onder elkaar, zodat de volgorde geen schijnwijziging geeft.
 */
final class ContactsDiff {

    private ContactsDiff() { }

    /** Vaste veldvolgorde voor weergave en vergelijking. */
    static final String[] FIELDS = {"Naam", "Bijnaam", "Telefoon", "E-mail", "Bedrijf", "Functie", "Adres",
            "Website", "Verjaardag", "Notitie", "Favoriet"};

    static final class Rec {
        String key, name;
        long id, updated;
        final Map<String, String> f = new LinkedHashMap<>();

        Rec() { }
        Rec(String key, String name) { this.key = key; this.name = name; }

        String get(String field) { String v = f.get(field); return v == null ? "" : v; }

        /** Zet een veld; leeg = weg. */
        void put(String field, String v) {
            if (v == null || v.trim().isEmpty()) f.remove(field); else f.put(field, v.trim());
        }

        /** Voegt een waarde toe aan een meervoudig veld (uniek, gesorteerd). */
        void add(String field, String v) {
            if (v == null || v.trim().isEmpty()) return;
            TreeSet<String> s = new TreeSet<>();
            String cur = f.get(field);
            if (cur != null) for (String x : cur.split("\n")) if (!x.isEmpty()) s.add(x);
            s.add(v.trim());
            f.put(field, String.join("\n", s));
        }

        /** Inhoud zonder wijzigingsdatum, om te zien of er echt iets veranderd is. */
        String content() {
            StringBuilder b = new StringBuilder();
            for (String k : FIELDS) { String v = f.get(k); if (v != null) b.append(k).append('=').append(v).append('\u0001'); }
            return b.toString();
        }
    }

    static final class Change {
        final String field, before, after;
        Change(String field, String before, String after) { this.field = field; this.before = before; this.after = after; }
    }

    /** Wijziging van één contact: '+' nieuw, '-' verwijderd, '~' gewijzigd. */
    static final class Entry {
        final char kind;
        final Rec rec;        // nieuw/gewijzigd: huidige versie; verwijderd: laatste bekende versie
        final List<Change> changes = new ArrayList<>();
        Entry(char kind, Rec rec) { this.kind = kind; this.rec = rec; }
    }

    static List<Change> fieldChanges(Rec a, Rec b) {
        List<Change> out = new ArrayList<>();
        for (String k : FIELDS) {
            String x = a.get(k), y = b.get(k);
            if (!x.equals(y)) out.add(new Change(k, x, y));
        }
        return out;
    }

    /**
     * Vergelijkt de vorige en de huidige lijst. Contacten worden op sleutel gekoppeld; wat
     * daarna overblijft wordt op naam gekoppeld (Android kan de sleutel veranderen als
     * contacten worden samengevoegd of opnieuw gesynchroniseerd).
     */
    static List<Entry> diff(List<Rec> before, List<Rec> after) {
        Map<String, Rec> old = new LinkedHashMap<>();
        for (Rec r : before) old.put(r.key, r);
        List<Entry> out = new ArrayList<>();
        List<Rec> added = new ArrayList<>();
        for (Rec r : after) {
            Rec o = old.remove(r.key);
            if (o == null) { added.add(r); continue; }
            if (!o.content().equals(r.content())) {
                Entry e = new Entry('~', r);
                e.changes.addAll(fieldChanges(o, r));
                out.add(e);
            }
        }
        // Overgebleven oude contacten op naam koppelen aan nieuwe.
        Map<String, List<Rec>> byName = new HashMap<>();
        for (Rec r : old.values()) {
            String n = r.name == null ? "" : r.name.toLowerCase();
            List<Rec> l = byName.get(n);
            if (l == null) { l = new ArrayList<>(); byName.put(n, l); }
            l.add(r);
        }
        for (Rec r : added) {
            List<Rec> l = byName.get(r.name == null ? "" : r.name.toLowerCase());
            if (l != null && !l.isEmpty() && r.name != null && !r.name.isEmpty()) {
                Rec o = l.remove(0);
                old.remove(o.key);
                if (!o.content().equals(r.content())) {
                    Entry e = new Entry('~', r);
                    e.changes.addAll(fieldChanges(o, r));
                    out.add(e);
                }
            } else {
                Entry e = new Entry('+', r);
                for (String k : FIELDS) if (!r.get(k).isEmpty()) e.changes.add(new Change(k, "", r.get(k)));
                out.add(e);
            }
        }
        for (Rec o : old.values()) {
            Entry e = new Entry('-', o);
            for (String k : FIELDS) if (!o.get(k).isEmpty()) e.changes.add(new Change(k, o.get(k), ""));
            out.add(e);
        }
        return out;
    }
}
