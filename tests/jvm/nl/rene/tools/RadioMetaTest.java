package nl.rene.tools;

/** Radio: titel uit de stream-metadata netjes maken (spaties en streepjes aan de randen weg). */
public class RadioMetaTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    public static void main(String[] a) {
        check(Radio.trimDash(" - Artiest - Titel -\t").equals("Artiest - Titel"), "randen weg, midden blijft");
        check(Radio.trimDash("---").isEmpty() && Radio.trimDash("").isEmpty(), "alleen streepjes of leeg: leeg");
        check(Radio.trimDash("Titel").equals("Titel"), "niets te doen");
        StringBuilder s = new StringBuilder("x"); for (int i = 0; i < 4000; i++) s.append(i % 2 == 0 ? ' ' : '-'); s.append('y');
        long t0 = System.currentTimeMillis();
        check(Radio.trimDash(s.toString()).length() == 4002 && System.currentTimeMillis() - t0 < 500, "lange reeks spaties/streepjes in het midden: snel");
        if (fails > 0) System.exit(1);
    }
}
