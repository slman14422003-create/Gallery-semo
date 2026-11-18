package com.gallery.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small builders for the iOS Photos look shared by both editors: nav bar text buttons, hairlines, round tool buttons. */
final class Ios {
    static final int YELLOW = 0xFFFFD60A;

    private Ios() {
    }

    static int hairPx(Context c) {
        return Math.max(1, Math.round(c.getResources().getDisplayMetrics().density * 0.5f));
    }

    static View hairline(Context c) {
        View v = new View(c);
        v.setBackgroundColor(Ui.color(c, R.color.stroke));
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, hairPx(c)));
        return v;
    }

    /** Blue text button of an iOS navigation bar. */
    /** Cancel (neutral pill) / Done (yellow pill with black text), like the iOS 26 Photos editor. */
    static TextView navText(Context c, CharSequence text, boolean semibold) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(16);
        t.setTextColor(semibold ? 0xFF000000 : Ui.color(c, R.color.text_primary));
        t.setTypeface(Typeface.create("sans-serif-medium", semibold ? Typeface.BOLD : Typeface.NORMAL));
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(Ui.dp(c, 40));
        t.setSingleLine(true);
        t.setPadding(Ui.dp(c, 18), 0, Ui.dp(c, 18), 0);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.dp(c, 22));
        g.setColor(semibold ? YELLOW : Ui.color(c, R.color.neutral_soft));
        t.setBackground(g);
        t.setElevation(Ui.dp(c, 2));
        Ui.press(c, t);
        return t;
    }

    /** Yellow tool-name chip in the middle of the second top row (e.g. ADJUST). */
    static TextView chip(Context c, CharSequence text) {
        TextView t = new TextView(c);
        t.setText(text.toString().toUpperCase(java.util.Locale.getDefault()));
        t.setTextSize(13);
        t.setTextColor(0xFF000000);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(Ui.dp(c, 10), Ui.dp(c, 3), Ui.dp(c, 10), Ui.dp(c, 3));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.dp(c, 7));
        g.setColor(YELLOW);
        t.setBackground(g);
        return t;
    }

    /** Rounded capsule that groups round buttons (the pills in the second top row). */
    static LinearLayout capsule(Context c, View... kids) {
        LinearLayout l = new LinearLayout(c);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        l.setPadding(Ui.dp(c, 2), 0, Ui.dp(c, 2), 0);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.dp(c, 22));
        g.setColor(Ui.color(c, R.color.neutral_soft));
        l.setBackground(g);
        for (View k : kids) l.addView(k, new LinearLayout.LayoutParams(Ui.dp(c, 42), Ui.dp(c, 42)));
        return l;
    }

    /** Round icon button with no background of its own (sits inside a capsule). */
    static ImageButton flatIcon(Context c, int icon, int desc, View.OnClickListener click) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(Ui.color(c, R.color.text_primary)));
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setBackground(null);
        b.setContentDescription(c.getString(desc));
        b.setOnClickListener(click);
        Ui.press(c, b);
        return b;
    }

    /** Two-row top area: [Cancel ... Done] over [left capsule | chip | right capsule]. */
    static LinearLayout topArea(Context c, View cancel, View done, View left, View chip, View right) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout r1 = new LinearLayout(c);
        r1.setGravity(Gravity.CENTER_VERTICAL);
        r1.setPadding(Ui.dp(c, 14), Ui.dp(c, 6), Ui.dp(c, 14), Ui.dp(c, 2));
        r1.addView(cancel, new LinearLayout.LayoutParams(-2, Ui.dp(c, 40)));
        r1.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
        r1.addView(done, new LinearLayout.LayoutParams(-2, Ui.dp(c, 40)));
        col.addView(r1);
        LinearLayout r2 = new LinearLayout(c);
        r2.setGravity(Gravity.CENTER_VERTICAL);
        r2.setPadding(Ui.dp(c, 14), Ui.dp(c, 4), Ui.dp(c, 14), Ui.dp(c, 6));
        r2.addView(left, new LinearLayout.LayoutParams(-2, Ui.dp(c, 42)));
        FrameLayout mid = new FrameLayout(c);
        mid.addView(chip, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        r2.addView(mid, new LinearLayout.LayoutParams(0, -2, 1f));
        r2.addView(right, new LinearLayout.LayoutParams(-2, Ui.dp(c, 42)));
        col.addView(r2);
        return col;
    }

    /** Makes the tool strip a floating capsule above the bottom edge (margins are set by the caller's params). */
    static void floatingBar(Context c, View bar) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.dp(c, 34));
        g.setColor(Ui.color(c, R.color.surface));
        g.setStroke(Math.max(1, Ui.dp(c, 1) / 2), Ui.color(c, R.color.stroke_soft));
        bar.setBackground(g);
        bar.setElevation(Ui.dp(c, 8));
    }

    static ImageButton navIcon(Context c, int icon, int desc, View.OnClickListener click) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(Ui.color(c, R.color.text_primary)));
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setBackgroundResource(R.drawable.bg_tile_round);
        b.setContentDescription(c.getString(desc));
        b.setOnClickListener(click);
        Ui.press(c, b);
        return b;
    }

    /** A round icon with its name underneath, like the tool row of the iOS Photos editor. */
    static LinearLayout toolItem(Context c, int icon, CharSequence label) {
        LinearLayout item = new LinearLayout(c);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4));
        View dot = new View(c);                                   // child 0: yellow dot above the selected tool
        GradientDrawable dg = new GradientDrawable();
        dg.setShape(GradientDrawable.OVAL);
        dg.setColor(YELLOW);
        dot.setBackground(dg);
        item.addView(dot, new LinearLayout.LayoutParams(Ui.dp(c, 4), Ui.dp(c, 4)));
        ImageView iv = new ImageView(c);                          // child 1: icon (dark disc when selected)
        iv.setImageResource(icon);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int p = Ui.dp(c, 8);
        iv.setPadding(p, p, p, p);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(c, 38), Ui.dp(c, 38));
        ip.topMargin = Ui.dp(c, 2);
        item.addView(iv, ip);
        TextView t = new TextView(c);                             // child 2: label
        t.setText(label);
        t.setTextSize(10.5f);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = Ui.dp(c, 2);
        item.addView(t, lp);
        item.setContentDescription(label);
        Ui.press(c, item);
        setToolSelected(c, item, false);
        return item;
    }

    static void setToolSelected(Context c, LinearLayout item, boolean on) {
        item.getChildAt(0).setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        ImageView iv = (ImageView) item.getChildAt(1);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(on ? Ui.color(c, R.color.text_primary) : 0x00000000);
        iv.setBackground(g);
        iv.setImageTintList(ColorStateList.valueOf(on ? Ui.color(c, R.color.bg) : Ui.color(c, R.color.text_secondary)));
        ((TextView) item.getChildAt(2)).setTextColor(Ui.color(c, on ? R.color.text_primary : R.color.text_secondary));
    }

    /** Small round icon button used inside tool panels (rotate, flip). */
    static ImageButton panelIcon(Context c, int icon, int desc, View.OnClickListener click) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(Ui.color(c, R.color.text_primary)));
        b.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Ui.color(c, R.color.neutral_soft));
        b.setBackground(g);
        b.setContentDescription(c.getString(desc));
        b.setOnClickListener(click);
        Ui.press(c, b);
        return b;
    }

    /** Rounded square that holds a filter thumbnail; the selected one gets a ring. */
    static void thumbFrame(Context c, View v, boolean selected) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.dp(c, 10));
        g.setColor(Ui.color(c, R.color.neutral_soft));
        if (selected) g.setStroke(Ui.dp(c, 2), Ui.color(c, R.color.accent));
        v.setBackground(g);
        v.setClipToOutline(true);
    }
}
