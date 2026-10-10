package com.gallery.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Small shared actions: share, open the viewer, open the editors. */
final class Actions {
    private Actions() {
    }

    static void share(Activity a, List<Media> items) {
        if (items == null || items.isEmpty()) return;
        try {
            Intent i;
            if (items.size() == 1) {
                Media m = items.get(0);
                i = new Intent(Intent.ACTION_SEND);
                i.setType(mimeOf(m));
                i.putExtra(Intent.EXTRA_STREAM, m.uri);
                i.setClipData(ClipData.newRawUri("", m.uri));
            } else {
                i = new Intent(Intent.ACTION_SEND_MULTIPLE);
                boolean vid = false, img = false;
                ArrayList<Uri> uris = new ArrayList<>();
                ClipData clip = null;
                for (Media m : items) {
                    uris.add(m.uri);
                    if (m.video) vid = true;
                    else img = true;
                    if (clip == null) clip = ClipData.newRawUri("", m.uri);
                    else clip.addItem(new ClipData.Item(m.uri));
                }
                i.setType(vid && img ? "*/*" : vid ? "video/*" : "image/*");
                i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                i.setClipData(clip);
            }
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(Intent.createChooser(i, a.getString(R.string.share)));
        } catch (Exception e) {
            Toast.makeText(a, R.string.error, Toast.LENGTH_SHORT).show();
        }
    }

    static String mimeOf(Media m) {
        if (m.mime != null && !m.mime.isEmpty()) return m.mime;
        return m.video ? "video/*" : "image/*";
    }

    static void openViewer(Activity a, List<Media> list, int index) {
        Library.setSession(list, index);
        a.startActivity(new Intent(a, ViewerActivity.class));
        a.overridePendingTransition(R.anim.viewer_open_enter, R.anim.hold);
    }

    static void play(Activity a, List<Media> list, int index) {
        Library.setSession(list, index);
        a.startActivity(new Intent(a, PlayerActivity.class));
        a.overridePendingTransition(R.anim.viewer_open_enter, R.anim.hold);
    }

    static void edit(Activity a, Media m) {
        Library.editTarget = m;
        a.startActivity(new Intent(a, m.video ? VideoEditorActivity.class : ImageEditorActivity.class));
    }
}
