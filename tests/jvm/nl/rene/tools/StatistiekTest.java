package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

/** Statistieken: alleen tellingen en de plek van een vastloper, nooit foutteksten of gegevens. */
public class StatistiekTest {
    static void check(boolean ok, String m) { if (!ok) throw new AssertionError(m); System.out.println("✓ " + m); }

    public static void main(String[] a) throws Exception {
        Throwable cause = new java.io.FileNotFoundException("/storage/emulated/0/Jan de Vries.pdf");
        cause.setStackTrace(new StackTraceElement[]{ new StackTraceElement("java.io.File", "open", "File.java", 1),
                new StackTraceElement("nl.rene.tools.PdfTools", "copy", "PdfTools.java", 91) });
        IllegalStateException top = new IllegalStateException("sms van Jan 0612345678 over het feest", cause);
        top.setStackTrace(new StackTraceElement[]{ new StackTraceElement("android.os.Handler", "dispatch", "Handler.java", 10),
                new StackTraceElement("nl.rene.tools.Podcasts", "open", "Podcasts.java", 72) });
        String k = Statistiek.crashKey(top);
        check(k.equals("java.io.FileNotFoundException | PdfTools.copy:91"), "vastloper: soort en plek van de oorzaak (" + k + ")");
        check(!k.contains("Jan") && !k.contains("0612") && !k.contains("storage"), "geen fouttekst, naam, nummer of pad");
        Error oom = new OutOfMemoryError("geheim 123");
        oom.setStackTrace(new StackTraceElement[]{ new StackTraceElement("java.util.Arrays", "copyOf", "Arrays.java", 1) });
        check(Statistiek.crashKey(oom).equals("java.lang.OutOfMemoryError | buiten de app"), "fout buiten de eigen code");
        Throwable noOwn = new IllegalStateException("x", new RuntimeException("y"));
        noOwn.setStackTrace(new StackTraceElement[]{ new StackTraceElement("nl.rene.tools.Outer$Inner", "run", "Outer.java", 5) });
        noOwn.getCause().setStackTrace(new StackTraceElement[0]);
        check(Statistiek.crashKey(noOwn).equals("java.lang.RuntimeException | Outer.run:5"), "oorzaak zonder eigen code: plek van hoger; geneste class: buitenste naam");
        check(Statistiek.crashKey(null).equals("onbekend | buiten de app"), "leeg");

        check(Statistiek.tag("BACKUP").equals("BACKUP") && Statistiek.tag("Jan belde").equals("ANDERS") && Statistiek.tag(null).equals("ANDERS")
                && Statistiek.tag("0612345678").equals("ANDERS"), "alleen vaste namen als soort logregel");

        check(Statistiek.validRepo("renevanes/renes-tools-statistiek") && !Statistiek.validRepo("renevanes") && !Statistiek.validRepo("a/../b")
                && !Statistiek.validRepo("evil.com/x") && !Statistiek.validRepo("a/b?x=1") && !Statistiek.validRepo("a/b/c"), "repository: alleen eigenaar/naam");
        check(Statistiek.validToken("github_pat_11ABCDEFG0123456789_abcdefghijklmnopqrstuvwxyz") && !Statistiek.validToken("kort")
                && !Statistiek.validToken("ghp_abcdefghijklmnopqrstuvwxyz0123456789") && !Statistiek.validToken("github_pat_abc def ghi jkl mno pqr"),
                "sleutel: alleen een fine-grained sleutel");
        check(!Statistiek.validRepo("renevanes/.") && !Statistiek.validRepo("renevanes/..."), "repository: niet alleen punten");

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
