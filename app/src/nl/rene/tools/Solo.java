package nl.rene.tools;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Losse apps: een tool als eigen app op het startscherm, met een eigen plek in "recente apps", zonder app-slot en
 * zonder verwijzingen naar de andere tools. Welke tools dat mogen, kies je in Instellingen (staat standaard uit).
 */
final class Solo {

    private Solo() { }

    /** Tools die als losse app kunnen (geen telefoon-skin of speler: die horen bij de hele app). */
    static final Set<String> TOOLS = Collections.unmodifiableSet(new LinkedHashSet<>(java.util.Arrays.asList(
            "redial", "wa", "tracks", "sms", "history", "calls", "notes", "contacts", "pdf", "kluis", "car", "recorder",
            "transcripts", "radio", "podcasts", "spotify", "vpn", "loc", "music", "backup", "auto")));

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("solo", Context.MODE_PRIVATE); }

    static boolean known(String tool) { return tool != null && TOOLS.contains(tool); }

    /** Mag deze tool als losse app draaien? */
    static boolean allowed(Context c, String tool) {
        return known(tool) && prefs(c).getStringSet("tools", Collections.<String>emptySet()).contains(tool);
    }

    static Set<String> list(Context c) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : prefs(c).getStringSet("tools", Collections.<String>emptySet())) if (known(t)) out.add(t);
        return out;
    }

    /** Aan of uit. Uit: een al vastgezette losse app werkt niet meer (het startscherm toont hem grijs). */
    static void set(Context c, String tool, boolean on) {
        if (!known(tool)) return;
        Set<String> s = new LinkedHashSet<>(list(c));
        if (on) s.add(tool); else s.remove(tool);
        prefs(c).edit().putStringSet("tools", s).apply();
        if (Build.VERSION.SDK_INT >= 25) {
            try {
                ShortcutManager sm = c.getSystemService(ShortcutManager.class);
                if (sm != null) {
                    if (on) sm.enableShortcuts(Collections.singletonList("solo-" + tool));
                    else sm.disableShortcuts(Collections.singletonList("solo-" + tool), "Deze losse app staat uit (Tools → Instellingen)");
                }
            } catch (Exception ignored) { }
        }
    }

    static Intent intent(Context c, String tool, String label, String color, String glyph) {
        return new Intent(c, SoloActivity.class).setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("tools-solo://" + tool)) // eigen taak per tool (documentLaunchMode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("solo", tool).putExtra("open", tool).putExtra("label", label).putExtra("color", color).putExtra("glyph", glyph);
    }

    /** Losse app op het startscherm zetten. Geeft "" bij succes, anders een melding. */
    static String pin(Context c, String tool, String label, String color, String glyph) {
        if (!allowed(c, tool)) return "Zet deze losse app eerst aan bij Instellingen";
        if (Build.VERSION.SDK_INT < 26) return "Losse apps op het startscherm kan vanaf Android 8";
        ShortcutManager sm = c.getSystemService(ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) return "Je startscherm ondersteunt geen snelkoppelingen";
        String l = label == null || label.isEmpty() ? "Tool" : label;
        try {
            ShortcutInfo si = new ShortcutInfo.Builder(c, "solo-" + tool).setShortLabel(l.length() > 20 ? l.substring(0, 20) : l)
                    .setLongLabel(l).setIcon(Shortcuts.icon(color, glyph)).setIntent(intent(c, tool, l, color, glyph)).build();
            try { sm.enableShortcuts(Collections.singletonList("solo-" + tool)); } catch (Exception ignored) { }
            sm.requestPinShortcut(si, null);
            return "";
        } catch (Exception e) {
            return "Losse app maken lukt niet";
        }
    }

    /** Pictogram voor "recente apps": rond, in de kleur van de tool, met het symbool erin. */
    static Bitmap taskIcon(String color, String glyph) {
        int size = 192;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color(color));
        cv.drawCircle(size / 2f, size / 2f, size / 2f, p);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(Color.WHITE);
        t.setTextAlign(Paint.Align.CENTER);
        String g = glyph == null || glyph.isEmpty() ? "★" : glyph;
        t.setTextSize(size * 0.5f);
        Rect r = new Rect();
        t.getTextBounds(g, 0, g.length(), r);
        cv.drawText(g, size / 2f, size / 2f - r.exactCenterY(), t);
        return b;
    }

    static int color(String s) {
        try { return Color.parseColor(s); } catch (Exception e) { return Color.parseColor("#2F5DA6"); }
    }
}
