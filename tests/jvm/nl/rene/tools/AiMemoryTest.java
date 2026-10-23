package nl.rene.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Geheugen van de assistent: herkennen van "onthoud"/"vergeet", wat de moeite waard is, opschonen, dubbelen, inkorten. */
public class AiMemoryTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    public static void main(String[] a) {
        // Opdrachten
        check("Ik op dinsdag sport".equals(AiMemory.rememberCmd("Onthoud dat ik op dinsdag sport.")), "onthoud dat … (het model maakt er daarna een nette zin van)");
        check("Mijn zoon heet Daan".equals(AiMemory.rememberCmd("onthoud: mijn zoon heet Daan")), "onthoud: …");
        check("I like tea".equals(AiMemory.rememberCmd("Remember that I like tea!")), "remember that …");
        check("Mijn auto is een Golf".equals(AiMemory.rememberCmd("Kun je onthouden dat mijn auto is een Golf?")), "kun je onthouden dat …");
        check(AiMemory.rememberCmd("Hoe onthoud ik beter dingen?") == null && AiMemory.rememberCmd("onthoud") == null && AiMemory.rememberCmd(null) == null, "geen opdracht: niets");
        check("Ik in Delft woon".equals(AiMemory.forgetCmd("Vergeet dat ik in Delft woon")), "vergeet dat …");
        check(AiMemory.forgetCmd("Ik vergeet altijd mijn sleutels") == null, "vergeten in een zin: geen opdracht");
        // Gewone vragen en zinnen zijn geen opdracht
        for (String q : new String[]{"Onthoud je wat ik gisteren zei?", "Onthoud jij dingen over mij?", "Onthouden is lastig voor me, tips?", "Kun je onthouden wat ik zeg?",
                "Noteer de afspraak in mijn agenda", "Remember what I said?", "Onthoud je dit?"})
            check(AiMemory.rememberCmd(q) == null, "geen onthoud-opdracht: " + q);
        for (String q : new String[]{"Vergeet niet dat ik morgen jarig ben", "Vergeet het maar", "Vergeet het maar, ik zoek het zelf op", "Vergeet je wel eens iets?", "Forget it"})
            check(AiMemory.forgetCmd(q) == null, "geen vergeet-opdracht: " + q);
        check("Mijn verjaardag is 12 mei".equals(AiMemory.rememberCmd("Onthoud mijn verjaardag is 12 mei")) && "Ik heb een kat".equals(AiMemory.rememberCmd("onthoud even: ik heb een kat")), "onthoud zonder \"dat\" werkt ook");
        check("Mijn oude adres".equals(AiMemory.forgetCmd("Vergeet mijn oude adres")), "vergeet zonder \"dat\"");
        for (String q : new String[]{"Vergeet de melk niet", "Vergeet onze afspraak van morgen niet", "Vergeet alsjeblieft niet de boodschappen", "Vergeet aub je jas niet", "Vergeet even niet de sleutels"})
            check(AiMemory.forgetCmd(q) == null, "\"niet vergeten\" is geen opdracht om te vergeten: " + q);
        for (String q : new String[]{"Onthoud alsjeblieft wat ik zei", "Remember please what I said", "Onthoud goed wat ik zeg", "Remember the milk"})
            check(AiMemory.rememberCmd(q) == null, "geen onthoud-opdracht: " + q);
        check("Ik heb een hond".equals(AiMemory.rememberCmd("Onthoud dit: ik heb een hond")) && "Ik ben vegetariër".equals(AiMemory.rememberCmd("Onthoud het volgende: ik ben vegetariër")), "onthoud dit: …");
        check("Ik in Delft woon".equals(AiMemory.forgetCmd("Kun je vergeten dat ik in Delft woon?")), "kun je vergeten dat …");
        List<String> ms = Arrays.asList("Woont in Delft.", "Heeft een zoon, Daan.", "Werkt bij ESAPE.");
        check(AiMemory.forgetIndex(ms, "ik in Delft woon") == 0 && AiMemory.forgetIndex(ms, "Daan") == 1, "vergeet: de juiste herinnering");
        check(AiMemory.forgetIndex(ms, "maar") == -1 && AiMemory.forgetIndex(ms, "mijn werk bij de bakker") == -1, "vergeet: bij twijfel niets");

        String rp = AiMemory.rephrasePrompt("Ik op dinsdag sport");
        check(rp.contains("«Ik op dinsdag sport»") && rp.contains("- "), "opdracht om er een nette zin van te maken");
        check("Sport op dinsdag.".equals(AiMemory.rephrased("Ik op dinsdag sport", "- Sport op dinsdag.")), "nette zin overgenomen");
        check(AiMemory.rephrased("Ik op dinsdag sport", "- Houdt van jazz.") == null && AiMemory.rephrased("Ik op dinsdag sport", "GEEN") == null, "iets heel anders: oorspronkelijke tekst blijft");

        // Wat de moeite is om naar te kijken
        check(AiMemory.worthy("Ik woon sinds kort in Rotterdam, wat is er leuk?"), "iets over jezelf");
        check(AiMemory.worthy("My daughter starts school next week"), "ook in het Engels");
        check(!AiMemory.worthy("Wat is de hoofdstad van Frankrijk?"), "gewone vraag: niet");
        check(!AiMemory.worthy("Vat samen: ik heb gisteren met Jan gesproken over de begroting"), "opdracht met geplakte tekst: niet");
        check(!AiMemory.worthy("ik?") && !AiMemory.worthy(null), "te kort: niet");

        // Opschonen en vergelijken
        check("Woont in Rotterdam.".equals(AiMemory.clean("  - woont in Rotterdam.\n")), "opschonen: teken weg, hoofdletter, één regel");
        check(AiMemory.clean(new String(new char[500]).replace('\0', 'x')).length() == AiMemory.TEXT, "hooguit " + AiMemory.TEXT + " tekens");
        check(AiMemory.similar("Woont in Rotterdam.", "woont in rotterdam"), "zelfde zin: dubbel");
        check(AiMemory.similar("Heeft een zoon, Daan.", "Heeft een zoon die Daan heet."), "bijna dezelfde zin: dubbel");
        check(!AiMemory.similar("Woont in Rotterdam.", "Werkt in Rotterdam."), "ander feit: geen dubbel");
        check(AiMemory.similar("Woont in Rotterdam.", "Woont in Rotterdam met zijn zoon."), "het een staat in het ander: dubbel");

        // Geheimen
        check(AiMemory.secret("Mijn wachtwoord is hond123") && AiMemory.secret("Pincode 1234") && AiMemory.secret("Rekening NL91ABNA0417164300") && AiMemory.secret("BSN 123 456 789"), "wachtwoorden, codes en nummers herkend");
        check(!AiMemory.secret("Woont in Rotterdam sinds 2019."), "jaartal is geen geheim");
        check(!AiMemory.secret("Is geboren in Libanon.") && !AiMemory.secret("Verjaardag is 12-05-1980.") && !AiMemory.secret("Heeft een Pinda-allergie"), "geen geheim: Libanon, een datum");
        check(AiMemory.secret("Code van de voordeur is 4821.") && AiMemory.secret("Alarmcode 1234") && AiMemory.secret("Telefoon 0612345678"), "codes en lange nummers wel");
        check(AiMemory.secretWord("mijn pincode is 4321") && !AiMemory.secretWord("Telefoon 0612345678") && !AiMemory.secretWord("Woont in Delft"), "op verzoek: alleen echte geheimen geweigerd");
        check(!AiMemory.secret("Werkt met Claude Code.") && !AiMemory.secret("Betaalt met pin.") && !AiMemory.secretWord("ik met Claude Code werk"), "gewone woorden zijn geen geheim (Claude Code, pin)");
        check(AiMemory.secret("Telefoon 06-12345678") && AiMemory.secret("Klantnummer 123-456-789") && AiMemory.secretWord("code is 4821"), "nummers met streepjes en een code met waarde wel");

        // Voorstellen van het model
        List<String> f = AiMemory.parseFacts("- Woont in Rotterdam\n- Heeft een zoon, Daan.\n- Wat is je naam?\n- Wachtwoord is geheim123\nOverige tekst\n- Woont in Rotterdam.\n- Sport op dinsdag.\n- Houdt van jazz.", Arrays.asList("Sport op dinsdag."));
        check(f.size() == 3 && f.get(0).equals("Woont in Rotterdam.") && f.get(1).equals("Heeft een zoon, Daan.") && f.get(2).equals("Houdt van jazz."), "voorstellen: opgeschoond, zonder vragen, geheimen of dubbelen (" + f + ")");
        check(AiMemory.parseFacts("GEEN", null).isEmpty() && AiMemory.parseFacts("- Geen", null).isEmpty() && AiMemory.parseFacts("<think>x</think>\n- niets te onthouden", null).isEmpty(), "niets te onthouden: niets");
        String lp = AiMemory.learnPrompt("Ik woon in Delft", Arrays.asList("Heeft een zoon."));
        check(lp.contains("«Ik woon in Delft»") && lp.contains("- Heeft een zoon.") && lp.contains("GEEN"), "opdracht voor het model: bericht, wat al bekend is, en hoe te antwoorden");

        // In de vaste instructie: nieuwste eerst, binnen het budget
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 100; i++) many.add("Feit nummer " + i + " over de gebruiker.");
        List<String> p = AiMemory.pick(many, AiMemory.limit(3072));
        int len = 0; for (String s : p) len += s.length() + 3;
        check(p.size() > 5 && p.size() < 100 && len <= AiMemory.limit(3072) && p.get(0).equals(many.get(0)), "inkorten tot het budget, nieuwste eerst");
        check(AiMemory.limit(4096) > AiMemory.limit(3072) && AiMemory.limit(3072) > AiMemory.limit(2048), "meer geheugen bij een groter model");
        String sys = LocalAi.buildSystem("d", "normaal", "nl", "", "", Arrays.asList("Woont in Rotterdam."), "");
        check(sys.contains("onthouden") && sys.contains("- Woont in Rotterdam.") && sys.contains("Geheugen"), "herinneringen in de vaste instructie, met waar je ze ziet");
        check(!LocalAi.buildSystem("d", "normaal", "nl", "", "", new ArrayList<String>(), "").contains("onthouden"), "zonder herinneringen: niets");
        check(LocalAi.memNote("Sport op dinsdag", null, false, false).contains("\"Sport op dinsdag\""), "melding na onthouden");
        check(LocalAi.memNote(null, "Woont in Delft.", true, false).contains("niet meer") && LocalAi.memNote(null, "", true, false).contains("niets over"), "melding na vergeten (wel of niet gevonden)");
        check(LocalAi.memNote("x", null, false, true).contains("staat uit") && LocalAi.memNote(null, null, false, false).isEmpty(), "geheugen uit: dat zeggen; anders niets");
        check(!LocalAi.sideTooBig(3000, 160, 4096) && LocalAi.sideTooBig(12000, 160, 4096), "leren overslaan als het niet in het geheugen van het model past");
        List<String[]> all = new ArrayList<>();
        all.add(new String[]{"b", "B."}); all.add(new String[]{"a", "A."});
        List<String> f1 = LocalAi.memFor("c1", all, 1000);
        all.add(0, new String[]{"n", "Nieuw."}); // er komt iets bij (nieuwste eerst)
        List<String> f2 = LocalAi.memFor("c1", all, 1000);
        check(f1.equals(f2) && f2.equals(Arrays.asList("B.", "A.")), "per gesprek dezelfde herinneringen (de motor kan hergebruiken)");
        all.get(1)[1] = "B anders.";
        check(LocalAi.memFor("c1", all, 1000).equals(Arrays.asList("B anders.", "A.")), "aangepast: de nieuwe tekst");
        all.remove(2);
        check(LocalAi.memFor("c1", all, 1000).equals(Arrays.asList("B anders.")), "wat weg is, gaat er wel uit");
        check(LocalAi.memFor("c1", all, 400).get(0).equals("Nieuw."), "ander model (ander budget): opnieuw gekozen");
        check(LocalAi.memFor("c2", all, 1000).get(0).equals("Nieuw."), "een nieuw gesprek: de nieuwste");

        if (fails > 0) { System.out.println(fails + " fout(en)"); System.exit(1); }
        System.out.println("alles goed");
        System.exit(0);
    }
}
