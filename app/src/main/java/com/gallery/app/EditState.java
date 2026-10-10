package com.gallery.app;

import java.util.ArrayList;
import java.util.List;

/** Everything the image editor changes, as small numbers: applied the same way to the preview and to the full-size save. */
final class EditState {
    static final class Stroke {
        int color;
        float width;                 // fraction of the image width
        final List<float[]> pts = new ArrayList<>();   // normalised x, y

        Stroke copy() {
            Stroke s = new Stroke();
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

        Label copy() {
            Label l = new Label();
            l.text = text;
            l.color = color;
            l.size = size;
            l.x = x;
            l.y = y;
            return l;
        }
    }

    int rot;                         // quarter turns clockwise
    boolean flipH, flipV;
    float cl = 0f, ct = 0f, cr = 1f, cb = 1f;   // crop, normalised on the rotated image
    float brightness, contrast, saturation, warmth, vignette;
    int filter;
    final List<Stroke> strokes = new ArrayList<>();
    final List<Label> labels = new ArrayList<>();

    EditState copy() {
        EditState s = new EditState();
        s.rot = rot;
        s.flipH = flipH;
        s.flipV = flipV;
        s.cl = cl;
        s.ct = ct;
        s.cr = cr;
        s.cb = cb;
        s.brightness = brightness;
        s.contrast = contrast;
        s.saturation = saturation;
        s.warmth = warmth;
        s.vignette = vignette;
        s.filter = filter;
        for (Stroke k : strokes) s.strokes.add(k.copy());
        for (Label l : labels) s.labels.add(l.copy());
        return s;
    }

    boolean changed() {
        return rot != 0 || flipH || flipV || cl > 0 || ct > 0 || cr < 1 || cb < 1 || brightness != 0 || contrast != 0
                || saturation != 0 || warmth != 0 || vignette != 0 || filter != 0 || !strokes.isEmpty() || !labels.isEmpty();
    }

    void resetCrop() {
        cl = 0;
        ct = 0;
        cr = 1;
        cb = 1;
    }
}
