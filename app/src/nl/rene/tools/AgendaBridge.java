package nl.rene.tools;

import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Brug voor Agenda (Android.agenda…). Eigen afspraken staan in AgendaStore. Agenda's op de telefoon, ook van Google,
 * gaan via AgendaCal: Tools stuurt niets zelf naar Google.
 */
public class AgendaBridge extends FeatureBridge {

    AgendaBridge(MainActivity act) { super(act); }

    private static String tekst(JSONObject o) { return o == null ? "{}" : o.toString(); }

    private String fout(String code) {
        String t;
        switch (code == null ? "" : code) {
            case "titel": t = Taal.t(ctx, "Vul een titel in."); break;
            case "datum": t = Taal.t(ctx, "Die datum klopt niet."); break;
            case "vol": t = Taal.t(ctx, "De agenda zit vol (hooguit {n} afspraken).", "n", Agenda.MAX_ITEMS); break;
            case "onleesbaar": t = Taal.t(ctx, "De agenda is niet leesbaar. Er is niets overschreven."); break;
            case "telefoon": t = Taal.t(ctx, "Opslaan in de agenda van je telefoon lukte niet."); break;
            case "lezen":
            case "kalender": t = Taal.t(ctx, "Die agenda is alleen-lezen."); break;
            case "weg": t = Taal.t(ctx, "Die afspraak staat er niet."); break;
            case "recht": t = Taal.t(ctx, "Geen toestemming om de agenda van je telefoon te wijzigen."); break;
            default: t = Taal.t(ctx, "Opslaan lukte niet."); break;
        }
        try { return new JSONObject().put("ok", false).put("fout", t).toString(); }
        catch (Exception e) { return "{\"ok\":false}"; }
    }

    private String vandaag() { return Regio.dayKey(ctx, System.currentTimeMillis()); }

    /** Toestemming, gekozen weergave en de agenda's op de telefoon. */
    @JavascriptInterface public String agendaStaat() {
        try {
            JSONObject o = new JSONObject();
            o.put("perm", AgendaCal.lezen(ctx)).put("schrijf", AgendaCal.schrijven(ctx));
            o.put("view", AgendaStore.view(ctx)).put("standaard", AgendaStore.standaard(ctx));
            o.put("ai", LocalAi.srcOn(ctx, "agenda"));
            Set<String> uit = AgendaStore.uit(ctx);
            JSONArray kals = AgendaCal.kalenders(ctx), net = new JSONArray();
            for (int i = 0; i < kals.length(); i++) {
                JSONObject k = kals.optJSONObject(i);
                if (k == null) continue;
                k.put("aan", !uit.contains(k.optString("id")));
                net.put(k);
            }
            o.put("kalenders", net);
            JSONArray items = AgendaStore.items(ctx);
            o.put("aantal", items == null ? -1 : items.length());
            return o.toString();
        } catch (Throwable t) {
            App.log(ctx, "AGENDA", "staat: " + t.getClass().getSimpleName());
            return "{}";
        }
    }

    /** Dag, week, maand of lijst rond anker. weekStart: 1 maandag, 0 zondag, 6 zaterdag. */
    @JavascriptInterface public String agendaToon(String view, String anker, int weekStart) {
        try {
            if (!"dag".equals(view) && !"maand".equals(view) && !"lijst".equals(view)) view = "week";
            JSONArray lokaal = AgendaStore.zichtbaar(ctx);
            boolean kapot = lokaal == null;
            if (kapot) lokaal = new JSONArray();
            String[] r = Agenda.bereik(view, Agenda.dagOk(anker) ? anker : vandaag(), weekStart);
            JSONArray extern = r == null ? new JSONArray() : AgendaCal.instanties(ctx, r[0], r[1], AgendaStore.uit(ctx), null, 800);
            JSONObject o = Agenda.toon(lokaal, extern, view, anker, weekStart, vandaag());
            if (kapot) o.put("onleesbaar", true);
            return o.toString();
        } catch (Throwable t) {
            App.log(ctx, "AGENDA", "toon: " + t.getClass().getSimpleName());
            return "{}";
        }
    }

    /** Zoeken, ook in het verleden. van en tot leeg = een ruim bereik. */
    @JavascriptInterface public String agendaZoek(String q, String van, String tot) {
        try {
            String nu = vandaag();
            String vanD = Agenda.dagOk(van) ? van : Regio.dayAdd(nu, -365 * 15);
            String totD = Agenda.dagOk(tot) ? tot : Regio.dayAdd(nu, 365 * 3);
            if (vanD.compareTo(totD) > 0) { String s = vanD; vanD = totD; totD = s; }
            String[] w = Agenda.woorden(q);
            JSONArray lokaal = AgendaStore.zichtbaar(ctx);
            if (lokaal == null) lokaal = new JSONArray();
            JSONArray extern = AgendaCal.instanties(ctx, vanD, totD, AgendaStore.uit(ctx), w, 400);
            JSONArray items = new JSONArray();
            for (Agenda.Item it : Agenda.lijst(lokaal, extern, vanD, totD, nu, w)) items.put(Agenda.json(it));
            return new JSONObject().put("items", items).put("van", vanD).put("tot", totD).toString();
        } catch (Throwable t) {
            App.log(ctx, "AGENDA", "zoek: " + t.getClass().getSimpleName());
            return "{\"items\":[]}";
        }
    }

    /** Eén afspraak. Een id dat met x begint komt uit de agenda van de telefoon. */
    @JavascriptInterface public String agendaHaal(String id) {
        try {
            if (id != null && id.startsWith("x")) {
                int p = id.indexOf('_');
                String num = p > 1 ? id.substring(1, p) : id.substring(1);
                if (!num.matches("\\d+")) return fout("weg");
                JSONObject ev = AgendaCal.event(ctx, Long.parseLong(num));
                if (ev == null || ev.optBoolean("fout")) return fout(ev == null ? "weg" : "telefoon");
                ev.put("id", id);
                return new JSONObject().put("ok", true).put("item", ev).toString();
            }
            JSONObject it = AgendaStore.vind(ctx, id);
            if (it == null) return fout("weg");
            return new JSONObject().put("ok", true).put("item", it).toString();
        } catch (Throwable t) {
            App.log(ctx, "AGENDA", "haal: " + t.getClass().getSimpleName());
            return fout("weg");
        }
    }

    /** Bewaar een afspraak. Met een agenda-id ook in de agenda van de telefoon. */
    @JavascriptInterface public String agendaBewaar(String json) {
        try {
            JSONObject in = new JSONObject(json == null ? "{}" : json);
            JSONArray nu = AgendaStore.items(ctx);
            if (nu == null) return fout("onleesbaar");
            String id = in.optString("id");
            boolean nieuw = AgendaStore.vind(ctx, id) == null || id.startsWith("x");
            if (id.startsWith("x")) in.remove("id");
            Agenda.Uitkomst u = Agenda.schoon(in, nu.length(), nieuw);
            if (!u.ok()) return fout(u.fout);
            JSONObject it = u.item;
            String kal = it.optString("kalender");
            boolean los = false;
            if (!kal.isEmpty()) {
                if (!AgendaCal.schrijven(ctx)) return fout("recht");
                JSONObject k = AgendaCal.kalender(AgendaCal.kalenders(ctx), kal);
                if (k == null || !k.optBoolean("schrijf")) return fout("lezen");
                long ext = AgendaCal.schrijf(ctx, it);
                if (ext == -3) return fout("recht");
                if (ext == -2) return fout("lezen");
                if (ext <= 0) return fout("telefoon");
                it.put("ext", ext);
                it.put("naam", k.optString("naam"));
                if (Agenda.kleurOk(k.optString("kleur"))) it.put("kleur", k.optString("kleur"));
            } else if (it.optLong("ext") > 0) {
                it.put("ext", 0);
                it.put("naam", "");
                los = true;
            }
            String e = AgendaStore.zetItem(ctx, it);
            if (!e.isEmpty()) return fout("vol".equals(e) ? "vol" : "onleesbaar".equals(e) ? "onleesbaar" : "opslaan");
            App.log(ctx, "AGENDA", "bewaard " + AgendaStore.items(ctx).length());
            JSONObject o = new JSONObject().put("ok", true).put("id", it.optString("id"));
            if (los) o.put("los", true);
            return o.toString();
        } catch (Throwable t) {
            App.log(ctx, "AGENDA", "bewaar: " + t.getClass().getSimpleName());
            return fout("opslaan");
        }
    }

    /** Verwijder een eigen afspraak. Stond hij in een agenda op de telefoon, dan daar ook. */
    @JavascriptInterface public String agendaWis(String id) {
        try {
            JSONObject it = AgendaStore.vind(ctx, id);
            if (it == null) return fout("weg");
            long ext = it.optLong("ext");
            if (ext > 0) {
                int w = AgendaCal.wis(ctx, ext);
                if (w == 0) return fout("recht");
                if (w < 0) return fout("telefoon");
            }
            String e = AgendaStore.wis(ctx, id);
            if (!e.isEmpty()) return fout("onleesbaar".equals(e) ? "onleesbaar" : "weg");
            App.log(ctx, "AGENDA", "gewist");
            return new JSONObject().put("ok", true).put("id", id).toString();
        } catch (Throwable t) {
            App.log(ctx, "AGENDA", "wis: " + t.getClass().getSimpleName());
            return fout("opslaan");
        }
    }

    /** Weergave, standaardagenda en welke agenda's uit staan. */
    @JavascriptInterface public String agendaZet(String json) {
        try {
            JSONObject in = new JSONObject(json == null ? "{}" : json);
            Set<String> uit = null;
            if (in.has("uit")) {
                uit = new HashSet<>();
                JSONArray a = in.optJSONArray("uit");
                if (a != null) for (int i = 0; i < a.length() && uit.size() < 40; i++) {
                    String id = a.optString(i);
                    if (id.matches("[1-9]\\d{0,17}")) uit.add(id);
                }
            }
            AgendaStore.zet(ctx, in.has("view") ? in.optString("view") : null, in.has("standaard") ? in.optString("standaard") : null, uit);
            return agendaStaat();
        } catch (Throwable t) {
            App.log(ctx, "AGENDA", "zet: " + t.getClass().getSimpleName());
            return "{}";
        }
    }

    /**
     * Haal gekoppelde afspraken opnieuw uit de agenda van de telefoon. Weg daar: de kopie in Tools blijft,
     * zonder koppeling, en blijft zichtbaar.
     */
    @JavascriptInterface public String agendaSync() {
        try {
            JSONArray items = AgendaStore.items(ctx);
            if (items == null) return fout("onleesbaar");
            if (!AgendaCal.lezen(ctx)) return fout("recht");
            int bij = 0, los = 0, mis = 0;
            JSONArray uit = new JSONArray();
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                long ext = it.optLong("ext");
                if (ext <= 0) { uit.put(it); continue; }
                JSONObject ev = AgendaCal.event(ctx, ext);
                if (ev != null && ev.optBoolean("fout")) { mis++; uit.put(it); continue; }
                if (ev == null) {
                    it.put("ext", 0); it.put("kalender", ""); it.put("naam", "");
                    los++; uit.put(it); continue;
                }
                it.put("titel", ev.optString("titel")).put("plek", ev.optString("plek")).put("notitie", ev.optString("notitie"));
                it.put("dag", ev.optString("dag")).put("eind", ev.optString("eind")).put("van", ev.optString("van")).put("tot", ev.optString("tot"));
                it.put("heleDag", ev.optBoolean("heleDag")).put("herhaal", ev.optString("herhaal")).put("totDag", ev.optString("totDag"));
                it.put("kalender", ev.optString("kalender")).put("naam", ev.optString("naam"));
                if (Agenda.kleurOk(ev.optString("kleur"))) it.put("kleur", ev.optString("kleur"));
                bij++; uit.put(it);
            }
            String e = AgendaStore.bewaar(ctx, uit);
            if (!e.isEmpty()) return fout("onleesbaar".equals(e) ? "onleesbaar" : "opslaan");
            App.log(ctx, "AGENDA", "sync bijgewerkt=" + bij + " los=" + los + " mislukt=" + mis);
            return new JSONObject().put("ok", true).put("bijgewerkt", bij).put("los", los).put("mislukt", mis).toString();
        } catch (Throwable t) {
            App.log(ctx, "AGENDA", "sync: " + t.getClass().getSimpleName());
            return fout("telefoon");
        }
    }
}
