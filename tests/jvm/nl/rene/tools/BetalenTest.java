package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** Betalen: een rekening en een QR-code herkennen, een betaling nakijken, en de regels voor klaarzetten bij de bank. */
public class BetalenTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    static final List<String> HOSTS = Arrays.asList("ideal.nl", "betaalverzoek.rabobank.nl");

    /** Woorden zoals de tekstherkenning ze geeft: per regel (dwars door de kolommen heen) de stukken tekst met hun plek. */
    static void regel(List<OcrIndeling.Woord> uit, int nr, int y, Object... kolommen) {
        for (int k = 0; k < kolommen.length; k += 2) {
            int x = (Integer) kolommen[k];
            for (String w : ((String) kolommen[k + 1]).split(" ")) { uit.add(new OcrIndeling.Woord(w, x, y, x + w.length() * 14, y + 36, nr)); x += w.length() * 14 + 12; }
        }
    }

    static String fout(JSONObject in) { try { Betalen.normaal(in, HOSTS); return ""; } catch (Exception e) { return String.valueOf(e.getMessage()); } }

    public static void main(String[] args) throws Exception {
        // ----- tekst van een rekening
        String rekening = "Waterbedrijf Voorbeeld B.V.\nFactuurnummer: F2026-0042\nFactuurdatum: 01-11-2026\nUiterste betaaldatum: 15-11-2026\n"
                + "Uw rekening: NL39 RABO 0300 0652 64\nTotaal excl. btw € 102,02\nBtw 21% € 21,43\nTotaal te betalen € 123,45\n"
                + "Gelieve te betalen op NL91 ABNA 0417 1643 00 t.n.v. Waterbedrijf Voorbeeld B.V.   BIC ABNANL2A\nBetalingskenmerk: 8234 5678 9012 3456\n";
        JSONObject r = BetalenHerken.herken(rekening, Arrays.asList("NL39RABO0300065264"));
        check("NL91ABNA0417164300".equals(r.optString("iban")) && r.optJSONArray("ibans").length() == 1, "IBAN van de ontvanger; het eigen rekeningnummer telt niet");
        check("123,45".equals(r.optString("bedrag")), "bedrag: wat te betalen is, niet het bedrag zonder btw");
        check("8234567890123456".equals(r.optString("kenmerk")) && !r.has("omschrijving"), "betalingskenmerk zonder spaties; dan geen omschrijving");
        check("Waterbedrijf Voorbeeld B.V.".equals(r.optString("naam")), "naam achter t.n.v., zonder wat er nog achter staat");
        check("2026-11-15".equals(r.optString("vervaldatum")), "uiterste betaaldatum");
        JSONObject z = BetalenHerken.herken("Factuur 2026118\nTotaal € 45,10\nIBAN NL91ABNA0417164300", null);
        check("Factuur 2026118".equals(z.optString("omschrijving")) && !z.has("kenmerk") && !z.has("naam"), "zonder kenmerk: het factuurnummer als omschrijving; geen naam verzinnen");
        check(BetalenHerken.herken(null, null).length() == 0 && BetalenHerken.herken("Hallo wereld", null).optJSONArray("ibans").length() == 0, "geen tekst of niets herkenbaars: geen voorstel");
        check(!BetalenHerken.herken("IBAN NL91ABNA0417164301 totaal 1,00", null).has("iban"), "een IBAN met een leesfout (controlegetal klopt niet) wordt niet voorgesteld");
        check(BetalenHerken.kenmerkOk("8234567890123456") && !BetalenHerken.kenmerkOk("1234567890123456") && !BetalenHerken.kenmerkOk("823456789012345"), "betalingskenmerk van 16 cijfers: controlecijfer");
        check(BetalenHerken.rfOk("RF18539007547034") && !BetalenHerken.rfOk("RF19539007547034"), "Europees kenmerk (RF): controlegetal");
        check("RF18539007547034".equals(BetalenHerken.kenmerk("Payment reference: RF18 5390 0754 7034")), "RF-kenmerk met spaties");
        check(BetalenHerken.kenmerk("Klantnummer 1234567890123456").isEmpty(), "zomaar 16 cijfers zonder geldig controlecijfer zijn geen kenmerk");
        check(BetalenHerken.bedragOk("0,01") && !BetalenHerken.bedragOk("0,00") && !BetalenHerken.bedragOk("1000000,00") && !BetalenHerken.bedragOk("12.50"), "bedrag: groter dan nul, kleiner dan een miljoen");
        long t0 = System.currentTimeMillis();
        StringBuilder lang = new StringBuilder();
        for (int i = 0; i < 20000; i++) lang.append("t.n.v. kenmerk 1234 5678 NL91 ");
        BetalenHerken.herken(lang.toString(), null);
        check(System.currentTimeMillis() - t0 < 5000, "een heel lange tekst kost geen eindeloze tijd");
        t0 = System.currentTimeMillis();
        StringBuilder regels = new StringBuilder("tnv");
        for (int i = 0; i < 40000; i++) regels.append('\n');
        for (int i = 0; i < 2000; i++) regels.append("t.n.v.          \n kenmerk    \n");
        BetalenHerken.herken(regels.toString() + regels, null);
        check(System.currentTimeMillis() - t0 < 4000, "veel lege regels en losse woorden kosten geen eindeloze tijd");
        check(BetalenHerken.naam("Rekeninghouder:\nIBAN NL91ABNA0417164300").isEmpty() && "Jansen B.V.".equals(BetalenHerken.naam("t.n.v.: Jansen B.V.")), "de naam staat op dezelfde regel, niet op de volgende");
        check(BetalenHerken.kenmerk("Klantnummer 8234567890123456").isEmpty() && "8234567890123456".equals(BetalenHerken.kenmerk("8234 5678 9012 3456")), "16 cijfers zonder het woord kenmerk: alleen in vier groepjes");

        // ----- een brief met drie kolommen en vakken: de tekstherkenning plakt de kolommen per regel aan elkaar
        List<OcrIndeling.Woord> ws = new java.util.ArrayList<>();
        regel(ws, 1, 100, 100, "Omschrijving overtreding", 900, "Door u te betalen", 1500, "Betaal op tijd. Zo voorkomt u dat de");
        regel(ws, 2, 150, 100, "6 km per uur harder", 900, "\u20ac 65,00", 1500, "boete hoger wordt.");
        regel(ws, 3, 260, 100, "Wanneer", 900, "Betaal v\u00f3\u00f3r");
        regel(ws, 4, 310, 100, "1 november 2026 om 00.19 uur", 900, "7 januari 2027", 1500, "Meer informatie");
        regel(ws, 5, 420, 900, "op rekeningnummer", 1500, "Log voor meer informatie");
        regel(ws, 6, 470, 100, "Waar", 900, "NL91 ABNA 0417 1643 00", 1500, "deze direct bekijken.");
        regel(ws, 7, 580, 900, "naam ontvanger", 1500, "Betaal dan nog niet. Ga uiterlijk");
        regel(ws, 8, 630, 100, "Gemeente Voorbeeld", 900, "Voorbeeld Verkeersboetes", 1500, "24 december 2026 in beroep");
        regel(ws, 9, 740, 900, "met betalingskenmerk", 1500, "Schrijft u liever? Stuur uw brief");
        regel(ws, 10, 790, 100, "Kenteken", 900, "8234 5678 9012 3456", 1500, "Postbus 1, 1234 AB Utrecht.");
        regel(ws, 11, 900, 900, "| |", 1500, "Controleer of de naam ontvanger juist is, of");
        String kolommen = OcrIndeling.tekst(ws);
        check(kolommen.contains("naam ontvanger\nVoorbeeld Verkeersboetes") && kolommen.contains("op rekeningnummer\nNL91 ABNA 0417 1643 00") && kolommen.contains("met betalingskenmerk\n8234 5678 9012 3456")
                && kolommen.contains("Door u te betalen\n\u20ac 65,00") && !kolommen.contains("| |"), "kolommen: de waarde staat weer onder zijn eigen naam; losse streepjes van een kader vallen weg");
        JSONObject brief = BetalenHerken.herken(kolommen, null);
        check("NL91ABNA0417164300".equals(brief.optString("iban")) && "65,00".equals(brief.optString("bedrag")) && "8234567890123456".equals(brief.optString("kenmerk"))
                && "Voorbeeld Verkeersboetes".equals(brief.optString("naam")) && "2027-01-06".equals(brief.optString("vervaldatum")), "brief met vakken: IBAN, bedrag, kenmerk, naam en uiterste dag (de dag v\u00f3\u00f3r 'betaal v\u00f3\u00f3r'): " + brief);
        check(OcrIndeling.tekst(null).isEmpty() && OcrIndeling.tekst(new java.util.ArrayList<OcrIndeling.Woord>()).isEmpty(), "geen woorden: lege tekst");
        List<OcrIndeling.Woord> veel = new java.util.ArrayList<>();
        for (int i = 0; i < 20000; i++) veel.add(new OcrIndeling.Woord("w" + i, (i % 50) * 40, (i / 50) * 40, (i % 50) * 40 + 30, (i / 50) * 40 + 30, i / 50));
        long t1 = System.currentTimeMillis();
        OcrIndeling.tekst(veel);
        check(System.currentTimeMillis() - t1 < 5000, "heel veel woorden: begrensd en binnen de tijd");
        check("65,00".equals(BetalenHerken.teBetalen("Door u te betalen\nr \u20ac 65,00\nDit bedrag is inclusief\n\u20ac 9,00 administratiekosten.\nBedrag na 1e verhoging \u20ac 93,00")), "het bedrag achter 'te betalen', niet de kosten of de verhoging");
        check(BetalenHerken.teBetalen("U hoeft niets te betalen. Bijvoorbeeld\nwanneer uw voertuig 3 maanden").isEmpty(), "'te betalen' zonder bedrag erachter: niets");
        check("2027-01-06".equals(BetalenHerken.betaalVoor("Betaal vo6r\n\u20ac 7 januari 2027")) && "2026-12-31".equals(BetalenHerken.betaalVoor("Betaal v\u00f3\u00f3r 01-01-2027")) && BetalenHerken.betaalVoor("Betaal voor uw gemak via iDEAL").isEmpty(), "betaal v\u00f3\u00f3r een datum, ook als de tekstherkenning de \u00f3 anders leest");
        check("Jansen B.V.".equals(BetalenHerken.naam("Naam van de begunstigde: Jansen B.V.")) && "Jansen B.V.".equals(BetalenHerken.naam("Maak over naar NL91ABNA0417164300 of t.n.v. Jansen B.V."))
                && "Op Maat B.V.".equals(BetalenHerken.naam("t.n.v. Op Maat B.V.")) && BetalenHerken.naam("of de naam ontvanger juist is").isEmpty(), "naam: ook na 'de' of 'of', en een naam die met Op begint; een zin over de naam telt niet");
        check("Jansen B.V.".equals(BetalenHerken.naam("} NL91 ABNA 0417 1643 00\ni naam ontvanger\ni Jansen B.V.\n{met betalingskenmerk")) && "Jansen B.V.".equals(BetalenHerken.naam("{naarn ontvanqer\n|\n| Jansen B.V."))
                && "Jansen B.V.".equals(BetalenHerken.naam("Naam ontvanger\n\u2018 Jansen B.V.")) && BetalenHerken.naam("uw bankapp. Controleer of\nde naam ontvanger juistis, of\nu de brief").isEmpty(), "naam eronder: ook met een los teken van het kader ervoor of ertussen, en een leesfout in het woord");
        String los = "op rekeningnummer\nnaam ontvanger\nmet betalingskenmerk\nNL91 ABNA 0417 1643 00\nJansen B.V.\n8234 5678 9012 3456";
        String I = "NL91ABNA0417164300", kop = "op rekeningnummer\nnaam ontvanger\nmet betalingskenmerk\nNL91 ABNA 0417 1643 00\n";
        check("Jansen B.V.".equals(BetalenHerken.herken(los, null).optString("naam")) && BetalenHerken.naamBijIban("NL91 ABNA 0417 1643 00\nJansen B.V.", I).isEmpty(), "namen van de velden en de waarden los van elkaar: de naam onder het IBAN, alleen als 'naam ontvanger' er vlak boven staat");
        for (String geen : new String[]{"met betalingskenmerk", "Met betalingskenmerk", "Te betalen 65,00", "Vervaldatum 7 januari", "Factuurnummer A", "ABNANL2A", "Uw gegevens", "Pagina een", "Geachte heer Voorbeeld,", "Postbus 1234 Utrecht", "8234 5678 9012 3456"})
            check(BetalenHerken.naamBijIban(kop + geen, I).isEmpty(), "onder het IBAN staat geen naam: " + geen);
        check(BetalenHerken.naamBijIban("naam ontvanger\nMaak over naar NL91 ABNA 0417 1643 00 graag\nJansen B.V.", I).isEmpty() && BetalenHerken.naamBijIban("naam ontvanger\na\nb\nc\nd\ne\nNL91 ABNA 0417 1643 00\nJansen B.V.", I).isEmpty(), "het IBAN in een zin, of het woord ver erboven: niets");
        check("ABC".equals(BetalenHerken.naam("naam ontvanger\nABC\nPostbus 12")) && BetalenHerken.naam("U, begunstigde\nVul het formulier in").isEmpty() && "e Bakker".equals(BetalenHerken.naam("t.n.v. e Bakker")) && BetalenHerken.naam("naam ontvanger\nrekeningnummer\nNL91").isEmpty(), "een korte naam wordt niet overgeslagen; een zin is geen veld");
        check("Jansen B.V.".equals(BetalenHerken.naam("op rekeningnummer\nNL91 ABNA 0417 1643 00\nnaam ontvange r\nJansen B.V.\nmet betalingskenmerk")) && "Jansen B.V.".equals(BetalenHerken.naam("naam ont vanger\nJansen B.V."))
                && BetalenHerken.naam("Controleer of\nde naam ontvange r juistis, of").isEmpty() && BetalenHerken.naam("Controleer of de n aam ontvanger Jansen goed is").isEmpty() && BetalenHerken.naam("Is de naa m ontvanger Bakker correct?").isEmpty(), "naam: ook als de tekstherkenning een spatie midden in 'naam ontvanger' leest; een zin erover telt nog steeds niet");
        check("65,00".equals(BetalenHerken.teBetalen("Totaal te betalen \u20ac 65,00\nBetaalt u te laat, dan is het te betalen bedrag \u20ac 97,50"))
                && "65,00".equals(BetalenHerken.teBetalen("Te betalen \u20ac 97,50 bij te laat\nTotaal te betalen \u20ac 65,00")), "te betalen: het totaal gaat voor, anders het eerste");
        check(BetalenHerken.naam("Controleer of\nde naam ontvanger juist is, of").isEmpty() && "Jansen B.V.".equals(BetalenHerken.naam("naam ontvanger\n| Jansen B.V.")) && BetalenHerken.naam("naam ontvanger\nIBAN NL91ABNA0417164300").isEmpty(), "de naam op de regel onder 'naam ontvanger'; geen zin over de naam en geen rekeningnummer");

        // ----- QR-codes
        JSONObject epc = BetalenHerken.qr("BCD\n002\n1\nSCT\nABNANL2A\nWaterbedrijf Voorbeeld B.V.\nNL91ABNA0417164300\nEUR123.45\n\n8234567890123456\n", HOSTS);
        check(epc != null && "epc".equals(epc.optString("soort")) && "NL91ABNA0417164300".equals(epc.optString("iban")) && "123,45".equals(epc.optString("bedrag"))
                && "Waterbedrijf Voorbeeld B.V.".equals(epc.optString("naam")) && "8234567890123456".equals(epc.optString("kenmerk")), "SEPA-QR: naam, IBAN, bedrag en kenmerk");
        JSONObject epc2 = BetalenHerken.qr("BCD\n001\n1\nSCT\n\nJan\nNL91ABNA0417164300\nEUR5\n\n\nFactuur 12\n", HOSTS);
        check(epc2 != null && "5,00".equals(epc2.optString("bedrag")) && "Factuur 12".equals(epc2.optString("omschrijving")) && !epc2.has("kenmerk"), "SEPA-QR met omschrijving en een rond bedrag");
        check(BetalenHerken.qr("BCD\n002\n1\nSCT\n\nJan\nNL91ABNA0417164301\nEUR5\n", HOSTS) == null, "SEPA-QR met een IBAN dat niet klopt telt niet");
        JSONObject epc3 = BetalenHerken.qr("BCD\n002\n1\nSCT\n\nJan\nNL91ABNA0417164300\nEUR5.5\n\nRF00FOUT\n", HOSTS);
        check(epc3 != null && "NL91ABNA0417164300".equals(epc3.optString("iban")) && "5,50".equals(epc3.optString("bedrag")) && !epc3.has("kenmerk"), "SEPA-QR met een kenmerk dat niet klopt: alleen het kenmerk vervalt");
        JSONObject link = BetalenHerken.qr(" https://www.ideal.nl/betalen?trxid=123 ", HOSTS);
        check(link != null && "link".equals(link.optString("soort")) && "www.ideal.nl".equals(link.optString("host")) && link.optBoolean("bekend") && "https://www.ideal.nl/betalen?trxid=123".equals(link.optString("link")), "betaallink van een bekende betaaldienst");
        JSONObject vreemd = BetalenHerken.qr("https://ideal.nl.voorbeeld.example/betalen", HOSTS);
        check(vreemd != null && !vreemd.optBoolean("bekend") && "ideal.nl.voorbeeld.example".equals(vreemd.optString("host")), "een domein dat alleen op een betaaldienst lijkt is niet bekend");
        for (String slecht : new String[]{"http://www.ideal.nl/x", "javascript:alert(1)", "https://user@ideal.nl/x", "https://ideal.nl:8443/x", "https://192.168.1.1/x", "https://ideal.nl/a b", "intent://x#Intent;end",
                "https://ideal.nl/x\"onload", "https://ideal.nl/a\nb", "https://ideal.nl/a\u2028b", "https://ide\u0430l.nl/x", "https://ideal.nl/caf\u00e9", "WIFI:S:thuis;;", "", "https://", "https://localhost/x", "https://ideal.nl\\@voorbeeld.example/"})
            check(BetalenHerken.qr(slecht, HOSTS) == null, "geen betaallink: " + slecht);
        StringBuilder groot = new StringBuilder("https://ideal.nl/");
        for (int i = 0; i < 700; i++) groot.append('a');
        check(BetalenHerken.link(groot.toString()) == null, "een veel te lang adres telt niet");

        // ----- een betaling nakijken
        JSONObject b = Betalen.normaal(new JSONObject().put("naam", "  Waterbedrijf\n Voorbeeld  ").put("iban", "nl91 abna 0417 1643 00").put("bedrag", "1234,5").put("kenmerk", "8234 5678 9012 3456")
                .put("omschrijving", "x").put("vervaldatum", "2026-11-15").put("link", "https://www.ideal.nl/a").put("status", "raar").put("bron", "elders").put("extra", "weg").put("sha", "../x"), HOSTS);
        check(b.optString("id").matches("b[0-9a-f]{12}") && "Waterbedrijf Voorbeeld".equals(b.optString("naam")) && "NL91ABNA0417164300".equals(b.optString("iban")) && "1234,50".equals(b.optString("bedrag")), "naam op één regel, IBAN zonder spaties, bedrag als 1234,50");
        check("8234567890123456".equals(b.optString("kenmerk")) && "open".equals(b.optString("status")) && "hand".equals(b.optString("bron")) && !b.has("extra") && b.optString("sha").isEmpty(), "onbekende waarden en velden vervallen");
        check("www.ideal.nl".equals(b.optString("linkHost")) && b.optBoolean("linkBekend") && Betalen.compleet(b), "betaallink met domein; compleet met naam, IBAN en bedrag");
        check(fout(new JSONObject().put("iban", "NL91ABNA0417164301")).contains("IBAN") && fout(new JSONObject().put("bedrag", "veel")).contains("bedrag")
                && fout(new JSONObject().put("bedrag", "0")).contains("bedrag") && fout(new JSONObject().put("bedrag", "1.234")).contains("bedrag") && fout(new JSONObject().put("bedrag", "0.001")).contains("bedrag")
                && fout(new JSONObject().put("bedrag", "12,345")).contains("bedrag") && fout(new JSONObject().put("bedrag", "1.234,50")).contains("bedrag") && fout(new JSONObject().put("kenmerk", "a b;c")).contains("kenmerk")
                && fout(new JSONObject().put("kenmerk", "RF19539007547034")).contains("kenmerk") && fout(new JSONObject().put("link", "http://ideal.nl/")).contains("https"), "een fout IBAN, bedrag, kenmerk of onveilige link wordt geweigerd");
        JSONObject leeg = Betalen.normaal(new JSONObject(), HOSTS);
        check(!Betalen.compleet(leeg) && leeg.optString("link").isEmpty() && fout(new JSONObject()).isEmpty(), "een onaffe betaling mag bewaard worden, maar is niet compleet");
        check(!Betalen.compleet(Betalen.normaal(new JSONObject().put("iban", "NL91ABNA0417164300").put("bedrag", "1"), HOSTS)), "zonder naam niet compleet");
        check(!Betalen.normaal(new JSONObject().put("bevestigd", true).put("status", "betaald"), HOSTS).optBoolean("bevestigd"), "de interface kan een betaling niet zelf als bevestigd aanmerken");
        JSONArray vanzelf = new JSONArray().put(new JSONObject().put("id", "b000000000001").put("iban", "NL91ABNA0417164300").put("status", "betaald"));
        check(Betalen.nieuweOntvanger(vanzelf, new JSONObject().put("id", "b000000000002").put("iban", "NL91ABNA0417164300")), "vanzelf op Betaald gezet maakt een IBAN nog niet bekend");
        JSONArray alle = new JSONArray().put(new JSONObject().put("id", "b000000000001").put("iban", "NL91ABNA0417164300").put("status", "betaald").put("bevestigd", true));
        check(!Betalen.nieuweOntvanger(alle, new JSONObject().put("id", "b000000000002").put("iban", "NL91ABNA0417164300"))
                && Betalen.nieuweOntvanger(alle, new JSONObject().put("id", "b000000000002").put("iban", "DE89370400440532013000"))
                && Betalen.nieuweOntvanger(alle, new JSONObject().put("id", "b000000000001").put("iban", "NL91ABNA0417164300")), "nieuwe ontvanger: nog niet eerder naar dit IBAN betaald");
        JSONObject inst = Betalen.instNormaal(new JSONObject().put("bank", "ING!").put("naKlaar", "x").put("eigen", new JSONArray().put("nl39 rabo 0300 0652 64").put("")));
        check("rabobank".equals(inst.optString("bank")) && "vragen".equals(inst.optString("naKlaar")) && "NL39RABO0300065264".equals(inst.optJSONArray("eigen").optString(0)) && inst.optJSONArray("eigen").length() == 1, "instellingen: beginwaarden en nette rekeningnummers");
        boolean geweigerd = false;
        try { Betalen.instNormaal(new JSONObject().put("eigen", new JSONArray().put("NL00TEST0000000000"))); } catch (Betalen.Fout e) { geweigerd = true; }
        check(geweigerd, "een eigen rekeningnummer dat niet klopt wordt geweigerd");

        // ----- klaarzetten bij de bank: de instellingen in web/bank-banken.json
        String json = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("web/bank-banken.json")), StandardCharsets.UTF_8);

        // ----- de rekening zelf: instelling en koppeling, en wat de viewer kan tonen
        check(Betalen.instNormaal(new JSONObject()).optBoolean("stuk") && !Betalen.instNormaal(new JSONObject().put("stuk", false)).optBoolean("stuk"), "rekening bewaren in de kluis: standaard aan, uit te zetten");
        check("abcdef0123".equals(Betalen.normaal(new JSONObject().put("doc", "abcdef0123"), HOSTS).optString("doc")) && Betalen.normaal(new JSONObject().put("doc", "../kluis"), HOSTS).optString("doc").isEmpty(), "betaling met het id van de rekening in de kluis; alleen een geldig id");
        byte[] jpg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 0, 0, 0, 0, 0, 0, 0}, png = {(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 0, 0, 0, 0, 0},
                webp = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'}, heic = {0, 0, 0, 0, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'}, pdf = {'%', 'P', 'D', 'F', '-', '1', '.', '7', 0, 0, 0, 0};
        check("jpg".equals(PdfDoc.soort(jpg, 12)) && "png".equals(PdfDoc.soort(png, 12)) && "webp".equals(PdfDoc.soort(webp, 12)) && "heic".equals(PdfDoc.soort(heic, 12))
                && PdfDoc.soort(pdf, 12) == null && PdfDoc.soort(jpg, 3) == null, "de viewer herkent een afbeelding aan de inhoud, niet aan de naam");

        // ----- klopt de ontvanger? (BetalenControle): de officiële lijst, de bank, eerdere betalingen
        JSONObject lijst = new JSONObject(json);
        JSONArray ontv = lijst.getJSONArray("ontvangers");
        check(ontv.length() >= 2, "officiële lijst met organisaties");
        for (int i = 0; i < ontv.length(); i++) {
            JSONObject o = ontv.getJSONObject(i);
            check(o.optString("bron").startsWith("https://") && o.optString("gecontroleerd").matches("20\\d{2}-\\d{2}-\\d{2}") && o.getJSONArray("iban").length() > 0, "organisatie met bron en datum: " + o.optString("naam"));
            for (String veld : new String[]{"iban", "oud"}) {
                JSONArray ib = o.optJSONArray(veld);
                if (ib != null) for (int k = 0; k < ib.length(); k++) check(FacturenHerken.ibanOk(ib.getString(k)), "officieel IBAN heeft een kloppend controlegetal: " + o.optString("naam") + " " + veld + " " + k);
            }
        }
        JSONObject cj = BetalenControle.controle(new JSONObject().put("id", "b000000000001").put("iban", "NL56INGB0705005100").put("naam", "CHB Verkeersboetes"), new JSONArray(), lijst, "");
        check("CJIB".equals(cj.optString("bekend")) && "CJIB".equals(cj.optString("naam")) && "ING".equals(cj.optString("bank")) && !cj.optBoolean("buitenland") && !cj.has("verdacht"),
                "officieel rekeningnummer: de naam van de lijst (ook als de tekstherkenning zich vergiste), bank ING");
        JSONObject vals = BetalenControle.controle(new JSONObject().put("iban", "NL91ABNA0417164300").put("naam", "C.J.I.B. Verkeersboetes"), new JSONArray(), lijst, "");
        check("CJIB".equals(vals.optString("verdacht")) && !vals.has("bekend") && !vals.has("naam") && "ABN AMRO".equals(vals.optString("bank")), "de naam van een organisatie uit de lijst met een ander rekeningnummer: waarschuwing");
        check("Belastingdienst".equals(BetalenControle.controle(new JSONObject().put("iban", "NL91ABNA0417164300"), new JSONArray(), lijst, "Belastingdienst\nUw aanslag").optString("verdacht")), "de organisatie bovenaan de gelezen tekst (afzender) telt ook");
        check(!BetalenControle.controle(new JSONObject().put("iban", "NL91ABNA0417164300").put("naam", "Gemeente Voorbeeld"), new JSONArray(), lijst,
                "Gemeente Voorbeeld\nParkeren\nFactuur\nBedrag\nDatum\nKenmerk\nBetaalt u niet, dan dragen wij de vordering over aan het CJIB.").has("verdacht"), "een organisatie alleen in de lopende tekst: geen waarschuwing");
        JSONObject oudNr = BetalenControle.controle(new JSONObject().put("iban", "NL86INGB0002445588"), new JSONArray(), lijst, "");
        check("Belastingdienst".equals(oudNr.optString("bekend")) && oudNr.optBoolean("oud") && "Belastingdienst".equals(BetalenControle.controle(new JSONObject().put("iban", "NL04RABO0200112244"), new JSONArray(), lijst, "").optString("bekend"))
                && !BetalenControle.controle(new JSONObject().put("iban", "NL04RABO0200112244"), new JSONArray(), lijst, "").optBoolean("oud"), "oud nummer van de Belastingdienst: bekend, maar als oud nummer");
        check(!BetalenControle.controle(new JSONObject().put("iban", "NL91ABNA0417164300").put("naam", "Jansen B.V."), new JSONArray(), lijst, "Verkeersboetes en belasting").has("verdacht"), "geen organisatie genoemd: geen waarschuwing");
        JSONObject buiten = BetalenControle.controle(new JSONObject().put("iban", "DE89370400440532013000"), new JSONArray(), lijst, "");
        check(buiten.optBoolean("buitenland") && "DE".equals(buiten.optString("land")) && buiten.optString("bank").isEmpty(), "buitenlandse rekening");
        check(BetalenControle.controle(new JSONObject().put("iban", "NL00GEEN0000000000"), new JSONArray(), lijst, "").length() == 0, "zonder kloppend IBAN: geen controle");
        JSONArray eerder = new JSONArray()
                .put(new JSONObject().put("id", "b1").put("iban", "NL91ABNA0417164300").put("naam", "Jansen B.V.").put("status", "betaald").put("bevestigd", true).put("gewijzigd", 10))
                .put(new JSONObject().put("id", "b2").put("iban", "NL91ABNA0417164300").put("naam", "Jansen BV").put("status", "betaald").put("bevestigd", true).put("gewijzigd", 20))
                .put(new JSONObject().put("id", "b3").put("iban", "NL91ABNA0417164300").put("naam", "Iets anders").put("status", "klaargezet").put("gewijzigd", 30));
        JSONObject ee = BetalenControle.controle(new JSONObject().put("id", "b4").put("iban", "NL91ABNA0417164300").put("naam", "Jansen 8.V."), eerder, lijst, "");
        check(ee.optInt("eerder") == 2 && "Jansen BV".equals(ee.optString("eerderNaam")) && "Jansen BV".equals(ee.optString("naam")), "eerder zelf op Betaald gezet: het aantal en de naam van de laatste keer");
        check(BetalenControle.genoemd("Centraal Justitieel Incassobureau", lijst) != null && BetalenControle.genoemd("Rijksbelastingdienst", lijst) == null, "een organisatie wordt alleen als heel woord gevonden");

        JSONArray banken = BankLink.banken(json);
        JSONObject rabo = BankLink.bank(banken, "rabobank");
        check(BetaalLink.kan(rabo) && !BetaalLink.kan(BankLink.bank(banken, "ing")) && !BetaalLink.kan(null), "Rabobank kan klaarzetten; een bank zonder instellingen niet");
        JSONObject stuk = new JSONObject(rabo.toString());
        stuk.getJSONObject("betalen").put("iban", "(");
        check(!BetaalLink.kan(stuk), "een ongeldig zoekpatroon: dan niet klaarzetten");
        stuk = new JSONObject(rabo.toString()); stuk.put("startUrl", "https://voorbeeld.example/");
        check(!BetaalLink.kan(stuk), "een startadres buiten de domeinen van de bank: dan niet klaarzetten");
        JSONObject cfg = new JSONObject(BetaalLink.robotCfg(rabo));
        check(!cfg.optString("naar").isEmpty() && !cfg.optString("inlog").isEmpty() && !cfg.has("startUrl") && !cfg.has("domeinen"), "de robot krijgt alleen zoekpatronen");
        JSONObject rb = new JSONObject(BetaalLink.robotBetaling(b));
        check(rb.length() == 5 && "1234,50".equals(rb.optString("bedrag")) && !rb.has("link") && !rb.has("id"), "de robot krijgt alleen naam, IBAN, bedrag, kenmerk en omschrijving");
        boolean onaf = false;
        try { BetaalLink.robotBetaling(leeg); } catch (Betalen.Fout e) { onaf = true; }
        check(onaf, "een onaffe betaling gaat niet naar de bank");
        List<String> hosts = BetaalLink.hosts(json);
        check(hosts.contains("ideal.nl") && hosts.contains("betaalverzoek.rabobank.nl") && hosts.size() >= 5, "bekende betaaldiensten uit het bestand");
        for (String h : hosts) check(h.matches("[a-z0-9-]+(\\.[a-z0-9-]+)+"), "betaaldienst is een gewone domeinnaam: " + h);

        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
        System.out.println("alles goed");
    }
}
