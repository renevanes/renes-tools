package nl.rene.tools;

import java.util.Arrays;
import java.util.Set;

/** Extra ontgrendelen per tool: alleen echte tools, aan en uit per tool, en wanneer terugkomen weer op slot zet. */
public class ToolSlotTest {
    static void check(boolean yes, String text) { if (!yes) throw new AssertionError(text); }
    public static void main(String[] args) {
        Set<String> s = ToolSlot.geldig(Arrays.asList("notes", "../home", "settings", "notes", null, "checkin"));
        check(s.equals(new java.util.TreeSet<>(Arrays.asList("checkin", "notes"))), "alleen tools uit het overzicht, zonder dubbele: " + s);
        check(ToolSlot.geldig(null).isEmpty(), "geen lijst = niets aan");

        Set<String> aan = ToolSlot.met(s, "calls", true);
        check(aan.contains("calls") && aan.contains("notes") && aan.contains("checkin"), "aanzetten voegt één tool toe");
        check(!s.contains("calls"), "de oude lijst blijft ongewijzigd");
        Set<String> uit = ToolSlot.met(aan, "notes", false);
        check(!uit.contains("notes") && uit.contains("calls"), "uitzetten haalt alleen die tool weg");
        check(ToolSlot.met(uit, "settings", true).equals(uit), "een onbekende naam verandert niets");
        check(ToolSlot.met(uit, "", true).equals(uit) && ToolSlot.met(uit, null, false).equals(uit), "lege naam verandert niets");
        for (String t : Solo.TOOLS) check(ToolSlot.met(null, t, true).contains(t), "elke tool kan extra ontgrendelen krijgen: " + t);

        check(ToolSlot.naam("Notities").equals("Notities"), "gewone naam blijft");
        check(ToolSlot.naam(" Not\u202Eities\n ").equals("Notities"), "geen stuur- of richtingtekens in de vraag");
        check(ToolSlot.naam(null).isEmpty(), "geen naam");
        check(ToolSlot.naam(new String(new char[100]).replace('\0', 'a')).length() == 40, "naam ingekort");

        long nu = 10_000_000L;
        check(!ToolSlot.teLang(false, nu - 1_000_000L, nu), "gewoon weggaan zette hem al bij het weggaan op slot");
        check(!ToolSlot.teLang(true, nu - 60_000L, nu), "kort in een eigen kiezer: blijft open");
        check(ToolSlot.teLang(true, nu - ToolSlot.KIES_MS - 1, nu), "te lang in een eigen scherm: weer op slot");
        check(!ToolSlot.teLang(true, 0, nu), "nog nooit uit beeld geweest");
        System.out.println("ok");
    }
}
