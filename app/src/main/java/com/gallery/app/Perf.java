package com.gallery.app;

import android.content.Context;

import eightbitlab.com.blurview.BlurTarget;

/** Performance switches. Live glass blur re-renders behind the bars every frame, so it is off unless the user turns it on. */
final class Perf {
    private Perf() {
    }

    static boolean blur(Context c) {
        return Store.bool(c, "glass_blur", false);
    }

    static void apply(Context c) {
        BlurTarget.globalRecording = blur(c);
    }
}
