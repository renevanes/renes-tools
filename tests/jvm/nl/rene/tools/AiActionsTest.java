package nl.rene.tools;

import java.util.Calendar;
import java.util.TimeZone;

/** Acties van de assistent: herkennen, de regel van het model lezen, datums en tijden uit gewone taal, en voorlezen. */
public class AiActionsTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    static final TimeZone TZ = TimeZone.getTimeZone("Europe/Amsterdam");
    static long t(int y, int mo, int d, int h, int mi) { Calendar c = Calendar.getInstance(TZ); c.clear(); c.set(y, mo - 1, d, h, mi, 0); return c.getTimeInMillis(); }
    static String fmt(long ms) { Calendar c = Calendar.getInstance(TZ); c.setTimeInMillis(ms); return String.format("%04d-%02d-%02d %02d:%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)); }

    public static void main(String[] args) {
        // Zaterdag 24 oktober 2026, 14:05 (de nacht erna gaat de klok een uur terug)
        long now = t(2026, 10, 24, 14, 5);

        // Wanneer krijgt het model de opdracht mee
        check(AiActions.wanted("Herinner me morgen om 9 uur aan de vuilnis"), "herinner me … = actie");
        check(AiActions.wanted("Maak een notitie: boodschappen melk en brood") && AiActions.wanted("noteer even dat de monteur dinsdag komt"), "notitie / noteer = actie");
        check(AiActions.wanted("Zet de tandarts vrijdag om 10 uur in mijn agenda"), "agenda = actie");
        check(!AiActions.wanted("Wat is de hoofdstad van Frankrijk?") && !AiActions.wanted(null) && !AiActions.wanted(""), "gewone vraag: geen actie");
        check(!AiActions.wanted("Onthoud dat ik op dinsdag sport") && !AiActions.wanted("Vergeet dat ik in Delft woon"), "onthoud/vergeet = geheugen, geen actie");
        StringBuilder lang = new StringBuilder("Vat samen: ");
        for (int i = 0; i < 80; i++) lang.append("we hebben een afspraak in de agenda gezet. ");
        check(!AiActions.wanted(lang.toString()), "lange geplakte tekst: geen actie");

        String p = AiActions.prompt(now, TZ);
        check(p.contains("zaterdag 24 oktober 2026, 14:05") && p.contains("morgen zondag 2026-10-25") && p.contains("overmorgen maandag 2026-10-26") && p.contains("vrijdag 2026-10-30"), "opdracht met vandaag, morgen en de komende dagen");
        check(p.contains("[[herinnering: JJJJ-MM-DD UU:MM |") && p.contains("[[notitie:") && p.contains("[[agenda:"), "opdracht met de drie regels");

        // Datum en tijd uit gewone taal
        AiActions.When w = AiActions.when("morgen om 9 uur", now, TZ);
        check(w.day == t(2026, 10, 25, 0, 0) && w.min == 9 * 60, "morgen om 9 uur");
        check(AiActions.when("overmorgen om half 3", now, TZ).day == t(2026, 10, 26, 0, 0) && AiActions.when("overmorgen om half 3", now, TZ).min == 14 * 60 + 30, "overmorgen om half 3 = 14:30");
        check(AiActions.when("vanavond om 8", now, TZ).min == 20 * 60 && AiActions.when("vanavond om 8", now, TZ).day == t(2026, 10, 24, 0, 0), "vanavond om 8 = 20:00 vandaag");
        check(AiActions.when("om 7 uur 's ochtends", now, TZ).min == 7 * 60 && AiActions.when("om 8 uur 's avonds", now, TZ).min == 20 * 60, "'s ochtends en 's avonds");
        check(AiActions.when("kwart over 3", now, TZ).min == 15 * 60 + 15 && AiActions.when("kwart voor 9", now, TZ).min == 8 * 60 + 45, "kwart over / kwart voor (niet: over 3 uur)");
        check(AiActions.when("om 09:00", now, TZ).min == 9 * 60 && AiActions.when("14.30 uur", now, TZ).min == 14 * 60 + 30, "09:00 en 14.30 uur");
        check(AiActions.when("zaterdag", now, TZ).day == t(2026, 10, 31, 0, 0), "zaterdag op een zaterdag = volgende week");
        check(AiActions.when("dinsdag", now, TZ).day == t(2026, 10, 27, 0, 0) && AiActions.when("aanstaande vrijdag", now, TZ).day == t(2026, 10, 30, 0, 0), "dinsdag, aanstaande vrijdag");
        check(AiActions.when("volgende week dinsdag", now, TZ).day == t(2026, 10, 27, 0, 0), "volgende week dinsdag (op zaterdag: de week die maandag begint)");
        long mon = t(2026, 10, 26, 10, 0);
        check(AiActions.when("volgende week dinsdag", mon, TZ).day == t(2026, 11, 3, 0, 0) && AiActions.when("dinsdag", mon, TZ).day == t(2026, 10, 27, 0, 0), "op maandag: dinsdag = morgen, volgende week dinsdag = over 8 dagen");
        check(AiActions.when("25 okt", now, TZ).day == t(2026, 10, 25, 0, 0) && AiActions.when("3 januari", now, TZ).day == t(2027, 1, 3, 0, 0), "25 okt; 3 januari (voorbij: volgend jaar)");
        check(AiActions.when("op 2-11 om 10 uur", now, TZ).day == t(2026, 11, 2, 0, 0) && AiActions.when("2026-12-01", now, TZ).day == t(2026, 12, 1, 0, 0), "2-11 en 2026-12-01");
        check(AiActions.when("31-02", now, TZ).day < 0, "31 februari bestaat niet");
        check(AiActions.when("over 2 uur", now, TZ).abs == now + 2 * 3_600_000L && AiActions.when("over een kwartier", now, TZ).abs == now + 15 * 60_000L && AiActions.when("over een half uur", now, TZ).abs == now + 30 * 60_000L, "over 2 uur / een kwartier / een half uur");
        check(AiActions.when("over 3 dagen", now, TZ).day == t(2026, 10, 27, 0, 0), "over 3 dagen");
        check(AiActions.when("morgenochtend", now, TZ).sug == 9 * 60 && AiActions.when("morgenochtend", now, TZ).min < 0, "morgenochtend: dag, tijd nog kiezen (voorstel 9:00)");
        w = AiActions.when("morgen om 9 uur aan de vuilnis", now, TZ);
        check(w.rest.equals("aan de vuilnis"), "rest zonder datum en tijd: " + w.rest);
        check(!AiActions.when("wat is de hoofdstad van Frankrijk", now, TZ).any(), "geen datum in een gewone zin");
        // Bereiken en bedragen zijn geen datum of tijd
        w = AiActions.when("morgen tussen 9-10 uur", now, TZ);
        check(w.day == t(2026, 10, 25, 0, 0) && w.min == 9 * 60, "morgen tussen 9-10 uur = morgen 9:00, niet 9 oktober");
        w = AiActions.when("bel tussen 9-10", now, TZ);
        check(w.day < 0 && w.min == 9 * 60, "bel tussen 9-10 = 9:00 (geen datum)");
        w = AiActions.when("van 9-10 uur", now, TZ);
        check(w.day < 0 && w.min == 9 * 60, "9-10 uur = 9:00");
        check(AiActions.when("24/7 bereikbaar", now, TZ).day < 0, "24/7 is geen datum");
        check(AiActions.when("zaterdag 2-11", now, TZ).day == t(2026, 10, 31, 0, 0), "dagnaam gaat voor een dag-maand zonder jaar");
        check(AiActions.when("op 2-11-2026", now, TZ).day == t(2026, 11, 2, 0, 0), "dag-maand met jaar blijft een datum");
        w = AiActions.when("het kost 2.50 euro", now, TZ);
        check(w.min < 0, "2.50 euro is geen tijd");
        check(AiActions.when("€ 9.95 en om 14.30", now, TZ).min == 14 * 60 + 30, "bedrag overgeslagen, de tijd erna telt");
        // anderhalf
        check(AiActions.when("over anderhalf uur", now, TZ).abs == now + 90 * 60_000L, "over anderhalf uur = 90 minuten");
        check(AiActions.when("over anderhalve dag", now, TZ).abs == now + 36 * 3_600_000L, "over anderhalve dag = 36 uur");
        check(AiActions.when("over anderhalve week", now, TZ).day == t(2026, 11, 4, 0, 0), "over anderhalve week = over 11 dagen");
        check(AiActions.when("over een half uur", now, TZ).abs == now + 30 * 60_000L, "over een half uur blijft 30 minuten");
        // 's nachts
        check(AiActions.when("om 11 uur 's nachts", now, TZ).min == 23 * 60, "11 uur 's nachts = 23:00");
        check(AiActions.when("om 12 uur 's nachts", now, TZ).min == 0, "12 uur 's nachts = 00:00");
        check(AiActions.when("kwart voor 12 's nachts", now, TZ).min == 23 * 60 + 45, "kwart voor 12 's nachts = 23:45");
        check(AiActions.when("om 2 uur 's nachts", now, TZ).min == 2 * 60, "2 uur 's nachts = 02:00");
        w = AiActions.when("vannacht om 2 uur", now, TZ);
        check(w.day == t(2026, 10, 25, 0, 0) && w.min == 2 * 60, "vannacht om 2 uur ('s middags gevraagd) = morgen 02:00");
        // half en kwart voor: de middagregel op het uur zelf, net als "om 6"
        check(AiActions.when("om half 7", now, TZ).min == 18 * 60 + 30 && AiActions.when("om 6", now, TZ).min == 18 * 60, "half 7 = 18:30, om 6 = 18:00");
        check(AiActions.when("half 1", now, TZ).min == 12 * 60 + 30 && AiActions.when("half 10", now, TZ).min == 9 * 60 + 30, "half 1 = 12:30, half 10 = 9:30");
        check(AiActions.when("kwart voor 7", now, TZ).min == 18 * 60 + 45 && AiActions.when("half 8 's ochtends", now, TZ).min == 7 * 60 + 30, "kwart voor 7 = 18:45; half 8 's ochtends = 7:30");
        // Na de klokwissel (25 oktober): 9 uur blijft 9 uur
        check(fmt(AiActions.at(t(2026, 10, 25, 0, 0), 9 * 60, TZ)).equals("2026-10-25 09:00"), "klokwissel: 9:00 blijft 9:00");

        // De regel van het model
        String ans = "Prima, ik zet een herinnering voor je.\n[[herinnering: 2026-10-25 09:00 | Jos bellen]]";
        check(AiActions.strip(ans).equals("Prima, ik zet een herinnering voor je."), "regel uit het antwoord gehaald");
        AiActions.Act a = AiActions.parse(ans, "Herinner me eraan Jos te bellen", now, TZ);
        check(a != null && "herinnering".equals(a.type) && "Jos bellen".equals(a.title) && "2026-10-25 09:00".equals(fmt(a.at)) && a.need.isEmpty(), "herinnering met moment van het model");
        // Het model rekent fout, de vraag zegt "morgen om 9 uur": de vraag wint
        a = AiActions.parse("Goed.\n[[herinnering: 2026-10-26 10:00 | de vuilnis buiten zetten]]", "Herinner me morgen om 9 uur aan de vuilnis", now, TZ);
        check(a != null && "2026-10-25 09:00".equals(fmt(a.at)) && "De vuilnis buiten zetten".equals(a.title), "vraag gaat voor het model: " + (a == null ? "null" : fmt(a.at)));
        // Alleen een dag in de vraag, tijd van het model op dezelfde dag
        a = AiActions.parse("[[herinnering: 2026-10-27 08:30 | Pakket ophalen]]", "Herinner me dinsdag aan het pakket", now, TZ);
        check(a != null && "2026-10-27 08:30".equals(fmt(a.at)), "dag uit de vraag, tijd van het model");
        // Alleen een dag, model noemt een andere dag: tijd zelf kiezen
        a = AiActions.parse("[[herinnering: 2026-10-28 08:30 | Pakket ophalen]]", "Herinner me dinsdag aan het pakket", now, TZ);
        check(a != null && a.at == 0 && "tijd".equals(a.need) && a.day == t(2026, 10, 27, 0, 0), "andere dag van het model: tijd zelf kiezen");
        // Geen moment: kiezen
        a = AiActions.parse("[[herinnering:  | Jos bellen]]", "Herinner me eraan Jos te bellen", now, TZ);
        check(a != null && a.at == 0 && "datum".equals(a.need), "leeg moment: zelf kiezen");
        a = AiActions.parse("[[herinnering: JJJJ-MM-DD UU:MM | Jos bellen]]", "Herinner me eraan Jos te bellen", now, TZ);
        check(a != null && "datum".equals(a.need), "voorbeeldtekst als moment: zelf kiezen");
        // Voorbij of te ver weg
        a = AiActions.parse("[[herinnering: 2025-01-01 09:00 | Oud]]", "herinner me hieraan", now, TZ);
        check(a != null && a.at == 0 && "datum".equals(a.need), "voorbij: zelf kiezen");
        a = AiActions.parse("[[herinnering: 2031-01-01 09:00 | Ver]]", "herinner me hieraan", now, TZ);
        check(a != null && a.at == 0 && "datum".equals(a.need), "meer dan twee jaar vooruit: zelf kiezen");
        a = AiActions.parse("[[herinnering: 2026-10-24 09:00 | Vanochtend]]", "herinner me vandaag om 9 uur", now, TZ);
        check(a != null && a.at == 0 && "datum".equals(a.need), "vandaag 9:00 terwijl het al 14:05 is: zelf kiezen");
        // Alleen een tijd: vandaag als dat nog kan, anders morgen
        a = AiActions.parse("", "Herinner me om 16:00 aan de was", now, TZ);
        check(a != null && "2026-10-24 16:00".equals(fmt(a.at)) && "De was".equals(a.title) && "vraag".equals(a.src), "zonder regel: uit de vraag, vandaag 16:00");
        a = AiActions.parse("", "Herinner me om 10 uur aan de was", now, TZ);
        check(a != null && "2026-10-25 10:00".equals(fmt(a.at)), "10 uur is al voorbij: morgen");
        a = AiActions.parse("", "herinner me over 2 uur aan de oven", now, TZ);
        check(a != null && a.at == now + 2 * 3_600_000L && "De oven".equals(a.title), "over 2 uur");
        a = AiActions.parse("", "Kun je me eraan herinneren dat ik zaterdag om 10 uur naar de markt ga?", now, TZ);
        check(a != null && "2026-10-31 10:00".equals(fmt(a.at)) && a.title.startsWith("Ik") && a.title.contains("markt"), "kun je me eraan herinneren dat …: " + (a == null ? "null" : a.title));
        // Notities
        a = AiActions.parse("Hier is je notitie.\n[[notitie: Boodschappen | melk, brood en kaas]]", "Maak een notitie van de boodschappen: melk, brood en kaas", now, TZ);
        check(a != null && "notitie".equals(a.type) && "Boodschappen".equals(a.title) && "melk, brood en kaas".equals(a.text) && a.at == 0, "notitie met titel en tekst");
        a = AiActions.parse("", "Noteer: de monteur komt dinsdag", now, TZ);
        check(a != null && "notitie".equals(a.type) && "De monteur komt dinsdag".equals(a.title), "noteer zonder regel: uit de vraag");
        // Agenda
        a = AiActions.parse("[[agenda: 2026-10-30 10:00 | Tandarts]]", "Zet de tandarts vrijdag om 10 uur in mijn agenda", now, TZ);
        check(a != null && "agenda".equals(a.type) && "Tandarts".equals(a.title) && "2026-10-30 10:00".equals(fmt(a.at)) && !a.allDay, "agenda met tijd");
        a = AiActions.parse("[[agenda: 2026-10-30 | Verjaardag Daan]]", "Zet de verjaardag van Daan vrijdag in mijn agenda", now, TZ);
        check(a != null && a.allDay && a.day == t(2026, 10, 30, 0, 0) && a.need.isEmpty(), "agenda zonder tijd: hele dag");
        a = AiActions.parse("", "Zet de tandarts vrijdag om 10 uur in mijn agenda", now, TZ);
        check(a != null && "agenda".equals(a.type) && "De tandarts".equals(a.title) && "2026-10-30 10:00".equals(fmt(a.at)), "agenda uit de vraag: " + (a == null ? "null" : a.title));
        // Rommelige regels van kleine modellen
        a = AiActions.parse("Oké!\n`[[Herinnering: 2026-10-25 09:00 Jos bellen]]`", "herinner me morgen om 9 uur aan Jos", now, TZ);
        check(a != null && "Jos bellen".equals(a.title) && "2026-10-25 09:00".equals(fmt(a.at)), "regel zonder | en met backticks");
        check(AiActions.strip("Oké!\n`[[Herinnering: 2026-10-25 09:00 Jos bellen]]`").equals("Oké!"), "ook die regel weg");
        a = AiActions.parse("[[herinnering: 2026-10-25 09:00 | Jos bellen]]\nTot morgen!", "herinner me morgen om 9 uur aan Jos", now, TZ);
        check(a != null && AiActions.strip("[[herinnering: 2026-10-25 09:00 | Jos bellen]]\nTot morgen!").equals("Tot morgen!"), "regel niet aan het eind");
        check(AiActions.parse("Gewoon een antwoord zonder regel.", "Wat is 2+2?", now, TZ) == null, "geen regel, geen opdracht: niets");
        check(AiActions.strip("Zie [1] en [2].").equals("Zie [1] en [2]."), "verwijzingen [1] blijven staan");
        check(AiActions.strip("Zie [notitie: x].").equals("Zie [notitie: x]."), "één blokhaak is geen actieregel");

        // Wanneer acties en leren mogen (LocalAi)
        check(LocalAi.actAllowed(false, false, null, null, "Herinner me morgen aan Jos"), "actie: gevraagd");
        check(!LocalAi.actAllowed(true, false, null, null, "Herinner me morgen aan Jos") && !LocalAi.actAllowed(false, true, null, null, "Herinner me morgen aan Jos"), "geen actie met noAct of in een gesprek met een bijlage (samenvatting)");
        check(!LocalAi.actAllowed(false, false, "x", null, "Herinner me aan x") && !LocalAi.actAllowed(false, false, null, null, "Wat is 2+2?"), "geen actie bij onthoud of een gewone vraag");
        String me = "Ik woon sinds kort in Rotterdam, wat is er leuk?";
        check(LocalAi.learnAllowed(true, false, false, false, null, null, "vragen", me), "leren: iets over jezelf");
        check(!LocalAi.learnAllowed(true, false, true, false, null, null, "vragen", me) && !LocalAi.learnAllowed(true, true, false, false, null, null, "vragen", me), "niet leren met berichten van anderen (bijlage of noLearn)");
        check(!LocalAi.learnAllowed(true, false, false, true, null, null, "vragen", me) && !LocalAi.learnAllowed(true, false, false, false, null, null, "uit", me) && !LocalAi.learnAllowed(false, false, false, false, null, null, "vragen", me), "niet leren bij een actie, leren uit of geheugen uit");
        // De bijlage passend maken: opdracht blijft, de nieuwste regels blijven
        StringBuilder att = new StringBuilder("Vat dit sms-gesprek samen.\n\nHet gesprek, van oud naar nieuw:\n");
        for (int i = 0; i < 500; i++) att.append("12:00 Jos: bericht ").append(i).append('\n');
        String fit = LocalAi.fitAttachment(att.toString().trim(), 3000);
        check(fit.length() <= 3000 && fit.startsWith("Vat dit sms-gesprek samen.\n\nHet gesprek, van oud naar nieuw:\n(het begin") && fit.endsWith("bericht 499") && !fit.contains("bericht 0\n") && fit.contains("\n12:00 Jos: bericht"), "bijlage ingekort: opdracht en de nieuwste berichten, op een regelgrens");
        check(LocalAi.fitAttachment("kort", 3000).equals("kort"), "past al: ongewijzigd");
        check(LocalAi.attBudget(4096, 700, 1500, 3) > 5000 && LocalAi.attBudget(3072, 700, 1500, 3) < LocalAi.attBudget(4096, 700, 1500, 3) && LocalAi.attBudget(1024, 700, 9000, 9) == 600, "ruimte voor de bijlage hangt af van model, antwoord en de rest");

        // Voorlezen: opmaak weg
        String sp = AiSpeech.plain("## Kop\n**Vet** en *schuin* met `code` [1].\n- Eén\n- Twee\n1. Drie");
        check(sp.equals("Kop.\nVet en schuin met code.\nEén.\nTwee.\nDrie."), "voorlezen zonder opmaak: " + sp.replace("\n", "⏎"));
        java.util.List<String> parts = AiSpeech.chunks(new String(new char[9000]).replace('\0', 'a').replaceAll("(a{99})a", "$1. "), 3900);
        boolean small = true; for (String s : parts) if (s.length() > 3900) small = false;
        check(parts.size() >= 3 && small, "lange tekst in stukken voor de spraakmotor");

        System.out.println(fails == 0 ? "Alles goed" : fails + " fout(en)");
        if (fails > 0) System.exit(1);
    }
}
