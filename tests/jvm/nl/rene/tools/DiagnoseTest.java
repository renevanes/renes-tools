package nl.rene.tools;

import org.json.JSONArray;

/** Diagnose: alleen in het geheugen, begrensd; het lijstje van wat verstuurd is bevat nooit inhoud. */
public class DiagnoseTest {
    static void check(boolean ok, String m) { if (!ok) throw new AssertionError(m); System.out.println("✓ " + m); }

    public static void main(String[] a) throws Exception {
        Diagnose.wis();
        String id = Diagnose.legAlleen("Betalen!", "Rekening\ngelezen", "naam ontvanger\nJansen B.V.", System.currentTimeMillis());
        Diagnose.Item it = Diagnose.item(id);
        check(it != null && "betalen".equals(it.tool) && "Rekening gelezen".equals(it.titel) && it.verstuurd == 0, "vastgelegd: toolnaam alleen kleine letters en cijfers, de titel op één regel");
        String tekst = Diagnose.tekst(id);
        check(tekst.startsWith("Diagnose Tools ") && tekst.contains("Tool: betalen") && tekst.contains("Wat: Rekening gelezen") && tekst.endsWith("Jansen B.V."), "wat er gekopieerd of verstuurd wordt: een kop en precies de vastgelegde tekst");
        check(Diagnose.tekst("d999").isEmpty() && "tools".equals(Diagnose.toolNaam("../..")) && "tools".equals(Diagnose.toolNaam(null)) && Diagnose.toolNaam("abcdefghijklmnopqrstuvwxyz0123").length() == 24, "onbekend id: leeg; een vreemde toolnaam wordt een veilige naam");
        StringBuilder lang = new StringBuilder();
        while (lang.length() < Diagnose.MAX_TEKENS + 500) lang.append("0123456789");
        Diagnose.Item groot = Diagnose.item(Diagnose.legAlleen("betalen", "x", lang.toString(), System.currentTimeMillis()));
        check(groot.tekst.length() <= Diagnose.MAX_TEKENS + 20 && groot.tekst.endsWith("[afgekapt]"), "een te lange tekst wordt afgekapt");
        for (int i = 0; i < Diagnose.MAX + 5; i++) Diagnose.legAlleen("nieuws", "n" + i, "t", System.currentTimeMillis());
        check(Diagnose.item(id) == null, "hooguit " + Diagnose.MAX + " stukken: de oudste vallen weg");

        String pad = Diagnose.pad(1_800_000_000_000L, "ab12cd34", "Betalen", "d7");
        check(pad.matches("diagnose/2027-01-15T080000Z-ab12cd34-betalen-d7\\.txt") && pad.matches("diagnose/[A-Za-z0-9T_-]{1,120}\\.txt"), "bestandsnaam: map diagnose, tijd, installatie, tool en volgnummer (" + pad + ")");
        check(Diagnose.pad(1L, "x", "../../statistiek/a", "d1/../x").matches("diagnose/[A-Za-z0-9T_-]{1,120}\\.txt"), "een vreemde toolnaam of id komt nooit buiten de map diagnose");

        check(Statistiek.prive("{\"full_name\":\"a/b\",\"private\":true}") && !Statistiek.prive("{\"private\":false}")
                && !Statistiek.prive("{\"private\":\"true\"}") && !Statistiek.prive("{}") && !Statistiek.prive("") && !Statistiek.prive(null)
                && !Statistiek.prive("geen json"), "versturen alleen als GitHub de repository precies als privé meldt");

        String zl = Diagnose.zonderLinks("Betaal via https://tikkie.me/pay/AbC123 of www.betaal.nl/x?id=9\nanders tikkie.me/pay/Q1 binnen 14 dagen (12.03.2026), bedrag 12,50 aan Jansen B.V.");
        check(!zl.contains("tikkie") && !zl.contains("betaal.nl") && zl.contains("[link]") && zl.contains("12.03.2026") && zl.contains("12,50") && zl.contains("Jansen B.V."), "betaallinks worden [link], gewone tekst blijft (" + zl + ")");
        StringBuilder punten = new StringBuilder();
        for (int i = 0; i < 100_000; i++) punten.append("a.");
        long t0 = System.nanoTime();
        Diagnose.zonderLinks(punten.toString());
        check(System.nanoTime() - t0 < 2_000_000_000L, "links weghalen blijft snel bij lange tekst zonder schuine streep");

        JSONArray v = Diagnose.erbij(new JSONArray(), 5L, "betalen", 1234);
        for (int i = 0; i < 40; i++) v = Diagnose.erbij(v, 10L + i, "Betalen", i);
        check(v.length() == Diagnose.MAX_VERSTUURD && v.getJSONObject(0).getLong("t") == 49L && v.getJSONObject(0).length() == 3, "wat de telefoon verliet: de nieuwste vooraan, hooguit " + Diagnose.MAX_VERSTUURD + ", per regel alleen tijd, tool en aantal tekens");
        JSONArray vreemd = Diagnose.erbij(new JSONArray("[{\"t\":1,\"tool\":\"x\",\"n\":2,\"tekst\":\"geheim\"},\"los\"]"), 3L, "a", 1);
        check(vreemd.length() == 2 && !vreemd.toString().contains("geheim"), "een bewaard lijstje met iets extra's: alleen de drie velden blijven");
        check(Diagnose.verstuurd("geen json").length() == 0 && Diagnose.verstuurd(null).length() == 0, "een onleesbaar lijstje: leeg");
        check(Diagnose.autoGeldt(true, "a/b", "a/b") && !Diagnose.autoGeldt(true, "a/b", "a/c") && !Diagnose.autoGeldt(true, "", "") && !Diagnose.autoGeldt(false, "a/b", "a/b") && !Diagnose.autoGeldt(true, "a/b", null),
                "vanzelf sturen geldt alleen voor de repository waarvoor het is aangezet");
        long nu = System.currentTimeMillis();
        String oud = Diagnose.legAlleen("betalen", "oud", "t", nu - Diagnose.HOUDBAAR - 1000), vers = Diagnose.legAlleen("betalen", "vers", "t", nu);
        check(Diagnose.item(oud) == null && Diagnose.item(vers) != null && Diagnose.item(vers, nu + Diagnose.HOUDBAAR + 1000) == null, "een stuk blijft hooguit een uur in het geheugen");
        Diagnose.wis();
        check(Diagnose.item(vers) == null, "wissen: alles weg");
    }
}
