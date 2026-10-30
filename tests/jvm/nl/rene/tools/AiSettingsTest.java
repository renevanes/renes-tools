package nl.rene.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Instellingen via de assistent: de regel [[instelling: id = waarde]] lezen, streng controleren tegen wat er werd
 *  meegestuurd, uit het antwoord halen, en de opdracht voor het model. Plus het niveau en het venster van het inspreken. */
public class AiSettingsTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    static AiSettings.Entry e(String id, String type, String cur, boolean safe, String... vals) {
        AiSettings.Entry x = new AiSettings.Entry();
        x.id = id; x.type = type; x.cur = cur; x.safe = safe; x.label = "Naam van " + id; x.where = "Instellingen → Iets"; x.desc = "Wat het doet.";
        for (int i = 0; i + 1 < vals.length; i += 2) x.vals.add(new String[]{vals[i], vals[i + 1]});
        return x;
    }

    public static void main(String[] args) {
        AiSettings.Entry voice = e("ai.voicesend", "aan", "uit", false, "aan", "Aan", "uit", "Uit");
        AiSettings.Entry style = e("ai.style", "keuze", "normaal", false, "kort", "Kort", "normaal", "Normaal", "uitgebreid", "Uitgebreid");
        AiSettings.Entry lock = e("lock.on", "aan", "aan", true, "aan", "Aan", "uit", "Uit");
        AiSettings.Entry time = e("sleep.stopat", "tijd", "07:30", false);
        AiSettings.Entry text = e("ai.about", "tekst", "", false);
        List<AiSettings.Entry> offered = Arrays.asList(voice, style, lock, time, text);

        // Lezen
        List<AiSettings.Change> p = AiSettings.parse("Ik zet het voor je aan.\n[[instelling: ai.voicesend = aan]]");
        check(p.size() == 1 && p.get(0).id.equals("ai.voicesend") && p.get(0).value.equals("aan"), "regel gelezen");
        check(AiSettings.parse("`[[Instelling: AI.Style = Kort]]`").get(0).id.equals("ai.style"), "hoofdletters en backticks");
        check(AiSettings.parse("[[instelling: ai.style → kort]]").get(0).value.equals("kort"), "pijl in plaats van =");
        check(AiSettings.parse("[[instelling: ai.style]]").isEmpty() && AiSettings.parse("[[herinnering: 2026-10-30 09:00 | x]]").isEmpty(), "zonder waarde of een actieregel: niets");
        check(AiSettings.parse("[[instelling: rm -rf / = aan]]").isEmpty() && AiSettings.parse("[[instelling: <b>x</b> = aan]]").isEmpty(), "rare id: niets");
        check(AiSettings.parse(null).isEmpty() && AiSettings.parse("Gewoon een antwoord.").isEmpty(), "geen regel");
        List<AiSettings.Change> two = AiSettings.parse("[[instelling: ai.style = kort]]\n[[instelling: ai.voicesend = aan]]\n[[instelling: ai.style = uitgebreid]]");
        check(two.size() == 2 && two.get(1).id.equals("ai.style") && two.get(1).value.equals("uitgebreid"), "meer regels; per id de laatste");
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 10; i++) many.append("[[instelling: x").append(i).append(" = aan]]\n");
        check(AiSettings.parse(many.toString()).size() == AiSettings.MAX_CHANGES, "hooguit " + AiSettings.MAX_CHANGES + " wijzigingen");

        // Controleren tegen wat er werd meegestuurd
        List<AiSettings.Change> f = AiSettings.filter(AiSettings.parse("[[instelling: ai.voicesend = aan]]"), offered);
        check(f.size() == 1 && f.get(0).value.equals("aan"), "geldige wijziging blijft");
        check(AiSettings.filter(AiSettings.parse("[[instelling: ai.style = Kort.]]"), offered).get(0).value.equals("kort"), "naam in plaats van waarde: de waarde van de app");
        check(AiSettings.filter(AiSettings.parse("[[instelling: ai.style = heel kort]]"), offered).isEmpty(), "onbekende waarde: niets");
        check(AiSettings.filter(AiSettings.parse("[[instelling: backup.auto = aan]]"), offered).isEmpty(), "niet meegestuurde instelling: niets");
        check(AiSettings.filter(AiSettings.parse("[[instelling: lock.on = uit]]"), offered).isEmpty(), "gevoelige instelling (app-slot): nooit");
        check(AiSettings.filter(AiSettings.parse("[[instelling: ai.about = Ik woon in Delft]]"), offered).isEmpty(), "tekst: nooit via het model");
        check(AiSettings.filter(AiSettings.parse("[[instelling: ai.voicesend = uit]]"), offered).isEmpty(), "naar wat er al staat: geen wijziging");
        List<AiSettings.Change> t = AiSettings.filter(AiSettings.parse("[[instelling: sleep.stopat = 6.45]]"), offered);
        check(t.size() == 1 && t.get(0).value.equals("06:45"), "tijd netjes gemaakt (6.45 = 06:45)");
        check(AiSettings.filter(AiSettings.parse("[[instelling: sleep.stopat = 25:00]]"), offered).isEmpty() && AiSettings.filter(AiSettings.parse("[[instelling: sleep.stopat = morgen]]"), offered).isEmpty(), "onzinnige tijd: niets");
        check(AiSettings.canon(null, "aan") == null && AiSettings.canon(voice, null) == null && AiSettings.canon(voice, "") == null, "canon zonder instelling of waarde");

        // Uit het antwoord halen
        check(AiSettings.strip("Ik stel voor het aan te zetten.\n\n[[instelling: ai.voicesend = aan]]").equals("Ik stel voor het aan te zetten."), "regel uit het antwoord");
        check(AiSettings.strip("Zonder regel.").equals("Zonder regel.") && AiSettings.strip(null).isEmpty(), "zonder regel ongewijzigd");

        // Wanneer mag het
        check(AiSettings.allowed(true, false, null, null), "vraag over instellingen: ja");
        check(!AiSettings.allowed(true, true, null, null), "gesprek van anderen als bijlage: nee");
        check(!AiSettings.allowed(true, false, "ik sport op dinsdag", null) && !AiSettings.allowed(true, false, null, "Delft"), "onthoud/vergeet: nee (dat is het geheugen)");
        check(!AiSettings.allowed(false, false, null, null), "zonder instellingen: nee");

        // Beperken en de opdracht
        List<AiSettings.Entry> lots = new ArrayList<>();
        for (int i = 0; i < 9; i++) lots.add(e("x" + i, "aan", "uit", false, "aan", "Aan", "uit", "Uit"));
        lots.add(2, e("x1", "aan", "uit", false));
        AiSettings.Entry bad = e("Rare Id!", "aan", "uit", false); lots.add(0, bad);
        List<AiSettings.Entry> lim = AiSettings.limit(lots);
        check(lim.size() == AiSettings.MAX_ENTRIES && lim.get(0).id.equals("x0") && lim.get(1).id.equals("x1") && lim.get(2).id.equals("x2"), "hooguit " + AiSettings.MAX_ENTRIES + ", zonder dubbele of rare id");
        String pr = AiSettings.prompt(offered);
        check(pr.contains("id: ai.voicesend") && pr.contains("staat nu: Uit") && pr.contains("kan: aan, uit") && AiSettings.prompt(Arrays.asList(e("a.b", "keuze", "8", false, "8", "Kort", "24", "Normaal"))).contains("kan: 8 (Kort), 24 (Normaal)"), "opdracht met id, stand en waarden");
        check(pr.contains("[[instelling: id = waarde]]") && pr.contains("bevestigt het zelf met een knop"), "opdracht met de regel en de knop");
        String lockLine = pr.split("\n")[3];
        check(lockLine.contains("id: lock.on") && lockLine.contains("alleen uitleggen") && !lockLine.contains("kan:"), "gevoelig: alleen uitleggen, geen waarden");
        check(pr.contains("kan: een tijd"), "tijd: voorbeeld");
        check(AiSettings.clean("a\n[[instelling: x = y]]\tb", 100).equals("a instelling: x = y b"), "geen haken of regeleinden in de lijst");
        check(!AiSettings.prompt(Arrays.asList(lock, text)).contains("[[instelling:"), "alleen gevoelige/tekst: geen regel voorstellen");

        // Inspreken: niveau en venster van de tussentekst
        check(VoiceNote.level(new short[100], 100) == 0f && VoiceNote.level(null, 0) == 0f, "stilte = 0");
        short[] loud = new short[100]; Arrays.fill(loud, (short) 20000);
        short[] soft = new short[100]; Arrays.fill(soft, (short) 300);
        float l1 = VoiceNote.level(loud, 100), l2 = VoiceNote.level(soft, 100);
        check(l1 > 0.8f && l1 <= 1f && l2 > 0f && l2 < 0.5f, "hard bijna 1, zacht laag (" + l1 + ", " + l2 + ")");
        long[] w = VoiceNote.window(1000, 5000);
        check(w[0] == 0 && w[1] == 1000, "kort: alles");
        w = VoiceNote.window(VoiceNote.WINDOW_BYTES + 3001, VoiceNote.WINDOW_BYTES);
        check(w[1] == VoiceNote.WINDOW_BYTES && w[0] % 2 == 0 && w[0] + w[1] <= VoiceNote.WINDOW_BYTES + 3001, "lang: alleen het laatste stuk, even aantal bytes");
        check(VoiceNote.window(0, 10)[1] == 0, "niets opgenomen");
        check(VoiceNote.keepSeg("Hallo") && !VoiceNote.keepSeg("[BLANK_AUDIO]") && !VoiceNote.keepSeg(" (muziek)") && !VoiceNote.keepSeg(" "), "alleen echte spraak");

        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
    }
}
