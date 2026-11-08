package nl.rene.tools;

import java.util.List;

/** Terugvalstem van de assistent (SystemSpeech): alleen stemmen die zonder internet werken, ingebouwd eerst. */
public class SystemSpeechTest {
    static int fails = 0;
    static void check(boolean ok, String msg) { if (!ok) { fails++; System.out.println("FOUT: " + msg); } }
    public static void main(String[] args) {
        // score(taal, gewenst, netwerk nodig, netwerksynthese, niet geïnstalleerd, ingebouwd, kwaliteit, vertraging)
        check(SystemSpeech.score("nl", "nl", true, false, false, true, 400, 100) == -1, "stem die internet nodig heeft: nooit");
        check(SystemSpeech.score("nl", "nl", false, true, false, true, 400, 100) == -1, "netwerksynthese: nooit");
        check(SystemSpeech.score("nl", "nl", false, false, true, true, 400, 100) == -1, "nog te downloaden stem: nooit");
        check(SystemSpeech.score("en", "nl", false, false, false, true, 400, 100) == -1 && SystemSpeech.score(null, "nl", false, false, false, true, 400, 100) == -1, "alleen de gevraagde taal");
        int embedded = SystemSpeech.score("nl", "nl", false, false, false, true, 100, 400), other = SystemSpeech.score("nl", "nl", false, false, false, false, 500, 0);
        check(embedded > other && other >= 0, "ingebouwde stem gaat voor, ook boven een betere andere");
        check(SystemSpeech.score("nl", "nl", false, false, false, true, 400, 100) > SystemSpeech.score("nl", "nl", false, false, false, true, 300, 100), "daarna kwaliteit");
        check(SystemSpeech.score("NL", "nl", false, false, false, false, 300, 300) >= 0, "taalcode zonder hoofdlettergevoeligheid");
        // Stukken: hele zinnen samen, nooit langer dan max
        List<String> p = SystemSpeech.parts("Een. Twee. Drie.", 10);
        check(p.size() == 2 && p.get(0).equals("Een. Twee.") && p.get(1).equals("Drie."), "zinnen samengevoegd tot max: " + p);
        StringBuilder lang = new StringBuilder(); for (int i = 0; i < 300; i++) lang.append("Zin nummer ").append(i).append(". ");
        boolean small = true; for (String s : SystemSpeech.parts(lang.toString(), 500)) if (s.length() > 500) small = false;
        check(small && SystemSpeech.parts("", 500).isEmpty() && SystemSpeech.parts(null, 500).isEmpty(), "lange tekst in stukken; lege tekst geen stukken");
        System.out.println(fails == 0 ? "Alles goed" : fails + " fout(en)");
        if (fails > 0) System.exit(1);
    }
}
