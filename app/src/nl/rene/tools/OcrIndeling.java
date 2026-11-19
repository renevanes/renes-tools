package nl.rene.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Van losse woorden met hun plek op de pagina (uit de tekstherkenning) naar leesbare tekst, ook bij een brief of rekening
 * met kolommen en gekleurde vakken. De tekstherkenning plakt daar vaak de regels van twee kolommen aan elkaar ("IBAN NL…
 * deze direct bekijken"). Hier wordt een regel bij een groot gat gesplitst, en komen de stukken die onder elkaar staan
 * achter elkaar: eerst een heel blok, dan het volgende. Zo staat de waarde weer onder zijn eigen naam ("naam ontvanger" →
 * de naam). Zuiver (getest in BetalenTest): geen Android. Begrensd in aantal woorden.
 */
final class OcrIndeling {

    private OcrIndeling() { }

    static final int MAX_WOORDEN = 4000;

    /** Eén woord: de tekst, de rechthoek (links, boven, rechts, onder) en het volgnummer van de regel volgens de tekstherkenning. */
    static final class Woord {
        final String tekst; final int x0, y0, x1, y1, regel;
        Woord(String tekst, int x0, int y0, int x1, int y1, int regel) { this.tekst = tekst; this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1; this.regel = regel; }
    }

    /** Een stuk van een regel dat bij elkaar hoort. */
    static final class Stuk {
        final StringBuilder tekst = new StringBuilder(); int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1, y1;
        void erbij(Woord w) { if (tekst.length() > 0) tekst.append(' '); tekst.append(w.tekst); x0 = Math.min(x0, w.x0); y0 = Math.min(y0, w.y0); x1 = Math.max(x1, w.x1); y1 = Math.max(y1, w.y1); }
        int hoog() { return Math.max(1, y1 - y0); }
        int breed() { return Math.max(1, x1 - x0); }
    }

    /** Regels splitsen waar het gat tussen twee woorden veel groter is dan een spatie (een andere kolom). */
    static List<Stuk> stukken(List<Woord> woorden) {
        List<Stuk> uit = new ArrayList<>();
        if (woorden == null) return uit;
        List<Woord> ws = new ArrayList<>();
        for (Woord w : woorden) { if (w != null && w.tekst != null && !w.tekst.trim().isEmpty() && w.x1 > w.x0 && w.y1 > w.y0 && ws.size() < MAX_WOORDEN) ws.add(w); }
        Collections.sort(ws, (a, b) -> a.regel != b.regel ? Integer.compare(a.regel, b.regel) : Integer.compare(a.x0, b.x0));
        int i = 0;
        while (i < ws.size()) {
            int j = i;
            List<Integer> hoogtes = new ArrayList<>();
            while (j < ws.size() && ws.get(j).regel == ws.get(i).regel) { hoogtes.add(ws.get(j).y1 - ws.get(j).y0); j++; }
            Collections.sort(hoogtes);
            int h = hoogtes.get(hoogtes.size() / 2), gat = Math.max(h * 8 / 5, 12);
            Stuk s = new Stuk();
            for (int k = i; k < j; k++) {
                Woord w = ws.get(k);
                if (k > i && w.x0 - ws.get(k - 1).x1 > gat) { voegToe(uit, s); s = new Stuk(); }
                s.erbij(w);
            }
            voegToe(uit, s);
            i = j;
        }
        return uit;
    }

    /** Alleen stukken waar iets leesbaars in staat (geen losse streepjes van een kader). */
    private static void voegToe(List<Stuk> uit, Stuk s) {
        String t = s.tekst.toString();
        int n = 0;
        for (int i = 0; i < t.length(); i++) if (Character.isLetterOrDigit(t.charAt(i))) n++;
        if (n >= 2) uit.add(s);
    }

    /**
     * De tekst, blok voor blok: een stuk hoort bij het blok dat er vlak boven eindigt en er recht boven staat (zelfde
     * linkerkant, genoeg overlap). Blokken staan in de volgorde van hun eerste regel; tussen twee blokken een lege regel.
     */
    static String tekst(List<Woord> woorden) {
        List<Stuk> stukken = stukken(woorden);
        Collections.sort(stukken, (a, b) -> a.y0 != b.y0 ? Integer.compare(a.y0, b.y0) : Integer.compare(a.x0, b.x0));
        List<List<Stuk>> blokken = new ArrayList<>();
        for (Stuk s : stukken) {
            List<Stuk> best = null;
            for (List<Stuk> b : blokken) {
                Stuk l = b.get(b.size() - 1);
                int overlap = Math.min(s.x1, l.x1) - Math.max(s.x0, l.x0), h = s.hoog();
                if (overlap * 5 > Math.min(s.breed(), l.breed()) * 2 && Math.abs(s.x0 - l.x0) < 3 * h && s.y0 - l.y1 < h * 7 / 2 && s.y0 >= l.y0
                        && (best == null || l.y1 > best.get(best.size() - 1).y1)) best = b;
            }
            if (best == null) { best = new ArrayList<>(); blokken.add(best); }
            best.add(s);
        }
        StringBuilder uit = new StringBuilder();
        for (List<Stuk> b : blokken) {
            if (uit.length() > 0) uit.append("\n\n");
            for (int i = 0; i < b.size(); i++) { if (i > 0) uit.append('\n'); uit.append(b.get(i).tekst); }
        }
        return uit.toString();
    }
}
