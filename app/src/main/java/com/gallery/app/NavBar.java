package com.gallery.app;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.transition.ChangeBounds;
import android.transition.Fade;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Floating bottom navigation (thumb reach): Photos, Albums, Search, Settings. Added to the screen's root column.
 * The four items are built once; switching tabs slides the active pill and fades its label instead of
 * rebuilding the bar, which is what made the old version flicker.
 */
final class NavBar {
    static final int PHOTOS = 0, ALBUMS = 1, SEARCH = 2, SETTINGS = 3;
    private static final int COUNT = 4;
    private static final long DURATION = 260;
    private static final ArgbEvaluator ARGB = new ArgbEvaluator();

    interface Callback {
        void onTab(int index);
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
        bar.setBackgroundResource(R.drawable.bg_nav_float);
        bar.setPadding(Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 6));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(Ui.dp(a, 16), Ui.dp(a, 6), Ui.dp(a, 16), Ui.dp(a, 12));
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
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER);
            item.setContentDescription(a.getString(labelRes[i]));
            item.setBackgroundResource(R.drawable.bg_nav_active);
            pills[i] = item.getBackground().mutate();
            pills[i].setAlpha(0);

            ImageView icon = new ImageView(a);
            icon.setImageResource(iconRes[i]);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            item.addView(icon, new LinearLayout.LayoutParams(Ui.dp(a, 28), Ui.dp(a, 28)));

            TextView t = new TextView(a);   // only the current tab shows its name: a calmer, more modern bar
            t.setText(labelRes[i]);
            t.setTextSize(13.5f);
            t.setSingleLine(true);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextColor(Ui.color(a, R.color.on_accent));
            t.setPadding(Ui.dp(a, 6), 0, 0, 0);
            t.setVisibility(View.GONE);
            item.addView(t);

            Ui.press(a, item);
            item.setOnClickListener(v -> {
                if (idx != selected) cb.onTab(idx);
            });
            bar.addView(item, new LinearLayout.LayoutParams(0, Ui.dp(a, 48), 1f));
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
        animate = animate && bar.isLaidOut();

        if (animate) {
            TransitionSet ts = new TransitionSet();
            ts.setOrdering(TransitionSet.ORDERING_TOGETHER);
            ts.addTransition(new ChangeBounds());
            ts.addTransition(new Fade());
            ts.setDuration(DURATION);
            ts.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
            TransitionManager.beginDelayedTransition(bar, ts);
        }
        for (int i = 0; i < COUNT; i++) {
            boolean on = i == sel;
            names[i].setVisibility(on ? View.VISIBLE : View.GONE);
            items[i].setPadding(Ui.dp(a, 8), 0, Ui.dp(a, on ? 14 : 8), 0);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) items[i].getLayoutParams();
            lp.weight = on ? 2.1f : 1f;
            items[i].setLayoutParams(lp);
        }

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

    /** t = 0..1 moves the pill and the icon colours from tab {@code from} to tab {@code to}. */
    private void paint(int from, int to, float t) {
        int on = Ui.color(a, R.color.on_accent);
        int off = Ui.color(a, R.color.text_secondary);
        for (int i = 0; i < COUNT; i++) {
            float k = i == to ? t : i == from ? 1f - t : 0f;
            pills[i].setAlpha(Math.round(255f * k));
            icons[i].setImageTintList(ColorStateList.valueOf((Integer) ARGB.evaluate(k, off, on)));
        }
    }
}
