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

        if (fails > 0) { System.out.println(fails + " fout(en)"); System.exit(1); }
        System.out.println("alles goed");
        System.exit(0);
    }
}
