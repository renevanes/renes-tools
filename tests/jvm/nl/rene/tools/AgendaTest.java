package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/** Datums, ISO-weken, herhaling en zoeken in de agenda, ook in het verleden. */
public class AgendaTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    static Agenda.Item item(String titel, String dag, String van, String tot, String her) {
        Agenda.Item it = new Agenda.Item();
        it.id = "a1"; it.titel = titel; it.dag = dag; it.eind = dag; it.van = van; it.tot = tot; it.herhaal = her;
        return it;
    }

    public static void main(String[] args) throws Exception {
        check(Agenda.isoWeek("2020-01-01") == 1, "1 januari 2020 is week 1");
        check(Agenda.isoWeek("2021-01-01") == 53 && Agenda.isoJaar("2021-01-01") == 2020, "1 januari 2021 is week 53 van 2020");
        check(Agenda.isoWeek("2024-12-30") == 1 && Agenda.isoJaar("2024-12-30") == 2025, "30 december 2024 is week 1 van 2025");
        check("2024-12-30".equals(Agenda.isoMaandag(2025, 1)), "maandag van week 1 in 2025");
        check(Agenda.isoWeek("geen") == 0, "ongeldige datum: week 0");
        check("2024-02-29".equals(Agenda.plusMaand("2024-01-31", 1)) && "2025-02-28".equals(Agenda.plusMaand("2025-01-31", 1)), "31 januari schuift naar de laatste dag van februari");
        check(Agenda.jsDow("2026-01-01") == 4 && Agenda.jsDow("2026-11-11") == 3, "1 januari 2026 is donderdag, 11 november is woensdag");

        String[] week = Agenda.bereik("week", "2026-11-11", 1);
        check(week != null && "2026-11-09".equals(week[0]) && "2026-11-15".equals(week[1]), "week vanaf maandag: 9 t/m 15 november (" + (week == null ? "?" : week[0] + ".." + week[1]) + ")");
        String[] zon = Agenda.bereik("week", "2026-11-11", 0);
        check(zon != null && "2026-11-08".equals(zon[0]) && "2026-11-14".equals(zon[1]), "week vanaf zondag: 8 t/m 14 november (" + (zon == null ? "?" : zon[0] + ".." + zon[1]) + ")");
        String[] maand = Agenda.bereik("maand", "2026-11-11", 1);
        check(maand != null && "2026-10-26".equals(maand[0]), "maandrooster begint op 26 oktober (1 november is zondag)");
        String[] lijst = Agenda.bereik("lijst", "2026-11-11", 1);
        check(lijst != null && "2026-11-01".equals(lijst[0]) && "2026-11-30".equals(lijst[1]), "lijst is de kalendermaand");

        List<Agenda.Item> reeks = Agenda.momenten(item("Sport", "2024-01-01", "18:00", "19:00", "w"), "2024-01-01", "2024-01-21", "2026-11-11");
        check(reeks.size() == 3 && "2024-01-15".equals(reeks.get(2).dag), "elke week, drie keer in 21 dagen");

        JSONObject oud = new JSONObject().put("id", "aoud").put("titel", "Tandarts").put("plek", "Rotterdam").put("notitie", "controle")
                .put("dag", "2019-06-03").put("eind", "2019-06-03").put("van", "14:00").put("tot", "14:30");
        JSONArray lokaal = new JSONArray().put(oud);
        String q = "wanneer was de tandarts in mijn agenda";
        String ant = Agenda.antwoord(lokaal, new JSONArray(), q, "2026-11-11");
        check(ant.contains("Tandarts") && ant.contains("2019") && ant.contains("14:00"), "zoeken in het verleden: tandarts in 2019 (" + ant + ")");
        check(!Agenda.gaatOver("Zet de tandarts morgen in mijn agenda") && !Agenda.gaatOver("Plan een afspraak in mijn kalender"), "iets in de agenda zetten is geen zoekvraag");
        check(Agenda.gaatOver("wat staat er morgen in mijn agenda"), "wat staat er morgen wel");
        Agenda.Venster morgen = Agenda.venster("wat staat er morgen in mijn agenda", "2026-11-11");
        check("2026-11-12".equals(morgen.van) && "2026-11-12".equals(morgen.tot), "morgen is één dag");
        Agenda.Uitkomst leeg = Agenda.schoon(new JSONObject().put("titel", "  ").put("dag", "2026-11-11"), 0, true);
        check(!leeg.ok() && "titel".equals(leeg.fout), "lege titel weigeren");

        System.out.println(fails == 0 ? "\nAlles goed" : "\n" + fails + " mislukt");
        if (fails > 0) System.exit(1);
    }
}
