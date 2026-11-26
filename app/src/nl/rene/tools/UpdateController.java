package nl.rene.tools;

import android.content.Context;
import org.json.JSONObject;

/** Achtergrondtaken en terugmeldingen voor updates; de activity beheert alleen het zichtbare venster. */
final class UpdateController {
    private UpdateController() { }

    static void check(MainActivity activity, boolean manual) {
        Context app = activity.getApplicationContext();
        new Thread(() -> {
            JSONObject result;
            try { result = Updater.check(app, MainActivity.progressTo("web")); }
            catch (Throwable failure) { result = error("Controleren op updates mislukt"); }
            final JSONObject reply = result;
            try { reply.put("kind", "web").put("manual", manual); } catch (Exception ignored) { }
            activity.h.post(() -> {
                MainActivity current = MainActivity.live != null ? MainActivity.live : activity;
                current.js("onUpdateStatus", reply.toString());
            });
            // Een nieuwe app die nog niet klaarstaat: vanzelf downloaden (als dat aan staat en het netwerk het toelaat)
            try {
                if ("apk".equals(reply.optString("state")) && !reply.optBoolean("klaar") && Updater.magVanzelf(app)) vanzelf(app, reply.optString("versionName"));
            } catch (Throwable t) { App.log(app, "UPDATE", "vanzelf downloaden mislukt (" + t.getClass().getSimpleName() + ")"); }
        }, "update-controle").start();
    }

    /** Vanzelf downloaden met de voortgang in de balk; daarna staat hij klaar (Installeren) of weer gewoon beschikbaar. */
    static void vanzelf(Context app, String versie) {
        new Thread(Doorwerken.door("update-vanzelf", () -> vanzelfHier(app, versie)), "update-vanzelf").start();
    }

    /**
     * Op deze draad vanzelf downloaden; staat de app open, dan ziet die de voortgang en de uitkomst (ook vanuit de
     * dagelijkse taak). Loopt er al een download, dan niets melden: die meldt zelf.
     */
    static void vanzelfHier(Context app, String versie) {
        String reden = Updater.vanzelfNu(app, MainActivity.progressTo("apk"));
        if ("bezig".equals(reden)) return;
        JSONObject k = Updater.klaar(app);
        if (k == null && reden == null) return; // niets gedaan (uit, geen netwerk, pauze of al bij)
        try {
            JSONObject r = new JSONObject().put("state", "apk").put("kind", "apk").put("vanzelf", true)
                    .put("versionName", k != null ? k.optString("versionName") : versie);
            if (k != null) r.put("klaar", true);
            if (reden != null) r.put("reden", reden);
            MainActivity.updateStatus(r.toString());
        } catch (Throwable ignored) { }
    }

    static void install(Context context) {
        new Thread(Doorwerken.door("update-installatie", () -> {
            String problem;
            try { problem = Updater.downloadAndInstall(context, MainActivity.progressTo("apk")); }
            catch (OutOfMemoryError failure) { problem = "Te weinig geheugen voor de update"; }
            catch (Throwable failure) { problem = "Update mislukt: " + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()); }
            try {
                if ("busy".equals(problem)) MainActivity.updateStatus("{\"state\":\"busy\",\"kind\":\"apk\"}");
                else if ("uptodate".equals(problem)) MainActivity.updateStatus("{\"state\":\"uptodate\",\"kind\":\"apk\",\"manual\":true}");
                else if ("needs-permission".equals(problem)) {
                    MainActivity current = MainActivity.live;
                    if (current != null) current.h.post(current::openInstallSettings);
                    MainActivity.updateStatus("{\"state\":\"needs-permission\",\"kind\":\"apk\"}");
                } else if (problem != null) {
                    JSONObject e = error(problem).put("kind", "apk");
                    JSONObject klaar = Updater.klaar(context); // de bewaarde download staat er nog: alleen nog installeren
                    if (klaar != null) e.put("klaar", klaar.optString("versionName")).put("bewaard", true);
                    MainActivity.updateStatus(e.toString());
                }
            } catch (Throwable ignored) { }
        }), "update-installatie").start();
    }

    private static JSONObject error(String message) {
        JSONObject result = new JSONObject();
        try { result.put("state", "error").put("error", message); } catch (Exception ignored) { }
        return result;
    }
}
