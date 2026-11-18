package com.gallery.app;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
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
    private View thumb;                 // the one highlight capsule; press and drag it between the tabs
    private boolean touching, dragging, thumbAnimating;
    private int downIdx = -1, previewIdx = -1;
    private float downX, thumbMin, thumbMax;
    private final int slop;
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
        this.slop = ViewConfiguration.get(a).getScaledTouchSlop();
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
        thumb = new View(a);
        thumb.setBackgroundResource(R.drawable.bg_nav_pill);
        thumb.setAlpha(0f);
        capsule.addView(thumb, new FrameLayout.LayoutParams(Ui.dp(a, 80), Ui.dp(a, 54), Gravity.LEFT | Gravity.TOP));
        capsule.addView(bar, new FrameLayout.LayoutParams(-1, -1));
        row.addView(capsule, new LinearLayout.LayoutParams(0, Ui.dp(a, 64), 1f));
        bar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> settleThumb());
        capsule.setOnTouchListener(this::onCapsuleTouch);

        for (int i = 0; i < TABS; i++) {
            LinearLayout item = new LinearLayout(a);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setContentDescription(a.getString(labelRes[i]));

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

            // taps and drags on the tabs are handled by the capsule (see onCapsuleTouch)
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
        moveThumb(from, sel, animate);
        // if the finger previewed a tab while dragging, the tint already shows it: continue from there
        int tintFrom = previewIdx >= 0 ? previewIdx : from;
        previewIdx = -1;
        if (!animate || tintFrom < 0 || tintFrom == sel) {
            paint(-1, sel, 1f);
            return;
        }
        final int tf = tintFrom;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(DURATION);
        anim.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        anim.addUpdateListener(v -> paint(tf, sel, (float) v.getAnimatedValue()));
        anim.start();
    }

    /** t = 0..1 cross-fades the tint from tab {@code from} to tab {@code to}. */
    private void paint(int from, int to, float t) {
        int on = Ui.color(a, R.color.accent);
        int off = Ui.color(a, R.color.text_secondary);
        for (int i = 0; i < COUNT; i++) {
            float k = i == to ? t : i == from ? 1f - t : 0f;
            int col = (Integer) ARGB.evaluate(k, off, on);
            icons[i].setImageTintList(ColorStateList.valueOf(col));
            if (i < TABS) names[i].setTextColor(col);
        }
    }

    // ------------------------------------------------------------------ the draggable highlight

    private void sizeThumb(View it) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) thumb.getLayoutParams();
        if (lp.width != it.getWidth() || lp.height != it.getHeight()) {
            lp.width = it.getWidth();
            lp.height = it.getHeight();
            thumb.setLayoutParams(lp);
        }
        thumb.setTranslationY(it.getTop());
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (int i = 0; i < TABS; i++) {
            lo = Math.min(lo, items[i].getLeft());
            hi = Math.max(hi, items[i].getLeft());
        }
        thumbMin = lo;
        thumbMax = hi;
    }

    /** Puts the highlight under the selected tab without animation (first layout, rotation). */
    private void settleThumb() {
        if (touching || thumbAnimating) return;
        View ref = items[selected >= 0 && selected < TABS ? selected : 0];
        if (ref.getWidth() == 0) return;
        sizeThumb(ref);
        if (selected >= 0 && selected < TABS) thumb.setTranslationX(ref.getLeft());
    }

    private void moveThumb(int from, int to, boolean animate) {
        thumb.animate().cancel();
        thumbAnimating = false;
        if (to < 0 || to >= TABS) {                      // search is selected: the highlight fades away
            if (animate) thumb.animate().alpha(0f).scaleX(1f).scaleY(1f).setDuration(160).start();
            else {
                thumb.setAlpha(0f);
                thumb.setScaleX(1f);
                thumb.setScaleY(1f);
            }
            return;
        }
        View it = items[to];
        if (it.getWidth() == 0) return;                  // not laid out yet: settleThumb() will place it
        sizeThumb(it);
        float tx = it.getLeft();
        boolean wasShown = thumb.getAlpha() > 0.5f;
        if (!animate) {
            thumb.setTranslationX(tx);
            thumb.setAlpha(1f);
            thumb.setScaleX(1f);
            thumb.setScaleY(1f);
            return;
        }
        if (!wasShown) {
            thumb.setTranslationX(tx);                   // coming from search: fade in on the spot
            thumb.setAlpha(0f);
            thumb.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start();
            return;
        }
        thumbAnimating = true;
        thumb.animate().translationX(tx).alpha(1f).scaleX(1f).scaleY(1f).setDuration(380)
                .setInterpolator(new OvershootInterpolator(0.9f))
                .withEndAction(() -> thumbAnimating = false).start();
    }

    private void lift(boolean up) {
        thumb.animate().scaleX(up ? 1.08f : 1f).scaleY(up ? 1.1f : 1f).setDuration(up ? 160 : 260)
                .setInterpolator(new DecelerateInterpolator(1.5f)).start();
    }

    private int indexAt(float centerX) {
        int best = 0;
        float bd = Float.MAX_VALUE;
        for (int i = 0; i < TABS; i++) {
            float d = Math.abs(centerX - (items[i].getLeft() + items[i].getWidth() / 2f));
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    /**
     * iOS 26 tab bar: a tap picks a tab; pressing and dragging lifts the highlight and slides it under the
     * finger, the tab under it lights up, and letting go settles it on the nearest tab.
     */
    private boolean onCapsuleTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (items[0].getWidth() == 0) return false;
                touching = true;
                dragging = false;
                downX = e.getX();
                downIdx = indexAt(downX);
                if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                if (selected >= 0 && selected < TABS) {
                    thumb.animate().cancel();
                    thumbAnimating = false;
                    lift(true);
                }
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (!touching) return false;
                float x = e.getX();
                if (!dragging && Math.abs(x - downX) > slop) {
                    dragging = true;
                    sizeThumb(items[0]);
                    thumb.animate().cancel();
                    thumbAnimating = false;
                    if (thumb.getAlpha() < 0.5f) {      // search was selected: the highlight appears under the finger
                        thumb.setTranslationX(Math.max(thumbMin, Math.min(thumbMax, x - thumb.getWidth() / 2f)));
                    }
                    thumb.animate().alpha(1f).scaleX(1.08f).scaleY(1.1f).setDuration(140).start();
                }
                if (dragging) {
                    float tx = Math.max(thumbMin, Math.min(thumbMax, x - thumb.getWidth() / 2f));
                    thumb.setTranslationX(tx);
                    int idx = indexAt(tx + thumb.getWidth() / 2f);
                    if (idx != previewIdx) {
                        previewIdx = idx;
                        paint(-1, idx, 1f);
                        v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!touching) return false;
                boolean cancel = e.getActionMasked() == MotionEvent.ACTION_CANCEL;
                boolean wasDrag = dragging;
                touching = false;
                dragging = false;
                int target = cancel ? selected
                        : wasDrag ? indexAt(thumb.getTranslationX() + thumb.getWidth() / 2f) : downIdx;
                if (!cancel && !wasDrag) v.performClick();
                if (target < 0 || target >= TABS || target == selected) {
                    previewIdx = -1;
                    if (selected >= 0) paint(-1, selected, 1f);
                    if (selected >= 0 && selected < TABS) {
                        moveThumb(selected, selected, true);
                        if (!cancel && !wasDrag && target == selected && reselect != null) reselect.onReselect(target);
                    }
                    return true;
                }
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                cb.onTab(target);                       // render() then slides the highlight the rest of the way
                if (selected != target) {                // the screen refused: go back
                    previewIdx = -1;
                    if (selected >= 0) paint(-1, selected, 1f);
                    if (selected >= 0 && selected < TABS) moveThumb(selected, selected, true);
                }
                return true;
            }
            default:
                return false;
        }
    }

    /** Slides the whole bar away (selection mode) or back. */
    void setHidden(boolean hide) {
        if (row == null) return;
        row.animate().cancel();
        if (hide) {
            float dist = row.getHeight() + ((ViewGroup.MarginLayoutParams) row.getLayoutParams()).bottomMargin + Ui.dp(a, 8);
            row.animate().translationY(dist).alpha(0f).setDuration(240)
                    .setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f))
                    .withEndAction(() -> row.setVisibility(View.INVISIBLE)).start();
        } else {
            row.setVisibility(View.VISIBLE);
            row.animate().translationY(0f).alpha(1f).setDuration(320)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        }
    }
}
