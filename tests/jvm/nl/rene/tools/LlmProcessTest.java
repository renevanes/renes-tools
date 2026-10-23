package nl.rene.tools;

import java.io.File;

/**
 * De AI-motor als kindproces: nette fouten als motor of model ontbreekt.
 * De motor zelf (het protocol) test tools/llm/test.sh; org.json is op de computer maar een stub. Hier alleen de
 * foutgevallen bij het starten, als LLM_BIN (tools/llm-out/llm-x86_64) en LLM_CHAT (een .gguf) gezet zijn.
 */
public class LlmProcessTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    public static void main(String[] a) throws Exception {
        String bin = System.getenv("LLM_BIN"), chat = System.getenv("LLM_CHAT");
        if (bin == null || chat == null || !new File(bin).isFile()) { System.out.println("overgeslagen (geen LLM_BIN/LLM_CHAT)"); System.exit(0); }
        File home = new File(System.getProperty("java.io.tmpdir"));
        // Bestaat het model niet: nette fout
        try { LlmProcess.start(new File(bin), home, new File("/bestaat/niet.gguf"), 512, 2, false, false); check(false, "geen model: fout"); }
        catch (Exception e) { check(e.getMessage().contains("model"), "geen model: nette fout"); }
        try { LlmProcess.start(new File("/bestaat/niet"), home, new File(chat), 512, 2, false, false); check(false, "geen motor: fout"); }
        catch (Exception e) { check(e.getMessage().contains("ontbreekt"), "geen motor: nette fout"); }
        System.out.println("alles goed (alleen de foutgevallen; de motor zelf: zie tools/llm/test.sh)");
        System.exit(fails > 0 ? 1 : 0);
    }
}
