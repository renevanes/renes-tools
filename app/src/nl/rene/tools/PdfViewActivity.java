package nl.rene.tools;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Eigen PDF-viewer. Opent PDF's uit andere apps (Bestanden, Gmail, WhatsApp, Downloads) en uit het PDF-scherm.
 * De PDF wordt eerst naar de cache van de app gekopieerd: de toegang van de andere app geldt alleen even, en zo
 * kun je hem daarna nog delen, opslaan, afdrukken of splitsen. Bij sluiten wordt de kopie weer weggehaald.
 */
public class PdfViewActivity extends Activity implements PdfView.Host {
    static final String DIR = "pdf-view";
    static final long MAX = 500L * 1024 * 1024;
    private static final int REQ_SAVE = 1;
    private static final int BRAND = 0xFF1E5AA8;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread th;
    private Handler bg;
    private float dp;

    private FrameLayout root;
    private PdfView view;
    private LinearLayout bar, searchBar;
    private TextView title, sub, pill, msg, qCount;
    private EditText q;
    private ProgressBar spin;
    private ImageButton searchBtn;
    private boolean barsOn = true, searchOn;
    private int insetTop, insetBottom;

    private PdfDoc doc;
    private File file;
    private boolean ownCopy;
    private String name = "document";
    private String posKey;
    private Uri source;

    private final List<PdfDoc.Match> matches = new ArrayList<>();
    private int cur = -1;
    private volatile int searchGen;
    private final View.OnClickListener pillPage = v -> askPage();
    private final Runnable hidePill = () -> pill.animate().alpha(0f).setDuration(250).start();

    // ---------- opbouw ----------

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        th = new HandlerThread("pdf-view");
        th.start();
        bg = new Handler(th.getLooper());
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        buildUi();
        if (Build.VERSION.SDK_INT >= 33)
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, (android.window.OnBackInvokedCallback) this::back);
        load(getIntent(), null);
    }

    private int px(float v) { return Math.round(v * dp); }

    private ImageButton iconButton(int res, String label, View.OnClickListener l) {
        ImageButton ib = new ImageButton(this);
        ib.setImageResource(res);
        ib.setContentDescription(label);
        ib.setOnClickListener(l);
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        ib.setBackgroundResource(tv.resourceId);
        ib.setLayoutParams(new LinearLayout.LayoutParams(px(48), px(48)));
        if (Build.VERSION.SDK_INT >= 26) ib.setTooltipText(label);
        return ib;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF3C4043);
        view = new PdfView(this);
        view.setHost(this);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));

        // balk bovenaan
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(BRAND);
        bar.setElevation(px(4));
        bar.addView(iconButton(R.drawable.ic_pv_back, "Terug", v -> back()));
        LinearLayout tl = new LinearLayout(this);
        tl.setOrientation(LinearLayout.VERTICAL);
        title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        title.setText("PDF");
        sub = new TextView(this);
        sub.setTextColor(0xCCFFFFFF);
        sub.setTextSize(13);
        sub.setSingleLine(true);
        tl.addView(title); tl.addView(sub);
        tl.setOnClickListener(v -> askPage());
        bar.addView(tl, new LinearLayout.LayoutParams(0, -2, 1f));
        searchBtn = iconButton(R.drawable.ic_pv_search, "Zoeken", v -> openSearch());
        searchBtn.setVisibility(PdfDoc.canText() ? View.VISIBLE : View.GONE);
        bar.addView(searchBtn);
        bar.addView(iconButton(R.drawable.ic_pv_share, "Delen", v -> share()));
        final ImageButton more = iconButton(R.drawable.ic_pv_more, "Meer", null);
        more.setOnClickListener(v -> menu(more));
        bar.addView(more);
        root.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // zoekbalk (Android 15 en nieuwer)
        searchBar = new LinearLayout(this);
        searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setBackgroundColor(BRAND);
        searchBar.setElevation(px(4));
        searchBar.setVisibility(View.GONE);
        searchBar.addView(iconButton(R.drawable.ic_pv_close, "Zoeken sluiten", v -> closeSearch()));
        q = new EditText(this);
        q.setHint("Zoeken in PDF");
        q.setTextColor(Color.WHITE);
        q.setHintTextColor(0x99FFFFFF);
        q.setSingleLine(true);
        q.setBackground(null);
        q.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        q.setInputType(InputType.TYPE_CLASS_TEXT);
        q.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_SEARCH || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) { search(q.getText().toString().trim()); hideKeyboard(); return true; }
            return false;
        });
        searchBar.addView(q, new LinearLayout.LayoutParams(0, -2, 1f));
        qCount = new TextView(this);
        qCount.setTextColor(0xDDFFFFFF);
        qCount.setTextSize(14);
        qCount.setPadding(px(4), 0, px(4), 0);
        searchBar.addView(qCount);
        searchBar.addView(iconButton(R.drawable.ic_pv_up, "Vorige", v -> step(-1)));
        searchBar.addView(iconButton(R.drawable.ic_pv_down, "Volgende", v -> step(1)));
        root.addView(searchBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // paginanummer onderaan
        pill = new TextView(this);
        pill.setTextColor(Color.WHITE);
        pill.setTextSize(14);
        pill.setPadding(px(14), px(6), px(14), px(6));
        GradientDrawable pb = new GradientDrawable();
        pb.setColor(0xDD202124);
        pb.setCornerRadius(px(18));
        pill.setBackground(pb);
        pill.setAlpha(0f);
        pill.setOnClickListener(pillPage);
        pill.setContentDescription("Naar pagina");
        root.addView(pill, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        // laden / foutmelding
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setGravity(Gravity.CENTER);
        spin = new ProgressBar(this);
        mid.addView(spin);
        msg = new TextView(this);
        msg.setTextColor(Color.WHITE);
        msg.setTextSize(16);
        msg.setGravity(Gravity.CENTER);
        msg.setPadding(px(32), px(12), px(32), 0);
        msg.setText("PDF openen…");
        mid.addView(msg);
        root.addView(mid, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        root.setOnApplyWindowInsetsListener((v, ins) -> { applyInsets(ins); return ins; });
        setContentView(root);
    }

    private void applyInsets(WindowInsets ins) {
        int l, r;
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets s = ins.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            insetTop = s.top; insetBottom = s.bottom; l = s.left; r = s.right;
            android.graphics.Insets ime = ins.getInsets(WindowInsets.Type.ime());
            insetBottom = Math.max(insetBottom, ime.bottom);
        } else { insetTop = ins.getSystemWindowInsetTop(); insetBottom = ins.getSystemWindowInsetBottom(); l = ins.getSystemWindowInsetLeft(); r = ins.getSystemWindowInsetRight(); }
        bar.setPadding(l + px(4), insetTop, r + px(4), 0);
        searchBar.setPadding(l + px(4), insetTop, r + px(4), 0);
        bar.setMinimumHeight(insetTop + px(56));
        searchBar.setMinimumHeight(insetTop + px(56));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) pill.getLayoutParams();
        lp.bottomMargin = insetBottom + px(20);
        pill.setLayoutParams(lp);
        view.setPadding(l, insetTop + px(56), r, insetBottom);
    }

    // ---------- openen ----------

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        savePos();
        load(i, null);
    }

    private void load(Intent i, String password) {
        final Uri u = i == null ? null : i.getData();
        if (u == null) { fail("Geen PDF om te openen"); return; }
        if (!PdfTools.grantedBySender(i, java.util.Collections.singletonList(u))) { fail("Deze PDF kan niet geopend worden"); return; }
        source = u;
        spin.setVisibility(View.VISIBLE);
        msg.setText("PDF openen…");
        msg.setVisibility(View.VISIBLE);
        final Context app = getApplicationContext();
        final String pw = password;
        final File keep = password != null ? file : null;
        final boolean keepOwn = ownCopy;
        bg.post(() -> {
            File f = keep;
            boolean own = keepOwn;
            String nm = name;
            PdfDoc d;
            try {
                if (f == null) {
                    cleanup(app);
                    File mine = null;
                    try { mine = PdfProvider.file(app, u); } catch (Exception ignored) { }
                    if (mine != null) { f = mine; own = false; nm = mine.getName().replaceAll("\\.pdf$", ""); } // eigen bestand: niet kopiëren of weggooien
                    else {
                        PdfTools.checkUri(app, u);
                        nm = PdfTools.base(PdfTools.displayName(app, u));
                        File dir = new File(PdfTools.dir(app, DIR), Long.toHexString(System.nanoTime()) + Integer.toHexString(new java.util.Random().nextInt()));
                        if (!dir.mkdirs()) throw new Exception("Geen ruimte om de PDF te openen");
                        f = new File(dir, nm + ".pdf");
                        copyIn(app, u, f);
                        own = true;
                    }
                }
                d = new PdfDoc(f, nm, pw);
            } catch (PdfDoc.NeedsPassword e) {
                final File ff = f; final boolean oo = own; final String nn = nm; final boolean retry = pw != null;
                ui.post(() -> { if (isDestroyed()) { if (oo && ff != null) deleteCopy(ff); return; } file = ff; ownCopy = oo; name = nn; askPassword(retry); });
                return;
            } catch (OutOfMemoryError e) {
                if (f != null && own) deleteCopy(f);
                ui.post(() -> fail("Deze PDF is te groot om te openen"));
                return;
            } catch (Throwable e) {
                if (f != null && own) deleteCopy(f);
                final String m = e.getMessage() == null || e instanceof SecurityException ? "Deze PDF is niet te openen" : e.getMessage();
                ui.post(() -> fail(m));
                return;
            }
            final PdfDoc dd = d; final File ff = f; final boolean oo = own; final String nn = nm;
            final float[] pos = readPos(app, key(nn, ff.length(), dd.count));
            ui.post(() -> {
                if (isDestroyed()) { dd.close(); if (oo) deleteCopy(ff); return; }
                PdfDoc old = doc;
                File oldFile = file; boolean oldOwn = ownCopy;
                doc = dd; file = ff; ownCopy = oo; name = nn;
                posKey = key(nn, ff.length(), dd.count);
                closeSearch();
                view.setDoc(dd, bg, (int) pos[0], pos[1]);
                spin.setVisibility(View.GONE); msg.setVisibility(View.GONE);
                title.setText(nn);
                updateSub(view.currentPage());
                if (old != null && old != dd) { bg.post(old::close); if (oldOwn && oldFile != null && !oldFile.equals(ff)) deleteCopy(oldFile); }
                maybeHintDefault();
            });
        });
    }

    /** Kopie maken (een PDF uit een andere app is daarna nog te delen, op te slaan of te splitsen). */
    private static void copyIn(Context c, Uri u, File to) throws Exception {
        PdfTools.copy(c, u, to, MAX); // controleert ook of we dit namens de andere app mogen lezen
        // moet echt een PDF zijn
        byte[] head = new byte[1024]; int n;
        try (InputStream in = new FileInputStream(to)) { n = in.read(head); }
        if (n <= 0 || !new String(head, 0, n, java.nio.charset.StandardCharsets.ISO_8859_1).contains("%PDF-")) { to.delete(); throw new Exception("Dit is geen PDF"); }
    }

    private static void deleteCopy(File f) {
        File d = f.getParentFile();
        f.delete();
        if (d != null && d.getParentFile() != null && DIR.equals(d.getParentFile().getName())) d.delete();
    }

    /** Kopieën die ouder zijn dan een dag (bijv. na een crash) weghalen. De voorbeeld-PDF blijft staan. */
    static void cleanup(Context c) {
        File[] ds = PdfTools.dir(c, DIR).listFiles();
        if (ds == null) return;
        long now = System.currentTimeMillis();
        for (File d : ds) {
            if ("standaard".equals(d.getName())) continue;
            if (now - d.lastModified() > 86_400_000L) { PdfTools.clear(d); d.delete(); }
        }
    }

    private void fail(String m) {
        if (isDestroyed()) return;
        spin.setVisibility(View.GONE);
        msg.setVisibility(View.VISIBLE);
        msg.setText(m);
        title.setText(doc == null ? "PDF" : name);
    }

    private void askPassword(boolean wrong) {
        spin.setVisibility(View.GONE);
        if (!PdfDoc.canPassword()) {
            if (ownCopy && file != null) deleteCopy(file);
            file = null;
            fail("Deze PDF is beveiligd met een wachtwoord. Op deze Android-versie kan Rene's Tools hem niet openen.");
            return;
        }
        msg.setVisibility(View.VISIBLE);
        msg.setText("Deze PDF is beveiligd met een wachtwoord");
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        e.setHint("Wachtwoord");
        FrameLayout box = new FrameLayout(this);
        box.setPadding(px(20), px(8), px(20), 0);
        box.addView(e);
        new AlertDialog.Builder(this)
                .setTitle(wrong ? "Wachtwoord klopt niet" : "Wachtwoord")
                .setMessage(wrong ? "Probeer het opnieuw." : "Voer het wachtwoord van deze PDF in.")
                .setView(box)
                .setPositiveButton("Openen", (dl, w) -> load(getIntent(), e.getText().toString()))
                .setNegativeButton("Annuleren", (dl, w) -> back())
                .setOnCancelListener(dl -> back())
                .show();
        e.requestFocus();
    }

    // ---------- positie onthouden (alleen paginanummer, onder een hash van naam en grootte) ----------

    private static String key(String name, long size, int pages) { return Integer.toHexString((name + "|" + size + "|" + pages).hashCode()); }

    private static float[] readPos(Context c, String k) {
        String v = c.getSharedPreferences("pdfview", MODE_PRIVATE).getString("p" + k, null);
        if (v == null) return new float[]{0, 0};
        try { String[] p = v.split(","); return new float[]{Integer.parseInt(p[0]), Float.parseFloat(p[1])}; } catch (Exception e) { return new float[]{0, 0}; }
    }

    private void savePos() {
        if (doc == null || posKey == null) return;
        SharedPreferences sp = getSharedPreferences("pdfview", MODE_PRIVATE);
        int p = view.topPage();
        SharedPreferences.Editor ed = sp.edit().putString("p" + posKey, p + "," + view.topFrac(p) + "," + System.currentTimeMillis());
        Map<String, ?> all = sp.getAll();
        if (all.size() > 80) { // de oudste weghalen
            List<Map.Entry<String, ?>> l = new ArrayList<>(all.entrySet());
            java.util.Collections.sort(l, (a, b) -> Long.compare(stamp(a.getValue()), stamp(b.getValue())));
            for (int i = 0; i < 30 && i < l.size(); i++) if (l.get(i).getKey().startsWith("p")) ed.remove(l.get(i).getKey());
        }
        ed.apply();
    }

    private static long stamp(Object v) { try { return Long.parseLong(String.valueOf(v).split(",")[2]); } catch (Exception e) { return 0; } }

    // ---------- PdfView.Host ----------

    @Override public void onTap() { setBars(!barsOn); }

    @Override public void onScrolled(int page) {
        updateSub(page);
        if (doc == null) return;
        pill.setText((page + 1) + " / " + doc.count);
        pill.setOnClickListener(pillPage);
        pill.animate().cancel();
        pill.setAlpha(1f);
        ui.removeCallbacks(hidePill);
        ui.postDelayed(hidePill, 1500);
    }

    @Override public void onLink(PdfDoc.Link l) {
        if (l.page >= 0) { view.goToPage(l.page); return; }
        final Uri u = Uri.parse(l.uri);
        String s = u.getScheme() == null ? "" : u.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!s.equals("http") && !s.equals("https") && !s.equals("mailto") && !s.equals("tel")) { Toast.makeText(this, "Deze link kan niet geopend worden", Toast.LENGTH_SHORT).show(); return; }
        // Eerst laten zien waar de link heen gaat: een PDF kan een misleidende tekst over een link hebben
        String shown = l.uri.length() > 300 ? l.uri.substring(0, 300) + "…" : l.uri;
        new AlertDialog.Builder(this).setTitle("Link openen?").setMessage(shown)
                .setPositiveButton("Openen", (d, w) -> { try { startActivity(new Intent(Intent.ACTION_VIEW, u).addCategory(Intent.CATEGORY_BROWSABLE)); } catch (Exception e) { Toast.makeText(this, "Geen app voor deze link", Toast.LENGTH_SHORT).show(); } })
                .setNegativeButton("Annuleren", null).show();
    }

    private void updateSub(int page) {
        if (doc == null) { sub.setText(""); return; }
        sub.setText("Pagina " + (page + 1) + " van " + doc.count);
    }

    private void setBars(boolean on) {
        barsOn = on;
        View top = searchOn ? searchBar : bar;
        top.animate().translationY(on ? 0 : -top.getHeight()).setDuration(180).start();
        if (Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (on) c.show(WindowInsets.Type.systemBars());
            else { c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); c.hide(WindowInsets.Type.systemBars()); }
        }
    }

    // ---------- terug ----------

    private void back() {
        if (searchOn) { closeSearch(); return; }
        finish();
    }

    @Override public void onBackPressed() { back(); }

    @Override protected void onPause() { super.onPause(); savePos(); }

    @Override protected void onDestroy() {
        super.onDestroy();
        searchGen++;
        view.release();
        final PdfDoc d = doc; final File f = file; final boolean own = ownCopy;
        doc = null;
        bg.post(() -> { if (d != null) d.close(); if (own && f != null) deleteCopy(f); });
        th.quitSafely();
    }

    // ---------- naar pagina ----------

    private void askPage() {
        if (doc == null) return;
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setHint("1 – " + doc.count);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(px(20), px(8), px(20), 0);
        box.addView(e);
        AlertDialog dl = new AlertDialog.Builder(this).setTitle("Naar pagina").setView(box)
                .setPositiveButton("Ga", (d, w) -> { try { view.goToPage(Integer.parseInt(e.getText().toString().trim()) - 1); } catch (Exception ignored) { } })
                .setNegativeButton("Annuleren", null).create();
        dl.show();
        e.requestFocus();
        if (dl.getWindow() != null) dl.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    }

    // ---------- zoeken ----------

    private void openSearch() {
        if (doc == null || !PdfDoc.canText()) return;
        searchOn = true;
        if (!barsOn) setBars(true);
        bar.setVisibility(View.INVISIBLE);
        searchBar.setTranslationY(0);
        searchBar.setVisibility(View.VISIBLE);
        q.requestFocus();
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.showSoftInput(q, InputMethodManager.SHOW_IMPLICIT);
    }

    private void closeSearch() {
        searchGen++;
        if (!searchOn) return;
        searchOn = false;
        hideKeyboard();
        searchBar.setVisibility(View.GONE);
        bar.setTranslationY(0);
        bar.setVisibility(View.VISIBLE);
        matches.clear(); cur = -1; qCount.setText("");
        view.setMatches(null, -1);
    }

    private void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(q.getWindowToken(), 0);
    }

    private void search(final String s) {
        final int g = ++searchGen;
        matches.clear(); cur = -1;
        view.setMatches(matches, -1);
        if (s.isEmpty() || doc == null) { qCount.setText(""); return; }
        qCount.setText("…");
        final PdfDoc d = doc;
        final int start = view.currentPage();
        new Thread(() -> {
            for (int i = 0; i < d.count; i++) {
                if (g != searchGen || d.isClosed()) return;
                final List<PdfDoc.Match> m = d.search(i, s);
                if (m.isEmpty()) continue;
                ui.post(() -> {
                    if (g != searchGen) return;
                    matches.addAll(m);
                    if (cur < 0 && m.get(0).page >= start) { cur = matches.size() - m.size(); view.showMatch(cur); }
                    view.setMatches(matches, cur);
                    qCount.setText((cur + 1) + " / " + matches.size() + " …");
                });
            }
            ui.post(() -> {
                if (g != searchGen) return;
                if (matches.isEmpty()) { qCount.setText("0"); Toast.makeText(this, "Niet gevonden (een gescande PDF heeft geen tekst)", Toast.LENGTH_SHORT).show(); return; }
                if (cur < 0) { cur = 0; view.showMatch(0); view.setMatches(matches, 0); }
                qCount.setText((cur + 1) + " / " + matches.size());
            });
        }, "pdf-search").start();
    }

    private void step(int d) {
        if (matches.isEmpty()) { String s = q.getText().toString().trim(); if (!s.isEmpty()) search(s); return; }
        cur = (cur + d + matches.size()) % matches.size();
        view.showMatch(cur);
        view.setMatches(matches, cur);
        String c = qCount.getText().toString();
        qCount.setText((cur + 1) + " / " + matches.size() + (c.endsWith("…") ? " …" : ""));
    }

    // ---------- menu ----------

    private void menu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        boolean has = doc != null;
        m.add(0, 1, 0, "Naar pagina…").setEnabled(has);
        if (PdfDoc.canText()) m.add(0, 2, 0, "Tekst van deze pagina kopiëren").setEnabled(has);
        m.add(0, 3, 0, "Donker lezen").setCheckable(true).setChecked(view.isDark());
        m.add(0, 4, 0, "Opslaan als…").setEnabled(has);
        m.add(0, 5, 0, "Afdrukken").setEnabled(has);
        m.add(0, 6, 0, "Pagina's eruit halen of splitsen").setEnabled(has);
        m.add(0, 7, 0, "Openen met andere app").setEnabled(has);
        m.add(0, 8, 0, "Standaard PDF-app");
        pm.setOnMenuItemClickListener(it -> {
            switch (it.getItemId()) {
                case 1: askPage(); break;
                case 2: copyText(); break;
                case 3: view.setDark(!view.isDark()); getSharedPreferences("pdfview", MODE_PRIVATE).edit().putBoolean("dark", view.isDark()).apply(); break;
                case 4: saveAs(); break;
                case 5: print(); break;
                case 6: toTools(); break;
                case 7: openWith(); break;
                case 8: defaultDialog(this); break;
                default: return false;
            }
            return true;
        });
        pm.show();
    }

    @Override protected void onResume() {
        super.onResume();
        if (file != null && file.getParentFile() != null) file.getParentFile().setLastModified(System.currentTimeMillis()); // nog in gebruik: niet opruimen
        boolean dk = getSharedPreferences("pdfview", MODE_PRIVATE).getBoolean("dark", false);
        if (dk != view.isDark()) view.setDark(dk);
    }

    private void copyText() {
        final PdfDoc d = doc;
        if (d == null) return;
        final int p = view.currentPage();
        new Thread(() -> {
            final String t = d.text(p);
            ui.post(() -> {
                if (t.isEmpty()) { Toast.makeText(this, "Op deze pagina staat geen tekst (misschien een scan)", Toast.LENGTH_SHORT).show(); return; }
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("PDF pagina " + (p + 1), t));
                if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Tekst van pagina " + (p + 1) + " gekopieerd", Toast.LENGTH_SHORT).show();
            });
        }, "pdf-text").start();
    }

    /**
     * Uri voor een andere app. De kopie blijft dan na sluiten nog een dag staan (die app leest hem misschien
     * pas later, bijv. bij het versturen van een mail); daarna ruimt cleanup() hem op.
     */
    private Uri shareUri() {
        if (file == null) return null;
        ownCopy = false;
        File d = file.getParentFile();
        if (d != null) d.setLastModified(System.currentTimeMillis());
        return PdfProvider.uri(file);
    }

    private void share() {
        Uri u = shareUri();
        if (u == null) return;
        Intent s = new Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        s.setClipData(ClipData.newRawUri("", u));
        try { startActivity(Intent.createChooser(s, "PDF delen")); } catch (Exception e) { Toast.makeText(this, "Delen lukt niet", Toast.LENGTH_SHORT).show(); }
    }

    private void openWith() {
        Uri u = shareUri();
        if (u == null) return;
        Intent v = new Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        v.setClipData(ClipData.newRawUri("", u));
        Intent ch = Intent.createChooser(v, "Openen met");
        ch.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, new ComponentName[]{new ComponentName(this, PdfViewActivity.class)});
        try { startActivity(ch); } catch (Exception e) { Toast.makeText(this, "Geen andere app voor PDF's", Toast.LENGTH_SHORT).show(); }
    }

    private void saveAs() {
        if (file == null) return;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf").putExtra(Intent.EXTRA_TITLE, name + ".pdf");
        try { startActivityForResult(i, REQ_SAVE); } catch (Exception e) { Toast.makeText(this, "Opslaan niet beschikbaar", Toast.LENGTH_SHORT).show(); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_SAVE || res != RESULT_OK || data == null || data.getData() == null || file == null) return;
        final Uri to = data.getData();
        final File f = file;
        final Context app = getApplicationContext();
        new Thread(() -> {
            String r = "✓ Opgeslagen";
            try (InputStream in = new FileInputStream(f); OutputStream o = app.getContentResolver().openOutputStream(to, "w")) {
                if (o == null) throw new Exception();
                byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) > 0) o.write(b, 0, n);
            } catch (Exception e) { r = "Opslaan lukt niet"; }
            final String rr = r;
            ui.post(() -> Toast.makeText(app, rr, Toast.LENGTH_SHORT).show());
        }, "pdf-save").start();
    }

    private void print() {
        if (file == null || doc == null) return;
        PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
        if (pm == null) return;
        shareUri();
        try { pm.print(name, new PrintAdapter(file, name, doc.count), new PrintAttributes.Builder().build()); }
        catch (Exception e) { Toast.makeText(this, "Afdrukken lukt niet", Toast.LENGTH_SHORT).show(); }
    }

    /** Geeft de PDF zoals hij is aan de afdrukservice. */
    static final class PrintAdapter extends PrintDocumentAdapter {
        final File f; final String name; final int pages;
        PrintAdapter(File f, String name, int pages) { this.f = f; this.name = name; this.pages = pages; }

        @Override public void onLayout(PrintAttributes o, PrintAttributes n, CancellationSignal cs, LayoutResultCallback cb, Bundle extras) {
            if (cs.isCanceled()) { cb.onLayoutCancelled(); return; }
            cb.onLayoutFinished(new PrintDocumentInfo.Builder(name + ".pdf").setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(pages).build(), false);
        }

        /** Kopiëren op de achtergrond (een grote PDF zou het scherm laten hangen); antwoorden op de hoofdthread. */
        @Override public void onWrite(PageRange[] range, ParcelFileDescriptor dest, CancellationSignal cs, WriteResultCallback cb) {
            final Handler h = new Handler(Looper.getMainLooper());
            new Thread(() -> {
                int res = 0; // 0 klaar, 1 geannuleerd, 2 fout
                try (InputStream in = new FileInputStream(f); OutputStream o = new FileOutputStream(dest.getFileDescriptor())) {
                    byte[] b = new byte[1 << 16]; int r;
                    while ((r = in.read(b)) > 0) { if (cs.isCanceled()) { res = 1; break; } o.write(b, 0, r); }
                } catch (Exception e) { res = 2; }
                final int fr = res;
                h.post(() -> { if (fr == 0) cb.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES}); else if (fr == 1) cb.onWriteCancelled(); else cb.onWriteFailed("Afdrukken lukt niet"); });
            }, "pdf-print").start();
        }
    }

    /** Naar het PDF-scherm om pagina's eruit te halen of te splitsen. */
    private void toTools() {
        if (file == null) return;
        final File f = file; final String nm = name;
        final Context app = getApplicationContext();
        Toast.makeText(this, "Bezig…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String r;
            try {
                File tmp = new File(PdfTools.dir(app, "pdf-in"), "kopie.pdf");
                try (InputStream in = new FileInputStream(f); OutputStream o = new FileOutputStream(tmp)) { byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) > 0) o.write(b, 0, n); }
                JSONObject j = new JSONObject(PdfTools.openPdfFile(app, tmp, nm));
                if (!j.has("error")) j.put("kind", "pdf");
                r = j.toString();
            } catch (Throwable e) { r = PdfTools.err(e); }
            final String res = r;
            ui.post(() -> {
                PdfShare.pending = res;
                startActivity(new Intent(app, MainActivity.class).putExtra("open", "pdf-share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            });
        }, "pdf-tools").start();
    }

    // ---------- standaard PDF-app ----------

    /** Welke app opent PDF's nu? {state: ours|none|other, app, pkg}. */
    static JSONObject defaultState(Context c) {
        JSONObject o = new JSONObject();
        try {
            PackageManager pm = c.getPackageManager();
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://" + PdfProvider.AUTH + "/test.pdf"), "application/pdf");
            ResolveInfo def = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            List<ResolveInfo> all = pm.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY);
            String state = "none", app = "", pkg = "";
            if (def != null && def.activityInfo != null) {
                boolean candidate = false;
                for (ResolveInfo r : all) if (r.activityInfo != null && r.activityInfo.packageName.equals(def.activityInfo.packageName) && r.activityInfo.name.equals(def.activityInfo.name)) candidate = true;
                if (candidate) {
                    pkg = def.activityInfo.packageName;
                    state = pkg.equals(c.getPackageName()) ? "ours" : "other";
                    app = String.valueOf(def.loadLabel(pm));
                }
            }
            o.put("state", state).put("app", app).put("pkg", pkg).put("count", all.size());
        } catch (Exception e) { try { o.put("state", "none"); } catch (Exception ignored) { } }
        return o;
    }

    /** Opent een voorbeeld-PDF via Android, zodat het keuzescherm verschijnt met "Altijd". */
    static void askAndroid(Context c) {
        try {
            File d = new File(PdfTools.dir(c, DIR), "standaard");
            d.mkdirs();
            File f = new File(d, "PDF-app instellen.pdf");
            if (!f.isFile()) {
                File t = PdfTools.textToPdf(c, "Rene's Tools opent je PDF's", "",
                        "Gelukt! Als je bij het kiezen 'Altijd' hebt getikt, openen PDF's vanaf nu in Rene's Tools: uit Bestanden, Downloads, Gmail en WhatsApp.\n\n"
                        + "Tips in de viewer:\n• Knijp om in te zoomen, dubbeltik om snel in of uit te zoomen.\n• Tik op het paginanummer om naar een pagina te gaan.\n"
                        + "• Tik één keer op de pagina om de balk te verbergen.\n• Via ⋮ kun je opslaan, afdrukken, donker lezen of pagina's eruit halen.");
                if (!t.renameTo(f)) { f = t; }
            }
            Uri u = PdfProvider.uri(f);
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            i.setClipData(ClipData.newRawUri("", u));
            c.startActivity(i);
        } catch (Exception e) { Toast.makeText(c, "Lukt niet: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    /** De instellingen van de app die nu PDF's opent (daar: Standaard openen → Standaardinstellingen wissen). */
    static void openAppSettings(Context c, String pkg) {
        try { c.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) { Toast.makeText(c, "Instellingen niet te openen", Toast.LENGTH_SHORT).show(); }
    }

    static void defaultDialog(Activity a) {
        JSONObject s = defaultState(a);
        String st = s.optString("state"), app = s.optString("app"), pkg = s.optString("pkg");
        AlertDialog.Builder b = new AlertDialog.Builder(a).setTitle("Standaard PDF-app");
        if (st.equals("ours")) b.setMessage("✓ PDF's openen al in Rene's Tools.").setPositiveButton("OK", null);
        else if (st.equals("other"))
            b.setMessage("PDF's openen nu in " + app + ".\n\nZo kies je Rene's Tools:\n1. Tik op 'Instellingen van " + app + "'.\n2. Kies 'Standaard openen' en tik op 'Standaardinstellingen wissen'.\n3. Kom terug en tik op 'Nu instellen'; kies Rene's Tools en 'Altijd'.")
                    .setPositiveButton("Instellingen van " + app, (d, w) -> openAppSettings(a, pkg))
                    .setNeutralButton("Nu instellen", (d, w) -> askAndroid(a))
                    .setNegativeButton("Annuleren", null);
        else b.setMessage("Nog geen app gekozen. Tik op 'Nu instellen', kies Rene's Tools en tik op 'Altijd'.")
                    .setPositiveButton("Nu instellen", (d, w) -> askAndroid(a))
                    .setNegativeButton("Annuleren", null);
        b.show();
    }

    /** Eenmalig geopend (niet als standaard)? Een paar keer een tip laten zien. */
    private void maybeHintDefault() {
        final SharedPreferences sp = getSharedPreferences("pdfview", MODE_PRIVATE);
        final int n = sp.getInt("hint", 0);
        if (n >= 3) return;
        final Context app = getApplicationContext();
        bg.post(() -> {
            String st = defaultState(app).optString("state");
            if (st.equals("ours")) { sp.edit().putInt("hint", 3).apply(); return; }
            ui.post(() -> {
                if (isDestroyed()) return;
                sp.edit().putInt("hint", n + 1).apply();
                pill.setText("Altijd met Rene's Tools openen? Tik hier");
                pill.setOnClickListener(v -> defaultDialog(this));
                pill.animate().cancel(); pill.setAlpha(1f);
                ui.removeCallbacks(hidePill);
                ui.postDelayed(() -> { hidePill.run(); pill.setOnClickListener(pillPage); }, 5000);
            });
        });
    }
}
