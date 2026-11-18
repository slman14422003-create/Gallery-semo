package com.gallery.app;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;

/**
 * iOS interactive pop: drag from the screen edge and the whole screen follows the finger (with a soft shadow on its
 * leading edge) over the screen underneath; let go past half way, or with a flick, and it slides off and closes,
 * otherwise it springs back. The activity must use a translucent theme so the previous screen shows through.
 * Left edge in LTR, right edge in RTL (Arabic).
 */
final class SwipeBack {
    interface Gate {
        boolean allowed();
    }

    private final Activity a;
    private final View target;
    private final Gate gate;
    private final int edge, slop;
    private final boolean rtl;
    private final float dir;          // +1: content moves right (LTR), -1: moves left (RTL)
    private boolean tracking, dragging, closing;
    private float downX, downY, progress;
    private VelocityTracker vt;
    private ValueAnimator settle;

    SwipeBack(Activity a, View target, Gate gate) {
        this.a = a;
        this.target = target;
        this.gate = gate;
        this.edge = Ui.dp(a, 40);
        this.slop = ViewConfiguration.get(a).getScaledTouchSlop();
        this.rtl = a.getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        this.dir = rtl ? -1f : 1f;
    }

    /** Returns true when the event belongs to the swipe (the activity must not pass it on). */
    boolean intercept(MotionEvent e) {
        if (closing) return true;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                release();
                if (!gate.allowed() || target.getWidth() == 0) return false;
                float x = e.getRawX();
                tracking = rtl ? x > target.getWidth() - edge : x < edge;
                if (tracking) {
                    downX = x;
                    downY = e.getRawY();
                    vt = VelocityTracker.obtain();
                    vt.addMovement(e);
                }
                return false;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                if (tracking && !dragging) release();
                return dragging;
            case MotionEvent.ACTION_MOVE: {
                if (!tracking) return false;
                vt.addMovement(e);
                float dx = (e.getRawX() - downX) * dir;
                float dy = e.getRawY() - downY;
                if (!dragging) {
                    if (Math.abs(dy) > slop && Math.abs(dy) > dx) {
                        release();                       // a vertical gesture: not ours
                        return false;
                    }
                    if (dx > slop && dx > Math.abs(dy) * 1.2f) {
                        dragging = true;
                        MotionEvent c = MotionEvent.obtain(e);
                        c.setAction(MotionEvent.ACTION_CANCEL);
                        a.getWindow().superDispatchTouchEvent(c);   // whatever was under the finger lets go
                        c.recycle();
                        target.setElevation(Ui.dp(a, 14));
                        downX = e.getRawX() - dir * slop;           // no jump when the drag starts
                    } else {
                        return false;
                    }
                }
                progress = Math.max(0f, Math.min(target.getWidth(), (e.getRawX() - downX) * dir));
                target.setTranslationX(dir * progress);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!dragging) {
                    release();
                    return false;
                }
                vt.addMovement(e);
                vt.computeCurrentVelocity(1000, ViewConfiguration.get(a).getScaledMaximumFlingVelocity());
                float v = vt.getXVelocity() * dir;
                boolean cancel = e.getActionMasked() == MotionEvent.ACTION_CANCEL;
                boolean close = !cancel && (v > 900f || (progress > target.getWidth() * 0.4f && v > -600f));
                tracking = false;
                dragging = false;
                recycle();
                finishDrag(close);
                return true;
            }
            default:
                return dragging;
        }
    }

    private void finishDrag(boolean close) {
        final float w = target.getWidth();
        final float from = progress;
        final float to = close ? w : 0f;
        if (settle != null) settle.cancel();
        if (close) closing = true;
        long dur = Math.round(120 + 160 * Math.abs(to - from) / Math.max(1f, w));
        settle = ValueAnimator.ofFloat(from, to);
        settle.setDuration(dur);
        settle.setInterpolator(new DecelerateInterpolator(1.6f));
        settle.addUpdateListener(an -> target.setTranslationX(dir * (float) an.getAnimatedValue()));
        settle.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (cancelled) return;
                if (close) {
                    Motion.finishNow(a);
                } else {
                    target.setElevation(0f);
                    progress = 0f;
                }
            }
        });
        settle.start();
    }

    private void release() {
        tracking = false;
        dragging = false;
        recycle();
    }

    private void recycle() {
        if (vt != null) {
            vt.recycle();
            vt = null;
        }
    }
}
