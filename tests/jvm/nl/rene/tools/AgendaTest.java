package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.TimeZone;

/** Datums, ISO-weken, herhaling, duur en zoeken in de agenda, ook in het verleden. */
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
        check(Agenda.gaatOver("Wat staat er morgen in mijn agenda???"), "vraagtekens aan het eind blijven een vraag");
        Agenda.Venster morgen = Agenda.venster("wat staat er morgen in mijn agenda", "2026-11-11");
        check("2026-11-12".equals(morgen.van) && "2026-11-12".equals(morgen.tot), "morgen is één dag");
        check("".equals(morgen.woorden), "morgen telt niet als zoekwoord (" + morgen.woorden + ")");
        Agenda.Venster aan = Agenda.venster("wat staat er aanstaande vrijdag in mijn agenda", "2026-11-11");
        check("2026-11-13".equals(aan.van) && "2026-11-13".equals(aan.tot), "aanstaande vrijdag is 13 november (" + aan.van + ")");
        Agenda.Venster kom = Agenda.venster("wat staat er komende vrijdag in mijn agenda", "2026-11-11");
        check("2026-11-13".equals(kom.van), "komende vrijdag is deze vrijdag (" + kom.van + ")");
        Agenda.Venster volgVr = Agenda.venster("wat staat er volgende vrijdag in mijn agenda", "2026-11-11");
        check("2026-11-20".equals(volgVr.van), "volgende vrijdag is 20 november (" + volgVr.van + ")");
        Agenda.Venster aanMa = Agenda.venster("wat staat er aanstaande maandag in mijn agenda", "2026-11-11");
        check("2026-11-16".equals(aanMa.van), "aanstaande maandag is 16 november (" + aanMa.van + ")");
        Agenda.Venster dezeVr = Agenda.venster("wat staat er deze vrijdag in mijn agenda", "2026-11-11");
        check("2026-11-13".equals(dezeVr.van), "deze vrijdag blijft in deze week (" + dezeVr.van + ")");
        Agenda.Venster kaal = Agenda.venster("wat staat er vrijdag in mijn agenda", "2026-11-11");
        check("2026-11-13".equals(kaal.van), "vrijdag is de eerstvolgende (" + kaal.van + ")");
        Agenda.Venster vw = Agenda.venster("wat staat er volgende week woensdag in mijn agenda", "2026-11-11");
        check("2026-11-18".equals(vw.van) && "2026-11-18".equals(vw.tot) && !vw.woorden.contains("woensdag"), "volgende week woensdag is 18 november (" + vw.van + ", " + vw.woorden + ")");
        Agenda.Venster wk = Agenda.venster("wat staat er volgende week in mijn agenda", "2026-11-11");
        check("2026-11-16".equals(wk.van) && "2026-11-22".equals(wk.tot), "volgende week is 16 t/m 22 november (" + wk.van + ".." + wk.tot + ")");
        Agenda.Venster dec = Agenda.venster("wat was er in december in mijn agenda", "2026-11-11");
        check("2025-12-01".equals(dec.van) && "2025-12-31".equals(dec.tot), "december zonder jaar, en 'was', is vorig jaar (" + dec.van + ".." + dec.tot + ")");
        Agenda.Venster decJaar = Agenda.venster("wat was er in december 2019 in mijn agenda", "2026-11-11");
        check("2019-12-01".equals(decJaar.van) && "2019-12-31".equals(decJaar.tot), "december 2019 blijft 2019");
        check(Agenda.verschil("1970-01-01", "1970-01-02") == 1 && Agenda.verschil("2026-11-11", "2026-11-11") == 0, "dagtelling");
        Agenda.Uitkomst leeg = Agenda.schoon(new JSONObject().put("titel", "  ").put("dag", "2026-11-11"), 0, true);
        check(!leeg.ok() && "titel".equals(leeg.fout), "lege titel weigeren");

        check("PT1H".equals(Agenda.duurIcs(3_600_000L, false)) && "PT1H30M".equals(Agenda.duurIcs(90 * 60_000L, false)), "duur van een uur en van anderhalf uur");
        check("P1D".equals(Agenda.duurIcs(86_400_000L, true)) && "P2D".equals(Agenda.duurIcs(2 * 86_400_000L, true)), "duur van hele dagen");
        TimeZone utc = TimeZone.getTimeZone("UTC");
        JSONObject archief = new JSONObject().put("titel", "Archief").put("dag", "1960-03-12").put("eind", "1960-03-12").put("van", "14:00").put("tot", "15:00");
        AgendaCal.Plan oudPlan = AgendaCal.plan(utc, archief);
        long verwacht = Regio.toMillis(utc, "1960-03-12", "14:00");
        check(oudPlan != null && oudPlan.start == verwacht && oudPlan.start < 0 && oudPlan.einde == Regio.toMillis(utc, "1960-03-12", "15:00") && !oudPlan.herhaalt,
                "afspraak van voor 1970 houdt zijn tijdstip");
        JSONObject sport = new JSONObject().put("titel", "Sport").put("dag", "2026-11-11").put("eind", "2026-11-11").put("van", "09:00").put("tot", "10:00").put("herhaal", "w");
        AgendaCal.Plan reeksPlan = AgendaCal.plan(utc, sport);
        check(reeksPlan != null && reeksPlan.herhaalt && reeksPlan.einde == 0 && "PT1H".equals(reeksPlan.duur) && "FREQ=WEEKLY".equals(reeksPlan.regel),
                "herhaling krijgt een duur en geen eindtijdstip");
        JSONObject hele = new JSONObject().put("heleDag", true).put("dag", "2026-11-11").put("eind", "2026-11-11").put("herhaal", "d");
        AgendaCal.Plan dagPlan = AgendaCal.plan(utc, hele);
        check(dagPlan != null && dagPlan.herhaalt && "P1D".equals(dagPlan.duur), "hele dag die herhaalt duurt P1D");
        check(AgendaCal.plan(utc, new JSONObject().put("dag", "geen")) == null, "ongeldige datum wordt niet weggeschreven");
        check(AgendaCal.duurMs("P3600S", false) == 3_600_000L && AgendaCal.duurMs("PT900S", false) == 900_000L, "duur in seconden, zoals de agenda van de telefoon die schrijft");
        check(AgendaCal.duurMs("PT1H30M", false) == 5_400_000L && AgendaCal.duurMs("P1DT2H", false) == 26 * 3_600_000L, "duur met uren en dagen");
        check(AgendaCal.duurMs("P1D", true) == 86_400_000L && AgendaCal.duurMs("onzin", false) == 3_600_000L && AgendaCal.duurMs(null, true) == 86_400_000L, "hele dag, en een onleesbare duur");

        JSONObject los = new JSONObject().put("id", "x3_20261112").put("titel", "Overleg").put("dag", "2026-11-12")
                .put("eind", "2026-11-12").put("van", "11:00").put("tot", "11:30").put("herhaal", "").put("ext", 3);
        List<Agenda.Item> weekLos = Agenda.lijst(new JSONArray(), new JSONArray().put(los), "2026-11-09", "2026-11-15", "2026-11-11", new String[0]);
        check(weekLos.size() == 1 && "2026-11-12".equals(weekLos.get(0).dag), "een losse afspraak uit de agenda van de telefoon blijft in de week");
        JSONObject vroeg = new JSONObject().put("id", "x4").put("titel", "Tandarts").put("dag", "2024-03-12").put("eind", "2024-03-12").put("herhaal", "").put("ext", 4);
        check(Agenda.lijst(new JSONArray(), new JSONArray().put(vroeg), "2026-11-09", "2026-11-15", "2026-11-11", new String[0]).isEmpty(),
                "een afspraak buiten de week staat niet in de week");

        JSONObject serie = new JSONObject().put("id", "x9").put("titel", "Stand-up").put("dag", "2020-01-06").put("eind", "2020-01-06")
                .put("van", "09:00").put("tot", "09:15").put("herhaal", "w").put("ext", 9);
        JSONArray veel = new JSONArray();
        for (int i = 0; i < 210; i++) {
            String dag = Regio.dayAdd("2012-01-01", i);
            veel.put(new JSONObject().put("id", "o" + i).put("titel", "Notitie").put("dag", dag).put("eind", dag)
                    .put("van", "08:00").put("tot", "08:30").put("herhaal", "").put("ext", 1000 + i));
        }
        veel.put(serie);
        List<Agenda.Item> breed = Agenda.lijst(new JSONArray(), veel, "2011-01-01", "2029-11-11", "2026-11-11", new String[0]);
        boolean dezeWeek = false;
        for (Agenda.Item it : breed) if ("2026-11-09".equals(it.dag) && "Stand-up".equals(it.titel)) dezeWeek = true;
        String tip = breed.isEmpty() ? "" : breed.get(0).dag + " … " + breed.get(breed.size() - 1).dag;
        check(breed.size() == Agenda.MAX_ZOEK && dezeWeek, "een lang bereik houdt de afspraak van deze week (" + breed.size() + ", " + tip + ")");
        JSONObject kopie = new JSONObject(serie.toString());
        List<Agenda.Item> dubbel = Agenda.lijst(new JSONArray().put(kopie), new JSONArray().put(serie), "2026-11-09", "2026-11-15", "2026-11-11", new String[0]);
        check(dubbel.size() == 1, "dezelfde reeks in Tools en op de telefoon telt één keer (" + dubbel.size() + ")");

        // Backup: agenda/ gaat mee in Alle appgegevens, niet in de Android-backup (Google) of bij overzetten
        check(AppData.FILE_KNOWN.contains("agenda") && !AppData.FILE_SKIP.containsKey("agenda"), "Alle appgegevens: agenda/ gaat mee");
        String br = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("app/res/xml/backup_rules.xml")), java.nio.charset.StandardCharsets.UTF_8);
        String dr = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("app/res/xml/data_rules.xml")), java.nio.charset.StandardCharsets.UTF_8);
        String ex = "<exclude domain=\"file\" path=\"agenda/\" />";
        int cb = dr.indexOf("<cloud-backup>"), dt = dr.indexOf("<device-transfer>");
        check(br.contains(ex) && cb >= 0 && dt > cb && dr.substring(cb, dt).contains(ex) && dr.substring(dt).contains(ex), "Android-backup en overzetten: agenda/ uitgesloten");

        System.out.println(fails == 0 ? "\nAlles goed" : "\n" + fails + " mislukt");
        if (fails > 0) System.exit(1);
    }
}
