package nl.rene.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ID-kopie afschermen: waar staan de velden die je meestal afschermt? Zuiver (IdVeldenTest): uit de woorden van de
 * tekstherkenning met hun plek (delen van de pagina, 0..1) en de gevonden gezichten. Alles is een voorstel dat Rene
 * controleert; niets hiervan komt in het logboek.
 */
final class IdVelden {
    private IdVelden() {}
    static final String STROOK = "strook", BSN = "bsn", NUMMER = "documentnummer", FOTO = "pasfoto";
    static final int MAX = 20;

    static final class Woord {
        final String t; final double x0, y0, x1, y1; final int regel;
        Woord(String t, double x0, double y0, double x1, double y1, int regel) { this.t = t == null ? "" : t; this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1; this.regel = regel; }
    }
    static final class Veld {
        final String veld; final double x, y, w, h;
        Veld(String veld, double x0, double y0, double x1, double y1) {
            x0 = Math.max(0, Math.min(1, x0)); y0 = Math.max(0, Math.min(1, y0)); x1 = Math.max(x0, Math.min(1, x1)); y1 = Math.max(y0, Math.min(1, y1));
            this.veld = veld; x = x0; y = y0; w = x1 - x0; h = y1 - y0;
        }
        boolean overlapt(Veld o) { return x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h; }
    }

    /** Elfproef van een burgerservicenummer (negen cijfers). */
    static boolean elfproef(String s) {
        if (s == null || s.length() != 9) return false; int som = 0;
        for (int i = 0; i < 9; i++) { char c = s.charAt(i); if (c < '0' || c > '9') return false; som += (c - '0') * (i == 8 ? -1 : 9 - i); }
        return som % 11 == 0 && !s.equals("000000000");
    }
    private static String kaal(String s) {
        int a = 0, b = s.length();
        while (a < b && !Character.isLetterOrDigit(s.charAt(a)) && s.charAt(a) != '<') a++;
        while (b > a && !Character.isLetterOrDigit(s.charAt(b - 1)) && s.charAt(b - 1) != '<') b--;
        return s.substring(a, b);
    }
    private static boolean cijfers(String s) { if (s.isEmpty()) return false; for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return false; return true; }
    /** Een regel van de machineleesbare strook: lang, alleen hoofdletters, cijfers en vultekens (de tekstherkenning leest &lt; ook als « of K). */
    static boolean strookRegel(String regel) {
        String s = regel.replace(" ", "").replace('«', '<'); if (s.length() < 22) return false;
        int vul = 0, goed = 0;
        for (int i = 0; i < s.length(); i++) { char c = s.charAt(i); if (c == '<') { vul++; goed++; } else if (c >= 'A' && c <= 'Z' || c >= '0' && c <= '9') goed++; }
        return goed >= s.length() * 0.9 && (vul >= 3 || s.length() >= 28 && s.contains("NLD") && vul >= 1);
    }
    /** Documentnummer van een Nederlands paspoort of identiteitskaart (twee letters, zes letters of cijfers, een cijfer) of rijbewijs (tien cijfers). */
    static boolean nummer(String s) {
        if (s.length() == 10 && cijfers(s)) return true;
        if (s.length() != 9 || !Character.isDigit(s.charAt(8))) return false;
        for (int i = 0; i < 8; i++) { char c = s.charAt(i); boolean letter = c >= 'A' && c <= 'Z'; if (i < 2 ? !letter : !(letter || c >= '0' && c <= '9')) return false; }
        return true;
    }

    /** Soort document volgens de gelezen tekst: paspoort, idkaart, rijbewijs of leeg. */
    static String soort(List<Woord> woorden) {
        StringBuilder alles = new StringBuilder();
        for (Woord w : woorden) alles.append(w.t.toUpperCase(Locale.ROOT)).append(' ');
        String s = alles.toString();
        if (s.contains("RIJBEWIJS") || s.contains("DRIVING LICEN")) return "rijbewijs";
        if (s.contains("IDENTITEITSKAART") || s.contains("IDENTITY CARD")) return "idkaart";
        if (s.contains("PASPOORT") || s.contains("PASSPORT") || s.contains("P<NLD")) return "paspoort";
        if (s.contains("I<NLD") || s.contains("IDNLD")) return "idkaart";
        // geen opschrift gelezen: de vorm van de strook zegt het (twee lange regels = paspoort, drie korte = kaart)
        // alleen bij een duidelijke vorm: een half gelezen strook zegt te weinig
        Strook st = strook(regels(woorden));
        return st == null ? "" : st.regels >= 3 ? "idkaart" : st.regels == 2 && st.tekens >= 38 ? "paspoort" : "";
    }

    /** De strook in de gelezen regels: welke regels, waar (ook links en rechts) en hoe lang de langste regel is. */
    static final class Strook {
        boolean[] in; double y0 = 2, y1 = -1, x0 = 2, x1 = -1, hoog; int regels, tekens;
        /** Een paspoort heeft twee regels van 44 tekens, een kaart drie van 30. */
        String soort() { return regels >= 3 ? "idkaart" : tekens >= 38 ? "paspoort" : tekens <= 32 ? "idkaart" : regels == 2 ? "paspoort" : "idkaart"; }
    }
    static Strook strook(List<List<Woord>> regels) {
        Strook s = new Strook(); s.in = new boolean[regels.size()];
        for (int i = 0; i < regels.size(); i++) {
            List<Woord> r = regels.get(i); StringBuilder t = new StringBuilder(); double y0 = 2, y1 = -1, x0 = 2, x1 = -1;
            for (Woord w : r) { t.append(w.t.toUpperCase(Locale.ROOT)); y0 = Math.min(y0, w.y0); y1 = Math.max(y1, w.y1); x0 = Math.min(x0, w.x0); x1 = Math.max(x1, w.x1); }
            if (!strookRegel(t.toString())) continue;
            s.in[i] = true; s.regels++; s.tekens = Math.max(s.tekens, t.toString().replace(" ", "").length());
            s.y0 = Math.min(s.y0, y0); s.y1 = Math.max(s.y1, y1); s.x0 = Math.min(s.x0, x0); s.x1 = Math.max(s.x1, x1); s.hoog = Math.max(s.hoog, y1 - y0);
        }
        return s.regels == 0 ? null : s;
    }

    /** Waar de pagina met de gegevens ligt, als deel van de foto, met het soort document. */
    static final class Pagina {
        final double x, y, w, h; final String soort;
        Pagina(double x, double y, double w, double h, String soort) { this.x = x; this.y = y; this.w = w; this.h = h; this.soort = soort; }
    }
    /**
     * De pagina afgeleid uit de strook: die loopt over bijna de hele breedte en staat onderaan. Zo klopt de vaste indeling
     * ook als de foto meer toont dan die ene pagina: een opengeklapt paspoort (twee pagina's boven elkaar) of een kaart
     * met tafel eromheen. verhouding = breedte / hoogte van de foto. null als er geen bruikbare strook is.
     */
    static Pagina pagina(List<Woord> woorden, double verhouding) {
        Strook s = strook(regels(woorden)); if (s == null || verhouding <= 0) return null;
        double tekst = s.x1 - s.x0; if (tekst < 0.25) return null; // te weinig van de strook gelezen om de breedte te schatten
        String soort = s.soort(); boolean pas = soort.equals("paspoort"); double vorm = pas ? 125.0 / 88.0 : 85.6 / 54.0;
        // de strook vult 0,9 van de breedte als hij helemaal gelezen is; een duidelijk te korte regel = geen schatting
        double deel = Math.min(1, s.tekens / (pas ? 44.0 : 30.0)); if (deel < 0.8) return null;
        double w = Math.min(1, tekst / (0.9 * deel)), midden = (s.x0 + s.x1) / 2, x0 = Math.max(0, midden - w / 2), x1 = Math.min(1, midden + w / 2);
        w = x1 - x0; double h = w * verhouding / vorm, onder = Math.min(1, s.y1 + h * 0.05), boven = Math.max(0, onder - h);
        return new Pagina(x0, boven, w, onder - boven, soort);
    }

    /**
     * De velden op de pagina. gezichten: per gezicht {midden x, midden y (tussen de ogen), afstand tussen de ogen}, als deel
     * van de breedte en hoogte; verhouding = breedte / hoogte van de pagina.
     */
    static List<Veld> uit(List<Woord> woorden, double[][] gezichten, double verhouding) {
        List<Veld> uit = new ArrayList<>();
        if (verhouding <= 0) verhouding = 1.5;
        // 1. de strook: opeenvolgende regels die eruitzien als MRZ; over de hele breedte
        List<List<Woord>> regels = regels(woorden); Strook st = strook(regels);
        boolean[] inStrook = st == null ? new boolean[regels.size()] : st.in;
        double sy0 = st == null ? 2 : st.y0, sy1 = st == null ? -1 : st.y1, hoog = st == null ? 0 : st.hoog;
        Veld strook = null;
        // ruim: een regel die slecht gelezen is (erboven of eronder) moet ook weg; de strook staat onderaan, dus daar tot de rand
        if (sy1 > sy0) { strook = new Veld(STROOK, 0, sy0 - hoog * 1.6, 1, sy1 > 0.55 ? 1 : sy1 + hoog * 1.6); uit.add(strook); }
        // 2. nummers buiten de strook
        for (int i = 0; i < regels.size(); i++) {
            if (inStrook[i]) continue; List<Woord> r = regels.get(i);
            for (int a = 0; a < r.size() && uit.size() < MAX; a++) {
                Woord w = r.get(a); String s = kaal(w.t).toUpperCase(Locale.ROOT);
                if (elfproef(s) || s.length() > 9 && !Character.isDigit(s.charAt(s.length() - 10)) && elfproef(s.substring(s.length() - 9))) { uit.add(om(BSN, w.x0, w.y0, w.x1, w.y1)); continue; } // ook "BSN:111222333" als één woord
                if (nummer(s)) { uit.add(om(NUMMER, w.x0, w.y0, w.x1, w.y1)); continue; }
                // negen cijfers in stukjes ("1234 56 789")
                if (!cijfers(s) || s.length() >= 9) continue;
                StringBuilder n = new StringBuilder(s); double x1 = w.x1, y0 = w.y0, y1 = w.y1; int b = a + 1;
                while (b < r.size() && n.length() < 9) { String v = kaal(r.get(b).t); if (!cijfers(v) || r.get(b).x0 - x1 > (w.y1 - w.y0) * 2 / verhouding) break; n.append(v); x1 = r.get(b).x1; y0 = Math.min(y0, r.get(b).y0); y1 = Math.max(y1, r.get(b).y1); b++; }
                if (b > a + 1 && elfproef(n.toString())) { uit.add(om(BSN, w.x0, y0, x1, y1)); a = b - 1; }
            }
        }
        // 3. het nummer achter of onder een opschrift (ook als de elfproef door een leesfout niet klopt)
        for (int i = 0; i < regels.size() && uit.size() < MAX; i++) {
            if (inStrook[i]) continue; List<Woord> r = regels.get(i);
            for (int a = 0; a < r.size(); a++) {
                String s = r.get(a).t.toLowerCase(Locale.ROOT);
                if (!(s.contains("burgerservice") || s.equals("bsn") || s.startsWith("bsn") || s.contains("persoonsnummer") || s.contains("personal") && a + 1 < r.size() && r.get(a + 1).t.toLowerCase(Locale.ROOT).startsWith("no"))) continue;
                Woord beste = null; Woord label = r.get(a);
                for (int b = a + 1; b < r.size(); b++) if (veelCijfers(r.get(b).t)) { beste = r.get(b); break; }
                if (beste == null && i + 1 < regels.size() && !inStrook[i + 1]) for (Woord w : regels.get(i + 1)) if (veelCijfers(w.t) && w.x1 > label.x0 - 0.05 && w.x0 < label.x1 + 0.3) { beste = w; break; }
                if (beste == null) continue;
                Veld v = om(BSN, beste.x0, beste.y0, beste.x1, beste.y1); boolean dubbel = false;
                for (Veld o : uit) if (o.veld.equals(BSN) && o.overlapt(v)) dubbel = true;
                if (!dubbel) uit.add(v);
                break;
            }
        }
        // 4. de pasfoto: ruim om het gezicht (ook de kleine tweede foto)
        if (gezichten != null) for (double[] g : gezichten) {
            if (g == null || g.length < 3 || g[2] <= 0 || uit.size() >= MAX) continue;
            double d = g[2], dy = d * verhouding; // de afstand tussen de ogen als deel van de hoogte
            Veld v = new Veld(FOTO, g[0] - d * 1.9, g[1] - dy * 2.4, g[0] + d * 1.9, g[1] + dy * 2.8); // ook haar, oren en kin
            if (strook != null && v.y + v.h > strook.y) v = new Veld(FOTO, v.x, v.y, v.x + v.w, Math.max(v.y, strook.y));
            if (v.w > 0.02 && v.h > 0.02) uit.add(v);
        }
        return uit;
    }
    private static boolean veelCijfers(String t) { int n = 0; String s = kaal(t); for (int i = 0; i < s.length(); i++) if (Character.isDigit(s.charAt(i))) n++; return n >= 7 && n >= s.length() - 2; }
    /** Een vlak ruim om een woord: een derde van de hoogte erbij, zodat ook de randen van de cijfers weg zijn. */
    private static Veld om(String veld, double x0, double y0, double x1, double y1) { double m = (y1 - y0) * 0.35; return new Veld(veld, x0 - m, y0 - m, x1 + m, y1 + m); }
    /** Woorden per regel (regelnummer van de tekstherkenning), van links naar rechts. */
    private static List<List<Woord>> regels(List<Woord> woorden) {
        List<List<Woord>> uit = new ArrayList<>(); int vorige = Integer.MIN_VALUE; List<Woord> nu = null;
        for (Woord w : woorden) { if (nu == null || w.regel != vorige) { nu = new ArrayList<>(); uit.add(nu); vorige = w.regel; } nu.add(w); }
        for (List<Woord> r : uit) java.util.Collections.sort(r, (a, b) -> Double.compare(a.x0, b.x0));
        return uit;
    }
}
