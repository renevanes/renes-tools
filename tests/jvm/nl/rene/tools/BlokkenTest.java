package nl.rene.tools;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.zip.ZipInputStream;

/** Grote bestanden één keer bewaren (Blokken): maken, lezen, hergebruiken, controleren en opruimen. */
public class BlokkenTest {

    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    /** De map Grote bestanden als gewone map (op de telefoon is dat de backup-map via Android). */
    static final class MapOpslag implements Blokken.Opslag {
        final File dir; int gemaakt = 0;
        MapOpslag(File d) { dir = d; d.mkdirs(); }
        public Map<String, Long> lijst() {
            Map<String, Long> m = new HashMap<>();
            File[] fs = dir.listFiles();
            if (fs != null) for (File f : fs) m.put(f.getName(), f.length());
            return m;
        }
        public Blokken.Schrijf maak(String naam) throws IOException {
            gemaakt++;
            return new Schrijf(new File(dir, naam + ".part"), new File(dir, naam));
        }
        public InputStream open(String naam) throws IOException { return new FileInputStream(new File(dir, naam)); }
        public void weg(String naam) { new File(dir, naam).delete(); }
    }

    static class Schrijf implements Blokken.Schrijf {
        final File part, doel;
        Schrijf(File p, File d) { part = p; doel = d; }
        public OutputStream uit() throws IOException { return new FileOutputStream(part); }
        public InputStream terug() throws IOException { return new FileInputStream(part); }
        public void klaar() throws IOException { if (!part.renameTo(doel)) throw new IOException("hernoemen"); }
        public void afbreken() { part.delete(); }
    }

    static byte[] tekst(int n, long seed) {
        Random r = new Random(seed);
        StringBuilder b = new StringBuilder();
        while (b.length() < n) b.append("{\"t\":").append(r.nextInt(1000)).append(",\"x\":\"regel\"}\n");
        return b.substring(0, n).getBytes(StandardCharsets.UTF_8);
    }

    static byte[] ruis(int n, long seed) { byte[] b = new byte[n]; new Random(seed).nextBytes(b); return b; }

    /** Maakt een archief zoals AllBackup: kleine en grote bestanden door elkaar. */
    static byte[] archief(Vault.Key k, Blokken.Opslag o, Map<String, byte[]> files, Blokken.Schrijver[] uit) throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        Secure.Zip zip = new Secure.Zip(null, null, null, "test.rtb", raw, k);
        zip.blok = new Blokken.Schrijver(o, k);
        zip.tmpDir = Files.createTempDirectory("blok").toFile();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            String p = e.getKey();
            int slash = p.lastIndexOf('/');
            if (p.endsWith(".m4a")) zip.stored(p.substring(0, slash), p.substring(slash + 1), e.getValue());
            else try (OutputStream out = zip.stream(p.substring(0, slash), p.substring(slash + 1))) { out.write(e.getValue()); }
        }
        zip.lijst();
        try (java.io.Writer w = zip.writer("", "inhoud.txt")) { w.write("klaar"); }
        zip.z.close();
        if (uit != null) uit[0] = zip.blok;
        return raw.toByteArray();
    }

    static Map<String, byte[]> lees(byte[] arc, Vault.Key k, Blokken.Opslag o) throws Exception {
        InputStream in = new java.io.ByteArrayInputStream(arc);
        Vault.In v = new Vault.In(in, Vault.readHeader(in), k);
        Map<String, byte[]> out = new java.util.LinkedHashMap<>();
        try (Blokken.Lezer l = new Blokken.Lezer(new ZipInputStream(v), v, Blokken.bron(o, k))) {
            while (l.volgende()) out.put(l.naam(), Blokken.klein(l.inhoud(), 1L << 30));
        }
        return out;
    }

    static String fout(Runnable r) { try { r.run(); return null; } catch (RuntimeException e) { return String.valueOf(e.getCause() != null ? e.getCause().getMessage() : e.getMessage()); } }

    public static void main(String[] a) throws Exception {
        Vault.Key k = Vault.derive("geheim-wachtwoord".toCharArray(), new byte[16], 1000, 1);
        Vault.Key anders = Vault.derive("ander".toCharArray(), new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16}, 1000, 1);

        // ---------- namen ----------
        Blokken.Ids ids = new Blokken.Ids(k);
        String sha = AppData.hex(AppData.sha256().digest("x".getBytes(StandardCharsets.UTF_8)));
        check(ids.naam(sha, false).equals(new Blokken.Ids(k).naam(sha, false)), "zelfde sleutel en inhoud: zelfde naam");
        check(!ids.naam(sha, false).equals(ids.naam(sha, true)), "met en zonder gzip: andere naam");
        check(!ids.naam(sha, false).equals(new Blokken.Ids(anders).naam(sha, false)), "ander wachtwoord: andere naam");
        check(Blokken.NAME.matcher(ids.naam(sha, true)).matches() && ids.kid.equals(Blokken.kid(ids.naam(sha, true))), "naam: sleutel-id en inhoud-id");
        check(!ids.naam(sha, false).contains(sha.substring(0, 16)), "de SHA-256 van de inhoud staat niet in de naam");
        check(Blokken.inpakken("a/notities.json") && !Blokken.inpakken("a/opname.m4a") && !Blokken.inpakken("a/brief.pdf"), "alleen inpakken wat kleiner kan");

        // ---------- maken en lezen ----------
        File dir = Files.createTempDirectory("groot").toFile();
        MapOpslag o = new MapOpslag(dir);
        Map<String, byte[]> files = new java.util.LinkedHashMap<>();
        files.put("Notities/notities.json", "[]".getBytes(StandardCharsets.UTF_8));                // klein: in het archief
        files.put("Appgegevens/bestanden/call-recordings/a.m4a", ruis(300_000, 1));              // groot, geluid, opgeslagen zonder gzip
        files.put("Appgegevens/bestanden/nieuws/lijst.json", tekst(500_000, 2));                 // groot, tekst: gzip
        files.put("Kluis/brief.pdf", ruis((int) Blokken.MIN, 3));                                // precies de grens: apart
        files.put("Appgegevens/bestanden/kopie/brief.pdf", ruis((int) Blokken.MIN, 3));          // zelfde inhoud: één bestand
        files.put("Appgegevens/bestanden/klein.bin", ruis((int) Blokken.MIN - 1, 4));             // net te klein: in het archief
        Blokken.Schrijver[] s = new Blokken.Schrijver[1];
        byte[] arc = archief(k, o, files, s);
        check(s[0].items.size() == 4, "4 grote bestanden in de lijst (" + s[0].items.size() + ")");
        check(s[0].nieuw == 3 && s[0].hergebruikt == 1, "zelfde inhoud twee keer: één keer opgeslagen (" + s[0].nieuw + "/" + s[0].hergebruikt + ")");
        check(o.lijst().size() == 3, "3 bestanden in de map (" + o.lijst().size() + ")");
        check(arc.length < 120_000, "het archief zelf is klein (" + arc.length + " bytes)");
        for (Blokken.Item it : s[0].items) if (!it.z) check(new File(dir, it.b).length() == Blokken.versleuteld(it.s) && it.e == Blokken.versleuteld(it.s), "grootte vooraf bekend: " + it.n);
        Blokken.Item gz = null;
        for (Blokken.Item it : s[0].items) if (it.n.endsWith("lijst.json")) gz = it;
        check(gz != null && gz.z && new File(dir, gz.b).length() < 250_000, "tekst ingepakt (" + (gz == null ? -1 : new File(dir, gz.b).length()) + " bytes)");

        Map<String, byte[]> terug = lees(arc, k, o);
        boolean gelijk = true;
        for (Map.Entry<String, byte[]> e : files.entrySet()) if (!Arrays.equals(e.getValue(), terug.get(e.getKey()))) { gelijk = false; System.out.println("  anders: " + e.getKey()); }
        check(gelijk && terug.containsKey("inhoud.txt"), "alles terug, ook de grote bestanden");
        check(!terug.containsKey(Blokken.LIST), "de lijst zelf komt niet langs");
        check(Arrays.equals(lees(arc, k, o).get("Kluis/brief.pdf"), files.get("Kluis/brief.pdf")), "nog een keer lezen");

        // Een oud archief (zonder lijst) leest als altijd
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        Secure.Zip oud = new Secure.Zip(null, null, null, "oud.rtb", raw, k);
        try (OutputStream out = oud.stream("Kluis", "groot.pdf")) { out.write(ruis(100_000, 9)); }
        oud.lijst();
        oud.z.close();
        Map<String, byte[]> o2 = lees(raw.toByteArray(), k, null);
        check(o2.size() == 1 && o2.get("Kluis/groot.pdf").length == 100_000, "zonder grote bestanden apart: alles in het archief, geen map nodig");

        // ---------- volgende nacht: hergebruiken ----------
        int voor = o.gemaakt;
        Blokken.Schrijver[] s2 = new Blokken.Schrijver[1];
        Map<String, byte[]> files2 = new java.util.LinkedHashMap<>(files);
        files2.put("Appgegevens/bestanden/call-recordings/b.m4a", ruis(200_000, 5)); // nieuwe opname
        byte[] arc2 = archief(k, o, files2, s2);
        check(o.gemaakt - voor == 1 && s2[0].nieuw == 1 && s2[0].hergebruikt == 4, "volgende backup: alleen de nieuwe opname erbij (" + (o.gemaakt - voor) + ")");
        check(arc2.length < 120_000, "tweede archief ook klein");
        check(lees(arc2, k, o).get("Appgegevens/bestanden/call-recordings/b.m4a").length == 200_000, "tweede archief leesbaar");

        // ---------- controle bij lezen ----------
        File doelwit = new File(dir, s[0].items.get(0).b);
        byte[] goed = Files.readAllBytes(doelwit.toPath());
        try (RandomAccessFile f = new RandomAccessFile(doelwit, "rw")) { f.seek(goed.length / 2); int b = f.read(); f.seek(goed.length / 2); f.write(b ^ 1); }
        String e1 = null;
        try { lees(arc, k, o); } catch (IOException e) { e1 = e.getMessage(); }
        check(e1 != null && Blokken.beschadigd(new IOException(e1)), "beschadigd groot bestand: gemeld (" + e1 + ")");
        Files.write(doelwit.toPath(), Arrays.copyOf(goed, goed.length - 30));
        e1 = null;
        try { lees(arc, k, o); } catch (IOException e) { e1 = e.getMessage(); }
        check(e1 != null && Blokken.beschadigd(new IOException(e1)), "afgekapt groot bestand: gemeld (" + e1 + ")");
        doelwit.delete();
        e1 = null;
        try { lees(arc, k, o); } catch (IOException e) { e1 = e.getMessage(); }
        check(e1 != null && e1.contains("ontbreekt"), "ontbrekend groot bestand: gemeld (" + e1 + ")");
        // Opnieuw back-uppen herstelt het (zelfde inhoud = zelfde naam): ook het oude archief klopt dan weer
        archief(k, o, files, null);
        check(doelwit.isFile() && lees(arc, k, o).size() == files.size() + 1, "na een nieuwe backup is het oude archief weer compleet");
        // Een groot bestand met een ander wachtwoord (bijv. onder de naam gezet) wordt geweigerd
        Files.write(doelwit.toPath(), Vault.seal(files.get("Appgegevens/bestanden/call-recordings/a.m4a"), anders));
        e1 = null;
        try { lees(arc, k, o); } catch (IOException e) { e1 = e.getMessage(); }
        check(e1 != null && e1.contains("ander wachtwoord"), "groot bestand van een ander wachtwoord: geweigerd");
        Files.write(doelwit.toPath(), goed);

        // Een onvolledig geschreven bestand krijgt nooit zijn echte naam
        Blokken.Opslag stuk = new Blokken.Opslag() {
            public Map<String, Long> lijst() { return new HashMap<>(); }
            public Blokken.Schrijf maak(String naam) throws IOException {
                final File p = new File(dir, naam + ".part");
                return new Schrijf(p, new File(dir, naam + ".x")) {
                    @Override public InputStream terug() throws IOException { byte[] b = Files.readAllBytes(p.toPath()); return new java.io.ByteArrayInputStream(Arrays.copyOf(b, b.length - 10)); }
                };
            }
            public InputStream open(String naam) throws IOException { throw new IOException("nee"); }
            public void weg(String naam) { }
        };
        String e2 = null;
        Blokken.Schrijver sw = new Blokken.Schrijver(stuk, k);
        File tmp = File.createTempFile("blok", ".tmp");
        Files.write(tmp.toPath(), ruis(70_000, 7));
        try { sw.bewaar("x/y.bin", tmp, 70_000, AppData.hex(AppData.sha256().digest(ruis(70_000, 7)))); } catch (Blokken.OpslagFout e) { e2 = e.getMessage(); }
        boolean geenX = true;
        for (File f : dir.listFiles()) if (f.getName().endsWith(".x") || f.getName().endsWith(".part")) geenX = false;
        check(e2 != null && geenX, "teruglezen klopt niet: niet bewaard, tijdelijk bestand weg (" + e2 + ")");

        // Mislukt opslaan: het archief weet het (wordt onvolledig), en volgende grote bestanden proberen het niet steeds opnieuw
        ByteArrayOutputStream raw3 = new ByteArrayOutputStream();
        Secure.Zip z3 = new Secure.Zip(null, null, null, "x.rtb", raw3, k);
        z3.blok = new Blokken.Schrijver(stuk, k);
        z3.tmpDir = Files.createTempDirectory("blok").toFile();
        String e3 = null;
        try (OutputStream out = z3.stream("A", "groot.bin")) { out.write(ruis(50_000, 8)); } catch (Blokken.OpslagFout e) { e3 = e.getMessage(); }
        try (OutputStream out = z3.stream("A", "klein.txt")) { out.write("hoi".getBytes(StandardCharsets.UTF_8)); }
        check(e3 != null && z3.blokFout != null && z3.blok.items.isEmpty(), "opslaan mislukt: gemeld en onthouden");
        check(z3.tmpDir.list().length == 0, "geen tijdelijke bestanden achtergebleven");

        // ---------- de lijst uit een archief is niet te vertrouwen ----------
        String goedJson = Blokken.json(ids.kid, s[0].items);
        check(Blokken.parse(goedJson).size() == 4, "lijst terug te lezen");
        String[] slecht = {
                goedJson.replace("Kluis\\/brief.pdf", "..\\/brief.pdf"),
                goedJson.replace("\"format\":\"" + Blokken.FORMAT + "\"", "\"format\":\"iets\""),
                goedJson.replace("\"kid\":\"" + ids.kid + "\"", "\"kid\":\"00000000\""),
                goedJson.replace("\"version\":1", "\"version\":99"),
                "{niet json",
        };
        int geweigerd = 0;
        for (String x : slecht) { try { Blokken.parse(x); System.out.println("  niet geweigerd: " + x.substring(0, Math.min(120, x.length()))); } catch (IOException e) { geweigerd++; } }
        check(geweigerd == slecht.length, "vreemde of beschadigde lijst geweigerd (" + geweigerd + "/" + slecht.length + ")");

        // ---------- opruimen ----------
        Set<String> gebruikt = new HashSet<>(Arrays.asList(ids.naam(sha, false)));
        List<String> er = new ArrayList<>(Arrays.asList(ids.naam(sha, false), ids.naam(sha, true), new Blokken.Ids(anders).naam(sha, false), "leesmij.txt", ids.naam(sha, true) + ".part"));
        List<String> weg = Blokken.overbodig(er, gebruikt, ids.kid, false);
        check(weg.equals(Arrays.asList(ids.naam(sha, true), new Blokken.Ids(anders).naam(sha, false)).stream().sorted().collect(java.util.stream.Collectors.toList())),
                "opruimen: alleen eigen namen die niemand gebruikt " + weg);
        check(Blokken.overbodig(er, gebruikt, ids.kid, true).equals(Arrays.asList(ids.naam(sha, true))), "archief met een ander wachtwoord: die bestanden blijven");

        // ---------- voor ontsleutelen.html (sec.test.js / herstel.test.js): een archief met grote bestanden apart ----------
        File uit = new File(System.getProperty("java.io.tmpdir"), "rt-groot-test");
        if (uit.isDirectory()) for (File f : uit.listFiles()) f.delete();
        MapOpslag ho = new MapOpslag(new File(uit, Blokken.DIR));
        Map<String, byte[]> hf = new java.util.LinkedHashMap<>();
        hf.put("Notities/notities.json", "[]".getBytes(StandardCharsets.UTF_8));
        hf.put("Kluis/groot.pdf", ruis(150_000, 11));
        hf.put("Appgegevens/bestanden/nieuws/lijst.json", tekst(120_000, 12));
        hf.put(AppData.SECRET_DIR + "/kluis/def456", ruis(90_000, 13));
        hf.put(AppData.PREFS, "{\"waKey\":\"abc\"}".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(uit, "backup-2026-11-24_0330.rtb").toPath(), archief(Vault.derive("geheim-wachtwoord".toCharArray(), new byte[16], 1000, 1), ho, hf, null));
        try (java.io.Writer w = new java.io.OutputStreamWriter(new FileOutputStream(new File(uit, "verwacht.txt")), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> e : hf.entrySet()) w.write(e.getKey() + "\t" + AppData.hex(AppData.sha256().digest(e.getValue())) + "\n");
        }
        check(new File(uit, Blokken.DIR).list().length == 3, "testarchief met grote bestanden voor ontsleutelen.html geschreven");

        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
        System.out.println("alles goed");
    }
}
