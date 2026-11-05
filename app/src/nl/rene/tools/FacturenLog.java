package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

/** Begrensd lokaal diagnosespoor: vaste gebeurtenissen en aantallen, nooit websitegegevens of geheimen. */
final class FacturenLog {
    enum Stap { START, INLOG, FACTUREN, LIJST, MEER, VOLGENDE, WACHT, DOWNLOAD, HTTP, DOWNLOADFOUT, PDF, HERSTEL,
        VOLLEDIG, LIMIET, LAADFOUT, HERHALING, GEWIJZIGD, HERSTELFOUT, OPSLAAN, KLAAR, FOUT, STOP }
    static final int MAX = 160;
    final long start;
    final java.util.LinkedList<JSONObject> regels = new java.util.LinkedList<>();
    int weggelaten;

    FacturenLog(long start) { this.start = start; }

    synchronized void voeg(Stap stap, long nu, int pagina, int aantal) {
        try {
            JSONObject r = new JSONObject().put("ms", Math.max(0, nu - start)).put("stap", stap.name())
                    .put("pagina", Math.max(0, pagina)).put("aantal", Math.max(0, aantal));
            if (!regels.isEmpty()) {
                JSONObject vorig = regels.getLast();
                if (vorig.optString("stap").equals(stap.name()) && vorig.optInt("pagina") == pagina && vorig.optInt("aantal") == aantal) return;
            }
            if (regels.size() >= MAX) { regels.removeFirst(); weggelaten++; }
            regels.add(r);
        } catch (Exception ignored) { }
    }

    synchronized JSONObject json() {
        try { return new JSONObject().put("t", start).put("versie", Version.NAME).put("native", Version.NATIVE_LEVEL)
                .put("weggelaten", weggelaten).put("stappen", new JSONArray(regels)); }
        catch (Exception ignored) { return new JSONObject(); }
    }
}
