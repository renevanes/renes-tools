package nl.rene.tools;

import java.nio.charset.StandardCharsets;
import org.json.JSONArray;

/** Het native register volgt het tooloverzicht; een los bankvenster moet zelf ontgrendeld zijn. */
public class SoloTest {
    static void check(boolean yes, String text) { if (!yes) throw new AssertionError(text); }
    public static void main(String[] args) throws Exception {
        Process p = new ProcessBuilder("node", "tools/tool-register.js", "--json").redirectErrorStream(true).start();
        String raw = new String(Facturen.lees(p.getInputStream(), 100_000), StandardCharsets.UTF_8);
        check(p.waitFor() == 0, "toolregister lezen");
        JSONArray ids = new JSONArray(raw);
        check(Solo.TOOLS.size() == ids.length(), "native en interface ondersteunen dezelfde tools");
        for (int i = 0; i < ids.length(); i++) check(Solo.known(ids.getString(i)), "tool kan los draaien: " + ids.getString(i));
        check(Solo.known("wc") && Solo.known("bank") && Solo.known("masterdata") && Solo.known("ereader") && Solo.known("backup") && Solo.known("auto") && Solo.known("player"), "ontbrekende tools zijn toegevoegd");
        check(!Solo.known(null) && !Solo.known("../home") && !Solo.known("settings"), "alleen echte tools mogen los starten");
        Bank.close();
        check(!Bank.session(-1), "nieuw venster erft geen banksessie");
        long first = Bank.open();
        check(Bank.session(first), "eigen ontgrendeling werkt");
        long second = Bank.open();
        check(!Bank.session(first) && Bank.session(second), "ander venster of nieuwe ontgrendeling maakt de vorige vrijgave ongeldig");
        Bank.close();
        check(!Bank.session(second), "sluiten trekt een vrijgave in");
        Bank.open();
        check(!Bank.session(second), "een latere ontgrendeling maakt een oude vrijgave niet opnieuw geldig");
        Bank.close();
        System.out.println("Register en venstergebonden bankontgrendeling geslaagd");
    }
}
