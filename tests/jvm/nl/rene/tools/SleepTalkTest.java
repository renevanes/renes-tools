package nl.rene.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.TimeZone;

/** Slaappraat: stoptijd, tekst van whisper opschonen, praten of geluid, WAV, het budget per nacht en meteen uitschrijven. */
public class SleepTalkTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    static long at(TimeZone tz, int y, int mo, int d, int h, int mi) { Calendar k = Calendar.getInstance(tz); k.clear(); k.set(y, mo - 1, d, h, mi); return k.getTimeInMillis(); }

    public static void main(String[] a) {
        TimeZone ams = TimeZone.getTimeZone("Europe/Amsterdam");
        check(SleepTalk.stopTime(at(ams, 2026, 10, 23, 23, 15), "07:30", ams) == at(ams, 2026, 10, 24, 7, 30), "om 23:15 gestart: de volgende ochtend om 07:30");
        check(SleepTalk.stopTime(at(ams, 2026, 10, 24, 1, 0), "07:30", ams) == at(ams, 2026, 10, 24, 7, 30), "na middernacht gestart: dezelfde ochtend");
        check(SleepTalk.stopTime(at(ams, 2026, 10, 24, 7, 20), "07:30", ams) == at(ams, 2026, 10, 25, 7, 30), "vlak voor de stoptijd gestart: niet meteen stoppen");
        check(SleepTalk.stopTime(at(ams, 2026, 10, 24, 23, 0), "07:30", ams) == at(ams, 2026, 10, 25, 7, 30), "over de wintertijd heen: toch 07:30");
        check(SleepTalk.stopTime(1000, "", ams) == 0 && SleepTalk.stopTime(1000, "25:00", ams) == 0, "geen of foute tijd: niet vanzelf stoppen");
        check(SleepTalk.validTime("06:45") && SleepTalk.validTime("") && !SleepTalk.validTime("6:45") && !SleepTalk.validTime("24:00") && !SleepTalk.validTime(null), "tijd controleren");

        check("Ik moet de trein halen.".equals(SleepTalk.cleanText(Arrays.asList(" Ik moet de trein", "[muziek]", "halen.", "(gesnurk)"))) , "tekst: [..] en (..) weg, stukken aan elkaar");
        check(SleepTalk.cleanText(Arrays.asList("Ondertiteld door de Amara.org gemeenschap", "Bedankt voor het kijken!")).isEmpty(), "wat whisper bij stilte verzint: weg");
        check("praten".equals(SleepTalk.kind("Nee, niet de rode")) && "geluid".equals(SleepTalk.kind("")) && "geluid".equals(SleepTalk.kind("...")) && "geluid".equals(SleepTalk.kind(null)), "praten of geluid");

        byte[] w = SleepTalk.wav(new short[]{1, -1, 300});
        check(w.length == 50 && w[0] == 'R' && w[8] == 'W' && w[22] == 1 && (w[24] & 0xff) == 0x80 && (w[25] & 0xff) == 0x3e && w[44] == 1 && w[46] == (byte) 0xff, "WAV: kop (16 kHz mono) en de samples");

        check(SleepTalk.safeId("1761256800000") && !SleepTalk.safeId("../x") && SleepTalk.safeClip("c1761256800000") && !SleepTalk.safeClip("c1/../x"), "alleen eigen bestandsnamen");

        // Herinnering op bedtijd
        check(SleepReminder.next(at(ams, 2026, 10, 24, 20, 0), "22:30", ams) == at(ams, 2026, 10, 24, 22, 30), "herinnering: vanavond");
        check(SleepReminder.next(at(ams, 2026, 10, 24, 23, 0), "22:30", ams) == at(ams, 2026, 10, 25, 22, 30), "herinnering: al voorbij, dus morgen");
        check(SleepReminder.next(1000, "", ams) == 0, "herinnering uit");
        // Accu per uur
        long s0 = at(ams, 2026, 10, 24, 23, 0);
        check(Math.abs(SleepTalk.batPerHour(s0, s0 + 8 * 3_600_000L, 90, 74, false) - 2.0) < 0.01, "accu: 16% in 8 uur = 2% per uur");
        check(SleepTalk.batPerHour(s0, s0 + 8 * 3_600_000L, 90, 74, true) == -1 && SleepTalk.batPerHour(s0, s0 + 600_000L, 90, 89, false) == -1
                && SleepTalk.batPerHour(s0, s0 + 8 * 3_600_000L, -1, 74, false) == -1 && SleepTalk.batPerHour(s0, s0 + 8 * 3_600_000L, 70, 74, false) == -1, "accu: niet te zeggen (lader, te kort, onbekend, opgeladen)");

        budget();
        direct();

        if (fails > 0) { System.out.println(fails + " fout(en)"); System.exit(1); }
        System.out.println("alles goed");
        System.exit(0);
    }

    static SleepTalk.Slot slot(String id, long t, double dur, boolean audio, boolean done, String kind, String guess, double ratio) {
        return new SleepTalk.Slot(id, t, dur, audio, done, kind, guess, ratio);
    }

    /** Het budget: alleen bewaard geluid telt, en wat als het vol is (instelbaar). */
    static void budget() {
        double max = SleepTalk.MAX_AUDIO_H * 3600.0;
        check(SleepTalk.MAX_AUDIO_H >= 8 && SleepTalk.MIN_FREE_MB >= 300, "budget: standaard een hele nacht geluid (" + SleepTalk.MAX_AUDIO_H + " uur) en altijd ruimte vrij");
        List<SleepTalk.Slot> none = new ArrayList<>();
        check(SleepTalk.makeRoom(none, 60, false, max, "geluid", null).isEmpty(), "lege nacht: past, niets weg");
        check(SleepTalk.makeRoom(none, 60, false, Double.MAX_VALUE, "stop", null).isEmpty(), "onbeperkt: past altijd");

        // Geluid dat al uitgeschreven is en (geluiden niet bewaren) zijn geluid kwijt is: telt niet meer mee
        List<SleepTalk.Slot> freed = new ArrayList<>();
        for (int i = 0; i < 1000; i++) freed.add(slot("c" + i, i, 60, false, true, "geluid", "geluid", 0.1));
        check(SleepTalk.keptSeconds(freed) == 0 && SleepTalk.makeRoom(freed, 60, false, max, "geluid", null).isEmpty(), "geluid zonder bewaard geluid telt niet mee");

        // Bijna vol (nog 10 s): bewaard geluid (Ook geluiden bewaren aan), nog niet uitgeschreven geluid en praten
        List<SleepTalk.Slot> full = new ArrayList<>();
        full.add(slot("praat", 0, 50, true, true, "praten", "praten", 0.6));
        full.add(slot("oud", 10, 60, true, false, "", "geluid", 0.20));
        full.add(slot("snurk", 20, 60, true, false, "", "geluid", 0.05));
        full.add(slot("gok", 30, 60, true, false, "", "praten", 0.40));
        full.add(slot("klaar", 40, 30, true, true, "geluid", "geluid", 0.10));
        full.add(slot("stuk", 45, 5, true, true, "onleesbaar", "geluid", 0.10));
        double rest = max - SleepTalk.keptSeconds(full);
        full.add(slot("vul", 50, rest - 10, true, true, "praten", "praten", 0.5));
        check(Math.abs(SleepTalk.keptSeconds(full) - (max - 10)) < 1e-6, "bijna vol (nog 10 s)");
        for (String mode : new String[]{"stop", "geluid", "oudste"})
            check(SleepTalk.makeRoom(full, 10, true, max, mode, null).isEmpty(), "past nog precies: niets weg (" + mode + ")");
        // Standaard: oudste geluid weg voor nieuw gepraat
        check(SleepTalk.makeRoom(full, 20, false, max, "geluid", null) == null, "geluid: vol, nieuw geluid krijgt geen geluid");
        List<String> r = SleepTalk.makeRoom(full, 20, true, max, "geluid", null);
        check(r != null && r.equals(Collections.singletonList("klaar")), "geluid: praten maakt plaats, eerst uitgeschreven geluid (" + r + ")");
        r = SleepTalk.makeRoom(full, 45, true, max, "geluid", null);
        check(r != null && r.equals(Arrays.asList("klaar", "stuk")), "daarna wat onleesbaar was (" + r + ")");
        r = SleepTalk.makeRoom(full, 60, true, max, "geluid", null);
        check(r == null, "nog niet uitgeschreven geluid gaat nooit weg (de gok kan mis zijn): past niet, dan niets weg");
        r = SleepTalk.makeRoom(full, 20, true, max, "geluid", new HashSet<>(Collections.singletonList("klaar")));
        check(r != null && r.equals(Arrays.asList("stuk", "klaar")), "wat nu bezig is liever niet, maar als het niet anders kan wel (" + r + ")");
        check(SleepTalk.makeRoom(full, 200, true, max, "geluid", null) == null, "praten en gegokt praten gaan nooit weg: past niet, dan niets weg");
        // Stop bewaren
        check(SleepTalk.makeRoom(full, 20, true, max, "stop", null) == null, "stop: ook praten krijgt dan geen geluid meer, niets weg");
        // Oudste weg: alles, het oudste eerst (ook geluid maakt plaats)
        r = SleepTalk.makeRoom(full, 20, false, max, "oudste", null);
        check(r != null && r.equals(Collections.singletonList("praat")), "oudste: het oudste stukje gaat weg, ook voor geluid (" + r + ")");
        r = SleepTalk.makeRoom(full, 125, true, max, "oudste", null);
        check(r != null && r.equals(Arrays.asList("praat", "oud", "snurk")), "oudste: zoveel als nodig, op volgorde (" + r + ")");
        check(SleepTalk.makeRoom(full, max + 100, true, max, "oudste", null) == null, "oudste: groter dan het hele budget past nooit");
    }

    /** Meteen uitschrijven: dezelfde regels als 's ochtends, en wanneer het mag. */
    static void direct() {
        check("praten".equals(SleepTalk.kindOf("Ik moet de trein halen", false)) && "geluid".equals(SleepTalk.kindOf("", false)) && "onleesbaar".equals(SleepTalk.kindOf("", true)), "praten, geluid of onleesbaar");
        check(SleepTalk.dropAudio("geluid", false) && !SleepTalk.dropAudio("geluid", true) && !SleepTalk.dropAudio("praten", false) && !SleepTalk.dropAudio("onleesbaar", false),
                "alleen geluid zonder woorden gaat weg (en alleen als je het niet wilt bewaren)");
        int b = SleepTalk.DIRECT_MIN_BAT;
        check(SleepTalk.directWait("accu", b, true, 10, false, false, true).isEmpty(), "aan de lader: altijd (ook bij weinig accu of batterijbesparing)");
        check(SleepTalk.directWait("accu", b, false, 80, false, false, false).isEmpty() && SleepTalk.directWait("accu", b, false, b, false, false, false).isEmpty(), "zonder lader: met genoeg accu");
        check(SleepTalk.directWait("accu", b, false, b - 1, false, false, false).contains("accu onder " + b + "%"), "zonder lader en weinig accu: wachten tot het eind");
        check(SleepTalk.directWait("accu", 60, false, 55, false, false, false).contains("60%"), "accudrempel instelbaar");
        check(SleepTalk.directWait("accu", b, false, -1, false, false, false).contains("accu"), "accu onbekend zonder lader: wachten");
        check(SleepTalk.directWait("accu", b, false, 80, false, false, true).contains("batterijbesparing"), "batterijbesparing aan: wachten");
        check(SleepTalk.directWait("lader", b, false, 100, false, false, false).contains("lader") && SleepTalk.directWait("lader", b, true, 5, false, false, false).isEmpty(), "alleen aan de lader");
        check(SleepTalk.directWait("altijd", b, false, 5, false, false, true).isEmpty(), "altijd: ook zonder lader en met weinig accu");
        for (String w : new String[]{"altijd", "lader", "accu"})
            check(SleepTalk.directWait(w, b, true, 100, true, false, false).contains("gesprek") && SleepTalk.directWait(w, b, true, 100, false, true, false).contains("warm"), "tijdens een gesprek of als de telefoon warm is: nooit (" + w + ")");
        check(SleepTalk.allowed(15, SleepTalk.STOP_BATS) && !SleepTalk.allowed(16, SleepTalk.STOP_BATS) && SleepTalk.allowed(0, SleepTalk.MAX_HOURS) && SleepTalk.allowed(1500, SleepTalk.HANGS), "alleen vaste keuzes");
    }
}
