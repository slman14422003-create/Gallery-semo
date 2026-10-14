package com.gallery.app;

import android.os.Bundle;

import androidx.activity.OnBackPressedCallback;

/** The photos of one album (folder, favourites, videos or screenshots). */
public class AlbumActivity extends BaseActivity implements Library.Listener {
    private MediaGrid grid;
    private int kind;
    private long bucket;
    private int seenVersion = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        kind = getIntent().getIntExtra("kind", Album.NORMAL);
        bucket = getIntent().getLongExtra("bucket", 0);
        String name = getIntent().getStringExtra("name");
        grid = new MediaGrid(this, MediaGrid.ALBUM);
        grid.setTitle(name == null ? "" : name);
        grid.setOnBack(this::finish);
        setContentView(grid);
        Motion.install(this, R.anim.act_open_enter, R.anim.act_open_exit, R.anim.act_close_enter, R.anim.act_close_exit);
        final OnBackPressedCallback selBack = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                grid.handleBack();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, selBack);
        grid.setOnSelectChange(() -> selBack.setEnabled(grid.isSelecting()));
        // the grid is filled in onStart(), which runs right after this
    }

    @Override
    protected void onStart() {
        super.onStart();
        Library.listen(this);
        // returning from the viewer with nothing changed: keep the grid as it is (rebuilding it caused the lag)
        if (seenVersion != Library.version) onLibraryChanged();
    }

    @Override
    protected void onStop() {
        Library.unlisten(this);
        super.onStop();
    }

    @Override
    public void onLibraryChanged() {
        seenVersion = Library.version;
        grid.setSource(Library.itemsFor(this, kind, bucket));
    }
}
