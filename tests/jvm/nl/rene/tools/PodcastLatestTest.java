package nl.rene.tools;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** "Nieuwe afleveringen" op Podcasts: welke afleveringen, in welke volgorde, en wanneer een bewaarde feed opnieuw gelezen wordt. */
public class PodcastLatestTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    static final long NOW = 1_800_000_000_000L, DAY = 86_400_000L;

    static PodcastLatest.Ep ep(String key, long daysAgo) { return new PodcastLatest.Ep(key, "g" + key, "Titel " + key, "https://x.nl/" + key + ".mp3", "audio/mpeg", "", NOW - daysAgo * DAY, 1800); }
    static PodcastLatest.Feed feed(String title, PodcastLatest.Ep... eps) { return new PodcastLatest.Feed(title, "Maker", "https://x.nl/hoes.jpg", "https://x.nl", Arrays.asList(eps)); }
    static PodcastLatest.Sub sub(String id, long seenDaysAgo) { return new PodcastLatest.Sub(id, "https://x.nl/" + id, "", "", "", seenDaysAgo < 0 ? 0 : NOW - seenDaysAgo * DAY); }
    static List<String> keys(List<PodcastLatest.Pick> l) { List<String> o = new ArrayList<>(); for (PodcastLatest.Pick p : l) o.add(p.ep.key); return o; }

    public static void main(String[] a) throws Exception {
        Map<String, PodcastLatest.Feed> feeds = new HashMap<>();
        feeds.put("pa", feed("Podcast A", ep("a1", 1), ep("a2", 3), ep("a3", 5)));
        feeds.put("pb", feed("Podcast B", ep("b1", 2), ep("b2", 4)));
        feeds.put("pc", feed("Niet meer gevolgd", ep("c1", 0)));
        Map<String, PodcastLatest.Prog> prog = new HashMap<>();
        List<PodcastLatest.Sub> subs = Arrays.asList(sub("pa", 2), sub("pb", -1));

        List<PodcastLatest.Pick> l = PodcastLatest.pick(subs, feeds::get, prog::get, Collections.<String>emptySet(), NOW, 20);
        check(keys(l).equals(Arrays.asList("a1", "b1", "a2", "b2", "a3")), "nieuwste eerst, over alle podcasts heen: " + keys(l));
        check(!keys(l).contains("c1"), "niet (meer) gevolgd: doet niet mee, ook al staat de feed er nog");
        check(PodcastLatest.pick(subs, feeds::get, prog::get, null, NOW, 2).size() == 2, "hooguit het gevraagde aantal");

        // isNew: verschenen na de laatste keer bekijken; nooit bekeken (seen 0) = niet als nieuw markeren
        check(l.get(0).isNew && !l.get(2).isNew, "nieuw = na de laatste keer dat je de podcast bekeek");
        check(!l.get(1).isNew, "seen nog onbekend: niet nieuw");

        // Beluisterd overslaan, half beluisterd met plek erbij
        prog.put("a1", new PodcastLatest.Prog(0, 0, true));
        prog.put("b1", new PodcastLatest.Prog(60_000, 1_800_000, false));
        l = PodcastLatest.pick(subs, feeds::get, prog::get, null, NOW, 20);
        check(!keys(l).contains("a1"), "beluisterd: overgeslagen");
        check(l.get(0).ep.key.equals("b1") && l.get(0).prog.pos == 60_000, "half beluisterd: met de plek");

        // Weggehaald (Uit deze lijst halen, of uit Verder luisteren): komt niet terug
        l = PodcastLatest.pick(subs, feeds::get, prog::get, new HashSet<>(Arrays.asList("b1")), NOW, 20);
        check(!keys(l).contains("b1"), "weggehaald: niet terug");

        // Ouder dan 60 dagen of zonder datum: niet nieuw
        feeds.put("pd", feed("Oud", ep("d1", 61), ep("d2", 59), new PodcastLatest.Ep("d3", "", "", "https://x.nl/d3", "", "", 0, 0)));
        l = PodcastLatest.pick(Arrays.asList(sub("pd", -1)), feeds::get, prog::get, null, NOW, 20);
        check(keys(l).equals(Arrays.asList("d2")), "ouder dan 60 dagen of zonder datum: weg: " + keys(l));

        // Dubbele sleutels en dubbele abonnementen: één keer; gelijke datum: volgorde van Mijn podcasts
        feeds.put("px", feed("X", ep("same", 1)));
        feeds.put("py", feed("Y", ep("same", 1), ep("y1", 1)));
        l = PodcastLatest.pick(Arrays.asList(sub("px", -1), sub("py", -1), sub("px", -1)), feeds::get, prog::get, null, NOW, 20);
        check(keys(l).equals(Arrays.asList("same", "y1")) && l.get(0).feed.title.equals("X"), "dubbel: één keer, gelijke datum in vaste volgorde: " + keys(l));

        // Hooguit PER_POD per podcast
        List<PodcastLatest.Ep> many = new ArrayList<>();
        for (int i = 0; i < 50; i++) many.add(ep("m" + i, 0));
        feeds.put("pm", new PodcastLatest.Feed("M", "", "", "", many));
        check(PodcastLatest.pick(Arrays.asList(sub("pm", -1)), feeds::get, prog::get, null, NOW, 100).size() == PodcastLatest.PER_POD, "hooguit " + PodcastLatest.PER_POD + " per podcast");

        // Naam en maker: leeg opgeslagen = die van de feed
        l = PodcastLatest.pick(Arrays.asList(new PodcastLatest.Sub("pa", "f", " ", "", "", 0)), feeds::get, prog::get, null, NOW, 1);
        check(l.get(0).title().equals("Podcast A") && l.get(0).author().equals("Maker") && l.get(0).image().equals("https://x.nl/hoes.jpg"), "lege naam of maker: die van de feed");
        l = PodcastLatest.pick(Arrays.asList(new PodcastLatest.Sub("pa", "f", "Eigen naam", "", "", 0)), feeds::get, prog::get, null, NOW, 1);
        check(l.get(0).title().equals("Eigen naam"), "eigen naam blijft");
        // Een kapotte feed laat de rest niet vallen
        l = PodcastLatest.pick(Arrays.asList(sub("kapot", -1), sub("pb", -1)), id -> { if (id.equals("kapot")) throw new RuntimeException("x"); return feeds.get(id); }, prog::get, null, NOW, 5);
        check(keys(l).equals(Arrays.asList("b1", "b2")), "fout bij één podcast: de rest gaat door");

        // ---- De cache: alleen opnieuw lezen als het bestand veranderde ----
        File dir = Files.createTempDirectory("podlatest").toFile();
        File f = new File(dir, "pa.json");
        Files.write(f.toPath(), "goed".getBytes(StandardCharsets.UTF_8));
        PodcastLatest.Cache c = new PodcastLatest.Cache();
        final int[] loads = { 0 };
        java.util.function.Function<File, PodcastLatest.Feed> load = file -> {
            loads[0]++;
            try { String t = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); return t.startsWith("goed") ? feed(t) : null; }
            catch (Exception e) { return null; }
        };
        PodcastLatest.Feed g1 = c.get("pa", f, load), g2 = c.get("pa", f, load);
        check(g1 != null && g1 == g2 && loads[0] == 1, "ongewijzigd bestand: één keer gelezen");
        Files.write(f.toPath(), "goed, nu langer".getBytes(StandardCharsets.UTF_8));
        PodcastLatest.Feed g3 = c.get("pa", f, load);
        check(g3 != null && g3.title.equals("goed, nu langer") && loads[0] == 2, "bestand veranderd: opnieuw gelezen");
        f.setLastModified(f.lastModified() - 5000);
        c.get("pa", f, load);
        check(loads[0] == 3, "alleen de tijd veranderd: ook opnieuw gelezen");
        Files.write(f.toPath(), "kapot{".getBytes(StandardCharsets.UTF_8));
        check(c.get("pa", f, load) == null && c.get("pa", f, load) == null && loads[0] == 4, "kapot bestand: niets, en niet bij elke keer opnieuw proberen");
        check(c.get("weg", new File(dir, "weg.json"), load) == null && loads[0] == 4, "geen bestand: niets, zonder te lezen");
        Files.write(f.toPath(), "goed".getBytes(StandardCharsets.UTF_8));
        PodcastLatest.Feed mine = feed("zelf geschreven");
        c.put("pa", f, mine);
        check(c.get("pa", f, load) == mine && loads[0] == 4, "net zelf geschreven (na ophalen): niet opnieuw gelezen");
        f.delete();
        check(c.get("pa", f, load) == null && !c.has("pa"), "bestand weg: vergeten");
        c.put("pb", new File(dir, "nee.json"), mine);
        check(!c.has("pb"), "put zonder bestand: niets bewaard");
        File f2 = new File(dir, "pq.json"); Files.write(f2.toPath(), "goed".getBytes(StandardCharsets.UTF_8));
        c.get("pq", f2, load);
        c.retain(new HashSet<>(Arrays.asList("pa")));
        check(!c.has("pq"), "niet meer gevolgd: uit het geheugen");
        f2.delete(); dir.delete();

        if (fails > 0) { System.out.println(fails + " fout(en)"); System.exit(1); }
        System.out.println("alles goed");
    }
}
