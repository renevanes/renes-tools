package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

/** Door factuurlijsten lopen. Zonder WebView: wachttijden, vervangen/uitklappen en teruggaan zijn zo te testen. */
final class FacturenLijst {
    enum Stap { WACHT, DOWNLOAD, MEER, VOLGENDE, KLAAR, FOUT }
    static final long TIMEOUT = 30_000, STABIEL = 1000;
    final int max;
    final java.util.List<String> pad = new java.util.ArrayList<>(), acties = new java.util.ArrayList<>();
    JSONArray knoppen = new JSONArray();
    int index, aantal;
    volatile int downloadToken;
    final java.util.concurrent.atomic.AtomicInteger afgerondToken = new java.util.concurrent.atomic.AtomicInteger();
    String kenmerk = "", fout = "", kandidaat = "", vanaf = "", actie = "";
    long sinds, gelijkSinds;
    boolean onderweg, verwerkt, herstellen, volledig;

    FacturenLijst(int max, long nu) { this.max = max; wacht(nu); }

    void wacht(long nu) { sinds = nu; kandidaat = ""; gelijkSinds = nu; }
    void paginaKlaar(long nu) { verwerkt = true; wacht(nu); }
    void herstel(long nu) { herstellen = true; onderweg = false; wacht(nu); }
    void geklikt(Stap stap, String bron, long nu) {
        actie = stap == Stap.MEER ? "meer" : "volgende";
        vanaf = bron; onderweg = true; wacht(nu);
    }
    Stap mis(String reden) { fout = reden; return Stap.FOUT; }

    Stap bekijk(JSONObject s, long nu) {
        JSONArray l = s.optJSONArray("knoppen");
        String k = s.optString("kenmerk");
        if (l == null || l.length() == 0 || k.isEmpty() || s.optBoolean("bezig") || (onderweg && k.equals(vanaf))) {
            kandidaat = "";
            return nu - sinds >= TIMEOUT ? mis("laden") : Stap.WACHT;
        }
        if (!k.equals(kandidaat)) { kandidaat = k; gelijkSinds = nu; }
        if (nu - gelijkSinds < STABIEL) return nu - sinds >= TIMEOUT ? mis("laden") : Stap.WACHT;
        onderweg = false;
        if (herstellen) {
            if (k.equals(kenmerk)) { knoppen = l; herstellen = false; return Stap.DOWNLOAD; }
            int p = pad.indexOf(k);
            if (p < 0 || p + 1 >= pad.size()) return mis("herstel");
            return "meer".equals(acties.get(p + 1)) ? Stap.MEER : Stap.VOLGENDE;
        }
        if (verwerkt) {
            if (!kenmerk.equals(k)) return mis("gewijzigd");
            if (s.optBoolean("meer") || s.optBoolean("volgende")) {
                if (aantal >= max) return mis("limiet");
                if (pad.size() >= 100) return mis("herhalen");
                return s.optBoolean("meer") ? Stap.MEER : Stap.VOLGENDE;
            }
            volledig = true;
            return Stap.KLAAR;
        }
        if (pad.contains(k)) return mis("herhalen");
        // Toon meer voegt meestal achteraan toe; de eerste regels zijn dan al gedownload.
        int begin = 0;
        if ("meer".equals(actie) && l.length() > knoppen.length()) {
            while (begin < knoppen.length() && knoppen.optJSONObject(begin).optString("sleutel")
                    .equals(l.optJSONObject(begin).optString("sleutel"))) begin++;
            if (begin != knoppen.length()) begin = 0;
        }
        knoppen = l; index = begin; kenmerk = k;
        pad.add(k); acties.add(actie); actie = "";
        return Stap.DOWNLOAD;
    }

    /** Voor elke werkelijk aangeboden download, ook als het resultaat geen PDF is. */
    boolean beginKnop() {
        if (aantal >= max) { fout = "limiet"; return false; }
        aantal++; downloadToken++; return true;
    }

    void resultaat(int token) { if (token > 0 && token == downloadToken) afgerondToken.set(token); }
    boolean ontvangen() { return downloadToken > 0 && afgerondToken.get() == downloadToken; }

    void volgendeLijst() { verwerkt = false; }
}
