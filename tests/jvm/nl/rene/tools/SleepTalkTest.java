package nl.rene.tools;

import java.util.Arrays;
import java.util.Calendar;
import java.util.TimeZone;

/** Slaappraat: stoptijd, tekst van whisper opschonen, praten of geluid, WAV. */
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

        if (fails > 0) { System.out.println(fails + " fout(en)"); System.exit(1); }
        System.out.println("alles goed");
        System.exit(0);
    }
}
