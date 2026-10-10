package com.gallery.app;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/** Sizes its children to the video's shape: fit, stretch, crop-to-fill, 16:9 or 4:3. */
final class AspectFrame extends FrameLayout {
    private float ratio = 0f;
    private int mode = PlayerEngine.FIT;

    AspectFrame(Context c) {
        super(c);
    }

    void setVideoRatio(float r) {
        ratio = r;
        requestLayout();
    }

    void setMode(int m) {
        mode = m;
        requestLayout();
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws), h = MeasureSpec.getSize(hs);
        setMeasuredDimension(w, h);
        int cw = w, ch = h;
        float target = mode == PlayerEngine.R16_9 ? 16f / 9f : mode == PlayerEngine.R4_3 ? 4f / 3f : ratio;
        if (target > 0 && mode != PlayerEngine.STRETCH && w > 0 && h > 0) {
            float view = (float) w / h;
            if (mode == PlayerEngine.CROP) {
                if (view > target) ch = (int) (w / target);
                else cw = (int) (h * target);
            } else {
                if (view > target) cw = (int) (h * target);
                else ch = (int) (w / target);
            }
        }
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            int cw = c.getMeasuredWidth(), ch = c.getMeasuredHeight();
            int x = ((r - l) - cw) / 2, y = ((b - t) - ch) / 2;
            c.layout(x, y, x + cw, y + ch);
        }
    }
}
