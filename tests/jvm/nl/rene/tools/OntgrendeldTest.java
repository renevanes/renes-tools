package nl.rene.tools;

import java.util.ArrayList;
import java.util.List;

/** Extra ontgrendelen uit: hoe lang de sleutel van Bankzaken (15 min) en Stamgegevens (10 min) na de laatste ontgrendeling van de telefoon nog zeker werkt. */
public class OntgrendeldTest {
    static void check(boolean yes, String text) { if (!yes) throw new AssertionError(text); }

    /** De telefoon is {@code geleden} seconden geleden ontgrendeld: een proef van p seconden werkt als p > geleden. */
    static long zeker(int sleutel, int geleden, List<Integer> gevraagd) {
        return Ontgrendeld.zeker(sleutel, p -> { gevraagd.add(p); return p > geleden; });
    }

    public static void main(String[] args) {
        List<Integer> v = new ArrayList<>();
        check(zeker(900, 10, v) == (900 - 60 - Ontgrendeld.MARGE) * 1000L && v.size() == 1, "net ontgrendeld: Bankzaken nog 13,5 minuut zeker, met één proef");
        v.clear();
        check(zeker(900, 200, v) == (900 - 300 - Ontgrendeld.MARGE) * 1000L && v.toString().equals("[60, 180, 300]"), "3 min 20 geleden: zeker tot 5 minuten na ontgrendelen: " + v);
        v.clear();
        check(zeker(900, 500, v) == (900 - 600 - Ontgrendeld.MARGE) * 1000L, "ruim 8 minuten geleden: nog 4,5 minuut");
        v.clear();
        check(zeker(900, 700, v) == 0 && v.toString().equals("[60, 180, 300, 600]"), "langer dan 10 minuten geleden: niet zeker, dus vragen");

        v.clear();
        check(zeker(600, 10, v) == (600 - 60 - Ontgrendeld.MARGE) * 1000L, "Stamgegevens net ontgrendeld: 8,5 minuut");
        v.clear();
        check(zeker(600, 400, v) == 0 && v.toString().equals("[60, 180, 300]"), "Stamgegevens: geen proef van 10 minuten (even lang als de sleutel zelf): " + v);
        v.clear();
        check(zeker(600, 250, v) == (600 - 300 - Ontgrendeld.MARGE) * 1000L, "Stamgegevens ruim 4 minuten geleden: nog 4,5 minuut");

        for (int geleden = 0; geleden < 1000; geleden += 7) {
            for (int sleutel : new int[]{600, 900}) {
                long z = zeker(sleutel, geleden, new ArrayList<>());
                check(z == 0 || geleden * 1000L + z + Ontgrendeld.MARGE * 1000L <= sleutel * 1000L, "nooit langer dan de sleutel werkt (" + sleutel + ", " + geleden + ")");
                check(z == 0 || z >= 120_000L, "open zonder vragen = minstens 2 minuten (" + sleutel + ", " + geleden + ")");
            }
        }
        check(Ontgrendeld.alias("renes_bankzaken", 60).equals("renes_bankzaken_proef_60") && !Ontgrendeld.alias("renes_bankzaken", 60).equals(Bank.ALIAS), "eigen naam per proef, los van de echte sleutel");
        check(!Ontgrendeld.alias("renes_masterdata", 60).equals(MasterKeys.ALIAS), "Stamgegevens: eigen naam");
        System.out.println("ok");
    }
}
