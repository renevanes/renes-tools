package nl.rene.tools;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * "Nieuwe afleveringen" op Podcasts: de nieuwste afleveringen die je nog niet beluisterde, van alles wat je volgt.
 * Zonder Android en zonder org.json (Podcasts zet de bewaarde feeds om), zodat de keuze en de cache te testen zijn.
 */
final class PodcastLatest {
    private PodcastLatest() { }

    /** Per podcast hooguit zoveel kandidaten (de nieuwste): ruim genoeg voor de nieuwste 20-30 over alles heen. */
    static final int PER_POD = 30;
    /** Ouder dan dit is niet meer "nieuw". */
    static final long MAX_AGE = 60L * 24 * 3600_000L;

    static final class Ep {
        final String key, guid, title, url, type, image;
        final long date, dur;
        Ep(String key, String guid, String title, String url, String type, String image, long date, long dur) {
            this.key = nz(key); this.guid = nz(guid); this.title = nz(title); this.url = nz(url); this.type = nz(type); this.image = nz(image);
            this.date = date; this.dur = dur;
        }
    }

    /** Wat van een bewaarde feed nodig is (zonder beschrijvingen: die haalt "Beschrijving" pas op als je erom vraagt). */
    static final class Feed {
        final String title, author, image, link;
        final List<Ep> eps;
        Feed(String title, String author, String image, String link, List<Ep> eps) {
            this.title = nz(title); this.author = nz(author); this.image = nz(image); this.link = nz(link);
            this.eps = eps == null ? Collections.<Ep>emptyList() : eps;
        }
    }

    static final class Sub {
        final String id, feed, title, author, image;
        final long seen;
        Sub(String id, String feed, String title, String author, String image, long seen) {
            this.id = nz(id); this.feed = nz(feed); this.title = nz(title); this.author = nz(author); this.image = nz(image); this.seen = seen;
        }
    }

    static final class Prog {
        final long pos, dur;
        final boolean done;
        Prog(long pos, long dur, boolean done) { this.pos = pos; this.dur = dur; this.done = done; }
    }

    static final class Pick {
        final Ep ep;
        final Sub sub;
        final Feed feed;
        final Prog prog;
        final boolean isNew;
        Pick(Ep ep, Sub sub, Feed feed, Prog prog, boolean isNew) { this.ep = ep; this.sub = sub; this.feed = feed; this.prog = prog; this.isNew = isNew; }
        /** Naam, maker en hoesje: wat bij het abonnement staat, anders wat de feed zegt (ook als het opgeslagen veld leeg is). */
        String title() { return first(sub.title, feed.title, "Podcast"); }
        String author() { return first(sub.author, feed.author, ""); }
        String image() { return first(ep.image, first(sub.image, feed.image, ""), ""); }
    }

    static String nz(String s) { return s == null ? "" : s; }
    static String first(String a, String b, String c) { return a != null && !a.trim().isEmpty() ? a : b != null && !b.trim().isEmpty() ? b : c; }

    /**
     * De keuze: per gevolgde podcast de nieuwste afleveringen die niet beluisterd zijn, niet weggehaald (hidden), niet ouder dan
     * MAX_AGE en met een datum; dan over alles heen de nieuwste eerst (bij gelijke datum de volgorde van Mijn podcasts),
     * zonder dubbele sleutels, hooguit limit. isNew = verschenen nadat je de podcast voor het laatst bekeek (zoals de teller "nieuw").
     * Podcasts die je niet (meer) volgt, doen niet mee: alleen subs telt.
     */
    static List<Pick> pick(List<Sub> subs, Function<String, Feed> feeds, Function<String, Prog> progress, Set<String> hidden, long now, int limit) {
        List<Pick> all = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        if (subs != null) for (Sub s : subs) {
            if (s == null || s.id.isEmpty() || !ids.add(s.id)) continue;
            Feed f;
            try { f = feeds.apply(s.id); } catch (Throwable t) { f = null; }
            if (f == null) continue;
            int taken = 0;
            for (Ep e : f.eps) {
                if (taken >= PER_POD) break;
                if (e == null || e.key.isEmpty() || e.date <= 0 || e.date < now - MAX_AGE) continue;
                if (hidden != null && hidden.contains(e.key)) continue;
                Prog p = progress == null ? null : progress.apply(e.key);
                if (p != null && p.done) continue;
                all.add(new Pick(e, s, f, p, s.seen > 0 && e.date > s.seen));
                taken++;
            }
        }
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) idx.add(i);
        final List<Pick> a = all;
        Collections.sort(idx, (x, y) -> a.get(x).ep.date != a.get(y).ep.date ? Long.compare(a.get(y).ep.date, a.get(x).ep.date) : Integer.compare(x, y));
        List<Pick> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (int i : idx) {
            if (out.size() >= Math.max(0, limit)) break;
            Pick p = all.get(i);
            if (used.add(p.ep.key)) out.add(p);
        }
        return out;
    }

    /**
     * De omgezette feeds per podcast in het geheugen. Opnieuw lezen gebeurt alleen als het bestand veranderde (tijd of grootte);
     * een kapot bestand wordt onthouden (niet bij elke keer opnieuw lezen), een verdwenen bestand vergeten.
     */
    static final class Cache {
        private final ConcurrentHashMap<String, Object[]> m = new ConcurrentHashMap<>();
        int reads;

        Feed get(String id, File f, Function<File, Feed> load) {
            if (id == null || f == null || !f.isFile()) { if (id != null) m.remove(id); return null; }
            long mod = f.lastModified(), len = f.length();
            Object[] e = m.get(id);
            if (e != null && (Long) e[0] == mod && (Long) e[1] == len) return (Feed) e[2];
            Feed feed;
            try { reads++; feed = load.apply(f); } catch (Throwable t) { feed = null; }
            m.put(id, new Object[] { mod, len, feed });
            return feed;
        }

        /** Net zelf geschreven (na ophalen): meteen goed, zonder het bestand opnieuw te lezen. */
        void put(String id, File f, Feed feed) {
            if (id == null || f == null || feed == null || !f.isFile()) return;
            m.put(id, new Object[] { f.lastModified(), f.length(), feed });
        }

        /** Alleen wat je nog volgt bewaren. */
        void retain(Set<String> ids) { m.keySet().retainAll(ids); }

        boolean has(String id) { Object[] e = m.get(id); return e != null && e[2] != null; }
    }
}
