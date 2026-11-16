package com.gallery.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;

final class OcrPrep {
    private static final int MIN_SIDE = 1500;
    private static final float MAX_SCALE = 3f;

    private OcrPrep() {
    }

    static Bitmap scaleUp(Bitmap src) {
        int side = Math.max(src.getWidth(), src.getHeight());
        if (side >= MIN_SIDE) return src;
        float k = Math.min(MAX_SCALE, MIN_SIDE / (float) side);
        return Bitmap.createScaledBitmap(src, Math.round(src.getWidth() * k), Math.round(src.getHeight() * k), true);
    }

    static Bitmap enhance(Bitmap src, boolean invert) {
        int[] range = levels(src);
        float gain = 255f / Math.max(48f, range[1] - range[0]);
        float off = -range[0] * gain;
        float r = 0.299f * gain, g = 0.587f * gain, b = 0.114f * gain;
        ColorMatrix cm = new ColorMatrix(new float[]{r, g, b, 0, off, r, g, b, 0, off, r, g, b, 0, off, 0, 0, 0, 1, 0});
        if (invert) cm.postConcat(new ColorMatrix(new float[]{-1, 0, 0, 0, 255, 0, -1, 0, 0, 255, 0, 0, -1, 0, 255, 0, 0, 0, 1, 0}));
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        new Canvas(out).drawBitmap(src, 0, 0, p);
        return out;
    }

    private static int[] levels(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        float k = Math.min(1f, 256f / Math.max(w, h));
        Bitmap s = k < 1f ? Bitmap.createScaledBitmap(src, Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), true) : src;
        int sw = s.getWidth(), sh = s.getHeight();
        int[] px = new int[sw * sh];
        s.getPixels(px, 0, sw, 0, 0, sw, sh);
        if (s != src) s.recycle();
        int[] hist = new int[256];
        for (int c : px) hist[(Color.red(c) * 77 + Color.green(c) * 151 + Color.blue(c) * 28) >> 8]++;
        int cut = px.length / 100;
        int lo = 0, acc = 0;
        while (lo < 255 && acc + hist[lo] <= cut) acc += hist[lo++];
        int hi = 255;
        acc = 0;
        while (hi > 0 && acc + hist[hi] <= cut) acc += hist[hi--];
        return new int[]{lo, Math.max(hi, lo + 1)};
    }
}
