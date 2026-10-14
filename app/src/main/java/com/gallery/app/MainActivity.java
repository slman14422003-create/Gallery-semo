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
import androidx.core.splashscreen.SplashScreen;

import java.util.concurrent.ExecutorService;
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
    private View shown;   // the tab currently on screen
    private int seenVersion = -1;   // Library.version the grids were last filled from
    // Each tab is only rebuilt once it is actually shown; returning from the viewer with a single
    // favourite toggled no longer re-filters + re-sorts + re-groups all three tabs in one frame.
    private final int[] filledVersion = {-1, -1, -1};
    private final Runnable syncRun = this::onLibraryChanged;
    /** One shared worker for the update check (a new executor per screen leaked a thread every time). */
    private static final ExecutorService UPDATE_IO = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String[]> permLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), r -> afterPermission());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // swaps the splash theme (Theme.App.Starting) for the real AppCompat theme; without this the app crashes at launch
        SplashScreen splash = SplashScreen.installSplashScreen(this);
        splash.setOnExitAnimationListener(provider -> {
            try {
                final View sv = provider.getView();
                View ic = provider.getIconView();
                if (ic != null) {
                    ic.animate().scaleX(1.7f).scaleY(1.7f).alpha(0f).setDuration(360)
                            .setInterpolator(new android.view.animation.AccelerateInterpolator(1.2f)).start();
                }
                if (sv != null) {
                    sv.animate().alpha(0f).setStartDelay(140).setDuration(320).withEndAction(() -> provider.remove()).start();
                } else {
                    provider.remove();
                }
            } catch (Throwable t) {
                try {
                    provider.remove();
                } catch (Throwable ignored) {
                }
            }
        });
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.color(this, R.color.bg));
        stage = new FrameLayout(this);
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        root.setAlpha(0f);
        root.animate().alpha(1f).setStartDelay(160).setDuration(420).start();

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
                startActivity(new Intent(this, SettingsActivity.class));
                overridePendingTransition(R.anim.tab_in, R.anim.tab_out);
            } else {
                showTab(idx);
            }
        });

        if (nav != null) {
            nav.setOnReselect(idx -> {
                if (idx == NavBar.PHOTOS) photos.scrollToTop();
                else if (idx == NavBar.ALBUMS) albums.scrollToTop();
                else if (idx == NavBar.SEARCH) search.focusSearch2();
            });
        }

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
        // onStart() (right after this) loads the library and fills the grids once; doing it here too built every grid twice
        if (Store.autoUpdate(this)) Updater.autoCheck(this, UPDATE_IO, ui);
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
        final String crash = CrashLog.take(this);
        if (crash != null) {
            android.widget.ScrollView sv = new android.widget.ScrollView(this);
            TextView t = new TextView(this);
            t.setText(crash);
            t.setTextSize(11);
            t.setTextIsSelectable(true);
            t.setTextColor(Ui.color(this, R.color.text_secondary));
            t.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), Ui.dp(this, 8));
            sv.addView(t);
            new Dlg(this).setTitle(R.string.crash_title).setView(sv)
                    .setPositiveButton(R.string.crash_copy, (d, w) -> {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", crash));
                    })
                    .setNegativeButton(R.string.close, null).show();
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(syncRun);
        Library.unlisten(this);
        super.onDestroy();
    }

    private View viewFor(int idx) {
        return idx == NavBar.PHOTOS ? photos : idx == NavBar.ALBUMS ? albums : search;
    }

    /** Cross-fades to another tab (the old tab fades out while the new one fades in; no blank flash in between). */
    private void showTab(int idx) {
        if (idx < NavBar.PHOTOS || idx > NavBar.SEARCH) idx = NavBar.PHOTOS;
        final View next = viewFor(idx);
        final View prev = shown;
        final int old = tab;
        tab = idx;
        if (seenVersion >= 0 && filledVersion[idx] != seenVersion) fillTab(idx);
        if (nav != null) nav.render(idx);
        if (prev != next) {
            for (View v : new View[]{photos, albums, search}) {
                if (v != next && v != prev) {
                    v.animate().cancel();
                    v.setAlpha(1f);
                    v.setVisibility(View.GONE);
                }
            }
            next.animate().cancel();
            if (next.getVisibility() != View.VISIBLE) {
                next.setAlpha(0f);
                next.setVisibility(View.VISIBLE);
            }
            if (prev == null) {
                next.setAlpha(1f);
            } else {
                next.animate().alpha(1f).setStartDelay(50).setDuration(220)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f))
                        .withLayer().start();
                prev.animate().cancel();
                prev.animate().alpha(0f).setStartDelay(0).setDuration(150)
                        .withLayer()
                        .withEndAction(() -> {
                            if (prev != shown) {
                                prev.setVisibility(View.GONE);
                                prev.setAlpha(1f);
                            }
                        }).start();
            }
            shown = next;
        }
        if (old == NavBar.SEARCH && idx != NavBar.SEARCH) hideKeyboard();
        if (idx == NavBar.SEARCH) search.focusSearch2();
    }

    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager im =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null && stage != null) im.hideSoftInputFromWindow(stage.getWindowToken(), 0);
    }

    // ------------------------------------------------------------------ access

    private void refreshAccess() {
        boolean ok = Perms.hasMedia(this);
        permView.setVisibility(ok ? View.GONE : View.VISIBLE);
        if (ok) {
            if (!Library.loaded) Library.reload(this);
            ui.removeCallbacks(syncRun);
            if (seenVersion < 0) {
                onLibraryChanged();   // first fill
            } else if (seenVersion != Library.version) {
                // coming back from the viewer / editor with something changed: wait for the close animation
                // (190ms) to finish before touching the grid. The filter/sort/group/diff pass itself now runs
                // on a background thread (see MediaGrid.rebuild), so this only needs to clear the animation,
                // not also cover the rebuild time the way the old fixed 400ms guess had to.
                ui.postDelayed(syncRun, 220);
            }
        }
    }

    private void afterPermission() {
        refreshAccess();
        if (Perms.hasMedia(this)) Library.reload(this);
    }

    @Override
    public void onLibraryChanged() {
        seenVersion = Library.version;
        // Only the tab the user is actually looking at needs to be rebuilt right now; the other two
        // are marked stale and will catch up the moment showTab() brings them on screen, so a return
        // from the viewer never pays for rebuilding tabs the user can't see yet.
        fillTab(tab);
    }

    private void fillTab(int idx) {
        if (idx == NavBar.PHOTOS) photos.setSource(Library.all);
        else if (idx == NavBar.SEARCH) search.setSource(Library.all);
        else if (idx == NavBar.ALBUMS) albums.refresh();
        if (idx >= 0 && idx < filledVersion.length) filledVersion[idx] = seenVersion;
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
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
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
