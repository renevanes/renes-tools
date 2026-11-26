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
        public InputStream open(String naam) throws IOException { File f = new File(dir, naam); if (!f.isFile()) throw new Blokken.Ontbreekt(naam); return new FileInputStream(f); }
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
        Blokken.Schrijver.TERUG_WACHT = new long[]{1, 1}; // niet echt wachten in de test
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

        // Andere grootte bekend dan in de map (een andere telefoon pakte anders in): eerst nalezen, dan gewoon hergebruiken
        {
            Blokken.Item zi = null;
            for (Blokken.Item it : s[0].items) if (it.z) zi = it;
            check(zi != null, "er is een ingepakt groot bestand");
            File tf = File.createTempFile("blok", ".tmp");
            Files.write(tf.toPath(), files.get(zi.n));
            Blokken.Schrijver sw0 = new Blokken.Schrijver(o, k);
            sw0.bekend.put(zi.b, 12345L);
            int gem = o.gemaakt;
            sw0.bewaar(zi.n, tf, zi.s, zi.h);
            check(o.gemaakt == gem && sw0.hergebruikt == 1 && sw0.bekend.get(zi.b) == new File(dir, zi.b).length(), "andere bekende grootte maar inhoud klopt: hergebruikt, niets opnieuw geschreven");
            tf.delete();
        }

        // ---------- controle bij lezen ----------
        File doelwit = new File(dir, s[0].items.get(0).b);
        byte[] goed = Files.readAllBytes(doelwit.toPath());
        try (RandomAccessFile f = new RandomAccessFile(doelwit, "rw")) { f.seek(goed.length / 2); int b = f.read(); f.seek(goed.length / 2); f.write(b ^ 1); }
        String e1 = null;
        try { lees(arc, k, o); } catch (IOException e) { e1 = e.getMessage(); }
        check(e1 != null && Blokken.schade(new IOException(e1)), "beschadigd groot bestand: gemeld als schade (" + e1 + ")");
        IOException soort = null;
        try { lees(arc, k, o); } catch (IOException e) { soort = e; }
        check(soort instanceof Blokken.LeesFout, "fout bij lezen van een groot bestand: LeesFout (terugzetten slaat alleen dat bestand over)");
        Files.write(doelwit.toPath(), Arrays.copyOf(goed, goed.length - 30));
        e1 = null;
        try { lees(arc, k, o); } catch (IOException e) { e1 = e.getMessage(); }
        check(Blokken.AFGEKAPT.equals(e1) && Blokken.schade(new IOException(e1)), "afgekapt groot bestand (korter in de map dan geschreven): zekere schade (" + e1 + ")");
        // Zelfde afgekapte inhoud, maar de map meldt de volle grootte (verbinding viel weg): geen zekere schade, terugzetten stopt
        final byte[] kortGelezen = Files.readAllBytes(doelwit.toPath());
        Blokken.Opslag wankel = new Blokken.Opslag() {
            public Map<String, Long> lijst() throws IOException { return o.lijst(); }
            public Blokken.Schrijf maak(String naam) throws IOException { throw new IOException("nee"); }
            public InputStream open(String naam) throws IOException { return naam.equals(doelwit.getName()) ? new java.io.ByteArrayInputStream(kortGelezen) : o.open(naam); }
            public void weg(String naam) { }
        };
        Map<String, Long> volle = new HashMap<>(o.lijst()); volle.put(doelwit.getName(), (long) goed.length);
        Blokken.Opslag wankel2 = new Blokken.Opslag() {
            public Map<String, Long> lijst() { return volle; }
            public Blokken.Schrijf maak(String naam) throws IOException { throw new IOException("nee"); }
            public InputStream open(String naam) throws IOException { return wankel.open(naam); }
            public void weg(String naam) { }
        };
        IOException wf = null;
        try { lees(arc, k, wankel2); } catch (IOException e) { wf = e; }
        check(wf != null && !(wf instanceof Blokken.LeesFout) && Blokken.ONVOLLEDIG.equals(wf.getMessage()), "onvolledig gelezen bij de volle grootte: geen LeesFout (" + wf + ")");
        // Kapot op een manier die op een weggevallen verbinding lijkt (lengte van het laatste blok), maar het hele bestand
        // is gelezen en nog een keer lezen gaat precies zo: zeker schade (anders zou terugzetten er altijd op stoppen)
        {
            byte[] g = goed.clone();
            int pos = Vault.HEADER, laatste = -1;
            while (pos + 4 <= g.length) { laatste = pos; pos += 4 + java.nio.ByteBuffer.wrap(g, pos, 4).getInt(); }
            java.nio.ByteBuffer.wrap(g, laatste, 4).putInt(Vault.CHUNK + Vault.TAG);
            Files.write(doelwit.toPath(), g);
            IOException vk = null;
            try { lees(arc, k, o); } catch (IOException e) { vk = e; }
            check(vk instanceof Blokken.LeesFout && Blokken.AFGEKAPT.equals(vk.getMessage()), "vast kapot bij de volle grootte: zeker schade (" + vk + ")");
            check(Blokken.vastKapot(o, doelwit.getName(), k, s[0].items.get(0).z, s[0].items.get(0).s, s[0].items.get(0).h, g.length), "vastKapot: ja");
            Files.write(doelwit.toPath(), goed);
            check(!Blokken.vastKapot(o, doelwit.getName(), k, s[0].items.get(0).z, s[0].items.get(0).s, s[0].items.get(0).h, goed.length), "vastKapot: een goed bestand niet");
            Files.write(doelwit.toPath(), Arrays.copyOf(goed, goed.length - 30));
        }
        // Map niet te lezen of geen verbinding bij openen: geen LeesFout
        Blokken.Opslag offline = new Blokken.Opslag() {
            public Map<String, Long> lijst() throws IOException { throw new IOException("Geen verbinding"); }
            public Blokken.Schrijf maak(String naam) throws IOException { throw new IOException("nee"); }
            public InputStream open(String naam) throws IOException { throw new java.io.FileNotFoundException("offline"); }
            public void weg(String naam) { }
        };
        wf = null;
        try { lees(arc, k, offline); } catch (IOException e) { wf = e; }
        check(wf != null && !(wf instanceof Blokken.LeesFout), "geen verbinding: geen LeesFout, terugzetten stopt (" + wf + ")");
        check(!Blokken.schade(new IOException("Groot bestand onvolledig gelezen")) && !Blokken.schade(new IOException("Connection reset")), "te vroeg afgelopen of geen verbinding: geen zekere schade");
        doelwit.delete();
        e1 = null; soort = null;
        try { lees(arc, k, o); } catch (IOException e) { e1 = e.getMessage(); soort = e; }
        check(e1 != null && e1.contains("ontbreekt") && soort instanceof Blokken.LeesFout, "ontbrekend groot bestand: gemeld (" + e1 + ")");
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

        // Mislukt apart opslaan: het bestand gaat dan gewoon in het archief (dat blijft compleet), de fout wordt onthouden
        // en volgende grote bestanden proberen het niet steeds opnieuw
        ByteArrayOutputStream raw3 = new ByteArrayOutputStream();
        Secure.Zip z3 = new Secure.Zip(null, null, null, "x.rtb", raw3, k);
        z3.blok = new Blokken.Schrijver(stuk, k);
        z3.tmpDir = Files.createTempDirectory("blok").toFile();
        String e3 = null;
        try (OutputStream out = z3.stream("A", "groot.bin")) { out.write(ruis(50_000, 8)); } catch (IOException e) { e3 = e.getMessage(); }
        try (OutputStream out = z3.stream("A", "groot2.bin")) { out.write(ruis(60_000, 9)); }
        try (OutputStream out = z3.stream("A", "klein.txt")) { out.write("hoi".getBytes(StandardCharsets.UTF_8)); }
        z3.lijst();
        z3.z.close();
        check(e3 == null && z3.blokFout != null && z3.blok.items.isEmpty(), "apart opslaan mislukt: onthouden, geen fout voor het archief");
        Map<String, byte[]> l3 = lees(raw3.toByteArray(), k, stuk);
        check(Arrays.equals(l3.get("A/groot.bin"), ruis(50_000, 8)) && Arrays.equals(l3.get("A/groot2.bin"), ruis(60_000, 9)), "apart opslaan mislukt: de bestanden staan in het archief zelf");
        check(z3.tmpDir.list().length == 0, "geen tijdelijke bestanden achtergebleven");
        check(z3.blokFouten == 2, "beide grote bestanden apart geprobeerd (" + z3.blokFouten + ")");

        // Een cloudmap geeft een net geschreven bestand eerst nog onvolledig terug: even later nog eens lezen
        File traagDir = Files.createTempDirectory("traag").toFile();
        final int[] terugKeer = {0};
        Blokken.Opslag traag = new Blokken.Opslag() {
            public Map<String, Long> lijst() { return new HashMap<>(); }
            public Blokken.Schrijf maak(String naam) throws IOException {
                final File p = new File(traagDir, naam + ".part");
                return new Schrijf(p, new File(traagDir, naam)) {
                    @Override public InputStream terug() throws IOException {
                        byte[] b = Files.readAllBytes(p.toPath());
                        return new java.io.ByteArrayInputStream(terugKeer[0]++ == 0 ? Arrays.copyOf(b, b.length / 2) : b);
                    }
                };
            }
            public InputStream open(String naam) throws IOException { return new FileInputStream(new File(traagDir, naam)); }
            public void weg(String naam) { }
        };
        Blokken.Schrijver tw = new Blokken.Schrijver(traag, k);
        File tmp2 = File.createTempFile("blok", ".tmp");
        Files.write(tmp2.toPath(), ruis(70_000, 11));
        String e4 = null;
        try { tw.bewaar("x/z.bin", tmp2, 70_000, AppData.hex(AppData.sha256().digest(ruis(70_000, 11)))); } catch (IOException e) { e4 = e.getMessage(); }
        File[] traagFs = traagDir.listFiles();
        check(e4 == null && tw.nieuw == 1 && tw.terugOpnieuw == 1 && terugKeer[0] == 2 && traagFs.length == 1 && !traagFs[0].getName().endsWith(".part"),
                "teruglezen eerst onvolledig, daarna goed: toch apart bewaard (" + e4 + ")");
        check(Blokken.Schrijver.TERUG_WACHT.length == 2 && terugKeer[0] <= 1 + Blokken.Schrijver.TERUG_WACHT.length, "hooguit zo vaak opnieuw als er wachttijden zijn");

        // Eén bestand lukt apart niet (verbinding): dat ene in het archief, de volgende gewoon weer apart
        File halfDir = Files.createTempDirectory("half").toFile();
        final int[] maakKeer = {0};
        MapOpslag goedeMap = new MapOpslag(halfDir);
        Blokken.Opslag eenKeerMis = new Blokken.Opslag() {
            public Map<String, Long> lijst() { return goedeMap.lijst(); }
            public Blokken.Schrijf maak(String naam) throws IOException { if (maakKeer[0]++ == 0) throw new IOException("Connection reset"); return goedeMap.maak(naam); }
            public InputStream open(String naam) throws IOException { return goedeMap.open(naam); }
            public void weg(String naam) { goedeMap.weg(naam); }
        };
        ByteArrayOutputStream raw5 = new ByteArrayOutputStream();
        Secure.Zip z5 = new Secure.Zip(null, null, null, "x.rtb", raw5, k);
        z5.blok = new Blokken.Schrijver(eenKeerMis, k);
        z5.tmpDir = Files.createTempDirectory("blok").toFile();
        for (int i = 0; i < 3; i++) try (OutputStream out = z5.stream("B", "groot" + i + ".bin")) { out.write(ruis(50_000, 20 + i)); }
        z5.lijst();
        z5.z.close();
        Map<String, byte[]> l5 = lees(raw5.toByteArray(), k, eenKeerMis);
        check(z5.blokFouten == 1 && z5.blok.items.size() == 2 && z5.blok.nieuw == 2, "één keer mislukt: de volgende grote bestanden gaan weer apart (" + z5.blokFouten + ", " + z5.blok.items.size() + ")");
        boolean alle5 = true;
        for (int i = 0; i < 3; i++) alle5 &= Arrays.equals(l5.get("B/groot" + i + ".bin"), ruis(50_000, 20 + i));
        check(alle5, "en alles is terug te lezen (het mislukte uit het archief zelf)");

        // Blijft het mislukken, dan na MAX_FOUTEN keer niet meer proberen
        final int[] probeer = {0};
        Blokken.Opslag dicht = new Blokken.Opslag() {
            public Map<String, Long> lijst() { return new HashMap<>(); }
            public Blokken.Schrijf maak(String naam) throws IOException { probeer[0]++; throw new IOException("Connection reset"); }
            public InputStream open(String naam) throws IOException { throw new IOException("nee"); }
            public void weg(String naam) { }
        };
        ByteArrayOutputStream raw6 = new ByteArrayOutputStream();
        Secure.Zip z6 = new Secure.Zip(null, null, null, "x.rtb", raw6, k);
        z6.blok = new Blokken.Schrijver(dicht, k);
        z6.tmpDir = Files.createTempDirectory("blok").toFile();
        for (int i = 0; i < Blokken.MAX_FOUTEN + 3; i++) try (OutputStream out = z6.stream("C", "groot" + i + ".bin")) { out.write(ruis(40_000, 30 + i)); }
        z6.lijst();
        z6.z.close();
        check(probeer[0] == Blokken.MAX_FOUTEN && z6.blokFouten == Blokken.MAX_FOUTEN, "na " + Blokken.MAX_FOUTEN + " keer mislukt: de rest meteen in het archief (" + probeer[0] + ")");
        Map<String, byte[]> l6 = lees(raw6.toByteArray(), k, dicht);
        boolean alle6 = true;
        for (int i = 0; i < Blokken.MAX_FOUTEN + 3; i++) alle6 &= Arrays.equals(l6.get("C/groot" + i + ".bin"), ruis(40_000, 30 + i));
        check(alle6, "en dat archief is compleet");

        // ---------- de lijst uit een archief is niet te vertrouwen ----------
        String goedJson = Blokken.json(ids.kid, s[0].items);
        check(Blokken.parse(goedJson).size() == 4, "lijst terug te lezen");
        check(Blokken.parse(goedJson.replace("Kluis\\/brief.pdf", "..\\/brief.pdf")).size() == 3, "onveilige naam in de lijst: alleen die overgeslagen");
        check(Blokken.apart("Kluis/brief.pdf") && !Blokken.apart("../x") && !Blokken.apart(AppData.MANIFEST) && !Blokken.apart("/x"), "apart: alleen veilige namen, nooit wat altijd in het archief hoort");
        String[] slecht = {
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

        // ---------- één ronde opruimen (merkteken, wachttijd, gemiste archieven) ----------
        {
            String b1 = ids.naam(sha, false), b2 = ids.naam(sha, true);
            String sha3 = AppData.hex(AppData.sha256().digest("y".getBytes(StandardCharsets.UTF_8)));
            String b3 = ids.naam(sha3, false), vr = new Blokken.Ids(anders).naam(sha, false);
            org.json.JSONObject st = new org.json.JSONObject().put("archieven", new org.json.JSONObject()
                    .put("backup-A.rtb", new org.json.JSONArray().put(b1).put(b2)).put("backup-B.rtb", new org.json.JSONArray().put(b2)));
            List<String> map = Arrays.asList(b1, b2, b3, vr, "leesmij.txt", Blokken.sindsNaam(0));
            Set<String> beide = new HashSet<>(Arrays.asList("backup-A.rtb", "backup-B.rtb"));
            long t0 = 1_800_000_000_000L, uur = 3_600_000L;
            Blokken.Ronde r1 = Blokken.ronde(st, beide, map, ids.kid, t0);
            check(r1.weg.isEmpty() && st.getJSONObject("kandidaten").has(b3) && st.getJSONObject("kandidaten").has(vr), "eerste ronde: ongebruikt alleen onthouden, niets weg");
            check(Blokken.ronde(st, beide, map, ids.kid, t0 + 10 * uur).weg.isEmpty(), "tien uur later: nog niets weg");
            Blokken.Ronde r3 = Blokken.ronde(st, beide, map, ids.kid, t0 + 40 * uur);
            check(r3.weg.equals(Arrays.asList(b3, vr).stream().sorted().collect(java.util.stream.Collectors.toList())), "na twee rondes en 36 uur: alleen wat niemand gebruikt " + r3.weg);
            check(!r3.weg.contains(b1) && !r3.weg.contains(b2) && r3.gebruikt.contains(b1), "wat een archief gebruikt blijft");
            // Een archief dat de map even niet laat zien telt mee (pas na drie keer niet meer)
            Set<String> alleenB = new HashSet<>(Arrays.asList("backup-B.rtb"));
            for (int i = 1; i <= 2; i++) check(Blokken.ronde(st, alleenB, map, ids.kid, t0 + (40 + i) * uur).gebruikt.contains(b1), "archief " + i + " keer gemist: telt nog mee");
            Blokken.Ronde r6 = Blokken.ronde(st, alleenB, map, ids.kid, t0 + 43 * uur);
            check(!r6.gebruikt.contains(b1) && !r6.weg.contains(b1) && st.getJSONObject("kandidaten").has(b1), "drie keer gemist: telt niet meer, maar pas na een volgende ronde en 36 uur weg");
            check(Blokken.ronde(st, alleenB, map, ids.kid, t0 + 80 * uur).weg.contains(b1), "daarna wel weg");
            // Weer gebruikt (een nieuw archief) = de wachttijd begint opnieuw
            st.getJSONObject("archieven").put("backup-C.rtb", new org.json.JSONArray().put(b3));
            Set<String> metC = new HashSet<>(Arrays.asList("backup-B.rtb", "backup-C.rtb"));
            check(!Blokken.ronde(st, metC, map, ids.kid, t0 + 81 * uur).weg.contains(b3) && !st.getJSONObject("kandidaten").has(b3), "weer gebruikt: geen kandidaat meer");
            st.getJSONObject("archieven").remove("backup-C.rtb");
            check(Blokken.ronde(st, alleenB, map, ids.kid, t0 + 82 * uur).weg.stream().noneMatch(b3::equals), "opnieuw ongebruikt: eerst weer wachten");
            // Klok teruggezet: niet sneller weg
            st.getJSONObject("kandidaten").put(b3, t0 + 500 * uur);
            check(!Blokken.ronde(st, alleenB, map, ids.kid, t0 + 83 * uur).weg.contains(b3) && st.getJSONObject("kandidaten").getLong(b3) == t0 + 83 * uur, "kandidaat uit de toekomst: opnieuw wachten");
            // Een archief met een ander wachtwoord: grote bestanden van die sleutel blijven
            st.getJSONObject("archieven").put("backup-X.rtb", new org.json.JSONArray().put(Blokken.VREEMD));
            Set<String> metX = new HashSet<>(Arrays.asList("backup-B.rtb", "backup-X.rtb"));
            Blokken.ronde(st, metX, map, ids.kid, t0 + 90 * uur);
            check(!Blokken.ronde(st, metX, map, ids.kid, t0 + 200 * uur).weg.contains(vr) && !st.getJSONObject("kandidaten").has(vr), "ander wachtwoord in de map: die bestanden blijven");
        }

        // ---------- merkteken en opzij gezet ----------
        String m1 = Blokken.sindsNaam(1_700_000_000_000L);
        check(Blokken.SINDS_NAAM.matcher(m1).matches() && Math.abs(Blokken.sindsTijd(m1) - 1_700_000_000_000L) < 60_000, "merkteken: tijd in de naam (" + m1 + ")");
        check(Blokken.sinds(Arrays.asList("leesmij.txt", Blokken.sindsNaam(2_000_000_000_000L), m1)) == Blokken.sindsTijd(m1), "het vroegste merkteken telt");
        check(Blokken.sinds(Arrays.asList("leesmij.txt", ids.naam(sha, true))) == -1 && Blokken.kid(m1) == null, "geen merkteken: -1; een merkteken is geen groot bestand");
        String kp = Blokken.kapotNaam(ids.naam(sha, true), 1_700_000_000_000L);
        check(Blokken.kid(kp) == null && Blokken.KAPOT.matcher(kp).matches() && Math.abs(Blokken.kapotTijd(kp) - 1_700_000_000_000L) < 2 * 86_400_000L, "opzij gezet: eigen naam met datum (" + kp + ")");
        check(!Blokken.overbodig(Arrays.asList(kp, m1), new HashSet<>(), ids.kid, false).contains(kp), "opzij gezet en merkteken: nooit als ongebruikt weggehaald");

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
