package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

/** Id's uit een teruggezette notitie-backup mogen nooit code in de weergave brengen. */
public class SafeIdTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }
    public static void main(String[] a) throws Exception {
        check("abc_12-x".equals(Restore.safeId("abc_12-x", "n")), "gewone id blijft");
        check(!Restore.safeId("x');Android.waChats();//", "n").contains("'"), "id met code wordt vervangen");
        check(Restore.safeId("", "r1").startsWith("r1x"), "lege id krijgt een nieuwe");
        check(Restore.safeId(null, "n").matches("[A-Za-z0-9_-]+"), "null wordt een geldige id");
        check(!Restore.safeId(new String(new char[100]).replace('\0', 'a'), "n").equals(new String(new char[100]).replace('\0', 'a')), "te lange id wordt vervangen");
        JSONObject list = Restore.cleanNote(new JSONObject().put("id", "l1").put("kind", "list").put("title", "Boodschappen").put("text", ""));
        check(list != null && "list".equals(list.optString("kind")) && list.optJSONArray("items").length() == 0, "lege lijst blijft een lijst");
        JSONObject note = Restore.cleanNote(new JSONObject().put("id", "n1").put("kind", "note").put("title", "").put("text", "Bellen\nMorgen")
                .put("items", new JSONArray().put(new JSONObject().put("id", "i").put("text", "Melk").put("done", false))));
        check(note != null && "note".equals(note.optString("kind")) && note.optJSONArray("items").length() == 1 && "Bellen".equals(Notes.showTitle(note)), "notitie met item blijft een notitie en heet naar de eerste regel");
        JSONObject old = Restore.cleanNote(new JSONObject().put("id", "o1").put("title", "Oud").put("text", ""));
        check(old != null && !old.has("kind"), "oude notitie zonder soort blijft zonder soort");
        JSONObject bad = Restore.cleanNote(new JSONObject().put("id", "b1").put("kind", "script").put("title", "X"));
        check(bad != null && !bad.has("kind"), "onbekend soort wordt niet overgenomen");
        System.out.println(fails == 0 ? "Alle id-tests geslaagd" : fails + " id-tests mislukt");
        System.exit(fails == 0 ? 0 : 1);
    }
}
