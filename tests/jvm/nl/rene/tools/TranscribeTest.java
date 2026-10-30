package nl.rene.tools;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Arrays;

/** De gemelde televisievermelding, behoud van gewone spraak en digitale stilte vóór de motor. */
public final class TranscribeTest {
    private static int checks;
    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
    private static void rejected(File file, byte[] data) throws Exception {
        Files.write(file.toPath(), data);
        try { Transcribe.silentWav(file); throw new AssertionError("Ongeldig WAV-bestand als stilte geaccepteerd"); }
        catch (IOException expected) { checks++; }
    }

    public static void main(String[] args) throws Exception {
        for (String text : new String[]{"(C) TV GELDERLAND 2021", " (c) tv gelderland 2021. ", "© TV Gelderland 2021", "TV GELDERLAND 2021", "Copyright TV Gelderland 2021!",
                "Ondertiteld door de Amara.org gemeenschap", "Subtitles by the Amara.org community.", "Ondertitels ingediend door de Amara.org gemeenschap",
                "[BLANK_AUDIO]", " (muziek) ", "[Muziek] (geluid)", "*gelach*", "♪", "...", "", "  ", null}) {
            check(SpeechText.segment(text).isEmpty(), "Losse vermelding overslaan: " + text);
            check(!VoiceNote.keepSeg(text), "Inspreken slaat dezelfde vermelding over: " + text);
        }
        // Niet op losse woorden of namen filteren: ook zinnen over de omroep zijn gewone spraak.
        for (String text : new String[]{"TV Gelderland", "TV Gelderland.", "Ik zag het op TV Gelderland in 2021.", "TV Gelderland is een regionale omroep.", "Bedankt voor het kijken!", "Dank je wel, tot de volgende keer.",
                "We moeten nog ondertiteling toevoegen.", "Ondertiteld door de Amara.org gemeenschap. Morgen spreken we af.", "(Even denken) morgen om twee uur.", "[onverstaanbaar] Ik ben thuis.", "(C) TV GELDERLAND 2021. Hallo!", "Ja.", "Nee!"}) {
            check(text.equals(SpeechText.segment("  " + text + "  ")), "Gewone spraak behouden: " + text);
            check(VoiceNote.keepSeg(text), "Inspreken behoudt gewone spraak: " + text);
        }
        check("Morgen om twee uur.".equals(SleepTalk.cleanText(Arrays.asList("(C) TV GELDERLAND 2021", "Morgen om twee uur."))), "Slaappraat blijft dezelfde bescherming gebruiken");

        File wav = File.createTempFile("tools-stilte-", ".wav");
        try {
            Files.write(wav.toPath(), SleepTalk.wav(new short[70000]));
            check(Transcribe.silentWav(wav), "Ook meer dan één buffer digitale stilte herkennen");
            Transcribe.cancel = true;
            try { Transcribe.silentWav(wav, true); throw new AssertionError("Stoppen wordt tijdens de stiltescan genegeerd"); }
            catch (IOException expected) { check("Gestopt".equals(expected.getMessage()), "Stoppen tijdens de stiltescan"); }
            check(Transcribe.silentWav(wav), "Stoppen bij gesprekken annuleert geen spraaknotitie of Slaappraat");
            Transcribe.cancel = false;
            Files.write(wav.toPath(), SleepTalk.wav(new short[0]));
            check(Transcribe.silentWav(wav), "Geen samples: geen spraakmodel nodig");
            for (short sample : new short[]{1, -1, 256, -256, Short.MIN_VALUE, Short.MAX_VALUE}) {
                short[] samples = new short[70000]; samples[samples.length - 1] = sample;
                Files.write(wav.toPath(), SleepTalk.wav(samples));
                check(!Transcribe.silentWav(wav), "Ook heel zacht of laat geluid behouden: " + sample);
            }
            try (RandomAccessFile r = new RandomAccessFile(wav, "rw")) {
                r.setLength(0); Transcribe.writeWavHeader(r, 2); r.write(new byte[]{0, 0});
            }
            check(Transcribe.silentWav(wav), "Het WAV-formaat van de audiodecoder en inspreken wordt ondersteund");
            rejected(wav, new byte[44]);
            rejected(wav, new byte[20]);
            rejected(wav, Arrays.copyOf(SleepTalk.wav(new short[10]), 60));
            byte[] wrong = SleepTalk.wav(new short[10]); wrong[22] = 2; rejected(wav, wrong);
            wrong = SleepTalk.wav(new short[10]); wrong[24] = 0; rejected(wav, wrong);
            wrong = SleepTalk.wav(new short[10]); wrong[40] = 19; rejected(wav, wrong);
            wrong = SleepTalk.wav(new short[10]); wrong[4] = 0; rejected(wav, wrong);
            rejected(wav, Arrays.copyOf(SleepTalk.wav(new short[10]), 66));
        } finally { Transcribe.cancel = false; Files.deleteIfExists(wav.toPath()); }
        System.out.println("Alle " + checks + " transcriptiecontroles geslaagd");
    }
}
