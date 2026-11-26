package nl.rene.tools;

import java.util.ArrayList;
import java.util.List;

/** SMS-backup: de gesprekkenlijst uit berichten zonder tekst (nieuwste eerst) en de ingekorte laatste tekst. */
public class SmsTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    static Sms.Msg m(long id, long thread, String adres, long date) {
        Sms.Msg x = new Sms.Msg(); x.id = id; x.threadId = thread; x.address = adres; x.date = date; return x;
    }

    public static void main(String[] args) {
        List<Sms.Msg> rijen = new ArrayList<>();
        // nieuwste eerst, zoals de vraag met "date DESC"
        rijen.add(m(9, 2, "0612345678", 900));
        rijen.add(m(8, 1, null, 800));
        rijen.add(m(7, 1, "0201234567", 700));
        rijen.add(m(5, 2, "0612345678", 500));
        rijen.add(m(3, 1, "0201234567", 300));
        rijen.add(m(1, 3, "KPN", 100));
        List<Sms.Conv> cv = Sms.Gesprekken.groepeerNieuwsteEerst(rijen);
        check(cv.size() == 3, "drie gesprekken (" + cv.size() + ")");
        check(cv.get(0).threadId == 2 && cv.get(1).threadId == 1 && cv.get(2).threadId == 3, "volgorde: nieuwste gesprek eerst");
        check(cv.get(0).count == 2 && cv.get(1).count == 3 && cv.get(2).count == 1, "aantal berichten per gesprek");
        check(cv.get(0).last == 900 && cv.get(0).lastId == 9, "tijd en id van het nieuwste bericht");
        check(cv.get(1).lastId == 8 && "0201234567".equals(cv.get(1).address), "nieuwste bericht zonder adres: adres van een ouder bericht");
        check(Sms.Gesprekken.groepeerNieuwsteEerst(new ArrayList<>()).isEmpty(), "geen berichten: geen gesprekken");

        check("".equals(Sms.Gesprekken.kort(null)) && "hoi".equals(Sms.Gesprekken.kort("hoi")), "korte tekst blijft gelijk");
        StringBuilder lang = new StringBuilder();
        for (int i = 0; i < 500; i++) lang.append('a');
        String k = Sms.Gesprekken.kort(lang.toString());
        check(k.length() == Sms.Gesprekken.LAATSTE_TEKST + 1 && k.endsWith("…"), "lange tekst ingekort tot " + Sms.Gesprekken.LAATSTE_TEKST + " tekens en …");
        StringBuilder emoji = new StringBuilder();
        for (int i = 0; i < Sms.Gesprekken.LAATSTE_TEKST - 1; i++) emoji.append('a');
        emoji.append("😀😀😀");
        String e = Sms.Gesprekken.kort(emoji.toString());
        check(!Character.isHighSurrogate(e.charAt(e.length() - 2)), "geen half teken (emoji) aan het eind");

        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
    }
}
