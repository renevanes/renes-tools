package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/** Zoeklijsten voor transcripten en slaappraat, en het facturendossier in het geheugen. */
public class ZoekSnelTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    static void weg(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) weg(k);
        f.delete();
    }

    public static void main(String[] a) throws Exception {
        String p = Sms.likePatroon("100%_ok\\x");
        check("%100\\%\\_ok\\\\x%".equals(p), "sms: like-patroon escapet % _ en \\ (" + p + ")");

        File tmp = new File(System.getProperty("java.io.tmpdir"), "zoek-snel-" + System.nanoTime());
        tmp.mkdirs();
        try {
            File dir = new File(tmp, "transcripts");
            dir.mkdirs();
            File index = new File(tmp, "transcript-zoek.json");
            Contacts.writeText(new File(dir, "t1.json"), "{\"id\":\"t1\",\"name\":\"Piet\",\"file\":\"a.m4a\",\"mtime\":1000,\"segments\":[{\"from\":10,\"text\":\"de sleutels liggen\"},{\"from\":20,\"text\":\"tot morgen\"}]}", false);
            JSONArray tekst = Transcribe.zoekTranscripten(dir, index, "sleutels");
            check(tekst.length() == 1 && tekst.getJSONObject(0).getLong("from") == 10, "transcript: treffer in de tekst");
            check(index.isFile(), "transcript: zoeklijst geschreven");
            JSONArray naam = Transcribe.zoekTranscripten(dir, index, "piet");
            check(naam.length() == 1 && naam.getJSONObject(0).getLong("from") == 10, "transcript: naam telt alleen het eerste stuk");
            JSONArray later = Transcribe.zoekTranscripten(dir, index, "morgen");
            check(later.length() == 1 && later.getJSONObject(0).getLong("from") == 20, "transcript: volgend stuk uit de zoeklijst");
            Contacts.writeText(new File(dir, "t1.json"), "{\"id\":\"t1\",\"name\":\"Piet\",\"file\":\"a.m4a\",\"mtime\":2000,\"segments\":[{\"from\":10,\"text\":\"niets\"}]}", false);
            check(Transcribe.zoekTranscripten(dir, index, "sleutels").length() == 0, "transcript: gewijzigd bestand telt meteen mee");

            File root = new File(tmp, "sleep");
            File nacht = new File(root, "1700000000000");
            nacht.mkdirs();
            File night = new File(nacht, "night.json");
            Contacts.writeText(night, "{\"id\":\"1700000000000\",\"clips\":[{\"id\":\"c1700000000001\",\"kind\":\"praten\",\"t\":1700000000001,\"text\":\"de sleutels\"},{\"id\":\"c1700000000002\",\"kind\":\"stil\",\"t\":1700000000002,\"text\":\"de sleutels stil\"}]}", false);
            File slaap = new File(tmp, "slaap-zoek.json");
            JSONArray zei = SleepTalk.zoekNachten(root, slaap, "sleutels");
            check(zei.length() == 1 && zei.getJSONObject(0).getString("clip").equals("c1700000000001"), "slaappraat: alleen praten, niet een ander geluid");
            check(SleepTalk.zoekNachten(root, slaap, "sleutels").length() == 1, "slaappraat: tweede zoekactie uit de zoeklijst");
            Contacts.writeText(night, "{\"id\":\"1700000000000\",\"clips\":[{\"id\":\"c1700000000001\",\"kind\":\"praten\",\"t\":1700000000001,\"text\":\"niets\"}]}", false);
            check(SleepTalk.zoekNachten(root, slaap, "sleutels").length() == 0, "slaappraat: gewijzigde nacht telt meteen mee");

            File dossier = new File(tmp, "dossier.json");
            Contacts.writeText(dossier, "{\"items\":{\"a\":{\"n\":1}}}", false);
            JSONObject een = FacturenDossier.leesBestand(dossier);
            JSONObject twee = FacturenDossier.leesBestand(dossier);
            check(een == twee && een.getJSONObject("a").getInt("n") == 1, "dossier: dezelfde lezing zolang het bestand gelijk blijft");
            Contacts.writeText(dossier, "{\"items\":{\"a\":{\"n\":1,\"extra\":\"tweede lezing van schijf\"}}}", false);
            JSONObject drie = FacturenDossier.leesBestand(dossier);
            check(drie != een && drie.getJSONObject("a").getString("extra").equals("tweede lezing van schijf"), "dossier: opnieuw lezen nadat het bestand wijzigt");
        } finally { weg(tmp); }

        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
    }
}
