package nl.rene.tools;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TimeZone;

/**
 * Signalen: bronnen uit Tools zelf. Elke soort levert berichten aan uit gegevens die al op de telefoon staan (Agenda,
 * Nieuws, het facturendossier, Notities en de stand van geplande taken); Signalen leest ze alleen, het verandert daar
 * niets. De functies die een lijst omzetten in berichten zijn zuiver (getest in SignalenTest); {@code c} is alleen
 * nodig voor de taal van de teksten en mag daar null zijn.
 */
final class SignalenIntern {
    private SignalenIntern() { }

    static final long DAG = 86_400_000L;

    private static JSONObject item(JSONObject bron, String sleutel, String titel, String tekst, long nu) throws Exception {
        String soort = bron.optString("soort");
        return new JSONObject().put("id", Signalen.itemId(soort + ":" + sleutel)).put("bron", bron.optString("id")).put("bronNaam", bron.optString("naam"))
                .put("soort", soort).put("tool", Signalen.tool(soort)).put("titel", NieuwsFeed.cut(titel, 300)).put("tekst", NieuwsFeed.cut(tekst, 1500))
                .put("link", "").put("open", "").put("gevonden", nu).put("dag", "").put("tijd", "").put("status", "nieuw").put("later", 0).put("gemeld", false);
    }

    // ---------------------------------------------------------------- Agenda: afspraken die eraan komen

    /**
     * Afspraken die binnen {@code vooraf} uur beginnen en nog niet begonnen zijn (een hele dag: vanaf vandaag). Het venster
     * is één ronde ruimer ({@code elke} uur): een afspraak die net na de volgende ronde begint, zou anders nooit gemeld
     * worden. De melding komt dus {@code vooraf} tot {@code vooraf + elke} uur van tevoren.
     */
    static List<JSONObject> agenda(List<Agenda.Item> afspraken, JSONObject bron, long nu, TimeZone tz) throws Exception {
        List<JSONObject> uit = new ArrayList<>();
        long tot = nu + (bron.optInt("vooraf", 24) + Math.max(1, bron.optInt("elke", 1))) * 3_600_000L;
        String vandaag = Regio.dayKey(tz, nu);
        for (Agenda.Item a : afspraken) {
            if (a == null || a.titel.isEmpty() || !Agenda.dagOk(a.dag)) continue;
            boolean heel = a.heleDag || !Signalen.hm(a.van);
            long begin = Regio.toMillis(tz, a.dag, heel ? "00:00" : a.van);
            if (begin < 0 || begin > tot) continue;
            if (heel ? a.dag.compareTo(vandaag) < 0 : begin <= nu) continue;
            String tekst = a.plek.isEmpty() ? a.notitie : a.notitie.isEmpty() ? a.plek : a.plek + "\n\n" + a.notitie;
            uit.add(item(bron, a.id + "|" + a.dag + "|" + (heel ? "" : a.van), a.titel, tekst, nu)
                    .put("dag", a.dag).put("tijd", heel ? "" : a.van).put("inAgenda", true).put("open", "agenda").put("begin", begin).put("verloopt", heel ? begin + DAG : begin));
        }
        // wat het eerst begint bovenaan
        java.util.Collections.sort(uit, (x, y) -> Long.compare(x.optLong("begin"), y.optLong("begin")));
        for (int i = 0; i < uit.size(); i++) uit.get(i).put("gevonden", nu - i);
        return uit;
    }

    // ---------------------------------------------------------------- Nieuws: artikelen die bij de trefwoorden passen

    /** Artikelen (zoals Nieuws ze toont: id, titel, link, sam, bronnaam) waarvan titel of begin bij de trefwoorden past. */
    static List<JSONObject> nieuws(JSONArray artikelen, JSONObject bron, long nu) throws Exception {
        List<JSONObject> uit = new ArrayList<>();
        JSONArray woorden = bron.optJSONArray("woorden");
        if (woorden == null || woorden.length() == 0) return uit;
        for (int i = 0; artikelen != null && i < artikelen.length(); i++) {
            JSONObject a = artikelen.optJSONObject(i);
            if (a == null || a.optString("titel").isEmpty()) continue;
            if (!Signalen.past(a.optString("titel") + " " + a.optString("sam"), woorden)) continue;
            String link = a.optString("link");
            JSONObject it = item(bron, link.isEmpty() ? a.optString("id") : link, a.optString("titel"), a.optString("sam"), nu - uit.size())
                    .put("link", NieuwsFeed.httpsOpenbaar(link) ? link : "").put("open", "nieuws");
            if (!link.isEmpty()) it.put("id", Signalen.itemId(link)); // hetzelfde artikel via een website-bron is één bericht
            if (!a.optString("bronnaam").isEmpty()) it.put("bronNaam", bron.optString("naam") + ": " + a.optString("bronnaam"));
            uit.add(it);
        }
        return uit;
    }

    // ---------------------------------------------------------------- Facturen: wat er in het dossier bij kwam

    /** Facturen uit het dossier die de afgelopen 30 dagen zijn opgehaald, nieuwste eerst. */
    static List<JSONObject> facturen(Context c, JSONObject dossier, JSONObject bron, long nu) throws Exception {
        List<JSONObject> uit = new ArrayList<>();
        if (dossier == null) return uit;
        for (Iterator<String> k = dossier.keys(); k.hasNext(); ) {
            String sha = k.next();
            JSONObject s = dossier.optJSONObject(sha);
            long t = s == null ? 0 : s.optLong("opgehaald");
            if (s == null || t <= 0 || t > nu + DAG || nu - t > 30 * DAG) continue;
            String lev = s.optString("leverancierNaam");
            JSONObject meta = s.optJSONObject("meta");
            StringBuilder tekst = new StringBuilder(s.optString("naam"));
            if (meta != null && !meta.optString("nummer").isEmpty()) tekst.append("\n").append(Taal.t(c, "Factuurnummer {nummer}", "nummer", meta.optString("nummer")));
            uit.add(item(bron, sha, lev.isEmpty() ? Taal.t(c, "Nieuwe factuur") : Taal.t(c, "Nieuwe factuur van {naam}", "naam", lev), tekst.toString(), t).put("open", "facturen"));
        }
        java.util.Collections.sort(uit, (x, y) -> Long.compare(y.optLong("gevonden"), x.optLong("gevonden")));
        return uit;
    }

    // ---------------------------------------------------------------- Notities: lijsten met open punten die blijven liggen

    /** Lijsten met open punten die {@code dagen} dagen niet zijn aangeraakt. Opnieuw aangeraakt en weer blijven liggen = een nieuw bericht. */
    static List<JSONObject> notities(Context c, JSONArray notities, JSONObject bron, long nu) throws Exception {
        List<JSONObject> uit = new ArrayList<>();
        long grens = nu - bron.optInt("dagen", 7) * DAG;
        for (int i = 0; notities != null && i < notities.length(); i++) {
            JSONObject n = notities.optJSONObject(i);
            JSONArray punten = n == null ? null : n.optJSONArray("items");
            long bij = n == null ? 0 : n.optLong("updated");
            if (punten == null || bij <= 0 || bij > grens) continue;
            int open = 0;
            StringBuilder tekst = new StringBuilder();
            for (int j = 0; j < punten.length(); j++) {
                JSONObject p = punten.optJSONObject(j);
                if (p == null || p.optBoolean("done") || p.optString("text").trim().isEmpty()) continue;
                if (open++ < 6) tekst.append(tekst.length() > 0 ? "\n" : "").append("• ").append(NieuwsFeed.cut(p.optString("text").trim(), 120));
            }
            if (open == 0) continue;
            if (open > 6) tekst.append("\n…");
            String titel = Notes.showTitle(n);
            uit.add(item(bron, n.optString("id") + "|" + bij, Taal.tn(c, open, "{titel}: één open punt", "{titel}: {n} open punten", "titel", titel.isEmpty() ? Taal.t(c, "Lijst zonder titel") : titel),
                    tekst.toString(), nu).put("open", "note:" + n.optString("id")).put("oud", bij));
        }
        // wat het langst ligt bovenaan
        java.util.Collections.sort(uit, (x, y) -> Long.compare(x.optLong("oud"), y.optLong("oud")));
        for (int i = 0; i < uit.size(); i++) uit.get(i).put("gevonden", nu - i);
        return uit;
    }

    // ---------------------------------------------------------------- Taken van Tools die mislukten

    /**
     * Geplande taken die de afgelopen 3 dagen mislukten: de backup ({@code backup} = de laatste uitkomst van Alles
     * back-uppen), facturen ophalen (leveranciers met hun toestand) en bronnen van Toiletten. Per taak hooguit één bericht per dag.
     */
    static List<JSONObject> taken(Context c, JSONObject backup, JSONArray leveranciers, JSONObject facToestand, JSONArray wcBronnen, JSONObject wcToestand, JSONObject bron, long nu, TimeZone tz) throws Exception {
        List<JSONObject> uit = new ArrayList<>();
        long sinds = nu - 3 * DAG;
        if (backup != null && !backup.optBoolean("stopped") && backup.optLong("t") > sinds && backup.optLong("t") <= nu) { // zelf gestopt is niet mislukt
            int mis = 0;
            for (Iterator<String> k = backup.keys(); k.hasNext(); ) { JSONObject r = backup.optJSONObject(k.next()); if (r != null && r.has("ok") && !r.optBoolean("ok")) mis++; }
            if (mis > 0) uit.add(item(bron, "backup|" + backup.optLong("t"), Taal.tn(c, mis, "Backup: één onderdeel is mislukt", "Backup: {n} onderdelen zijn mislukt"),
                    Taal.t(c, "Kijk bij Alles back-uppen wat er misging."), backup.optLong("t")).put("open", "backup").put("tool", "backup"));
        }
        for (int i = 0; leveranciers != null && facToestand != null && i < leveranciers.length(); i++) {
            JSONObject l = leveranciers.optJSONObject(i);
            JSONObject t = l == null ? null : facToestand.optJSONObject(l.optString("id"));
            if (t == null || t.optBoolean("ok", true) || t.optLong("lastTry") <= sinds || t.optLong("lastTry") > nu || "Gestopt".equals(t.optString("msg"))) continue;
            uit.add(item(bron, "facturen|" + l.optString("id") + "|" + Regio.dayKey(tz, t.optLong("lastTry")), Taal.t(c, "Facturen ophalen bij {naam} lukte niet", "naam", l.optString("naam")),
                    Taal.tx(c, t.optString("msg")), t.optLong("lastTry")).put("open", "facturen").put("tool", "facturen"));
        }
        for (int i = 0; wcBronnen != null && wcToestand != null && i < wcBronnen.length(); i++) {
            JSONObject b = wcBronnen.optJSONObject(i);
            JSONObject t = b == null ? null : wcToestand.optJSONObject(b.optString("id"));
            if (t == null || !b.optBoolean("aan", true) || t.optString("fout").isEmpty() || t.optLong("lastTry") <= sinds || t.optLong("lastTry") > nu) continue;
            uit.add(item(bron, "toiletten|" + b.optString("id") + "|" + Regio.dayKey(tz, t.optLong("lastTry")), Taal.t(c, "Toiletten en douches: {naam} bijwerken lukte niet", "naam", b.optString("naam")),
                    Taal.tx(c, t.optString("fout")), t.optLong("lastTry")).put("open", "wc").put("tool", "wc"));
        }
        java.util.Collections.sort(uit, (x, y) -> Long.compare(y.optLong("gevonden"), x.optLong("gevonden")));
        return uit;
    }

    // ---------------------------------------------------------------- Publiceren en tests: wat op GitHub mislukte

    /**
     * Runs op main die de afgelopen 3 dagen mislukten ({@code runs} zoals GithubStatus.samenvatten ze geeft), nieuwste
     * eerst. Elk mislukte run is één bericht, met een link naar die run op GitHub.
     */
    static List<JSONObject> github(Context c, JSONArray runs, JSONObject bron, long nu) throws Exception {
        List<JSONObject> uit = new ArrayList<>();
        long sinds = nu - 3 * DAG;
        for (int i = 0; runs != null && i < runs.length(); i++) {
            JSONObject r = runs.optJSONObject(i);
            if (r == null || !r.optBoolean("main") || !"mislukt".equals(r.optString("soort"))) continue;
            long t = r.optLong("eind");
            if (t <= sinds || t > nu + DAG) continue;
            String tekst = r.optString("titel");
            uit.add(item(bron, "run|" + r.optLong("id"), Taal.t(c, "{naam} mislukt", "naam", r.optString("naam")), tekst, t)
                    .put("link", GithubStatus.url(r.optString("url"))));
        }
        java.util.Collections.sort(uit, (x, y) -> Long.compare(y.optLong("gevonden"), x.optLong("gevonden")));
        return uit;
    }

    // ---------------------------------------------------------------- de gegevens erbij halen (Android)

    /** Wat deze bron nu te melden heeft (nog niet gefilterd op wat al gezien is). */
    static List<JSONObject> kandidaten(Context c, JSONObject bron, long nu) throws Exception {
        TimeZone tz = Regio.zone(c);
        switch (bron.optString("soort")) {
            case "agenda": {
                String van = Regio.dayKey(tz, nu), tot = Regio.dayAdd(van, bron.optInt("vooraf", 24) / 24 + 1);
                JSONArray lokaal = AgendaStore.zichtbaar(c);
                if (lokaal == null) throw new Signalen.Fout(Taal.T("De agenda is niet leesbaar."));
                return agenda(Agenda.lijst(lokaal, AgendaCal.instanties(c, van, tot, AgendaStore.uit(c), null, 300), van, tot, van, null), bron, nu, tz);
            }
            case "nieuws":
                return nieuws(Nieuws.lijst(Nieuws.dir(c), Nieuws.cache(c)).optJSONArray("artikelen"), bron, nu);
            case "facturen":
                if (!FacturenDossier.leesbaar(c)) throw new Signalen.Fout(Taal.T("Het facturendossier is niet leesbaar."));
                return facturen(c, FacturenDossier.alle(c), bron, nu);
            case "notities": {
                String raw = Notes.load(c);
                if (raw.isEmpty()) throw new Signalen.Fout(Taal.T("De notities zijn niet leesbaar."));
                return notities(c, new JSONArray(raw), bron, nu);
            }
            case "taken": {
                JSONObject backup = null;
                try {
                    // Mislukte de nachtelijke backup en probeert hij het straks opnieuw, dan nog geen bericht
                    String last = AllBackup.pogingNu(c) > 0 ? null : AllBackup.prefs(c).getString("last", null);
                    if (last != null) backup = new JSONObject(last);
                } catch (Exception ignored) { }
                return taken(c, backup, Facturen.lijst(c), Facturen.toestand(c), Toiletten.bronnen(c), Toiletten.toestand(c), bron, nu, tz);
            }
            case "github":
                try { return github(c, GithubStatus.mislukt(), bron, nu); }
                catch (GithubStatus.Fout e) { throw new Signalen.Fout(e.getMessage()); }
            default:
                return new ArrayList<>();
        }
    }
}
