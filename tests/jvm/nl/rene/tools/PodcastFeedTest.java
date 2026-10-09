package nl.rene.tools;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStream;

/** Podcast-feeds lezen (zonder Android). */
public class PodcastFeedTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    public static void main(String[] a) throws Exception {
        PodcastFeed.Feed f;
        try (InputStream in = new FileInputStream("tests/jvm/podcast/feed.xml")) { f = PodcastFeed.parse(in, 100); }
        check("De Test Podcast".equals(f.title), "titel: " + f.title);
        check("Rene & Co".equals(f.author), "maker met &: " + f.author);
        check("https://example.org/groot.jpg".equals(f.image), "itunes:image wint: " + f.image);
        check(f.desc.startsWith("Elke week een gesprek.") && f.desc.contains("Met & zonder gasten – altijd leuk."), "beschrijving zonder HTML: " + f.desc.replace("\n", " | "));
        check(f.items.size() == 3, "3 afleveringen met audio (zonder audio overgeslagen): " + f.items.size());
        PodcastFeed.Episode e2 = f.items.get(0);
        check("Aflevering 2: Over <HTML>".equals(e2.title), "titel met tekens: " + e2.title);
        check("ep-2".equals(e2.guid) && "https://cdn.example.org/ep2.mp3".equals(e2.url) && e2.size == 12345678L && "audio/mpeg".equals(e2.type), "enclosure");
        check(e2.dur == 3723, "duur 1:02:03 = 3723 s: " + e2.dur);
        check(e2.number == 2, "afleveringnummer");
        check(e2.desc.startsWith("Een veel langere beschrijving") && !e2.desc.contains("<"), "lange beschrijving zonder tags: " + e2.desc);
        check("https://example.org/ep2.jpg".equals(e2.image), "eigen plaatje");
        java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        cal.setTimeInMillis(e2.date);
        check(cal.get(java.util.Calendar.YEAR) == 2025 && cal.get(java.util.Calendar.MONTH) == 9 && cal.get(java.util.Calendar.DAY_OF_MONTH) == 7 && cal.get(java.util.Calendar.HOUR_OF_DAY) == 4, "datum met tijdzone");
        PodcastFeed.Episode e1 = f.items.get(1);
        check(e1.guid.equals(e1.url) && e1.dur == 3723 && e1.date > 0, "zonder guid: adres als id; duur in seconden; GMT-datum");
        PodcastFeed.Episode e0 = f.items.get(2);
        check("https://cdn.example.org/ep0.mp3".equals(e0.url) && e0.dur == 2730 && e0.date > 0, "media:content en ISO-datum");
        // Latin-1 volgens de xml-kop
        try (InputStream in = new FileInputStream("tests/jvm/podcast/latin1.xml")) { f = PodcastFeed.parse(in, 10); }
        check("Café Gesprekken".equals(f.title) && "Crème brûlée".equals(f.items.get(0).title), "latin-1 feed: " + f.title + " / " + f.items.get(0).title);
        // Eigen entiteiten worden geweigerd
        boolean ent = false;
        try (InputStream in = new FileInputStream("tests/jvm/podcast/entiteit.xml")) { PodcastFeed.parse(in, 10); } catch (Exception x) { ent = x.getMessage().contains("onveilig"); }
        check(ent, "feed met eigen entiteiten geweigerd");
        // Ook als de declaratie pas na een lang commentaar komt
        StringBuilder pad = new StringBuilder("<?xml version=\"1.0\"?><!--");
        for (int i = 0; i < 600; i++) pad.append("opvulling ");
        String laat = pad + "--><!DOCTYPE rss [<!ENTITY a \"aaaa\">]><rss version=\"2.0\"><channel><title>&a;</title><item><title>x</title><enclosure url=\"https://x/a.mp3\"/></item></channel></rss>";
        ent = false;
        try { PodcastFeed.parse(new java.io.ByteArrayInputStream(laat.getBytes("UTF-8")), 10); } catch (Exception x) { ent = x.getMessage() != null && x.getMessage().contains("onveilig"); }
        check(ent, "entiteit na 4 KB commentaar ook geweigerd");
        // De bewaker zelf (op Android geeft de parser declaraties niet door)
        String[] fout = { pad + "--><!DOCTYPE rss [<!ENTITY a \"x\">]><rss/>", "<!-- <rss> --><!DOCTYPE rss [<!ENTITY a \"x\">]><rss/>", "<!DOCTYPE rss SYSTEM \"<!--\" [<!ENTITY a \"x\">]><rss/>" };
        for (String x : fout) {
            boolean g = false;
            try (java.io.Reader r = new PodcastFeed.EntityGuard(new java.io.StringReader(x))) { char[] buf = new char[100]; while (r.read(buf, 0, buf.length) > 0) { } }
            catch (java.io.IOException io) { g = io.getMessage().contains("onveilig"); }
            check(g, "bewaker weigert: " + x.substring(Math.max(0, x.length() - 40)));
        }
        String goed = "<?xml version=\"1.0\"?><!DOCTYPE rss SYSTEM \"http://my.netscape.com/publish/formats/rss-0.91.dtd\"><rss version=\"0.91\"><channel><title>T</title>"
                + "<item><title>Over &lt;!ENTITY&gt;</title><description><![CDATA[Zo schrijf je <!ENTITY x \"y\"> in XML]]></description><enclosure url=\"https://x/a.mp3\"/></item></channel></rss>";
        f = PodcastFeed.parse(new java.io.ByteArrayInputStream(goed.getBytes("UTF-8")), 10);
        check("T".equals(f.title) && f.items.size() == 1, "RSS 0.91 met DOCTYPE en de tekst <!ENTITY in een beschrijving: gewoon gelezen");
        // BOM vooraan (UTF-8 en UTF-16)
        String bomFeed = "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>Bóm</title><item><title>x</title><enclosure url=\"https://x/a.mp3\"/></item></channel></rss>";
        byte[] u8 = bomFeed.getBytes("UTF-8"), b8 = new byte[u8.length + 3];
        b8[0] = (byte) 0xEF; b8[1] = (byte) 0xBB; b8[2] = (byte) 0xBF; System.arraycopy(u8, 0, b8, 3, u8.length);
        f = PodcastFeed.parse(new java.io.ByteArrayInputStream(b8), 10);
        check("Bóm".equals(f.title) && f.items.size() == 1, "UTF-8 met BOM");
        f = PodcastFeed.parse(new java.io.ByteArrayInputStream(("﻿" + bomFeed.replace("version=\"1.0\"", "version=\"1.0\" encoding=\"UTF-16\"")).getBytes("UTF-16LE")), 10);
        check("Bóm".equals(f.title) && f.items.size() == 1, "UTF-16LE met BOM");
        check(PodcastFeed.charset("x".getBytes(), 1, "windows-1252").name().equals("windows-1252"), "tekenset van de server als de xml-kop niets zegt");
        // Maximum
        try (InputStream in = new FileInputStream("tests/jvm/podcast/feed.xml")) { f = PodcastFeed.parse(in, 1); }
        check(f.items.size() == 1 && f.truncated, "na het maximum gestopt");
        // Te groot
        boolean big = false;
        try (InputStream in = new PodcastFeed.Limited(new FileInputStream("tests/jvm/podcast/feed.xml"), 300)) { PodcastFeed.parse(in, 100); } catch (Exception x) { big = true; }
        check(big, "te grote feed geweigerd");
        // Geen feed
        boolean notFeed = false;
        try { PodcastFeed.parse(new ByteArrayInputStream("<html><body>hoi</body></html>".getBytes("UTF-8")), 10); } catch (Exception x) { notFeed = true; }
        check(notFeed, "gewone webpagina is geen feed");
        check(PodcastFeed.parseDuration("45:30") == 2730 && PodcastFeed.parseDuration("12.7") == 12 && PodcastFeed.parseDuration("x") == 0 && PodcastFeed.parseDuration("") == 0, "duur-varianten");
        check(PodcastFeed.parseDate("Wed, 8 Oct 2025 10:00:00 +0000") > 0 && PodcastFeed.parseDate("8 Oct 2025 10:00:00 GMT") > 0 && PodcastFeed.parseDate("onzin") == 0, "datum-varianten");
        System.out.println(fails == 0 ? "Alle podcast-tests geslaagd" : fails + " podcast-tests mislukt");
        System.exit(fails == 0 ? 0 : 1);
    }
}
