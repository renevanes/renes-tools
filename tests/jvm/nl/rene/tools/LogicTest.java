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

    static byte[] open(byte[] sealed, String pw) throws Exception {
        java.io.InputStream in = new java.io.ByteArrayInputStream(sealed);
        byte[] h = Vault.readHeader(in);
        java.io.InputStream d = new Vault.In(in, h, Vault.keyFor(h, pw.toCharArray()));
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1000];
        int n;
        while ((n = d.read(buf)) > 0) o.write(buf, 0, n);
        return o.toByteArray();
    }

    static String err(byte[] sealed, String pw) {
        try { open(sealed, pw); return null; } catch (Exception e) { return e.getMessage(); }
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

        // Versleutelde backup: heen en terug, fout wachtwoord, afgekapt, aangepast
        Vault.Key k = Vault.derive("geheim-wachtwoord".toCharArray(), new byte[16], 1000, 1);
        byte[] data = new byte[Vault.CHUNK * 2 + 123];
        new java.util.Random(7).nextBytes(data);
        byte[] sealed = Vault.seal(data, k);
        check("vault: begint met RTB1", new String(sealed, 0, 4, "US-ASCII").equals("RTB1"));
        check("vault: heen en terug gelijk", Arrays.equals(data, open(sealed, "geheim-wachtwoord")));
        check("vault: precies 2 volle blokken", Arrays.equals(Arrays.copyOf(data, Vault.CHUNK * 2), open(Vault.seal(Arrays.copyOf(data, Vault.CHUNK * 2), k), "geheim-wachtwoord")));
        check("vault: leeg archief", open(Vault.seal(new byte[0], k), "geheim-wachtwoord").length == 0);
        check("vault: verkeerd wachtwoord gemeld", "Verkeerd wachtwoord".equals(err(sealed, "fout")));
        check("vault: afgekapt gemeld", String.valueOf(err(Arrays.copyOf(sealed, sealed.length - 40), "geheim-wachtwoord")).contains("onvolledig"));
        byte[] cut = Arrays.copyOf(sealed, Vault.HEADER + 2 * (4 + Vault.CHUNK + Vault.TAG)); // slotblok weggelaten
        check("vault: zonder slotblok gemeld", String.valueOf(err(cut, "geheim-wachtwoord")).contains("onvolledig"));
        byte[] bad = sealed.clone(); bad[Vault.HEADER + 4 + Vault.CHUNK + 50] ^= 1;
        check("vault: aangepast gemeld", "Het archief is beschadigd".equals(err(bad, "geheim-wachtwoord")));
        byte[] hdr = Arrays.copyOf(sealed, Vault.HEADER);
        check("vault: eigen sleutel herkend", Vault.matches(k, hdr) && !Vault.matches(Vault.derive("x".toCharArray(), new byte[]{1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16}, 1000, 1), hdr));
        java.nio.file.Files.write(java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "rt-test.rtb"), Vault.seal("hallo wereld".getBytes("UTF-8"), k));

        // Opruimen: 14 dagen + nieuwste per maand
        c.set(2026, java.util.Calendar.OCTOBER, 4, 12, 0, 0);
        long now = c.getTimeInMillis();
        List<String> names = Arrays.asList("sms-2026-10-04.xml", "sms-2026-09-25.xml", "sms-2026-09-15.xml", "sms-2026-09-10.xml",
                "sms-2026-08-01.xml", "sms-2026-08-31.xml", "sms-2026-07-02.xml", "oproepen-2026-08-01.csv", "oproepen-2026-08-02-selectie-120000.csv",
                "contacten-versie-3.vcf", "backup-2026-08-03_0330.rtb", "backup-2026-08-03_1200.rtb", "notities.json", "backup-2026-01-01_0330.rtb");
        java.util.Set<String> del = new java.util.HashSet<>(Rotate.toDelete(names, now));
        check("opruimen: binnen 14 dagen blijft", !del.contains("sms-2026-09-25.xml") && !del.contains("sms-2026-10-04.xml"));
        check("opruimen: oudere in een maand met een recente weg", del.contains("sms-2026-09-10.xml") && del.contains("sms-2026-09-15.xml"));
        check("opruimen: per maand de nieuwste", del.contains("sms-2026-08-01.xml") && !del.contains("sms-2026-08-31.xml") && !del.contains("sms-2026-07-02.xml"));
        check("opruimen: tijd telt binnen een dag", del.contains("backup-2026-08-03_0330.rtb") && !del.contains("backup-2026-08-03_1200.rtb"));
        check("opruimen: selecties, versies en losse bestanden blijven", !del.contains("oproepen-2026-08-02-selectie-120000.csv") && !del.contains("contacten-versie-3.vcf") && !del.contains("notities.json"));
        check("opruimen: enige van een soort blijft", !del.contains("oproepen-2026-08-01.csv"));
        check("opruimen: precies 4 weg", del.size() == 4);
        java.util.Set<String> del2 = new java.util.HashSet<>(Rotate.toDelete(Arrays.asList("meldingen-2026-08-01_031500.json", "meldingen-2026-08-20_031500.json",
                "meldingen-2026-08-20_031500.html", "backup-2026-10-04_120000.rtb", "backup-2026-10-04_1200.rtb"), now));
        check("opruimen: meldingen-exports doen mee", del2.contains("meldingen-2026-08-01_031500.json") && !del2.contains("meldingen-2026-08-20_031500.json") && !del2.contains("meldingen-2026-08-20_031500.html"));
        check("opruimen: naam met seconden herkend", Rotate.NAME.matcher("backup-2026-10-04_120000.rtb").matches() && !del2.contains("backup-2026-10-04_120000.rtb"));
        // Routes: geïmporteerde route zonder tijden niet wegfilteren; echte gps-route wel filteren
        List<Tracks.Pt> plan = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) plan.add(new Tracks.Pt(52.0 + i * 0.001, 4.4, Double.NaN, 946_684_800_000L + i * 1000L, 5f, -1f));
        check("routes: geplande route (zonder tijden) blijft heel", Tracks.synthetic(plan) && Tracks.filter(plan).size() == 20);
        List<Tracks.Pt> gps = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) gps.add(new Tracks.Pt(52.0 + i * 0.0001, 4.4, Double.NaN, 1_700_000_000_000L + i * 2000L, 8f, 2f));
        check("routes: gps-route wordt gefilterd", !Tracks.synthetic(gps) && Tracks.filter(gps).size() < 20);
        check("whatsapp: versienaam", WaBackup.isVersion("Factuur~20261009.pdf") && WaBackup.isVersion("Factuur~20261009-2.pdf") && !WaBackup.isVersion("Factuur.pdf") && !WaBackup.isVersion("IMG-20261009-WA0001.jpg"));
        check("whatsapp: versienaam maken", WaBackup.versionName("Factuur.pdf", null).matches("Factuur~\\d{8}\\.pdf"));

        System.out.println(failed == 0 ? "Alle logica-tests geslaagd" : failed + " test(s) mislukt");
        System.exit(failed == 0 ? 0 : 1);
    }
}
