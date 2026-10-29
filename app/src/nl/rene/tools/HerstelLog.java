package nl.rene.tools;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Logboek van het terugzetten van alle appgegevens (puur Java, getest in HerstelLogTest).
 *
 * Regels, in deze volgorde: "begin", per verplaatsing "M&lt;tab&gt;van&lt;tab&gt;naar" (vóór het verplaatsen geschreven),
 * "files" (alle mappen staan op hun plek), "prefs" (instellingen geschreven), "ok" (klaar).
 * Valt de app ertussen om, dan beslist {@link #decide} bij de volgende start:
 *  - vóór "files": alles terug zoals het was ({@link #rollback});
 *  - na "files" maar vóór "ok": afmaken met wat klaarstaat (de bestanden staan al goed);
 *  - na "ok": alleen opruimen.
 */
final class HerstelLog {

    private HerstelLog() { }

    static final int NOTHING = 0, ROLLBACK = 1, FINISH = 2, CLEANUP = 3;

    /** Wat er moet gebeuren met dit logboek (null of leeg = er liep niets). Alleen hele regels tellen. */
    static int decide(String log) {
        if (log == null || log.isEmpty()) return NOTHING;
        boolean files = false, ok = false;
        for (String l : log.split("\n")) {
            if ("ok".equals(l)) ok = true;
            else if ("files".equals(l)) files = true;
        }
        if (ok) return CLEANUP;
        return files ? FINISH : ROLLBACK;
    }

    /** De verplaatsingen in het logboek, in de volgorde waarin ze gebeurden: {van, naar}. */
    static List<String[]> moves(String log) {
        List<String[]> l = new ArrayList<>();
        if (log == null) return l;
        for (String line : log.split("\n")) {
            String[] p = line.split("\t", -1);
            if (p.length == 3 && "M".equals(p[0]) && !p[1].isEmpty() && !p[2].isEmpty()) l.add(new String[]{p[1], p[2]});
        }
        return l;
    }

    /** Eén regel erbij, meteen naar de schijf. */
    static void line(File journal, String s) throws IOException {
        try (FileOutputStream out = new FileOutputStream(journal, true)) {
            out.write((s + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            try { out.getFD().sync(); } catch (IOException ignored) { }
        }
    }

    /** Verplaatsen, met eerst de regel in het logboek (zo kan het altijd worden teruggedraaid). */
    static void move(File journal, File from, File to) throws IOException {
        File p = to.getParentFile();
        if (p != null && !p.isDirectory() && !p.mkdirs()) throw new IOException("map maken lukt niet");
        if (to.exists()) throw new IOException("bestaat al: " + to.getName());
        line(journal, "M\t" + from.getPath() + "\t" + to.getPath());
        if (!from.renameTo(to)) throw new IOException("verplaatsen lukt niet: " + from.getName());
    }

    /**
     * Alles wat volgens het logboek verplaatst is, terug (omgekeerde volgorde). Een verplaatsing die niet meer gebeurde
     * (de app viel om tussen de regel en het verplaatsen) wordt overgeslagen. Geeft het aantal teruggezette.
     */
    static int rollback(String log) {
        List<String[]> m = moves(log);
        int n = 0;
        for (int i = m.size() - 1; i >= 0; i--) {
            File from = new File(m.get(i)[0]), to = new File(m.get(i)[1]);
            if (to.exists() && !from.exists()) {
                File p = from.getParentFile();
                if (p != null && !p.isDirectory()) p.mkdirs();
                if (to.renameTo(from)) n++;
            }
        }
        return n;
    }
}
