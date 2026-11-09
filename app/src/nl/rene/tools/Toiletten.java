package nl.rene.tools;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Toiletten en douches: bronnen (instellingen), opslag en de eigen laag (bewerken, afwijzen, toevoegen).
 * Een bron is alleen een instelling (sjablonen in web/toiletten-bronnen.json); ToilettenScan haalt de gegevens op,
 * ToilettenSamen voegt ze samen en zoekt dichtbij. Je locatie gaat nooit mee: bronnen worden in hun geheel gedownload.
 * Opslag: toiletten/ (bronnen, toestand, eigen laag, gedeeld; gaat mee in "Alle appgegevens", niet in de Android-backup)
 * en toiletten-cache/ (de gedownloade bronnen; nergens in een backup, opnieuw op te halen).
 */
final class Toiletten {
    private Toiletten() { }

    static final int FORMAAT = 1;
    static final String ASSET = "toiletten-bronnen.json";
    static final Object LOCK = new Object();

    // Eigenschappen als bits; zelfde waarden als tools/toiletten/regels.json en 73-toiletten.js
    static final int TOILET = 1, DOUCHE = 2, MEESTAL = 4, GRATIS = 8, BETAALD = 16, ROLSTOEL = 32, ROLSTOEL_BEPERKT = 64,
            VERSCHOON = 128, KLANTEN = 256, URINOIR = 512, ALTIJD = 1024, ALLE_BITS = 2047;
    static final String[] BIT_NAAM = {"toilet", "douche", "meestal", "gratis", "betaald", "rolstoel", "rolstoelBeperkt",
            "verschoon", "klanten", "urinoir", "altijd"};
    static final String[] SOORTEN = {"toilet", "douche", "tank", "verzorg", "rust", "camping", "haven", "station", "horeca",
            "winkel", "biblio", "zorg", "sport", "strand", "overig"};
    static final String[] BRON_SOORT = {"tools", "geojson", "arcgis", "csv"};
    static final String[] VELDEN = {"id", "naam", "uren", "lat", "lon", "rolstoel", "verschoon", "gratis", "prijs", "douche"};
    static final String[] ZET = {"klanten", "rolstoel", "urinoir", "gratis", "betaald", "verschoon", "douche", "overslaan"};
    static final String[] EXTRA = {"rolstoel", "gratis", "douche", "toilet", "klanten", "verschoon"};
    static final String[] REDEN = {"weg", "dicht", "prive", "klanten", "anders"};
    static final int MAX_TEKST = 120, MAX_BRONNEN = 20, MAX_EIGEN = 2000; // ook in de meldingen "Hooguit 20 bronnen" en "Hooguit 2000 eigen wijzigingen"

    static File dir(Context c) { return new File(c.getFilesDir(), "toiletten"); }
    static File cacheDir(Context c) { return new File(c.getFilesDir(), "toiletten-cache"); }
    static File bronnenFile(Context c) { return new File(dir(c), "bronnen.json"); }
    static File toestandFile(Context c) { return new File(dir(c), "toestand.json"); }
    static File eigenFile(Context c) { return new File(dir(c), "eigen.json"); }
    static File gedeeldFile(Context c) { return new File(dir(c), "gedeeld.json"); }
    static File cacheFile(Context c, String id) { return new File(cacheDir(c), Facturen.veiligId(id) + ".json.gz"); }

    static int bit(String naam) {
        for (int i = 0; i < BIT_NAAM.length; i++) if (BIT_NAAM[i].equals(naam)) return 1 << i;
        return 0;
    }

    /** Toilet en douche zijn een eigen groep; de rest per soort plek. Alleen binnen een groep wordt samengevoegd. */
    static String groep(String soort, int bits) {
        if ("toilet".equals(soort) || "douche".equals(soort)) return (bits & TOILET) != 0 || "toilet".equals(soort) ? "toilet" : "douche";
        return soort;
    }

    static String kort(String s, int max) {
        String v = s == null ? "" : s.replaceAll("[\\u0000-\\u001f\\u007f]+", " ").replaceAll("\\s+", " ").trim();
        return v.length() <= max ? v : v.substring(0, max - 1).trim() + "…";
    }

    // ================= bronnen (zonder Android: getest in ToilettenTest) =================

    private static String tekst(JSONObject o, String k, int max, String def) throws Exception {
        String v = o.optString(k, def == null ? "" : def).trim();
        if (v.length() > max) throw new Exception(Taal.T("Een van de velden is te lang"));
        return v;
    }

    /** leeg: de melding als het adres nodig is maar ontbreekt (leeg = mag ontbreken); geenHttps: als het niet met https:// begint. */
    static String adres(String v, String leeg, String geenHttps) throws Exception {
        if (v.isEmpty()) { if (!leeg.isEmpty()) throw new Exception(leeg); return v; }
        if (!v.matches("(?i)https://[^\\s/?#@]+(?:[/?#][^\\s]*)?")) throw new Exception(geenHttps);
        return v;
    }

    /** ArcGIS-laag: altijd /query met alle velden, in graden (WGS84) en als GeoJSON. */
    static String arcgisUrl(String u) {
        String basis = u, q = "";
        int i = u.indexOf('?');
        if (i >= 0) { basis = u.substring(0, i); q = u.substring(i + 1); }
        basis = basis.replaceAll("/+$", "");
        if (!basis.endsWith("/query")) basis += "/query";
        StringBuilder b = new StringBuilder(q);
        String lq = "&" + q.toLowerCase(Locale.ROOT);
        if (!lq.contains("&where=")) b.append(b.length() > 0 ? "&" : "").append("where=1%3D1");
        if (!lq.contains("&outfields=")) b.append("&outFields=*");
        if (!lq.contains("&outsr=")) b.append("&outSR=4326");
        if (!lq.contains("&f=")) b.append("&f=geojson");
        return basis + "?" + b.toString().replaceAll("^&", "");
    }

    /** Controleert een bron en vult ontbrekende keuzes aan. Fout = Nederlandse uitleg voor in de app. */
    static JSONObject normaal(JSONObject in) throws Exception {
        if (in == null) throw new Exception(Taal.T("Geen instellingen"));
        JSONObject o = new JSONObject();
        String naam = tekst(in, "naam", 60, "");
        if (naam.isEmpty()) throw new Exception(Taal.T("Geef de bron een naam"));
        o.put("naam", naam);
        String id = in.optString("id", "").trim();
        id = Facturen.veiligId(id.isEmpty() ? naam : id);
        o.put("id", id.equals("eigen") ? "eigen-bron" : id);
        o.put("sjabloon", Facturen.veiligId(in.optString("sjabloon", "eigen")));
        String soort = in.optString("soort", "geojson");
        if (!Facturen.in(soort, BRON_SOORT)) throw new Exception(Taal.T("Onbekende soort bron"));
        o.put("soort", soort);
        String url = adres(tekst(in, "url", 1000, ""), Taal.T("Vul het adres van de gegevens in"), Taal.T("Het adres van de gegevens moet beginnen met https://"));
        o.put("url", "arcgis".equals(soort) ? arcgisUrl(url) : url);
        o.put("metaUrl", adres(tekst(in, "metaUrl", 1000, ""), "", Taal.T("Het adres van meta.json moet beginnen met https://")));
        o.put("info", adres(tekst(in, "info", 1000, ""), "", Taal.T("Het adres met uitleg moet beginnen met https://")));
        String lic = tekst(in, "licentie", MAX_TEKST, "");
        if (lic.isEmpty()) throw new Exception(Taal.T("Vul de licentie of bronvermelding in (staat bij de gegevens)"));
        o.put("licentie", lic);
        o.put("tip", tekst(in, "tip", 400, ""));
        o.put("aan", in.optBoolean("aan", true));
        String schema = in.optString("schema", "week");
        if (!Facturen.in(schema, Facturen.SCHEMA)) throw new Exception(Taal.T("Onbekende keuze voor hoe vaak"));
        o.put("schema", schema);
        o.put("dag", Math.max(1, Math.min(31, in.optInt("dag", 1))));
        o.put("weekdag", Math.max(1, Math.min(7, in.optInt("weekdag", 2))));
        String tijd = in.optString("tijd", "04:00").trim();
        if (!tijd.matches("([01]?\\d|2[0-3]):[0-5]\\d")) throw new Exception(Taal.T("Vul een tijd in als 04:00"));
        o.put("tijd", tijd.length() == 4 ? "0" + tijd : tijd);
        o.put("opnieuw", Math.max(1, Math.min(24, in.optInt("opnieuw", 6))));
        o.put("wifi", in.optBoolean("wifi", true));
        if ("tools".equals(soort)) return o;
        JSONObject vi = in.optJSONObject("velden"), vo = new JSONObject();
        if (vi != null) for (String k : VELDEN) {
            String v = tekst(vi, k, 80, "");
            if (!v.isEmpty()) vo.put(k, v);
        }
        if ("csv".equals(soort) && (!vo.has("lat") || !vo.has("lon")) && !vo.has("punt"))
            vo.put("lat", vo.optString("lat", "lat|latitude|breedtegraad|y")).put("lon", vo.optString("lon", "lon|lng|longitude|lengtegraad|x"));
        o.put("velden", vo);
        String plek = in.optString("plek", "toilet");
        if (!Facturen.in(plek, SOORTEN)) throw new Exception(Taal.T("Onbekende soort plek"));
        o.put("plek", plek);
        JSONArray ei = in.optJSONArray("extra"), eo = new JSONArray();
        if (ei != null) for (int i = 0; i < ei.length(); i++) { String v = ei.optString(i); if (Facturen.in(v, EXTRA)) eo.put(v); }
        o.put("extra", eo);
        JSONArray ri = in.optJSONArray("regels"), ro = new JSONArray();
        if (ri != null) {
            if (ri.length() > 20) throw new Exception(Taal.T("Hooguit 20 regels"));
            for (int i = 0; i < ri.length(); i++) {
                JSONObject r = ri.optJSONObject(i);
                if (r == null) continue;
                String veld = tekst(r, "veld", 80, ""), pat = tekst(r, "patroon", 100, ""), zet = r.optString("zet", "");
                if (veld.isEmpty() || pat.isEmpty()) continue;
                if (!Facturen.in(zet, ZET)) throw new Exception(Taal.T("Onbekende regel"));
                try { Pattern.compile(pat); } catch (Exception e) { throw new Exception(Taal.fill(Taal.T("Geen geldig zoekpatroon: {patroon}"), "patroon", pat)); }
                if (riskant(pat)) throw new Exception(Taal.T("Dit zoekpatroon kan heel traag worden: geen herhaling binnen een herhaling en hooguit drie keer + of *"));
                ro.put(new JSONObject().put("veld", veld).put("patroon", pat).put("zet", zet));
            }
        }
        o.put("regels", ro);
        return o;
    }

    /**
     * Een zoekpatroon dat eindeloos kan duren (Android kan een zoekopdracht niet afbreken): een herhaling van een groep
     * die zelf herhaalt of kiest ((a+)+, (.*a){20}, (a|aa)*, (a?){25}), of meer dan drie keer + of *. Ook ? en {m,n}
     * tellen mee: samen niet meer manieren om een veld (hooguit {@link #VELD_MAX} tekens) te verdelen dan drie keer + of *
     * (a?a?a?…a? of .{0,9}.{0,9}… zonder groep kan anders ook eindeloos duren).
     */
    static boolean riskant(String p) {
        java.util.ArrayDeque<boolean[]> stapel = new java.util.ArrayDeque<>();
        boolean[] groep = {false};
        boolean naGroep = false, groepHerhaalt = false, naOpen = false, naHerhaling = false;
        int open = 0;
        double kost = 1;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            boolean herhaal = c == '+' || c == '*' || c == '{';
            if (herhaal && naGroep && groepHerhaalt) return true;
            naGroep = false;
            boolean wasOpen = naOpen, wasHerhaling = naHerhaling;
            naOpen = false; naHerhaling = false;
            if (c == '\\') { i++; continue; }
            if (c == '[') {
                int j = i + 1;
                if (j < p.length() && p.charAt(j) == '^') j++;
                if (j < p.length() && p.charAt(j) == ']') j++;
                while (j < p.length() && p.charAt(j) != ']') { if (p.charAt(j) == '\\') j++; j++; }
                i = j;
                continue;
            }
            if (c == '(') { stapel.push(groep); groep = new boolean[]{false}; naOpen = true; continue; }
            if (c == ')') {
                naGroep = true; groepHerhaalt = groep[0];
                groep = stapel.isEmpty() ? new boolean[]{false} : stapel.pop();
                if (groepHerhaalt) groep[0] = true;
                continue;
            }
            if (c == '+' || c == '*') { open++; if (!wasHerhaling) kost *= VELD_MAX; naHerhaling = true; }
            else if (c == '?' && !wasOpen) { if (!wasHerhaling) kost *= 2; naHerhaling = true; } // na een herhaling: zuinig (a+?), telt niet
            else if (c == '{') { int j = p.indexOf('}', i); if (j > i) { kost *= bereik(p.substring(i + 1, j)); i = j; naHerhaling = true; } }
            boolean keuze = c == '|' || (c == '?' && i > 0 && p.charAt(i - 1) != '(');
            if (herhaal || keuze) groep[0] = true;
        }
        return open > 3 || kost > 4.0 * VELD_MAX * VELD_MAX * VELD_MAX; // ruimte voor drie keer + of * met wat ? erbij
    }

    /** Zoveel tekens van een veld bekijkt een regel (ToilettenScan.past). */
    static final int VELD_MAX = 120;

    /** Hoeveel keuzes {m}, {m,} of {m,n} geeft (hooguit VELD_MAX). */
    private static double bereik(String s) {
        int k = s.indexOf(',');
        if (k < 0) return 1;
        try {
            String hi = s.substring(k + 1).trim();
            if (hi.isEmpty()) return VELD_MAX;
            return Math.max(1, Math.min(VELD_MAX, Long.parseLong(hi) - Long.parseLong(s.substring(0, k).trim()) + 1));
        } catch (NumberFormatException e) { return VELD_MAX; }
    }

    static JSONArray checkLijst(JSONArray a) throws Exception {
        JSONArray out = new JSONArray();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int i = 0; i < a.length() && out.length() < MAX_BRONNEN; i++) {
            JSONObject b = normaal(a.getJSONObject(i));
            if (ids.add(b.getString("id"))) out.put(b);
        }
        return out;
    }

    /** Bij het lezen: een bron die niet (meer) klopt valt weg, de andere blijven. */
    static JSONArray leesLijst(JSONArray a) {
        JSONArray out = new JSONArray();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int i = 0; a != null && i < a.length() && out.length() < MAX_BRONNEN; i++) {
            try {
                JSONObject b = normaal(a.getJSONObject(i));
                if (ids.add(b.getString("id"))) out.put(b);
            } catch (Exception ignored) { }
        }
        return out;
    }

    static JSONArray sjablonen(Context c) {
        try (InputStream in = c.getAssets().open(ASSET)) {
            return new JSONObject(new String(Facturen.lees(in, 1_000_000), StandardCharsets.UTF_8)).getJSONArray("sjablonen");
        } catch (Throwable e) { return new JSONArray(); }
    }

    /** Ingesteld = Rene heeft de gegevens een keer opgehaald of een bron bewaard; pas dan plant de app zelf scans. */
    static boolean ingesteld(Context c) { return JsonFileStore.exists(bronnenFile(c)); }

    /** De bronnen; vóór het instellen de sjablonen die standaard aan staan. Onleesbaar bestand = fout: dan niets overschrijven. */
    static JSONArray bewaardeBronnen(Context c) throws Exception {
        synchronized (LOCK) {
            if (!ingesteld(c)) return standaard(sjablonen(c));
            try { return leesLijst(new JSONObject(JsonFileStore.read(bronnenFile(c), Toiletten::checkBronnen)).optJSONArray("bronnen")); }
            catch (Exception e) { throw new Exception(Taal.T("De bronnen zijn niet leesbaar; er is niets overschreven"), e); }
        }
    }

    static JSONArray bronnen(Context c) {
        try { return bewaardeBronnen(c); }
        catch (Throwable e) { App.log(c, "TOILET", "bronnen onleesbaar: " + e.getClass().getSimpleName()); return new JSONArray(); }
    }

    static JSONArray standaard(JSONArray sj) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < sj.length(); i++) {
            JSONObject s = sj.optJSONObject(i);
            if (s == null || !s.optBoolean("standaard")) continue;
            try { out.put(normaal(new JSONObject(s.toString()).put("sjabloon", s.optString("sjabloon")).put("id", s.optString("sjabloon")))); } catch (Exception ignored) { }
        }
        return out;
    }

    private static void checkBronnen(String t) throws Exception {
        JSONArray a = new JSONObject(t).optJSONArray("bronnen");
        if (a == null) throw new Exception(Taal.T("Geen lijst"));
    }

    static void bewaarLijst(Context c, JSONArray lijst) throws Exception {
        synchronized (LOCK) {
            JsonFileStore.write(bronnenFile(c), new JSONObject().put("formaat", FORMAAT).put("bronnen", checkLijst(lijst)).toString(), Toiletten::checkBronnen);
        }
    }

    /** Een id dat nog niet in de lijst staat (een nieuwe bron met dezelfde naam als een bestaande). */
    static String vrijId(JSONArray l, String id) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int i = 0; i < l.length(); i++) { JSONObject x = l.optJSONObject(i); if (x != null) ids.add(x.optString("id")); }
        String v = id;
        String basis = id.length() > 36 ? id.substring(0, 36) : id;
        for (int n = 2; ids.contains(v); n++) v = basis + "-" + n;
        return v;
    }

    static JSONObject bewaarBron(Context c, JSONObject in) throws Exception {
        boolean nieuwe = in.optString("id", "").trim().isEmpty();
        JSONObject b = normaal(in);
        synchronized (LOCK) {
            JSONArray oud = bewaardeBronnen(c), nieuw = new JSONArray();
            if (nieuwe) b.put("id", vrijId(oud, b.getString("id")));
            boolean gevonden = false;
            for (int i = 0; i < oud.length(); i++) {
                JSONObject x = oud.getJSONObject(i);
                if (x.getString("id").equals(b.getString("id"))) { nieuw.put(b); gevonden = true; } else nieuw.put(x);
            }
            if (!gevonden) {
                if (nieuw.length() >= MAX_BRONNEN) throw new Exception(Taal.T("Hooguit 20 bronnen"));
                nieuw.put(b);
            }
            bewaarLijst(c, nieuw);
        }
        return b;
    }

    static void verwijderBron(Context c, String id) throws Exception {
        synchronized (LOCK) {
            JSONArray oud = bewaardeBronnen(c), nieuw = new JSONArray();
            for (int i = 0; i < oud.length(); i++) if (!oud.getJSONObject(i).getString("id").equals(id)) nieuw.put(oud.getJSONObject(i));
            bewaarLijst(c, nieuw);
            JSONObject t = toestand(c);
            t.remove(id);
            bewaarToestand(c, t);
            //noinspection ResultOfMethodCallIgnored
            cacheFile(c, id).delete();
        }
        ToilettenSamen.wis();
    }

    static JSONObject bron(Context c, String id) {
        JSONArray a = bronnen(c);
        for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i).optString("id").equals(id)) return a.optJSONObject(i);
        return null;
    }

    // ================= toestand per bron =================

    private static void checkObject(String t) throws Exception { new JSONObject(t); }

    static JSONObject toestand(Context c) {
        synchronized (LOCK) {
            try { return new JSONObject(JsonFileStore.read(toestandFile(c), Toiletten::checkObject)); } catch (Throwable e) { return new JSONObject(); }
        }
    }

    static void bewaarToestand(Context c, JSONObject t) throws Exception {
        synchronized (LOCK) { JsonFileStore.writeCurrent(toestandFile(c), t.toString(), Toiletten::checkObject); }
    }

    // ================= eigen laag (zonder Android: getest in ToilettenTest) =================
    // eigen.json: {"wijzig": {sleutel: {la, lo, s, t, v: {s, f, n, u, notitie}, was: {s, f, n, u}}},
    //              "afgewezen": {sleutel: {la, lo, s, n, reden, tekst, t}},
    //              "toegevoegd": {"eigen:<id>": {la, lo, s, f, n, u, notitie, t}}}
    // Sleutel = "<bron>:<id in de bron>". Een bron die zijn id's wisselt: dan telt dezelfde soort binnen 25 m (ToilettenSamen).

    static JSONObject leegEigen() throws Exception {
        return new JSONObject().put("wijzig", new JSONObject()).put("afgewezen", new JSONObject()).put("toegevoegd", new JSONObject());
    }

    static void checkEigen(String t) throws Exception {
        JSONObject o = new JSONObject(t);
        if (o.length() == 0) return;
        for (String k : new String[]{"wijzig", "afgewezen", "toegevoegd"}) if (o.has(k) && o.optJSONObject(k) == null) throw new Exception(Taal.T("Onverwachte eigen laag"));
    }

    static JSONObject eigen(Context c) {
        synchronized (LOCK) {
            try {
                JSONObject o = new JSONObject(JsonFileStore.read(eigenFile(c), Toiletten::checkEigen));
                for (String k : new String[]{"wijzig", "afgewezen", "toegevoegd"}) if (o.optJSONObject(k) == null) o.put(k, new JSONObject());
                return o;
            } catch (Throwable e) {
                App.log(c, "TOILET", "eigen laag onleesbaar: " + e.getClass().getSimpleName());
                return null;
            }
        }
    }

    static void bewaarEigen(Context c, JSONObject e) throws Exception {
        synchronized (LOCK) { JsonFileStore.write(eigenFile(c), e.toString(), Toiletten::checkEigen); }
        ToilettenSamen.wis();
    }

    private static double coord(JSONObject in, String k, double min, double max) throws Exception {
        double v = in.optDouble(k, Double.NaN);
        if (Double.isNaN(v) || v < min || v > max) throw new Exception(Taal.T("Onbekende plek op de kaart"));
        return Math.round(v * 1e6) / 1e6;
    }

    /** De waarden die Rene zelf invult: soort, eigenschappen, naam, openingstijden en een notitie. */
    static JSONObject waarden(JSONObject in) throws Exception {
        String s = in.optString("s", "toilet");
        if (!Facturen.in(s, SOORTEN)) throw new Exception(Taal.T("Onbekende soort plek"));
        int f = in.optInt("f", TOILET) & ALLE_BITS & ~MEESTAL;
        if ((f & (TOILET | DOUCHE)) == 0) f |= "douche".equals(s) ? DOUCHE : TOILET;
        if ((f & GRATIS) != 0) f &= ~BETAALD;
        if ((f & ROLSTOEL) != 0) f &= ~ROLSTOEL_BEPERKT;
        return new JSONObject().put("s", s).put("f", f).put("n", kort(in.optString("n"), 80))
                .put("u", kort(in.optString("u"), MAX_TEKST)).put("notitie", kort(in.optString("notitie"), 300));
    }

    static String nieuwId() {
        byte[] b = new byte[6];
        new SecureRandom().nextBytes(b);
        return "eigen:" + AppData.hex(b);
    }

    static int aantal(JSONObject eigen) {
        return eigen.optJSONObject("wijzig").length() + eigen.optJSONObject("afgewezen").length() + eigen.optJSONObject("toegevoegd").length();
    }

    /**
     * Bewaart een eigen toevoeging (sleutel leeg of "eigen:…") of een bewerking van een plek uit een bron (bron = de
     * huidige waarden uit de bron, als "was": later ziet de app zo of de bron intussen iets anders zegt).
     */
    static String bewaarPlek(JSONObject eigen, JSONObject in, JSONObject bron, long nu) throws Exception {
        String k = in.optString("k", "");
        JSONObject v = waarden(in);
        if (k.isEmpty() || k.startsWith("eigen:")) {
            JSONObject t = eigen.getJSONObject("toegevoegd");
            if (k.isEmpty()) {
                if (aantal(eigen) >= MAX_EIGEN) throw new Exception(Taal.T("Hooguit 2000 eigen wijzigingen"));
                k = nieuwId();
            } else if (!t.has(k)) throw new Exception(Taal.T("Deze plek bestaat niet meer"));
            JSONObject oud = t.optJSONObject(k);
            JSONObject o = new JSONObject(v.toString())
                    .put("la", in.has("la") || oud == null ? coord(in, "la", -90, 90) : oud.getDouble("la"))
                    .put("lo", in.has("lo") || oud == null ? coord(in, "lo", -180, 180) : oud.getDouble("lo"))
                    .put("t", nu);
            t.put(k, o);
            return k;
        }
        if (bron == null) throw new Exception(Taal.T("Deze plek staat niet meer in de bron"));
        if (!k.matches("[a-z0-9-]{1,40}:[^\\s]{1,80}")) throw new Exception(Taal.T("Onbekende plek"));
        JSONObject w = eigen.getJSONObject("wijzig");
        if (!w.has(k) && aantal(eigen) >= MAX_EIGEN) throw new Exception(Taal.T("Hooguit 2000 eigen wijzigingen"));
        JSONObject was = new JSONObject().put("s", bron.optString("s")).put("f", bron.optInt("f")).put("n", bron.optString("n")).put("u", bron.optString("u"));
        JSONObject oud = w.optJSONObject(k);
        if (oud != null && oud.optJSONObject("was") != null && !in.optBoolean("houden")) was = oud.getJSONObject("was");
        int wf = was.optInt("f");
        // "Meestal een toilet" (tankstation e.d.) blijft, tenzij Rene aangeeft dat er zeker een is
        if ((wf & MEESTAL) != 0 && !in.optBoolean("zeker") && (v.getInt("f") & TOILET) != 0) v.put("f", v.getInt("f") | MEESTAL);
        if (zelfde(v, was) && (v.getInt("f") & MEESTAL) == (wf & MEESTAL) && v.optString("notitie").isEmpty()) { w.remove(k); return k; }
        w.put(k, new JSONObject().put("la", bron.getDouble("la")).put("lo", bron.getDouble("lo")).put("s", bron.optString("s"))
                .put("t", nu).put("v", v).put("was", was));
        eigen.getJSONObject("afgewezen").remove(k);
        return k;
    }

    static boolean zelfde(JSONObject a, JSONObject b) {
        return a.optString("s").equals(b.optString("s")) && (a.optInt("f") & ~MEESTAL) == (b.optInt("f") & ~MEESTAL)
                && a.optString("n").equals(b.optString("n")) && a.optString("u").equals(b.optString("u"));
    }

    static void afwijzen(JSONObject eigen, String k, String reden, String tekst, JSONObject plek, long nu) throws Exception {
        if (!Facturen.in(reden, REDEN)) throw new Exception(Taal.T("Kies een reden"));
        if (k.startsWith("eigen:")) throw new Exception(Taal.T("Je eigen plek kun je weghalen"));
        if (plek == null) throw new Exception(Taal.T("Deze plek staat niet meer in de bron"));
        JSONObject a = eigen.getJSONObject("afgewezen");
        if (!a.has(k) && aantal(eigen) >= MAX_EIGEN) throw new Exception(Taal.T("Hooguit 2000 eigen wijzigingen"));
        a.put(k, new JSONObject().put("la", plek.getDouble("la")).put("lo", plek.getDouble("lo")).put("s", plek.optString("s"))
                .put("n", kort(plek.optString("n"), 80)).put("reden", reden).put("tekst", kort(tekst, 300)).put("t", nu));
    }

    /** Terugzetten: een bewerking of afwijzing vergeten, of een eigen toevoeging weghalen. */
    static boolean terug(JSONObject eigen, String k, String wat) {
        if (!"wijzig".equals(wat) && !"afgewezen".equals(wat) && !"toegevoegd".equals(wat)) return false;
        JSONObject o = eigen.optJSONObject(wat);
        return o != null && o.remove(k) != null;
    }
}
