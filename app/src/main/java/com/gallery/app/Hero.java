package com.gallery.app;

import android.graphics.Rect;

/** Hand-over between the grid and the viewer for the iOS style zoom transition (tile to full screen and back). */
final class Hero {
    interface Locator {
        /** Screen rectangle of the visible tile showing {@code key}, or null when it is not on screen. */
        Rect find(String key);
    }

    static Rect rect;
    static String key;
    static int px;
    static Locator locator;

    private Hero() {
    }

    static void set(String k, Rect r, int size, Locator l) {
        key = k;
        rect = r;
        px = size;
        locator = l;
    }

    static void clear() {
        key = null;
        rect = null;
        locator = null;
    }
}
