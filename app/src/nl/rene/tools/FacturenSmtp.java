package nl.rene.tools;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Een kleine SMTP-client om mail te versturen via je eigen mailserver: SSL (poort 465) of STARTTLS (poort 587), met
 * controle van het certificaat en de servernaam, inloggen met AUTH PLAIN of LOGIN. Zonder versleuteling alleen naar de
 * eigen telefoon (localhost), zodat een wachtwoord nooit onversleuteld over het netwerk gaat.
 */
final class FacturenSmtp {
    private FacturenSmtp() { }

    static final class Verbinding implements AutoCloseable {
        Socket s; InputStream in; OutputStream out;
        final String host;
        String laatste = "";
        Verbinding(String host) { this.host = host; }

        void zet(Socket x) throws Exception { s = x; s.setSoTimeout(60_000); in = new BufferedInputStream(s.getInputStream()); out = s.getOutputStream(); }

        /** Een antwoord van de server (ook meerdere regels: "250-…" … "250 …"). */
        int antwoord() throws Exception {
            StringBuilder alles = new StringBuilder();
            while (true) {
                String r = regel();
                if (r == null) throw new Exception("De mailserver verbrak de verbinding");
                alles.append(r).append('\n');
                if (alles.length() > 64_000) throw new Exception("De mailserver antwoordt vreemd");
                if (r.length() < 3 || !r.substring(0, 3).matches("\\d{3}")) throw new Exception("De mailserver antwoordt vreemd");
                if (r.length() == 3 || r.charAt(3) != '-') { laatste = alles.toString(); return Integer.parseInt(r.substring(0, 3)); }
            }
        }

        private String regel() throws Exception {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            int ch;
            while ((ch = in.read()) != -1) {
                if (ch == '\n') break;
                if (ch != '\r') b.write(ch);
                if (b.size() > 4096) throw new Exception("De mailserver antwoordt vreemd");
            }
            if (ch == -1 && b.size() == 0) return null;
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }

        void zeg(String r) throws Exception {
            if (r.indexOf('\r') >= 0 || r.indexOf('\n') >= 0) throw new Exception("ongeldige opdracht");
            out.write((r + "\r\n").getBytes(StandardCharsets.UTF_8)); out.flush();
        }

        int vraag(String r, int... goed) throws Exception {
            zeg(r);
            int code = antwoord();
            for (int g : goed) if (code == g) return code;
            throw new Exception(fout(code, r));
        }

        String fout(int code, String r) {
            String w = r.split(" ")[0].toUpperCase(Locale.ROOT);
            String tekst = laatste.trim().replaceAll("^\\d{3}[ -]", "").replaceAll("\\n\\d{3}[ -]", " ");
            if (tekst.length() > 160) tekst = tekst.substring(0, 160) + "…";
            if (w.equals("AUTH") || code == 535) return "Inloggen bij de mailserver lukt niet: controleer gebruikersnaam en wachtwoord";
            if (w.startsWith("RCPT")) return "De mailserver weigert het adres (" + code + " " + tekst + ")";
            return "Mailserver: " + code + " " + tekst;
        }

        @Override public void close() { try { if (s != null) s.close(); } catch (Exception ignored) { } }
    }

    static boolean lokaal(String host) { return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host); }

    private static Socket verbind(String host, int poort) throws Exception {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host.replaceAll("^\\[|\\]$", ""), poort), 20_000);
        return s;
    }

    /** TLS over een verbinding; met de servernaam erbij (SNI), zodat de server het juiste certificaat geeft. */
    private static SSLSocket tls(Socket onder, String host, int poort) throws Exception {
        SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket s = (SSLSocket) f.createSocket(onder, host, poort, true);
        s.setSoTimeout(60_000);
        s.startHandshake();
        // SSLSocket controleert de servernaam niet zelf: doe dat zoals HTTPS
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.getSession())) { s.close(); throw new Exception("Het certificaat van de mailserver hoort niet bij " + host); }
        return s;
    }

    /** Controle van de instellingen (zonder netwerk). */
    static void controleer(String host, int poort, String beveiliging) throws Exception {
        if (host == null || !host.matches("[A-Za-z0-9.-]{1,253}|\\[[0-9a-fA-F:]+\\]|::1")) throw new Exception("Vul de naam van de mailserver in, bijv. smtp.voorbeeld.nl");
        if (poort < 1 || poort > 65535) throw new Exception("Poort: 1 tot 65535 (meestal 465 of 587)");
        if (!"ssl".equals(beveiliging) && !"starttls".equals(beveiliging) && !"geen".equals(beveiliging)) throw new Exception("Kies SSL of STARTTLS");
        if ("geen".equals(beveiliging) && !lokaal(host)) throw new Exception("Zonder versleuteling alleen naar deze telefoon zelf (localhost)");
    }

    /** Een mail versturen. rcpt = alle ontvangers (aan, cc, bcc); mime = het hele bericht (null: alleen verbinden en inloggen). */
    static void stuur(String host, int poort, String beveiliging, String gebruiker, String wachtwoord, String van, List<String> rcpt, byte[] mime) throws Exception {
        controleer(host, poort, beveiliging);
        if (mime != null && rcpt.isEmpty()) throw new Exception("Geen ontvanger");
        try (Verbinding v = new Verbinding(host)) {
            v.zet(verbind(host, poort)); // eerst vastleggen: zo sluit close() hem ook als TLS mislukt
            if ("ssl".equals(beveiliging)) v.zet(tls(v.s, host, poort));
            if (v.antwoord() != 220) throw new Exception(v.fout(0, "CONNECT"));
            v.vraag("EHLO tools.local", 250);
            String mogelijk = v.laatste.toUpperCase(Locale.ROOT);
            if ("starttls".equals(beveiliging)) {
                if (!mogelijk.contains("STARTTLS")) throw new Exception("De mailserver kan geen STARTTLS: kies SSL (meestal poort 465)");
                v.vraag("STARTTLS", 220);
                v.zet(tls(v.s, host, poort));
                v.vraag("EHLO tools.local", 250);
                mogelijk = v.laatste.toUpperCase(Locale.ROOT);
            }
            if (gebruiker != null && !gebruiker.isEmpty()) {
                if (wachtwoord == null) throw new Exception("Vul het wachtwoord van de mailserver in");
                if (mogelijk.matches("(?s).*AUTH[ =][^\\n]*PLAIN.*") || !mogelijk.contains("LOGIN")) {
                    String t = FacturenEmail.base64(("\0" + gebruiker + "\0" + wachtwoord).getBytes(StandardCharsets.UTF_8), false);
                    v.vraag("AUTH PLAIN " + t, 235);
                } else {
                    v.vraag("AUTH LOGIN", 334);
                    v.vraag(FacturenEmail.base64(gebruiker.getBytes(StandardCharsets.UTF_8), false), 334);
                    v.zeg(FacturenEmail.base64(wachtwoord.getBytes(StandardCharsets.UTF_8), false));
                    if (v.antwoord() != 235) throw new Exception(v.fout(535, "AUTH"));
                }
            }
            if (mime == null) { try { v.zeg("QUIT"); v.antwoord(); } catch (Exception ignored) { } return; } // alleen testen: verbinden en inloggen
            v.vraag("MAIL FROM:<" + adres(van) + ">", 250);
            for (String r : rcpt) v.vraag("RCPT TO:<" + adres(r) + ">", 250, 251);
            v.vraag("DATA", 354);
            punten(mime, v.out);
            v.out.write(".\r\n".getBytes(StandardCharsets.US_ASCII));
            v.out.flush();
            int code = v.antwoord();
            if (code != 250) throw new Exception(v.fout(code, "DATA"));
            try { v.zeg("QUIT"); v.antwoord(); } catch (Exception ignored) { }
        }
    }

    static String adres(String a) throws Exception {
        if (a == null || !a.matches(FacturenEmail.ADRES)) throw new Exception("Ongeldig mailadres");
        return a;
    }

    /** Regels die met een punt beginnen krijgen een extra punt (RFC 5321 4.5.2); eindigt altijd op CRLF. */
    static byte[] punten(byte[] m) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(m.length + 64);
        try { punten(m, o); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        return o.toByteArray();
    }

    /** Rechtstreeks naar de server (gebufferd), zonder een tweede kopie van de mail in het geheugen. */
    static void punten(byte[] m, OutputStream uit) throws java.io.IOException {
        java.io.BufferedOutputStream o = new java.io.BufferedOutputStream(uit, 65536);
        boolean begin = true;
        for (int i = 0; i < m.length; i++) {
            byte b = m[i];
            if (begin && b == '.') o.write('.');
            if (b == '\n' && (i == 0 || m[i - 1] != '\r')) o.write('\r');
            o.write(b);
            begin = b == '\n';
        }
        if (!begin) { o.write('\r'); o.write('\n'); }
        o.flush();
    }
}
