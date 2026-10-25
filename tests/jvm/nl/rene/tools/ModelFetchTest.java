package nl.rene.tools;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

/** Zelf een model downloaden: hervatten met Range, opnieuw na een afgebroken verbinding, stoppen, fouten. */
public class ModelFetchTest {
    static boolean all = true;
    static void ok(boolean c, String m) { System.out.println((c ? "✓ " : "✗ ") + m); if (!c) all = false; }

    public static void main(String[] a) throws Exception {
        ok(ModelFetch.rangeTotal("bytes 100-199/200") == 200 && ModelFetch.rangeTotal("bytes 0-9/*") == -1 && ModelFetch.rangeTotal(null) == -1, "totaal uit Content-Range");
        ok(ModelDownloadService.why(new ModelFetch.Fatal("Download mislukt (HTTP 404)")).contains("404") && ModelDownloadService.why(new java.io.IOException("write failed: ENOSPC (No space left on device)")).equals("Te weinig opslag")
                && ModelDownloadService.why(new java.net.UnknownHostException("Unable to resolve host")).startsWith("Downloaden mislukt"), "foutmeldingen in gewone taal");
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
        // Range-antwoord dat ergens anders begint dan gevraagd: opnieuw van voren af aan
        srv.createContext("/anders", x -> {
            if (x.getRequestHeaders().getFirst("Range") != null) { x.getResponseHeaders().add("Content-Range", "bytes 0-" + (body.length - 1) + "/" + body.length); x.sendResponseHeaders(206, body.length); }
            else x.sendResponseHeaders(200, body.length);
            x.getResponseBody().write(body); x.close(); });
        // Ander totaal in Content-Range dan verwacht
        srv.createContext("/groter", x -> { x.getResponseHeaders().add("Content-Range", "bytes 10-19/999999"); x.sendResponseHeaders(206, 10); x.getResponseBody().write(body, 10, 10); x.close(); });
        // 416: het stuk past niet (bijv. ander bestand op de server): opnieuw
        AtomicInteger r416 = new AtomicInteger();
        srv.createContext("/416", x -> {
            if (x.getRequestHeaders().getFirst("Range") != null) { r416.incrementAndGet(); x.sendResponseHeaders(416, -1); x.close(); return; }
            x.sendResponseHeaders(200, body.length); x.getResponseBody().write(body); x.close(); });
        // Doorverwijzing naar http: weigeren
        srv.createContext("/omweg", x -> { x.getResponseHeaders().add("Location", "http://example.org/model"); x.sendResponseHeaders(302, -1); x.close(); });
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

            // Range-antwoord vanaf een ander punt: van voren af aan, geen rommel
            File f6 = new File(dir, "h.download");
            Files.write(f6.toPath(), java.util.Arrays.copyOf(body, 70_000));
            ModelFetch.fetch(base + "/anders", f6, body.length, (d, t) -> { }, () -> false);
            ok(java.util.Arrays.equals(Files.readAllBytes(f6.toPath()), body), "206 vanaf een ander punt: opnieuw van het begin");

            // Ander totaal in Content-Range: fout
            File f7 = new File(dir, "i.download");
            Files.write(f7.toPath(), java.util.Arrays.copyOf(body, 10));
            msg = "";
            try { ModelFetch.fetch(base + "/groter", f7, body.length, (d, t) -> { }, () -> false); } catch (Exception e) { msg = String.valueOf(e.getMessage()); }
            ok(msg.contains("grootte"), "ander totaal in Content-Range: fout");

            // 416 met een stuk: dat stuk weg en opnieuw
            File f8 = new File(dir, "j.download");
            Files.write(f8.toPath(), java.util.Arrays.copyOf(body, 1000));
            ModelFetch.fetch(base + "/416", f8, body.length, (d, t) -> { }, () -> false);
            ok(java.util.Arrays.equals(Files.readAllBytes(f8.toPath()), body) && r416.get() == 1, "416: opnieuw van het begin");

            // Groter stuk dan het hoort: weg en opnieuw
            File f9 = new File(dir, "k.download");
            Files.write(f9.toPath(), new byte[body.length + 10]);
            ModelFetch.fetch(base + "/model", f9, body.length, (d, t) -> { }, () -> false);
            ok(java.util.Arrays.equals(Files.readAllBytes(f9.toPath()), body), "te groot stuk: opnieuw");

            // Doorverwijzing naar http: geweigerd
            msg = "";
            try { ModelFetch.fetch(base + "/omweg", new File(dir, "l.download"), body.length, (d, t) -> { }, () -> false); } catch (Exception e) { msg = String.valueOf(e.getMessage()); }
            ok(msg.contains("onveilige doorverwijzing"), "doorverwijzing naar http geweigerd");

            // Een vooraf op volle grootte gezet bestand (zoals de DownloadManager): alleen het echte stuk houden
            File f10 = new File(dir, "m.download");
            byte[] pre = new byte[body.length];
            System.arraycopy(body, 0, pre, 0, 120_000);
            Files.write(f10.toPath(), pre);
            ok(LocalAi.keepPart(f10, 120_000) && f10.length() == 120_000, "vooraf gereserveerd bestand: ingekort tot wat binnen is");
            ModelFetch.fetch(base + "/model", f10, body.length, (d, t) -> { }, () -> false);
            ok(java.util.Arrays.equals(Files.readAllBytes(f10.toPath()), body), "daarna verder tot het hele bestand");
            ok(!LocalAi.keepPart(f10, -1) && !LocalAi.keepPart(f10, body.length + 1), "onbekend of te veel: niet houden");

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
