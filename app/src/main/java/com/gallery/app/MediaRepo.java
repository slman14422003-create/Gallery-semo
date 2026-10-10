package com.gallery.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Reads every photo and video from MediaStore. */
final class MediaRepo {
    private MediaRepo() {
    }

    static List<Media> load(Context c) {
        ArrayList<Media> out = new ArrayList<>();
        ContentResolver cr = c.getContentResolver();
        query(cr, out, false);
        query(cr, out, true);
        Collections.sort(out, (a, b) -> Long.compare(b.date(), a.date()));
        return out;
    }

    private static void query(ContentResolver cr, List<Media> out, boolean video) {
        Uri base = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] proj;
        if (video) {
            proj = new String[]{MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.MIME_TYPE, MediaStore.Video.Media.DATE_TAKEN,
                    MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.DATE_MODIFIED,
                    MediaStore.Video.Media.SIZE, MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT,
                    MediaStore.Video.Media.BUCKET_ID, MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION, MediaStore.Video.Media.DATA};
        } else {
            proj = new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.DATE_MODIFIED,
                    MediaStore.Images.Media.SIZE, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT,
                    MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Images.Media.ORIENTATION, MediaStore.Images.Media.DATA};
        }
        try (Cursor q = cr.query(base, proj, null, null, null)) {
            if (q == null) return;
            while (q.moveToNext()) {
                Media m = new Media();
                m.video = video;
                m.id = q.getLong(0);
                m.uri = ContentUris.withAppendedId(base, m.id);
                m.name = str(q, 1);
                m.mime = str(q, 2);
                m.taken = q.isNull(3) ? 0 : q.getLong(3);
                m.added = q.isNull(4) ? 0 : q.getLong(4);
                m.modified = q.isNull(5) ? 0 : q.getLong(5);
                m.size = q.isNull(6) ? 0 : q.getLong(6);
                m.w = q.isNull(7) ? 0 : q.getInt(7);
                m.h = q.isNull(8) ? 0 : q.getInt(8);
                m.bucketId = q.isNull(9) ? 0 : q.getLong(9);
                m.bucket = str(q, 10);
                if (video) m.duration = q.isNull(11) ? 0 : q.getLong(11);
                else m.orientation = q.isNull(11) ? 0 : q.getInt(11);
                m.path = str(q, 12);
                if (!video && (m.orientation == 90 || m.orientation == 270)) {
                    int t = m.w;
                    m.w = m.h;
                    m.h = t;
                }
                if (m.bucket.isEmpty()) m.bucket = "?";
                out.add(m);
            }
        } catch (Exception ignored) {
            // permission revoked mid-way or provider hiccup: show what we have
        }
    }

    private static String str(Cursor q, int i) {
        String s = q.isNull(i) ? "" : q.getString(i);
        return s == null ? "" : s;
    }
}
