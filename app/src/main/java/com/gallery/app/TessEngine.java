package com.gallery.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import com.googlecode.tesseract.android.ResultIterator;
import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.File;
import java.io.IOException;

final class TessEngine implements OcrEngine {
    private static final String BASE = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/4.1.0/";
    private static final String[] LANGS = {"ara", "eng"};
    private static final int MIN_BYTES = 100000;
    private static final long MAX_BYTES = 60L << 20;

    static File root(Context c) {
        return new File(c.getFilesDir(), "tess");
    }

    static File data(Context c) {
        return new File(root(c), "tessdata");
    }

    static boolean ready(Context c) {
        for (String l : LANGS) if (new File(data(c), l + ".traineddata").length() < MIN_BYTES) return false;
        return true;
    }

    interface Progress {
        void on(int percent);
    }

    static void download(Context c, final Progress cb) throws IOException {
        final long[] size = {1_500_000L, 4_100_000L};
        File dir = data(c);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("dir");
        if (cb != null) cb.on(0);
        for (int i = 0; i < LANGS.length; i++) {
            final int idx = i;
            String l = LANGS[i];
            File f = new File(dir, l + ".traineddata");
            if (f.length() >= MIN_BYTES) continue;
            File tmp = new File(dir, l + ".part");
            Net.download(BASE + l + ".traineddata", tmp, MAX_BYTES, (done, total) -> {
                if (total > 0) size[idx] = total;
                long all = 0;
                long before = 0;
                for (int j = 0; j < size.length; j++) {
                    all += size[j];
                    if (j < idx) before += size[j];
                }
                if (cb != null && all > 0) cb.on((int) Math.min(99, 100 * (before + done) / all));
            });
            if (tmp.length() < MIN_BYTES || !tmp.renameTo(f)) {
                tmp.delete();
                throw new IOException("bad file");
            }
        }
        if (cb != null) cb.on(100);
    }

    @Override
    public OcrResult run(Context c, Bitmap src) throws Exception {
        Bitmap argb = src.getConfig() == Bitmap.Config.ARGB_8888 ? src : src.copy(Bitmap.Config.ARGB_8888, false);
        Bitmap big = OcrPrep.scaleUp(argb);
        Bitmap img = OcrPrep.enhance(big, false);
        TessBaseAPI api = new TessBaseAPI();
        try {
            if (!api.init(root(c).getAbsolutePath(), "ara+eng")) throw new IOException("init");
            api.setVariable("preserve_interword_spaces", "1");
            api.setVariable("user_defined_dpi", "300");
            OcrResult best = pass(api, img, TessBaseAPI.PageSegMode.PSM_AUTO);
            if (best.words.size() < 3) {
                OcrResult sparse = pass(api, img, TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT);
                if (sparse.words.size() > best.words.size()) best = sparse;
            }
            if (best.words.size() < 3) {
                Bitmap inv = OcrPrep.enhance(big, true);
                try {
                    OcrResult flipped = pass(api, inv, TessBaseAPI.PageSegMode.PSM_AUTO);
                    if (flipped.words.size() > best.words.size()) best = flipped;
                } finally {
                    inv.recycle();
                }
            }
            return best;
        } finally {
            api.recycle();
            img.recycle();
            if (big != argb) big.recycle();
        }
    }

    private static OcrResult pass(TessBaseAPI api, Bitmap b, int mode) {
        api.setPageSegMode(mode);
        api.setImage(b);
        api.getUTF8Text();
        OcrResult res = new OcrResult();
        ResultIterator it = api.getResultIterator();
        if (it == null) return res;
        try {
            int level = TessBaseAPI.PageIteratorLevel.RIL_WORD;
            int line = 0;
            float lt = 0;
            float lb = -1;
            it.begin();
            do {
                String t = it.getUTF8Text(level);
                Rect r = it.getBoundingRect(level);
                if (t == null || r == null || t.trim().isEmpty() || it.confidence(level) < 25f) continue;
                float cy = (r.top + r.bottom) / 2f;
                if (lb < 0) {
                    lt = r.top;
                    lb = r.bottom;
                } else if (cy >= lt && cy <= lb) {
                    lt = Math.min(lt, r.top);
                    lb = Math.max(lb, r.bottom);
                } else {
                    line++;
                    lt = r.top;
                    lb = r.bottom;
                }
                res.add(t, r.left, r.top, r.right, r.bottom, b.getWidth(), b.getHeight(), line);
            } while (it.next(level));
        } finally {
            it.delete();
        }
        return res;
    }
}
