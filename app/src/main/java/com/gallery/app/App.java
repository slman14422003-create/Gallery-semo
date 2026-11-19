package com.gallery.app;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Android 15+ (and every app targeting API 35/36) draws edge-to-edge and the old opt-out is gone on
 * Android 16. This keeps every screen clear of the status bar, navigation bar, cutout and keyboard.
 */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(this);
        Lang.init(this);
        Appearance.applyMode(this);
        Perf.apply(this);
        // Glide: a memory cache sized for a few screens of thumbnails and a bitmap pool, set up once before first use
        try {
            long max = Runtime.getRuntime().maxMemory();
            com.bumptech.glide.Glide.init(this, new com.bumptech.glide.GlideBuilder()
                    .setMemoryCache(new com.bumptech.glide.load.engine.cache.LruResourceCache(Math.min(160L << 20, max / 6)))
                    .setBitmapPool(new com.bumptech.glide.load.engine.bitmap_recycle.LruBitmapPool(Math.min(96L << 20, max / 10)))
                    .setLogLevel(android.util.Log.ERROR));
        } catch (Throwable ignored) {
        }
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle b) { }

            @Override
            public void onActivityStarted(Activity a) {
                if (Build.VERSION.SDK_INT < 35) return;
                View content = a.findViewById(android.R.id.content);
                if (content == null || content.getTag(R.id.tag_insets) != null) return;
                content.setTag(R.id.tag_insets, Boolean.TRUE);
                ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
                    Insets b = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
                    v.setPadding(b.left, b.top, b.right, b.bottom);
                    return WindowInsetsCompat.CONSUMED;
                });
                ViewCompat.requestApplyInsets(content);
            }

            @Override public void onActivityResumed(Activity a) { }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivityStopped(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });
    }
}
