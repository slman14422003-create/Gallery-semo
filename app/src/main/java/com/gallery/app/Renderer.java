package com.gallery.app;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.util.Random;

/** Turns a source bitmap plus an {@link EditState} into the edited picture (used for preview and for saving). */
final class Renderer {
    private Renderer() {
    }

    static final int FILTERS = 14;

    private static Bitmap noiseTile;

    /** Rotation, flips and the fine "straighten" angle. */
    static Bitmap transform(Bitmap src, EditState s) {
        Bitmap b = src;
        if (s.rot != 0 || s.flipH || s.flipV) {
            Matrix m = new Matrix();
            m.postScale(s.flipH ? -1 : 1, s.flipV ? -1 : 1);
            m.postRotate(s.rot * 90f);
            b = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        }
        if (Math.abs(s.angle) > 0.05f) {
            Bitmap r = straighten(b, s.angle);
            if (b != src) b.recycle();
            b = r;
        }
        return b;
    }

    /** Rotates by a small angle and zooms just enough that no empty corners show. */
    static Bitmap straighten(Bitmap b, float deg) {
        int w = b.getWidth(), h = b.getHeight();
        double rad = Math.toRadians(Math.abs(deg));
        float k = (float) (Math.cos(rad) + Math.sin(rad) * Math.max((double) w / h, (double) h / w));
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Matrix m = new Matrix();
        m.postScale(k, k, w / 2f, h / 2f);
        m.postRotate(deg, w / 2f, h / 2f);
        new Canvas(out).drawBitmap(b, m, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        return out;
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
            case 9:   // vintage
                m.set(new float[]{1.1f, 0.04f, 0, 0, 10, 0, 1.0f, 0, 0, 4, 0, 0.04f, 0.82f, 0, -4, 0, 0, 0, 1, 0});
                m.postConcat(satMatrix(0.72f));
                m.postConcat(contrast(0.9f, 14));
                break;
            case 10:  // cinema (warm highlights, cool shadows feel)
                m.set(new float[]{1.08f, 0, 0, 0, 2, 0, 0.98f, 0.03f, 0, 0, 0, 0.05f, 1.08f, 0, 6, 0, 0, 0, 1, 0});
                m.postConcat(satMatrix(1.12f));
                m.postConcat(contrast(1.18f, -6));
                break;
            case 11:  // sunset
                m.set(new float[]{1.18f, 0.05f, 0, 0, 8, 0, 0.95f, 0, 0, 0, 0, 0, 0.78f, 0, -6, 0, 0, 0, 1, 0});
                m.postConcat(satMatrix(1.2f));
                break;
            case 12:  // ocean
                m.set(new float[]{0.85f, 0, 0, 0, -4, 0, 1.02f, 0.06f, 0, 2, 0, 0.05f, 1.18f, 0, 8, 0, 0, 0, 1, 0});
                m.postConcat(satMatrix(1.1f));
                break;
            case 13:  // cyano: blue monochrome
                m.setSaturation(0f);
                m.postConcat(new ColorMatrix(new float[]{0.55f, 0, 0, 0, 0, 0, 0.8f, 0, 0, 4, 0, 0, 1.1f, 0, 22, 0, 0, 0, 1, 0}));
                m.postConcat(contrast(1.08f, 0));
                break;
            default:
        }
        return m;
    }

    private static ColorMatrix satMatrix(float s) {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(s);
        return m;
    }

    /** Contrast around mid grey, plus an offset in 0..255 units. */
    private static ColorMatrix contrast(float c, float offset) {
        float t = 127.5f * (1 - c) + offset;
        return new ColorMatrix(new float[]{c, 0, 0, 0, t, 0, c, 0, 0, t, 0, 0, c, 0, t, 0, 0, 0, 1, 0});
    }

    /** Blends a colour matrix with "no change": amount 0 = untouched picture, 1 = full effect. */
    private static ColorMatrix scaleEffect(ColorMatrix full, float amount) {
        float[] id = new ColorMatrix().getArray();
        float[] f = full.getArray();
        float[] o = new float[20];
        for (int i = 0; i < 20; i++) o[i] = id[i] + (f[i] - id[i]) * amount;
        return new ColorMatrix(o);
    }

    static ColorMatrix matrix(EditState s) {
        ColorMatrix cm = new ColorMatrix(preset(s.filter));
        if (s.filter != 0 && s.filterAmt < 0.999f) cm = scaleEffect(cm, Math.max(0f, s.filterAmt));
        if (s.saturation != 0) cm.postConcat(satMatrix(Math.max(0f, 1f + s.saturation)));
        if (s.exposure != 0) {
            float g = (float) Math.pow(2.0, s.exposure * 1.1);
            cm.postConcat(new ColorMatrix(new float[]{g, 0, 0, 0, 0, 0, g, 0, 0, 0, 0, 0, g, 0, 0, 0, 0, 0, 1, 0}));
        }
        if (s.contrast != 0 || s.brightness != 0) cm.postConcat(contrast(1f + s.contrast * 0.8f, s.brightness * 90f));
        if (s.warmth != 0) {
            float w = s.warmth;
            cm.postConcat(new ColorMatrix(new float[]{1 + 0.22f * w, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1 - 0.22f * w, 0, 0, 0, 0, 0, 1, 0}));
        }
        if (s.tint != 0) {
            float t = s.tint;   // + magenta, - green
            cm.postConcat(new ColorMatrix(new float[]{1 + 0.10f * t, 0, 0, 0, 0, 0, 1 - 0.18f * t, 0, 0, 0, 0, 0, 1 + 0.10f * t, 0, 0, 0, 0, 0, 1, 0}));
        }
        if (s.fade != 0) cm.postConcat(contrast(1f - 0.22f * s.fade, 12f * s.fade));
        return cm;
    }

    /** Colour adjustments, sharpness, vignette and grain onto a copy of {@code src}. */
    static Bitmap color(Bitmap src, EditState s) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        boolean tone = s.filter != 0 || s.exposure != 0 || s.brightness != 0 || s.contrast != 0 || s.saturation != 0
                || s.warmth != 0 || s.tint != 0 || s.fade != 0;
        if (tone) p.setColorFilter(new ColorMatrixColorFilter(matrix(s)));
        c.drawBitmap(src, 0, 0, p);
        float w = out.getWidth(), h = out.getHeight();
        if (s.sharpen > 0.01f) sharpen(out, s.sharpen);
        if (s.vignette > 0) {
            float r = (float) Math.hypot(w, h) / 2f;
            Paint v = new Paint(Paint.ANTI_ALIAS_FLAG);
            int edge = ((int) (s.vignette * 200)) << 24;
            v.setShader(new RadialGradient(w / 2f, h / 2f, r, new int[]{0x00000000, 0x00000000, edge},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, v);
        }
        if (s.grain > 0.01f) grain(c, w, h, s.grain);
        return out;
    }

    /** Film grain: a tiled noise picture blended with "overlay" so it only adds texture, not a grey haze. */
    private static void grain(Canvas c, float w, float h, float amount) {
        if (noiseTile == null) {
            int n = 160;
            int[] px = new int[n * n];
            Random rnd = new Random(7);
            for (int i = 0; i < px.length; i++) {
                int v = Math.max(0, Math.min(255, 128 + (int) (rnd.nextGaussian() * 46)));
                px[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
            noiseTile = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
        }
        Paint g = new Paint();
        BitmapShader sh = new BitmapShader(noiseTile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        Matrix m = new Matrix();
        float k = Math.max(1f, Math.max(w, h) / 1100f);   // same grain size on the preview and on the full-size save
        m.setScale(k, k);
        sh.setLocalMatrix(m);
        g.setShader(sh);
        g.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.OVERLAY));
        g.setAlpha(Math.round(Math.min(1f, amount) * 255));
        c.drawRect(0, 0, w, h, g);
    }

    /** Cross-shaped sharpen filter, row by row so a 12 MP picture needs only three rows of extra memory. */
    static void sharpen(Bitmap bmp, float amount) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        if (w < 3 || h < 3) return;
        float a = amount * 1.1f;
        int center = Math.round((1f + 4f * a) * 256f), side = Math.round(a * 256f);
        int[] prev = new int[w], cur = new int[w], next = new int[w], out = new int[w];
        bmp.getPixels(prev, 0, w, 0, 0, w, 1);
        bmp.getPixels(cur, 0, w, 0, 1, w, 1);
        for (int y = 1; y < h - 1; y++) {
            bmp.getPixels(next, 0, w, 0, y + 1, w, 1);
            out[0] = cur[0];
            out[w - 1] = cur[w - 1];
            for (int x = 1; x < w - 1; x++) {
                int c0 = cur[x], l = cur[x - 1], r = cur[x + 1], u = prev[x], d = next[x];
                int rr = (center * ((c0 >> 16) & 255) - side * (((l >> 16) & 255) + ((r >> 16) & 255) + ((u >> 16) & 255) + ((d >> 16) & 255))) >> 8;
                int gg = (center * ((c0 >> 8) & 255) - side * (((l >> 8) & 255) + ((r >> 8) & 255) + ((u >> 8) & 255) + ((d >> 8) & 255))) >> 8;
                int bb = (center * (c0 & 255) - side * ((l & 255) + (r & 255) + (u & 255) + (d & 255))) >> 8;
                rr = rr < 0 ? 0 : Math.min(rr, 255);
                gg = gg < 0 ? 0 : Math.min(gg, 255);
                bb = bb < 0 ? 0 : Math.min(bb, 255);
                out[x] = (c0 & 0xFF000000) | (rr << 16) | (gg << 8) | bb;
            }
            bmp.setPixels(out, 0, w, 0, y, w, 1);
            int[] t = prev;
            prev = cur;
            cur = next;
            next = t;
        }
    }

    static Bitmap crop(Bitmap b, EditState s) {
        Rect r = cropRect(b, s);
        if (r.left == 0 && r.top == 0 && r.right == b.getWidth() && r.bottom == b.getHeight()) return b;
        return Bitmap.createBitmap(b, r.left, r.top, r.width(), r.height());
    }

    // ------------------------------------------------------------------ text helpers (shared with the editor view)

    static Typeface labelFace(int font) {
        switch (font) {
            case 1:
                return Typeface.create(Typeface.SERIF, Typeface.BOLD);
            case 2:
                return Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
            case 3:
                return Typeface.create("cursive", Typeface.BOLD);
            default:
                return Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
        }
    }

    static Paint labelPaint(EditState.Label l, float h) {
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setTextAlign(Paint.Align.CENTER);
        t.setTypeface(labelFace(l.font));
        t.setTextSize(l.size * h);
        return t;
    }

    /** The box around a label, in picture pixels. */
    static RectF labelBounds(EditState.Label l, float w, float h) {
        Paint t = labelPaint(l, h);
        float tw = t.measureText(l.text);
        Paint.FontMetrics fm = t.getFontMetrics();
        float cx = l.x * w, cy = l.y * h;
        float base = cy - (fm.ascent + fm.descent) / 2f;
        float pad = l.size * h * 0.28f;
        return new RectF(cx - tw / 2f - pad, base + fm.ascent - pad * 0.5f, cx + tw / 2f + pad, base + fm.descent + pad * 0.5f);
    }

    /** Draws strokes, shapes and text; positions are fractions of the (cropped) picture. */
    static void overlays(Canvas c, int w, int h, EditState s) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        for (EditState.Stroke k : s.strokes) drawStroke(c, p, k, w, h);
        for (EditState.Label l : s.labels) {
            if (l.text.isEmpty()) continue;
            Paint t = labelPaint(l, h);
            Paint.FontMetrics fm = t.getFontMetrics();
            if (l.box != 0) {
                RectF r = labelBounds(l, w, h);
                Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
                bg.setColor(l.box == 1 ? 0xB8000000 : 0xDDFFFFFF);
                float rad = l.size * h * 0.3f;
                c.drawRoundRect(r, rad, rad, bg);
            } else {
                t.setShadowLayer(Math.max(2f, l.size * h * 0.06f), 0, 2f, 0x99000000);
            }
            t.setColor(l.color);
            float y = l.y * h - (fm.ascent + fm.descent) / 2f;
            c.drawText(l.text, l.x * w, y, t);
        }
    }

    private static void drawStroke(Canvas c, Paint p, EditState.Stroke k, int w, int h) {
        if (k.pts.isEmpty()) return;
        float sw = Math.max(1f, k.width * w);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(k.color);
        p.setStrokeWidth(sw);
        float[] f = k.pts.get(0);
        float[] e = k.pts.get(k.pts.size() - 1);
        float x0 = f[0] * w, y0 = f[1] * h, x1 = e[0] * w, y1 = e[1] * h;
        switch (k.type) {
            case EditState.LINE:
                c.drawLine(x0, y0, x1, y1, p);
                return;
            case EditState.ARROW: {
                c.drawLine(x0, y0, x1, y1, p);
                double ang = Math.atan2(y1 - y0, x1 - x0);
                float len = Math.max(sw * 4.2f, w * 0.03f);
                for (int i = -1; i <= 1; i += 2) {
                    double a = ang + Math.PI + i * Math.toRadians(27);
                    c.drawLine(x1, y1, x1 + (float) Math.cos(a) * len, y1 + (float) Math.sin(a) * len, p);
                }
                return;
            }
            case EditState.BOX:
                p.setStrokeJoin(Paint.Join.ROUND);
                c.drawRoundRect(new RectF(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1)), sw, sw, p);
                return;
            case EditState.OVAL:
                c.drawOval(new RectF(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1)), p);
                return;
            default:
        }
        if (k.type == EditState.MARKER) {
            p.setColor((k.color & 0x00FFFFFF) | 0x80000000);
            p.setStrokeWidth(sw * 2.6f);
            p.setStrokeCap(Paint.Cap.SQUARE);
        }
        Path path = new Path();
        path.moveTo(x0, y0);
        if (k.pts.size() == 1) {
            path.lineTo(x0 + 0.1f, y0);
        } else {
            // smooth the freehand line with quadratic curves through the midpoints
            float px = x0, py = y0;
            for (int i = 1; i < k.pts.size(); i++) {
                float[] q = k.pts.get(i);
                float qx = q[0] * w, qy = q[1] * h;
                path.quadTo(px, py, (px + qx) / 2f, (py + qy) / 2f);
                px = qx;
                py = qy;
            }
            path.lineTo(px, py);
        }
        c.drawPath(path, p);
        p.setStrokeCap(Paint.Cap.ROUND);
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
