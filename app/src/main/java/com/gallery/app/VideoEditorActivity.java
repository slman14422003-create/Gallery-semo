package com.gallery.app;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AlertDialog;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.Brightness;
import androidx.media3.effect.Contrast;
import androidx.media3.effect.Crop;
import androidx.media3.effect.HslAdjustment;
import androidx.media3.effect.RgbAdjustment;
import androidx.media3.effect.RgbFilter;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Video editor (beta): trim, rotate, flip, crop to a ratio, mute and colour looks. Exports a new MP4; the original is kept. */
@OptIn(markerClass = UnstableApi.class)
public class VideoEditorActivity extends BaseActivity {
    private static final float[] RATIOS = {0f, 1f, 16f / 9f, 9f / 16f, 4f / 3f};
    private static final int[] RATIO_NAMES = {R.string.cr_original, R.string.cr_1_1, R.string.cr_16_9, R.string.cr_9_16, R.string.cr_4_3};
    private static final int[] LOOK_NAMES = {R.string.fl_original, R.string.fl_bw, R.string.fl_warm, R.string.fl_cool, R.string.fl_vivid, R.string.fl_fade};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Media media;
    private ExoPlayer player;
    private AspectFrame frame;
    private TrimBar trim;
    private TextView rangeTv;
    private ImageButton playBtn, muteBtn;
    private Transformer transformer;
    private AlertDialog progressDialog;
    private ProgressBar progressBar;
    private TextView progressText;
    private boolean exporting = false, dirty = false;

    private int rot = 0;
    private boolean flipH = false, flipV = false, mute = false;
    private int ratioIdx = 0, look = 0;
    private float brightness = 0f, contrast = 0f, aiWarm = 0f, aiSat = 0f;
    private SeekBar sbBright, sbContrast;
    private boolean aiBusy = false;
    private int srcW = 0, srcH = 0;
    private long duration = 0;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (player != null) {
                long p = player.getCurrentPosition();
                if (player.isPlaying() && p >= trim.end()) {
                    player.seekTo(trim.start());
                    p = trim.start();
                }
                trim.setPlayhead(p);
            }
            ui.postDelayed(this, 80);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        media = Library.editTarget;
        if (media == null || !media.video) {
            finish();
            return;
        }
        duration = media.duration;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.color(this, R.color.bg));
        setContentView(root);

        // header
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 6));
        ImageButton close = new ImageButton(this, null, 0, R.style.IconButton);
        close.setImageResource(R.drawable.ic_cancel);
        close.setOnClickListener(v -> askLeave());
        head.addView(close, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        TextView title = new TextView(this);
        title.setText(R.string.edit_video);
        title.setTextColor(Ui.color(this, R.color.text_primary));
        title.setTextSize(17);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        title.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        LinearLayout tl = new LinearLayout(this);
        tl.setGravity(Gravity.CENTER_VERTICAL);
        tl.addView(title);
        TextView beta = new TextView(this);
        beta.setText(R.string.beta2);
        beta.setTextSize(10.5f);
        beta.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        beta.setTextColor(Ui.color(this, R.color.accent_text));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(this, 20));
        bg.setColor((Ui.color(this, R.color.accent_text) & 0x00FFFFFF) | 0x26000000);
        beta.setBackground(bg);
        beta.setPadding(Ui.dp(this, 8), Ui.dp(this, 2), Ui.dp(this, 8), Ui.dp(this, 2));
        tl.addView(beta);
        head.addView(tl, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView export = new TextView(this);
        export.setText(R.string.export);
        export.setTextColor(Ui.color(this, R.color.on_accent));
        export.setTextSize(14.5f);
        export.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        export.setBackgroundResource(R.drawable.btn_primary);
        export.setGravity(Gravity.CENTER);
        export.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), 0);
        export.setOnClickListener(v -> export());
        head.addView(export, new LinearLayout.LayoutParams(-2, Ui.dp(this, 42)));
        root.addView(head);

        // preview
        FrameLayout pv = new FrameLayout(this);
        pv.setBackgroundColor(0xFF000000);
        frame = new AspectFrame(this);
        SurfaceView sv = new SurfaceView(this);
        frame.addView(sv);
        pv.addView(frame, new FrameLayout.LayoutParams(-1, -1));
        root.addView(pv, new LinearLayout.LayoutParams(-1, 0, 1f));

        player = new ExoPlayer.Builder(this).build();
        player.setVideoSurfaceView(sv);
        player.setMediaItem(MediaItem.fromUri(media.uri));
        player.setRepeatMode(Player.REPEAT_MODE_OFF);
        player.addListener(new Player.Listener() {
            @Override
            public void onVideoSizeChanged(androidx.media3.common.VideoSize v) {
                if (v.width > 0 && v.height > 0 && srcW == 0) {
                    srcW = v.width;
                    srcH = v.height;
                    frame.setVideoRatio(v.width * v.pixelWidthHeightRatio / v.height);
                }
            }

            @Override
            public void onIsPlayingChanged(boolean playing) {
                playBtn.setImageResource(playing ? R.drawable.ic_pause_fill : R.drawable.ic_play_fill);
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY && duration <= 0) {
                    duration = player.getDuration();
                    trim.setDuration(duration);
                    updateRange();
                }
            }
        });
        player.prepare();

        // transport + range
        LinearLayout tr = new LinearLayout(this);
        tr.setGravity(Gravity.CENTER_VERTICAL);
        tr.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), 0);
        playBtn = new ImageButton(this, null, 0, R.style.IconButton);
        playBtn.setImageResource(R.drawable.ic_play_fill);
        playBtn.setOnClickListener(v -> {
            if (player.isPlaying()) player.pause();
            else {
                if (player.getCurrentPosition() >= trim.end() - 100 || player.getCurrentPosition() < trim.start()) player.seekTo(trim.start());
                player.play();
            }
        });
        tr.addView(playBtn, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        rangeTv = new TextView(this);
        rangeTv.setTextColor(Ui.color(this, R.color.text_secondary));
        rangeTv.setTextSize(13.5f);
        rangeTv.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        tr.addView(rangeTv, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(tr);

        trim = new TrimBar(this);
        trim.setDuration(Math.max(1, duration));
        trim.setListener((s, e, which, done) -> {
            updateRange();
            if (which == 0) player.seekTo(s);
            else if (which == 1) player.seekTo(Math.max(s, e - 400));
            else if (which == 2) player.seekTo(trim.head());
            dirty = true;
        });
        root.addView(trim, new LinearLayout.LayoutParams(-1, Ui.dp(this, 76)));
        loadThumbs();

        // tools
        ScrollView sv2 = new ScrollView(this);
        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.VERTICAL);
        tools.setPadding(Ui.dp(this, 14), Ui.dp(this, 2), Ui.dp(this, 14), Ui.dp(this, 10));
        LinearLayout ops = new LinearLayout(this);
        ops.setGravity(Gravity.CENTER);
        ops.addView(icon(R.drawable.ic_rotate_left, R.string.rot_left, v -> {
            rot = (rot + 3) % 4;
            applyEffects();
        }), new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 48)));
        ops.addView(icon(R.drawable.ic_rotate, R.string.rot_right, v -> {
            rot = (rot + 1) % 4;
            applyEffects();
        }), new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 48)));
        ops.addView(icon(R.drawable.ic_flip, R.string.flip_h, v -> {
            flipH = !flipH;
            applyEffects();
        }), new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 48)));
        ImageButton fv = icon(R.drawable.ic_flip, R.string.flip_v, v -> {
            flipV = !flipV;
            applyEffects();
        });
        fv.setRotation(90f);
        ops.addView(fv, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 48)));
        muteBtn = icon(R.drawable.ic_volume, R.string.mute, v -> {
            mute = !mute;
            muteBtn.setImageResource(mute ? R.drawable.ic_volume_off : R.drawable.ic_volume);
            player.setVolume(mute ? 0f : 1f);
            dirty = true;
        });
        ops.addView(muteBtn, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 48)));
        tools.addView(ops);

        tools.addView(Ui.label(this, getString(R.string.v_ai)));
        HorizontalScrollView aiScroll = new HorizontalScrollView(this);
        aiScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout aiRow = new LinearLayout(this);
        TextView aiAuto = Ui.chip(this, "\u2728  " + getString(R.string.ad_auto), false);
        aiAuto.setOnClickListener(v -> aiEnhance());
        aiRow.addView(aiAuto);
        TextView aiLow = Ui.chip(this, getString(R.string.v_lowlight), false);
        aiLow.setOnClickListener(v -> preset(0.14f, 0.12f, 0f, 12f));
        aiRow.addView(aiLow);
        TextView aiHaze = Ui.chip(this, getString(R.string.v_dehaze), false);
        aiHaze.setOnClickListener(v -> preset(-0.03f, 0.25f, 0f, 22f));
        aiRow.addView(aiHaze);
        TextView aiTrim = Ui.chip(this, getString(R.string.v_smart_trim), false);
        aiTrim.setOnClickListener(v -> aiSmartTrim());
        aiRow.addView(aiTrim);
        aiScroll.addView(aiRow);
        tools.addView(aiScroll);

        tools.addView(Ui.label(this, getString(R.string.crop_ratio)));
        tools.addView(chipRow(RATIO_NAMES, 0, i -> {
            ratioIdx = i;
            applyEffects();
        }));
        tools.addView(Ui.label(this, getString(R.string.look)));
        tools.addView(chipRow(LOOK_NAMES, 0, i -> {
            look = i;
            applyEffects();
        }));
        View bs = slider(R.string.ad_brightness, v -> {
            brightness = v;
            applyEffects();
        });
        sbBright = (SeekBar) ((LinearLayout) bs).getChildAt(1);
        tools.addView(bs);
        View cs = slider(R.string.ad_contrast, v -> {
            contrast = v;
            applyEffects();
        });
        sbContrast = (SeekBar) ((LinearLayout) cs).getChildAt(1);
        tools.addView(cs);
        TextView note = Ui.noteCard(this, getString(R.string.video_beta_note), R.color.text_secondary);
        tools.addView(note);
        sv2.addView(tools);
        root.addView(sv2, new LinearLayout.LayoutParams(-1, Ui.dp(this, 210)));

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                askLeave();
            }
        });
        updateRange();
        ui.post(tick);
    }

    private interface IntCb {
        void on(int i);
    }

    private interface FloatCb {
        void on(float v);
    }

    private ImageButton icon(int res, int desc, View.OnClickListener c) {
        ImageButton b = new ImageButton(this, null, 0, R.style.IconButton);
        b.setImageResource(res);
        b.setContentDescription(getString(desc));
        b.setOnClickListener(c);
        return b;
    }

    private HorizontalScrollView chipRow(int[] names, int sel, final IntCb cb) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        final List<TextView> cs = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            final int k = i;
            TextView c = Ui.chip(this, getString(names[i]), i == sel);
            c.setOnClickListener(v -> {
                for (int j = 0; j < cs.size(); j++) Ui.setChip(this, cs.get(j), j == k);
                cb.on(k);
            });
            cs.add(c);
            row.addView(c);
        }
        hs.addView(row);
        return hs;
    }

    private View slider(int label, final FloatCb cb) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(Ui.color(this, R.color.text_secondary));
        l.setTextSize(13.5f);
        r.addView(l, new LinearLayout.LayoutParams(Ui.dp(this, 84), -2));
        SeekBar sb = new SeekBar(this);
        sb.setMax(200);
        sb.setProgress(100);
        sb.setProgressTintList(ColorStateList.valueOf(Ui.color(this, R.color.accent)));
        sb.setThumbTintList(ColorStateList.valueOf(Ui.color(this, R.color.accent)));
        final float[] pending = new float[1];
        final Runnable fire = () -> cb.on(pending[0]);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser) return;
                pending[0] = (p - 100) / 100f * 0.6f;
                ui.removeCallbacks(fire);
                ui.postDelayed(fire, 60);
            }

            @Override public void onStartTrackingTouch(SeekBar s) { }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                ui.removeCallbacks(fire);
                cb.on((s.getProgress() - 100) / 100f * 0.6f);
            }
        });
        r.addView(sb, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f));
        return r;
    }

    private void updateRange() {
        long s = trim.start(), e = trim.end();
        rangeTv.setText(String.format(Locale.US, "%s – %s   (%s)", Fmt.dur(s), Fmt.dur(e), Fmt.dur(e - s)));
    }

    private void loadThumbs() {
        new Thread(() -> {
            List<Bitmap> out = new ArrayList<>();
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(this, media.uri);
                long d = media.duration > 0 ? media.duration : 1000;
                int n = 9;
                for (int i = 0; i < n; i++) {
                    Bitmap b = r.getFrameAtTime(d * 1000L * i / n, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (b != null) out.add(Bitmap.createScaledBitmap(b, 120, Math.max(1, 120 * b.getHeight() / b.getWidth()), true));
                }
            } catch (Exception ignored) {
            } finally {
                try {
                    r.release();
                } catch (Exception ignored) {
                }
            }
            runOnUiThread(() -> trim.setThumbs(out));
        }).start();
    }

    // ------------------------------------------------------------------ effects

    private List<Effect> effects() {
        List<Effect> v = new ArrayList<>();
        if (rot != 0 || flipH || flipV) {
            v.add(new ScaleAndRotateTransformation.Builder()
                    .setRotationDegrees(-rot * 90f)
                    .setScale(flipH ? -1f : 1f, flipV ? -1f : 1f)
                    .build());
        }
        if (ratioIdx != 0 && srcW > 0 && srcH > 0) {
            boolean odd = rot % 2 == 1;
            float fw = odd ? srcH : srcW, fh = odd ? srcW : srcH;
            float frameA = fw / fh, a = RATIOS[ratioIdx];
            if (Math.abs(frameA - a) > 0.01f) {
                if (frameA > a) {
                    float f = a / frameA;
                    v.add(new Crop(-f, f, -1f, 1f));
                } else {
                    float f = frameA / a;
                    v.add(new Crop(-1f, 1f, -f, f));
                }
            }
        }
        switch (look) {
            case 1:
                v.add(RgbFilter.createGrayscaleFilter());
                break;
            case 2:
                v.add(new RgbAdjustment.Builder().setRedScale(1.12f).setBlueScale(0.86f).build());
                break;
            case 3:
                v.add(new RgbAdjustment.Builder().setRedScale(0.88f).setBlueScale(1.14f).build());
                break;
            case 4:
                v.add(new HslAdjustment.Builder().adjustSaturation(40f).build());
                break;
            case 5:
                v.add(new Contrast(-0.22f));
                v.add(new Brightness(0.05f));
                break;
            default:
        }
        if (aiWarm != 0f) v.add(new RgbAdjustment.Builder().setRedScale(1f + 0.12f * aiWarm).setBlueScale(1f - 0.12f * aiWarm).build());
        if (aiSat != 0f) v.add(new HslAdjustment.Builder().adjustSaturation(aiSat).build());
        if (brightness != 0f) v.add(new Brightness(brightness));
        if (contrast != 0f) v.add(new Contrast(contrast));
        return v;
    }

    private void applyEffects() {
        dirty = true;
        boolean odd = rot % 2 == 1;
        float base = srcW > 0 && srcH > 0 ? (odd ? (float) srcH / srcW : (float) srcW / srcH) : 0f;
        frame.setVideoRatio(ratioIdx != 0 ? RATIOS[ratioIdx] : base);
        try {
            player.setVideoEffects(effects());
            if (!player.isPlaying()) player.seekTo(player.getCurrentPosition());
        } catch (Throwable t) {
            Toast.makeText(this, getString(R.string.error) + ": " + t.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ AI tools (on device, no network)

    private void syncSliders() {
        if (sbBright != null) sbBright.setProgress(Math.max(0, Math.min(200, Math.round(brightness / 0.6f * 100f) + 100)));
        if (sbContrast != null) sbContrast.setProgress(Math.max(0, Math.min(200, Math.round(contrast / 0.6f * 100f) + 100)));
    }

    private void preset(float b, float c, float warm, float sat) {
        brightness = b;
        contrast = c;
        aiWarm = warm;
        aiSat = sat;
        syncSliders();
        applyEffects();
    }

    private Bitmap frameAt(MediaMetadataRetriever r, long ms, int maxSide) {
        Bitmap b = r.getFrameAtTime(ms * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        if (b == null) return null;
        float k = Math.min(1f, maxSide / (float) Math.max(b.getWidth(), b.getHeight()));
        if (k >= 1f) return b;
        return Bitmap.createScaledBitmap(b, Math.max(8, Math.round(b.getWidth() * k)), Math.max(8, Math.round(b.getHeight() * k)), true);
    }

    private void aiEnhance() {
        if (aiBusy) return;
        aiBusy = true;
        Toast.makeText(this, R.string.v_working, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            AutoEnhance.Result res = null;
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(this, media.uri);
                Bitmap f = frameAt(r, Math.max(0, duration / 2), 320);
                if (f != null) res = AutoEnhance.analyze(f);
            } catch (Throwable ignored) {
            } finally {
                try {
                    r.release();
                } catch (Throwable ignored) {
                }
            }
            final AutoEnhance.Result out = res;
            runOnUiThread(() -> {
                aiBusy = false;
                if (isDestroyed() || out == null) return;
                preset(Math.max(-0.3f, Math.min(0.3f, out.brightness * 0.4f)),
                        Math.max(-0.3f, Math.min(0.4f, out.contrast * 0.6f)),
                        out.warmth, Math.max(-30f, Math.min(40f, out.saturation * 40f)));
            });
        }).start();
    }

    /** Samples frames and cuts away black lead-in / lead-out. */
    private void aiSmartTrim() {
        if (aiBusy || duration <= 0) return;
        aiBusy = true;
        Toast.makeText(this, R.string.v_working, Toast.LENGTH_SHORT).show();
        final long d = duration;
        new Thread(() -> {
            final int n = 24;
            boolean[] black = new boolean[n];
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(this, media.uri);
                for (int i = 0; i < n; i++) black[i] = AiTools.isDark(frameAt(r, d * i / (n - 1), 64));
            } catch (Throwable t) {
                java.util.Arrays.fill(black, false);
            } finally {
                try {
                    r.release();
                } catch (Throwable ignored) {
                }
            }
            int a = 0;
            while (a < n && black[a]) a++;
            int z = n - 1;
            while (z >= 0 && black[z]) z--;
            final boolean none = a >= n || (a == 0 && z == n - 1);
            final long s = a == 0 ? 0 : d * (a - 1) / (n - 1);
            final long e = z == n - 1 ? d : d * (z + 1) / (n - 1);
            runOnUiThread(() -> {
                aiBusy = false;
                if (isDestroyed()) return;
                if (none || e - s < 300) {
                    Toast.makeText(this, R.string.v_trim_none, Toast.LENGTH_SHORT).show();
                    return;
                }
                trim.setRange(s, e);
                player.seekTo(s);
                updateRange();
                dirty = true;
            });
        }).start();
    }

    // ------------------------------------------------------------------ export

    private void export() {
        if (exporting) return;
        player.pause();
        long s = trim.start(), e = trim.end();
        if (e - s < 300) {
            Toast.makeText(this, R.string.trim_too_short, Toast.LENGTH_SHORT).show();
            return;
        }
        exporting = true;
        final File out = MediaSaver.tempVideo(this);
        MediaItem item = new MediaItem.Builder().setUri(media.uri)
                .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(s).setEndPositionMs(e).build())
                .build();
        EditedMediaItem em = new EditedMediaItem.Builder(item)
                .setRemoveAudio(mute)
                .setEffects(new Effects(new ArrayList<AudioProcessor>(), effects()))
                .build();
        transformer = new Transformer.Builder(this)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .addListener(new Transformer.Listener() {
                    @Override
                    public void onCompleted(Composition composition, ExportResult result) {
                        finishExport(out, null);
                    }

                    @Override
                    public void onError(Composition composition, ExportResult result, ExportException ex) {
                        finishExport(out, ex);
                    }
                }).build();

        LinearLayout box = Ui.box(this);
        progressText = new TextView(this);
        progressText.setTextColor(Ui.color(this, R.color.text_secondary));
        progressText.setText("0%");
        box.addView(progressText);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        Ui.tint(this, progressBar);
        box.addView(progressBar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 16)));
        progressDialog = new Dlg(this).setTitle(R.string.exporting).setView(box).setCancelable(false)
                .setNegativeButton(R.string.cancel, (d, w) -> {
                    if (transformer != null) transformer.cancel();
                    exporting = false;
                    out.delete();
                }).create();
        progressDialog.show();
        try {
            transformer.start(em, out.getAbsolutePath());
        } catch (Throwable t) {
            finishExport(out, t);
            return;
        }
        final ProgressHolder holder = new ProgressHolder();
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!exporting || transformer == null) return;
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    progressBar.setProgress(holder.progress);
                    progressText.setText(holder.progress + "%");
                }
                ui.postDelayed(this, 300);
            }
        }, 300);
    }

    private void finishExport(final File tmp, final Throwable error) {
        ui.post(() -> {
            if (!exporting) return;   // cancelled
            exporting = false;
            if (progressDialog != null) progressDialog.dismiss();
            if (error != null) {
                tmp.delete();
                String why = error instanceof ExportException ? ((ExportException) error).getErrorCodeName() : error.getClass().getSimpleName();
                Toast.makeText(this, getString(R.string.export_failed) + " (" + why + ")", Toast.LENGTH_LONG).show();
                return;
            }
            new Thread(() -> {
                try {
                    MediaSaver.publishVideo(this, tmp);
                    runOnUiThread(() -> {
                        Toast.makeText(this, R.string.saved_copy, Toast.LENGTH_LONG).show();
                        Library.reload(this);
                        finish();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, R.string.export_failed, Toast.LENGTH_LONG).show());
                }
            }).start();
        });
    }

    private void askLeave() {
        if (exporting) return;
        if (!dirty) {
            finish();
            return;
        }
        new Dlg(this).setTitle(R.string.discard_title).setMessage(R.string.discard_msg)
                .setPositiveButton(R.string.discard, (d, w) -> finish())
                .setNegativeButton(R.string.keep_editing, null).show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) player.pause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (transformer != null && exporting) transformer.cancel();
        exporting = false;
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
