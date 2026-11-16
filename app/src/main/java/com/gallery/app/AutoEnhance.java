package com.gallery.app;

import android.graphics.Bitmap;

/**
 * Local AI (beta 3) — reads the photo's own pixels and suggests exposure / contrast / white-balance /
 * sharpen / denoise values, the same way a person would drag the sliders by hand. Everything here is a
 * well-understood, classic image-statistics technique (gray-world white balance, percentile contrast
 * stretch, a Laplacian high-frequency estimate) — not a bundled neural network — which is exactly why it
 * stays small, fast, and needs no model download: it runs fully on-device, with no network access at all,
 * same as the rest of this app.
 */
final class AutoEnhance {
    private AutoEnhance() {
    }

    static final class Result {
        float brightness, contrast, saturation, warmth, tint, sharpen, denoise;
    }

    /** {@code src} should be the editor's own working preview (already capped to a sane size); this does
     *  its own extra downsampling on top of that for the colour pass, and strides across {@code src}
     *  for the detail pass, so it stays well under a frame even on a large photo. */
    static Result analyze(Bitmap src) {
        Result r = new Result();
        if (src == null || src.getWidth() < 4 || src.getHeight() < 4) return r;

        analyzeColor(src, r);
        analyzeDetail(src, r);
        return r;
    }

    // ------------------------------------------------------------------ colour + light

    private static void analyzeColor(Bitmap src, Result r) {
        int sw = 96, sh = Math.max(1, 96 * src.getHeight() / Math.max(1, src.getWidth()));
        Bitmap small = Bitmap.createScaledBitmap(src, sw, sh, true);
        int[] px = new int[sw * sh];
        small.getPixels(px, 0, sw, 0, 0, sw, sh);
        small.recycle();

        long sumR = 0, sumG = 0, sumB = 0;
        double sumY = 0, sumY2 = 0;
        int[] hist = new int[256];
        for (int p : px) {
            int rr = (p >> 16) & 255, gg = (p >> 8) & 255, bb = p & 255;
            sumR += rr;
            sumG += gg;
            sumB += bb;
            double y = 0.299 * rr + 0.587 * gg + 0.114 * bb;
            sumY += y;
            sumY2 += y * y;
            hist[(int) y]++;
        }
        int n = px.length;
        float avgR = sumR / (float) n, avgG = sumG / (float) n, avgB = sumB / (float) n;
        float gray = (avgR + avgG + avgB) / 3f;
        double mean = sumY / n;
        double sd = Math.sqrt(Math.max(1.0, sumY2 / n - mean * mean));

        // 1st / 99th percentile luma: a few very dark or very bright pixels (night sky, a lamp) can't
        // swing this the way they swing a plain min/max stretch would.
        int lo = percentile(hist, n, 0.01f), hi = percentile(hist, n, 0.99f);
        float range = Math.max(1, hi - lo);

        r.brightness = clamp((float) ((124 - mean) * 0.5 / 90.0), -0.3f, 0.3f);

        float fromRange = 170f / range;
        float fromSd = (float) (58.0 / sd);
        float f = clamp((fromRange + fromSd) / 2f, 0.9f, 1.4f);
        r.contrast = clamp((f - 1f) / 0.8f, -0.4f, 0.45f);

        // Gray-world white balance: push the channel(s) that are off-neutral back towards the overall
        // average, expressed in the same warmth / tint units the manual sliders already use.
        float gainR = gray / Math.max(8f, avgR), gainG = gray / Math.max(8f, avgG), gainB = gray / Math.max(8f, avgB);
        r.warmth = clamp(((gainR - 1f) - (gainB - 1f)) / 0.44f, -0.3f, 0.3f);
        r.tint = clamp((1f - gainG) / 0.18f, -0.25f, 0.25f);

        r.saturation = 0.12f;   // a small, safe lift once the colour cast above is already corrected
    }

    // ------------------------------------------------------------------ sharpness + noise

    /** One strided Laplacian pass doubles as both signals: a soft/blurry photo needs more sharpening,
     *  a busy/grainy one needs a touch of noise reduction — each capped low enough to stay a gentle
     *  assist rather than something that could wreck a genuinely detailed shot. */
    private static void analyzeDetail(Bitmap src, Result r) {
        int w = src.getWidth(), h = src.getHeight();
        int step = Math.max(1, Math.max(w, h) / 480);
        if (w < 2 * step + 2 || h < 2 * step + 2) {
            r.sharpen = 0.2f;
            return;
        }
        int[] top = new int[w], mid = new int[w], bot = new int[w];
        double sumAbs = 0;
        long cnt = 0;
        for (int y = step; y < h - step; y += step) {
            src.getPixels(top, 0, w, 0, y - step, w, 1);
            src.getPixels(mid, 0, w, 0, y, w, 1);
            src.getPixels(bot, 0, w, 0, y + step, w, 1);
            for (int x = step; x < w - step; x += step) {
                // classic 3x3 Laplacian-of-Gaussian-ish mask: [[1,-2,1],[-2,4,-2],[1,-2,1]]
                double l = lumaAt(top, x - step) - 2 * lumaAt(top, x) + lumaAt(top, x + step)
                        - 2 * lumaAt(mid, x - step) + 4 * lumaAt(mid, x) - 2 * lumaAt(mid, x + step)
                        + lumaAt(bot, x - step) - 2 * lumaAt(bot, x) + lumaAt(bot, x + step);
                sumAbs += Math.abs(l);
                cnt++;
            }
        }
        float energy = cnt == 0 ? 6f : (float) (sumAbs / cnt);
        r.sharpen = clamp((9f - energy) / 14f, 0.08f, 0.45f);
        r.denoise = clamp((energy - 9f) / 30f, 0f, 0.3f);
    }

    private static double lumaAt(int[] row, int x) {
        int p = row[x];
        return 0.299 * ((p >> 16) & 255) + 0.587 * ((p >> 8) & 255) + 0.114 * (p & 255);
    }

    private static int percentile(int[] hist, int total, float p) {
        int target = Math.round(total * p);
        int run = 0;
        for (int i = 0; i < 256; i++) {
            run += hist[i];
            if (run >= target) return i;
        }
        return 255;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.min(hi, Math.max(lo, v));
    }
}
