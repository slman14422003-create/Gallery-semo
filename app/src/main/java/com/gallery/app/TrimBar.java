package com.gallery.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Filmstrip with two handles to choose the part of the video to keep, and a playhead. */
final class TrimBar extends View {
    interface Listener {
        /** {@code which}: 0 start handle, 1 end handle, 2 playhead; {@code done} when the finger lifted. */
        void onChange(long start, long end, int which, boolean done);
    }

    private final List<Bitmap> thumbs = new ArrayList<>();
    private long dur = 1, start = 0, end = 1, head = 0;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF strip = new RectF();
    private final float handleW, pad, minGap;
    private int drag = -1;
    private Listener listener;
    private final int accent;

    TrimBar(Context c) {
        super(c);
        handleW = Ui.dp(c, 16);
        pad = Ui.dp(c, 18);
        minGap = Ui.dp(c, 40);
        accent = Ios.YELLOW;
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setDuration(long d) {
        dur = Math.max(1, d);
        start = 0;
        end = dur;
        invalidate();
    }

    void setRange(long s, long e) {
        start = Math.max(0, Math.min(s, dur - 1));
        end = Math.min(dur, Math.max(start + 1, e));
        invalidate();
    }

    void setThumbs(List<Bitmap> t) {
        thumbs.clear();
        thumbs.addAll(t);
        invalidate();
    }

    void setPlayhead(long ms) {
        head = ms;
        invalidate();
    }

    long head() {
        return head;
    }

    long start() {
        return start;
    }

    long end() {
        return end;
    }

    private float xOf(long ms) {
        return strip.left + strip.width() * ms / dur;
    }

    private long msOf(float x) {
        return Math.max(0, Math.min(dur, Math.round((x - strip.left) / strip.width() * dur)));
    }

    @Override
    protected void onDraw(Canvas c) {
        float h = getHeight();
        strip.set(pad + handleW, h * 0.14f, getWidth() - pad - handleW, h * 0.86f);
        float r = Ui.dp(getContext(), 8);
        Path clip = new Path();
        clip.addRoundRect(strip, r, r, Path.Direction.CW);
        int save = c.save();
        c.clipPath(clip);
        p.setColor(Ui.color(getContext(), R.color.surface_high));
        c.drawRect(strip, p);
        if (!thumbs.isEmpty()) {
            float tw = strip.width() / thumbs.size();
            for (int i = 0; i < thumbs.size(); i++) {
                Bitmap b = thumbs.get(i);
                RectF d = new RectF(strip.left + i * tw, strip.top, strip.left + (i + 1) * tw + 1, strip.bottom);
                float s = Math.max(d.width() / b.getWidth(), d.height() / b.getHeight());
                float sw = d.width() / s, sh = d.height() / s;
                android.graphics.Rect src = new android.graphics.Rect((int) ((b.getWidth() - sw) / 2), (int) ((b.getHeight() - sh) / 2),
                        (int) ((b.getWidth() + sw) / 2), (int) ((b.getHeight() + sh) / 2));
                c.drawBitmap(b, src, d, p);
            }
        }
        // dim what is cut away
        p.setColor(0xAA000000);
        c.drawRect(strip.left, strip.top, xOf(start), strip.bottom, p);
        c.drawRect(xOf(end), strip.top, strip.right, strip.bottom, p);
        c.restoreToCount(save);
        // frame between the handles
        p.setColor(accent);
        float t = Ui.dp(getContext(), 3);
        c.drawRect(xOf(start), strip.top - t, xOf(end), strip.top, p);
        c.drawRect(xOf(start), strip.bottom, xOf(end), strip.bottom + t, p);
        // handles
        RectF l = new RectF(xOf(start) - handleW, strip.top - t, xOf(start), strip.bottom + t);
        RectF rr = new RectF(xOf(end), strip.top - t, xOf(end) + handleW, strip.bottom + t);
        c.drawRoundRect(l, r, r, p);
        c.drawRoundRect(rr, r, r, p);
        p.setColor(0xFF1C1C1E);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Ui.dp(getContext(), 2));
        p.setStrokeCap(Paint.Cap.ROUND);
        float ch = Ui.dp(getContext(), 5), cw = Ui.dp(getContext(), 3);
        float ly = l.centerY(), lx = l.centerX();
        c.drawLine(lx + cw / 2, ly - ch, lx - cw / 2, ly, p);
        c.drawLine(lx - cw / 2, ly, lx + cw / 2, ly + ch, p);
        float rx = rr.centerX();
        c.drawLine(rx - cw / 2, ly - ch, rx + cw / 2, ly, p);
        c.drawLine(rx + cw / 2, ly, rx - cw / 2, ly + ch, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFFFFF);
        // playhead
        if (head >= start && head <= end) {
            float x = xOf(head);
            c.drawRoundRect(x - 2, strip.top - t * 2, x + 2, strip.bottom + t * 2, 4, 4, p);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float slop = Ui.dp(getContext(), 26);
                float ds = Math.abs(x - (xOf(start) - handleW / 2)), de = Math.abs(x - (xOf(end) + handleW / 2));
                if (ds < slop && ds <= de) drag = 0;
                else if (de < slop) drag = 1;
                else drag = 2;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                long gap = Math.max(300, Math.round(minGap / strip.width() * dur));
                if (drag == 0) start = Math.min(msOf(x), end - gap);
                else if (drag == 1) end = Math.max(msOf(x), start + gap);
                else head = Math.max(start, Math.min(end, msOf(x)));
                invalidate();
                if (listener != null) listener.onChange(start, end, drag, false);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (listener != null) listener.onChange(start, end, drag, true);
                drag = -1;
                return true;
            default:
                return true;
        }
    }
}
