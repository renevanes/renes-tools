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
    static void nieuw(Context c, JSONObject lev, Facturen.Pdf p, String naam, File f) { nieuw(c, lev, p, naam, f, null, null); }

    /**
     * tekst = de tekst van de PDF als die al gelezen is (null: hier lezen). extra = velden erbij, bijv. bij facturen uit de
     * mail {via: de mailbron, kenmerken: wat de afzender en de PDF zeggen (FacturenHerken)}.
     */
    static void nieuw(Context c, JSONObject lev, Facturen.Pdf p, String naam, File f, String tekst, JSONObject extra) {
        try {
            JSONObject bron = p.bron == null ? new JSONObject() : new JSONObject(p.bron.toString());
            if (bron.optBoolean("toegevoegd")) bron.put("soort", "hand");
            else bron.put("soort", Facturen.uitMail(lev) || extra != null && extra.has("via") ? "mail" : lev.optString("soort", "website"));
            JSONObject s = new JSONObject()
                    .put("sha", p.sha).put("naam", naam).put("origineel", p.naam).put("grootte", p.data.length)
                    .put("leverancier", lev.optString("id")).put("leverancierNaam", lev.optString("naam"))
                    .put("opgehaald", System.currentTimeMillis()).put("bron", bron)
                    .put("plekken", new JSONObject().put("app", new JSONObject().put("pad", f.getAbsolutePath()).put("t", System.currentTimeMillis())))
                    .put("verzonden", new JSONObject());
            if (extra != null) for (Iterator<String> it = extra.keys(); it.hasNext(); ) { String k = it.next(); s.put(k, extra.get(k)); }
            // Factuurgegevens uit de tekst van de PDF (Android 15+), anders uit de bestandsnaam
            JSONObject meta = herken((tekst == null ? tekst(f) : tekst) + "\n" + naamZonderSha(p.naam, p.sha));
            meta.put("bron", meta.length() > 0 ? "herkend" : "");
            if (PdfDoc.canText()) meta.put("versie", HERKEN_VERSIE);
            s.put("meta", meta);
            synchronized (Facturen.LOCK) {
                JSONObject all = alle(c);
                JSONObject oud = all.optJSONObject(p.sha);
                if (oud == null) { // eerder al aangeleverd (voor verwijderen of leegmaken)? Dan niet nog eens sturen
                    JSONObject eerder = FacturenAanlever.data(c).optJSONObject("eerder");
                    JSONObject v = eerder == null ? null : eerder.optJSONObject(p.sha);
                    if (v != null) s.put("verzonden", new JSONObject(v.toString()));
                    all.put(p.sha, s);
                }
                else { // opnieuw opgehaald (bijv. na het opnieuw toevoegen van de leverancier): de plek in de app klopt weer
                    if (oud.optJSONObject("plekken") == null) oud.put("plekken", new JSONObject());
                    oud.getJSONObject("plekken").put("app", s.getJSONObject("plekken").getJSONObject("app"));
                }
                bewaar(c, all);
            }
        } catch (Throwable ignored) { }
    }

    // ---------- welke documenten horen bij één factuur ----------

    static String nummer(JSONObject s) { JSONObject m = s.optJSONObject("meta"); return m == null ? "" : m.optString("nummer", "").trim().toLowerCase(Locale.ROOT); }
    static String mailId(JSONObject s) { JSONObject b = s.optJSONObject("bron"); return b == null ? "" : b.optString("mailId", ""); }
    /** jjjj-mm van de herkende factuurdatum, of "". */
    static String maand(JSONObject s) { JSONObject m = s.optJSONObject("meta"); String d = m == null ? "" : m.optString("datum", ""); return d.matches("\\d{4}-\\d{2}-\\d{2}") ? d.substring(0, 7) : ""; }

    /**
     * Per document (sha) de sleutel van de factuur waar het bij hoort. Zelfde leverancier (bij mail: afzender) en
     * factuurnummer = één factuur (factuur, specificatie, twee keer gedownload). Een document zonder nummer uit dezelfde
     * mail als een factuur met nummer (bijv. algemene voorwaarden of een bijlage) hoort bij die factuur; documenten zonder
     * nummer in één mail samen ook. Zelfde regels als fdSamen in 63-facturen.js. Zuiver (getest).
     */
    static java.util.Map<String, String> groepen(JSONObject items) {
        java.util.Map<String, String> uit = new java.util.HashMap<>(), perMail = new java.util.HashMap<>();
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (Iterator<String> it = items.keys(); it.hasNext(); ) keys.add(it.next());
        java.util.Collections.sort(keys); // vaste volgorde
        // Zelfde nummer maar een andere maand (bijv. een klantnummer dat als factuurnummer is herkend): aparte facturen.
        // Een document met nummer maar zonder datum hoort bij de eerste factuur met dat nummer.
        java.util.Map<String, String> eersteMetNr = new java.util.HashMap<>();
        for (int ronde = 0; ronde < 2; ronde++) for (String k : keys) {
            JSONObject s = items.optJSONObject(k); if (s == null) continue;
            String nr = nummer(s), mid = mailId(s), mnd = maand(s);
            if (nr.isEmpty() || (ronde == 0) == mnd.isEmpty()) continue; // eerst de documenten met datum
            JSONObject b = s.optJSONObject("bron");
            String basis = "n|" + s.optString("leverancier") + "|" + (b == null ? "" : b.optString("afzender", "")) + "|" + nr;
            String g = !mnd.isEmpty() ? basis + "|" + mnd : eersteMetNr.containsKey(basis) ? eersteMetNr.get(basis) : basis;
            if (!eersteMetNr.containsKey(basis)) eersteMetNr.put(basis, g);
            uit.put(k, g);
            if (!mid.isEmpty() && !perMail.containsKey(s.optString("leverancier") + "|" + mid)) perMail.put(s.optString("leverancier") + "|" + mid, g);
        }
        for (String k : keys) {
            JSONObject s = items.optJSONObject(k); if (s == null || uit.containsKey(k)) continue;
            String mid = mailId(s), lev = s.optString("leverancier");
            if (!mid.isEmpty()) { String g = perMail.get(lev + "|" + mid); uit.put(k, g != null ? g : "m|" + lev + "|" + mid); }
            else uit.put(k, "s|" + k);
        }
        return uit;
    }

    /** Hoeveel facturen deze documenten samen zijn. */
    static int aantal(JSONObject items, java.util.Collection<String> shas) {
        java.util.Map<String, String> g = groepen(items);
        java.util.Set<String> s = new java.util.HashSet<>();
        for (String sha : shas) s.add(g.containsKey(sha) ? g.get(sha) : "s|" + sha);
        return s.size();
    }

    /** Hoeveel nieuwe facturen: documenten die bij een factuur horen die er al was (een extra bijlage) tellen niet. */
    static int aantalNieuw(JSONObject items, java.util.Collection<String> nieuw) {
        java.util.Map<String, String> g = groepen(items);
        java.util.Set<String> oud = new java.util.HashSet<>(), s = new java.util.HashSet<>();
        for (Iterator<String> it = items.keys(); it.hasNext(); ) { String k = it.next(); if (!nieuw.contains(k) && g.containsKey(k)) oud.add(g.get(k)); }
        for (String sha : nieuw) { String k = g.containsKey(sha) ? g.get(sha) : "s|" + sha; if (!oud.contains(k)) s.add(k); }
        return s.size();
    }

    /** Hoeveel facturen (niet documenten) er van deze leverancier zijn. */
    static int aantalVan(JSONObject items, String lev) {
        java.util.List<String> l = new java.util.ArrayList<>();
        for (Iterator<String> it = items.keys(); it.hasNext(); ) { String k = it.next(); JSONObject s = items.optJSONObject(k); if (s != null && lev.equals(s.optString("leverancier"))) l.add(k); }
        return l.isEmpty() ? 0 : aantal(items, l);
    }

    /** De datum van een document voor het overzicht: factuurdatum, anders de datum van de mail, anders de dag van ophalen. */
    static String dag(JSONObject s) {
        JSONObject m = s.optJSONObject("meta");
        String d = m == null ? "" : m.optString("datum", "");
        if (d.matches("\\d{4}-\\d{2}-\\d{2}")) return d;
        JSONObject b = s.optJSONObject("bron");
        long t = b != null && b.optLong("maildatum") > 0 ? b.optLong("maildatum") : s.optLong("opgehaald");
        return t <= 0 ? "" : new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date(t));
    }

    /**
     * Per maand (jjjj-mm) hoeveel facturen van deze leverancier, op factuurdatum. Per factuur telt het document met een
     * bedrag (de factuur zelf), anders het oudste herkende. Zuiver (getest).
     */
    static JSONObject maanden(JSONObject items, String lev) {
        JSONObject uit = new JSONObject();
        try {
            java.util.Map<String, String> g = groepen(items);
            java.util.Map<String, String> maandVan = new java.util.HashMap<>(), metBedrag = new java.util.HashMap<>();
            for (Iterator<String> it = items.keys(); it.hasNext(); ) {
                String k = it.next(); JSONObject s = items.optJSONObject(k);
                if (s == null || !lev.equals(s.optString("leverancier"))) continue;
                String d = dag(s); if (d.length() < 7) continue;
                String key = g.containsKey(k) ? g.get(k) : "s|" + k, m = d.substring(0, 7);
                JSONObject meta = s.optJSONObject("meta");
                boolean bedrag = meta != null && !meta.optString("bedrag", "").isEmpty() && !meta.optString("datum", "").isEmpty();
                if (bedrag) { if (!metBedrag.containsKey(key) || m.compareTo(metBedrag.get(key)) < 0) metBedrag.put(key, m); }
                else if (!maandVan.containsKey(key) || m.compareTo(maandVan.get(key)) < 0) maandVan.put(key, m);
            }
            maandVan.putAll(metBedrag);
            for (String m : maandVan.values()) uit.put(m, uit.optInt(m) + 1);
        } catch (Exception ignored) { }
        return uit;
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
        String lev = in.optString("leverancierNaam", "").trim();
        if (lev.length() > 80) throw new Exception("De naam van de leverancier is te lang");
        synchronized (Facturen.LOCK) {
            JSONObject all = alle(c);
            JSONObject s = all.optJSONObject(sha);
            if (s == null) throw new Exception("Factuur niet gevonden in het dossier");
            s.put("meta", m);
            if (!lev.isEmpty()) s.put("leverancierNaam", lev);
            bewaar(c, all);
        }
    }

    /**
     * Onthoud per factuur (sha) bij welke ontvangers hij al is aangeleverd, ook als hij uit het dossier gaat: komt hij
     * later terug (opnieuw opgehaald), dan gaat hij niet nog een keer naar de accountant. In aanleveren.json ("eerder").
     */
    static void onthoudVerzonden(Context c, JSONObject stukken) throws Exception {
        synchronized (Facturen.LOCK) {
            JSONObject a = FacturenAanlever.data(c), eerder = a.optJSONObject("eerder");
            if (eerder == null) eerder = new JSONObject();
            boolean iets = false;
            for (java.util.Iterator<String> it = stukken.keys(); it.hasNext(); ) {
                String sha = it.next();
                JSONObject s = stukken.optJSONObject(sha), v = s == null ? null : s.optJSONObject("verzonden");
                if (v == null) continue;
                JSONObject ok = eerder.optJSONObject(sha) == null ? new JSONObject() : eerder.getJSONObject(sha);
                for (java.util.Iterator<String> io = v.keys(); io.hasNext(); ) {
                    String o = io.next(); JSONObject x = v.optJSONObject(o);
                    if (x != null && x.optBoolean("ok")) { ok.put(o, x); iets = true; }
                }
                if (ok.length() > 0) eerder.put(sha, ok);
            }
            if (iets) { a.put("eerder", eerder); FacturenAanlever.bewaarData(c, a); }
        }
    }

    /** Leverancier-id voor facturen die Rene zelf toevoegt (zonder leverancier in Facturen). */
    static final String HANDMATIG = "handmatig";

    /**
     * Een PDF zelf aan het dossier toevoegen (bijv. een factuur die je per post of los kreeg): in de app bewaren en de
     * gegevens herkennen. levId = een bestaande leverancier, anders levNaam (eigen naam). Geeft de SHA-256.
     */
    static String voegToe(Context c, byte[] data, String bestandsnaam, String levId, String levNaam) throws Exception {
        if (data == null || !Facturen.isPdf(data)) throw new Exception("Dit is geen PDF");
        JSONObject lev = levId == null || levId.isEmpty() ? null : Facturen.leverancier(c, levId);
        String naamLev = lev != null ? lev.optString("naam") : levNaam == null ? "" : levNaam.trim();
        if (naamLev.isEmpty()) throw new Exception("Kies of vul de leverancier in");
        if (naamLev.length() > 80) throw new Exception("De naam van de leverancier is te lang");
        String sha = Facturen.sha(data);
        if (bestandsnaam != null && bestandsnaam.length() > 120) bestandsnaam = bestandsnaam.substring(0, 110) + ".pdf";
        synchronized (Facturen.LOCK) { if (alle(c).has(sha)) throw new Exception("Deze factuur staat al in het dossier"); }
        String id = lev != null ? lev.optString("id") : HANDMATIG;
        File d = Facturen.pdfDir(c, id);
        if (!d.isDirectory() && !d.mkdirs()) throw new Exception("Opslagmap maken lukt niet");
        String naam = Facturen.veiligeNaam(bestandsnaam == null || bestandsnaam.trim().isEmpty() ? naamLev + " " + sha.substring(0, 8) : bestandsnaam.trim());
        if (!naam.toLowerCase(Locale.ROOT).endsWith(".pdf")) naam += ".pdf";
        File f = new File(d, naam);
        for (int n = 2; f.exists(); n++) { f = new File(d, naam.replaceAll("(?i)\\.pdf$", "") + " (" + n + ").pdf"); }
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { o.write(data); o.getFD().sync(); }
        JSONObject l = lev != null ? lev : new JSONObject().put("id", HANDMATIG).put("naam", naamLev).put("soort", "hand");
        Facturen.Pdf p = new Facturen.Pdf(data, f.getName(), sha, null, new JSONObject().put("toegevoegd", true));
        nieuw(c, l, p, f.getName(), f);
        if (lev == null) werkBij(c, sha, new JSONObject().put("leverancierNaam", naamLev));
        synchronized (Facturen.LOCK) { if (!alle(c).has(sha)) { f.delete(); throw new Exception("Toevoegen aan het dossier lukt niet"); } }
        return sha;
    }

    /**
     * Een factuur uit het dossier en uit de app weghalen (kopieën in je backup-map, Drive of mail blijven staan).
     * opnieuw = de app mag hem de volgende keer weer ophalen; anders onthoudt de leverancier dat hij hem al had.
     */
    static void verwijder(Context c, String sha, boolean opnieuw) throws Exception {
        synchronized (Facturen.LOCK) {
            JSONObject all = alle(c), s = all.optJSONObject(sha);
            if (s == null) throw new Exception("Factuur niet gevonden in het dossier");
            FacturenRun r = FacturenRun.LOPEND.get();
            if (r != null && r.id.equals(s.optString("leverancier"))) throw new Exception("Er worden nu facturen opgehaald; probeer het straks nog een keer");
            onthoudVerzonden(c, new JSONObject().put(sha, s));
            File f = FacturenAanlever.bestand(c, s);
            if (f != null && !f.delete() && f.exists()) throw new Exception("Het bestand weghalen lukt niet");
            all.remove(sha);
            bewaar(c, all);
            String lev = s.optString("leverancier");
            if (opnieuw && !lev.isEmpty() && !HANDMATIG.equals(lev)) {
                JSONObject t = Facturen.toestand(c, lev), vw = t.optJSONObject("verwerkt");
                if (vw != null && vw.has(sha)) { vw.remove(sha); Facturen.werkBij(c, lev, new JSONObject().put("verwerkt", vw)); }
            }
        }
    }

    /** Bezig met alles weghalen: dan start er geen ophalen of aanleveren (die controleren dit na hun eigen vlag). */
    static final java.util.concurrent.atomic.AtomicBoolean WEGHALEN = new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Welke kopieën de app zelf zette en die bij "alles weghalen" weg moeten: per plek (map = backup-map, lok = map op de
     * telefoon, gd = Google Drive) {sha, plek, uri of id}. Elk bestand één keer (twee facturen kunnen naar hetzelfde
     * bestand wijzen); alleen content://-uri's en geldige Drive-id's. In de app zelf en mail (ec) tellen niet. Zuiver (getest).
     */
    static JSONArray doelen(JSONObject alles) throws Exception {
        JSONArray uit = new JSONArray();
        java.util.HashSet<String> gehad = new java.util.HashSet<>();
        for (Iterator<String> it = alles.keys(); it.hasNext(); ) {
            String sha = it.next();
            JSONObject s = alles.optJSONObject(sha), pl = s == null ? null : s.optJSONObject("plekken");
            if (pl == null) continue;
            for (String k : new String[]{"map", "lok"}) {
                JSONObject p = pl.optJSONObject(k); String u = p == null ? "" : p.optString("uri", "");
                if (!u.startsWith("content://") || !gehad.add(u)) continue;
                uit.put(new JSONObject().put("sha", sha).put("plek", k).put("uri", u));
            }
            JSONObject gd = pl.optJSONObject("gd"); String id = gd == null ? "" : gd.optString("id", "");
            if (FacturenDrive.geldigId(id) && gehad.add("gd:" + id)) uit.put(new JSONObject().put("sha", sha).put("plek", "gd").put("id", id));
        }
        return uit;
    }

    /** Bestaat dit document (nog)? Op Android 9 en ouder geeft deleteDocument bij een verdwenen bestand alleen false. */
    private static boolean bestaat(Context c, android.net.Uri u) {
        try (android.database.Cursor cu = c.getContentResolver().query(u, new String[]{android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
            return cu != null && cu.moveToFirst();
        } catch (IllegalArgumentException e) { return false; }
        catch (Exception e) { // de provider meldt "niet gevonden" soms verpakt; anders (geen toegang meer) telt het als niet gelukt
            String m = e + " " + e.getCause();
            return !(m.contains("FileNotFound") || m.toLowerCase(Locale.ROOT).contains("not found"));
        }
    }

    /**
     * Echt alles weg: eerst de opgeslagen kopieën (backup-map, map op de telefoon, Google Drive; alleen de bestanden die
     * de app zelf daar zette en in het dossier staan), daarna het dossier leeg zoals bij reset. Lukt een kopie niet (geen
     * verbinding, Google niet gekoppeld, geen toegang meer tot de map), dan blijft het dossier staan zodat je het opnieuw
     * kunt proberen; wat wel weg is, gaat uit het dossier. Mail die al verstuurd is (Eigen Cloud, aanleveren) kan niet
     * terug. Geeft {aantal, weg, mislukt, nietGevonden, leeg}. Netwerk: niet op de hoofdthread.
     */
    static JSONObject allesWeg(Context c) throws Exception {
        if (!WEGHALEN.compareAndSet(false, true)) throw new Exception("Er wordt al weggehaald; even wachten");
        try {
            if (FacturenRun.LOPEND.get() != null) throw new Exception("Er worden nu facturen opgehaald; probeer het straks nog een keer");
            if (FacturenAanlever.BEZIG.get()) throw new Exception("Er wordt nu aangeleverd; probeer het straks nog een keer");
            JSONArray d;
            synchronized (Facturen.LOCK) { d = doelen(alle(c)); }
            int weg = 0, mislukt = 0, niet = 0;
            JSONArray klaar = new JSONArray(); // {sha, plek} die weg zijn (of er al niet meer waren)
            for (int i = 0; i < d.length(); i++) {
                JSONObject x = d.getJSONObject(i);
                boolean ok;
                if (x.has("uri")) {
                    android.net.Uri u = android.net.Uri.parse(x.getString("uri"));
                    if (!bestaat(c, u)) { niet++; ok = true; }
                    else {
                        try { ok = android.provider.DocumentsContract.deleteDocument(c.getContentResolver(), u); }
                        catch (java.io.FileNotFoundException e) { ok = true; niet++; klaar.put(x); continue; }
                        catch (Exception e) { ok = false; }
                        if (ok) weg++; else mislukt++;
                    }
                } else {
                    try { FacturenDrive.verwijder(c, x.getString("id")); ok = true; weg++; }
                    catch (Exception e) {
                        // 404: al weg, of gemaakt met een ander Google-project (dan kan de app er niet meer bij)
                        ok = String.valueOf(e.getMessage()).contains("niet (meer) gevonden");
                        if (ok) niet++; else mislukt++;
                    }
                }
                if (ok) klaar.put(x);
            }
            if (mislukt > 0) { // wat weg is uit het dossier halen, de rest laten staan om het opnieuw te proberen
                synchronized (Facturen.LOCK) {
                    JSONObject all = alle(c);
                    for (int i = 0; i < klaar.length(); i++) {
                        JSONObject x = klaar.getJSONObject(i), s = all.optJSONObject(x.optString("sha")), pl = s == null ? null : s.optJSONObject("plekken");
                        if (pl != null) pl.remove(x.optString("plek"));
                    }
                    bewaar(c, all);
                }
                return new JSONObject().put("weg", weg).put("mislukt", mislukt).put("nietGevonden", niet).put("leeg", false);
            }
            int n = reset(c);
            App.log(c, "FACTUREN", "alles weggehaald");
            return new JSONObject().put("aantal", n).put("weg", weg).put("mislukt", 0).put("nietGevonden", niet).put("leeg", true);
        } finally { WEGHALEN.set(false); }
    }

    /**
     * Opnieuw beginnen: het hele dossier leeg, de facturen in de app weg, bij elke leverancier vergeten wat al
     * was opgehaald (de volgende keer begint hij weer bij het begin van de periode) en het aanleveren vergeten. Kopieën in
     * je backup-map, op Drive en in de mail blijven staan; instellingen (leveranciers, ontvangers, koppelingen) ook.
     */
    static int reset(Context c) throws Exception {
        int n;
        synchronized (Facturen.LOCK) {
            if (FacturenRun.LOPEND.get() != null) throw new Exception("Er worden nu facturen opgehaald; probeer het straks nog een keer");
            if (FacturenAanlever.BEZIG.get()) throw new Exception("Er wordt nu aangeleverd; probeer het straks nog een keer");
            JSONObject alles = alle(c);
            n = alles.length();
            onthoudVerzonden(c, alles); // wat al bij de accountant is, gaat na opnieuw ophalen niet nog een keer
            JSONArray l = Facturen.lijst(c);
            for (int i = 0; i < l.length(); i++) {
                String id = l.getJSONObject(i).optString("id");
                Facturen.werkBij(c, id, new JSONObject().put("verwerkt", new JSONObject()).put("lastOk", 0).put("lastTry", 0)
                        .put("mailSinds", 0).put("mailMeer", false).put("log", new JSONArray()).put("nieuw", 0).put("msg", "").put("ok", true)
                        .put("heelPogingen", 0).put("gedekt", 0).put("mailFouten", 0));
            }
            File pdf = new File(Facturen.dir(c), "pdf");
            File[] mappen = pdf.listFiles();
            if (mappen != null) for (File m : mappen) {
                File[] fs = m.listFiles();
                if (fs != null) for (File f : fs) f.delete();
                m.delete();
            }
            JsonFileStore.delete(file(c));
            bewaar(c, new JSONObject());
            JSONObject a = FacturenAanlever.data(c);
            a.put("toestand", new JSONObject()).put("log", new JSONArray());
            FacturenAanlever.bewaarData(c, a);
        }
        App.log(c, "FACTUREN", "dossier leeggemaakt");
        return n;
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
                if (r == null || r.optBoolean("buiten")) continue; // buiten de periode: niet bewaard
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
            nieuw.put(e.getKey(), herken(tekst + "\n" + naamZonderSha(s.optString("origineel"), s.optString("sha")) + "\n" + naamZonderSha(s.optString("naam"), s.optString("sha"))));
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
        if (v.matches("\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4}|\\d{4}-\\d{2}-\\d{2}|(19|20)\\d{2}[-_.](0[1-9]|1[0-2])")) return false; // datum of jaar-maand
        if (v.matches("[0-9a-f]{8}") && v.matches(".*[a-f].*")) return false; // de eerste 8 tekens van de SHA-256 in onze bestandsnamen
        return !v.matches("(?i)(NL)?\\d{2}[A-Z]{4}\\d{10}"); // geen IBAN
    }

    // Namen van velden zoals ze op facturen staan (ook in een kopregel van een tabel, met de waarden op de regel eronder)
    private static final String L_NUMMER = "factuur ?nummer|factuur ?nr\\.?|factuurnr|nota ?nummer|nota ?nr\\.?|invoice (?:number|no\\.?)|invoice ?#|documentnummer";
    private static final String L_DATUM = "factuurdatum|factuur datum|notadatum|documentdatum|datum factuur|invoice date|rechnungsdatum|datum";
    private static final String L_VERVAL = "uiterste betaaldatum|uiterlijk betalen(?: op| voor)?|vervaldatum|betaaldatum|due date|payment due";
    private static final String L_ANDERS = "leverdatum|subtotaal|totaal excl\\.?(?: btw)?|totaal btw|btw|klant ?nummer|klantnr\\.?|debiteur ?nummer|debiteurnr\\.?|contract ?nummer|referentie|periode|betalingskenmerk|btw[- ]?nummer|kvk[- ]?nummer|pagina";
    private static final String L_BEDRAG = "totaal te betalen|te betalen|totaalbedrag|totaal incl\\.?(?: btw)?|factuurbedrag|totaal";
    private static final String DATUMWAARDE = "\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4}|\\d{1,2} " + "(?:jan(?:uari)?|feb(?:ruari)?|mrt|maart|apr(?:il)?|mei|jun(?:i)?|jul(?:i)?|aug(?:ustus)?|sep(?:t(?:ember)?)?|okt(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\.? \\d{4}|\\d{4}-\\d{2}-\\d{2}";
    private static final String BEDRAGWAARDE = "(?:€|eur)?\\s?-?\\d{1,3}(?:\\.\\d{3})*,\\d{2}(?![\\d])|(?:€|eur)?\\s?-?\\d+\\.\\d{2}(?![\\d.])";

    /**
     * Een tabel met een kopregel (bijv. "Factuurnummer  Factuurdatum  Vervaldatum") en de waarden op de regel eronder:
     * per kolom de waarde in dezelfde volgorde. Vult alleen wat nog ontbreekt.
     */
    private static void kopregels(String t, JSONObject m) throws Exception {
        String[] regels = t.split("\\r?\\n");
        Pattern lab = Pattern.compile("(?i)(?<![a-z])(?:(" + L_VERVAL + ")|(" + L_NUMMER + ")|(" + L_DATUM + ")|(" + L_ANDERS + ")|(" + L_BEDRAG + "))(?![a-z])");
        Pattern waarde = Pattern.compile("(?i)(" + DATUMWAARDE + ")|(" + BEDRAGWAARDE + ")|([A-Z0-9][A-Z0-9./-]*)");
        for (int i = 0; i + 1 < regels.length; i++) {
            java.util.List<String> soorten = new java.util.ArrayList<>();
            Matcher x = lab.matcher(regels[i]);
            int eind = 0;
            while (x.find()) {
                soorten.add(x.group(1) != null ? "vervaldatum" : x.group(2) != null ? "nummer" : x.group(3) != null ? "datum" : x.group(5) != null ? "bedrag" : "");
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

    /** Onze eigen bestandsnaam zonder het stukje controlegetal ("… f59e6964.pdf"), zodat dat nooit als nummer telt. */
    static String naamZonderSha(String n, String sha) {
        if (n == null) return "";
        if (sha == null || sha.length() < 8) return n;
        return n.replace(" " + sha.substring(0, 8) + ".pdf", ".pdf");
    }

    /** Factuurnummer, -datum, totaalbedrag, btw en uiterste betaaldatum uit de tekst van een factuur (of de bestandsnaam). */
    static JSONObject herken(String tekst) {
        JSONObject m = new JSONObject();
        if (tekst == null) return m;
        String t = tekst.replace('\u00a0', ' ').replace('\t', ' ');
        try {
            kopregels(t, m); // eerst tabellen (kopregel met de waarden eronder): daar hoort elke waarde bij zijn eigen kolom
            // Naam en waarde op dezelfde regel, of een naam alleen op zijn regel met de waarde eronder
            Matcher x = Pattern.compile("(?im)(?:(?<![a-z])(?:" + L_NUMMER + ")[ :#.]{1,6}|^[ ]*(?:" + L_NUMMER + ")[ :#.]*\\n\\s*)([A-Z0-9][A-Z0-9./-]{2,30})").matcher(t);
            while (!m.has("nummer") && x.find()) { String n = x.group(1).replaceAll("[./-]+$", ""); if (geldigNummer(n)) m.put("nummer", n); }
            if (!m.has("nummer")) { // alleen "Factuur 123456" (niet in onze eigen bestandsnaam met controlegetal)
                x = Pattern.compile("(?i)\\bfactuur[\\s:#.]{1,4}([A-Z]{0,4}\\d[A-Z0-9/-]{3,29})\\b").matcher(t);
                while (x.find()) { String n = x.group(1).replaceAll("[./-]+$", ""); if (geldigNummer(n)) { m.put("nummer", n); break; } }
            }
            String d = m.has("datum") ? m.optString("datum") : null;
            String wv = "(" + DATUMWAARDE + "|\\d{1,2}[-/. ](?:" + MAAND + ")[-/. ]\\d{2,4})";
            String sterk = "factuurdatum|factuur datum|notadatum|documentdatum|datum factuur|invoice date|rechnungsdatum";
            if (d == null) { x = Pattern.compile("(?im)(?:(?<![a-z])(?:" + sterk + ")[ :]{1,6}|^[ ]*(?:" + sterk + ")[ :]*\\n\\s*)" + wv).matcher(t); if (x.find()) d = datum(x.group(1)); }
            if (d == null) {
                x = Pattern.compile("(?im)(?:(?<![a-z])(?<!due |payment |verval |uiterste |betaal |betalings|lever )(?:datum|date)[ :]{1,6}|^[ ]*(?:datum|date)[ :]*\\n\\s*)" + wv).matcher(t);
                if (x.find()) d = datum(x.group(1));
            }
            if (d != null) m.put("datum", d);
            String verval = "uiterste betaaldatum|uiterlijk betalen(?: op| voor)?|te betalen (?:voor|v\\u00f3\\u00f3r|uiterlijk)|vervaldatum|betaaldatum|due date|payment due";
            x = Pattern.compile("(?imu)(?:(?<![a-z])(?:" + verval + ")[ :]{1,6}|^[ ]*(?:" + verval + ")[ :]*\\n\\s*)" + wv).matcher(t);
            if (!m.has("vervaldatum") && x.find()) { String v = datum(x.group(1)); if (v != null) m.put("vervaldatum", v); }
            // Bedrag: eerst wat "te betalen" / "totaal incl." heet, dan een gewoon "totaal" (niet excl. btw of totaal btw),
            // dan "bedrag"; telkens de laatste (meestal het eindbedrag); nooit een stukje van een datum
            String getal = "(?:€|eur|euro)?\\s*(-?\\d{1,3}(?:[.\\s]\\d{3})*,\\d{2}|-?\\d+[.,]\\d{2})(?![\\d]|[.,]\\d)";
            String[] soorten = {
                    "(?<![a-z])(?:totaal te betalen|te betalen|totaalbedrag|totaal incl\\.?(?: btw)?|totaal inclusief(?: btw)?|factuurbedrag|amount due|total due|total amount)(?![a-z])",
                    "(?<![a-z])(?:totaal|total)(?![a-z])(?!\\s*(?:excl|exclusief|btw|vat|ex\\b))",
                    "(?<![a-z])bedrag(?![a-z])"};
            String best = null;
            for (String sk : soorten) {
                if (m.has("bedrag") || best != null) break;
                x = Pattern.compile("(?i)" + sk + "[^\\d€\\n]{0,30}(?:\\n[^\\d€\\n]{0,10})?" + getal).matcher(t);
                while (x.find()) best = x.group(1);
            }
            if (best != null) { String bd = bedrag(best); if (bd != null) m.put("bedrag", bd); }
            x = Pattern.compile("(?i)(?<![a-z])(?<!excl\\. |incl\\. |excl |incl |exclusief |inclusief )(?:totaal )?(?:btw|vat|omzetbelasting)(?![a-z-])(?:\\s*\\d{1,2}(?:[.,]\\d)?\\s*%)?[^\\d€\\n]{0,15}(?:€|eur)?\\s*(\\d{1,3}(?:[.\\s]\\d{3})*,\\d{2}|\\d+\\.\\d{2})(?![\\d]|[.,]\\d)").matcher(t);
            if (!m.has("btw") && x.find()) { String bd = bedrag(x.group(1)); if (bd != null) m.put("btw", bd); }
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
