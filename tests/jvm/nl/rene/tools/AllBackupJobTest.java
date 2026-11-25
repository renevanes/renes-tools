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
        // Wanneer telt de hele backup als mislukt (en heeft opnieuw proberen zin)?
        JSONObject laden = new JSONObject().put("all", new JSONObject().put("ok", false).put("msg", "De backup-map is nog aan het laden; probeer het zo opnieuw"));
        JSONObject deel = new JSONObject().put("sms", new JSONObject().put("ok", false)).put("notes", new JSONObject().put("ok", true));
        JSONObject goed = new JSONObject().put("notes", new JSONObject().put("ok", true)).put("archive", "backup-2026-11-25_0330.rtb");
        JSONObject alGemaakt = new JSONObject().put("archive", "backup-2026-11-25_0330.rtb").put("all", new JSONObject().put("ok", false).put("msg", "Archief afsluiten mislukt"));
        JSONObject geenToegang = new JSONObject().put("all", new JSONObject().put("ok", false).put("msg", WaBackup.NO_ACCESS));
        check(AllBackupJob.nietGelukt(laden), "archief niet begonnen: hele backup mislukt");
        check(!AllBackupJob.nietGelukt(deel) && !AllBackupJob.nietGelukt(goed) && !AllBackupJob.nietGelukt(null), "één onderdeel mis of alles goed: geen nieuwe poging");
        check(!AllBackupJob.nietGelukt(alGemaakt) && !AllBackupJob.nietGelukt(geenToegang), "archief staat er al, of geen toegang tot de map: niet opnieuw");

        // Pogingen tellen; opgeven na MAX_POGINGEN of als het te lang duurt (bijv. opladen gestopt): dan een melding
        long nu = 1_800_000_000_000L, uur = 3_600_000L;
        check(AllBackupJob.poging(0, 0, nu) == 1 && AllBackupJob.poging(nu + uur, 2, nu) == 1, "eerste poging (ook als de klok terugging)");
        check(AllBackupJob.poging(nu - uur, 1, nu) == 2 && AllBackupJob.poging(nu - 13 * uur, 3, nu) == 4, "pogingen tellen door, ook na lang wachten");
        check(!AllBackupJob.opgeven(nu, 1, nu) && !AllBackupJob.opgeven(nu - uur, 2, nu), "eerste pogingen: stil opnieuw");
        check(AllBackupJob.opgeven(nu - uur, AllBackupJob.MAX_POGINGEN, nu), "genoeg pogingen: melding");
        check(AllBackupJob.opgeven(nu - 13 * uur, 2, nu), "opladen stopte en het werd te laat: toch een melding");
        long totaal = 0;
        for (int p = 1; p < AllBackupJob.MAX_POGINGEN; p++) totaal += AllBackupJob.OPNIEUW_MS * p;
        check(AllBackupJob.MAX_POGINGEN >= 3 && totaal <= AllBackupJob.OPGEVEN_MS, "nieuwe pogingen passen in de tijd voor opgeven (" + totaal / 60_000 + " min)");

        // Al met de hand gelukt na een mislukte nacht: de taak hoeft niet meer; anders wel (ook na 03:30 van vandaag)
        java.util.Calendar k = java.util.Calendar.getInstance();
        k.set(2026, 10, 25, 5, 0, 0);
        long vijfUur = k.getTimeInMillis();
        k.set(2026, 10, 24, 3, 35, 0);
        long gisterNacht = k.getTimeInMillis();
        check(AllBackupJob.nachtGedaan(vijfUur - 10 * 60_000L, vijfUur), "met de hand gelukt om 04:50: om 05:00 niet nog eens");
        check(!AllBackupJob.nachtGedaan(gisterNacht, vijfUur) && !AllBackupJob.nachtGedaan(0, vijfUur), "gisteren of nooit: wel back-uppen");
        k.set(2026, 10, 25, 2, 0, 0);
        check(!AllBackupJob.nachtGedaan(gisterNacht, k.getTimeInMillis() + 89 * 60_000L), "vlak voor 03:30: gewoon de nacht van vandaag");

        // Een backup-map bij een bekende clouddienst wacht op een verbinding; op de telefoon of onbekend niet
        check(WaBackup.cloud("com.google.android.apps.docs.storage") && WaBackup.cloud("com.microsoft.skydrive.content.StorageAccessProvider")
                && WaBackup.cloud("org.nextcloud.documents"), "Google Drive, OneDrive, Nextcloud: cloud");
        check(!WaBackup.cloud("com.android.externalstorage.documents") && !WaBackup.cloud("com.android.providers.downloads.documents")
                && !WaBackup.cloud("com.android.mtp.documents") && !WaBackup.cloud("com.omega.files") && !WaBackup.cloud(null), "telefoon, SD-kaart, USB, MTP en onbekend: geen verbinding nodig");

        // Eén regel in het foutlogboek: alleen getallen en vaste namen van onderdelen
        JSONObject res = new JSONObject().put("archive", "backup-2026-11-25_0330.rtb")
                .put("blok", new JSONObject().put("archief", 3_400_000L).put("nieuw", 2).put("nieuwBytes", 900_000L).put("hergebruikt", 812).put("hergebruiktBytes", 190_000_000L))
                .put("notes", new JSONObject().put("ok", true).put("o", 20_000L)).put("sms", new JSONObject().put("ok", true).put("o", 700_000L))
                .put("kluis", new JSONObject().put("ok", true).put("o", 0L)).put("rotated", new JSONObject().put("count", 1));
        String regel = AllBackup.samenvatting(res);
        check(regel.equals("backup klaar: archief 3321 kB, grote bestanden nieuw 2 (879 kB), hergebruikt 812 (181 MB); erbij: sms 684 kB, notes 20 kB; 1 oude weg"), "samenvatting: " + regel);
        check(AllBackup.samenvatting(new JSONObject().put("archive", "x.rtb")).equals("backup klaar"), "zonder grote bestanden: kort");
        check(!Logboek.probleem("BACKUP", regel) && Logboek.probleem("BACKUP", "nachtelijke backup mislukt, poging 1, straks opnieuw"), "geslaagd is geen probleem, mislukt wel");
        res.put("sms", new JSONObject().put("ok", false));
        check(AllBackup.samenvatting(res).startsWith("backup klaar met 1 fout(en): ") && AllBackup.samenvatting(res.put("stopped", true)).startsWith("backup gestopt: "), "met fouten of gestopt: zo genoemd");

        if (fails > 0) { System.out.println(fails + " fout(en)"); System.exit(1); }
        System.out.println("AllBackupJobTest ok");
    }
}
