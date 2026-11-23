package nl.rene.tools;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Diagnose: één plek waar een tool vastlegt wat er bij een actie gebeurde (bijv. de gelezen tekst van een rekening en
 * wat daaruit herkend is), zodat een probleem na te zoeken is. Anders dan het logboek en de statistieken bevat dit wel
 * inhoud. Daarom: alleen in het geheugen (weg als de app stopt), niets in een bestand of backup, en de telefoon verlaat
 * het alleen als Rene het per stuk verstuurt of kopieert, of als hij "Vanzelf sturen" heeft aangezet. Versturen gaat
 * naar dezelfde privé GitHub-repository als de statistieken (Statistiek), in de map diagnose/. Wat er verstuurd is,
 * staat in een lijstje (tijd, tool, aantal tekens; nooit de inhoud).
 */
final class Diagnose {
    private Diagnose() { }

    static final int MAX = 12, MAX_TEKENS = 120_000, MAX_VERSTUURD = 20;
    /** Zo lang blijft een stuk hooguit in het geheugen (de app kan dagen blijven draaien door radio of VPN). */
    static final long HOUDBAAR = 60 * 60_000L;
    private static final Object LOCK = new Object();
    private static final List<Item> ITEMS = new ArrayList<>();
    private static long teller;

    static final class Item {
        final String id, tool, titel, tekst;
        final long tijd;
        volatile long verstuurd;
        boolean bezig;
        Item(String id, String tool, String titel, String tekst, long tijd) { this.id = id; this.tool = tool; this.titel = titel; this.tekst = tekst; this.tijd = tijd; }
    }

    static String toolNaam(String tool) {
        String t = tool == null ? "" : tool.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return t.isEmpty() ? "tools" : t.length() > 24 ? t.substring(0, 24) : t;
    }

    /** Legt iets vast (alleen in het geheugen). Geeft het id. Zonder context: nooit vanzelf sturen. */
    static String leg(Context c, String tool, String titel, String tekst) {
        String id = legAlleen(tool, titel, tekst, System.currentTimeMillis());
        if (c != null && auto(c)) {
            final Context app = c.getApplicationContext();
            new Thread(App.veilig(() -> stuur(app, id)), "diagnose").start();
        }
        return id;
    }

    /** Webadressen (met of zonder https://, zoals een betaallink op een rekening) worden [link]: een betaallink hoort
     *  niet in Diagnose. Per woord (tussen witruimte), dus lineair in de lengte van de tekst. Ook "\\/" uit JSON, en het
     *  stuk van een link dat de tekstherkenning als eigen woord achter een eerder adres las (bijv. "tikkie." + "me/pay/…"). */
    private static final java.util.regex.Pattern LINK = java.util.regex.Pattern.compile(
            "(?is)(?:.*?(?:https?://|www\\.).*|[^/]*?[\\w-]\\.[\\w-]+/.*)");
    static String zonderLinks(String tekst) {
        if (tekst == null) return "";
        if (tekst.length() > 2 * MAX_TEKENS) tekst = tekst.substring(0, 2 * MAX_TEKENS); // legAlleen kapt daarna af op MAX_TEKENS
        tekst = tekst.replace("\\/", "/");
        StringBuilder uit = new StringBuilder(tekst.length());
        int n = tekst.length(), i = 0;
        boolean vorigeLink = false;
        while (i < n) {
            int b = i;
            if (Character.isWhitespace(tekst.charAt(i))) { while (i < n && Character.isWhitespace(tekst.charAt(i))) i++; uit.append(tekst, b, i); continue; }
            while (i < n && !Character.isWhitespace(tekst.charAt(i))) i++;
            String w = tekst.substring(b, i);
            boolean link = w.length() <= 4000 ? LINK.matcher(w).matches() : w.indexOf('/') >= 0 || w.contains("www.");
            if (!link && vorigeLink && w.indexOf('/') >= 0) link = true;
            uit.append(link ? "[link]" : w);
            vorigeLink = link;
        }
        return uit.toString();
    }

    static String legAlleen(String tool, String titel, String tekst, long nu) {
        String t = tekst == null ? "" : tekst.length() > MAX_TEKENS ? tekst.substring(0, MAX_TEKENS) + "\n[afgekapt]" : tekst;
        String kop = titel == null ? "" : titel.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (kop.length() > 80) kop = kop.substring(0, 80);
        synchronized (LOCK) {
            String id = "d" + (++teller);
            ITEMS.add(0, new Item(id, toolNaam(tool), kop, t, nu));
            while (ITEMS.size() > MAX) ITEMS.remove(ITEMS.size() - 1);
            return id;
        }
    }

    static Item item(String id) { return item(id, System.currentTimeMillis()); }

    static Item item(String id, long nu) {
        synchronized (LOCK) { verlopen(nu); for (Item i : ITEMS) if (i.id.equals(id)) return i; }
        return null;
    }

    /** Onder LOCK: wat ouder is dan HOUDBAAR gaat weg. */
    private static void verlopen(long nu) {
        for (int i = ITEMS.size() - 1; i >= 0; i--) if (nu - ITEMS.get(i).tijd > HOUDBAAR || ITEMS.get(i).tijd - nu > HOUDBAAR) ITEMS.remove(i);
    }

    static String tekst(String id) { Item i = item(id); return i == null ? "" : inhoud(i); }

    static void wis() { synchronized (LOCK) { ITEMS.clear(); } }

    /** Precies wat er gekopieerd of verstuurd wordt: een kop (tool, wat, tijd, versie) en de vastgelegde tekst. */
    static String inhoud(Item i) {
        return "Diagnose Tools " + Version.NAME + " (" + Version.CODE + ")\nTool: " + i.tool + "\nWat: " + i.titel + "\nTijd: " + Statistiek.iso(i.tijd) + "\n\n" + i.tekst;
    }

    /** Vanzelf sturen geldt alleen voor de repository waarvoor Rene het aanzette: een andere bestemming zet het uit. */
    static boolean auto(Context c) {
        SharedPreferences p = Statistiek.prefs(c);
        return autoGeldt(p.getBoolean("diagAuto", false), p.getString("diagAutoRepo", ""), p.getString("repo", ""));
    }
    static boolean autoGeldt(boolean aan, String voor, String repo) { return aan && repo != null && !repo.isEmpty() && repo.equals(voor); }
    static void setAuto(Context c, boolean on) {
        SharedPreferences p = Statistiek.prefs(c);
        p.edit().putBoolean("diagAuto", on && !p.getString("repo", "").isEmpty()).putString("diagAutoRepo", on ? p.getString("repo", "") : "").apply();
    }

    /** {items: [{id, tool, titel, tijd, tekens, verstuurd}], auto, repo, klaar (bestemming ingesteld), verstuurd: [{t, tool, n}]} */
    static JSONObject staat(Context c) {
        JSONObject uit = new JSONObject();
        try {
            JSONArray a = new JSONArray();
            synchronized (LOCK) {
                verlopen(System.currentTimeMillis());
                for (Item i : ITEMS) a.put(new JSONObject().put("id", i.id).put("tool", i.tool).put("titel", i.titel).put("tijd", i.tijd)
                        .put("tekens", inhoud(i).length()).put("verstuurd", i.verstuurd));
            }
            JSONObject s = Statistiek.state(c);
            uit.put("items", a).put("auto", auto(c)).put("kan", PlayPolicy.allows(c, "statistiek")).put("repo", s.optString("repo")).put("klaar", !s.optString("repo").isEmpty() && s.optBoolean("token"))
                    .put("verstuurd", verstuurd(Statistiek.prefs(c).getString("diagSent", "")));
        } catch (Exception ignored) { }
        return uit;
    }

    static JSONArray verstuurd(String bewaard) {
        try { return bewaard == null || bewaard.isEmpty() ? new JSONArray() : new JSONArray(bewaard); } catch (Exception e) { return new JSONArray(); }
    }

    /** Het lijstje van wat de telefoon verliet, met de nieuwste vooraan: alleen tijd, tool en aantal tekens. */
    static JSONArray erbij(JSONArray oud, long t, String tool, int tekens) {
        JSONArray uit = new JSONArray();
        try {
            uit.put(new JSONObject().put("t", t).put("tool", toolNaam(tool)).put("n", tekens));
            for (int i = 0; i < oud.length() && uit.length() < MAX_VERSTUURD; i++) {
                JSONObject o = oud.optJSONObject(i);
                if (o != null) uit.put(new JSONObject().put("t", o.optLong("t")).put("tool", toolNaam(o.optString("tool"))).put("n", o.optInt("n")));
            }
        } catch (Exception ignored) { }
        return uit;
    }

    /** Bestandsnaam in de repository: tijd tot op de seconde, de installatie, de tool en een volgnummer. */
    static String pad(long nu, String installatie, String tool, String id) {
        String naam = Statistiek.path(nu, installatie);
        naam = naam.substring(naam.lastIndexOf('/') + 1).replace(".json", "");
        return "diagnose/" + naam + "-" + toolNaam(tool) + "-" + id.replaceAll("[^a-z0-9]", "") + ".txt";
    }

    /** Verstuurt één stuk naar de privé repository. "" bij succes, anders de reden. Niet op de hoofdthread. */
    static String stuur(Context c, String id) {
        Item i = item(id);
        if (i == null) return "Deze diagnose is er niet meer";
        synchronized (LOCK) { // niet twee keer tegelijk hetzelfde stuk (vanzelf sturen en een tik)
            if (i.verstuurd > 0 || i.bezig) return "";
            i.bezig = true;
        }
        try { return stuurNu(c, i); } finally { synchronized (LOCK) { i.bezig = false; } }
    }

    private static String stuurNu(Context c, Item i) {
        String tekst = inhoud(i);
        long nu = System.currentTimeMillis();
        String r = Statistiek.zet(c, pad(nu, Statistiek.installId(c), i.tool, i.id), tekst, "Diagnose " + i.tool);
        if (r.isEmpty()) {
            i.verstuurd = nu;
            synchronized (LOCK) {
                SharedPreferences p = Statistiek.prefs(c);
                p.edit().putString("diagSent", erbij(verstuurd(p.getString("diagSent", "")), nu, i.tool, tekst.length()).toString()).apply();
            }
            App.log(c, "DIAGNOSE", "verstuurd");
        }
        return r;
    }
}
