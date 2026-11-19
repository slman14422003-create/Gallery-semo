package com.gallery.app;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * iOS pull-down menu: a rounded card that springs out of the button, rows with a leading check for the current choice,
 * thick bands between groups, and submenus that open inside the same card (with a back row on top). Replaces the
 * option dialogs.
 */
final class PullDown {
    static final class Row {
        String label = "";
        String value;                 // grey text on the trailing side (submenus)
        int icon;                     // trailing icon, 0 = none
        boolean checked, destructive, keepOpen, separator;
        List<Row> children;           // submenu
        Runnable run;

        /** Called on a "keep open" row to update its check state after the tap. */
        Row onTap(Runnable r) {
            run = r;
            return this;
        }

        Row keepOpen() {
            keepOpen = true;
            return this;
        }
    }

    static Row action(String label, int icon, Runnable run) {
        Row r = new Row();
        r.label = label;
        r.icon = icon;
        r.run = run;
        return r;
    }

    static Row danger(String label, int icon, Runnable run) {
        Row r = action(label, icon, run);
        r.destructive = true;
        return r;
    }

    static Row choice(String label, boolean checked, Runnable run) {
        Row r = new Row();
        r.label = label;
        r.checked = checked;
        r.run = run;
        return r;
    }

    static Row sub(String label, String value, List<Row> children) {
        Row r = new Row();
        r.label = label;
        r.value = value;
        r.children = children;
        return r;
    }

    static Row separator() {
        Row r = new Row();
        r.separator = true;
        return r;
    }

    private final Activity a;
    private final PopupWindow pw;
    private final LinearLayout card;
    private final int cardW;
    private final List<List<Row>> stack = new ArrayList<>();
    private final List<String> titles = new ArrayList<>();

    private PullDown(Activity a, PopupWindow pw, LinearLayout card, int cardW) {
        this.a = a;
        this.pw = pw;
        this.card = card;
        this.cardW = cardW;
    }

    static PopupWindow show(Activity a, View anchor, List<Row> rows) {
        if (rows.isEmpty()) return null;
        final int pad = Ui.dp(a, 14);
        final int cardW = Ui.dp(a, 254);
        FrameLayout wrap = new FrameLayout(a);
        wrap.setPadding(pad, pad, pad, pad);
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.dp(a, 24));
        g.setColor(Ui.color(a, R.color.surface));
        g.setStroke(Math.max(1, Ui.dp(a, 1) / 2), Ui.color(a, R.color.stroke_soft));
        card.setBackground(g);
        card.setClipToOutline(true);
        card.setElevation(Ui.dp(a, 12));
        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setClipToOutline(true);
        sv.addView(card, new FrameLayout.LayoutParams(-1, -2));
        int maxH = Math.round(a.getResources().getDisplayMetrics().heightPixels * 0.62f);
        final PopupWindow pw = new PopupWindow(wrap, cardW + pad * 2, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        pw.setOutsideTouchable(true);
        wrap.addView(sv, new FrameLayout.LayoutParams(-1, -2));
        PullDown pd = new PullDown(a, pw, card, cardW);
        pd.stack.add(rows);
        pd.titles.add(null);
        pd.render(false);

        card.measure(View.MeasureSpec.makeMeasureSpec(cardW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int h = Math.min(maxH, card.getMeasuredHeight());
        wrap.removeAllViews();
        wrap.addView(sv, new FrameLayout.LayoutParams(-1, h));

        boolean rtl = a.getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        int[] o = new int[2];
        anchor.getLocationOnScreen(o);
        int screenW = a.getResources().getDisplayMetrics().widthPixels;
        int x = rtl ? o[0] - pad : o[0] + anchor.getWidth() - (cardW + pad);
        x = Math.max(Ui.dp(a, 4) - pad, Math.min(screenW - cardW - pad - Ui.dp(a, 4), x));
        int screenH = a.getResources().getDisplayMetrics().heightPixels;
        int below = o[1] + anchor.getHeight() - pad + Ui.dp(a, 6);
        boolean up = below + h + pad > screenH - Ui.dp(a, 24) && o[1] > screenH / 2;
        int y = up ? o[1] - h - pad - Ui.dp(a, 6) : below;           // near the bottom edge the menu opens upward
        pw.showAtLocation(anchor.getRootView(), Gravity.TOP | Gravity.LEFT, x, y);
        float anchorCenter = o[0] + anchor.getWidth() / 2f;
        sv.setPivotX(Math.max(0f, Math.min(cardW, anchorCenter - (x + pad))));
        sv.setPivotY(up ? h : 0f);
        sv.setScaleX(0.5f);
        sv.setScaleY(0.35f);
        sv.setAlpha(0f);
        sv.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(320)
                .setInterpolator(new android.view.animation.OvershootInterpolator(0.9f)).start();
        return pw;
    }

    private void render(boolean animate) {
        List<Row> rows = stack.get(stack.size() - 1);
        card.removeAllViews();
        if (stack.size() > 1) {                                    // submenu: back row with the title
            LinearLayout back = new LinearLayout(a);
            back.setGravity(Gravity.CENTER_VERTICAL);
            back.setPaddingRelative(Ui.dp(a, 12), 0, Ui.dp(a, 18), 0);
            back.setBackground(ripple());
            ImageView ch = new ImageView(a);
            ch.setImageResource(R.drawable.ic_chevron);
            ch.setRotation(a.getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? 0f : 180f);
            ch.setImageTintList(ColorStateList.valueOf(Ui.color(a, R.color.accent)));
            back.addView(ch, new LinearLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24)));
            TextView t = new TextView(a);
            t.setText(titles.get(titles.size() - 1));
            t.setTextSize(17f);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            t.setTextColor(Ui.color(a, R.color.text_primary));
            t.setPaddingRelative(Ui.dp(a, 6), 0, 0, 0);
            back.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            back.setOnClickListener(v -> {
                stack.remove(stack.size() - 1);
                titles.remove(titles.size() - 1);
                render(true);
            });
            card.addView(back, new LinearLayout.LayoutParams(-1, Ui.dp(a, 50)));
            card.addView(band(Ui.dp(a, 6)));
        }
        boolean anyCheck = false;
        for (Row r : rows) anyCheck |= r.checked || (r.run != null && !r.separator && r.children == null && isChoice(r));
        boolean prevWasRow = false;
        for (int i = 0; i < rows.size(); i++) {
            final Row r = rows.get(i);
            if (r.separator) {
                card.addView(band(Ui.dp(a, 8)));
                prevWasRow = false;
                continue;
            }
            if (prevWasRow) card.addView(hair());
            card.addView(rowView(r, anyCheck), new LinearLayout.LayoutParams(-1, Ui.dp(a, 50)));
            prevWasRow = true;
        }
        if (animate) {
            card.setAlpha(0f);
            card.animate().alpha(1f).setDuration(180).start();
        }
    }

    private static boolean isChoice(Row r) {
        return r.icon == 0 && r.value == null && !r.destructive;
    }

    private View rowView(final Row r, boolean reserveCheck) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPaddingRelative(Ui.dp(a, reserveCheck ? 10 : 18), 0, Ui.dp(a, 16), 0);
        row.setBackground(ripple());
        int col = r.destructive ? Ui.color(a, R.color.bad) : Ui.color(a, R.color.text_primary);
        if (reserveCheck) {                                         // leading check column keeps labels aligned
            ImageView ck = new ImageView(a);
            ck.setImageResource(R.drawable.ic_check);
            ck.setImageTintList(ColorStateList.valueOf(Ui.color(a, R.color.accent)));
            ck.setVisibility(r.checked ? View.VISIBLE : View.INVISIBLE);
            row.addView(ck, new LinearLayout.LayoutParams(Ui.dp(a, 26), Ui.dp(a, 22)));
        }
        TextView t = new TextView(a);
        t.setText(r.label);
        t.setTextSize(17f);
        t.setTextColor(col);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        if (r.value != null) {
            TextView v = new TextView(a);
            v.setText(r.value);
            v.setTextSize(15f);
            v.setTextColor(Ui.color(a, R.color.text_secondary));
            v.setPaddingRelative(Ui.dp(a, 8), 0, Ui.dp(a, 4), 0);
            row.addView(v);
        }
        if (r.children != null) {
            ImageView ch = new ImageView(a);
            ch.setImageResource(R.drawable.ic_chevron);
            ch.setRotation(a.getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? 180f : 0f);
            ch.setImageTintList(ColorStateList.valueOf(Ui.color(a, R.color.text_secondary)));
            row.addView(ch, new LinearLayout.LayoutParams(Ui.dp(a, 20), Ui.dp(a, 20)));
        } else if (r.icon != 0) {
            ImageView ic = new ImageView(a);
            ic.setImageResource(r.icon);
            ic.setImageTintList(ColorStateList.valueOf(col));
            row.addView(ic, new LinearLayout.LayoutParams(Ui.dp(a, 22), Ui.dp(a, 22)));
        }
        row.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            if (r.children != null) {
                stack.add(r.children);
                titles.add(r.label);
                render(true);
                return;
            }
            if (r.run != null) r.run.run();
            if (r.keepOpen) {
                // a choice inside a group: move the check to the tapped row and stay open
                List<Row> rows = stack.get(stack.size() - 1);
                int mine = rows.indexOf(r);
                int from = mine, to = mine;
                while (from > 0 && !rows.get(from - 1).separator) from--;
                while (to < rows.size() - 1 && !rows.get(to + 1).separator) to++;
                for (int i = from; i <= to; i++) rows.get(i).checked = i == mine;
                render(false);
            } else {
                pw.dismiss();
            }
        });
        return row;
    }

    private RippleDrawable ripple() {
        return new RippleDrawable(ColorStateList.valueOf(Ui.color(a, R.color.ripple)), null, new ColorDrawable(0xFFFFFFFF));
    }

    private View hair() {
        View d = new View(a);
        d.setBackgroundColor(Ui.color(a, R.color.stroke_soft));
        d.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, Ui.dp(a, 1) / 2)));
        return d;
    }

    private View band(int h) {
        View d = new View(a);
        d.setBackgroundColor(Ui.color(a, R.color.neutral_soft));
        d.setLayoutParams(new LinearLayout.LayoutParams(-1, h));
        return d;
    }
}
