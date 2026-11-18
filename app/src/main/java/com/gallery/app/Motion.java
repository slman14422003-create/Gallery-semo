package com.gallery.app;

import android.app.Activity;
import android.os.Build;

final class Motion {
    private Motion() {
    }

    static void install(Activity a, int openEnter, int openExit, int closeEnter, int closeExit) {
        if (Build.VERSION.SDK_INT >= 34) {
            a.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, openEnter, openExit);
            a.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, closeEnter, closeExit);
        }
    }

    /** Closes the activity with no transition of its own (it has already slid away under the finger). */
    @SuppressWarnings("deprecation")
    static void finishNow(Activity a) {
        if (Build.VERSION.SDK_INT >= 34) a.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0);
        a.finish();
        if (Build.VERSION.SDK_INT < 34) a.overridePendingTransition(0, 0);
    }

    @SuppressWarnings("deprecation")
    static void legacy(Activity a, int enter, int exit) {
        if (Build.VERSION.SDK_INT < 34) a.overridePendingTransition(enter, exit);
    }
}
