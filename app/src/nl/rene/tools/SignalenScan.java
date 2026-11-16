package nl.rene.tools;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Signalen: de bronnen nalopen en melden. Ophalen gaat via Nieuws.haal (alleen https naar openbare sites, begrensd in
 * tijd en grootte, ook bij doorverwijzingen). Er gaat niets anders naar de site dan het opvragen van de pagina zelf.
 * In het logboek (soort SIGNAAL) staan alleen aantallen en vaste woorden, nooit adressen, titels of trefwoorden.
 */
final class SignalenScan {
    static final AtomicBoolean BEZIG = new AtomicBoolean();
    private static final long PER_BRON = 45_000L, PER_PAGINA = 15_000L;

    private SignalenScan() { }

    private static String html(Nieuws.Antwoord a) {
        int n = Math.min(a.data.length, 2 * 1024 * 1024);
        return new String(a.data, 0, n, PodcastFeed.charset(a.data, n, a.charset));
    }

    /** De fout als vaste Nederlandse zin (de brug vertaalt hem), zonder adressen. */
    static String fout(Throwable e) {
        if (e instanceof Nieuws.TeTraag) return Taal.T("De website reageert te traag");
        if (e instanceof Nieuws.HttpFout) return Taal.fill(Taal.T("De website antwoordt met {code}"), "code", ((Nieuws.HttpFout) e).code);
        if (e instanceof Signalen.Fout || e instanceof Nieuws.Fout) return e.getMessage();
        if (e instanceof java.net.UnknownHostException) return Taal.T("De website is niet te vinden (of er is geen internet)");
        if (e instanceof java.io.IOException) return Taal.T("Geen verbinding met de website");
        return Taal.T("De pagina kon niet gelezen worden");
    }

    /** De pagina van een bron ophalen en de links eruit halen. */
    static List<String[]> haalLinks(JSONObject bron, long tot) throws Exception {
        Nieuws.Antwoord a = Nieuws.haal(bron.optString("url"), tot);
        List<String[]> links = Signalen.links(html(a), a.url);
        if (links.isEmpty()) throw new Signalen.Fout(Taal.T("Op deze pagina staan geen links naar andere pagina's van de site"));
        return links;
    }

    /** Een gevonden link uitwerken tot een item: titel, tekst en datum van de pagina zelf (lukt dat niet: alleen de linktekst). */
    static JSONObject item(JSONObject bron, String[] link, long tot, String vandaag, long nu) throws Exception {
        String titel = link[1], tekst = "", kop = "";
        try {
            Nieuws.Antwoord a = Nieuws.haal(link[0], Math.min(tot, System.currentTimeMillis() + PER_PAGINA));
            if (Signalen.host(a.url).equals(Signalen.host(link[0]))) {
                String h = html(a);
                Nieuws.Kop k = Nieuws.paginaKop(h, a.url);
                if (!k.titel.isEmpty()) titel = k.titel;
                kop = k.tekst;
                tekst = Signalen.stuk(Signalen.tekstVan(h, 60_000), bron.optJSONArray("woorden"), 900);
            }
        } catch (Throwable ignored) { }
        if (titel.isEmpty()) titel = Signalen.slak(link[0]);
        String[] d = Signalen.datum(titel + ". " + kop + " " + tekst, vandaag);
        return new JSONObject().put("id", Signalen.itemId(link[0])).put("bron", bron.optString("id")).put("bronNaam", bron.optString("naam"))
                .put("titel", NieuwsFeed.cut(titel, 300)).put("tekst", !kop.isEmpty() && !tekst.contains(kop) ? kop + (tekst.isEmpty() ? "" : "\n\n" + tekst) : tekst)
                .put("link", link[0]).put("gevonden", nu).put("dag", d[0]).put("tijd", d[1]).put("status", "nieuw").put("later", 0).put("gemeld", false);
    }

    /**
     * Bronnen nalopen. {@code id} leeg = alle bronnen die aan staan ({@code auto}: alleen wie aan de beurt is, en met
     * {@code wifi} onwaar alleen de bronnen die ook zonder wifi mogen). Geeft {ok, nieuw, bronnen, fouten}.
     */
    static JSONObject scan(Context c, String id, boolean auto, boolean wifi) throws Exception {
        JSONObject uit = new JSONObject().put("ok", false).put("nieuw", 0).put("bronnen", 0).put("fouten", 0);
        if (!BEZIG.compareAndSet(false, true)) return uit.put("bezig", true);
        try {
            JSONArray bronnen = Signalen.bronnen(c);
            TimeZone tz = Regio.zone(c);
            int nieuw = 0, gedaan = 0, fouten = 0;
            if (Signalen.items(c) == null) return uit.put("onleesbaar", true); // niets als gezien noteren wat niet bewaard kan worden
            for (int i = 0; i < bronnen.length(); i++) {
                JSONObject b = bronnen.optJSONObject(i);
                if (b == null) continue;
                String bid = b.optString("id");
                if (id != null && !id.isEmpty() ? !id.equals(bid) : !b.optBoolean("aan", true)) continue;
                long nu = System.currentTimeMillis();
                JSONObject t = Signalen.toestand(c).optJSONObject(bid);
                if (t == null) t = new JSONObject();
                if (auto && (Signalen.aanDeBeurt(b, t.length() == 0 ? null : t, nu) > nu || (b.optBoolean("wifi", true) && !wifi))) continue;
                gedaan++;
                t.put("lastTry", nu);
                try {
                    long tot = nu + PER_BRON;
                    List<String[]> links = haalLinks(b, tot);
                    Set<String> gezien = new HashSet<>();
                    JSONArray g = t.optJSONArray("gezien");
                    for (int k = 0; g != null && k < g.length(); k++) gezien.add(g.optString(k));
                    Signalen.Vondst v = Signalen.nieuw(b, links, gezien, Signalen.MAX_PER_SCAN);
                    boolean eerste = !t.optBoolean("klaar");
                    String vandaag = Regio.dayKey(tz, nu);
                    List<JSONObject> items = new ArrayList<>();
                    for (String[] l : v.nieuw) {
                        JSONObject it = item(b, l, tot, vandaag, nu - items.size()); // de volgorde van de pagina blijft: bovenaan is het nieuwst
                        if (eerste) it.put("gemeld", true).put("eerste", true); // wat er al stond: wel in de inbox, geen melding
                        items.add(it);
                    }
                    int erbij = Signalen.voegToe(c, items);
                    nieuw += erbij;
                    JSONArray ng = new JSONArray();
                    for (String s : v.gezien) ng.put(s);
                    for (int k = 0; g != null && k < g.length() && ng.length() < Signalen.MAX_GEZIEN; k++) ng.put(g.optString(k));
                    t.put("gezien", ng).put("klaar", true).put("lastOk", nu).put("fout", "").put("fouten", 0).put("links", v.links).put("gevonden", erbij).put("gemeldFout", false);
                    App.log(c, "SIGNAAL", "scan ok: " + v.links + " links, " + erbij + " nieuw");
                } catch (Throwable e) {
                    fouten++;
                    int n = t.optInt("fouten") + 1;
                    t.put("fout", fout(e)).put("fouten", n);
                    App.log(c, "SIGNAAL", "scan mislukt: " + e.getClass().getSimpleName());
                    if (auto && n >= Signalen.FOUT_MELDEN && !t.optBoolean("gemeldFout")) { t.put("gemeldFout", true); meldFout(c, b, t.optString("fout")); }
                }
                Signalen.zetToestand(c, bid, t, null);
            }
            return uit.put("ok", fouten == 0 || gedaan > fouten).put("nieuw", nieuw).put("bronnen", gedaan).put("fouten", fouten);
        } finally { BEZIG.set(false); }
    }

    /** Proef: wat zou deze bron nu vinden? Bewaart niets en meldt niets. Geeft {ok, links, gevonden:[{titel, link, dag}]} of {ok:false, fout}. */
    static JSONObject proef(Context c, JSONObject in) {
        JSONObject uit = new JSONObject();
        try {
            JSONObject b = Signalen.normaal(in);
            long nu = System.currentTimeMillis();
            Signalen.Vondst v = Signalen.nieuw(b, haalLinks(b, nu + PER_BRON), new HashSet<String>(), 10);
            String vandaag = Regio.dayKey(Regio.zone(c), nu);
            JSONArray g = new JSONArray();
            for (String[] l : v.nieuw) {
                String titel = l[1].isEmpty() ? Signalen.slak(l[0]) : l[1];
                g.put(new JSONObject().put("titel", titel).put("link", l[0]).put("dag", Signalen.datum(titel, vandaag)[0]));
            }
            return uit.put("ok", true).put("links", v.links).put("gevonden", g);
        } catch (Throwable e) {
            try { return uit.put("ok", false).put("fout", fout(e)); } catch (Exception x) { return uit; }
        }
    }

    // ---------------------------------------------------------------- meldingen

    static void kanaal(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(Signalen.CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(Signalen.CHANNEL, Taal.t(c, "Signalen"), NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription(Taal.t(c, "Als een bron die je volgt iets nieuws heeft, het dagoverzicht, of als een bron niet meer werkt"));
        nm.createNotificationChannel(ch);
    }

    private static void plaats(Context c, int nr, String open, String titel, String tekst) {
        kanaal(c);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, NotifCenter.ch(c, Signalen.CHANNEL)) : new Notification.Builder(c);
        Intent i = new Intent(c, MainActivity.class).putExtra("open", open).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        // titel en tekst komen van de website of zijn al vertaald: niet nog eens door het woordenboek
        Taal.keep(b, Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT);
        NotifCenter.post(c, nr, b.setSmallIcon(R.drawable.ic_notif).setContentTitle(titel).setContentText(tekst)
                .setStyle(new Notification.BigTextStyle().bigText(tekst)).setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(c, nr, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
    }

    static int nr(String id) { return 4900 + (id.hashCode() & 0x7fffffff) % 80; }

    private static void meldFout(Context c, JSONObject bron, String fout) {
        try {
            plaats(c, 4980 + (bron.optString("id").hashCode() & 0x7fffffff) % 10, "signalen-bron:" + bron.optString("id"),
                    Taal.t(c, "{naam}: nakijken lukt niet meer", "naam", bron.optString("naam")), Taal.tx(c, fout));
        } catch (Throwable ignored) { }
    }

    private static String regel(Context c, JSONObject it) {
        String dag = it.optString("dag");
        String wanneer = dag.isEmpty() ? "" : Regio.fmt(c, "EEE d MMM", Regio.toMillis(Regio.zone(c), dag, "12:00")) + (it.optString("tijd").isEmpty() ? "" : " " + it.optString("tijd"));
        return wanneer.isEmpty() ? it.optString("bronNaam") : wanneer + " · " + it.optString("bronNaam");
    }

    /**
     * Wat nu gemeld mag worden, melden: losse meldingen (direct, of een herinnering die je zelf vroeg), één dagoverzicht op het
     * gekozen moment, en niets tijdens de stille uren (behalve van een bron die je belangrijk noemt). Geeft het aantal meldingen.
     */
    static int meld(Context c) {
        synchronized (Signalen.class) { // zelfde slot als de opslag: een tik in de inbox en het melden schrijven niet door elkaar
            return meldNu(c);
        }
    }

    private static int meldNu(Context c) {
        try {
            JSONArray items = Signalen.items(c);
            if (items == null || items.length() == 0) return 0;
            JSONObject bronnen = Signalen.perId(Signalen.bronnen(c)), inst = Signalen.instellingen(c);
            TimeZone tz = Regio.zone(c);
            long nu = System.currentTimeMillis();
            String dag = Signalen.overzichtDag(c);
            boolean overzichtNu = Signalen.overzichtNu(inst, dag, nu, tz), anders = false;
            List<JSONObject> lijst = new ArrayList<>();
            int n = 0;
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                int w = Signalen.wijze(it, bronnen.optJSONObject(it.optString("bron")), inst, nu, tz);
                if (w == Signalen.NU) {
                    boolean later = it.optLong("later") > 0;
                    plaats(c, nr(it.optString("id")), "signalen:" + it.optString("id"), (later ? Taal.t(c, "Herinnering: {titel}", "titel", it.optString("titel")) : it.optString("titel")), regel(c, it));
                    it.put("gemeld", true).put("later", 0);
                    anders = true; n++;
                } else if (w == Signalen.STIL) { it.put("gemeld", true); anders = true; }
                else if (w == Signalen.OVERZICHT && overzichtNu) { lijst.add(it); it.put("gemeld", true); anders = true; }
            }
            if (!lijst.isEmpty()) {
                StringBuilder s = new StringBuilder();
                for (int i = 0; i < Math.min(6, lijst.size()); i++) s.append(i > 0 ? "\n" : "").append("• ").append(lijst.get(i).optString("titel"));
                if (lijst.size() > 6) s.append("\n…");
                plaats(c, 4999, "signalen", Taal.tn(c, lijst.size(), "Signalen: één nieuw bericht", "Signalen: {n} nieuwe berichten"), s.toString());
                n++;
            }
            if (overzichtNu) Signalen.zetToestand(c, null, null, Regio.dayKey(tz, nu));
            if (anders) Signalen.bewaarItems(c, items);
            if (n > 0) App.log(c, "SIGNAAL", "gemeld: " + n);
            return n;
        } catch (Throwable e) {
            App.log(c, "SIGNAAL", "melden mislukt: " + e.getClass().getSimpleName());
            return 0;
        }
    }
}
