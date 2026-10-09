package nl.rene.tools;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/**
 * Snelkoppelingen: een tool (of een radiozender) als eigen icoon op het startscherm, en
 * snelle keuzes als je het app-icoon lang ingedrukt houdt. Het icoon wordt getekend met de
 * kleur en het symbool van de tegel in de app.
 */
final class Shortcuts {

    private Shortcuts() { }

    static Intent intent(Context c, String tool, String play) {
        Intent i = new Intent(c, MainActivity.class).setAction(Intent.ACTION_VIEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("open", tool).putExtra("tok", App.token(c));
        if (play != null) i.putExtra("play", play).putExtra("tok", App.token(c));
        return i;
    }

    /** Tekent een rond/vierkant icoon: gekleurde achtergrond met een wit of kleurig symbool. */
    static Icon icon(String color, String glyph) {
        int size = Build.VERSION.SDK_INT >= 26 ? 432 : 192; // adaptief: 108dp met veilige zone van 72dp
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        int bg;
        try { bg = Color.parseColor(color); } catch (Exception e) { bg = Color.parseColor("#1E5AA8"); }
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(bg);
        if (Build.VERSION.SDK_INT >= 26) cv.drawRect(0, 0, size, size, p);
        else cv.drawCircle(size / 2f, size / 2f, size / 2f, p);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(Color.WHITE);
        t.setTextAlign(Paint.Align.CENTER);
        String g = glyph == null || glyph.isEmpty() ? "★" : glyph;
        // Binnen de veilige zone blijven (adaptief: middelste 2/3).
        float target = Build.VERSION.SDK_INT >= 26 ? size * 0.36f : size * 0.5f;
        t.setTextSize(target);
        Rect r = new Rect();
        t.getTextBounds(g, 0, g.length(), r);
        if (r.width() > target * 1.2f) t.setTextSize(target * target * 1.2f / r.width());
        t.getTextBounds(g, 0, g.length(), r);
        cv.drawText(g, size / 2f, size / 2f - r.exactCenterY(), t);
        return Build.VERSION.SDK_INT >= 26 ? Icon.createWithAdaptiveBitmap(b) : Icon.createWithBitmap(b);
    }

    static ShortcutInfo info(Context c, String id, String label, String tool, String play, String color, String glyph) {
        String shortLabel = label.length() > 20 ? label.substring(0, 20) : label;
        return new ShortcutInfo.Builder(c, id).setShortLabel(shortLabel).setLongLabel(label)
                .setIcon(icon(color, glyph)).setIntent(intent(c, tool, play)).build();
    }

    /** Vraagt het startscherm om een snelkoppeling. Geeft "" bij succes, anders een melding. */
    static String pin(Context c, String id, String label, String tool, String play, String color, String glyph) {
        if (Build.VERSION.SDK_INT < 26) return "Snelkoppelingen vastzetten kan vanaf Android 8";
        ShortcutManager sm = c.getSystemService(ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) return "Je startscherm ondersteunt geen snelkoppelingen";
        try {
            sm.requestPinShortcut(info(c, id, label, tool, play, color, glyph), null);
            return "";
        } catch (Exception e) {
            return "Snelkoppeling maken lukt niet";
        }
    }

    /**
     * Snelkoppelingen die al op het startscherm staan (van vóór versie 1.51) krijgen eenmalig het installatiegeheim,
     * zodat zenderknoppen blijven afspelen. Icoon en naam blijven zoals ze zijn.
     */
    static void refreshPinned(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        android.content.SharedPreferences p = c.getSharedPreferences("ui", Context.MODE_PRIVATE);
        if (p.getBoolean("pinTok", false)) return;
        try {
            ShortcutManager sm = c.getSystemService(ShortcutManager.class);
            if (sm == null) return;
            List<ShortcutInfo> up = new ArrayList<>();
            for (ShortcutInfo s : sm.getPinnedShortcuts()) {
                Intent old = s.getIntent();
                if (old == null || old.getStringExtra("play") == null) continue;
                ShortcutInfo.Builder b = new ShortcutInfo.Builder(c, s.getId()).setIntent(intent(c, old.getStringExtra("open"), old.getStringExtra("play")));
                if (s.getShortLabel() != null) b.setShortLabel(s.getShortLabel());
                up.add(b.build());
            }
            if (!up.isEmpty()) sm.updateShortcuts(up);
            p.edit().putBoolean("pinTok", true).apply();
        } catch (Exception ignored) { }
    }

    /** Snelle keuzes bij lang indrukken van het app-icoon (max. 4). */
    static void dynamic(Context c, String[][] items) {
        if (Build.VERSION.SDK_INT < 25) return;
        try {
            ShortcutManager sm = c.getSystemService(ShortcutManager.class);
            if (sm == null) return;
            List<ShortcutInfo> l = new ArrayList<>();
            int max = Math.min(4, sm.getMaxShortcutCountPerActivity());
            for (String[] it : items) {
                if (l.size() >= max) break;
                l.add(info(c, "dyn-" + it[0], it[1], it[0], null, it[2], it[3]));
            }
            sm.setDynamicShortcuts(l);
        } catch (Exception ignored) { }
    }
}
