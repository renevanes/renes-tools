package nl.rene.tools;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;

/** Voorlezen in de e-reader: zinnen knippen (Nederlands en Engels), leesplek naar zin, hoofdstukvolgorde en slaaptimer. */
public class EreaderSpeechTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static List<String> parts(String text) {
        int[] s = EreaderSpeech.split(text); List<String> out = new ArrayList<>();
        for (int i = 0; i < EreaderSpeech.count(s); i++) out.add(text.substring(s[2 * i], s[2 * i + 1]));
        return out;
    }
    static void same(String text, String[] want, String message) {
        List<String> got = parts(text);
        check(got.equals(java.util.Arrays.asList(want)), message + ": " + got);
    }
    public static void main(String[] args) throws Exception {
        same("Dit is bijv. een test. Dhr. Jansen kwam om 3.15 uur. Hij zei: “Ja!” Toen ging hij.",
                new String[]{"Dit is bijv. een test.", "Dhr. Jansen kwam om 3.15 uur.", "Hij zei: “Ja!”", "Toen ging hij."}, "Nederlandse afkortingen, tijden en aanhalingstekens");
        same("We zagen o.a. vogels, d.w.z. meeuwen enz. en ca. tien reigers. Prof. De Vries knikte.",
                new String[]{"We zagen o.a. vogels, d.w.z. meeuwen enz. en ca. tien reigers.", "Prof. De Vries knikte."}, "afkortingen met punten in het woord");
        same("Mr. Smith paid $3.50 at 9.30 today. J. K. Rowling wrote it, e.g. in 1997. Did she? Yes!",
                new String[]{"Mr. Smith paid $3.50 at 9.30 today.", "J. K. Rowling wrote it, e.g. in 1997.", "Did she?", "Yes!"}, "Engelse afkortingen, voorletters en bedragen");
        same("“Wat?” zei hij. Het was 1990. Daarna... Niets meer.", new String[]{"“Wat?” zei hij.", "Het was 1990.", "Daarna...", "Niets meer."}, "vraag midden in een zin en jaartal aan het eind");
        same("Hoofdstuk 1\nDe zon kwam op\nboven de stad.\n\nEen nieuwe dag", new String[]{"Hoofdstuk 1", "De zon kwam op\nboven de stad.", "Een nieuwe dag"}, "kopjes, hard afgebroken regels en alinea's");
        same("  \n\n ", new String[]{}, "alleen witruimte");
        same("Kijk op www.voorbeeld.nl voor meer. Klaar", new String[]{"Kijk op www.voorbeeld.nl voor meer.", "Klaar"}, "webadres en een laatste zin zonder punt");

        StringBuilder long1 = new StringBuilder(); for (int i = 0; i < 200; i++) long1.append("woord, ");
        List<String> chunks = parts(long1.toString());
        check(chunks.size() > 1 && chunks.stream().allMatch(c -> c.length() <= EreaderSpeech.MAX), "lange zin in stukken van hooguit " + EreaderSpeech.MAX + " tekens");
        check(String.join(" ", chunks).replace(" ", "").equals(long1.toString().replace(" ", "")), "knippen verliest geen tekst");
        StringBuilder emoji = new StringBuilder(); for (int i = 0; i < 400; i++) emoji.append("📚");
        for (String c : parts(emoji.toString())) check(!Character.isHighSurrogate(c.charAt(c.length() - 1)), "geen half teken bij knippen");

        String text = "Eén. Twee is langer. Drie.";
        int[] s = EreaderSpeech.split(text);
        check(EreaderSpeech.at(s, 0) == 0 && EreaderSpeech.at(s, -1) == 0, "begin van het hoofdstuk");
        check(EreaderSpeech.at(s, text.indexOf("langer")) == 1, "leesplek midden in een zin: die zin opnieuw");
        check(EreaderSpeech.at(s, text.indexOf(" Drie")) == 2, "witruimte tussen zinnen: de volgende zin");
        check(EreaderSpeech.at(s, 999) == 2 && EreaderSpeech.at(new int[0], 5) == -1, "na het einde en zonder zinnen");
        check(s[2] == text.indexOf("Twee") && "📚 Twee.".substring(EreaderSpeech.split("📚 Twee.")[0]).startsWith("📚"), "posities in UTF-16, zoals in JavaScript");
        check(EreaderSpeech.offset(50, 200) == .25 && EreaderSpeech.offset(5, 0) == 0 && EreaderSpeech.offset(300, 200) == 1, "leesplek als deel van het hoofdstuk");

        JSONArray linear = new JSONArray().put(true).put(false).put(true);
        check(EreaderSpeech.neighbor(linear, 3, 0, 1) == 2 && EreaderSpeech.neighbor(linear, 3, 2, -1) == 0, "losse noten overslaan");
        check(EreaderSpeech.neighbor(linear, 3, 2, 1) == -1 && EreaderSpeech.neighbor(null, 3, 0, 1) == 1, "einde van het boek en boek zonder leesvolgorde");

        check(EreaderSpeech.clampRate(0.1f) == 0.75f && EreaderSpeech.clampRate(5f) == 2f && EreaderSpeech.clampRate(Float.NaN) == 1f && EreaderSpeech.clampRate(1.25f) == 1.25f, "snelheid tussen 0,75× en 2×");
        long now = 1_000_000L;
        check(EreaderSpeech.sleepUntil(now, "15") == now + 15 * 60_000L && EreaderSpeech.sleepUntil(now, "60") == now + 3_600_000L, "slaaptimer in minuten");
        check(EreaderSpeech.sleepUntil(now, "chapter") == 0 && EreaderSpeech.sleepUntil(now, "7") == 0 && EreaderSpeech.sleepUntil(now, null) == 0, "einde hoofdstuk en onbekende keuze zonder tijdstip");
        check(!EreaderSpeech.sleepDue(now, 0) && !EreaderSpeech.sleepDue(now, now + 1) && EreaderSpeech.sleepDue(now, now), "slaaptimer loopt af");
        System.out.println("EreaderSpeechTest OK");
    }
}
