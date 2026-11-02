package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

/** Statistieken: alleen tellingen en de plek van een vastloper, nooit foutteksten of gegevens. */
public class StatistiekTest {
    static void check(boolean ok, String m) { if (!ok) throw new AssertionError(m); System.out.println("✓ " + m); }

    public static void main(String[] a) throws Exception {
        String trace = "thread main\njava.lang.IllegalStateException: sms van Jan 0612345678 over het feest\n"
                + "\tat android.os.Handler.dispatch(Handler.java:10)\n\tat nl.rene.tools.Podcasts.open(Podcasts.java:72)\n"
                + "\tat nl.rene.tools.MainActivity$Bridge.podcast(MainActivity.java:900)\n"
                + "Caused by: java.io.FileNotFoundException: /storage/emulated/0/Jan de Vries.pdf\n\tat java.io.File.open(File.java:1)\n"
                + "\tat nl.rene.tools.PdfTools.copy(PdfTools.java:91)\n";
        String k = Statistiek.crashKey(trace);
        check(k.equals("java.io.FileNotFoundException | PdfTools.copy:91"), "vastloper: soort en plek van de oorzaak (" + k + ")");
        check(!k.contains("Jan") && !k.contains("0612") && !k.contains("storage"), "geen fouttekst, naam, nummer of pad");
        check(Statistiek.crashKey("thread x\njava.lang.OutOfMemoryError\n\tat java.util.Arrays.copyOf(Arrays.java:1)\n").equals("java.lang.OutOfMemoryError | buiten de app"), "fout buiten de eigen code");
        check(Statistiek.crashKey("thread x\nkapot: geheim 123\n").equals("onbekend | buiten de app"), "onleesbaar: niets van de tekst");
        check(Statistiek.crashKey(null).equals("onbekend | buiten de app"), "leeg");
        check(Statistiek.crashKey("java.lang.RuntimeException: x\n\tat nl.rene.tools.Outer$Inner.run(Outer.java:5)\n").equals("java.lang.RuntimeException | Outer.run:5"), "geneste class: buitenste naam");

        check(Statistiek.tag("BACKUP").equals("BACKUP") && Statistiek.tag("Jan belde").equals("ANDERS") && Statistiek.tag(null).equals("ANDERS")
                && Statistiek.tag("0612345678").equals("ANDERS"), "alleen vaste namen als soort logregel");

        check(Statistiek.validRepo("renevanes/renes-tools-statistiek") && !Statistiek.validRepo("renevanes") && !Statistiek.validRepo("a/../b")
                && !Statistiek.validRepo("evil.com/x") && !Statistiek.validRepo("a/b?x=1") && !Statistiek.validRepo("a/b/c"), "repository: alleen eigenaar/naam");
        check(Statistiek.validToken("github_pat_11ABCDEFG0123456789_abcdefghijklmnopqrstuvwxyz") && !Statistiek.validToken("kort")
                && !Statistiek.validToken("abc def ghi jkl mno pqr stu"), "sleutel: vorm van een GitHub-sleutel");

        JSONObject tags = new JSONObject().put("BACKUP", 3).put("PODCAST", 1).put("Jan de Vries", 2);
        JSONObject crashes = new JSONObject().put(k, 2);
        JSONObject r = Statistiek.report(tags, crashes, 0L, 7 * 86_400_000L, "1.118", 119, 105, 36, "Fabrikant Model", "a1b2c3d4", false);
        check(r.getJSONObject("logregels").optInt("BACKUP") == 3 && !r.getJSONObject("logregels").has("Jan de Vries"), "rapport: tellingen, geen vreemde namen");
        JSONArray v = r.getJSONArray("vastlopers");
        check(v.length() == 1 && v.getJSONObject(0).getString("plek").equals("PdfTools.copy:91") && v.getJSONObject(0).getInt("aantal") == 2, "rapport: vastlopers met plek en aantal");
        check(r.getString("van").equals("1970-01-01T00:00Z") && r.getInt("native") == 105 && r.getString("installatie").equals("a1b2c3d4"), "rapport: periode, versie, installatie");
        String all = r.toString();
        check(!all.contains("Jan") && !all.contains("0612") && !all.contains("feest") && !all.contains("storage"), "rapport: niets uit de foutteksten");
    }
}
