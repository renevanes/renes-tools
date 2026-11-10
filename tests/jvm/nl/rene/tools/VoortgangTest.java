package nl.rene.tools;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Voortgang bij het toevoegen van bestanden: hooguit één bericht per 200 ms, nieuwe stap en einde altijd, alleen aantallen. */
public class VoortgangTest {
    static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
    public static void main(String[] args) throws Exception {
        List<String> uit = new ArrayList<>(); long[] nu = {1000};
        Voortgang v = new Voortgang(uit::add, "ereader", 1, () -> nu[0]);
        byte[] data = new byte[1 << 20];
        try (InputStream in = v.lees(new ByteArrayInputStream(data), data.length)) {
            byte[] buf = new byte[4096]; int r;
            while ((r = in.read(buf)) > 0) nu[0] += 1; // 256 blokken in 256 ms
        }
        check(uit.size() >= 2 && uit.size() <= 4, "lezen beperkt tot een paar berichten: " + uit.size());
        JSONObject eerste = new JSONObject(uit.get(0)), laatste = new JSONObject(uit.get(uit.size() - 1));
        check("lezen".equals(eerste.getString("stap")) && eerste.getLong("totaal") == data.length && eerste.getLong("gedaan") == 4096, "eerste blok meteen: " + eerste);
        check(laatste.getLong("gedaan") == data.length, "het einde van het lezen altijd: " + laatste);
        int voor = uit.size(); v.stap("verwerken", 0, 0); check(uit.size() == voor + 1, "nieuwe stap meteen, ook binnen 200 ms");
        for (int i = 0; i <= 10; i++) v.stap("opslaan", i, 10);
        check(uit.size() == voor + 3, "opslaan: eerste en laatste, tussendoor niets binnen 200 ms: " + (uit.size() - voor));
        nu[0] += 250; v.stap("opslaan", 5, 10); check(uit.size() == voor + 4, "na 200 ms wel weer");
        v.close(); v.close(); v.stap("opslaan", 6, 10);
        JSONObject eind = new JSONObject(uit.get(uit.size() - 1));
        check(eind.optBoolean("klaar") && "ereader".equals(eind.getString("soort")) && uit.size() == voor + 5, "precies één keer klaar, daarna niets meer");
        for (String s : uit) { JSONObject o = new JSONObject(s); for (java.util.Iterator<String> k = o.keys(); k.hasNext();) { String n = k.next(); check(n.matches("soort|stap|gedaan|totaal|bestand|bestanden|klaar"), "alleen stappen en aantallen: " + n); } }
        // Meer bestanden: welk bestand (vanaf 1), en een nieuw bestand begint de stap opnieuw.
        uit.clear(); Voortgang m = new Voortgang(uit::add, "bank", 3, () -> nu[0]);
        m.stap("lezen", 1, 10); m.bestand(1); m.stap("lezen", 1, 10); m.bestand(7); m.stap("lezen", 2, 10);
        check(uit.size() == 3 && new JSONObject(uit.get(1)).getInt("bestand") == 2 && new JSONObject(uit.get(2)).getInt("bestand") == 3 && new JSONObject(uit.get(2)).getInt("bestanden") == 3, "bestand 2 en 3 van 3: " + uit);
        // Zonder balk: niets, en de stroom blijft dezelfde.
        InputStream ruw = new ByteArrayInputStream(data);
        check(Voortgang.GEEN.lees(ruw, 10) == ruw, "GEEN wikkelt niets in"); Voortgang.GEEN.stap("lezen", 1, 2); Voortgang.GEEN.close();
        // Onbekende grootte: het aantal gelezen bytes, totaal 0; skip telt mee.
        uit.clear(); Voortgang s = new Voortgang(uit::add, "kluis", 1, () -> nu[0]);
        try (InputStream in = s.lees(new ByteArrayInputStream(data), 0)) { check(in.skip(100) == 100, "skip"); check(in.read() >= 0, "read"); }
        JSONObject o = new JSONObject(uit.get(0)); check(o.getLong("totaal") == 0 && o.getLong("gedaan") == 100, "onbekende grootte: " + o);
        // Android meldt een te kleine grootte: het einde gaat één keer mee, daarna blijft het begrensd.
        uit.clear(); Voortgang k = new Voortgang(uit::add, "ereader", 1, () -> nu[0]);
        try (InputStream in = k.lees(new ByteArrayInputStream(data), 4096)) { byte[] buf = new byte[1024]; while (in.read(buf) > 0) { } }
        check(uit.size() <= 3, "te kleine grootte: niet bij elk blok een bericht (" + uit.size() + ")");
        // Na de bestanden: alles samen (bestand 0 = geen "bestand n van m").
        uit.clear(); Voortgang a = new Voortgang(uit::add, "bank", 3, () -> nu[0]); a.bestand(2); a.alleBestanden(); a.stap("verwerken", 0, 0);
        check(new JSONObject(uit.get(0)).getInt("bestand") == 0, "alle bestanden samen: " + uit);
        // Herkennen met een langste duur: de balk loopt met de tijd mee tot hooguit het totaal, en stopt bij de volgende stap.
        List<String> tik = java.util.Collections.synchronizedList(new ArrayList<>()); long[] klok = {5000};
        Voortgang h = new Voortgang(tik::add, "kaartfoto", 1, () -> klok[0]);
        h.tijd("herkennen", 2000);
        check(tik.size() == 1 && new JSONObject(tik.get(0)).getLong("gedaan") == 0 && new JSONObject(tik.get(0)).getLong("totaal") == 2000, "herkennen begint meteen: " + tik);
        klok[0] += 1500; Thread.sleep(Voortgang.TIK_MS * 2 + 200);
        JSONObject mee = new JSONObject(tik.get(tik.size() - 1));
        check(tik.size() >= 2 && "herkennen".equals(mee.getString("stap")) && mee.getLong("gedaan") == 1500, "loopt met de tijd mee: " + tik);
        klok[0] += 5000; Thread.sleep(Voortgang.TIK_MS + 200);
        check(new JSONObject(tik.get(tik.size() - 1)).getLong("gedaan") == 2000, "niet verder dan het totaal");
        h.stap("opslaan", 0, 0); int na = tik.size(); klok[0] += 1000; Thread.sleep(Voortgang.TIK_MS + 200);
        check(tik.size() == na && "opslaan".equals(new JSONObject(tik.get(na - 1)).getString("stap")), "volgende stap stopt het meelopen: " + tik);
        h.close(); Voortgang.GEEN.tijd("herkennen", 1000);
        System.out.println("Voortgang: begrensd, stappen, bestanden, einde, meelopen met de tijd en alleen aantallen gecontroleerd");
    }
}
