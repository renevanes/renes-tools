package nl.rene.tools;

import org.json.JSONObject;

/**
 * Nachtelijke backup: mislukt de hele backup (Google Drive nog aan het laden, geen verbinding), dan stil een paar keer
 * opnieuw in dezelfde nacht en pas daarna een melding; een backup-map in de cloud wacht op een verbinding.
 */
public class AllBackupJobTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    public static void main(String[] a) throws Exception {
        // Wanneer telt de hele backup als mislukt?
        JSONObject laden = new JSONObject().put("all", new JSONObject().put("ok", false).put("msg", "De backup-map is nog aan het laden; probeer het zo opnieuw"));
        JSONObject deel = new JSONObject().put("sms", new JSONObject().put("ok", false)).put("notes", new JSONObject().put("ok", true));
        JSONObject goed = new JSONObject().put("notes", new JSONObject().put("ok", true)).put("archive", "backup-2026-11-25_0330.rtb");
        check(AllBackupJob.nietGelukt(laden), "archief niet begonnen: hele backup mislukt");
        check(!AllBackupJob.nietGelukt(deel) && !AllBackupJob.nietGelukt(goed) && !AllBackupJob.nietGelukt(null), "één onderdeel mis of alles goed: geen nieuwe poging");

        // Pogingen tellen per nacht
        long nu = 1_800_000_000_000L, uur = 3_600_000L;
        check(AllBackupJob.poging(0, 0, nu) == 1, "eerste mislukte poging");
        check(AllBackupJob.poging(nu - uur, 1, nu) == 2 && AllBackupJob.poging(nu - 3 * uur, 3, nu) == 4, "dezelfde nacht: tellen door");
        check(AllBackupJob.poging(nu - 13 * uur, 3, nu) == 1, "een vorige nacht telt niet mee");
        check(AllBackupJob.poging(nu + uur, 2, nu) == 1, "klok teruggezet: opnieuw beginnen");
        // Hooguit MAX_POGINGEN per nacht, met steeds meer tijd ertussen; daarna een melding
        long totaal = 0;
        for (int p = 1; p < AllBackupJob.MAX_POGINGEN; p++) totaal += AllBackupJob.OPNIEUW_MS * p;
        check(AllBackupJob.MAX_POGINGEN >= 3 && totaal <= 4 * uur, "nieuwe pogingen binnen dezelfde nacht (" + totaal / 60_000 + " min)");

        // Een backup-map in de cloud wacht op een verbinding; op de telefoon zelf niet
        check(WaBackup.lokaal("com.android.externalstorage.documents") && WaBackup.lokaal("com.android.providers.downloads.documents"), "telefoon, SD-kaart en Downloads: lokaal");
        check(!WaBackup.lokaal("com.google.android.apps.docs.storage") && !WaBackup.lokaal(null), "Google Drive (of onbekend): cloud");

        // Eén regel in het foutlogboek: alleen getallen en vaste namen van onderdelen
        JSONObject res = new JSONObject().put("archive", "backup-2026-11-25_0330.rtb")
                .put("blok", new JSONObject().put("archief", 3_400_000L).put("nieuw", 2).put("nieuwBytes", 900_000L).put("hergebruikt", 812).put("hergebruiktBytes", 190_000_000L))
                .put("notes", new JSONObject().put("ok", true).put("o", 20_000L)).put("sms", new JSONObject().put("ok", true).put("o", 700_000L))
                .put("kluis", new JSONObject().put("ok", true).put("o", 0L)).put("rotated", new JSONObject().put("count", 1));
        String regel = AllBackup.samenvatting(res);
        check(regel.equals("backup klaar: archief 3321 kB, grote bestanden nieuw 2 (879 kB), hergebruikt 812 (181 MB); erbij: sms 684 kB, notes 20 kB; 1 oude weg"), "samenvatting: " + regel);
        check(AllBackup.samenvatting(new JSONObject().put("archive", "x.rtb")).equals("backup klaar"), "zonder grote bestanden: kort");
        check(!Logboek.probleem("BACKUP", regel) && Logboek.probleem("BACKUP", "nachtelijke backup mislukt, poging 1 van 4"), "geslaagd is geen probleem, mislukt wel");

        if (fails > 0) { System.out.println(fails + " fout(en)"); System.exit(1); }
        System.out.println("AllBackupJobTest ok");
    }
}
