package nl.rene.tools;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.Collections;

/**
 * Maakt stil één foto met de voorcamera bij een (mislukte) ontgrendeling, als Rene dat heeft aangezet. Zonder zichtbaar
 * venster: een doorzichtige activity die ook op het vergrendelscherm mag tonen (setShowWhenLocked) gebruikt Camera2 met
 * een ImageReader (geen voorbeeld). De foto gaat ALLEEN naar de eigen, afgeschermde opslag van de app
 * (getFilesDir()/gebruik/foto) — nooit naar de galerij of MediaStore — en is alleen in de app te zien.
 *
 * Eerlijk over de grenzen van Android: de camera op de achtergrond is sinds Android 9 beperkt, en een camera-dienst
 * vanaf de achtergrond vanaf Android 14. Deze aanpak (korte activity met overlay-recht) werkt op veel telefoons, maar
 * niet overal; de interface toont of het op deze telefoon lukt en heeft een "Proefopname".
 */
public class GebruikShot extends Activity {

    private HandlerThread thread;
    private Handler bg;
    private CameraDevice camera;
    private ImageReader reader;
    private String reason = "ontgrendeld";
    private boolean saved;

    /** Start een opname (vanaf de achtergrond, bijv. uit GebruikAdmin). */
    static void capture(Context c, String reason) {
        try {
            Intent i = new Intent(c, GebruikShot.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            i.putExtra("reason", reason);
            c.startActivity(i);
        } catch (Throwable e) { GebruikStore.prefs(c).edit().putString("lastShot", "mislukt: " + msg(e)).apply(); }
    }

    static String msg(Throwable e) { return e == null || e.getMessage() == null ? "onbekende fout" : e.getMessage(); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        reason = getIntent() != null && getIntent().getStringExtra("reason") != null ? getIntent().getStringExtra("reason") : "ontgrendeld";
        // Op het vergrendelscherm mogen tonen, maar het scherm niet aanzetten (onopvallend)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); }
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.width = 1; lp.height = 1; lp.alpha = 0f;
        getWindow().setAttributes(lp);
        start();
    }

    private void start() {
        try {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { fail("geen cameratoestemming"); return; }
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String front = frontCamera(cm);
            if (front == null) { fail("geen voorcamera gevonden"); return; }
            thread = new HandlerThread("gebruik-shot"); thread.start(); bg = new Handler(thread.getLooper());
            reader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 1);
            reader.setOnImageAvailableListener(ir -> onImage(ir), bg);
            cm.openCamera(front, new Open(this), bg);
        } catch (Throwable e) { fail(msg(e)); }
    }

    static String frontCamera(CameraManager cm) throws Exception {
        if (cm == null) return null;
        for (String id : cm.getCameraIdList()) {
            Integer f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (f != null && f == CameraCharacteristics.LENS_FACING_FRONT) return id;
        }
        return null;
    }

    private void onImage(ImageReader ir) {
        try (Image img = ir.acquireLatestImage()) {
            if (img == null) { fail("geen beeld"); return; }
            ByteBuffer buf = img.getPlanes()[0].getBuffer();
            byte[] data = new byte[buf.remaining()];
            buf.get(data);
            File dir = GebruikStore.photoDir(getApplicationContext());
            String rn = reason.equals("mislukt") ? "mislukt" : reason.equals("proef") ? "proef" : "ontgrendeld";
            File out = new File(dir, "shot-" + System.currentTimeMillis() + "-" + rn + ".jpg");
            // Versleuteld op de telefoon met de kluissleutel (zoals de kluis en de PDF-handtekening); alleen in de app te zien.
            Kluis.writeEncrypted(out, data);
            saved = true;
            GebruikStore.prefs(getApplicationContext()).edit().putString("lastShot", "ok|" + System.currentTimeMillis()).apply();
            GebruikStore.prune(getApplicationContext());
        } catch (Throwable e) { fail(msg(e)); }
        finally { done(); }
    }

    void onOpened(CameraDevice dev) {
        camera = dev;
        try {
            CaptureRequest.Builder req = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            req.addTarget(reader.getSurface());
            req.set(CaptureRequest.CONTROL_MODE, CameraMetadataAuto());
            dev.createCaptureSession(Collections.singletonList(reader.getSurface()), new Session(this, req), bg);
        } catch (Throwable e) { fail(msg(e)); }
    }

    private int CameraMetadataAuto() { return android.hardware.camera2.CameraMetadata.CONTROL_MODE_AUTO; }

    private void fail(String why) {
        try { GebruikStore.prefs(getApplicationContext()).edit().putString("lastShot", "mislukt: " + why).apply(); } catch (Throwable ignored) { }
        done();
    }

    private boolean closed;
    private synchronized void done() {
        if (closed) return; closed = true;
        try { if (camera != null) camera.close(); } catch (Throwable ignored) { }
        try { if (reader != null) reader.close(); } catch (Throwable ignored) { }
        try { if (thread != null) thread.quitSafely(); } catch (Throwable ignored) { }
        runOnUiThread(() -> { try { finish(); } catch (Throwable ignored) { } });
    }

    @Override protected void onDestroy() { super.onDestroy(); }

    /** Of de foto-functie op deze telefoon kan werken, met uitleg. */
    static JSONObject caps(Context c) {
        JSONObject o = new JSONObject();
        try {
            boolean cam = c.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
            boolean overlay = Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(c);
            boolean front = false;
            try { front = frontCamera((CameraManager) c.getSystemService(Context.CAMERA_SERVICE)) != null; } catch (Throwable ignored) { }
            o.put("camera", cam).put("overlay", overlay).put("front", front);
            String why = "";
            if (!front) why = "Geen voorcamera gevonden.";
            else if (!cam) why = "Geef de app cameratoestemming.";
            else if (!overlay) why = "Zet \"Tonen boven andere apps\" aan, zodat de opname ook vanaf het vergrendelscherm mag starten.";
            else if (Build.VERSION.SDK_INT >= 34) why = "Werkt op veel telefoons; op Android 14+ beperkt Android de camera vanaf de achtergrond soms. Test met Proefopname.";
            o.put("works", front && cam && overlay).put("why", why);
        } catch (Exception ignored) { }
        return o;
    }

    /** Camera openen: eigen klasse (geen anonieme inner class, D8). */
    static final class Open extends CameraDevice.StateCallback {
        private final GebruikShot a;
        Open(GebruikShot a) { this.a = a; }
        @Override public void onOpened(CameraDevice dev) { a.onOpened(dev); }
        @Override public void onDisconnected(CameraDevice dev) { a.fail("camera losgekoppeld"); }
        @Override public void onError(CameraDevice dev, int error) { a.fail("camera-fout " + error); }
    }

    /** Opnamesessie: eigen klasse. */
    static final class Session extends CameraCaptureSession.StateCallback {
        private final GebruikShot a; private final CaptureRequest.Builder req;
        Session(GebruikShot a, CaptureRequest.Builder req) { this.a = a; this.req = req; }
        @Override public void onConfigured(CameraCaptureSession s) {
            try { s.capture(req.build(), null, a.bg); } catch (Throwable e) { a.fail(msg(e)); }
        }
        @Override public void onConfigureFailed(CameraCaptureSession s) { a.fail("opnamesessie lukt niet"); }
    }
}
