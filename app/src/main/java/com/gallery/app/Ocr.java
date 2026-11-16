package com.gallery.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy;
import com.bumptech.glide.request.FutureTarget;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Ocr {
    private Ocr() {
    }

    static final String AUTO = "auto";
    static final String MLKIT = "mlkit";
    static final String TESS = "tess";
    static final String VISION = "vision";

    private static final int MAX_SIDE = 3200;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final LruCache<String, OcrResult> CACHE = new LruCache<>(12);

    interface Callback {
        void onDownload(int percent);

        void onDone(OcrResult r);

        void onError(int msg);
    }

    static String mode(Context c) {
        return Store.str(c, "ocr_engine", AUTO);
    }

    static void setMode(Context c, String m) {
        Store.setStr(c, "ocr_engine", m);
    }

    static String resolve(Context c) {
        String m = mode(c);
        if (AUTO.equals(m)) return Lang.isAr() ? TESS : MLKIT;
        return m;
    }

    static boolean needsKey(Context c) {
        return VISION.equals(resolve(c)) && VisionEngine.key(c).isEmpty();
    }

    private static String tag(Context app) {
        return mode(app) + (TessEngine.ready(app) ? "+t" : "");
    }

    static void run(Context ctx, final Media m, final Callback cb) {
        final Context app = ctx.getApplicationContext();
        final String mode = mode(app);
        final String base = m.key() + ":" + m.modified + ":";
        OcrResult hit = CACHE.get(base + tag(app));
        if (hit != null) {
            cb.onDone(hit);
            return;
        }
        POOL.execute(() -> {
            FutureTarget<Bitmap> ft = null;
            try {
                final boolean auto = AUTO.equals(mode);
                boolean useTess = TESS.equals(mode) || (auto && (Lang.isAr() || TessEngine.ready(app)));
                final boolean useMl = MLKIT.equals(mode) || auto;
                if (useTess && !TessEngine.ready(app)) {
                    MAIN.post(() -> cb.onDownload(0));
                    final int[] last = {-1};
                    try {
                        TessEngine.download(app, pct -> {
                            if (pct == last[0]) return;
                            last[0] = pct;
                            MAIN.post(() -> cb.onDownload(pct));
                        });
                    } catch (IOException e) {
                        if (!auto) {
                            MAIN.post(() -> cb.onError(R.string.ocr_download_failed));
                            return;
                        }
                        useTess = false;
                    }
                }
                MAIN.post(() -> cb.onDownload(-1));
                ft = Glide.with(app).asBitmap().load(m.uri).override(MAX_SIDE, MAX_SIDE)
                        .downsample(DownsampleStrategy.AT_MOST).submit();
                Bitmap bmp = ft.get();
                List<OcrResult> parts = new ArrayList<>();
                Throwable failure = null;
                if (useMl) {
                    try {
                        parts.add(new MlKitEngine().run(app, bmp));
                    } catch (Throwable t) {
                        failure = t;
                    }
                }
                if (useTess) {
                    try {
                        parts.add(new TessEngine().run(app, bmp));
                    } catch (Throwable t) {
                        failure = t;
                    }
                }
                if (VISION.equals(mode)) parts.add(new VisionEngine().run(app, bmp));
                if (parts.isEmpty()) throw new IOException("ocr", failure);
                final OcrResult r = parts.size() == 1 ? parts.get(0) : OcrResult.merge(parts);
                CACHE.put(base + tag(app), r);
                MAIN.post(() -> cb.onDone(r));
            } catch (Throwable t) {
                MAIN.post(() -> cb.onError(R.string.ocr_failed));
            } finally {
                if (ft != null) Glide.with(app).clear(ft);
            }
        });
    }
}
