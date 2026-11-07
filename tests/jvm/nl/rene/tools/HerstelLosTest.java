package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;

/** Alles terugzetten uit de backup-map: de juiste bestanden kiezen, en podcastlijsten uit een backup veilig opschonen. */
public class HerstelLosTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    public static void main(String[] a) throws Exception {
        // Contacten: de nieuwste dagbackup, geen oude versies of andere bestanden
        List<String> c = Arrays.asList("contacten-2026-10-01.vcf", "contacten-versie-12.vcf", "wijzigingslog.txt", "contacten-2026-11-06.vcf", "contacten-2026-11-06~2026-11-07-2.vcf");
        check("contacten-2026-11-06.vcf".equals(HerstelLos.newestContacts(c)), "nieuwste contactenbackup gekozen");
        check(HerstelLos.newestContacts(Arrays.asList("wijzigingslog.txt", "contacten-versie-3.vcf")) == null, "alleen versies: niets");

        // Routes: alleen .gpx, gesorteerd, verborgen bestanden niet
        List<String> g = HerstelLos.gpxNames(Arrays.asList("b.gpx", "a.GPX", ".tmp.gpx", "notes.txt", "c.gpx.part"));
        check(g.equals(Arrays.asList("a.GPX", "b.gpx")), "alleen echte GPX-bestanden: " + g);

        // Onderdelen: instellingen (met radio en podcasts) gaan als eerste terug
        check("settings".equals(HerstelLos.PARTS[0][0]), "instellingen eerst");
        check(SettingsBackup.FILE.equals(HerstelLos.PARTS[0][2]) && SettingsBackup.DIR.equals(HerstelLos.PARTS[0][1]), "instellingen op hun vaste plek");

        // Een aflevering uit een backup: alleen bekende velden van het juiste soort
        String key = "e0123456789abcdef";
        JSONObject ep = new JSONObject().put("key", key).put("url", "https://voorbeeld.nl/a.mp3").put("title", "Aflevering 1")
                .put("date", 1700000000000L).put("dur", 3600).put("image", "javascript:alert(1)").put("onclick", "x()");
        JSONObject cl = Podcasts.cleanEp(ep);
        check(cl != null && key.equals(cl.optString("key")) && "Aflevering 1".equals(cl.optString("title")), "aflevering blijft herkenbaar");
        check(cl != null && !cl.has("onclick"), "onbekend veld valt weg");
        check(cl != null && cl.optString("image").isEmpty(), "plaatje zonder http(s) valt weg");
        check(cl != null && cl.optLong("date") == 1700000000000L && cl.optLong("dur") == 3600, "getallen blijven getallen");
        check(Podcasts.cleanEp(new JSONObject().put("key", "verkeerd").put("url", "https://x.nl/a.mp3")) == null, "ongeldige sleutel: weg");
        check(Podcasts.cleanEp(new JSONObject().put("key", key).put("url", "file:///sdcard/a.mp3")) == null, "geen http(s): weg");

        JSONObject pod = Podcasts.cleanPod(new JSONObject().put("id", "p0123456789abcdef").put("feed", "https://x.nl/feed").put("title", "Pod").put("link", "javascript:x"));
        check("Pod".equals(pod.optString("title")) && !pod.has("link") && "p0123456789abcdef".equals(pod.optString("id")), "podcast opgeschoond");
        check(!Podcasts.cleanPod(new JSONObject().put("id", "<b>")).has("id"), "vreemde podcast-id valt weg");

        // Lijst: dubbele afleveringen één keer, ongeldige weg, hooguit max
        JSONArray in = new JSONArray()
                .put(new JSONObject().put("ep", ep).put("pod", pod).put("t", 5L))
                .put(new JSONObject().put("ep", ep).put("pod", pod))
                .put(new JSONObject().put("ep", new JSONObject().put("key", "x").put("url", "https://y.nl")))
                .put("geen object");
        for (int i = 0; i < 5; i++) in.put(new JSONObject().put("ep", new JSONObject().put("key", String.format("e%016x", i + 1)).put("url", "https://x.nl/" + i + ".mp3")));
        JSONArray out = Podcasts.cleanList(in, 4);
        check(out.length() == 4, "hooguit max: " + out.length());
        check(out.getJSONObject(0).optLong("t") == 5L, "tijdstip blijft");
        check(!out.getJSONObject(1).getJSONObject("ep").optString("key").equals(key), "dubbele aflevering één keer");
        check(Podcasts.cleanList(null, 10).length() == 0, "geen lijst: leeg");

        System.out.println(fails == 0 ? "Alle HerstelLos-tests geslaagd" : fails + " HerstelLos-tests mislukt");
        System.exit(fails == 0 ? 0 : 1);
    }
}
