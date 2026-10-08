package nl.rene.tools;

import java.nio.charset.StandardCharsets;

/** E-mails (.eml) lezen voor "E-mail naar PDF". */
public class MailParseTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }
    public static void main(String[] a) {
        String eml = "From: =?UTF-8?B?UmVuw6k=?= <rene@example.nl>\r\nTo: Jos <jos@example.nl>\r\nSubject: =?iso-8859-1?Q?Afspraak_v=F3=F3r_maandag?=\r\n"
                + "Date: Wed, 7 Oct 2026 10:00:00 +0200\r\nMIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=\"XX\"\r\n\r\n"
                + "--XX\r\nContent-Type: multipart/alternative; boundary=YY\r\n\r\n"
                + "--YY\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\nHoi Jos, tot ma=\r\nandag =E2=82=AC 5\r\n"
                + "--YY\r\nContent-Type: text/html; charset=utf-8\r\nContent-Transfer-Encoding: base64\r\n\r\n"
                + java.util.Base64.getMimeEncoder().encodeToString("<p>Hoi <b>Jos</b>, € 5</p>".getBytes(StandardCharsets.UTF_8)) + "\r\n--YY--\r\n"
                + "--XX\r\nContent-Type: application/pdf; name=\"factuur.pdf\"\r\nContent-Disposition: attachment; filename=\"factuur.pdf\"\r\nContent-Transfer-Encoding: base64\r\n\r\nJVBERi0=\r\n--XX--\r\n";
        byte[] raw = eml.getBytes(StandardCharsets.ISO_8859_1);
        check(MailParse.looksLikeMail(raw), "herkend als e-mail");
        MailParse.Mail m = MailParse.parse(raw);
        check(m.h("subject").equals("Afspraak vóór maandag"), "onderwerp (quoted-printable kop): " + m.h("subject"));
        check(m.h("from").startsWith("René "), "afzender (base64 kop): " + m.h("from"));
        check(m.text.equals("Hoi Jos, tot maandag € 5"), "tekst (quoted-printable met zachte regeleinde): " + m.text);
        check(m.html.contains("<b>Jos</b>") && m.html.contains("€"), "html-versie (base64)");
        check(m.attachments.size() == 1 && m.attachments.get(0).equals("factuur.pdf"), "bijlage alleen bij naam: " + m.attachments);
        MailParse.Mail plain = MailParse.parse("Subject: Kort\nFrom: a@b.c\n\nAlleen tekst".getBytes(StandardCharsets.UTF_8));
        check(plain.text.equals("Alleen tekst") && plain.h("subject").equals("Kort"), "eenvoudige e-mail met LF-regels");
        check(!MailParse.looksLikeMail("Gewoon een tekst\nmet regels".getBytes()), "gewone tekst is geen e-mail");
        System.out.println(fails == 0 ? "Alle e-mail-tests geslaagd" : fails + " e-mail-tests mislukt");
        System.exit(fails == 0 ? 0 : 1);
    }
}
