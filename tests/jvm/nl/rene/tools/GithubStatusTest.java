package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

/** Publiceren en tests: de stand van GitHub samenvatten (runs, per taak, open punten) en alleen eigen adressen doorlaten. */
public class GithubStatusTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    static JSONObject run(long id, String naam, String status, String conclusion, String tak, String titel, String start, String eind, String url) throws Exception {
        JSONObject r = new JSONObject().put("id", id).put("name", naam).put("status", status).put("head_branch", tak).put("display_title", titel)
                .put("run_started_at", start).put("updated_at", eind).put("html_url", url).put("run_number", id % 100).put("event", "push");
        r.put("conclusion", conclusion == null ? JSONObject.NULL : conclusion);
        r.put("head_repository", new JSONObject().put("full_name", GithubStatus.REPO));
        return r;
    }

    public static void main(String[] a) throws Exception {
        String W = GithubStatus.WEB;
        // ----- adressen: alleen deze repository
        check(GithubStatus.url(W + "actions/runs/123").equals(W + "actions/runs/123") && GithubStatus.url(W + "issues/32#issuecomment-1").length() > 0, "adres van de repository blijft");
        check(GithubStatus.url("https://github.com/iemand/anders/actions/runs/1").isEmpty() && GithubStatus.url("javascript:alert(1)").isEmpty() && GithubStatus.url(null).isEmpty()
                && GithubStatus.url(W + "../anders").isEmpty() && GithubStatus.url(W + "x\"><img src=x>").isEmpty() && GithubStatus.url("http://github.com/renevanes/renes-tools/x").isEmpty(), "ander of vreemd adres: leeg");
        // ----- tijd en soort
        check(GithubStatus.tijd("2026-11-24T10:05:00Z") == 1795514700000L && GithubStatus.tijd("gisteren") == 0 && GithubStatus.tijd(null) == 0, "ISO-tijd van GitHub in milliseconden");
        check("bezig".equals(GithubStatus.soort("in_progress", "")) && "bezig".equals(GithubStatus.soort("queued", "")) && "gelukt".equals(GithubStatus.soort("completed", "success"))
                && "mislukt".equals(GithubStatus.soort("completed", "failure")) && "mislukt".equals(GithubStatus.soort("completed", "timed_out")) && "gestopt".equals(GithubStatus.soort("completed", "cancelled"))
                && "overgeslagen".equals(GithubStatus.soort("completed", "skipped")) && "wacht".equals(GithubStatus.soort("completed", "action_required")), "uitkomst in één woord");
        // ----- samenvatten
        JSONArray rs = new JSONArray()
                .put(run(5, "Bouwen en testen", "in_progress", null, "claude/x", "Nieuw werk", "2026-11-24T10:00:00Z", "2026-11-24T10:01:00Z", W + "actions/runs/5"))
                .put(run(4, "Publiceren", "completed", "failure", "main", "Backups kleiner\nmet details", "2026-11-24T09:00:00Z", "2026-11-24T09:20:00Z", W + "actions/runs/4"))
                .put(run(3, "Bouwen en testen", "completed", "success", "main", "Backups kleiner", "2026-11-24T08:50:00Z", "2026-11-24T09:05:00Z", W + "actions/runs/3"))
                .put(run(2, "Telefoontest", "completed", "success", "main", "Backups kleiner", "2026-11-24T08:50:00Z", "2026-11-24T09:10:00Z", "https://elders.example/2"))
                .put(run(1, "Publiceren", "completed", "success", "main", "Versie 1.245", "2026-11-23T20:00:00Z", "2026-11-23T20:30:00Z", W + "actions/runs/1"))
                .put(new JSONObject().put("id", 9));
        JSONArray punten = new JSONArray()
                .put(new JSONObject().put("number", 32).put("title", "Facturen in de kluis").put("html_url", W + "issues/32").put("updated_at", "2026-11-23T12:00:00Z")
                        .put("labels", new JSONArray().put(new JSONObject().put("name", "openstaand"))))
                .put(new JSONObject().put("number", 50).put("title", "Een pull request").put("html_url", W + "pull/50").put("pull_request", new JSONObject()));
        // Een pull request uit een fork met een tak die main heet: geen run op main (ook niet als hij Publiceren heet)
        rs.put(run(0, "Publiceren", "completed", "failure", "main", "Overgenomen", "2026-11-24T10:30:00Z", "2026-11-24T10:31:00Z", W + "actions/runs/7").put("event", "pull_request")
                .put("head_repository", new JSONObject().put("full_name", "iemand/renes-tools")));
        // Een wekelijkse taak die niet bij de nieuwste runs zit: los opgehaald
        JSONArray extra = new JSONArray().put(run(77, "Onderhoud", "completed", "failure", "main", "Onderhoud", "2026-11-23T10:00:00Z", "2026-11-23T10:20:00Z", W + "actions/runs/77").put("event", "schedule"));
        JSONObject s = GithubStatus.samenvatten(new JSONObject().put("workflow_runs", rs), extra, punten, 1L);
        JSONArray runs = s.getJSONArray("runs");
        check(runs.length() == 6 && "bezig".equals(runs.getJSONObject(0).optString("soort")) && !runs.getJSONObject(0).optBoolean("main"), "runs nieuwste eerst; een run zonder naam telt niet; een losse run staat niet bij de recente runs");
        check(!runs.getJSONObject(5).optBoolean("main"), "pull request uit een fork (tak main): geen run op main");
        check(s.optLong("sinds") == GithubStatus.tijd("2026-11-23T20:00:00Z"), "periode van de runs: de oudste start");
        check("Backups kleiner met details".equals(runs.getJSONObject(1).optString("titel")) && runs.getJSONObject(3).optString("url").isEmpty(), "titel op één regel; adres buiten de repository wordt leeg");
        JSONArray taken = s.getJSONArray("taken");
        check(taken.length() == 4 && "Publiceren".equals(taken.getJSONObject(0).optString("naam")), "per taak, Publiceren bovenaan, ook de wekelijkse taak");
        JSONObject ond = null;
        for (int i = 0; i < taken.length(); i++) if ("Onderhoud".equals(taken.getJSONObject(i).optString("naam"))) ond = taken.getJSONObject(i);
        check(ond != null && "mislukt".equals(ond.getJSONObject("stand").optString("soort")) && ond.getJSONObject("stand").optBoolean("main"), "wekelijkse taak: zijn laatste run op main telt mee");
        JSONObject bt = null;
        for (int i = 0; i < taken.length(); i++) if ("Bouwen en testen".equals(taken.getJSONObject(i).optString("naam"))) bt = taken.getJSONObject(i);
        check(bt != null && "gelukt".equals(bt.getJSONObject("stand").optString("soort")) && bt.getJSONObject("laatste").optLong("id") == 5, "de stand van een taak is de laatste run op main; de laatste run (op een andere tak) staat erbij");
        check("mislukt".equals(taken.getJSONObject(0).getJSONObject("stand").optString("soort")) && taken.getJSONObject(0).getJSONObject("stand").optLong("id") == 4, "Publiceren: de nieuwste run telt, niet een oudere die lukte");
        JSONObject samen = s.getJSONObject("samen");
        check(samen.optInt("bezig") == 1 && samen.optInt("mislukt") == 2 && samen.optInt("gelukt") == 2, "tellingen: 1 bezig, 2 taken mislukt, 2 gelukt (" + samen + ")");
        check(s.getJSONArray("punten").length() == 1 && s.optInt("prs") == 1 && "openstaand".equals(s.getJSONArray("punten").getJSONObject(0).getJSONArray("labels").optString(0)), "open punten zonder pull requests (die apart geteld), met labels");
        check(GithubStatus.samenvatten(null, null, null, 1L).getJSONArray("runs").length() == 0 && !GithubStatus.samenvatten(null, null, null, 1L).optBoolean("puntenOk"), "zonder gegevens: leeg, niet vastlopen");
        StringBuilder lang = new StringBuilder();
        for (int i = 0; i < 500; i++) lang.append('x');
        check(GithubStatus.kort(lang.toString(), 160).length() == 160, "lange titels worden ingekort");
        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
        System.out.println("alles goed");
    }
}
