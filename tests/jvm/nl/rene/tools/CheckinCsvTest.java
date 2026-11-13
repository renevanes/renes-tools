package nl.rene.tools;

import java.util.TimeZone;

import org.json.JSONArray;
import org.json.JSONObject;

public class CheckinCsvTest {
    static int fails = 0;
    static void check(boolean ok, String name) { if (!ok) { fails++; System.out.println("FAIL: " + name); } else System.out.println("ok: " + name); }

    public static void main(String[] args) throws Exception {
        TimeZone tz = TimeZone.getTimeZone("Europe/Amsterdam");
        JSONArray qs = new JSONArray().put(new JSONObject().put("id", "q1").put("text", "Hoe voel je je?")).put(new JSONObject().put("id", "q2").put("text", "Werk; \"goed\"?"));
        long t1 = 1762941600000L; // 2025-11-12 11:00 in Amsterdam
        JSONArray as = new JSONArray()
                .put(new JSONObject().put("q", "q2").put("p", "2025-11-13").put("t", t1 + 86400000L).put("v", 7.5).put("l", "Prima").put("labels", new JSONArray().put("druk").put("thuis")).put("note", "=SOM(A1)\nregel twee"))
                .put(new JSONObject().put("q", "q1").put("p", "2025-11-12").put("t", t1).put("v", 4).put("l", "Goed").put("note", ""))
                .put(new JSONObject().put("q", "weg").put("p", "2025-W46").put("t", t1 + 1000).put("l", "Zonder score"));
        JSONArray rs = new JSONArray().put(new JSONObject().put("q", "q1").put("p", "2025-11-12").put("t", t1 + 500).put("text", "+31 terugblik"));
        String csv = CheckinCsv.csv(qs, as, rs, tz);
        String[] r = csv.split("\r\n");
        check(csv.startsWith("﻿" + CheckinCsv.KOP + "\r\n"), "BOM en kopregel");
        check(r.length == 5 && CheckinCsv.aantal(as, rs) == 4, "een regel per antwoord en opmerking");
        check(r[1].equals("Antwoord;Hoe voel je je?;2025-11-12;2025-11-12 11:00;4;Goed;;"), "oudste eerst, tijd in de app-zone: " + r[1]);
        check(r[2].startsWith("Opmerking;Hoe voel je je?;2025-11-12;2025-11-12 11:00;;;;'+31 terugblik"), "opmerking, geen formule: " + r[2]);
        check(r[3].startsWith("Antwoord;(verwijderde vraag);2025-W46;") && r[3].endsWith(";;Zonder score;;"), "verwijderde vraag en geen score: " + r[3]);
        check(r[4].equals("Antwoord;\"Werk; \"\"goed\"\"?\";2025-11-13;2025-11-13 11:00;7,5;Prima;druk, thuis;'=SOM(A1) regel twee"), "aanhalingstekens, labels, komma, geen formule of regeleinde: " + r[4]);
        check(CheckinCsv.csv(qs, as, rs, TimeZone.getTimeZone("UTC")).contains("2025-11-12 10:00"), "een andere zone geeft een andere tijd");
        check(CheckinCsv.getal(3.0).equals("3") && CheckinCsv.getal(2.25).equals("2,25") && CheckinCsv.getal(Double.NaN).isEmpty(), "getallen");
        check(CheckinCsv.csv(null, null, null, null).equals("﻿" + CheckinCsv.KOP + "\r\n"), "leeg: alleen de kop");
        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
        System.out.println("CheckinCsvTest geslaagd");
    }
}
