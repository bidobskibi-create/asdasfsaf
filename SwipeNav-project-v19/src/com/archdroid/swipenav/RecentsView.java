package com.archdroid.swipenav;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Recents screen with four looks: Classic, MIUI, iOS, Windows Phone.
 *  - Classic / iOS: horizontal strip of cards. MIUI / Windows Phone: 2-column grid that scrolls vertically.
 *  - ONE View that draws everything itself: no child views, no layouts, no screenshots.
 *  - Only app icons are kept in memory (a handful) and all of it is freed when the window is removed.
 *  - Redraws only while something is moving (spring physics); tasks are read on a background thread.
 *  - Every look is bouncy by default except iOS / Windows Phone, which use softer springs. All of it is configurable.
 * Close an app with its x button or by swiping its card away; "Clear all" closes everything.
 */
public class RecentsView extends View {
    static final int CLASSIC = 0, MIUI = 1, IOS = 2, WP = 3;
    private static final int[] DEF_ACCENT = {0, 0xFFF7821B, 0xFF0A84FF, 0xFF0078D7};
    private static final int[] DEF_ANIM = {1, 1, 0, 0};            // 0 smooth, 1 bouncy, 2 extra bouncy
    private static final int[] DEF_CARD = {0, 5, 3, 4};            // 0 dark 1 black 2 light 3 glass 4 accent 5 gray
    private static final float[] BASE_RAD = {18f, 3f, 12f, 0f};    // dp
    private static final boolean[] DEF_CLOSE = {true, true, false, true};
    private static final boolean[] DEF_PILL = {true, false, false, false};
    private static final boolean[] DEF_DEPTH = {true, false, true, false};
    private static final int[] DEF_DIM = {70, 80, 70, 100};

    private final float K, DAMP;                       // spring stiffness / damping
    private final float cardScale, roundMul, iconMul, posPct, cardA;
    private final int dimA, cardFill, cardText, cardStroke, closeFill, closeX;
    private final boolean showLabels, showClose, showPill, depthFx, snapFx;
    private final int style;
    private final boolean grid;

    private static final class Item {
        int id;
        Intent intent;
        String pkg, label, shown;
        Drawable icon;
        float x, vx, tx;                 // slot x
        float ry, vry, rty;              // slot y (content coordinate)
        float y, vy, ty;                 // vertical offset (rise / fly up)
        float ox, vox, tox;              // horizontal offset (swipe away in grid)
        float s, vs, ts = 1f;            // scale
        float fx = 1f, vfx, tfx = 1f;    // horizontal flip (Windows turnstile)
        float a, va, ta = 1f;            // alpha
        float press, vpress, tpress;
        int delay;
        int flyDelay = -1;
        boolean gone;
    }

    private final Context ctx;
    private final Runnable onClose;
    private final Handler h = new Handler();
    private final float d = getResources().getDisplayMetrics().density;
    private final ArrayList<Item> items = new ArrayList<Item>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface tfMed = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Typeface tfLight = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private final RectF r = new RectF();
    private final int accent;
    private final boolean haptic, showRam;
    private final int touchSlop;

    private int W, H, cardW, cardH, gap, pad, stripTop, gridTop, bottomReserve, live;
    private float sx, svx, fling;
    private float springTarget = Float.NaN;
    private boolean dragging, springing;
    private float scrim, scrimT = 1f;
    private boolean loaded, closing, finished;
    private float clearPress;
    private long ramFree, ramTotal;
    private String ramStr;

    private static final int M_NONE = 0, M_SCROLL = 1, M_DISMISS = 2;
    private int mode;
    private float downX, downY, lastX, lastY;
    private Item touched;
    private boolean touchedClear, longFired;
    private VelocityTracker vt;
    private final Runnable longPress = new Runnable() {
        public void run() {
            if (touched == null || mode != M_NONE || closing) return;
            longFired = true;
            haptic();
            try {   // long-press a card = that app's info page (force stop, uninstall, ...)
                ctx.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + touched.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                requestClose();
            } catch (Throwable t) { longFired = false; }
        }
    };

    public RecentsView(Context c, Runnable onClose) {
        super(c);
        ctx = c;
        this.onClose = onClose;
        SharedPreferences sp = c.getSharedPreferences(SwipeNavService.PREFS, Context.MODE_PRIVATE);
        int st = Math.max(0, Math.min(3, sp.getInt("rc_style", CLASSIC)));
        style = st;
        grid = st == MIUI || st == WP;
        int acc = sp.getInt("panel_accent", 0);
        accent = acc != 0 ? acc : (st == CLASSIC ? Ui.ACCENT : DEF_ACCENT[st]);
        haptic = sp.getBoolean(SwipeNavService.KEY_HAPTIC, true);
        showRam = sp.getBoolean("recents_ram", true);
        int anim = sp.contains("rc_bounce") ? sp.getInt("rc_bounce", 1) : DEF_ANIM[st];
        K = anim == 0 ? 0.20f : (anim == 2 ? 0.13f : 0.16f);
        DAMP = anim == 0 ? 0.62f : (anim == 2 ? 0.82f : 0.76f);
        cardScale = Math.max(0.7f, Math.min(1.3f, sp.getInt("rc_size", 100) / 100f));
        roundMul = Math.max(0f, Math.min(1.5f, sp.getInt("rc_round", 100) / 100f));
        iconMul = Math.max(0.6f, Math.min(1.4f, sp.getInt("rc_icon", 100) / 100f));
        posPct = Math.max(0, Math.min(100, sp.getInt("rc_pos", 30)));
        dimA = (int) (255 * Math.max(0, Math.min(100, sp.getInt("rc_dim", DEF_DIM[st]))) / 100f);
        showLabels = sp.getBoolean("rc_labels", true);
        showClose = sp.getBoolean("rc_close", DEF_CLOSE[st]);
        showPill = sp.getBoolean("rc_pill", DEF_PILL[st]);
        depthFx = sp.getBoolean("rc_depth", DEF_DEPTH[st]);
        snapFx = sp.getBoolean("rc_snap", true);
        int scheme = sp.contains("rc_card") ? sp.getInt("rc_card", 0) : DEF_CARD[st];
        int fill = 0xFF1E2028, txt = 0xFFFFFFFF, stroke = 0x22FFFFFF, cf = 0xFFFFFFFF, cx = 0xFFFFFFFF;
        float ca = 1f;
        switch (scheme) {
            case 1: fill = 0xFF000000; break;
            case 2: fill = 0xFFF2F3F7; txt = 0xFF16181F; stroke = 0x33000000; cf = 0xFF000000; cx = 0xFF16181F; break;
            case 3: ca = 0.72f; break;
            case 4: fill = accent; stroke = 0x00000000; break;
            case 5: fill = 0xFF2B2B2B; stroke = 0x14FFFFFF; break;
            default: break;
        }
        cardFill = fill; cardText = txt; cardStroke = stroke; closeFill = cf; closeX = cx; cardA = ca;
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        text.setTypeface(tfMed);
        text.setTextAlign(Paint.Align.CENTER);
        final int max = Math.max(4, Math.min(12, sp.getInt("recents_max", 8)));
        new Thread(new Runnable() {
            public void run() {
                final ArrayList<Item> list = load(max);
                post(new Runnable() { public void run() { setItems(list); } });
            }
        }).start();
    }

    // ---------- loading ----------

    private void readRam(ActivityManager am) {
        try {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            ramFree = mi.availMem; ramTotal = mi.totalMem;
            ramStr = (ramFree / (1024 * 1024)) + " MB free";
        } catch (Throwable t) { }
    }

    /** After closing apps: wait a moment, re-read free RAM so the number visibly goes up. */
    private void refreshRamLater() {
        if (!showRam) return;
        new Thread(new Runnable() {
            public void run() {
                try { Thread.sleep(900); } catch (InterruptedException ignored) { }
                readRam((ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE));
                postInvalidate();
            }
        }).start();
    }

    private ArrayList<Item> load(int max) {
        ArrayList<Item> out = new ArrayList<Item>();
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            PackageManager pm = ctx.getPackageManager();
            readRam(am);
            List<ActivityManager.RecentTaskInfo> tasks =
                    am.getRecentTasks(max * 2 + 4, ActivityManager.RECENT_IGNORE_UNAVAILABLE);
            HashSet<String> seen = new HashSet<String>();
            if (tasks != null) for (ActivityManager.RecentTaskInfo t : tasks) {
                if (out.size() >= max) break;
                if (t.baseIntent == null) continue;
                Intent in = new Intent(t.baseIntent);
                if (t.origActivity != null) in.setComponent(t.origActivity);
                if ((in.getFlags() & Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS) != 0) continue;
                if (in.hasCategory(Intent.CATEGORY_HOME)) continue;
                ActivityInfo ai = in.resolveActivityInfo(pm, 0);
                if (ai == null) continue;
                String pkg = ai.packageName;
                if (Actions.isProtected(ctx, pkg) || !seen.add(pkg)) continue;
                in.setFlags((in.getFlags() & ~Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) | Intent.FLAG_ACTIVITY_NEW_TASK);
                Item it = new Item();
                it.id = t.persistentId;
                it.intent = in;
                it.pkg = pkg;
                it.label = String.valueOf(ai.loadLabel(pm));
                try { it.icon = ai.loadIcon(pm).mutate(); } catch (Throwable e) { continue; }
                out.add(it);
            }
        } catch (Throwable t) { }
        return out;
    }

    private void setItems(ArrayList<Item> list) {
        if (finished) return;
        items.clear();
        items.addAll(list);
        loaded = true;
        relayout();
        sx = lo();
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            it.x = it.tx; it.ry = it.rty;
            switch (style) {
                case IOS:       // cards glide in from the right
                    it.x = it.tx + W * 0.55f; it.s = 0.9f; it.a = 0f; it.delay = 1 + i * 2;
                    break;
                case WP:        // turnstile: tiles flip in, one after another
                    it.fx = 0f; it.a = 1f; it.s = 1f; it.delay = 2 + i * 2;
                    break;
                case MIUI:
                    it.y = 60 * d; it.s = 0.78f; it.a = 0f; it.delay = 2 + i * 2;
                    break;
                default:        // classic: rise up, staggered
                    it.y = 70 * d; it.s = 0.72f; it.a = 0f; it.delay = 2 + i * 3;
            }
        }
        fit();
        postInvalidateOnAnimation();
    }

    // ---------- layout ----------

    @Override protected void onSizeChanged(int w, int hh, int ow, int oh) {
        W = w; H = hh;
        gridTop = (int) ((style == WP ? 78 : 64) * d);
        if (grid) {
            pad = (int) ((style == WP ? 16 : 12) * d);
            gap = (int) ((style == WP ? 8 : 10) * d);
            cardW = (W - 2 * pad - gap) / 2;
            cardH = (int) (cardW * (style == WP ? 1.0f : 0.86f) * cardScale);
            bottomReserve = (int) ((style == WP ? 72 : 88) * d);
        } else if (style == IOS) {
            cardW = (int) (0.54f * W * cardScale);
            cardH = Math.min((int) (0.66f * H), (int) (cardW * 1.62f));
            gap = (int) (18 * d); pad = (W - cardW) / 2; bottomReserve = 0;
        } else {
            cardW = (int) (124 * d * cardScale); cardH = (int) (160 * d * cardScale);
            gap = (int) (14 * d); pad = (int) (24 * d); bottomReserve = 0;
        }
        int maxTop = Math.max((int) (80 * d), H - cardH - (int) (110 * d));
        stripTop = Math.max((int) (80 * d), Math.min(maxTop, (int) (H * posPct / 100f)));
        relayout();
        fit();
    }

    private void fit() {
        if (W == 0) return;
        text.setTextSize(13 * d);
        float room = style == IOS ? cardW - 44 * d : cardW - 16 * d;
        for (Item it : items) {
            String l = style == WP ? it.label.toLowerCase() : it.label;
            it.shown = TextUtils.ellipsize(l, text, room, TextUtils.TruncateAt.END).toString();
        }
    }

    /** Slots only count cards that are not flying out, so neighbours slide into the gap at once. */
    private void relayout() {
        int n = 0;
        for (Item it : items) {
            if (it.gone) continue;
            if (grid) {
                it.tx = pad + (n % 2) * (cardW + gap);
                it.rty = (n / 2) * (cardH + gap);
            } else {
                it.tx = pad + n * (cardW + gap);
                it.rty = stripTop;
            }
            n++;
        }
        live = n;
    }

    private int rows() { return (live + 1) / 2; }
    private float totalMain() {
        if (grid) return rows() * cardH + Math.max(0, rows() - 1) * gap + 16 * d;
        return pad * 2 + live * cardW + Math.max(0, live - 1) * gap;
    }
    private float viewMain() { return grid ? Math.max(1, H - gridTop - bottomReserve) : W; }
    private float lo() {
        if (grid) return 0f;
        float t = totalMain();
        return t <= W ? -(W - t) / 2f : 0f;
    }
    private float hi() {
        float t = totalMain();
        if (grid) return Math.max(0f, t - viewMain());
        return t <= W ? -(W - t) / 2f : t - W;
    }

    // card position on screen (without the dismiss offsets)
    private float baseLeft(Item it) { return grid ? it.x : it.x - sx; }
    private float baseTop(Item it) { return grid ? gridTop + it.ry - sx : it.ry; }

    private Item firstLive() {
        for (int i = 0; i < items.size(); i++) if (!items.get(i).gone) return items.get(i);
        return null;
    }

    // ---------- actions ----------

    private void haptic() { if (haptic) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); }

    /** dir: -1 / 1 = swiped away sideways (grid), 0 = closed with x or Clear all. */
    private void fly(final Item it, int dir) {
        if (it.gone) return;
        it.gone = true;
        it.ta = 0f; it.ts = 0.88f;
        if (grid && dir != 0) it.tox = dir * (W + cardW);
        else if (style == WP) it.tfx = 0f;                                   // flips away
        else it.ty = -(baseTop(it) + cardH + 60 * d);                         // flies up
        new Thread(new Runnable() { public void run() { Actions.closeTask(ctx, it.id, it.pkg); } }).start();
        haptic();
        relayout();
        refreshRamLater();
        if (live == 0) h.postDelayed(new Runnable() { public void run() { requestClose(); } }, 650);
    }

    private void clearAll() {
        int k = 0;
        for (Item it : items) if (!it.gone) it.flyDelay = (k++) * 3;
        haptic();
        refreshRamLater();
        postInvalidateOnAnimation();
    }

    private void launch(Item it) {
        it.ts = 1.14f; it.ta = 0f;
        Actions.launchTask(ctx, it.id, it.intent);
        requestClose();
    }

    public void requestClose() {
        if (closing) return;
        closing = true;
        scrimT = 0f;
        int i = 0;
        for (Item it : items) {
            if (it.gone || it.ta == 0f) continue;
            if (style == WP) it.tfx = 0f; else it.ty = 90 * d;
            it.ta = 0f; it.ts = 0.86f; it.delay = i++;
        }
        h.postDelayed(new Runnable() { public void run() { finish(); } }, 700);   // safety net
        postInvalidateOnAnimation();
    }

    private void finish() {
        if (finished) return;
        finished = true;
        h.removeCallbacksAndMessages(null);
        if (onClose != null) onClose.run();
    }

    // ---------- touch ----------

    private Item hitCard(float x, float y) {
        if (grid && (y < gridTop || y > H - bottomReserve)) return null;
        for (int i = items.size() - 1; i >= 0; i--) {
            Item it = items.get(i);
            if (it.gone) continue;
            float l = baseLeft(it), t = baseTop(it);
            if (x >= l && x <= l + cardW && y >= t && y <= t + cardH) return it;
        }
        return null;
    }

    private float closeInset() { return (style == WP ? 20 : (style == MIUI ? 16 : 18)) * d; }

    private boolean hitClose(Item it, float x, float y) {
        if (!showClose) return false;
        float cx = baseLeft(it) + cardW - closeInset(), cy = baseTop(it) + closeInset();
        float dx = x - cx, dy = y - cy;
        return dx * dx + dy * dy <= (26 * d) * (26 * d);
    }

    private boolean hasClear() { return live > 0 && style != IOS; }
    private float clearY() { return grid ? H - (style == WP ? 36 : 44) * d : stripTop + cardH + 44 * d; }

    private boolean hitClear(float x, float y) {
        if (!hasClear()) return false;
        return Math.abs(x - W / 2f) <= 66 * d && Math.abs(y - clearY()) <= 26 * d;
    }

    private boolean tapOutside(float y) {
        if (grid) return y < gridTop - 8 * d;
        return !(y >= stripTop - 70 * d && y <= stripTop + cardH + 80 * d);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (closing || !loaded) {
            if (!loaded && e.getActionMasked() == MotionEvent.ACTION_UP) requestClose();
            return true;
        }
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(e);
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = x; downY = lastY = y; mode = M_NONE;
                fling = 0; springing = false; springTarget = Float.NaN;
                touched = hitCard(x, y);
                touchedClear = touched == null && hitClear(x, y);
                longFired = false;
                h.removeCallbacks(longPress);
                if (touched != null) { touched.tpress = 1f; h.postDelayed(longPress, 550); }
                if (touchedClear) clearPress = 1f;
                break;
            case MotionEvent.ACTION_MOVE: {
                float dx = x - downX, dy = y - downY;
                if (mode == M_NONE && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) h.removeCallbacks(longPress);
                if (mode == M_NONE) {
                    boolean scrollMove = grid ? (Math.abs(dy) > touchSlop && Math.abs(dy) > Math.abs(dx))
                                              : (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy));
                    boolean dismissMove = touched != null && (grid
                            ? (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy))
                            : (dy < -touchSlop && Math.abs(dy) > Math.abs(dx)));
                    if (scrollMove) {
                        mode = M_SCROLL; dragging = true;
                        if (touched != null) touched.tpress = 0f;
                        touchedClear = false; clearPress = 0f;
                    } else if (dismissMove) {
                        mode = M_DISMISS;
                        touched.tpress = 0f;
                    }
                }
                if (mode == M_SCROLL) {
                    float mv = grid ? lastY - y : lastX - x;
                    if (sx < lo() || sx > hi()) mv *= 0.4f;       // rubber band
                    sx += mv;
                } else if (mode == M_DISMISS && touched != null) {
                    if (grid) {
                        touched.ox = dx; touched.tox = dx; touched.vox = 0;
                        touched.a = Math.max(0.15f, 1f - Math.abs(dx) / (cardW * 1.1f));
                    } else {
                        float off = Math.min(dy, 12 * d);
                        touched.y = off; touched.ty = off; touched.vy = 0;
                        touched.a = Math.max(0.15f, 1f - Math.max(0f, -off) / (cardH * 1.3f));
                    }
                    touched.ta = 1f;
                }
                lastX = x; lastY = y;
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                h.removeCallbacks(longPress);
                vt.computeCurrentVelocity(1000);
                float vx = vt.getXVelocity(), vy = vt.getYVelocity();
                vt.recycle(); vt = null;
                dragging = false;
                for (Item it : items) it.tpress = 0f;
                clearPress = 0f;
                if (mode == M_SCROLL) {
                    float f = -(grid ? vy : vx) / 60f, lim = 38 * d;
                    fling = Math.max(-lim, Math.min(lim, f));
                } else if (mode == M_DISMISS && touched != null) {
                    boolean away = grid ? (Math.abs(touched.ox) > cardW * 0.35f || Math.abs(vx) > 900 * d)
                                        : (touched.y < -cardH * 0.33f || vy < -900 * d);
                    if (up && away) fly(touched, grid ? (touched.ox < 0 || (touched.ox == 0 && vx < 0) ? -1 : 1) : 0);
                    else {      // springs back, bouncing
                        touched.ty = 0f; touched.tox = 0f; touched.ta = 1f; touched.a = Math.max(touched.a, 0.6f);
                    }
                } else if (up && mode == M_NONE && !longFired) {
                    if (touched != null) {
                        if (hitClose(touched, x, y)) fly(touched, 0); else launch(touched);
                    } else if (touchedClear && hitClear(x, y)) {
                        clearAll();
                    } else if (tapOutside(y)) {
                        requestClose();
                    }
                }
                touched = null; touchedClear = false; mode = M_NONE;
                break;
            }
        }
        postInvalidateOnAnimation();
        return true;
    }

    // ---------- physics ----------

    /** Nearest card centre to the middle of the screen, kept inside the scroll bounds (strip looks only). */
    private float snapTarget() {
        float lo = lo(), hi = hi();
        if (live == 0 || hi <= lo) return lo;
        float centre = sx + W / 2f, best = lo, bd = Float.MAX_VALUE;
        for (int i = 0; i < live; i++) {
            float c = pad + i * (cardW + gap) + cardW / 2f;
            float t = Math.max(lo, Math.min(hi, c - W / 2f));
            float dd = Math.abs(c - centre);
            if (dd < bd) { bd = dd; best = t; }
        }
        return best;
    }

    private boolean stepScroll() {
        if (dragging) return false;
        float lo = lo(), hi = hi();
        boolean out = sx < lo - 0.5f || sx > hi + 0.5f;
        if (snapFx && !grid && !out && !springing && Math.abs(fling) <= 1.6f
                && Math.abs(sx - snapTarget()) > 0.6f && live > 0) {
            springing = true; springTarget = snapTarget(); svx = fling; fling = 0f;
        }
        if (out || springing) {
            float t;
            if (out) { t = sx < lo ? lo : hi; springTarget = Float.NaN; }
            else t = Float.isNaN(springTarget) ? sx : springTarget;
            if (!springing) { springing = true; svx = fling * 0.6f; fling = 0f; }
            svx = (svx + (t - sx) * 0.14f) * 0.78f;
            sx += svx;
            if (Math.abs(sx - t) < 0.3f && Math.abs(svx) < 0.3f) {
                sx = Math.max(lo, Math.min(hi, t)); springing = false; svx = 0f; springTarget = Float.NaN;
                return false;
            }
            return true;
        }
        if (Math.abs(fling) > 0.6f) { sx += fling; fling *= 0.945f; return true; }
        fling = 0f;
        return false;
    }

    /** One spring step for every channel of a card. Returns true while it is still moving. */
    private boolean step(Item it) {
        if (it.delay > 0) { it.delay--; return true; }
        if (it.flyDelay >= 0) {
            if (it.flyDelay-- == 0) { it.flyDelay = -1; fly(it, 0); }
            return true;
        }
        it.vx = (it.vx + (it.tx - it.x) * K) * DAMP; it.x += it.vx;
        it.vry = (it.vry + (it.rty - it.ry) * K) * DAMP; it.ry += it.vry;
        it.vy = (it.vy + (it.ty - it.y) * K) * DAMP; it.y += it.vy;
        it.vox = (it.vox + (it.tox - it.ox) * K) * DAMP; it.ox += it.vox;
        it.vs = (it.vs + (it.ts - it.s) * K) * DAMP; it.s += it.vs;
        it.vfx = (it.vfx + (it.tfx - it.fx) * K) * DAMP; it.fx += it.vfx;
        it.va = (it.va + (it.ta - it.a) * 0.12f) * 0.7f; it.a += it.va;
        it.vpress = (it.vpress + (it.tpress - it.press) * 0.22f) * 0.7f; it.press += it.vpress;
        if (it.a < 0f) it.a = 0f;
        if (it.a > 1f) it.a = 1f;
        if (it.fx < 0f) it.fx = 0f;
        return Math.abs(it.tx - it.x) > 0.3f || Math.abs(it.vx) > 0.05f
                || Math.abs(it.rty - it.ry) > 0.3f || Math.abs(it.vry) > 0.05f
                || Math.abs(it.ty - it.y) > 0.3f || Math.abs(it.vy) > 0.05f
                || Math.abs(it.tox - it.ox) > 0.3f || Math.abs(it.vox) > 0.05f
                || Math.abs(it.ts - it.s) > 0.004f || Math.abs(it.vs) > 0.002f
                || Math.abs(it.tfx - it.fx) > 0.004f || Math.abs(it.vfx) > 0.002f
                || Math.abs(it.ta - it.a) > 0.01f
                || Math.abs(it.tpress - it.press) > 0.01f || Math.abs(it.vpress) > 0.01f;
    }

    // ---------- drawing ----------

    private void drawChrome(Canvas c, int sa) {
        float rise = (1f - scrim) * 14 * d;
        float base;                                 // baseline of the title / RAM text
        text.setColor(0xFFFFFFFF); text.setAlpha(sa);
        if (style == CLASSIC) base = stripTop - 26 * d;
        else if (style == MIUI) base = 36 * d;
        else if (style == WP) base = 58 * d;
        else base = 36 * d;
        if (style != IOS) {
            text.setTextAlign(Paint.Align.LEFT);
            if (style == WP) { text.setTypeface(tfLight); text.setTextSize(30 * d); }
            else text.setTextSize((style == MIUI ? 18 : 20) * d);
            c.drawText(style == WP ? "recent apps" : "Recent apps", pad, base + rise, text);
            text.setTypeface(tfMed);
            if (style != WP) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(accent); paint.setAlpha(sa);
                r.set(pad, base + 8 * d, pad + 26 * d, base + 12 * d);
                c.drawRoundRect(r, 2 * d, 2 * d, paint);
            }
        }
        if (showRam && ramStr != null && ramTotal > 0) {
            text.setTextAlign(Paint.Align.RIGHT);
            text.setTextSize(12 * d);
            text.setColor(0xFFB8BCC8); text.setAlpha(sa);
            c.drawText(style == WP ? ramStr.toLowerCase() : ramStr, W - pad, base + rise, text);
            float bw = 92 * d, bx = W - pad - bw, by = base + 9 * d;
            float used = 1f - Math.min(1f, (float) ramFree / ramTotal);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFFFFFFF); paint.setAlpha((int) (40 * scrim));
            r.set(bx, by, bx + bw, by + 3 * d);
            c.drawRoundRect(r, style == WP ? 0 : 2 * d, style == WP ? 0 : 2 * d, paint);
            paint.setColor(accent); paint.setAlpha(sa);
            r.set(bx, by, bx + bw * used, by + 3 * d);
            c.drawRoundRect(r, style == WP ? 0 : 2 * d, style == WP ? 0 : 2 * d, paint);
        }
        text.setTextAlign(Paint.Align.CENTER);
    }

    private void drawCard(Canvas c, Item it) {
        float left = baseLeft(it) + it.ox, top = baseTop(it) + it.y;
        if (left > W || left + cardW < 0 || top > H || top + cardH < 0) return;
        if (it.fx < 0.02f) return;
        float cx = left + cardW / 2f, cy = top + cardH / 2f;
        float dist = depthFx && !grid ? Math.min(1f, Math.abs(cx - W / 2f) / (W * 0.62f)) : 0f;   // 0 centre .. 1 edge
        float sc = it.s * (1f - 0.07f * it.press) * (1f - 0.09f * dist);
        int al = (int) (255 * it.a * (1f - 0.32f * dist));
        float rad = Math.min(BASE_RAD[style] * d * roundMul, Math.min(cardW, cardH) / 2f);
        c.save();
        c.scale(sc * it.fx, sc, cx, cy);

        // card body
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(cardFill); paint.setAlpha((int) (al * cardA));
        r.set(left, top, left + cardW, top + cardH);
        c.drawRoundRect(r, rad, rad, paint);
        int sa = cardStroke >>> 24;
        boolean cur = style == CLASSIC && it == firstLive();
        if (cur || sa > 0) {
            paint.setStyle(Paint.Style.STROKE);
            if (cur) { paint.setColor(accent); paint.setAlpha((int) (150 * it.a)); paint.setStrokeWidth(2 * d); }
            else { paint.setColor(cardStroke); paint.setAlpha((int) (sa * it.a)); paint.setStrokeWidth(1f); }
            c.drawRoundRect(r, rad, rad, paint);
            paint.setStrokeWidth(1f);
        }

        float cs = Math.min(1.25f, cardScale);
        int isz, ix, iy;
        switch (style) {
            case IOS: {
                // name + small icon above the card (like the iOS app switcher), big icon on the card
                int small = (int) (26 * d);
                if (showLabels) {
                    it.icon.setBounds((int) left, (int) (top - 38 * d), (int) left + small, (int) (top - 38 * d) + small);
                    it.icon.setAlpha(al);
                    it.icon.draw(c);
                    text.setTextAlign(Paint.Align.LEFT);
                    text.setTextSize(13 * d); text.setColor(0xFFFFFFFF); text.setAlpha(al);
                    c.drawText(it.shown == null ? it.label : it.shown, left + small + 8 * d, top - 38 * d + small * 0.68f, text);
                    text.setTextAlign(Paint.Align.CENTER);
                }
                isz = (int) (Math.min(cardW * 0.46f, 84 * d) * iconMul);
                ix = (int) (cx - isz / 2f); iy = (int) (cy - isz / 2f);
                it.icon.setBounds(ix, iy, ix + isz, iy + isz);
                it.icon.setAlpha(al);
                it.icon.draw(c);
                break;
            }
            case WP: {
                isz = (int) (56 * d * iconMul * cs);
                ix = (int) (cx - isz / 2f); iy = (int) (cy - isz / 2f - 8 * d);
                it.icon.setBounds(ix, iy, ix + isz, iy + isz);
                it.icon.setAlpha(al);
                it.icon.draw(c);
                if (showLabels) {
                    text.setTextAlign(Paint.Align.LEFT);
                    text.setTextSize(13 * d); text.setColor(cardText); text.setAlpha(al);
                    c.drawText(it.shown == null ? it.label : it.shown, left + 10 * d, top + cardH - 12 * d, text);
                    text.setTextAlign(Paint.Align.CENTER);
                }
                break;
            }
            default: {      // classic + MIUI: icon on top, name below
                isz = (int) (58 * d * iconMul * cs * (style == MIUI ? 0.92f : 1f));
                ix = (int) (cx - isz / 2f);
                iy = (int) (showLabels ? top + (style == MIUI ? 0.18f : 0.21f) * cardH : top + (cardH - isz) / 2f);
                it.icon.setBounds(ix, iy, ix + isz, iy + isz);
                it.icon.setAlpha(al);
                it.icon.draw(c);
                if (showLabels) {
                    text.setTextSize(13 * d); text.setColor(cardText); text.setAlpha(al);
                    c.drawText(it.shown == null ? it.label : it.shown, cx, iy + isz + 24 * d, text);
                }
                if (showPill) {
                    paint.setStyle(Paint.Style.FILL); paint.setColor(accent); paint.setAlpha(al);
                    r.set(cx - 12 * d, top + cardH - 16 * d, cx + 12 * d, top + cardH - 13 * d);
                    c.drawRoundRect(r, 2 * d, 2 * d, paint);
                }
            }
        }

        // close button (look depends on the style)
        if (showClose) {
            float bx = left + cardW - closeInset(), by = top + closeInset(), br = 12 * d, q = 4.2f * d;
            if (style == WP) {
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.6f * d);
                paint.setColor(closeX); paint.setAlpha((int) (230 * it.a));
                c.drawCircle(bx, by, br, paint);
            } else if (style != MIUI) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(closeFill); paint.setAlpha((int) (46 * it.a));
                c.drawCircle(bx, by, br, paint);
            }
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2 * d); paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(closeX); paint.setAlpha((int) (230 * it.a));
            if (style == MIUI) q = 5f * d;
            c.drawLine(bx - q, by - q, bx + q, by + q, paint);
            c.drawLine(bx - q, by + q, bx + q, by - q, paint);
            paint.setStrokeCap(Paint.Cap.BUTT);
            paint.setStrokeWidth(1f);
        }
        c.restore();
    }

    private void drawClear(Canvas c, int sa) {
        float cy = clearY(), hw = (style == WP ? 70 : 64) * d, hh = (style == WP ? 22 : 20) * d;
        float s = 1f - 0.06f * clearPress;
        c.save(); c.scale(s, s, W / 2f, cy);
        r.set(W / 2f - hw, cy - hh, W / 2f + hw, cy + hh);
        if (style == MIUI) {                        // solid accent button
            paint.setStyle(Paint.Style.FILL); paint.setColor(accent); paint.setAlpha(sa);
            c.drawRoundRect(r, 3 * d, 3 * d, paint);
            text.setColor(0xFFFFFFFF); text.setAlpha(sa);
            text.setTextSize(15 * d);
            c.drawText("Clear all", W / 2f, cy + 5.5f * d, text);
        } else if (style == WP) {                   // outlined, lowercase
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.6f * d);
            paint.setColor(0xFFFFFFFF); paint.setAlpha(sa);
            c.drawRect(r, paint);
            paint.setStrokeWidth(1f);
            text.setColor(0xFFFFFFFF); text.setAlpha(sa);
            text.setTextSize(15 * d);
            c.drawText("clear all", W / 2f, cy + 5.5f * d, text);
        } else {                                    // classic
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFFFFFFF); paint.setAlpha((int) (34 * scrim));
            c.drawRoundRect(r, hh, hh, paint);
            text.setColor(accent); text.setAlpha(sa);
            text.setTextSize(15 * d);
            c.drawText("Clear all", W / 2f, cy + 5.5f * d, text);
        }
        c.restore();
    }

    @Override protected void onDraw(Canvas c) {
        boolean busy = false;
        scrim += (scrimT - scrim) * 0.16f;
        if (Math.abs(scrimT - scrim) > 0.01f) busy = true; else scrim = scrimT;
        c.drawColor(((int) (scrim * dimA)) << 24);

        busy |= stepScroll();
        int sa = (int) (255 * scrim);
        if (loaded && !closing || scrim > 0.05f) drawChrome(c, sa);

        for (int i = 0; i < items.size(); i++) busy |= step(items.get(i));

        if (grid) { c.save(); c.clipRect(0, gridTop - 4 * d, W, H - bottomReserve); }
        for (int i = 0; i < items.size(); i++) if (items.get(i).a >= 0.01f) drawCard(c, items.get(i));
        if (grid) c.restore();

        if (loaded && live == 0 && !closing) {
            text.setTextSize(16 * d); text.setColor(0xFFAAAAAA); text.setAlpha(sa);
            c.drawText(style == WP ? "no recent apps" : "No recent apps", W / 2f,
                    grid ? H / 2f : stripTop + cardH / 2f, text);
        }
        if (hasClear() && !closing) drawClear(c, sa);

        // remove cards that finished flying out
        for (int i = items.size() - 1; i >= 0; i--) {
            Item it = items.get(i);
            if (it.gone && it.a < 0.03f) { items.remove(i); busy = true; }
        }
        if (busy) postInvalidateOnAnimation();
        else if (closing) post(new Runnable() { public void run() { finish(); } });
    }

    @Override protected void onDetachedFromWindow() {
        h.removeCallbacksAndMessages(null);
        if (vt != null) { vt.recycle(); vt = null; }
        finished = true;
        items.clear();       // drop icon references so memory is freed at once
        super.onDetachedFromWindow();
    }
}
