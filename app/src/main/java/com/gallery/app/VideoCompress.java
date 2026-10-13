package com.gallery.app;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.transformer.AudioEncoderSettings;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.VideoEncoderSettings;

/** Export size presets for the video editor: smaller resolution, a target bitrate, optional H.265 and a size estimate. */
@OptIn(markerClass = UnstableApi.class)
final class VideoCompress {
    static final int ORIGINAL = 0, HIGH = 1, BALANCED = 2, SMALL = 3, TINY = 4;
    static final int DEFAULT = BALANCED;

    // short side in pixels (0 = keep), bits per pixel per frame at 30 fps, audio bitrate
    private static final int[] SHORT_SIDE = {0, 1080, 720, 540, 480};
    private static final float[] BPP = {0.15f, 0.10f, 0.07f, 0.045f, 0.03f};
    private static final int[] AUDIO_BPS = {128000, 128000, 96000, 64000, 48000};
    private static final float HEVC_FACTOR = 0.6f;

    private static Boolean hevc;

    private VideoCompress() {
    }

    /** True when the device has a hardware or software H.265 encoder. */
    static boolean hevcSupported() {
        if (hevc == null) {
            boolean ok = false;
            try {
                for (MediaCodecInfo i : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                    if (!i.isEncoder()) continue;
                    for (String t : i.getSupportedTypes()) {
                        if (t.equalsIgnoreCase("video/hevc")) ok = true;
                    }
                }
            } catch (Throwable ignored) {
            }
            hevc = ok;
        }
        return hevc;
    }

    /** Output frame size {w, h} after the preset's downscale (even numbers, never larger than the input). */
    static int[] size(int w, int h, int preset) {
        int side = SHORT_SIDE[preset];
        int s = Math.min(w, h);
        if (side == 0 || s <= side) return new int[]{even(w), even(h)};
        float f = (float) side / s;
        return new int[]{even(Math.round(w * f)), even(Math.round(h * f))};
    }

    /** True when the preset shrinks the picture, so a scale effect is needed. */
    static boolean scales(int w, int h, int preset) {
        int side = SHORT_SIDE[preset];
        return side != 0 && Math.min(w, h) > side;
    }

    static int videoBitrate(int w, int h, int preset, boolean h265, int sourceBps) {
        int[] o = size(w, h, preset);
        double bps = (double) o[0] * o[1] * 30.0 * BPP[preset] * (h265 ? HEVC_FACTOR : 1f);
        if (preset == ORIGINAL && sourceBps > 0) bps = sourceBps * (h265 ? HEVC_FACTOR : 1f);
        if (sourceBps > 0) bps = Math.min(bps, sourceBps * 0.95);
        return (int) Math.max(250000, bps);
    }

    static int audioBitrate(int preset) {
        return AUDIO_BPS[preset];
    }

    static boolean capFps(int preset) {
        return preset >= SMALL;
    }

    /** Estimated file size in bytes. */
    static long estimate(int w, int h, int preset, boolean h265, int sourceBps, boolean audio, double seconds) {
        long bps = videoBitrate(w, h, preset, h265, sourceBps) + (audio ? AUDIO_BPS[preset] : 0);
        return Math.round(bps * seconds / 8.0);
    }

    static DefaultEncoderFactory encoders(Context c, int videoBps, int audioBps) {
        return new DefaultEncoderFactory.Builder(c)
                .setRequestedVideoEncoderSettings(new VideoEncoderSettings.Builder()
                        .setBitrate(videoBps)
                        .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                        .build())
                .setRequestedAudioEncoderSettings(new AudioEncoderSettings.Builder().setBitrate(audioBps).build())
                .setEnableFallback(true)
                .build();
    }

    private static int even(int v) {
        return Math.max(2, v & ~1);
    }
}
