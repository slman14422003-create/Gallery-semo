package com.gallery.app;

import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Rational;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The video player: ExoPlayer first, libVLC automatically when a file is not supported. Gestures:
 * double-tap to skip, swipe for seek / brightness / volume, pinch to zoom, hold for 2x speed.
 */
public class PlayerActivity extends BaseActivity {
    private static final float[] SPEEDS = {0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f, 4f};
    private static final int[] ASPECT_NAMES = {R.string.asp_fit, R.string.asp_stretch, R.string.asp_crop, R.string.asp_169, R.string.asp_43};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Media> playlist = new ArrayList<>();
    private int idx;
    private PlayerEngine engine;
    private String engineKind = "exo";
    private FrameLayout root, stage, controls;
    private LinearLayout topBar, centerBar, bottomBar;
    private TextView titleTv, curTv, durTv, engineTv, hud, speedBtn;
    // brightness / volume indicator (icon + gradient bar + percent), styled like the rest of the app
    private LinearLayout levelHud;
    private ImageView levelIcon;
    private View levelFill;
    private TextView levelText;
    private SeekBar seek;
    private ImageButton playBtn, lockFab;
    private SpinnerView spinner;
    private OnBackPressedCallback lockBack;
    private boolean controlsOn = true, locked = false, dragging = false, playing = false, ended = false;
    // engine recovery: every config (exo, vlc-hw, vlc-sw) is tried once per failure before giving up
    private volatile int engineGen = 0;
    private boolean vlcHw = true, manualEngine = false, errorShown = false, skipTried = false;
    private final java.util.Set<String> tried = new java.util.HashSet<>();
    private long lastPos = 0, recoveredAt = -1;
    private final List<String> errors = new ArrayList<>();
    // files from other apps (and any file that cannot be read randomly) are copied to the cache so seeking always works
    private Uri cachedUri;
    private boolean cacheTried;
    private int cacheToken;
    private float speed = 1f, zoom = 1f;
    private int aspect = 0;
    private boolean loop = false;
    private int orient = 0;   // 0 auto, 1 landscape, 2 portrait
    private int vw, vh;
    private long lastSave = 0;
    private float brightness = -1f;
    private AudioManager audio;
    private ActivityResultLauncher<String[]> subPicker;

    private final Runnable hideRun = () -> {
        if (playing && !dragging) setControls(false);
    };
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            ui.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Motion.install(this, R.anim.viewer_open_enter, R.anim.hold, R.anim.hold_close, R.anim.viewer_close_exit);
        playlist.clear();
        for (Media m : Library.session) if (m.video) playlist.add(m);
        Media start = Library.session.isEmpty() ? null : Library.session.get(Math.min(Library.sessionIndex, Library.session.size() - 1));
        if (playlist.isEmpty()) {
            finish();
            return;
        }
        idx = Math.max(0, playlist.indexOf(start));
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        build();
        subPicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onSubtitlePicked);
        lockBack = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                flashLock();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, lockBack);
        startEngine(preferredKind(), Store.bool(this, "hw", true));
        openCurrent(true);
        ui.post(tick);
    }

    @Override
    protected void onPostCreate(Bundle b) {
        super.onPostCreate(b);
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        c.setAppearanceLightStatusBars(false);
        c.setAppearanceLightNavigationBars(false);
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        c.hide(WindowInsetsCompat.Type.systemBars());
    }

    // ------------------------------------------------------------------ UI

    /** Round translucent icon button. */
    private ImageButton glass(int icon, int desc, int color) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        b.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        b.setBackground(g);
        b.setContentDescription(getString(desc));
        Ui.press(this, b);
        return b;
    }

    private ImageButton toolIcon(int icon, int desc, View.OnClickListener click) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setBackgroundResource(R.drawable.bg_tile_round);
        b.setContentDescription(getString(desc));
        Ui.press(this, b);
        b.setOnClickListener(click);
        return b;
    }

    /** Icon with no background (sits inside a capsule). */
    private ImageButton toolIconFlat(int icon, int desc, View.OnClickListener click) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setBackground(null);
        b.setContentDescription(getString(desc));
        Ui.press(this, b);
        b.setOnClickListener(click);
        return b;
    }

    private LinearLayout.LayoutParams toolLp() {
        return new LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1f);
    }

    private void build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        View content = findViewById(android.R.id.content);
        if (content != null) content.setTag(R.id.tag_insets, Boolean.TRUE);

        stage = new FrameLayout(this);
        root.addView(stage, new FrameLayout.LayoutParams(-1, -1));

        View gestures = new View(this);
        root.addView(gestures, new FrameLayout.LayoutParams(-1, -1));
        setupGestures(gestures);

        controls = new FrameLayout(this);
        root.addView(controls, new FrameLayout.LayoutParams(-1, -1));

        // top (iOS 26 Photos video): glass back circle, centred title pill, "..." circle with the menu
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = glass(R.drawable.ic_back, R.string.back, 0x66000000);
        back.setOnClickListener(v -> finish());
        topBar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)));
        titleTv = new TextView(this);
        titleTv.setTextColor(Color.WHITE);
        titleTv.setTextSize(15);
        titleTv.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        titleTv.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), 0);
        titleTv.setGravity(Gravity.CENTER);
        GradientDrawable tg = new GradientDrawable();
        tg.setCornerRadius(Ui.dp(this, 23));
        tg.setColor(0x66000000);
        tg.setStroke(Math.max(1, Ui.dp(this, 1) / 2), 0x33FFFFFF);
        titleTv.setBackground(tg);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1f);
        tlp.setMargins(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        topBar.addView(titleTv, tlp);
        engineTv = new TextView(this);                  // kept for engine.name(); the engine switch now lives in the "..." menu
        ImageButton moreTop = glass(R.drawable.ic_more, R.string.t_more, 0x66000000);
        moreTop.setOnClickListener(v -> showMore(v));
        topBar.addView(moreTop, new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)));
        controls.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // centre: previous / back 10 / forward 10 / next (play lives in the bottom capsule)
        centerBar = new LinearLayout(this);
        centerBar.setOrientation(LinearLayout.HORIZONTAL);
        centerBar.setGravity(Gravity.CENTER);
        centerBar.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        ImageButton prev = glass(R.drawable.ic_skip_prev, R.string.prev, 0x55000000);
        ImageButton rew = glass(R.drawable.ic_rewind, R.string.back10, 0x55000000);
        ImageButton fwd = glass(R.drawable.ic_forward, R.string.fwd10, 0x55000000);
        ImageButton next = glass(R.drawable.ic_skip_next, R.string.next, 0x55000000);
        prev.setOnClickListener(v -> step(-1));
        next.setOnClickListener(v -> step(1));
        rew.setOnClickListener(v -> skip(-10_000));
        fwd.setOnClickListener(v -> skip(10_000));
        int[] sizes = {46, 56, 56, 46};
        ImageButton[] tb = {prev, rew, fwd, next};
        for (int i = 0; i < tb.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, sizes[i]), Ui.dp(this, sizes[i]));
            lp.setMargins(Ui.dp(this, 9), 0, Ui.dp(this, 9), 0);
            centerBar.addView(tb[i], lp);
        }
        controls.addView(centerBar, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        // bottom: a capsule (play, seek bar, subtitles, audio) with the tool row under it, like the iOS video controls
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        LinearLayout cap = new LinearLayout(this);
        cap.setGravity(Gravity.CENTER_VERTICAL);
        cap.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        cap.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        GradientDrawable cg = new GradientDrawable();
        cg.setCornerRadius(Ui.dp(this, 32));
        cg.setColor(0x88000000);
        cg.setStroke(Math.max(1, Ui.dp(this, 1) / 2), 0x33FFFFFF);
        cap.setBackground(cg);
        playBtn = new ImageButton(this);
        playBtn.setImageResource(R.drawable.ic_play_fill);
        playBtn.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        playBtn.setScaleType(ImageView.ScaleType.CENTER);
        playBtn.setBackground(null);
        playBtn.setContentDescription(getString(R.string.play));
        Ui.press(this, playBtn);
        playBtn.setOnClickListener(v -> togglePlay());
        curTv = timeText();
        durTv = timeText();
        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setProgressTintList(ColorStateList.valueOf(Color.WHITE));
        seek.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(0x55FFFFFF));
        seek.setSecondaryProgressTintList(ColorStateList.valueOf(0x44FFFFFF));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && engine != null) curTv.setText(Fmt.dur(engine.duration() * p / 1000));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                dragging = true;
                ui.removeCallbacks(hideRun);
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                if (engine != null) engine.seekTo(engine.duration() * s.getProgress() / 1000);
                scheduleHide();
            }
        });
        cap.addView(playBtn, new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)));
        cap.addView(curTv);
        cap.addView(seek, new LinearLayout.LayoutParams(0, Ui.dp(this, 36), 1f));
        cap.addView(durTv);
        cap.addView(toolIconFlat(R.drawable.ic_subtitle, R.string.t_subs, this::pickSubs), new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 46)));
        cap.addView(toolIconFlat(R.drawable.ic_music, R.string.t_audio, this::pickAudio), new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 46)));
        bottomBar.addView(cap, new LinearLayout.LayoutParams(-1, Ui.dp(this, 58)));

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        ImageButton lockBtn = glass(R.drawable.ic_lock, R.string.t_lock, 0x88000000);
        lockBtn.setOnClickListener(v -> setLocked(true));
        tools.addView(lockBtn, new LinearLayout.LayoutParams(Ui.dp(this, 54), Ui.dp(this, 54)));
        tools.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        LinearLayout mid = new LinearLayout(this);
        mid.setGravity(Gravity.CENTER_VERTICAL);
        mid.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        GradientDrawable mg = new GradientDrawable();
        mg.setCornerRadius(Ui.dp(this, 30));
        mg.setColor(0x88000000);
        mg.setStroke(Math.max(1, Ui.dp(this, 1) / 2), 0x33FFFFFF);
        mid.setBackground(mg);
        speedBtn = new TextView(this);
        speedBtn.setText("1×");
        speedBtn.setTextColor(Color.WHITE);
        speedBtn.setTextSize(15);
        speedBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        speedBtn.setGravity(Gravity.CENTER);
        speedBtn.setContentDescription(getString(R.string.t_speed));
        Ui.press(this, speedBtn);
        speedBtn.setOnClickListener(this::pickSpeed);
        mid.addView(speedBtn, new LinearLayout.LayoutParams(Ui.dp(this, 54), Ui.dp(this, 54)));
        mid.addView(toolIconFlat(R.drawable.ic_aspect, R.string.t_aspect, v -> cycleAspect()), new LinearLayout.LayoutParams(Ui.dp(this, 54), Ui.dp(this, 54)));
        mid.addView(toolIconFlat(R.drawable.ic_rotate, R.string.t_rotate, v -> cycleOrientation()), new LinearLayout.LayoutParams(Ui.dp(this, 54), Ui.dp(this, 54)));
        tools.addView(mid, new LinearLayout.LayoutParams(-2, Ui.dp(this, 54)));
        tools.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        ImageButton shareBtn = glass(R.drawable.ic_share, R.string.share, 0x88000000);
        shareBtn.setOnClickListener(v -> {
            if (!playlist.isEmpty()) Actions.share(this, java.util.Collections.singletonList(playlist.get(idx)));
        });
        tools.addView(shareBtn, new LinearLayout.LayoutParams(Ui.dp(this, 54), Ui.dp(this, 54)));
        LinearLayout.LayoutParams toolsLp = new LinearLayout.LayoutParams(-1, -2);
        toolsLp.topMargin = Ui.dp(this, 10);
        bottomBar.addView(tools, toolsLp);
        controls.addView(bottomBar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        hud = new TextView(this);
        hud.setTextColor(Color.WHITE);
        hud.setTextSize(17);
        hud.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        hud.setGravity(Gravity.CENTER);
        GradientDrawable hg = new GradientDrawable();
        hg.setCornerRadius(Ui.dp(this, 22));
        hg.setColor(0xB3000000);
        hud.setBackground(hg);
        hud.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 12));
        hud.setVisibility(View.GONE);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hp.topMargin = Ui.dp(this, 120);
        root.addView(hud, hp);
        buildLevelHud(root);

        spinner = new SpinnerView(this);
        root.addView(spinner, new FrameLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52), Gravity.CENTER));
        spinner.setVisibility(View.GONE);

        lockFab = glass(R.drawable.ic_lock_fill, R.string.t_unlock, 0x99000000);
        GradientDrawable lg = new GradientDrawable();
        lg.setShape(GradientDrawable.OVAL);
        lg.setColor(0x99000000);
        lockFab.setBackground(lg);
        lockFab.setVisibility(View.GONE);
        lockFab.setOnClickListener(v -> setLocked(false));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52), Gravity.CENTER_VERTICAL | Gravity.START);
        lp.setMarginStart(Ui.dp(this, 24));
        root.addView(lockFab, lp);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets b = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            topBar.setPadding(Ui.dp(this, 12) + b.left, b.top + Ui.dp(this, 10), Ui.dp(this, 12) + b.right, 0);
            FrameLayout.LayoutParams bp = (FrameLayout.LayoutParams) bottomBar.getLayoutParams();
            bp.setMargins(Ui.dp(this, 12) + b.left, 0, Ui.dp(this, 12) + b.right, Ui.dp(this, 12) + b.bottom);
            bottomBar.setLayoutParams(bp);
            bottomBar.setPadding(0, 0, 0, 0);
            return insets;
        });
    }

    private TextView timeText() {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(12.5f);
        t.setText("0:00");
        t.setMinWidth(Ui.dp(this, 46));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    // ------------------------------------------------------------------ engines

    private String preferredKind() {
        return "vlc".equals(Store.str(this, "engine", "auto")) ? "vlc" : "exo";
    }

    private String configName() {
        return "vlc".equals(engineKind) ? (vlcHw ? "vlc-hw" : "vlc-sw") : "exo";
    }

    /** Events of an old (replaced) engine, or arriving after the screen closed, are dropped; everything runs on the UI thread. */
    private PlayerEngine.Callback guard(final int gen) {
        return new PlayerEngine.Callback() {
            private boolean failed;

            private void post(Runnable r) {
                ui.post(() -> {
                    if (gen == engineGen && engine != null && !isFinishing() && !isDestroyed()) r.run();
                });
            }

            @Override
            public void onPlaying(boolean p) {
                post(() -> handlePlaying(p));
            }

            @Override
            public void onBuffering(boolean b) {
                post(() -> spinner.setVisibility(b ? View.VISIBLE : View.GONE));
            }

            @Override
            public void onReady() {
                post(() -> durTv.setText(Fmt.dur(engine.duration())));
            }

            @Override
            public void onEnded() {
                post(PlayerActivity.this::handleEnded);
            }

            @Override
            public void onError(String msg) {
                post(() -> {
                    if (failed) return;
                    failed = true;
                    errors.add(configName() + ": " + msg + " @" + Fmt.dur(Math.max(lastPos, engine.position())));
                    handleError();
                });
            }

            @Override
            public void onVideoSize(int w, int h) {
                post(() -> handleVideoSize(w, h));
            }

            @Override
            public void onTracksChanged() {
            }
        };
    }

    private void startEngine(String kind, boolean hw) {
        engineKind = kind;
        vlcHw = hw;
        tried.add("vlc".equals(kind) ? (hw ? "vlc-hw" : "vlc-sw") : "exo");
        engineGen++;
        if (engine != null) {
            stage.removeView(engine.view());
            engine.release();
            engine = null;
        }
        PlayerEngine.Callback cb = guard(engineGen);
        if ("vlc".equals(kind)) {
            try {
                engine = new VlcEngine(this, cb, hw);
            } catch (Throwable t) {
                engineKind = "exo";
                tried.add("exo");
                engine = new ExoEngine(this, cb);
            }
        } else {
            engine = new ExoEngine(this, cb);
        }
        stage.addView(engine.view(), 0, new FrameLayout.LayoutParams(-1, -1));
        engineTv.setText(engine.name());
        engine.setSpeed(speed);
        engine.setAspect(aspect);
        engine.setLooping(loop);
    }

    private void startConfig(String cfg) {
        if ("exo".equals(cfg)) startEngine("exo", true);
        else startEngine("vlc", "vlc-hw".equals(cfg));
    }

    private void resetRecovery() {
        tried.clear();
        tried.add(configName());
        skipTried = false;
        errorShown = false;
        recoveredAt = -1;
        lastPos = 0;
        errors.clear();
        cacheTried = false;
    }

    private void toggleEngine() {
        if (locked || engine == null) return;
        manualEngine = true;
        long pos = Math.max(lastPos, engine.position());
        tried.clear();
        skipTried = false;
        errorShown = false;
        startEngine("vlc".equals(engineKind) ? "exo" : "vlc", Store.bool(this, "hw", true));
        openUri(pos, true);
        hudShow(getString(R.string.engine_switched, engine.name()));
    }

    private void openCurrent(boolean allowResume) {
        Media m = playlist.get(idx);
        titleTv.setText(m.name);
        ended = false;
        long start = 0;
        if (allowResume && Store.bool(this, "resume", true)) {
            long r = Store.resume(this, m.resumeKey());
            if (r > 5000 && (m.duration <= 0 || r < m.duration - 8000)) {
                start = r;
                hudShow(getString(R.string.resumed_from, Fmt.dur(r)));
            }
        }
        // a previous file may have needed a fallback engine: every new file starts with the preferred one again
        boolean hw = Store.bool(this, "hw", true);
        if (!manualEngine && (!preferredKind().equals(engineKind) || ("vlc".equals(engineKind) && vlcHw != hw))) {
            startEngine(preferredKind(), hw);
        }
        dropCache();
        resetRecovery();
        if (external(m)) cacheAndPlay(m, start, true);
        else openUri(start, true);
    }

    private boolean external(Media m) {
        return m.id <= 0 && m.uri != null && "content".equals(m.uri.getScheme());
    }

    private Uri sourceUri(Media m) {
        return cachedUri != null ? cachedUri : m.uri;
    }

    private void dropCache() {
        cacheToken++;
        cachedUri = null;
        File[] fs = new File(getCacheDir(), "play").listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }

    private static boolean seekable(android.content.ContentResolver cr, Uri u) {
        try (android.os.ParcelFileDescriptor p = cr.openFileDescriptor(u, "r")) {
            if (p == null) return false;
            android.system.Os.lseek(p.getFileDescriptor(), 0, android.system.OsConstants.SEEK_END);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private Uri copyToCache(Media m, int token) throws Exception {
        File dir = new File(getCacheDir(), "play");
        dir.mkdirs();
        String nm = m.name == null ? "" : m.name;
        int dot = nm.lastIndexOf('.');
        String ext = dot >= 0 && nm.length() - dot <= 6 ? nm.substring(dot).replaceAll("[^A-Za-z0-9.]", "") : "";
        File f = new File(dir, "cur_" + System.currentTimeMillis() + ext);
        long size = m.size;
        if (size <= 0) {
            try (Cursor c = getContentResolver().query(m.uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
                if (c != null && c.moveToFirst() && !c.isNull(0)) size = c.getLong(0);
            } catch (Throwable ignored) {
            }
        }
        if (size > 0 && dir.getUsableSpace() < size + (64L << 20)) return null;
        boolean ok = false;
        try (InputStream in = getContentResolver().openInputStream(m.uri); FileOutputStream out = new FileOutputStream(f)) {
            if (in == null) return null;
            byte[] buf = new byte[256 * 1024];
            long done = 0;
            int lastPct = -1, n;
            while ((n = in.read(buf)) > 0) {
                if (token != cacheToken) return null;
                out.write(buf, 0, n);
                done += n;
                if (size > 0) {
                    final int pct = (int) (done * 100 / size);
                    if (pct != lastPct) {
                        lastPct = pct;
                        ui.post(() -> {
                            if (token == cacheToken) hudShow(getString(R.string.preparing_file) + " " + pct + "%");
                        });
                    }
                }
            }
            ok = true;
        } finally {
            if (!ok) f.delete();
        }
        return Uri.fromFile(f);
    }

    /**
     * Plays a file that cannot be read randomly from where it is: copies it to the cache first (a seek on such a
     * source is what failed) and plays the local copy. {@code check}: only copy when the source is not seekable.
     */
    private void cacheAndPlay(final Media m, final long start, final boolean check) {
        spinner.setVisibility(View.VISIBLE);
        final int token = ++cacheToken;
        new Thread(() -> {
            Uri r = null;
            boolean needed = true;
            try {
                if (check && seekable(getContentResolver(), m.uri)) needed = false;
                else r = copyToCache(m, token);
            } catch (Throwable ignored) {
            }
            final Uri res = r;
            final boolean copied = needed;
            ui.post(() -> {
                if (token != cacheToken || engine == null || isFinishing() || isDestroyed()) return;
                spinner.setVisibility(View.GONE);
                if (res != null) cachedUri = res;
                else if (copied && !check) {
                    errors.add("cache copy failed");
                    showPlayError();
                    return;
                }
                engine.open(sourceUri(m), start, true);
                setPlayingUi(true);
            });
        }).start();
    }

    private void openUri(long start, boolean play) {
        Media m = playlist.get(idx);
        engine.open(sourceUri(m), start, play);
        setPlayingUi(play);
    }

    private void handlePlaying(boolean p) {
        playing = p;
        setPlayingUi(p);
        if (p) {
            ended = false;
            scheduleHide();
        } else {
            setControls(true);
        }
    }

    private void handleEnded() {
        ended = true;
        Store.setResume(this, playlist.get(idx).resumeKey(), 0);
        if (Store.bool(this, "autonext", true) && idx < playlist.size() - 1) {
            step(1);
        } else {
            playing = false;
            setPlayingUi(false);
            setControls(true);
        }
    }

    /** The next engine / decoding mode that was not tried yet for this failure (null when everything failed). */
    private String nextConfig() {
        boolean hw = Store.bool(this, "hw", true);
        String[] order = "vlc".equals(preferredKind())
                ? new String[]{hw ? "vlc-hw" : "vlc-sw", "vlc-sw", "exo"}
                : new String[]{"exo", hw ? "vlc-hw" : "vlc-sw", "vlc-sw"};
        for (String c : order) if (!tried.contains(c)) return c;
        return null;
    }

    /**
     * Playback failed (often in the middle of a big or old file): carry on from the same spot with the next engine,
     * then with software decoding, then once more a few seconds further on (a damaged stretch), before giving up.
     */
    private void handleError() {
        spinner.setVisibility(View.GONE);
        // resume from where playback last really ran: after a failed seek the engine reports the unreadable target
        long pos = lastPos > 0 ? lastPos : (engine == null ? 0 : engine.position());
        Media m = playlist.get(idx);
        String next = nextConfig();
        if (next == null && !cacheTried && cachedUri == null && "content".equals(m.uri.getScheme())) {
            // every engine failed on the original source: play a local copy of the file instead
            cacheTried = true;
            tried.clear();
            recoveredAt = pos;
            errors.add("-> cache copy");
            startEngine(preferredKind(), Store.bool(this, "hw", true));
            cacheAndPlay(m, pos, false);
            hudShow(getString(R.string.preparing_file));
            return;
        }
        boolean skipped = false;
        if (next == null && !skipTried && pos > 0 && (m.duration <= 0 || pos + 8000 < m.duration)) {
            skipTried = true;
            skipped = true;
            pos += 8000;
            tried.clear();
            next = nextConfig();
        }
        if (next == null) {
            showPlayError();
            return;
        }
        recoveredAt = pos;
        startConfig(next);
        openUri(pos, true);
        hudShow(getString(skipped ? R.string.recover_skip : "exo".equals(next) ? R.string.engine_switched
                : "vlc-sw".equals(next) ? R.string.fallback_sw : R.string.fallback_vlc, engine.name()));
    }

    private void showPlayError() {
        if (errorShown || isFinishing() || isDestroyed()) return;
        errorShown = true;
        new Dlg(this)
                .setTitle(R.string.play_error)
                .setMessage(getString(R.string.play_error_msg) + "\n\n" + android.text.TextUtils.join("\n", errors))
                .setPositiveButton(R.string.open_with, (d, w) -> openExternal())
                .setNegativeButton(R.string.close, (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    private void handleVideoSize(int w, int h) {
        if (w > 0 && h > 0) {
            vw = w;
            vh = h;
            if (orient == 0 && Store.bool(this, "autorotate", true) && !isInPip()) {
                setRequestedOrientation(w >= h ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
            }
        }
    }

    private void openExternal() {
        try {
            Media m = playlist.get(idx);
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(m.uri, Actions.mimeOf(m));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, getString(R.string.open_with)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    // ------------------------------------------------------------------ transport

    private void setPlayingUi(boolean p) {
        playBtn.setImageResource(p ? R.drawable.ic_pause_fill : R.drawable.ic_play_fill);
        playBtn.setContentDescription(getString(p ? R.string.pause : R.string.play));
    }

    private void togglePlay() {
        if (engine == null) return;
        if (ended) {
            ended = false;
            engine.seekTo(0);
            engine.play();
        } else if (engine.isPlaying()) engine.pause();
        else engine.play();
    }

    private void skip(long delta) {
        if (engine == null) return;
        long d = engine.duration();
        long t = Math.max(0, engine.position() + delta);
        if (d > 0) t = Math.min(t, d - 500);
        engine.seekTo(t);
        scheduleHide();
    }

    private void step(int dir) {
        int n = idx + dir;
        if (n < 0 || n >= playlist.size()) return;
        saveResume();
        idx = n;
        openCurrent(false);
    }

    private void updateProgress() {
        if (engine == null) return;
        long d = engine.duration();
        long p = engine.position();
        if (playing && p > 0) {
            lastPos = p;
            if (recoveredAt >= 0 && p > recoveredAt + 5000) {   // playing again: a later failure gets a full set of retries
                recoveredAt = -1;
                skipTried = false;
                tried.clear();
                tried.add(configName());
            }
        }
        if (!dragging) {
            if (d > 0) seek.setProgress((int) (p * 1000 / d));
            curTv.setText(Fmt.dur(p));
        }
        if (d > 0) durTv.setText(Fmt.dur(d));
        long now = System.currentTimeMillis();
        if (playing && now - lastSave > 5000) {
            lastSave = now;
            saveResume();
        }
    }

    private void saveResume() {
        if (engine == null || playlist.isEmpty()) return;
        Media m = playlist.get(idx);
        long p = engine.position(), d = engine.duration();
        if (p <= 0) p = lastPos;   // an engine that just failed reports 0: keep the last good spot
        if (p <= 0) return;
        Store.setResume(this, m.resumeKey(), d > 0 && p > d - 8000 ? 0 : p);
    }

    // ------------------------------------------------------------------ controls visibility

    private void setControls(boolean on) {
        if (locked) on = false;
        controlsOn = on;
        controls.animate().alpha(on ? 1f : 0f).setDuration(160).start();
        controls.setVisibility(View.VISIBLE);
        topBar.setEnabled(on);
        for (int i = 0; i < controls.getChildCount(); i++) controls.getChildAt(i).setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        if (on) scheduleHide();
    }

    private void scheduleHide() {
        ui.removeCallbacks(hideRun);
        if (playing) ui.postDelayed(hideRun, 3500);
    }

    private void setLocked(boolean l) {
        locked = l;
        if (lockBack != null) lockBack.setEnabled(l);
        if (l) {
            setControls(false);
            lockFab.setVisibility(View.VISIBLE);
            ui.postDelayed(() -> {
                if (locked) lockFab.setVisibility(View.GONE);
            }, 2000);
            hudShow(getString(R.string.locked));
        } else {
            lockFab.setVisibility(View.GONE);
            setControls(true);
        }
    }

    private void flashLock() {
        lockFab.setVisibility(View.VISIBLE);
        ui.postDelayed(() -> {
            if (locked) lockFab.setVisibility(View.GONE);
        }, 2000);
    }

    private final Runnable hudHide = () -> hud.setVisibility(View.GONE);
    private final Runnable levelHide = () -> {
        if (levelHud == null) return;
        levelHud.animate().alpha(0f).setDuration(180).withEndAction(() -> levelHud.setVisibility(View.GONE)).start();
    };

    private void buildLevelHud(FrameLayout root) {
        levelHud = new LinearLayout(this);
        levelHud.setOrientation(LinearLayout.HORIZONTAL);
        levelHud.setGravity(Gravity.CENTER_VERTICAL);
        levelHud.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(this, 100));
        bg.setColor((Ui.color(this, R.color.surface) & 0x00FFFFFF) | 0xF2000000);
        bg.setStroke(Ui.dp(this, 1), Ui.color(this, R.color.stroke));
        levelHud.setBackground(bg);
        levelHud.setElevation(Ui.dp(this, 6));
        levelHud.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 18), Ui.dp(this, 10));

        levelIcon = new ImageView(this);
        levelIcon.setImageTintList(ColorStateList.valueOf(Ui.color(this, R.color.accent_text)));
        levelHud.addView(levelIcon, new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));

        FrameLayout track = new FrameLayout(this);
        GradientDrawable tg = new GradientDrawable();
        tg.setCornerRadius(Ui.dp(this, 6));
        tg.setColor(Ui.color(this, R.color.neutral_soft));
        track.setBackground(tg);
        levelFill = new View(this);
        GradientDrawable fg = new GradientDrawable();
        fg.setCornerRadius(Ui.dp(this, 6));
        fg.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
        fg.setColors(new int[]{Ui.color(this, R.color.brand_start), Ui.color(this, R.color.brand_mid), Ui.color(this, R.color.brand_end)});
        levelFill.setBackground(fg);
        levelFill.setPivotX(0f);
        track.addView(levelFill, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(Ui.dp(this, 120), Ui.dp(this, 6));
        tp.setMargins(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        levelHud.addView(track, tp);

        levelText = new TextView(this);
        levelText.setTextColor(Ui.color(this, R.color.text_primary));
        levelText.setTextSize(15);
        levelText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        levelText.setMinWidth(Ui.dp(this, 42));
        levelText.setGravity(Gravity.END);
        levelHud.addView(levelText);

        levelHud.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = Ui.dp(this, 120);
        root.addView(levelHud, lp);
    }

    /** Shows the brightness / volume indicator: {@code frac} is 0..1. */
    private void levelShow(int iconRes, float frac) {
        if (levelHud == null) return;
        frac = Math.max(0f, Math.min(1f, frac));
        levelIcon.setImageResource(iconRes);
        levelText.setText(String.format(Locale.US, "%d%%", Math.round(frac * 100f)));
        levelFill.setScaleX(Math.max(0.01f, frac));
        hud.setVisibility(View.GONE);
        ui.removeCallbacks(hudHide);
        levelHud.animate().cancel();
        if (levelHud.getVisibility() != View.VISIBLE) {
            levelHud.setAlpha(0f);
            levelHud.setVisibility(View.VISIBLE);
        }
        levelHud.animate().alpha(1f).setDuration(120).start();
        ui.removeCallbacks(levelHide);
        ui.postDelayed(levelHide, 900);
    }

    private void hudShow(String s) {
        if (levelHud != null) {
            levelHud.animate().cancel();
            levelHud.setVisibility(View.GONE);
            ui.removeCallbacks(levelHide);
        }
        hud.setText(s);
        hud.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hudHide);
        ui.postDelayed(hudHide, 900);
    }

    // ------------------------------------------------------------------ gestures

    private void setupGestures(View v) {
        final int[] axis = {0};            // 0 undecided, 1 horizontal, 2 vertical-left, 3 vertical-right, 4 pan
        final long[] seekTo = {-1};
        final float[] startVol = {0}, startBr = {0};
        final long[] startPos = {0};
        final boolean[] holding = {false};
        final ScaleGestureDetector sg = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                if (locked) return true;
                zoom = Math.max(1f, Math.min(4f, zoom * d.getScaleFactor()));
                stage.setScaleX(zoom);
                stage.setScaleY(zoom);
                if (zoom == 1f) {
                    stage.setTranslationX(0);
                    stage.setTranslationY(0);
                }
                hudShow(String.format(Locale.US, "%d%%", Math.round(zoom * 100)));
                return true;
            }
        });
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                axis[0] = 0;
                seekTo[0] = -1;
                startPos[0] = engine == null ? 0 : engine.position();
                startVol[0] = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                startBr[0] = currentBrightness();
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (locked) {
                    flashLock();
                    return true;
                }
                setControls(!controlsOn);
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (locked || engine == null) return true;
                float x = e.getX() / v.getWidth();
                if (x < 0.35f) {
                    skip(-10_000);
                    hudShow("⏪ 10");
                } else if (x > 0.65f) {
                    skip(10_000);
                    hudShow("10 ⏩");
                } else togglePlay();
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (locked || engine == null) return;
                holding[0] = true;
                engine.setSpeed(2f);
                hudShow("2×");
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (locked || e1 == null || engine == null || holding[0]) return true;
                if (zoom > 1.05f) {
                    float lim = zoom * v.getWidth() / 2f;
                    stage.setTranslationX(Math.max(-lim, Math.min(lim, stage.getTranslationX() - dx)));
                    stage.setTranslationY(Math.max(-lim, Math.min(lim, stage.getTranslationY() - dy)));
                    return true;
                }
                float tx = e2.getX() - e1.getX(), ty = e2.getY() - e1.getY();
                if (axis[0] == 0) {
                    if (Math.abs(tx) > Math.abs(ty) * 1.4f && Math.abs(tx) > Ui.dp(PlayerActivity.this, 14)) axis[0] = 1;
                    else if (Math.abs(ty) > Ui.dp(PlayerActivity.this, 14)) axis[0] = e1.getX() < v.getWidth() / 2f ? 2 : 3;
                    else return true;
                }
                if (axis[0] == 1) {
                    long d = engine.duration();
                    long t = startPos[0] + (long) (tx / v.getWidth() * 90_000f);
                    t = Math.max(0, d > 0 ? Math.min(t, d) : t);
                    seekTo[0] = t;
                    long delta = (t - startPos[0]) / 1000;
                    hudShow(Fmt.dur(t) + "  (" + (delta >= 0 ? "+" : "") + delta + "s)");
                } else if (axis[0] == 2) {
                    float b = Math.max(0.02f, Math.min(1f, startBr[0] - ty / v.getHeight() * 1.3f));
                    WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.screenBrightness = b;
                    getWindow().setAttributes(lp);
                    brightness = b;
                    levelShow(R.drawable.ic_brightness, b);
                } else if (axis[0] == 3) {
                    int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    float vol = Math.max(0, Math.min(max, startVol[0] - ty / v.getHeight() * max * 1.4f));
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(vol), 0);
                    levelShow(Math.round(vol) == 0 ? R.drawable.ic_volume_off : R.drawable.ic_volume, max > 0 ? vol / max : 0f);
                }
                return true;
            }
        });
        v.setOnTouchListener((view, ev) -> {
            sg.onTouchEvent(ev);
            if (!sg.isInProgress()) gd.onTouchEvent(ev);
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                if (seekTo[0] >= 0 && engine != null) engine.seekTo(seekTo[0]);
                seekTo[0] = -1;
                if (holding[0] && engine != null) {
                    holding[0] = false;
                    engine.setSpeed(speed);
                    hudShow(String.format(Locale.US, "%s×", fmtSpeed(speed)));
                }
            }
            return true;
        });
    }

    private float currentBrightness() {
        if (brightness >= 0) return brightness;
        float cur = getWindow().getAttributes().screenBrightness;
        if (cur >= 0) return cur;
        try {
            return Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS) / 255f;
        } catch (Exception e) {
            return 0.5f;
        }
    }

    // ------------------------------------------------------------------ tools

    private static String fmtSpeed(float s) {
        return s == (int) s ? String.valueOf((int) s) : String.valueOf(s);
    }

    private void pickSpeed(View anchor) {
        List<PullDown.Row> rows = new ArrayList<>();
        for (final float sp : SPEEDS) {
            rows.add(PullDown.choice(fmtSpeed(sp) + "×", sp == speed, () -> {
                speed = sp;
                engine.setSpeed(speed);
                speedBtn.setText(fmtSpeed(speed) + "×");
                hudShow(fmtSpeed(speed) + "×");
            }));
        }
        PullDown.show(this, anchor, rows);
    }

    private void cycleAspect() {
        aspect = (aspect + 1) % 5;
        engine.setAspect(aspect);
        zoom = 1f;
        stage.setScaleX(1f);
        stage.setScaleY(1f);
        stage.setTranslationX(0);
        stage.setTranslationY(0);
        hudShow(getString(ASPECT_NAMES[aspect]));
    }

    private void cycleOrientation() {
        orient = (orient + 1) % 3;
        if (orient == 0) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        else if (orient == 1) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        else setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        hudShow(getString(orient == 0 ? R.string.rot_auto : orient == 1 ? R.string.rot_land : R.string.rot_port));
    }

    private void showMore(View anchor) {
        List<PullDown.Row> rows = new ArrayList<>();
        rows.add(PullDown.action(getString(loop ? R.string.loop_on : R.string.t_loop), R.drawable.ic_refresh, () -> {
            loop = !loop;
            engine.setLooping(loop);
            hudShow(getString(loop ? R.string.loop_on : R.string.loop_off));
        }));
        rows.add(PullDown.action(getString(R.string.t_pip), R.drawable.ic_play, this::enterPip));
        rows.add(PullDown.action(getString(R.string.info), R.drawable.ic_info, () -> InfoSheet.show(this, playlist.get(idx))));
        rows.add(PullDown.separator());
        rows.add(PullDown.action(getString(R.string.set_engine), R.drawable.ic_play_fill, this::toggleEngine));
        PullDown.show(this, anchor, rows);
    }

    private void pickAudio(View anchor) {
        final List<PlayerEngine.Track> l = engine.audioTracks();
        if (l.isEmpty()) {
            Toast.makeText(this, R.string.no_tracks, Toast.LENGTH_SHORT).show();
            return;
        }
        List<PullDown.Row> rows = new ArrayList<>();
        for (int i = 0; i < l.size(); i++) {
            final int k = i;
            rows.add(PullDown.choice(l.get(i).label, l.get(i).selected, () -> engine.selectAudio(k)));
        }
        PullDown.show(this, anchor, rows);
    }

    private void pickSubs(View anchor) {
        final List<PlayerEngine.Track> l = engine.textTracks();
        boolean any = false;
        for (PlayerEngine.Track t : l) if (t.selected) any = true;
        List<PullDown.Row> rows = new ArrayList<>();
        rows.add(PullDown.choice(getString(R.string.subs_off), !any, () -> engine.selectText(-1)));
        for (int i = 0; i < l.size(); i++) {
            final int k = i;
            rows.add(PullDown.choice(l.get(i).label, l.get(i).selected, () -> engine.selectText(k)));
        }
        rows.add(PullDown.separator());
        rows.add(PullDown.action(getString(R.string.subs_add), R.drawable.ic_plus, () -> subPicker.launch(new String[]{"*/*"})));
        PullDown.show(this, anchor, rows);
    }

    private void onSubtitlePicked(Uri u) {
        if (u == null) return;
        String ext = "srt";
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String nm = c.getString(0);
                if (nm != null && nm.lastIndexOf('.') > 0) ext = nm.substring(nm.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            }
        } catch (Exception ignored) {
        }
        try {
            File dir = new File(getCacheDir(), "subs");
            dir.mkdirs();
            File f = new File(dir, "sub_" + System.currentTimeMillis() + "." + ext);
            try (InputStream in = getContentResolver().openInputStream(u); FileOutputStream out = new FileOutputStream(f)) {
                byte[] buf = new byte[16384];
                int n;
                while (in != null && (n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            engine.addSubtitle(Uri.fromFile(f), ext);
            hudShow(getString(R.string.subs_added));
        } catch (Exception e) {
            Toast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ picture in picture

    private boolean isInPip() {
        return Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode();
    }

    private void enterPip() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            PictureInPictureParams.Builder b = new PictureInPictureParams.Builder();
            if (vw > 0 && vh > 0) {
                float r = Math.max(0.42f, Math.min(2.39f, (float) vw / vh));
                b.setAspectRatio(new Rational(Math.round(r * 1000), 1000));
            }
            enterPictureInPictureMode(b.build());
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (playing && Store.bool(this, "pip", true) && !locked) enterPip();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, Configuration cfg) {
        super.onPictureInPictureModeChanged(inPip, cfg);
        if (inPip) {
            ui.removeCallbacks(hideRun);
            controls.setVisibility(View.GONE);
            lockFab.setVisibility(View.GONE);
        } else {
            controls.setVisibility(View.VISIBLE);
            setControls(true);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Stops the picture first (a live video surface inside a closing window is what made leaving the player stutter). */
    @Override
    public void finish() {
        if (!isFinishing() && engine != null) {
            try {
                engine.pause();
                saveResume();
                View v = engine.view();
                if (v != null) v.setVisibility(View.INVISIBLE);
            } catch (Throwable ignored) {
            }
        }
        super.finish();
        Motion.legacy(this, R.anim.hold_close, R.anim.viewer_close_exit);
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveResume();
        if (engine != null && !isInPip()) engine.pause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isInPip() && engine != null) engine.pause();   // PiP window was closed
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (engine != null) {
            saveResume();
            engineGen++;   // nothing from the old engine may reach this screen any more
            engine.release();
            engine = null;
        }
        dropCache();
        super.onDestroy();
    }
}
