package com.gallery.app;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import eightbitlab.com.blurview.BlurTarget;
import eightbitlab.com.blurview.BlurView;

/**
 * The iPhone Photos selection toolbar: while items are selected a glass capsule floats where the tab bar
 * was, with share on one side, "N selected" in the middle and delete (red) on the other. It slides up when
 * selecting starts and away when it ends.
 */
final class SelectBar {
    private final Activity a;
    private final LinearLayout row;
    private final TextView count;
    private final ImageButton share, del;
    private boolean shown;
    private int shownCount = -1;

    private SelectBar(Activity a, BlurTarget target, Runnable onShare, Runnable onDelete) {
        this.a = a;
        row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
        row.setClipChildren(false);

        LinearLayout inner = new LinearLayout(a);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setGravity(Gravity.CENTER_VERTICAL);
        inner.setPadding(Ui.dp(a, 6), 0, Ui.dp(a, 6), 0);

        share = button(R.drawable.ic_share, R.string.share, Ui.color(a, R.color.accent));
        del = button(R.drawable.ic_delete, R.string.delete, Ui.color(a, R.color.bad));
        count = new TextView(a);
        count.setTextSize(16f);
        count.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        count.setTextColor(Ui.color(a, R.color.text_primary));
        count.setGravity(Gravity.CENTER);
        count.setSingleLine(true);
        inner.addView(share, new LinearLayout.LayoutParams(Ui.dp(a, 56), Ui.dp(a, 56)));
        inner.addView(count, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        inner.addView(del, new LinearLayout.LayoutParams(Ui.dp(a, 56), Ui.dp(a, 56)));

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
        capsule.addView(inner, new FrameLayout.LayoutParams(-1, -1));
        row.addView(capsule, new LinearLayout.LayoutParams(0, Ui.dp(a, 64), 1f));

        share.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            onShare.run();
        });
        del.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            onDelete.run();
        });
        row.setVisibility(View.GONE);
    }

    /** Adds the bar to the bottom of {@code root} (same spot and margins as the tab bar). */
    static SelectBar attach(Activity a, FrameLayout root, BlurTarget target, Runnable onShare, Runnable onDelete) {
        SelectBar b = new SelectBar(a, target, onShare, onDelete);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.setMargins(Ui.dp(a, 20), 0, Ui.dp(a, 20), Ui.dp(a, 10));
        root.addView(b.row, lp);
        return b;
    }

    private ImageButton button(int icon, int desc, int tint) {
        ImageButton b = new ImageButton(a);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(tint));
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setBackground(null);
        b.setContentDescription(a.getString(desc));
        Ui.press(a, b);
        return b;
    }

    /** Distance from the bottom edge: the system navigation bar. */
    void setInset(int bottomPx) {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) row.getLayoutParams();
        lp.bottomMargin = Ui.dp(a, 10) + bottomPx;
        row.setLayoutParams(lp);
    }

    /** Called on every selection change. */
    void update(boolean selecting, int n) {
        if (selecting) {
            boolean any = n > 0;
            share.setEnabled(any);
            del.setEnabled(any);
            share.setAlpha(any ? 1f : 0.35f);
            del.setAlpha(any ? 1f : 0.35f);
            if (shownCount != n) {
                boolean first = shownCount < 0;
                shownCount = n;
                count.setText(any ? a.getString(R.string.n_selected, n) : a.getString(R.string.select_items));
                if (!first) {
                    count.animate().cancel();
                    count.setAlpha(0.4f);
                    count.animate().alpha(1f).setDuration(140).start();
                }
            }
            if (!shown) show();
        } else if (shown) {
            shownCount = -1;
            hide();
        }
    }

    private void show() {
        shown = true;
        row.animate().cancel();
        row.setVisibility(View.VISIBLE);
        row.setAlpha(0f);
        row.setTranslationY(Ui.dp(a, 90));
        row.animate().alpha(1f).translationY(0f).setDuration(340)
                .setInterpolator(new DecelerateInterpolator(1.8f)).start();
    }

    private void hide() {
        shown = false;
        row.animate().cancel();
        row.animate().alpha(0f).translationY(Ui.dp(a, 90)).setDuration(240)
                .setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f))
                .withEndAction(() -> {
                    if (!shown) row.setVisibility(View.GONE);
                }).start();
    }
}
