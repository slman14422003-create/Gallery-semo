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

    static void download(Context c) throws IOException {
        File dir = data(c);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("dir");
        for (String l : LANGS) {
            File f = new File(dir, l + ".traineddata");
            if (f.length() >= MIN_BYTES) continue;
            File tmp = new File(dir, l + ".part");
            Net.download(BASE + l + ".traineddata", tmp, MAX_BYTES);
            if (tmp.length() < MIN_BYTES || !tmp.renameTo(f)) {
                tmp.delete();
                throw new IOException("bad file");
            }
        }
    }

    @Override
    public OcrResult run(Context c, Bitmap src) throws Exception {
        Bitmap b = src.getConfig() == Bitmap.Config.ARGB_8888 ? src : src.copy(Bitmap.Config.ARGB_8888, false);
        TessBaseAPI api = new TessBaseAPI();
        try {
            if (!api.init(root(c).getAbsolutePath(), "ara+eng")) throw new IOException("init");
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO);
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
        } finally {
            api.recycle();
        }
    }
}
