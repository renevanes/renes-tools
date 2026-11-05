package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

/** De hele lijst ophalen, inclusief trage wijzigingen, gelijke paginagroottes en teruggaan uit een factuur. */
public class FacturenLijstTest {
    static int fails;
    static void check(boolean ok, String wat) { System.out.println((ok ? "✓ " : "✗ ") + wat); if (!ok) fails++; }
    static JSONObject lijst(int van, int tot, boolean meer, boolean volgende) throws Exception {
        JSONArray l = new JSONArray();
        for (int i = van; i <= tot; i++) l.put(new JSONObject().put("i", i - van).put("sleutel", "factuur-" + i));
        return new JSONObject().put("knoppen", l).put("kenmerk", van + "-" + tot).put("meer", meer).put("volgende", volgende);
    }
    static FacturenLijst.Stap stabiel(FacturenLijst l, JSONObject s, long nu) {
        l.bekijk(s, nu); return l.bekijk(s, nu + FacturenLijst.STABIEL);
    }
    static void download(FacturenLijst l) { while (l.index < l.knoppen.length()) { if (!l.beginKnop()) break; l.index++; } }

    public static void main(String[] args) throws Exception {
        JSONObject a = lijst(1, 5, true, false), b = lijst(1, 10, true, false), c = lijst(1, 12, false, false);
        FacturenLijst l = new FacturenLijst(50, 0);
        check(stabiel(l, a, 0) == FacturenLijst.Stap.DOWNLOAD, "eerste lijst eerst downloaden");
        download(l); l.paginaKlaar(2000);
        check(stabiel(l, a, 2000) == FacturenLijst.Stap.MEER && l.aantal == 5, "pas daarna Toon meer");
        l.geklikt(FacturenLijst.Stap.MEER, l.kenmerk, 4000); l.volgendeLijst();
        check(l.bekijk(a, 6500) == FacturenLijst.Stap.WACHT && !l.volledig, "na 2,5 seconde zonder groei blijven wachten");
        check(stabiel(l, b, 9000) == FacturenLijst.Stap.DOWNLOAD && l.index == 5, "pas na vijf seconden laden verder; eerste vijf niet dubbel");
        download(l); l.paginaKlaar(11000); stabiel(l, b, 11000);
        l.geklikt(FacturenLijst.Stap.MEER, l.kenmerk, 13000); l.volgendeLijst();
        check(stabiel(l, c, 14000) == FacturenLijst.Stap.DOWNLOAD && l.index == 10, "een tweede keer uitklappen");
        download(l); l.paginaKlaar(16000);
        check(stabiel(l, c, 16000) == FacturenLijst.Stap.KLAAR && l.volledig && l.aantal == 12, "alle twaalf gedownload: pas nu volledig");

        JSONObject p1 = lijst(1, 10, false, true), p2 = lijst(11, 20, false, true), p3 = lijst(21, 22, false, false);
        l = new FacturenLijst(50, 0); stabiel(l, p1, 0); download(l); l.paginaKlaar(2000);
        check(stabiel(l, p1, 2000) == FacturenLijst.Stap.VOLGENDE, "volgende pagina na downloaden");
        l.geklikt(FacturenLijst.Stap.VOLGENDE, l.kenmerk, 4000); l.volgendeLijst();
        check(stabiel(l, p2, 5000) == FacturenLijst.Stap.DOWNLOAD && l.index == 0, "tien andere regels tellen als nieuwe pagina");
        l.beginKnop(); l.index++;
        l.herstel(7000);
        check(stabiel(l, p1, 8000) == FacturenLijst.Stap.VOLGENDE, "teruggaan zet paginering terug: juiste pad opnieuw volgen");
        l.geklikt(FacturenLijst.Stap.VOLGENDE, p1.optString("kenmerk"), 10000);
        check(stabiel(l, p2, 11000) == FacturenLijst.Stap.DOWNLOAD && l.index == 1 && l.aantal == 11, "juiste pagina hersteld zonder downloadindex kwijt te raken");
        download(l); l.paginaKlaar(13000); stabiel(l, p2, 13000);
        l.geklikt(FacturenLijst.Stap.VOLGENDE, l.kenmerk, 15000); l.volgendeLijst();
        stabiel(l, p3, 16000); download(l); l.paginaKlaar(18000);
        check(stabiel(l, p3, 18000) == FacturenLijst.Stap.KLAAR && l.aantal == 22, "ook laatste korte pagina verwerkt");

        l = new FacturenLijst(50, 0); stabiel(l, a, 0); download(l); l.paginaKlaar(2000); stabiel(l, a, 2000);
        l.geklikt(FacturenLijst.Stap.MEER, l.kenmerk, 4000); l.volgendeLijst();
        check(l.bekijk(a, 34000) == FacturenLijst.Stap.FOUT && l.fout.equals("laden") && !l.volledig, "klik zonder resultaat wordt laadfout, geen volledige periode");

        l = new FacturenLijst(10, 0); stabiel(l, p1, 0); download(l); l.paginaKlaar(2000);
        check(stabiel(l, p1, 2000) == FacturenLijst.Stap.FOUT && l.fout.equals("limiet") && !l.volledig, "maximum met vervolgpagina is onvolledig");
        l = new FacturenLijst(10, 0); stabiel(l, lijst(1, 12, false, false), 0); download(l);
        check(l.aantal == 10 && !l.beginKnop() && l.fout.equals("limiet") && !l.volledig, "ook maximum midden in een pagina is onvolledig");
        l = new FacturenLijst(10, 0); JSONObject exact = lijst(1, 10, false, false); stabiel(l, exact, 0); download(l); l.paginaKlaar(2000);
        check(stabiel(l, exact, 2000) == FacturenLijst.Stap.KLAAR, "precies maximum zonder vervolg is volledig");

        l = new FacturenLijst(50, 0); stabiel(l, p1, 0); download(l); l.paginaKlaar(2000); stabiel(l, p1, 2000);
        l.geklikt(FacturenLijst.Stap.VOLGENDE, l.kenmerk, 4000); l.volgendeLijst(); stabiel(l, p2, 5000); download(l); l.paginaKlaar(7000); stabiel(l, p2, 7000);
        l.geklikt(FacturenLijst.Stap.VOLGENDE, l.kenmerk, 9000); l.volgendeLijst();
        check(stabiel(l, p1, 10000) == FacturenLijst.Stap.FOUT && l.fout.equals("herhalen"), "paginering die rondjes loopt stopt met een reden");
        l = new FacturenLijst(50, 0); stabiel(l, a, 0); download(l); l.paginaKlaar(2000); stabiel(l, a, 2000);
        l.geklikt(FacturenLijst.Stap.MEER, l.kenmerk, 4000); l.volgendeLijst(); stabiel(l, c, 5000); l.index = 7; l.herstel(7000);
        check(stabiel(l, a, 8000) == FacturenLijst.Stap.MEER, "ook uitklappen na teruggaan herstellen");
        l.geklikt(FacturenLijst.Stap.MEER, a.optString("kenmerk"), 10000);
        check(stabiel(l, c, 11000) == FacturenLijst.Stap.DOWNLOAD && l.index == 7, "verder bij juiste knop na uitklappen");

        l = new FacturenLijst(50, 0);
        JSONObject druk = lijst(1, 5, false, false).put("bezig", true);
        check(l.bekijk(druk, 0) == FacturenLijst.Stap.WACHT && l.bekijk(druk, 30000) == FacturenLijst.Stap.FOUT, "laadindicator kan geen schijnbaar volledige lijst opleveren");

        l = new FacturenLijst(50, 0);
        l.beginKnop(); int eerste = l.downloadToken; l.resultaat(eerste);
        check(l.ontvangen(), "eerste downloadresultaat rondt de eigen knop af");
        l.beginKnop(); int tweede = l.downloadToken;
        l.resultaat(eerste); l.resultaat(eerste);
        check(!l.ontvangen(), "late dubbele callbacks van de vorige knop ronden de volgende niet af");
        l.resultaat(tweede);
        check(l.ontvangen(), "pas het resultaat van de tweede knop rondt die af");

        FacturenLog log = new FacturenLog(1000);
        log.voeg(FacturenLog.Stap.START, 1000, 0, 0);
        log.voeg(FacturenLog.Stap.WACHT, 3500, 1, 5);
        log.voeg(FacturenLog.Stap.WACHT, 4500, 1, 5);
        JSONArray stappen = log.json().getJSONArray("stappen");
        check(stappen.length() == 2 && stappen.getJSONObject(1).getLong("ms") == 2500, "log bewaart tijd en aantallen zonder herhaalde wachtrijen");
        for (int i = 0; i < 200; i++) log.voeg(FacturenLog.Stap.DOWNLOAD, 5000 + i, 1, i);
        check(log.json().getJSONArray("stappen").length() == FacturenLog.MAX && log.json().getInt("weggelaten") > 0, "diagnosespoor is begrensd");
        check(log.json().getJSONArray("stappen").getJSONObject(0).length() == 4, "alleen stap, verstreken tijd, lijstnummer en aantal per logregel");
        if (fails > 0) throw new AssertionError(fails + " controles mislukt");
    }
}
