package nl.rene.tools;

import android.content.Intent;
import android.location.Address;
import android.location.Geocoder;
import android.provider.Settings;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/** Brugfuncties voor "Locatie kiezen". Zit tussen VpnBridge en MainActivity.Bridge. */
public class LocBridge extends VpnBridge {

    LocBridge(MainActivity act) { super(act); }

    @JavascriptInterface public String locState() { return MockLoc.state(ctx).toString(); }

    /** Geeft "" of "perm" / "mock" / een melding. */
    @JavascriptInterface public String locStart(double lat, double lng, String name) { return MockLoc.start(ctx, lat, lng, name); }

    @JavascriptInterface public void locStop() { MockLoc.stop(ctx); }

    @JavascriptInterface public void locJitter(boolean on) { MockLoc.prefs(ctx).edit().putBoolean("jitter", on).apply(); }

    @JavascriptInterface public String locSave(String name, double lat, double lng) { return MockLoc.savePlace(ctx, name, lat, lng); }

    @JavascriptInterface public void locDelete(int i) { MockLoc.deletePlace(ctx, i); }

    /** Ontwikkelaarsopties openen (daar kies je de app voor de locatie). */
    @JavascriptInterface public void locDevSettings() {
        a.h.post(() -> {
            try { a.startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); }
            catch (Exception e) { try { a.startActivity(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)); } catch (Exception ignored) { } }
        });
    }

    /** "Over de telefoon" (daar 7× op het buildnummer tikken om ontwikkelaarsopties aan te zetten). */
    @JavascriptInterface public void locAboutPhone() {
        a.h.post(() -> { try { a.startActivity(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)); } catch (Exception ignored) { } });
    }

    /** Adres zoeken; antwoord via onLocSearch({q, results:[{name, lat, lng}]} of {q, error}). */
    @JavascriptInterface public void locSearch(final String q) {
        if (q == null || q.trim().isEmpty()) return;
        if (q.length() > 200) { try { a.js("onLocSearch", new JSONObject().put("q", q).put("error", "Zoekopdracht is te lang").toString()); } catch (Exception ignored) { } return; }
        new Thread(() -> {
            JSONObject r = new JSONObject();
            try {
                r.put("q", q);
                if (!Geocoder.isPresent()) throw new Exception("Adressen zoeken kan niet op deze telefoon; tik de plek aan op de kaart");
                @SuppressWarnings("deprecation")
                List<Address> l = new Geocoder(ctx, new Locale("nl", "NL")).getFromLocationName(q.trim(), 6);
                JSONArray out = new JSONArray();
                if (l != null) for (Address ad : l) {
                    if (!ad.hasLatitude() || !ad.hasLongitude()) continue;
                    StringBuilder n = new StringBuilder();
                    for (int i = 0; i <= ad.getMaxAddressLineIndex() && i < 2; i++) { String al = ad.getAddressLine(i); if (al == null) continue; if (n.length() > 0) n.append(", "); n.append(al); }
                    if (n.length() == 0 && ad.getFeatureName() != null) n.append(ad.getFeatureName());
                    out.put(new JSONObject().put("name", n.toString()).put("lat", ad.getLatitude()).put("lng", ad.getLongitude()));
                }
                r.put("results", out);
            } catch (java.io.IOException e) {
                try { r.put("error", "Zoeken lukt niet (geen internet?)"); } catch (Exception ignored) { }
            } catch (Exception e) {
                try { r.put("error", e.getMessage() == null ? "Zoeken lukt niet" : e.getMessage()); } catch (Exception ignored) { }
            }
            a.js("onLocSearch", r.toString());
        }, "loc-search").start();
    }
}
