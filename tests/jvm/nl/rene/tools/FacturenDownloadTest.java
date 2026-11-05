package nl.rene.tools;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Dezelfde downloadlus als in de app, met gecontroleerde HTTP-antwoorden. */
public class FacturenDownloadTest {
    static int fails;
    static void check(boolean ok, String wat) { System.out.println((ok ? "✓ " : "✗ ") + wat); if (!ok) fails++; }

    static final class Verbinding extends HttpURLConnection {
        final int code;
        final String locatie, inhoud;
        boolean dicht;
        Verbinding(String url, int code, String locatie, String inhoud) throws Exception { super(new URL(url)); this.code = code; this.locatie = locatie; this.inhoud = inhoud; }
        public int getResponseCode() { return code; }
        public String getHeaderField(String naam) { return "Location".equals(naam) ? locatie : "Content-Disposition".equals(naam) ? "attachment; filename=F2026.pdf" : null; }
        public Map<String, List<String>> getHeaderFields() { return Collections.emptyMap(); }
        public InputStream getInputStream() { return new ByteArrayInputStream(inhoud.getBytes(StandardCharsets.UTF_8)); }
        public void disconnect() { dicht = true; }
        public boolean usingProxy() { return false; }
        public void connect() { }
    }
    static final class Bron implements FacturenRun.DownloadBron {
        int code = 200, aanvragen, pdfs;
        String locatie, inhoud = "%PDF-1.4\nvoorbeeld", naam;
        final java.util.List<Verbinding> verbindingen = new java.util.ArrayList<>();
        public HttpURLConnection open(String url) throws Exception { aanvragen++; Verbinding h = new Verbinding(url, code, locatie, inhoud); verbindingen.add(h); return h; }
        public String cookie(String url) { return null; }
        public void zetCookie(String url, String waarde) { }
        public void status(int code) { }
        public boolean neem(byte[] b, String naam, String url) { this.naam = naam; if (!Facturen.isPdf(b)) return false; pdfs++; return true; }
        boolean haal() throws Exception { return FacturenRun.haalPdf("https://leverancier.test/f.pdf", null, null, "Tools-test", this); }
    }
    public static void main(String[] args) throws Exception {
        Bron b = new Bron();
        check(b.haal() && b.pdfs == 1 && "F2026.pdf".equals(b.naam), "PDF wordt pas na geldige bytes aangenomen");
        b.code = 404;
        check(!b.haal() && b.pdfs == 1, "een tweede download met 404 is een fout ondanks een eerdere geslaagde PDF");
        b.code = 500;
        check(!b.haal(), "serverfout is geen volledige download");
        b.code = 200; b.inhoud = "<html>Inloggen</html>";
        check(!b.haal(), "webpagina in plaats van PDF telt niet als download");
        b.code = 302; b.locatie = "http://leverancier.test/f.pdf";
        int voor = b.aanvragen;
        check(!b.haal() && b.aanvragen == voor + 1, "redirect naar HTTP wordt geweigerd en is een mislukte download");
        b.locatie = "https://leverancier.test/lus"; voor = b.aanvragen;
        check(!b.haal() && b.aanvragen == voor + 6, "redirectlus eindigt als fout, niet als geslaagde PDF");
        check(b.verbindingen.stream().allMatch(v -> v.dicht), "alle verbindingen worden gesloten");
        if (fails > 0) throw new AssertionError(fails + " controles mislukt");
    }
}
