package com.gallery.app;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import java.util.List;

final class OcrLayer {
    interface Listener {
        void onSelection(boolean settled);
    }

    private final PhotoView view;
    private final OcrResult res;
    private final Listener listener;
    private final RectF[] rects;
    private final Matrix m = new Matrix();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint under = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float slop;
    private final float pad;
    private final float radius;
    private final float knobR;
    private final float grab;
    private int sel0 = -1;
    private int sel1 = -1;
    private boolean drag;
    private int anchor = -1;
    private int pending = -1;
    private float downX;
    private float downY;

    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            if (pending < 0) return;
            drag = true;
            anchor = pending;
            sel0 = pending;
            sel1 = pending;
            pending = -1;
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(true);
            view.invalidate();
            listener.onSelection(false);
        }
    };

    OcrLayer(PhotoView view, OcrResult res, Listener listener) {
        this.view = view;
        this.res = res;
        this.listener = listener;
        float d = view.getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        pad = 2f * d;
        radius = 4f * d;
        knobR = 5f * d;
        grab = 30f * d;
        rects = new RectF[res.words.size()];
        for (int i = 0; i < rects.length; i++) rects[i] = new RectF();
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0x26FFFFFF);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.2f * d);
        stroke.setColor(0x99FFD60A);
        sel.setStyle(Paint.Style.FILL);
        sel.setColor(0x66007AFF);
        under.setStyle(Paint.Style.STROKE);
        under.setStrokeWidth(2f * d);
        under.setColor(0xFF4DA3FF);
        knob.setStyle(Paint.Style.FILL);
        knob.setColor(0xFF0A84FF);
    }

    void release() {
        view.removeCallbacks(longPress);
        view.setLayer(null);
    }

    boolean hasSelection() {
        return sel0 >= 0;
    }

    String selectedText() {
        return sel0 < 0 ? "" : res.text(sel0, sel1);
    }

    List<OcrEntity> selectionEntities() {
        return sel0 < 0 ? java.util.Collections.<OcrEntity>emptyList() : res.entitiesIn(sel0, sel1);
    }

    String allText() {
        return res.text();
    }

    void selectAll() {
        if (res.words.isEmpty()) return;
        sel0 = 0;
        sel1 = res.words.size() - 1;
        view.invalidate();
        listener.onSelection(true);
    }

    void clearSelection() {
        sel0 = -1;
        sel1 = -1;
        view.invalidate();
        listener.onSelection(true);
    }

    boolean onBackgroundTap() {
        if (sel0 < 0) return false;
        clearSelection();
        return true;
    }

    private boolean selected(int i) {
        return sel0 >= 0 && i >= sel0 && i <= sel1;
    }

    private boolean layout() {
        Drawable d = view.getDrawable();
        if (d == null) return false;
        int dw = d.getIntrinsicWidth();
        int dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) return false;
        m.set(view.currentMatrix());
        for (int i = 0; i < rects.length; i++) {
            RectF b = res.words.get(i).box;
            rects[i].set(b.left * dw, b.top * dh, b.right * dw, b.bottom * dh);
            m.mapRect(rects[i]);
            rects[i].inset(-pad, -pad);
        }
        return true;
    }

    private static boolean rtl(String s) {
        for (int i = 0; i < s.length(); i++) {
            byte d = Character.getDirectionality(s.charAt(i));
            if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) return true;
            if (d == Character.DIRECTIONALITY_LEFT_TO_RIGHT) return false;
        }
        return false;
    }

    private float[] startHandle() {
        RectF r = rects[sel0];
        return new float[]{rtl(res.words.get(sel0).text) ? r.right : r.left, r.top - knobR, r.top, r.bottom};
    }

    private float[] endHandle() {
        RectF r = rects[sel1];
        return new float[]{rtl(res.words.get(sel1).text) ? r.left : r.right, r.bottom + knobR, r.top, r.bottom};
    }

    void draw(Canvas c) {
        if (!layout()) return;
        for (int i = 0; i < rects.length; i++) {
            RectF r = rects[i];
            if (selected(i)) {
                c.drawRoundRect(r, radius, radius, sel);
            } else {
                c.drawRoundRect(r, radius, radius, fill);
                c.drawRoundRect(r, radius, radius, stroke);
            }
        }
        for (OcrEntity e : res.entities()) {
            for (int i = e.from; i <= e.to && i < rects.length; i++) {
                RectF r = rects[i];
                c.drawLine(r.left, r.bottom, r.right, r.bottom, under);
            }
        }
        if (sel0 >= 0 && !drag) {
            float[] s = startHandle();
            float[] e = endHandle();
            c.drawLine(s[0], s[2], s[0], s[3], under);
            c.drawLine(e[0], e[2], e[0], e[3], under);
            c.drawCircle(s[0], s[1], knobR, knob);
            c.drawCircle(e[0], e[1], knobR, knob);
        }
    }

    private int hit(float x, float y, float tol) {
        int best = -1;
        float bd = Float.MAX_VALUE;
        for (int i = 0; i < rects.length; i++) {
            RectF r = rects[i];
            float dx = Math.max(Math.max(r.left - x, x - r.right), 0f);
            float dy = Math.max(Math.max(r.top - y, y - r.bottom), 0f);
            float d = (float) Math.hypot(dx, dy);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return bd <= tol ? best : -1;
    }

    private void cancelPending() {
        pending = -1;
        view.removeCallbacks(longPress);
    }

    boolean onTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return down(e.getX(), e.getY());
            case MotionEvent.ACTION_POINTER_DOWN:
                cancelPending();
                return drag;
            case MotionEvent.ACTION_MOVE:
                return move(e.getX(), e.getY());
            case MotionEvent.ACTION_UP:
                return up();
            case MotionEvent.ACTION_CANCEL:
                cancelPending();
                if (drag) {
                    drag = false;
                    listener.onSelection(true);
                }
                return false;
            default:
                return drag;
        }
    }

    private boolean down(float x, float y) {
        drag = false;
        cancelPending();
        if (!layout()) return false;
        downX = x;
        downY = y;
        if (sel0 >= 0) {
            float[] s = startHandle();
            float[] en = endHandle();
            if (Math.hypot(x - s[0], y - s[1]) < grab) {
                drag = true;
                anchor = sel1;
                listener.onSelection(false);
                view.invalidate();
                return true;
            }
            if (Math.hypot(x - en[0], y - en[1]) < grab) {
                drag = true;
                anchor = sel0;
                listener.onSelection(false);
                view.invalidate();
                return true;
            }
        }
        int w = hit(x, y, pad * 3f);
        if (w >= 0) {
            pending = w;
            view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
        }
        return false;
    }

    private boolean move(float x, float y) {
        if (!drag) {
            if (pending >= 0 && Math.hypot(x - downX, y - downY) > slop) cancelPending();
            return false;
        }
        if (!layout()) return true;
        int w = hit(x, y, Float.MAX_VALUE);
        if (w >= 0) {
            sel0 = Math.min(anchor, w);
            sel1 = Math.max(anchor, w);
            view.invalidate();
        }
        return true;
    }

    private boolean up() {
        view.removeCallbacks(longPress);
        if (drag) {
            drag = false;
            view.invalidate();
            listener.onSelection(true);
            return true;
        }
        if (pending >= 0) {
            int w = pending;
            pending = -1;
            OcrEntity en = res.entityAt(w);
            if (en != null) {
                sel0 = en.from;
                sel1 = en.to;
            } else {
                sel0 = w;
                sel1 = w;
            }
            view.invalidate();
            listener.onSelection(true);
            return true;
        }
        return false;
    }
}
