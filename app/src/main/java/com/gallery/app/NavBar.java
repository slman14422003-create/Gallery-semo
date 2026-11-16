package com.gallery.app;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * iPhone-style floating tab bar: a capsule with four equal tabs (Photos, Albums, Search, Settings).
 * Every tab shows its icon with its name underneath; the current tab gets a soft capsule highlight and the
 * iOS blue tint, the others stay grey. Built once; switching tabs cross-fades the highlight and the tint
 * instead of rebuilding the bar (no flicker, no layout jumps).
 */
final class NavBar {
    static final int PHOTOS = 0, ALBUMS = 1, SEARCH = 2, SETTINGS = 3;
    private static final int COUNT = 4;
    private static final long DURATION = 220;
    private static final ArgbEvaluator ARGB = new ArgbEvaluator();

    interface Callback {
        void onTab(int index);
    }

    /** Tapping the tab that is already open (e.g. scroll to top). */
    interface Reselect {
        void onReselect(int index);
    }

    private final Activity a;
    private final Callback cb;
    private final LinearLayout bar;
    private final LinearLayout[] items = new LinearLayout[COUNT];
    private final ImageView[] icons = new ImageView[COUNT];
    private final TextView[] names = new TextView[COUNT];
    private final Drawable[] pills = new Drawable[COUNT];
    private int selected = -1;
    private ValueAnimator anim;
    private Reselect reselect;

    void setOnReselect(Reselect r) {
        reselect = r;
    }

    private NavBar(Activity a, Callback cb, LinearLayout bar) {
        this.a = a;
        this.cb = cb;
        this.bar = bar;
    }

    static NavBar attach(Activity a, int selected, Callback cb) {
        ViewGroup content = a.findViewById(android.R.id.content);
        View root = content.getChildAt(0);
        if (!(root instanceof LinearLayout)) return null;
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
        bar.setBackgroundResource(R.drawable.bg_nav_float);
        bar.setPadding(Ui.dp(a, 5), Ui.dp(a, 5), Ui.dp(a, 5), Ui.dp(a, 5));
        bar.setElevation(Ui.dp(a, 8));   // soft shadow so the capsule lifts off the content
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(Ui.dp(a, 20), Ui.dp(a, 6), Ui.dp(a, 20), Ui.dp(a, 10));
        ((LinearLayout) root).addView(bar, blp);
        NavBar n = new NavBar(a, cb, bar);
        n.build();
        n.apply(selected, false);
        return n;
    }

    private void build() {
        int[] iconRes = {R.drawable.ic_image, R.drawable.ic_folder, R.drawable.ic_search, R.drawable.ic_settings};
        int[] labelRes = {R.string.nav_photos, R.string.nav_albums, R.string.nav_search, R.string.nav_settings};
        for (int i = 0; i < COUNT; i++) {
            final int idx = i;
            LinearLayout item = new LinearLayout(a);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setContentDescription(a.getString(labelRes[i]));
            item.setBackgroundResource(R.drawable.bg_nav_pill);
            pills[i] = item.getBackground().mutate();
            pills[i].setAlpha(0);

            ImageView icon = new ImageView(a);
            icon.setImageResource(iconRes[i]);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            item.addView(icon, new LinearLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24)));

            TextView t = new TextView(a);
            t.setText(labelRes[i]);
            t.setTextSize(10.5f);
            t.setSingleLine(true);
            t.setGravity(Gravity.CENTER);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t.setTextColor(Ui.color(a, R.color.text_secondary));
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tp.topMargin = Ui.dp(a, 2);
            item.addView(t, tp);

            Ui.press(a, item);
            item.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                if (idx != selected) cb.onTab(idx);
                else if (reselect != null) reselect.onReselect(idx);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(a, 54), 1f);
            lp.setMargins(Ui.dp(a, 2), 0, Ui.dp(a, 2), 0);
            bar.addView(item, lp);
            items[i] = item;
            icons[i] = icon;
            names[i] = t;
        }
    }

    /** Highlights a tab (animated once the bar is on screen). */
    void render(int sel) {
        apply(sel, selected >= 0);
    }

    private void apply(final int sel, boolean animate) {
        if (sel == selected) return;
        final int from = selected;
        selected = sel;
        if (anim != null) anim.cancel();
        for (int i = 0; i < COUNT; i++) items[i].setSelected(i == sel);
        animate = animate && bar.isLaidOut();
        if (!animate || from < 0) {
            paint(from, sel, 1f);
            return;
        }
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(DURATION);
        anim.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        anim.addUpdateListener(v -> paint(from, sel, (float) v.getAnimatedValue()));
        anim.start();
    }

    /** t = 0..1 cross-fades the highlight and the tint from tab {@code from} to tab {@code to}. */
    private void paint(int from, int to, float t) {
        int on = Ui.color(a, R.color.accent);
        int off = Ui.color(a, R.color.text_secondary);
        for (int i = 0; i < COUNT; i++) {
            float k = i == to ? t : i == from ? 1f - t : 0f;
            pills[i].setAlpha(Math.round(255f * k));
            int col = (Integer) ARGB.evaluate(k, off, on);
            icons[i].setImageTintList(ColorStateList.valueOf(col));
            names[i].setTextColor(col);
        }
    }
}
