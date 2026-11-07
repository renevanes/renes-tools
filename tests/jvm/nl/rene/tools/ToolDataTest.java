package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/** Toolgegevens: nooit geheimen, alleen tools zonder eigen onderdeel, en terugzetten voegt alleen toe wat ontbreekt. */
public class ToolDataTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    public static void main(String[] a) throws Exception {
        // Geheimen nooit
        check(!ToolData.keepPrefsFile("lock") && !ToolData.keepPrefsFile("vpn") && !ToolData.keepPrefsFile("secure") && !ToolData.keepPrefsFile("statistiek"), "slot, VPN en sleutels gaan niet mee");
        check(ToolData.secretKey("music", "token") && ToolData.secretKey("spotify", "access_token") && ToolData.secretKey("x", "apiKey"), "tokens en sleutels herkend");
        check(!ToolData.keepPref("spotify", "refresh"), "Spotify-koppeling niet mee");
        check(ToolData.keepPref("car", "park") && ToolData.keepPref("regio", "spots") && ToolData.keepPref("apparaten", "trackers"), "Mijn auto, regio en apparaten wel");
        check(!ToolData.keepPref("allbackup", "auto") && !ToolData.keepPref("radio", "favs"), "wat al in Instellingen staat, niet dubbel");
        check(!ToolData.keepPref("localai", "dl_x"), "lopende downloads niet mee");
        // Bestanden
        check(ToolData.keepFile("facturen/dossier.json") && ToolData.keepFile("apparaten/apparaten.json") && ToolData.keepFile("toiletten/eigen.json"), "facturen, apparaten en toiletten mee");
        check(!ToolData.keepFile("notes.json") && !ToolData.keepFile("transcripts/a.json") && !ToolData.keepFile("tracks/1.trk"), "eigen onderdelen niet dubbel");
        check(!ToolData.keepFile("ai/geheugen.json") && !ToolData.keepFile("sleep/n/a.wav") && !ToolData.keepFile("checkin/x.json"), "privé alleen via hun eigen vinkje");
        check(!ToolData.keepFile("bank/a") && !ToolData.keepFile("masterdata/a") && !ToolData.keepFile("kluis/a") && !ToolData.keepFile("gebruik/foto/a.jpg"), "bank, stamgegevens, kluis en foto's nooit");
        check(!ToolData.keepFile("../x") && !ToolData.keepFile("/etc/x") && !ToolData.keepFile("facturen/a.json.tmp"), "vreemde en tijdelijke paden niet");
        // Plan: alleen wat ontbreekt
        JSONObject prefs = new JSONObject()
                .put("car", new JSONObject().put("park", new JSONObject().put("t", "s").put("v", "{}")).put("name", new JSONObject().put("t", "s").put("v", "Golf")))
                .put("lock", new JSONObject().put("codeHash", new JSONObject().put("t", "s").put("v", "x")))
                .put("music", new JSONObject().put("token", new JSONObject().put("t", "s").put("v", "geheim")).put("used", new JSONObject().put("t", "i").put("v", "geen getal")));
        JSONArray files = new JSONArray()
                .put(new JSONObject().put("path", "facturen/dossier.json").put("text", "{}"))
                .put(new JSONObject().put("path", "apparaten/a.json").put("text", "[]"))
                .put(new JSONObject().put("path", "../buiten.txt").put("text", "x"))
                .put(new JSONObject().put("path", "notes.json").put("text", "[]"));
        JSONObject o = new JSONObject().put("format", ToolData.FORMAT).put("version", 1).put("prefs", prefs).put("files", files);
        Set<String> have = new HashSet<>(); have.add("car|name"); have.add("apparaten/a.json");
        ToolData.Plan p = ToolData.plan(o, (f, k) -> have.contains(f + "|" + k), rel -> have.contains(rel));
        check(p.fresh == 2 && p.total == 4, "nieuw 2 van 4: " + p.fresh + "/" + p.total);
        check(p.prefs.has("car") && !p.prefs.getJSONObject("car").has("name"), "bestaande instelling blijft");
        check(!p.prefs.has("lock") && !p.prefs.has("music"), "geheimen en verkeerde soorten uit een backup worden genegeerd");
        check(p.files.size() == 1 && "facturen/dossier.json".equals(p.files.get(0).optString("path")), "alleen het ontbrekende bestand, nooit buiten de app");
        check(p.tools.contains("Mijn auto") && p.tools.contains("Facturen"), "overzicht noemt de tools");
        try { ToolData.parse("{\"format\":\"iets anders\"}"); check(false, "verkeerd bestand geweigerd"); } catch (Exception e) { check(true, "verkeerd bestand geweigerd"); }
        try { ToolData.parse(new JSONObject().put("format", ToolData.FORMAT).put("version", 99).toString()); check(false, "nieuwere versie geweigerd"); } catch (Exception e) { check(true, "nieuwere versie geweigerd"); }

        System.out.println(fails == 0 ? "Alle ToolData-tests geslaagd" : fails + " ToolData-tests mislukt");
        System.exit(fails == 0 ? 0 : 1);
    }
}
