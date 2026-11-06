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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Leveranciers uit de mail beheren: goedkeuren (Klopt), samenvoegen (ook "Hoort bij" een website-leverancier),
 * overslaan, één factuur naar een andere leverancier (met "Onthoud dit voor deze afzender": een regel) en eenmalig
 * herindelen van de facturen die vroeger allemaal onder de mailbron stonden. Bestanden in de app verhuizen mee;
 * kopieën buiten de app (backup-map, telefoon, Drive) blijven waar ze zijn.
 */
final class FacturenIndeling {
    private FacturenIndeling() { }

    static final int VERSIE = 1;
    static final AtomicBoolean BEZIG = new AtomicBoolean(false);

    /**
     * Iets aan de indeling veranderen: nooit tegelijk met ophalen, aanleveren, het dossier leegmaken of een andere indeling
     * (die controleren BEZIG na hun eigen vlag). Na afloop BEZIG vrijgeven.
     */
    private static void begin() throws Exception {
        if (!BEZIG.compareAndSet(false, true)) throw new Exception("De facturen uit je mail worden nu ingedeeld; probeer het straks nog een keer");
        String fout = FacturenRun.LOPEND.get() != null ? "Er worden nu facturen opgehaald; probeer het straks nog een keer"
                : FacturenAanlever.BEZIG.get() ? "Er wordt nu aangeleverd; probeer het straks nog een keer"
                : FacturenDossier.WEGHALEN.get() ? "Het dossier wordt nu leeggemaakt; probeer het straks nog een keer" : null;
        if (fout != null) { BEZIG.set(false); throw new Exception(fout); }
    }

    /** De leveranciers, of een fout als het bestand onleesbaar is (dan nooit een lege lijst wegschrijven). */
    private static JSONArray leveranciers(Context c) throws Exception {
        JSONArray levs = Facturen.leveranciers(c);
        if (levs == null) throw new Exception("De opgeslagen leveranciers zijn niet leesbaar");
        return levs;
    }

    private static JSONObject uitMail(JSONArray levs, String id) throws Exception {
        JSONObject l = Facturen.zoek(levs, id);
        if (l == null) throw new Exception("Leverancier niet gevonden");
        if (!Facturen.uitMail(l)) throw new Exception("Dit kan alleen bij een leverancier uit je mail");
        return l;
    }

    /** Klopt: de nieuwe leverancier is goed zo (geen label Nieuw en geen voorstel meer). */
    static void klopt(Context c, String id) throws Exception {
        synchronized (Facturen.LOCK) {
            JSONObject l = uitMail(Facturen.lijst(c), id);
            l.put("nieuw", false).put("voorstel", "");
            Facturen.bewaar(c, l, null);
        }
    }

    /**
     * Overslaan (aan) of terugzetten (uit). ookUitDossier: de facturen die er al zijn ook uit het dossier en de app halen.
     * Geeft bij overslaan hoeveel er weg zijn, bij terugzetten hoeveel overgeslagen facturen de volgende keer alsnog komen.
     */
    static int overslaan(Context c, String id, boolean aan, boolean ookUitDossier) throws Exception {
        begin();
        try {
            int n = 0;
            synchronized (Facturen.LOCK) {
                JSONObject l = uitMail(leveranciers(c), id);
                l.put("overslaan", aan).put("nieuw", false);
                Facturen.bewaar(c, l, null);
                if (aan && ookUitDossier) {
                    JSONObject all = FacturenDossier.alle(c);
                    List<String> shas = new ArrayList<>();
                    for (Iterator<String> it = all.keys(); it.hasNext(); ) { String k = it.next(); JSONObject s = all.optJSONObject(k); if (s != null && id.equals(s.optString("leverancier"))) shas.add(k); }
                    for (String sha : shas) { FacturenDossier.verwijder(c, sha, false); n++; } // niet opnieuw ophalen: de mailbron onthoudt ze
                }
                if (!aan) { JSONObject t = Facturen.toestand(c); n = terug(t, id); if (n > 0) Facturen.bewaarToestand(c, t); }
            }
            return n;
        } finally { BEZIG.set(false); }
    }

    /**
     * Terugzetten: de mailbronnen vergeten wat ze van deze leverancier oversloegen en zoeken de volgende keer de hele
     * periode af (wat al binnen is, komt niet dubbel). Weggehaalde facturen blijven weg. Zuiver (getest); geeft het aantal.
     */
    static int terug(JSONObject t, String id) throws Exception {
        int n = 0;
        for (Iterator<String> it = t.keys(); it.hasNext(); ) {
            JSONObject tb = t.optJSONObject(it.next()), vw = tb == null ? null : tb.optJSONObject("verwerkt");
            if (vw == null) continue;
            int was = n;
            for (Iterator<String> j = vw.keys(); j.hasNext(); ) {
                JSONObject r = vw.optJSONObject(j.next());
                if (r != null && r.optBoolean("overgeslagen") && id.equals(r.optString("l"))) { j.remove(); n++; }
            }
            if (n > was) tb.put("lastOk", 0).put("mailSinds", 0).put("mailMeer", false).put("heelPogingen", 0);
        }
        return n;
    }

    /**
     * Een dossierstuk bij een andere leverancier zetten (in het geheugen: all = het dossier, t = de hele toestand).
     * Het bestand in de app verhuist naar de map van die leverancier; de mailbron onthoudt waar hij nu staat.
     */
    static void zetBij(Context c, JSONObject all, JSONObject t, String sha, JSONObject naar) throws Exception {
        JSONObject s = all.optJSONObject(sha);
        if (s == null) return;
        String naarId = naar.optString("id");
        File f = FacturenAanlever.bestand(c, s), nf = null;
        if (f != null) nf = Facturen.verplaats(f, Facturen.pdfDir(c, naarId), f.getName()); // eerst het bestand: lukt dat niet, dan blijft alles zoals het was
        s.put("leverancier", naarId).put("leverancierNaam", naar.optString("naam"));
        if (nf != null) {
            s.getJSONObject("plekken").getJSONObject("app").put("pad", nf.getAbsolutePath());
            s.put("naam", nf.getName());
        }
        String via = s.optString("via");
        JSONObject tb = via.isEmpty() ? null : t.optJSONObject(via), vw = tb == null ? null : tb.optJSONObject("verwerkt");
        JSONObject rec = vw == null ? null : vw.optJSONObject(sha);
        if (rec != null) {
            if (naarId.equals(via)) rec.remove("l"); else rec.put("l", naarId);
            if (nf != null) rec.put("n", nf.getName());
            rec.remove("s"); // de oude map per afzender: voortaan de map van de leverancier
        }
    }

    /**
     * Eén factuur (met de documenten die erbij horen) bij een andere leverancier zetten. onthoud = voortaan komen
     * facturen van deze afzender ook daar (een regel: het adres, of bij een platform de naam; de nummers uit de PDF gaan mee).
     */
    static int verplaats(Context c, String sha, String naarId, boolean onthoud) throws Exception {
        begin();
        try { return verplaatsNu(c, sha, naarId, onthoud); } finally { BEZIG.set(false); }
    }

    private static int verplaatsNu(Context c, String sha, String naarId, boolean onthoud) throws Exception {
        JSONObject km;
        int n = 0;
        synchronized (Facturen.LOCK) {
            JSONArray levs = leveranciers(c);
            JSONObject naar = Facturen.zoek(levs, naarId);
            if (naar == null || "mail".equals(naar.optString("soort"))) throw new Exception("Leverancier niet gevonden");
            JSONObject all = FacturenDossier.alle(c), s = all.optJSONObject(sha);
            if (s == null) throw new Exception("Factuur niet gevonden in het dossier");
            if (s.optString("via").isEmpty()) throw new Exception("Alleen facturen uit je mail kunnen naar een andere leverancier");
            km = s.optJSONObject("kenmerken");
            String van = s.optString("leverancier");
            Map<String, String> g = FacturenDossier.groepen(all);
            String groep = g.get(sha);
            List<String> shas = new ArrayList<>();
            for (Iterator<String> it = all.keys(); it.hasNext(); ) {
                String k = it.next();
                JSONObject x = all.optJSONObject(k);
                if (k.equals(sha) || groep != null && groep.equals(g.get(k)) && x != null && van.equals(x.optString("leverancier")) && !x.optString("via").isEmpty()) shas.add(k);
            }
            // De regel eerst controleren (zoals bij bewaren): gaat die mis, dan is er nog niets verplaatst
            JSONArray lijst = onthoud && km != null && regel(levs, naarId, km, FacturenHerken.platforms(c)) ? Facturen.normaalAlle(levs) : null;
            JSONObject t = Facturen.toestand(c);
            Exception fout = null;
            for (String k : shas) { try { zetBij(c, all, t, k, naar); n++; } catch (Exception e) { fout = e; break; } }
            FacturenDossier.bewaar(c, all); // ook als het halverwege misging: wat verplaatst is, klopt in het dossier
            Facturen.bewaarToestand(c, t);
            if (fout != null) throw fout;
            if (lijst != null) Facturen.schrijfAlle(c, lijst);
        }
        return n;
    }

    /**
     * De regel "deze afzender hoort bij naar": het adres (bij een platform de naam, of het domein van het antwoordadres;
     * nooit het adres van het platform zelf, dat delen alle winkels daar) en de nummers uit de PDF bij naar, en weg bij de
     * andere leveranciers (anders wint daar een oude regel). platforms = de maildomeinen van platforms. Zuiver (getest).
     * Geeft true als er iets veranderde.
     */
    static boolean regel(JSONArray levs, String naarId, JSONObject km, java.util.Set<String> platforms) throws Exception {
        JSONObject naar = Facturen.zoek(levs, naarId);
        if (naar == null || km == null || FacturenHerken.NIET_HERKEND.equals(naarId)) return false;
        List<String[]> wat = new ArrayList<>();
        String soort = km.optString("soort"), adres = km.optString("adres"), domein = km.optString("domein"), naam = km.optString("naam").toLowerCase(Locale.ROOT);
        boolean platform = km.optBoolean("platform") || platforms.contains(FacturenHerken.domein(adres));
        if ("platform".equals(soort)) { if (!naam.isEmpty()) wat.add(new String[]{"namen", naam}); }
        else if ("eigen".equals(soort)) { } // Rene's eigen adres: alleen de nummers
        else if (platform) { if ("domein".equals(soort) && !domein.isEmpty() && !platforms.contains(domein)) wat.add(new String[]{"domeinen", domein}); }
        else if (!adres.isEmpty()) wat.add(new String[]{"afzenders", adres});
        for (String s : FacturenHerken.NUMMERS) for (String x : FacturenHerken.lijst(km.optJSONArray(s))) wat.add(new String[]{s, x});
        boolean bij = false;
        for (int i = 0; i < levs.length(); i++) {
            JSONObject l = levs.optJSONObject(i);
            if (l == null || l == naar || l.optJSONObject("herken") == null) continue;
            for (String[] w : wat) {
                JSONArray a = l.getJSONObject("herken").optJSONArray(w[0]);
                if (a == null) continue;
                JSONArray n = new JSONArray();
                for (int j = 0; j < a.length(); j++) if (!w[1].equals(a.optString(j))) n.put(a.optString(j)); else bij = true;
                l.getJSONObject("herken").put(w[0], n);
            }
        }
        JSONObject h = naar.optJSONObject("herken");
        if (h == null) { h = new JSONObject(); naar.put("herken", h); }
        for (String[] w : wat) {
            JSONArray a = h.optJSONArray(w[0]);
            if (a == null) { a = new JSONArray(); h.put(w[0], a); }
            if (FacturenHerken.lijst(a).contains(w[1]) || a.length() >= ("afzenders".equals(w[0]) ? 30 : "domeinen".equals(w[0]) ? 20 : FacturenHerken.MAX)) continue;
            a.put(w[1]);
            bij = true;
        }
        if (Facturen.uitMail(naar) && naar.optBoolean("nieuw")) { naar.put("nieuw", false); bij = true; }
        return bij;
    }

    /** id vervangen in een lijst met ids (zonder dubbele). Zuiver. */
    static boolean vervang(JSONArray ids, String van, String naar) throws Exception {
        if (ids == null) return false;
        boolean bij = false, heeft = false;
        JSONArray n = new JSONArray();
        for (int i = 0; i < ids.length(); i++) {
            String x = ids.optString(i);
            if (x.equals(van)) { bij = true; x = naar; }
            if (x.equals(naar)) { if (heeft) continue; heeft = true; }
            n.put(x);
        }
        if (!bij) return false;
        while (ids.length() > 0) ids.remove(0);
        for (int i = 0; i < n.length(); i++) ids.put(n.get(i));
        return true;
    }

    /**
     * Samenvoegen: alles van van (een leverancier uit de mail) gaat naar naar (uit de mail, of een website: "Hoort bij").
     * Herkenning, dossier, bestanden in de app, wat de mailbron onthoudt en de keuzes bij Aanleveren gaan mee; van verdwijnt.
     */
    static int samenvoegen(Context c, String vanId, String naarId) throws Exception {
        if (vanId.equals(naarId)) throw new Exception("Kies een andere leverancier");
        begin();
        try { return samenvoegenNu(c, vanId, naarId); } finally { BEZIG.set(false); }
    }

    private static int samenvoegenNu(Context c, String vanId, String naarId) throws Exception {
        int n = 0;
        synchronized (Facturen.LOCK) {
            JSONArray levs = leveranciers(c);
            JSONObject van = uitMail(levs, vanId), naar = Facturen.zoek(levs, naarId);
            if (naar == null || "mail".equals(naar.optString("soort"))) throw new Exception("Leverancier niet gevonden");
            JSONObject h = FacturenHerken.samen(FacturenHerken.herken(naar), FacturenHerken.herken(van));
            if (h.length() > 0) naar.put("herken", h);
            if (Facturen.uitMail(naar)) {
                JSONArray b = naar.optJSONArray("bronnen");
                if (b == null) { b = new JSONArray(); naar.put("bronnen", b); }
                for (String x : FacturenHerken.lijst(van.optJSONArray("bronnen"))) if (!FacturenHerken.lijst(b).contains(x)) b.put(x);
                if (vanId.equals(naar.optString("voorstel"))) naar.put("voorstel", "");
            }
            // De nieuwe lijst (van weg, voorstellen die naar van wezen naar naar) eerst controleren: dan gaat niets half
            JSONArray rest = new JSONArray();
            for (int i = 0; i < levs.length(); i++) {
                JSONObject l = levs.optJSONObject(i);
                if (l == null || l == van) continue;
                if (vanId.equals(l.optString("voorstel"))) l.put("voorstel", l == naar ? "" : naarId);
                rest.put(l);
            }
            JSONArray lijst = Facturen.normaalAlle(rest);
            // dossier en bestanden in de app
            JSONObject all = FacturenDossier.alle(c), t = Facturen.toestand(c);
            List<String> shas = new ArrayList<>();
            for (Iterator<String> it = all.keys(); it.hasNext(); ) { String k = it.next(); JSONObject s = all.optJSONObject(k); if (s != null && vanId.equals(s.optString("leverancier"))) shas.add(k); }
            Exception fout = null;
            for (String k : shas) { try { zetBij(c, all, t, k, naar); n++; } catch (Exception e) { fout = e; break; } }
            if (fout != null) { // wat verplaatst is, klopt; van blijft staan met de rest (nog een keer proberen)
                FacturenDossier.bewaar(c, all);
                Facturen.bewaarToestand(c, t);
                throw fout;
            }
            // wat de mailbronnen onthouden (ook overgeslagen facturen)
            for (Iterator<String> it = t.keys(); it.hasNext(); ) {
                JSONObject tb = t.optJSONObject(it.next()), vw = tb == null ? null : tb.optJSONObject("verwerkt");
                if (vw == null) continue;
                for (Iterator<String> j = vw.keys(); j.hasNext(); ) { JSONObject r = vw.optJSONObject(j.next()); if (r != null && vanId.equals(r.optString("l"))) r.put("l", naarId); }
            }
            t.remove(vanId);
            FacturenDossier.bewaar(c, all);
            Facturen.bewaarToestand(c, t);
            // Aanleveren: wie van aangevinkt had, krijgt naar
            JSONObject a = FacturenAanlever.data(c);
            JSONArray ol = a.optJSONArray("lijst");
            boolean ontv = false;
            for (int i = 0; ol != null && i < ol.length(); i++) { JSONObject o = ol.optJSONObject(i); if (o != null) ontv |= vervang(o.optJSONArray("leveranciers"), vanId, naarId); }
            if (ontv) FacturenAanlever.bewaarData(c, a);
            Facturen.schrijfAlle(c, lijst);
            File d = Facturen.pdfDir(c, vanId);
            File[] over = d.listFiles();
            if (over == null || over.length == 0) d.delete();
        }
        return n;
    }

    // ================= eenmalig herindelen =================

    static boolean nodig(Context c) { return Facturen.prefs(c).getInt("indeling", 0) < VERSIE; }

    /**
     * Facturen uit de mail die nog onder de mailbron staan (van vóór deze indeling): elk naar zijn eigen leverancier,
     * zoals bij nieuw ophalen. Eén keer (niet als er opgehaald, aangeleverd of weggehaald wordt: dan de volgende keer).
     * Zelf toegevoegde facturen blijven waar ze zijn. Geeft {aantal, leveranciers, klaar}.
     */
    static JSONObject herindelen(Context c) {
        JSONObject uit = new JSONObject();
        if (!nodig(c) || !BEZIG.compareAndSet(false, true)) return uit;
        try {
            if (FacturenRun.LOPEND.get() != null || FacturenAanlever.BEZIG.get() || FacturenDossier.WEGHALEN.get()) return uit;
            JSONArray levs = Facturen.lijst(c);
            Map<String, JSONObject> bronnen = new HashMap<>();
            for (int i = 0; i < levs.length(); i++) { JSONObject l = levs.optJSONObject(i); if (l != null && "mail".equals(l.optString("soort"))) bronnen.put(l.optString("id"), l); }
            JSONObject items = FacturenDossier.alle(c);
            List<JSONObject> todo = new ArrayList<>();
            for (Iterator<String> it = items.keys(); it.hasNext(); ) {
                JSONObject s = items.optJSONObject(it.next()), b = s == null ? null : s.optJSONObject("bron");
                if (s == null || b == null || b.optBoolean("toegevoegd") || b.optString("afzender").isEmpty()) continue; // zelf toegevoegd: geen afzender
                if (bronnen.containsKey(s.optString("leverancier")) && s.optString("via").isEmpty()) todo.add(s);
            }
            java.util.Collections.sort(todo, (x, y) -> Long.compare(x.optLong("opgehaald"), y.optLong("opgehaald")));
            FacturenHerken.Kennis k = FacturenHerken.kennis(c);
            JSONArray nieuwe = new JSONArray();
            Map<String, JSONObject> perMail = new HashMap<>();
            List<Object[]> stap = new ArrayList<>(); // {sha, bronId, kenmerken, doel}; per 25 weggeschreven
            int[] tel = {0, 0}; // ingedeeld, niet gelukt
            for (int i = 0; i < todo.size(); i++) {
                JSONObject s0 = todo.get(i);
                try {
                    String bronId = s0.optString("leverancier");
                    File f = FacturenAanlever.bestand(c, s0);
                    JSONObject km = FacturenHerken.kenmerken(s0.optJSONObject("bron"), f == null ? "" : FacturenDossier.tekst(f), k);
                    String mailId = FacturenDossier.mailId(s0);
                    JSONObject doel = Facturen.toewijzen(c, bronnen.get(bronId), km, k, perMail.get(mailId), nieuwe);
                    if (!mailId.isEmpty()) perMail.put(mailId, doel);
                    k.tel(km);
                    stap.add(new Object[]{s0.optString("sha"), bronId, km, doel});
                } catch (Throwable e) { tel[1]++; } // blijft bij de mailbron staan; met de hand te verplaatsen
                if (stap.size() >= 25 || i == todo.size() - 1) { schrijf(c, stap, tel); stap.clear(); }
            }
            if (tel[1] > 0) App.log(c, "FACTUREN", "herindelen: " + tel[1] + " niet gelukt");
            Facturen.prefs(c).edit().putInt("indeling", VERSIE).apply();
            uit.put("aantal", tel[0]).put("leveranciers", nieuwe).put("klaar", true);
            if (tel[0] > 0) melding(c, tel[0], nieuwe.length());
        } catch (Throwable e) {
            App.log(c, "FACTUREN", "herindelen mislukt");
        } finally {
            BEZIG.set(false);
        }
        return uit;
    }

    /** Een stap van het herindelen wegschrijven: dossier en toestand één keer lezen en schrijven. */
    private static void schrijf(Context c, List<Object[]> stap, int[] tel) {
        if (stap.isEmpty()) return;
        synchronized (Facturen.LOCK) {
            try {
                JSONObject all = FacturenDossier.alle(c), t = Facturen.toestand(c);
                int n = 0;
                for (Object[] x : stap) {
                    String sha = (String) x[0], bronId = (String) x[1];
                    JSONObject km = (JSONObject) x[2], doel = (JSONObject) x[3], s = all.optJSONObject(sha);
                    if (s == null || !s.optString("via").isEmpty()) continue;
                    try {
                        if (!bronId.equals(doel.optString("id"))) zetBij(c, all, t, sha, doel);
                        s.put("via", bronId).put("kenmerken", km);
                        n++;
                    } catch (Throwable e) { tel[1]++; }
                }
                FacturenDossier.bewaar(c, all);
                Facturen.bewaarToestand(c, t);
                tel[0] += n;
            } catch (Throwable e) { tel[1] += stap.size(); }
        }
    }

    private static void melding(Context c, int facturen, int leveranciers) {
        try {
            Facturen.kanaal(c);
            String titel = Taal.t(c, "Facturen uit je mail ingedeeld");
            String tekst = Taal.tn(c, facturen, "Eén factuur uit je mail staat nu bij zijn eigen leverancier.", "{n} facturen uit je mail staan nu bij hun eigen leverancier.")
                    + (leveranciers > 0 ? " " + Taal.tn(c, leveranciers, "Er is één nieuwe leverancier bij gekomen.", "Er zijn {n} nieuwe leveranciers bij gekomen.") : "")
                    + " " + Taal.t(c, "Kijk even of het klopt.");
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, NotifCenter.ch(c, Facturen.CHANNEL)) : new Notification.Builder(c);
            Intent i = new Intent(c, MainActivity.class).putExtra("open", "facturen").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int nr = 4398;
            Taal.keep(b, Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT);
            NotifCenter.post(c, nr, b.setSmallIcon(R.drawable.ic_backup).setContentTitle(titel).setContentText(tekst)
                    .setStyle(new Notification.BigTextStyle().bigText(tekst)).setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(c, nr, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
        } catch (Throwable ignored) { }
    }
}
