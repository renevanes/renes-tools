package nl.rene.tools;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agenda synchroniseren met één agenda op de telefoon, meestal een Google-agenda: Tools → Google, Google → Tools of
 * beide kanten op, met de hand of op een vaste tijd (AgendaSyncJob). Alles gaat via de agenda van de telefoon
 * (CalendarContract); de agenda-app van de telefoon stuurt het naar Google. Tools praat zelf niet met Google.
 *
 * Instellingen: sleutel "sync" in het instellingenbestand "agenda" (gaat mee in backups). Toestand van deze telefoon
 * (laatste ronde, logboek, conflicten, afspraken die in Tools weg zijn maar in Google bleven): instellingenbestand
 * "agenda_sync" (bewust niet in backups: id's van de agenda van deze telefoon). Regels: AgendaSyncRegels.
 */
final class AgendaSync {
    private AgendaSync() { }

    static final String PREFS = "agenda_sync";
    static final String CHANNEL = "agenda-sync";
    static final int MELDING = 5205;
    static final AtomicBoolean BEZIG = new AtomicBoolean();

    // plekken in de tellers, zoals AgendaSyncRegels.logRegel
    static final int NAAR_NIEUW = 0, NAAR_BIJ = 1, VAN_NIEUW = 2, VAN_BIJ = 3, WEG_TOOLS = 4, WEG_GOOGLE = 5, LOS = 6, CONFLICT = 7, MISLUKT = 8, REM = 9;

    static SharedPreferences toestand(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static JSONObject instellingen(Context c) {
        try { return AgendaSyncRegels.normaal(new JSONObject(AgendaStore.prefs(c).getString("sync", "{}"))); }
        catch (Exception e) { return AgendaSyncRegels.standaard(); }
    }

    static void bewaarInstellingen(Context c, JSONObject cfg) {
        AgendaStore.prefs(c).edit().putString("sync", AgendaSyncRegels.normaal(cfg).toString()).apply();
    }

    /** Aan, met deze agenda als gekozen agenda? */
    static boolean isSyncAgenda(Context c, String kal) {
        JSONObject cfg = instellingen(c);
        return AgendaSyncRegels.aan(cfg) && kal != null && !kal.isEmpty() && kal.equals(cfg.optString("kal"));
    }

    /**
     * De gekozen agenda, ook na een nieuwe telefoon of terugzetten (dan heeft hij vaak een ander id: zoek op account en
     * naam en onthoud het nieuwe id). null = staat niet (meer) op de telefoon.
     */
    static JSONObject kalender(Context c, JSONObject cfg) {
        JSONArray kals = AgendaCal.kalenders(c);
        JSONObject k = AgendaCal.kalender(kals, cfg.optString("kal"));
        String acc = cfg.optString("kalAccount"), naam = cfg.optString("kalNaam"), kg = cfg.optString("kalGid");
        // het id alleen als het nog dezelfde agenda is: op een andere telefoon kan hetzelfde nummer bij een andere agenda horen
        if (k != null && (!kg.isEmpty() ? kg.equals(k.optString("gid")) && acc.equals(k.optString("account"))
                : (acc.isEmpty() && naam.isEmpty()) || (acc.equals(k.optString("account")) && naam.equals(k.optString("naam"))))) {
            if (!naam.equals(k.optString("naam")) || (kg.isEmpty() && !k.optString("gid").isEmpty())) {
                try { cfg.put("kalNaam", k.optString("naam")).put("kalGid", k.optString("gid")); } catch (Exception ignored) { }
                bewaarInstellingen(c, cfg); // andere naam gekregen, of het id van Google is nu bekend
            }
            return k;
        }
        if (acc.isEmpty() && naam.isEmpty()) return null;
        JSONObject gevonden = null;
        for (int i = 0; i < kals.length() && gevonden == null; i++) {
            JSONObject x = kals.optJSONObject(i);
            if (x != null && !kg.isEmpty() && kg.equals(x.optString("gid")) && acc.equals(x.optString("account"))) gevonden = x;
        }
        for (int i = 0; i < kals.length() && gevonden == null; i++) {
            JSONObject x = kals.optJSONObject(i);
            if (x != null && acc.equals(x.optString("account")) && naam.equals(x.optString("naam"))) gevonden = x;
        }
        if (gevonden == null) return null;
        String oud = cfg.optString("kal");
        try { cfg.put("kal", gevonden.optString("id")).put("kalNaam", gevonden.optString("naam")).put("kalGid", gevonden.optString("gid")); } catch (Exception ignored) { }
        bewaarInstellingen(c, cfg);
        verhuis(c, oud, gevonden.optString("id"));
        return gevonden;
    }

    /** Afspraken die bij het oude id hoorden, horen nu bij het nieuwe; hun koppeling wordt opnieuw gezocht. */
    private static void verhuis(Context c, String oud, String nieuw) {
        if (oud == null || oud.isEmpty() || oud.equals(nieuw)) return;
        synchronized (AgendaStore.class) {
            JSONArray items = AgendaStore.items(c);
            if (items == null) return;
            boolean iets = false;
            try {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it == null || !oud.equals(it.optString("kalender"))) continue;
                    it.put("kalender", nieuw).put("ext", 0); // sync (met het id van Google) blijft: daarmee wordt hij teruggevonden
                    iets = true;
                }
                if (iets) AgendaStore.bewaar(c, items);
            } catch (Exception ignored) { }
            toestand(c).edit().remove("kalKey").apply();
        }
    }

    // ---------------------------------------------------------------- toestand

    static JSONArray lijst(Context c, String sleutel) {
        try { return new JSONArray(toestand(c).getString(sleutel, "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    /**
     * In Tools weg of losgemaakt, in Google gebleven: niet opnieuw ophalen. In het instellingenbestand "agenda" (gaat mee
     * in backups), met het id van Google (_SYNC_ID, gelijk op elke telefoon) en het id van deze telefoon.
     */
    static JSONArray weg(Context c) {
        try { return new JSONArray(AgendaStore.prefs(c).getString("syncWeg", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    static void onthoudWeg(Context c, JSONObject item) {
        if (item == null || item.optLong("ext") <= 0) return;
        long ext = item.optLong("ext");
        JSONObject meta = item.optJSONObject("sync");
        String gid = meta == null ? "" : meta.optString("gid");
        if (gid.isEmpty()) {
            JSONObject ev = AgendaCal.event(c, ext);
            if (ev != null && !ev.optBoolean("fout")) gid = ev.optString("gid");
        }
        try {
            JSONArray oud = weg(c), nieuw = new JSONArray().put(new JSONObject().put("e", ext).put("g", gid));
            for (int i = 0; i < oud.length() && nieuw.length() < AgendaSyncRegels.MAX_WEG; i++) {
                JSONObject w = oud.optJSONObject(i);
                if (w != null && w.optLong("e") != ext) nieuw.put(w);
            }
            AgendaStore.prefs(c).edit().putString("syncWeg", nieuw.toString()).apply();
        } catch (Exception ignored) { }
    }

    /** Afspraken op de lijst van weg die nog geen id van Google hadden: alsnog opzoeken (alleen op dezelfde telefoon). */
    private static void vulWegAan(Context c, JSONArray weg) {
        boolean iets = false;
        try {
            for (int i = 0; i < weg.length() && i < 100; i++) {
                JSONObject w = weg.optJSONObject(i);
                if (w == null || !w.optString("g").isEmpty() || w.optLong("e") <= 0) continue;
                JSONObject ev = AgendaCal.event(c, w.optLong("e"));
                if (ev != null && !ev.optBoolean("fout") && !ev.optString("gid").isEmpty()) { w.put("g", ev.optString("gid")); iets = true; }
            }
            if (iets) AgendaStore.prefs(c).edit().putString("syncWeg", weg.toString()).apply();
        } catch (Exception ignored) { }
    }

    /** Staat deze afspraak uit Google op de lijst van weg? Het id van deze telefoon telt alleen op dezelfde telefoon. */
    static boolean isWeg(JSONArray weg, JSONObject ev, boolean herkoppel) {
        String gid = ev.optString("gid");
        long ext = ev.optLong("ext");
        for (int i = 0; i < weg.length(); i++) {
            JSONObject w = weg.optJSONObject(i);
            if (w == null) continue;
            if (!gid.isEmpty() && gid.equals(w.optString("g"))) return true;
            if (!herkoppel && ext > 0 && ext == w.optLong("e") && (gid.isEmpty() || w.optString("g").isEmpty())) return true;
        }
        return false;
    }

    static void vergeetConflict(Context c, String id) {
        JSONArray oud = lijst(c, "conflicten"), nieuw = new JSONArray();
        for (int i = 0; i < oud.length(); i++) {
            JSONObject k = oud.optJSONObject(i);
            if (k != null && !id.equals(k.optString("id"))) nieuw.put(k);
        }
        toestand(c).edit().putString("conflicten", nieuw.toString()).apply();
    }

    /** Afspraken die alleen in Tools staan en naar Google zouden gaan (voor de vraag bij het aanzetten). */
    static int teVersturen(Context c, JSONObject cfg) {
        JSONArray items = AgendaStore.items(c);
        if (items == null) return 0;
        String[] v = AgendaSyncRegels.venster(cfg, Regio.dayKey(c, System.currentTimeMillis()));
        int n = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it != null && it.optString("kalender").isEmpty() && !it.optBoolean("lokaal") && AgendaSyncRegels.inVenster(it, v)) n++;
        }
        return n;
    }

    /** Alleen nieuwe afspraken naar Google: wat er nu alleen in Tools staat, blijft alleen in Tools. */
    static int houdBestaandeLokaal(Context c) {
        synchronized (AgendaStore.class) {
            JSONArray items = AgendaStore.items(c);
            if (items == null) return 0;
            int n = 0;
            try {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it != null && it.optString("kalender").isEmpty() && !it.optBoolean("lokaal")) { it.put("lokaal", true); n++; }
                }
                if (n > 0 && !AgendaStore.bewaar(c, items).isEmpty()) return 0;
            } catch (Exception e) { return 0; }
            return n;
        }
    }

    // ---------------------------------------------------------------- de ronde

    static final class Uit {
        final int[] n = new int[10];
        String fout = "";
        boolean bezig;
        long tijd;
    }

    /** Vingerafdrukken na een ronde of na bewaren. */
    static void meta(JSONObject it, String t, String g, String gid) {
        try { it.put("sync", new JSONObject().put("t", t).put("g", g).put("gid", gid == null ? "" : gid)); } catch (Exception ignored) { }
    }

    private static void kopieer(JSONObject ev, JSONObject it, JSONObject kal) throws Exception {
        JSONObject v = AgendaSyncRegels.vorm(ev); // in de vorm van Tools: anders wordt een afspraak tot middernacht bij terugschrijven korter
        for (String k : new String[]{"titel", "plek", "notitie", "dag", "eind", "van", "tot", "herhaal", "totDag"}) it.put(k, v.optString(k));
        it.put("heleDag", v.optBoolean("heleDag"));
        it.put("kalender", kal.optString("id")).put("ext", ev.optLong("ext")).put("naam", kal.optString("naam"));
        String kleur = Agenda.kleurOk(ev.optString("kleur")) ? ev.optString("kleur") : kal.optString("kleur");
        it.put("kleur", Agenda.kleurOk(kleur) ? kleur : "");
        it.remove("lokaal");
        it.remove("complex");
    }

    private static String gid(JSONObject ev) { return ev == null ? "" : ev.optString("gid"); }

    /** Schrijf naar Google en lees terug (zo telt de vorm die de telefoon ervan maakt). Geeft het id, of ≤ 0. */
    private static long naarGoogle(Context c, JSONObject it, JSONObject kal) throws Exception {
        it.put("kalender", kal.optString("id"));
        long ext = AgendaCal.schrijf(c, it);
        if (ext <= 0) return ext;
        it.put("ext", ext).put("naam", kal.optString("naam"));
        if (Agenda.kleurOk(kal.optString("kleur"))) it.put("kleur", kal.optString("kleur"));
        JSONObject terug = AgendaCal.event(c, ext);
        boolean goed = terug != null && !terug.optBoolean("fout");
        meta(it, AgendaSyncRegels.vinger(it), AgendaSyncRegels.vinger(goed ? terug : it), goed ? gid(terug) : "");
        it.remove("lokaal");
        return ext;
    }

    /** Wat er met afspraken gebeurt die uit Google verdwenen: wis, opnieuw (Tools → Google) of los. */
    static String verdwenenActie(JSONObject cfg) {
        if (AgendaSyncRegels.wisInTools(cfg)) return "wis";
        if ("naar".equals(cfg.optString("richting"))) return "opnieuw";
        return "los";
    }

    private static boolean ingewikkeld(JSONObject ev) {
        return ev != null && (ev.optBoolean("ingewikkeld") || AgendaSyncRegels.complex(ev.optString("rrule"), ev.optString("dag")));
    }

    /**
     * Eén ronde. waarom: hand, plan, open (alleen voor het logboek). rem: wat te doen als de veiligheidsrem het
     * verwerken van verdwenen afspraken tegenhoudt: "" = wachten op Rene, "wis" = doorgaan zoals ingesteld (alleen als
     * het er niet meer zijn dan Rene zag), "los" = in Tools houden zonder koppeling.
     */
    static Uit ronde(Context c, String waarom, String rem) {
        Uit u = new Uit();
        if (!BEZIG.compareAndSet(false, true)) { u.bezig = true; return u; }
        long start = System.currentTimeMillis();
        u.tijd = start;
        try {
            rondeNu(c, u, rem == null ? "" : rem);
        } catch (Throwable t) {
            u.fout = "telefoon";
            App.log(c, "AGENDA", "sync: " + t.getClass().getSimpleName());
        } finally {
            BEZIG.set(false);
        }
        if (!"uit".equals(u.fout)) na(c, u, waarom, System.currentTimeMillis() - start);
        return u;
    }

    /** Wat een ronde nodig heeft, eerst gelezen (buiten het slot van de opslag: lezen kan even duren). */
    static final class Ronde {
        JSONObject cfg, kal;
        String kid = "", rem = "";
        String[] v;
        Map<Long, JSONObject> g;
        boolean herkoppel, zoneWissel;
        JSONArray weg, oudConflict, conflicten = new JSONArray();
        Set<Long> gebruikt = new HashSet<>();
        int werk;
        boolean veranderd, schreef;
    }

    private static void rondeNu(Context c, Uit u, String rem) throws Exception {
        Ronde r = new Ronde();
        r.cfg = instellingen(c);
        r.rem = rem;
        if (!AgendaSyncRegels.aan(r.cfg)) { u.fout = "uit"; return; }
        boolean naar = AgendaSyncRegels.naarGoogle(r.cfg);
        if (!AgendaCal.lezen(c) || (naar && !AgendaCal.schrijven(c))) { u.fout = "recht"; return; }
        r.kal = kalender(c, r.cfg);
        if (r.kal == null) { u.fout = "kalender"; return; }
        if (naar && !r.kal.optBoolean("schrijf")) { u.fout = "lezen"; return; }
        // de telefoon synchroniseert deze agenda niet (uitgezet in de agenda-app): dan zijn de afspraken er niet, niet weg
        if (!r.kal.optBoolean("syncEvents", true)) { u.fout = "uitgezet"; return; }
        r.kid = r.kal.optString("id");
        SharedPreferences st = toestand(c);
        r.v = AgendaSyncRegels.venster(r.cfg, Regio.dayKey(c, System.currentTimeMillis()));
        r.g = AgendaCal.vanAgenda(c, r.kid, r.v[0], r.v[1], AgendaSyncRegels.MAX_PER_RONDE);
        if (r.g == null) { u.fout = "telefoon"; return; }
        String sleutel = r.kid + "|" + r.kal.optString("account");
        r.herkoppel = !sleutel.equals(st.getString("kalKey", "")); // eerste ronde op deze telefoon of met deze agenda
        String zone = Regio.zone(c).getID(), oudeZone = st.getString("zone", "");
        r.zoneWissel = !oudeZone.isEmpty() && !oudeZone.equals(zone);
        r.weg = weg(c);
        if (!r.herkoppel) vulWegAan(c, r.weg);
        r.oudConflict = lijst(c, "conflicten");
        synchronized (AgendaStore.class) {
            JSONArray items = AgendaStore.items(c);
            if (items == null) { u.fout = "onleesbaar"; return; }
            if (r.herkoppel && r.g.isEmpty()) {
                // nieuwe telefoon of andere agenda, en die is (nog) leeg terwijl er afspraken aan gekoppeld waren: wachten
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it != null && r.kid.equals(it.optString("kalender")) && it.optJSONObject("sync") != null) { u.fout = "leeg"; return; }
                }
            }
            List<JSONObject> lijst = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) if (items.optJSONObject(i) != null) lijst.add(items.optJSONObject(i));
            SharedPreferences.Editor e = st.edit();
            verwerkAlles(c, r, lijst, u.n, e);
            if (r.veranderd) {
                JSONArray nieuw = new JSONArray();
                for (JSONObject it : lijst) nieuw.put(it);
                String f = AgendaStore.bewaar(c, nieuw);
                if (!f.isEmpty()) { u.fout = "onleesbaar".equals(f) ? "onleesbaar" : "opslaan"; e.apply(); return; }
            }
            e.putString("conflicten", r.conflicten.toString()).putString("kalKey", sleutel).putString("zone", zone)
                    .putLong("laatst", System.currentTimeMillis()).apply();
        }
        if (r.schreef && r.cfg.optBoolean("googleNu", true)) AgendaCal.nuBijwerken(c, r.kal);
    }

    private static void verwerkAlles(Context c, Ronde r, List<JSONObject> lijst, int[] n, SharedPreferences.Editor e) throws Exception {
        JSONObject cfg = r.cfg, kal = r.kal;
        String kid = r.kid;
        boolean naar = AgendaSyncRegels.naarGoogle(cfg), van = AgendaSyncRegels.vanGoogle(cfg);
        List<JSONObject> verdwenen = new ArrayList<>();

        // 1. wat al gekoppeld is (of gekoppeld was: kalender = deze agenda, met het id van Google in sync)
        // 1a. eerst de koppelingen op id nakijken, zodat 1b geen afspraak pakt die bij een ander hoort
        boolean afgekapt = r.g.size() >= AgendaSyncRegels.MAX_PER_RONDE; // niet alles gelezen: niets als verdwenen tellen
        java.util.IdentityHashMap<JSONObject, JSONObject> evVan = new java.util.IdentityHashMap<>();
        java.util.Set<JSONObject> mislukt = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<JSONObject, Boolean>());
        for (JSONObject it : lijst) {
            if (!kid.equals(it.optString("kalender"))) continue;
            long ext = it.optLong("ext");
            if (ext <= 0) continue;
            JSONObject meta = it.optJSONObject("sync");
            String mg = meta == null ? "" : meta.optString("gid");
            JSONObject ev = r.g.get(ext);
            if (ev == null) {
                JSONObject los = AgendaCal.event(c, ext);
                if (los != null && los.optBoolean("fout")) { n[MISLUKT]++; r.gebruikt.add(ext); mislukt.add(it); continue; }
                ev = los != null && kid.equals(los.optString("kalender")) ? los : null;
            }
            if (r.herkoppel) {
                // het id geldt alleen als het dezelfde afspraak is: hetzelfde id van Google, of dezelfde titel, dag en tijd
                boolean zelfde = ev != null && ((!mg.isEmpty() && mg.equals(gid(ev))) || (mg.isEmpty() && AgendaSyncRegels.zelfde(it, ev)));
                if (!zelfde) { it.put("ext", 0); r.veranderd = true; continue; }
            }
            r.gebruikt.add(ext);
            if (ev != null) evVan.put(it, ev);
        }
        // 1b. verwerken
        for (JSONObject it : lijst) {
            if (!kid.equals(it.optString("kalender")) || mislukt.contains(it)) continue;
            long ext = it.optLong("ext");
            JSONObject meta = it.optJSONObject("sync");
            String mg = meta == null ? "" : meta.optString("gid");
            JSONObject ev = evVan.get(it);
            if (ext <= 0) {
                if (mg.isEmpty()) continue; // nooit in Google geweest: hieronder (2 en 3)
                // stond in Google: terugvinden op het id van Google, eerst in wat gelezen is, dan in de hele agenda
                for (JSONObject x : r.g.values()) if (!r.gebruikt.contains(x.optLong("ext")) && mg.equals(gid(x))) { ev = x; break; }
                if (ev == null) {
                    JSONObject los = AgendaCal.opGid(c, kid, mg);
                    if (los != null && los.optBoolean("fout")) { n[MISLUKT]++; continue; }
                    if (los != null && !r.gebruikt.contains(los.optLong("ext"))) ev = los;
                }
                if (ev == null) {
                    if (!AgendaSyncRegels.inVenster(it, r.v) || r.herkoppel || afgekapt) continue; // eerste ronde: de telefoon haalt de agenda misschien nog binnen
                    verdwenen.add(it); // niet terug te vinden: weg in Google, met de veiligheidsrem
                    continue;
                }
                ext = ev.optLong("ext");
                it.put("ext", ext).put("naam", kal.optString("naam"));
                r.gebruikt.add(ext);
                r.veranderd = true;
            }
            if (ev == null) {
                if (!AgendaSyncRegels.inVenster(it, r.v) || afgekapt) continue; // buiten het bereik (of niet alles gelezen): blijft zoals het is
                verdwenen.add(it); // weg in Google: hieronder, met de veiligheidsrem
                continue;
            }
            // het id van Google komt pas als de telefoon de afspraak naar Google stuurde: dan alsnog onthouden
            if (meta != null && mg.isEmpty() && !gid(ev).isEmpty()) { meta.put("gid", gid(ev)); r.veranderd = true; }
            if (ingewikkeld(ev)) continue; // een herhaling die Tools niet precies kan tonen: niet aanraken
            if (!AgendaSyncRegels.inVenster(it, r.v) && !AgendaSyncRegels.inVenster(ev, r.v)) continue;
            if (++r.werk > AgendaSyncRegels.MAX_PER_RONDE) continue; // de rest de volgende ronde
            verwerk(c, r, it, ev, n, meta != null && meta.optBoolean("vers"));
        }

        // 1b. weg in Google
        if (!verdwenen.isEmpty()) {
            String actie = verdwenenActie(cfg);
            int toegestaan = toestand(c).getInt("rem", 0);
            boolean door = "los".equals(r.rem) || ("wis".equals(r.rem) && verdwenen.size() <= toegestaan)
                    || !AgendaSyncRegels.remRaakt(verdwenen.size(), cfg.optInt("rem", 10), r.g.size());
            if (!door) {
                n[REM] = verdwenen.size();
                e.putInt("rem", verdwenen.size());
            } else {
                if ("los".equals(r.rem)) actie = "los";
                for (JSONObject it : verdwenen) {
                    if ("wis".equals(actie)) { lijst.remove(it); n[WEG_TOOLS]++; }
                    else if ("opnieuw".equals(actie)) { it.put("ext", 0); it.remove("sync"); } // Google volgt Tools: hieronder opnieuw zetten
                    else {
                        it.put("ext", 0).put("kalender", "").put("naam", "").put("lokaal", true);
                        it.remove("sync");
                        n[LOS]++;
                    }
                }
                e.remove("rem");
                r.veranderd = true;
            }
        } else e.remove("rem");

        // 2. Tools → Google: wat alleen in Tools staat (of opnieuw gezet moet worden)
        if (naar) for (JSONObject it : lijst) {
            String k = it.optString("kalender");
            boolean eigen = k.isEmpty() && !it.optBoolean("lokaal");
            JSONObject meta = it.optJSONObject("sync");
            boolean opnieuw = kid.equals(k) && it.optLong("ext") <= 0 && (meta == null || meta.optString("gid").isEmpty()); // met een id van Google: stap 1
            if ((!eigen && !opnieuw) || !AgendaSyncRegels.inVenster(it, r.v)) continue;
            if (++r.werk > AgendaSyncRegels.MAX_PER_RONDE) break;
            JSONObject ev = zoekZelfde(r, it);
            if (ev != null) {
                // staat er al (na terugzetten, of opnieuw aangezet): koppelen in plaats van een dubbele
                koppel(it, ev, kal);
                r.gebruikt.add(ev.optLong("ext"));
                r.veranderd = true;
                if (!ingewikkeld(ev)) verwerk(c, r, it, ev, n, true);
                continue;
            }
            String oudNaam = it.optString("naam"), oudKleur = it.optString("kleur");
            long ne = naarGoogle(c, it, kal);
            if (ne <= 0) {
                it.put("kalender", k).put("ext", 0).put("naam", oudNaam).put("kleur", oudKleur); // blijft zoals het was; volgende ronde opnieuw
                n[MISLUKT]++;
                continue;
            }
            r.gebruikt.add(ne);
            n[NAAR_NIEUW]++; r.veranderd = true; r.schreef = true;
        }

        // 3. Google → Tools: wat alleen in Google staat
        if (van) for (JSONObject ev : r.g.values()) {
            long ext = ev.optLong("ext");
            if (ext <= 0 || r.gebruikt.contains(ext) || ingewikkeld(ev) || isWeg(r.weg, ev, r.herkoppel) || !AgendaSyncRegels.inVenster(ev, r.v)) continue;
            if (++r.werk > AgendaSyncRegels.MAX_PER_RONDE) break;
            JSONObject it = zoekEigen(lijst, kid, ev);
            if (it != null) {
                koppel(it, ev, kal);
                r.gebruikt.add(ext);
                r.veranderd = true;
                verwerk(c, r, it, ev, n, true);
                continue;
            }
            if (lijst.size() >= Agenda.MAX_ITEMS) { n[MISLUKT]++; break; }
            JSONObject nieuw = new JSONObject().put("id", Agenda.nieuwId());
            kopieer(ev, nieuw, kal);
            meta(nieuw, AgendaSyncRegels.vinger(nieuw), AgendaSyncRegels.vinger(ev), gid(ev));
            lijst.add(nieuw);
            r.gebruikt.add(ext);
            n[VAN_NIEUW]++; r.veranderd = true;
        }
    }

    /** Een afspraak aan een afspraak in Google koppelen; de vingerafdrukken volgen in verwerk (vers). */
    private static void koppel(JSONObject it, JSONObject ev, JSONObject kal) throws Exception {
        it.put("kalender", kal.optString("id")).put("ext", ev.optLong("ext")).put("naam", kal.optString("naam"));
        it.remove("sync"); // gevonden op titel, dag en tijd: wat er verder anders is, beslist verwerk (vers)
        it.remove("lokaal");
    }

    /** Dezelfde afspraak in Google, nog niet gekoppeld: eerst op het id van Google, dan op titel, dag en tijd. */
    private static JSONObject zoekZelfde(Ronde r, JSONObject it) {
        JSONObject meta = it.optJSONObject("sync");
        String mg = meta == null ? "" : meta.optString("gid");
        if (!mg.isEmpty()) for (JSONObject ev : r.g.values()) if (!r.gebruikt.contains(ev.optLong("ext")) && mg.equals(gid(ev))) return ev;
        // op titel, dag en tijd alleen als niet allebei een (ander) id van Google hebben
        for (JSONObject ev : r.g.values()) if (!r.gebruikt.contains(ev.optLong("ext")) && (mg.isEmpty() || gid(ev).isEmpty()) && AgendaSyncRegels.zelfde(it, ev)) return ev;
        return null;
    }

    /** Een eigen afspraak zonder koppeling die dezelfde is als deze uit Google (niet een die Rene alleen in Tools wil). */
    private static JSONObject zoekEigen(List<JSONObject> lijst, String kid, JSONObject ev) {
        String g = gid(ev);
        JSONObject opNaam = null;
        for (JSONObject it : lijst) {
            String k = it.optString("kalender");
            boolean vrij = (k.isEmpty() && !it.optBoolean("lokaal")) || (kid.equals(k) && it.optLong("ext") <= 0);
            if (!vrij) continue;
            JSONObject meta = it.optJSONObject("sync");
            if (!g.isEmpty() && meta != null && g.equals(meta.optString("gid"))) return it;
            if (opNaam == null && AgendaSyncRegels.zelfde(it, ev)) opNaam = it;
        }
        return opNaam;
    }

    /** Eén gekoppelde afspraak bijwerken volgens de regels. vers: net voor het eerst gekoppeld. */
    private static int verwerk(Context c, Ronde r, JSONObject it, JSONObject ev, int[] n, boolean vers) throws Exception {
        JSONObject meta = it.optJSONObject("sync");
        String t = AgendaSyncRegels.vinger(it), gv = AgendaSyncRegels.vinger(ev);
        String tOud = meta == null ? "" : meta.optString("t"), gOud = meta == null ? "" : meta.optString("g");
        int b = AgendaSyncRegels.besluit(r.cfg, t, gv, tOud, gOud, vers);
        // de tijdzone van de app is gewijzigd: Google geeft dezelfde momenten in andere wandkloktijden; neem die over
        if (r.zoneWissel && !tOud.isEmpty() && t.equals(tOud) && !gv.equals(gOud)) b = AgendaSyncRegels.NAAR_TOOLS;
        switch (b) {
            case AgendaSyncRegels.KOPPEL:
                meta(it, t, gv, gid(ev));
                r.veranderd = true;
                break;
            case AgendaSyncRegels.NAAR_GOOGLE: {
                long ne = naarGoogle(c, it, r.kal);
                if (ne <= 0) { n[MISLUKT]++; return AgendaSyncRegels.NIETS; }
                n[NAAR_BIJ]++;
                r.veranderd = true; r.schreef = true;
                break;
            }
            case AgendaSyncRegels.NAAR_TOOLS: {
                // de agenda is voor het slot gelezen: kijk vlak voor het overnemen nog één keer (Rene kan net iets bewaard hebben)
                JSONObject vers2 = AgendaCal.event(c, ev.optLong("ext"));
                if (vers2 == null || vers2.optBoolean("fout")) return AgendaSyncRegels.NIETS;
                String g2 = AgendaSyncRegels.vinger(vers2);
                if (!g2.equals(gv)) return AgendaSyncRegels.NIETS; // intussen veranderd: de volgende ronde
                kopieer(vers2, it, r.kal);
                meta(it, AgendaSyncRegels.vinger(it), gv, gid(ev).isEmpty() ? gid(vers2) : gid(ev));
                n[VAN_BIJ]++;
                r.veranderd = true;
                break;
            }
            case AgendaSyncRegels.CONFLICTEERT: {
                long sinds = System.currentTimeMillis();
                for (int i = 0; i < r.oudConflict.length(); i++) {
                    JSONObject k = r.oudConflict.optJSONObject(i);
                    if (k != null && it.optString("id").equals(k.optString("id"))) sinds = k.optLong("sinds", sinds);
                }
                if (r.conflicten.length() < AgendaSyncRegels.MAX_CONFLICT) r.conflicten.put(new JSONObject().put("id", it.optString("id")).put("sinds", sinds));
                // net gekoppeld: onthouden, zodat het de volgende ronde weer een conflict is (en niet stil één kant wint)
                if (vers) it.put("sync", new JSONObject().put("vers", true).put("gid", gid(ev)));
                r.veranderd = true;
                n[CONFLICT]++;
                break;
            }
            default: break;
        }
        return b;
    }

    /** Na de ronde: logboek, logregel en zo nodig een melding. */
    private static void na(Context c, Uit u, String waarom, long duur) {
        try {
            SharedPreferences st = toestand(c);
            JSONArray log = AgendaSyncRegels.logErbij(lijst(c, "log"), AgendaSyncRegels.logRegel(u.tijd, waarom, duur, u.n, u.fout));
            String vorigeFout = st.getString("fout", "");
            int vorigConflict = st.getInt("nConflict", 0);
            st.edit().putString("log", log.toString()).putString("fout", u.fout).putInt("nConflict", u.n[CONFLICT]).apply();
            StringBuilder b = new StringBuilder("sync");
            String[] namen = {"naar+", "naar~", "van+", "van~", "wegT", "wegG", "los", "conflict", "mislukt", "rem"};
            for (int i = 0; i < namen.length; i++) if (u.n[i] > 0) b.append(' ').append(namen[i]).append('=').append(u.n[i]);
            if (!u.fout.isEmpty()) b.append(" mislukt reden=").append(u.fout);
            App.log(c, "AGENDA", b.toString());
            String meld = instellingen(c).optString("meld", "fouten");
            if ("nooit".equals(meld) || "hand".equals(waarom)) return;
            boolean nieuwFout = !u.fout.isEmpty() && !u.fout.equals(vorigeFout);
            boolean nieuwConflict = u.n[CONFLICT] > vorigConflict;
            boolean rem = u.n[REM] > 0 && st.getInt("remGemeld", 0) != u.n[REM];
            if (rem) st.edit().putInt("remGemeld", u.n[REM]).apply();
            if (nieuwFout || nieuwConflict || rem || ("altijd".equals(meld) && AgendaSyncRegels.gewijzigd(u.n))) melding(c, u);
        } catch (Throwable t) {
            App.log(c, "AGENDA", "sync na: " + t.getClass().getSimpleName());
        }
    }

    static String foutTekst(Context c, String code) {
        switch (code == null ? "" : code) {
            case "": return "";
            case "recht": return Taal.t(c, "Geen toestemming om de agenda van je telefoon te lezen of te wijzigen.");
            case "kalender": return Taal.t(c, "De gekozen agenda staat niet meer op je telefoon. Kies hem opnieuw.");
            case "lezen": return Taal.t(c, "In de gekozen agenda mag Tools niet schrijven.");
            case "onleesbaar": return Taal.t(c, "De agenda is niet leesbaar. Er is niets overschreven.");
            case "opslaan": return Taal.t(c, "Opslaan lukte niet.");
            case "leeg": return Taal.t(c, "De gekozen agenda is nog leeg op deze telefoon. Tools wacht tot de telefoon hem van Google heeft binnengehaald.");
            case "uitgezet": return Taal.t(c, "Je telefoon synchroniseert de gekozen agenda niet. Zet synchroniseren voor die agenda aan in de agenda-app van je telefoon.");
            default: return Taal.t(c, "De agenda van je telefoon reageerde niet.");
        }
    }

    /** Korte samenvatting van een ronde, alleen aantallen. */
    static String samenvatting(Context c, int[] n) {
        List<String> d = new ArrayList<>();
        if (n[NAAR_NIEUW] + n[NAAR_BIJ] > 0) d.add(Taal.tn(c, n[NAAR_NIEUW] + n[NAAR_BIJ], "1 naar Google", "{n} naar Google"));
        if (n[VAN_NIEUW] + n[VAN_BIJ] > 0) d.add(Taal.tn(c, n[VAN_NIEUW] + n[VAN_BIJ], "1 uit Google", "{n} uit Google"));
        if (n[WEG_TOOLS] > 0) d.add(Taal.tn(c, n[WEG_TOOLS], "1 verwijderd", "{n} verwijderd"));
        if (n[LOS] > 0) d.add(Taal.tn(c, n[LOS], "1 losgemaakt", "{n} losgemaakt"));
        if (n[CONFLICT] > 0) d.add(Taal.tn(c, n[CONFLICT], "1 conflict", "{n} conflicten"));
        if (n[MISLUKT] > 0) d.add(Taal.tn(c, n[MISLUKT], "1 mislukt", "{n} mislukt"));
        if (n[REM] > 0) d.add(Taal.tn(c, n[REM], "1 wacht op je ok om te verwijderen", "{n} wachten op je ok om te verwijderen"));
        if (d.isEmpty()) return Taal.t(c, "Niets veranderd");
        StringBuilder b = new StringBuilder();
        for (String s : d) { if (b.length() > 0) b.append(" · "); b.append(s); }
        return b.toString();
    }

    private static void melding(Context c, Uit u) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = c.getSystemService(NotificationManager.class);
                if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                    NotificationChannel ch = new NotificationChannel(CHANNEL, Taal.t(c, "Agenda synchroniseren"), NotificationManager.IMPORTANCE_DEFAULT);
                    ch.setDescription(Taal.t(c, "Als synchroniseren met Google een conflict vindt, niet lukt of wil verwijderen"));
                    nm.createNotificationChannel(ch);
                }
            }
            String titel = !u.fout.isEmpty() ? Taal.t(c, "Agenda: synchroniseren lukt niet")
                    : u.n[CONFLICT] > 0 ? Taal.t(c, "Agenda: kies welke versie blijft")
                    : u.n[REM] > 0 ? Taal.t(c, "Agenda: verwijderen wacht op je ok") : Taal.t(c, "Agenda gesynchroniseerd");
            String tekst = !u.fout.isEmpty() ? foutTekst(c, u.fout) : samenvatting(c, u.n);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, NotifCenter.ch(c, CHANNEL)) : new Notification.Builder(c);
            Intent i = new Intent(c, MainActivity.class).putExtra("open", "agenda-sync").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            Taal.keep(b, Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT);
            NotifCenter.post(c, MELDING, b.setSmallIcon(R.drawable.ic_notif).setContentTitle(titel).setContentText(tekst)
                    .setStyle(new Notification.BigTextStyle().bigText(tekst)).setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(c, MELDING, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
        } catch (Throwable t) {
            App.log(c, "AGENDA", "sync melding: " + t.getClass().getSimpleName());
        }
    }

    // ---------------------------------------------------------------- conflict oplossen

    /** keuze: tools, google of beide (de versie van Tools blijft als losse afspraak alleen in Tools). Foutcode of "". */
    static String kies(Context c, String id, String keuze) {
        synchronized (AgendaStore.class) {
            try {
                JSONObject cfg = instellingen(c);
                JSONObject kal = kalender(c, cfg);
                if (kal == null) return "kalender";
                JSONArray items = AgendaStore.items(c);
                if (items == null) return "onleesbaar";
                JSONObject it = null;
                for (int i = 0; i < items.length(); i++) {
                    JSONObject x = items.optJSONObject(i);
                    if (x != null && id.equals(x.optString("id"))) it = x;
                }
                if (it == null) { vergeetConflict(c, id); return "weg"; }
                JSONObject ev = AgendaCal.event(c, it.optLong("ext"));
                if (ev != null && ev.optBoolean("fout")) return "telefoon";
                if ("tools".equals(keuze) || ev == null) {
                    if (!AgendaCal.schrijven(c)) return "recht";
                    if (naarGoogle(c, it, kal) <= 0) return "telefoon";
                } else {
                    if ("beide".equals(keuze)) {
                        if (items.length() >= Agenda.MAX_ITEMS) return "vol";
                        JSONObject kopie = new JSONObject(it.toString()).put("id", Agenda.nieuwId()).put("kalender", "").put("ext", 0)
                                .put("naam", "").put("kleur", "").put("lokaal", true);
                        kopie.remove("sync");
                        items.put(kopie);
                    }
                    kopieer(ev, it, kal);
                    meta(it, AgendaSyncRegels.vinger(it), AgendaSyncRegels.vinger(ev), gid(ev));
                }
                String f = AgendaStore.bewaar(c, items);
                if (!f.isEmpty()) return f;
                vergeetConflict(c, id);
                SharedPreferences st = toestand(c);
                st.edit().putInt("nConflict", Math.max(0, st.getInt("nConflict", 0) - 1)).apply();
                if ("tools".equals(keuze) && cfg.optBoolean("googleNu", true)) AgendaCal.nuBijwerken(c, kal);
                App.log(c, "AGENDA", "sync conflict opgelost: " + ("tools".equals(keuze) ? "tools" : "beide".equals(keuze) ? "beide" : "google"));
                return "";
            } catch (Throwable t) {
                App.log(c, "AGENDA", "sync kies: " + t.getClass().getSimpleName());
                return "opslaan";
            }
        }
    }
}
