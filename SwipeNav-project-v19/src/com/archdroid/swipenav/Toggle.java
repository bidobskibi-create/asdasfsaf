package com.archdroid.swipenav;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** A small pill switch, drawn directly. Taps are handled by the row around it. */
class Toggle extends View {
    interface Listener { void on(boolean v); }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();
    private final Listener listener;
    private boolean on;
    private float pos;

    Toggle(Context c, boolean on, Listener l) {
        super(c);
        this.on = on;
        this.pos = on ? 1f : 0f;
        this.listener = l;
    }

    void toggle() {
        on = !on;
        ValueAnimator a = ValueAnimator.ofFloat(pos, on ? 1f : 0f);
        a.setDuration(140);
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator v) {
                pos = (Float) v.getAnimatedValue();
                invalidate();
            }
        });
        a.start();
        if (listener != null) listener.on(on);
    }

    private static int mix(int a, int b, float t) {
        return Color.argb(255,
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        p.setStyle(Paint.Style.FILL);
        p.setColor(mix(0xFF3A3D46, Ui.ACCENT, pos));
        rf.set(0, 0, w, h);
        c.drawRoundRect(rf, h / 2f, h / 2f, p);
        float r = h / 2f - 3 * getResources().getDisplayMetrics().density;
        float cx = h / 2f + pos * (w - h);
        p.setColor(0xFFFFFFFF);
        c.drawCircle(cx, h / 2f, r, p);
    }
}
