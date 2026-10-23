package nl.rene.tools;

import java.util.ArrayList;
import java.util.List;

/** Slim zoeken: tekst in stukken, getallen als bytes, overeenkomst, beste per bron; en de context voor de assistent. */
public class SmartSearchTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    public static void main(String[] a) {
        // Stukken
        check(SmartSearch.chunks("", 100, 10).isEmpty() && SmartSearch.chunks(null, 100, 10).isEmpty(), "lege tekst: geen stukken");
        List<int[]> one = SmartSearch.chunks("  Korte notitie.  ", 100, 10);
        check(one.size() == 1 && "Korte notitie.".equals("  Korte notitie.  ".substring(one.get(0)[0], one.get(0)[1])), "korte tekst: één stuk zonder witruimte");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) sb.append("Dit is zin nummer ").append(i).append(" over de boodschappen. ");
        String t = sb.toString();
        List<int[]> cs = SmartSearch.chunks(t, 300, 60);
        boolean ends = true, sizes = true, cover = cs.get(0)[0] == 0 && cs.get(cs.size() - 1)[1] >= t.trim().length(), overlap = true, words = true;
        for (int i = 0; i < cs.size(); i++) {
            int[] r = cs.get(i);
            if (r[1] - r[0] > 300) sizes = false;
            if (i < cs.size() - 1 && t.charAt(r[1] - 1) != '.') ends = false;
            if (i > 0 && r[0] >= cs.get(i - 1)[1]) overlap = false;
            if (r[0] > 0 && !Character.isWhitespace(t.charAt(r[0] - 1))) words = false;
        }
        check(cs.size() > 5 && sizes, "lange tekst: stukken van hooguit 300 tekens");
        check(ends, "stukken eindigen na een zin");
        check(overlap && cover, "stukken overlappen en dekken de hele tekst");
        check(words, "stukken beginnen op een woordgrens");
        String noSpace = new String(new char[1000]).replace('\0', 'x');
        List<int[]> ns = SmartSearch.chunks(noSpace, 300, 50);
        check(ns.size() >= 4 && ns.get(ns.size() - 1)[1] == 1000, "tekst zonder spaties: toch in stukken, zonder te blijven hangen");

        // Getallen en overeenkomst
        float[] v = {0.6f, -0.8f, 0f};
        byte[] q = SmartSearch.quant(v);
        check(q[0] == 76 && q[1] == -102 && q[2] == 0, "omzetten naar bytes");
        check(SmartSearch.quant(new float[]{2f, -2f})[0] == 127 && SmartSearch.quant(new float[]{2f, -2f})[1] == -127, "buiten bereik: afgekapt");
        check(Math.abs(SmartSearch.dot(v, q) - 1f) < 0.02f, "zelfde reeks: overeenkomst ±1");
        check(Math.abs(SmartSearch.dot(new float[]{0.8f, 0.6f, 0f}, q)) < 0.02f, "loodrecht: ±0");

        // Beste per bron
        String[] keys = {"notes:a", "notes:a", "tx:b", "notes:c", "tx:d"};
        float[] sc = {0.5f, 0.7f, 0.65f, 0.2f, 0.4f};
        int[] best = SmartSearch.best(keys, sc, 3, 0.3f);
        check(best.length == 3 && best[0] == 1 && best[1] == 2 && best[2] == 4, "per bron het beste stuk, gesorteerd, onder de drempel weg");
        check(SmartSearch.best(keys, sc, 10, 0.9f).length == 0, "niets boven de drempel: niets");

        // Context voor de assistent
        check("Vraag?".equals(LocalAi.withSources("Vraag?", new ArrayList<>())), "zonder bronnen: alleen de vraag");
        List<String[]> src = new ArrayList<>();
        src.add(new String[]{"Notitie \"Boodschappen\"", "melk, eieren"});
        src.add(new String[]{"Gesprek met Jos", new String(new char[2000]).replace('\0', 'a')});
        String w = LocalAi.withSources("Wat moet ik kopen?", src);
        check(w.startsWith("Mijn vraag: Wat moet ik kopen?") && w.contains("[1] Notitie \"Boodschappen\":\nmelk, eieren") && w.contains("[2] Gesprek met Jos"), "eerst de vraag, dan de bronnen genummerd (inkorten raakt de vraag niet)");
        check(w.length() < 1400, "lange bron ingekort");

        // Berichten (sms, WhatsApp, oproepen) als bron
        check(SmartSearch.kindLabel("sms").startsWith("Sms") && SmartSearch.kindLabel("wa").startsWith("WhatsApp") && SmartSearch.kindLabel("calls").startsWith("Oproepen")
                && SmartSearch.kindLabel("contacts").startsWith("Contact") && SmartSearch.kindLabel("notes").startsWith("Notitie") && SmartSearch.kindLabel(null).startsWith("Notitie"), "soort bron in de tekst voor het zoekmodel");
        List<String> lines = new ArrayList<>(); List<Long> times = new ArrayList<>();
        for (int i = 0; i < 300; i++) { lines.add("[" + i + " okt] Jos: bericht nummer " + i + " over de verhuizing en de sleutels van het huis"); times.add(1000L + i); }
        check(SmartSearch.linesDoc("sms", "1", "Jos", new ArrayList<String>(), null, 0) == null, "geen berichten: geen bron");
        SmartSearch.Doc d1 = SmartSearch.linesDoc("sms", "1", "Jos", lines, times, 5);
        boolean small = true, starts = true;
        for (long[] m : d1.marks) {
            if (m[1] - m[0] > SmartSearch.CHUNK) small = false;
            if (!d1.text.startsWith("[", (int) m[0])) starts = false;
        }
        check(d1.marks.size() > 10 && small && starts, "berichten in stukken van hooguit CHUNK tekens, op een berichtgrens");
        check(d1.marks.get(0)[2] == 1000L && d1.text.endsWith("nummer 299 over de verhuizing en de sleutels van het huis\n"), "tijd van het eerste bericht per stuk; het nieuwste staat erin");
        // Er komt een bericht bij en het oudste valt weg: de meeste stukken blijven precies hetzelfde (geen werk voor het model)
        List<String> l2 = new ArrayList<>(lines.subList(1, 300)); List<Long> t2 = new ArrayList<>(times.subList(1, 300));
        l2.add("[300 okt] Ik: nieuw bericht"); t2.add(1300L);
        SmartSearch.Doc d2 = SmartSearch.linesDoc("sms", "1", "Jos", l2, t2, 6);
        java.util.Set<String> old = new java.util.HashSet<>();
        for (long[] m : d1.marks) old.add(d1.text.substring((int) m[0], (int) m[1]));
        int same = 0;
        for (long[] m : d2.marks) if (old.contains(d2.text.substring((int) m[0], (int) m[1]))) same++;
        check(same >= d2.marks.size() - 4, "nieuw bericht: hooguit een paar stukken nieuw (" + (d2.marks.size() - same) + " van " + d2.marks.size() + ")");
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 2000; i++) many.add("bericht " + i + " " + new String(new char[60]).replace('\0', 'x'));
        SmartSearch.Doc d3 = SmartSearch.linesDoc("wa", "2", "Groep", many, null, 0);
        check(d3.text.length() <= 60000 && d3.text.contains("bericht 1999 ") && !d3.text.contains("bericht 0 "), "te veel: de nieuwste berichten blijven");

        // Modellen: vaste grootte en controlegetal
        boolean shaOk = true;
        for (LocalAi.Model m : LocalAi.MODELS) if (!m.sha.matches("[0-9a-f]{64}") || m.size < 100_000_000L || !m.url().matches("https://huggingface\\.co/[^/]+/[^/]+/resolve/[0-9a-f]{40}/[^/]+\\.gguf")) shaOk = false;
        check(shaOk, "elk model heeft een SHA-256, een grootte en een vast https-adres (commit)");
        check(LocalAi.model("klein") == LocalAi.SMALL && LocalAi.model("x") == null && LocalAi.EMBED.embed && !LocalAi.LARGE.embed, "modellen opzoeken");

        if (fails > 0) { System.out.println(fails + " fout(en)"); System.exit(1); }
        System.out.println("alles goed");
        System.exit(0); // de AI-thread (LocalAi) niet laten wachten
    }
}
