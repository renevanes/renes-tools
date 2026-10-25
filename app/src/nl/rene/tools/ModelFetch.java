package nl.rene.tools;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Een groot bestand (een AI-model) zelf downloaden, zonder de DownloadManager van Android: hervat met Range waar
 * hij gebleven was, probeert het bij een haperende verbinding een paar keer opnieuw en controleert de grootte.
 * Of het echt het goede bestand is, controleert LocalAi daarna met de SHA-256.
 */
final class ModelFetch {

    private ModelFetch() { }

    interface Step { void step(long done, long total); }

    /** Afgebroken (Stoppen of Verwijderen). */
    static final class Stopped extends Exception { Stopped() { super("Gestopt"); } }

    /** Hoe lang wachten voor een nieuwe poging (ms); daarna geeft hij het op (Verder kan dan met de hand). */
    static final long[] WAIT = {3_000, 10_000, 30_000, 60_000};

    /**
     * Downloadt url naar part; staat er al een stuk, dan gaat hij daar verder. size = de verwachte grootte (0 =
     * onbekend). stop: true = afbreken. Gooit een fout als het niet lukt; het halve bestand blijft dan staan.
     */
    static void fetch(String url, File part, long size, Step p, java.util.function.BooleanSupplier stop) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt <= WAIT.length; attempt++) {
            if (stop.getAsBoolean()) throw new Stopped();
            try {
                once(url, part, size, p, stop);
                return;
            } catch (Stopped | Fatal e) {
                throw e;
            } catch (IOException e) {
                last = e; // verbinding weg of haperend: zo meteen verder waar hij was
                if (attempt == WAIT.length) break;
                long until = System.currentTimeMillis() + WAIT[attempt];
                while (System.currentTimeMillis() < until) { if (stop.getAsBoolean()) throw new Stopped(); Thread.sleep(250); }
            }
        }
        throw new Exception("Geen verbinding" + (last != null && last.getMessage() != null ? " (" + last.getMessage() + ")" : "") + "; tik op Verder om door te gaan");
    }

    /** Een fout waarbij opnieuw proberen niet helpt (bijv. HTTP 404 of een te groot bestand). */
    static final class Fatal extends Exception { Fatal(String m) { super(m); } }

    private static void once(String url, File part, long size, Step p, java.util.function.BooleanSupplier stop) throws Exception {
        long have = part.isFile() ? part.length() : 0;
        if (size > 0 && have > size) { part.delete(); have = 0; } // groter dan het hoort: opnieuw
        if (size > 0 && have == size) { p.step(have, size); return; }
        HttpURLConnection h = open(url, have);
        try {
            int code = h.getResponseCode();
            boolean append;
            if (code == 206) {
                append = have > 0 && rangeStart(h.getHeaderField("Content-Range")) == have;
                if (!append) { h.disconnect(); part.delete(); h = open(url, 0); code = h.getResponseCode(); if (code != 200) throw new Fatal("Download mislukt (HTTP " + code + ")"); }
            } else if (code == 200) {
                append = false; // de server hervat niet: van voren af aan
            } else if (code == 416 && have > 0) {
                part.delete(); // het stuk dat er stond past niet: opnieuw
                throw new IOException("opnieuw beginnen");
            } else if (code >= 500 || code == 429 || code == 408) {
                throw new IOException("HTTP " + code);
            } else {
                throw new Fatal("Download mislukt (HTTP " + code + ")");
            }
            long len = h.getContentLengthLong();
            long start = append ? have : 0;
            long total = size > 0 ? size : (len > 0 ? start + len : 0);
            if (size > 0 && len > 0 && start + len != size) throw new Fatal("Onverwachte grootte van de download");
            long done = start;
            p.step(done, total);
            try (InputStream in = h.getInputStream(); OutputStream o = new FileOutputStream(part, append)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (stop.getAsBoolean()) throw new Stopped();
                    o.write(buf, 0, n);
                    done += n;
                    if (size > 0 && done > size) throw new Fatal("Download groter dan verwacht");
                    p.step(done, total);
                }
            }
            if (total > 0 && done < total) throw new IOException("verbinding verbroken bij " + done + " van " + total);
        } finally { h.disconnect(); }
    }

    /** Verbinding openen, doorverwijzingen zelf volgen (alleen naar https), met Range als er al een stuk is. */
    private static HttpURLConnection open(String url, long from) throws Exception {
        for (int hop = 0; hop < 8; hop++) {
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
            h.setInstanceFollowRedirects(false);
            h.setConnectTimeout(20_000);
            h.setReadTimeout(60_000);
            h.setRequestProperty("User-Agent", "Tools");
            h.setRequestProperty("Accept-Encoding", "identity");
            if (from > 0) h.setRequestProperty("Range", "bytes=" + from + "-");
            int code = h.getResponseCode();
            String loc = h.getHeaderField("Location");
            if (code >= 300 && code < 400 && loc != null) {
                h.disconnect();
                url = new URL(new URL(url), loc).toString();
                if (!url.startsWith("https://")) throw new Fatal("Download mislukt: onveilige doorverwijzing");
                continue;
            }
            return h;
        }
        throw new Fatal("Download mislukt: te veel doorverwijzingen");
    }

    /** "bytes 100-199/200" → 100; anders -1. Zuiver (getest). */
    static long rangeStart(String cr) {
        if (cr == null) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^bytes (\\d+)-\\d+/(\\d+|\\*)$").matcher(cr.trim());
        return m.matches() ? Long.parseLong(m.group(1)) : -1;
    }
}
