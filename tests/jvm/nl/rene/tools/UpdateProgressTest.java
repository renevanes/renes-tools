package nl.rene.tools;

import java.util.ArrayList;
import java.util.List;

/** Voortgang van een update: hooguit eens per 200 ms, maar altijd bij een nieuwe fase en als het klaar is. */
public class UpdateProgressTest {
    static boolean all = true;
    static void ok(boolean c, String m) { System.out.println((c ? "✓ " : "✗ ") + m); if (!c) all = false; }

    static void downloads() throws Exception {
        byte[] body = new byte[100_000];
        for (int i = 0; i < body.length; i++) body[i] = (byte) i;
        com.sun.net.httpserver.HttpServer srv = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/lengte", x -> { x.sendResponseHeaders(200, body.length); x.getResponseBody().write(body); x.close(); });
        srv.createContext("/zonder", x -> { x.sendResponseHeaders(200, 0); x.getResponseBody().write(body); x.close(); }); // chunked
        srv.createContext("/kort", x -> { x.getResponseHeaders().add("Content-Length", "200000"); x.sendResponseHeaders(200, 0); x.getResponseBody().write(body); x.close(); });
        srv.createContext("/fout", x -> { x.sendResponseHeaders(404, -1); x.close(); });
        srv.start();
        String base = "http://127.0.0.1:" + srv.getAddress().getPort();
        try {
            List<long[]> st = new ArrayList<>();
            Updater.Progress rec = (ph, d, t) -> st.add(new long[]{d, t});
            byte[] got = Updater.get(base + "/lengte", rec, "download", 1_000_000);
            ok(java.util.Arrays.equals(got, body), "met grootte: alles binnen");
            long[] last = st.get(st.size() - 1);
            ok(st.get(0)[0] == 0 && st.get(0)[1] == body.length && last[0] == body.length && last[1] == body.length, "met grootte: van 0 tot 100%");
            st.clear();
            got = Updater.get(base + "/zonder", rec, "download", 1_000_000);
            last = st.get(st.size() - 1);
            ok(got.length == body.length && st.get(0)[1] == 0 && last[0] == body.length && last[1] == body.length, "zonder grootte: totaal onbekend, laatste stap klaar");
            boolean big = false;
            try { Updater.get(base + "/zonder", Updater.NONE, "", 50_000); } catch (Exception e) { big = e.getMessage().contains("te groot"); }
            ok(big, "te groot (zonder grootte): afgebroken");
            big = false;
            try { Updater.get(base + "/lengte", Updater.NONE, "", 50_000); } catch (Exception e) { big = e.getMessage().contains("te groot"); }
            ok(big, "te groot (volgens de server): meteen afgebroken");
            boolean shortOk = false;
            try { Updater.get(base + "/kort", Updater.NONE, "", 1_000_000); } catch (Exception e) { shortOk = true; }
            ok(shortOk, "onvolledige download: fout");
            boolean http = false;
            try { Updater.get(base + "/fout", Updater.NONE, "", 1_000_000); } catch (Exception e) { http = e.getMessage().contains("404"); }
            ok(http, "HTTP-fout: fout");
        } finally { srv.stop(0); }
    }

    public static void main(String[] a) {
        long[] now = {1000};
        List<String> seen = new ArrayList<>();
        Updater.Throttle t = new Updater.Throttle((ph, d, tot) -> seen.add(ph + ":" + d + "/" + tot), 200, () -> now[0]);
        t.step("start", 0, 0);
        t.step("download", 0, 1000);           // nieuwe fase: door
        now[0] += 50; t.step("download", 100, 1000);  // te snel: niet
        now[0] += 50; t.step("download", 200, 1000);  // te snel: niet
        now[0] += 150; t.step("download", 300, 1000); // 250 ms na de vorige: door
        now[0] += 10; t.step("download", 1000, 1000); // klaar: altijd door
        now[0] += 10; t.step("check", 0, 0);          // nieuwe fase: door
        ok(seen.size() == 5, "vijf stappen doorgegeven (" + seen + ")");
        ok(seen.get(0).equals("start:0/0") && seen.get(1).equals("download:0/1000"), "begin en nieuwe fase");
        ok(seen.get(2).equals("download:300/1000"), "tussenstap na 200 ms");
        ok(seen.get(3).equals("download:1000/1000"), "klaar komt altijd door");
        ok(seen.get(4).equals("check:0/0"), "volgende fase");
        // Onbekende grootte (0): gewoon op tijd doorgeven
        seen.clear();
        now[0] += 1000; t.step("download2", 10, 0);
        now[0] += 10; t.step("download2", 20, 0);
        now[0] += 300; t.step("download2", 30, 0);
        ok(seen.size() == 2 && seen.get(1).equals("download2:30/0"), "zonder grootte: op tijd (" + seen + ")");
        // Echte downloads tegen een kleine server op deze computer
        try { downloads(); } catch (Exception e) { ok(false, "downloadtest: " + e); }
        System.out.println(all ? "Alle voortgangstests geslaagd" : "Voortgangstest mislukt");
        System.exit(all ? 0 : 1);
    }
}
