package nl.rene.tools;

/** Opstarttijden: alleen vaste stapnamen en milliseconden (de telefoontest leest deze regel uit logcat). */
public class OpstartTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    public static void main(String[] args) {
        long[] t = {1000};
        Opstart o = new Opstart(() -> t[0]);
        t[0] = 1003; o.stap("opruimen");
        t[0] = 1183; o.stap("webview");
        t[0] = 1190;
        check("onCreate opruimen=3 webview=180 totaal=183".equals(o.regel("onCreate")), "regel met stappen en totaal: " + o.regel("onCreate"));
        check(o.sinds() == 190, "tijd sinds het begin");
        t[0] = 1100; o.stap("terug");
        check(o.regel("x").contains("terug=0"), "een klok die teruggaat geeft geen negatieve tijd");
        check(o.regel("onCreate").matches("[A-Za-z]+( [A-Za-z]+=\\d+)+"), "alleen woorden en getallen (rooktest.sh leest [\\w=\\s])");
        Opstart.log("proef"); // zonder Android: geen fout
        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
    }
}
