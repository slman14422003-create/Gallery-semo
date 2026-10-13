package com.gallery.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.animation.DecelerateInterpolator;

import androidx.appcompat.widget.AppCompatImageView;

/** Image view with pinch zoom, drag, double-tap zoom and a single-tap callback; plays nicely inside a pager. */
public class PhotoView extends AppCompatImageView {
    public interface TapListener {
        void onTap();
    }

    private static final float MAX_ZOOM = 8f;

    private final Matrix matrix = new Matrix();
    private final float[] vals = new float[9];
    private float baseScale = 1f;
    private ValueAnimator zoomAnim;
    private TapListener tap;
    private OcrLayer layer;
    private boolean layerActive;
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    public PhotoView(Context c) {
        this(c, null);
    }

    public PhotoView(Context c, AttributeSet a) {
        super(c, a);
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float cur = currentScale();
                float target = Math.max(baseScale * 0.9f, Math.min(baseScale * MAX_ZOOM, cur * d.getScaleFactor()));
                float f = target / cur;
                matrix.postScale(f, f, d.getFocusX(), d.getFocusY());
                fixTranslation();
                setImageMatrix(matrix);
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector d) {
                if (currentScale() < baseScale) animateScale(baseScale, getWidth() / 2f, getHeight() / 2f);
            }
        });
        gestureDetector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (layer != null && layer.onBackgroundTap()) return true;
                if (tap != null) tap.onTap();
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (!isZoomed()) return false;
                matrix.getValues(vals);
                float s = vals[Matrix.MSCALE_X];
                Drawable d = getDrawable();
                float w = d == null ? 0 : d.getIntrinsicWidth() * s;
                float tx = vals[Matrix.MTRANS_X];
                boolean atLeft = tx >= -1f && dx < 0;
                boolean atRight = tx + w <= getWidth() + 1f && dx > 0;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(!(atLeft || atRight));
                matrix.postTranslate(-dx, -dy);
                fixTranslation();
                setImageMatrix(matrix);
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (isZoomed()) animateScale(baseScale, getWidth() / 2f, getHeight() / 2f);
                else animateScale(baseScale * 2.6f, e.getX(), e.getY());
                return true;
            }
        });
    }

    public void setTapListener(TapListener l) {
        tap = l;
    }

    public void setLayer(OcrLayer l) {
        layer = l;
        layerActive = false;
        invalidate();
    }

    Matrix currentMatrix() {
        return matrix;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (layer != null) layer.draw(canvas);
    }

    @Override
    public void setImageDrawable(Drawable d) {
        super.setImageDrawable(d);
        fit();
    }

    /** Back to the fitted, unzoomed state. */
    public void reset() {
        if (zoomAnim != null) zoomAnim.cancel();
        fit();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        fit();
    }

    private float currentScale() {
        matrix.getValues(vals);
        return vals[Matrix.MSCALE_X];
    }

    public boolean isZoomed() {
        return currentScale() > baseScale * 1.02f;
    }

    private void animateScale(final float target, final float fx, final float fy) {
        if (zoomAnim != null) zoomAnim.cancel();
        final float start = currentScale();
        zoomAnim = ValueAnimator.ofFloat(0f, 1f);
        zoomAnim.setDuration(260);
        zoomAnim.setInterpolator(new DecelerateInterpolator(1.6f));
        zoomAnim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            float want = start + (target - start) * t;
            float f = want / currentScale();
            matrix.postScale(f, f, fx, fy);
            fixTranslation();
            setImageMatrix(matrix);
        });
        zoomAnim.start();
    }

    private void fit() {
        Drawable d = getDrawable();
        int vw = getWidth();
        int vh = getHeight();
        if (d == null || vw == 0 || vh == 0) return;
        int dw = d.getIntrinsicWidth();
        int dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) return;
        baseScale = Math.min((float) vw / dw, (float) vh / dh);
        matrix.reset();
        matrix.postScale(baseScale, baseScale);
        matrix.postTranslate((vw - dw * baseScale) / 2f, (vh - dh * baseScale) / 2f);
        setImageMatrix(matrix);
    }

    private void fixTranslation() {
        Drawable d = getDrawable();
        if (d == null) return;
        matrix.getValues(vals);
        float s = vals[Matrix.MSCALE_X];
        float w = d.getIntrinsicWidth() * s;
        float h = d.getIntrinsicHeight() * s;
        float tx = vals[Matrix.MTRANS_X];
        float ty = vals[Matrix.MTRANS_Y];
        float vw = getWidth();
        float vh = getHeight();
        float nx = w <= vw ? (vw - w) / 2f : Math.max(vw - w, Math.min(0f, tx));
        float ny = h <= vh ? (vh - h) / 2f : Math.max(vh - h, Math.min(0f, ty));
        matrix.postTranslate(nx - tx, ny - ty);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int act = ev.getActionMasked();
        if (layer != null) {
            if (act == MotionEvent.ACTION_DOWN) layerActive = false;
            if (layer.onTouch(ev)) {
                if (!layerActive) {
                    layerActive = true;
                    MotionEvent cancel = MotionEvent.obtain(ev);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    scaleDetector.onTouchEvent(cancel);
                    gestureDetector.onTouchEvent(cancel);
                    cancel.recycle();
                }
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
        }
        if (act == MotionEvent.ACTION_DOWN) {
            if (zoomAnim != null) zoomAnim.cancel();
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(isZoomed());
        } else if (act == MotionEvent.ACTION_POINTER_DOWN && getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        scaleDetector.onTouchEvent(ev);
        if (!scaleDetector.isInProgress()) gestureDetector.onTouchEvent(ev);
        return true;
    }
}
