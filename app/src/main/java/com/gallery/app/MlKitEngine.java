package com.gallery.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

final class MlKitEngine implements OcrEngine {
    @Override
    public OcrResult run(Context c, Bitmap b) throws Exception {
        TextRecognizer r = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        try {
            Text t = Tasks.await(r.process(InputImage.fromBitmap(b, 0)));
            OcrResult res = new OcrResult();
            int line = 0;
            for (Text.TextBlock bl : t.getTextBlocks()) {
                for (Text.Line ln : bl.getLines()) {
                    for (Text.Element el : ln.getElements()) {
                        Rect rc = el.getBoundingBox();
                        if (rc == null) continue;
                        res.add(el.getText(), rc.left, rc.top, rc.right, rc.bottom, b.getWidth(), b.getHeight(), line);
                    }
                    line++;
                }
            }
            return res;
        } finally {
            r.close();
        }
    }
}
