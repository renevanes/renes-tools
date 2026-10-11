package nl.rene.tools;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Brugfuncties voor de VPN. Zit tussen SpotifyBridge en MainActivity.Bridge. */
public class VpnBridge extends SpotifyBridge {

    static final int REQ_VPN = 70, REQ_VPN_FILE = 71, REQ_VPN_IMG = 72, REQ_CAM = 73;

    VpnBridge(MainActivity act) { super(act); }

    @JavascriptInterface public String vpnState() { return Vpn.state(ctx).toString(); }

    /** De tunnels zonder geheimen. */
    @JavascriptInterface public String vpnTunnels() {
        JSONArray a = Vpn.tunnels(ctx), out = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            try { JSONObject c = new JSONObject(o.toString()); c.remove("conf"); out.put(c); } catch (Exception ignored) { }
        }
        return out.toString();
    }

    /** De configuratietekst om te bewerken (met de privésleutel); "" als het niet lukt. */
    @JavascriptInterface public String vpnConfig(String id) {
        try { return new JSONObject().put("text", Vpn.configText(ctx, id)).toString(); }
        catch (Exception e) { try { return new JSONObject().put("error", e.getMessage()).toString(); } catch (Exception x) { return "{}"; } }
    }

    /** Controleren zonder op te slaan: {ok, summary} of {error}. */
    @JavascriptInterface public String vpnCheck(String text) {
        try { return new JSONObject().put("ok", true).put("summary", Vpn.summary(WgConfig.parse(text))).toString(); }
        catch (Exception e) { try { return new JSONObject().put("error", e.getMessage() == null ? "Klopt niet" : e.getMessage()).toString(); } catch (Exception x) { return "{}"; } }
    }

    @JavascriptInterface public String vpnSave(String id, String name, String text) {
        JSONObject r = Vpn.save(ctx, id, name, text);
        if (r.optBoolean("restart")) Vpn.start(ctx, r.optString("id"), "app", true); // draaiende tunnel met de nieuwe instellingen
        Vpn.armAuto(ctx);
        return r.toString();
    }

    @JavascriptInterface public void vpnDelete(String id) { if (id != null) Vpn.delete(ctx, id); }

    @JavascriptInterface public void vpnSetActive(String id) { if (Vpn.tunnel(ctx, id) != null) Vpn.prefs(ctx).edit().putString("active", id).apply(); }

    /** Nieuw sleutelpaar voor een nieuwe tunnel: {priv, pub}. */
    @JavascriptInterface public String vpnNewKey() {
        byte[] k = WgCrypto.newPrivateKey();
        try { return new JSONObject().put("priv", WgConfig.b64(k)).put("pub", WgConfig.b64(WgCrypto.publicKey(k))).toString(); } catch (Exception e) { return "{}"; }
    }

    /** Publieke sleutel bij een privésleutel ("" als die niet klopt). */
    @JavascriptInterface public String vpnPubOf(String priv) {
        try { return WgConfig.b64(WgCrypto.publicKey(WgConfig.key32(priv, "PrivateKey"))); } catch (Exception e) { return ""; }
    }

    /** Aanzetten. Geeft "" (bezig), "consent" (Android vraagt nu toestemming), of een melding. */
    @JavascriptInterface public String vpnStart(String id) {
        String r = Vpn.start(ctx, id, "app");
        if (r.equals(Vpn.NEED_CONSENT)) { askConsent(id); return "consent"; }
        if (r.equals(Vpn.NO_TUNNEL)) return "Stel eerst een tunnel in";
        if (r.equals(Vpn.BACKGROUND)) return "Android liet de VPN nu niet starten; probeer het nog eens";
        return r;
    }

    void askConsent(String id) {
        a.h.post(() -> {
            try {
                Intent i = android.net.VpnService.prepare(ctx);
                if (i == null) { Vpn.start(ctx, id, "app"); a.js("onVpnChanged", ""); return; }
                a.vpnPending = id == null ? "" : id;
                a.startActivityForResult(i, REQ_VPN);
            } catch (Exception e) { a.js("onVpnChanged", "\"Toestemming vragen lukt niet\""); }
        });
    }

    @JavascriptInterface public void vpnStop() { Vpn.stop(ctx, "app"); }

    /** Instellingen voor vanzelf aan/uit: {wifi, home, mobile, trusted[]} */
    @JavascriptInterface public void vpnAuto(String json) {
        try { Vpn.setAuto(ctx, new JSONObject(json)); } catch (Exception ignored) { }
    }

    /** De wifi waar je nu op zit als vertrouwd; geeft de naam of "" (onbekend: locatietoestemming nodig). */
    @JavascriptInterface public String vpnTrustCurrent() {
        String s = Vpn.currentSsid(ctx);
        if (s == null) return "";
        try {
            JSONObject au = Vpn.autoCfg(ctx);
            JSONArray t = au.optJSONArray("trusted");
            if (t == null) t = new JSONArray();
            boolean has = false;
            for (int i = 0; i < t.length(); i++) has |= s.equals(t.optString(i));
            if (!has) t.put(s);
            au.put("trusted", t);
            Vpn.setAuto(ctx, au);
        } catch (Exception ignored) { }
        return s;
    }

    /** VPN-instellingen van Android (Altijd-aan VPN en "verbindingen zonder VPN blokkeren" = kill switch). */
    @JavascriptInterface public void vpnSystemSettings() {
        a.h.post(() -> {
            try { a.startActivity(new Intent(Settings.ACTION_VPN_SETTINGS)); }
            catch (Exception e) { try { a.startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS)); } catch (Exception ignored) { } }
        });
    }

    /** Tegel "VPN" aan de snelle instellingen toevoegen (Android 13+ vraagt het zelf; anders uitleg). Geeft "" of "manual". */
    @JavascriptInterface public String vpnAddTile() {
        if (Build.VERSION.SDK_INT < 33) return "manual";
        a.h.post(() -> {
            try {
                android.app.StatusBarManager sb = ctx.getSystemService(android.app.StatusBarManager.class);
                sb.requestAddTileService(new ComponentName(ctx, VpnTile.class), "VPN",
                        android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.ic_vpn), ctx.getMainExecutor(),
                        r -> a.js("onVpnTile", String.valueOf(r)));
            } catch (Exception e) { a.js("onVpnTile", "-1"); }
        });
        return "";
    }

    /** Snelkoppeling op het startscherm die de VPN aan- of uitzet. */
    @JavascriptInterface public String vpnPinToggle() {
        return Shortcuts.pin(ctx, "vpn-toggle", "VPN aan/uit", "vpn", "{\"vpn\":\"toggle\"}", "#1E8449", "🔑");
    }

    /** Publieke sleutel e.d. naar het klembord (geen geheimen: hooguit 200 tekens). */
    @JavascriptInterface public void vpnCopy(String text) {
        if (text == null || text.length() > 200) return;
        a.h.post(() -> {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("WireGuard", text));
            } catch (Exception ignored) { }
        });
    }

    // ----- importeren -----

    @JavascriptInterface public void vpnPickFile() {
        a.h.post(() -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            try { a.startActivityForResult(i, REQ_VPN_FILE); } catch (Exception e) { a.js("onVpnImported", "{\"error\":\"Geen bestandskiezer gevonden\"}"); }
        });
    }

    @JavascriptInterface public void vpnPickImage() {
        a.h.post(() -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
            try { a.startActivityForResult(i, REQ_VPN_IMG); } catch (Exception e) { a.js("onVpnImage", "{\"error\":\"Geen fotokiezer gevonden\"}"); }
        });
    }

    /** Camera voor de QR-code: toestemming vragen; antwoord via onVpnCamera(true/false). */
    @JavascriptInterface public void vpnCamera() {
        a.h.post(() -> {
            if (ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) { a.camAllowed = true; a.js("onVpnCamera", "true"); return; }
            a.requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
        });
    }

    /** Klaar met scannen: de pagina mag de camera niet meer gebruiken. */
    @JavascriptInterface public void vpnCameraDone() { a.camAllowed = false; }

    /** Bestand gekozen (vanuit MainActivity.onActivityResult). */
    static void onFile(MainActivity a, Uri u) {
        new Thread(() -> {
            JSONObject r = new JSONObject();
            try (InputStream in = a.getContentResolver().openInputStream(u)) {
                if (in == null) throw new Exception("Bestand niet te lezen");
                byte[] b = readMax(in, 64 * 1024);
                if (b == null) throw new Exception("Dit bestand is te groot voor een WireGuard-configuratie");
                String name = "";
                try (android.database.Cursor c = a.getContentResolver().query(u, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (c != null && c.moveToFirst()) name = c.getString(0);
                } catch (Exception ignored) { }
                if (name != null && name.toLowerCase(java.util.Locale.ROOT).endsWith(".conf")) name = name.substring(0, name.length() - 5);
                r.put("text", new String(b, StandardCharsets.UTF_8)).put("name", name == null ? "" : name);
            } catch (Exception e) {
                try { r.put("error", e.getMessage() == null ? "Bestand niet te lezen" : e.getMessage()); } catch (Exception ignored) { }
            }
            a.js("onVpnImported", r.toString());
        }, "vpn-file").start();
    }

    /** Afbeelding gekozen: verkleind als PNG naar de pagina, die zoekt de QR-code (jsQR). */
    static void onImage(MainActivity a, Uri u) {
        new Thread(() -> {
            JSONObject r = new JSONObject();
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (InputStream in = a.getContentResolver().openInputStream(u)) { BitmapFactory.decodeStream(in, null, o); }
                int s = 1;
                while (Math.max(o.outWidth, o.outHeight) / s > 2400) s *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = s;
                Bitmap bm;
                try (InputStream in = a.getContentResolver().openInputStream(u)) { bm = BitmapFactory.decodeStream(in, null, o2); }
                if (bm == null) throw new Exception("Deze afbeelding is niet te openen");
                int max = Math.max(bm.getWidth(), bm.getHeight());
                if (max > 1600) { float f = 1600f / max; Bitmap sc = Bitmap.createScaledBitmap(bm, Math.round(bm.getWidth() * f), Math.round(bm.getHeight() * f), true); bm.recycle(); bm = sc; }
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                bm.compress(Bitmap.CompressFormat.PNG, 100, bo);
                bm.recycle();
                r.put("data", "data:image/png;base64," + Secure.b64(bo.toByteArray()));
            } catch (Exception e) {
                try { r.put("error", e.getMessage() == null ? "Afbeelding niet te lezen" : e.getMessage()); } catch (Exception ignored) { }
            }
            a.js("onVpnImage", r.toString());
        }, "vpn-img").start();
    }

    static byte[] readMax(InputStream in, int max) throws java.io.IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) { bo.write(buf, 0, n); if (bo.size() > max) return null; }
        return bo.toByteArray();
    }
}
