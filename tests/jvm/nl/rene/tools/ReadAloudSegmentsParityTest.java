package nl.rene.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Zinnen van de motor (ReadAloud.segments) voor een vaste set teksten naar build/test-data/readaloud-segments.json.
 * tests/ui/ereader-lezen.test.js laat dezelfde teksten door erSegments (de JS-versie in 74c-ereader-lezen.js) gaan en
 * vergelijkt de grenzen: markeren, vorige/volgende zin en het begin bij de zichtbare zin moeten precies op de zinnen
 * van de motor vallen.
 */
public class ReadAloudSegmentsParityTest {
    static final String[] TEXTS = {
        "'Waar ga je heen?' vroeg ze. 'Naar de haven.' Hij knikte.",
        "Hij zei: \"Kom.\" Toen ging hij. \"Echt?\" vroeg ze.",
        "Dit klopt (zie boven.) Daarna verder. [Noot.] Klaar.",
        "„Ja,” zei hij. «Nee.» ‘Misschien.’ Einde.",
        "Het kost 3.5 euro op www.x.nl vandaag. Ok…  Wacht?! Ja.",
        "Kop\n\nRegel zonder punt\nWacht...  echt?!\n  \nLaatste regel.",
        "Hallo. Niet gesplitst door een harde spatie. Wel hier. En dit.",
        "Tab.\tNa een tab. Einde",
        "a ".concat(new String(new char[160]).replace("\0", "woord ")).concat("einde. Volgende zin."),
        new String(new char[219]).replace('\0', 'a') + "😀" + "bbb. Daarna.",
        "",
        "   \n  ",
    };

    public static void main(String[] args) throws Exception {
        JSONArray out = new JSONArray();
        for (String text : TEXTS) {
            JSONArray segs = new JSONArray();
            List<int[]> list = ReadAloud.segments(text, 0, ReadAloud.MAX);
            for (int[] s : list) segs.put(new JSONArray().put(s[0]).put(s[1]));
            // Ook vanaf een plek midden in de tekst (zoals bij een leesplek)
            JSONArray mid = new JSONArray();
            int from = text.length() / 2;
            for (int[] s : ReadAloud.segments(text, from, ReadAloud.MAX)) mid.put(new JSONArray().put(s[0]).put(s[1]));
            out.put(new JSONObject().put("text", text).put("segs", segs).put("from", from).put("mid", mid));
            if (text.contains("vroeg ze") && list.size() < 3) throw new AssertionError("dialoog wordt niet in zinnen geknipt");
        }
        Files.createDirectories(Paths.get("build/test-data"));
        Files.write(Paths.get("build/test-data/readaloud-segments.json"), out.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("ReadAloudSegmentsParityTest OK: " + out.length() + " teksten");
    }
}
