package nl.rene.tools;

import org.json.JSONObject;

/** Naam van een notitie: titel, anders de eerste regel. De tekst eronder herhaalt die regel niet. */
public class NotesLabelTest {
    static int failed = 0;
    static void check(String what, boolean ok) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) failed++; }

    public static void main(String[] a) throws Exception {
        JSONObject note = new JSONObject().put("title", "").put("text", "Eerste regel\nDe rest van de notitie");
        check("titel is de eerste regel", "Eerste regel".equals(Notes.showTitle(note)));
        check("tekst zonder de titelregel", "De rest van de notitie".equals(Notes.showText(note)));
        JSONObject one = new JSONObject().put("title", "  ").put("text", "Alleen deze zin");
        check("één regel is de titel", "Alleen deze zin".equals(Notes.showTitle(one)));
        check("één regel heeft geen aparte tekst", "".equals(Notes.showText(one)));
        JSONObject named = new JSONObject().put("title", "Boodschappen").put("text", "Voor zaterdag\nextra");
        check("ingevulde titel blijft", "Boodschappen".equals(Notes.showTitle(named)));
        check("bij een titel blijft de hele tekst", "Voor zaterdag\nextra".equals(Notes.showText(named)));
        String lang = "Dit is een heel lange eerste regel die zeker meer dan tachtig tekens telt en dus netjes wordt afgekapt";
        check("de proefzin is langer dan 80", lang.length() > 80);
        JSONObject cut = new JSONObject().put("text", lang);
        String title = Notes.showTitle(cut);
        check("lange regel wordt afgekapt", title.length() <= 80 && title.length() >= 40 && lang.startsWith(title));
        check("afkappen op een spatie", lang.charAt(title.length()) == ' ');
        check("lege notitie", "".equals(Notes.showTitle(new JSONObject())) && "".equals(Notes.showText(null)));
        System.out.println(failed == 0 ? "Alle titel-tests geslaagd" : failed + " test(s) mislukt");
        System.exit(failed == 0 ? 0 : 1);
    }
}
