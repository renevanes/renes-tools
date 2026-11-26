package nl.rene.tools;

/** Foto bewerken: de bewerking uit de interface lezen en controleren (FotoOps), maat en kleur zoals fbMaat en fbKleurAB. */
public class FotoOpsTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }
    static boolean fout(String json) { try { FotoOps.lees(json); return false; } catch (FotoOps.Fout e) { return e.getMessage() != null && !e.getMessage().isEmpty(); } }

    public static void main(String[] a) throws Exception {
        check(FotoOps.lees(null).leeg() && FotoOps.lees("").leeg() && FotoOps.lees("{}").leeg(), "leeg = geen bewerking");
        FotoOps o = FotoOps.lees("{\"v\":1,\"draai\":90,\"q\":[[0.1,0.2],[0.8,0.15],[0.9,0.9],[0.05,0.85]],\"verhouding\":1.5852,\"licht\":10,\"contrast\":-20,\"niveau\":[12,240],\"grijs\":true,\"vlakken\":[[0.1,0.1,0.2,0.3]]}");
        check(o.draai == 90 && o.q[2] == 0.8 && o.verhouding > 1.58 && o.grijs && o.vlakken.length == 1 && !o.leeg() && !o.kleurLeeg(), "volledige bewerking gelezen");
        int[] g = o.gedraaid(800, 600); check(g[0] == 600 && g[1] == 800, "kwartslag: breedte en hoogte wisselen");

        // Controles: alles wat niet klopt is een fout, nooit stilletjes iets anders
        check(fout("{\"draai\":45}"), "draai alleen per kwartslag");
        check(fout("{\"q\":[[0,0],[1,0],[1,1]]}"), "vier hoeken");
        check(fout("{\"q\":[[0,0],[1,0],[1,1],[0,2]]}"), "hoeken binnen de foto");
        check(fout("{\"q\":[[0,0],[1,1],[1,0],[0,1]]}"), "hoeken over elkaar (niet bol) geweigerd");
        check(fout("{\"q\":[[0,0],[0,1],[1,1],[1,0]]}"), "gespiegeld (tegen de klok in) geweigerd");
        check(fout("{\"q\":[[0.5,0.5],[0.51,0.5],[0.51,0.51],[0.5,0.51]]}"), "te klein vlak geweigerd");
        check(fout("{\"verhouding\":9}") && fout("{\"verhouding\":-1}"), "verhouding begrensd");
        check(fout("{\"licht\":101}") && fout("{\"contrast\":-101}"), "licht en contrast begrensd");
        check(fout("{\"niveau\":[200,210]}") && fout("{\"niveau\":[0,256]}") && fout("{\"niveau\":[1]}"), "niveau begrensd");
        check(fout("{\"vlakken\":[[0.9,0,0.2,0.1]]}") && fout("{\"vlakken\":[[0,0,0,0.1]]}"), "vlakken binnen de uitkomst");
        StringBuilder veel = new StringBuilder("{\"vlakken\":["); for (int i = 0; i < 61; i++) veel.append(i > 0 ? "," : "").append("[0,0,0.1,0.1]"); veel.append("]}");
        check(fout(veel.toString()), "hooguit 60 vlakken");
        check(fout("{\"q\":\"x\"}") && fout("geen json") && fout("{\"draai\":\"x\"}"), "onzin = fout");
        StringBuilder groot = new StringBuilder("{\"x\":\""); for (int i = 0; i < FotoOps.MAX_JSON; i++) groot.append('a'); groot.append("\"}");
        check(fout(groot.toString()), "te lang");
        check(FotoOps.lees("{\"q\":[[-0.0005,0],[1.0005,0],[1,1],[0,1]]}").q[0] == 0, "afronding net buiten 0..1 wordt 0..1");

        // Maat (zelfde als fbMaat in de interface)
        FotoOps heel = FotoOps.geen();
        int[] m = heel.maat(800, 600, 400); check(m[0] == 400 && m[1] == 300, "maat: langste kant hooguit 400");
        m = heel.maat(800, 600, 4000); check(m[0] == 800 && m[1] == 600, "nooit groter dan de foto zelf");
        FotoOps id = FotoOps.lees("{\"verhouding\":1.5852}"); m = id.maat(800, 600, 2000);
        check(Math.abs((double) m[0] / m[1] - 1.5852) < 0.01, "gekozen verhouding: " + m[0] + "x" + m[1]);
        FotoOps schuin = FotoOps.lees("{\"q\":[[0.1,0.1],[0.9,0.2],[0.9,0.8],[0.1,0.9]]}"); m = schuin.maat(1000, 1000, 5000);
        check(m[0] == Math.round(Math.hypot(800, 100)) && m[1] == 800, "schuin vlak: de langste zijden (" + m[0] + "x" + m[1] + ")");

        // Kleur: niets doen = a 1, b 0; niveau rekt uit; zelfde formule als fbKleurAB
        double[] k = heel.kleur(); check(Math.abs(k[0] - 1) < 1e-9 && Math.abs(k[1]) < 1e-9, "geen kleurbewerking: uit = in");
        double[] n = FotoOps.lees("{\"niveau\":[50,200]}").kleur(); check(Math.abs(n[0] * 50 + n[1]) < 1e-6 && Math.abs(n[0] * 200 + n[1] - 255) < 1e-6, "niveau: 50 wordt zwart, 200 wit");
        double[] l = FotoOps.lees("{\"licht\":50}").kleur(); check(Math.abs(l[1] - 64) < 1e-9, "helderheid +50 = +64");
        double[] c = FotoOps.lees("{\"contrast\":100}").kleur(); check(Math.abs(c[0] * 128 + c[1] - 128) < 1e-9 && c[0] == 2, "contrast draait om het midden");

        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
        System.out.println("alles goed");
    }
}
