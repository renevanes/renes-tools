package nl.rene.tools;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** De motorversies die Modellen beheren toont, horen bij wat er echt in de app zit (README van de meegeleverde bibliotheken). */
public class EngineVersionTest {
    public static void main(String[] a) throws Exception {
        String r = new String(Files.readAllBytes(new File("app/lib/arm64-v8a/README.txt").toPath()), StandardCharsets.UTF_8);
        boolean ok = true;
        String w = Transcribe.ENGINE.replace("whisper.cpp ", "whisper.cpp v");
        if (!r.contains(w + ",")) { System.out.println("✗ Transcribe.ENGINE (" + Transcribe.ENGINE + ") klopt niet met README.txt"); ok = false; }
        else System.out.println("✓ " + Transcribe.ENGINE);
        if (!r.contains(LocalAi.ENGINE + ",")) { System.out.println("✗ LocalAi.ENGINE (" + LocalAi.ENGINE + ") klopt niet met README.txt"); ok = false; }
        else System.out.println("✓ " + LocalAi.ENGINE);
        if (!ok) System.exit(1);
    }
}
