package nl.rene.tools;

/** Id's uit een teruggezette notitie-backup mogen nooit code in de weergave brengen. */
public class SafeIdTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }
    public static void main(String[] a) {
        check("abc_12-x".equals(Restore.safeId("abc_12-x", "n")), "gewone id blijft");
        check(!Restore.safeId("x');Android.waChats();//", "n").contains("'"), "id met code wordt vervangen");
        check(Restore.safeId("", "r1").startsWith("r1x"), "lege id krijgt een nieuwe");
        check(Restore.safeId(null, "n").matches("[A-Za-z0-9_-]+"), "null wordt een geldige id");
        check(!Restore.safeId(new String(new char[100]).replace('\0', 'a'), "n").equals(new String(new char[100]).replace('\0', 'a')), "te lange id wordt vervangen");
        System.out.println(fails == 0 ? "Alle id-tests geslaagd" : fails + " id-tests mislukt");
        System.exit(fails == 0 ? 0 : 1);
    }
}
