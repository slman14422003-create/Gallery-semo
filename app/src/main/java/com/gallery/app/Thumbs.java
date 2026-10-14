package com.gallery.app;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.BitmapTransitionOptions;
import com.bumptech.glide.signature.ObjectKey;

/** Grid / album thumbnails through Glide (disk + memory cached, video frames included). */
final class Thumbs {
    private Thumbs() {
    }

    static void load(ImageView iv, Media m, int px) {
        Context c = iv.getContext();
        Glide.with(iv)
                .asBitmap()
                .load(m.uri)
                .override(px, px)
                .centerCrop()
                .signature(new ObjectKey(m.modified + ":" + m.size))
                .placeholder(new ColorDrawable(Ui.color(c, R.color.surface_high)))
                .transition(BitmapTransitionOptions.withCrossFade(90))
                .into(iv);
    }

    static void clear(ImageView iv) {
        try {
            Glide.with(iv).clear(iv);
        } catch (Exception ignored) {
        }
    }
}
