package com.gallery.app;

import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

final class DismissLayout extends FrameLayout {
    interface Listener {
        boolean canDismiss();

        void onProgress(float p);

        void onCancel();

        void onDismiss();
    }

    private final float slop;
    private final float trigger;
    private Listener listener;
    private float downX, downY;
    private boolean dragging;
    private VelocityTracker velocity;

    DismissLayout(Context c) {
        super(c);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        trigger = Ui.dp(c, 120);
    }

    void setListener(Listener l) {
        listener = l;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = e.getX();
                downY = e.getY();
                dragging = false;
                View t = getChildAt(0);
                if (t != null && (t.getTranslationY() != 0f || t.getScaleX() != 1f)) {
                    t.animate().cancel();
                    t.setTranslationX(0f);
                    t.setTranslationY(0f);
                    t.setScaleX(1f);
                    t.setScaleY(1f);
                }
                break;
            }
            case MotionEvent.ACTION_MOVE:
                if (dragging) return true;
                if (e.getPointerCount() > 1 || listener == null) return false;
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (dy > slop && dy > Math.abs(dx) * 1.4f && listener.canDismiss()) {
                    dragging = true;
                    downX = e.getX();
                    downY = e.getY();
                    velocity = VelocityTracker.obtain();
                    velocity.addMovement(e);
                    return true;
                }
                break;
            default:
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!dragging) return false;
        velocity.addMovement(e);
        View t = getChildAt(0);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - downX, dy = e.getY() - downY;
                float p = Math.max(0f, Math.min(1f, dy / (getHeight() * 0.45f)));
                float s = 1f - 0.22f * p;
                t.setScaleX(s);
                t.setScaleY(s);
                t.setTranslationX(dx * 0.9f);
                t.setTranslationY(dy > 0 ? dy : dy * 0.25f);
                listener.onProgress(p);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                float dy = e.getY() - downY;
                velocity.computeCurrentVelocity(1000);
                float vy = velocity.getYVelocity();
                velocity.recycle();
                velocity = null;
                dragging = false;
                if (e.getActionMasked() == MotionEvent.ACTION_UP && (dy > trigger || vy > 1600f)) {
                    listener.onDismiss();
                } else {
                    t.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(260)
                            .setInterpolator(new OvershootInterpolator(0.7f)).start();
                    listener.onCancel();
                }
                return true;
            }
            default:
                return true;
        }
    }
}
