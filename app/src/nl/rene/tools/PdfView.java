package nl.rene.tools;

import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.OverScroller;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Laat de pagina's van een PDF onder elkaar zien: scrollen, vegen, knijpen om te zoomen, dubbeltikken.
 * Pagina's worden op een achtergrondthread getekend; bij inzoomen wordt het zichtbare stuk opnieuw scherp getekend.
 */
public final class PdfView extends View implements GestureDetector.OnGestureListener, GestureDetector.OnDoubleTapListener, ScaleGestureDetector.OnScaleGestureListener {

    /** Wat de viewer-activiteit wil horen. */
    public interface Host {
        void onTap();
        void onLink(PdfDoc.Link l);
        void onScrolled(int page);
    }

    /** Een scherp getekend stuk van een pagina bij inzoomen (plek in documentcoördinaten bij zoom 1). */
    static final class Tile {
        final int page; final RectF doc; final Bitmap bmp;
        Tile(int p, RectF d, Bitmap b) { page = p; doc = d; bmp = b; }
    }

    static final float ZMAX = 8f;

    private final float dp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final GestureDetector gd;
    private final ScaleGestureDetector sgd;
    private final OverScroller scroller;
    private final Paint pagePaint = new Paint(Paint.FILTER_BITMAP_FLAG), blank = new Paint(), hl = new Paint(), hlCur = new Paint(), thumb = new Paint(Paint.ANTI_ALIAS_FLAG), shade = new Paint();
    private final RectF tmp = new RectF(), tmp2 = new RectF();

    private PdfDoc doc;
    private Handler bg;
    private Host host;

    // indeling bij zoom 1, in schermpixels
    private float k = 1, gap, docW, docH;
    private float[] top = new float[0], pw = new float[0], ph = new float[0];
    private float z = 1, zMin = 1, sx, sy;
    private boolean laidOut, scaling, dragThumb, dark;
    private float dragOff;
    private long lastScroll;
    private int lastPage = -1;
    private int pendingPage = -1; private float pendingFrac;

    // getekende pagina's (zoom 1) en wat er nog onderweg is
    private final LinkedHashMap<Integer, Bitmap> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final Set<Integer> pending = new HashSet<>();
    private final SparseArray<List<PdfDoc.Link>> links = new SparseArray<>();
    private int maxCache = 4;
    private volatile int gen, visFirst, visLast;

    // scherpe stukken bij inzoomen
    private List<Tile> tiles = new ArrayList<>();
    private volatile int tileGen;
    private boolean scaledThisGesture;
    private final Runnable detailJob = this::renderDetail;
    private final Runnable hideThumb = this::invalidate;
    private ValueAnimator anim;

    // zoekresultaten
    private List<PdfDoc.Match> matches = new ArrayList<>();
    private int matchCur = -1;

    public PdfView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        gap = 8 * dp;
        gd = new GestureDetector(c, this);
        sgd = new ScaleGestureDetector(c, this);
        sgd.setQuickScaleEnabled(true);
        scroller = new OverScroller(c);
        blank.setColor(0xFFFFFFFF);
        hl.setColor(0x66FFD54F);
        hlCur.setColor(0x99FF9800);
        thumb.setColor(0xCC8AB4F8);
        shade.setColor(0x33000000);
        setFocusable(true);
        setContentDescription("PDF");
    }

    void setHost(Host h) { host = h; }

    /** Document laten zien, beginnend op pagina page (fractie frac van die pagina naar beneden gescrold). */
    void setDoc(PdfDoc d, Handler bgThread, int page, float frac) {
        doc = d; bg = bgThread;
        cache.clear(); pending.clear(); links.clear(); tiles = new ArrayList<>(); matches = new ArrayList<>(); matchCur = -1;
        gen++; z = 1; laidOut = false;
        pendingPage = Math.max(0, Math.min(page, d.count - 1)); pendingFrac = Math.max(0, Math.min(frac, 1));
        if (getWidth() > 0) layoutPages();
        invalidate();
    }

    void setDark(boolean on) {
        dark = on;
        if (on) {
            ColorMatrix m = new ColorMatrix(new float[]{-1, 0, 0, 0, 255, 0, -1, 0, 0, 255, 0, 0, -1, 0, 255, 0, 0, 0, 1, 0});
            pagePaint.setColorFilter(new ColorMatrixColorFilter(m));
            blank.setColor(0xFF000000);
        } else { pagePaint.setColorFilter(null); blank.setColor(0xFFFFFFFF); }
        invalidate();
    }

    boolean isDark() { return dark; }

    // ---------- indeling ----------

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (doc == null || w <= 0) return;
        if (laidOut) { pendingPage = topPage(); pendingFrac = topFrac(pendingPage); }
        layoutPages();
    }

    private void layoutPages() {
        int n = doc.count, vw = getWidth(), vh = getHeight();
        if (vw <= 0 || vh <= 0) return;
        float maxW = 1, maxH = 1;
        for (int i = 0; i < n; i++) { maxW = Math.max(maxW, doc.w[i]); }
        k = (vw - 2 * gap) / maxW;
        top = new float[n]; pw = new float[n]; ph = new float[n];
        float y = gap;
        for (int i = 0; i < n; i++) { pw[i] = doc.w[i] * k; ph[i] = doc.h[i] * k; top[i] = y; y += ph[i] + gap; maxH = Math.max(maxH, ph[i]); }
        docW = vw; docH = y;
        // uitzoomen tot een hele pagina past (handig bij liggend scherm)
        zMin = Math.max(0.25f, Math.min(1f, (vh - getPaddingTop() - getPaddingBottom()) / (maxH + 2 * gap)));
        // genoeg pagina's bewaren voor vlot scrollen, zonder te veel geheugen
        long per = Math.max(1L, Math.min((long) (vw - 2 * gap) * (long) Math.max(1, (int) (maxH)), 12_000_000L) * 4L);
        int mc = 192;
        try { mc = ((ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE)).getMemoryClass(); } catch (Exception ignored) { }
        maxCache = (int) Math.max(3, Math.min(12, (mc * 1024L * 1024L / 4) / per));
        cache.clear(); pending.clear(); tiles = new ArrayList<>(); gen++;
        laidOut = true;
        z = Math.max(zMin, Math.min(z, ZMAX));
        int p = pendingPage >= 0 ? pendingPage : 0;
        sx = 0; sy = (top[p] + pendingFrac * ph[p]) * z - (pendingFrac == 0 ? gap * z / 2 : 0) - getPaddingTop();
        pendingPage = -1;
        clamp();
        invalidate();
        scheduleDetail();
    }

    private float contentW() { return docW * z; }
    private float contentH() { return docH * z; }
    /** Grootte waarop pagina i "gewoon" (zoom 1) getekend wordt: hooguit 4096 px per zijde en 12 MP. */
    private float baseScale(int i) {
        float w = Math.max(1, pw[i]), h = Math.max(1, ph[i]);
        return Math.min(1f, Math.min(Math.min(4096f / w, 4096f / h), (float) Math.sqrt(12e6 / (w * h))));
    }

    private float minX() { float cw = contentW(), vw = getWidth(); return cw <= vw ? (cw - vw) / 2 : 0; }
    private float maxX() { float cw = contentW(), vw = getWidth(); return cw <= vw ? (cw - vw) / 2 : cw - vw; }
    // Boven ligt de balk over de pagina (padding): de eerste pagina moet daar onder vandaan te scrollen zijn
    private float avail() { return getHeight() - getPaddingTop() - getPaddingBottom(); }
    private float minY() { float ch = contentH(); return ch <= avail() ? (ch - avail()) / 2 - getPaddingTop() : -getPaddingTop(); }
    private float maxY() { float ch = contentH(); return ch <= avail() ? (ch - avail()) / 2 - getPaddingTop() : ch - getHeight() + getPaddingBottom(); }
    private void clamp() { sx = Math.max(minX(), Math.min(sx, maxX())); sy = Math.max(minY(), Math.min(sy, maxY())); }

    /** Pagina op documenthoogte y (zoom 1). */
    private int pageAt(float y) {
        int lo = 0, hi = top.length - 1;
        while (lo < hi) { int mid = (lo + hi + 1) >>> 1; if (top[mid] - gap / 2 <= y) lo = mid; else hi = mid - 1; }
        return Math.max(0, lo);
    }

    int topPage() { return laidOut && top.length > 0 ? pageAt(Math.max(0, (sy + getPaddingTop()) / z)) : Math.max(0, pendingPage); }
    float topFrac(int p) { if (!laidOut || p >= top.length) return pendingFrac; return Math.max(0, Math.min(1, ((sy + getPaddingTop()) / z - top[p]) / ph[p])); }

    /** Balk erbij of eraf: de plek in het document blijft gelijk. */
    @Override public void setPadding(int l, int t, int r, int b) {
        if (l == getPaddingLeft() && t == getPaddingTop() && r == getPaddingRight() && b == getPaddingBottom()) return;
        int p = laidOut ? topPage() : -1; float f = p >= 0 ? topFrac(p) : 0;
        super.setPadding(l, t, r, b);
        if (p >= 0) { sy = (top[p] + f * ph[p]) * z - getPaddingTop(); clamp(); invalidate(); }
    }

    /** De pagina die je nu leest (bovenste derde deel van het scherm). */
    int currentPage() { return laidOut && top.length > 0 ? pageAt((sy + getPaddingTop() + avail() * 0.3f) / z) : Math.max(0, pendingPage); }

    void goToPage(int p) {
        if (doc == null) return;
        p = Math.max(0, Math.min(p, doc.count - 1));
        if (!laidOut) { pendingPage = p; pendingFrac = 0; return; }
        scroller.forceFinished(true);
        sy = (top[p] - gap / 2) * z - getPaddingTop();
        clamp(); scrolled(); invalidate(); scheduleDetail();
    }

    // ---------- zoeken ----------

    void setMatches(List<PdfDoc.Match> m, int cur) { matches = m == null ? new ArrayList<>() : m; matchCur = cur; invalidate(); }

    /** Zorgt dat zoekresultaat i in beeld is. */
    void showMatch(int i) {
        matchCur = i;
        if (!laidOut || i < 0 || i >= matches.size()) { invalidate(); return; }
        PdfDoc.Match m = matches.get(i);
        if (m.bounds.isEmpty() || m.page >= top.length) { goToPage(m.page); return; }
        RectF b = m.bounds.get(0);
        float pl = (docW - pw[m.page]) / 2;
        float dx = (pl + b.centerX() * k) * z, dy = (top[m.page] + b.centerY() * k) * z;
        if (dx < sx + 24 * dp || dx > sx + getWidth() - 24 * dp) sx = dx - getWidth() / 2f;
        if (dy < sy + getPaddingTop() + 24 * dp || dy > sy + getHeight() - getPaddingBottom() - 24 * dp) sy = dy - getHeight() / 3f;
        scroller.forceFinished(true);
        clamp(); invalidate(); scheduleDetail();
    }

    // ---------- tekenen ----------

    @Override protected void onDraw(Canvas c) {
        c.drawColor(dark ? 0xFF121212 : 0xFF3C4043);
        if (doc == null || !laidOut) return;
        int vh = getHeight();
        float vt = sy / z, vb = (sy + vh) / z;
        int first = pageAt(vt), last = first;
        for (int i = first; i < top.length && top[i] <= vb; i++) {
            last = i;
            float l = ((docW - pw[i]) / 2) * z - sx, t = top[i] * z - sy;
            tmp.set(l, t, l + pw[i] * z, t + ph[i] * z);
            Bitmap b = cache.get(i);
            if (b != null) c.drawBitmap(b, null, tmp, pagePaint); else c.drawRect(tmp, blank);
            for (Tile tl : tiles) {
                if (tl.page != i) continue;
                tmp2.set(tl.doc.left * z - sx, tl.doc.top * z - sy, tl.doc.right * z - sx, tl.doc.bottom * z - sy);
                c.drawBitmap(tl.bmp, null, tmp2, pagePaint);
            }
            for (int m = 0; m < matches.size(); m++) {
                PdfDoc.Match mt = matches.get(m);
                if (mt.page != i) continue;
                for (RectF r : mt.bounds) {
                    tmp2.set(l + r.left * k * z, t + r.top * k * z, l + r.right * k * z, t + r.bottom * k * z);
                    c.drawRect(tmp2, m == matchCur ? hlCur : hl);
                }
            }
        }
        visFirst = first; visLast = last;
        for (int i = first; i <= Math.min(last + 1, doc.count - 1); i++) requestBase(i);
        if (first > 0) requestBase(first - 1);
        drawThumb(c);
        int cp = currentPage();
        if (cp != lastPage) { lastPage = cp; setContentDescription("Pagina " + (cp + 1) + " van " + doc.count); }
    }

    private boolean thumbOn() { return doc != null && contentH() > getHeight() * 3 && (dragThumb || SystemClock.uptimeMillis() - lastScroll < 1500); }
    private float trackTop() { return getPaddingTop() + 8 * dp; }
    private float trackH() { return Math.max(1, getHeight() - getPaddingBottom() - 8 * dp - trackTop()); }
    private float thumbH() { return Math.max(44 * dp, trackH() * getHeight() / contentH()); }
    private float thumbY() { float range = maxY() - minY(); return trackTop() + (range <= 0 ? 0 : (sy - minY()) / range) * (trackH() - thumbH()); }

    private void drawThumb(Canvas c) {
        if (!thumbOn()) return;
        float w = 6 * dp, x = getWidth() - w - 4 * dp, y = thumbY();
        tmp.set(x, y, x + w, y + thumbH());
        c.drawRoundRect(tmp, w / 2, w / 2, thumb);
        if (!dragThumb) { ui.removeCallbacks(hideThumb); ui.postDelayed(hideThumb, 1600); }
    }

    private void scrolled() {
        lastScroll = SystemClock.uptimeMillis();
        if (host != null) host.onScrolled(currentPage());
    }

    // ---------- pagina's tekenen op de achtergrond ----------

    private void requestBase(int i) {
        if (bg == null || cache.containsKey(i) || pending.contains(i)) return;
        pending.add(i);
        final int g = gen, page = i;
        final PdfDoc d = doc;
        final float bs = baseScale(i);
        final int bw = Math.max(1, Math.round(pw[i] * bs)), bh = Math.max(1, Math.round(ph[i] * bs));
        final boolean wantLinks = PdfDoc.canText() && links.get(i) == null;
        bg.post(() -> {
            if (g != gen || page < visFirst - 2 || page > visLast + 2) { ui.post(() -> pending.remove(page)); return; }
            Matrix m = new Matrix();
            m.setScale(bw / d.w[page], bh / d.h[page]);
            final Bitmap b = d.render(page, bw, bh, m);
            final List<PdfDoc.Link> ls = wantLinks ? d.links(page) : null;
            ui.post(() -> onBase(g, page, b, ls));
        });
    }

    private void onBase(int g, int page, Bitmap b, List<PdfDoc.Link> ls) {
        pending.remove(page);
        if (g != gen) { if (b != null) b.recycle(); return; }
        if (ls != null) links.put(page, ls);
        if (b == null) return;
        cache.put(page, b);
        // oudste pagina's buiten beeld vergeten (niet recyclen: kan nog in een getekend beeld zitten)
        Iterator<Map.Entry<Integer, Bitmap>> it = cache.entrySet().iterator();
        while (cache.size() > maxCache && it.hasNext()) {
            int p = it.next().getKey();
            if (p < visFirst - 1 || p > visLast + 1) it.remove();
        }
        invalidate();
    }

    /** Scherp bijtekenen nodig: ingezoomd, of een pagina in beeld is verkleind getekend (heel lange pagina). */
    private boolean needDetail() {
        if (z >= 1.3f) return true;
        if (!laidOut) return false;
        for (int i = Math.max(0, visFirst); i <= Math.min(visLast, top.length - 1); i++) if (baseScale(i) * 1.3f < z) return true;
        return false;
    }

    private void scheduleDetail() {
        ui.removeCallbacks(detailJob);
        if (!needDetail()) { if (!tiles.isEmpty()) { tiles = new ArrayList<>(); tileGen++; invalidate(); } return; }
        ui.postDelayed(detailJob, 140);
    }

    /** Het zichtbare stuk van elke pagina opnieuw tekenen op de huidige zoom. */
    private void renderDetail() {
        if (doc == null || bg == null || !laidOut || !needDetail() || scaling || !scroller.isFinished()) return;
        final int g = ++tileGen, lg = gen;
        final float zz = z;
        final PdfDoc d = doc;
        final List<int[]> jobs = new ArrayList<>();
        final List<RectF> rects = new ArrayList<>();
        int vw = getWidth(), vh = getHeight();
        for (int i = pageAt(sy / z); i < top.length && top[i] * z - sy <= vh; i++) {
            float l = ((docW - pw[i]) / 2) * z - sx, t = top[i] * z - sy;
            tmp.set(Math.max(0, l), Math.max(0, t), Math.min(vw, l + pw[i] * z), Math.min(vh, t + ph[i] * z));
            if (tmp.width() < 1 || tmp.height() < 1) continue;
            rects.add(new RectF((tmp.left + sx) / z, (tmp.top + sy) / z, (tmp.right + sx) / z, (tmp.bottom + sy) / z));
            jobs.add(new int[]{i, Math.round(tmp.width()), Math.round(tmp.height())});
        }
        final float kk = k, dw = docW;
        final float[] tops = top, pws = pw;
        bg.post(() -> {
            final List<Tile> out = new ArrayList<>();
            for (int j = 0; j < jobs.size(); j++) {
                if (g != tileGen || lg != gen) return;
                int[] jb = jobs.get(j); RectF r = rects.get(j);
                int p = jb[0];
                float pl = (dw - pws[p]) / 2;
                Matrix m = new Matrix();
                m.setScale(kk * zz, kk * zz);
                m.postTranslate((pl - r.left) * zz, (tops[p] - r.top) * zz);
                Bitmap b = d.render(p, jb[1], jb[2], m);
                if (b != null) out.add(new Tile(p, r, b));
            }
            ui.post(() -> { if (g == tileGen && lg == gen) { tiles = out; invalidate(); } else for (Tile t : out) t.bmp.recycle(); });
        });
    }

    // ---------- aanraken ----------

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (doc == null || !laidOut) return true;
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN && thumbOn() && e.getX() > getWidth() - 40 * dp) {
            float ty = thumbY();
            if (e.getY() >= ty - 20 * dp && e.getY() <= ty + thumbH() + 20 * dp) { dragThumb = true; dragOff = e.getY() - ty; scroller.forceFinished(true); getParent().requestDisallowInterceptTouchEvent(true); return true; }
        }
        if (dragThumb) {
            if (a == MotionEvent.ACTION_MOVE) {
                float f = (e.getY() - dragOff - trackTop()) / Math.max(1, trackH() - thumbH());
                sy = minY() + Math.max(0, Math.min(1, f)) * (maxY() - minY());
                clamp(); scrolled(); invalidate();
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) { dragThumb = false; scrolled(); invalidate(); scheduleDetail(); }
            return true;
        }
        sgd.onTouchEvent(e);
        gd.onTouchEvent(e);
        if ((a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) && scroller.isFinished()) scheduleDetail();
        return true;
    }

    @Override public boolean onDown(MotionEvent e) { scroller.forceFinished(true); scaledThisGesture = false; if (anim != null) anim.cancel(); return true; }
    @Override public void onShowPress(MotionEvent e) { }
    @Override public boolean onSingleTapUp(MotionEvent e) { return false; }
    @Override public void onLongPress(MotionEvent e) { }

    @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
        if (scaling) return false;
        sx += dx; sy += dy; clamp(); scrolled(); invalidate();
        ui.removeCallbacks(detailJob);
        return true;
    }

    @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
        if (scaling) return false;
        scroller.fling(Math.round(sx), Math.round(sy), Math.round(-vx), Math.round(-vy), Math.round(minX()), Math.round(maxX()), Math.round(minY()), Math.round(maxY()));
        postInvalidateOnAnimation();
        return true;
    }

    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            sx = scroller.getCurrX(); sy = scroller.getCurrY(); clamp(); scrolled();
            postInvalidateOnAnimation();
            if (scroller.isFinished()) scheduleDetail();
        }
    }

    private void zoomAround(float fx, float fy, float nz) {
        nz = Math.max(zMin, Math.min(nz, ZMAX));
        float dx = (sx + fx) / z, dy = (sy + fy) / z;
        z = nz; sx = dx * z - fx; sy = dy * z - fy;
        clamp(); invalidate();
    }

    @Override public boolean onScaleBegin(ScaleGestureDetector d) { scaling = true; scaledThisGesture = true; scroller.forceFinished(true); ui.removeCallbacks(detailJob); return true; }
    @Override public boolean onScale(ScaleGestureDetector d) { zoomAround(d.getFocusX(), d.getFocusY(), z * d.getScaleFactor()); scrolled(); return true; }
    @Override public void onScaleEnd(ScaleGestureDetector d) { scaling = false; scheduleDetail(); }

    @Override public boolean onDoubleTap(MotionEvent e) { return true; }
    /** Dubbeltikken zoomt in of terug; dubbeltikken en slepen (snel zoomen) laat de ScaleGestureDetector doen. */
    @Override public boolean onDoubleTapEvent(MotionEvent e) {
        if (e.getActionMasked() != MotionEvent.ACTION_UP || scaledThisGesture) return false;
        final float fx = e.getX(), fy = e.getY(), from = z, to = z < 1.8f ? 2.5f : Math.max(zMin, 1f);
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(220);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(va -> { float f = (Float) va.getAnimatedValue(); zoomAround(fx, fy, from + (to - from) * f); if (f >= 1f) scheduleDetail(); });
        anim.start();
        return true;
    }

    @Override public boolean onSingleTapConfirmed(MotionEvent e) {
        PdfDoc.Link l = linkAt(e.getX(), e.getY());
        if (l != null && host != null) { host.onLink(l); return true; }
        if (host != null) host.onTap();
        return true;
    }

    private PdfDoc.Link linkAt(float x, float y) {
        if (!laidOut) return null;
        float dx = (x + sx) / z, dy = (y + sy) / z;
        int i = pageAt(dy);
        if (i >= top.length || dy < top[i] || dy > top[i] + ph[i]) return null;
        List<PdfDoc.Link> ls = links.get(i);
        if (ls == null) return null;
        float px = (dx - (docW - pw[i]) / 2) / k, py = (dy - top[i]) / k, slop = 6 * dp / (k * z);
        for (PdfDoc.Link l : ls) for (RectF r : l.bounds) if (px >= r.left - slop && px <= r.right + slop && py >= r.top - slop && py <= r.bottom + slop) return l;
        return null;
    }

    /** Stopt het tekenwerk (bij sluiten). */
    void release() {
        gen++; tileGen++;
        ui.removeCallbacks(detailJob); ui.removeCallbacks(hideThumb);
        if (anim != null) anim.cancel();
        cache.clear(); tiles = new ArrayList<>();
        doc = null;
    }
}
