package nl.rene.tools;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/** Gebruik: app-sessies, blokken per ontgrendeling (pincode/anders/mislukt), schermtijd per uur en per app, bewaartermijn. */
public class GebruikTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    static final TimeZone AMS = TimeZone.getTimeZone("Europe/Amsterdam");
    static long at(int y, int mo, int d, int h, int mi) { Calendar k = Calendar.getInstance(AMS); k.clear(); k.set(y, mo - 1, d, h, mi); return k.getTimeInMillis(); }
    static long min(int m) { return m * 60_000L; }

    public static void main(String[] a) {
        // ---------- sessies ----------
        List<Gebruik.Ev> evs = new ArrayList<>();
        long t0 = at(2026, 10, 29, 9, 0);
        evs.add(new Gebruik.Ev(Gebruik.APP_START, "nl.rene.tools", t0));
        evs.add(new Gebruik.Ev(Gebruik.APP_START, "com.whatsapp", t0 + min(5)));      // wisselt van app: eerste sessie stopt
        evs.add(new Gebruik.Ev(Gebruik.APP_STOP, "com.whatsapp", t0 + min(8)));
        evs.add(new Gebruik.Ev(Gebruik.APP_START, "com.android.chrome", t0 + min(8)));
        evs.add(new Gebruik.Ev(Gebruik.SCREEN_OFF, null, t0 + min(20)));              // scherm uit: laatste sessie stopt
        List<Gebruik.Session> sess = Gebruik.sessions(evs);
        check(sess.size() == 3, "drie sessies uit de gebeurtenissen (" + sess.size() + ")");
        check(sess.get(0).pkg.equals("nl.rene.tools") && sess.get(0).dur() == min(5), "eerste sessie 5 min tot de volgende app");
        check(sess.get(2).pkg.equals("com.android.chrome") && sess.get(2).dur() == min(12), "laatste sessie tot scherm uit (12 min)");

        // sessie zonder duur valt weg
        List<Gebruik.Ev> leeg = new ArrayList<>();
        leeg.add(new Gebruik.Ev(Gebruik.APP_START, "x", t0));
        leeg.add(new Gebruik.Ev(Gebruik.APP_STOP, "x", t0));
        check(Gebruik.sessions(leeg).isEmpty(), "sessie van 0 ms telt niet mee");

        // ---------- totalen en aantallen ----------
        List<Gebruik.Ev> twee = new ArrayList<>();
        twee.add(new Gebruik.Ev(Gebruik.APP_START, "a", t0));
        twee.add(new Gebruik.Ev(Gebruik.APP_START, "b", t0 + min(10)));
        twee.add(new Gebruik.Ev(Gebruik.APP_START, "a", t0 + min(15)));
        twee.add(new Gebruik.Ev(Gebruik.SCREEN_OFF, null, t0 + min(45)));
        List<Gebruik.Session> ts = Gebruik.sessions(twee);
        Map<String, Long> tot = Gebruik.totals(ts);
        check(tot.get("a") == min(40) && tot.get("b") == min(5), "totaal per app klopt (a 40, b 5)");
        check(tot.keySet().iterator().next().equals("a"), "hoogste eerst (a)");
        check(Gebruik.counts(ts).get("a") == 2, "a twee keer geopend");

        // ---------- per uur ----------
        List<Gebruik.Session> hs = new ArrayList<>();
        long ds = Gebruik.dayStart(t0, AMS);
        hs.add(new Gebruik.Session("a", ds + min(30), ds + min(90))); // 00:30–01:30: 30 min in uur 0, 30 min in uur 1
        long[] h = Gebruik.hourly(hs, ds);
        check(h[0] == min(30) && h[1] == min(30), "sessie over twee uren wordt verdeeld");

        // ---------- ontgrendelingen: pincode, anders, mislukt ----------
        List<Gebruik.Ev> u = new ArrayList<>();
        List<Gebruik.Pw> pw = new ArrayList<>();
        long m0 = at(2026, 10, 29, 8, 0);
        // 1) met pincode: gelukte poging, dan ontgrendeld, dan een app, dan scherm uit
        pw.add(new Gebruik.Pw(m0, true));
        u.add(new Gebruik.Ev(Gebruik.UNLOCKED, null, m0 + 1500));
        u.add(new Gebruik.Ev(Gebruik.APP_START, "com.whatsapp", m0 + min(1)));
        u.add(new Gebruik.Ev(Gebruik.SCREEN_OFF, null, m0 + min(3)));
        // 2) twee keer mis, dan gelukt, dan ontgrendeld
        long m1 = at(2026, 10, 29, 12, 0);
        pw.add(new Gebruik.Pw(m1, false));
        pw.add(new Gebruik.Pw(m1 + 5000, false));
        pw.add(new Gebruik.Pw(m1 + 10000, true));
        u.add(new Gebruik.Ev(Gebruik.UNLOCKED, null, m1 + 11000));
        u.add(new Gebruik.Ev(Gebruik.APP_START, "com.android.chrome", m1 + min(1)));
        u.add(new Gebruik.Ev(Gebruik.SCREEN_OFF, null, m1 + min(2)));
        // 3) ontgrendeld zonder gelukte poging ervoor: "anders" (vingerafdruk)
        long m2 = at(2026, 10, 29, 15, 0);
        u.add(new Gebruik.Ev(Gebruik.UNLOCKED, null, m2));
        u.add(new Gebruik.Ev(Gebruik.SCREEN_OFF, null, m2 + min(1)));
        // 4) drie mislukte pogingen, geen ontgrendeling erna
        long m3 = at(2026, 10, 29, 23, 0);
        pw.add(new Gebruik.Pw(m3, false));
        pw.add(new Gebruik.Pw(m3 + 3000, false));
        pw.add(new Gebruik.Pw(m3 + 6000, false));

        List<Gebruik.Unlock> blk = Gebruik.unlocks(u, pw, 30_000);
        check(blk.size() == 4, "vier blokken (" + blk.size() + ")");
        check(blk.get(0).how.equals("pincode") && blk.get(0).fails == 0, "1: met pincode, geen mislukte pogingen");
        check(blk.get(0).apps.size() == 1 && blk.get(0).apps.get(0).pkg.equals("com.whatsapp"), "1: WhatsApp erna");
        check(blk.get(1).how.equals("pincode") && blk.get(1).fails == 2, "2: met pincode na 2 mislukte pogingen");
        check(blk.get(2).how.equals("anders") && blk.get(2).fails == 0, "3: anders (geen pincode ervoor)");
        check(blk.get(3).how.equals("mislukt") && blk.get(3).fails == 3 && blk.get(3).apps.isEmpty(), "4: los blok met 3 mislukte pogingen");

        // pincode-venster: gelukte poging te lang geleden telt niet als pincode
        List<Gebruik.Ev> u2 = new ArrayList<>();
        List<Gebruik.Pw> pw2 = new ArrayList<>();
        pw2.add(new Gebruik.Pw(m0, true));
        u2.add(new Gebruik.Ev(Gebruik.UNLOCKED, null, m0 + 120_000)); // 2 min later
        check(Gebruik.unlocks(u2, pw2, 30_000).get(0).how.equals("anders"), "gelukte poging te lang geleden: anders");

        // ---------- bewaartermijn ----------
        long now = at(2026, 10, 29, 10, 0);
        check(Gebruik.olderThan(at(2026, 9, 1, 10, 0), now, 30, AMS), "ruim een maand oud is ouder dan 30 dagen");
        check(!Gebruik.olderThan(at(2026, 10, 20, 10, 0), now, 30, AMS), "9 dagen oud blijft binnen 30 dagen");
        check(!Gebruik.olderThan(at(2026, 10, 29, 0, 1), now, 1, AMS), "vandaag blijft binnen 1 dag");
        check(!Gebruik.olderThan(at(2020, 1, 1, 0, 0), now, 0, AMS), "bewaartermijn 0 = niets opruimen");
        check(Gebruik.dateKey(at(2026, 10, 29, 23, 59), AMS).equals("2026-10-29"), "datumsleutel lokaal");

        System.out.println(fails == 0 ? "ALLES GOED" : (fails + " MIS"));
        if (fails > 0) System.exit(1);
    }
}
