package nl.rene.tools;

/** Instellingen van de assistent: geldige waarden, tekst opschonen, wat elke keuze betekent, en de instructie voor het model. */
public class LocalAiConfigTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    public static void main(String[] a) {
        check("kort".equals(LocalAi.cfgValid("style", "kort")) && LocalAi.cfgValid("style", "lang") == null, "lengte: alleen bekende waarden");
        check(LocalAi.cfgValid("onbekend", "x") == null && LocalAi.cfgValid("power", "max") != null && LocalAi.cfgValid("idle", "15") != null, "onbekende instelling geweigerd");
        check("normaal".equals(LocalAi.cfgDefault("style")) && "nl".equals(LocalAi.cfgDefault("lang")) && "24".equals(LocalAi.cfgDefault("history")) && "4".equals(LocalAi.cfgDefault("idle")), "standaardwaarden");
        check("normaal".equals(LocalAi.cfg(null, "creative")), "zonder telefoon: de standaard");
        check("a\nb".equals(LocalAi.cleanText("  a\u0000\nb\u001b  ", 100)), "tekst: stuurtekens weg, witruimte rondom weg, regels blijven");
        String lang = new String(new char[3000]).replace('\0', 'x');
        check(LocalAi.cleanText(lang, LocalAi.TEXT_MAX).length() == 1000 && LocalAi.cfgValid("about", lang).length() == 1000, "tekst: hooguit 1000 tekens");
        check(LocalAi.maxTokens("kort") < LocalAi.maxTokens("normaal") && LocalAi.maxTokens("normaal") < LocalAi.maxTokens("uitgebreid"), "lengte: meer ruimte bij uitgebreid");
        check(LocalAi.temperature("precies") < LocalAi.temperature("normaal") && LocalAi.temperature("normaal") < LocalAi.temperature("creatief"), "creativiteit: temperatuur oplopend");
        check(LocalAi.historyFor("8") == 8 && LocalAi.historyFor("48") == 48 && LocalAi.historyFor("x") == 24, "gespreksgeheugen");
        check(LocalAi.idleMs("1") == 60_000L && LocalAi.idleMs("15") == 900_000L && LocalAi.idleMs("99") == 240_000L, "vrijgeven na");
        check(LocalAi.threadsFor("zuinig", 8) == 2 && LocalAi.threadsFor("normaal", 8) == 4 && LocalAi.threadsFor("max", 8) == 7 && LocalAi.threadsFor("max", 4) == 3 && LocalAi.threadsFor("normaal", 2) == 2, "rekenkracht: aantal kernen");
        String s = LocalAi.buildSystem("vrijdag 23 oktober 2026", "normaal", "nl", "", "");
        check(s.contains("Nederlands") && s.contains("vrijdag 23 oktober 2026") && !s.contains("Over de gebruiker"), "standaard: Nederlands, datum, niets persoonlijks");
        check(LocalAi.buildSystem("d", "kort", "en", "", "").contains("Engels") && LocalAi.buildSystem("d", "kort", "en", "", "").contains("kort"), "kort en Engels");
        check(LocalAi.buildSystem("d", "uitgebreid", "auto", "", "").contains("dezelfde taal als de vraag") && LocalAi.buildSystem("d", "uitgebreid", "auto", "", "").contains("uitgebreide"), "uitgebreid en zelfde taal");
        String p = LocalAi.buildSystem("d", "normaal", "nl", "Ik woon in Rotterdam.", "Spreek me aan met je.");
        check(p.contains("Over de gebruiker") && p.contains("Ik woon in Rotterdam.") && p.contains("Spreek me aan met je.") && p.indexOf("verzin geen feiten") < p.indexOf("Over de gebruiker"), "persoonlijk achteraan, vaste regels blijven");
        if (fails > 0) { System.out.println(fails + " fout(en)"); System.exit(1); }
        System.out.println("alles goed");
        System.exit(0);
    }
}
