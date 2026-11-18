package nl.rene.tools;

/** Nieuws: welke videospeler het script krijgt, waar het in de pagina komt en hoe de CSP het toelaat. */
public class NieuwsVideoTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    public static void main(String[] a) {
        // Alleen de bekende speler, alleen https
        check(NieuwsVideo.speler("https://space-wfqme.video-dns.com/jHBL8SEz7x/player.html"), "speler van mave (subdomein)");
        check(NieuwsVideo.speler("https://video-dns.com/x"), "het domein zelf");
        check(!NieuwsVideo.speler("http://space.video-dns.com/x"), "geen http");
        check(!NieuwsVideo.speler("https://video-dns.com.boos.nl/x") && !NieuwsVideo.speler("https://nietvideo-dns.com/x"), "geen namen die er alleen op lijken");
        check(!NieuwsVideo.speler("https://www.aireport.nl/p/x") && !NieuwsVideo.speler(null) && !NieuwsVideo.speler("javascript:alert(1)"), "andere sites niet");
        check(NieuwsVideo.document("text/html,application/xhtml+xml,*/*;q=0.8") && !NieuwsVideo.document("image/avif,image/webp,*/*") && !NieuwsVideo.document(null), "alleen een document");

        // Waar het script komt: vooraan in <head>, vóór een CSP in een meta-tag
        String s = "X();";
        check(NieuwsVideo.injecteer("<!DOCTYPE html><html lang=\"nl\"><head><meta http-equiv=\"Content-Security-Policy\" content=\"x\"><title>P</title></head></html>", s, null)
                .equals("<!DOCTYPE html><html lang=\"nl\"><head><script>X();</script><meta http-equiv=\"Content-Security-Policy\" content=\"x\"><title>P</title></head></html>"), "direct na <head>");
        check(NieuwsVideo.injecteer("<HTML><HEAD class=\"a\">", s, "n1").equals("<HTML><HEAD class=\"a\"><script nonce=\"n1\">X();</script>"), "hoofdletters, attributen en een nonce");
        check(NieuwsVideo.injecteer("<html><header>…", s, null).equals("<html><script>X();</script><header>…"), "<header> is geen <head>");
        check(NieuwsVideo.injecteer("<!doctype html><p>hoi", s, null).equals("<!doctype html><script>X();</script><p>hoi"), "zonder head en html: na het doctype");
        check(NieuwsVideo.injecteer("<p>hoi", s, null).equals("<script>X();</script><p>hoi"), "helemaal kaal: vooraan");
        check(!NieuwsVideo.injecteer("<head>", "a('</script><b>')", null).contains("</script><b>"), "het script kan zijn eigen tag niet sluiten");

        // CSP: alleen ons script erbij
        check(NieuwsVideo.csp(null, "n") == null && "".equals(NieuwsVideo.csp("", "n")), "geen CSP: niets");
        check(NieuwsVideo.csp("frame-ancestors *", "n").equals("frame-ancestors *"), "geen regel voor scripts: ongewijzigd");
        check(NieuwsVideo.csp("script-src 'self' 'unsafe-inline' https:", "n").equals("script-src 'self' 'unsafe-inline' https:"), "inline mag al: ongewijzigd (een nonce zou dat uitzetten)");
        check(NieuwsVideo.csp("default-src 'self'; script-src 'self' https://cdn.x", "n").equals("default-src 'self'; script-src 'self' https://cdn.x 'nonce-n'"), "nonce bij script-src");
        check(NieuwsVideo.csp("default-src 'self'; img-src *", "n").equals("default-src 'self'; img-src *; script-src 'self' 'nonce-n'"), "alleen default-src: een eigen script-src erbij");
        check(NieuwsVideo.csp("script-src 'nonce-abc' 'strict-dynamic' 'unsafe-inline'", "n").endsWith("'nonce-n'"), "met nonce en strict-dynamic: onze nonce erbij");
        check(NieuwsVideo.csp("script-src 'none'", "n") == null, "scripts helemaal uit: dan geen script");

        // Teksten en tekenset
        check(NieuwsVideo.json("Verder \"x\" <tijd> é").equals("\"Verder \\\"x\\\" \\u003ctijd\\u003e \\u00e9\""), "teksten veilig in het script");
        String sc = NieuwsVideo.script("var T = __TEKST__; // vóór", "Verder vanaf {tijd}", "Vanaf het begin");
        boolean ascii = true;
        for (char c : sc.toCharArray()) if (c > 0x7e) ascii = false;
        check(ascii && sc.contains("\"verder\":\"Verder vanaf {tijd}\"") && sc.contains("\"begin\":\"Vanaf het begin\""), "script met teksten, alleen ASCII");
        check(NieuwsVideo.charset("text/html; charset=UTF-8").equals("UTF-8") && NieuwsVideo.charset("text/html") == null && NieuwsVideo.charset("text/html; charset=onzin") == null, "tekenset uit de kop");
        check(NieuwsVideo.nonce().matches("[0-9a-f]{36}") && !NieuwsVideo.nonce().equals(NieuwsVideo.nonce()), "nonce: willekeurig");

        if (fails > 0) { System.out.println(fails + " fout(en)"); System.exit(1); }
        System.out.println("alles goed");
    }
}
