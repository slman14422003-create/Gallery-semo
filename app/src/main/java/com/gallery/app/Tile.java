package com.gallery.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** One square thumbnail in the grid: picture, video / GIF badge, favourite mark and selection state. */
final class Tile extends FrameLayout {
    final ImageView thumb;
    private final LinearLayout videoBadge;
    private final TextView durText;
    private final TextView gifBadge;
    private final ImageView favMark;
    private final View scrim;
    private final ImageView check;

    Tile(Context c) {
        super(c);
        // iOS Photos grid: square, edge-to-edge, no rounded corners
        setWillNotDraw(true);

        thumb = new ImageView(c);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(thumb, new LayoutParams(-1, -1));

        scrim = new View(c);
        scrim.setBackgroundColor(0x33000000);
        scrim.setVisibility(GONE);
        addView(scrim, new LayoutParams(-1, -1));

        videoBadge = new LinearLayout(c);
        videoBadge.setOrientation(LinearLayout.HORIZONTAL);
        videoBadge.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.dp(c, 20));
        g.setColor(0x99000000);
        videoBadge.setBackground(g);
        videoBadge.setPadding(Ui.dp(c, 6), Ui.dp(c, 2), Ui.dp(c, 7), Ui.dp(c, 2));
        ImageView pi = new ImageView(c);
        pi.setImageResource(R.drawable.ic_play_fill);
        pi.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        videoBadge.addView(pi, new LinearLayout.LayoutParams(Ui.dp(c, 13), Ui.dp(c, 13)));
        durText = new TextView(c);
        durText.setTextColor(0xFFFFFFFF);
        durText.setTextSize(11);
        durText.setTypeface(Typeface.DEFAULT_BOLD);
        durText.setPadding(Ui.dp(c, 3), 0, 0, 0);
        videoBadge.addView(durText);
        LayoutParams vp = new LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        vp.setMargins(Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6));
        addView(videoBadge, vp);

        gifBadge = new TextView(c);
        gifBadge.setText("GIF");
        gifBadge.setTextColor(0xFFFFFFFF);
        gifBadge.setTextSize(10);
        gifBadge.setTypeface(Typeface.DEFAULT_BOLD);
        gifBadge.setBackground(g.getConstantState().newDrawable());
        gifBadge.setPadding(Ui.dp(c, 6), Ui.dp(c, 2), Ui.dp(c, 6), Ui.dp(c, 2));
        LayoutParams gp = new LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        gp.setMargins(Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6));
        addView(gifBadge, gp);

        favMark = new ImageView(c);
        favMark.setImageResource(R.drawable.ic_heart_fill);
        favMark.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        LayoutParams fp = new LayoutParams(Ui.dp(c, 16), Ui.dp(c, 16), Gravity.BOTTOM | Gravity.START);
        fp.setMargins(Ui.dp(c, 7), Ui.dp(c, 7), Ui.dp(c, 7), Ui.dp(c, 7));
        addView(favMark, fp);

        check = new ImageView(c);
        int p = Ui.dp(c, 3);
        check.setPadding(p, p, p, p);
        LayoutParams cp = new LayoutParams(Ui.dp(c, 24), Ui.dp(c, 24), Gravity.BOTTOM | Gravity.END);
        cp.setMargins(Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6));
        addView(check, cp);
        setClickable(true);
    }

    @Override
    protected void onMeasure(int w, int h) {
        super.onMeasure(w, w);   // always square
    }

    void bind(Media m, int px, boolean fav) {
        durText.setText(m.video ? Fmt.dur(m.duration) : "");
        videoBadge.setVisibility(m.video ? VISIBLE : GONE);
        gifBadge.setVisibility(!m.video && m.isGif() ? VISIBLE : GONE);
        favMark.setVisibility(fav ? VISIBLE : GONE);
        Thumbs.load(thumb, m, px);
        setContentDescription(m.name);
    }

    private GradientDrawable ringBg, onBg;
    private boolean lastSelecting, lastSelected;

    /**
     * iOS selection: while selecting every tile shows a small ring in its bottom corner; a selected tile gets a
     * filled blue circle with a white check that pops in, and a light dim. The picture itself never moves or
     * shrinks, so the grid stays perfectly aligned.
     */
    void setSelection(boolean selecting, boolean selected, boolean animate) {
        Context c = getContext();
        if (ringBg == null) {
            ringBg = new GradientDrawable();
            ringBg.setShape(GradientDrawable.OVAL);
            ringBg.setColor(0x33000000);
            ringBg.setStroke(Ui.dp(c, 2), 0xFFFFFFFF);
            onBg = new GradientDrawable();
            onBg.setShape(GradientDrawable.OVAL);
            onBg.setColor(Ui.color(c, R.color.accent));
            onBg.setStroke(Ui.dp(c, 2), 0xFFFFFFFF);
        }
        boolean wasSelecting = lastSelecting, wasSelected = lastSelected;
        lastSelecting = selecting;
        lastSelected = selected;
        scrim.animate().cancel();
        check.animate().cancel();
        if (!selecting) {
            scrim.setVisibility(GONE);
            if (animate && wasSelecting && check.getVisibility() == VISIBLE) {
                check.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(140).withEndAction(() -> check.setVisibility(GONE)).start();
            } else {
                check.setVisibility(GONE);
            }
            if (isSelected()) setSelected(false);
            return;
        }
        check.setBackground(selected ? onBg : ringBg);
        if (selected) {
            check.setImageResource(R.drawable.ic_check);
            check.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        } else {
            check.setImageDrawable(null);
        }
        boolean appearing = check.getVisibility() != VISIBLE;
        check.setVisibility(VISIBLE);
        if (!animate) {
            check.setAlpha(1f);
            check.setScaleX(1f);
            check.setScaleY(1f);
            scrim.setAlpha(1f);
            scrim.setVisibility(selected ? VISIBLE : GONE);
        } else {
            if (appearing) {
                check.setAlpha(0f);
                check.setScaleX(0.6f);
                check.setScaleY(0.6f);
                check.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start();
            } else if (selected != wasSelected) {
                check.setScaleX(selected ? 0.55f : 1f);
                check.setScaleY(selected ? 0.55f : 1f);
                check.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(new android.view.animation.OvershootInterpolator(3.2f)).start();
            }
            if (selected) {
                scrim.setVisibility(VISIBLE);
                scrim.setAlpha(wasSelected ? 1f : 0f);
                scrim.animate().alpha(1f).setDuration(140).start();
            } else {
                scrim.animate().alpha(0f).setDuration(120).withEndAction(() -> scrim.setVisibility(GONE)).start();
            }
        }
        if (isSelected() != selected) setSelected(selected);
    }

    void setFav(boolean fav) {
        favMark.setVisibility(fav ? VISIBLE : GONE);
    }
}
