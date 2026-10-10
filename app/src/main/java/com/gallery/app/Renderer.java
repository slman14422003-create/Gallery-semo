package com.gallery.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;

/** Turns a source bitmap plus an {@link EditState} into the edited picture (used for preview and for saving). */
final class Renderer {
    private Renderer() {
    }

    static final int FILTERS = 9;

    /** Rotation and flips. */
    static Bitmap transform(Bitmap src, EditState s) {
        if (s.rot == 0 && !s.flipH && !s.flipV) return src;
        Matrix m = new Matrix();
        m.postScale(s.flipH ? -1 : 1, s.flipV ? -1 : 1);
        m.postRotate(s.rot * 90f);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

    static Rect cropRect(Bitmap b, EditState s) {
        int l = Math.round(s.cl * b.getWidth()), t = Math.round(s.ct * b.getHeight());
        int r = Math.round(s.cr * b.getWidth()), bt = Math.round(s.cb * b.getHeight());
        l = Math.max(0, Math.min(l, b.getWidth() - 2));
        t = Math.max(0, Math.min(t, b.getHeight() - 2));
        r = Math.max(l + 2, Math.min(r, b.getWidth()));
        bt = Math.max(t + 2, Math.min(bt, b.getHeight()));
        return new Rect(l, t, r, bt);
    }

    static ColorMatrix preset(int f) {
        ColorMatrix m = new ColorMatrix();
        switch (f) {
            case 1:   // vivid
                m.setSaturation(1.45f);
                m.postConcat(contrast(1.1f, 0));
                break;
            case 2:   // warm
                m.set(new float[]{1.12f, 0, 0, 0, 6, 0, 1.0f, 0, 0, 0, 0, 0, 0.86f, 0, -6, 0, 0, 0, 1, 0});
                break;
            case 3:   // cool
                m.set(new float[]{0.88f, 0, 0, 0, -4, 0, 1.0f, 0, 0, 0, 0, 0, 1.14f, 0, 8, 0, 0, 0, 1, 0});
                break;
            case 4:   // black & white
                m.setSaturation(0f);
                break;
            case 5:   // noir
                m.setSaturation(0f);
                m.postConcat(contrast(1.35f, -10));
                break;
            case 6:   // sepia
                m.set(new float[]{0.393f, 0.769f, 0.189f, 0, 0, 0.349f, 0.686f, 0.168f, 0, 0, 0.272f, 0.534f, 0.131f, 0, 0, 0, 0, 0, 1, 0});
                break;
            case 7:   // fade
                m.setSaturation(0.8f);
                m.postConcat(contrast(0.82f, 28));
                break;
            case 8:   // dramatic
                m.setSaturation(0.75f);
                m.postConcat(contrast(1.3f, -8));
                break;
            default:
        }
        return m;
    }

    /** Contrast around mid grey, plus an offset in 0..255 units. */
    private static ColorMatrix contrast(float c, float offset) {
        float t = 127.5f * (1 - c) + offset;
        return new ColorMatrix(new float[]{c, 0, 0, 0, t, 0, c, 0, 0, t, 0, 0, c, 0, t, 0, 0, 0, 1, 0});
    }

    static ColorMatrix matrix(EditState s) {
        ColorMatrix cm = new ColorMatrix(preset(s.filter));
        if (s.saturation != 0) {
            ColorMatrix sat = new ColorMatrix();
            sat.setSaturation(Math.max(0f, 1f + s.saturation));
            cm.postConcat(sat);
        }
        if (s.contrast != 0 || s.brightness != 0) cm.postConcat(contrast(1f + s.contrast * 0.8f, s.brightness * 90f));
        if (s.warmth != 0) {
            float w = s.warmth;
            cm.postConcat(new ColorMatrix(new float[]{1 + 0.22f * w, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1 - 0.22f * w, 0, 0, 0, 0, 0, 1, 0}));
        }
        return cm;
    }

    /** Colour adjustments and the vignette onto a copy of {@code src}. */
    static Bitmap color(Bitmap src, EditState s) {
        boolean plain = s.filter == 0 && s.brightness == 0 && s.contrast == 0 && s.saturation == 0 && s.warmth == 0 && s.vignette == 0;
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        if (!plain) p.setColorFilter(new ColorMatrixColorFilter(matrix(s)));
        c.drawBitmap(src, 0, 0, p);
        if (s.vignette > 0) {
            float w = out.getWidth(), h = out.getHeight();
            float r = (float) Math.hypot(w, h) / 2f;
            Paint v = new Paint(Paint.ANTI_ALIAS_FLAG);
            int edge = ((int) (s.vignette * 200)) << 24;
            v.setShader(new RadialGradient(w / 2f, h / 2f, r, new int[]{0x00000000, 0x00000000, edge},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, v);
        }
        return out;
    }

    static Bitmap crop(Bitmap b, EditState s) {
        Rect r = cropRect(b, s);
        if (r.left == 0 && r.top == 0 && r.right == b.getWidth() && r.bottom == b.getHeight()) return b;
        return Bitmap.createBitmap(b, r.left, r.top, r.width(), r.height());
    }

    /** Draws strokes and text, positions are fractions of the (cropped) picture. */
    static void overlays(Canvas c, int w, int h, EditState s) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        for (EditState.Stroke k : s.strokes) {
            if (k.pts.isEmpty()) continue;
            p.setColor(k.color);
            p.setStrokeWidth(Math.max(1f, k.width * w));
            Path path = new Path();
            float[] f = k.pts.get(0);
            path.moveTo(f[0] * w, f[1] * h);
            if (k.pts.size() == 1) path.lineTo(f[0] * w + 0.1f, f[1] * h);
            for (int i = 1; i < k.pts.size(); i++) {
                float[] q = k.pts.get(i);
                path.lineTo(q[0] * w, q[1] * h);
            }
            c.drawPath(path, p);
        }
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setTextAlign(Paint.Align.CENTER);
        t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        for (EditState.Label l : s.labels) {
            if (l.text.isEmpty()) continue;
            t.setTextSize(l.size * h);
            t.setColor(l.color);
            t.setShadowLayer(Math.max(2f, l.size * h * 0.06f), 0, 2f, 0x99000000);
            float y = l.y * h - (t.ascent() + t.descent()) / 2f;
            c.drawText(l.text, l.x * w, y, t);
        }
    }

    /** The finished picture. */
    static Bitmap finish(Bitmap src, EditState s) {
        Bitmap t = transform(src, s);
        Bitmap cr = crop(t, s);
        Bitmap col = color(cr, s);
        if (t != src && t != cr) t.recycle();
        Canvas c = new Canvas(col);
        overlays(c, col.getWidth(), col.getHeight(), s);
        return col;
    }
}
