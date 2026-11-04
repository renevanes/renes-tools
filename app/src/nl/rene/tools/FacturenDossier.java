package nl.rene.tools;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Iterator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Het dossier van Facturen: per opgehaalde factuur (sleutel = SHA-256 van de PDF) alle gegevens die de app kent:
 * waar hij vandaan komt (website of mail: afzender, onderwerp, datum), bestand, grootte, wanneer opgehaald, de herkende
 * factuurgegevens (nummer, datum, bedrag, btw; aan te passen), op welke plekken hij precies staat (app, backup-map, map op
 * de telefoon, Google Drive, Eigen Cloud) en aan wie hij wanneer is aangeleverd (FacturenAanlever).
 * Opslag: getFilesDir()/facturen/dossier.json (gaat mee in "Alle appgegevens" met de map facturen).
 */
final class FacturenDossier {
    private FacturenDossier() { }

    static File file(Context c) { return new File(Facturen.dir(c), "dossier.json"); }

    private static void check(String t) throws Exception { JSONObject o = new JSONObject(t); if (o.length() > 0 && o.optJSONObject("items") == null) throw new Exception("onleesbaar"); }

    /** Alle stukken: {sha: stuk}. Bij de eerste keer worden de al opgehaalde facturen overgenomen. */
    static JSONObject alle(Context c) {
        synchronized (Facturen.LOCK) {
            try {
                File f = file(c);
                if (!JsonFileStore.exists(f)) { JSONObject o = overnemen(c); bewaar(c, o); return o; }
                JSONObject o = new JSONObject(JsonFileStore.read(f, FacturenDossier::check)).optJSONObject("items");
                return o == null ? new JSONObject() : o;
            } catch (Exception e) { return new JSONObject(); }
        }
    }

    /** Is het dossier te lezen? Zo niet, dan wordt er niets in geschreven en niets aangeleverd (anders gaat alles kwijt of dubbel). */
    static boolean leesbaar(Context c) {
        synchronized (Facturen.LOCK) {
            File f = file(c);
            if (!JsonFileStore.exists(f)) return true;
            try { JsonFileStore.read(f, FacturenDossier::check); return true; } catch (Exception e) { return false; }
        }
    }

    static void bewaar(Context c, JSONObject items) throws Exception {
        synchronized (Facturen.LOCK) {
            if (!leesbaar(c)) throw new Exception("Het dossier is onleesbaar; er is niets veranderd");
            JsonFileStore.write(file(c), new JSONObject().put("items", items).toString(), FacturenDossier::check);
        }
    }

    /** Een stuk bijwerken (velden samenvoegen; JSONObject-velden zoals plekken en verzonden worden ook samengevoegd). */
    static void werkBij(Context c, String sha, JSONObject wijziging) {
        synchronized (Facturen.LOCK) {
            try {
                JSONObject all = alle(c);
                JSONObject s = all.optJSONObject(sha);
                if (s == null) return;
                samenvoegen(s, wijziging);
                all.put(sha, s);
                bewaar(c, all);
            } catch (Exception ignored) { }
        }
    }

    static void samenvoegen(JSONObject doel, JSONObject bron) throws Exception {
        for (Iterator<String> it = bron.keys(); it.hasNext(); ) {
            String k = it.next();
            Object v = bron.get(k);
            if (v instanceof JSONObject && doel.optJSONObject(k) != null && !"meta".equals(k)) samenvoegen(doel.getJSONObject(k), (JSONObject) v);
            else doel.put(k, v);
        }
    }

    /** Nieuwe factuur in het dossier (na het bewaren in de app). */
    static void nieuw(Context c, JSONObject lev, Facturen.Pdf p, String naam, File f) {
        try {
            JSONObject bron = p.bron == null ? new JSONObject() : new JSONObject(p.bron.toString());
            bron.put("soort", lev.optString("soort", "website"));
            JSONObject s = new JSONObject()
                    .put("sha", p.sha).put("naam", naam).put("origineel", p.naam).put("grootte", p.data.length)
                    .put("leverancier", lev.optString("id")).put("leverancierNaam", lev.optString("naam"))
                    .put("opgehaald", System.currentTimeMillis()).put("bron", bron)
                    .put("plekken", new JSONObject().put("app", new JSONObject().put("pad", f.getAbsolutePath()).put("t", System.currentTimeMillis())))
                    .put("verzonden", new JSONObject());
            // Factuurgegevens uit de tekst van de PDF (Android 15+), anders uit de bestandsnaam
            JSONObject meta = herken(tekst(f) + "\n" + p.naam);
            meta.put("bron", meta.length() > 0 ? "herkend" : "");
            if (PdfDoc.canText()) meta.put("versie", HERKEN_VERSIE);
            s.put("meta", meta);
            synchronized (Facturen.LOCK) {
                JSONObject all = alle(c);
                JSONObject oud = all.optJSONObject(p.sha);
                if (oud == null) all.put(p.sha, s);
                else { // opnieuw opgehaald (bijv. na het opnieuw toevoegen van de leverancier): de plek in de app klopt weer
                    if (oud.optJSONObject("plekken") == null) oud.put("plekken", new JSONObject());
                    oud.getJSONObject("plekken").put("app", s.getJSONObject("plekken").getJSONObject("app"));
                }
                bewaar(c, all);
            }
        } catch (Throwable ignored) { }
    }

    /** Waar een factuur staat (map, lok, gd, ec): {pad of id, link, t}. */
    static void plek(Context c, String sha, String plek, JSONObject info) {
        try { info.put("t", System.currentTimeMillis()); werkBij(c, sha, new JSONObject().put("plekken", new JSONObject().put(plek, info))); } catch (Exception ignored) { }
    }

    /** Aangeleverd (of mislukt) aan een afnemer. */
    static void verzonden(Context c, String sha, String ontvanger, JSONObject info) throws Exception {
        synchronized (Facturen.LOCK) {
            JSONObject all = alle(c), s = all.optJSONObject(sha);
            if (s == null) throw new Exception("Factuur niet gevonden in het dossier");
            if (s.optJSONObject("verzonden") == null) s.put("verzonden", new JSONObject());
            s.getJSONObject("verzonden").put(ontvanger, info); // vervangen, niet samenvoegen (geen oude fout of opnieuw-vlag)
            bewaar(c, all);
        }
    }

    /** Opnieuw aanleveren aan deze ontvanger: de aantekening weghalen, dan staat hij weer open. */
    static void vergeet(Context c, String sha, String ontvanger) throws Exception {
        synchronized (Facturen.LOCK) {
            JSONObject all = alle(c), s = all.optJSONObject(sha);
            if (s == null) throw new Exception("Factuur niet gevonden in het dossier");
            JSONObject v = s.optJSONObject("verzonden");
            if (v == null) { v = new JSONObject(); s.put("verzonden", v); }
            v.put(ontvanger, new JSONObject().put("ok", false).put("opnieuw", true).put("t", System.currentTimeMillis()));
            bewaar(c, all);
        }
    }

    /** Factuurgegevens met de hand aanpassen. */
    static void meta(Context c, String sha, JSONObject in) throws Exception {
        JSONObject m = new JSONObject();
        for (String k : new String[]{"nummer", "datum", "bedrag", "btw", "notitie", "categorie", "vervaldatum", "betaald"}) {
            String v = in.optString(k, "").trim();
            if (v.length() > ("notitie".equals(k) ? 1000 : 80)) throw new Exception("Te lang: " + k);
            m.put(k, v);
        }
        for (String k : new String[]{"datum", "vervaldatum", "betaald"})
            if (!m.optString(k).isEmpty() && !m.optString(k).matches("(19|20|21)\\d{2}-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])")) throw new Exception("Datum als JJJJ-MM-DD, bijv. 2026-10-05");
        for (String k : new String[]{"bedrag", "btw"}) { // als 1234,56 bewaren (ook als er 1.234,56 of 12.50 is ingetypt)
            if (m.optString(k).isEmpty()) continue;
            String b = bedrag(m.optString(k));
            if (b == null) throw new Exception(("btw".equals(k) ? "Btw" : "Bedrag") + " als 123,45");
            m.put(k, b);
        }
        m.put("bron", "zelf");
        synchronized (Facturen.LOCK) {
            JSONObject all = alle(c);
            JSONObject s = all.optJSONObject(sha);
            if (s == null) throw new Exception("Factuur niet gevonden in het dossier");
            s.put("meta", m);
            bewaar(c, all);
        }
    }

    /** De facturen in de app van deze leverancier zijn weg (leverancier verwijderd): in het dossier noteren. */
    static void appWeg(Context c, String levId) {
        synchronized (Facturen.LOCK) {
            try {
                JSONObject all = alle(c);
                for (Iterator<String> it = all.keys(); it.hasNext(); ) {
                    JSONObject s = all.optJSONObject(it.next());
                    if (s == null || !levId.equals(s.optString("leverancier"))) continue;
                    JSONObject app = s.optJSONObject("plekken") == null ? null : s.optJSONObject("plekken").optJSONObject("app");
                    if (app != null) app.put("weg", System.currentTimeMillis());
                }
                bewaar(c, all);
            } catch (Exception ignored) { }
        }
    }

    /** Eerste keer: de facturen die al eerder opgehaald zijn (toestand → verwerkt) overnemen. */
    static JSONObject overnemen(Context c) throws Exception {
        JSONObject items = new JSONObject(), toestand = Facturen.toestand(c);
        for (Iterator<String> it = toestand.keys(); it.hasNext(); ) {
            String id = it.next();
            JSONObject t = toestand.optJSONObject(id), vw = t == null ? null : t.optJSONObject("verwerkt");
            JSONObject lev = Facturen.leverancier(c, id);
            if (vw == null) continue;
            for (Iterator<String> j = vw.keys(); j.hasNext(); ) {
                String sha = j.next();
                JSONObject r = vw.optJSONObject(sha);
                if (r == null) continue;
                File f = new File(Facturen.pdfDir(c, id), r.optString("n"));
                JSONObject plekken = new JSONObject();
                if (f.isFile()) plekken.put("app", new JSONObject().put("pad", f.getAbsolutePath()).put("t", r.optLong("t")));
                for (String k : new String[]{"map", "lok", "gd", "ec"}) if (r.optBoolean(k)) plekken.put(k, new JSONObject().put("t", r.optLong("t")));
                items.put(sha, new JSONObject().put("sha", sha).put("naam", r.optString("n")).put("origineel", r.optString("o", r.optString("n")))
                        .put("grootte", f.isFile() ? f.length() : 0).put("leverancier", id).put("leverancierNaam", lev == null ? id : lev.optString("naam"))
                        .put("opgehaald", r.optLong("t")).put("bron", new JSONObject().put("soort", lev == null ? "" : lev.optString("soort", "website")))
                        .put("plekken", plekken).put("verzonden", new JSONObject()).put("meta", herken(r.optString("n")).put("bron", "herkend")));
            }
        }
        return items;
    }

    /** Versie van het herkennen: gaat omhoog als herken() beter wordt, dan wordt alles nog een keer gelezen. */
    static final int HERKEN_VERSIE = 3;

    /** Mist er iets (geen datum, bedrag of betaaldatum) en is het niet met de hand ingevuld? Dan nog eens lezen. (zonder Android) */
    static boolean opnieuwLezen(JSONObject s) {
        JSONObject m = s.optJSONObject("meta");
        if (m == null) return true;
        if ("zelf".equals(m.optString("bron"))) return false;
        if (m.optInt("versie") >= HERKEN_VERSIE) return false;
        // Oudere herkenning: altijd opnieuw (die kon fouten bevatten, bijv. een stukje van de bestandsnaam als factuurnummer)
        return m.optInt("versie") > 0 || m.optString("datum").isEmpty() || m.optString("bedrag").isEmpty() || m.optString("nummer").isEmpty() || m.optString("vervaldatum").isEmpty();
    }

    static int gevuld(JSONObject m) { int n = 0; if (m != null) for (String k : new String[]{"nummer", "datum", "bedrag", "btw", "vervaldatum"}) if (!m.optString(k).isEmpty()) n++; return n; }

    /**
     * Opnieuw herkend: de nieuwe herkenning gaat voor; wat nu niet gevonden wordt, blijft van de vorige keer (behalve een
     * factuurnummer dat niet klopt). Categorie en notitie blijven altijd. (zonder Android)
     */
    static JSONObject aanvullen(JSONObject oud, JSONObject nieuw) throws Exception {
        JSONObject m = oud == null ? new JSONObject() : new JSONObject(oud.toString());
        for (String k : new String[]{"nummer", "datum", "bedrag", "btw", "vervaldatum"}) {
            if (!nieuw.optString(k).isEmpty()) m.put(k, nieuw.optString(k));
            else if ("nummer".equals(k) && !geldigNummer(m.optString(k))) m.remove(k);
        }
        boolean iets = false;
        for (String k : new String[]{"nummer", "datum", "bedrag", "btw", "vervaldatum"}) if (!m.optString(k).isEmpty()) iets = true;
        m.put("bron", iets ? "herkend" : "").put("versie", HERKEN_VERSIE);
        return m;
    }

    /**
     * De facturen in het dossier waar nog gegevens ontbreken opnieuw lezen (de tekst van de PDF, Android 15+): bijv. facturen
     * die al waren opgehaald voordat het dossier bestond. Met de hand ingevulde gegevens blijven zoals ze zijn. Geeft het
     * aantal facturen waarbij er iets bij kwam.
     */
    static int herkenAlle(Context c) throws Exception {
        java.util.Map<String, JSONObject> lezen = new java.util.LinkedHashMap<>();
        synchronized (Facturen.LOCK) {
            JSONObject all = alle(c);
            for (Iterator<String> it = all.keys(); it.hasNext(); ) {
                String sha = it.next();
                JSONObject s = all.optJSONObject(sha);
                if (s != null && opnieuwLezen(s)) lezen.put(sha, s);
            }
        }
        if (lezen.isEmpty()) return 0;
        java.util.Map<String, JSONObject> nieuw = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, JSONObject> e : lezen.entrySet()) { // buiten het slot: PDF's lezen duurt even
            JSONObject s = e.getValue();
            File f = FacturenAanlever.bestand(c, s);
            String tekst = f == null ? "" : tekst(f);
            nieuw.put(e.getKey(), herken(tekst + "\n" + s.optString("origineel") + "\n" + s.optString("naam")));
        }
        int beter = 0;
        synchronized (Facturen.LOCK) {
            JSONObject all = alle(c);
            for (java.util.Map.Entry<String, JSONObject> e : nieuw.entrySet()) {
                JSONObject s = all.optJSONObject(e.getKey());
                if (s == null || !opnieuwLezen(s)) continue; // intussen met de hand aangepast
                JSONObject oud = s.optJSONObject("meta"), m = aanvullen(oud, e.getValue());
                if (gevuld(m) > gevuld(oud)) beter++;
                s.put("meta", m);
            }
            bewaar(c, all);
        }
        return beter;
    }

    // ================= factuurgegevens herkennen (zonder Android: getest in FacturenTest) =================

    private static final String MAAND = "(jan(?:uari)?|feb(?:ruari)?|mrt|maart|apr(?:il)?|mei|jun(?:i)?|jul(?:i)?|aug(?:ustus)?|sep(?:t(?:ember)?)?|okt(?:ober)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?|"
            + "january|february|march|april|may|june|july|august|september|october|november|december)";

    /** Een factuurnummer: met cijfers (minstens 4), geen datum, geen bestandsnaam en geen stukje controlegetal van de app. */
    static boolean geldigNummer(String n) {
        if (n == null) return false;
        String v = n.trim();
        if (v.length() < 4 || v.length() > 30 || v.toLowerCase(Locale.ROOT).endsWith(".pdf")) return false;
        if (v.replaceAll("\\D", "").length() < 4) return false;
        if (v.matches("\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4}|\\d{4}-\\d{2}-\\d{2}")) return false;
        if (v.matches("[0-9a-f]{8}") && v.matches(".*[a-f].*")) return false; // de eerste 8 tekens van de SHA-256 in onze bestandsnamen
        return !v.matches("(?i)(NL)?\\d{2}[A-Z]{4}\\d{10}"); // geen IBAN
    }

    // Namen van velden zoals ze op facturen staan (ook in een kopregel van een tabel, met de waarden op de regel eronder)
    private static final String L_NUMMER = "factuur ?nummer|factuur ?nr\\.?|factuurnr|nota ?nummer|nota ?nr\\.?|invoice (?:number|no\\.?)|invoice ?#|rekening ?nummer|documentnummer";
    private static final String L_DATUM = "factuurdatum|factuur datum|notadatum|documentdatum|datum factuur|invoice date|rechnungsdatum|datum";
    private static final String L_VERVAL = "uiterste betaaldatum|uiterlijk betalen(?: op| voor)?|vervaldatum|betaaldatum|due date|payment due";
    private static final String L_ANDERS = "klant ?nummer|klantnr\\.?|debiteur ?nummer|debiteurnr\\.?|contract ?nummer|referentie|periode|betalingskenmerk|btw[- ]?nummer|kvk[- ]?nummer|pagina";
    private static final String L_BEDRAG = "totaal te betalen|te betalen|totaalbedrag|totaal incl\\.?(?: btw)?|factuurbedrag|totaal";
    private static final String DATUMWAARDE = "\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4}|\\d{1,2} " + "(?:jan(?:uari)?|feb(?:ruari)?|mrt|maart|apr(?:il)?|mei|jun(?:i)?|jul(?:i)?|aug(?:ustus)?|sep(?:t(?:ember)?)?|okt(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\.? \\d{4}|\\d{4}-\\d{2}-\\d{2}";
    private static final String BEDRAGWAARDE = "(?:€|eur)?\\s?-?\\d{1,3}(?:\\.\\d{3})*,\\d{2}(?![\\d])|(?:€|eur)?\\s?-?\\d+\\.\\d{2}(?![\\d.])";

    /**
     * Een tabel met een kopregel (bijv. "Factuurnummer  Factuurdatum  Vervaldatum") en de waarden op de regel eronder:
     * per kolom de waarde in dezelfde volgorde. Vult alleen wat nog ontbreekt.
     */
    private static void kopregels(String t, JSONObject m) throws Exception {
        String[] regels = t.split("\\r?\\n");
        Pattern lab = Pattern.compile("(?i)(" + L_VERVAL + ")|(" + L_NUMMER + ")|(" + L_DATUM + ")|(" + L_BEDRAG + ")|(" + L_ANDERS + ")");
        Pattern waarde = Pattern.compile("(?i)(" + DATUMWAARDE + ")|(" + BEDRAGWAARDE + ")|([A-Z0-9][A-Z0-9./-]*)");
        for (int i = 0; i + 1 < regels.length; i++) {
            java.util.List<String> soorten = new java.util.ArrayList<>();
            Matcher x = lab.matcher(regels[i]);
            int eind = 0;
            while (x.find()) {
                soorten.add(x.group(1) != null ? "vervaldatum" : x.group(2) != null ? "nummer" : x.group(3) != null ? "datum" : x.group(4) != null ? "bedrag" : "");
                eind = x.end();
            }
            if (soorten.size() < 2 || regels[i].substring(eind).matches(".*\\d.*")) continue; // geen kopregel (er staan al waarden in)
            String volgende = "";
            for (int j = i + 1; j < Math.min(regels.length, i + 3) && volgende.trim().isEmpty(); j++) volgende = regels[j];
            java.util.List<String> waarden = new java.util.ArrayList<>();
            Matcher w = waarde.matcher(volgende);
            while (w.find()) waarden.add(w.group().trim());
            if (waarden.size() != soorten.size()) continue;
            for (int k = 0; k < soorten.size(); k++) {
                String soort = soorten.get(k), v = waarden.get(k);
                if (soort.isEmpty() || !m.optString(soort).isEmpty()) continue;
                if (soort.equals("nummer") && geldigNummer(v)) m.put("nummer", v.replaceAll("[./-]+$", ""));
                else if (soort.equals("datum") || soort.equals("vervaldatum")) { String d = datum(v); if (d != null) m.put(soort, d); }
                else if (soort.equals("bedrag")) { String b = bedrag(v); if (b != null) m.put("bedrag", b); }
            }
        }
    }

    /** Factuurnummer, -datum, totaalbedrag, btw en uiterste betaaldatum uit de tekst van een factuur (of de bestandsnaam). */
    static JSONObject herken(String tekst) {
        JSONObject m = new JSONObject();
        if (tekst == null) return m;
        String t = tekst.replace('\u00a0', ' ').replace('\t', ' ');
        try {
            // Factuurnummer: naam en waarde op dezelfde regel, of de waarde op de regel eronder
            Matcher x = Pattern.compile("(?i)(?:" + L_NUMMER + ")[\\s:#.]{1,6}(?:\\n\\s*)?([A-Z0-9][A-Z0-9./-]{2,30})").matcher(t);
            while (x.find()) { String n = x.group(1).replaceAll("[./-]+$", ""); if (geldigNummer(n)) { m.put("nummer", n); break; } }
            if (!m.has("nummer")) { // alleen "Factuur 123456" (niet in onze eigen bestandsnaam met controlegetal)
                x = Pattern.compile("(?i)\\bfactuur[\\s:#.]{1,4}([A-Z]{0,4}\\d[A-Z0-9/-]{3,29})\\b").matcher(t);
                while (x.find()) { String n = x.group(1).replaceAll("[./-]+$", ""); if (geldigNummer(n)) { m.put("nummer", n); break; } }
            }
            String d = null;
            String w = "[\\s:]{1,6}(?:\\n\\s*)?(" + DATUMWAARDE + "|\\d{1,2}[-/. ](?:" + MAAND + ")[-/. ]\\d{2,4})";
            x = Pattern.compile("(?i)(?:factuurdatum|factuur datum|notadatum|documentdatum|datum factuur|invoice date|rechnungsdatum)" + w).matcher(t);
            if (x.find()) d = datum(x.group(1));
            if (d == null) {
                x = Pattern.compile("(?i)(?<![a-z])(?<!due |payment |verval |uiterste |betaal |betalings)(?:datum|date)" + w).matcher(t);
                if (x.find()) d = datum(x.group(1));
            }
            if (d != null) m.put("datum", d);
            x = Pattern.compile("(?iu)(?:uiterste betaaldatum|uiterlijk betalen(?: op| voor)?|te betalen (?:voor|v\\u00f3\\u00f3r|uiterlijk)|vervaldatum|betaaldatum|due date|payment due)" + w).matcher(t);
            if (x.find()) { String v = datum(x.group(1)); if (v != null) m.put("vervaldatum", v); }
            // Bedrag: de laatste "totaal" (ook met de waarde op de volgende regel); nooit een stukje van een datum
            x = Pattern.compile("(?i)(?:totaal ?(?:te betalen|bedrag|incl\\.?(?: btw)?|factuurbedrag)?|te betalen|totaalbedrag|factuurbedrag|amount due|total(?: due| amount)?)(?![a-z])[^\\d€\\n]{0,30}(?:\\n[^\\d€\\n]{0,10})?(?:€|eur|euro)?\\s*(-?\\d{1,3}(?:[.\\s]\\d{3})*,\\d{2}|-?\\d+[.,]\\d{2})(?![\\d]|[.,]\\d)").matcher(t);
            String best = null;
            while (x.find()) best = x.group(1);
            if (best == null) { // anders: "Bedrag" (zonder totaal)
                x = Pattern.compile("(?i)(?<![a-z])bedrag(?![a-z])[^\\d€\\n]{0,25}(?:€|eur)?\\s*(-?\\d{1,3}(?:[.\\s]\\d{3})*,\\d{2}|-?\\d+[.,]\\d{2})(?![\\d]|[.,]\\d)").matcher(t);
                while (x.find()) best = x.group(1);
            }
            if (best != null) { String b = bedrag(best); if (b != null) m.put("bedrag", b); }
            x = Pattern.compile("(?i)(?:btw|vat|omzetbelasting)(?:\\s*\\d{1,2}(?:[.,]\\d)?\\s*%)?[^\\d€\\n]{0,15}(?:€|eur)?\\s*(\\d{1,3}(?:[.\\s]\\d{3})*,\\d{2}|\\d+\\.\\d{2})(?![\\d]|[.,]\\d)").matcher(t);
            if (x.find()) { String b = bedrag(x.group(1)); if (b != null) m.put("btw", b); }
            kopregels(t, m); // tabellen: wat nog ontbreekt
            // Als laatste: een datum in de bestandsnaam (2026-10)
            if (!m.has("datum")) { x = Pattern.compile("\\b(20\\d{2})[-_.](0[1-9]|1[0-2])(?:[-_.](0[1-9]|[12]\\d|3[01]))?\\b").matcher(t); if (x.find()) m.put("datum", x.group(1) + "-" + x.group(2) + "-" + (x.group(3) == null ? "01" : x.group(3))); }
        } catch (Exception ignored) { }
        return m;
    }

    /** "1.234,56" / "1234.56" / "12,5" → "1234,56" (Nederlandse schrijfwijze), of null. */
    static String bedrag(String s) {
        if (s == null) return null;
        String v = s.replaceAll("[€\\s]|eur|EUR", "").trim();
        if (v.matches("-?\\d{1,3}(\\.\\d{3})+(,\\d{1,2})?")) v = v.replace(".", "");
        else if (v.matches("-?\\d{1,3}(,\\d{3})+(\\.\\d{1,2})?")) v = v.replace(",", "");
        v = v.replace(',', '.');
        if (!v.matches("-?\\d+(\\.\\d{1,2})?")) return null;
        try { return String.format(Locale.ROOT, "%.2f", Double.parseDouble(v)).replace('.', ','); } catch (Exception e) { return null; }
    }

    /** "5-10-2026", "05/10/26", "5 oktober 2026", "2026-10-05" → "2026-10-05", of null. */
    static String datum(String s) {
        if (s == null) return null;
        String v = s.trim().toLowerCase(Locale.ROOT).replaceAll("([a-z])\\.", "$1").replaceAll("\\s+", " ");
        Matcher m = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})").matcher(v);
        if (m.matches()) return v;
        m = Pattern.compile("(\\d{1,2})[-/. ]([a-z]+|\\d{1,2})[-/. ](\\d{2,4})").matcher(v);
        if (!m.matches()) return null;
        int dag = Integer.parseInt(m.group(1)), jaar = Integer.parseInt(m.group(3));
        if (jaar < 100) jaar += 2000;
        int maand;
        if (m.group(2).matches("\\d+")) maand = Integer.parseInt(m.group(2));
        else {
            String[] nl = {"jan", "feb", "m", "apr", "mei", "jun", "jul", "aug", "sep", "o", "nov", "dec"};
            String w = m.group(2);
            maand = 0;
            if (w.startsWith("mrt") || w.startsWith("maa") || w.startsWith("mar")) maand = 3;
            else if (w.startsWith("okt") || w.startsWith("oct")) maand = 10;
            else if (w.startsWith("may")) maand = 5;
            else for (int i = 0; i < nl.length; i++) if (nl[i].length() > 1 && w.startsWith(nl[i])) maand = i + 1;
            if (w.startsWith("ma") && maand == 0) maand = 3;
        }
        if (maand < 1 || maand > 12 || dag < 1 || dag > 31 || jaar < 2000 || jaar > 2100) return null;
        return String.format(Locale.ROOT, "%04d-%02d-%02d", jaar, maand, dag);
    }

    /** De tekst van de eerste twee pagina's (Android 15+), of "". */
    static String tekst(File f) {
        if (!PdfDoc.canText()) return "";
        try (PdfDoc d = new PdfDoc(f, f.getName(), null)) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < Math.min(2, d.count); i++) b.append(d.text(i)).append('\n');
            return b.length() > 20000 ? b.substring(0, 20000) : b.toString();
        } catch (Throwable e) { return ""; }
    }

    // ================= CSV voor de boekhouding =================

    static String cel(String s) {
        String v = s == null ? "" : s.replace("\r", " ").replace("\n", " ");
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0 && !v.matches("-?\\d+(,\\d+)?")) v = "'" + v; // geen formules in Excel
        return v.matches(".*[;\"].*") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    /** Het dossier als CSV (puntkomma, met BOM, voor Excel): één regel per factuur. */
    static String csv(JSONObject items) {
        StringBuilder b = new StringBuilder("\ufeffOpgehaald;Leverancier;Bestand;Factuurnummer;Factuurdatum;Bedrag;Btw;Uiterste betaaldatum;Betaald op;Categorie;Notitie;Bron;Afzender;Onderwerp;Plekken;Aangeleverd;SHA-256\r\n");
        java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        java.util.List<JSONObject> l = new java.util.ArrayList<>();
        for (Iterator<String> it = items.keys(); it.hasNext(); ) { JSONObject s = items.optJSONObject(it.next()); if (s != null) l.add(s); }
        java.util.Collections.sort(l, (x, y) -> Long.compare(x.optLong("opgehaald"), y.optLong("opgehaald")));
        for (JSONObject s : l) {
            JSONObject m = s.optJSONObject("meta") == null ? new JSONObject() : s.optJSONObject("meta");
            JSONObject bron = s.optJSONObject("bron") == null ? new JSONObject() : s.optJSONObject("bron");
            StringBuilder plekken = new StringBuilder(), aan = new StringBuilder();
            JSONObject p = s.optJSONObject("plekken");
            if (p != null) for (Iterator<String> it = p.keys(); it.hasNext(); ) { String k = it.next(); JSONObject x = p.optJSONObject(k); if (x != null && x.optLong("weg") == 0) plekken.append(plekken.length() > 0 ? ", " : "").append(PLEK.containsKey(k) ? PLEK.get(k) : k); }
            JSONObject v = s.optJSONObject("verzonden");
            if (v != null) for (Iterator<String> it = v.keys(); it.hasNext(); ) { JSONObject x = v.optJSONObject(it.next()); if (x != null && x.optBoolean("ok")) aan.append(aan.length() > 0 ? ", " : "").append(x.optString("naam")).append(" (").append(df.format(new java.util.Date(x.optLong("t")))).append(")"); }
            b.append(cel(df.format(new java.util.Date(s.optLong("opgehaald"))))).append(';').append(cel(s.optString("leverancierNaam"))).append(';').append(cel(s.optString("naam"))).append(';')
                    .append(cel(m.optString("nummer"))).append(';').append(cel(m.optString("datum"))).append(';').append(cel(m.optString("bedrag"))).append(';').append(cel(m.optString("btw"))).append(';')
                    .append(cel(m.optString("vervaldatum"))).append(';').append(cel(m.optString("betaald"))).append(';')
                    .append(cel(m.optString("categorie"))).append(';').append(cel(m.optString("notitie"))).append(';').append(cel(bron.optString("soort"))).append(';')
                    .append(cel(bron.optString("afzender"))).append(';').append(cel(bron.optString("onderwerp"))).append(';').append(cel(plekken.toString())).append(';')
                    .append(cel(aan.toString())).append(';').append(s.optString("sha")).append("\r\n");
        }
        return b.toString();
    }

    static final java.util.Map<String, String> PLEK = new java.util.LinkedHashMap<>();
    static {
        PLEK.put("app", "in de app"); PLEK.put("lok", "map op de telefoon"); PLEK.put("map", "backup-map");
        PLEK.put("gd", "Google Drive"); PLEK.put("ec", "Eigen Cloud");
    }

    /** CSV in de map op de telefoon (of de backup-map): Facturen/dossier.csv. Geeft waar het staat. */
    static String exporteer(Context c) throws Exception {
        String csv = csv(alle(c));
        File tmp = new File(Facturen.dir(c), "dossier.csv.tmp");
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(tmp)) { o.write(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        try {
            android.net.Uri tree = Facturen.lokaleMap(c);
            if (tree != null && WaBackup.hasGrant(c.getContentResolver(), tree)) { Facturen.naarBoom(c, tree, "", "", "dossier.csv", tmp, true); return "de map op je telefoon (dossier.csv)"; }
            tree = WaBackup.destUri(c);
            if (tree == null) throw new Exception("Kies eerst een map op je telefoon of een backup-map");
            Facturen.naarBoom(c, tree, "", Facturen.MAP, "dossier.csv", tmp, true);
            return "je backup-map (Facturen/dossier.csv)";
        } finally { tmp.delete(); }
    }

    /** Een kort overzicht voor het scherm: [{…stuk, zonder grote velden}], nieuwste eerst. */
    static JSONArray lijst(Context c) {
        JSONArray out = new JSONArray();
        JSONObject all = alle(c);
        java.util.List<JSONObject> l = new java.util.ArrayList<>();
        for (Iterator<String> it = all.keys(); it.hasNext(); ) { JSONObject s = all.optJSONObject(it.next()); if (s != null) l.add(s); }
        java.util.Collections.sort(l, (x, y) -> Long.compare(y.optLong("opgehaald"), x.optLong("opgehaald")));
        for (JSONObject s : l) out.put(s);
        return out;
    }
}
