package nl.rene.tools;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;

/** Het echte adres van een aflevering vooraf opzoeken (PodcastUrl.follow): doorverwijzingen, grenzen en fouten. */
public class PodcastUrlTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    public static void main(String[] a) throws Exception {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final String[] lastRange = {null};
        srv.createContext("/", x -> {
            String p = x.getRequestURI().getPath();
            lastRange[0] = x.getRequestHeaders().getFirst("Range");
            int port = srv.getAddress().getPort();
            if (p.equals("/teller")) { x.getResponseHeaders().add("Location", "/cdn/aflevering.mp3?x=1"); x.sendResponseHeaders(302, -1); }
            else if (p.equals("/abs")) { x.getResponseHeaders().add("Location", "http://127.0.0.1:" + port + "/teller"); x.sendResponseHeaders(301, -1); }
            else if (p.equals("/lus")) { x.getResponseHeaders().add("Location", "/lus"); x.sendResponseHeaders(302, -1); }
            else if (p.equals("/ftp")) { x.getResponseHeaders().add("Location", "ftp://127.0.0.1/x"); x.sendResponseHeaders(302, -1); }
            else if (p.equals("/geen")) { x.sendResponseHeaders(403, -1); }
            else if (p.equals("/traag")) { try { Thread.sleep(3000); } catch (InterruptedException ignored) { } x.sendResponseHeaders(200, -1); }
            else if (p.startsWith("/cdn/")) { byte[] b = new byte[100]; x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); }
            else x.sendResponseHeaders(404, -1);
            x.close();
        });
        srv.start();
        String base = "http://127.0.0.1:" + srv.getAddress().getPort();
        long far = System.currentTimeMillis() + 10_000;
        try {
            check((base + "/cdn/aflevering.mp3?x=1").equals(PodcastUrl.follow(base + "/teller", "Tools-test", far)), "relatieve doorverwijzing naar het bestand");
            check(lastRange[0] == null, "gewone GET zonder Range-kop (tellers zien een gewone luisterbeurt)");
            check((base + "/cdn/aflevering.mp3?x=1").equals(PodcastUrl.follow(base + "/abs", "Tools-test", far)), "twee stappen, ook een volledig adres");
            check(PodcastUrl.follow(base + "/lus", "Tools-test", far) == null, "eindeloze lus: niets (gewone adres)");
            check(PodcastUrl.follow(base + "/ftp", "Tools-test", far) == null, "alleen http(s)");
            check(PodcastUrl.follow(base + "/geen", "Tools-test", far) == null, "403: niets");
            check(PodcastUrl.follow("file:///etc/passwd", "Tools-test", far) == null, "geen bestanden");
            long t0 = System.currentTimeMillis();
            check(PodcastUrl.follow(base + "/traag", "Tools-test", t0 + 800) == null && System.currentTimeMillis() - t0 < 2500, "eindtijd: wacht niet op een trage server");
            check(PodcastUrl.signed("https://cdn.x/a.mp3?Expires=1&Signature=2") && PodcastUrl.signed("https://cdn.x/a.mp3?X-Amz-Date=1") && !PodcastUrl.signed("https://cdn.x/a.mp3") && !PodcastUrl.signed("https://cdn.x/token=a/b.mp3"), "ondertekende adressen herkend (alleen in de query)");
        } finally { srv.stop(0); }

        if (fails > 0) { System.out.println(fails + " fout(en)"); System.exit(1); }
        System.out.println("alles goed");
    }
}
