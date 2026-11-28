package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Terug en weer vooruit door wat er afgespeeld is (wens van Rene, 28-11-2026: terug kunnen naar wat al gespeeld is, ook
 * verder terug). De lijst "recent gestart" zet wat je terug-speelt zelf weer vooraan; daarom onthoudt deze klasse bij de
 * eerste stap terug de volgorde van dat moment (de rij) en waar je daarin bent. Zolang wat er speelt de aflevering op die
 * plek is, gaat terug steeds een stap verder terug en vooruit weer dezelfde weg terug. Speelt er iets anders (zelf
 * gekozen, of de speler ging vanzelf door), dan begint de rij opnieuw. Zuiver; getest in PodcastTerugTest.
 */
final class PodcastTerug {
    private PodcastTerug() { }

    static final int MAX = 40;

    static final class Stand {
        final List<String> rij; final int i;
        Stand(List<String> rij, int i) { this.rij = rij; this.i = i; }
        boolean klopt(String nu) { return i >= 0 && i < rij.size() && rij.get(i).equals(nu == null ? "" : nu); }
    }

    static final class Stap {
        /** De aflevering om af te spelen, of null als er niets (meer) is. */
        final String key; final Stand stand;
        Stap(String key, Stand stand) { this.key = key; this.stand = stand; }
    }

    /** Eén stap terug. recent = de sleutels van recent gestart, nieuwste eerst; nu = wat er speelt ("" = niets). */
    static Stap terug(List<String> recent, String nu, Stand s) {
        nu = nu == null ? "" : nu;
        if (s == null || !s.klopt(nu)) s = nieuw(recent, nu);
        Set<String> er = new HashSet<>(recent);
        for (int j = s.i + 1; j < s.rij.size(); j++) if (er.contains(s.rij.get(j))) return new Stap(s.rij.get(j), new Stand(s.rij, j));
        return new Stap(null, s);
    }

    /** Eén stap vooruit in dezelfde rij (alleen na terug); key null = er is geen vooruit: dan kiest de speler zelf. */
    static Stap vooruit(List<String> recent, String nu, Stand s) {
        if (s == null || !s.klopt(nu)) return new Stap(null, null);
        Set<String> er = new HashSet<>(recent);
        for (int j = s.i - 1; j >= 0; j--) if (er.contains(s.rij.get(j))) return new Stap(s.rij.get(j), new Stand(s.rij, j));
        return new Stap(null, s);
    }

    /** Een nieuwe rij: wat er nu speelt vooraan, daarachter wat eerder gestart is. */
    static Stand nieuw(List<String> recent, String nu) {
        List<String> rij = new ArrayList<>();
        rij.add(nu == null ? "" : nu);
        for (String k : recent) if (k != null && !k.isEmpty() && !rij.contains(k) && rij.size() < MAX) rij.add(k);
        return new Stand(rij, 0);
    }

    static String json(Stand s) {
        if (s == null) return "";
        try { return new JSONObject().put("i", s.i).put("rij", new JSONArray(s.rij)).toString(); } catch (Exception e) { return ""; }
    }

    /** null bij iets onleesbaars. */
    static Stand lees(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(json); JSONArray a = o.getJSONArray("rij");
            List<String> rij = new ArrayList<>();
            for (int j = 0; j < a.length() && j < MAX; j++) rij.add(a.optString(j));
            int i = o.optInt("i", -1);
            return i < 0 || i >= rij.size() ? null : new Stand(rij, i);
        } catch (Exception e) { return null; }
    }

    /**
     * Terugspoelen aan het begin van een aflevering gaat door in het einde van de vorige: de plek daar (ms), of -1 als dat
     * niet kan (duur onbekend of te kort). terug = hoeveel ms terug.
     */
    static long plekAanEinde(long duur, long terug) {
        if (duur < 30_000) return -1;
        return Math.max(0, duur - Math.max(5_000, terug) - 5_000); // ruim voor het einde: anders telt hij meteen als afgelopen
    }

    /** Aan het begin (hooguit zoveel ms ver) gaat terugspoelen de vorige aflevering in. */
    static final long BEGIN_MS = 3_000;
    /** Niet als de vorige druk op terugspoelen korter dan dit geleden is: snel drukken naar het begin springt niet door. */
    static final long RUST_MS = 1_500;

    static boolean overBegin(long plek, long sindsVorigeDruk) { return plek <= BEGIN_MS && (sindsVorigeDruk < 0 || sindsVorigeDruk >= RUST_MS); }
}
