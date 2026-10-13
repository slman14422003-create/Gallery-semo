package com.gallery.app;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Answers "choose a photo / video" requests (GET_CONTENT, OPEN_DOCUMENT, PICK, PICK_IMAGES) from other apps with the
 * gallery's own grid over every photo and video on the device, instead of the system picker.
 */
public class PickerActivity extends BaseActivity implements Library.Listener {
    private static final int COLS = 4;

    private final List<Media> shown = new ArrayList<>();
    private final Set<String> sel = new LinkedHashSet<>();
    private final List<Media> picked = new ArrayList<>();
    private final List<TextView> chips = new ArrayList<>();
    private List<Album> albums = new ArrayList<>();
    private int albumIdx = -1;
    private int typeFilter = 0;   // 0 all, 1 photos, 2 videos
    private boolean multiple;
    private int max = Integer.MAX_VALUE;
    private boolean allowImages = true, allowVideos = true;
    private String[] types = new String[]{"*/*"};

    private RecyclerView rv;
    private TextView title, doneBtn, emptyView;
    private LinearLayout albumRow, filterRow;
    private final Adapter ad = new Adapter();

    private final ActivityResultLauncher<String[]> permLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), r -> {
                if (Perms.hasMedia(this)) Library.reload(this);
                refresh();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        readRequest(getIntent());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.color(this, R.color.bg));
        setContentView(root);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 6));
        ImageButton close = new ImageButton(this, null, 0, R.style.IconButton);
        close.setImageResource(R.drawable.ic_cancel);
        close.setContentDescription(getString(R.string.cancel));
        close.setOnClickListener(v -> finish());
        head.addView(close, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        title = new TextView(this);
        title.setTextColor(Ui.color(this, R.color.text_primary));
        title.setTextSize(17);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        title.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        doneBtn = new TextView(this);
        doneBtn.setText(R.string.done);
        doneBtn.setTextColor(Ui.color(this, R.color.on_accent));
        doneBtn.setTextSize(14.5f);
        doneBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        doneBtn.setBackgroundResource(R.drawable.btn_primary);
        doneBtn.setGravity(Gravity.CENTER);
        doneBtn.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), 0);
        doneBtn.setOnClickListener(v -> finishPicking());
        doneBtn.setVisibility(multiple ? View.VISIBLE : View.GONE);
        head.addView(doneBtn, new LinearLayout.LayoutParams(-2, Ui.dp(this, 42)));
        root.addView(head);

        filterRow = new LinearLayout(this);
        filterRow.setPaddingRelative(Ui.dp(this, 16), Ui.dp(this, 2), Ui.dp(this, 16), Ui.dp(this, 4));
        if (allowImages && allowVideos) {
            int[] names = {R.string.f_all, R.string.f_photos, R.string.f_videos};
            final List<TextView> fc = new ArrayList<>();
            for (int i = 0; i < names.length; i++) {
                final int k = i;
                TextView c = Ui.chip(this, getString(names[i]), i == 0);
                c.setOnClickListener(v -> {
                    typeFilter = k;
                    for (int j = 0; j < fc.size(); j++) Ui.setChip(this, fc.get(j), j == k);
                    refresh();
                });
                fc.add(c);
                filterRow.addView(c);
            }
            root.addView(filterRow);
        }

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        albumRow = new LinearLayout(this);
        albumRow.setPaddingRelative(Ui.dp(this, 16), Ui.dp(this, 2), Ui.dp(this, 16), Ui.dp(this, 8));
        hs.addView(albumRow);
        root.addView(hs, new LinearLayout.LayoutParams(-1, -2));

        final android.widget.FrameLayout bodyFrame = new android.widget.FrameLayout(this);
        rv = new RecyclerView(this);
        rv.setLayoutManager(new GridLayoutManager(this, COLS));
        rv.setHasFixedSize(true);
        rv.setAdapter(ad);
        rv.setClipToPadding(false);
        bodyFrame.addView(rv, new android.widget.FrameLayout.LayoutParams(-1, -1));
        emptyView = new TextView(this);
        emptyView.setTextColor(Ui.color(this, R.color.text_secondary));
        emptyView.setTextSize(15);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setText(R.string.pick_empty);
        emptyView.setVisibility(View.GONE);
        bodyFrame.addView(emptyView, new android.widget.FrameLayout.LayoutParams(-1, -1));
        root.addView(bodyFrame, new LinearLayout.LayoutParams(-1, 0, 1f));

        Library.watch(this);
        if (!Perms.hasMedia(this)) {
            permLauncher.launch(Perms.mediaPerms());
        } else if (!Library.loaded) {
            Library.reload(this);
        }
        updateTitle();
        refresh();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Library.listen(this);
        refresh();
    }

    @Override
    protected void onDestroy() {
        Library.unlisten(this);
        super.onDestroy();
    }

    @Override
    public void onLibraryChanged() {
        refresh();
    }

    // ------------------------------------------------------------------ request

    private void readRequest(Intent in) {
        if (in == null) return;
        String[] extra = in.getStringArrayExtra(Intent.EXTRA_MIME_TYPES);
        if (extra != null && extra.length > 0) types = extra;
        else if (in.getType() != null) types = new String[]{in.getType()};
        allowImages = false;
        allowVideos = false;
        for (String t : types) {
            if (t == null) continue;
            if (t.equals("*/*") || t.startsWith("image/")) allowImages = true;
            if (t.equals("*/*") || t.startsWith("video/")) allowVideos = true;
        }
        if (!allowImages && !allowVideos) {
            allowImages = true;
            allowVideos = true;
            types = new String[]{"*/*"};
        }
        multiple = in.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
        if ("android.provider.action.PICK_IMAGES".equals(in.getAction())) {
            int m = in.getIntExtra("android.provider.extra.PICK_IMAGES_MAX", 1);
            if (m > 1) {
                multiple = true;
                max = m;
            }
        }
    }

    private boolean accepts(Media m) {
        if (m.video ? !allowVideos : !allowImages) return false;
        String mime = m.mime == null || m.mime.isEmpty() ? (m.video ? "video/mp4" : "image/jpeg") : m.mime;
        for (String t : types) {
            if (t == null) continue;
            if (t.equals("*/*")) return true;
            if (t.endsWith("/*")) {
                if (mime.startsWith(t.substring(0, t.length() - 1))) return true;
            } else if (t.equalsIgnoreCase(mime)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ list

    private void refresh() {
        if (rv == null) return;
        albums = Library.albums(this);
        buildAlbumChips();
        List<Media> src;
        if (albumIdx >= 0 && albumIdx < albums.size()) {
            Album a = albums.get(albumIdx);
            src = Library.itemsFor(this, a.kind, a.bucketId);
        } else {
            albumIdx = -1;
            src = Library.all;
        }
        shown.clear();
        for (Media m : src) {
            if (typeFilter == 1 && m.video) continue;
            if (typeFilter == 2 && !m.video) continue;
            if (accepts(m)) shown.add(m);
        }
        ad.notifyDataSetChanged();
        emptyView.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void buildAlbumChips() {
        albumRow.removeAllViews();
        chips.clear();
        addAlbumChip(getString(R.string.f_all), -1);
        for (int i = 0; i < albums.size(); i++) addAlbumChip(albums.get(i).name, i);
    }

    private void addAlbumChip(String name, final int idx) {
        TextView c = Ui.chip(this, name, idx == albumIdx);
        c.setOnClickListener(v -> {
            albumIdx = idx;
            refresh();
            rv.scrollToPosition(0);
        });
        chips.add(c);
        albumRow.addView(c);
    }

    private void updateTitle() {
        title.setText(multiple && !sel.isEmpty() ? getString(R.string.pick_selected, sel.size()) : getString(R.string.pick_title));
    }

    // ------------------------------------------------------------------ result

    private void toggle(Media m) {
        String k = m.key();
        if (sel.contains(k)) {
            sel.remove(k);
            for (int i = picked.size() - 1; i >= 0; i--) if (picked.get(i).key().equals(k)) picked.remove(i);
        } else {
            if (sel.size() >= max) return;
            sel.add(k);
            picked.add(m);
        }
        updateTitle();
    }

    private void finishPicking() {
        if (picked.isEmpty()) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        Intent out = new Intent();
        Uri first = picked.get(0).uri;
        ClipData clip = ClipData.newRawUri("media", first);
        for (int i = 1; i < picked.size(); i++) clip.addItem(new ClipData.Item(picked.get(i).uri));
        out.setData(first);
        out.setClipData(clip);
        out.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        setResult(RESULT_OK, out);
        finish();
    }

    private void pickOne(Media m) {
        picked.clear();
        picked.add(m);
        finishPicking();
    }

    // ------------------------------------------------------------------ adapter

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            Tile t = new Tile(parent.getContext());
            int gap = Ui.dp(parent.getContext(), 1);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(-1, -2);
            lp.setMargins(gap, gap, gap, gap);
            t.setLayoutParams(lp);
            return new RecyclerView.ViewHolder(t) { };
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder h, int pos) {
            final Media m = shown.get(pos);
            final Tile t = (Tile) h.itemView;
            int px = Math.max(120, getResources().getDisplayMetrics().widthPixels / COLS);
            t.bind(m, px, false);
            t.setSelection(multiple, sel.contains(m.key()), false);
            t.setOnClickListener(v -> {
                if (!multiple) {
                    pickOne(m);
                    return;
                }
                toggle(m);
                t.setSelection(true, sel.contains(m.key()), true);
            });
        }

        @Override
        public void onViewRecycled(RecyclerView.ViewHolder h) {
            Thumbs.clear(((Tile) h.itemView).thumb);
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }
    }
}
