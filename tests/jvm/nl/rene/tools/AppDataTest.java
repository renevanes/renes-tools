package nl.rene.tools;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** Alle appgegevens (terugzetten na herinstallatie): wat meegaat, namen controleren, inhoudsopgave, opnieuw versleutelen. */
public class AppDataTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    static String sha(String s) throws Exception { return AppData.hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }

    public static void main(String[] a) throws Exception {
        // ---------- instellingen ----------
        check(AppData.keepPref("auto", "rules"), "automatiseringen gaan mee");
        check(AppData.keepPref("wabackup", "waKey"), "WhatsApp-sleutel gaat mee (alleen in het versleutelde archief)");
        check(!AppData.keepPref("wabackup", "dest"), "backup-map niet (net opnieuw gekozen)");
        check(!AppData.keepPref("secure", "wrapped"), "sleutel van de versleuteling nooit");
        check(!AppData.keepPref("secure", "plain_ai"), "toestemming voor leesbaar privé nooit");
        check(!AppData.keepPref("intent_token", "intentToken"), "geheim van deze installatie nooit");
        check(!AppData.keepPref("localai", "dl_klein") && !AppData.keepPref("localai", "own_klein") && !AppData.keepPref("localai", "ok_groot"), "downloads van modellen niet");
        check(AppData.keepPref("localai", "chat") && AppData.keepPref("localai", "wifiOnly"), "keuzes van de assistent wel");
        check(!AppData.keepPref("lock", "fails") && !AppData.keepPref("lock", "waitUntil") && AppData.keepPref("lock", "codeHash"), "app-slot: code wel, wachttijd niet");
        check(!AppData.keepPref("spotify", "refresh") && AppData.keepPref("spotify", "clientId"), "Spotify: ververs-sleutel apart (geheim), rest gewoon");
        check(!AppData.keepPref("mockloc", "active"), "gekozen locatie die nu actief is: niet");
        check(!AppData.keepPref("onbekend", "x"), "onbekend instellingenbestand: niet");
        check(!AppData.keepPref("auto", ""), "lege sleutel: niet");

        // Elke naam die de app gebruikt, moet ingedeeld zijn (mee of bewust niet): zo vergeet niemand een nieuwe
        File src = new File("app/src/nl/rene/tools");
        TreeSet<String> prefs = new TreeSet<>(), roots = new TreeSet<>();
        Pattern pp = Pattern.compile("getSharedPreferences\\(\"([A-Za-z0-9_]+)\""), pc = Pattern.compile("static final String PREFS\\s*=\\s*\"([A-Za-z0-9_]+)\"");
        Pattern pf = Pattern.compile("getFilesDir\\(\\),\\s*\"([^\"]+)\"");
        File[] javas = src.listFiles((d, n) -> n.endsWith(".java"));
        check(javas != null && javas.length > 100, "broncode gevonden (" + (javas == null ? 0 : javas.length) + " bestanden)");
        for (File f : javas) {
            String t = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Matcher m = pp.matcher(t); while (m.find()) prefs.add(m.group(1));
            m = pc.matcher(t); while (m.find()) prefs.add(m.group(1));
            m = pf.matcher(t); while (m.find()) roots.add(m.group(1).split("/")[0]);
        }
        List<String> loose = new ArrayList<>();
        for (String p : prefs) if (!AppData.PREFS_KEEP.contains(p) && !AppData.PREFS_SKIP.containsKey(p)) loose.add(p);
        check(loose.isEmpty(), "alle instellingenbestanden ingedeeld " + loose);
        loose.clear();
        for (String r : roots) if (!AppData.FILE_ROOTS.contains(r) && !AppData.FILE_SKIP.containsKey(r)) loose.add(r);
        check(loose.isEmpty(), "alle mappen in getFilesDir ingedeeld " + loose);
        check(prefs.contains("vpn") && prefs.contains("lock") && roots.contains("call-recordings"), "inventaris vindt vpn, lock en opnames");
        for (String p : AppData.PREFS_SKIP.keySet()) check(!AppData.PREFS_KEEP.contains(p), "niet tegelijk mee en niet mee: " + p);

        // ---------- bestanden ----------
        check(AppData.fileRule("notes.json") == null, "notities mee");
        check(AppData.fileRule("call-recordings/2026-10-01 Moeder.m4a") == null, "gespreksopnames mee");
        check(AppData.fileRule("sleep/n1/k1.wav") == null, "Slaappraat mee");
        check(AppData.fileRule("ai/memory.json") == null && AppData.fileRule("ai/index.json") != null, "geheugen wel, zoekindex niet");
        check(AppData.fileRule("ai-models/qwen.gguf") != null && AppData.fileRule("whisper/ggml-base.bin") != null, "modellen niet (opnieuw downloaden)");
        check(AppData.fileRule("web/index.html") != null, "bijgewerkte interface niet");
        check(AppData.fileRule("wa/msgstore.db") != null, "leesbare WhatsApp-database niet (wordt opnieuw gemaakt)");
        check(AppData.fileRule("kluis/abc123.bin") != null, "kluis niet als gewone bestanden (gaat ontsleuteld via geheim/)");
        check(AppData.fileRule("crash.log") != null, "foutlogboek niet");
        check(AppData.fileRule("podcasts/abonnementen.json") == null && AppData.fileRule("podcasts/downloads.json") != null, "podcasts: abonnementen wel, downloadlijst niet");
        check(AppData.fileRule("tracks/r1.trk") == null && AppData.fileRule("tracks/live.trk") != null, "routes wel, lopende opname niet");
        check(AppData.fileRule("transcripts/x.json.tmp") != null && AppData.fileRule("ai/chats.json.writing") != null && AppData.fileRule("sleep/a.wav.rt-part") != null, "tijdelijke bestanden niet");
        check(AppData.fileRule(".cache/llama") != null && AppData.fileRule("iets-nieuws/a.txt") != null, "onbekende mappen niet");

        // ---------- namen uit een (niet te vertrouwen) archief ----------
        check(AppData.safeRel("a/b/c.txt") != null, "gewoon pad");
        for (String bad : new String[]{"../x", "a/../../x", "/etc/passwd", "a//b", "a/./b", "a\\b", "C:/x", "a/", "", "a/\u0000b", "a/\nb", " /x", "a/ /b"})
            check(AppData.safeRel(bad) == null, "geweigerd: " + bad.replace("\n", "\\n").replace("\u0000", "\\0"));
        StringBuilder deep = new StringBuilder("a"); for (int i = 0; i < 20; i++) deep.append("/a");
        check(AppData.safeRel(deep.toString()) == null, "te diep geweigerd");
        check("f:notes.json".equals(AppData.target("Appgegevens/bestanden/notes.json")), "bestand → plek in de app");
        check(AppData.target("Appgegevens/bestanden/../../shared_prefs/secure.xml") == null, "uit de app-map ontsnappen: geweigerd");
        check(AppData.target("Appgegevens/bestanden/web/index.html") == null, "naar de interface schrijven (code in de WebView): geweigerd");
        check(AppData.target("Appgegevens/bestanden/ai-models/x.gguf") == null, "modelbestanden uit een backup: geweigerd");
        check(AppData.target("Appgegevens/bestanden/kluis/x.bin") == null, "kluis buiten om: geweigerd");
        check("kluis:abc123def".equals(AppData.target("Appgegevens/geheim/kluis/abc123def")) && AppData.target("Appgegevens/geheim/kluis/../x") == null
                && AppData.target("Appgegevens/geheim/kluis/A-B") == null, "kluisdocument alleen met een geldig id");
        check("prefs".equals(AppData.target(AppData.PREFS)) && "secrets".equals(AppData.target(AppData.SECRETS)) && AppData.target("Notities/notities.json") == null, "vaste onderdelen herkend, de rest niet");

        // ---------- inhoudsopgave ----------
        AppData.Manifest m = new AppData.Manifest();
        m.app = "1.106"; m.appCode = "107"; m.time = 1_760_000_000_000L;
        m.entries.add(new AppData.Entry(AppData.PREFS, 5, sha("hallo")));
        m.entries.add(new AppData.Entry("Appgegevens/bestanden/notes.json", 2, sha("[]"), 1_759_000_000_000L));
        m.entries.add(new AppData.Entry("Appgegevens/bestanden/call-recordings/Moeder\tbel.m4a".replace("\t", " "), 3, sha("abc")));
        m.skipped.add(new String[]{"ai-models", "taalmodellen: worden opnieuw gedownload"});
        m.broken.add("Appgegevens/bestanden/tracks/half.trk");
        String text = AppData.build(m);
        AppData.Manifest back = AppData.parse(text);
        check(back.entries.size() == 3 && "1.106".equals(back.app) && back.time == m.time && back.bytes() == 10, "inhoudsopgave terug te lezen");
        check(back.find("Appgegevens/bestanden/notes.json").mtime == 1_759_000_000_000L, "tijd van het bestand bewaard");
        check(back.broken.contains("Appgegevens/bestanden/tracks/half.trk") && back.skipped.size() == 1, "halve en overgeslagen bestanden staan erin");
        Map<String, AppData.Entry> seen = new HashMap<>();
        for (AppData.Entry e : back.entries) seen.put(e.path, new AppData.Entry(e.path, e.size, e.sha));
        seen.put("Appgegevens/bestanden/tracks/half.trk", new AppData.Entry("Appgegevens/bestanden/tracks/half.trk", 1, sha("x")));
        check(AppData.verify(back, seen) == null, "alles klopt: in orde");
        Map<String, AppData.Entry> s2 = new HashMap<>(seen);
        s2.put(AppData.PREFS, new AppData.Entry(AppData.PREFS, 5, sha("hallO")));
        check(String.valueOf(AppData.verify(back, s2)).startsWith("Beschadigd"), "aangepast bestand: beschadigd");
        s2 = new HashMap<>(seen); s2.remove("Appgegevens/bestanden/notes.json");
        check(String.valueOf(AppData.verify(back, s2)).startsWith("Ontbreekt"), "ontbrekend bestand: gemerkt");
        s2 = new HashMap<>(seen); s2.put("Appgegevens/bestanden/sleep/extra.wav", new AppData.Entry("x", 1, sha("y")));
        check(String.valueOf(AppData.verify(back, s2)).startsWith("Niet in de inhoudsopgave"), "ondergeschoven bestand: gemerkt");
        s2 = new HashMap<>(seen); s2.put("Appgegevens/bestanden/notes.json", new AppData.Entry("x", 3, sha("[]")));
        check(AppData.verify(back, s2) != null, "andere grootte: gemerkt");
        boolean threw = false;
        try { AppData.parse(text.substring(0, text.indexOf("end\t"))); } catch (Exception e) { threw = true; }
        check(threw, "afgekapte inhoudsopgave (zonder slotregel): geweigerd");
        threw = false;
        try { AppData.parse(text.replace("end\t3\t0", "end\t2\t0")); } catch (Exception e) { threw = true; }
        check(threw, "verkeerd aantal: geweigerd");
        threw = false;
        try { AppData.parse(text.replace("renes-tools-app\t1", "renes-tools-app\t9")); } catch (Exception e) { threw = e.getMessage().contains("nieuwere versie"); }
        check(threw, "van een nieuwere versie: duidelijke melding");
        threw = false;
        try { AppData.parse(text.replace("Appgegevens/bestanden/notes.json", "Appgegevens/bestanden/../notes.json")); } catch (Exception e) { threw = true; }
        check(threw, "vreemde naam in de inhoudsopgave: geweigerd");
        threw = false;
        try { AppData.parse("F\t1\t" + sha("a") + "\t0\tAppgegevens/instellingen.json\nend\t1\t0\n"); } catch (Exception e) { threw = true; }
        check(threw, "geen kopregel: geweigerd");
        threw = false;
        String dup = text.replace("end\t3\t0", "F\t5\t" + sha("hallo") + "\t0\t" + AppData.PREFS + "\nend\t4\t0");
        try { AppData.parse(dup); } catch (Exception e) { threw = true; }
        check(threw, "dubbele naam: geweigerd");

        AppData.Manifest nw = AppData.parse(text.replace("end\t3\t0", "F\t4\t" + sha("iets") + "\t0\tAppgegevens/nieuw-onderdeel/x.json\nend\t4\t0"));
        check(AppData.verify(nw, seen) == null, "onbekend (veilig) onderdeel van een nieuwere versie: overgeslagen, rest in orde");

        // ---------- verwijzingen (zelfde inhoud staat al bij een ander onderdeel) ----------
        AppData.Manifest mr = new AppData.Manifest();
        mr.entries.add(new AppData.Entry(AppData.PREFS, 5, sha("hallo")));
        mr.refs.add(new AppData.Ref("Kluis/Paspoort.pdf", AppData.KLUIS_DIR + "/abc123def", 8, sha("paspoort"), 0));
        mr.refs.add(new AppData.Ref("Slaappraat/n1/k1.m4a", "Appgegevens/bestanden/sleep/n1/k1.m4a", 6, sha("geluid"), 1_700_000_000_000L));
        AppData.Manifest br = AppData.parse(AppData.build(mr));
        check(br.refs.size() == 2 && br.refs.get(1).mtime == 1_700_000_000_000L && br.bytes() == 5 && br.refBytes() == 14, "verwijzingen terug te lezen");
        Map<String, AppData.Entry> own = new HashMap<>(), other = new HashMap<>();
        own.put(AppData.PREFS, new AppData.Entry(AppData.PREFS, 5, sha("hallo")));
        other.put("Kluis/Paspoort.pdf", new AppData.Entry("Kluis/Paspoort.pdf", 8, sha("paspoort")));
        other.put("Slaappraat/n1/k1.m4a", new AppData.Entry("Slaappraat/n1/k1.m4a", 6, sha("geluid")));
        check(AppData.verify(br, own, other) == null, "verwijzingen kloppen met de andere onderdelen");
        other.put("Slaappraat/n1/k1.m4a", new AppData.Entry("Slaappraat/n1/k1.m4a", 6, sha("gelui!")));
        check(String.valueOf(AppData.verify(br, own, other)).startsWith("Beschadigd"), "andere inhoud bij het onderdeel: beschadigd");
        other.remove("Kluis/Paspoort.pdf");
        check(String.valueOf(AppData.verify(br, own, other)).startsWith("Ontbreekt"), "onderdeel weg: ontbreekt");
        for (String badRef : new String[]{"R\t8\t" + sha("p") + "\t0\tAppgegevens/x\t" + AppData.KLUIS_DIR + "/abc123def",
                "R\t8\t" + sha("p") + "\t0\t../Kluis/x\t" + AppData.KLUIS_DIR + "/abc123def",
                "R\t8\t" + sha("p") + "\t0\tKluis/x\tNotities/notities.json",
                "R\t8\t" + sha("p") + "\t0\tKluis/x\t" + AppData.PREFS, "R\t8\t" + sha("p") + "\t0\tKluis/x\t" + AppData.SECRETS}) {
            threw = false;
            try { AppData.parse("renes-tools-app\t1\n" + badRef + "\nend\t0\t1\n"); } catch (Exception e) { threw = true; }
            check(threw, "vreemde verwijzing geweigerd");
        }

        // ---------- welke archieven alle appgegevens hebben ----------
        String list = AppData.appNamesAdd(AppData.appNamesAdd("", "backup-2026-10-26_0330.rtb", 3), "backup-2026-10-27_0330.rtb", 3);
        list = AppData.appNamesAdd(list + "../stiekem.rtb\nonzin\n", "backup-2026-10-26_0330.rtb", 3);
        check(AppData.appNames(list).equals(Arrays.asList("backup-2026-10-27_0330.rtb", "backup-2026-10-26_0330.rtb")), "lijst van archieven met appgegevens " + AppData.appNames(list));
        String l2 = ""; for (int i = 10; i < 20; i++) l2 = AppData.appNamesAdd(l2, "backup-2026-10-" + i + "_0330.rtb", 3);
        check(AppData.appNames(l2).size() == 3 && AppData.appNames(l2).get(2).equals("backup-2026-10-19_0330.rtb"), "lijst blijft kort (nieuwste blijven)");

        // ---------- meldingen zonder inhoud ----------
        check("x".equals(AppData.safeMsg(new Exception("Value {\"waKey\":\"geheim\"} of type org.json.JSONObject"), "x")), "JSON-fout met invoer: algemene melding");
        check("x".equals(AppData.safeMsg(new Exception((String) null), "x")) && "Verkeerd wachtwoord".equals(AppData.safeMsg(new java.io.IOException("Verkeerd wachtwoord"), "x")), "gewone melding blijft");
        check(AppData.audio("sleep/n1/k1.m4a") && AppData.audio("sleep/n1/k1.WAV") && !AppData.audio("sleep/n1/night.json"), "geluid van Slaappraat herkend");

        // ---------- controlegetal tijdens het schrijven ----------
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        AppData.DigestOut d = new AppData.DigestOut(bo);
        byte[] data = "Notities van Rene".getBytes(StandardCharsets.UTF_8);
        d.write(data, 0, 5); d.write(data, 5, data.length - 5); d.close();
        check(d.n == data.length && d.sha().equals(sha("Notities van Rene")) && Arrays.equals(bo.toByteArray(), data), "grootte en SHA-256 tellen mee");

        // ---------- opnieuw versleutelen (sleutel van de oude en de nieuwe installatie) ----------
        KeyGenerator g = KeyGenerator.getInstance("AES"); g.init(256);
        SecretKey oldK = g.generateKey(), newK = g.generateKey();
        String conf = "[Interface]\nPrivateKey = aGVsbG8gd2lyZWd1YXJkIGtleSBoZXJlIQ==\n";
        String sealedOld = AppData.seal(oldK, conf);
        check(sealedOld.matches("[A-Za-z0-9+/=]+:[A-Za-z0-9+/=]+") && !sealedOld.contains("PrivateKey"), "versleuteld bewaard (iv:gegevens)");
        String plain = AppData.open(oldK, sealedOld);                 // bij het maken van de backup (oude telefoon)
        String sealedNew = AppData.seal(newK, plain);                 // bij terugzetten (nieuwe installatie)
        check(conf.equals(AppData.open(newK, sealedNew)), "na opnieuw versleutelen weer te lezen met de nieuwe sleutel");
        threw = false;
        try { AppData.open(newK, sealedOld); } catch (Exception e) { threw = true; }
        check(threw, "oude versleuteling is met de nieuwe sleutel niet te lezen (daarom opnieuw versleutelen)");
        threw = false;
        try { AppData.open(newK, sealedNew.substring(0, sealedNew.length() - 4) + "AAA="); } catch (Exception e) { threw = true; }
        check(threw, "aangepaste versleutelde waarde: geweigerd");

        // base64 zoals android.util.Base64 (NO_WRAP), zonder java.util.Base64 in de app
        java.util.Random r = new java.util.Random(7);
        boolean same = true;
        for (int n = 0; n < 70; n++) {
            byte[] b = new byte[n]; r.nextBytes(b);
            String e = java.util.Base64.getEncoder().encodeToString(b);
            if (!e.equals(AppData.b64(b)) || !Arrays.equals(b, AppData.unb64(e))) same = false;
        }
        check(same, "base64 gelijk aan de standaard (0..69 bytes)");
        threw = false;
        try { AppData.unb64("ab$d"); } catch (Exception e) { threw = true; }
        check(threw, "ongeldige base64: geweigerd");

        // ---------- welke backup ----------
        List<String> names = AppData.archives(Arrays.asList("backup-2026-10-26_0330.rtb", "backup-2026-10-28_0331_deels.rtb", "backup-2026-10-28_0331.rtb.part",
                "ontsleutelen.html", "backup-2026-10-27_0330.rtb", "backup-2026-10-27_033012.rtb"));
        check(names.equals(Arrays.asList("backup-2026-10-28_0331_deels.rtb", "backup-2026-10-27_033012.rtb", "backup-2026-10-27_0330.rtb", "backup-2026-10-26_0330.rtb")), "archieven nieuwste eerst, zonder .part " + names);
        check("backup-2026-10-27_033012.rtb".equals(AppData.recommended(names)), "voorstel: nieuwste volledige (niet de afgebroken)");
        check("backup-2026-10-28_0331_deels.rtb".equals(AppData.recommended(Arrays.asList("backup-2026-10-28_0331_deels.rtb"))), "alleen een afgebroken: die dan");
        check(AppData.recommended(new ArrayList<String>()) == null, "geen archieven: geen voorstel");
        check("backup-2026-10-26_0330.rtb".equals(AppData.recommended(names, Arrays.asList("backup-2026-10-26_0330.rtb", "backup-2026-09-01_0330.rtb"))),
                "voorstel: nieuwste waarin alle appgegevens gelukt zijn");
        check("backup-2026-10-27_033012.rtb".equals(AppData.recommended(names, Arrays.asList("backup-2025-01-01_0330.rtb"))), "die bestaat niet meer: nieuwste volledige");

        check(AppData.compressed("Moeder.m4a") && !AppData.compressed("night.json"), "al samengeperst geluid herkend");

        // ---------- heel archief: maken, versleutelen, lezen en controleren (zoals AppBackup.stage) ----------
        Vault.Key k = Vault.derive("geheim wachtwoord".toCharArray(), new byte[16], 1000, 1);
        byte[] arc = archive(k, false);
        check(readAndVerify(arc, k) == null, "versleuteld archief: helemaal gelezen en in orde");
        check(String.valueOf(readAndVerify(archive(k, true), k)).startsWith("Beschadigd"), "archief met een ander bestand dan in de inhoudsopgave: beschadigd");
        Vault.Key wrong = Vault.derive("verkeerd".toCharArray(), new byte[16], 1000, 1);
        check(String.valueOf(readAndVerify(arc, wrong)).contains("Verkeerd wachtwoord"), "verkeerd wachtwoord: duidelijk");
        check(String.valueOf(readAndVerify(Arrays.copyOf(arc, arc.length - 40), k)).contains("onvolledig"), "afgekapt archief: onvolledig");

        // Archieven voor de test van ontsleutelen.html (herstel.test.js): met geheimen, en een met zip64 (> 65535 bestanden)
        Vault.Key pk = Vault.derive("geheim-wachtwoord".toCharArray(), new byte[16], 1000, 1);
        for (int n : new int[]{0, 70_000}) {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(new Vault.Out(raw, pk))) {
                String[][] files = {{"Notities/notities.json", "[]"}, {AppData.PREFS, "{\"waKey\":\"abc\"}"}, {AppData.SECRETS, "{\"vpn\":\"PrivateKey\"}"},
                        {AppData.KLUIS_DIR + "/abc123def", "paspoort"}, {"Appgegevens/bestanden/notes.json", "[{\"id\":\"n1\"}]"}};
                for (String[] f : files) { z.putNextEntry(new java.util.zip.ZipEntry(f[0])); z.write(f[1].getBytes(StandardCharsets.UTF_8)); z.closeEntry(); }
                for (int i = 0; i < n; i++) { z.putNextEntry(new java.util.zip.ZipEntry("Vul/x" + i + ".txt")); z.write('x'); z.closeEntry(); }
            }
            Files.write(new File(System.getProperty("java.io.tmpdir"), n == 0 ? "rt-app-test.rtb" : "rt-app-test64.rtb").toPath(), raw.toByteArray());
        }
        check(new File(System.getProperty("java.io.tmpdir"), "rt-app-test64.rtb").length() > 0, "testarchieven voor ontsleutelen.html geschreven");

        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
        System.out.println("alles goed");
    }

    /** Een klein archief zoals AppBackup.export het maakt; tamper = een bestand anders dan de inhoudsopgave zegt. */
    static byte[] archive(Vault.Key k, boolean tamper) throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        AppData.Manifest m = new AppData.Manifest();
        m.app = "test"; m.time = 1;
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(new Vault.Out(raw, k))) {
            String[][] files = {{"Notities/notities.json", "[]"}, {AppData.PREFS, "{\"format\":\"renes-tools-app-prefs\"}"},
                    {"Appgegevens/bestanden/notes.json", "[{\"id\":\"n1\"}]"}, {"Appgegevens/bestanden/call-recordings/a.m4a", "geluid"}};
            for (String[] f : files) {
                z.putNextEntry(new java.util.zip.ZipEntry(f[0]));
                byte[] b = (tamper && f[0].endsWith("a.m4a") ? "anders" : f[1]).getBytes(StandardCharsets.UTF_8);
                z.write(b);
                z.closeEntry();
                if (f[0].startsWith("Appgegevens/")) m.entries.add(new AppData.Entry(f[0], f[1].getBytes(StandardCharsets.UTF_8).length, sha(f[1])));
            }
            z.putNextEntry(new java.util.zip.ZipEntry(AppData.MANIFEST));
            z.write(AppData.build(m).getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        return raw.toByteArray();
    }

    static String readAndVerify(byte[] arc, Vault.Key k) {
        try {
            java.io.InputStream in = new java.io.ByteArrayInputStream(arc);
            Map<String, AppData.Entry> seen = new HashMap<>();
            String man = null;
            try (java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(new Vault.In(in, Vault.readHeader(in), k))) {
                java.util.zip.ZipEntry e;
                byte[] buf = new byte[4096];
                while ((e = z.getNextEntry()) != null) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    AppData.DigestOut d = new AppData.DigestOut(b);
                    int r; while ((r = z.read(buf)) > 0) d.write(buf, 0, r);
                    if (e.getName().equals(AppData.MANIFEST)) man = new String(b.toByteArray(), StandardCharsets.UTF_8);
                    else if (e.getName().startsWith("Appgegevens/")) { if (AppData.target(e.getName()) == null) return "onbekend: " + e.getName(); seen.put(e.getName(), new AppData.Entry(e.getName(), d.n, d.sha())); }
                }
            }
            return AppData.verify(AppData.parse(man), seen);
        } catch (Exception e) { return String.valueOf(e.getMessage()); }
    }
}
