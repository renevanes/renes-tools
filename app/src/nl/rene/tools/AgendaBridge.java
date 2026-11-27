package nl.rene.tools;

import android.content.SharedPreferences;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Brug voor Agenda (Android.agenda…). Eigen afspraken staan in AgendaStore. Agenda's op de telefoon, ook van Google,
 * gaan via AgendaCal: Tools stuurt niets zelf naar Google.
 */
public class AgendaBridge extends SignalenBridge {

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
            case "complex": t = Taal.t(ctx, "Deze herhaling is ingewikkelder dan Tools kan bewaren. Wijzig hem in de agenda-app van je telefoon."); break;
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
            JSONObject sk = syncKort();
            o.put("sync", sk);
            if (sk.optBoolean("aan")) a.h.post(() -> AgendaSyncJob.schedule(ctx)); // bijv. na het geven van toestemming
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
            JSONArray extern = AgendaCal.zoek(ctx, vanD, totD, AgendaStore.uit(ctx), w, 400);
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
            JSONObject vorig = id.startsWith("x") ? null : AgendaStore.vind(ctx, id);
            boolean nieuw = vorig == null || id.startsWith("x");
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
                long oudExt = it.optLong("ext");
                String oudKal = oudExt > 0 ? AgendaCal.agendaVan(ctx, oudExt) : "";
                if (oudKal == null) return fout("telefoon");
                // een herhaling die Tools niet precies kan tonen: niet overschrijven (dan gaat de herhaling in Google verloren)
                if (oudExt > 0 && kal.equals(oudKal)) {
                    JSONObject nu0 = AgendaCal.event(ctx, oudExt);
                    String rr = nu0 == null ? "" : nu0.optString("rrule");
                    if (!rr.isEmpty() && (AgendaSyncRegels.complex(rr, nu0.optString("dag")) || AgendaCal.heeftUitzonderingen(ctx, oudExt))) return fout("complex");
                }
                long ext = AgendaCal.schrijf(ctx, it);
                if (ext == -3) return fout("recht");
                if (ext == -2) return fout("lezen");
                if (ext <= 0) return fout("telefoon");
                // naar een andere agenda gezet: daar staat nu een nieuwe, de oude gaat weg (niet twee keer)
                if (oudExt > 0 && ext != oudExt && !oudKal.isEmpty() && !oudKal.equals(kal)) AgendaCal.wis(ctx, oudExt, oudKal);
                it.put("ext", ext);
                it.put("naam", k.optString("naam"));
                if (Agenda.kleurOk(k.optString("kleur"))) it.put("kleur", k.optString("kleur"));
                if (AgendaSync.isSyncAgenda(ctx, kal)) {
                    // net zelf naar Google gezet: de volgende ronde ziet dit niet als een wijziging aan beide kanten
                    JSONObject terug = AgendaCal.event(ctx, ext);
                    boolean goed = terug != null && !terug.optBoolean("fout");
                    AgendaSync.meta(it, AgendaSyncRegels.vinger(it), AgendaSyncRegels.vinger(goed ? terug : it), goed ? terug.optString("gid") : "");
                    AgendaSync.vergeetConflict(ctx, it.optString("id"));
                }
            } else if (it.optLong("ext") > 0) {
                if (vorig != null && AgendaSync.isSyncAgenda(ctx, vorig.optString("kalender"))) AgendaSync.onthoudWeg(ctx, vorig);
                it.put("ext", 0);
                it.put("naam", "");
                los = true;
            }
            // "Alleen in Tools" gekozen terwijl synchroniseren naar Google aan staat: deze blijft hier
            if (kal.isEmpty() && (in.optBoolean("lokaal") || (vorig != null && vorig.optBoolean("lokaal") && !in.has("lokaal")))) it.put("lokaal", true);
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
            boolean houdInGoogle = ext > 0 && AgendaSync.isSyncAgenda(ctx, it.optString("kalender")) && !AgendaSyncRegels.wisInGoogle(AgendaSync.instellingen(ctx));
            if (houdInGoogle) AgendaSync.onthoudWeg(ctx, it); // blijft in Google staan en komt niet terug in Tools
            else if (ext > 0) {
                int w = AgendaCal.wis(ctx, ext, it.optString("kalender"));
                if (w == 0) return fout("recht");
                if (w < 0) return fout("telefoon");
            }
            String e = AgendaStore.wis(ctx, id);
            if (!e.isEmpty()) return fout("onleesbaar".equals(e) ? "onleesbaar" : "weg");
            AgendaSync.vergeetConflict(ctx, id);
            App.log(ctx, "AGENDA", "gewist");
            return new JSONObject().put("ok", true).put("id", id).put("inGoogle", houdInGoogle).toString();
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
                // de agenda van Synchroniseren doet de ronde zelf (agendaSyncNu), met de eigen regels
                if (ext <= 0 || AgendaSync.isSyncAgenda(ctx, it.optString("kalender"))) { uit.put(it); continue; }
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

    // ---------------------------------------------------------------- Synchroniseren met Google

    private JSONObject syncKort() throws Exception {
        JSONObject cfg = AgendaSync.instellingen(ctx);
        SharedPreferences st = AgendaSync.toestand(ctx);
        return new JSONObject().put("aan", AgendaSyncRegels.aan(cfg)).put("richting", cfg.optString("richting")).put("kal", cfg.optString("kal"))
                .put("kalNaam", cfg.optString("kalNaam")).put("naar", AgendaSyncRegels.naarGoogle(cfg)).put("wisGoogle", AgendaSyncRegels.wisInGoogle(cfg))
                .put("elke", cfg.optInt("elke")).put("open", cfg.optBoolean("open", true)).put("laatst", st.getLong("laatst", 0))
                .put("bezig", AgendaSync.BEZIG.get()).put("conflicten", AgendaSync.lijst(ctx, "conflicten").length())
                .put("rem", st.getInt("rem", 0)).put("remActie", AgendaSync.verdwenenActie(cfg)).put("fout", AgendaSync.foutTekst(ctx, st.getString("fout", "")));
    }

    private static JSONObject kort(JSONObject it) throws Exception {
        JSONObject o = new JSONObject();
        if (it == null) return o;
        for (String k : new String[]{"titel", "plek", "dag", "eind", "van", "tot", "herhaal", "totDag"}) o.put(k, it.optString(k));
        return o.put("heleDag", it.optBoolean("heleDag")).put("notitie", Agenda.tekst(it.optString("notitie"), 200, false));
    }

    /** Alles voor het scherm Synchroniseren: instellingen, agenda's, stand, logboek en conflicten (met beide versies). */
    @JavascriptInterface public String agendaSyncStaat() {
        try {
            JSONObject cfg = AgendaSync.instellingen(ctx);
            SharedPreferences st = AgendaSync.toestand(ctx);
            JSONObject o = new JSONObject().put("cfg", cfg).put("kort", syncKort());
            o.put("perm", AgendaCal.lezen(ctx)).put("schrijf", AgendaCal.schrijven(ctx));
            JSONArray kals = AgendaCal.kalenders(ctx), lijst = new JSONArray();
            for (int i = 0; i < kals.length(); i++) {
                JSONObject k = kals.optJSONObject(i);
                if (k != null) lijst.put(new JSONObject().put("id", k.optString("id")).put("naam", k.optString("naam")).put("account", k.optString("account"))
                        .put("google", k.optBoolean("google")).put("schrijf", k.optBoolean("schrijf")));
            }
            o.put("kalenders", lijst);
            JSONObject kal = AgendaSyncRegels.aan(cfg) || !cfg.optString("kal").isEmpty() ? AgendaSync.kalender(ctx, AgendaSync.instellingen(ctx)) : null;
            o.put("kalOk", kal != null);
            JSONArray log = AgendaSync.lijst(ctx, "log"), net = new JSONArray();
            for (int i = 0; i < log.length(); i++) {
                JSONObject r = log.optJSONObject(i);
                if (r == null) continue;
                JSONObject k = new JSONObject(r.toString());
                if (!r.optString("fout").isEmpty()) k.put("fout", AgendaSync.foutTekst(ctx, r.optString("fout")));
                net.put(k);
            }
            o.put("log", net);
            JSONArray con = AgendaSync.lijst(ctx, "conflicten"), cn = new JSONArray();
            for (int i = 0; i < con.length(); i++) {
                JSONObject k = con.optJSONObject(i);
                if (k == null) continue;
                JSONObject it = AgendaStore.vind(ctx, k.optString("id"));
                if (it == null) continue;
                JSONObject ev = AgendaCal.event(ctx, it.optLong("ext"));
                cn.put(new JSONObject().put("id", k.optString("id")).put("sinds", k.optLong("sinds")).put("tools", kort(it))
                        .put("google", ev == null || ev.optBoolean("fout") ? JSONObject.NULL : kort(ev)));
            }
            o.put("conflicten", cn).put("teVersturen", AgendaSync.teVersturen(ctx, cfg)).put("weg", AgendaSync.lijst(ctx, "weg").length());
            JSONArray elke = new JSONArray();
            for (int e : AgendaSyncRegels.ELKE) elke.put(e);
            return o.put("elkeKeuzes", elke).toString();
        } catch (Throwable t) {
            App.log(ctx, "AGENDA", "sync staat: " + t.getClass().getSimpleName());
            return "{}";
        }
    }

    /**
     * Instellingen wijzigen (alleen de velden die meekomen). bestaandLokaal: true = wat nu alleen in Tools staat, blijft
     * alleen in Tools (alleen nieuwe afspraken gaan naar Google).
     */
    @JavascriptInterface public String agendaSyncZet(String json) {
        try {
            JSONObject in = new JSONObject(json == null ? "{}" : json);
            JSONObject cfg = AgendaSync.instellingen(ctx);
            for (java.util.Iterator<String> k = in.keys(); k.hasNext(); ) {
                String n = k.next();
                if (AgendaSyncRegels.standaard().has(n) && !"kalAccount".equals(n) && !"kalNaam".equals(n) && !"kalGid".equals(n)) cfg.put(n, in.get(n));
            }
            if (in.has("kal")) {
                JSONObject k = AgendaCal.kalender(AgendaCal.kalenders(ctx), in.optString("kal"));
                cfg.put("kalAccount", k == null ? "" : k.optString("account")).put("kalNaam", k == null ? "" : k.optString("naam")).put("kalGid", k == null ? "" : k.optString("gid"));
                AgendaSync.toestand(ctx).edit().remove("kalKey").remove("conflicten").remove("nConflict").remove("rem").apply();
            }
            if ("uit".equals(cfg.optString("richting"))) AgendaSync.toestand(ctx).edit().remove("conflicten").remove("nConflict").remove("rem").apply();
            AgendaSync.bewaarInstellingen(ctx, cfg);
            if (in.optBoolean("bestaandLokaal")) AgendaSync.houdBestaandeLokaal(ctx);
            App.log(ctx, "AGENDA", "sync ingesteld: " + AgendaSync.instellingen(ctx).optString("richting"));
            a.h.post(() -> AgendaSyncJob.schedule(ctx));
            return agendaSyncStaat();
        } catch (Throwable t) {
            App.log(ctx, "AGENDA", "sync zet: " + t.getClass().getSimpleName());
            return "{}";
        }
    }

    /**
     * Nu synchroniseren, op de achtergrond; de uitkomst komt in onAgendaSync. waarom: hand of open.
     * rem: "" (veiligheidsrem houdt verwijderen tegen), "wis" (toch verwijderen) of "los" (in Tools houden).
     */
    @JavascriptInterface public boolean agendaSyncNu(String waarom, String rem) {
        if (AgendaSync.BEZIG.get()) return false;
        final String w = "open".equals(waarom) ? "open" : "hand";
        final String r = "wis".equals(rem) || "los".equals(rem) ? rem : "";
        new Thread(App.veilig(() -> {
            AgendaSync.Uit u = AgendaSync.ronde(ctx, w, r);
            try {
                JSONObject o = new JSONObject().put("ok", u.fout.isEmpty() && !u.bezig).put("bezig", u.bezig).put("waarom", w)
                        .put("fout", AgendaSync.foutTekst(ctx, "uit".equals(u.fout) ? "" : u.fout)).put("uit", "uit".equals(u.fout))
                        .put("samenvatting", AgendaSync.samenvatting(ctx, u.n)).put("veranderd", AgendaSyncRegels.gewijzigd(u.n))
                        .put("conflicten", u.n[AgendaSync.CONFLICT]).put("rem", u.n[AgendaSync.REM]);
                a.js("onAgendaSync", o.toString());
            } catch (Exception ignored) { }
        }, () -> a.js("onAgendaSync", "{\"ok\":false}")), "agenda-sync-nu").start();
        return true;
    }

    /** Conflict oplossen: keuze tools, google of beide. */
    @JavascriptInterface public String agendaSyncKies(String id, String keuze) {
        try {
            String k = "tools".equals(keuze) || "beide".equals(keuze) ? keuze : "google";
            String e = AgendaSync.kies(ctx, id == null ? "" : id, k);
            if ("kalender".equals(e) || "telefoon".equals(e) || "recht".equals(e))
                return new JSONObject().put("ok", false).put("fout", AgendaSync.foutTekst(ctx, e)).toString();
            if (!e.isEmpty()) return fout(e);
            return new JSONObject().put("ok", true).toString();
        } catch (Throwable t) {
            App.log(ctx, "AGENDA", "sync kies: " + t.getClass().getSimpleName());
            return fout("opslaan");
        }
    }
}
