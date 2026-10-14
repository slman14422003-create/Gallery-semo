package com.gallery.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.widget.OverScroller;

/** The horizontal tick ruler of the iOS Photos editor: drag (or fling) the ticks, the centre mark reads the value. */
final class RulerView extends View {
    interface Listener {
        void onStart();

        void onValue(float v);

        void onEnd();
    }

    interface Format {
        String text(float v);
    }

    private float min = -1f, max = 1f, def = 0f, value = 0f;
    private int ticks = 100, major = 10;
    private Listener listener;
    private Format format;
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float gap, snap, minFling;
    private final OverScroller scroller;
    private VelocityTracker tracker;
    private float lastX;
    private boolean dragging, flinging, atDefault = true;

    RulerView(Context c) {
        super(c);
        setLayoutDirection(LAYOUT_DIRECTION_LTR);
        gap = Ui.dpf(c, 8f);
        snap = Ui.dpf(c, 3f);
        minFling = Ui.dpf(c, 200f);
        scroller = new OverScroller(c);
        tickPaint.setStrokeCap(Paint.Cap.ROUND);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(Ui.dpf(c, 15f));
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textPaint.setColor(Ui.color(c, R.color.text_primary));
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setFormat(Format f) {
        format = f;
        invalidate();
    }

    void configure(float min, float max, float def, int ticks, int major) {
        this.min = min;
        this.max = max;
        this.def = def;
        this.ticks = Math.max(2, ticks);
        this.major = Math.max(1, major);
        value = clamp(value);
        invalidate();
    }

    void setValue(float v) {
        scroller.forceFinished(true);
        flinging = false;
        value = clamp(v);
        atDefault = Math.abs(value - def) < 1e-4f;
        invalidate();
    }

    float getValue() {
        return value;
    }

    private float clamp(float v) {
        return Math.max(min, Math.min(max, v));
    }

    private float ppu() {
        return gap * ticks / (max - min);
    }

    private float total() {
        return (max - min) * ppu();
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        int h = Ui.dp(getContext(), 64);
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), ws), h);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f;
        float u = ppu();
        float top = h * 0.46f;
        float stroke = Ui.dpf(getContext(), 1.4f);
        tickPaint.setStrokeWidth(stroke);
        int hint = Ui.color(getContext(), R.color.text_hint);
        int sec = Ui.color(getContext(), R.color.text_secondary);
        int prim = Ui.color(getContext(), R.color.text_primary);
        for (int i = 0; i <= ticks; i++) {
            float v = min + (max - min) * i / ticks;
            float x = cx + (v - value) * u;
            if (x < -gap || x > w + gap) continue;
            boolean isMajor = i % major == 0;
            boolean isDef = Math.abs(v - def) < 1e-4f;
            float len = Ui.dpf(getContext(), isDef ? 20f : isMajor ? 15f : 9f);
            float edge = Math.min(1f, Math.abs(x - cx) / cx);
            float fade = 1f - 0.85f * edge * edge;
            int base = isDef ? prim : isMajor ? sec : hint;
            tickPaint.setColor(base);
            tickPaint.setAlpha(Math.round(255f * fade));
            float y0 = top + (Ui.dpf(getContext(), 20f) - len) / 2f;
            c.drawLine(x, y0, x, y0 + len, tickPaint);
        }
        tickPaint.setAlpha(255);
        tickPaint.setColor(Ui.color(getContext(), R.color.accent));
        tickPaint.setStrokeWidth(Ui.dpf(getContext(), 2.4f));
        c.drawLine(cx, top - Ui.dpf(getContext(), 2f), cx, top + Ui.dpf(getContext(), 22f), tickPaint);
        String s = format != null ? format.text(value) : String.valueOf(Math.round(value));
        textPaint.setColor(Math.abs(value - def) < 1e-4f ? Ui.color(getContext(), R.color.text_secondary) : Ui.color(getContext(), R.color.accent));
        c.drawText(s, cx, h * 0.30f, textPaint);
    }

    private void moveTo(float pos, boolean user) {
        float t = total();
        pos = Math.max(0f, Math.min(t, pos));
        float v = min + pos / ppu();
        if (Math.abs(v - def) * ppu() < snap) v = def;
        v = clamp(v);
        boolean now = Math.abs(v - def) < 1e-4f;
        if (user && now && !atDefault) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        atDefault = now;
        if (v == value) return;
        value = v;
        invalidate();
        if (listener != null) listener.onValue(v);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                scroller.forceFinished(true);
                if (flinging) {
                    flinging = false;
                    if (listener != null) listener.onEnd();
                }
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                tracker = VelocityTracker.obtain();
                tracker.addMovement(e);
                lastX = e.getX();
                dragging = true;
                if (listener != null) listener.onStart();
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (!dragging) return false;
                if (tracker != null) tracker.addMovement(e);
                float dx = e.getX() - lastX;
                lastX = e.getX();
                moveTo((value - min) * ppu() - dx, true);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!dragging) return false;
                dragging = false;
                float vx = 0f;
                if (tracker != null) {
                    tracker.addMovement(e);
                    tracker.computeCurrentVelocity(1000);
                    vx = tracker.getXVelocity();
                    tracker.recycle();
                    tracker = null;
                }
                if (e.getActionMasked() == MotionEvent.ACTION_UP && Math.abs(vx) > minFling) {
                    flinging = true;
                    int start = Math.round((value - min) * ppu());
                    scroller.fling(start, 0, Math.round(-vx * 0.8f), 0, 0, Math.round(total()), 0, 0);
                    postInvalidateOnAnimation();
                } else if (listener != null) {
                    listener.onEnd();
                }
                return true;
            }
            default:
                return true;
        }
    }

    @Override
    public void computeScroll() {
        if (!flinging) return;
        if (scroller.computeScrollOffset()) {
            moveTo(scroller.getCurrX(), true);
            postInvalidateOnAnimation();
        } else {
            flinging = false;
            if (listener != null) listener.onEnd();
        }
    }
}
