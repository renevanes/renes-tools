package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;

public class EreaderReadingTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static JSONObject mark(String kind, int start, int end) throws Exception { return new JSONObject().put("kind", kind).put("chapter", 0).put("start", start).put("end", end); }
    static void rejected(JSONArray all, JSONObject value, String text, String message) throws Exception {
        String before = all.toString(); boolean rejected = false;
        try { EreaderMarks.change(all, value, 2, text, 1); } catch (Exception expected) { rejected = true; }
        check(rejected && before.equals(all.toString()), message);
    }
    public static void main(String[] args) throws Exception {
        String text = "Een boek met café en een zin. Nog een CAFÉ. Ook [a-z] is gewone tekst. 📚";
        List<EreaderSearch.Hit> hits = EreaderSearch.find(text, "café", 100);
        check(hits.size() == 2 && text.substring(hits.get(1).start, hits.get(1).end).equals("CAFÉ"), "zoekresultaat bewaart precieze positie en negeert hoofdletters");
        check(EreaderSearch.find(text, "[a-z]", 100).size() == 1, "zoekopdracht is letterlijk en voert geen regex uit");
        check(EreaderSearch.find(text, "café", 1).size() == 1 && EreaderSearch.find(text, "a", 100).isEmpty(), "resultaatlimiet en minimale zoeklengte");
        check(EreaderSearch.find(text, "📚", 100).get(0).start == text.indexOf("📚"), "UTF-16-positie gelijk aan JavaScript, ook bij emoji");
        JSONArray empty = new JSONArray(), a = EreaderMarks.change(empty, mark("bookmark", 4, 4), 2, text, 10);
        check(empty.length() == 0 && a.length() == 1 && a.getJSONObject(0).getString("text").startsWith("boek"), "bladwijzer toevoegen zonder invoer te veranderen");
        String firstId = a.getJSONObject(0).getString("id");
        a = EreaderMarks.change(a, mark("highlight", 4, 8).put("note", "<script>gewone eigen tekst</script>"), 2, text, 11);
        check(a.length() == 2 && a.getJSONObject(1).getString("text").equals("boek"), "markering slaat alleen geselecteerde tekst en notitie op");
        JSONObject edit = new JSONObject(a.getJSONObject(1).toString()).put("note", "aangepast"); String editedId = edit.getString("id");
        a = EreaderMarks.change(a, edit, 2, text, 12);
        check(a.length() == 2 && a.getJSONObject(1).getString("id").equals(editedId) && a.getJSONObject(1).getString("note").equals("aangepast"), "notitie wijzigen zonder andere leeswijzers te verliezen");
        rejected(a, mark("highlight", -1, 6), text, "ongeldige selectie weigeren zonder gegevensverlies");
        rejected(a, mark("highlight", 4, 4), text, "lege markering weigeren");
        rejected(a, mark("bookmark", 0, 0).put("chapter", 2), text, "hoofdstuk buiten boek weigeren");
        StringBuilder large = new StringBuilder(); for (int i = 0; i < 2001; i++) large.append('x');
        rejected(a, mark("highlight", 4, 8).put("note", large.toString()), text, "te lange notitie weigeren zonder andere leeswijzers te verliezen");
        a = EreaderMarks.change(a, new JSONObject().put("id", firstId).put("remove", true), 2, "", 13);
        check(a.length() == 1 && a.getJSONObject(0).getString("id").equals(editedId), "alleen gekozen bladwijzer verwijderen");
        check(AppData.fileRule("ereader/abc/leeswijzers.json") == null && AppData.fileRule("ereader/abc/omslag.jpg") == null, "notities en omslagen gaan mee in de bestaande backup");
        System.out.println("EreaderReadingTest OK");
    }
}
