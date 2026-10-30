package nl.rene.tools;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Surface;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Maakt stil één foto met de voorcamera bij een (mislukte) ontgrendeling, als Rene dat heeft aangezet. Zonder zichtbaar
 * en zonder focus/aanraking te stelen: een doorzichtige activity die ook op het vergrendelscherm mag tonen
 * (setShowWhenLocked) gebruikt Camera2 met een onzichtbaar voorbeeld (SurfaceTexture) om de belichting te laten
 * instellen en daarna één foto via een ImageReader. De foto gaat ALLEEN naar de eigen, afgeschermde opslag van de app
 * (getFilesDir()/gebruik/foto), versleuteld met de kluissleutel — nooit naar de galerij of MediaStore.
 *
 * Eerlijk over de grenzen van Android: de camera op de achtergrond is sinds Android 9 beperkt, en een camera-dienst
 * vanaf de achtergrond vanaf Android 14. Deze aanpak werkt op veel telefoons, maar niet overal; de interface toont of
 * het op deze telefoon lukt en heeft een "Proefopname". Let op: Android laat kort het camera-stipje zien.
 */
public class GebruikShot extends Activity {

    private HandlerThread thread;
    private Handler bg;
    private Handler ui;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private SurfaceTexture texture;
    private Surface preview;
    private String reason = "ontgrendeld";
    private boolean still;      // de echte foto is aangevraagd (eerdere voorbeeldbeelden negeren we)
    private Runnable watchdog;

    /** Start een opname (vanaf de achtergrond, bijv. uit GebruikStore.onUnlock / GebruikAdmin). */
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
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true);
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        // Onzichtbaar en geen focus/aanraking stelen (zodat het de pincode-invoer niet in de weg zit).
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.width = 1; lp.height = 1; lp.alpha = 0f;
        getWindow().setAttributes(lp);
        ui = new Handler(Looper.getMainLooper());
        start();
    }

    private void start() {
        try {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { fail("geen cameratoestemming"); return; }
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String front = frontCamera(cm);
            if (front == null) { fail("geen voorcamera gevonden"); return; }
            thread = new HandlerThread("gebruik-shot"); thread.start(); bg = new Handler(thread.getLooper());
            // Vangnet: als er binnen 5 s niets gelukt is, netjes afsluiten.
            watchdog = () -> fail("time-out");
            ui.postDelayed(watchdog, 5000);
            reader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2);
            reader.setOnImageAvailableListener(ir -> onImage(ir), bg);
            texture = new SurfaceTexture(false);
            texture.setDefaultBufferSize(640, 480);
            preview = new Surface(texture);
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

    void onOpened(CameraDevice dev) {
        camera = dev;
        try {
            CaptureRequest.Builder prev = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            prev.addTarget(preview);
            prev.set(CaptureRequest.CONTROL_MODE, android.hardware.camera2.CameraMetadata.CONTROL_MODE_AUTO);
            dev.createCaptureSession(Arrays.asList(preview, reader.getSurface()), new Session(this, prev), bg);
        } catch (Throwable e) { fail(msg(e)); }
    }

    /** Sessie klaar: eerst ~900 ms voorbeeld (belichting laten instellen), dan de echte foto. */
    void onConfigured(CameraCaptureSession s, CaptureRequest.Builder prev) {
        session = s;
        try {
            s.setRepeatingRequest(prev.build(), null, bg);
            bg.postDelayed(this::fireStill, 900);
        } catch (Throwable e) { fail(msg(e)); }
    }

    private void fireStill() {
        if (still || camera == null || session == null) return;
        still = true;
        try {
            session.stopRepeating();
            CaptureRequest.Builder req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            req.addTarget(reader.getSurface());
            req.set(CaptureRequest.CONTROL_MODE, android.hardware.camera2.CameraMetadata.CONTROL_MODE_AUTO);
            session.capture(req.build(), new Shot(this), bg);
        } catch (Throwable e) { fail(msg(e)); }
    }

    private void onImage(ImageReader ir) {
        Image img = null;
        try {
            img = ir.acquireLatestImage();
            if (!still) { if (img != null) img.close(); return; } // voorbeeldbeeld: negeren
            if (img == null) { fail("geen beeld"); return; }
            ByteBuffer buf = img.getPlanes()[0].getBuffer();
            byte[] data = new byte[buf.remaining()];
            buf.get(data);
            String rn = reason.equals("mislukt") ? "mislukt" : reason.equals("proef") ? "proef" : "ontgrendeld";
            File out = new File(GebruikStore.photoDir(getApplicationContext()), "shot-" + System.currentTimeMillis() + "-" + rn + ".jpg");
            Kluis.writeEncrypted(out, data); // versleuteld met de kluissleutel
            GebruikStore.prefs(getApplicationContext()).edit().putString("lastShot", "ok|" + System.currentTimeMillis()).apply();
            GebruikStore.prune(getApplicationContext(), false);
        } catch (Throwable e) { fail(msg(e)); }
        finally { if (img != null) try { img.close(); } catch (Throwable ignored) { } done(); }
    }

    private void fail(String why) {
        try { GebruikStore.prefs(getApplicationContext()).edit().putString("lastShot", "mislukt: " + why).apply(); } catch (Throwable ignored) { }
        done();
    }

    private boolean closed;
    private synchronized void done() {
        if (closed) return; closed = true;
        if (ui != null && watchdog != null) ui.removeCallbacks(watchdog);
        try { if (session != null) session.close(); } catch (Throwable ignored) { }
        try { if (camera != null) camera.close(); } catch (Throwable ignored) { }
        try { if (reader != null) reader.close(); } catch (Throwable ignored) { }
        try { if (preview != null) preview.release(); } catch (Throwable ignored) { }
        try { if (texture != null) texture.release(); } catch (Throwable ignored) { }
        try { if (thread != null) thread.quitSafely(); } catch (Throwable ignored) { }
        runOnUiThread(() -> { try { finish(); } catch (Throwable ignored) { } });
    }

    @Override protected void onDestroy() { done(); super.onDestroy(); }

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
            else if (!overlay) why = "Zet \"Boven andere apps tonen\" aan, zodat de opname ook vanaf het vergrendelscherm mag starten.";
            else why = "Android laat kort het camera-stipje zien" + (Build.VERSION.SDK_INT >= 34 ? "; op Android 14+ beperkt Android de camera vanaf de achtergrond soms." : ".") + " Test met Proefopname.";
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
        private final GebruikShot a; private final CaptureRequest.Builder prev;
        Session(GebruikShot a, CaptureRequest.Builder prev) { this.a = a; this.prev = prev; }
        @Override public void onConfigured(CameraCaptureSession s) { a.onConfigured(s, prev); }
        @Override public void onConfigureFailed(CameraCaptureSession s) { a.fail("opnamesessie lukt niet"); }
    }

    /** Resultaat van de foto-opname: eigen klasse. */
    static final class Shot extends CameraCaptureSession.CaptureCallback {
        private final GebruikShot a;
        Shot(GebruikShot a) { this.a = a; }
        @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, android.hardware.camera2.CaptureFailure f) { a.fail("opname mislukt"); }
        @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) { /* het beeld komt via de ImageReader */ }
    }
}
