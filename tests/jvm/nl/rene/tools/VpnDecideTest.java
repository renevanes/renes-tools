package nl.rene.tools;

/** VPN vanzelf aan/uit bij een nieuw netwerk. */
public class VpnDecideTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    public static void main(String[] a) {
        // decide(wifi, cell, trusted, autoWifi, autoHome, autoMobile, running, autoStarted)
        check(Vpn.decide(true, false, false, true, true, false, false, false).equals("on"), "onbekende wifi, instelling aan: aan");
        check(Vpn.decide(true, false, false, false, true, false, false, false).equals(""), "onbekende wifi, instelling uit: niets");
        check(Vpn.decide(true, false, false, true, true, false, true, false).equals(""), "draait al: niets");
        check(Vpn.decide(true, false, true, true, true, false, true, true).equals("off"), "thuis-wifi en vanzelf aangegaan: uit");
        check(Vpn.decide(true, false, true, true, true, false, true, false).equals(""), "thuis-wifi maar zelf aangezet: blijft aan");
        check(Vpn.decide(true, false, true, true, false, false, true, true).equals(""), "thuis-wifi, 'weer uit' staat uit: blijft aan");
        check(Vpn.decide(false, true, false, true, true, true, false, false).equals("on"), "mobiel, instelling aan: aan");
        check(Vpn.decide(false, true, false, true, true, false, false, false).equals(""), "mobiel, instelling uit: niets");
        check(Vpn.decide(false, false, false, true, true, true, false, false).equals(""), "ander netwerk: niets");
        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
        System.out.println("Alle VPN-beslistests geslaagd");
    }
}
