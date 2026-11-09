package nl.rene.tools;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bankzaken: afschriften van Nederlandse banken inlezen. Puur Java (geen Android), zodat het op een gewone JVM getest
 * wordt (BankTest). Alles gebeurt op de telefoon; er gaat niets over het netwerk.
 *
 * Herkent: CSV van ING, Rabobank, Knab, bunq, ASN/SNS/RegioBank (de Volksbank) en Triodos, het TXT-bestand van
 * ABN AMRO, CAMT.053/052/054 (XML, alle banken) en MT940 (.sta/.940, alle banken). Een andere CSV met kopregel
 * (datum, bedrag, naam …) lukt meestal ook.
 */
final class BankImport {

    private BankImport() { }

    /** Maximale grootte van één bestand (een jaar afschriften is meestal enkele MB). */
    static final int MAX_BYTES = 25 * 1024 * 1024;
    static final int MAX_ROWS = 200_000;

    /** Eén boeking. a = bedrag in centen (negatief = af), b = saldo na de boeking in centen (of null). */
    static final class Tx {
        String acc = "", d = "", cp = "", ci = "", ds = "", ty = "";
        long a;
        Long b;
    }

    static final class Result {
        String bank = "", format = "";
        final List<Tx> tx = new ArrayList<>();
        int skipped = 0;
    }

    // ---------- algemeen ----------

    static Result parse(byte[] data, String fileName) throws Exception {
        if (data == null || data.length == 0) throw new Exception("Het bestand is leeg");
        if (data.length > MAX_BYTES) throw new Exception("Het bestand is te groot (max 25 MB)");
        String text = decode(data);
        if (text.startsWith("﻿")) text = text.substring(1);
        String head = text.substring(0, Math.min(text.length(), 4000));
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        Result r;
        if (head.trim().startsWith("<") || name.endsWith(".xml")) r = camt(text);
        else if (head.contains(":61:") || (head.contains(":20:") && head.contains(":25:"))) r = mt940(text);
        else r = csv(text);
        if (r.tx.isEmpty()) throw new Exception("Geen boekingen gevonden. Kies een export (CSV, CAMT.053 of MT940) van je bank.");
        return r;
    }

    /** UTF-8 als dat klopt, anders Windows-1252 (oudere exports van Nederlandse banken). */
    static String decode(byte[] b) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            return new String(b, Charset.forName("windows-1252"));
        }
    }

    static String clean(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        boolean sp = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 || c == 0x7f || Character.isWhitespace(c) || c == ' ') { if (!sp && b.length() > 0) b.append(' '); sp = true; }
            else { b.append(c); sp = false; }
        }
        String o = b.toString().trim();
        return o.length() > 400 ? o.substring(0, 400) : o;
    }

    static final Pattern IBAN = Pattern.compile("\\b([A-Z]{2}\\d{2}[A-Z0-9]{10,30})\\b");

    /** Rekeningnummer netjes: hoofdletters, zonder spaties. Leeg als het niets lijkt. */
    static String iban(String s) {
        if (s == null) return "";
        String t = s.replace(" ", "").replace(" ", "").trim().toUpperCase(Locale.ROOT);
        if (t.length() > 34) t = t.substring(0, 34);
        return t.matches("[A-Z0-9]{5,34}") ? t : "";
    }

    /** Datum naar jjjj-mm-dd; leeg als het geen geldige datum is. */
    static String date(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() > 10 && t.charAt(10) == 'T') t = t.substring(0, 10); // 2026-10-03T12:00:00
        if (t.length() > 10 && t.charAt(10) == ' ') t = t.substring(0, 10);
        int y, m, d;
        Matcher x;
        if ((x = Pattern.compile("^(\\d{4})(\\d{2})(\\d{2})$").matcher(t)).find()
                || (x = Pattern.compile("^(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})$").matcher(t)).find()) {
            y = Integer.parseInt(x.group(1)); m = Integer.parseInt(x.group(2)); d = Integer.parseInt(x.group(3));
        } else if ((x = Pattern.compile("^(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{4})$").matcher(t)).find()) {
            d = Integer.parseInt(x.group(1)); m = Integer.parseInt(x.group(2)); y = Integer.parseInt(x.group(3));
        } else if ((x = Pattern.compile("^(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{2})$").matcher(t)).find()) {
            d = Integer.parseInt(x.group(1)); m = Integer.parseInt(x.group(2)); y = 2000 + Integer.parseInt(x.group(3));
        } else return "";
        if (y < 1990 || y > 2100 || m < 1 || m > 12 || d < 1 || d > daysIn(y, m)) return "";
        return String.format(Locale.ROOT, "%04d-%02d-%02d", y, m, d);
    }

    static int daysIn(int y, int m) {
        if (m == 2) return (y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)) ? 29 : 28;
        return m == 4 || m == 6 || m == 9 || m == 11 ? 30 : 31;
    }

    /**
     * Bedrag naar centen. Kent "1.234,56", "1,234.56", "-12,34", "+12.34", "€ 5", "12,34-".
     * hint = het decimaalteken van het formaat (',' of '.'), of 0 als het niet bekend is.
     * Gooit een fout als het geen bedrag is.
     */
    static long cents(String s, char hint) throws Exception {
        if (s == null) throw new Exception("geen bedrag");
        String t = s.replace("€", "").replace("EUR", "").replace(" ", "").replace(" ", "").replace("'", "").trim();
        boolean neg = false;
        if (t.startsWith("(") && t.endsWith(")")) { neg = true; t = t.substring(1, t.length() - 1); }
        if (t.endsWith("-")) { neg = !neg; t = t.substring(0, t.length() - 1); }
        if (t.startsWith("-")) { neg = !neg; t = t.substring(1); }
        else if (t.startsWith("+")) t = t.substring(1);
        if (t.isEmpty() || !t.matches("[0-9.,]+")) throw new Exception("geen bedrag: " + s);
        int lc = t.lastIndexOf(','), ld = t.lastIndexOf('.');
        char dec;
        if (lc >= 0 && ld >= 0) dec = lc > ld ? ',' : '.';
        else if (lc < 0 && ld < 0) dec = 0;
        else {
            char only = lc >= 0 ? ',' : '.';
            int pos = Math.max(lc, ld), after = t.length() - pos - 1, count = 0;
            for (int i = 0; i < t.length(); i++) if (t.charAt(i) == only) count++;
            if (count > 1) dec = 0;                          // 1.234.567: alleen duizendtallen
            else if (after != 3) dec = only;                 // 12,3 of 12,34
            else dec = hint == only ? only : hint == 0 ? (only == ',' ? ',' : 0) : 0; // 1.234 of 1,234: hangt af van het formaat
        }
        String whole, frac = "";
        if (dec == 0) whole = t.replace(",", "").replace(".", "");
        else {
            int p = t.lastIndexOf(dec);
            whole = t.substring(0, p).replace(",", "").replace(".", "");
            frac = t.substring(p + 1);
        }
        if (whole.isEmpty()) whole = "0";
        if (frac.length() > 2) {
            // Meer dan 2 decimalen (bijv. 12,3450): afronden op centen
            if (!frac.substring(2).matches("0*")) { long r = Math.round(Double.parseDouble("0." + frac) * 100); frac = String.format(Locale.ROOT, "%02d", Math.min(99, r)); }
            else frac = frac.substring(0, 2);
        }
        while (frac.length() < 2) frac += "0";
        if (whole.length() > 13) throw new Exception("bedrag te groot");
        long v = Long.parseLong(whole) * 100 + Long.parseLong(frac);
        return neg ? -v : v;
    }

    // ---------- CSV ----------

    /** Eén CSV-regel splitsen (aanhalingstekens, "" binnen een veld). Regels met een veld over meerdere regels kloppen ook. */
    static List<List<String>> rows(String text, char sep) {
        List<List<String>> out = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean q = false, any = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (q) {
                if (c == '"') { if (i + 1 < text.length() && text.charAt(i + 1) == '"') { f.append('"'); i++; } else q = false; }
                else f.append(c);
            } else if (c == '"' && f.toString().trim().isEmpty()) { q = true; f.setLength(0); any = true; }
            else if (c == sep) { row.add(f.toString()); f.setLength(0); any = true; }
            else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                if (any || f.length() > 0) { row.add(f.toString()); out.add(row); }
                row = new ArrayList<>(); f.setLength(0); any = false;
                if (out.size() > MAX_ROWS + 10) break;
            } else { f.append(c); if (c != ' ') any = true; }
        }
        if (any || f.length() > 0) { row.add(f.toString()); out.add(row); }
        return out;
    }

    static char separator(String text) {
        // Het teken dat buiten aanhalingstekens het vaakst en het gelijkmatigst voorkomt in de eerste regels
        String[] lines = text.split("\r?\n", 12);
        char best = ',';
        int bestScore = -1;
        for (char s : new char[]{';', ',', '\t', '|'}) {
            int score = 0, prev = -1;
            for (int l = 0; l < lines.length && l < 10; l++) {
                int n = 0; boolean q = false;
                for (char c : lines[l].toCharArray()) { if (c == '"') q = !q; else if (c == s && !q) n++; }
                if (n > 0) score += n + (n == prev ? 5 : 0);
                prev = n;
            }
            if (score > bestScore) { bestScore = score; best = s; }
        }
        return best;
    }

    static String key(String h) {
        return h == null ? "" : h.replace("\"", "").replace("﻿", "").trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** Welke kolom wat is, uit de kopregel. */
    static final String[][] ROLES = {
            {"date", "datum", "date", "transactiedatum", "boekingsdatum", "boekdatum", "transaction date", "uitvoeringsdatum", "booking date"},
            {"amount", "bedrag (eur)", "bedrag", "amount (eur)", "amount", "transactiebedrag", "bedrag in eur"},
            {"sign", "af bij", "af/bij", "debit/credit", "creditdebet", "credit/debet", "debet/credit", "credit/debit", "bij/af"},
            {"acc", "rekening", "iban/bban", "account", "rekeningnummer", "eigen rekening", "iban", "own account"},
            {"ci", "tegenrekening", "tegenrekening iban/bban", "counterparty", "tegenrekeningnummer", "counterparty iban", "tegenrekening iban", "iban tegenpartij"},
            {"cp", "naam / omschrijving", "name / description", "naam tegenpartij", "tegenrekeninghouder", "counterparty name", "name", "naam", "begunstigde", "naam begunstigde"},
            {"ds", "mededelingen", "omschrijving", "description", "notifications", "omschrijving-1", "omschrijving-2", "omschrijving-3", "mededeling", "betalingskenmerk"},
            {"ty", "mutatiesoort", "transaction type", "betaalwijze", "type betaling", "type", "soort transactie"},
            {"b", "saldo na mutatie", "saldo na trn", "resulting balance", "saldo na transactie", "saldo", "balance"}};

    static Map<String, List<Integer>> roles(List<String> header) {
        Map<String, List<Integer>> m = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String h = key(header.get(i));
            role:
            for (String[] r : ROLES) for (int j = 1; j < r.length; j++) if (h.equals(r[j])) {
                // Het eerste woord in de lijst wint: "naam / omschrijving" is de naam, niet de omschrijving
                List<Integer> l = m.get(r[0]);
                if (l == null) { l = new ArrayList<>(); m.put(r[0], l); }
                if (!r[0].equals("ds") && !l.isEmpty()) break role;
                l.add(i);
                break role;
            }
        }
        return m;
    }

    static String at(List<String> row, Map<String, List<Integer>> m, String role) {
        List<Integer> l = m.get(role);
        if (l == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i : l) if (i < row.size()) { String v = clean(row.get(i)); if (!v.isEmpty()) { if (b.length() > 0) b.append(' '); b.append(v); } }
        return b.toString();
    }

    static Result csv(String text) throws Exception {
        char sep = separator(text);
        List<List<String>> all = rows(text, sep);
        Result r = new Result();
        r.format = "CSV";
        String low = text.substring(0, Math.min(400, text.length())).toLowerCase(Locale.ROOT);
        // Kopregel zoeken (Knab begint met een regel "KNAB EXPORT")
        int hi = -1;
        Map<String, List<Integer>> m = null;
        for (int i = 0; i < Math.min(6, all.size()); i++) {
            Map<String, List<Integer>> t = roles(all.get(i));
            if (t.containsKey("date") && t.containsKey("amount")) { hi = i; m = t; break; }
        }
        if (m == null) return headerless(all, r);
        List<String> header = all.get(hi);
        StringBuilder hs = new StringBuilder();
        for (String h : header) hs.append('|').append(key(h));
        String h = hs.toString();
        char hint = ',';
        if (low.contains("knab")) r.bank = "Knab";
        else if (h.contains("naam / omschrijving") || h.contains("name / description")) r.bank = "ING";
        else if (h.contains("naam tegenpartij") && h.contains("volgnr")) r.bank = "Rabobank";
        else if (h.contains("rentedatum") || h.contains("interest date")) r.bank = "bunq";
        else { r.bank = ""; hint = 0; }
        for (int i = hi + 1; i < all.size() && r.tx.size() < MAX_ROWS; i++) {
            List<String> row = all.get(i);
            if (row.size() < 2) continue;
            try {
                Tx t = new Tx();
                t.d = date(at(row, m, "date"));
                if (t.d.isEmpty()) { r.skipped++; continue; }
                long a = cents(at(row, m, "amount"), hint);
                String s = key(at(row, m, "sign"));
                if (!s.isEmpty()) {
                    if (s.equals("af") || s.startsWith("deb") || s.equals("d") || s.equals("-")) a = -Math.abs(a);
                    else if (s.equals("bij") || s.startsWith("cred") || s.equals("c") || s.equals("+")) a = Math.abs(a);
                }
                t.a = a;
                t.acc = iban(at(row, m, "acc"));
                t.ci = iban(at(row, m, "ci"));
                t.cp = at(row, m, "cp");
                t.ds = at(row, m, "ds");
                t.ty = at(row, m, "ty");
                String b = at(row, m, "b");
                if (!b.isEmpty()) try { t.b = cents(b, hint); } catch (Exception ignored) { }
                if (t.cp.isEmpty()) t.cp = nameFrom(t.ds);
                if (t.cp.isEmpty()) t.cp = t.ci;
                r.tx.add(t);
            } catch (Exception e) { r.skipped++; }
        }
        return r;
    }

    /** Exports zonder kopregel: ABN AMRO (TXT), ASN/SNS/RegioBank, Triodos. */
    static Result headerless(List<List<String>> all, Result r) throws Exception {
        if (all.isEmpty()) return r;
        List<String> f = all.get(0);
        // ABN AMRO: rekening, EUR, datum (jjjjmmdd), beginsaldo, eindsaldo, rentedatum, bedrag, omschrijving
        if (f.size() >= 8 && clean(f.get(1)).equals("EUR") && clean(f.get(2)).matches("\\d{8}")) {
            r.bank = "ABN AMRO"; r.format = "TXT";
            for (List<String> row : all) {
                if (row.size() < 8 || r.tx.size() >= MAX_ROWS) { r.skipped++; continue; }
                try {
                    Tx t = new Tx();
                    t.acc = iban(row.get(0));
                    t.d = date(clean(row.get(2)));
                    if (t.d.isEmpty()) { r.skipped++; continue; }
                    t.a = cents(row.get(6), ',');
                    try { t.b = cents(row.get(4), ','); } catch (Exception ignored) { }
                    abnDescription(t, clean(row.get(7)));
                    r.tx.add(t);
                } catch (Exception e) { r.skipped++; }
            }
            return r;
        }
        // ASN/SNS/RegioBank: datum, rekening, tegenrekening, naam, adres, postcode, plaats, munt, saldo vóór, munt, bedrag, …, omschrijving (17)
        if (f.size() >= 18 && !date(clean(f.get(0))).isEmpty() && !iban(f.get(1)).isEmpty() && clean(f.get(9)).equals("EUR")) {
            r.bank = "ASN/SNS/RegioBank"; r.format = "CSV";
            for (List<String> row : all) {
                if (row.size() < 18 || r.tx.size() >= MAX_ROWS) { r.skipped++; continue; }
                try {
                    Tx t = new Tx();
                    t.d = date(clean(row.get(0)));
                    if (t.d.isEmpty()) { r.skipped++; continue; }
                    t.acc = iban(row.get(1));
                    t.ci = iban(row.get(2));
                    t.cp = clean(row.get(3));
                    t.a = cents(row.get(10), '.');
                    try { t.b = cents(row.get(8), '.') + t.a; } catch (Exception ignored) { }
                    t.ty = clean(row.get(14));
                    t.ds = clean(row.get(17));
                    if (t.cp.isEmpty()) t.cp = nameFrom(t.ds);
                    r.tx.add(t);
                } catch (Exception e) { r.skipped++; }
            }
            return r;
        }
        // Triodos: datum, rekening, bedrag, Debet/Credit, naam, tegenrekening, code, omschrijving
        if (f.size() >= 8 && !date(clean(f.get(0))).isEmpty() && clean(f.get(3)).toLowerCase(Locale.ROOT).matches("debet|credit")) {
            r.bank = "Triodos"; r.format = "CSV";
            for (List<String> row : all) {
                if (row.size() < 8 || r.tx.size() >= MAX_ROWS) { r.skipped++; continue; }
                try {
                    Tx t = new Tx();
                    t.d = date(clean(row.get(0)));
                    if (t.d.isEmpty()) { r.skipped++; continue; }
                    t.acc = iban(row.get(1));
                    long a = Math.abs(cents(row.get(2), ','));
                    t.a = clean(row.get(3)).toLowerCase(Locale.ROOT).startsWith("deb") ? -a : a;
                    t.cp = clean(row.get(4));
                    t.ci = iban(row.get(5));
                    t.ty = clean(row.get(6));
                    t.ds = clean(row.get(7));
                    if (t.cp.isEmpty()) t.cp = nameFrom(t.ds);
                    r.tx.add(t);
                } catch (Exception e) { r.skipped++; }
            }
            return r;
        }
        throw new Exception("Dit bestand herken ik niet. Kies een CSV met kopregel, CAMT.053 (XML) of MT940 van je bank.");
    }

    static final Pattern ABN_SLASH = Pattern.compile("/(TRTP|IBAN|BIC|NAME|REMI|EREF|MARF|CSID|ORDP|BENM|ADDR|ISDT|RTRN|CNTP)/");
    static final Pattern ABN_OLD = Pattern.compile("(?i)Naam:\\s*(.+?)(?:\\s{2,}|\\s+Omschrijving:|\\s+Kenmerk:|\\s+Machtiging:|$)");
    static final Pattern ABN_OLD_DESC = Pattern.compile("(?i)Omschrijving:\\s*(.+?)(?:\\s{2,}Kenmerk:|\\s+Kenmerk:|\\s+IBAN:|$)");
    static final Pattern ABN_CARD = Pattern.compile("\\d{2}\\.\\d{2}\\.\\d{2}/\\d{2}[.:]\\d{2}\\s+(.+?)(?:,PAS\\d+|$)");
    static final Pattern ABN_CARD2 = Pattern.compile("^(?:BEA|GEA|eCom),?\\s*(?:Apple Pay|Google Pay|Betaalpas|Garmin Pay)?\\s+(.+?),PAS\\d+");

    /** ABN AMRO zet naam, rekening en omschrijving samen in één tekst; die uit elkaar halen. */
    static void abnDescription(Tx t, String s) {
        t.ds = s;
        if (s.startsWith("/")) {
            Map<String, String> f = slashFields(s);
            t.ty = f.containsKey("TRTP") ? f.get("TRTP") : "";
            t.ci = iban(f.get("IBAN"));
            t.cp = f.containsKey("NAME") ? f.get("NAME") : "";
            if (f.containsKey("REMI")) t.ds = f.get("REMI");
        } else if (s.matches("(?s)^(BEA|GEA|eCom).*")) {
            t.ty = s.startsWith("GEA") ? "Geldautomaat" : "Betaalautomaat";
            Matcher m = ABN_CARD2.matcher(s);
            if (m.find()) t.cp = clean(m.group(1));
            else if ((m = ABN_CARD.matcher(s)).find()) t.cp = clean(m.group(1));
        } else {
            Matcher m = Pattern.compile("(?i)IBAN:\\s*([A-Z]{2}\\d{2}[A-Z0-9 ]{10,30}?)\\s+BIC:").matcher(s);
            if (m.find()) t.ci = iban(m.group(1));
            if ((m = ABN_OLD.matcher(s)).find()) t.cp = clean(m.group(1));
            if ((m = ABN_OLD_DESC.matcher(s)).find()) t.ds = clean(m.group(1));
            if (s.startsWith("SEPA")) { int i = s.indexOf("  "); t.ty = clean(i > 0 ? s.substring(0, i) : "SEPA"); }
        }
        if (t.cp.isEmpty()) t.cp = nameFrom(s);
    }

    static Map<String, String> slashFields(String s) {
        Map<String, String> f = new HashMap<>();
        Matcher m = ABN_SLASH.matcher(s);
        List<int[]> pos = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        while (m.find()) { pos.add(new int[]{m.start(), m.end()}); keys.add(m.group(1)); }
        for (int i = 0; i < keys.size(); i++) {
            int end = i + 1 < pos.size() ? pos.get(i + 1)[0] : s.length();
            String v = s.substring(pos.get(i)[1], Math.max(pos.get(i)[1], end));
            if (v.endsWith("/")) v = v.substring(0, v.length() - 1);
            if (!f.containsKey(keys.get(i))) f.put(keys.get(i), clean(v));
        }
        return f;
    }

    /** Een naam uit een omschrijving halen als er geen aparte naam is: het begin, tot een cijferreeks of scheiding. */
    static String nameFrom(String ds) {
        if (ds == null) return "";
        String s = clean(ds);
        Matcher m = Pattern.compile("(?i)naam:\\s*([^:]+?)(\\s+\\w+:|$)").matcher(s);
        if (m.find()) return clean(m.group(1));
        s = s.replaceAll("^(?i)(BEA|GEA|eCom|SEPA \\w+( \\w+)?|iDEAL)[,:]?\\s*", "");
        int cut = s.length();
        for (String sep : new String[]{"  ", " - ", ",", ";", " Pasvolgnr", " IBAN", " Kenmerk", " Omschrijving"}) { int i = s.indexOf(sep); if (i > 2 && i < cut) cut = i; }
        s = s.substring(0, cut);
        return clean(s.length() > 60 ? s.substring(0, 60) : s);
    }

    // ---------- MT940 ----------

    static Result mt940(String text) throws Exception {
        Result r = new Result();
        r.format = "MT940";
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        // Velden samenvoegen: een veld loopt door tot de volgende regel die met :xx: begint
        List<String[]> fields = new ArrayList<>();
        Pattern tag = Pattern.compile("^:(\\d{2}[A-Z]?):(.*)$");
        for (String l : lines) {
            Matcher m = tag.matcher(l);
            if (m.find()) fields.add(new String[]{m.group(1), m.group(2)});
            else if (!fields.isEmpty() && !l.startsWith("-}") && !l.startsWith("{") && !l.equals("-")) {
                String[] last = fields.get(fields.size() - 1);
                last[1] = last[1] + (last[0].equals("86") ? "" : "\n") + l;
            }
        }
        String acc = "";
        Long run = null;
        Tx cur = null;
        Pattern p61 = Pattern.compile("^(\\d{6})(\\d{4})?(R?[CD])([A-Z])?(\\d+(?:,\\d{0,2})?)");
        for (String[] f : fields) {
            if (r.tx.size() >= MAX_ROWS) break;
            switch (f[0]) {
                case "25": {
                    Matcher m = IBAN.matcher(f[1].replace(" ", ""));
                    acc = m.find() ? iban(m.group(1).replaceAll("[A-Z]{3}$", "").length() >= 15 ? stripCcy(m.group(1)) : m.group(1)) : iban(f[1].split("/")[f[1].split("/").length - 1]);
                    if (f[1].toUpperCase(Locale.ROOT).contains("INGB")) r.bank = "ING";
                    else if (f[1].toUpperCase(Locale.ROOT).contains("RABO")) r.bank = "Rabobank";
                    else if (f[1].toUpperCase(Locale.ROOT).contains("ABNA")) r.bank = "ABN AMRO";
                    break;
                }
                case "60F": case "60M": {
                    Matcher m = Pattern.compile("^([CD])(\\d{6})[A-Z]{3}([\\d,]+)").matcher(f[1].trim());
                    run = null;
                    if (m.find()) { try { long v = cents(m.group(3), ','); run = m.group(1).equals("D") ? -v : v; } catch (Exception ignored) { } }
                    break;
                }
                case "61": {
                    Matcher m = p61.matcher(f[1].trim());
                    if (!m.find()) { r.skipped++; cur = null; break; }
                    String ds = m.group(1);
                    String d = date("20" + ds);
                    if (d.isEmpty()) { r.skipped++; cur = null; break; }
                    long a = cents(m.group(5), ',');
                    String cd = m.group(3);
                    boolean debit = cd.equals("D") || cd.equals("RC"); // RC = terugboeking van een bijschrijving: af
                    cur = new Tx();
                    cur.acc = acc; cur.d = d; cur.a = debit ? -a : a;
                    if (run != null) { run += cur.a; cur.b = run; }
                    String rest = f[1].trim().substring(m.end());
                    if (rest.length() >= 4 && rest.charAt(0) == 'N') cur.ty = rest.substring(1, 4);
                    r.tx.add(cur);
                    break;
                }
                case "86": {
                    if (cur == null) break;
                    mt86(cur, f[1]);
                    cur = null;
                    break;
                }
                default: break;
            }
        }
        if (r.bank.isEmpty()) r.bank = "MT940";
        return r;
    }

    static String stripCcy(String s) { return s.endsWith("EUR") ? s.substring(0, s.length() - 3) : s; }

    static final Pattern MT_KEYS = Pattern.compile("/(CNTP|NAME|REMI|IBAN|BIC|EREF|MARF|CSID|ORDP|BENM|ADDR|TRTP|PURP|ULTD|ULTC|RTRN|BUSP|ISDT)/");

    /** Omschrijving van MT940 (:86:): gestructureerd (/CNTP/…/REMI/…) of vrije tekst. */
    static void mt86(Tx t, String raw) {
        String s = raw.trim();
        t.ds = clean(s);
        if (MT_KEYS.matcher(s).find()) {
            Map<String, String> f = new HashMap<>();
            Matcher m = MT_KEYS.matcher(s);
            List<int[]> pos = new ArrayList<>();
            List<String> keys = new ArrayList<>();
            while (m.find()) { pos.add(new int[]{m.start(), m.end()}); keys.add(m.group(1)); }
            for (int i = 0; i < keys.size(); i++) {
                int end = i + 1 < pos.size() ? pos.get(i + 1)[0] : s.length();
                String v = s.substring(pos.get(i)[1], Math.max(pos.get(i)[1], end));
                if (!f.containsKey(keys.get(i))) f.put(keys.get(i), v);
            }
            if (f.containsKey("CNTP")) {
                // ING: /CNTP/IBAN/BIC/NAAM/PLAATS/
                String[] p = f.get("CNTP").split("/", -1);
                if (p.length > 0) t.ci = iban(p[0]);
                if (p.length > 2) t.cp = clean(p[2]);
            }
            if (t.cp.isEmpty() && f.containsKey("NAME")) t.cp = clean(zonderSlash(f.get("NAME")));
            if (t.ci.isEmpty() && f.containsKey("IBAN")) t.ci = iban(zonderSlash(f.get("IBAN")));
            for (String k : new String[]{"ORDP", "BENM"}) if (t.cp.isEmpty() && f.containsKey(k)) {
                Matcher n = Pattern.compile("NAME/([^/]+)").matcher(f.get(k));
                if (n.find()) t.cp = clean(n.group(1));
            }
            if (f.containsKey("REMI")) {
                String rm = zonderSlash(f.get("REMI").replaceFirst("^USTD//", "").replaceFirst("^STRD/CUR/", ""));
                t.ds = clean(rm);
            }
            if (f.containsKey("TRTP")) t.ty = clean(zonderSlash(f.get("TRTP")));
        } else {
            abnLike(t, s);
        }
        if (t.cp.isEmpty()) t.cp = nameFrom(t.ds);
    }

    /** Zonder schuine strepen aan het eind. Zonder zoekpatroon: "/+$" kost kwadratisch veel tijd bij een lang veld vol strepen. */
    static String zonderSlash(String s) {
        int e = s.length();
        while (e > 0 && s.charAt(e - 1) == '/') e--;
        return s.substring(0, e);
    }

    static void abnLike(Tx t, String s) {
        String keepTy = t.ty;
        Tx x = new Tx();
        abnDescription(x, clean(s));
        t.cp = x.cp; t.ci = x.ci; t.ds = x.ds; t.ty = x.ty.isEmpty() ? keepTy : x.ty;
    }

    // ---------- CAMT (XML) ----------

    static Result camt(String text) throws Exception {
        // Geen DOCTYPE/entiteiten: zo kan een bestand geen andere bestanden of het netwerk laten lezen (XXE)
        String up = text.toUpperCase(Locale.ROOT); // het hele bestand: een DOCTYPE na lang commentaar telt ook
        if (up.contains("<!DOCTYPE") || up.contains("<!ENTITY")) throw new Exception("Dit XML-bestand wordt om veiligheidsredenen niet gelezen");
        javax.xml.parsers.DocumentBuilderFactory dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setExpandEntityReferences(false);
        try { dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Throwable ignored) { }
        try { dbf.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Throwable ignored) { }
        try { dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (Throwable ignored) { }
        try { dbf.setXIncludeAware(false); } catch (Throwable ignored) { }
        org.w3c.dom.Document doc;
        try { doc = dbf.newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader(text))); } // al gedecodeerd: de encoding-kop telt niet meer
        catch (Exception e) { throw new Exception("Dit XML-bestand is geen leesbaar CAMT-afschrift"); }
        Result r = new Result();
        r.format = "CAMT";
        List<org.w3c.dom.Element> stmts = new ArrayList<>();
        for (String n : new String[]{"Stmt", "Rpt", "Ntfctn"}) stmts.addAll(all(doc.getDocumentElement(), n));
        for (org.w3c.dom.Element st : stmts) {
            String acc = iban(text(first(st, "Acct", "Id", "IBAN")));
            if (r.bank.isEmpty()) r.bank = bankOf(acc);
            Long run = null;
            for (org.w3c.dom.Element bal : children(st, "Bal")) {
                String cd = text(first(bal, "Tp", "CdOrPrtry", "Cd"));
                if (cd.equals("OPBD") || cd.equals("PRCD")) {
                    try { long v = cents(text(first(bal, "Amt")), '.'); run = "DBIT".equals(text(first(bal, "CdtDbtInd"))) ? -v : v; } catch (Exception ignored) { }
                }
            }
            for (org.w3c.dom.Element n : children(st, "Ntry")) {
                if (r.tx.size() >= MAX_ROWS) break;
                try {
                    boolean debit = "DBIT".equals(text(first(n, "CdtDbtInd")));
                    String d = date(text(first(n, "BookgDt", "Dt")));
                    if (d.isEmpty()) d = date(text(first(n, "BookgDt", "DtTm")));
                    if (d.isEmpty()) d = date(text(first(n, "ValDt", "Dt")));
                    if (d.isEmpty()) { r.skipped++; continue; }
                    if ("BOOK".equals(text(first(n, "Sts"))) || text(first(n, "Sts")).isEmpty() || "BOOK".equals(text(first(n, "Sts", "Cd")))) { /* geboekt */ }
                    else if ("PDNG".equals(text(first(n, "Sts"))) || "PDNG".equals(text(first(n, "Sts", "Cd")))) { r.skipped++; continue; } // nog niet geboekt
                    String ntryInfo = text(first(n, "AddtlNtryInf"));
                    String ty = text(first(n, "BkTxCd", "Prtry", "Cd"));
                    if (ty.isEmpty()) ty = text(first(n, "BkTxCd", "Domn", "Fmly", "SubFmlyCd"));
                    List<org.w3c.dom.Element> txd = all(n, "TxDtls");
                    long total = Math.abs(cents(text(first(n, "Amt")), '.'));
                    if (txd.size() <= 1) {
                        Tx t = camtTx(txd.isEmpty() ? null : txd.get(0), debit);
                        t.a = debit ? -total : total;
                        finish(t, acc, d, ty, ntryInfo);
                        if (run != null) { run += t.a; t.b = run; }
                        r.tx.add(t);
                    } else {
                        // Verzamelboeking: elke deelbetaling apart (met een eigen bedrag), anders één boeking
                        long sum = 0; List<Tx> parts = new ArrayList<>();
                        for (org.w3c.dom.Element x : txd) {
                            String amt = text(first(x, "AmtDtls", "TxAmt", "Amt"));
                            if (amt.isEmpty()) amt = text(first(x, "Amt"));
                            if (amt.isEmpty()) { parts.clear(); break; }
                            Tx t = camtTx(x, debit);
                            long v = Math.abs(cents(amt, '.'));
                            t.a = debit ? -v : v; sum += v;
                            finish(t, acc, d, ty, ntryInfo);
                            parts.add(t);
                        }
                        if (parts.isEmpty() || sum != total) {
                            Tx t = camtTx(txd.get(0), debit);
                            t.a = debit ? -total : total;
                            finish(t, acc, d, ty, ntryInfo);
                            parts.clear(); parts.add(t);
                        }
                        for (Tx t : parts) { if (run != null) { run += t.a; t.b = run; } r.tx.add(t); }
                    }
                } catch (Exception e) { r.skipped++; }
            }
        }
        if (r.bank.isEmpty()) r.bank = "CAMT";
        return r;
    }

    static void finish(Tx t, String acc, String d, String ty, String ntryInfo) {
        t.acc = acc; t.d = d; t.ty = clean(ty);
        if (t.ds.isEmpty()) t.ds = clean(ntryInfo);
        if (t.cp.isEmpty()) t.cp = nameFrom(t.ds.isEmpty() ? ntryInfo : t.ds);
    }

    static Tx camtTx(org.w3c.dom.Element x, boolean debit) {
        Tx t = new Tx();
        if (x == null) return t;
        // Bij een afschrijving is de tegenpartij de ontvanger (Cdtr), bij een bijschrijving de betaler (Dbtr)
        String who = debit ? "Cdtr" : "Dbtr";
        String nm = text(first(x, "RltdPties", who, "Nm"));
        if (nm.isEmpty()) nm = text(first(x, "RltdPties", who, "Pty", "Nm"));
        if (nm.isEmpty()) nm = text(first(x, "RltdPties", debit ? "UltmtCdtr" : "UltmtDbtr", "Nm"));
        t.cp = clean(nm);
        t.ci = iban(text(first(x, "RltdPties", who + "Acct", "Id", "IBAN")));
        StringBuilder ds = new StringBuilder();
        for (org.w3c.dom.Element u : all(x, "Ustrd")) { if (ds.length() > 0) ds.append(' '); ds.append(text(u)); }
        if (ds.length() == 0) ds.append(text(first(x, "RmtInf", "Strd", "CdtrRefInf", "Ref")));
        if (ds.length() == 0) ds.append(text(first(x, "AddtlTxInf")));
        t.ds = clean(ds.toString());
        return t;
    }

    static String bankOf(String iban) {
        if (iban.length() < 8) return "";
        String b = iban.substring(4, 8);
        switch (b) {
            case "INGB": return "ING";
            case "RABO": return "Rabobank";
            case "ABNA": return "ABN AMRO";
            case "ASNB": return "ASN Bank";
            case "SNSB": return "SNS";
            case "RBRB": return "RegioBank";
            case "TRIO": return "Triodos";
            case "KNAB": return "Knab";
            case "BUNQ": return "bunq";
            default: return "";
        }
    }

    // DOM-hulpjes (op lokale naam, zonder namespace: CAMT-versies verschillen)
    static List<org.w3c.dom.Element> children(org.w3c.dom.Element e, String name) {
        List<org.w3c.dom.Element> l = new ArrayList<>();
        if (e == null) return l;
        for (org.w3c.dom.Node n = e.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof org.w3c.dom.Element && name.equals(local(n))) l.add((org.w3c.dom.Element) n);
        return l;
    }

    static List<org.w3c.dom.Element> all(org.w3c.dom.Element e, String name) {
        List<org.w3c.dom.Element> l = new ArrayList<>();
        if (e == null) return l;
        org.w3c.dom.NodeList nl = e.getElementsByTagNameNS("*", name);
        for (int i = 0; i < nl.getLength(); i++) l.add((org.w3c.dom.Element) nl.item(i));
        if (l.isEmpty()) { nl = e.getElementsByTagName(name); for (int i = 0; i < nl.getLength(); i++) l.add((org.w3c.dom.Element) nl.item(i)); }
        return l;
    }

    static String local(org.w3c.dom.Node n) { String l = n.getLocalName(); return l == null ? n.getNodeName() : l; }

    static org.w3c.dom.Element first(org.w3c.dom.Element e, String... path) {
        org.w3c.dom.Element cur = e;
        for (String p : path) {
            if (cur == null) return null;
            List<org.w3c.dom.Element> c = children(cur, p);
            cur = c.isEmpty() ? null : c.get(0);
        }
        return cur;
    }

    static String text(org.w3c.dom.Element e) { return e == null ? "" : clean(e.getTextContent()); }

    // ---------- samenvoegen met wat er al is ----------

    /** Vaste id van een boeking: rekening, datum, bedrag en het hoeveelste gelijke bedrag op die dag in dit bestand.
     *  Zo valt een boeking die in twee overlappende afschriften staat (ook CSV naast CAMT) maar één keer mee. */
    static String id(String acc, String d, long a, int n) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((acc + "|" + d + "|" + a + "|" + n).getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 9; i++) b.append(String.format(Locale.ROOT, "%02x", h[i] & 0xff));
            return b.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Voegt de boekingen toe aan store (in de plaats). Geeft {nieuw, dubbel, overgeslagen, bank, van, tot, rekeningen}. */
    static org.json.JSONObject merge(org.json.JSONObject store, Result r, long now) throws Exception {
        org.json.JSONArray tx = store.optJSONArray("tx");
        if (tx == null) { tx = new org.json.JSONArray(); store.put("tx", tx); }
        java.util.HashSet<String> have = new java.util.HashSet<>();
        for (int i = 0; i < tx.length(); i++) { org.json.JSONObject o = tx.optJSONObject(i); if (o != null) have.add(o.optString("id")); }
        Map<String, Integer> seen = new HashMap<>();
        int added = 0, dup = 0;
        String from = "", to = "";
        java.util.TreeSet<String> accs = new java.util.TreeSet<>();
        for (Tx t : r.tx) {
            String acc = t.acc.isEmpty() ? "onbekend" : t.acc;
            String k = acc + "|" + t.d + "|" + t.a;
            Integer n = seen.get(k);
            n = n == null ? 0 : n + 1;
            seen.put(k, n);
            String id = id(acc, t.d, t.a, n);
            if (from.isEmpty() || t.d.compareTo(from) < 0) from = t.d;
            if (to.isEmpty() || t.d.compareTo(to) > 0) to = t.d;
            accs.add(acc);
            if (!have.add(id)) { dup++; continue; }
            if (tx.length() >= MAX_ROWS) throw new Exception("Er staan te veel boekingen in Bankzaken (max " + MAX_ROWS + ")");
            org.json.JSONObject o = new org.json.JSONObject().put("id", id).put("acc", acc).put("d", t.d).put("a", t.a)
                    .put("cp", t.cp).put("ci", t.ci).put("ds", t.ds).put("ty", t.ty).put("src", r.bank).put("in", now);
            if (t.b != null) o.put("b", t.b.longValue());
            tx.put(o);
            added++;
        }
        org.json.JSONArray imp = store.optJSONArray("imports");
        if (imp == null) { imp = new org.json.JSONArray(); store.put("imports", imp); }
        org.json.JSONObject sum = new org.json.JSONObject().put("t", now).put("bank", r.bank).put("format", r.format).put("nieuw", added)
                .put("dubbel", dup).put("overgeslagen", r.skipped).put("van", from).put("tot", to).put("rekeningen", new org.json.JSONArray(accs));
        imp.put(sum);
        while (imp.length() > 200) imp.remove(0);
        return sum;
    }
}
