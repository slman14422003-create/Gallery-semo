package com.gallery.app;

import java.util.ArrayList;
import java.util.List;

/** Everything the image editor changes, as small numbers: applied the same way to the preview and to the full-size save. */
final class EditState {
    static final int PEN = 0, MARKER = 1, LINE = 2, ARROW = 3, BOX = 4, OVAL = 5;

    static final class Stroke {
        int type = PEN;
        int color;
        float width;                 // fraction of the image width
        final List<float[]> pts = new ArrayList<>();   // normalised x, y (shapes keep only the two corner points)

        Stroke copy() {
            Stroke s = new Stroke();
            s.type = type;
            s.color = color;
            s.width = width;
            for (float[] p : pts) s.pts.add(new float[]{p[0], p[1]});
            return s;
        }
    }

    static final class Label {
        String text = "";
        int color = 0xFFFFFFFF;
        float size = 0.06f;          // fraction of the image height
        float x = 0.5f, y = 0.5f;    // centre, normalised
        int font;                    // 0 modern, 1 classic, 2 mono, 3 script
        int box;                     // 0 none, 1 dark box, 2 light box

        Label copy() {
            Label l = new Label();
            l.text = text;
            l.color = color;
            l.size = size;
            l.x = x;
            l.y = y;
            l.font = font;
            l.box = box;
            return l;
        }
    }

    int rot;                         // quarter turns clockwise
    float angle;                     // straighten, degrees (-45..45)
    boolean flipH, flipV;
    float cl = 0f, ct = 0f, cr = 1f, cb = 1f;   // crop, normalised on the rotated image
    float exposure, brightness, contrast, saturation, warmth, tint, fade;   // -1..1 (fade 0..1)
    float sharpen, grain, vignette, denoise;                                // 0..1
    float dehaze, clarity, shadows;                                         // 0..1 (local AI tools)
    int filter;
    float filterAmt = 1f;            // how strongly the filter is applied (0..1)
    final List<Stroke> strokes = new ArrayList<>();
    final List<Label> labels = new ArrayList<>();

    EditState copy() {
        EditState s = new EditState();
        s.rot = rot;
        s.angle = angle;
        s.flipH = flipH;
        s.flipV = flipV;
        s.cl = cl;
        s.ct = ct;
        s.cr = cr;
        s.cb = cb;
        s.exposure = exposure;
        s.brightness = brightness;
        s.contrast = contrast;
        s.saturation = saturation;
        s.warmth = warmth;
        s.tint = tint;
        s.fade = fade;
        s.sharpen = sharpen;
        s.grain = grain;
        s.vignette = vignette;
        s.denoise = denoise;
        s.dehaze = dehaze;
        s.clarity = clarity;
        s.shadows = shadows;
        s.filter = filter;
        s.filterAmt = filterAmt;
        for (Stroke k : strokes) s.strokes.add(k.copy());
        for (Label l : labels) s.labels.add(l.copy());
        return s;
    }

    /** True when the colour matrix (filter, light and colour sliders) has something to apply. */
    boolean toneChanged() {
        return filter != 0 || exposure != 0 || brightness != 0 || contrast != 0 || saturation != 0 || warmth != 0
                || tint != 0 || fade != 0;
    }

    /** True when any colour / light adjustment or filter is active (the pixels need the colour pass). */
    boolean colorChanged() {
        return filter != 0 || exposure != 0 || brightness != 0 || contrast != 0 || saturation != 0 || warmth != 0
                || tint != 0 || fade != 0 || sharpen != 0 || grain != 0 || vignette != 0 || denoise != 0
                || dehaze != 0 || clarity != 0 || shadows != 0;
    }

    boolean changed() {
        return rot != 0 || angle != 0 || flipH || flipV || cl > 0 || ct > 0 || cr < 1 || cb < 1 || colorChanged()
                || !strokes.isEmpty() || !labels.isEmpty();
    }

    void resetCrop() {
        cl = 0;
        ct = 0;
        cr = 1;
        cb = 1;
    }

    /** Back to the untouched light / colour settings (crop, drawings and text stay). */
    void resetAdjust() {
        exposure = brightness = contrast = saturation = warmth = tint = fade = 0f;
        sharpen = grain = vignette = denoise = 0f;
        dehaze = clarity = shadows = 0f;
    }
}
