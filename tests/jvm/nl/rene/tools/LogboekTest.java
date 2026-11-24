package nl.rene.tools;

import java.util.List;

/** Foutlogboek meesturen: alleen nieuwe meldingen, en opgeschoond (geen e-mail, nummers, rekeningen, sleutels, adressen, paden). */
public final class LogboekTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    static String melding(String tijd, String soort, String tekst) { return tijd + " [" + soort + "] v1.245 (1245)\n" + tekst + "\n\n"; }

    public static void main(String[] args) {
        String a = melding("2026-11-24 10:00:00", "STAMGEGEVENS", "beide zijden (pdf): niet gelukt, reden beelden");
        String b = melding("2026-11-24 10:05:00", "CRASH", "thread main\njava.lang.IllegalStateException: x\n\tat nl.rene.tools.Podcasts.open(Podcasts.java:72)");
        String c = melding("2026-11-24 10:05:00", "JS", "fout @x.js:12");
        String log = a + b + c;

        List<String> m = Logboek.meldingen(log);
        check(m.size() == 3 && m.get(1).contains("Podcasts.java:72"), "drie meldingen, stapel blijft bij de melding: " + m.size());
        check(Logboek.meldingen("los tekstje zonder kop\n").isEmpty(), "tekst zonder kopregel telt niet");

        // De eerste keer alles; daarna alleen wat nieuw is
        String[] eerst = Logboek.nieuw(log, "");
        check(eerst[0].contains("STAMGEGEVENS") && eerst[0].contains("[JS]") && eerst[1].equals(Logboek.merk(m.get(2))), "eerste keer: alles");
        check(Logboek.nieuw(log, eerst[1])[0].isEmpty(), "niets nieuw");
        String d = melding("2026-11-24 11:00:00", "BACKUP", "klaar");
        String[] daarna = Logboek.nieuw(log + d, eerst[1]);
        check(daarna[0].contains("[BACKUP]") && !daarna[0].contains("[JS]") && !daarna[0].contains("STAMGEGEVENS"), "alleen de nieuwe melding");
        // Twee meldingen in dezelfde seconde: de tweede gaat niet verloren
        String[] tot2 = Logboek.nieuw(a + b, "");
        String[] na2 = Logboek.nieuw(log, tot2[1]);
        check(na2[0].contains("[JS]") && !na2[0].contains("[CRASH]"), "zelfde seconde, na de laatst verstuurde");
        // Logboek ingekort (de laatst verstuurde staat er niet meer): alles vanaf dat tijdstip
        String[] kort = Logboek.nieuw(c + d, Logboek.merk(m.get(1)));
        check(kort[0].contains("[JS]") && kort[0].contains("[BACKUP]"), "na inkorten: vanaf het tijdstip");
        // Hooguit MAX tekens: de nieuwste
        StringBuilder groot = new StringBuilder();
        for (int i = 0; i < 3000; i++) groot.append(melding(String.format("2026-11-24 %02d:%02d:%02d", i / 3600, (i / 60) % 60, i % 60), "JS", "fout nummer " + i + " met wat extra tekst"));
        String[] veel = Logboek.nieuw(groot.toString(), "");
        check(veel[0].length() <= Logboek.MAX + 100 && veel[0].contains("fout nummer 2999") && !veel[0].contains("fout nummer 0 "), "begrensd, de nieuwste blijven");

        // Opschonen
        String vies = "mail rene@example.nl, bel 06-12345678 of +31 6 1234 5678, IBAN NL91 ABNA 0417 1643 00 en NL91ABNA0417164300,"
                + " bsn 123456782, sleutel github_pat_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789, link https://voorbeeld.nl/pad?x=1,"
                + " content://com.android.providers/document/42, pad /storage/emulated/0/Download/paspoort.pdf,"
                + " tijd 2026-11-24 10:00:00, versie 1.245 (1245), at nl.rene.tools.Podcasts.open(Podcasts.java:72)";
        String schoon = Logboek.schoon(vies);
        for (String weg : new String[]{"rene@example.nl", "12345678", "1234 5678", "NL91", "0417", "123456782", "github_pat_", "voorbeeld.nl", "content://", "paspoort.pdf", "/storage"})
            check(!schoon.contains(weg), "weg: " + weg + " in " + schoon);
        for (String blijft : new String[]{"2026-11-24 10:00:00", "1.245 (1245)", "Podcasts.java:72", "nl.rene.tools.Podcasts.open"})
            check(schoon.contains(blijft), "blijft: " + blijft + " in " + schoon);
        System.out.println("LogboekTest ok");
    }
}
