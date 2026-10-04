package nl.rene.tools;

import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;

/**
 * Tests van de pure-Java logica (draaien op een gewone JVM met android.jar alleen als compileerbron):
 * contactversies vergelijken, vCard schrijven en weer lezen, en vCards van andere telefoons.
 */
public class LogicTest {

    static int failed = 0;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "✓ " : "✗ ") + what);
        if (!ok) failed++;
    }

    static ContactsDiff.Rec rec(String k, String n, String... kv) {
        ContactsDiff.Rec x = new ContactsDiff.Rec(k, n);
        x.put("Naam", n);
        for (int i = 0; i < kv.length; i += 2) x.add(kv[i], kv[i + 1]);
        return x;
    }

    public static void main(String[] a) throws Exception {
        // ContactsDiff
        List<ContactsDiff.Rec> v1 = Arrays.asList(rec("a", "Moeder", "Telefoon", "0612 (mobiel)"), rec("b", "Jos", "E-mail", "jos@x.nl"), rec("c", "Oud", "Telefoon", "010"));
        List<ContactsDiff.Rec> v2 = Arrays.asList(rec("a", "Moeder", "Telefoon", "0612 (mobiel)", "Telefoon", "010 (thuis)"), rec("b2", "Jos", "E-mail", "jos@x.nl"), rec("d", "Nieuw", "Telefoon", "020"));
        List<ContactsDiff.Entry> d = ContactsDiff.diff(v1, v2);
        check("diff: 3 wijzigingen (gewijzigd, nieuw, verwijderd)", d.size() == 3);
        check("diff: andere sleutel met zelfde naam is geen wijziging", d.stream().noneMatch(e -> e.rec.name.equals("Jos")));
        check("diff: telefoon toegevoegd gezien", d.stream().anyMatch(e -> e.kind == '~' && e.changes.get(0).field.equals("Telefoon")));
        check("diff: geen verschil met zichzelf", ContactsDiff.diff(v2, v2).isEmpty());
        ContactsDiff.Rec x = rec("z", "Z", "Telefoon", "2", "Telefoon", "1", "Telefoon", "2");
        check("meervoudig veld uniek en gesorteerd", x.get("Telefoon").equals("1\n2"));

        // vCard: eigen export terug inlezen
        ContactsDiff.Rec r = rec("k", "Jan; de, Vries", "Telefoon", "06 12345678 (mobiel)", "Telefoon", "010 1234567 (werk)", "E-mail", "a,b@x.nl");
        r.put("Notitie", "Regel1\nRegel2 " + new String(new char[90]).replace('\0', 'x'));
        r.put("Bedrijf", "ESAPE");
        r.add("Adres", "Coolsingel 1, Rotterdam");
        StringWriter w = new StringWriter();
        Contacts.writeVcf(w, Arrays.asList(r));
        boolean folded = true;
        for (String l : w.toString().split("\r\n")) if (l.length() > 75) folded = false;
        check("vCard: regels gevouwen op 75 tekens", folded);
        ContactsDiff.Rec back = VCard.parse(w.toString()).get(0);
        check("vCard: eigen export komt identiek terug", r.content().equals(back.content()));

        // vCard 2.1 met quoted-printable
        String v21 = "BEGIN:VCARD\r\nVERSION:2.1\r\nN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:Bakker;Ren=C3=A9;;;\r\nFN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:Ren=C3=A9 Bakker\r\nTEL;CELL:+31612345678\r\nTEL;HOME;VOICE:0101234567\r\nNOTE;ENCODING=QUOTED-PRINTABLE:eerste=0D=0Atweede regel =\r\n en verder\r\nEND:VCARD\r\n";
        ContactsDiff.Rec q = VCard.parse(v21).get(0);
        check("vCard 2.1: naam met é", q.name.equals("René Bakker"));
        check("vCard 2.1: twee nummers met label", q.get("Telefoon").contains("(mobiel)") && q.get("Telefoon").contains("(thuis)"));
        check("vCard 2.1: doorlopende regel met spatie behouden", q.get("Notitie").endsWith("tweede regel  en verder"));

        // vCard 3.0 (Google): itemgroepen, vouwen, geen FN
        String g = "BEGIN:VCARD\nVERSION:3.0\nN:Jansen;Piet;;;\nitem1.TEL;TYPE=CELL:06-11 22 33 44\nEMAIL;TYPE=INTERNET:piet@\n example.nl\nORG:Gemeente Rotterdam;Afd. ICT\nBDAY:1970-05-04\nEND:VCARD\nBEGIN:VCARD\nVERSION:3.0\nTEL:0612000000\nEND:VCARD\n";
        List<ContactsDiff.Rec> gl = VCard.parse(g);
        check("vCard 3.0: naam uit N", gl.get(0).name.equals("Piet Jansen"));
        check("vCard 3.0: gevouwen e-mail", gl.get(0).get("E-mail").equals("piet@example.nl"));
        check("vCard 3.0: contact zonder naam krijgt nummer", gl.get(1).name.equals("0612000000"));

        // Radiowekker: volgende tijd
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(2026, java.util.Calendar.OCTOBER, 2, 8, 0, 0); // vrijdag 08:00
        long fri = c.getTimeInMillis();
        long n1 = RadioAlarm.next(7, 0, 31, fri); // ma-vr 07:00 → maandag
        c.setTimeInMillis(n1);
        check("wekker: ma-vr na vrijdag 08:00 → maandag", c.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.MONDAY && c.get(java.util.Calendar.HOUR_OF_DAY) == 7);
        long n2 = RadioAlarm.next(9, 30, 0, fri); // eenmalig 09:30 → dezelfde dag
        c.setTimeInMillis(n2);
        check("wekker: eenmalig later vandaag", c.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.FRIDAY && c.get(java.util.Calendar.MINUTE) == 30);

        System.out.println(failed == 0 ? "Alle logica-tests geslaagd" : failed + " test(s) mislukt");
        System.exit(failed == 0 ? 0 : 1);
    }
}
