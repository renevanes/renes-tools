package nl.rene.tools;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Facturen aanleveren aan je accountant of boekhouder. Een ontvanger is alleen een instelling: naam, een kanaal
 * (eerste: e-mail, zie FacturenEmail) met de gegevens van dat kanaal, welke leveranciers, en wanneer (direct na het
 * ophalen, elke dag/week/maand op een vast moment, of alleen met de hand). Wat nog niet aan een ontvanger is aangeleverd
 * volgt uit het dossier (FacturenDossier: per factuur "verzonden"). Alles wordt in het dossier en een logboek bijgehouden.
 *
 * Nieuw kanaal (bijv. uploaden naar een boekhoudpakket of een map delen): een klasse die {@link Kanaal} doet en een regel
 * in {@link #KANALEN}, plus de velden op het scherm (web/src/script/63-facturen.js, faOntv…) en tests in FacturenTest.
 *
 * Opslag: getFilesDir()/facturen/aanleveren.json {lijst: [ontvanger], toestand: {id: {...}}, log: [...]}.
 */
final class FacturenAanlever {
    private FacturenAanlever() { }

    /** Een manier om facturen af te leveren. */
    interface Kanaal {
        String naam();
        /** Kanaalvelden controleren en (genormaliseerd) in uit zetten. */
        void normaal(JSONObject in, JSONObject uit) throws Exception;
        /** Wat er nog ontbreekt om te kunnen versturen (koppeling), of "". */
        String klaar(Context c, JSONObject ontv);
        /** Hoe groot één zending hooguit mag zijn (bytes aan bijlagen). */
        long maxZending(JSONObject ontv);
        /** Eén zending versturen (gooit bij een fout). Proef: alleen naar jezelf. Geeft aan wie het ging. */
        String stuur(Context c, JSONObject ontv, Zending z, boolean proef) throws Exception;
    }

    /** Wat er in één keer gaat: de stukken uit het dossier met hun bestand, en de ingevulde teksten. */
    static final class Zending {
        final List<JSONObject> stukken = new ArrayList<>();
        final List<File> bestanden = new ArrayList<>();
        long grootte;
        String sleutel = ""; // voor het voorkomen van dubbel versturen bij opnieuw proberen
    }

    static final Map<String, Kanaal> KANALEN = new LinkedHashMap<>();
    static { KANALEN.put("email", new FacturenEmail()); }

    /** Zo vaak mislukt: dan niet meer vanzelf (anders houdt een kapotte factuur de rest tegen); in het dossier: Opnieuw. */
    static final int MAX_POGINGEN = 5;
    static final String[] SCHEMA = {"direct", "dag", "week", "maand", "uit"};
    static final AtomicBoolean BEZIG = new AtomicBoolean(false);
    static volatile String voortgang = "";

    static File file(Context c) { return new File(Facturen.dir(c), "aanleveren.json"); }

    private static void check(String t) throws Exception { JSONObject o = new JSONObject(t); if (o.length() > 0 && o.optJSONArray("lijst") == null) throw new Exception("onleesbaar"); }

    static JSONObject data(Context c) {
        synchronized (Facturen.LOCK) {
            try {
                File f = file(c);
                if (!JsonFileStore.exists(f)) return new JSONObject().put("lijst", new JSONArray()).put("toestand", new JSONObject()).put("log", new JSONArray());
                JSONObject o = new JSONObject(JsonFileStore.read(f, FacturenAanlever::check));
                if (o.optJSONArray("lijst") == null) o.put("lijst", new JSONArray());
                if (o.optJSONObject("toestand") == null) o.put("toestand", new JSONObject());
                if (o.optJSONArray("log") == null) o.put("log", new JSONArray());
                return o;
            } catch (Exception e) {
                try { return new JSONObject().put("lijst", new JSONArray()).put("toestand", new JSONObject()).put("log", new JSONArray()).put("onleesbaar", true); }
                catch (Exception x) { return new JSONObject(); }
            }
        }
    }

    static void bewaarData(Context c, JSONObject d) throws Exception {
        synchronized (Facturen.LOCK) {
            if (d.optBoolean("onleesbaar")) throw new Exception("Het bestand met ontvangers is onleesbaar; er is niets veranderd");
            JsonFileStore.write(file(c), d.toString(), FacturenAanlever::check);
        }
    }

    static JSONArray lijst(Context c) { JSONArray l = data(c).optJSONArray("lijst"); return l == null ? new JSONArray() : l; }

    static JSONObject ontvanger(Context c, String id) {
        JSONArray l = lijst(c);
        for (int i = 0; i < l.length(); i++) { JSONObject o = l.optJSONObject(i); if (o != null && id.equals(o.optString("id"))) return o; }
        return null;
    }

    static JSONObject toestand(Context c, String id) {
        JSONObject t = data(c).optJSONObject("toestand").optJSONObject(id);
        return t == null ? new JSONObject() : t;
    }

    static void werkBij(Context c, String id, JSONObject w) {
        synchronized (Facturen.LOCK) {
            try {
                JSONObject d = data(c), t = d.getJSONObject("toestand");
                JSONObject x = t.optJSONObject(id) == null ? new JSONObject() : t.optJSONObject(id);
                for (Iterator<String> it = w.keys(); it.hasNext(); ) { String k = it.next(); x.put(k, w.get(k)); }
                t.put(id, x);
                bewaarData(c, d);
            } catch (Exception ignored) { }
        }
    }

    static void log(Context c, JSONObject regel) {
        synchronized (Facturen.LOCK) {
            try {
                JSONObject d = data(c);
                JSONArray oud = d.getJSONArray("log"), l = new JSONArray().put(regel);
                for (int i = 0; i < oud.length() && l.length() < 200; i++) l.put(oud.get(i));
                d.put("log", l);
                bewaarData(c, d);
            } catch (Exception ignored) { }
        }
    }

    // ================= instellingen controleren (zonder Android: getest in FacturenTest) =================

    static JSONObject normaal(JSONObject in) throws Exception {
        JSONObject o = new JSONObject();
        String naam = in.optString("naam", "").trim();
        if (naam.isEmpty()) throw new Exception("Geef de ontvanger een naam (bijv. de naam van je boekhouder)");
        if (naam.length() > 80) throw new Exception("De naam is te lang");
        o.put("naam", naam);
        String k = in.optString("kanaal", "email");
        Kanaal kanaal = KANALEN.get(k);
        if (kanaal == null) throw new Exception("Onbekend kanaal: " + k);
        o.put("kanaal", k);
        kanaal.normaal(in, o);
        o.put("actief", in.optBoolean("actief", true));
        String schema = in.optString("schema", "maand");
        if (!Facturen.in(schema, SCHEMA)) throw new Exception("Kies wanneer er aangeleverd wordt");
        o.put("schema", schema);
        String tijd = in.optString("tijd", "09:00");
        if (!tijd.matches("([01]\\d|2[0-3]):[0-5]\\d")) throw new Exception("Tijd als UU:MM, bijv. 09:00");
        o.put("tijd", tijd);
        int dag = in.optInt("dag", 1), wd = in.optInt("weekdag", 1), opnieuw = in.optInt("opnieuw", 3);
        if (dag < 1 || dag > 31) throw new Exception("Dag van de maand: 1 tot en met 31");
        if (wd < 1 || wd > 7) throw new Exception("Kies een dag van de week");
        if (opnieuw < 1 || opnieuw > 48) throw new Exception("Opnieuw proberen: na 1 tot 48 uur");
        o.put("dag", dag).put("weekdag", wd).put("opnieuw", opnieuw);
        JSONArray lev = in.optJSONArray("leveranciers"), l = new JSONArray();
        for (int i = 0; lev != null && i < lev.length(); i++) { String x = lev.optString(i, ""); if (x.matches("[a-z0-9-]{1,40}")) l.put(x); }
        o.put("leveranciers", l);
        o.put("meldFout", in.optBoolean("meldFout", true)).put("meldOk", in.optBoolean("meldOk", false));
        return o;
    }

    /** Bewaren (nieuw of bijwerken). ookOud: ook de facturen die al in het dossier staan (anders alleen nieuwe). Geeft id. */
    static String bewaar(Context c, JSONObject in) throws Exception {
        JSONObject o = normaal(in);
        synchronized (Facturen.LOCK) {
            JSONObject d = data(c);
            JSONArray l = d.getJSONArray("lijst");
            String id = in.optString("id", "");
            int plek = -1;
            Set<String> bezet = new HashSet<>();
            for (int i = 0; i < l.length(); i++) { String x = l.getJSONObject(i).optString("id"); bezet.add(x); if (x.equals(id)) plek = i; }
            if (plek < 0) {
                id = Facturen.uniekId(Facturen.veiligId(o.optString("naam")).toLowerCase(Locale.ROOT), bezet);
                o.put("vanaf", in.optBoolean("ookOud") ? 0 : System.currentTimeMillis()).put("gemaakt", System.currentTimeMillis());
            } else {
                JSONObject oud = l.getJSONObject(plek);
                o.put("vanaf", in.optBoolean("ookOud") ? 0 : oud.optLong("vanaf")).put("gemaakt", oud.optLong("gemaakt"));
            }
            o.put("id", id);
            if (plek < 0) l.put(o); else l.put(plek, o);
            // Nieuw: de eerste keer op het eerstvolgende geplande moment (niet meteen, tenzij "direct")
            if (plek < 0) d.getJSONObject("toestand").put(id, new JSONObject().put("lastOk", System.currentTimeMillis()).put("ok", true));
            bewaarData(c, d);
            return id;
        }
    }

    static void verwijder(Context c, String id) throws Exception {
        synchronized (Facturen.LOCK) {
            JSONObject d = data(c);
            JSONArray l = d.getJSONArray("lijst"), n = new JSONArray();
            for (int i = 0; i < l.length(); i++) if (!id.equals(l.getJSONObject(i).optString("id"))) n.put(l.get(i));
            d.put("lijst", n);
            d.getJSONObject("toestand").remove(id);
            bewaarData(c, d);
        }
    }

    // ================= wat moet er nog heen =================

    /** Valt dit stuk onder deze ontvanger (leverancier, vanaf) en is het nog niet aangeleverd? (zonder Android) */
    static boolean open(JSONObject ontv, JSONObject stuk) { return open(ontv, stuk, java.util.Collections.<String>emptySet()); }

    /** overgeslagen = leveranciers uit de mail die Rene overslaat: die gaan alleen nog mee als hij ze zelf aanvinkte. */
    static boolean open(JSONObject ontv, JSONObject stuk, java.util.Set<String> overgeslagen) {
        if (stuk == null) return false;
        JSONArray lev = ontv.optJSONArray("leveranciers");
        boolean zelf = false;
        if (lev != null && lev.length() > 0) { // de mailbron aangevinkt = alles uit die mail, ook leveranciers die er later bij komen
            boolean ja = false;
            for (int i = 0; i < lev.length(); i++) {
                if (lev.optString(i).equals(stuk.optString("leverancier"))) { ja = true; zelf = true; }
                else if (lev.optString(i).equals(stuk.optString("via"))) ja = true;
            }
            if (!ja) return false;
        }
        JSONObject v = stuk.optJSONObject("verzonden"), x = v == null ? null : v.optJSONObject(ontv.optString("id"));
        if (!zelf && overgeslagen.contains(stuk.optString("leverancier")) && !(x != null && x.optBoolean("opnieuw"))) return false; // "Opnieuw aanleveren" in het dossier telt wel
        if (x != null) return !x.optBoolean("ok") && x.optInt("pogingen") < MAX_POGINGEN; // na 5 keer mislukt alleen nog via Opnieuw in het dossier // al eens geprobeerd (of "opnieuw aanleveren"): open tot het lukt
        if (stuk.optLong("opgehaald") < ontv.optLong("vanaf")) return false;
        // Oude facturen die later alsnog zijn opgehaald (de hele periode inhalen): niet vanzelf meesturen; dat kan met de hand
        JSONObject m = stuk.optJSONObject("meta");
        String d = m == null ? "" : m.optString("datum", "");
        if (ontv.optLong("vanaf") > 0 && d.matches("\\d{4}-\\d{2}-\\d{2}")) {
            String grens = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date(ontv.optLong("vanaf") - 60L * 86_400_000L));
            if (d.compareTo(grens) < 0) return false;
        }
        return true;
    }

    /** Het bestand in de app, alleen als het echt in de facturenmap staat (een aangepast dossier kan niets anders meesturen). */
    static File bestand(Context c, JSONObject stuk) {
        JSONObject p = stuk.optJSONObject("plekken"), app = p == null ? null : p.optJSONObject("app");
        if (app == null || app.optLong("weg") > 0 || app.optString("pad").isEmpty()) return null;
        try {
            File f = new File(app.optString("pad")).getCanonicalFile();
            String root = new File(Facturen.dir(c), "pdf").getCanonicalPath() + File.separator;
            return f.getPath().startsWith(root) && f.isFile() ? f : null;
        } catch (Exception e) { return null; }
    }

    /** De stukken die nog naar deze ontvanger moeten (en nog in de app staan), oudste eerst. */
    static List<JSONObject> openStukken(Context c, JSONObject ontv) {
        List<JSONObject> out = new ArrayList<>();
        JSONObject all = FacturenDossier.alle(c);
        java.util.Set<String> over = new java.util.HashSet<>();
        JSONArray levs = Facturen.lijst(c);
        for (int i = 0; i < levs.length(); i++) { JSONObject l = levs.optJSONObject(i); if (l != null && Facturen.uitMail(l) && l.optBoolean("overslaan")) over.add(l.optString("id")); }
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            JSONObject s = all.optJSONObject(it.next());
            if (open(ontv, s, over) && bestand(c, s) != null) out.add(s);
        }
        java.util.Collections.sort(out, (x, y) -> Long.compare(x.optLong("opgehaald"), y.optLong("opgehaald")));
        return out;
    }

    /** In zendingen verdelen: los (één per factuur) of samen (zoveel als past in max bytes). (zonder Android) */
    static List<List<JSONObject>> verdeel(List<JSONObject> stukken, boolean samen, long max) {
        List<List<JSONObject>> out = new ArrayList<>();
        List<JSONObject> nu = null;
        long tot = 0;
        for (JSONObject s : stukken) {
            long g = s.optLong("grootte");
            if (!samen || nu == null || tot + g > max) { nu = new ArrayList<>(); out.add(nu); tot = 0; }
            nu.add(s);
            tot += g;
        }
        return out;
    }

    // ================= teksten =================

    private static final String[] MAANDEN = {"januari", "februari", "maart", "april", "mei", "juni", "juli", "augustus", "september", "oktober", "november", "december"};

    /** De maand(en) van de facturen: "oktober 2026" of "september – oktober 2026". (zonder Android) */
    static String periode(List<JSONObject> stukken, java.util.TimeZone tz) {
        String van = null, tot = null;
        java.text.SimpleDateFormat ym = new java.text.SimpleDateFormat("yyyy-MM", Locale.US);
        ym.setTimeZone(tz);
        for (JSONObject s : stukken) {
            JSONObject m = s.optJSONObject("meta");
            String d = m == null ? "" : m.optString("datum");
            String x = d.matches("\\d{4}-(0[1-9]|1[0-2]).*") ? d.substring(0, 7) : ym.format(new java.util.Date(s.optLong("opgehaald")));
            if (van == null || x.compareTo(van) < 0) van = x;
            if (tot == null || x.compareTo(tot) > 0) tot = x;
        }
        if (van == null) return "";
        String a = maand(van), b = maand(tot);
        if (van.equals(tot)) return a;
        return van.substring(0, 4).equals(tot.substring(0, 4)) ? a.substring(0, a.lastIndexOf(' ')) + " – " + b : a + " – " + b;
    }

    private static String maand(String ym) { return MAANDEN[Integer.parseInt(ym.substring(5, 7)) - 1] + " " + ym.substring(0, 4); }

    /** Eén regel per factuur voor in de mail. (zonder Android) */
    static String regels(List<JSONObject> stukken) {
        StringBuilder b = new StringBuilder();
        for (JSONObject s : stukken) {
            JSONObject m = s.optJSONObject("meta") == null ? new JSONObject() : s.optJSONObject("meta");
            b.append("- ").append(s.optString("leverancierNaam")).append(": ").append(s.optString("naam"));
            if (!m.optString("nummer").isEmpty()) b.append(", nr. ").append(m.optString("nummer"));
            if (!m.optString("datum").isEmpty()) b.append(", ").append(m.optString("datum"));
            if (!m.optString("bedrag").isEmpty()) b.append(", € ").append(m.optString("bedrag"));
            b.append('\n');
        }
        return b.toString().trim();
    }

    /** {aantal} {lijst} {leveranciers} {periode} {datum} {naam} invullen. (zonder Android) */
    static String vul(String sjabloon, JSONObject ontv, List<JSONObject> stukken, long nu, java.util.TimeZone tz) {
        Set<String> lev = new java.util.LinkedHashSet<>();
        for (JSONObject s : stukken) lev.add(s.optString("leverancierNaam"));
        StringBuilder l = new StringBuilder();
        for (String x : lev) l.append(l.length() > 0 ? ", " : "").append(x);
        java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("d MMMM yyyy", new Locale("nl"));
        df.setTimeZone(tz);
        int n = stukken.size();
        return sjabloon.replace("{aantal}", n + (n == 1 ? " factuur" : " facturen")).replace("{lijst}", regels(stukken))
                .replace("{leveranciers}", l.toString()).replace("{periode}", periode(stukken, tz))
                .replace("{datum}", df.format(new java.util.Date(nu))).replace("{naam}", ontv.optString("naam"));
    }

    // ================= versturen =================

    /** Alles wat nog open staat naar deze ontvanger sturen (of een proef naar jezelf). Geeft {ok, msg, aantal, fouten}. */
    static JSONObject stuurNu(Context c, String id, boolean proef, boolean auto) {
        JSONObject r = new JSONObject();
        if (!BEZIG.compareAndSet(false, true)) { try { r.put("bezig", true); } catch (Exception ignored) { } return zet(r, false, "Er wordt al aangeleverd; even wachten"); }
        if (FacturenDossier.WEGHALEN.get()) { BEZIG.set(false); return zet(r, false, "Het dossier wordt nu leeggemaakt; probeer het straks nog een keer"); }
        if (!proef && FacturenIndeling.BEZIG.get()) { BEZIG.set(false); return zet(r, false, "De facturen uit je mail worden nu ingedeeld; probeer het straks nog een keer"); }
        try {
            JSONObject ontv = ontvanger(c, id);
            if (ontv == null) return zet(r, false, "Ontvanger niet gevonden");
            Kanaal k = KANALEN.get(ontv.optString("kanaal"));
            if (k == null) return zet(r, false, "Onbekend kanaal");
            String mist = k.klaar(c, ontv);
            if (!mist.isEmpty()) { r.put("koppelNodig", true); return einde(c, ontv, r, false, mist, 0, proef, auto, null); }
            if (!FacturenDossier.leesbaar(c)) return einde(c, ontv, r, false, "Het dossier is onleesbaar: er wordt niets aangeleverd (herstel eerst de opgeslagen gegevens)", 0, proef, auto, null);
            List<JSONObject> open = openStukken(c, ontv);
            if (proef) {
                Zending z = new Zending();
                if (!open.isEmpty()) { JSONObject s = open.get(0); File f = bestand(c, s); z.stukken.add(s); z.bestanden.add(f); z.grootte = f.length(); }
                z.sleutel = "proef-" + System.currentTimeMillis();
                voortgang = "Proef versturen…";
                String aan = k.stuur(c, ontv, z, true);
                return einde(c, ontv, r, true, "Proef verstuurd naar " + aan + (open.isEmpty() ? " (zonder bijlage: er staat nog niets open)" : " (met één factuur)"), 0, true, auto, null);
            }
            if (open.isEmpty()) return einde(c, ontv, r, true, "Niets aan te leveren: alles is al verstuurd", 0, false, auto, null);
            long max = k.maxZending(ontv);
            List<List<JSONObject>> delen = verdeel(open, "samen".equals(ontv.optString("bundel", "samen")), max);
            int gelukt = 0, n = 0;
            JSONArray fouten = new JSONArray(), namen = new JSONArray();
            for (List<JSONObject> deel : delen) {
                n++;
                voortgang = delen.size() == 1 ? "Versturen…" : "Versturen " + n + " van " + delen.size() + "…";
                Zending z = new Zending();
                StringBuilder sl = new StringBuilder(id);
                for (JSONObject s : deel) {
                    File f = bestand(c, s); if (f == null) continue;
                    z.stukken.add(s); z.bestanden.add(f); z.grootte += f.length(); sl.append(s.optString("sha"));
                    JSONObject x = s.optJSONObject("verzonden") == null ? null : s.optJSONObject("verzonden").optJSONObject(id);
                    long ronde = x == null ? 0 : x.optBoolean("opnieuw") ? x.optLong("t") : x.optLong("ronde");
                    if (ronde > 0) sl.append('@').append(ronde); // bewust opnieuw: een nieuwe zending (ook bij de pogingen daarna)
                }
                if (z.stukken.isEmpty()) continue;
                long t = System.currentTimeMillis();
                if (z.grootte > max) {
                    String fout = z.stukken.get(0).optString("naam") + " is te groot om te versturen (" + (z.grootte / 1_048_576 + 1) + " MB)";
                    fouten.put(fout);
                    for (JSONObject s : z.stukken) FacturenDossier.verzonden(c, s.optString("sha"), id, mislukt(s, id, t, ontv, fout).put("pogingen", MAX_POGINGEN));
                    continue;
                }
                z.sleutel = Facturen.sha(sl.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 40);
                String aan;
                try { aan = k.stuur(c, ontv, z, false); }
                catch (Throwable e) { // ook te weinig geheugen: dan telt het als een mislukte poging
                    String fout = e instanceof OutOfMemoryError ? "te groot om in een keer te versturen: kies een kleinere mail" : Facturen.kort(e);
                    fouten.put(fout);
                    for (JSONObject s : z.stukken) FacturenDossier.verzonden(c, s.optString("sha"), id, mislukt(s, id, t, ontv, fout));
                    if (gelukt == 0 && fouten.length() >= 3) break; // verbinding of koppeling klopt niet: niet alles proberen
                    continue;
                }
                gelukt += z.stukken.size();
                // Verstuurd: vastleggen. Lukt dat niet, dan stoppen (anders gaat het de volgende keer nog een keer)
                for (JSONObject s : z.stukken) {
                    namen.put(s.optString("naam"));
                    FacturenDossier.verzonden(c, s.optString("sha"), id, new JSONObject().put("ok", true).put("t", t).put("naam", ontv.optString("naam"))
                            .put("aan", aan).put("kanaal", ontv.optString("kanaal")).put("via", ontv.optString("via")).put("auto", auto));
                }
            }
            r.put("aantal", gelukt).put("fouten", fouten).put("namen", namen);
            boolean ok = fouten.length() == 0;
            String msg = ok ? gelukt + (gelukt == 1 ? " factuur" : " facturen") + " aangeleverd aan " + ontv.optString("naam")
                    : (gelukt > 0 ? gelukt + " aangeleverd, " : "") + "niet alles gelukt: " + fouten.optString(0);
            return einde(c, ontv, r, ok, msg, gelukt, false, auto, namen);
        } catch (Throwable e) {
            try { JSONObject ontv = ontvanger(c, id); if (ontv != null) return einde(c, ontv, r, false, Facturen.kort(e), 0, proef, auto, null); } catch (Throwable ignored) { }
            return zet(r, false, Facturen.kort(e));
        } finally {
            voortgang = "";
            BEZIG.set(false);
        }
    }

    private static JSONObject mislukt(JSONObject s, String id, long t, JSONObject ontv, String fout) throws Exception {
        JSONObject x = s.optJSONObject("verzonden") == null ? null : s.optJSONObject("verzonden").optJSONObject(id);
        int n = x == null || x.optBoolean("opnieuw") ? 0 : x.optInt("pogingen");
        long ronde = x == null ? 0 : x.optBoolean("opnieuw") ? x.optLong("t") : x.optLong("ronde");
        return new JSONObject().put("ok", false).put("t", t).put("naam", ontv.optString("naam")).put("fout", fout).put("pogingen", n + 1).put("ronde", ronde);
    }

    private static JSONObject zet(JSONObject r, boolean ok, String msg) { try { r.put("ok", ok).put("msg", msg); } catch (Exception ignored) { } return r; }

    private static JSONObject einde(Context c, JSONObject ontv, JSONObject r, boolean ok, String msg, int aantal, boolean proef, boolean auto, JSONArray namen) throws Exception {
        long nu = System.currentTimeMillis();
        String id = ontv.optString("id");
        r.put("ok", ok).put("msg", msg).put("id", id).put("naam", ontv.optString("naam"));
        log(c, new JSONObject().put("t", nu).put("ontvanger", id).put("naam", ontv.optString("naam")).put("ok", ok).put("msg", msg)
                .put("aantal", aantal).put("proef", proef).put("auto", auto).put("namen", namen == null ? new JSONArray() : namen));
        if (!proef) {
            JSONObject t = new JSONObject().put("lastTry", nu).put("ok", ok).put("msg", msg).put("koppelNodig", r.optBoolean("koppelNodig"));
            if (ok) t.put("lastOk", nu);
            werkBij(c, id, t);
        }
        if (!ok) App.log(c, "FACTUREN", "aanleveren: " + msg);
        return r;
    }

    // ================= planning =================

    /** Het geplande moment als bij een leverancier (dag/week/maand); "direct" en "uit" hebben er geen. */
    static long gepland(JSONObject ontv, long nu, boolean volgende, java.util.TimeZone tz) {
        String s = ontv.optString("schema");
        if ("direct".equals(s) || "uit".equals(s) || !ontv.optBoolean("actief", true)) return volgende ? Long.MAX_VALUE : Long.MIN_VALUE;
        return Facturen.gepland(ontv, nu, volgende, tz);
    }

    /** Is het tijd om naar deze ontvanger te sturen? (zonder Android) heeftOpen = er staat iets open. */
    static boolean aanDeBeurt(JSONObject ontv, JSONObject t, boolean heeftOpen, long nu, java.util.TimeZone tz) {
        if (!ontv.optBoolean("actief", true) || "uit".equals(ontv.optString("schema")) || !heeftOpen) return false;
        boolean wacht = t.optLong("lastTry") > 0 && !t.optBoolean("ok", true) && nu - t.optLong("lastTry") < ontv.optInt("opnieuw", 3) * 3_600_000L;
        if ("direct".equals(ontv.optString("schema"))) return !wacht;
        long g = Facturen.gepland(ontv, nu, false, tz);
        if (t.optLong("lastOk") >= g) return false;
        return !(t.optLong("lastTry") >= g && wacht);
    }

    /** Ontvangers die nu aan de beurt zijn. */
    static List<String> aanDeBeurt(Context c, long nu) {
        List<String> out = new ArrayList<>();
        JSONArray l = lijst(c);
        java.util.TimeZone tz = Regio.zone(c);
        for (int i = 0; i < l.length(); i++) {
            JSONObject o = l.optJSONObject(i);
            if (o == null || !o.optBoolean("actief", true) || "uit".equals(o.optString("schema"))) continue;
            if (aanDeBeurt(o, toestand(c, o.optString("id")), !openStukken(c, o).isEmpty(), nu, tz)) out.add(o.optString("id"));
        }
        return out;
    }

    /** Eerstvolgende moment om naar een ontvanger te sturen, of Long.MAX_VALUE. */
    static long volgende(Context c, long nu) {
        long m = Long.MAX_VALUE;
        JSONArray l = lijst(c);
        java.util.TimeZone tz = Regio.zone(c);
        for (int i = 0; i < l.length(); i++) {
            JSONObject o = l.optJSONObject(i);
            if (o == null || !o.optBoolean("actief", true) || "uit".equals(o.optString("schema"))) continue;
            JSONObject t = toestand(c, o.optString("id"));
            boolean open = !openStukken(c, o).isEmpty();
            long retry = Math.max(nu + 60_000L, t.optLong("lastTry") + o.optInt("opnieuw", 3) * 3_600_000L);
            boolean fout = t.optLong("lastTry") > 0 && !t.optBoolean("ok", true);
            if ("direct".equals(o.optString("schema"))) { if (open) m = Math.min(m, fout ? retry : nu + 60_000L); continue; }
            m = Math.min(m, Facturen.gepland(o, nu, true, tz));
            if (open && t.optLong("lastOk") < Facturen.gepland(o, nu, false, tz)) m = Math.min(m, fout ? retry : nu + 60_000L);
        }
        return m;
    }

    static boolean nodig(Context c) {
        JSONArray l = lijst(c);
        for (int i = 0; i < l.length(); i++) { JSONObject o = l.optJSONObject(i); if (o != null && o.optBoolean("actief", true) && !"uit".equals(o.optString("schema"))) return true; }
        return false;
    }

    // ================= meldingen =================

    static void melding(Context c, JSONObject ontv, JSONObject r) {
        try {
            boolean ok = r.optBoolean("ok");
            if (ok && (!ontv.optBoolean("meldOk", false) || r.optInt("aantal") == 0)) return;
            if (!ok && !ontv.optBoolean("meldFout", true)) return;
            String titel = ok ? "Facturen aangeleverd aan " + ontv.optString("naam") : "Aanleveren aan " + ontv.optString("naam") + " mislukt";
            String tekst = r.optString("msg");
            Facturen.kanaal(c);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, NotifCenter.ch(c, Facturen.CHANNEL)) : new Notification.Builder(c);
            Intent i = new Intent(c, MainActivity.class).putExtra("open", "facturen").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int nr = 4490 + (Math.abs(ontv.optString("id").hashCode()) % 9);
            NotifCenter.post(c, nr, b.setSmallIcon(R.drawable.ic_backup).setContentTitle(titel).setContentText(tekst)
                    .setStyle(new Notification.BigTextStyle().bigText(tekst)).setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(c, nr, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
        } catch (Throwable ignored) { }
    }

    /** Alleen bij de eerste fout na een geslaagde keer een melding (niet bij elke nieuwe poging). */
    static boolean eersteFout(Context c, String id) {
        JSONArray log = data(c).optJSONArray("log");
        for (int i = 1; log != null && i < log.length(); i++) { // 0 = deze keer
            JSONObject r = log.optJSONObject(i);
            if (r == null || !id.equals(r.optString("ontvanger")) || r.optBoolean("proef")) continue;
            return r.optBoolean("ok");
        }
        return true;
    }

    /** Voor het scherm: ontvangers met toestand en wat er open staat. */
    static JSONArray overzicht(Context c) throws Exception {
        JSONArray l = lijst(c), out = new JSONArray();
        long nu = System.currentTimeMillis();
        for (int i = 0; i < l.length(); i++) {
            JSONObject o = new JSONObject(l.getJSONObject(i).toString());
            List<JSONObject> open = openStukken(c, o);
            long v = gepland(o, nu, true, Regio.zone(c));
            Kanaal k = KANALEN.get(o.optString("kanaal"));
            o.put("toestand", toestand(c, o.optString("id"))).put("open", open.size()).put("volgende", v == Long.MAX_VALUE ? 0 : v)
                    .put("mist", k == null ? "Onbekend kanaal" : k.klaar(c, o));
            out.put(o);
        }
        return out;
    }
}
