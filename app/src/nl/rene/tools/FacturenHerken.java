package nl.rene.tools;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Welke leverancier hoort bij een factuur uit de mail? Een mailbron (Gmail) is alleen de bron: elke leverancier die erin
 * zit, krijgt een eigen rij (soort "uitmail") met een eigen naam, map en opslag.
 *
 * Herkennen in lagen, de eerste die past wint: eigen regels (adres of domein), het btw- of KvK-nummer uit de PDF, het
 * IBAN uit de PDF, het domein van de afzender (subdomeinen tellen als het hoofddomein). Bij platforms (Moneybird, Stripe …),
 * gratis mailadressen en doorgestuurde mail zegt het domein niets: dan de naam of de oorspronkelijke afzender. Een nummer
 * beslist alleen als de afzender zelf niets anders zegt; anders wordt het een voorstel ("Hoort dit bij …?").
 * Past niets, dan een nieuwe leverancier.
 *
 * Alles op de telefoon: geen opzoeken bij de KvK of VIES. Rene's eigen nummers en adressen tellen nooit, en een nummer dat
 * bij drie of meer afzenders staat (van een platform of incassobureau) ook niet. Zonder Android: getest in FacturenHerkenTest.
 */
final class FacturenHerken {
    private FacturenHerken() { }

    static final String SOORT = "uitmail";
    static final String NIET_HERKEND = "niet-herkend", NIET_HERKEND_NAAM = "Niet herkend";
    static final String[] NUMMERS = {"btw", "kvk", "iban"};
    static final int MAX = 10;
    static final int GEDEELD = 3;

    static File file(Context c) { return new File(Facturen.dir(c), "herkennen.json"); }

    /** Wat de app weet: platforms, gratis maildomeinen, Rene's eigen nummers en adressen, en nummers die bij veel afzenders staan. */
    static final class Kennis {
        final Set<String> platforms = new HashSet<>(), freemail = new HashSet<>();
        final Set<String> eigen = new HashSet<>(), adressen = new HashSet<>();
        final Map<String, Set<String>> bij = new HashMap<>();
        final Set<String> gedeeld = new HashSet<>();

        /** Een factuur meetellen: staat een nummer bij drie of meer afzenders, dan zegt het niets over de leverancier. */
        void tel(JSONObject km) {
            String wie = sleutel(km);
            if (wie.isEmpty()) return;
            for (String s : NUMMERS) for (String n : lijst(km.optJSONArray(s))) {
                Set<String> w = bij.get(n);
                if (w == null) { w = new HashSet<>(); bij.put(n, w); }
                w.add(wie);
                if (w.size() >= GEDEELD) gedeeld.add(n);
            }
        }

        boolean telt(String nummer) { return !nummer.isEmpty() && !eigen.contains(nummer) && !gedeeld.contains(nummer); }
    }

    static Kennis kennis(JSONObject asset, JSONObject eigen, JSONObject items) {
        Kennis k = new Kennis();
        if (asset != null) {
            for (String s : lijst(asset.optJSONArray("mailPlatforms"))) k.platforms.add(s.toLowerCase(Locale.ROOT));
            for (String s : lijst(asset.optJSONArray("freemail"))) k.freemail.add(s.toLowerCase(Locale.ROOT));
        }
        if (eigen != null) {
            for (String n : lijst(eigen.optJSONArray("nummers"))) k.eigen.add(nummer(n));
            for (String a : lijst(eigen.optJSONArray("adressen"))) { String x = adres(a); if (!x.isEmpty()) k.adressen.add(x); }
        }
        if (items != null) for (Iterator<String> it = items.keys(); it.hasNext(); ) {
            JSONObject s = items.optJSONObject(it.next());
            JSONObject km = s == null ? null : s.optJSONObject("kenmerken");
            if (km != null) k.tel(km);
        }
        return k;
    }

    /** Alles uit de app: de lijsten (assets), herkennen.json, de adressen van de koppelingen en het dossier. */
    static Kennis kennis(Context c) {
        JSONObject asset = asset(c);
        JSONObject eigen = eigen(c);
        JSONArray adr = eigen.optJSONArray("adressen");
        if (adr == null) adr = new JSONArray();
        android.content.SharedPreferences p = Facturen.prefs(c);
        for (String k : new String[]{"gdGebruiker", "ecAdres", "ecGebruiker", "smtpVan"}) adr.put(p.getString(k, ""));
        try { eigen.put("adressen", adr); } catch (Exception ignored) { }
        return kennis(asset, eigen, FacturenDossier.alle(c));
    }

    private static JSONObject asset(Context c) {
        try (InputStream in = c.getAssets().open(Facturen.ASSET)) { return new JSONObject(new String(Facturen.lees(in, 1_000_000), StandardCharsets.UTF_8)); }
        catch (Exception e) { return null; }
    }

    /** De maildomeinen van platforms (Moneybird, Stripe e.d.): een adres daar zegt niet wie de leverancier is. */
    static Set<String> platforms(Context c) { return kennis(asset(c), null, null).platforms; }

    /** Rene's eigen KvK-, btw- en IBAN-nummers en mailadressen: {nummers: [], adressen: []}. */
    static JSONObject eigen(Context c) {
        synchronized (Facturen.LOCK) {
            try { return new JSONObject(JsonFileStore.read(file(c), t -> new JSONObject(t))); } catch (Exception e) { return new JSONObject(); }
        }
    }

    static void bewaarEigen(Context c, JSONObject in) throws Exception {
        JSONObject o = eigenNormaal(in);
        synchronized (Facturen.LOCK) {
            File d = Facturen.dir(c);
            if (!d.isDirectory() && !d.mkdirs()) throw new Exception("Opslagmap maken lukt niet");
            JsonFileStore.write(file(c), o.toString(), t -> new JSONObject(t));
        }
    }

    // ================= afzender =================

    private static final Pattern HOEK = Pattern.compile("<\\s*(?:mailto:)?([^<>\\s]+@[^<>\\s]+)\\s*>");
    private static final Pattern LOS = Pattern.compile("([A-Za-z0-9._%+'=-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})");

    /** Het mailadres uit "Naam <adres>" (of alleen een adres), in kleine letters; "" als er geen is. */
    static String adres(String van) {
        if (van == null) return "";
        // De kop komt van buiten (elke afzender): begrensd, zodat HOEK/LOS niet traag worden of de stack opmaken. Het adres staat achteraan.
        if (van.length() > 1000) van = van.substring(van.length() - 1000);
        Matcher m = HOEK.matcher(van);
        String a = m.find() ? m.group(1) : null;
        if (a == null) { Matcher l = LOS.matcher(van); a = l.find() ? l.group(1) : ""; }
        a = a.trim().toLowerCase(Locale.ROOT).replaceAll("^mailto:", "");
        return a.length() <= 254 && a.matches("[^\\s@<>\"]+@[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}") ? a : "";
    }

    /** De naam uit "Naam <adres>"; "" als er geen naam staat (of de naam zelf een adres is). */
    static String naam(String van) {
        if (van == null) return "";
        int i = van.indexOf('<');
        if (i <= 0) return "";
        String n = van.substring(0, i).trim().replaceAll("^[\"']+|[\"']+$", "").replace("\\\"", "").trim();
        return n.contains("@") ? "" : n;
    }

    private static final Pattern VIA = Pattern.compile("(?i)\\s*[(\\[]?\\s*\\bvia\\s+[^)\\]]*[)\\]]?\\s*$");
    private static final Pattern STAART = Pattern.compile("(?i)(?:\\s*[-|–:,]\\s*|\\s+)(?:facturen|factuur|facturatie|facturering|invoices?|billing|administratie|debiteuren(?:administratie)?|klantenservice|customer service|no-?reply|noreply|accounts?(?: receivable)?)\\s*$");
    private static final Pattern KOP = Pattern.compile("(?i)^\\s*(?:facturen|factuur|facturatie|invoices?|billing)\\s*[-|–:]\\s*");
    private static final Pattern ALGEMEEN = Pattern.compile("(?i)(?:no[- ]?reply|do[- ]?not[- ]?reply|donotreply|facturen|factuur|facturatie|facturering|invoices?|billing|billing team|"
            + "administratie|debiteuren|accounts?|accounting|info|support|klantenservice|customer service|service|notifications?|notificaties|mailer|postmaster|team|"
            + "nieuwsbrief|newsletter|mail|e-?mail|online|mijn account|uw factuur|je factuur|your invoice|receipts?|bonnetje|ontvangstbewijs)");

    /** Een nette naam van de afzender: zonder "via Moneybird", zonder "- Facturen", en leeg als het alleen "noreply" of "Facturen" is. */
    static String schoneNaam(String n) {
        if (n == null) return "";
        if (n.length() > 1000) n = n.substring(0, 1000); // naam uit de mailkop (van buiten): begrensd, anders worden VIA/STAART traag; er blijven er hooguit 60 over
        String s = n.replaceAll("\\s+", " ").trim();
        for (int i = 0; i < 3; i++) {
            String v = KOP.matcher(STAART.matcher(VIA.matcher(s).replaceAll("")).replaceAll("")).replaceAll("").trim();
            if (v.equals(s)) break;
            s = v;
        }
        s = s.replaceAll("\\s*[,;]+\\s*", " ").replaceAll("^[\"'\\s]+|[\"'\\s]+$", "");
        if (s.isEmpty() || ALGEMEEN.matcher(s).matches() || !s.matches(".*\\p{L}.*")) return "";
        return s.length() > 60 ? s.substring(0, 60).trim() : s;
    }

    /** Het hoofddomein van een mailadres: facturen@mail.kpn.com → kpn.com. */
    static String domein(String adres) {
        int at = adres == null ? -1 : adres.lastIndexOf('@');
        return at < 0 ? "" : Facturen.domein("https://" + adres.substring(at + 1));
    }

    private static final Pattern DOOR = Pattern.compile("(?im)(?:(?<!-)-{2,}\\s*(?:forwarded message|doorgestuurd bericht|oorspronkelijk bericht|original message|weitergeleitete nachricht|message transf[ée]r[ée])\\s*-{2,}"
            + "|begin forwarded message:|begin doorgestuurd bericht:|^[ \\t]*_{8,}[ \\t]*$)");
    private static final Pattern VAN = Pattern.compile("(?im)^[\\s>*]*(?:from|van|von|de)\\s*\\*?\\s*:\\s*\\*?\\s*(.+)$");
    private static final Pattern DOORGESTUURD = Pattern.compile("(?i)^\\s*(?:fwd?|doorst|tr|wg|fw)\\s*:");

    /** De oorspronkelijke afzender in een doorgestuurde mail (de "Van:"-regel na "Doorgestuurd bericht"), of "". */
    static String doorgestuurdVan(String tekst) {
        if (tekst == null || tekst.isEmpty()) return "";
        Matcher m = DOOR.matcher(tekst);
        if (!m.find()) return "";
        String na = tekst.substring(m.end(), Math.min(tekst.length(), m.end() + 2000));
        Matcher v = VAN.matcher(na);
        if (!v.find()) return "";
        String s = v.group(1).replaceAll("\\[mailto:([^\\]]+)\\]", "<$1>").replace("*", "").trim();
        if (adres(s).isEmpty()) return "";
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    // ================= nummers uit de PDF =================

    /** Hoofdletters en cijfers, zonder spaties, punten en streepjes. */
    static String nummer(String s) { return s == null ? "" : s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", ""); }

    private static final String KLANT = "(?i).*\\b(?:(?:uw|jouw|je|your)\\s+(?:btw|kvk|vat|iban|rekening|account|klant|coc|bedrijf|gegevens)|klant(?:gegevens|nummer)?|afnemer|customer|client|debiteur|bill(?:ed)? to|invoice to|factuuradres)\\b[^\\n]{0,40}$";
    private static final String INCASSO = "(?i).*\\b(?:afgeschreven|afschrijven|incasso|ge[iï]ncasseerd|incasseren|machtiging|van (?:uw |je )?rekening|from (?:your )?account|direct debit|debited)\\b[^\\n]{0,60}$";

    /** Staat het nummer bij de klant (Rene zelf) in plaats van bij de leverancier? Kijkt naar het stuk ervoor op dezelfde regel. */
    private static boolean bijKlant(String t, int start, boolean iban) {
        int b = t.lastIndexOf('\n', Math.max(0, start - 1));
        String voor = t.substring(Math.max(b + 1, start - 60), start);
        return voor.matches(KLANT) || iban && voor.matches(INCASSO);
    }

    private static final Pattern BTW_NL = Pattern.compile("(?i)(?<![A-Z0-9])NL[ .]?((?:\\d[ .]?){8}\\d)[ .]?B[ .]?(\\d[ .]?\\d)(?![0-9])");
    private static final Pattern BTW_BE = Pattern.compile("(?i)(?<![A-Z0-9])BE[ .]?([01](?:[ .]?\\d){9})(?![0-9])");
    private static final Pattern BTW_EU = Pattern.compile("(?i)(?<![a-z])(?:btw|vat|ust|mwst|tva|iva|moms)[^\\n]{0,30}?(?<![A-Z0-9])((?:AT|BG|CY|CZ|DE|DK|EE|EL|ES|FI|FR|GB|HR|HU|IE|IT|LT|LU|LV|MT|PL|PT|RO|SE|SI|SK|CH|NO)[ .]?[0-9A-Z](?:[ .]?[0-9A-Z]){7,12})(?![0-9A-Z])");
    private static final Pattern KVK = Pattern.compile("(?i)(?<![a-z])(?:kvk|k\\.v\\.k\\.?|kamer van koophandel|chamber of commerce|coc|handelsregister)(?![a-z])[^0-9\\n]{0,25}((?:\\d ?){7}\\d)(?![0-9])");
    private static final Pattern IBAN_BEGIN = Pattern.compile("(?i)(?<![A-Z0-9])([A-Z]{2})(\\d{2})");
    private static final Map<String, Integer> IBAN_LENGTE = new HashMap<>();
    static {
        String[] l = {"AD24", "AT20", "BA20", "BE16", "BG22", "CH21", "CY28", "CZ24", "DE22", "DK18", "EE20", "ES24", "FI18", "FO18", "FR27", "GB22", "GI23", "GL18", "GR27",
                "HR21", "HU28", "IE22", "IS26", "IT27", "LI21", "LT20", "LU20", "LV21", "MC27", "MT31", "NL18", "NO15", "PL28", "PT25", "RO24", "SE24", "SI19", "SK24", "SM27"};
        for (String s : l) IBAN_LENGTE.put(s.substring(0, 2), Integer.parseInt(s.substring(2)));
    }

    static boolean mod97(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (ch >= 'A' && ch <= 'Z') b.append(ch - 'A' + 10);
            else if (ch >= '0' && ch <= '9') b.append(ch);
            else return false;
        }
        return b.length() > 0 && new BigInteger(b.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }

    static boolean ibanOk(String s) {
        String n = nummer(s);
        Integer len = n.length() >= 4 ? IBAN_LENGTE.get(n.substring(0, 2)) : null;
        return len != null && n.length() == len && n.substring(2, 4).matches("\\d\\d") && mod97(n.substring(4) + n.substring(0, 4));
    }

    /** Nederlands btw-nummer: het oude (11-proef op het RSIN) of het btw-id van na 2020 (mod 97). */
    static boolean btwNlOk(String n) {
        if (!n.matches("NL\\d{9}B\\d{2}")) return false;
        int som = 0;
        for (int i = 0; i < 8; i++) som += (n.charAt(2 + i) - '0') * (9 - i);
        som -= n.charAt(10) - '0';
        return som % 11 == 0 || mod97(n);
    }

    static boolean btwBeOk(String n) {
        if (!n.matches("BE[01]\\d{9}")) return false;
        long eerst = Long.parseLong(n.substring(2, 10));
        return 97 - eerst % 97 == Integer.parseInt(n.substring(10));
    }

    static boolean btwOk(String n) {
        if (n.startsWith("NL")) return btwNlOk(n);
        if (n.startsWith("BE")) return btwBeOk(n);
        return n.matches("[A-Z]{2}[0-9A-Z]{8,13}") && n.replaceAll("[^0-9]", "").length() >= 7;
    }

    static List<String> btw(String t) {
        LinkedHashSet<String> uit = new LinkedHashSet<>();
        if (t == null) return new ArrayList<>(uit);
        Matcher m = BTW_NL.matcher(t);
        while (m.find() && uit.size() < 5) { String n = "NL" + nummer(m.group(1)) + "B" + nummer(m.group(2)); if (btwNlOk(n) && !bijKlant(t, m.start(), false)) uit.add(n); }
        m = BTW_BE.matcher(t);
        while (m.find() && uit.size() < 5) { String n = "BE" + nummer(m.group(1)); if (btwBeOk(n) && !bijKlant(t, m.start(), false)) uit.add(n); }
        m = BTW_EU.matcher(t);
        while (m.find() && uit.size() < 5) { String n = nummer(m.group(1)); if (btwOk(n) && !ibanOk(n) && !bijKlant(t, m.start(1), false)) uit.add(n); }
        return new ArrayList<>(uit);
    }

    static List<String> kvk(String t) {
        LinkedHashSet<String> uit = new LinkedHashSet<>();
        if (t == null) return new ArrayList<>(uit);
        Matcher m = KVK.matcher(t);
        while (m.find() && uit.size() < 5) { String n = nummer(m.group(1)); if (n.length() == 8 && !bijKlant(t, m.start(), false)) uit.add(n); }
        return new ArrayList<>(uit);
    }

    static List<String> iban(String t) {
        LinkedHashSet<String> uit = new LinkedHashSet<>();
        if (t == null) return new ArrayList<>(uit);
        Matcher m = IBAN_BEGIN.matcher(t);
        while (m.find() && uit.size() < 5) {
            String land = m.group(1).toUpperCase(Locale.ROOT);
            Integer len = IBAN_LENGTE.get(land);
            if (len == null) continue;
            StringBuilder b = new StringBuilder(land).append(m.group(2));
            int i = m.end();
            while (i < t.length() && b.length() < len) {
                char ch = t.charAt(i);
                if (Character.isLetterOrDigit(ch) && ch < 128) { b.append(Character.toUpperCase(ch)); i++; }
                else if (ch == ' ' && i + 1 < t.length() && Character.isLetterOrDigit(t.charAt(i + 1))) i++;
                else break;
            }
            if (b.length() != len || i < t.length() && Character.isLetterOrDigit(t.charAt(i))) continue;
            String n = b.toString();
            if (ibanOk(n) && !bijKlant(t, m.start(), true)) uit.add(n);
        }
        return new ArrayList<>(uit);
    }

    // ================= kenmerken van één factuur =================

    /**
     * Wat zegt deze factuur over wie hem stuurde? {adres, domein, naam, soort, doorgestuurd, btw[], kvk[], iban[]}.
     * soort: domein (het domein zegt wie het is), platform (Moneybird e.d.: de naam zegt het), freemail (gmail.com e.d.:
     * het adres zegt het) of eigen (Rene's eigen adres: alleen de nummers zeggen iets).
     * bron = {afzender, antwoordAan, doorgestuurdVan, onderwerp} uit de mail.
     */
    static JSONObject kenmerken(JSONObject bron, String pdfTekst, Kennis k) {
        JSONObject km = new JSONObject();
        try {
            String van = bron == null ? "" : bron.optString("afzender", "");
            String adres = adres(van);
            String door = bron == null ? "" : bron.optString("doorgestuurdVan", "");
            boolean doorgestuurd = false;
            if (!adres(door).isEmpty() && (k.adressen.contains(adres) || DOORGESTUURD.matcher(bron.optString("onderwerp", "")).find())) {
                van = door; adres = adres(door); doorgestuurd = true;
            }
            String domein = domein(adres), naam = schoneNaam(naam(van)), soort;
            boolean platform = false;
            if (adres.isEmpty() || k.adressen.contains(adres)) { soort = "eigen"; naam = ""; }
            else if (k.platforms.contains(domein)) {
                soort = "platform"; platform = true;
                String antw = adres(bron == null ? "" : bron.optString("antwoordAan", "")), ad = domein(antw);
                if (!doorgestuurd && !antw.isEmpty() && !k.adressen.contains(antw) && !k.platforms.contains(ad) && !k.freemail.contains(ad)) { soort = "domein"; domein = ad; }
            }
            else if (k.freemail.contains(domein)) soort = "freemail";
            else soort = "domein";
            km.put("adres", adres).put("domein", domein).put("naam", naam).put("soort", soort);
            if (doorgestuurd) km.put("doorgestuurd", true);
            if (platform) km.put("platform", true); // het adres is dat van het platform, niet van de leverancier
            String t = pdfTekst == null ? "" : pdfTekst;
            km.put("btw", schoon(btw(t), k)).put("kvk", schoon(kvk(t), k)).put("iban", schoon(iban(t), k));
        } catch (Exception ignored) { }
        return km;
    }

    private static JSONArray schoon(List<String> l, Kennis k) {
        JSONArray a = new JSONArray();
        for (String n : l) if (!k.eigen.contains(n)) a.put(n);
        return a;
    }

    /** Wie het is, voor het tellen van gedeelde nummers: het domein, het adres of de naam. */
    static String sleutel(JSONObject km) {
        switch (km.optString("soort")) {
            case "domein": return km.optString("domein").isEmpty() ? "" : "d:" + km.optString("domein");
            case "freemail": return km.optString("adres").isEmpty() ? "" : "a:" + km.optString("adres");
            case "platform": return !km.optString("naam").isEmpty() ? "n:" + km.optString("naam").toLowerCase(Locale.ROOT) : km.optString("adres").isEmpty() ? "" : "a:" + km.optString("adres");
            default: return "";
        }
    }

    // ================= kiezen =================

    static JSONObject herken(JSONObject l) { JSONObject h = l == null ? null : l.optJSONObject("herken"); return h == null ? new JSONObject() : h; }

    /** Een eigen regel: een heel adres, of een domein (ook subdomeinen). */
    static boolean regelPast(String regel, String adres) {
        String r = regel.trim().toLowerCase(Locale.ROOT);
        if (r.isEmpty() || adres.isEmpty()) return false;
        if (r.contains("@")) return r.equals(adres);
        return adres.endsWith("@" + r) || adres.endsWith("." + r);
    }

    /** De domeinen van een leverancier: uit de herkenning, en bij een website ook die van de website. */
    static Set<String> domeinenVan(JSONObject l) {
        Set<String> d = new LinkedHashSet<>(lijst(herken(l).optJSONArray("domeinen")));
        if ("website".equals(l.optString("soort", "website"))) for (String x : Facturen.domeinen(null, l)) d.add(x);
        return d;
    }

    /** Leveranciers waar een factuur uit de mail bij kan horen: die uit de mail, en websites die Rene eraan koppelde. */
    static List<JSONObject> doelen(JSONArray levs) {
        List<JSONObject> d = new ArrayList<>();
        for (int i = 0; levs != null && i < levs.length(); i++) {
            JSONObject l = levs.optJSONObject(i);
            if (l == null || "mail".equals(l.optString("soort")) || NIET_HERKEND.equals(l.optString("id"))) continue;
            if (SOORT.equals(l.optString("soort")) || herken(l).length() > 0) d.add(l);
        }
        return d;
    }

    /** Zegt de afzender zelf iets anders dan deze leverancier (ander domein, andere naam bij een platform, ander adres)? */
    static boolean spreektTegen(JSONObject l, JSONObject km) {
        JSONObject h = herken(l);
        switch (km.optString("soort")) {
            case "domein": { Set<String> d = domeinenVan(l); return !d.isEmpty() && !d.contains(km.optString("domein")); }
            case "platform": { List<String> n = lijst(h.optJSONArray("namen")); String naam = km.optString("naam").toLowerCase(Locale.ROOT); return !naam.isEmpty() && !n.isEmpty() && !n.contains(naam); }
            case "freemail": {
                List<String> a = lijst(h.optJSONArray("afzenders"));
                if (a.isEmpty()) return false;
                for (String r : a) if (regelPast(r, km.optString("adres"))) return false;
                return true;
            }
            default: return false;
        }
    }

    private static JSONObject opNummer(List<JSONObject> doelen, JSONObject km, String[] soorten, Kennis k) {
        JSONObject gevonden = null;
        for (JSONObject l : doelen) {
            boolean past = false;
            for (String s : soorten) for (String n : lijst(km.optJSONArray(s))) if (k.telt(n) && lijst(herken(l).optJSONArray(s)).contains(n)) past = true;
            if (!past) continue;
            if (gevonden != null) return null; // twee leveranciers met hetzelfde nummer: dan zegt het niets
            gevonden = l;
        }
        return gevonden;
    }

    /**
     * Welke leverancier? {id, hoe: regel|nummer|domein|naam} als het vaststaat, of alleen {voorstel: id} als een nummer
     * bij een leverancier hoort waar de afzender zelf iets anders over zegt. Leeg object: niets bekends.
     */
    static JSONObject kies(JSONArray levs, JSONObject km, Kennis k) {
        JSONObject uit = new JSONObject();
        try {
            List<JSONObject> doelen = doelen(levs);
            String adres = km.optString("adres"), domein = km.optString("domein"), soort = km.optString("soort");
            for (JSONObject l : doelen) for (String r : lijst(herken(l).optJSONArray("afzenders")))
                if (regelPast(r, adres)) return uit.put("id", l.optString("id")).put("hoe", "regel");
            for (String[] laag : new String[][]{{"btw", "kvk"}, {"iban"}}) {
                JSONObject l = opNummer(doelen, km, laag, k);
                if (l == null) continue;
                if (!spreektTegen(l, km)) return uit.put("id", l.optString("id")).put("hoe", "nummer");
                if (!uit.has("voorstel")) uit.put("voorstel", l.optString("id"));
            }
            if ("domein".equals(soort) && !domein.isEmpty())
                for (JSONObject l : doelen) if (lijst(herken(l).optJSONArray("domeinen")).contains(domein)) return uit.put("id", l.optString("id")).put("hoe", "domein");
            String naam = km.optString("naam").toLowerCase(Locale.ROOT);
            if (!"domein".equals(soort) && !naam.isEmpty())
                for (JSONObject l : doelen) if (lijst(herken(l).optJSONArray("namen")).contains(naam)) return uit.put("id", l.optString("id")).put("hoe", "naam");
        } catch (Exception ignored) { }
        return uit;
    }

    private static boolean ietsAnders(JSONArray levs, String id, String soort, String waarde) {
        for (JSONObject l : doelen(levs)) if (!l.optString("id").equals(id) && lijst(herken(l).optJSONArray(soort)).contains(waarde)) return true;
        return false;
    }

    private static boolean voegToe(JSONObject h, String soort, String waarde) throws Exception {
        if (waarde == null || waarde.isEmpty()) return false;
        JSONArray a = h.optJSONArray(soort);
        if (a == null) { a = new JSONArray(); h.put(soort, a); }
        if (lijst(a).contains(waarde) || a.length() >= MAX) return false;
        a.put(waarde);
        return true;
    }

    /**
     * Onthouden wat bij deze leverancier hoort (nummers die niemand anders heeft; het domein, adres of de naam als die er
     * nog niet waren). Geeft true als er iets bij kwam. Een regel van Rene zelf blijft staan.
     */
    static boolean leer(JSONObject l, JSONObject km, JSONArray levs, Kennis k) {
        if (NIET_HERKEND.equals(l.optString("id"))) return false;
        try {
            JSONObject h = l.optJSONObject("herken");
            if (h == null) { h = new JSONObject(); l.put("herken", h); }
            String id = l.optString("id");
            boolean bij = false;
            for (String s : NUMMERS) for (String n : lijst(km.optJSONArray(s))) if (k.telt(n) && !ietsAnders(levs, id, s, n)) bij |= voegToe(h, s, n);
            switch (km.optString("soort")) {
                case "domein":
                    if (lijst(h.optJSONArray("domeinen")).isEmpty() && !ietsAnders(levs, id, "domeinen", km.optString("domein"))) bij |= voegToe(h, "domeinen", km.optString("domein"));
                    break;
                case "platform":
                    if (lijst(h.optJSONArray("namen")).isEmpty() && !ietsAnders(levs, id, "namen", km.optString("naam").toLowerCase(Locale.ROOT))) bij |= voegToe(h, "namen", km.optString("naam").toLowerCase(Locale.ROOT));
                    break;
                case "freemail":
                    if (lijst(h.optJSONArray("afzenders")).isEmpty() && !ietsAnders(levs, id, "afzenders", km.optString("adres"))) bij |= voegToe(h, "afzenders", km.optString("adres"));
                    break;
                default: break;
            }
            return bij;
        } catch (Exception e) { return false; }
    }

    /**
     * Een nieuwe leverancier uit de mail. Naam: die van de afzender, anders het domein of het adres. Zonder iets
     * bruikbaars: "Niet herkend" (één rij, id NIET_HERKEND). Opslag zoals bij de mailbron; voorstel = een bestaande
     * leverancier waar hij waarschijnlijk bij hoort (zelfde naam, de website met dit domein, of een nummer).
     */
    static JSONObject nieuw(JSONObject km, JSONObject bron, JSONArray levs, Kennis k, String voorstel) throws Exception {
        String soort = km.optString("soort"), adres = km.optString("adres"), domein = km.optString("domein"), naam = km.optString("naam");
        JSONObject h = new JSONObject();
        if ("domein".equals(soort) && !domein.isEmpty() && !ietsAnders(levs, "", "domeinen", domein)) voegToe(h, "domeinen", domein);
        else if ("freemail".equals(soort) && !adres.isEmpty()) voegToe(h, "afzenders", adres);
        else if ("platform".equals(soort) && !naam.isEmpty()) voegToe(h, "namen", naam.toLowerCase(Locale.ROOT));
        for (String s : NUMMERS) for (String n : lijst(km.optJSONArray(s))) if (k.telt(n) && !ietsAnders(levs, "", s, n)) voegToe(h, s, n);
        boolean leeg = h.length() == 0;
        String nr = ""; // zegt de afzender niets, dan het nummer op de factuur
        for (String s : NUMMERS) { List<String> l = lijst(h.optJSONArray(s)); if (nr.isEmpty() && !l.isEmpty()) nr = "kvk".equals(s) ? "KvK " + l.get(0) : "iban".equals(s) ? "IBAN " + l.get(0) : l.get(0); }
        String titel = leeg ? NIET_HERKEND_NAAM : !naam.isEmpty() ? naam : "domein".equals(soort) && !domein.isEmpty() ? domein
                : "freemail".equals(soort) && !adres.isEmpty() ? adres : !nr.isEmpty() ? nr : NIET_HERKEND_NAAM;
        if (titel.equals(NIET_HERKEND_NAAM)) { leeg = true; h = new JSONObject(); }
        String vs = voorstel == null ? "" : voorstel;
        if (!leeg) for (int i = 0; levs != null && i < levs.length(); i++) {
            JSONObject l = levs.optJSONObject(i);
            if (l == null || "mail".equals(l.optString("soort")) || !l.optString("naam").equalsIgnoreCase(titel)) continue;
            if (vs.isEmpty()) vs = l.optString("id");
            String wat = "domein".equals(soort) ? domein : "freemail".equals(soort) ? adres : "";
            if (!wat.isEmpty() && !wat.equalsIgnoreCase(titel)) titel = titel + " (" + wat + ")";
            break;
        }
        if (vs.isEmpty() && !domein.isEmpty()) for (int i = 0; levs != null && i < levs.length(); i++) {
            JSONObject l = levs.optJSONObject(i);
            if (l != null && "website".equals(l.optString("soort", "website")) && domeinenVan(l).contains(domein)) { vs = l.optString("id"); break; }
        }
        JSONObject o = new JSONObject().put("naam", titel.length() > 60 ? titel.substring(0, 60).trim() : titel).put("soort", SOORT).put("sjabloon", SOORT)
                .put("nieuw", true).put("herken", h).put("voorstel", vs).put("bronnen", new JSONArray().put(bron.optString("id")))
                .put("map", bron.optBoolean("map", true)).put("lokaal", bron.optBoolean("lokaal", false)).put("drive", bron.optBoolean("drive", false))
                .put("eigenCloud", bron.optBoolean("eigenCloud", false)).put("meldNieuw", bron.optBoolean("meldNieuw", true));
        if (leeg) o.put("id", NIET_HERKEND);
        return o;
    }

    // ================= instellingen controleren =================

    /** Een lijst uit JSON (array) of tekst met komma's, puntkomma's of regels. */
    static List<String> delen(Object v) {
        List<String> l = new ArrayList<>();
        if (v instanceof JSONArray) l.addAll(lijst((JSONArray) v));
        else if (v != null) for (String s : String.valueOf(v).split("[,;\\n]+")) if (!s.trim().isEmpty()) l.add(s.trim());
        return l;
    }

    static List<String> lijst(JSONArray a) {
        List<String> l = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) { String s = a.optString(i, "").trim(); if (!s.isEmpty()) l.add(s); }
        return l;
    }

    private static String kort(String s) { return s.length() > 40 ? s.substring(0, 40) + "…" : s; }

    private static final String DOMEIN = "[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*\\.[a-z]{2,}";

    /** Een geldige domeinnaam (hooguit 253 tekens, zoals DNS: langer maakt het patroon traag en de stack vol). */
    private static boolean domeinOk(String v) { return v.length() <= 253 && v.matches(DOMEIN); }

    /** Herkenning van een leverancier, zoals Rene hem kan aanpassen. Fout = Nederlandse uitleg. */
    static JSONObject herkenNormaal(JSONObject in) throws Exception {
        JSONObject o = new JSONObject();
        if (in == null) return o;
        JSONArray afz = new JSONArray(), dom = new JSONArray(), nm = new JSONArray(), btw = new JSONArray(), kvk = new JSONArray(), iban = new JSONArray();
        for (String x : delen(in.opt("afzenders"))) {
            String v = x.toLowerCase(Locale.ROOT).replaceAll("^mailto:|^@", "");
            boolean ok = v.contains("@") ? adres("<" + v + ">").equals(v) : domeinOk(v);
            if (!ok) throw new Exception("Geen geldig mailadres of domein: " + kort(x));
            if (!lijst(afz).contains(v)) afz.put(v);
        }
        for (String x : delen(in.opt("domeinen"))) {
            String v = x.toLowerCase(Locale.ROOT).replaceAll("^https?://", "").replaceAll("/.*$", "").replaceAll("^\\*?\\.|^@", "");
            if (!domeinOk(v)) throw new Exception("Geen geldig domein: " + kort(x) + " (bijv. kpn.com)");
            v = Facturen.domein("https://" + v);
            if (!lijst(dom).contains(v)) dom.put(v);
        }
        for (String x : delen(in.opt("namen"))) {
            String v = x.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
            if (v.length() > 60) throw new Exception("Te lange naam: " + kort(x));
            if (!lijst(nm).contains(v)) nm.put(v);
        }
        for (String x : delen(in.opt("btw"))) {
            String v = nummer(x);
            if (!btwOk(v)) throw new Exception("Geen geldig btw-nummer: " + kort(x) + " (bijv. NL001234567B01)");
            if (!lijst(btw).contains(v)) btw.put(v);
        }
        for (String x : delen(in.opt("kvk"))) {
            String v = nummer(x);
            if (!v.matches("\\d{8}")) throw new Exception("Een KvK-nummer heeft 8 cijfers: " + kort(x));
            if (!lijst(kvk).contains(v)) kvk.put(v);
        }
        for (String x : delen(in.opt("iban"))) {
            String v = nummer(x);
            if (!ibanOk(v)) throw new Exception("Geen geldig IBAN: " + kort(x));
            if (!lijst(iban).contains(v)) iban.put(v);
        }
        Object[][] alles = {{"afzenders", afz, 30}, {"domeinen", dom, 20}, {"namen", nm, MAX}, {"btw", btw, MAX}, {"kvk", kvk, MAX}, {"iban", iban, MAX}};
        for (Object[] a : alles) {
            JSONArray l = (JSONArray) a[1];
            if (l.length() > (Integer) a[2]) throw new Exception("Te veel in één lijst (hooguit " + a[2] + ")");
            if (l.length() > 0) o.put((String) a[0], l);
        }
        return o;
    }

    /** Rene's eigen nummers (KvK, btw, IBAN) en mailadressen. Fout = Nederlandse uitleg. */
    static JSONObject eigenNormaal(JSONObject in) throws Exception {
        JSONArray nummers = new JSONArray(), adressen = new JSONArray();
        if (in != null) {
            for (String x : delen(in.opt("nummers"))) {
                String v = nummer(x);
                if (!(v.matches("\\d{8}") || ibanOk(v) || btwOk(v))) throw new Exception("Geen KvK-, btw- of IBAN-nummer: " + kort(x));
                if (!lijst(nummers).contains(v)) nummers.put(v);
            }
            for (String x : delen(in.opt("adressen"))) {
                String v = adres(x);
                if (v.isEmpty() || !v.equals(x.trim().toLowerCase(Locale.ROOT))) throw new Exception("Geen geldig mailadres: " + kort(x));
                if (!lijst(adressen).contains(v)) adressen.put(v);
            }
        }
        if (nummers.length() > 20) throw new Exception("Hooguit 20 eigen nummers");
        if (adressen.length() > 10) throw new Exception("Hooguit 10 eigen mailadressen");
        return new JSONObject().put("nummers", nummers).put("adressen", adressen);
    }

    /** Twee herkenningen samen (bij samenvoegen): alles van beide, zonder dubbele. */
    static JSONObject samen(JSONObject a, JSONObject b) throws Exception {
        JSONObject o = new JSONObject();
        for (String s : new String[]{"afzenders", "domeinen", "namen", "btw", "kvk", "iban"}) {
            LinkedHashSet<String> l = new LinkedHashSet<>(lijst(a == null ? null : a.optJSONArray(s)));
            l.addAll(lijst(b == null ? null : b.optJSONArray(s)));
            JSONArray r = new JSONArray();
            int max = "afzenders".equals(s) ? 30 : "domeinen".equals(s) ? 20 : MAX;
            for (String x : l) if (r.length() < max) r.put(x);
            if (r.length() > 0) o.put(s, r);
        }
        return o;
    }
}
