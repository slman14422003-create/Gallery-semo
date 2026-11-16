package com.gallery.app;

import android.content.Context;
import android.graphics.Bitmap;

interface OcrEngine {
    OcrResult run(Context c, Bitmap b) throws Exception;
}
