package nl.rene.tools;

import java.util.List;

/** Zinnen en uitspreekbare tekst voor lokaal voorlezen (ReadAloud): posities in de oorspronkelijke tekst. */
public class ReadAloudTextTest {
    static int fails = 0;
    static void check(boolean ok, String msg) { if (!ok) { fails++; System.out.println("FOUT: " + msg); } }
    static String parts(String text, int from, int max) {
        StringBuilder out = new StringBuilder();
        for (int[] s : ReadAloud.segments(text, from, max)) out.append('[').append(text, s[0], s[1]).append(']');
        return out.toString();
    }
    public static void main(String[] args) {
        check(parts("Dit is een zin. En nog een!  Klaar?", 0, 220).equals("[Dit is een zin.][En nog een!][Klaar?]"), "zinnen: " + parts("Dit is een zin. En nog een!  Klaar?", 0, 220));
        check(parts("Het kost 3.5 miljoen op www.x.nl vandaag.", 0, 220).equals("[Het kost 3.5 miljoen op www.x.nl vandaag.]"), "getallen en adressen blijven heel");
        check(parts("Hij zei: \"Ga weg.\" Toen ging ze.", 0, 220).equals("[Hij zei: \"Ga weg.\"][Toen ging ze.]"), "aanhalingsteken hoort bij de zin");
        check(parts("Kop\n\nRegel zonder punt\nWacht...  echt?!", 0, 220).equals("[Kop][Regel zonder punt][Wacht...][echt?!]"), "regeleinden en reeksen leestekens: " + parts("Kop\n\nRegel zonder punt\nWacht...  echt?!", 0, 220));
        check(parts("Een. Twee. Drie.", 5, 220).equals("[Twee.][Drie.]"), "vanaf een leesplek");
        check(ReadAloud.segments("   \n  ", 0, 220).isEmpty() && ReadAloud.segments("", 0, 220).isEmpty() && ReadAloud.segments("abc", 9, 220).isEmpty(), "lege tekst en plek voorbij het einde");
        // Lange zin zonder leestekens: knippen bij een spatie, nooit langer dan max, niets kwijt
        StringBuilder lang = new StringBuilder(); for (int i = 0; i < 400; i++) lang.append("woord").append(i).append(' ');
        String t = lang.toString(); List<int[]> segs = ReadAloud.segments(t, 0, 220);
        StringBuilder back = new StringBuilder(); boolean small = true, words = true;
        for (int[] s : segs) { if (s[1] - s[0] > 220) small = false; if (Character.isWhitespace(t.charAt(s[0])) || Character.isWhitespace(t.charAt(s[1] - 1))) words = false; back.append(t, s[0], s[1]).append(' '); }
        check(small && words && segs.size() > 10 && back.toString().equals(t), "lange zin bij spaties geknipt, zonder verlies");
        // Zonder spaties: hard knippen, maar geen surrogaatpaar doormidden
        String emoji = new String(new char[219]).replace('\0', 'a') + "😀" + "bbb";
        List<int[]> e = ReadAloud.segments(emoji, 0, 220);
        check(e.size() == 2 && e.get(0)[1] == 219 && e.get(1)[0] == 219, "surrogaatpaar blijft heel");
        // Uitspreekbaar: Latijnse letters of cijfers; geen \p{IsLatin} (Android 7 kent dat niet)
        check(ReadAloud.sayable("Één") && ReadAloud.sayable("42") && ReadAloud.sayable("ẞ") && !ReadAloud.sayable("* * *") && !ReadAloud.sayable("—") && !ReadAloud.sayable("日本語") && !ReadAloud.sayable(""), "uitspreekbaar");
        check(ReadAloud.speakable("Hoi​ 😀 daar\u0007\n").equals("Hoi  daar\n"), "emoji, opmaak- en besturingstekens weg: " + ReadAloud.speakable("Hoi​ 😀 daar\u0007\n"));
        System.out.println(fails == 0 ? "Alles goed" : fails + " fout(en)");
        if (fails > 0) System.exit(1);
    }
}
