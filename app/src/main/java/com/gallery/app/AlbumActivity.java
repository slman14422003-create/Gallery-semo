package com.gallery.app;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import eightbitlab.com.blurview.BlurTarget;

/** The photos of one album (folder, favourites, videos or screenshots). */
public class AlbumActivity extends BaseActivity implements Library.Listener {
    private MediaGrid grid;
    private int kind;
    private long bucket;
    private int seenVersion = -1;
    private SelectBar selectBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        kind = getIntent().getIntExtra("kind", Album.NORMAL);
        bucket = getIntent().getLongExtra("bucket", 0);
        String name = getIntent().getStringExtra("name");
        // edge to edge like the main screen: photos scroll under the status bar, the selection bar floats over them
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (android.os.Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        grid = new MediaGrid(this, MediaGrid.ALBUM);
        grid.setTitle(name == null ? "" : name);
        grid.setOnBack(this::finish);
        final FrameLayout root = new FrameLayout(this);
        final BlurTarget target = new BlurTarget(this);
        target.setBackgroundColor(Ui.color(this, R.color.bg));
        target.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        root.addView(target, new FrameLayout.LayoutParams(-1, -1));
        selectBar = SelectBar.attach(this, root, target, () -> grid.shareSelected(), () -> grid.deleteSelected());
        grid.setSelectionListener((selecting, n) -> selectBar.update(selecting, n));
        setContentView(root);
        View content = findViewById(android.R.id.content);
        if (content != null) content.setTag(R.id.tag_insets, Boolean.TRUE);
        root.setTag(R.id.tag_insets, Boolean.TRUE);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sb = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(sb.left, 0, sb.right, 0);
            grid.setTopInset(sb.top);
            grid.setBottomInset(sb.bottom);
            selectBar.setInset(sb.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        enableSwipeBack();
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
    protected boolean canSwipeBack() {
        return !grid.isSelecting();   // while selecting, a sideways drag from the edge selects tiles instead
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
