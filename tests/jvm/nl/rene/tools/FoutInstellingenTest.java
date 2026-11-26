package nl.rene.tools;

/** Foutrapport en statistieken: alleen bekende keuzes, het tempo van het foutlogboek en wanneer de statistieken weer gaan. */
public class FoutInstellingenTest {
    static void check(boolean value, String why) { if (!value) throw new AssertionError(why); }

    public static void main(String[] args) {
        // Alleen bekende sleutels en waarden; de rest wordt geweigerd (dan blijft de standaard)
        check("7".equals(FoutInstellingen.normaal("elke", " 7 ")) && "1".equals(FoutInstellingen.normaal("elke", "1")) && "30".equals(FoutInstellingen.normaal("elke", "30")), "elke dag, week of maand");
        check(FoutInstellingen.normaal("elke", "0") == null && FoutInstellingen.normaal("elke", "365") == null && FoutInstellingen.normaal("elke", "x") == null, "geen andere aantallen");
        check("uur".equals(FoutInstellingen.normaal("tempo", "uur")) && FoutInstellingen.normaal("tempo", "meteen") == null, "tempo: snel, uur of dag");
        check("false".equals(FoutInstellingen.normaal("wifi", "false")) && FoutInstellingen.normaal("wifi", "ja") == null, "aan of uit");
        check("240".equals(FoutInstellingen.normaal("diagBewaar", "240")) && FoutInstellingen.normaal("diagBewaar", "99999") == null, "bewaartijd: 15, 60 of 240 minuten");
        check(FoutInstellingen.normaal("repo", "x") == null && FoutInstellingen.normaal(null, "1") == null && FoutInstellingen.normaal("elke", null) == null, "onbekende sleutel");
        check("7".equals(FoutInstellingen.standaard("elke")) && "snel".equals(FoutInstellingen.standaard("tempo")) && "false".equals(FoutInstellingen.standaard("wifi"))
                && "true".equals(FoutInstellingen.standaard("diagLeg")) && "60".equals(FoutInstellingen.standaard("diagBewaar")), "standaard zoals het was");

        // Tempo van het foutlogboek
        check(FoutInstellingen.per("snel", true) == Logboek.KWARTIER && FoutInstellingen.per("snel", false) == Logboek.UUR, "snel: na een probleem per kwartier, anders per uur");
        check(FoutInstellingen.per("uur", true) == Logboek.UUR && FoutInstellingen.per("dag", true) == FoutInstellingen.DAG, "per uur of per dag, ook na een probleem");
        long nu = 1_800_000_000_000L;
        check(Logboek.wachtTijd(nu - 10 * 60_000L, nu, false, "dag") == FoutInstellingen.DAG - 10 * 60_000L, "per dag: wacht tot de dag om is");
        check(Logboek.wachtTijd(0, nu, true, "dag") == Logboek.WACHT_MS, "nog nooit verstuurd: na een paar minuten");
        check(Logboek.wachtTijd(nu - 2 * 60_000L, nu, true) == Logboek.wachtTijd(nu - 2 * 60_000L, nu, true, "snel"), "zonder keuze: snel (zoals het was)");

        // Statistieken: elke dag, week of maand, met een paar uur speling
        long dag = FoutInstellingen.DAG;
        check(FoutInstellingen.statAanDeBeurt(0, nu, 7), "nog nooit verstuurd: nu");
        check(!FoutInstellingen.statAanDeBeurt(nu - 6 * dag, nu, 7) && FoutInstellingen.statAanDeBeurt(nu - 7 * dag + 3_600_000L, nu, 7), "week: na zes dagen niet, net voor zeven dagen wel (speling)");
        check(FoutInstellingen.statAanDeBeurt(nu - dag + 3_600_000L, nu, 1) && !FoutInstellingen.statAanDeBeurt(nu - 12 * 3_600_000L, nu, 1), "elke dag: ook als het onderhoud iets eerder loopt");
        check(!FoutInstellingen.statAanDeBeurt(nu - 20 * dag, nu, 30) && FoutInstellingen.statAanDeBeurt(nu - 30 * dag, nu, 30), "maand");
        check(FoutInstellingen.statAanDeBeurt(nu + dag, nu, 7), "klok teruggezet: gewoon versturen");
        System.out.println("FoutInstellingenTest: alles goed");
    }
}
