package nl.rene.tools;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Backup-map schoon houden: elke map die de app bovenin de backup-map maakt, staat in BackupMap.KNOWN (zodat het
 * opruimen hem kent en een nieuwe tool niet ongemerkt een eigen map erbij zet), en bij dubbele namen kiest de app
 * altijd hetzelfde exemplaar.
 */
public class BackupMapTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    /** Plekken waar het pad uit een variabele komt (en waarom dat goed zit). */
    static final String[] VARIABLE_OK = {
            "AiSleepBackup.java:dirPath", "AiSleepBackup.java:dirName", // binnen Slaappraat/
            "HerstelLos.java:p[1]", "HerstelLos.java:i[1]",               // alleen lezen, mappen uit de eigen lijsten
            "Secure.java:d", "Secure.java:rel.toString()",                // Versleuteld/ en Uitgepakt/…
            "WaBackup.java:parent", "WaBackup.java:destRel",               // binnen WhatsApp backup/
            "Facturen.java:rel",                                           // Facturen/<leverancier>
            "BackupMap.java:", "ToolData.java:DIR",
            "BlokOpslag.java:PAD", "BlokOpslag.java:Secure.DIR"};             // Versleuteld/Grote bestanden

    public static void main(String[] a) throws Exception {
        File src = new File("app/src/nl/rene/tools");
        Pattern call = Pattern.compile("dest\\.dir\\(([^,]+),");
        Pattern lit = Pattern.compile("^\"([^\"/]+)");
        Map<String, String> consts = new HashMap<>();
        File[] all = src.listFiles();
        for (File f : all) {
            if (!f.getName().endsWith(".java")) continue;
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("static final String ([A-Z_]+) = \"([^\"]*)\"").matcher(s);
            String cls = f.getName().replace(".java", "");
            while (m.find()) { consts.put(cls + "." + m.group(1), m.group(2)); }
            Matcher m2 = Pattern.compile("([A-Z_]+) = \"([^\"]*)\"").matcher(s); // ook "DIR = …, FILE = …" op één regel
            while (m2.find()) consts.putIfAbsent(cls + "." + m2.group(1), m2.group(2));
        }
        int checked = 0;
        for (File f : all) {
            if (!f.getName().endsWith(".java")) continue;
            String cls = f.getName().replace(".java", "");
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Matcher m = call.matcher(s);
            while (m.find()) {
                String arg = m.group(1).trim();
                String top = null;
                Matcher l = lit.matcher(arg);
                if (l.find()) top = l.group(1);
                else {
                    String first = arg.split("\\s*\\+\\s*")[0].trim();
                    String key = first.contains(".") ? first : cls + "." + first;
                    if (consts.containsKey(key)) top = consts.get(key).split("/")[0];
                }
                if (top == null) {
                    boolean ok = false;
                    for (String v : VARIABLE_OK) if ((cls + ".java:" + arg).startsWith(v) || (cls + ".java:" + arg).equals(v)) ok = true;
                    check(ok, cls + ": map uit een variabele staat in VARIABLE_OK (" + arg + ")");
                    continue;
                }
                checked++;
                if (!BackupMap.known(top)) check(false, cls + ": map \"" + top + "\" staat in BackupMap.KNOWN");
            }
        }
        check(checked > 20, "alle vaste mappen nagelopen (" + checked + ")");

        // Dubbele namen: bestand boven map, nieuwste bestand, map met meeste inhoud, anders laagste id
        WaBackup.Child f1 = new WaBackup.Child("b", 10, 100, false), f2 = new WaBackup.Child("a", 10, 200, false);
        check(WaBackup.better(f2, f1, null) && !WaBackup.better(f1, f2, null), "nieuwste bestand telt");
        WaBackup.Child d1 = new WaBackup.Child("x", 0, 0, true), d2 = new WaBackup.Child("y", 0, 0, true);
        Map<String, Integer> sizes = new HashMap<>(); sizes.put("x", 2); sizes.put("y", 40);
        check(WaBackup.better(d2, d1, sizes), "map met de meeste inhoud telt");
        sizes.put("y", 2);
        check(WaBackup.better(d1, d2, sizes) && !WaBackup.better(d2, d1, sizes), "gelijk: altijd dezelfde (laagste id)");
        check(WaBackup.better(d1, f1, sizes) && !WaBackup.better(f1, d1, sizes), "map boven bestand met dezelfde naam (de backup staat in de map)");
        check("notities.json".equals(BackupMap.realName("notities.json.rt-tmp")) && "a.jpg".equals(BackupMap.realName("a.jpg.rt-part")) && "x.json".equals(BackupMap.realName("x.json.rt-old")), "echte naam bij tijdelijke namen");
        check(BackupMap.realName("backup-1.rtb.part") == null && BackupMap.realName("notities.json") == null, "archieven en gewone bestanden: geen tijdelijke naam");
        check(BackupMap.ours("", "Notities") && BackupMap.ours("", BackupMap.MARKER) && !BackupMap.ours("", "Mijn foto's") && BackupMap.ours("Notities", "iets"), "bovenin alleen eigen mappen en bestanden");

        check(BackupMap.temp("notities.json.rt-tmp") && BackupMap.temp("foto.jpg.rt-part") && BackupMap.temp("backup-2026.rtb.part"), "tijdelijke namen herkend");
        check(!BackupMap.temp("notities.json") && !BackupMap.temp("Factuur~20261009.pdf"), "gewone en oude versies zijn niet tijdelijk");
        check(BackupMap.known("Notities") && BackupMap.known("Toolgegevens") && !BackupMap.known("Oude foto's"), "bekende en onbekende mappen");

        // Foutlogboek: waarom iets niet lukte en waar het stopte, in vaste woorden (geen namen van bestanden of mappen)
        check(BackupMap.soort(new Exception("Hernoemen lukt niet: Mijn geheime brief.pdf")).equals("hernoemen lukt niet")
                && BackupMap.soort(new Exception("Kopie onvolledig: vakantie.jpg")).equals("kopie onvolledig")
                && BackupMap.soort(new Exception("Verwijderen geweigerd")).equals("verwijderen geweigerd")
                && BackupMap.soort(new java.io.IOException("De backup-map is nog aan het laden; probeer het zo opnieuw")).equals("map laadt nog")
                && BackupMap.soort(new SecurityException("uri content://x/Jan")).equals("geen toegang")
                && BackupMap.soort(new java.io.FileNotFoundException("/Jan/foto.jpg")).equals("niet gevonden")
                && BackupMap.soort(new java.io.IOException("Connection reset by /10.0.0.1")).equals("verbinding of opslag")
                && BackupMap.soort(new IllegalStateException("Jan")).equals("IllegalStateException")
                && BackupMap.soort(null).equals("onbekend"), "reden van een fout: vaste woorden, zonder namen");
        check(BackupMap.bovenMap("WhatsApp backup/Media/Jan").equals("WhatsApp backup") && BackupMap.bovenMap("Notities").equals("Notities")
                && BackupMap.bovenMap("Mijn foto's/x").isEmpty() && BackupMap.bovenMap("").isEmpty() && BackupMap.bovenMap(null).isEmpty(), "waar het stopte: alleen een eigen map bovenin");
        BackupMap.Report r = new BackupMap.Report();
        r.repair = true; r.dupDirs = 2; r.dupFiles = 1;
        r.fout("verwijderen geweigerd"); r.fout("verwijderen geweigerd"); r.fout("map niet te lezen: map laadt nog");
        r.stopped = true; r.stopReden = "tijd op"; r.stopMap = "WhatsApp backup"; r.overgeslagen = 4;
        String regel = r.regel();
        check(regel.equals("backup-map opgeruimd: 3 punten, 3 fouten (1 map niet te lezen: map laadt nog, 2 verwijderen geweigerd), 4 mappen al eerder nagelopen, nog niet af (tijd op, in WhatsApp backup)"), "logregel: " + regel);
        BackupMap.Report leeg = new BackupMap.Report();
        leeg.repair = true;
        check(leeg.regel().equals("backup-map opgeruimd: 0 punten, 0 fouten"), "logregel zonder fouten: kort (" + leeg.regel() + ")");
        check(Logboek.probleem("BACKUP", regel) && !Logboek.probleem("BACKUP", leeg.regel()), "met fouten een probleem in het logboek, zonder niet");

        // Verder waar het opruimen stopte: alleen voor dezelfde backup-map
        java.util.Set<String> klaar = new java.util.HashSet<>(java.util.Arrays.asList("Notities", "WhatsApp backup/Media"));
        String kj = BackupMap.klaarJson(klaar, "content://drive/tree/A");
        check(BackupMap.klaarLees(kj, "content://drive/tree/A").equals(klaar), "nagelopen mappen terug te lezen");
        check(BackupMap.klaarLees(kj, "content://drive/tree/B").isEmpty(), "andere backup-map: vooraan beginnen");
        check(BackupMap.klaarLees("{kapot", "content://drive/tree/A").isEmpty() && BackupMap.klaarLees(null, "x").isEmpty()
                && BackupMap.klaarLees("[\"Notities\"]", "x").isEmpty(), "onleesbaar of oud formaat: vooraan beginnen");
        java.util.Set<String> veel = new java.util.HashSet<>();
        for (int i = 0; i < BackupMap.MAX_KLAAR + 50; i++) veel.add("Notities/" + i);
        check(BackupMap.klaarLees(BackupMap.klaarJson(veel, "m"), "m").size() == BackupMap.MAX_KLAAR, "hooguit MAX_KLAAR mappen onthouden");

        // Eenmalig opruimen na het opstarten: niet bij elke start van de app opnieuw
        long nu = 1_800_000_000_000L;
        check(BackupMap.onceMag(0, nu) && BackupMap.onceMag(nu - BackupMap.ONCE_PAUZE, nu) && !BackupMap.onceMag(nu - 60_000L, nu)
                && BackupMap.onceMag(nu + 3_600_000L, nu), "eenmalig opruimen: hooguit eens per zes uur (klok terug: mag)");

        System.out.println(fails == 0 ? "Alle BackupMap-tests geslaagd" : fails + " BackupMap-tests mislukt");
        System.exit(fails == 0 ? 0 : 1);
    }
}
