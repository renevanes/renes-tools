package nl.rene.tools;

/** Opdrachten aan de assistent: herkennen en nakijken (duur, tijd, nummer, tool), zonder dat er iets gebeurt. */
public class AiCommandsTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }
    /** Contacten zoals in de app (AiBridge kijkt in je eigen contacten). */
    static final AiCommands.Known KNOWN = n -> n != null && java.util.Arrays.asList("jos", "marieke", "piet").contains(n.toLowerCase(java.util.Locale.ROOT));
    static AiCommands.Cmd p(String q) { return AiCommands.parse(q, KNOWN); }

    public static void main(String[] args) {
        // Duur
        check(AiCommands.duration("10 minuten") == 600 && AiCommands.duration("tien minuten") == 600 && AiCommands.duration("1 minuut") == 60, "10 minuten, tien minuten, 1 minuut");
        check(AiCommands.duration("anderhalf uur") == 5400 && AiCommands.duration("een half uur") == 1800 && AiCommands.duration("een kwartier") == 900, "anderhalf uur, een half uur, een kwartier");
        check(AiCommands.duration("1 uur en 20 minuten") == 4800 && AiCommands.duration("90 seconden") == 90 && AiCommands.duration("2,5 uur") == 9000, "1 uur en 20 minuten, 90 seconden, 2,5 uur");
        check(AiCommands.duration("twee en een half uur") == 9000 && AiCommands.duration("vijfentwintig minuten") == 1500 && AiCommands.duration("drie kwartier") == 2700, "twee en een half uur, vijfentwintig minuten, drie kwartier");
        check(AiCommands.duration("geen tijd") == -1 && AiCommands.duration(null) == -1, "geen duur: -1");
        check(AiCommands.validSecs(1) && AiCommands.validSecs(86400) && !AiCommands.validSecs(0) && !AiCommands.validSecs(86401), "timer: 1 s tot 24 uur");

        // Kloktijd voor een wekker (zonder dagdeel: 's ochtends)
        check(AiCommands.clock("om 7 uur") == 420 && AiCommands.clock("om 6 uur") == 360 && AiCommands.clock("07:30") == 450, "7 uur, 6 uur (ochtend), 07:30");
        check(AiCommands.clock("half 8") == 450 && AiCommands.clock("kwart over 6") == 375 && AiCommands.clock("kwart voor 7") == 405, "half 8, kwart over 6, kwart voor 7");
        check(AiCommands.clock("om 25 over 7") == 445 && AiCommands.clock("tien over half acht") == 460 && AiCommands.clock("vijf voor acht") == 475 && AiCommands.clock("5 voor half 8") == 445, "25 over 7, tien over half acht, vijf voor acht, 5 voor half 8");
        check(AiCommands.clock("vijf over 12") == 725 && AiCommands.clock("tien voor 1") == 50 && AiCommands.clock("tien over half 9 's avonds") == 1240, "vijf over 12, tien voor 1, 's avonds");
        check(AiCommands.clock("om 8 uur 's avonds") == 1200 && AiCommands.clock("om half 1") == 30 && AiCommands.clock("om 25 uur") == -1 && AiCommands.clock("geen") == -1, "'s avonds, half 1, ongeldig");
        check(AiCommands.validClock(0) && AiCommands.validClock(1439) && !AiCommands.validClock(1440) && !AiCommands.validClock(-1), "geldige tijd");

        // Telefoonnummers
        check("0612345678".equals(AiCommands.number("06-12345678")) && "+31612345678".equals(AiCommands.number("+31 6 1234 5678")) && "+31612345678".equals(AiCommands.number("0031612345678")), "nummers zonder opmaak");
        check(AiCommands.number("Jos").isEmpty() && AiCommands.number("12").isEmpty() && AiCommands.number("112").isEmpty() && AiCommands.number("0612abc").isEmpty() && AiCommands.number(null).isEmpty(), "geen nummer, te kort, alarmnummer, letters");
        check(AiCommands.number("+31112").isEmpty() && AiCommands.number("+310112").isEmpty() && AiCommands.number("0112").isEmpty() && AiCommands.number("+1911").isEmpty() && AiCommands.number("+44999").isEmpty() && AiCommands.number("00 31 112").isEmpty(), "alarmnummers, ook met landnummer of 0");
        check(AiCommands.number("14010").isEmpty() && AiCommands.number("1233").isEmpty() && "123456".equals(AiCommands.number("123456")), "korter dan 6 cijfers: geen nummer");
        check("31612345678".equals(AiCommands.waNumber("06 12345678")) && "31612345678".equals(AiCommands.waNumber("+31612345678")) && AiCommands.waNumber("x").isEmpty(), "WhatsApp: met landnummer");

        // Tools
        check(AiCommands.validTool("apparaten") && AiCommands.validTool("sleep") && !AiCommands.validTool("evil") && !AiCommands.validTool(null), "alleen bekende tools");
        check("apparaten".equals(AiCommands.toolFor("Apparaten")) && "sleep".equals(AiCommands.toolFor("slaappraat")) && AiCommands.toolFor("garagedeur").isEmpty(), "naam → tool");

        // Timer
        AiCommands.Cmd c = p("Zet een timer van 10 minuten");
        check(c != null && "timer".equals(c.type) && c.secs == 600 && c.need.isEmpty(), "zet een timer van 10 minuten");
        c = p("Kun je even een timer zetten voor 1 uur en 20 minuten alsjeblieft?");
        check(c != null && "timer".equals(c.type) && c.secs == 4800, "beleefd: timer 1 uur en 20 minuten");
        c = p("timer van vijf minuten voor de eieren");
        check(c != null && c.secs == 300 && "Eieren".equals(c.label), "timer met naam: eieren");
        c = p("zet een timer");
        check(c != null && "timer".equals(c.type) && "duur".equals(c.need), "timer zonder duur: zelf kiezen");
        c = p("wek me over 20 minuten");
        check(c != null && "timer".equals(c.type) && c.secs == 1200 && "Wekker".equals(c.label), "wek me over 20 minuten = timer");
        check(p("zet een timer van 30 uur") == null, "timer langer dan 24 uur: geen opdracht");
        c = p("zet een wekker om 25 over 7");
        check(c != null && "wekker".equals(c.type) && c.min == 445, "wekker om 25 over 7: een wekker, geen timer");
        c = p("zet een wekker om tien over half acht");
        check(c != null && "wekker".equals(c.type) && c.min == 460, "wekker om tien over half acht: 07:40");
        c = p("wek me over een kwartier");
        check(c != null && "timer".equals(c.type) && c.secs == 900, "wek me over een kwartier = timer");
        c = p("zet een wekker over 2 uur");
        check(c != null && "timer".equals(c.type) && c.secs == 7200, "wekker over 2 uur = timer");
        for (String q : new String[]{"zet de timer van het scherm op 5 minuten", "een timer is een handig ding", "timer van de oven werkt niet meer",
                "start een timer voor het leven", "mijn timer gaat niet af", "de kookwekker doet het niet", "een timer van 5 minuten is te kort voor eieren"})
            check(p(q) == null, "geen timer: \"" + q + "\"");
        c = p("start een timer");
        check(c != null && "timer".equals(c.type) && "duur".equals(c.need), "start een timer: zelf de duur kiezen");
        check(p("Hoeveel tijd staat er nog op de timer?") == null && p("stop de timer") == null, "vraag of stoppen: geen opdracht");

        // Wekker in de Klok-app; "de wekker" = de radiowekker (een instelling)
        c = p("Zet een wekker om 7 uur");
        check(c != null && "wekker".equals(c.type) && c.min == 420 && !c.radio, "zet een wekker om 7 uur");
        c = p("wek me morgen om half 7");
        check(c != null && c.min == 390 && "morgen".equals(c.dayWord), "wek me morgen om half 7");
        c = p("wek me om 7 uur met de radio");
        check(c != null && "wekker".equals(c.type) && c.radio && c.min == 420, "met de radio: radiowekker");
        check(p("zet de wekker op 6 uur") == null && p("zet de wekker aan") == null, "de wekker: blijft een instelling (radiowekker)");
        c = p("zet een wekker");
        check(c != null && "tijd".equals(c.need), "wekker zonder tijd: zelf kiezen");

        // Radio en podcasts
        c = p("speel radio 538");
        check(c != null && "radio".equals(c.type) && "538".equals(c.q), "speel radio 538");
        c = p("zet de radio aan");
        check(c != null && "radio".equals(c.type) && c.q.isEmpty() && !c.stop, "zet de radio aan: de laatste zender");
        c = p("Zet NPO Radio 2 aan");
        check(c != null && "radio".equals(c.type) && "NPO Radio 2".equals(c.q), "zet NPO Radio 2 aan");
        c = p("zet de radio uit");
        check(c != null && "radio".equals(c.type) && c.stop, "zet de radio uit");
        for (String q : new String[]{"zet de radio harder", "zet de radio zachter", "zet de radio uit om 23 uur", "zet de sleeptimer van de radio op 30 minuten",
                "zet radio 538 harder", "zet NPO Radio 2 zachter", "speel de radio af om half 8"})
            check(p(q) == null, "geen radio aanzetten: \"" + q + "\"");
        check(p("luister naar me") == null && p("luister naar mij") == null && p("speel de") == null, "luister naar me, speel de: niets");
        c = p("speel de podcast NRC Vandaag");
        check(c != null && "podcast".equals(c.type) && "NRC Vandaag".equals(c.q) && !c.newest, "speel de podcast NRC Vandaag");
        c = p("speel de nieuwste aflevering van De Dag");
        check(c != null && "podcast".equals(c.type) && c.newest && "De Dag".equals(c.q), "nieuwste aflevering van De Dag");
        c = p("luister verder naar de podcast");
        check(c != null && "podcast".equals(c.type) && c.q.isEmpty(), "verder met de podcast");
        c = p("speel Met het Oog op Morgen");
        check(c != null && "speel".equals(c.type) && "Met het Oog op Morgen".equals(c.q), "speel …: zender of podcast (de interface zoekt)");

        // Slaappraat
        check(p("start slaappraat") != null && "slaap".equals(p("start slaappraat").type) && !p("start slaappraat").stop, "start slaappraat");
        check(p("zet slaappraat uit").stop && p("stop slaappraat").stop && !p("zet slaappraat aan").stop, "slaappraat aan / uit / stop");
        check(p("zet de herinnering van slaappraat om 22 uur") == null, "een instelling van Slaappraat: geen opdracht");

        // Tool openen
        c = p("open Apparaten");
        check(c != null && "open".equals(c.type) && "apparaten".equals(c.tool), "open Apparaten");
        check("checkin".equals(p("ga naar check-in").tool) && "notes".equals(p("laat mijn notities zien").tool), "ga naar check-in, laat mijn notities zien");
        check(p("open de garagedeur") == null, "onbekende tool: geen opdracht");

        // Berichten (alleen klaarzetten)
        c = p("Stuur een sms naar Jos dat ik later ben");
        check(c != null && "sms".equals(c.type) && "Jos".equals(c.to) && "Ik ben later".equals(c.text), "sms naar Jos dat ik later ben → \"Ik ben later\"");
        c = p("stuur Jos een appje: Hoi, ik kom eraan!");
        check(c != null && "wa".equals(c.type) && "Jos".equals(c.to) && "Hoi, ik kom eraan!".equals(c.text), "appje met dubbele punt: tekst blijft precies");
        for (String q : new String[]{"vertel me een grap over dat boek", "vertel eens dat verhaal", "laat zien dat je het snapt", "laat maar weten dat het goed is",
                "zeg tegen jezelf dat het goed komt", "vertel Henk dat ik later ben", "app Henk dat ik later ben", "stuur Henk een appje: hoi"})
            check(p(q) == null, "geen bericht (geen contact): \"" + q + "\"");
        check(AiCommands.parse("app Marieke dat we eten", null) == null && AiCommands.parse("laat Jos weten dat ik kom", null) == null, "zonder contacten na te kijken: geen bericht met alleen een naam");
        c = AiCommands.parse("stuur een sms naar Henk dat ik later ben", null);
        check(c != null && "sms".equals(c.type) && "Henk".equals(c.to), "stuur een sms naar …: altijd een opdracht (de kaart zoekt het nummer)");
        c = p("vertel Jos dat ik later ben");
        check(c != null && "sms".equals(c.type) && "Jos".equals(c.to) && "Ik ben later".equals(c.text), "vertel Jos dat …: wel, Jos staat in je contacten");
        c = p("app Marieke dat we om zes uur eten");
        check(c != null && "wa".equals(c.type) && "We eten om zes uur".equals(c.text), "app Marieke dat we om zes uur eten");
        c = p("stuur een berichtje naar 06-12345678: bel me even");
        check(c != null && "sms".equals(c.type) && "0612345678".equals(c.number) && c.to.isEmpty() && "bel me even".equals(c.text), "naar een nummer");
        c = p("laat Jos weten dat hij moet bellen via whatsapp");
        check(c != null && "wa".equals(c.type) && "Hij moet bellen".equals(c.text), "laat … weten via whatsapp");
        c = p("stuur een sms naar Jos");
        check(c != null && "tekst".equals(c.need) && c.text.isEmpty(), "zonder tekst: zelf invullen");
        check(p("whatsapp backup") == null && p("stuur een sms naar 112: hulp") == null, "geen bericht: whatsapp backup; nooit naar 112");
        check(AiCommands.parse("stuur een sms naar Jos: " + new String(new char[1200]).replace('\0', 'a')) == null, "te lange vraag: geen opdracht");
        check("Ik ben het niet vergeten".equals(AiCommands.mainClause("ik het niet vergeten ben")) && "Het gaat goed".equals(AiCommands.mainClause("het goed gaat"))
                && "Morgen kan niet".equals(AiCommands.mainClause("morgen kan niet")), "dat-zin naar een gewone zin");

        // Gewone vragen en andere functies blijven ongemoeid
        check(p("Wat is de hoofdstad van Frankrijk?") == null && p("Herinner me morgen om 9 uur aan de vuilnis") == null && p("Onthoud dat ik in Rotterdam woon") == null, "gewone vraag, herinnering, onthouden: geen opdracht");
        check(p("zet de nachtelijke backup aan") == null && p("Vat deze tekst samen") == null, "instelling of tekstopdracht: geen opdracht");

        System.out.println(fails == 0 ? "\nAlles goed" : "\n" + fails + " mislukt");
        if (fails > 0) System.exit(1);
    }
}
