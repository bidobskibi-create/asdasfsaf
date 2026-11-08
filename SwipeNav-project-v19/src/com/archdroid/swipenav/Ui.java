package com.archdroid.swipenav;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/** One small look for every screen: dark page, rounded cards, mint accent. No images, no layouts files. */
final class Ui {
    static final int BG = 0xFF0B0C10, CARD = 0xFF16181F, TEXT = 0xFFFFFFFF, SUB = 0xFF8A8F9C,
            ACCENT = 0xFF6EE7A8, LINE = 0x14FFFFFF;
    private static final Object LABEL = new Object();

    private Ui() { }

    static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    static TextView text(Context c, String s, float sp, int color, boolean medium) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }

    /** Sets up a scrolling dark page with a header and returns the column to add cards to. */
    static LinearLayout page(Activity a, String title, String sub) {
        a.getWindow().setBackgroundDrawable(new ColorDrawable(BG));   // single background, no overdraw
        ScrollView sv = new ScrollView(a);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(a, 16), dp(a, 26), dp(a, 16), dp(a, 28));

        View pill = new View(a);
        GradientDrawable pg = new GradientDrawable();
        pg.setColor(ACCENT);
        pg.setCornerRadius(dp(a, 3));
        pill.setBackgroundDrawable(pg);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(a, 26), dp(a, 4));
        plp.leftMargin = dp(a, 4);
        root.addView(pill, plp);
        TextView t = text(a, title, 30, TEXT, false);
        t.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        t.setPadding(dp(a, 4), dp(a, 10), 0, 0);
        root.addView(t);
        if (sub != null) {
            TextView s = text(a, sub, 13, SUB, false);
            s.setPadding(dp(a, 4), dp(a, 2), 0, dp(a, 18));
            root.addView(s);
        }
        sv.addView(root);
        a.setContentView(sv);
        return root;
    }

    static LinearLayout card(LinearLayout root, String label) {
        Context c = root.getContext();
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(c, 18));
        g.setStroke(1, LINE);
        l.setBackgroundDrawable(g);
        l.setPadding(dp(c, 16), dp(c, 4), dp(c, 16), dp(c, 4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(c, 12);
        root.addView(l, lp);
        if (label != null) {
            TextView t = text(c, label.toUpperCase(Locale.US), 11, ACCENT, true);
            t.setPadding(0, dp(c, 12), 0, dp(c, 2));
            t.setTag(LABEL);
            l.addView(t);
        }
        return l;
    }

    private static void gap(LinearLayout card) {
        int n = card.getChildCount();
        if (n > 0 && card.getChildAt(n - 1).getTag() != LABEL) {
            View v = new View(card.getContext());
            v.setBackgroundColor(LINE);
            card.addView(v, new LinearLayout.LayoutParams(-1, 1));
        }
    }

    private static void pressable(View v) {
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(0x14FFFFFF));
        sl.addState(new int[]{}, new ColorDrawable(0));
        v.setBackgroundDrawable(sl);
        v.setClickable(true);
    }

    private static LinearLayout row(LinearLayout card, String title, String sub, boolean press) {
        Context c = card.getContext();
        gap(card);
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(c, 12), 0, dp(c, 12));
        if (press) pressable(r);
        LinearLayout left = new LinearLayout(c);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(text(c, title, 15, TEXT, true));
        if (sub != null && sub.length() > 0) {
            TextView s = text(c, sub, 12, SUB, false);
            s.setPadding(0, dp(c, 2), 0, 0);
            left.addView(s);
        }
        r.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(r);
        return r;
    }

    static void toggleRow(LinearLayout card, String title, String sub, boolean on, Toggle.Listener l) {
        Context c = card.getContext();
        LinearLayout r = row(card, title, sub, true);
        final Toggle t = new Toggle(c, on, l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 46), dp(c, 28));
        lp.leftMargin = dp(c, 12);
        r.addView(t, lp);
        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { t.toggle(); }
        });
    }

    /** A tappable row with a value on the right. Returns the value view so it can be updated. */
    static TextView valueRow(LinearLayout card, String title, String sub, String value, View.OnClickListener click) {
        Context c = card.getContext();
        LinearLayout r = row(card, title, sub, true);
        TextView v = text(c, value == null ? "\u203A" : value + "  \u203A", 14, ACCENT, true);
        v.setPadding(dp(c, 10), 0, 0, 0);
        r.addView(v);
        if (click != null) r.setOnClickListener(click);
        return v;
    }

    static void sliderRow(LinearLayout card, String title, final String unit, int min, int max, int cur,
                          final Slider.Listener l) {
        Context c = card.getContext();
        gap(card);
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(0, dp(c, 12), 0, dp(c, 6));
        LinearLayout top = new LinearLayout(c);
        top.addView(text(c, title, 15, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView val = text(c, cur + unit, 14, ACCENT, true);
        top.addView(val);
        r.addView(top);
        Slider s = new Slider(c, min, max, cur, new Slider.Listener() {
            public void on(int v, boolean user) {
                val.setText(v + unit);
                if (user) l.on(v, true);
            }
        });
        r.addView(s, new LinearLayout.LayoutParams(-1, dp(c, 30)));
        card.addView(r);
    }

    static TextView link(LinearLayout card, String label, View.OnClickListener click) {
        Context c = card.getContext();
        gap(card);
        TextView t = text(c, label, 14, ACCENT, true);
        t.setPadding(0, dp(c, 14), 0, dp(c, 14));
        pressable(t);
        t.setOnClickListener(click);
        card.addView(t);
        return t;
    }

    static TextView note(LinearLayout card, String s) {
        Context c = card.getContext();
        TextView t = text(c, s, 12, SUB, false);
        t.setPadding(0, dp(c, 4), 0, dp(c, 10));
        card.addView(t);
        return t;
    }
}
