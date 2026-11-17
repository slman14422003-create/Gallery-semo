package com.gallery.app;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;

/** The floating pill of round icon buttons used over media (viewer, editors). */
final class ViewerBar {
    private ViewerBar() {
    }

    static LinearLayout build(Activity a, int[] icons, int[] descs, View.OnClickListener[] clicks, ImageButton[] out) {
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.dp(a, 40));
        g.setColor(0xCC1C1C1E);
        bar.setBackground(g);
        bar.setPadding(Ui.dp(a, 8), Ui.dp(a, 6), Ui.dp(a, 8), Ui.dp(a, 6));
        int bw = icons.length > 5 ? 44 : 50;
        for (int i = 0; i < icons.length; i++) {
            ImageButton b = new ImageButton(a);
            b.setImageResource(icons[i]);
            b.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
            b.setScaleType(ImageView.ScaleType.CENTER);
            b.setBackgroundResource(R.drawable.bg_tile_round);
            b.setContentDescription(a.getString(descs[i]));
            Ui.press(a, b);
            b.setOnClickListener(clicks[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(a, bw), Ui.dp(a, 46));
            lp.setMargins(Ui.dp(a, 3), 0, Ui.dp(a, 3), 0);
            bar.addView(b, lp);
            if (out != null) out[i] = b;
        }
        return bar;
    }
}
