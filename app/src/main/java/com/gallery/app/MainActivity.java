package com.gallery.app;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import java.util.concurrent.Executors;

/** Photos, Albums and Search live here; Settings is its own screen. */
public class MainActivity extends BaseActivity implements Library.Listener {
    private MediaGrid photos, search;
    private AlbumsView albums;
    private FrameLayout stage;
    private View permView;
    private NavBar nav;
    private int tab = NavBar.PHOTOS;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<String[]> permLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), r -> afterPermission());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.color(this, R.color.bg));
        stage = new FrameLayout(this);
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        photos = new MediaGrid(this, MediaGrid.PHOTOS);
        albums = new AlbumsView(this);
        search = new MediaGrid(this, MediaGrid.SEARCH);
        stage.addView(photos, new FrameLayout.LayoutParams(-1, -1));
        stage.addView(albums, new FrameLayout.LayoutParams(-1, -1));
        stage.addView(search, new FrameLayout.LayoutParams(-1, -1));
        permView = buildPermView();
        stage.addView(permView, new FrameLayout.LayoutParams(-1, -1));

        nav = NavBar.attach(this, NavBar.PHOTOS, idx -> {
            if (idx == NavBar.SETTINGS) {
                startActivity(new Intent(this, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION));
                overridePendingTransition(0, 0);
            } else {
                showTab(idx);
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                MediaGrid g = tab == NavBar.PHOTOS ? photos : tab == NavBar.SEARCH ? search : null;
                if (g != null && g.handleBack()) return;
                if (tab != NavBar.PHOTOS) {
                    showTab(NavBar.PHOTOS);
                    return;
                }
                finish();
            }
        });

        tab = getIntent().getIntExtra("tab", NavBar.PHOTOS);
        showTab(tab);
        Library.watch(this);
        Library.listen(this);
        refreshAccess();
        if (Store.autoUpdate(this)) Updater.autoCheck(this, Executors.newSingleThreadExecutor(), ui);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        int t = intent.getIntExtra("tab", tab);
        if (t >= 0 && t <= NavBar.SEARCH) showTab(t);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Library.listen(this);
        refreshAccess();
    }

    @Override
    protected void onDestroy() {
        Library.unlisten(this);
        super.onDestroy();
    }

    private void showTab(int idx) {
        tab = idx;
        photos.setVisibility(idx == NavBar.PHOTOS ? View.VISIBLE : View.GONE);
        albums.setVisibility(idx == NavBar.ALBUMS ? View.VISIBLE : View.GONE);
        search.setVisibility(idx == NavBar.SEARCH ? View.VISIBLE : View.GONE);
        View v = idx == NavBar.PHOTOS ? photos : idx == NavBar.ALBUMS ? albums : search;
        v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(180).start();
        if (nav != null) nav.render(idx);
        if (idx == NavBar.SEARCH) search.focusSearch2();
    }

    // ------------------------------------------------------------------ access

    private void refreshAccess() {
        boolean ok = Perms.hasMedia(this);
        permView.setVisibility(ok ? View.GONE : View.VISIBLE);
        if (ok) {
            if (!Library.loaded) Library.reload(this);
            onLibraryChanged();
        }
    }

    private void afterPermission() {
        refreshAccess();
        if (Perms.hasMedia(this)) Library.reload(this);
    }

    @Override
    public void onLibraryChanged() {
        photos.setSource(Library.all);
        search.setSource(Library.all);
        albums.refresh();
    }

    private View buildPermView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Ui.color(this, R.color.bg));
        box.setPadding(Ui.dp(this, 32), Ui.dp(this, 24), Ui.dp(this, 32), Ui.dp(this, 24));
        box.setClickable(true);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_image);
        int col = Ui.color(this, R.color.accent_text);
        icon.setImageTintList(ColorStateList.valueOf(col));
        int p = Ui.dp(this, 22);
        icon.setPadding(p, p, p, p);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor((col & 0x00FFFFFF) | 0x26000000);
        icon.setBackground(g);
        box.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 104), Ui.dp(this, 104)));
        TextView t = new TextView(this);
        t.setText(R.string.access_title);
        t.setTextColor(Ui.color(this, R.color.text_primary));
        t.setTextSize(22);
        t.setTypeface(android.graphics.Typeface.SERIF);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = Ui.dp(this, 22);
        box.addView(t, tp);
        TextView m = new TextView(this);
        m.setText(R.string.access_body);
        m.setTextColor(Ui.color(this, R.color.text_secondary));
        m.setTextSize(15);
        m.setLineSpacing(0, 1.2f);
        m.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2);
        mp.topMargin = Ui.dp(this, 10);
        box.addView(m, mp);
        Button b = Ui.button(this, R.string.access_allow, true);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = Ui.dp(this, 26);
        b.setOnClickListener(v -> requestAccess());
        box.addView(b, bp);
        return box;
    }

    private void requestAccess() {
        boolean asked = Store.flag(this, "media_asked");
        boolean canAsk = false;
        for (String p : Perms.mediaPerms()) {
            if (androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(this, p)) canAsk = true;
        }
        if (asked && !canAsk) {
            Perms.openAppSettings(this);
            return;
        }
        Store.setFlag(this, "media_asked", true);
        permLauncher.launch(Perms.mediaPerms());
    }
}
