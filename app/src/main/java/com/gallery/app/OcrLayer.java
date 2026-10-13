package com.gallery.app;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import java.util.ArrayList;
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
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float ringW;
    private float selA = 1f;
    private ValueAnimator selAnim;
    private final float slop;
    private final float pad;
    private final float radius;
    private final float knobR;
    private final float grab;
    private float flash = 1f;
    private ValueAnimator flashAnim;
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
            popSelection();
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
        pad = 3f * d;
        radius = 6f * d;
        knobR = 7f * d;
        ringW = 1.8f * d;
        grab = 32f * d;
        rects = new RectF[res.words.size()];
        flashAnim = ValueAnimator.ofFloat(1f, 0f);
        flashAnim.setStartDelay(500);
        flashAnim.setDuration(1300);
        flashAnim.addUpdateListener(a -> {
            flash = (float) a.getAnimatedValue();
            view.invalidate();
        });
        flashAnim.start();
        for (int i = 0; i < rects.length; i++) rects[i] = new RectF();
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0x33FFFFFF);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1f * d);
        stroke.setColor(0x80FFFFFF);
        sel.setStyle(Paint.Style.FILL);
        sel.setColor(0x5C0A84FF);
        selEdge.setStyle(Paint.Style.STROKE);
        selEdge.setStrokeWidth(1f * d);
        selEdge.setColor(0x990A84FF);
        under.setStyle(Paint.Style.STROKE);
        under.setStrokeWidth(2f * d);
        under.setStrokeCap(Paint.Cap.ROUND);
        under.setColor(0xFF0A84FF);
        knob.setStyle(Paint.Style.FILL);
        knob.setColor(0xFF0A84FF);
        ring.setStyle(Paint.Style.FILL);
        ring.setColor(0xFFFFFFFF);
        shadow.setStyle(Paint.Style.FILL);
        shadow.setColor(0x40000000);
    }

    private void popSelection() {
        if (selAnim != null) selAnim.cancel();
        selAnim = ValueAnimator.ofFloat(0f, 1f);
        selAnim.setDuration(190);
        selAnim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        selAnim.addUpdateListener(a -> {
            selA = (float) a.getAnimatedValue();
            view.invalidate();
        });
        selAnim.start();
    }

    private void drawKnob(Canvas c, float x, float y, float r) {
        c.drawCircle(x, y + knobR * 0.25f, r + ringW + knobR * 0.15f, shadow);
        c.drawCircle(x, y, r + ringW, ring);
        c.drawCircle(x, y, r, knob);
    }

    void release() {
        if (selAnim != null) selAnim.cancel();
        if (flashAnim != null) flashAnim.cancel();
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
        popSelection();
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

    private List<RectF> lines(int from, int to) {
        List<RectF> out = new ArrayList<>();
        RectF cur = null;
        int curLine = -1;
        for (int i = Math.max(0, from); i <= to && i < rects.length; i++) {
            int ln = res.words.get(i).line;
            if (cur == null || ln != curLine) {
                cur = new RectF(rects[i]);
                out.add(cur);
                curLine = ln;
            } else {
                cur.union(rects[i]);
            }
        }
        return out;
    }

    boolean selectionBounds(RectF out) {
        if (sel0 < 0 || !layout()) return false;
        List<RectF> ls = lines(sel0, sel1);
        if (ls.isEmpty()) return false;
        out.set(ls.get(0));
        for (RectF r : ls) out.union(r);
        return true;
    }

    private float[] handles() {
        List<RectF> ls = lines(sel0, sel1);
        RectF f = ls.get(0);
        RectF l = ls.get(ls.size() - 1);
        float sx = rtl(res.words.get(sel0).text) ? f.right : f.left;
        float ex = rtl(res.words.get(sel1).text) ? l.left : l.right;
        return new float[]{sx, f.top - knobR, f.top, f.bottom, ex, l.bottom + knobR, l.top, l.bottom};
    }

    void draw(Canvas c) {
        if (!layout()) return;
        if (flash > 0.01f) {
            fill.setAlpha((int) (51 * flash));
            stroke.setAlpha((int) (128 * flash));
            for (RectF r : lines(0, rects.length - 1)) {
                c.drawRoundRect(r, radius, radius, fill);
                c.drawRoundRect(r, radius, radius, stroke);
            }
        }
        if (sel0 >= 0) {
            sel.setAlpha((int) (0x5C * selA));
            selEdge.setAlpha((int) (0x99 * selA));
            for (RectF r : lines(sel0, sel1)) {
                c.drawRoundRect(r, radius, radius, sel);
                c.drawRoundRect(r, radius, radius, selEdge);
            }
            float[] h = handles();
            under.setAlpha((int) (255 * selA));
            c.drawLine(h[0], h[2], h[0], h[3], under);
            c.drawLine(h[4], h[6], h[4], h[7], under);
            float kr = knobR * (0.35f + 0.65f * selA);
            drawKnob(c, h[0], h[1], kr);
            drawKnob(c, h[4], h[5], kr);
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
            listener.onSelection(false);
            float[] h = handles();
            if (Math.hypot(x - h[0], y - h[1]) < grab || (Math.abs(x - h[0]) < grab / 2 && y > h[2] && y < h[3])) {
                drag = true;
                anchor = sel1;
                view.invalidate();
                return true;
            }
            if (Math.hypot(x - h[4], y - h[5]) < grab || (Math.abs(x - h[4]) < grab / 2 && y > h[6] && y < h[7])) {
                drag = true;
                anchor = sel0;
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
            popSelection();
            view.invalidate();
            listener.onSelection(true);
            return true;
        }
        return false;
    }
}
