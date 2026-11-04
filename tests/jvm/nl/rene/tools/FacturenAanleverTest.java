package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

/** Facturen: dossier (gegevens herkennen, CSV) en aanleveren (instellingen, wat open staat, teksten, planning, MIME, SMTP). */
public class FacturenAanleverTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }
    static String fout(JSONObject o) { try { FacturenAanlever.normaal(o); return ""; } catch (Exception e) { return e.getMessage(); } }
    static final TimeZone AMS = TimeZone.getTimeZone("Europe/Amsterdam");

    static JSONObject stuk(String sha, String lev, long opgehaald, String datum, String bedrag) throws Exception {
        return new JSONObject().put("sha", sha).put("naam", sha + ".pdf").put("leverancier", lev).put("leverancierNaam", lev.toUpperCase())
                .put("opgehaald", opgehaald).put("grootte", 1000).put("verzonden", new JSONObject())
                .put("meta", new JSONObject().put("datum", datum).put("bedrag", bedrag).put("nummer", "F-" + sha));
    }

    public static void main(String[] a) throws Exception {
        // ---------- factuurgegevens herkennen ----------
        JSONObject m = FacturenDossier.herken("KPN B.V.\nFactuurnummer: 8001234567\nFactuurdatum 5 oktober 2026\nSubtotaal € 39,97\nBTW 21% € 8,40\nTotaal te betalen € 48,37\n");
        check("8001234567".equals(m.optString("nummer")), "factuurnummer herkend");
        check("2026-10-05".equals(m.optString("datum")), "datum met maandnaam");
        check("48,37".equals(m.optString("bedrag")), "laatste totaal is het bedrag");
        check("8,40".equals(m.optString("btw")), "btw herkend");
        JSONObject n = FacturenDossier.herken("Invoice number: INV-2026-0042\nInvoice date: 03/11/2026\nAmount due EUR 1.234,50");
        check("INV-2026-0042".equals(n.optString("nummer")) && "2026-11-03".equals(n.optString("datum")) && "1234,50".equals(n.optString("bedrag")), "Engelse factuur");
        check("2026-09-01".equals(FacturenDossier.herken("KPN Zakelijk Factuur 2026-09.pdf").optString("datum")), "datum uit de bestandsnaam");
        JSONObject v = FacturenDossier.herken("Uiterste betaaldatum: 19-10-2026\nFactuurdatum: 5-10-2026\nTotaal € 10,00");
        check("2026-10-19".equals(v.optString("vervaldatum")) && "2026-10-05".equals(v.optString("datum")), "uiterste betaaldatum apart van de factuurdatum");
        JSONObject dd = FacturenDossier.herken("Due date: 19-10-2026\nInvoice date: 05-10-2026");
        check("2026-10-05".equals(dd.optString("datum")) && "2026-10-19".equals(dd.optString("vervaldatum")), "Engels: due date is niet de factuurdatum");
        check("2026-03-02".equals(FacturenDossier.herken("Notadatum 2 maart 2026").optString("datum")) && "2026-03-02".equals(FacturenDossier.herken("Datum: 02-03-2026").optString("datum")), "notadatum en losse datum");
        // ---------- herkennen in echte opmaak: tabellen, waarde op de volgende regel, eigen bestandsnaam ----------
        JSONObject kpn = FacturenDossier.herken("KPN B.V.\nKlantnummer Factuurnummer Factuurdatum Vervaldatum\n1234567 70012345678 05-10-2026 19-10-2026\n"
                + "Omschrijving Bedrag\nAbonnement 33,23\nBtw 21% 6,98\nTotaal te betalen\n€ 40,21\n\nKPN Zakelijk Factuur f59e6964.pdf");
        check("70012345678".equals(kpn.optString("nummer")), "KPN-tabel: factuurnummer uit de kolom (niet de bestandsnaam) " + kpn.optString("nummer"));
        check("2026-10-05".equals(kpn.optString("datum")) && "2026-10-19".equals(kpn.optString("vervaldatum")), "KPN-tabel: factuurdatum en vervaldatum per kolom");
        check("40,21".equals(kpn.optString("bedrag")) && "6,98".equals(kpn.optString("btw")), "totaal op de regel eronder");
        check(!FacturenDossier.herken("KPN Zakelijk Factuur f59e6964.pdf").has("nummer"), "nooit het controlegetal uit onze bestandsnaam als factuurnummer");
        check(!FacturenDossier.geldigNummer("f59e6964") && !FacturenDossier.geldigNummer("2f851ff5.pdf") && !FacturenDossier.geldigNummer("05-10-2026") && FacturenDossier.geldigNummer("320229454732") && FacturenDossier.geldigNummer("INV-2026-0042"), "geldig factuurnummer");
        JSONObject zg = FacturenDossier.herken("Ziggo Zakelijk\nFactuurnummer:\n320229454732\nFactuurdatum: 10 okt. 2026\nTe betalen voor 15 oktober 2026\nTotaal bedrag € 86,99");
        check("320229454732".equals(zg.optString("nummer")) && "2026-10-10".equals(zg.optString("datum")) && "2026-10-15".equals(zg.optString("vervaldatum")) && "86,99".equals(zg.optString("bedrag")), "waarde op de volgende regel, maand met punt " + zg);
        check(!"15,10".equals(FacturenDossier.herken("Te betalen 15.10.2026").optString("bedrag")), "een datum met punten is geen bedrag");
        JSONObject t1 = FacturenDossier.herken("Factuurdatum Vervaldatum\n05-10-2026 19-10-2026");
        check("2026-10-05".equals(t1.optString("datum")) && "2026-10-19".equals(t1.optString("vervaldatum")), "tabel: factuurdatum en vervaldatum in hun eigen kolom " + t1);
        check("70012345678".equals(FacturenDossier.herken("Klantnummer Factuurnummer\n1234567 70012345678").optString("nummer")), "tabel: niet het klantnummer");
        JSONObject t2 = FacturenDossier.herken("Datum Factuurnummer\n05-10-2026 F12345");
        check("F12345".equals(t2.optString("nummer")) && "2026-10-05".equals(t2.optString("datum")), "tabel: Datum en Factuurnummer apart " + t2);
        check("70012345678".equals(FacturenDossier.herken("Rekeningnummer 12.34.56.789\nFactuurnummer 70012345678").optString("nummer")), "rekeningnummer is geen factuurnummer");
        check(!FacturenDossier.herken("Factuur 2026-10.pdf").has("nummer") && !FacturenDossier.herken(FacturenDossier.naamZonderSha("KPN Zakelijk Factuur 12345678.pdf", "12345678abcdef")).has("nummer"), "geen jaar-maand of controlegetal als nummer");
        JSONObject t3 = FacturenDossier.herken("Totaal excl. btw € 33,23\nBtw 21% € 6,98\nTotaal incl. btw € 40,21\nTotaal btw € 6,98");
        check("40,21".equals(t3.optString("bedrag")) && "6,98".equals(t3.optString("btw")), "bedrag incl. btw en het btw-bedrag " + t3);
        check("40,21".equals(FacturenDossier.herken("Totaal te betalen € 40,21\nTotaal btw € 6,98").optString("bedrag")), "te betalen gaat voor totaal btw");
        check("Factuur 70012345.pdf".equals(FacturenDossier.naamZonderSha("Factuur 70012345.pdf", "abcdef0123")) && "70012345".equals(FacturenDossier.herken("Factuur 70012345.pdf").optString("nummer")), "een echt nummer in de bestandsnaam blijft");
        JSONObject t4 = FacturenDossier.herken("Totaal excl. btw Btw Totaal\n€ 33,23 € 6,98 € 40,21");
        check("40,21".equals(t4.optString("bedrag")), "tabel: totaal excl. btw is niet het bedrag " + t4);
        JSONObject oudFout = new JSONObject().put("nummer", "f59e6964.pdf").put("datum", "2026-10-05").put("bron", "herkend").put("versie", 2).put("categorie", "telefoon");
        JSONObject vernieuwd = FacturenDossier.aanvullen(oudFout, new JSONObject().put("bedrag", "40,21"));
        check(!vernieuwd.has("nummer") && "2026-10-05".equals(vernieuwd.optString("datum")) && "40,21".equals(vernieuwd.optString("bedrag")) && "telefoon".equals(vernieuwd.optString("categorie")), "opnieuw lezen: fout nummer weg, rest blijft");
        check(FacturenDossier.opnieuwLezen(new JSONObject().put("meta", oudFout)), "oudere herkenning wordt opnieuw gelezen");
        JSONObject leeg = new JSONObject().put("meta", new JSONObject().put("bron", ""));
        check(FacturenDossier.opnieuwLezen(leeg) && !FacturenDossier.opnieuwLezen(new JSONObject().put("meta", new JSONObject().put("bron", "zelf"))), "ontbrekende gegevens opnieuw lezen, met de hand ingevuld niet");
        JSONObject aan = FacturenDossier.aanvullen(new JSONObject().put("nummer", "F1").put("bron", "herkend"), new JSONObject().put("nummer", "F2").put("bedrag", "10,00"));
        check("F2".equals(aan.optString("nummer")) && "10,00".equals(aan.optString("bedrag")) && aan.optInt("versie") == FacturenDossier.HERKEN_VERSIE && !FacturenDossier.opnieuwLezen(new JSONObject().put("meta", aan)), "nieuwe herkenning gaat voor, daarna niet opnieuw");
        check("2026-11-30".equals(FacturenDossier.herken("Te betalen voor 30 november 2026").optString("vervaldatum")), "te betalen voor …");
        check(FacturenDossier.herken("geen gegevens hier").length() == 0, "niets herkend: leeg");
        check("1234,56".equals(FacturenDossier.bedrag("1.234,56")) && "1234,56".equals(FacturenDossier.bedrag("1,234.56")) && "12,50".equals(FacturenDossier.bedrag("12,5")) && FacturenDossier.bedrag("veel") == null, "bedragen");
        check("2026-03-07".equals(FacturenDossier.datum("7 mrt 2026")) && "2026-05-01".equals(FacturenDossier.datum("1-5-26")) && FacturenDossier.datum("31-13-2026") == null, "datums");

        // ---------- CSV ----------
        check("'=SOM(A1)".equals(FacturenDossier.cel("=SOM(A1)")) && "-12,50".equals(FacturenDossier.cel("-12,50")) && "\"a;b\"".equals(FacturenDossier.cel("a;b")), "CSV-cellen: geen formules, puntkomma tussen aanhalingstekens");
        JSONObject items = new JSONObject();
        JSONObject s1 = stuk("aa", "kpn", 1000, "2026-10-05", "48,37");
        s1.getJSONObject("verzonden").put("jansen", new JSONObject().put("ok", true).put("t", 2000).put("naam", "Jansen"));
        s1.put("plekken", new JSONObject().put("app", new JSONObject().put("pad", "/x")).put("gd", new JSONObject().put("id", "1")));
        items.put("aa", s1).put("bb", stuk("bb", "ziggo", 500, "2026-09-01", "30,00"));
        String csv = FacturenDossier.csv(items);
        String[] regels = csv.split("\r\n");
        check(csv.startsWith("﻿Opgehaald;") && regels.length == 3, "CSV met kop en twee regels");
        check(regels[1].contains("ZIGGO") && regels[2].contains("KPN"), "CSV oudste eerst");
        check(regels[2].contains("in de app, Google Drive") && regels[2].contains("Jansen ("), "CSV met plekken en aangeleverd");

        // ---------- ontvanger: instellingen ----------
        JSONObject o = new JSONObject().put("naam", "Boekhouder Jansen").put("aan", "Facturen@Jansen.nl; boekhouding@jansen.nl").put("via", "smtp").put("schema", "maand").put("dag", 3);
        JSONObject on = FacturenAanlever.normaal(o);
        check("facturen@jansen.nl, boekhouding@jansen.nl".equals(on.optString("aan")), "adressen gecontroleerd en genormaliseerd");
        check("email".equals(on.optString("kanaal")) && "samen".equals(on.optString("bundel")) && on.optInt("maxMb") == 15 && on.optString("tekst").contains("{lijst}"), "standaardwaarden");
        check(fout(new JSONObject(o.toString()).put("naam", "")).contains("naam"), "naam verplicht");
        check(fout(new JSONObject(o.toString()).put("aan", "")).contains("mailadres"), "adres verplicht");
        check(fout(new JSONObject(o.toString()).put("aan", "jansen@nl\r\nBcc: x@y.nl")).contains("klopt niet"), "geen kopregels via het adres");
        check(fout(new JSONObject(o.toString()).put("onderwerp", "a\nBcc: x@y.nl")).contains("onderwerp"), "geen regeleinde in het onderwerp");
        check(fout(new JSONObject(o.toString()).put("kanaal", "fax")).contains("Onbekend kanaal"), "onbekend kanaal");
        check(fout(new JSONObject(o.toString()).put("via", "post")).contains("Kies waarmee"), "onbekende manier van versturen");
        check(fout(new JSONObject(o.toString()).put("maxMb", 50)).contains("20 MB"), "mail hooguit 20 MB");
        check(fout(new JSONObject(o.toString()).put("schema", "soms")).contains("wanneer"), "onbekend schema");
        check(fout(new JSONObject(o.toString()).put("tijd", "25:00")).contains("UU:MM"), "ongeldige tijd");
        check(fout(new JSONObject(o.toString()).put("cc", "a@b.nl,c@d.nl,e@f.nl,g@h.nl,i@j.nl,k@l.nl,m@n.nl,o@p.nl,q@r.nl,s@t.nl,u@v.nl")).contains("Hooguit 10"), "hooguit 10 adressen");
        check(FacturenAanlever.KANALEN.containsKey("email") && FacturenEmail.VERZENDERS.keySet().containsAll(Arrays.asList("eigencloud", "smtp", "gmail")), "kanaal e-mail met Eigen Cloud, SMTP en Gmail");

        // ---------- wat staat er open ----------
        JSONObject ontv = new JSONObject(on.toString()).put("id", "jansen").put("vanaf", 900);
        check(FacturenAanlever.open(ontv, stuk("x", "kpn", 1000, "", "")), "nieuw stuk staat open");
        check(!FacturenAanlever.open(ontv, stuk("x", "kpn", 800, "", "")), "van vóór de ontvanger: niet");
        check(!FacturenAanlever.open(ontv, s1), "al aangeleverd: niet");
        JSONObject mis = stuk("y", "kpn", 1000, "", ""); mis.getJSONObject("verzonden").put("jansen", new JSONObject().put("ok", false));
        check(FacturenAanlever.open(ontv, mis), "mislukt: weer open");
        mis.getJSONObject("verzonden").getJSONObject("jansen").put("pogingen", 5);
        check(!FacturenAanlever.open(ontv, mis), "na 5 mislukte pogingen niet meer vanzelf");
        check(fout(new JSONObject(o.toString()).put("aan", "jänsen@voorbeeld.nl")).contains("klopt niet"), "alleen ASCII-adressen");
        check(new FacturenEmail().maxZending(new JSONObject().put("maxMb", 20)) < 20 * FacturenEmail.MB * 3 / 4, "grootte rekent met base64");
        JSONObject opn = stuk("z", "kpn", 100, "", ""); opn.getJSONObject("verzonden").put("jansen", new JSONObject().put("ok", false).put("opnieuw", true));
        check(FacturenAanlever.open(ontv, opn), "opnieuw aanleveren: open, ook van vóór de ontvanger");
        JSONObject alleenKpn = new JSONObject(ontv.toString()).put("leveranciers", new JSONArray().put("kpn"));
        check(FacturenAanlever.open(alleenKpn, stuk("x", "kpn", 1000, "", "")) && !FacturenAanlever.open(alleenKpn, stuk("x", "ziggo", 1000, "", "")), "filter op leverancier");

        // ---------- zendingen ----------
        List<JSONObject> l = new ArrayList<>();
        for (int i = 0; i < 5; i++) l.add(stuk("s" + i, "kpn", i, "", "").put("grootte", 4_000_000));
        check(FacturenAanlever.verdeel(l, false, 15_000_000).size() == 5, "los: één mail per factuur");
        List<List<JSONObject>> d = FacturenAanlever.verdeel(l, true, 15_000_000);
        check(d.size() == 2 && d.get(0).size() == 3 && d.get(1).size() == 2, "samen: zoveel als past (3 + 2)");
        check(FacturenAanlever.verdeel(new ArrayList<>(Arrays.asList(stuk("g", "kpn", 0, "", "").put("grootte", 30_000_000))), true, 15_000_000).size() == 1, "te grote factuur: eigen zending (wordt gemeld)");

        // ---------- teksten ----------
        List<JSONObject> twee = Arrays.asList(stuk("aa", "kpn", 0, "2026-09-05", "45,10"), stuk("bb", "ziggo", 0, "2026-10-05", "30,00"));
        check("september – oktober 2026".equals(FacturenAanlever.periode(twee, AMS)), "periode over twee maanden");
        check("oktober 2026".equals(FacturenAanlever.periode(twee.subList(1, 2), AMS)), "periode één maand");
        check("december 2025 – januari 2026".equals(FacturenAanlever.periode(Arrays.asList(stuk("a", "k", 0, "2025-12-30", ""), stuk("b", "k", 0, "2026-01-02", "")), AMS)), "periode over de jaargrens");
        long nu = 1_793_000_000_000L; // nov 2026
        String t = FacturenAanlever.vul("Hierbij {aantal} van {leveranciers} ({periode}) voor {naam}:\n{lijst}", ontv, twee, nu, AMS);
        check(t.startsWith("Hierbij 2 facturen van KPN, ZIGGO (september – oktober 2026) voor Boekhouder Jansen:"), "sjabloon ingevuld");
        check(t.contains("- KPN: aa.pdf, nr. F-aa, 2026-09-05, € 45,10"), "een regel per factuur");
        check(FacturenAanlever.vul("{aantal}", ontv, twee.subList(0, 1), nu, AMS).equals("1 factuur"), "enkelvoud");

        // ---------- planning ----------
        JSONObject p = new JSONObject(ontv.toString()).put("schema", "maand").put("dag", 3).put("tijd", "09:00").put("actief", true);
        long g = Facturen.gepland(p, nu, false, AMS);
        check(!FacturenAanlever.aanDeBeurt(p, new JSONObject(), false, nu, AMS), "niets open: niet aan de beurt");
        check(FacturenAanlever.aanDeBeurt(p, new JSONObject(), true, nu, AMS), "open en moment voorbij: aan de beurt");
        check(!FacturenAanlever.aanDeBeurt(p, new JSONObject().put("lastOk", g + 1).put("ok", true), true, nu, AMS), "deze periode al gelukt: wacht op de volgende");
        check(!FacturenAanlever.aanDeBeurt(p, new JSONObject().put("lastTry", nu - 3_600_000L).put("ok", false), true, nu, AMS), "net mislukt: wacht 3 uur");
        check(FacturenAanlever.aanDeBeurt(p, new JSONObject().put("lastTry", nu - 4 * 3_600_000L).put("ok", false), true, nu, AMS), "na 3 uur opnieuw");
        JSONObject dir = new JSONObject(p.toString()).put("schema", "direct");
        check(FacturenAanlever.aanDeBeurt(dir, new JSONObject().put("lastOk", nu - 1000).put("ok", true), true, nu, AMS), "direct: zodra er iets open staat");
        check(!FacturenAanlever.aanDeBeurt(new JSONObject(p.toString()).put("schema", "uit"), new JSONObject(), true, nu, AMS), "alleen met de hand: nooit vanzelf");
        check(!FacturenAanlever.aanDeBeurt(new JSONObject(dir.toString()).put("actief", false), new JSONObject(), true, nu, AMS), "uitgezet: nooit");
        check(FacturenAanlever.gepland(dir, nu, true, AMS) == Long.MAX_VALUE, "direct heeft geen vast moment");

        // ---------- mail opbouwen ----------
        byte[] bytes = new byte[1000];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 37);
        boolean b64 = true;
        for (int len : new int[]{0, 1, 2, 3, 57, 58, 1000}) {
            byte[] x = Arrays.copyOf(bytes, len);
            b64 &= FacturenEmail.base64(x, false).equals(java.util.Base64.getEncoder().encodeToString(x));
            b64 &= FacturenEmail.base64(x, true).replace("\r\n", "").equals(java.util.Base64.getEncoder().encodeToString(x));
        }
        check(b64, "eigen base64 = java.util.Base64");
        check(Arrays.stream(FacturenEmail.base64(bytes, true).split("\r\n")).allMatch(r -> r.length() <= 76), "base64-regels hooguit 76 tekens");
        check("Facturen".equals(FacturenEmail.kop("Facturen")) && FacturenEmail.kop("Facturen – oktober").startsWith("=?UTF-8?B?"), "onderwerp met – gecodeerd (RFC 2047)");
        FacturenEmail.Bericht bericht = FacturenEmail.bericht(new JSONObject(ontv.toString()).put("kopieMij", true).put("cc", "cc@jansen.nl"), twee, "rene@esape.nl", false, nu, AMS);
        check(bericht.aan.equals(Arrays.asList("facturen@jansen.nl", "boekhouding@jansen.nl")) && bericht.cc.equals(Arrays.asList("cc@jansen.nl")) && bericht.bcc.equals(Arrays.asList("rene@esape.nl")), "aan, cc en kopie aan mij");
        check("Facturen september – oktober 2026".equals(bericht.onderwerp), "onderwerp uit het sjabloon");
        FacturenEmail.Bericht proef = FacturenEmail.bericht(ontv, twee, "rene@esape.nl", true, nu, AMS);
        check(proef.aan.equals(Arrays.asList("rene@esape.nl")) && proef.cc.isEmpty() && proef.bcc.isEmpty() && proef.onderwerp.startsWith("[Proef]"), "proef alleen naar jezelf");
        bericht.bijlagen.add(new FacturenEmail.Bijlage("Factuur oktober €.pdf", "application/pdf", "%PDF-1.4\n.regel met punt\n".getBytes(StandardCharsets.UTF_8)));
        String mime = new String(FacturenEmail.mime(bericht, "tools-abc@esape.nl", nu, false), StandardCharsets.US_ASCII);
        check(mime.contains("From: \"Rene van Es\" <rene@esape.nl>\r\n") && mime.contains("To: facturen@jansen.nl, boekhouding@jansen.nl\r\n") && mime.contains("Cc: cc@jansen.nl\r\n"), "koppen From, To, Cc");
        check(!mime.contains("Bcc:"), "geen Bcc-kop bij SMTP");
        check(new String(FacturenEmail.mime(bericht, "x@y", nu, true), StandardCharsets.US_ASCII).contains("Bcc: rene@esape.nl\r\n"), "Bcc-kop voor Gmail");
        check(mime.contains("filename*=UTF-8''Factuur%20oktober%20%E2%82%AC.pdf") && mime.contains("filename=\"Factuur oktober _.pdf\""), "bestandsnaam met € (RFC 2231)");
        check(mime.contains("Message-ID: <tools-abc@esape.nl>") && mime.contains("MIME-Version: 1.0"), "Message-ID en MIME-versie");
        check(mime.chars().allMatch(ch -> ch < 128) && !mime.matches("(?s).*[^\r]\n.*"), "alleen ASCII en CRLF");
        boolean kopFout = false;
        try { FacturenEmail.Bericht x = FacturenEmail.bericht(ontv, twee, "rene@esape.nl", false, nu, AMS); x.onderwerp = "a\r\nBcc: x@y.nl"; FacturenEmail.mime(x, "a@b", nu, false); } catch (IllegalArgumentException e) { kopFout = true; }
        check(kopFout, "geen regeleinde in koppen");

        // ---------- SMTP ----------
        check(new String(FacturenSmtp.punten("a\n.b\r\n..c".getBytes(StandardCharsets.US_ASCII)), StandardCharsets.US_ASCII).equals("a\r\n..b\r\n...c\r\n"), "punten verdubbelen en CRLF");
        boolean onveilig = false;
        try { FacturenSmtp.controleer("smtp.voorbeeld.nl", 25, "geen"); } catch (Exception e) { onveilig = e.getMessage().contains("localhost"); }
        check(onveilig, "zonder versleuteling alleen naar localhost");
        boolean slecht = false;
        try { FacturenSmtp.controleer("smtp.voor beeld.nl", 587, "starttls"); } catch (Exception e) { slecht = true; }
        check(slecht, "ongeldige servernaam geweigerd");
        smtp(false);
        smtp(true);

        System.out.println(fails == 0 ? "alles goed" : fails + " mislukt");
        System.exit(fails == 0 ? 0 : 1);
    }

    /** Een kleine mailserver op deze computer (zonder versleuteling, alleen localhost) om het gesprek te controleren. */
    static void smtp(boolean weiger) throws Exception {
        final List<String> gesprek = new ArrayList<>();
        final StringBuilder data = new StringBuilder();
        try (ServerSocket ss = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            Thread t = new Thread(() -> {
                try (Socket s = ss.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    OutputStream out = s.getOutputStream();
                    out.write("220 test ESMTP\r\n".getBytes()); out.flush();
                    String r; boolean inData = false;
                    while ((r = in.readLine()) != null) {
                        if (inData) { if (r.equals(".")) { inData = false; out.write("250 2.0.0 Ok: queued\r\n".getBytes()); } else data.append(r).append('\n'); out.flush(); continue; }
                        gesprek.add(r);
                        String w = r.toUpperCase();
                        if (w.startsWith("EHLO")) out.write("250-test\r\n250-SIZE 52428800\r\n250 AUTH LOGIN PLAIN\r\n".getBytes());
                        else if (w.startsWith("AUTH PLAIN")) out.write("235 2.7.0 Authentication successful\r\n".getBytes());
                        else if (w.startsWith("MAIL")) out.write("250 Ok\r\n".getBytes());
                        else if (w.startsWith("RCPT")) out.write((weiger && r.contains("boekhouding") ? "550 5.1.1 Onbekend adres\r\n" : "250 Ok\r\n").getBytes());
                        else if (w.equals("DATA")) { out.write("354 Go\r\n".getBytes()); inData = true; }
                        else if (w.equals("QUIT")) { out.write("221 Bye\r\n".getBytes()); out.flush(); break; }
                        else out.write("502 ?\r\n".getBytes());
                        out.flush();
                    }
                } catch (Exception ignored) { }
            });
            t.start();
            String fout = "";
            try {
                FacturenSmtp.stuur("127.0.0.1", ss.getLocalPort(), "geen", "rene", "geheim", "rene@esape.nl",
                        Arrays.asList("facturen@jansen.nl", "boekhouding@jansen.nl"), "Subject: x\r\n\r\n.punt\r\n".getBytes(StandardCharsets.US_ASCII));
            } catch (Exception e) { fout = e.getMessage(); }
            t.join(5000);
            if (weiger) {
                check(fout.contains("weigert het adres") && fout.contains("550"), "SMTP: geweigerd adres wordt gemeld");
                check(data.length() == 0, "SMTP: bij een fout niets verstuurd");
            } else {
                check(fout.isEmpty(), "SMTP: verstuurd " + fout);
                String plain = java.util.Base64.getEncoder().encodeToString("\0rene\0geheim".getBytes(StandardCharsets.UTF_8));
                check(gesprek.contains("AUTH PLAIN " + plain), "SMTP: inloggen met AUTH PLAIN");
                check(gesprek.contains("MAIL FROM:<rene@esape.nl>") && gesprek.contains("RCPT TO:<facturen@jansen.nl>") && gesprek.contains("RCPT TO:<boekhouding@jansen.nl>"), "SMTP: afzender en ontvangers");
                check(data.toString().contains("\n..punt\n"), "SMTP: regel met een punt verdubbeld");
                check(gesprek.get(gesprek.size() - 1).equals("QUIT"), "SMTP: netjes afgesloten");
            }
        }
    }
}
