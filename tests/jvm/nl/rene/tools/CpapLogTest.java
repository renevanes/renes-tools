package nl.rene.tools;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

public final class CpapLogTest {
    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    private static void reject(JSONObject night) throws Exception {
        try {
            CpapLog.cleanNight(night);
            throw new AssertionError("Onjuiste nacht geaccepteerd");
        } catch (JSONException expected) { }
    }

    private static JSONObject night() throws JSONException {
        return new JSONObject().put("day", "2024-02-29").put("mask", " AirFit P10 ").put("leak", 0).put("ahi", 1.25);
    }

    public static void main(String[] args) throws Exception {
        Locale old = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("th-TH"));
            check(CpapLog.validDay("2026-11-19"), "ISO-datums blijven geldig met de Thaise toestelregio");
        } finally { Locale.setDefault(old); }
        check(CpapLog.validDay("2024-02-29"), "Schrikkeldag blijft behouden");
        check(!CpapLog.validDay("2025-02-29") && !CpapLog.validDay("2026-11-31"), "Onmogelijke datums worden geweigerd");
        reject(night().put("mask", " "));
        reject(night().put("mask", "Naam\u202e"));
        reject(night().put("seal", 20.5));
        reject(night().put("score", 101));
        reject(night().put("usage", 1441));
        reject(night().put("leak", -1));
        reject(night().put("leak", "4"));
        reject(new JSONObject().put("day", "2024-02-29").put("mask", "AirFit P10"));

        JSONObject original = new JSONObject().put("version", 1).put("nights", new JSONObject()
                .put("2024-02-29", night().put("score", JSONObject.NULL).put("extra", "ongebruikt")));
        byte[] bron = original.toString().getBytes(StandardCharsets.UTF_8);
        File temp = Files.createTempDirectory("cpap-test").toFile();
        try {
            AppBackup.Ctx restore = new AppBackup.Ctx(null, temp, new AppBackup.Staged());
            AppData.Entry entry = AppBackup.stageEntry(restore, new ByteArrayInputStream(bron),
                    AppData.CPAP, AppData.target(AppData.CPAP), new byte[128]);
            check(entry.size == bron.length && restore.cpap != null, "Backup-lezer kiest het CPAP-logboek");
            JSONObject normal = CpapLog.normalize(new String(restore.cpap, StandardCharsets.UTF_8));
            JSONObject terug = normal.getJSONObject("nights").getJSONObject("2024-02-29");
            check(terug.getString("mask").equals("AirFit P10") && terug.getDouble("ahi") == 1.25
                    && terug.getDouble("leak") == 0, "Herstel houdt maskernaam, decimaal en gemeten nul");
            check(CpapLog.same(terug, terug.toString()) && !CpapLog.same(terug, night().put("ahi", 2).toString())
                    && !CpapLog.same(null, terug.toString()), "Verouderde versies overschrijven of verwijderen geen gewijzigde meting");
            check(!terug.has("score") && !terug.has("extra"), "Een ontbrekende meting blijft afwezig");
            check(CpapLog.normalize(normal.toString()).toString().equals(normal.toString()), "Opslagformaat is stabiel");
            restore.wipeSecrets();
            for (byte b : restore.cpap) check(b == 0, "Herstel wist de leesbare bufferversie");
        } finally {
            new File(temp, "files").delete();
            new File(temp, "kluis").delete();
            temp.delete();
        }
    }
}
