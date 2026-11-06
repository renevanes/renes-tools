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

    private static void nietNu() throws Exception {
        if (FacturenRun.LOPEND.get() != null) throw new Exception("Er worden nu facturen opgehaald; probeer het straks nog een keer");
        if (BEZIG.get()) throw new Exception("De facturen uit je mail worden nu ingedeeld; probeer het straks nog een keer");
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

    /** Overslaan (aan) of terugzetten (uit). ookUitDossier: de facturen die er al zijn ook uit het dossier en de app halen. */
    static int overslaan(Context c, String id, boolean aan, boolean ookUitDossier) throws Exception {
        nietNu();
        int weg = 0;
        synchronized (Facturen.LOCK) {
            JSONObject l = uitMail(Facturen.lijst(c), id);
            l.put("overslaan", aan).put("nieuw", false);
            Facturen.bewaar(c, l, null);
            if (aan && ookUitDossier) {
                JSONObject all = FacturenDossier.alle(c);
                List<String> shas = new ArrayList<>();
                for (Iterator<String> it = all.keys(); it.hasNext(); ) { String k = it.next(); JSONObject s = all.optJSONObject(k); if (s != null && id.equals(s.optString("leverancier"))) shas.add(k); }
                for (String sha : shas) { FacturenDossier.verwijder(c, sha, false); weg++; } // niet opnieuw ophalen: de mailbron onthoudt ze
            }
        }
        return weg;
    }

    /**
     * Een dossierstuk bij een andere leverancier zetten (in het geheugen: all = het dossier, t = de hele toestand).
     * Het bestand in de app verhuist naar de map van die leverancier; de mailbron onthoudt waar hij nu staat.
     */
    static void zetBij(Context c, JSONObject all, JSONObject t, String sha, JSONObject naar) throws Exception {
        JSONObject s = all.optJSONObject(sha);
        if (s == null) return;
        String naarId = naar.optString("id");
        s.put("leverancier", naarId).put("leverancierNaam", naar.optString("naam"));
        File f = FacturenAanlever.bestand(c, s), nf = null;
        if (f != null) {
            nf = Facturen.verplaats(f, Facturen.pdfDir(c, naarId), f.getName());
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
        nietNu();
        JSONObject km;
        int n = 0;
        synchronized (Facturen.LOCK) {
            JSONArray levs = Facturen.lijst(c);
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
            JSONObject t = Facturen.toestand(c);
            for (String k : shas) { zetBij(c, all, t, k, naar); n++; }
            FacturenDossier.bewaar(c, all);
            Facturen.bewaarToestand(c, t);
            if (onthoud && km != null && regel(levs, naarId, km)) Facturen.bewaarAlle(c, levs);
        }
        return n;
    }

    /**
     * De regel "deze afzender hoort bij naar": het adres (bij een platform: de naam) en de nummers uit de PDF bij naar,
     * en weg bij de andere leveranciers (anders wint daar een oude regel). Zuiver (getest). Geeft true als er iets veranderde.
     */
    static boolean regel(JSONArray levs, String naarId, JSONObject km) throws Exception {
        JSONObject naar = Facturen.zoek(levs, naarId);
        if (naar == null || km == null) return false;
        List<String[]> wat = new ArrayList<>();
        String soort = km.optString("soort"), adres = km.optString("adres"), naam = km.optString("naam").toLowerCase(Locale.ROOT);
        if ("platform".equals(soort) && !naam.isEmpty()) wat.add(new String[]{"namen", naam});
        else if (!"eigen".equals(soort) && !adres.isEmpty()) wat.add(new String[]{"afzenders", adres});
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
            if (FacturenHerken.lijst(a).contains(w[1]) || a.length() >= ("afzenders".equals(w[0]) ? 30 : FacturenHerken.MAX)) continue;
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
        nietNu();
        if (vanId.equals(naarId)) throw new Exception("Kies een andere leverancier");
        int n = 0;
        synchronized (Facturen.LOCK) {
            JSONArray levs = Facturen.leveranciers(c);
            if (levs == null) throw new Exception("De opgeslagen leveranciers zijn niet leesbaar");
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
            // dossier en bestanden in de app
            JSONObject all = FacturenDossier.alle(c), t = Facturen.toestand(c);
            List<String> shas = new ArrayList<>();
            for (Iterator<String> it = all.keys(); it.hasNext(); ) { String k = it.next(); JSONObject s = all.optJSONObject(k); if (s != null && vanId.equals(s.optString("leverancier"))) shas.add(k); }
            for (String k : shas) { zetBij(c, all, t, k, naar); n++; }
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
            // de lijst: van weg, voorstellen die naar van wezen naar naar
            JSONArray rest = new JSONArray();
            for (int i = 0; i < levs.length(); i++) {
                JSONObject l = levs.optJSONObject(i);
                if (l == null || l == van) continue;
                if (vanId.equals(l.optString("voorstel"))) l.put("voorstel", l == naar ? "" : naarId);
                rest.put(l);
            }
            Facturen.bewaarAlle(c, rest);
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
     * zoals bij nieuw ophalen. Eén keer; stopt als er opgehaald wordt (de volgende keer verder). Geeft {aantal, leveranciers}.
     */
    static JSONObject herindelen(Context c) {
        JSONObject uit = new JSONObject();
        if (!nodig(c) || !BEZIG.compareAndSet(false, true)) return uit;
        try {
            if (FacturenRun.LOPEND.get() != null) return uit;
            JSONArray levs = Facturen.lijst(c);
            Map<String, JSONObject> bronnen = new HashMap<>();
            for (int i = 0; i < levs.length(); i++) { JSONObject l = levs.optJSONObject(i); if (l != null && "mail".equals(l.optString("soort"))) bronnen.put(l.optString("id"), l); }
            JSONObject items = FacturenDossier.alle(c);
            List<JSONObject> todo = new ArrayList<>();
            for (Iterator<String> it = items.keys(); it.hasNext(); ) {
                JSONObject s = items.optJSONObject(it.next());
                if (s != null && bronnen.containsKey(s.optString("leverancier")) && s.optString("via").isEmpty()) todo.add(s);
            }
            java.util.Collections.sort(todo, (x, y) -> Long.compare(x.optLong("opgehaald"), y.optLong("opgehaald")));
            FacturenHerken.Kennis k = FacturenHerken.kennis(c);
            JSONArray nieuwe = new JSONArray();
            Map<String, JSONObject> perMail = new HashMap<>();
            int n = 0;
            for (JSONObject s0 : todo) {
                if (FacturenRun.LOPEND.get() != null) return uit.put("aantal", n).put("leveranciers", nieuwe); // later verder
                String sha = s0.optString("sha"), bronId = s0.optString("leverancier");
                JSONObject bron = bronnen.get(bronId);
                File f = FacturenAanlever.bestand(c, s0);
                String tekst = f == null ? "" : FacturenDossier.tekst(f);
                JSONObject km = FacturenHerken.kenmerken(s0.optJSONObject("bron"), tekst, k);
                String mailId = FacturenDossier.mailId(s0);
                JSONObject doel = Facturen.toewijzen(c, bron, km, k, perMail.get(mailId), nieuwe);
                if (!mailId.isEmpty()) perMail.put(mailId, doel);
                k.tel(km);
                synchronized (Facturen.LOCK) {
                    JSONObject all = FacturenDossier.alle(c), s = all.optJSONObject(sha);
                    if (s == null || !s.optString("via").isEmpty()) continue;
                    s.put("via", bronId).put("kenmerken", km);
                    JSONObject t = Facturen.toestand(c);
                    if (doel != bron) zetBij(c, all, t, sha, doel);
                    FacturenDossier.bewaar(c, all);
                    Facturen.bewaarToestand(c, t);
                }
                n++;
            }
            Facturen.prefs(c).edit().putInt("indeling", VERSIE).apply();
            uit.put("aantal", n).put("leveranciers", nieuwe).put("klaar", true);
            if (n > 0) melding(c, n, nieuwe.length());
        } catch (Throwable e) {
            App.log(c, "FACTUREN", "herindelen mislukt");
        } finally {
            BEZIG.set(false);
        }
        return uit;
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
