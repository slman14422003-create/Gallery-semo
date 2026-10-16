package com.gallery.app;

import android.net.Uri;

/** One photo or video from the device library. */
public final class Media {
    public long id;
    public boolean video;
    public Uri uri;
    public String name = "";
    public String mime = "";
    public String bucket = "";
    public String path = "";
    public long bucketId;
    public long taken;      // ms, 0 when unknown
    public long added;      // seconds
    public long modified;   // seconds
    public long size;
    public long duration;   // ms (video)
    public int w, h;
    public int orientation; // degrees (images)

    public String key() {
        return (video ? "v" : "i") + id;
    }

    public boolean isGif() {
        return "image/gif".equals(mime) || name.toLowerCase(java.util.Locale.ROOT).endsWith(".gif");
    }

    /** Best date for sorting and grouping, in ms. */
    public long date() {
        if (taken > 0) return taken;
        if (modified > 0) return modified * 1000L;
        return added * 1000L;
    }
}
