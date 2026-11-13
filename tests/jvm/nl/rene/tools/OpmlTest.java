package nl.rene.tools;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** OPML lezen en schrijven: heen en terug, rommel, grenzen en geen externe entiteiten. */
public final class OpmlTest {
    private static int checks;
    private static void check(boolean c, String t) { checks++; if (!c) throw new AssertionError(t); }
    private static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static String fout(String xml) { try { Opml.lees(b(xml)); return ""; } catch (Exception e) { return String.valueOf(e.getMessage()); } }

    public static void main(String[] args) throws Exception {
        List<Opml.Regel> in = new ArrayList<>();
        in.add(new Opml.Regel("Nieuws & \"Zo\" <b>", "https://voorbeeld.nl/feed?a=1&b=2"));
        in.add(new Opml.Regel("", "http://oud.voorbeeld.nl/rss"));
        in.add(new Opml.Regel("Weg", "javascript:alert(1)"));
        in.add(null);
        String xml = Opml.schrijf("Podcasts uit Tools", in);
        check(xml.startsWith("<?xml") && xml.contains("<opml version=\"2.0\">") && !xml.contains("javascript:"), "kop en alleen webadressen");
        check(xml.contains("Nieuws &amp; &quot;Zo&quot; &lt;b&gt;") && xml.contains("a=1&amp;b=2"), "tekens ontsnapt");
        List<Opml.Regel> uit = Opml.lees(b(xml));
        check(uit.size() == 2 && uit.get(0).titel.equals("Nieuws & \"Zo\" <b>") && uit.get(0).url.equals("https://voorbeeld.nl/feed?a=1&b=2") && uit.get(1).url.equals("http://oud.voorbeeld.nl/rss"), "heen en terug gelijk");

        // Wat andere apps maken: geneste mappen, text in plaats van title, hoofdletters, BOM, dubbele regels
        String ander = "﻿<?xml version='1.0'?><OPML version='1.0'><body><outline text='Map'><outline TEXT='Eén' XMLURL='https://a.nl/x'/>"
                + "<outline text='Twee' title='  Twee‮  titel ' xmlUrl=' https://b.nl/y '/><outline text='Dubbel' xmlUrl='https://a.nl/x'/>"
                + "<outline text='Geen feed' htmlUrl='https://c.nl'/><outline text='Raar' xmlUrl='ftp://d.nl/z'/><outline text='Spatie' xmlUrl='https://e.nl/a b'/></outline></body></OPML>";
        List<Opml.Regel> r = Opml.lees(b(ander));
        check(r.size() == 2 && r.get(0).titel.equals("Eén") && r.get(1).titel.equals("Twee titel") && r.get(1).url.equals("https://b.nl/y"), "geneste lijst, eigenschappen in elke schrijfwijze, dubbele en rare adressen weg: " + r.size());

        check(fout("").contains("leeg") && fout("gewoon tekst").contains("geen OPML") && fout("<rss><channel/></rss>").contains("geen OPML"), "geen OPML");
        check(fout("<opml><body><outline text='x'/></body></opml>").contains("geen feeds"), "OPML zonder feeds");
        check(!fout("<!DOCTYPE opml [<!ENTITY a 'aaaa'>]><opml><body><outline text='&a;' xmlUrl='https://a.nl/x'/></body></opml>").isEmpty(), "eigen entiteiten worden geweigerd");
        check(!fout("<!DOCTYPE opml [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><opml><body><outline text='&x;' xmlUrl='https://a.nl/x'/></body></opml>").isEmpty(), "geen externe bestanden");
        byte[] groot = new byte[Opml.MAX_BYTES + 1];
        try { Opml.lees(groot); check(false, "te groot moet falen"); } catch (Exception e) { check(String.valueOf(e.getMessage()).contains("te groot"), "te groot"); }
        StringBuilder veel = new StringBuilder("<opml><body>");
        for (int i = 0; i < Opml.MAX + 50; i++) veel.append("<outline text='p").append(i).append("' xmlUrl='https://a.nl/").append(i).append("'/>");
        check(Opml.lees(b(veel.append("</body></opml>").toString())).size() == Opml.MAX, "hooguit " + Opml.MAX + " regels");
        // Afgebroken bestand: wat al gelezen is telt
        check(Opml.lees(b("<opml><body><outline text='a' xmlUrl='https://a.nl/1'/><outline text='b' xmlUrl='https://a.nl/2'")).size() == 1, "afgebroken bestand: het gelezen deel");

        check(Opml.adresGoed("https://a.nl/x") && Opml.adresGoed("http://localhost:8080/f") && !Opml.adresGoed("https://geenpunt") && !Opml.adresGoed(null) && !Opml.adresGoed("https://a.nl/\"x"), "adrescontrole");
        check(Opml.netjes(" a\n\tb\u0000c ").equals("a b c") && Opml.netjes(null).isEmpty() && Opml.netjes(new String(new char[400]).replace('\0', 'x')).length() == 300, "titel netjes en begrensd");
        System.out.println("Alle " + checks + " OPML-controles geslaagd");
    }
}
