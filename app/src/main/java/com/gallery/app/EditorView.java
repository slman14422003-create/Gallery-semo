package com.gallery.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/** Shows the picture being edited and handles the touch tools: crop handles, freehand drawing, moving text. */
final class EditorView extends View {
    static final int NONE = 0, CROP = 1, DRAW = 2, TEXT = 3;

    interface Listener {
        void onBegin();             // a stroke / text move / crop drag is about to change the state: push an undo step

        void onCommit();            // ...and it finished

        void onLabelTap(EditState.Label l);
    }

    private Bitmap bmp;
    private EditState st;
    private int mode = NONE;
    private Listener listener;
    private final RectF dst = new RectF();
    private final RectF crop = new RectF();     // view pixels
    private float aspect = 0f;                   // w/h, 0 = free
    private int drag = 0;                        // 0 none, 1 move, 2..5 corners TL TR BL BR, 6..9 edges L T R B
    private float lx, ly;
    private int brushColor = 0xFFFFFFFF;
    private float brushWidth = 0.012f;
    private EditState.Stroke live;
    private EditState.Label active;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float touch;

    EditorView(Context c) {
        super(c);
        touch = Ui.dp(c, 28);
        line.setStyle(Paint.Style.STROKE);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void set(Bitmap b, EditState s) {
        bmp = b;
        st = s;
        layoutDst();
        if (mode == CROP) cropFromState();
        invalidate();
    }

    void setMode(int m) {
        mode = m;
        active = null;
        if (m == CROP) cropFromState();
        invalidate();
    }

    void setBrush(int color, float widthFrac) {
        brushColor = color;
        brushWidth = widthFrac;
    }

    void setAspect(float a) {
        aspect = a;
        if (a > 0 && dst.width() > 0) {
            float w = dst.width(), h = w / a;
            if (h > dst.height()) {
                h = dst.height();
                w = h * a;
            }
            crop.set(dst.centerX() - w / 2, dst.centerY() - h / 2, dst.centerX() + w / 2, dst.centerY() + h / 2);
            cropToState();
            invalidate();
        }
    }

    EditState.Label active() {
        return active;
    }

    void setActive(EditState.Label l) {
        active = l;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        layoutDst();
        if (mode == CROP) cropFromState();
    }

    private void layoutDst() {
        if (bmp == null || getWidth() == 0) return;
        float pad = Ui.dp(getContext(), 14);
        float vw = getWidth() - pad * 2, vh = getHeight() - pad * 2;
        float s = Math.min(vw / bmp.getWidth(), vh / bmp.getHeight());
        float w = bmp.getWidth() * s, h = bmp.getHeight() * s;
        dst.set((getWidth() - w) / 2f, (getHeight() - h) / 2f, (getWidth() + w) / 2f, (getHeight() + h) / 2f);
    }

    private void cropFromState() {
        crop.set(dst.left + st.cl * dst.width(), dst.top + st.ct * dst.height(),
                dst.left + st.cr * dst.width(), dst.top + st.cb * dst.height());
    }

    private void cropToState() {
        st.cl = (crop.left - dst.left) / dst.width();
        st.ct = (crop.top - dst.top) / dst.height();
        st.cr = (crop.right - dst.left) / dst.width();
        st.cb = (crop.bottom - dst.top) / dst.height();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (bmp == null) return;
        c.drawBitmap(bmp, null, dst, paint);
        if (mode == CROP) {
            paint.setColor(0x99000000);
            paint.setStyle(Paint.Style.FILL);
            c.drawRect(dst.left, dst.top, dst.right, crop.top, paint);
            c.drawRect(dst.left, crop.bottom, dst.right, dst.bottom, paint);
            c.drawRect(dst.left, crop.top, crop.left, crop.bottom, paint);
            c.drawRect(crop.right, crop.top, dst.right, crop.bottom, paint);
            line.setColor(0xFFFFFFFF);
            line.setStrokeWidth(Ui.dp(getContext(), 2));
            line.setPathEffect(null);
            c.drawRect(crop, line);
            line.setColor(0x66FFFFFF);
            line.setStrokeWidth(Ui.dp(getContext(), 1));
            for (int i = 1; i < 3; i++) {
                c.drawLine(crop.left + crop.width() * i / 3f, crop.top, crop.left + crop.width() * i / 3f, crop.bottom, line);
                c.drawLine(crop.left, crop.top + crop.height() * i / 3f, crop.right, crop.top + crop.height() * i / 3f, line);
            }
            paint.setColor(0xFFFFFFFF);
            float r = Ui.dp(getContext(), 7);
            float[][] pts = {{crop.left, crop.top}, {crop.right, crop.top}, {crop.left, crop.bottom}, {crop.right, crop.bottom}};
            for (float[] p : pts) c.drawCircle(p[0], p[1], r, paint);
            paint.setStyle(Paint.Style.FILL);
            return;
        }
        // overlays live in the picture's coordinates
        int save = c.save();
        c.translate(dst.left, dst.top);
        float w = dst.width(), h = dst.height();
        Renderer.overlays(c, Math.round(w), Math.round(h), st);
        if (live != null) {
            EditState tmp = new EditState();
            tmp.strokes.add(live);
            Renderer.overlays(c, Math.round(w), Math.round(h), tmp);
        }
        c.restoreToCount(save);
        if (mode == TEXT && active != null && !active.text.isEmpty()) {
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setTextSize(active.size * h);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            float tw = t.measureText(active.text), th = active.size * h;
            float cx = dst.left + active.x * w, cy = dst.top + active.y * h;
            line.setColor(0xFFFFFFFF);
            line.setStrokeWidth(Ui.dp(getContext(), 1) * 1.5f);
            line.setPathEffect(new DashPathEffect(new float[]{14, 10}, 0));
            c.drawRect(cx - tw / 2 - 12, cy - th * 0.7f, cx + tw / 2 + 12, cy + th * 0.55f, line);
            line.setPathEffect(null);
        }
    }

    private int hit(float x, float y) {
        float t = touch;
        boolean l = Math.abs(x - crop.left) < t, r = Math.abs(x - crop.right) < t;
        boolean tp = Math.abs(y - crop.top) < t, b = Math.abs(y - crop.bottom) < t;
        if (l && tp) return 2;
        if (r && tp) return 3;
        if (l && b) return 4;
        if (r && b) return 5;
        if (aspect == 0) {
            if (l && y > crop.top && y < crop.bottom) return 6;
            if (tp && x > crop.left && x < crop.right) return 7;
            if (r && y > crop.top && y < crop.bottom) return 8;
            if (b && x > crop.left && x < crop.right) return 9;
        }
        if (crop.contains(x, y)) return 1;
        return 0;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (bmp == null || st == null) return false;
        float x = e.getX(), y = e.getY();
        switch (mode) {
            case CROP:
                return touchCrop(e, x, y);
            case DRAW:
                return touchDraw(e, x, y);
            case TEXT:
                return touchText(e, x, y);
            default:
                return false;
        }
    }

    private boolean touchCrop(MotionEvent e, float x, float y) {
        float min = Ui.dp(getContext(), 56);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                drag = hit(x, y);
                lx = x;
                ly = y;
                if (drag != 0 && listener != null) listener.onBegin();
                return drag != 0;
            case MotionEvent.ACTION_MOVE:
                if (drag == 0) return false;
                float dx = x - lx, dy = y - ly;
                lx = x;
                ly = y;
                if (drag == 1) {
                    float nx = Math.max(dst.left - crop.left, Math.min(dst.right - crop.right, dx));
                    float ny = Math.max(dst.top - crop.top, Math.min(dst.bottom - crop.bottom, dy));
                    crop.offset(nx, ny);
                } else if (aspect > 0 && drag >= 2 && drag <= 5) {
                    // keep the opposite corner fixed and the ratio locked
                    float ax = (drag == 2 || drag == 4) ? crop.right : crop.left;
                    float ay = (drag == 2 || drag == 3) ? crop.bottom : crop.top;
                    float w = Math.abs(x - ax), h = w / aspect;
                    float maxW = (drag == 2 || drag == 4) ? ax - dst.left : dst.right - ax;
                    float maxH = (drag == 2 || drag == 3) ? ay - dst.top : dst.bottom - ay;
                    w = Math.max(min, Math.min(w, Math.min(maxW, maxH * aspect)));
                    h = w / aspect;
                    float left = (drag == 2 || drag == 4) ? ax - w : ax;
                    float top = (drag == 2 || drag == 3) ? ay - h : ay;
                    crop.set(left, top, left + w, top + h);
                } else {
                    if (drag == 2 || drag == 4 || drag == 6) crop.left = Math.max(dst.left, Math.min(crop.right - min, crop.left + dx));
                    if (drag == 3 || drag == 5 || drag == 8) crop.right = Math.min(dst.right, Math.max(crop.left + min, crop.right + dx));
                    if (drag == 2 || drag == 3 || drag == 7) crop.top = Math.max(dst.top, Math.min(crop.bottom - min, crop.top + dy));
                    if (drag == 4 || drag == 5 || drag == 9) crop.bottom = Math.min(dst.bottom, Math.max(crop.top + min, crop.bottom + dy));
                }
                cropToState();
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (drag != 0 && listener != null) listener.onCommit();
                drag = 0;
                return true;
            default:
                return true;
        }
    }

    private boolean touchDraw(MotionEvent e, float x, float y) {
        float nx = (x - dst.left) / dst.width(), ny = (y - dst.top) / dst.height();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!dst.contains(x, y)) return false;
                if (listener != null) listener.onBegin();
                live = new EditState.Stroke();
                live.color = brushColor;
                live.width = brushWidth;
                live.pts.add(new float[]{nx, ny});
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (live == null) return false;
                live.pts.add(new float[]{Math.max(0, Math.min(1, nx)), Math.max(0, Math.min(1, ny))});
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (live != null) {
                    st.strokes.add(live);
                    live = null;
                    if (listener != null) listener.onCommit();
                    invalidate();
                }
                return true;
            default:
                return true;
        }
    }

    private EditState.Label labelAt(float x, float y) {
        float w = dst.width(), h = dst.height();
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        for (int i = st.labels.size() - 1; i >= 0; i--) {
            EditState.Label l = st.labels.get(i);
            t.setTextSize(l.size * h);
            float tw = t.measureText(l.text) / 2 + 24, th = l.size * h * 0.75f + 16;
            float cx = dst.left + l.x * w, cy = dst.top + l.y * h;
            if (Math.abs(x - cx) < tw && Math.abs(y - cy) < th) return l;
        }
        return null;
    }

    private float downX, downY;
    private boolean moved;

    private boolean touchText(MotionEvent e, float x, float y) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                moved = false;
                EditState.Label l = labelAt(x, y);
                if (l != null) {
                    active = l;
                    invalidate();
                    return true;
                }
                if (listener != null) listener.onLabelTap(null);
                active = null;
                invalidate();
                return false;
            case MotionEvent.ACTION_MOVE:
                if (active == null) return false;
                if (!moved && Math.hypot(x - downX, y - downY) > Ui.dp(getContext(), 6)) {
                    moved = true;
                    if (listener != null) listener.onBegin();
                }
                if (moved) {
                    active.x = Math.max(0f, Math.min(1f, (x - dst.left) / dst.width()));
                    active.y = Math.max(0f, Math.min(1f, (y - dst.top) / dst.height()));
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (active != null) {
                    if (moved) {
                        if (listener != null) listener.onCommit();
                    } else if (listener != null) listener.onLabelTap(active);
                }
                return true;
            default:
                return true;
        }
    }
}
