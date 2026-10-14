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

    /**
     * iOS selection: while selecting every tile shows a small ring in its bottom corner; a selected tile gets a
     * filled blue circle with a white check and a light dim. The picture itself never moves or shrinks, so the
     * grid stays perfectly aligned.
     */
    void setSelection(boolean selecting, boolean selected, boolean animate) {
        Context c = getContext();
        scrim.setVisibility(selected ? VISIBLE : GONE);
        if (!selecting) {
            check.setVisibility(GONE);
        } else {
            check.setVisibility(VISIBLE);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            if (selected) {
                g.setColor(Ui.color(c, R.color.accent));
                g.setStroke(Ui.dp(c, 2), 0xFFFFFFFF);
                check.setImageResource(R.drawable.ic_check);
                check.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
            } else {
                g.setColor(0x33000000);
                g.setStroke(Ui.dp(c, 2), 0xFFFFFFFF);
                check.setImageDrawable(null);
            }
            check.setBackground(g);
        }
        if (isSelected() != selected) setSelected(selected);
    }

    void setFav(boolean fav) {
        favMark.setVisibility(fav ? VISIBLE : GONE);
    }
}
