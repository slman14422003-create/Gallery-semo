package com.gallery.app;

import android.content.Context;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

final class VideoStage extends FrameLayout {
    private final TextureView video;
    private final View mask;
    private final List<TextView> labels = new ArrayList<>();
    private List<VideoFx.Overlay> items = new ArrayList<>();
    private String looks = "";
    private float ratio = 16f / 9f;
    private int rot;
    private boolean flipH, flipV, fit;

    VideoStage(Context c) {
        super(c);
        setClipToOutline(true);
        setOutlineProvider(ViewOutlineProvider.BOUNDS);
        video = new TextureView(c);
        addView(video, new LayoutParams(-2, -2));
        mask = new View(c);
        mask.setBackgroundColor(0xFF000000);
        mask.setAlpha(0f);
        addView(mask, new LayoutParams(-1, -1));
    }

    TextureView surface() {
        return video;
    }

    void setVideoRatio(float r) {
        if (r <= 0f || r == ratio) return;
        ratio = r;
        requestLayout();
    }

    void setGeometry(int quarterTurns, boolean horizontal, boolean vertical, boolean contain) {
        boolean relayout = (quarterTurns & 1) != (rot & 1) || contain != fit;
        rot = quarterTurns & 3;
        flipH = horizontal;
        flipV = vertical;
        fit = contain;
        video.setRotation(rot * 90f);
        video.setScaleX(flipH ? -1f : 1f);
        video.setScaleY(flipV ? -1f : 1f);
        if (relayout) requestLayout();
    }

    void setColor(ColorMatrix cm) {
        Paint p = null;
        if (cm != null) {
            p = new Paint();
            p.setColorFilter(new ColorMatrixColorFilter(cm));
        }
        video.setLayerPaint(p);
    }

    void setFade(float amount) {
        float a = Math.max(0f, Math.min(1f, amount));
        if (mask.getAlpha() != a) mask.setAlpha(a);
    }

    void setOverlays(List<VideoFx.Overlay> list) {
        items = list;
        StringBuilder sb = new StringBuilder();
        for (VideoFx.Overlay o : list) {
            sb.append(o.text).append('|').append(o.size).append('|').append(o.color).append('|').append(o.sticker).append('|').append(o.box).append(';');
        }
        String key = sb.toString();
        if (key.equals(looks)) {
            place();
            return;
        }
        looks = key;
        applyLooks();
    }

    void moveOverlays() {
        place();
    }

    private TextView newLabel() {
        TextView tv = new TextView(getContext());
        tv.setIncludeFontPadding(false);
        tv.setPadding(0, 0, 0, 0);
        tv.setGravity(android.view.Gravity.CENTER);
        addView(tv, getChildCount() - 1, new LayoutParams(-2, -2));
        labels.add(tv);
        return tv;
    }

    private void applyLooks() {
        int base = Math.min(getWidth(), getHeight());
        if (base <= 0) return;
        int n = 0;
        for (VideoFx.Overlay o : items) {
            if (o.text == null || o.text.isEmpty()) continue;
            TextView tv = n < labels.size() ? labels.get(n) : newLabel();
            n++;
            tv.setText(o.text);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(12f, o.size * base));
            tv.setTextColor(o.color);
            tv.setTypeface(o.sticker ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD);
            tv.setBackgroundColor(o.box && !o.sticker ? 0xAA000000 : 0);
        }
        while (labels.size() > n) removeView(labels.remove(labels.size() - 1));
        requestLayout();
    }

    private void place() {
        int w = getWidth(), h = getHeight();
        int n = 0;
        for (VideoFx.Overlay o : items) {
            if (o.text == null || o.text.isEmpty()) continue;
            if (n >= labels.size()) break;
            TextView tv = labels.get(n++);
            tv.setTranslationX((o.x + 1f) / 2f * w - tv.getMeasuredWidth() / 2f);
            tv.setTranslationY((1f - o.y) / 2f * h - tv.getMeasuredHeight() / 2f);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        applyLooks();
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws), h = MeasureSpec.getSize(hs);
        setMeasuredDimension(w, h);
        boolean odd = (rot & 1) == 1;
        float nw = odd ? 1f : ratio, nh = odd ? ratio : 1f;
        float s = w > 0 && h > 0 ? (fit ? Math.min(w / nw, h / nh) : Math.max(w / nw, h / nh)) : 0f;
        video.measure(MeasureSpec.makeMeasureSpec(Math.max(1, Math.round(ratio * s)), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(1, Math.round(s)), MeasureSpec.EXACTLY));
        mask.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        for (TextView tv : labels) {
            tv.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST));
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        int vw = video.getMeasuredWidth(), vh = video.getMeasuredHeight();
        video.layout((w - vw) / 2, (h - vh) / 2, (w + vw) / 2, (h + vh) / 2);
        for (TextView tv : labels) tv.layout(0, 0, tv.getMeasuredWidth(), tv.getMeasuredHeight());
        mask.layout(0, 0, w, h);
        place();
    }
}
