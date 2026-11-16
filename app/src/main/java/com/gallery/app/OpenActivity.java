package com.gallery.app;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Entry point for "Open with" from other apps. Reads only the data Uri and its type (nothing else from the
 * incoming intent) and forwards to the viewer or the player.
 */
public class OpenActivity extends BaseActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        Uri uri = in == null ? null : in.getData();
        String type = in == null ? null : in.getType();
        if (uri == null || !allowed(uri)) {
            finish();
            return;
        }
        boolean video = (type != null && type.startsWith("video/")) || uri.toString().toLowerCase().matches(".*\\.(mp4|mkv|avi|mov|webm|3gp|flv|wmv|ts|m4v)$");
        Media found = null;
        List<Media> all = Library.all;
        for (Media m : all) {
            if (m.uri.equals(uri)) {
                found = m;
                break;
            }
        }
        List<Media> list;
        int index = 0;
        if (found != null) {
            list = new ArrayList<>();
            for (Media m : all) if (m.bucketId == found.bucketId) list.add(m);
            index = Math.max(0, list.indexOf(found));
        } else {
            Media m = new Media();
            m.uri = uri;
            m.video = video;
            m.mime = type == null ? "" : type;
            m.name = displayName(uri);
            list = Collections.singletonList(m);
        }
        Library.setSession(list, index);
        Intent out = new Intent(this, list.get(index).video ? PlayerActivity.class : ViewerActivity.class);
        startActivity(out);
        finish();
    }

    /** Other apps can send anything here: accept content:// and only public-storage file:// (never this app's private data or other apps' folders). */
    private boolean allowed(Uri u) {
        String sc = u.getScheme();
        if ("content".equals(sc)) return true;
        if (!"file".equals(sc) || u.getPath() == null) return false;
        try {
            String p = new java.io.File(u.getPath()).getCanonicalPath();
            String root = android.os.Environment.getExternalStorageDirectory().getCanonicalPath() + "/";
            return p.startsWith(root) && !p.startsWith(root + "Android/data/") && !p.startsWith(root + "Android/obb/");
        } catch (Exception e) {
            return false;
        }
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (Exception ignored) {
        }
        String p = u.getLastPathSegment();
        return p == null ? "media" : p;
    }
}
