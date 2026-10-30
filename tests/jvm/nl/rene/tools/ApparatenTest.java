package nl.rene.tools;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/** Apparaten: advertenties ontleden, trackers, soort, afstand, wifi-kanalen, SSDP/mDNS/NetBIOS, subnet, meereizen, geschiedenis. */
public class ApparatenTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    /** Eén AD-structuur: lengte, type, gegevens. */
    static byte[] ad(int type, int... data) {
        byte[] b = new byte[data.length + 2];
        b[0] = (byte) (data.length + 1); b[1] = (byte) type;
        for (int i = 0; i < data.length; i++) b[i + 2] = (byte) data[i];
        return b;
    }
    static byte[] adv(byte[]... parts) { ByteArrayOutputStream o = new ByteArrayOutputStream(); for (byte[] p : parts) o.write(p, 0, p.length); return o.toByteArray(); }
    static byte[] name(String n) { byte[] s = n.getBytes(StandardCharsets.UTF_8); int[] d = new int[s.length]; for (int i = 0; i < s.length; i++) d[i] = s[i] & 0xFF; return ad(0x09, d); }
    static int[] cat(int[] a, int... b) { int[] r = Arrays.copyOf(a, a.length + b.length); System.arraycopy(b, 0, r, a.length, b.length); return r; }
    static int[] zeros(int n) { return new int[n]; }

    static final TimeZone AMS = TimeZone.getTimeZone("Europe/Amsterdam");

    public static void main(String[] args) {
        // ---------- ontleden ----------
        Apparaten.Adv a = Apparaten.parse(adv(ad(0x01, 0x06), name("JBL Flip 5"), ad(0x0A, 0xF4), ad(0x19, 0x41, 0x08), ad(0x03, 0x0D, 0x18, 0x0F, 0x18), ad(0xFF, 0x57, 0x00, 0x01, 0x02)));
        check("JBL Flip 5".equals(a.name), "naam uit de advertentie");
        check(a.flags == 6 && a.txLevel == -12, "vlaggen en zendvermogen (-12 dBm)");
        check(a.appearance == 0x0841 && "speaker".equals(Apparaten.typeFromAppearance(a.appearance)), "appearance 0x0841 = speaker");
        check(a.uuid16.equals(Arrays.asList(0x180D, 0x180F)), "16-bits services");
        check(a.company() == 0x0057 && "Harman (JBL)".equals(Apparaten.company(a.company())), "maker uit de company identifier (0x0057)");
        check(Apparaten.parse(new byte[]{0x05, 0x09, 'a'}).name.isEmpty() && Apparaten.parse(null).name.isEmpty(), "afgekapte of lege advertentie: geen fout");
        check(Apparaten.parse(adv(ad(0x09, 'O', 'K', 0x07, 0x00))).name.equals("OK"), "stuurtekens uit de naam");
        check("Apple".equals(Apparaten.company(0x004C)) && "Samsung".equals(Apparaten.company(0x0075)) && "Google".equals(Apparaten.company(0x00E0)) && "".equals(Apparaten.company(0x7777)), "makers: Apple, Samsung, Google, onbekend leeg");

        // ---------- soort ----------
        check("oortjes".equals(Apparaten.typeFromAppearance(0x0941)) && "koptelefoon".equals(Apparaten.typeFromAppearance(0x0943)), "appearance: oordopje en koptelefoon");
        check("horloge".equals(Apparaten.typeFromAppearance(0x00C1)) && "telefoon".equals(Apparaten.typeFromAppearance(0x0040)) && "auto".equals(Apparaten.typeFromAppearance(0x08C1)), "appearance: horloge, telefoon, auto");
        check("invoer".equals(Apparaten.typeFromAppearance(0x03C1)) && "hoortoestel".equals(Apparaten.typeFromAppearance(0x0A41)) && "".equals(Apparaten.typeFromAppearance(-1)), "appearance: toetsenbord, hoortoestel, geen");
        check("koptelefoon".equals(Apparaten.typeFromClass(0x0418)) && "auto".equals(Apparaten.typeFromClass(0x0420)) && "telefoon".equals(Apparaten.typeFromClass(0x020C)) && "tv".equals(Apparaten.typeFromClass(0x043C)), "Bluetooth-class: koptelefoon, carkit, telefoon, tv");
        check("oortjes".equals(Apparaten.typeFromName("Galaxy Buds2 Pro")) && "horloge".equals(Apparaten.typeFromName("Forerunner 255")) && "tv".equals(Apparaten.typeFromName("[TV] Samsung Q80")) && "".equals(Apparaten.typeFromName("XYZ-123")), "soort uit de naam als hulp");
        Apparaten.Info airpods = Apparaten.classify(Apparaten.parse(adv(ad(0xFF, cat(new int[]{0x4C, 0x00, 0x07, 0x19}, zeros(25))))), "", -1);
        check("oortjes".equals(airpods.type) && "Apple".equals(airpods.maker) && airpods.tracker == null, "Apple 0x07 (nabij koppelen) = oortjes, geen tracker");
        Apparaten.Info hr = Apparaten.classify(Apparaten.parse(adv(ad(0x03, 0x0D, 0x18))), "", -1);
        check("sport".equals(hr.type), "hartslagservice = sport");

        // ---------- trackers ----------
        int[] findMy = cat(new int[]{0x4C, 0x00, 0x12, 0x19, 0x10}, zeros(24)); // status 0x10: AirTag
        Apparaten.Tracker t = Apparaten.tracker(Apparaten.parse(adv(ad(0xFF, findMy))));
        check(t != null && "airtag".equals(t.kind) && t.warn && Boolean.TRUE.equals(t.separated), "AirTag uit de buurt van de eigenaar herkend (0x12, lengte 0x19, type 1)");
        Apparaten.Info at = Apparaten.classify(Apparaten.parse(adv(ad(0xFF, findMy))), "", -1);
        check("tracker".equals(at.type) && "Apple".equals(at.maker), "AirTag: soort tracker, maker Apple");
        Apparaten.Tracker near = Apparaten.tracker(Apparaten.parse(adv(ad(0xFF, 0x4C, 0x00, 0x12, 0x02, 0x00, 0x00))));
        check(near != null && !near.warn && Boolean.FALSE.equals(near.separated), "Find My met de eigenaar in de buurt (korte vorm): geen waarschuwing");
        Apparaten.Tracker acc = Apparaten.tracker(Apparaten.parse(adv(ad(0xFF, cat(new int[]{0x4C, 0x00, 0x12, 0x19, 0x20}, zeros(24))))));
        check(acc != null && "findmy".equals(acc.kind) && acc.warn, "Find My-accessoire (type 2) telt mee");
        Apparaten.Tracker pods = Apparaten.tracker(Apparaten.parse(adv(ad(0xFF, cat(new int[]{0x4C, 0x00, 0x12, 0x19, 0x30}, zeros(24))))));
        check(pods != null && !pods.warn, "AirPods kwijt bij de eigenaar (type 3): wel herkend, geen waarschuwing");
        Apparaten.Tracker tag = Apparaten.tracker(Apparaten.parse(adv(ad(0x16, cat(new int[]{0x5A, 0xFD}, zeros(18))))));
        check(tag != null && "smarttag".equals(tag.kind) && tag.warn, "Samsung SmartTag (service data 0xFD5A)");
        Apparaten.Tracker tile = Apparaten.tracker(Apparaten.parse(adv(ad(0x03, 0xED, 0xFE))));
        check(tile != null && "tile".equals(tile.kind) && tile.warn, "Tile (service 0xFEED)");
        check("Tile".equals(Apparaten.classify(Apparaten.parse(adv(ad(0x03, 0xED, 0xFE))), "", -1).maker), "Tile: maker uit de service");
        Apparaten.Tracker chip = Apparaten.tracker(Apparaten.parse(adv(ad(0x03, 0x33, 0xFE))));
        check(chip != null && "chipolo".equals(chip.kind), "Chipolo (service 0xFE33)");
        Apparaten.Tracker g1 = Apparaten.tracker(Apparaten.parse(adv(ad(0x16, cat(new int[]{0xAA, 0xFE, 0x41}, zeros(20))))));
        Apparaten.Tracker g0 = Apparaten.tracker(Apparaten.parse(adv(ad(0x16, cat(new int[]{0xAA, 0xFE, 0x40}, zeros(20))))));
        check(g1 != null && g1.warn && g0 != null && !g0.warn, "Google Zoek mijn apparaat: alleen in beschermingsmodus (0x41) waarschuwen");
        check(Apparaten.tracker(Apparaten.parse(adv(ad(0x16, 0xAA, 0xFE, 0x10, 0x00)))) == null, "gewoon Eddystone-baken: geen tracker");
        check(Apparaten.tracker(a) == null, "speaker: geen tracker");

        // ---------- adressen en sleutels ----------
        check(Apparaten.rotating("4A:11:22:33:44:55") && !Apparaten.rotating("C4:11:22:33:44:55") && !Apparaten.rotating("00:1A:7D:DA:71:13"), "wisselend adres (bits 01) herkend");
        check(Apparaten.key("4A:11:22:33:44:55", false, "", 0x004C, "apple").equals("g:76|apple"), "wisselend zonder naam: groep");
        check(Apparaten.key("4A:11:22:33:44:55", false, "Rene's Watch", 0x0075, "horloge").equals("n:rene's watch|117"), "wisselend met naam: op naam");
        check(Apparaten.key("4a:11:22:33:44:55", true, "Buds", 0x0075, "oortjes").equals("a:4A:11:22:33:44:55"), "gekoppeld: op adres");
        check(Apparaten.stableKey("a:X") && Apparaten.stableKey("n:x|1") && Apparaten.stableKey("l:u:abc") && !Apparaten.stableKey("g:76|apple"), "groepen niet terug te herkennen");

        // ---------- afstand ----------
        check(Math.abs(Apparaten.distance(-59, Integer.MIN_VALUE, 2) - 1.0) < 0.01, "op -59 dBm zonder zendvermogen: ~1 m");
        check(Math.abs(Apparaten.distance(-79, Integer.MIN_VALUE, 2) - 10.0) < 0.1, "20 dB zwakker: ~10 m (n = 2)");
        check(Math.abs(Apparaten.distance(-53, -12, 2) - 1.0) < 0.01, "met zendvermogen -12 dBm (0 m): op 1 m -53 dBm");
        check(Apparaten.distance(-120, Integer.MIN_VALUE, 2) == 99 && Apparaten.distance(-20, Integer.MIN_VALUE, 2) == 0.1 && Apparaten.distance(0, -59, 2) == -1, "begrensd tussen 0,1 en 99 m; ongeldig = -1");
        check(Apparaten.trend(-60, -65) == 1 && Apparaten.trend(-66, -61) == -1 && Apparaten.trend(-60, -61) == 0 && Apparaten.trend(-60, Double.NaN) == 0, "warmer, kouder, gelijk");
        check(Apparaten.smooth(Double.NaN, -70, 0.3) == -70 && Math.abs(Apparaten.smooth(-70, -60, 0.3) + 67) < 1e-9, "afvlakken");
        check(Apparaten.meter(-100) == 0 && Apparaten.meter(-35) == 100 && Apparaten.meter(-67.5) == 50, "meter 0..100");

        // ---------- wifi ----------
        check(Apparaten.channel(2412) == 1 && Apparaten.channel(2437) == 6 && Apparaten.channel(2462) == 11 && Apparaten.channel(2484) == 14, "kanalen 2,4 GHz");
        check(Apparaten.channel(5180) == 36 && Apparaten.channel(5500) == 100 && Apparaten.channel(5825) == 165, "kanalen 5 GHz");
        check(Apparaten.channel(5955) == 1 && Apparaten.channel(6115) == 33 && Apparaten.channel(1234) == 0, "kanalen 6 GHz, onbekend 0");
        check("2,4 GHz".equals(Apparaten.band(2437)) && "5 GHz".equals(Apparaten.band(5180)) && "6 GHz".equals(Apparaten.band(5955)), "banden");
        check(Apparaten.width(2) == 80 && Apparaten.width(0) == 20 && Apparaten.width(5) == 320, "kanaalbreedte");
        check("wpa3".equals(Apparaten.security("[WPA2-PSK-CCMP][RSN-SAE-CCMP][ESS]")) && "wpa2".equals(Apparaten.security("[WPA2-PSK-CCMP][ESS]")) && "open".equals(Apparaten.security("[ESS]"))
                && "wep".equals(Apparaten.security("[WEP][ESS]")) && "zakelijk".equals(Apparaten.security("[WPA2-EAP-CCMP][ESS]")) && "owe".equals(Apparaten.security("[RSN-OWE-CCMP][ESS]")), "beveiliging uit capabilities");
        check(Apparaten.unsafe("open") && Apparaten.unsafe("wep") && !Apparaten.unsafe("owe") && !Apparaten.unsafe("wpa2"), "open en WEP onveilig");
        Map<Integer, Double> cr = Apparaten.crowding(new int[]{2412, 2412, 2437, 5180}, new int[]{-40, -50, -90, -50}, "2,4 GHz");
        check(cr.size() == 13 && cr.get(1) > cr.get(6) && cr.get(11) < cr.get(6) && Apparaten.quietest24(cr) == 11, "drukte 2,4 GHz met overlap: kanaal 11 het rustigst");
        check(cr.get(3) > 0 && cr.get(3) < cr.get(1), "overlap: kanaal 3 krijgt een deel van kanaal 1");
        Map<Integer, Double> c5 = Apparaten.crowding(new int[]{5180, 5180, 5200}, new int[]{-40, -40, -100}, "5 GHz");
        check(c5.get(36) == 2.0 && c5.get(40) == 0.05 && !c5.containsKey(44), "drukte 5 GHz: alleen hetzelfde kanaal");
        check("Ubiquiti".equals(Apparaten.ouiMaker("24:a4:3c:11:22:33")) && "".equals(Apparaten.ouiMaker("26:a4:3c:11:22:33")) && "".equals(Apparaten.ouiMaker("12:34:56:00:00:00")), "maker uit het adres; zelfgekozen adres geen maker");

        // ---------- subnet ----------
        int me = Apparaten.ipInt("192.168.1.37");
        int[] h = Apparaten.hosts(me, 24);
        check(h.length == 253 && Apparaten.ipStr(h[0]).equals("192.168.1.1") && Apparaten.ipStr(h[h.length - 1]).equals("192.168.1.254"), "/24: 1 t/m 254 zonder eigen adres");
        check(Arrays.stream(h).noneMatch(x -> x == me), "eigen adres niet");
        int[] big = Apparaten.hosts(Apparaten.ipInt("10.0.5.20"), 16);
        boolean inside = true; for (int x : big) if (!Apparaten.ipStr(x).startsWith("10.0.5.")) inside = false;
        check(big.length == 253 && inside, "groter netwerk (/16): toch alleen het eigen /24");
        check(Apparaten.hosts(Apparaten.ipInt("192.168.1.37"), 28).length == 13, "kleiner netwerk (/28): alleen dat");
        check(Apparaten.hosts(Apparaten.ipInt("8.8.4.4"), 24).length == 0 && Apparaten.hosts(Apparaten.ipInt("100.64.1.2"), 24).length == 0, "geen privé-adres: niets");
        check(Apparaten.sameNet(me, Apparaten.ipInt("192.168.1.200"), 16) && !Apparaten.sameNet(me, Apparaten.ipInt("192.168.2.1"), 16), "binnen het eigen /24, ook als het netwerk groter is");
        check(Apparaten.ipInt("300.1.1.1") == 0 && Apparaten.ipInt("a.b.c.d") == 0 && Apparaten.ipInt(null) == 0, "ongeldige adressen");

        // ---------- SSDP / UPnP / mDNS / NetBIOS ----------
        Map<String, String> hd = Apparaten.ssdpHeaders("HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: http://192.168.1.20:49152/desc.xml\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\nUSN: uuid:ABC-123::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n");
        check("http://192.168.1.20:49152/desc.xml".equals(hd.get("location")) && hd.get("st").contains("MediaRenderer") && hd.get("usn").startsWith("uuid:ABC"), "SSDP-kopregels");
        check("192.168.1.20".equals(Apparaten.urlHost(hd.get("location"))) && "".equals(Apparaten.urlHost("https://example.com/x")) && "".equals(Apparaten.urlHost("http://tv.local/x")), "alleen een http-adres met een IP-adres");
        String xml = "<?xml version=\"1.0\"?><root><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>Woonkamer TV &amp; zo</friendlyName><manufacturer>Samsung Electronics</manufacturer><modelName>QE55Q80</modelName><UDN>uuid:1234-abcd</UDN></device></root>";
        check("Woonkamer TV & zo".equals(Apparaten.xmlTag(xml, "friendlyName")) && "Samsung Electronics".equals(Apparaten.xmlTag(xml, "manufacturer")) && "uuid:1234-abcd".equals(Apparaten.xmlTag(xml, "UDN")) && "".equals(Apparaten.xmlTag(xml, "serialNumber")), "UPnP-beschrijving lezen");
        Map<String, String> txt = new HashMap<>(); txt.put("fn", "Keuken"); txt.put("md", "Google Nest Mini");
        check("Keuken".equals(Apparaten.txtName(txt)) && "Google Nest Mini".equals(Apparaten.txtModel(txt)), "mDNS TXT: naam en model");
        Set<String> svc = new HashSet<>(Arrays.asList("_googlecast._tcp"));
        check("speaker".equals(Apparaten.lanKind(svc, new HashSet<>(), "", false, "Google Nest Mini")) && "tv".equals(Apparaten.lanKind(svc, new HashSet<>(), "", false, "Chromecast")), "Chromecast: tv, Nest Mini: speaker");
        check("printer".equals(Apparaten.lanKind(new HashSet<>(Arrays.asList("_ipp._tcp")), new HashSet<>(), "", false, "")) && "router".equals(Apparaten.lanKind(new HashSet<>(), new HashSet<>(), "", true, ""))
                && "nas".equals(Apparaten.lanKind(new HashSet<>(), new HashSet<>(Arrays.asList(5000, 445)), "", false, "")) && "apparaat".equals(Apparaten.lanKind(new HashSet<>(), new HashSet<>(), "", false, "")), "soort op het netwerk: printer, router, NAS, onbekend");
        check("Chromecast".equals(Apparaten.serviceLabel("_googlecast._tcp")) && "HomeKit".equals(Apparaten.serviceLabel("_hap._tcp.")) && "Printer".equals(Apparaten.portLabel(9100)), "namen van diensten en poorten");
        byte[] q = Apparaten.nbstatQuery(0x1234);
        check(q.length == 50 && q[0] == 0x12 && q[1] == 0x34 && q[13] == 'C' && q[14] == 'K' && q[47] == 0x21, "NetBIOS-vraag");
        byte[] r = new byte[57 + 18 * 2 + 6];
        System.arraycopy(q, 0, r, 0, 12); r[2] = (byte) 0x84; r[7] = 1;
        r[12] = 0x20; for (int i = 13; i < 45; i++) r[i] = 'A'; r[45] = 0; r[47] = 0x21; r[49] = 1; // naam, type, klasse
        int i0 = 46 + 2 + 2 + 4 + 2; r[i0] = 2; // twee namen
        byte[] grp = "WERKGROEP      ".getBytes(StandardCharsets.US_ASCII), nm = "NAS-ZOLDER     ".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(grp, 0, r, i0 + 1, 15); r[i0 + 16] = 0x00; r[i0 + 17] = (byte) 0x80;
        System.arraycopy(nm, 0, r, i0 + 19, 15); r[i0 + 34] = 0x20; r[i0 + 35] = 0x00;
        check("NAS-ZOLDER".equals(Apparaten.nbstatName(r, r.length)), "NetBIOS-antwoord: de computernaam (groep overgeslagen)");
        check("".equals(Apparaten.nbstatName(new byte[10], 10)) && "".equals(Apparaten.nbstatName(null, 0)), "kapot NetBIOS-antwoord: geen naam");

        // ---------- meereizen ----------
        long t0 = 1_800_000_000_000L;
        List<Apparaten.Sighting> s = new ArrayList<>();
        s.add(new Apparaten.Sighting(t0, 51.9225, 4.4792));                  // Rotterdam centrum
        s.add(new Apparaten.Sighting(t0 + 10 * 60_000, 51.9230, 4.4800));    // vlakbij: zelfde plek
        s.add(new Apparaten.Sighting(t0 + 20 * 60_000, 51.9300, 4.4900));    // ~1 km verder
        Apparaten.Follow f = Apparaten.follows(s, t0 + 20 * 60_000);
        check(f.places == 2 && !f.follows, "twee plekken: nog niet");
        s.add(new Apparaten.Sighting(t0 + 35 * 60_000, 51.9400, 4.5000));    // nog ~1,3 km verder
        f = Apparaten.follows(s, t0 + 35 * 60_000);
        check(f.places == 3 && f.minutes == 35 && f.follows, "drie plekken ≥ 500 m uit elkaar over 35 minuten: reist mee");
        List<Apparaten.Sighting> quick = new ArrayList<>();
        quick.add(new Apparaten.Sighting(t0, 51.90, 4.40)); quick.add(new Apparaten.Sighting(t0 + 5 * 60_000, 51.92, 4.42)); quick.add(new Apparaten.Sighting(t0 + 12 * 60_000, 51.94, 4.44));
        check(!Apparaten.follows(quick, t0 + 12 * 60_000).follows, "drie plekken in 12 minuten (bijv. een trein die langsrijdt): nog niet");
        List<Apparaten.Sighting> home = new ArrayList<>();
        for (int k = 0; k < 12; k++) home.add(new Apparaten.Sighting(t0 + k * 15 * 60_000L, 51.9225 + (k % 2) * 0.001, 4.4792));
        check(!Apparaten.follows(home, t0 + 3 * 3600_000L).follows, "uren op dezelfde plek (thuis, kantoor): reist niet mee");
        List<Apparaten.Sighting> noLoc = new ArrayList<>();
        for (int k = 0; k < 5; k++) noLoc.add(new Apparaten.Sighting(t0 + k * 20 * 60_000L, Double.NaN, Double.NaN));
        check(!Apparaten.follows(noLoc, t0 + 2 * 3600_000L).follows, "zonder plek: geen waarschuwing");
        check(!Apparaten.follows(s, t0 + 10 * 3600_000L).follows, "ouder dan 8 uur: telt niet meer");
        check(Math.abs(Apparaten.meters(51.9225, 4.4792, 51.9225, 4.4792)) < 1e-6 && Math.abs(Apparaten.meters(52.0, 4.0, 52.01, 4.0) - 1112) < 5, "afstanden");
        check(Apparaten.round3(51.92254) == 51.923 && "51.92,4.48".equals(Apparaten.cell(51.9225, 4.4792)) && "".equals(Apparaten.cell(Double.NaN, 4)), "grove plekken (~100 m en ~1 km)");
        check(Apparaten.atHome(51.9226, 4.4793, 51.9225, 4.4792) && !Apparaten.atHome(51.93, 4.48, 51.9225, 4.4792), "thuis: binnen 300 m");

        // ---------- geschiedenis ----------
        long day = 86_400_000L, now = t0 + 40 * day;
        Map<String, Apparaten.Hist> all = new HashMap<>();
        Apparaten.Hist buds = new Apparaten.Hist(); buds.key = "a:BUDS"; all.put(buds.key, buds);
        Apparaten.record(buds, now - 2 * day, "thuis", AMS);
        Apparaten.record(buds, now - 2 * day + 3600_000, "thuis", AMS);
        Apparaten.record(buds, now - day, "51.92,4.48", AMS);
        Apparaten.record(buds, now, "thuis", AMS);
        check(buds.homeDays.size() == 2 && buds.awayDays.size() == 1 && buds.days() == 3, "dagen thuis en elders, één keer per dag");
        check(buds.last == now && "thuis".equals(buds.lastPlace) && buds.first == now - 2 * day, "eerste en laatste keer, laatste plek");
        Apparaten.Hist old = new Apparaten.Hist(); old.key = "n:oud|1"; all.put(old.key, old);
        Apparaten.record(old, now - 35 * day, "51.90,4.40", AMS);
        Apparaten.Hist mixed = new Apparaten.Hist(); mixed.key = "n:buur|1"; all.put(mixed.key, mixed);
        Apparaten.record(mixed, now - 31 * day, "thuis", AMS);
        Apparaten.record(mixed, now - 29 * day, "thuis", AMS);
        Apparaten.record(mixed, now - 3 * day, "thuis", AMS);
        Apparaten.prune(all, now, 30, AMS);
        check(!all.containsKey("n:oud|1"), "na de bewaartermijn (30 dagen) weg");
        check(all.containsKey("n:buur|1") && mixed.homeDays.size() == 2 && mixed.first >= now - 30 * day, "oude dagen binnen een apparaat weg");
        List<Apparaten.Hist> oftenHome = Apparaten.often(all.values(), false, 2), oftenAway = Apparaten.often(all.values(), true, 2);
        check(oftenHome.size() == 2 && oftenAway.isEmpty(), "vaak thuis: twee apparaten, vaak elders: geen");
        Apparaten.prune(all, now, 7, AMS);
        check(all.size() == 2 && all.containsKey("a:BUDS") && mixed.homeDays.size() == 1, "kortere bewaartermijn (7 dagen): alleen recente dagen");
        Map<String, Apparaten.Hist> many = new HashMap<>();
        for (int k = 0; k < Apparaten.HIST_MAX + 20; k++) { Apparaten.Hist x = new Apparaten.Hist(); x.key = "n:" + k + "|1"; Apparaten.record(x, now - k * 1000L, "", AMS); many.put(x.key, x); }
        Apparaten.prune(many, now, 30, AMS);
        check(many.size() == Apparaten.HIST_MAX && many.containsKey("n:0|1") && !many.containsKey("n:" + (Apparaten.HIST_MAX + 19) + "|1"), "hooguit " + Apparaten.HIST_MAX + " apparaten: de oudste vallen eraf");

        System.out.println(fails == 0 ? "Alles goed" : fails + " mislukt");
        if (fails > 0) System.exit(1);
    }
}
