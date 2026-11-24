package nl.rene.tools;

import java.util.List;

/** Foutlogboek meesturen: alleen nieuwe meldingen, en opgeschoond (geen e-mail, nummers, rekeningen, sleutels, adressen, paden). */
public final class LogboekTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    static String rep(String s, int n) { StringBuilder b = new StringBuilder(); for (int i = 0; i < n; i++) b.append(s); return b.toString(); }

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
        // Inhoud uit een kapot JSON-bestand (Android zet de hele tekst in de foutmelding)
        String json = melding("2026-11-24 12:00:00", "CHECKIN", "vragen kapot, apart gezet: org.json.JSONException: Unterminated string at character 31 of {\"vraag\":\"Hoe slaap je\",\"antwoord\":\"slecht door de buren\"}")
                + melding("2026-11-24 12:00:01", "CRASH", "thread x\norg.json.JSONException: End of input at character 5 of {\"a\":\n\tat org.json.JSONTokener.syntaxError(JSONTokener.java:449)");
        String js = Logboek.schoon(json);
        check(!js.contains("buren") && !js.contains("Hoe slaap") && js.contains("at character 31 of [inhoud]") && js.contains("JSONTokener.java:449"), "JSON-inhoud weg, stapel blijft: " + js);
        // Meer soorten: paden met spaties, losse bestandsnamen, kleine letters, IP, postcode, andere adressen
        String meer = Logboek.schoon("open /storage/emulated/0/Documents/Paspoort Rene van Es.pdf (No such file)\n"
                + "kon Huurcontract Jansen.pdf niet lezen\nnl91 abna 0417 1643 00\nvpn 192.168.1.20 en fe80::1c2b:3a4d en 2001:db8:85a3:0:0:8a2e:370:7334\n"
                + "adres 3011 AB Rotterdam\nwww.mijnbank.nl/login en bank.nl/inloggen wss://x.y/z intent://scan#Intent;end\n"
                + "Podcasts.java:72 toestand.json app.js:12 nl.rene.tools.App.log(App.java:150)");
        for (String weg : new String[]{"Paspoort", "Rene van Es", "Huurcontract", "Jansen", "abna", "0417", "192.168", "fe80", "2001:db8", "3011 AB", "mijnbank", "bank.nl", "x.y", "intent://"})
            check(!meer.contains(weg), "weg: " + weg + " in " + meer);
        for (String blijft : new String[]{"(No such file)", "Podcasts.java:72", "toestand.json", "app.js:12", "nl.rene.tools.App.log(App.java:150)"})
            check(meer.contains(blijft), "blijft: " + blijft + " in " + meer);
        // Stapel van JavaScript: het eigen adres gaat eruit, regel en kolom blijven
        String jsStapel = Logboek.schoon("belofte: TypeError: x is null\n    at f (https://app.renes-tools.local/:1234:56)");
        check(jsStapel.contains("at f (:1234:56)"), "JS-stapel houdt regel en kolom: " + jsStapel);
        // Een lange regel (geen stapelregel) wordt ingekort
        StringBuilder lang = new StringBuilder("tekst ");
        for (int i = 0; i < 100; i++) lang.append("woord ");
        check(Logboek.schoon(lang.toString()).length() <= Logboek.MAX_REGEL + 20, "lange regel ingekort");

        // Dezelfde melding twee keer in dezelfde seconde: de tweede gaat niet verloren
        String dubbel = melding("2026-11-24 13:00:00", "JS", "zelfde");
        String[] een = Logboek.nieuw(dubbel, "");
        String[] twee = Logboek.nieuw(dubbel + dubbel, een[1]);
        check(twee[0].contains("zelfde") && !twee[1].equals(een[1]), "tweede gelijke melding is nieuw: " + twee[1]);
        check(Logboek.nieuw(dubbel + dubbel, twee[1])[0].isEmpty(), "daarna niets nieuws");
        // Ingekort: de markering staat erboven, ook bij een achterstand na eerder versturen
        String[] achter = Logboek.nieuw(groot.toString(), Logboek.merk(Logboek.meldingen(groot.toString()).get(0)));
        check(veel[0].startsWith("…(oudere meldingen weggelaten)") && achter[0].startsWith("…(oudere meldingen weggelaten)"), "markering bij inkorten");

        // Hooguit eens per uur (behalve na een vastloper, dat regelt plan)
        long nu = 1_800_000_000_000L;
        check(Logboek.wachtTijd(0, nu) == Logboek.WACHT_MS && Logboek.wachtTijd(nu - 2 * Logboek.UUR, nu) == Logboek.WACHT_MS, "lang geleden: na een paar minuten");
        check(Logboek.wachtTijd(nu - 10 * 60_000L, nu) == Logboek.UUR - 10 * 60_000L && Logboek.wachtTijd(nu + 60_000L, nu) == Logboek.WACHT_MS, "net verstuurd: wacht tot het uur om is (klok terug: gewoon)");
        // Het pad mag van Statistiek.zet
        check(Logboek.pad(nu, "ab12cd34").matches("(?:diagnose|logboek)/[A-Za-z0-9T_-]{1,120}\\.txt"), "pad: " + Logboek.pad(nu, "ab12cd34"));
        // Opschonen blijft lineair: ook lange, rare tekst gaat snel (Android kan een zoekopdracht niet afbreken)
        for (String rare : new String[]{rep("a", 100_000), rep("a.", 50_000), rep("1.", 50_000), rep(". ", 50_000), rep(":", 100_000), rep("ab12 ", 20_000)}) {
            long t0 = System.nanoTime();
            Logboek.schoon(rare);
            check((System.nanoTime() - t0) / 1_000_000 < 5_000, "opschonen te traag op rare tekst");
        }
        System.out.println("LogboekTest ok");
    }
}
