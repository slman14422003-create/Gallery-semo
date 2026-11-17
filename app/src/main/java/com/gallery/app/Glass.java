package com.gallery.app;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

import eightbitlab.com.blurview.BlurTarget;
import eightbitlab.com.blurview.BlurView;

/** iOS-style glass: a BlurView with a rounded outline, a tinted overlay and a hairline edge. */
final class Glass {
    private Glass() {
    }

    /** Tint over media (viewer): always dark, whatever the app theme. */
    static final int OVER_MEDIA = 0x73000000;
    static final float RADIUS = 22f;

    /** Tint for bars over app content: white glass by day, dark glass by night. */
    static int overlay(Context c) {
        return Appearance.isNight(c) ? 0x8C1C1C1E : 0xA6FFFFFF;
    }

    /** cornerPx &lt; 0 means a full capsule / circle. */
    static void apply(BlurView bv, BlurTarget target, final float cornerPx, int overlay) {
        Context c = bv.getContext();
        bv.setupWith(target)
                .setFrameClearDrawable(new ColorDrawable(Ui.color(c, R.color.bg)))
                .setBlurRadius(RADIUS);
        bv.setOverlayColor(overlay);
        bv.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                float r = cornerPx < 0 ? Math.min(v.getWidth(), v.getHeight()) / 2f : cornerPx;
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
            }
        });
        bv.setClipToOutline(true);
        GradientDrawable edge = new GradientDrawable();
        edge.setColor(0x00000000);
        edge.setCornerRadius(cornerPx < 0 ? Ui.dp(c, 200) : cornerPx);
        edge.setStroke(Math.max(1, Ui.dp(c, 1) / 2), Appearance.isNight(c) || overlay == OVER_MEDIA ? 0x33FFFFFF : 0x22000000);
        bv.setForeground(edge);
    }

    /** Puts {@code child} on a glass panel (the child's own background is dropped). */
    static BlurView wrap(Context c, BlurTarget t, View child, float cornerPx, int overlay, FrameLayout.LayoutParams clp) {
        BlurView g = new BlurView(c);
        child.setBackground(null);
        g.addView(child, clp);
        apply(g, t, cornerPx, overlay);
        return g;
    }

    static BlurView wrap(Context c, BlurTarget t, View child, float cornerPx, int overlay) {
        return wrap(c, t, child, cornerPx, overlay, new FrameLayout.LayoutParams(-1, -1));
    }
}
