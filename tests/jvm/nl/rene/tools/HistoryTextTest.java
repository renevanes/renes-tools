package nl.rene.tools;

public final class HistoryTextTest {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        check(HistoryText.clean(null, 8).equals(""), "ontbrekende tekst");
        check(HistoryText.clean("a\u0000b", 8).equals("ab"), "NUL in SQLite-tekst");
        check(HistoryText.clean("abc😀", 4).equals("abc"), "geen halve emoji bij begrenzen");
        check(HistoryText.html("<script>\"&'").equals("&lt;script&gt;&quot;&amp;&#39;"), "export ontsnapt HTML");
        String original = HistoryText.fingerprint("app|key", 42, "Titel", "Bericht");
        check(original.equals(HistoryText.fingerprint("app|key", 42, "Titel", "Bericht")), "reconnect deduplicatie");
        check(!original.equals(HistoryText.fingerprint("app|key", 42, "Titel", "Nieuw bericht")), "bijgewerkte inhoud bewaren");
        check(!original.equals(HistoryText.fingerprint("app|key", 43, "Titel", "Bericht")), "volgende melding bewaren");
        check(!HistoryText.fingerprint("a", 1, "bc", "d").equals(HistoryText.fingerprint("a", 1, "b", "cd")), "geen ambiguïteit tussen velden");
        check(HistoryText.fold("Éénmaal Café").equals("eenmaal cafe"), "zoeken zonder accenten en hoofdletters");
        check(HistoryText.fold("ÉLAN").contains(HistoryText.fold("é")), "É vindt é");
        System.out.println("Alle 10 meldingsgeschiedenis-teksttests geslaagd");
    }
}
