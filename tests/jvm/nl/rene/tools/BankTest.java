package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** Bankzaken: afschriften van Nederlandse banken inlezen, dubbele boekingen, bedragen en datums, en de versleuteling. */
public class BankTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }
    static BankImport.Result p(String s, String name) throws Exception { return BankImport.parse(s.getBytes(StandardCharsets.UTF_8), name); }
    static long c(String s, char h) { try { return BankImport.cents(s, h); } catch (Exception e) { return Long.MIN_VALUE; } }
    static String err(String s, String name) { try { p(s, name); return ""; } catch (Exception e) { return e.getMessage(); } }

    public static void main(String[] args) throws Exception {
        // ---------- bedragen en datums ----------
        check(c("1.234,56", ',') == 123456 && c("1,234.56", '.') == 123456 && c("-12,34", ',') == -1234 && c("+12.34", '.') == 1234, "bedragen met punten en komma's");
        check(c("€ 5", ',') == 500 && c("12,34-", ',') == -1234 && c("(7,50)", ',') == -750 && c("0,5", ',') == 50, "euroteken, min achteraan, haakjes, één decimaal");
        check(c("1.234", ',') == 123400 && c("1,234", '.') == 123400 && c("1.234.567", ',') == 123456700, "duizendtallen volgens het formaat");
        check(c("12.3450", '.') == 1235 && c("abc", ',') == Long.MIN_VALUE && c("", ',') == Long.MIN_VALUE, "afronden op centen; geen bedrag = fout");
        check(BankImport.date("20261003").equals("2026-10-03") && BankImport.date("03-10-2026").equals("2026-10-03") && BankImport.date("2026-10-03T08:00:00").equals("2026-10-03"), "datumvormen");
        check(BankImport.date("31-02-2026").isEmpty() && BankImport.date("29-02-2028").equals("2028-02-29") && BankImport.date("morgen").isEmpty(), "ongeldige datum geweigerd, schrikkeljaar klopt");
        check(BankImport.iban("nl91 abna 0417 1643 00").equals("NL91ABNA0417164300") && BankImport.iban("<script>").isEmpty(), "rekeningnummer netjes, rommel geweigerd");

        // ---------- ING (CSV, puntkomma, met saldo) ----------
        String ing = "\"Datum\";\"Naam / Omschrijving\";\"Rekening\";\"Tegenrekening\";\"Code\";\"Af Bij\";\"Bedrag (EUR)\";\"Mutatiesoort\";\"Mededelingen\";\"Saldo na mutatie\";\"Tag\"\n"
                + "\"20261003\";\"ALBERT HEIJN 1234\";\"NL20INGB0001234567\";\"\";\"BA\";\"Af\";\"23,45\";\"Betaalautomaat\";\"Pasvolgnr: 008 03-10-2026 12:01\";\"1.976,55\";\"\"\n"
                + "\"20261025\";\"Werkgever B.V.\";\"NL20INGB0001234567\";\"NL02RABO0123456789\";\"OV\";\"Bij\";\"3.100,00\";\"Overschrijving\";\"Naam: Werkgever B.V. Omschrijving: Salaris oktober\";\"5.076,55\";\"\"\n";
        BankImport.Result r = p(ing, "NL20INGB_01-10-2026_31-10-2026.csv");
        check(r.bank.equals("ING") && r.tx.size() == 2, "ING herkend, 2 boekingen");
        BankImport.Tx t = r.tx.get(0);
        check(t.d.equals("2026-10-03") && t.a == -2345 && t.cp.equals("ALBERT HEIJN 1234") && t.acc.equals("NL20INGB0001234567") && t.b == 197655, "ING: datum, af, naam, rekening, saldo");
        check(r.tx.get(1).a == 310000 && r.tx.get(1).ci.equals("NL02RABO0123456789") && r.tx.get(1).ty.equals("Overschrijving"), "ING: bij, tegenrekening, soort");
        // ING oude komma-export
        String ingOud = "\"Datum\",\"Naam / Omschrijving\",\"Rekening\",\"Tegenrekening\",\"Code\",\"Af Bij\",\"Bedrag (EUR)\",\"MutatieSoort\",\"Mededelingen\"\n\"20250102\",\"NS GROEP IZ NS REIZIGERS\",\"NL20INGB0001234567\",\"\",\"BA\",\"Af\",\"4,10\",\"Betaalautomaat\",\"Pasvolgnr:008\"\n";
        r = p(ingOud, "x.csv");
        check(r.bank.equals("ING") && r.tx.get(0).a == -410 && r.tx.get(0).b == null, "ING: oude export met komma's");

        // ---------- Rabobank ----------
        String rabo = "\"IBAN/BBAN\",\"Munt\",\"BIC\",\"Volgnr\",\"Datum\",\"Rentedatum\",\"Bedrag\",\"Saldo na trn\",\"Tegenrekening IBAN/BBAN\",\"Naam tegenpartij\",\"Naam uiteindelijke partij\",\"Naam initiërende partij\",\"BIC tegenpartij\",\"Code\",\"Batch ID\",\"Transactiereferentie\",\"Machtigingskenmerk\",\"Incassant ID\",\"Betalingskenmerk\",\"Omschrijving-1\",\"Omschrijving-2\",\"Omschrijving-3\",\"Reden retour\",\"Oorspr bedrag\",\"Oorspr munt\",\"Koers\"\n"
                + "\"NL02RABO0123456789\",\"EUR\",\"RABONL2U\",\"000000000000007213\",\"2026-10-01\",\"2026-10-01\",\"-1.250,00\",\"+2.000,00\",\"NL11WONI0000000001\",\"Woonstichting\",\"\",\"\",\"\",\"ei\",\"\",\"\",\"\",\"\",\"\",\"Huur oktober\",\"woning 12\",\"\",\"\",\"\",\"\",\"\"\n";
        r = p(rabo, "CSV_A_20261101.csv");
        t = r.tx.get(0);
        check(r.bank.equals("Rabobank") && t.a == -125000 && t.b == 200000 && t.cp.equals("Woonstichting") && t.ds.equals("Huur oktober woning 12"), "Rabobank: bedrag met teken, saldo, omschrijving 1-3 samen");

        // ---------- Knab ----------
        String knab = "KNAB EXPORT;;;;;;;;;;;;;;;\n\"Rekeningnummer\";\"Transactiedatum\";\"Valutacode\";\"CreditDebet\";\"Bedrag\";\"Tegenrekeningnummer\";\"Tegenrekeninghouder\";\"Valutadatum\";\"Betaalwijze\";\"Omschrijving\";\"Type betaling\";\"Machtigingsnummer\";\"Incassant ID\";\"Adres\";\"Referentie\";\"Boekdatum\"\n"
                + "\"NL12KNAB0123456789\";\"05-10-2026\";\"EUR\";\"D\";\"9,99\";\"NL99ABNA0000000001\";\"Netflix International\";\"05-10-2026\";\"Incasso\";\"Netflix maand\";\"\";\"\";\"\";\"\";\"\";\"05-10-2026\"\n";
        r = p(knab, "knab.csv");
        check(r.bank.equals("Knab") && r.tx.get(0).a == -999 && r.tx.get(0).cp.equals("Netflix International"), "Knab: voorregel overgeslagen, D = af");

        // ---------- bunq ----------
        String bunq = "\"Date\";\"Interest Date\";\"Amount\";\"Account\";\"Counterparty\";\"Name\";\"Description\"\n\"2026-10-07\";\"2026-10-07\";\"-35,50\";\"NL55BUNQ2025123456\";\"NL69INGB0123456789\";\"Jan Jansen\";\"Etentje\"\n";
        r = p(bunq, "bunq.csv");
        check(r.bank.equals("bunq") && r.tx.get(0).a == -3550 && r.tx.get(0).ci.equals("NL69INGB0123456789") && r.tx.get(0).cp.equals("Jan Jansen"), "bunq");

        // ---------- ASN/SNS/RegioBank (zonder kopregel, punt als decimaalteken) ----------
        String asn = "03-10-2026,NL11ASNB0123456789,NL22INGB0000000002,Gemeente Rotterdam,,,,EUR,1500.00,EUR,-123.40,03-10-2026,03-10-2026,1234,IOB,1,,'Gemeentelijke belastingen',1\n";
        r = p(asn, "transactie-historie.csv");
        t = r.tx.get(0);
        check(r.bank.equals("ASN/SNS/RegioBank") && t.a == -12340 && t.b == 137660 && t.cp.equals("Gemeente Rotterdam") && t.ty.equals("IOB"), "ASN/SNS/RegioBank");

        // ---------- Triodos ----------
        String trio = "10-10-2026,NL33TRIO0123456789,\"1.020,00\",Credit,Mijn Spaarrekening,NL44TRIO0000000004,OV,Terug van sparen\n";
        r = p(trio, "triodos.csv");
        check(r.bank.equals("Triodos") && r.tx.get(0).a == 102000 && r.tx.get(0).cp.equals("Mijn Spaarrekening"), "Triodos");

        // ---------- ABN AMRO TXT ----------
        String abn = "417164300\tEUR\t20261004\t1000,00\t976,50\t20261004\t-23,50\tBEA, Apple Pay                 Jumbo Rotterdam,PAS123 NR:A1B2C3, 04.10.26/18:22 ROTTERDAM\n"
                + "417164300\tEUR\t20261005\t976,50\t951,50\t20261005\t-25,00\t/TRTP/SEPA OVERBOEKING/IBAN/NL69INGB0123456789/BIC/INGBNL2A/NAME/Piet Pietersen/REMI/Cadeau/EREF/NOTPROVIDED\n"
                + "417164300\tEUR\t20261006\t951,50\t906,50\t20261006\t-45,00\tSEPA Incasso algemeen doorlopend Incassant: NL12ZZZ123456780000  Naam: Vattenfall Klantenservice  Machtiging: 123  Omschrijving: Termijnbedrag energie\n";
        r = p(abn, "TXT261031.TAB");
        check(r.bank.equals("ABN AMRO") && r.tx.size() == 3, "ABN AMRO herkend");
        check(r.tx.get(0).cp.equals("Jumbo Rotterdam") && r.tx.get(0).a == -2350 && r.tx.get(0).b == 97650, "ABN: pinbetaling, naam uit de omschrijving");
        check(r.tx.get(1).cp.equals("Piet Pietersen") && r.tx.get(1).ci.equals("NL69INGB0123456789") && r.tx.get(1).ds.equals("Cadeau"), "ABN: SEPA met /NAME/ en /REMI/");
        check(r.tx.get(2).cp.equals("Vattenfall Klantenservice") && r.tx.get(2).ds.startsWith("Termijnbedrag"), "ABN: oude SEPA-tekst met Naam: en Omschrijving:");

        // ---------- MT940 ----------
        String mt = ":20:STARTUMS\n:25:NL20INGB0001234567EUR\n:28C:00000\n:60F:C261001EUR1000,00\n"
                + ":61:2610021002D12,50NTRFNONREF//\n:86:/CNTP/NL69INGB0123456789/INGBNL2A/Kapper Knip/ROTTERDAM/REMI/USTD//Knipbeurt/\n"
                + ":61:261003C200,00NTRFNONREF\n:86:/CNTP/NL02RABO0123456789/RABONL2U/Oma/\n/REMI/USTD//Verjaardag/\n:62F:C261003EUR1187,50\n-}\n";
        r = p(mt, "afschrift.sta");
        check(r.format.equals("MT940") && r.tx.size() == 2 && r.bank.equals("ING"), "MT940 van ING herkend");
        check(r.tx.get(0).a == -1250 && r.tx.get(0).cp.equals("Kapper Knip") && r.tx.get(0).ds.equals("Knipbeurt") && r.tx.get(0).b == 98750 && r.tx.get(0).acc.equals("NL20INGB0001234567"), "MT940: tegenpartij, omschrijving, lopend saldo, rekening");
        check(r.tx.get(1).a == 20000 && r.tx.get(1).cp.equals("Oma") && r.tx.get(1).ds.equals("Verjaardag") && r.tx.get(1).b == 118750, "MT940: :86: over meerdere regels");

        // ---------- CAMT.053 ----------
        String camt = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:camt.053.001.02\"><BkToCstmrStmt><Stmt>"
                + "<Acct><Id><IBAN>NL02RABO0123456789</IBAN></Id></Acct>"
                + "<Bal><Tp><CdOrPrtry><Cd>OPBD</Cd></CdOrPrtry></Tp><Amt Ccy=\"EUR\">500.00</Amt><CdtDbtInd>CRDT</CdtDbtInd></Bal>"
                + "<Ntry><Amt Ccy=\"EUR\">42.10</Amt><CdtDbtInd>DBIT</CdtDbtInd><Sts>BOOK</Sts><BookgDt><Dt>2026-10-08</Dt></BookgDt><NtryDtls><TxDtls>"
                + "<RltdPties><Cdtr><Nm>Bol.com</Nm></Cdtr><CdtrAcct><Id><IBAN>NL27INGB0000026500</IBAN></Id></CdtrAcct></RltdPties><RmtInf><Ustrd>Bestelling 123</Ustrd></RmtInf></TxDtls></NtryDtls></Ntry>"
                + "<Ntry><Amt Ccy=\"EUR\">10.00</Amt><CdtDbtInd>CRDT</CdtDbtInd><Sts>PDNG</Sts><BookgDt><Dt>2026-10-09</Dt></BookgDt></Ntry>"
                + "<Ntry><Amt Ccy=\"EUR\">30.00</Amt><CdtDbtInd>DBIT</CdtDbtInd><BookgDt><Dt>2026-10-10</Dt></BookgDt><NtryDtls>"
                + "<TxDtls><AmtDtls><TxAmt><Amt Ccy=\"EUR\">10.00</Amt></TxAmt></AmtDtls><RltdPties><Cdtr><Nm>A</Nm></Cdtr></RltdPties></TxDtls>"
                + "<TxDtls><AmtDtls><TxAmt><Amt Ccy=\"EUR\">20.00</Amt></TxAmt></AmtDtls><RltdPties><Cdtr><Nm>B</Nm></Cdtr></RltdPties></TxDtls></NtryDtls></Ntry>"
                + "</Stmt></BkToCstmrStmt></Document>";
        r = p(camt, "camt.xml");
        check(r.format.equals("CAMT") && r.bank.equals("Rabobank") && r.tx.size() == 3, "CAMT.053: geboekt, nog niet geboekt overgeslagen, verzamelboeking gesplitst");
        check(r.tx.get(0).a == -4210 && r.tx.get(0).cp.equals("Bol.com") && r.tx.get(0).ci.equals("NL27INGB0000026500") && r.tx.get(0).ds.equals("Bestelling 123") && r.tx.get(0).b == 45790, "CAMT: tegenpartij, rekening, omschrijving, saldo");
        check(r.tx.get(1).a == -1000 && r.tx.get(1).cp.equals("A") && r.tx.get(2).a == -2000 && r.tx.get(1).b == 44790 && r.tx.get(2).b == 42790, "CAMT: deelbetalingen met eigen bedrag");
        // Geen XXE: een bestand met DOCTYPE/entiteit wordt niet gelezen
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><Document><Stmt><Ntry><Amt>1.00</Amt></Ntry></Stmt></Document>";
        check(err(xxe, "x.xml").contains("veiligheidsredenen"), "XML met DOCTYPE/entiteit geweigerd (XXE)");

        // ---------- fouten ----------
        check(err("", "a.csv").contains("leeg") && err("hallo wereld\nnog een regel\n", "a.csv").contains("herken ik niet"), "lege of onbekende bestanden: duidelijke melding");
        check(err("\"Datum\";\"Bedrag\"\n\"geen\";\"x\"\n", "a.csv").contains("Geen boekingen"), "kopregel zonder geldige regels");
        byte[] big = new byte[BankImport.MAX_BYTES + 1];
        try { BankImport.parse(big, "x.csv"); check(false, "te groot geweigerd"); } catch (Exception e) { check(e.getMessage().contains("te groot"), "te groot bestand geweigerd"); }
        // Windows-1252 (oude export met é)
        byte[] w = "\"Datum\";\"Naam / Omschrijving\";\"Rekening\";\"Tegenrekening\";\"Code\";\"Af Bij\";\"Bedrag (EUR)\";\"Mutatiesoort\";\"Mededelingen\"\n\"20261003\";\"Café De Unie\";\"NL20INGB0001234567\";\"\";\"BA\";\"Af\";\"4,00\";\"Betaalautomaat\";\"\"\n".getBytes("windows-1252");
        check(BankImport.parse(w, "a.csv").tx.get(0).cp.equals("Café De Unie"), "Windows-1252 goed gelezen");

        // ---------- samenvoegen en dubbele boekingen ----------
        JSONObject store = new JSONObject().put("v", 1);
        JSONObject s1 = BankImport.merge(store, p(ing, "a.csv"), 1000);
        check(s1.getInt("nieuw") == 2 && s1.getInt("dubbel") == 0 && s1.getString("van").equals("2026-10-03") && s1.getString("tot").equals("2026-10-25"), "eerste import: 2 nieuw, periode");
        JSONObject s2 = BankImport.merge(store, p(ing, "a.csv"), 2000);
        check(s2.getInt("nieuw") == 0 && s2.getInt("dubbel") == 2 && store.getJSONArray("tx").length() == 2, "zelfde afschrift nog eens: niets dubbel");
        String twee = "\"Datum\";\"Naam / Omschrijving\";\"Rekening\";\"Tegenrekening\";\"Code\";\"Af Bij\";\"Bedrag (EUR)\";\"Mutatiesoort\";\"Mededelingen\"\n"
                + "\"20261004\";\"Koffie\";\"NL20INGB0001234567\";\"\";\"BA\";\"Af\";\"2,50\";\"Betaalautomaat\";\"1\"\n\"20261004\";\"Koffie\";\"NL20INGB0001234567\";\"\";\"BA\";\"Af\";\"2,50\";\"Betaalautomaat\";\"2\"\n";
        JSONObject s3 = BankImport.merge(store, p(twee, "b.csv"), 3000);
        check(s3.getInt("nieuw") == 2, "twee gelijke koffies op één dag: allebei bewaard");
        check(BankImport.merge(store, p(twee, "b.csv"), 4000).getInt("dubbel") == 2, "en bij opnieuw importeren niet nog eens");
        JSONObject o = store.getJSONArray("tx").getJSONObject(0);
        check(o.getString("id").matches("[0-9a-f]{18}") && o.getLong("a") == -2345 && o.getLong("b") == 197655 && o.getString("src").equals("ING"), "opgeslagen boeking: id, centen, saldo, bank");
        check(store.getJSONArray("imports").length() == 4, "importgeschiedenis bijgehouden");

        // ---------- versleuteling ----------
        KeyGenerator g = KeyGenerator.getInstance("AES"); g.init(256);
        SecretKey k = g.generateKey(), other = g.generateKey();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40000; i++) sb.append("{\"id\":\"").append(i).append("\",\"cp\":\"Albert Heijn ").append(Long.toHexString(new java.util.Random(i).nextLong())).append(Long.toHexString(new java.util.Random(-i).nextLong())).append("\"},");
        byte[] plain = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] blob = BankCrypt.encrypt(plain, k);
        check(java.util.Arrays.equals(plain, BankCrypt.decrypt(blob, k)), "versleutelen en ontsleutelen (meerdere blokken)");
        check(!new String(blob, StandardCharsets.ISO_8859_1).contains("Albert Heijn"), "geen leesbare tekst in het versleutelde bestand");
        check(!java.util.Arrays.equals(blob, BankCrypt.encrypt(plain, k)), "elke keer een nieuwe IV (zelfde inhoud, ander bestand)");
        check(fails(blob, other), "andere sleutel: geweigerd");
        byte[] bad = blob.clone(); bad[bad.length / 2] ^= 1;
        check(fails(bad, k), "één bit veranderd: geweigerd");
        check(fails(java.util.Arrays.copyOf(blob, blob.length - 1), k), "afgekapt: geweigerd");
        // Afgekapt precies op een blokgrens (laatste blok weggelaten): ook geweigerd
        int firstLen = ((blob[5 + 12] & 0xff) << 24) | ((blob[5 + 13] & 0xff) << 16) | ((blob[5 + 14] & 0xff) << 8) | (blob[5 + 15] & 0xff);
        check(fails(java.util.Arrays.copyOf(blob, 5 + 16 + firstLen), k), "laatste blok weggelaten: geweigerd");
        byte[] one = BankCrypt.encrypt(new byte[0], k);
        check(BankCrypt.decrypt(one, k).length == 0, "lege inhoud kan ook");

        System.out.println(fails == 0 ? "alles goed" : fails + " mislukt");
        System.exit(fails == 0 ? 0 : 1);
    }

    static boolean fails(byte[] blob, SecretKey k) { try { BankCrypt.decrypt(blob, k); return false; } catch (Exception e) { return true; } }
}
