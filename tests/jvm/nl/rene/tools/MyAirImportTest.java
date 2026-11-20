package nl.rene.tools;

import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class MyAirImportTest {
    private static final String HEADER = "\uFEFFsession_date,Gebruiksuren,Slaapscore,leak_score,leak_95_percentile,ahi,mask_session_count,record_delete_ind,extra\r\n";
    private static final String ROW = "2025-02-20,7.5166666667,93,17,22.7995,7.401,4,N,\"niet bewaren, bevat tekst\"\r\n";

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    private static ByteArrayInputStream zip(String name, String csv) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("PATIENT.csv"));
            zip.write("Identificatie".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(name));
            zip.write(csv.getBytes(StandardCharsets.UTF_8));
        }
        return new ByteArrayInputStream(bytes.toByteArray());
    }

    public static void main(String[] args) throws Exception {
        JSONObject imported = MyAirImport.read(zip("myAir/SLEEP_RECORD.csv", HEADER + ROW
                + "2025-02-21,0,20,20,,,0,N,\r\n2025-02-22,2,80,20,0,0,1,Y,\r\n"));
        check(imported.length() == 2, "Verwijderde nachten worden overgeslagen");
        JSONObject used = imported.getJSONObject("2025-02-20");
        check(used.getInt("usage") == 451 && used.getInt("score") == 93 && used.getInt("seal") == 17,
                "Gebruiksuren worden afgerond op minuten");
        check(used.getDouble("ahi") == 7.4 && used.getDouble("leak95") == 22.8 && used.getInt("sessions") == 4
                && !used.has("leak") && !used.has("off") && !used.has("extra"), "Exportwaarden behouden hun betekenis");
        JSONObject unused = imported.getJSONObject("2025-02-21");
        check(unused.getInt("usage") == 0 && !unused.has("ahi") && !unused.has("seal"),
                "Een nacht zonder gebruik heeft geen AHI of maskerafdichting");
        JSONObject saved = new JSONObject().put("2025-02-20", new JSONObject(used.toString()).put("mask", "AirFit P10"));
        JSONObject first = CpapLog.merge(saved, imported);
        check(first.getInt("added") == 1 && first.getInt("existing") == 1, "Import telt nieuwe en bestaande nachten");
        check(saved.getJSONObject("2025-02-20").getString("mask").equals("AirFit P10"), "Handmatige gegevens blijven behouden");
        check(CpapLog.merge(saved, imported).getInt("added") == 0, "Opnieuw importeren maakt geen duplicaten");
        JSONObject root = new JSONObject().put("version", 1).put("nights", saved);
        check(CpapLog.normalize(root.toString()).getJSONObject("nights").getJSONObject("2025-02-20")
                .getDouble("leak95") == 22.8, "Versleutelde opslag en herstel herkennen nieuwe velden");
        try {
            MyAirImport.read(zip("SLEEP_RECORD.csv", HEADER + ROW + ROW));
            throw new AssertionError("Dubbele datum geaccepteerd");
        } catch (IOException expected) { }
        try {
            MyAirImport.read(zip("HEALTH_METRICS.csv", HEADER + ROW));
            throw new AssertionError("Archief zonder nachten geaccepteerd");
        } catch (IOException expected) { }
    }
}
