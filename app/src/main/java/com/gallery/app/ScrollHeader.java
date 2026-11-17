package com.gallery.app;

import android.view.View;

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
        rv.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(View v) {
                sync();
            }

            @Override
            public void onChildViewDetachedFromWindow(View v) {
                sync();
            }
        });
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
        if (h <= 0) return;
        float ty = 0f;
        if (!pinned) {
            RecyclerView.Adapter<?> ad = rv.getAdapter();
            RecyclerView.LayoutManager lm = rv.getLayoutManager();
            if (ad != null && lm != null && ad.getItemCount() > 0) {
                RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(0);
                if (vh == null) {
                    ty = -h;
                } else {
                    int t = lm.getDecoratedTop(vh.itemView);
                    ty = Math.max(-h, Math.min(0, t - h));
                }
            }
        }
        if (top.getTranslationY() != ty) top.setTranslationY(ty);
    }
}
