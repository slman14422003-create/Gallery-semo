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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import eightbitlab.com.blurview.BlurTarget;
import eightbitlab.com.blurview.BlurView;

/**
 * iOS 26 floating tab bar: a glass capsule with two tabs (Library, Collections) and a separate round
 * search button next to it. With a {@link BlurTarget} the bar floats over the content (the photos scroll
 * underneath and are blurred through the glass); without one (Settings) it is a plain capsule at the bottom.
 * Settings is not a tab: it is the round button in the Collections header.
 */
final class NavBar {
    static final int PHOTOS = 0, ALBUMS = 1, SEARCH = 2, SETTINGS = 3;
    private static final int TABS = 2;      // capsule tabs
    private static final int COUNT = 3;     // capsule tabs + search button
    private static final long DURATION = 240;
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
    private final BlurTarget target;
    private LinearLayout bar;
    private LinearLayout row;
    private final View[] items = new View[COUNT];
    private final ImageView[] icons = new ImageView[COUNT];
    private final TextView[] names = new TextView[TABS];
    private final Drawable[] pills = new Drawable[TABS];
    private int selected = -1;
    private ValueAnimator anim;
    private Reselect reselect;
    private boolean overlay;

    void setOnReselect(Reselect r) {
        reselect = r;
    }

    private NavBar(Activity a, Callback cb, BlurTarget target) {
        this.a = a;
        this.cb = cb;
        this.target = target;
    }

    static NavBar attach(Activity a, int selected, Callback cb) {
        return attach(a, selected, null, cb);
    }

    static NavBar attach(Activity a, int selected, BlurTarget target, Callback cb) {
        ViewGroup content = a.findViewById(android.R.id.content);
        View root = content.getChildAt(0);
        boolean over = target != null && root instanceof FrameLayout;
        if (!over && !(root instanceof LinearLayout)) return null;

        NavBar n = new NavBar(a, cb, over ? target : null);
        n.overlay = over;
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        n.row = row;
        n.build();
        if (over) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            lp.setMargins(Ui.dp(a, 20), 0, Ui.dp(a, 20), Ui.dp(a, 10));
            ((FrameLayout) root).addView(row, lp);
        } else {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(Ui.dp(a, 20), Ui.dp(a, 6), Ui.dp(a, 20), Ui.dp(a, 10));
            ((LinearLayout) root).addView(row, lp);
        }
        n.apply(selected, false);
        return n;
    }

    /** Distance of the bar from the screen bottom (system navigation bar) in the floating layout. */
    void setInset(int bottomPx) {
        if (!overlay || row == null) return;
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) row.getLayoutParams();
        lp.bottomMargin = Ui.dp(a, 10) + bottomPx;
        row.setLayoutParams(lp);
    }

    private void build() {
        int[] iconRes = {R.drawable.ic_image, R.drawable.ic_folder, R.drawable.ic_search};
        int[] labelRes = {R.string.nav_library, R.string.nav_collections, R.string.nav_search};

        bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(a, 5), Ui.dp(a, 5), Ui.dp(a, 5), Ui.dp(a, 5));

        FrameLayout capsule;
        if (target != null) {
            BlurView g = new BlurView(a);
            Glass.apply(g, target, -1, Glass.overlay(a));
            capsule = g;
        } else {
            capsule = new FrameLayout(a);
            capsule.setBackgroundResource(R.drawable.bg_nav_float);
        }
        capsule.setElevation(Ui.dp(a, 10));
        capsule.addView(bar, new FrameLayout.LayoutParams(-1, -1));
        row.addView(capsule, new LinearLayout.LayoutParams(0, Ui.dp(a, 64), 1f));

        for (int i = 0; i < TABS; i++) {
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

            hook(item, idx);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            lp.setMargins(Ui.dp(a, 2), 0, Ui.dp(a, 2), 0);
            bar.addView(item, lp);
            items[i] = item;
            icons[i] = icon;
            names[i] = t;
        }

        // detached round search button
        FrameLayout circle;
        if (target != null) {
            BlurView g = new BlurView(a);
            Glass.apply(g, target, -1, Glass.overlay(a));
            circle = g;
        } else {
            circle = new FrameLayout(a);
            circle.setBackgroundResource(R.drawable.bg_nav_circle);
        }
        circle.setElevation(Ui.dp(a, 10));
        circle.setContentDescription(a.getString(labelRes[SEARCH]));
        ImageView si = new ImageView(a);
        si.setImageResource(iconRes[SEARCH]);
        si.setScaleType(ImageView.ScaleType.FIT_CENTER);
        circle.addView(si, new FrameLayout.LayoutParams(Ui.dp(a, 26), Ui.dp(a, 26), Gravity.CENTER));
        hook(circle, SEARCH);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(Ui.dp(a, 64), Ui.dp(a, 64));
        cp.setMarginStart(Ui.dp(a, 10));
        row.addView(circle, cp);
        items[SEARCH] = circle;
        icons[SEARCH] = si;
    }

    private void hook(View v, final int idx) {
        Ui.press(a, v);
        v.setOnClickListener(x -> {
            x.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (idx != selected) cb.onTab(idx);
            else if (reselect != null) reselect.onReselect(idx);
        });
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
            int col = (Integer) ARGB.evaluate(k, off, on);
            icons[i].setImageTintList(ColorStateList.valueOf(col));
            if (i < TABS) {
                pills[i].setAlpha(Math.round(255f * k));
                names[i].setTextColor(col);
            }
        }
    }
}
