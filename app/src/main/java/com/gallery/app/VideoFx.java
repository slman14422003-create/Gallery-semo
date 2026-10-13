package com.gallery.app;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import androidx.annotation.OptIn;
import androidx.media3.common.Effect;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.RgbMatrix;
import androidx.media3.effect.StaticOverlaySettings;
import androidx.media3.effect.TextOverlay;
import androidx.media3.effect.TextureOverlay;

import com.google.common.collect.ImmutableList;

import java.util.List;

/** Colour looks (fixed colour matrices) and text / emoji overlays shared by the video editor preview and export. */
@OptIn(markerClass = UnstableApi.class)
final class VideoFx {
    static final int LOOK_COUNT = 14;

    static final String[] EMOJI = {"\uD83D\uDE00", "\uD83D\uDE02", "\uD83D\uDE0D", "\uD83D\uDE0E", "\uD83E\uDD29", "\uD83D\uDD25", "\u2764\uFE0F", "\u2B50",
            "\uD83D\uDC4D", "\uD83D\uDC4F", "\uD83C\uDF89", "\uD83D\uDCAF", "\u2728", "\uD83C\uDF1F", "\uD83D\uDCA5", "\uD83C\uDF08",
            "\uD83C\uDFB5", "\uD83C\uDFAC", "\uD83D\uDCF8", "\uD83C\uDF39", "\uD83D\uDE80", "\uD83D\uDC51", "\uD83D\uDCA1", "\u26A1"};

    private static final float[] LUMA = {0.299f, 0.587f, 0.114f};

    private VideoFx() {
    }

    /** One text or emoji item placed on the video. Position is the anchor inside the frame, -1..1, y up. */
    static final class Overlay {
        String text;
        int color = Color.WHITE;
        float size = 0.07f;
        float x = 0f;
        float y = 0.6f;
        boolean sticker;
        boolean box;
    }

    /** A constant 4x4 colour matrix (column-major) applied to every frame. */
    static final class Fixed implements RgbMatrix {
        private final float[] m;

        Fixed(float[] m) {
            this.m = m;
        }

        @Override
        public float[] getMatrix(long presentationTimeUs, boolean useHdr) {
            return m;
        }
    }

    // ------------------------------------------------------------------ colour maths (3x3 rows + offset)

    private static float[] identity() {
        return new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
    }

    private static float[] saturation(float s) {
        float[] r = new float[9];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                r[row * 3 + col] = (1f - s) * LUMA[col] + (row == col ? s : 0f);
            }
        }
        return r;
    }

    private static float[] mix(float[] a, float[] b, float t) {
        float[] r = new float[9];
        for (int i = 0; i < 9; i++) r[i] = a[i] * (1f - t) + b[i] * t;
        return r;
    }

    private static float[] gray() {
        float[] r = new float[9];
        for (int row = 0; row < 3; row++) System.arraycopy(LUMA, 0, r, row * 3, 3);
        return r;
    }

    /** Applies a contrast factor around mid grey on top of rows + offset, then packs into a column-major 4x4. */
    private static Fixed pack(float[] rows, float ox, float oy, float oz, float contrast) {
        float[] m = new float[16];
        float lift = 0.5f * (1f - contrast);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) m[col * 4 + row] = rows[row * 3 + col] * contrast;
        }
        m[12] = ox * contrast + lift;
        m[13] = oy * contrast + lift;
        m[14] = oz * contrast + lift;
        m[15] = 1f;
        return new Fixed(m);
    }

    /** Look index 6..13; returns null for anything else (looks 0..5 are built from stock effects in the editor). */
    static Effect look(int look) {
        float[] sepia = {0.393f, 0.769f, 0.189f, 0.349f, 0.686f, 0.168f, 0.272f, 0.534f, 0.131f};
        switch (look) {
            case 6:
                return pack(sepia, 0f, 0f, 0f, 1f);
            case 7:
                return pack(mix(identity(), sepia, 0.55f), 0.03f, 0.02f, 0f, 0.9f);
            case 8:
                return pack(new float[]{1.10f, 0.04f, -0.04f, -0.02f, 1.00f, 0.04f, -0.06f, 0.10f, 0.95f},
                        -0.03f, -0.01f, 0.02f, 1.1f);
            case 9:
                return pack(gray(), 0f, 0f, 0f, 1.25f);
            case 10:
                return pack(new float[]{1.15f, 0.04f, 0f, 0f, 1.0f, 0f, 0f, 0.02f, 0.82f}, 0.03f, 0f, 0f, 1.05f);
            case 11:
                return pack(new float[]{0.88f, 0f, 0.02f, 0f, 1.0f, 0.06f, 0f, 0.04f, 1.15f}, 0f, 0f, 0.02f, 1f);
            case 12:
                return pack(new float[]{0.82f, 0.10f, 0.08f, 0f, 1.05f, 0.08f, 0f, 0.12f, 1.10f}, -0.02f, 0.01f, 0.03f, 1f);
            case 13:
                return pack(saturation(1.25f), 0f, 0f, 0f, 1.3f);
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------ overlays

    /** Builds one overlay effect for all items. Text size is a fraction of the short side so preview and export match. */
    static Effect overlays(List<Overlay> items, int frameW, int frameH) {
        int base = Math.max(1, Math.min(frameW, frameH));
        ImmutableList.Builder<TextureOverlay> list = new ImmutableList.Builder<>();
        for (Overlay o : items) {
            if (o.text == null || o.text.isEmpty()) continue;
            SpannableString s = new SpannableString(o.text);
            int px = Math.max(12, Math.round(o.size * base));
            int n = s.length();
            s.setSpan(new AbsoluteSizeSpan(px, false), 0, n, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new ForegroundColorSpan(o.color), 0, n, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (!o.sticker) s.setSpan(new StyleSpan(Typeface.BOLD), 0, n, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (o.box && !o.sticker) s.setSpan(new BackgroundColorSpan(0xAA000000), 0, n, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            StaticOverlaySettings st = new StaticOverlaySettings.Builder()
                    .setBackgroundFrameAnchor(o.x, o.y)
                    .setOverlayFrameAnchor(0f, 0f)
                    .build();
            list.add(TextOverlay.createStaticTextOverlay(s, st));
        }
        return new OverlayEffect(list.build());
    }
}
