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
            "BackupMap.java:", "ToolData.java:DIR"};

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
        check(WaBackup.better(f1, d1, sizes), "bestand boven map met dezelfde naam");

        check(BackupMap.temp("notities.json.rt-tmp") && BackupMap.temp("foto.jpg.rt-part") && BackupMap.temp("backup-2026.rtb.part"), "tijdelijke namen herkend");
        check(!BackupMap.temp("notities.json") && !BackupMap.temp("Factuur~20261009.pdf"), "gewone en oude versies zijn niet tijdelijk");
        check(BackupMap.known("Notities") && BackupMap.known("Toolgegevens") && !BackupMap.known("Oude foto's"), "bekende en onbekende mappen");

        System.out.println(fails == 0 ? "Alle BackupMap-tests geslaagd" : fails + " BackupMap-tests mislukt");
        System.exit(fails == 0 ? 0 : 1);
    }
}
