package com.gallery.app;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.List;

/** iOS context menu: a rounded card under a button that pops out of it. */
final class IosMenu {
    static final class Item {
        final int label, icon;
        final boolean destructive;
        final Runnable run;

        Item(int label, int icon, boolean destructive, Runnable run) {
            this.label = label;
            this.icon = icon;
            this.destructive = destructive;
            this.run = run;
        }
    }

    private IosMenu() {
    }

    static PopupWindow show(Activity a, View anchor, List<Item> items) {
        if (items.isEmpty()) return null;
        final int pad = Ui.dp(a, 14);
        final int cardW = Ui.dp(a, 232);
        FrameLayout wrap = new FrameLayout(a);
        wrap.setPadding(pad, pad, pad, pad);
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.dp(a, 16));
        g.setColor(Ui.color(a, R.color.surface));
        g.setStroke(Math.max(1, Ui.dp(a, 1) / 2), Ui.color(a, R.color.stroke_soft));
        card.setBackground(g);
        card.setElevation(Ui.dp(a, 10));
        card.setClipToOutline(true);
        final PopupWindow pw = new PopupWindow(wrap, cardW + pad * 2, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        pw.setOutsideTouchable(true);
        for (int i = 0; i < items.size(); i++) {
            final Item it = items.get(i);
            if (i > 0) {
                View d = new View(a);
                d.setBackgroundColor(Ui.color(a, R.color.stroke_soft));
                card.addView(d, new LinearLayout.LayoutParams(-1, Math.max(1, Ui.dp(a, 1) / 2)));
            }
            int col = it.destructive ? Ui.color(a, R.color.bad) : Ui.color(a, R.color.text_primary);
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPaddingRelative(Ui.dp(a, 18), 0, Ui.dp(a, 16), 0);
            row.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.color(a, R.color.ripple)), null, new ColorDrawable(0xFFFFFFFF)));
            TextView t = new TextView(a);
            t.setText(it.label);
            t.setTextSize(17f);
            t.setTextColor(col);
            t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            ImageView ic = new ImageView(a);
            ic.setImageResource(it.icon);
            ic.setImageTintList(ColorStateList.valueOf(col));
            row.addView(ic, new LinearLayout.LayoutParams(Ui.dp(a, 22), Ui.dp(a, 22)));
            row.setOnClickListener(v -> {
                pw.dismiss();
                it.run.run();
            });
            card.addView(row, new LinearLayout.LayoutParams(-1, Ui.dp(a, 50)));
        }
        wrap.addView(card, new FrameLayout.LayoutParams(-1, -2));

        boolean rtl = a.getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        int[] o = new int[2];
        anchor.getLocationOnScreen(o);
        int screenW = a.getResources().getDisplayMetrics().widthPixels;
        int x = rtl ? o[0] - pad : o[0] + anchor.getWidth() - (cardW + pad);
        x = Math.max(Ui.dp(a, 4) - pad, Math.min(screenW - cardW - pad - Ui.dp(a, 4), x));
        int y = o[1] + anchor.getHeight() - pad + Ui.dp(a, 4);
        pw.showAtLocation(anchor.getRootView(), Gravity.TOP | Gravity.LEFT, x, y);
        float anchorCenter = o[0] + anchor.getWidth() / 2f;
        card.setPivotX(Math.max(0f, Math.min(cardW, anchorCenter - (x + pad))));
        card.setPivotY(0f);
        card.setScaleX(0.55f);
        card.setScaleY(0.55f);
        card.setAlpha(0f);
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(240).setInterpolator(new OvershootInterpolator(1.1f)).start();
        return pw;
    }
}
