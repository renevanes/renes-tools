package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;

/** Facturen ophalen: instellingen controleren, sjablonen, inlogcode vinden, bestandsnamen en wanneer het aan de beurt is. */
public class FacturenTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }
    static String fout(JSONObject o) { try { Facturen.normaal(o); return ""; } catch (Exception e) { return e.getMessage(); } }

    public static void main(String[] a) throws Exception {
        // ---------- sjablonen ----------
        JSONObject sj = new JSONObject(new String(Files.readAllBytes(Paths.get("web/facturen-leveranciers.json")), StandardCharsets.UTF_8));
        JSONArray lijst = sj.getJSONArray("sjablonen");
        check(lijst.length() >= 2 && lijst.getJSONObject(0).getString("sjabloon").equals("kpn-zakelijk"), "eerste sjabloon is KPN Zakelijk");
        HashSet<String> ids = new HashSet<>();
        for (int i = 0; i < lijst.length(); i++) {
            JSONObject s = lijst.getJSONObject(i);
            check(ids.add(s.getString("sjabloon")), "sjabloon uniek: " + s.getString("sjabloon"));
            JSONObject t = new JSONObject(s.toString());
            if (t.optString("naam").isEmpty()) t.put("naam", "Test");
            if (t.optString("startUrl").isEmpty()) t.put("startUrl", "https://voorbeeld.nl/login");
            check(fout(t).isEmpty(), "sjabloon geldig: " + s.getString("sjabloon") + " " + fout(t));
        }
        JSONObject kpn = Facturen.normaal(lijst.getJSONObject(0));
        check(kpn.getString("id").equals("kpn-zakelijk") && kpn.getJSONObject("code").getString("bron").equals("sms"), "KPN: id en code per sms");
        check(kpn.getString("schema").equals("maand") && kpn.getInt("dag") == 5 && kpn.getString("tijd").equals("08:00") && kpn.getBoolean("map") && !kpn.getBoolean("eigenCloud"), "KPN: elke maand op de 5e om 08:00, backup-map aan, Eigen Cloud uit");
        check(!kpn.getBoolean("lokaal") && !kpn.getBoolean("drive"), "map op de telefoon en Google Drive staan standaard uit");
        check(FacturenDrive.geldigeClientId("1234567890-abc123def.apps.googleusercontent.com") && !FacturenDrive.geldigeClientId("abc") && !FacturenDrive.geldigeClientId(null), "Client ID van Google herkennen");
        check(FacturenDrive.q("Rene's facturen").equals("Rene\\'s facturen"), "naam veilig in een Drive-zoekvraag");
        check(kpn.getString("submap").equals("KPN Zakelijk"), "mapnaam = naam");

        // ---------- opgeslagen lijst ----------
        check(Facturen.lijstUit("{}").length() == 0, "nog niets opgeslagen = lege lijst (knop Leverancier toevoegen werkt)");
        check(Facturen.lijstUit("{\"lijst\":[{\"id\":\"a\"}]}").length() == 1, "opgeslagen lijst lezen");
        boolean fout = false; try { Facturen.lijstUit("{\"x\":1}"); } catch (Exception e) { fout = true; }
        check(fout, "echt onleesbaar blijft onleesbaar");

        // ---------- controleren ----------
        JSONObject basis = new JSONObject().put("naam", "Ziggo").put("startUrl", "https://www.ziggo.nl/mijn-ziggo");
        check(fout(basis).isEmpty(), "naam + https-adres is genoeg");
        check(fout(new JSONObject(basis.toString()).put("naam", "")).contains("naam"), "naam verplicht");
        check(fout(new JSONObject(basis.toString()).put("startUrl", "http://ziggo.nl")).contains("https://"), "alleen https");
        check(fout(new JSONObject(basis.toString()).put("startUrl", "javascript:alert(1)")).contains("https://"), "geen javascript:-adres");
        check(fout(new JSONObject(basis.toString()).put("facturenUrl", "file:///sdcard/x")).contains("https://"), "facturenpagina ook https");
        check(fout(new JSONObject(basis.toString()).put("knoppen", "download(")).contains("geen geldig zoekpatroon"), "kapot patroon geweigerd");
        check(fout(new JSONObject(basis.toString()).put("schema", "uur")).contains("hoe vaak"), "onbekend schema geweigerd");
        check(fout(new JSONObject(basis.toString()).put("code", new JSONObject().put("bron", "telepathie"))).contains("bron"), "onbekende codebron geweigerd");
        check(fout(new JSONObject(basis.toString()).put("velden", new JSONObject().put("knop", "button{color:red}"))).contains("veldaanwijzing"), "rare veldaanwijzing geweigerd");
        JSONObject n = Facturen.normaal(new JSONObject(basis.toString()).put("max", 500).put("submap", "a/b:c").put("id", "Mijn Ziggo!!"));
        check(n.getInt("max") == 50, "hooguit 50 per keer");
        check(n.getString("submap").equals("a_b_c"), "mapnaam veilig: " + n.getString("submap"));
        check(n.getString("id").equals("mijn-ziggo"), "id veilig: " + n.getString("id"));
        check(Facturen.normaal(new JSONObject(basis.toString()).put("knoppen", "")).getString("knoppen").contains("download"), "leeg patroon: standaard");
        check(Facturen.uniekId("KPN Zakelijk", new HashSet<>(Arrays.asList("kpn-zakelijk", "kpn-zakelijk-2"))).equals("kpn-zakelijk-3"), "tweede en derde KPN-account");
        check(Facturen.veiligId("").equals("leverancier") && Facturen.veiligId("---").equals("leverancier"), "lege id");

        // ---------- inlogcode ----------
        check("482913".equals(Facturen.vindCode("Je KPN verificatiecode is: 482913. Deel deze code met niemand.", "")), "code uit KPN-sms");
        check("7731".equals(Facturen.vindCode("Toegangscode 7731 (geldig 10 min) voor klantnummer 12345678", "")), "korte code bij het woord code, niet het klantnummer");
        check("123456".equals(Facturen.vindCode("Gebruik 123456 om in te loggen", "")), "losse 6 cijfers");
        check(Facturen.vindCode("Je factuur staat klaar", "") == null, "geen code");
        check("AB12CD".equals(Facturen.vindCode("Code: AB12CD", "Code: ([A-Z0-9]{6})")), "eigen patroon met groep");
        check("9999".equals(Facturen.vindCode("x 9999 y", "\\d{4}")), "eigen patroon zonder groep");
        check(Facturen.vindCode("code 123456", "(") == null, "kapot patroon: geen code, geen crash");
        check(Facturen.komtVan("KPN", "x", "kpn") && Facturen.komtVan("+316", "Uw KPN code", "KPN") && !Facturen.komtVan("Bank", "code 1234", "KPN") && Facturen.komtVan("x", "y", ""), "afzender herkennen");

        // ---------- bestandsnamen ----------
        check(Facturen.naamUitDisposition("attachment; filename=\"Factuur 2026-10.pdf\"").equals("Factuur 2026-10.pdf"), "filename=\"…\"");
        check(Facturen.naamUitDisposition("attachment; filename*=UTF-8''Factuur%20oktober%20%E2%82%AC.pdf; filename=\"x.pdf\"").equals("Factuur oktober €.pdf"), "filename* gaat voor");
        check(Facturen.naamUitDisposition("inline; filename=F123.pdf").equals("F123.pdf"), "zonder aanhalingstekens");
        check(Facturen.naamUitDisposition(null).isEmpty(), "geen kop");
        String sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        check(Facturen.bestandsnaam("Factuur 2026-10.pdf", null, "KPN Zakelijk", sha).equals("KPN Zakelijk Factuur 2026-10.pdf"), "naam van de leverancier ervoor");
        check(Facturen.bestandsnaam("KPN_factuur_123.pdf", null, "KPN Zakelijk", sha).equals("KPN_factuur_123.pdf"), "begint al met KPN: zo laten");
        check(Facturen.bestandsnaam("", "https://x.nl/api/invoices/F-2026-0042.pdf?token=abc", "Ziggo", sha).equals("Ziggo F-2026-0042.pdf"), "naam uit het adres");
        check(Facturen.bestandsnaam("", "https://x.nl/download/3f2a9c1e-77aa-4b1c-9d2e-0a1b2c3d4e5f", "Ziggo", sha).equals("Ziggo factuur 01234567.pdf"), "nietszeggend adres: factuur + code");
        check(Facturen.bestandsnaam("../../geheim.pdf", null, "X", sha).equals("X .._.._geheim.pdf"), "geen mappen in de naam: " + Facturen.bestandsnaam("../../geheim.pdf", null, "X", sha));
        check(!Facturen.bestandsnaam("a:b*c?.pdf", null, "", sha).matches(".*[:*?].*"), "FAT-veilig");

        // ---------- alleen op de eigen website van de leverancier ----------
        check(Facturen.domein("https://mijnkpnzakelijk.kpn.com").equals("kpn.com"), "domein van KPN Zakelijk");
        check(Facturen.domein("https://login.example.co.uk/x").equals("example.co.uk") && Facturen.domein("https://kpn.com").equals("kpn.com"), "domein bij .co.uk en kort adres");
        check(Facturen.eigenSite("https://id.kpn.com/login?x=1", "kpn.com"), "id.kpn.com hoort bij kpn.com");
        check(!Facturen.eigenSite("http://id.kpn.com/login", "kpn.com"), "zonder https: niet");
        check(!Facturen.eigenSite("https://kpn.com.aanvaller.nl/login", "kpn.com") && !Facturen.eigenSite("https://nepkpn.com/", "kpn.com"), "lijkt op kpn.com maar is het niet");
        check(!Facturen.eigenSite("https://kpn.com@aanvaller.nl/", "kpn.com"), "gebruikersnaam-truc in het adres");
        check(Facturen.host("https://user@ID.KPN.com:443/a").equals("id.kpn.com") && Facturen.host("javascript:x").isEmpty(), "host lezen");
        check(Facturen.uniekeNaam("KPN Zakelijk Factuur 2026-10.pdf") && Facturen.uniekeNaam("F-123-45.pdf") && !Facturen.uniekeNaam("KPN Zakelijk factuur.pdf") && !Facturen.uniekeNaam("KPN factuur 0123abcd.pdf"), "naam die één factuur aanwijst");

        // ---------- PDF herkennen ----------
        check(Facturen.isPdf("%PDF-1.7\n...".getBytes(StandardCharsets.UTF_8)), "PDF");
        check(Facturen.isPdf(("\n\n%PDF-1.4").getBytes(StandardCharsets.UTF_8)), "PDF met wat ervoor");
        check(!Facturen.isPdf("<!doctype html><html>".getBytes(StandardCharsets.UTF_8)) && !Facturen.isPdf(null) && !Facturen.isPdf(new byte[2]), "geen PDF");

        // ---------- facturen uit de mail (Gmail) ----------
        JSONObject gmail = null;
        for (int i = 0; i < lijst.length(); i++) if ("gmail".equals(lijst.getJSONObject(i).optString("sjabloon"))) gmail = Facturen.normaal(lijst.getJSONObject(i));
        check(gmail != null && gmail.getString("soort").equals("mail") && gmail.getString("startUrl").isEmpty() && gmail.getJSONObject("mail").getString("bron").equals("gmail"), "sjabloon Gmail: soort mail, geen inlogpagina nodig");
        check(fout(new JSONObject().put("naam", "X").put("soort", "fax")).contains("soort"), "onbekende soort geweigerd");
        check(fout(new JSONObject().put("naam", "X").put("soort", "mail").put("mail", new JSONObject().put("bron", "duifpost"))).contains("mailbron"), "onbekende mailbron geweigerd");
        check(fout(new JSONObject().put("naam", "X")).contains("inlogpagina"), "website zonder inlogpagina: geweigerd");
        JSONObject mm = FacturenMail.normaal(new JSONObject().put("woorden", "factuur; invoice,  factuur ,\"(x)\"").put("afzenders", "kpn.com, Ziggo NL").put("dagen", 5000).put("max", 0));
        check(mm.getString("woorden").equals("factuur, invoice, x"), "zoekwoorden opgeschoond: " + mm.getString("woorden"));
        check(mm.getInt("dagen") == 730 && mm.getInt("max") == 1 && mm.getBoolean("perAfzender"), "grenzen en standaard per afzender");
        String q = FacturenGmail.zoekvraag(mm.put("negeer", "nieuws@winkel.nl").put("extra", "-in:spam"), 1_700_000_000_000L);
        check(q.equals("has:attachment filename:pdf (factuur OR filename:factuur OR invoice OR filename:invoice OR x OR filename:x) from:(kpn.com OR \"Ziggo NL\") -from:nieuws@winkel.nl -in:spam after:1700000000"), "Gmail-zoekopdracht: " + q);
        check(FacturenGmail.zoekvraag(new JSONObject().put("woorden", "").put("afzenders", ""), 0).equals("has:attachment filename:pdf"), "zonder woorden: alleen PDF-bijlagen");
        check(FacturenMail.sinds(mm, 0, 100 * 86_400_000L) == 100 * 86_400_000L - 730 * 86_400_000L, "eerste keer: dagen terug");
        check(FacturenMail.sinds(mm, 50 * 86_400_000L, 100 * 86_400_000L) == 48 * 86_400_000L, "daarna: sinds de vorige keer, 2 dagen overlap");
        check(FacturenMail.afzenderNaam("KPN Zakelijk <noreply@kpn.com>").equals("KPN Zakelijk"), "afzender met naam");
        check(FacturenMail.afzenderNaam("\"Vattenfall\" <facturen@vattenfall.nl>").equals("Vattenfall"), "afzender met aanhalingstekens");
        check(FacturenMail.afzenderNaam("facturen@Ziggo.NL").equals("ziggo.nl") && FacturenMail.afzenderNaam("<x@kpn.com>").equals("kpn.com"), "zonder naam: het domein");
        check(FacturenMail.afzenderNaam("A/B: Bedrijf <a@b.nl>").equals("A_B_ Bedrijf"), "afzender veilig als mapnaam: " + FacturenMail.afzenderNaam("A/B: Bedrijf <a@b.nl>"));
        JSONObject bericht = new JSONObject("{\"mimeType\":\"multipart/mixed\",\"parts\":[{\"mimeType\":\"text/plain\",\"body\":{\"size\":10}},"
                + "{\"mimeType\":\"multipart/alternative\",\"parts\":[{\"filename\":\"Factuur 2026-10.pdf\",\"mimeType\":\"application/pdf\",\"body\":{\"attachmentId\":\"ANGjdJ8\",\"size\":81234}}]},"
                + "{\"filename\":\"logo.png\",\"mimeType\":\"image/png\",\"body\":{\"attachmentId\":\"x\"}},{\"filename\":\"SPEC.PDF\",\"mimeType\":\"application/octet-stream\",\"body\":{\"data\":\"JVBERi0\"}}]}");
        java.util.List<JSONObject> bij = FacturenGmail.bijlagen(bericht, null);
        check(bij.size() == 2 && bij.get(0).getString("naam").equals("Factuur 2026-10.pdf") && bij.get(0).getString("id").equals("ANGjdJ8") && bij.get(1).getString("data").equals("JVBERi0"), "PDF-bijlagen ook diep in het bericht, geen plaatjes");
        check(FacturenGmail.kop(new JSONObject("{\"headers\":[{\"name\":\"from\",\"value\":\"KPN <a@kpn.com>\"}]}"), "From").equals("KPN <a@kpn.com>"), "kop lezen");
        check(new Facturen.Pdf(new byte[0], "x.pdf", "s", "KPN: Zakelijk").sub.equals("KPN_ Zakelijk") && new Facturen.Pdf(new byte[0], "x.pdf", "s", " ").sub == null, "eigen submap per bestand (veilig)");
        check(FacturenDrive.scopes("gmail").equals(FacturenGmail.SCOPE) && FacturenDrive.scopes("drive,gmail").equals(FacturenDrive.SCOPE + " " + FacturenGmail.SCOPE) && FacturenDrive.scopes(null).equals(FacturenDrive.SCOPE), "Google-rechten → scopes");

        // ---------- planning: vast moment ----------
        java.util.TimeZone ams = java.util.TimeZone.getTimeZone("Europe/Amsterdam");
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm"); f.setTimeZone(ams);
        long nu = f.parse("2026-11-02 19:30").getTime();
        JSONObject m5 = Facturen.normaal(new JSONObject(basis.toString()).put("schema", "maand").put("dag", 5).put("tijd", "08:00"));
        check(f.format(new java.util.Date(Facturen.gepland(m5, nu, true, ams))).equals("2026-11-05 08:00"), "maand: volgende 5 nov 08:00");
        check(f.format(new java.util.Date(Facturen.gepland(m5, nu, false, ams))).equals("2026-10-05 08:00"), "maand: vorige 5 okt 08:00");
        JSONObject m31 = Facturen.normaal(new JSONObject(m5.toString()).put("dag", 31));
        check(f.format(new java.util.Date(Facturen.gepland(m31, nu, true, ams))).equals("2026-11-30 08:00"), "laatste dag: 30 nov");
        check(f.format(new java.util.Date(Facturen.gepland(m31, f.parse("2027-02-01 09:00").getTime(), true, ams))).equals("2027-02-28 08:00"), "laatste dag in februari");
        JSONObject w = Facturen.normaal(new JSONObject(basis.toString()).put("schema", "week").put("weekdag", 5).put("tijd", "7:15"));
        check(w.getString("tijd").equals("07:15"), "tijd netjes: 07:15");
        check(f.format(new java.util.Date(Facturen.gepland(w, nu, true, ams))).equals("2026-11-06 07:15"), "week: vrijdag 6 nov 07:15");
        JSONObject d = Facturen.normaal(new JSONObject(basis.toString()).put("schema", "dag").put("tijd", "20:00"));
        check(f.format(new java.util.Date(Facturen.gepland(d, nu, true, ams))).equals("2026-11-02 20:00"), "dag: vanavond 20:00");
        check(fout(new JSONObject(basis.toString()).put("tijd", "25:00")).contains("tijd"), "ongeldige tijd geweigerd");
        check(m5.getInt("opnieuw") == 3 && !m5.getBoolean("wifi") && m5.getBoolean("meldNieuw") && m5.getBoolean("meldFout"), "standaard: na 3 uur opnieuw, ook mobiel, beide meldingen");
        long okt5 = f.parse("2026-10-05 08:00").getTime(), nov5 = f.parse("2026-11-05 08:00").getTime(), uur = 3_600_000L;
        check(!Facturen.aanDeBeurt(m5, okt5 + uur, okt5 + uur, nu, ams), "oktober al opgehaald: nu niet");
        check(Facturen.aanDeBeurt(m5, okt5 - uur, 0, nu, ams), "oktober gemist: inhalen");
        check(Facturen.aanDeBeurt(m5, okt5 + uur, okt5 + uur, nov5 + 60_000, ams), "5 nov net na 08:00: aan de beurt");
        check(!Facturen.aanDeBeurt(m5, okt5 + uur, nov5 + 60_000, nov5 + 2 * uur, ams), "net mislukt: nog niet opnieuw");
        check(Facturen.aanDeBeurt(m5, okt5 + uur, nov5 + 60_000, nov5 + 4 * uur, ams), "na 3 uur opnieuw");
        JSONObject m5b = Facturen.normaal(new JSONObject(m5.toString()).put("opnieuw", 12));
        check(!Facturen.aanDeBeurt(m5b, okt5 + uur, nov5 + 60_000, nov5 + 4 * uur, ams), "opnieuw na 12 uur: na 4 uur nog niet");
        check(!Facturen.aanDeBeurt(Facturen.normaal(new JSONObject(basis.toString()).put("schema", "uit")), 0, 0, nu, ams), "uit: nooit vanzelf");

        // ---------- evaluateJavascript-uitkomst ----------
        check(FacturenRun.str("\"{\\\"a\\\":1}\"").equals("{\"a\":1}") && FacturenRun.str("null").isEmpty() && FacturenRun.str(null).isEmpty() && FacturenRun.str("12").equals("12"), "uitkomst van de robot lezen");

        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
    }
}
