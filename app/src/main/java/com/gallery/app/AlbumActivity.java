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
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!grid.handleBack()) finish();
            }
        });
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
