package com.gallery.app;

import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
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
import android.widget.HorizontalScrollView;
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
public class PlayerActivity extends BaseActivity implements PlayerEngine.Callback {
    private static final float[] SPEEDS = {0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f, 4f};
    private static final int[] ASPECT_NAMES = {R.string.asp_fit, R.string.asp_stretch, R.string.asp_crop, R.string.asp_169, R.string.asp_43};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Media> playlist = new ArrayList<>();
    private int idx;
    private PlayerEngine engine;
    private String engineKind = "exo";
    private FrameLayout root, stage, controls;
    private LinearLayout topBar, centerBar, bottomBar;
    private TextView titleTv, curTv, durTv, engineTv, hud;
    private SeekBar seek;
    private ImageButton playBtn, lockFab;
    private SpinnerView spinner;
    private boolean controlsOn = true, locked = false, dragging = false, playing = false, ended = false;
    private boolean triedFallback = false, userEngine = false;
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
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (locked) {
                    flashLock();
                    return;
                }
                finish();
            }
        });
        String pref = Store.str(this, "engine", "auto");
        engineKind = "vlc".equals(pref) ? "vlc" : "exo";
        startEngine(engineKind);
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

    private ImageButton ib(int icon, int desc, int sizeDp) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setBackgroundResource(R.drawable.bg_tile_round);
        b.setContentDescription(getString(desc));
        Ui.press(this, b);
        return b;
    }

    private LinearLayout tool(int icon, int label, View.OnClickListener click) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4));
        ImageView i = new ImageView(this);
        i.setImageResource(icon);
        i.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        t.addView(i, new LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26)));
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(0xDDFFFFFF);
        l.setTextSize(10.5f);
        l.setSingleLine(true);
        l.setGravity(Gravity.CENTER);
        t.addView(l);
        t.setContentDescription(getString(label));
        Ui.press(this, t);
        t.setOnClickListener(click);
        return t;
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

        // top
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xCC000000, 0}));
        ImageButton back = ib(R.drawable.ic_back, R.string.back, 44);
        back.setOnClickListener(v -> finish());
        topBar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        titleTv = new TextView(this);
        titleTv.setTextColor(Color.WHITE);
        titleTv.setTextSize(16);
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        titleTv.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        titleTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        topBar.addView(titleTv, new LinearLayout.LayoutParams(0, -2, 1f));
        engineTv = new TextView(this);
        engineTv.setTextColor(Color.WHITE);
        engineTv.setTextSize(11.5f);
        engineTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        GradientDrawable eg = new GradientDrawable();
        eg.setCornerRadius(Ui.dp(this, 20));
        eg.setColor(0x44FFFFFF);
        engineTv.setBackground(eg);
        engineTv.setPadding(Ui.dp(this, 10), Ui.dp(this, 5), Ui.dp(this, 10), Ui.dp(this, 5));
        engineTv.setOnClickListener(v -> toggleEngine());
        topBar.addView(engineTv);
        controls.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // center transport
        centerBar = new LinearLayout(this);
        centerBar.setOrientation(LinearLayout.HORIZONTAL);
        centerBar.setGravity(Gravity.CENTER);
        centerBar.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        ImageButton prev = ib(R.drawable.ic_skip_prev, R.string.prev, 52);
        ImageButton rew = ib(R.drawable.ic_rewind, R.string.back10, 52);
        playBtn = ib(R.drawable.ic_play_fill, R.string.play, 72);
        ImageButton fwd = ib(R.drawable.ic_forward, R.string.fwd10, 52);
        ImageButton next = ib(R.drawable.ic_skip_next, R.string.next, 52);
        GradientDrawable pg = new GradientDrawable();
        pg.setShape(GradientDrawable.OVAL);
        pg.setColor(Ui.color(this, R.color.accent));
        playBtn.setBackground(pg);
        prev.setOnClickListener(v -> step(-1));
        next.setOnClickListener(v -> step(1));
        rew.setOnClickListener(v -> skip(-10_000));
        fwd.setOnClickListener(v -> skip(10_000));
        playBtn.setOnClickListener(v -> togglePlay());
        centerBar.addView(prev, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        centerBar.addView(rew, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(Ui.dp(this, 74), Ui.dp(this, 74));
        pl.setMargins(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        centerBar.addView(playBtn, pl);
        centerBar.addView(fwd, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        centerBar.addView(next, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        controls.addView(centerBar, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        // bottom
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xCC000000, 0}));
        LinearLayout sr = new LinearLayout(this);
        sr.setGravity(Gravity.CENTER_VERTICAL);
        sr.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        curTv = timeText();
        durTv = timeText();
        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setProgressTintList(ColorStateList.valueOf(Ui.color(this, R.color.accent)));
        seek.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(0x55FFFFFF));
        seek.setSecondaryProgressTintList(ColorStateList.valueOf(0x55FFFFFF));
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
        sr.addView(curTv);
        sr.addView(seek, new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1f));
        sr.addView(durTv);
        bottomBar.addView(sr);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER);
        tools.addView(tool(R.drawable.ic_lock, R.string.t_lock, v -> setLocked(true)));
        tools.addView(tool(R.drawable.ic_speed, R.string.t_speed, v -> pickSpeed()));
        tools.addView(tool(R.drawable.ic_aspect, R.string.t_aspect, v -> cycleAspect()));
        tools.addView(tool(R.drawable.ic_rotate, R.string.t_rotate, v -> cycleOrientation()));
        tools.addView(tool(R.drawable.ic_music, R.string.t_audio, v -> pickAudio()));
        tools.addView(tool(R.drawable.ic_subtitle, R.string.t_subs, v -> pickSubs()));
        tools.addView(tool(R.drawable.ic_repeat, R.string.t_loop, v -> toggleLoop((LinearLayout) v)));
        if (Build.VERSION.SDK_INT >= 26 && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            tools.addView(tool(R.drawable.ic_pip, R.string.t_pip, v -> enterPip()));
        }
        tools.addView(tool(R.drawable.ic_info, R.string.info, v -> InfoSheet.show(this, playlist.get(idx))));
        hs.addView(tools);
        bottomBar.addView(hs);
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
        hp.topMargin = Ui.dp(this, 70);
        root.addView(hud, hp);

        spinner = new SpinnerView(this);
        root.addView(spinner, new FrameLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52), Gravity.CENTER));
        spinner.setVisibility(View.GONE);

        lockFab = ib(R.drawable.ic_lock_fill, R.string.t_unlock, 52);
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
            topBar.setPadding(Ui.dp(this, 8) + b.left, b.top + Ui.dp(this, 4), Ui.dp(this, 12) + b.right, Ui.dp(this, 20));
            bottomBar.setPadding(Ui.dp(this, 12) + b.left, Ui.dp(this, 16), Ui.dp(this, 12) + b.right, Ui.dp(this, 8) + b.bottom);
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

    private void startEngine(String kind) {
        engineKind = kind;
        if (engine != null) {
            stage.removeView(engine.view());
            engine.release();
        }
        if ("vlc".equals(kind)) {
            try {
                engine = new VlcEngine(this, this, Store.bool(this, "hw", true));
            } catch (Throwable t) {
                engineKind = "exo";
                engine = new ExoEngine(this, this);
            }
        } else {
            engine = new ExoEngine(this, this);
        }
        stage.addView(engine.view(), 0, new FrameLayout.LayoutParams(-1, -1));
        engineTv.setText(engine.name());
        engine.setSpeed(speed);
        engine.setAspect(aspect);
        engine.setLooping(loop);
    }

    private void toggleEngine() {
        if (locked) return;
        userEngine = true;
        long pos = engine == null ? 0 : engine.position();
        startEngine("vlc".equals(engineKind) ? "exo" : "vlc");
        openUri(pos, true);
        hudShow(getString(R.string.engine_switched, engine.name()));
    }

    private void openCurrent(boolean allowResume) {
        Media m = playlist.get(idx);
        titleTv.setText(m.name);
        ended = false;
        long start = 0;
        if (allowResume && Store.bool(this, "resume", true)) {
            long r = Store.resume(this, m.key() + m.modified);
            if (r > 5000 && (m.duration <= 0 || r < m.duration - 8000)) {
                start = r;
                hudShow(getString(R.string.resumed_from, Fmt.dur(r)));
            }
        }
        triedFallback = false;
        openUri(start, true);
    }

    private void openUri(long start, boolean play) {
        Media m = playlist.get(idx);
        engine.open(m.uri, start, play);
        setPlayingUi(play);
    }

    @Override
    public void onPlaying(boolean p) {
        ui.post(() -> {
            playing = p;
            setPlayingUi(p);
            if (p) {
                ended = false;
                scheduleHide();
            } else {
                setControls(true);
            }
        });
    }

    @Override
    public void onBuffering(boolean b) {
        ui.post(() -> spinner.setVisibility(b ? View.VISIBLE : View.GONE));
    }

    @Override
    public void onReady() {
        ui.post(() -> {
            long d = engine.duration();
            durTv.setText(Fmt.dur(d));
        });
    }

    @Override
    public void onEnded() {
        ui.post(() -> {
            ended = true;
            Store.setResume(this, playlist.get(idx).key() + playlist.get(idx).modified, 0);
            if (Store.bool(this, "autonext", true) && idx < playlist.size() - 1) {
                step(1);
            } else {
                playing = false;
                setPlayingUi(false);
                setControls(true);
            }
        });
    }

    @Override
    public void onError(String msg, boolean unsupported) {
        ui.post(() -> {
            String pref = Store.str(this, "engine", "auto");
            if ("exo".equals(engineKind) && unsupported && !"exo".equals(pref) && !triedFallback) {
                triedFallback = true;
                long pos = engine.position();
                startEngine("vlc");
                openUri(pos, true);
                hudShow(getString(R.string.fallback_vlc));
                return;
            }
            new Dlg(this)
                    .setTitle(R.string.play_error)
                    .setMessage(R.string.play_error_msg)
                    .setPositiveButton(R.string.open_with, (d, w) -> openExternal())
                    .setNegativeButton(R.string.close, (d, w) -> finish())
                    .show();
        });
    }

    @Override
    public void onVideoSize(int w, int h) {
        ui.post(() -> {
            if (w > 0 && h > 0) {
                vw = w;
                vh = h;
                if (orient == 0 && Store.bool(this, "autorotate", true) && !isInPip()) {
                    setRequestedOrientation(w >= h ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
                }
            }
        });
    }

    @Override
    public void onTracksChanged() {
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
        Store.setResume(this, m.key() + m.modified, d > 0 && p > d - 8000 ? 0 : p);
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

    private void hudShow(String s) {
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
                    hudShow("☀ " + Math.round(b * 100) + "%");
                } else if (axis[0] == 3) {
                    int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    float vol = Math.max(0, Math.min(max, startVol[0] - ty / v.getHeight() * max * 1.4f));
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(vol), 0);
                    hudShow("🔊 " + Math.round(vol * 100f / max) + "%");
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

    private void pickSpeed() {
        String[] n = new String[SPEEDS.length];
        for (int i = 0; i < n.length; i++) n[i] = (SPEEDS[i] == speed ? "● " : "○ ") + fmtSpeed(SPEEDS[i]) + "×";
        new Dlg(this).setTitle(R.string.t_speed).setItems(n, (d, w) -> {
            speed = SPEEDS[w];
            engine.setSpeed(speed);
            hudShow(fmtSpeed(speed) + "×");
        }).show();
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

    private void toggleLoop(LinearLayout tool) {
        loop = !loop;
        engine.setLooping(loop);
        tool.setAlpha(loop ? 1f : 0.7f);
        ((ImageView) tool.getChildAt(0)).setImageTintList(ColorStateList.valueOf(loop ? Ui.color(this, R.color.accent) : Color.WHITE));
        hudShow(getString(loop ? R.string.loop_on : R.string.loop_off));
    }

    private void pickAudio() {
        final List<PlayerEngine.Track> l = engine.audioTracks();
        if (l.isEmpty()) {
            Toast.makeText(this, R.string.no_tracks, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] n = new String[l.size()];
        for (int i = 0; i < n.length; i++) n[i] = (l.get(i).selected ? "● " : "○ ") + l.get(i).label;
        new Dlg(this).setTitle(R.string.t_audio).setItems(n, (d, w) -> engine.selectAudio(w)).show();
    }

    private void pickSubs() {
        final List<PlayerEngine.Track> l = engine.textTracks();
        boolean any = false;
        for (PlayerEngine.Track t : l) if (t.selected) any = true;
        final List<String> n = new ArrayList<>();
        n.add((any ? "○ " : "● ") + getString(R.string.subs_off));
        for (PlayerEngine.Track t : l) n.add((t.selected ? "● " : "○ ") + t.label);
        n.add("＋ " + getString(R.string.subs_add));
        new Dlg(this).setTitle(R.string.t_subs).setItems(n.toArray(new String[0]), (d, w) -> {
            if (w == 0) engine.selectText(-1);
            else if (w == n.size() - 1) subPicker.launch(new String[]{"*/*"});
            else engine.selectText(w - 1);
        }).show();
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
            engine.release();
            engine = null;
        }
        super.onDestroy();
    }
}
