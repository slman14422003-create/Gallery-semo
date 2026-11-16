package com.gallery.app;

import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.audio.AudioProcessor.AudioFormat;
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.common.OverlaySettings;
import androidx.media3.effect.RgbMatrix;
import androidx.media3.effect.StaticOverlaySettings;
import androidx.media3.effect.TextOverlay;
import androidx.media3.effect.TextureOverlay;

import com.google.common.collect.ImmutableList;

import java.nio.ByteBuffer;
import java.util.List;

/** Colour grade, fade, audio gain and text / emoji overlays used by the video export; the preview uses {@link VideoStage}. */
@OptIn(markerClass = UnstableApi.class)
final class VideoFx {
    static final String[] EMOJI = {"\uD83D\uDE00", "\uD83D\uDE02", "\uD83D\uDE0D", "\uD83D\uDE0E", "\uD83E\uDD29", "\uD83D\uDD25", "\u2764\uFE0F", "\u2B50",
            "\uD83D\uDC4D", "\uD83D\uDC4F", "\uD83C\uDF89", "\uD83D\uDCAF", "\u2728", "\uD83C\uDF1F", "\uD83D\uDCA5", "\uD83C\uDF08",
            "\uD83C\uDFB5", "\uD83C\uDFAC", "\uD83D\uDCF8", "\uD83C\uDF39", "\uD83D\uDE80", "\uD83D\uDC51", "\uD83D\uDCA1", "\u26A1"};

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

    /** The photo editor's colour matrix (filter + light + colour sliders) as one video effect, so photo and video match. */
    static final class Grade implements RgbMatrix {
        private final float[] m = new float[16];

        Grade(ColorMatrix cm) {
            float[] a = cm.getArray();
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) m[col * 4 + row] = a[row * 5 + col];
                m[12 + row] = a[row * 5 + 4] / 255f;
            }
            m[15] = 1f;
        }

        @Override
        public float[] getMatrix(long presentationTimeUs, boolean useHdr) {
            return m;
        }
    }

    /** Fade gain 0..1 for a time inside a clip: ramps up over inUs at the start and down over outUs at the end. */
    static float fadeGain(long tUs, long inUs, long outUs, long totalUs) {
        float g = 1f;
        if (inUs > 0 && tUs < inUs) g = Math.min(g, Math.max(0f, tUs / (float) inUs));
        if (outUs > 0 && tUs > totalUs - outUs) g = Math.min(g, Math.max(0f, (totalUs - tUs) / (float) outUs));
        return g;
    }

    /** Fades the picture to and from black. */
    static final class Fade implements RgbMatrix {
        private final long inUs, outUs, totalUs;

        Fade(long inUs, long outUs, long totalUs) {
            this.inUs = inUs;
            this.outUs = outUs;
            this.totalUs = totalUs;
        }

        @Override
        public float[] getMatrix(long presentationTimeUs, boolean useHdr) {
            float g = fadeGain(presentationTimeUs, inUs, outUs, totalUs);
            return new float[]{g, 0, 0, 0, 0, g, 0, 0, 0, 0, g, 0, 0, 0, 0, 1};
        }
    }

    /** Volume plus fade in / out for PCM audio with any channel count; placed last so it sees the output timeline. */
    static final class AudioGain extends BaseAudioProcessor {
        private final float volume;
        private final long inUs, outUs, totalUs;
        private long frames;

        AudioGain(float volume, long inUs, long outUs, long totalUs) {
            this.volume = volume;
            this.inUs = inUs;
            this.outUs = outUs;
            this.totalUs = totalUs;
        }

        @Override
        protected AudioFormat onConfigure(AudioFormat f) throws UnhandledAudioFormatException {
            if (f.encoding != C.ENCODING_PCM_16BIT && f.encoding != C.ENCODING_PCM_FLOAT) throw new UnhandledAudioFormatException(f);
            return f;
        }

        @Override
        protected void onFlush() {
            frames = 0;
        }

        @Override
        public void queueInput(ByteBuffer in) {
            int rem = in.remaining();
            if (rem == 0) return;
            ByteBuffer out = replaceOutputBuffer(rem);
            int ch = inputAudioFormat.channelCount, rate = inputAudioFormat.sampleRate;
            boolean flt = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT;
            int size = flt ? 4 : 2;
            while (in.remaining() >= size * ch) {
                float g = volume * fadeGain(frames * 1000000L / rate, inUs, outUs, totalUs);
                for (int c = 0; c < ch; c++) {
                    if (flt) out.putFloat(in.getFloat() * g);
                    else out.putShort((short) Math.max(-32768, Math.min(32767, Math.round(in.getShort() * g))));
                }
                frames++;
            }
            out.flip();
        }
    }

    // ------------------------------------------------------------------ overlays

    /** Text overlay used by the export; the live preview draws the same items as plain views. */
    static final class LiveText extends TextOverlay {
        private final Overlay o;
        private final SpannableString text;

        LiveText(Overlay o, int base) {
            this.o = o;
            SpannableString s = new SpannableString(o.text);
            int px = Math.max(12, Math.round(o.size * base));
            int n = s.length();
            s.setSpan(new AbsoluteSizeSpan(px, false), 0, n, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new ForegroundColorSpan(o.color), 0, n, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (!o.sticker) s.setSpan(new StyleSpan(Typeface.BOLD), 0, n, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (o.box && !o.sticker) s.setSpan(new BackgroundColorSpan(0xAA000000), 0, n, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text = s;
        }

        @Override
        public SpannableString getText(long presentationTimeUs) {
            return text;
        }

        @Override
        public OverlaySettings getOverlaySettings(long presentationTimeUs) {
            return new StaticOverlaySettings.Builder()
                    .setBackgroundFrameAnchor(o.x, o.y)
                    .setOverlayFrameAnchor(0f, 0f)
                    .build();
        }
    }

    static Effect overlays(List<Overlay> items, int frameW, int frameH) {
        int base = Math.max(1, Math.min(frameW, frameH));
        ImmutableList.Builder<TextureOverlay> list = new ImmutableList.Builder<>();
        for (Overlay o : items) {
            if (o.text == null || o.text.isEmpty()) continue;
            list.add(new LiveText(o, base));
        }
        return new OverlayEffect(list.build());
    }
}
