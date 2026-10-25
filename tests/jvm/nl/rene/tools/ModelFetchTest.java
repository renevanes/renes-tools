package nl.rene.tools;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

/** Zelf een model downloaden: hervatten met Range, opnieuw na een afgebroken verbinding, stoppen, fouten. */
public class ModelFetchTest {
    static boolean all = true;
    static void ok(boolean c, String m) { System.out.println((c ? "✓ " : "✗ ") + m); if (!c) all = false; }

    public static void main(String[] a) throws Exception {
        ok(ModelFetch.rangeStart("bytes 100-199/200") == 100 && ModelFetch.rangeStart("bytes 0-9/*") == 0 && ModelFetch.rangeStart("x") == -1 && ModelFetch.rangeStart(null) == -1, "Content-Range lezen");

        byte[] body = new byte[300_000];
        for (int i = 0; i < body.length; i++) body[i] = (byte) (i * 7);
        AtomicInteger hits = new AtomicInteger(), cut = new AtomicInteger();
        com.sun.net.httpserver.HttpServer srv = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        // Met Range (206), en de eerste keer(en) afgebroken halverwege
        srv.createContext("/model", x -> {
            hits.incrementAndGet();
            String r = x.getRequestHeaders().getFirst("Range");
            int from = r == null ? 0 : Integer.parseInt(r.replaceAll("^bytes=(\\d+)-$", "$1"));
            int n = body.length - from;
            if (r != null) { x.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + (body.length - 1) + "/" + body.length); x.sendResponseHeaders(206, n); }
            else x.sendResponseHeaders(200, n);
            try {
                if (cut.get() > 0) { cut.decrementAndGet(); x.getResponseBody().write(body, from, n / 2); x.getResponseBody().flush(); x.getHttpContext(); throw new java.io.IOException("afgebroken"); }
                x.getResponseBody().write(body, from, n);
            } finally { x.close(); }
        });
        // Zonder Range-ondersteuning: altijd alles (200)
        srv.createContext("/oud", x -> { x.sendResponseHeaders(200, body.length); x.getResponseBody().write(body); x.close(); });
        srv.createContext("/weg", x -> { x.sendResponseHeaders(404, -1); x.close(); });
        srv.start();
        String base = "http://127.0.0.1:" + srv.getAddress().getPort();
        File dir = Files.createTempDirectory("modelfetch").toFile();
        try {
            // Gewoon in één keer
            File f1 = new File(dir, "a.download");
            long[] last = {0, 0};
            ModelFetch.fetch(base + "/model", f1, body.length, (d, t) -> { last[0] = d; last[1] = t; }, () -> false);
            ok(java.util.Arrays.equals(Files.readAllBytes(f1.toPath()), body) && last[0] == body.length && last[1] == body.length, "in één keer, voortgang tot het eind");

            // Er staat al een stuk: verder met Range
            File f2 = new File(dir, "b.download");
            Files.write(f2.toPath(), java.util.Arrays.copyOf(body, 100_000));
            long[] first = {-1};
            ModelFetch.fetch(base + "/model", f2, body.length, (d, t) -> { if (first[0] < 0) first[0] = d; }, () -> false);
            ok(java.util.Arrays.equals(Files.readAllBytes(f2.toPath()), body) && first[0] == 100_000, "hervat waar hij was (vanaf 100000)");

            // Verbinding valt weg: zelf opnieuw, verder waar hij was
            File f3 = new File(dir, "c.download");
            cut.set(1); hits.set(0);
            ModelFetch.fetch(base + "/model", f3, body.length, (d, t) -> { }, () -> false);
            ok(java.util.Arrays.equals(Files.readAllBytes(f3.toPath()), body) && hits.get() == 2, "na een onderbreking vanzelf verder (" + hits.get() + " keer gevraagd)");

            // Server zonder Range: opnieuw van voren af aan, niet dubbel
            File f4 = new File(dir, "d.download");
            Files.write(f4.toPath(), java.util.Arrays.copyOf(body, 50_000));
            ModelFetch.fetch(base + "/oud", f4, body.length, (d, t) -> { }, () -> false);
            ok(java.util.Arrays.equals(Files.readAllBytes(f4.toPath()), body), "zonder Range: opnieuw van het begin");

            // Al helemaal binnen: niets doen
            hits.set(0);
            ModelFetch.fetch(base + "/model", f1, body.length, (d, t) -> { }, () -> false);
            ok(hits.get() == 0, "al compleet: geen download");

            // Stoppen
            File f5 = new File(dir, "e.download");
            boolean stopped = false;
            try { ModelFetch.fetch(base + "/model", f5, body.length, (d, t) -> { }, () -> true); } catch (ModelFetch.Stopped e) { stopped = true; }
            ok(stopped, "stoppen");

            // 404: meteen een fout, niet blijven proberen
            long t0 = System.currentTimeMillis();
            String msg = "";
            try { ModelFetch.fetch(base + "/weg", new File(dir, "f.download"), body.length, (d, t) -> { }, () -> false); } catch (Exception e) { msg = String.valueOf(e.getMessage()); }
            ok(msg.contains("404") && System.currentTimeMillis() - t0 < 2000, "404: meteen een fout (" + msg + ")");

            // Verkeerde grootte volgens de server: fout
            msg = "";
            try { ModelFetch.fetch(base + "/oud", new File(dir, "g.download"), body.length + 5, (d, t) -> { }, () -> false); } catch (Exception e) { msg = String.valueOf(e.getMessage()); }
            ok(msg.contains("grootte"), "onverwachte grootte: fout (" + msg + ")");
        } finally {
            srv.stop(0);
            File[] fs = dir.listFiles(); if (fs != null) for (File f : fs) f.delete(); dir.delete();
        }
        System.out.println(all ? "Alle downloadtests geslaagd" : "Downloadtest mislukt");
        System.exit(all ? 0 : 1);
    }
}
