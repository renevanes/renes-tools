package nl.rene.tools;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Logboek van het terugzetten (HerstelLog): de app valt op elk denkbaar moment om, daarna beslist de volgende start.
 * Vóór "files": alles precies terug zoals het was. Daarna: de teruggezette bestanden blijven (afmaken).
 */
public class HerstelLogTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    interface Step { void run() throws IOException; }

    static void write(File f, String s) throws IOException { f.getParentFile().mkdirs(); Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8)); }
    static String read(File f) throws IOException { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }

    /** Alle bestanden onder een map met hun inhoud (om toestanden te vergelijken). */
    static TreeMap<String, String> tree(File root) throws IOException {
        TreeMap<String, String> m = new TreeMap<>();
        walk(root, "", m);
        return m;
    }
    static void walk(File f, String rel, TreeMap<String, String> m) throws IOException {
        File[] k = f.listFiles();
        if (k == null) return;
        for (File x : k) { String r = rel + "/" + x.getName(); if (x.isDirectory()) walk(x, r, m); else m.put(r, read(x)); }
    }
    static void wipe(File f) { File[] k = f.listFiles(); if (k != null) for (File x : k) wipe(x); f.delete(); }

    public static void main(String[] a) throws Exception {
        // ---------- beslissen ----------
        check(HerstelLog.decide(null) == HerstelLog.NOTHING && HerstelLog.decide("") == HerstelLog.NOTHING, "geen logboek: niets te doen");
        check(HerstelLog.decide("begin\n") == HerstelLog.ROLLBACK, "alleen begonnen: terugdraaien");
        check(HerstelLog.decide("begin\nM\ta\tb\n") == HerstelLog.ROLLBACK, "halverwege het verplaatsen: terugdraaien");
        check(HerstelLog.decide("begin\nM\ta\tb\nfiles\n") == HerstelLog.FINISH, "bestanden op hun plek: afmaken");
        check(HerstelLog.decide("begin\nfiles\nprefs\n") == HerstelLog.FINISH, "instellingen geschreven: afmaken");
        check(HerstelLog.decide("begin\nfiles\nprefs\nok\n") == HerstelLog.CLEANUP, "klaar: alleen opruimen");
        check(HerstelLog.decide("begin\nM\t/x/files\t/y/files-oud\n") == HerstelLog.ROLLBACK, "\"files\" in een bestandsnaam telt niet als klaar");
        check(HerstelLog.decide("begin\nfil") == HerstelLog.ROLLBACK, "afgekapte regel telt niet");
        check(HerstelLog.moves("begin\nM\ta\tb\nM\tc\n\nM\td\te\nfiles\n").size() == 2, "alleen hele verplaatsregels");

        // ---------- omvallen op elk moment ----------
        File base = Files.createTempDirectory("herstel").toFile();
        int steps = -1;
        for (int crash = 0; crash < 100; crash++) {
            wipe(base); base.mkdirs();
            File files = new File(base, "files"), st = new File(base, "klaar"), old = new File(base, "oud"), jf = new File(base, "logboek.txt");
            // Nu op de telefoon: notities (oud), routes; in de backup: notities (nieuw) en opnames, geen routes
            write(new File(files, "notes.json"), "oud");
            write(new File(files, "tracks/r1.trk"), "route");
            write(new File(files, "tracks/live.trk"), "lopend");
            write(new File(st, "files/notes.json"), "nieuw");
            write(new File(st, "files/call-recordings/a.m4a"), "opname");
            TreeMap<String, String> before = tree(files);
            List<Step> plan = new ArrayList<>();
            plan.add(() -> HerstelLog.line(jf, "begin"));
            plan.add(() -> HerstelLog.move(jf, new File(files, "tracks/live.trk"), new File(st, "files/tracks/live.trk"))); // wat bij deze installatie hoort
            for (String r : new String[]{"notes.json", "tracks", "call-recordings"}) {
                plan.add(() -> { File cur = new File(files, r); if (cur.exists()) HerstelLog.move(jf, cur, new File(old, r)); });
                plan.add(() -> { File in = new File(st, "files/" + r); if (in.exists()) HerstelLog.move(jf, in, new File(files, r)); });
            }
            // Omvallen tussen de logboekregel en het verplaatsen zelf
            plan.add(() -> HerstelLog.line(jf, "M\t" + new File(base, "bestaat-niet") + "\t" + new File(base, "ook-niet")));
            plan.add(() -> HerstelLog.line(jf, "files"));
            plan.add(() -> HerstelLog.line(jf, "prefs"));
            plan.add(() -> HerstelLog.line(jf, "ok"));
            if (steps < 0) steps = plan.size();
            if (crash > plan.size()) break;
            for (int i = 0; i < crash; i++) plan.get(i).run();
            // ---- volgende start ----
            String log = jf.isFile() ? read(jf) : null;
            int d = HerstelLog.decide(log);
            TreeMap<String, String> want = new TreeMap<>();
            want.put("/notes.json", "nieuw"); want.put("/call-recordings/a.m4a", "opname"); want.put("/tracks/live.trk", "lopend");
            if (d == HerstelLog.ROLLBACK || d == HerstelLog.NOTHING) {
                HerstelLog.rollback(log);
                check(tree(files).equals(before), "omgevallen na stap " + crash + ": alles weer zoals het was " + tree(files));
            } else {
                check(tree(files).equals(want), "omgevallen na stap " + crash + ": teruggezet, lopende route bewaard " + tree(files));
            }
        }
        check(steps > 8, "alle " + steps + " momenten geprobeerd");
        // Verplaatsen naar iets wat al bestaat: weigeren (nooit iets overschrijven)
        File x = new File(base, "x"), y = new File(base, "y"), jf = new File(base, "l2");
        write(x, "1"); write(y, "2");
        boolean threw = false;
        try { HerstelLog.move(jf, x, y); } catch (IOException e) { threw = true; }
        check(threw && "2".equals(read(y)) && "1".equals(read(x)), "verplaatsen over een bestaand bestand: geweigerd");
        wipe(base);

        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
        System.out.println("alles goed");
    }
}
