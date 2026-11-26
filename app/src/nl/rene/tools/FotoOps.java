package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Een bewerking van een foto (Foto bewerken, ook bij Stamgegevens en ID-kopie): eerst een kwartslag draaien, dan het vlak
 * (vier hoeken, ook schuin of scheef) rechttrekken tot een rechthoek, dan licht en kleur, dan zwarte vlakken. Zuiver, zonder
 * Android, zodat de JVM-test het kan nalopen; FotoBewerk past het toe op de pixels, fbRender in 02-fotobewerken.js rekent
 * op dezelfde manier voor het voorbeeld. Alles komt uit de interface: hier wordt elk getal gecontroleerd.
 *
 * JSON: {v:1, draai:0|90|180|270, q:[[x,y]×4] (linksboven, rechtsboven, rechtsonder, linksonder; deel 0..1 van de gedraaide
 * foto), verhouding: 0 of breedte/hoogte, licht, contrast: -100..100, niveau:[laag,hoog], grijs, vlakken:[[x,y,b,h]]}.
 */
final class FotoOps {
    static final int MAX_JSON = 20000, MAX_VLAKKEN = 60;

    final int draai;
    /** x0,y0, x1,y1, x2,y2, x3,y3 (deel van de gedraaide foto). */
    final double[] q;
    final double verhouding;
    final int licht, contrast, lo, hi;
    final boolean grijs;
    final double[][] vlakken;

    private FotoOps(int draai, double[] q, double verhouding, int licht, int contrast, int lo, int hi, boolean grijs, double[][] vlakken) {
        this.draai = draai; this.q = q; this.verhouding = verhouding; this.licht = licht; this.contrast = contrast;
        this.lo = lo; this.hi = hi; this.grijs = grijs; this.vlakken = vlakken;
    }

    static final double[] HEEL = {0, 0, 1, 0, 1, 1, 0, 1};

    /** Geen bewerking: de foto zoals hij is. */
    static FotoOps geen() { return new FotoOps(0, HEEL.clone(), 0, 0, 0, 0, 255, false, new double[0][]); }

    static final class Fout extends Exception { Fout(String m) { super(m); } }

    /** Lezen en controleren; leeg of null = geen bewerking. Fout = een Nederlandse zin (Taal.T) voor de interface. */
    static FotoOps lees(String json) throws Fout {
        if (json == null || json.trim().isEmpty() || "{}".equals(json.trim())) return geen();
        if (json.length() > MAX_JSON) throw new Fout(Taal.T("Ongeldige bewerking"));
        try {
            JSONObject o = new JSONObject(json);
            double dr = getal(o, "draai", 0); int draai = (int) dr;
            if (dr != draai || draai != 0 && draai != 90 && draai != 180 && draai != 270) throw new Fout(Taal.T("Ongeldige bewerking"));
            double[] q = HEEL.clone();
            JSONArray a = lijst(o, "q");
            if (a != null) {
                if (a.length() != 4) throw new Fout(Taal.T("Ongeldige bewerking"));
                for (int i = 0; i < 4; i++) {
                    JSONArray p = a.getJSONArray(i);
                    if (p.length() != 2) throw new Fout(Taal.T("Ongeldige bewerking"));
                    for (int k = 0; k < 2; k++) {
                        double v = p.getDouble(k);
                        if (!(v >= -0.001 && v <= 1.001)) throw new Fout(Taal.T("Ongeldige bewerking"));
                        q[i * 2 + k] = Math.max(0, Math.min(1, v));
                    }
                }
            }
            if (!bol(q) || oppervlak(q) < 0.0004) throw new Fout(Taal.T("Dit vlak lukt niet. Zet de hoeken op de hoeken van wat je wilt houden."));
            double verh = getal(o, "verhouding", 0);
            if (Double.isNaN(verh) || verh < 0 || (verh != 0 && (verh < 0.2 || verh > 5))) throw new Fout(Taal.T("Ongeldige bewerking"));
            int licht = (int) Math.round(getal(o, "licht", 0)), contrast = (int) Math.round(getal(o, "contrast", 0));
            if (licht < -100 || licht > 100 || contrast < -100 || contrast > 100) throw new Fout(Taal.T("Ongeldige bewerking"));
            int lo = 0, hi = 255;
            JSONArray n = lijst(o, "niveau");
            if (n != null) {
                if (n.length() != 2) throw new Fout(Taal.T("Ongeldige bewerking"));
                lo = n.getInt(0); hi = n.getInt(1);
                if (lo < 0 || lo > 200 || hi > 255 || hi - lo < 20) throw new Fout(Taal.T("Ongeldige bewerking"));
            }
            JSONArray vl = lijst(o, "vlakken");
            int nv = vl == null ? 0 : vl.length();
            if (nv > MAX_VLAKKEN) throw new Fout(Taal.T("Maximaal 60 vlakken per pagina"));
            double[][] vlakken = new double[nv][];
            for (int i = 0; i < nv; i++) {
                JSONArray r = vl.getJSONArray(i);
                if (r.length() != 4) throw new Fout(Taal.T("Ongeldig afschermvlak"));
                double x = r.getDouble(0), y = r.getDouble(1), w = r.getDouble(2), h = r.getDouble(3);
                if (!(x >= 0 && y >= 0 && w > 0 && h > 0 && x + w <= 1.0000001 && y + h <= 1.0000001)) throw new Fout(Taal.T("Ongeldig afschermvlak"));
                vlakken[i] = new double[]{x, y, w, h};
            }
            return new FotoOps(draai, q, verh, licht, contrast, lo, hi, o.optBoolean("grijs", false), vlakken);
        } catch (Fout f) { throw f; }
        catch (Exception e) { throw new Fout(Taal.T("Ongeldige bewerking")); }
    }

    /** Een getal, of de standaard als het veld er niet is; iets anders dan een getal is een fout. */
    private static double getal(JSONObject o, String naam, double standaard) throws Fout {
        if (!o.has(naam) || o.isNull(naam)) return standaard;
        Object v = o.opt(naam);
        if (!(v instanceof Number)) throw new Fout(Taal.T("Ongeldige bewerking"));
        double d = ((Number) v).doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) throw new Fout(Taal.T("Ongeldige bewerking"));
        return d;
    }
    private static JSONArray lijst(JSONObject o, String naam) throws Fout {
        if (!o.has(naam) || o.isNull(naam)) return null;
        JSONArray a = o.optJSONArray(naam);
        if (a == null) throw new Fout(Taal.T("Ongeldige bewerking"));
        return a;
    }

    /** Bol en met de klok mee (y naar beneden)? Anders zijn de hoeken over elkaar gesleept. Zelfde regel als ksBol. */
    static boolean bol(double[] q) {
        for (int i = 0; i < 4; i++) {
            double ax = q[i * 2], ay = q[i * 2 + 1], bx = q[(i + 1) % 4 * 2], by = q[(i + 1) % 4 * 2 + 1], cx = q[(i + 2) % 4 * 2], cy = q[(i + 2) % 4 * 2 + 1];
            if ((bx - ax) * (cy - by) - (by - ay) * (cx - bx) <= 1e-9) return false;
        }
        return true;
    }
    static double oppervlak(double[] q) {
        double s = 0;
        for (int i = 0; i < 4; i++) { int j = (i + 1) % 4; s += q[i * 2] * q[j * 2 + 1] - q[j * 2] * q[i * 2 + 1]; }
        return Math.abs(s) / 2;
    }

    /** Is er niets te doen? */
    boolean leeg() {
        if (draai != 0 || verhouding != 0 || licht != 0 || contrast != 0 || lo != 0 || hi != 255 || grijs || vlakken.length > 0) return false;
        for (int i = 0; i < 8; i++) if (Math.abs(q[i] - HEEL[i]) > 1e-6) return false;
        return true;
    }
    boolean kleurLeeg() { return licht == 0 && contrast == 0 && lo == 0 && hi == 255 && !grijs; }

    /** Breedte en hoogte van de foto na de kwartslag. */
    int[] gedraaid(int w, int h) { return draai == 90 || draai == 270 ? new int[]{h, w} : new int[]{w, h}; }

    /**
     * Maat van de uitkomst bij een (al gedraaide) foto van w×h: de langste zijden van het vlak, of met de gekozen
     * verhouding; de langste kant hooguit max. Zelfde rekenwijze als fbMaat.
     */
    int[] maat(int w, int h, int max) {
        double x0 = q[0] * w, y0 = q[1] * h, x1 = q[2] * w, y1 = q[3] * h, x2 = q[4] * w, y2 = q[5] * h, x3 = q[6] * w, y3 = q[7] * h;
        double bw = Math.max(Math.hypot(x1 - x0, y1 - y0), Math.hypot(x2 - x3, y2 - y3));
        double bh = Math.max(Math.hypot(x3 - x0, y3 - y0), Math.hypot(x2 - x1, y2 - y1));
        if (verhouding > 0) { double b = Math.max(bw, bh * verhouding); bw = b; bh = b / verhouding; }
        double s = Math.min(1, max / Math.max(1, Math.max(bw, bh)));
        return new int[]{Math.max(16, (int) Math.round(bw * s)), Math.max(16, (int) Math.round(bh * s))};
    }

    /** Kleur: uit = a·in + b, per kanaal (bij grijs eerst de helderheid 0,299R + 0,587G + 0,114B). Zelfde als fbKleur. */
    double[] kleur() {
        double c = (100 + contrast) / 100.0, span = Math.max(1, hi - lo), a = 255.0 / span * c;
        double b = (-lo * 255.0 / span - 128) * c + 128 + licht * 1.28;
        return new double[]{a, b};
    }
}
