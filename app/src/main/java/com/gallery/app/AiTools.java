package com.gallery.app;

import android.graphics.Bitmap;

/**
 * On-device "AI" image tools built from classic, well-known algorithms (dark channel prior dehazing, local tone
 * mapping, gradient-orientation horizon detection). No models, no extra libraries and no network, so the app
 * does not grow.
 */
final class AiTools {
    private AiTools() {
    }

    // ------------------------------------------------------------------ dehaze (dark channel prior)

    static void dehaze(Bitmap bmp, float amount) {
        if (amount < 0.01f) return;
        final int w = bmp.getWidth(), h = bmp.getHeight();
        if (w < 8 || h < 8) return;
        int sw = Math.min(w, 200), sh = Math.max(4, sw * h / w);
        Bitmap small = Bitmap.createScaledBitmap(bmp, sw, sh, true);
        int[] sp = new int[sw * sh];
        small.getPixels(sp, 0, sw, 0, 0, sw, sh);
        if (small != bmp) small.recycle();
        int n = sw * sh;
        float[] dark = new float[n];
        for (int i = 0; i < n; i++) {
            int p = sp[i];
            dark[i] = Math.min((p >> 16) & 255, Math.min((p >> 8) & 255, p & 255)) / 255f;
        }
        float[] dmin = minFilter(dark, sw, sh, 2);
        int[] hist = new int[256];
        for (int i = 0; i < n; i++) hist[Math.min(255, (int) (dmin[i] * 255f))]++;
        int need = Math.max(1, n / 100), acc = 0, thr = 255;
        for (int b = 255; b >= 0; b--) {
            acc += hist[b];
            if (acc >= need) {
                thr = b;
                break;
            }
        }
        float ar = 0, ag = 0, ab = 0;
        int cnt = 0;
        for (int i = 0; i < n; i++) {
            if (dmin[i] * 255f >= thr) {
                int p = sp[i];
                ar += (p >> 16) & 255;
                ag += (p >> 8) & 255;
                ab += p & 255;
                cnt++;
            }
        }
        if (cnt == 0) return;
        ar = clamp(ar / cnt, 140f, 250f);
        ag = clamp(ag / cnt, 140f, 250f);
        ab = clamp(ab / cnt, 140f, 250f);
        float[] t = new float[n];
        for (int i = 0; i < n; i++) {
            int p = sp[i];
            t[i] = Math.min(((p >> 16) & 255) / ar, Math.min(((p >> 8) & 255) / ag, (p & 255) / ab));
        }
        t = minFilter(t, sw, sh, 2);
        float omega = 0.95f * amount;
        for (int i = 0; i < n; i++) t[i] = clamp(1f - omega * t[i], 0.25f, 1f);
        t = boxBlur(boxBlur(t, sw, sh, 2), sw, sh, 2);

        int[] x0 = new int[w];
        float[] xf = new float[w];
        for (int x = 0; x < w; x++) {
            float fx = w > 1 ? x * (sw - 1f) / (w - 1f) : 0f;
            x0[x] = Math.min(sw - 2, (int) fx);
            xf[x] = fx - x0[x];
        }
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            bmp.getPixels(row, 0, w, 0, y, w, 1);
            float fy = h > 1 ? y * (sh - 1f) / (h - 1f) : 0f;
            int y0 = Math.min(sh - 2, (int) fy);
            float yf = fy - y0;
            for (int x = 0; x < w; x++) {
                int i0 = y0 * sw + x0[x];
                float a = t[i0] + (t[i0 + 1] - t[i0]) * xf[x];
                float b = t[i0 + sw] + (t[i0 + sw + 1] - t[i0 + sw]) * xf[x];
                float tt = a + (b - a) * yf;
                int p = row[x];
                int r = (int) ((((p >> 16) & 255) - ar) / tt + ar);
                int g = (int) ((((p >> 8) & 255) - ag) / tt + ag);
                int bl = (int) (((p & 255) - ab) / tt + ab);
                row[x] = (p & 0xFF000000) | (c255(r) << 16) | (c255(g) << 8) | c255(bl);
            }
            bmp.setPixels(row, 0, w, 0, y, w, 1);
        }
    }

    // ------------------------------------------------------------------ local tone: clarity + shadow lift

    static void localTone(Bitmap bmp, float clarity, float shadows) {
        if (clarity < 0.01f && shadows < 0.01f) return;
        final int w = bmp.getWidth(), h = bmp.getHeight();
        if (w < 8 || h < 8) return;
        int sw = Math.max(8, Math.min(w, Math.max(8, w / 20))), sh = Math.max(8, sw * h / w);
        Bitmap small = Bitmap.createScaledBitmap(bmp, sw, sh, true);
        int[] sp = new int[sw * sh];
        small.getPixels(sp, 0, sw, 0, 0, sw, sh);
        if (small != bmp) small.recycle();
        float[] mean = new float[sw * sh];
        for (int i = 0; i < mean.length; i++) {
            int p = sp[i];
            mean[i] = (0.299f * ((p >> 16) & 255) + 0.587f * ((p >> 8) & 255) + 0.114f * (p & 255)) / 255f;
        }
        mean = boxBlur(mean, sw, sh, 1);
        int[] x0 = new int[w];
        float[] xf = new float[w];
        for (int x = 0; x < w; x++) {
            float fx = x * (sw - 1f) / Math.max(1, w - 1);
            x0[x] = Math.min(sw - 2, (int) fx);
            xf[x] = fx - x0[x];
        }
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            bmp.getPixels(row, 0, w, 0, y, w, 1);
            float fy = y * (sh - 1f) / Math.max(1, h - 1);
            int y0 = Math.min(sh - 2, (int) fy);
            float yf = fy - y0;
            for (int x = 0; x < w; x++) {
                int i0 = y0 * sw + x0[x];
                float a = mean[i0] + (mean[i0 + 1] - mean[i0]) * xf[x];
                float b = mean[i0 + sw] + (mean[i0 + sw + 1] - mean[i0 + sw]) * xf[x];
                float local = a + (b - a) * yf;
                int p = row[x];
                int r = (p >> 16) & 255, g = (p >> 8) & 255, bl = p & 255;
                float lum = (0.299f * r + 0.587f * g + 0.114f * bl) / 255f;
                float ny = lum;
                if (shadows > 0f) {
                    float dk = 1f - local;
                    ny += shadows * 0.65f * dk * dk * (1f - lum);
                }
                if (clarity > 0f) ny += clarity * 1.1f * (lum - local) * (1f - 0.5f * Math.abs(2f * local - 1f));
                ny = clamp(ny, 0f, 1f);
                float k = clamp((ny + 0.004f) / (lum + 0.004f), 0.3f, 4f);
                row[x] = (p & 0xFF000000) | (c255(Math.round(r * k)) << 16) | (c255(Math.round(g * k)) << 8) | c255(Math.round(bl * k));
            }
            bmp.setPixels(row, 0, w, 0, y, w, 1);
        }
    }

    // ------------------------------------------------------------------ suggestions for the one-tap AI button

    /** {dehaze, clarity, shadows}, each 0..1, read from the picture's own statistics. */
    static float[] suggest(Bitmap src) {
        float[] out = new float[3];
        if (src == null || src.getWidth() < 8 || src.getHeight() < 8) return out;
        int sw = 96, sh = Math.max(4, 96 * src.getHeight() / src.getWidth());
        Bitmap small = Bitmap.createScaledBitmap(src, sw, sh, true);
        int[] px = new int[sw * sh];
        small.getPixels(px, 0, sw, 0, 0, sw, sh);
        small.recycle();
        int n = px.length;
        float[] dark = new float[n];
        double sum = 0, sum2 = 0;
        int[] hist = new int[256];
        for (int i = 0; i < n; i++) {
            int p = px[i];
            int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            dark[i] = Math.min(r, Math.min(g, b)) / 255f;
            double y = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
            sum += y;
            sum2 += y * y;
            hist[(int) (y * 255)]++;
        }
        float[] dmin = minFilter(dark, sw, sh, 1);
        double dm = 0;
        for (float d : dmin) dm += d;
        dm /= n;
        double mean = sum / n, sd = Math.sqrt(Math.max(1e-6, sum2 / n - mean * mean));
        int darkCount = 0, acc = 0, p95 = 255;
        for (int i = 0; i < 51; i++) darkCount += hist[i];
        for (int i = 0; i < 256; i++) {
            acc += hist[i];
            if (acc >= n * 0.95) {
                p95 = i;
                break;
            }
        }
        if (dm > 0.2 && mean > 0.35 && sd < 0.28) out[0] = clamp((float) ((dm - 0.18) * 2.2), 0f, 0.7f);
        float frac = darkCount / (float) n;
        if (p95 > 150) out[2] = clamp((frac - 0.12f) * 2f, 0f, 0.6f);
        out[1] = clamp((float) ((0.24 - sd) * 2.5), 0.1f, 0.45f);
        return out;
    }

    // ------------------------------------------------------------------ auto straighten

    /** Degrees to pass to the straighten control, or 0 when the picture has no clear horizon / vertical lines. */
    static float straightenAngle(Bitmap src) {
        if (src == null) return 0f;
        int max = 320;
        float sc = Math.min(1f, max / (float) Math.max(src.getWidth(), src.getHeight()));
        int w = Math.max(8, Math.round(src.getWidth() * sc)), h = Math.max(8, Math.round(src.getHeight() * sc));
        Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        small.recycle();
        float[] gr = new float[w * h];
        for (int i = 0; i < gr.length; i++) {
            int p = px[i];
            gr[i] = 0.299f * ((p >> 16) & 255) + 0.587f * ((p >> 8) & 255) + 0.114f * (p & 255);
        }
        float[] hist = new float[181];
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                float gx = (gr[i - w + 1] + 2 * gr[i + 1] + gr[i + w + 1]) - (gr[i - w - 1] + 2 * gr[i - 1] + gr[i + w - 1]);
                float gy = (gr[i + w - 1] + 2 * gr[i + w] + gr[i + w + 1]) - (gr[i - w - 1] + 2 * gr[i - w] + gr[i - w + 1]);
                float mag = (float) Math.sqrt(gx * gx + gy * gy);
                if (mag < 60f) continue;
                double deg = Math.toDegrees(Math.atan2(gy, gx));
                double dev = ((deg % 90.0) + 135.0) % 90.0 - 45.0;
                if (Math.abs(dev) > 20.0) continue;
                hist[(int) Math.round((dev + 45.0) * 2.0)] += mag;
            }
        }
        float[] sm = new float[hist.length];
        for (int i = 1; i < hist.length - 1; i++) sm[i] = (hist[i - 1] + 2 * hist[i] + hist[i + 1]) / 4f;
        int best = -1;
        float bv = 0, total = 0;
        int lo = (int) ((45 - 20) * 2), hi = (int) ((45 + 20) * 2);
        for (int i = lo; i <= hi; i++) {
            total += sm[i];
            if (sm[i] > bv) {
                bv = sm[i];
                best = i;
            }
        }
        if (best < 0 || total <= 0) return 0f;
        float avg = total / (hi - lo + 1);
        if (bv < avg * 2.2f) return 0f;
        float dev = best / 2f - 45f;
        if (Math.abs(dev) < 0.4f) return 0f;
        return clamp(-dev, -20f, 20f);
    }

    // ------------------------------------------------------------------ video helper

    /** True for an (almost) black frame; used to find black lead-in / lead-out when trimming. */
    static boolean isDark(Bitmap b) {
        if (b == null) return true;
        Bitmap s = Bitmap.createScaledBitmap(b, 8, 8, true);
        int[] px = new int[64];
        s.getPixels(px, 0, 8, 0, 0, 8, 8);
        s.recycle();
        double sum = 0;
        for (int p : px) sum += 0.299 * ((p >> 16) & 255) + 0.587 * ((p >> 8) & 255) + 0.114 * (p & 255);
        return sum / 64.0 < 14.0;
    }

    // ------------------------------------------------------------------ small helpers

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static int c255(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    private static float[] minFilter(float[] a, int w, int h, int r) {
        float[] tmp = new float[a.length], out = new float[a.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float m = Float.MAX_VALUE;
                for (int k = Math.max(0, x - r); k <= Math.min(w - 1, x + r); k++) m = Math.min(m, a[y * w + k]);
                tmp[y * w + x] = m;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float m = Float.MAX_VALUE;
                for (int k = Math.max(0, y - r); k <= Math.min(h - 1, y + r); k++) m = Math.min(m, tmp[k * w + x]);
                out[y * w + x] = m;
            }
        }
        return out;
    }

    private static float[] boxBlur(float[] a, int w, int h, int r) {
        float[] tmp = new float[a.length], out = new float[a.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float s = 0;
                int c = 0;
                for (int k = Math.max(0, x - r); k <= Math.min(w - 1, x + r); k++) {
                    s += a[y * w + k];
                    c++;
                }
                tmp[y * w + x] = s / c;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float s = 0;
                int c = 0;
                for (int k = Math.max(0, y - r); k <= Math.min(h - 1, y + r); k++) {
                    s += tmp[k * w + x];
                    c++;
                }
                out[y * w + x] = s / c;
            }
        }
        return out;
    }
}
