package com.gallery.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
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
    static final int ERASER = -1;
    private int tool = EditState.PEN;            // pen / marker / shapes / ERASER
    private boolean eraseBegan;
    private boolean grid;
    private float grabX, grabY;
    private Bitmap plain;                        // the picture without colour edits and overlays (hold to compare)
    private boolean showOriginal;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tonePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tonePlain = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
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

    void set(Bitmap b, EditState s, Bitmap original, ColorMatrix tone) {
        bmp = b;
        plain = original;
        st = s;
        tonePaint.setColorFilter(tone == null ? null : new ColorMatrixColorFilter(tone));
        layoutDst();
        if (mode == CROP) cropFromState();
        invalidate();
    }

    void setMode(int m) {
        mode = m;
        active = null;
        if (m == CROP && st != null && dst.width() > 0) cropFromState();
        invalidate();
    }

    void setTool(int t) {
        tool = t;
    }

    /** While held, the picture shows without any edits so the effect can be compared. */
    void showOriginal(boolean on) {
        showOriginal = on && plain != null;
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

    /** Locks the ratio without moving the crop rectangle (used after a rotation, where the rectangle was rotated too). */
    void setAspectLock(float a) {
        aspect = a;
    }

    float aspect() {
        return aspect;
    }

    /** Shows the thirds grid even when no handle is being dragged (while the straighten ruler moves). */
    void setGrid(boolean on) {
        if (grid == on) return;
        grid = on;
        invalidate();
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
        if (st == null || dst.width() <= 0) return;
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
        if (showOriginal && plain != null) {
            c.drawBitmap(plain, null, dst, tonePlain);
            return;
        }
        c.drawBitmap(bmp, null, dst, tonePaint);
        if (mode == CROP) {
            paint.setColor(0x99000000);
            paint.setStyle(Paint.Style.FILL);
            c.drawRect(dst.left, dst.top, dst.right, crop.top, paint);
            c.drawRect(dst.left, crop.bottom, dst.right, dst.bottom, paint);
            c.drawRect(dst.left, crop.top, crop.left, crop.bottom, paint);
            c.drawRect(crop.right, crop.top, dst.right, crop.bottom, paint);
            line.setPathEffect(null);
            line.setStrokeCap(Paint.Cap.BUTT);
            line.setColor(0xCCFFFFFF);
            line.setStrokeWidth(Ui.dp(getContext(), 1));
            c.drawRect(crop, line);
            if (drag != 0 || grid) {   // the thirds grid only appears while a handle or the ruler is being dragged (like iOS Photos)
                line.setColor(0x80FFFFFF);
                line.setStrokeWidth(Ui.dp(getContext(), 1) * 0.6f);
                for (int i = 1; i < 3; i++) {
                    c.drawLine(crop.left + crop.width() * i / 3f, crop.top, crop.left + crop.width() * i / 3f, crop.bottom, line);
                    c.drawLine(crop.left, crop.top + crop.height() * i / 3f, crop.right, crop.top + crop.height() * i / 3f, line);
                }
            }
            // thick corner brackets and short edge bars
            float sw = Ui.dpf(getContext(), 3.5f);
            float len = Math.min(Ui.dp(getContext(), 22), Math.min(crop.width(), crop.height()) / 3f);
            line.setColor(0xFFFFFFFF);
            line.setStrokeWidth(sw);
            line.setStrokeCap(Paint.Cap.SQUARE);
            float o = sw / 2f;
            c.drawLine(crop.left - o, crop.top, crop.left + len, crop.top, line);
            c.drawLine(crop.left, crop.top - o, crop.left, crop.top + len, line);
            c.drawLine(crop.right + o, crop.top, crop.right - len, crop.top, line);
            c.drawLine(crop.right, crop.top - o, crop.right, crop.top + len, line);
            c.drawLine(crop.left - o, crop.bottom, crop.left + len, crop.bottom, line);
            c.drawLine(crop.left, crop.bottom + o, crop.left, crop.bottom - len, line);
            c.drawLine(crop.right + o, crop.bottom, crop.right - len, crop.bottom, line);
            c.drawLine(crop.right, crop.bottom + o, crop.right, crop.bottom - len, line);
            if (aspect == 0) {
                float half = len / 2f;
                c.drawLine(crop.centerX() - half, crop.top, crop.centerX() + half, crop.top, line);
                c.drawLine(crop.centerX() - half, crop.bottom, crop.centerX() + half, crop.bottom, line);
                c.drawLine(crop.left, crop.centerY() - half, crop.left, crop.centerY() + half, line);
                c.drawLine(crop.right, crop.centerY() - half, crop.right, crop.centerY() + half, line);
            }
            line.setStrokeCap(Paint.Cap.BUTT);
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
            RectF b = Renderer.labelBounds(active, w, h);
            b.offset(dst.left, dst.top);
            b.inset(-Ui.dp(getContext(), 4), -Ui.dp(getContext(), 2));
            line.setColor(0xFFFFFFFF);
            line.setStrokeWidth(Ui.dpf(getContext(), 1.5f));
            line.setPathEffect(new DashPathEffect(new float[]{14, 10}, 0));
            c.drawRoundRect(b, Ui.dp(getContext(), 8), Ui.dp(getContext(), 8), line);
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
                return touchCompare(e);
        }
    }

    /** Pressing and holding the photo shows the original (adjust and filter tabs), like iOS Photos. */
    private boolean touchCompare(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (plain == null) return false;
                showOriginal(true);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                showOriginal(false);
                return true;
            default:
                return true;
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
                invalidate();
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
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private boolean touchDraw(MotionEvent e, float x, float y) {
        float nx = (x - dst.left) / dst.width(), ny = (y - dst.top) / dst.height();
        if (tool == ERASER) return touchErase(e, x, y);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!dst.contains(x, y)) return false;
                if (listener != null) listener.onBegin();
                live = new EditState.Stroke();
                live.type = tool;
                live.color = brushColor;
                live.width = brushWidth;
                live.pts.add(new float[]{nx, ny});
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (live == null) return false;
                float cx = Math.max(0, Math.min(1, nx)), cy = Math.max(0, Math.min(1, ny));
                if (tool >= EditState.LINE) {
                    // shapes only need the start and the current end point
                    if (live.pts.size() > 1) live.pts.set(1, new float[]{cx, cy});
                    else live.pts.add(new float[]{cx, cy});
                } else {
                    float[] last = live.pts.get(live.pts.size() - 1);
                    float dx = (cx - last[0]) * dst.width(), dy = (cy - last[1]) * dst.height();
                    if (dx * dx + dy * dy >= 4f) live.pts.add(new float[]{cx, cy});   // skip sub-2px jitter
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (live != null) {
                    boolean keep = e.getActionMasked() == MotionEvent.ACTION_UP && (tool < EditState.LINE || live.pts.size() > 1);
                    if (keep) st.strokes.add(live);
                    live = null;
                    if (listener != null) listener.onCommit();
                    invalidate();
                }
                return true;
            default:
                return true;
        }
    }

    /** Eraser: removes every stroke / shape the finger touches. */
    private boolean touchErase(MotionEvent e, float x, float y) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                eraseBegan = false;
                eraseAt(x, y);
                return true;
            case MotionEvent.ACTION_MOVE:
                eraseAt(x, y);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (eraseBegan && listener != null) listener.onCommit();
                eraseBegan = false;
                return true;
            default:
                return true;
        }
    }

    private void eraseAt(float x, float y) {
        float w = dst.width(), h = dst.height();
        float reach = Ui.dp(getContext(), 16);
        for (int i = st.strokes.size() - 1; i >= 0; i--) {
            EditState.Stroke k = st.strokes.get(i);
            if (!strokeHit(k, x, y, w, h, reach)) continue;
            if (!eraseBegan) {
                eraseBegan = true;
                if (listener != null) listener.onBegin();   // undo step is taken before the first removal
            }
            st.strokes.remove(i);
            invalidate();
        }
    }

    private boolean strokeHit(EditState.Stroke k, float x, float y, float w, float h, float reach) {
        float r = reach + k.width * w / 2f;
        if (k.type >= EditState.LINE && k.pts.size() > 1) {
            float[] a = k.pts.get(0), b = k.pts.get(1);
            float ax = dst.left + a[0] * w, ay = dst.top + a[1] * h, bx = dst.left + b[0] * w, by = dst.top + b[1] * h;
            if (k.type == EditState.LINE || k.type == EditState.ARROW) return segDist(x, y, ax, ay, bx, by) < r;
            return x > Math.min(ax, bx) - r && x < Math.max(ax, bx) + r && y > Math.min(ay, by) - r && y < Math.max(ay, by) + r;
        }
        if (k.type == EditState.MARKER) r += k.width * w * 0.8f;
        for (int i = 0; i < k.pts.size(); i++) {
            float[] p = k.pts.get(i);
            float px = dst.left + p[0] * w, py = dst.top + p[1] * h;
            if (i == 0 ? Math.hypot(x - px, y - py) < r : segDist(x, y, dst.left + k.pts.get(i - 1)[0] * w,
                    dst.top + k.pts.get(i - 1)[1] * h, px, py) < r) return true;
        }
        return false;
    }

    private static float segDist(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay;
        float len2 = dx * dx + dy * dy;
        float t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
        return (float) Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    private EditState.Label labelAt(float x, float y) {
        float w = dst.width(), h = dst.height();
        float slop = Ui.dp(getContext(), 10);
        for (int i = st.labels.size() - 1; i >= 0; i--) {
            EditState.Label l = st.labels.get(i);
            RectF b = Renderer.labelBounds(l, w, h);
            b.offset(dst.left, dst.top);
            b.inset(-slop, -slop);
            if (b.contains(x, y)) return l;
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
                    grabX = l.x - (x - dst.left) / dst.width();
                    grabY = l.y - (y - dst.top) / dst.height();
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
                    active.x = Math.max(0f, Math.min(1f, (x - dst.left) / dst.width() + grabX));
                    active.y = Math.max(0f, Math.min(1f, (y - dst.top) / dst.height() + grabY));
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
