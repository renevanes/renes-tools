package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Betalen: wat staat er op een rekening? Uit de tekst (van de PDF of de tekstherkenning op de telefoon) de ontvanger,
 * het IBAN, het bedrag, het betalingskenmerk en de uiterste betaaldatum; uit een QR-code een betaallink (iDEAL e.d.) of
 * de gegevens van een SEPA-QR (EPC). Zuiver (getest in BetalenTest): geen Android, geen netwerk. Alles wat hier uit komt
 * is een voorstel: Rene controleert het altijd zelf op het scherm en daarna nog eens bij zijn bank.
 */
final class BetalenHerken {

    private BetalenHerken() { }

    static final int MAX_TEKST = 40_000, MAX_QR = 1200, MAX_LINK = 600;

    // ---------------------------------------------------------------- tekst van een rekening

    private static final Pattern TNV = Pattern.compile("(?im)(?<![a-z])(?:t\\.?\\s?n\\.?\\s?v\\.?|ten name van|ter attentie van rekeninghouder|naam rekeninghouder|rekeninghouder|begunstigde|naam ontvanger|account holder|beneficiary)(?![a-z])[ \\t]{0,10}[:.]?[ \\t]{0,10}([^\\n]{2,90})");
    private static final Pattern KENMERK_LABEL = Pattern.compile("(?i)(?<![a-z])(?:betalingskenmerk|betaalkenmerk|betalings\\s?kenmerk|payment reference|kenmerk)(?![a-z])[^0-9A-Z\\n]{0,20}(?:\\n\\s*)?((?:\\d[ ]?){7,20}|RF\\d{2}(?:[ ]?[A-Z0-9]){1,21})");
    private static final Pattern ZESTIEN = Pattern.compile("(?<![0-9])((?:\\d{4}[ ]?){3}\\d{4})(?![0-9])");
    private static final Pattern RF = Pattern.compile("(?<![A-Z0-9])(RF\\d{2}(?:[ ]?[A-Z0-9]){1,21})(?![A-Z0-9])");

    /**
     * {naam, iban, ibans[], bedrag ("123,45"), kenmerk, omschrijving, vervaldatum (jjjj-mm-dd), nummer}; wat niet gevonden
     * is ontbreekt. eigen = Rene's eigen rekeningnummers (die zijn nooit de ontvanger).
     */
    static JSONObject herken(String tekst, Collection<String> eigen) {
        JSONObject uit = new JSONObject();
        try {
            if (tekst == null) return uit;
            String t = tekst.length() > MAX_TEKST ? tekst.substring(0, MAX_TEKST) : tekst;
            t = t.replace(' ', ' ').replace('\t', ' ').replace("\r", "");
            JSONArray ibans = new JSONArray();
            for (String i : FacturenHerken.iban(t)) if (!isEigen(i, eigen)) ibans.put(i);
            if (ibans.length() > 0) uit.put("iban", ibans.getString(0));
            uit.put("ibans", ibans);
            JSONObject f = FacturenDossier.herken(t);
            String bedrag = f.optString("bedrag");
            if (!bedrag.isEmpty() && bedragOk(bedrag)) uit.put("bedrag", bedrag);
            if (!f.optString("vervaldatum").isEmpty()) uit.put("vervaldatum", f.optString("vervaldatum"));
            if (!f.optString("nummer").isEmpty()) uit.put("nummer", f.optString("nummer"));
            String k = kenmerk(t);
            if (!k.isEmpty()) uit.put("kenmerk", k);
            else if (!f.optString("nummer").isEmpty()) uit.put("omschrijving", "Factuur " + f.optString("nummer"));
            String naam = naam(t);
            if (!naam.isEmpty()) uit.put("naam", naam);
        } catch (Exception ignored) { }
        return uit;
    }

    static boolean isEigen(String iban, Collection<String> eigen) {
        if (eigen == null) return false;
        String n = FacturenHerken.nummer(iban);
        for (String e : eigen) if (n.equals(FacturenHerken.nummer(e))) return true;
        return false;
    }

    /** Groter dan nul en kleiner dan een miljoen (een herkend bedrag daarbuiten is vrijwel zeker een leesfout). */
    static boolean bedragOk(String nl) {
        long c = centen(nl);
        return c > 0 && c < 100_000_000L;
    }

    /** "1234,56" → 123456; ongeldig = -1. */
    static long centen(String nl) {
        if (nl == null || !nl.matches("\\d{1,9},\\d{2}")) return -1;
        return Long.parseLong(nl.replace(",", ""));
    }

    /** De naam achter "t.n.v." (of een ander woord voor de rekeninghouder), zonder wat er op dezelfde regel nog achter staat. */
    static String naam(String t) {
        Matcher m = TNV.matcher(t);
        while (m.find()) {
            String n = m.group(1);
            n = n.split("(?i)\\s{3,}|\\s+(?:IBAN|BIC|SWIFT|KvK|BTW|o\\.?v\\.?v\\.?|onder vermelding)\\b", 2)[0];
            n = netjes(n, 70).replaceAll("^[\\s:.,;-]+|[\\s:,;-]+$", "");
            if (n.length() >= 2 && !FacturenHerken.ibanOk(n) && n.matches(".*\\p{L}.*")) return n;
        }
        return "";
    }

    /** Het betalingskenmerk: wat zo heet, anders een geldig kenmerk van 16 cijfers of een RF-kenmerk. Zonder spaties. */
    static String kenmerk(String t) {
        Matcher m = KENMERK_LABEL.matcher(t);
        while (m.find()) {
            String k = m.group(1).replace(" ", "").toUpperCase(Locale.ROOT);
            if (k.startsWith("RF") ? rfOk(k) : k.matches("\\d{7}|\\d{9,14}|\\d{16}") && (k.length() != 16 || kenmerkOk(k))) return k;
        }
        // Zonder het woord "kenmerk" erbij: alleen zoals het op een rekening gedrukt staat, in vier groepjes van vier
        m = ZESTIEN.matcher(t);
        while (m.find()) { String k = m.group(1).replace(" ", ""); if (m.group(1).length() == 19 && kenmerkOk(k) && !k.matches("0{16}")) return k; }
        m = RF.matcher(t);
        while (m.find()) { String k = m.group(1).replace(" ", ""); if (rfOk(k)) return k; }
        return "";
    }

    /** Nederlands betalingskenmerk van 16 cijfers: het eerste cijfer is het controlecijfer over de 15 erna (gewogen 11-proef). */
    static boolean kenmerkOk(String k) {
        if (k == null || !k.matches("\\d{16}")) return false;
        int[] gewicht = {2, 4, 8, 5, 10, 9, 7, 3, 6, 1};
        int som = 0;
        for (int i = 0; i < 15; i++) som += (k.charAt(15 - i) - '0') * gewicht[i % 10];
        int c = 11 - som % 11;
        if (c == 10) c = 1; else if (c == 11) c = 0;
        return c == k.charAt(0) - '0';
    }

    /** Europees kenmerk (ISO 11649): RF + twee controlecijfers + hooguit 21 tekens. */
    static boolean rfOk(String k) {
        return k != null && k.matches("RF\\d{2}[A-Z0-9]{1,21}") && FacturenHerken.mod97(k.substring(4) + k.substring(0, 4));
    }

    /** Eén regel zonder stuurtekens en dubbele spaties, hooguit max tekens. */
    static String netjes(String s, int max) {
        if (s == null) return "";
        String v = s.replaceAll("[\\p{Cntrl}\\u2028\\u2029]+", " ").replaceAll("\\s{2,}", " ").trim();
        return v.length() > max ? v.substring(0, max).trim() : v;
    }

    // ---------------------------------------------------------------- QR-code op een rekening

    /**
     * Wat een QR-code op een rekening betekent: {soort:"epc", naam, iban, bedrag, kenmerk, omschrijving} voor een SEPA-QR,
     * {soort:"link", link, host, bekend} voor een betaallink, of null als het iets anders is. bekendeHosts = de domeinen
     * van betaaldiensten die Tools kent (uit bank-banken.json); een onbekend domein mag, maar krijgt een waarschuwing.
     */
    static JSONObject qr(String inhoud, Collection<String> bekendeHosts) {
        if (inhoud == null) return null;
        String s = inhoud.trim();
        if (s.isEmpty() || s.length() > MAX_QR) return null;
        try {
            JSONObject e = epc(s);
            if (e != null) return e.put("soort", "epc");
            String link = link(s);
            if (link == null) return null;
            String host = Facturen.host(link);
            return new JSONObject().put("soort", "link").put("link", link).put("host", host).put("bekend", hostBekend(host, bekendeHosts));
        } catch (Exception x) { return null; }
    }

    /** SEPA-QR (EPC069-12): BCD / versie / tekenset / SCT / BIC / naam / IBAN / EUR12.34 / doel / kenmerk / omschrijving. */
    static JSONObject epc(String s) throws Exception {
        String[] r = s.replace("\r", "").split("\n", -1);
        if (r.length < 7 || !"BCD".equals(r[0].trim()) || !r[1].trim().matches("00[12]") || !"SCT".equals(r[3].trim())) return null;
        String iban = FacturenHerken.nummer(r[6]);
        if (!FacturenHerken.ibanOk(iban)) return null;
        JSONObject o = new JSONObject().put("iban", iban);
        String naam = netjes(r[5], 70);
        if (!naam.isEmpty()) o.put("naam", naam);
        if (r.length > 7) {
            Matcher m = Pattern.compile("EUR(\\d{1,9})(?:\\.(\\d{1,2}))?").matcher(r[7].trim());
            if (m.matches()) {
                String c = m.group(2) == null ? "00" : m.group(2).length() == 1 ? m.group(2) + "0" : m.group(2);
                String b = Long.parseLong(m.group(1)) + "," + c;
                if (bedragOk(b)) o.put("bedrag", b);
            }
        }
        String ref = r.length > 9 ? r[9].replace(" ", "").toUpperCase(Locale.ROOT) : "";
        if (!ref.matches("[A-Z0-9]{1,35}") || ref.startsWith("RF") && !rfOk(ref)) ref = ""; // een kenmerk dat niet klopt vervalt; de rest blijft
        if (!ref.isEmpty()) o.put("kenmerk", ref);
        String tekst = r.length > 10 ? netjes(r[10], 140) : "";
        if (!tekst.isEmpty() && ref.isEmpty()) o.put("omschrijving", tekst);
        return o;
    }

    /** Een betaallink: alleen https, met een gewone domeinnaam (geen IP-adres, geen gebruikersnaam of poort erin). Anders null. */
    static String link(String s) {
        if (s == null) return null;
        String u = s.trim();
        if (u.length() > MAX_LINK || !u.regionMatches(true, 0, "https://", 0, 8)) return null;
        for (int i = 0; i < u.length(); i++) { // alleen gewone zichtbare tekens: geen spaties, regeleinden, stuurtekens of tekens buiten ASCII
            char c = u.charAt(i);
            if (c < 0x21 || c > 0x7e || "\"<>\\^`{|}".indexOf(c) >= 0) return null;
        }
        int eind = u.length();
        for (char c : new char[]{'/', '?', '#'}) { int i = u.indexOf(c, 8); if (i >= 0 && i < eind) eind = i; }
        String gezag = u.substring(8, eind).toLowerCase(Locale.ROOT);
        if (!gezag.matches("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*\\.[a-z]{2,24}")) return null;
        return "https://" + gezag + u.substring(eind);
    }

    static boolean hostBekend(String host, Collection<String> bekend) {
        if (host == null || host.isEmpty() || bekend == null) return false;
        for (String b : bekend) if (b != null && !b.isEmpty() && (host.equals(b) || host.endsWith("." + b))) return true;
        return false;
    }

    static List<String> lijst(JSONArray a) {
        List<String> l = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) { String s = a.optString(i); if (!s.isEmpty()) l.add(s); }
        return l;
    }
}
