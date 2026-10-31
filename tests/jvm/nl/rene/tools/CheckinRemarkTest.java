package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;

/**
 * Check-in-opmerkingen (CheckinStore.remarkApply/remarkWithout/remarkPlan): toevoegen, wijzigen, wissen, welke
 * periodes mogen (niet in de toekomst, wel bij gearchiveerd en los van wijzigen/notitie), opschonen, het maximum,
 * terugzetten zonder dubbelen, en de laatste opmerking voor de assistent.
 */
public class CheckinRemarkTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    static final TimeZone AMS = TimeZone.getTimeZone("Europe/Amsterdam");
    static long at(int y, int mo, int d, int h, int mi) { Calendar k = Calendar.getInstance(AMS); k.clear(); k.set(y, mo - 1, d, h, mi); return k.getTimeInMillis(); }

    static JSONObject question(String id, String rhythm) throws Exception {
        return new JSONObject().put("id", id).put("text", "Hoe voel je je?").put("rhythm", rhythm).put("times", new JSONArray().put("21:00"))
                .put("days", new JSONArray().put(1).put(2).put(3).put(4).put(5).put(6).put(7)).put("wday", 5).put("dom", 1).put("until", "23:59")
                .put("created", at(2026, 9, 1, 12, 0)).put("on", true);
    }

    static JSONObject add(JSONArray list, JSONObject q, Set<String> keys, String p, String text, long now) throws Exception {
        return CheckinStore.remarkApply(list, q, keys, new JSONObject().put("q", q == null ? "x" : q.optString("id")).put("p", p).put("text", text), now, AMS);
    }

    public static void main(String[] a) throws Exception {
        long now = at(2026, 10, 31, 18, 49);
        Set<String> none = new HashSet<>();
        JSONObject q = question("voel", "daily");
        JSONArray list = new JSONArray();

        // ---------- toevoegen ----------
        JSONObject r = add(list, q, none, "2026-10-20", "  Toen was ik  moe van de verhuizing\n\nmaar blij  ", now);
        check(r.optBoolean("ok") && r.optString("id").startsWith("r") && "2026-10-20".equals(r.optString("key")) && list.length() == 1, "opmerking bij een gemiste dag zonder antwoord");
        JSONObject x = list.getJSONObject(0);
        check("voel".equals(x.optString("q")) && "2026-10-20".equals(x.optString("p")) && x.optLong("t") == now && !x.has("edited") && !x.optBoolean("voice"), "bewaard met vraag, periode en tijd");
        check(x.optString("text").equals("Toen was ik  moe van de verhuizing\n\nmaar blij"), "tekst: rand weg, regels blijven (zoals een notitie)");
        r = add(list, q, none, "2026-10-21", "a\u0000b\u0007c\td", now);
        check(r.optBoolean("ok") && list.getJSONObject(1).optString("text").equals("abcd"), "stuurtekens eruit");
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 2600; i++) big.append('x');
        r = add(list, q, none, "2026-10-22", big.toString(), now);
        check(r.optBoolean("ok") && list.getJSONObject(2).optString("text").length() == CheckinStore.MAX_REMARK, "hooguit 2000 tekens");
        r = add(list, q, none, "2026-10-31", "Vandaag nog niet ingevuld, wel iets te zeggen", now);
        check(r.optBoolean("ok"), "vandaag mag (ook vóór 21:00)");
        JSONObject voiced = CheckinStore.remarkApply(list, q, none, new JSONObject().put("q", "voel").put("p", "2026-10-30").put("text", "ingesproken").put("voice", true), now, AMS);
        check(voiced.optBoolean("ok") && list.getJSONObject(4).optBoolean("voice"), "ingesproken: zo gemarkeerd");

        // ---------- wat niet mag ----------
        int n = list.length();
        check(add(list, q, none, "2026-11-01", "morgen", now).optString("error").equals("Deze periode is nog niet begonnen"), "niet in de toekomst");
        check(add(list, q, none, "2026-10-19", "   ", now).optString("error").equals("Schrijf eerst je opmerking"), "lege opmerking: niet bewaard");
        check(add(list, q, none, "2026-13-01", "x", now).optString("error").equals("Ongeldige periode"), "foute periode");
        check(add(list, q, none, "2026-W43", "x", now).optString("error").equals("Deze periode hoort niet bij de vraag"), "een week bij een dagelijkse vraag: hoort er niet bij");
        check(add(list, null, none, "2026-10-19", "x", now).optString("error").equals("Deze vraag bestaat niet meer"), "onbekende vraag");
        JSONObject work = question("werk", "workdays");
        check(add(list, work, none, "2026-10-25", "zondag", now).optString("error").equals("Deze periode hoort niet bij de vraag"), "werkdagen: zondag hoort er niet bij");
        check(list.length() == n, "niets bewaard bij een fout");

        // ---------- wel: los van wijzigen/notitie/achteraf, gearchiveerd, oude periodes, andere ritmes ----------
        JSONObject strict = question("streng", "daily").put("editable", false).put("note", false).put("voice", false).put("backfill", false).put("archived", true).put("on", false);
        check(add(list, strict, none, "2026-10-15", "Achteraf gezien viel het mee", now).optBoolean("ok"), "ook als wijzigen, notitie en achteraf invullen uit staan, en bij een gearchiveerde vraag");
        check(add(list, q, none, "2026-08-20", "Van vóór de vraag", now).optBoolean("ok"), "ook bij een periode van vóór de vraag");
        Set<String> keys = new HashSet<>(Arrays.asList("2026-10-10T08:00"));
        check(add(list, q, keys, "2026-10-10T08:00", "Bij een antwoord van het oude ritme", now).optBoolean("ok"), "bij een antwoord dat na een ander ritme niet meer bij een periode hoort");
        JSONObject weekly = question("wk", "weekly");
        check(add(list, weekly, none, "2026-W44", "Deze week", now).optBoolean("ok") && add(list, weekly, none, "2026-W45", "Volgende week", now).has("error"), "per week: deze week wel, volgende niet");
        JSONObject monthly = question("mnd", "monthly").put("dom", 31);
        check(add(list, monthly, none, "2026-10", "Oktober", now).optBoolean("ok") && add(list, monthly, none, "2026-11", "November", now).has("error"), "per maand: deze maand wel (ook vóór de 31e), volgende niet");
        JSONObject old = question("oud", "daily").put("created", at(2022, 1, 1, 12, 0));
        check(add(list, old, none, "2022-03-15", "Lang geleden", now).optBoolean("ok"), "ook een periode van jaren terug (zoals de kalender laat zien)");
        JSONObject multi = question("multi", "multi").put("times", new JSONArray().put("08:00").put("21:00"));
        check(add(list, multi, none, "2026-10-31T08:00", "Ochtend", now).optBoolean("ok"), "meerdere keren per dag: het moment van vanochtend wel");
        check(add(list, multi, none, "2026-10-31T21:00", "Avond", now).optString("error").equals("Dit moment is nog niet geweest"), "het moment van vanavond nog niet");
        check(add(list, multi, new HashSet<>(Arrays.asList("2026-10-31T21:00")), "2026-10-31T21:00", "Al ingevuld", now).optBoolean("ok"), "wel als je het al vooraf invulde");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < list.length(); i++) ids.add(list.getJSONObject(i).optString("id"));
        check(ids.size() == list.length(), "elke opmerking een eigen id");

        // ---------- wijzigen ----------
        String id = list.getJSONObject(0).optString("id");
        long later = now + 60_000;
        r = CheckinStore.remarkApply(list, q, none, new JSONObject().put("id", id).put("text", "Toch vooral moe").put("voice", true), later, AMS);
        x = list.getJSONObject(0);
        check(r.optBoolean("ok") && x.optString("text").equals("Toch vooral moe") && x.optLong("edited") == later && x.optLong("t") == now && x.optBoolean("voice") && "2026-10-20".equals(x.optString("p")), "wijzigen: tekst, gewijzigd-tijd, periode en aanmaaktijd blijven");
        check(CheckinStore.remarkApply(list, q, none, new JSONObject().put("id", id).put("text", " "), later, AMS).optString("error").equals("Schrijf iets, of kies Wissen") && x.optString("text").equals("Toch vooral moe"), "wijzigen naar leeg: niet (gebruik Wissen)");
        check(CheckinStore.remarkApply(list, q, none, new JSONObject().put("id", "rbestaatniet").put("text", "x"), later, AMS).optString("error").equals("Deze opmerking bestaat niet meer"), "wijzigen van een gewiste opmerking");
        check(CheckinStore.remarkApply(list, work, none, new JSONObject().put("id", id).put("text", "x"), later, AMS).optString("error").equals("Deze vraag bestaat niet meer"), "wijzigen via een andere vraag: niet");

        // ---------- wissen ----------
        int before = list.length();
        JSONArray left = CheckinStore.remarkWithout(list, id);
        check(left != null && left.length() == before - 1 && !left.toString().contains(id), "wissen");
        check(CheckinStore.remarkWithout(left, id) == null, "nogmaals wissen: bestaat niet meer");

        // ---------- maximum ----------
        JSONArray full = new JSONArray();
        for (int i = 0; i < CheckinStore.MAX_REMARKS; i++) full.put(new JSONObject().put("id", "r" + i));
        check(add(full, q, none, "2026-10-20", "nog één", now).optString("error").equals("Er zijn te veel opmerkingen bewaard") && full.length() == CheckinStore.MAX_REMARKS, "hooguit " + CheckinStore.MAX_REMARKS + " opmerkingen");

        // ---------- terugzetten: samenvoegen zonder dubbelen ----------
        JSONArray have = new JSONArray().put(new JSONObject().put("id", "r1").put("q", "voel").put("p", "2026-10-20").put("t", 100).put("text", "Eerste"));
        JSONArray in = new JSONArray()
                .put(new JSONObject().put("id", "r1").put("q", "voel").put("p", "2026-10-20").put("t", 100).put("text", "Eerste"))          // zelfde id
                .put(new JSONObject().put("id", "r9").put("q", "voel").put("p", "2026-10-20").put("t", 100).put("text", " Eerste "))        // zelfde inhoud
                .put(new JSONObject().put("id", "r2").put("q", "voel").put("p", "2026-10-21").put("t", 200).put("text", "Tweede").put("voice", true).put("edited", 300))
                .put(new JSONObject().put("id", "r2").put("q", "voel").put("p", "2026-10-21").put("t", 200).put("text", "Tweede"))          // dubbel in de backup
                .put(new JSONObject().put("id", "r3").put("q", "weg").put("p", "2026-10-21").put("t", 200).put("text", "Onbekende vraag"))
                .put(new JSONObject().put("id", "r4").put("q", "voel").put("p", "gisteren").put("t", 200).put("text", "Foute periode"))
                .put(new JSONObject().put("id", "r5").put("q", "voel").put("p", "2026-10-22").put("t", 200).put("text", "  "))
                .put(new JSONObject().put("id", "../x").put("q", "voel").put("p", "2026-10-22").put("t", 200).put("text", "Raar id"))
                .put(new JSONObject().put("id", "r6").put("q", "voel").put("p", "2026-10-22").put("t", 200).put("text", "Zes").put("extra", "weg"));
        in.put(new JSONObject().put("id", "r7").put("q", "voel").put("p", "2026-11-05").put("t", 200).put("text", "Periode in de toekomst"))
          .put(new JSONObject().put("id", "r8").put("q", "voel").put("p", "2026-10-22").put("t", now + 5 * 86_400_000L).put("text", "Tijd in de toekomst"));
        JSONObject plan = CheckinStore.remarkPlan(have, new HashSet<>(Arrays.asList("voel")), in, now, AMS);
        JSONArray fresh = plan.getJSONArray("fresh");
        check(fresh.length() == 2 && plan.optInt("dup") == 3 && plan.optInt("bad") == 6 && plan.optInt("full") == 0, "terugzetten: 2 nieuw, 3 dubbel, 6 overgeslagen (ook toekomst) (" + plan + ")");
        JSONObject f2 = fresh.getJSONObject(0);
        check("r2".equals(f2.optString("id")) && f2.optBoolean("voice") && f2.optLong("edited") == 300 && !fresh.getJSONObject(1).has("extra"), "teruggezet: opgeschoond, ingesproken en gewijzigd blijven");
        check(CheckinStore.remarkPlan(null, new HashSet<String>(), null, now, AMS).getJSONArray("fresh").length() == 0, "oude backup zonder opmerkingen");
        JSONArray nearly = new JSONArray();
        for (int i = 0; i < CheckinStore.MAX_REMARKS - 1; i++) nearly.put(new JSONObject().put("id", "x" + i).put("q", "voel").put("p", "2026-10-01").put("t", i + 1).put("text", "t" + i));
        plan = CheckinStore.remarkPlan(nearly, new HashSet<>(Arrays.asList("voel")), in, now, AMS);
        check(plan.getJSONArray("fresh").length() == 1 && plan.optInt("full") == 3, "terugzetten: niet meer dan " + CheckinStore.MAX_REMARKS + " (1 past, 3 niet) (" + plan.optInt("full") + ")");

        // ---------- de assistent: laatste opmerking ----------
        JSONArray qs = new JSONArray().put(new JSONObject().put("id", "voel").put("text", "Hoe voel je je?").put("emoji", "🙂")
                .put("options", new JSONArray().put(new JSONObject().put("value", 1)).put(new JSONObject().put("value", 5))));
        JSONArray ans = new JSONArray().put(new JSONObject().put("q", "voel").put("t", now - 3_600_000L).put("v", 4).put("e", "🙂").put("l", "Goed"));
        JSONArray rem = new JSONArray()
                .put(new JSONObject().put("q", "voel").put("p", "2026-10-20").put("t", now - 86_400_000L).put("text", "Oud"))
                .put(new JSONObject().put("q", "voel").put("p", "2026-10-29").put("t", now - 2 * 86_400_000L).put("edited", now - 60_000L).put("text", "Achteraf: drukke dag"));
        JSONObject cf = AiData.checkinFacts(qs, ans, rem, now);
        JSONObject lr = cf.getJSONArray("qs").getJSONObject(0).optJSONObject("lastRemark");
        check(lr != null && "Achteraf: drukke dag".equals(lr.optString("text")) && "do 29 okt".equals(lr.optString("p")), "assistent: de laatst geschreven opmerking, met de periode");
        AiData.Answer an = AiData.answer("checkin", "Wat heb ik vandaag bij check-in ingevuld?", cf, now, AMS);
        check(an != null && an.text.contains("laatste opmerking (over do 29 okt): Achteraf: drukke dag"), "assistent noemt de opmerking: " + (an == null ? "" : an.text.replace('\n', ' ')));
        check(!AiData.checkinFacts(qs, ans, now).toString().contains("lastRemark"), "zonder opmerkingen: zoals vroeger");

        System.out.println(fails == 0 ? "OK" : fails + " MISLUKT");
        System.exit(fails == 0 ? 0 : 1);
    }
}
