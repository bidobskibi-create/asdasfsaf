package com.archdroid.swipenav;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** A thin slider with an accent fill and a round knob, drawn directly. */
class Slider extends View {
    interface Listener { void on(int value, boolean fromUser); }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();
    private final int min, max;
    private final Listener listener;
    private final float d = getResources().getDisplayMetrics().density;
    private int value;

    Slider(Context c, int min, int max, int value, Listener l) {
        super(c);
        this.min = min;
        this.max = max;
        this.value = Math.max(min, Math.min(max, value));
        this.listener = l;
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), pad = 11 * d;
        float f = max > min ? (value - min) / (float) (max - min) : 0f;
        float x = pad + f * (w - 2 * pad), cy = h / 2f, th = 2 * d;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x26FFFFFF);
        rf.set(pad, cy - th, w - pad, cy + th);
        c.drawRoundRect(rf, th, th, p);
        p.setColor(Ui.ACCENT);
        rf.set(pad, cy - th, x, cy + th);
        c.drawRoundRect(rf, th, th, p);
        p.setColor(0xFFFFFFFF);
        c.drawCircle(x, cy, 9 * d, p);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
            float pad = 11 * d, w = getWidth();
            float f = (e.getX() - pad) / Math.max(1f, w - 2 * pad);
            f = f < 0 ? 0 : (f > 1 ? 1 : f);
            int v = min + Math.round(f * (max - min));
            if (v != value) {
                value = v;
                invalidate();
                if (listener != null) listener.on(value, true);
            }
        }
        return true;
    }
}
