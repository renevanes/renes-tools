package nl.rene.tools;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Process;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Snelkoppelingen van apps (Android 7.1+): de lijst bij lang drukken op een app (bijv. "Nieuw bericht"),
 * en vastgezette snelkoppelingen die een app op het startscherm wil zetten ("contact op startscherm").
 * Dat mag alleen het standaard-startscherm; anders geeft Android niets en doen we niets.
 */
final class LauncherShortcuts {

    private LauncherShortcuts() { }

    static LauncherApps la(Context c) { return (LauncherApps) c.getSystemService(Context.LAUNCHER_APPS_SERVICE); }

    static boolean canHost(Context c) {
        if (Build.VERSION.SDK_INT < 25) return false;
        try { LauncherApps l = la(c); return l != null && l.hasShortcutHostPermission(); } catch (Exception e) { return false; }
    }

    /** Snelkoppelingen van een app (uit het manifest en dynamisch), hoogstens 5, op volgorde. */
    static List<ShortcutInfo> forApp(Context c, String pkg) {
        List<ShortcutInfo> out = new ArrayList<>();
        if (!canHost(c) || pkg == null) return out;
        try {
            LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery().setPackage(pkg)
                    .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST);
            List<ShortcutInfo> l = la(c).getShortcuts(q, Process.myUserHandle());
            if (l != null) for (ShortcutInfo s : l) if (s.isEnabled()) out.add(s);
            Collections.sort(out, LauncherShortcuts::byRank);
        } catch (Exception ignored) { }
        while (out.size() > 5) out.remove(out.size() - 1);
        return out;
    }

    private static int byRank(ShortcutInfo a, ShortcutInfo b) {
        if (a.isDeclaredInManifest() != b.isDeclaredInManifest()) return a.isDeclaredInManifest() ? -1 : 1;
        return Integer.compare(a.getRank(), b.getRank());
    }

    static String label(ShortcutInfo s) {
        CharSequence l = s.getShortLabel();
        if (l == null || l.length() == 0) l = s.getLongLabel();
        return l == null ? s.getId() : l.toString();
    }

    /** [{id, label}] voor de menu's in de webpagina. */
    static String json(Context c, String pkg) {
        JSONArray a = new JSONArray();
        try { for (ShortcutInfo s : forApp(c, pkg)) a.put(new JSONObject().put("id", s.getId()).put("label", label(s))); } catch (Exception ignored) { }
        return a.toString();
    }

    /** Snelkoppeling starten. Geeft "" of een melding. */
    static String start(Context c, String pkg, String id) {
        if (Build.VERSION.SDK_INT < 25) return "Kan op deze Android-versie niet";
        if (!canHost(c)) return "Kies Rene's Tools eerst als standaard-startscherm";
        try { la(c).startShortcut(pkg, id, null, null, Process.myUserHandle()); return ""; }
        catch (Exception e) { return "Deze snelkoppeling werkt niet meer"; }
    }

    // ---------- pictogrammen van vastgezette snelkoppelingen ----------

    static File iconFile(Context c, String pkg, String id) {
        File d = new File(c.getFilesDir(), "shortcut-icons");
        d.mkdirs();
        return new File(d, Integer.toHexString((pkg + "/" + id).hashCode()) + ".png");
    }

    static void saveIcon(Context c, ShortcutInfo s) {
        try {
            Drawable d = la(c).getShortcutIconDrawable(s, c.getResources().getDisplayMetrics().densityDpi);
            if (d == null) return;
            int size = Math.round(56 * c.getResources().getDisplayMetrics().density);
            Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, size, size);
            d.draw(new Canvas(b));
            try (FileOutputStream o = new FileOutputStream(iconFile(c, s.getPackage(), s.getId()))) { b.compress(Bitmap.CompressFormat.PNG, 100, o); }
            b.recycle();
        } catch (Exception ignored) { }
    }

    static Bitmap icon(Context c, String pkg, String id) {
        try {
            File f = iconFile(c, pkg, id);
            return f.exists() ? android.graphics.BitmapFactory.decodeFile(f.getPath()) : null;
        } catch (Exception e) { return null; }
    }

    // ---------- wachtrij: vastgezet terwijl het startscherm niet open was ----------

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("pinned_shortcuts", Context.MODE_PRIVATE); }

    static synchronized void enqueue(Context c, String pkg, String id, String label) {
        try {
            JSONArray q = new JSONArray(prefs(c).getString("queue", "[]"));
            q.put(new JSONObject().put("p", pkg).put("s", id).put("n", label));
            prefs(c).edit().putString("queue", q.toString()).apply();
        } catch (Exception ignored) { }
    }

    /** Alles uit de wachtrij halen (en leegmaken). */
    static synchronized JSONArray take(Context c) {
        JSONArray q;
        String s = prefs(c).getString("queue", null);
        if (s == null) return new JSONArray();
        try { q = new JSONArray(s); } catch (Exception e) { q = new JSONArray(); }
        prefs(c).edit().remove("queue").apply();
        return q;
    }
}
