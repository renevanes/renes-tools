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
        }, "update-controle").start();
    }

    static void install(Context context) {
        new Thread(() -> {
            String problem;
            try { problem = Updater.downloadAndInstall(context, MainActivity.progressTo("apk")); }
            catch (OutOfMemoryError failure) { problem = "Te weinig geheugen voor de update"; }
            catch (Throwable failure) { problem = "Update mislukt: " + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()); }
            try {
                if ("busy".equals(problem)) MainActivity.updateStatus("{\"state\":\"busy\",\"kind\":\"apk\"}");
                else if ("needs-permission".equals(problem)) {
                    MainActivity current = MainActivity.live;
                    if (current != null) current.h.post(current::openInstallSettings);
                    MainActivity.updateStatus("{\"state\":\"needs-permission\",\"kind\":\"apk\"}");
                } else if (problem != null) MainActivity.updateStatus(error(problem).put("kind", "apk").toString());
            } catch (Throwable ignored) { }
        }, "update-installatie").start();
    }

    private static JSONObject error(String message) {
        JSONObject result = new JSONObject();
        try { result.put("state", "error").put("error", message); } catch (Exception ignored) { }
        return result;
    }
}
