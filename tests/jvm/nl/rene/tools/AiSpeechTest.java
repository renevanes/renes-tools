package nl.rene.tools;

/** Voorlezen: opmaak eruit, en een enorme regel (lus van het model) maakt het niet traag. */
public class AiSpeechTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    static String rep(String s, int n) { StringBuilder b = new StringBuilder(); for (int i = 0; i < n; i++) b.append(s); return b.toString(); }

    public static void main(String[] args) {
        check(AiSpeech.plain("## Kop\n- **vet** en [link](https://x.nl) [1]").equals("Kop.\nvet en link."), "opmaak, links en verwijzingen eruit");
        check(AiSpeech.plain("een    twee   ,drie").equals("een twee,drie."), "spaties samengevoegd");
        String[] slow = { rep("[a](", 7500), rep("[", 30000), "a" + rep(" ", 30000) + "[1]", rep("*a ", 10000), rep("**a", 10000) };
        long t0 = System.nanoTime();
        for (String s : slow) AiSpeech.plain(s);
        long ms = (System.nanoTime() - t0) / 1000000;
        check(ms < 1500, "enorme regels blijven snel (" + ms + " ms)");
        if (fails > 0) System.exit(1);
    }
}
