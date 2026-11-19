package com.gallery.app;

import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Makes a header that lives above a RecyclerView part of the page: the header is laid over the list, the list
 * gets top padding of the header's height, and the header follows the first row up while scrolling (and
 * comes back when the list returns to the top). While {@link #setPinned} is on it stays put (selection mode).
 */
final class ScrollHeader {
    private final RecyclerView rv;
    private final View top;
    private final int padL, padR;
    private int padB;
    private int topH = -1;
    private boolean pinned;

    ScrollHeader(RecyclerView rv, View top, int bottomPx) {
        this.rv = rv;
        this.top = top;
        this.padL = rv.getPaddingLeft();
        this.padR = rv.getPaddingRight();
        this.padB = bottomPx;
        top.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != topH) {
                topH = b - t;
                applyPadding();
            }
            sync();
        });
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView r, int dx, int dy) {
                sync();
            }
        });
        // after every layout pass (data replaced, tab shown again, rotation) re-derive the header from the list itself
        rv.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> sync());
    }

    private void applyPadding() {
        rv.setPadding(padL, Math.max(0, topH), padR, padB);
    }

    void setBottom(int px) {
        padB = px;
        applyPadding();
    }

    void setPinned(boolean p) {
        if (pinned == p) return;
        pinned = p;
        sync();
    }

    void sync() {
        int h = top.getHeight();
        if (h <= 0 || rv.isComputingLayout()) return;
        float ty = 0f;
        if (!pinned) {
            RecyclerView.Adapter<?> ad = rv.getAdapter();
            RecyclerView.LayoutManager lm = rv.getLayoutManager();
            if (ad != null && lm instanceof LinearLayoutManager && ad.getItemCount() > 0 && lm.getChildCount() > 0) {
                LinearLayoutManager ll = (LinearLayoutManager) lm;
                if (ll.findFirstVisibleItemPosition() > 0) {
                    ty = -h;                                     // scrolled well past the top
                } else {
                    View first = ll.findViewByPosition(0);
                    // row 0 not laid out yet (data was just replaced): keep the header, never leave a black gap
                    ty = first == null ? 0f : Math.max(-h, Math.min(0, lm.getDecoratedTop(first) - h));
                }
            }
        }
        if (top.getTranslationY() != ty) top.setTranslationY(ty);
    }
}
