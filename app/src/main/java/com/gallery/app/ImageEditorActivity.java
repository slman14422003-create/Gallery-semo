package com.gallery.app;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Photo editor (beta 2): crop, straighten and rotate, light and colour (exposure, tint, fade, sharpen, grain...),
 * 14 filters with intensity, drawing (pen, marker, shapes, eraser) and styled text. Saves a copy; the original is never touched.
 */
public class ImageEditorActivity extends BaseActivity implements EditorView.Listener {
    private static final int[] COLORS = {0xFFFFFFFF, 0xFF111111, 0xFFFF3B30, 0xFFFF9500, 0xFFFFD60A, 0xFF34C759, 0xFF0A84FF, 0xFFBF5AF2, 0xFFFF2D92};
    private static final int[] FILTER_NAMES = {R.string.fl_original, R.string.fl_vivid, R.string.fl_warm, R.string.fl_cool,
            R.string.fl_bw, R.string.fl_noir, R.string.fl_sepia, R.string.fl_fade, R.string.fl_dramatic,
            R.string.fl_vintage, R.string.fl_cinema, R.string.fl_sunset, R.string.fl_ocean, R.string.fl_cyan};
    private static final float[] ASPECTS = {0f, -1f, 1f, 4f / 3f, 3f / 4f, 3f / 2f, 2f / 3f, 5f / 4f, 16f / 9f, 9f / 16f};
    private static final int[] ASPECT_NAMES = {R.string.cr_free, R.string.cr_original, R.string.cr_1_1, R.string.cr_4_3, R.string.cr_3_4,
            R.string.cr_3_2, R.string.cr_2_3, R.string.cr_5_4, R.string.cr_16_9, R.string.cr_9_16};
    private static final int[] TOOLS = {EditState.PEN, EditState.MARKER, EditState.LINE, EditState.ARROW, EditState.BOX, EditState.OVAL, EditorView.ERASER};
    private static final int[] TOOL_NAMES = {R.string.dr_pen, R.string.dr_marker, R.string.dr_line, R.string.dr_arrow, R.string.dr_box, R.string.dr_circle, R.string.dr_eraser};
    private static final int[] FONT_NAMES = {R.string.tx_f_sans, R.string.tx_f_serif, R.string.tx_f_mono, R.string.tx_f_script};
    private static final int[] BOX_NAMES = {R.string.tx_bg_none, R.string.tx_bg_dark, R.string.tx_bg_light};

    private static final int MODE_IMAGE = 0, MODE_TOOL = 1, MODE_TEXT = 2;

    private Media media;
    private Bitmap preview;          // source at preview size
    private Bitmap transformed;      // preview after rotate / flip / straighten
    private int tKey = -1;
    private EditState st = new EditState();
    private final List<EditState> undo = new ArrayList<>();
    private final List<EditState> redo = new ArrayList<>();
    private EditorView ev;
    private FrameLayout panel;
    private int tab = -1;
    private ImageButton undoBtn, redoBtn;
    private TextView saveBtn;
    private boolean saving = false;
    private int brushColor = COLORS[0];
    private float brushW = 0.012f;
    private int drawTool = EditState.PEN;
    private int textColor = COLORS[0];
    private float textSize = 0.07f;
    private int textFont = 0;
    private int textBox = 0;
    private final List<TextView> aspectChips = new ArrayList<>();
    private LinearLayout[] tabViews = new LinearLayout[5];
    private SpinnerView spinner;

    // previews are rendered off the UI thread so sliders stay smooth even with sharpen / grain on
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Object lock = new Object();
    private volatile int gen = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        media = Library.editTarget;
        if (media == null || media.video) {
            finish();
            return;
        }
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
        close.setContentDescription(getString(R.string.cancel));
        close.setOnClickListener(v -> onBackPressedDispatcher());
        head.addView(close, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        LinearLayout tl = new LinearLayout(this);
        tl.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(R.string.edit_photo);
        title.setTextColor(Ui.color(this, R.color.text_primary));
        title.setTextSize(17);
        title.setSingleLine(true);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        tl.addView(title, new LinearLayout.LayoutParams(-2, -2));
        tl.addView(betaBadge());
        head.addView(tl, new LinearLayout.LayoutParams(0, -2, 1f));
        undoBtn = new ImageButton(this, null, 0, R.style.IconButton);
        undoBtn.setImageResource(R.drawable.ic_undo);
        undoBtn.setContentDescription(getString(R.string.undo));
        undoBtn.setOnClickListener(v -> undo());
        undoBtn.setAlpha(0.35f);
        head.addView(undoBtn, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        redoBtn = new ImageButton(this, null, 0, R.style.IconButton);
        redoBtn.setImageResource(R.drawable.ic_redo);
        redoBtn.setContentDescription(getString(R.string.redo));
        redoBtn.setOnClickListener(v -> redo());
        redoBtn.setAlpha(0.35f);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40));
        rp.setMarginEnd(Ui.dp(this, 8));
        head.addView(redoBtn, rp);
        saveBtn = new TextView(this);
        saveBtn.setText(R.string.save_copy);
        saveBtn.setTextColor(Ui.color(this, R.color.on_accent));
        saveBtn.setTextSize(14.5f);
        saveBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        saveBtn.setBackgroundResource(R.drawable.btn_primary);
        saveBtn.setGravity(Gravity.CENTER);
        saveBtn.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        saveBtn.setOnClickListener(v -> save());
        head.addView(saveBtn, new LinearLayout.LayoutParams(-2, Ui.dp(this, 38)));
        root.addView(head);

        FrameLayout canvasBox = new FrameLayout(this);
        canvasBox.setBackgroundColor(Ui.color(this, R.color.bg_group));
        ev = new EditorView(this);
        ev.setListener(this);
        canvasBox.addView(ev, new FrameLayout.LayoutParams(-1, -1));
        spinner = new SpinnerView(this);
        canvasBox.addView(spinner, new FrameLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44), Gravity.CENTER));
        canvasBox.addView(compareButton());
        root.addView(canvasBox, new LinearLayout.LayoutParams(-1, 0, 1f));

        panel = new FrameLayout(this);
        root.addView(panel, new LinearLayout.LayoutParams(-1, -2));

        // tab bar (same floating pill as the main navigation)
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundResource(R.drawable.bg_nav_float);
        bar.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
        int[] icons = {R.drawable.ic_crop, R.drawable.ic_adjust, R.drawable.ic_wand, R.drawable.ic_brush, R.drawable.ic_text};
        int[] names = {R.string.tab_crop, R.string.tab_adjust, R.string.tab_filters, R.string.tab_draw, R.string.tab_text};
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            LinearLayout it = new LinearLayout(this);
            it.setGravity(Gravity.CENTER);
            it.setOrientation(LinearLayout.VERTICAL);
            it.setContentDescription(getString(names[i]));
            ImageView ic = new ImageView(this);
            ic.setImageResource(icons[i]);
            it.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setTextSize(10.5f);
            t.setSingleLine(true);
            t.setGravity(Gravity.CENTER);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            it.addView(t);
            Ui.press(this, it);
            it.setOnClickListener(v -> selectTab(idx));
            tabViews[i] = it;
            bar.addView(it, new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1f));
        }
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.setMargins(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 12));
        root.addView(bar, bp);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                onBackPressedDispatcher();
            }
        });

        spinner.setVisibility(View.VISIBLE);
        new Thread(() -> {
            try {
                final Bitmap b = MediaSaver.load(this, media.uri, 1600);
                runOnUiThread(() -> {
                    preview = b;
                    spinner.setVisibility(View.GONE);
                    selectTab(0);
                });
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, R.string.error, Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    /** Floating "hold to compare with the original" button on the canvas. */
    private View compareButton() {
        ImageButton cmp = new ImageButton(this);
        cmp.setImageResource(R.drawable.ic_eye);
        cmp.setImageTintList(ColorStateList.valueOf(Ui.color(this, R.color.text_primary)));
        cmp.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Ui.color(this, R.color.surface));
        g.setStroke(Ui.dp(this, 1), Ui.color(this, R.color.stroke_soft));
        cmp.setBackground(g);
        cmp.setElevation(Ui.dp(this, 3));
        cmp.setContentDescription(getString(R.string.compare_hold));
        cmp.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setScaleX(0.92f);
                    v.setScaleY(0.92f);
                    ev.showOriginal(true);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setScaleX(1f);
                    v.setScaleY(1f);
                    ev.showOriginal(false);
                    break;
                default:
            }
            return true;
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44), Gravity.BOTTOM | Gravity.START);
        lp.setMargins(Ui.dp(this, 14), 0, Ui.dp(this, 14), Ui.dp(this, 12));
        cmp.setLayoutParams(lp);
        return cmp;
    }

    private void onBackPressedDispatcher() {
        if (saving) return;
        if (!st.changed()) {
            finish();
            return;
        }
        new Dlg(this).setTitle(R.string.discard_title).setMessage(R.string.discard_msg)
                .setPositiveButton(R.string.discard, (d, w) -> finish())
                .setNegativeButton(R.string.keep_editing, null).show();
    }

    private TextView betaBadge() {
        TextView b = new TextView(this);
        b.setText(R.string.beta2);
        b.setTextSize(10.5f);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(Ui.color(this, R.color.accent_text));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.dp(this, 20));
        g.setColor((Ui.color(this, R.color.accent_text) & 0x00FFFFFF) | 0x26000000);
        b.setBackground(g);
        b.setPadding(Ui.dp(this, 8), Ui.dp(this, 2), Ui.dp(this, 8), Ui.dp(this, 2));
        return b;
    }

    // ------------------------------------------------------------------ rendering

    private Bitmap[] compute(EditState snap, int curTab) {
        synchronized (lock) {
            int key = (snap.rot * 4 + (snap.flipH ? 1 : 0) + (snap.flipV ? 2 : 0)) + 16 * Math.round(snap.angle * 10f);
            if (transformed == null || key != tKey) {
                transformed = Renderer.transform(preview, snap);
                tKey = key;
            }
            Bitmap base = curTab == 0 ? transformed : Renderer.crop(transformed, snap);
            Bitmap shown = Renderer.color(base, snap);
            return new Bitmap[]{shown, base};
        }
    }

    /** Immediate rebuild (tab switches, taps). */
    private void rebuild() {
        if (preview == null || ev == null) return;
        gen++;
        Bitmap[] r = compute(st, tab);
        ev.set(r[0], st, r[1]);
    }

    /** Rebuild on the worker thread; only the newest request is shown (used while a slider is dragged). */
    private void rebuildAsync() {
        if (preview == null || ev == null) return;
        final int g = ++gen;
        final EditState snap = st.copy();
        final int curTab = tab;
        try {
            worker.execute(() -> {
                if (g != gen) return;
                try {
                    final Bitmap[] r = compute(snap, curTab);
                    runOnUiThread(() -> {
                        if (g == gen && !isDestroyed()) ev.set(r[0], st, r[1]);
                    });
                } catch (Throwable ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private void push() {
        undo.add(st.copy());
        if (undo.size() > 40) undo.remove(0);
        redo.clear();
        syncHistoryButtons();
    }

    private void syncHistoryButtons() {
        undoBtn.setAlpha(undo.isEmpty() ? 0.35f : 1f);
        redoBtn.setAlpha(redo.isEmpty() ? 0.35f : 1f);
    }

    private void undo() {
        if (undo.isEmpty()) return;
        redo.add(st.copy());
        st = undo.remove(undo.size() - 1);
        syncHistoryButtons();
        selectTab(tab);
    }

    private void redo() {
        if (redo.isEmpty()) return;
        undo.add(st.copy());
        st = redo.remove(redo.size() - 1);
        syncHistoryButtons();
        selectTab(tab);
    }

    @Override
    public void onBegin() {
        push();
    }

    @Override
    public void onCommit() {
        if (tab == 3 || tab == 4) ev.invalidate();
    }

    @Override
    public void onLabelTap(EditState.Label l) {
        if (l != null) editLabel(l);
    }

    // ------------------------------------------------------------------ tabs

    private void selectTab(int idx) {
        if (preview == null) return;
        tab = idx;
        for (int i = 0; i < 5; i++) {
            boolean on = i == idx;
            tabViews[i].setBackgroundResource(on ? R.drawable.bg_nav_active : 0);
            int col = Ui.color(this, on ? R.color.on_accent : R.color.text_secondary);
            ((ImageView) tabViews[i].getChildAt(0)).setImageTintList(ColorStateList.valueOf(col));
            ((TextView) tabViews[i].getChildAt(1)).setTextColor(col);
        }
        ev.setMode(idx == 0 ? EditorView.CROP : idx == 3 ? EditorView.DRAW : idx == 4 ? EditorView.TEXT : EditorView.NONE);
        ev.setBrush(brushColor, brushW);
        ev.setTool(drawTool);
        panel.removeAllViews();
        try {
            View p = idx == 0 ? cropPanel() : idx == 1 ? adjustPanel() : idx == 2 ? filterPanel() : idx == 3 ? drawPanel() : textPanel();
            panel.addView(p);
            rebuild();
        } catch (Throwable t) {
            Toast.makeText(this, getString(R.string.error) + ": " + t.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private ImageButton toolIcon(int icon, int desc, View.OnClickListener c) {
        ImageButton b = new ImageButton(this, null, 0, R.style.IconButton);
        b.setImageResource(icon);
        b.setContentDescription(getString(desc));
        b.setOnClickListener(c);
        return b;
    }

    private HorizontalScrollView hscroll(LinearLayout content) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hs.addView(content);
        return hs;
    }

    // --- crop & rotate

    private View cropPanel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 16), Ui.dp(this, 2), Ui.dp(this, 16), Ui.dp(this, 4));
        LinearLayout ops = row();
        ops.setGravity(Gravity.CENTER);
        ops.addView(toolIcon(R.drawable.ic_rotate_left, R.string.rot_left, v -> {
            push();
            st.rot = (st.rot + 3) % 4;
            st.resetCrop();
            rebuild();
        }), new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 44)));
        ops.addView(toolIcon(R.drawable.ic_rotate, R.string.rot_right, v -> {
            push();
            st.rot = (st.rot + 1) % 4;
            st.resetCrop();
            rebuild();
        }), new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 44)));
        ops.addView(toolIcon(R.drawable.ic_flip, R.string.flip_h, v -> {
            push();
            st.flipH = !st.flipH;
            st.resetCrop();
            rebuild();
        }), new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 44)));
        ImageButton fv = toolIcon(R.drawable.ic_flip, R.string.flip_v, v -> {
            push();
            st.flipV = !st.flipV;
            st.resetCrop();
            rebuild();
        });
        fv.setRotation(90f);
        ops.addView(fv, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 44)));
        box.addView(ops);
        slider(box, R.string.cr_straighten, st.angle, -45f, 45f, 0f, 1f, "°", MODE_IMAGE, v -> {
            st.angle = v;
            st.resetCrop();
        });
        LinearLayout chips = row();
        aspectChips.clear();
        for (int i = 0; i < ASPECTS.length; i++) {
            final int k = i;
            TextView c = Ui.chip(this, getString(ASPECT_NAMES[i]), i == 0);
            c.setOnClickListener(v -> {
                for (int j = 0; j < aspectChips.size(); j++) Ui.setChip(this, aspectChips.get(j), j == k);
                push();
                float a = ASPECTS[k];
                if (a < 0) a = (float) transformed.getWidth() / transformed.getHeight();
                st.resetCrop();
                ev.setAspect(a);
                if (a == 0) {
                    ev.setAspect(0);
                    ev.setMode(EditorView.CROP);
                }
            });
            aspectChips.add(c);
            chips.addView(c);
        }
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = Ui.dp(this, 2);
        box.addView(hscroll(chips), hp);
        return box;
    }

    // --- adjust

    private View adjustPanel() {
        ScrollView sv = new ScrollView(this);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 16), Ui.dp(this, 2), Ui.dp(this, 16), Ui.dp(this, 4));
        LinearLayout top = row();
        TextView auto = Ui.chip(this, "✦  " + getString(R.string.ad_auto), false);
        auto.setOnClickListener(v -> {
            push();
            autoEnhance();
            selectTab(1);
        });
        top.addView(auto);
        TextView reset = Ui.chip(this, getString(R.string.ad_reset), false);
        reset.setOnClickListener(v -> {
            if (!st.colorChanged()) return;
            push();
            st.resetAdjust();
            st.filter = 0;
            st.filterAmt = 1f;
            selectTab(1);
        });
        top.addView(reset);
        box.addView(top);
        slider(box, R.string.ad_exposure, st.exposure, -1f, 1f, 0f, 100f, "", MODE_IMAGE, v -> st.exposure = v);
        slider(box, R.string.ad_brightness, st.brightness, -1f, 1f, 0f, 100f, "", MODE_IMAGE, v -> st.brightness = v);
        slider(box, R.string.ad_contrast, st.contrast, -1f, 1f, 0f, 100f, "", MODE_IMAGE, v -> st.contrast = v);
        slider(box, R.string.ad_saturation, st.saturation, -1f, 1f, 0f, 100f, "", MODE_IMAGE, v -> st.saturation = v);
        slider(box, R.string.ad_warmth, st.warmth, -1f, 1f, 0f, 100f, "", MODE_IMAGE, v -> st.warmth = v);
        slider(box, R.string.ad_tint, st.tint, -1f, 1f, 0f, 100f, "", MODE_IMAGE, v -> st.tint = v);
        slider(box, R.string.ad_fade, st.fade, 0f, 1f, 0f, 100f, "", MODE_IMAGE, v -> st.fade = v);
        slider(box, R.string.ad_sharpen, st.sharpen, 0f, 1f, 0f, 100f, "", MODE_IMAGE, v -> st.sharpen = v);
        slider(box, R.string.ad_grain, st.grain, 0f, 1f, 0f, 100f, "", MODE_IMAGE, v -> st.grain = v);
        slider(box, R.string.ad_vignette, st.vignette, 0f, 1f, 0f, 100f, "", MODE_IMAGE, v -> st.vignette = v);
        sv.addView(box);
        sv.setLayoutParams(new FrameLayout.LayoutParams(-1, Ui.dp(this, 214)));
        return sv;
    }

    /** One-tap "make it look right": brightness and contrast from the picture's own histogram, a touch of colour and sharpness. */
    private void autoEnhance() {
        int w = 64, h = Math.max(1, 64 * preview.getHeight() / Math.max(1, preview.getWidth()));
        Bitmap s = Bitmap.createScaledBitmap(preview, w, h, true);
        int[] px = new int[w * h];
        s.getPixels(px, 0, w, 0, 0, w, h);
        double sum = 0, sum2 = 0;
        for (int p : px) {
            double y = 0.299 * ((p >> 16) & 255) + 0.587 * ((p >> 8) & 255) + 0.114 * (p & 255);
            sum += y;
            sum2 += y * y;
        }
        double mean = sum / px.length;
        double sd = Math.sqrt(Math.max(1.0, sum2 / px.length - mean * mean));
        st.exposure = 0f;
        st.brightness = (float) Math.max(-0.3, Math.min(0.3, (120 - mean) * 0.5 / 90.0));
        float f = (float) Math.max(0.9, Math.min(1.35, 58.0 / sd));
        st.contrast = Math.max(-0.4f, Math.min(0.4f, (f - 1f) / 0.8f));
        st.saturation = Math.max(st.saturation, 0.12f);
        st.sharpen = Math.max(st.sharpen, 0.2f);
    }

    private interface FloatCb {
        void on(float v);
    }

    private static String fmtVal(float v, float disp, String suffix) {
        if ("°".equals(suffix)) return String.format(Locale.US, "%.1f°", v);
        return String.valueOf(Math.round(v * disp));
    }

    /**
     * iOS-style slider row. Tapping the label puts the slider back to its default; the knob ticks when it crosses the default.
     * {@code mode}: image adjustment (undo step + live re-render), plain tool value, or text size.
     */
    private SeekBar slider(LinearLayout box, int label, float value, final float min, final float max, final float def,
                           final float disp, final String suffix, final int mode, final FloatCb cb) {
        final int steps = 200;
        LinearLayout r = row();
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(Ui.color(this, R.color.text_secondary));
        l.setTextSize(13.5f);
        l.setSingleLine(true);
        r.addView(l, new LinearLayout.LayoutParams(Ui.dp(this, 92), -2));
        final SeekBar sb = new SeekBar(this);
        sb.setMax(steps);
        final int defP = Math.round((def - min) / (max - min) * steps);
        sb.setProgress(Math.round((value - min) / (max - min) * steps));
        final TextView num = new TextView(this);
        num.setTextColor(Ui.color(this, R.color.text_hint));
        num.setTextSize(12.5f);
        num.setGravity(Gravity.CENTER);
        num.setText(fmtVal(value, disp, suffix));
        final int[] last = {sb.getProgress()};
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                float v = p == defP ? def : min + (max - min) * p / steps;
                num.setText(fmtVal(v, disp, suffix));
                if (!user) return;
                if ((last[0] - defP) * (p - defP) < 0 || (p == defP && last[0] != defP)) {
                    s.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                }
                last[0] = p;
                cb.on(v);
                if (mode == MODE_IMAGE) rebuildAsync();
                else if (mode == MODE_TEXT) ev.invalidate();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                if (mode == MODE_IMAGE || (mode == MODE_TEXT && ev.active() != null)) push();
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        l.setOnClickListener(v -> {
            if (mode == MODE_IMAGE || (mode == MODE_TEXT && ev.active() != null)) push();
            sb.setProgress(defP);
            last[0] = defP;
            cb.on(def);
            num.setText(fmtVal(def, disp, suffix));
            if (mode == MODE_IMAGE) rebuildAsync();
            else if (mode == MODE_TEXT) ev.invalidate();
        });
        r.addView(sb, new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1f));
        r.addView(num, new LinearLayout.LayoutParams(Ui.dp(this, 42), -2));
        box.addView(r);
        return sb;
    }

    // --- filters

    private View filterPanel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = row();
        row.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 2));
        Bitmap small = Bitmap.createScaledBitmap(preview, 128, Math.max(1, 128 * preview.getHeight() / preview.getWidth()), true);
        final List<LinearLayout> cells = new ArrayList<>();
        final View[] intensityRow = new View[1];
        final SeekBar[] intensity = new SeekBar[1];
        for (int i = 0; i < Renderer.FILTERS; i++) {
            final int k = i;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(Ui.dp(this, 5), Ui.dp(this, 4), Ui.dp(this, 5), Ui.dp(this, 4));
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Bitmap t = Bitmap.createBitmap(small.getWidth(), small.getHeight(), Bitmap.Config.ARGB_8888);
            Paint p = new Paint();
            p.setColorFilter(new android.graphics.ColorMatrixColorFilter(Renderer.preset(i)));
            new Canvas(t).drawBitmap(small, 0, 0, p);
            iv.setImageBitmap(t);
            iv.setClipToOutline(true);
            iv.setBackgroundResource(R.drawable.bg_tile);
            cell.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 70), Ui.dp(this, 70)));
            TextView n = new TextView(this);
            n.setText(FILTER_NAMES[i]);
            n.setTextSize(11.5f);
            n.setGravity(Gravity.CENTER);
            n.setSingleLine(true);
            n.setPadding(0, Ui.dp(this, 4), 0, 0);
            cell.addView(n);
            cells.add(cell);
            cell.setOnClickListener(v -> {
                push();
                st.filter = k;
                st.filterAmt = 1f;
                if (intensity[0] != null) intensity[0].setProgress(200);
                markFilters(cells, intensityRow[0]);
                rebuild();
            });
            row.addView(cell);
        }
        box.addView(hscroll(row));
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 4));
        intensity[0] = slider(wrap, R.string.fl_intensity, st.filterAmt, 0f, 1f, 1f, 100f, "", MODE_IMAGE, v -> st.filterAmt = v);
        intensityRow[0] = (View) intensity[0].getParent();
        box.addView(wrap);
        markFilters(cells, intensityRow[0]);
        return box;
    }

    private void markFilters(List<LinearLayout> cells, View intensityRow) {
        for (int i = 0; i < cells.size(); i++) {
            boolean on = i == st.filter;
            TextView n = (TextView) cells.get(i).getChildAt(1);
            n.setTextColor(Ui.color(this, on ? R.color.accent_text : R.color.text_secondary));
            n.setTypeface(Typeface.create("sans-serif-medium", on ? Typeface.BOLD : Typeface.NORMAL));
            cells.get(i).setAlpha(on ? 1f : 0.82f);
        }
        if (intensityRow != null) {
            boolean show = st.filter != 0;
            intensityRow.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        }
    }

    // --- shared colour dots

    private LinearLayout colorRow(final int current, final java.util.function.IntConsumer on) {
        LinearLayout r = row();
        r.setGravity(Gravity.CENTER);
        final List<View> dots = new ArrayList<>();
        for (final int col : COLORS) {
            View d = new View(this);
            d.setBackground(dot(col, col == current));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 30));
            lp.setMargins(Ui.dp(this, 5), 0, Ui.dp(this, 5), 0);
            d.setOnClickListener(v -> {
                on.accept(col);
                for (int i = 0; i < dots.size(); i++) dots.get(i).setBackground(dot(COLORS[i], COLORS[i] == col));
            });
            dots.add(d);
            r.addView(d, lp);
        }
        return r;
    }

    private GradientDrawable dot(int col, boolean selected) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(col);
        g.setStroke(Ui.dp(this, selected ? 3 : 1), selected ? Ui.color(this, R.color.accent) : 0x55888888);
        return g;
    }

    // --- draw

    private View drawPanel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 4));
        LinearLayout tools = row();
        final List<TextView> toolChips = new ArrayList<>();
        for (int i = 0; i < TOOLS.length; i++) {
            final int k = TOOLS[i];
            TextView c = Ui.chip(this, getString(TOOL_NAMES[i]), k == drawTool);
            c.setOnClickListener(v -> {
                drawTool = k;
                ev.setTool(k);
                for (int j = 0; j < toolChips.size(); j++) Ui.setChip(this, toolChips.get(j), TOOLS[j] == k);
            });
            toolChips.add(c);
            tools.addView(c);
        }
        box.addView(hscroll(tools));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = Ui.dp(this, 2);
        box.addView(hscroll(colorRow(brushColor, c -> {
            brushColor = c;
            ev.setBrush(brushColor, brushW);
        })), hp);
        slider(box, R.string.brush_size, brushW, 0.003f, 0.04f, 0.012f, 1000f, "", MODE_TOOL, v -> {
            brushW = v;
            ev.setBrush(brushColor, brushW);
        });
        return box;
    }

    // --- text

    private View textPanel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 4));
        LinearLayout top = row();
        TextView add = new TextView(this);
        add.setText("＋  " + getString(R.string.add_text));
        add.setTextColor(Ui.color(this, R.color.on_accent));
        add.setTextSize(14);
        add.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        add.setBackgroundResource(R.drawable.btn_primary);
        add.setGravity(Gravity.CENTER);
        add.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), 0);
        add.setOnClickListener(v -> {
            EditState.Label l = new EditState.Label();
            l.color = textColor;
            l.size = textSize;
            l.font = textFont;
            l.box = textBox;
            editLabel(l);
        });
        top.addView(add, new LinearLayout.LayoutParams(-2, Ui.dp(this, 38)));
        View sp = new View(this);
        top.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        top.addView(toolIcon(R.drawable.ic_delete, R.string.delete, v -> {
            EditState.Label a = ev.active();
            if (a == null) return;
            push();
            st.labels.remove(a);
            ev.setActive(null);
            rebuild();
        }), new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));
        box.addView(top);

        // font + background style
        LinearLayout styles = row();
        final List<TextView> fontChips = new ArrayList<>();
        final List<TextView> boxChips = new ArrayList<>();
        for (int i = 0; i < FONT_NAMES.length; i++) {
            final int k = i;
            TextView c = Ui.chip(this, getString(FONT_NAMES[i]), k == textFont);
            c.setTypeface(Renderer.labelFace(k));
            c.setOnClickListener(v -> {
                textFont = k;
                EditState.Label a = ev.active();
                if (a != null) {
                    push();
                    a.font = k;
                    rebuild();
                }
                for (int j = 0; j < fontChips.size(); j++) Ui.setChip(this, fontChips.get(j), j == k);
            });
            fontChips.add(c);
            styles.addView(c);
        }
        for (int i = 0; i < BOX_NAMES.length; i++) {
            final int k = i;
            TextView c = Ui.chip(this, getString(BOX_NAMES[i]), k == textBox);
            c.setOnClickListener(v -> {
                textBox = k;
                EditState.Label a = ev.active();
                if (a != null) {
                    push();
                    a.box = k;
                    rebuild();
                }
                for (int j = 0; j < boxChips.size(); j++) Ui.setChip(this, boxChips.get(j), j == k);
            });
            boxChips.add(c);
            styles.addView(c);
        }
        LinearLayout.LayoutParams sp2 = new LinearLayout.LayoutParams(-1, -2);
        sp2.topMargin = Ui.dp(this, 2);
        box.addView(hscroll(styles), sp2);

        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = Ui.dp(this, 4);
        box.addView(hscroll(colorRow(textColor, c -> {
            textColor = c;
            EditState.Label a = ev.active();
            if (a != null) {
                push();
                a.color = c;
                rebuild();
            }
        })), hp);
        slider(box, R.string.text_size, textSize, 0.03f, 0.2f, 0.07f, 1000f, "", MODE_TEXT, v -> {
            textSize = v;
            EditState.Label a = ev.active();
            if (a != null) a.size = v;
        });
        return box;
    }

    private void editLabel(final EditState.Label l) {
        final boolean isNew = !st.labels.contains(l);
        final EditText et = Ui.edit(this, getString(R.string.text_hint), l.text);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setSingleLine(true);
        LinearLayout box = Ui.box(this);
        box.addView(et);
        new Dlg(this).setTitle(R.string.add_text).setView(box)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    String t = et.getText().toString().trim();
                    push();
                    if (t.isEmpty()) {
                        st.labels.remove(l);
                    } else {
                        l.text = t;
                        if (isNew) st.labels.add(l);
                    }
                    ev.setActive(t.isEmpty() ? null : l);
                    rebuild();
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    // ------------------------------------------------------------------ save

    private void save() {
        if (saving || preview == null) return;
        if (!st.changed()) {
            Toast.makeText(this, R.string.nothing_changed, Toast.LENGTH_SHORT).show();
            return;
        }
        saving = true;
        saveBtn.setAlpha(0.5f);
        spinner.setVisibility(View.VISIBLE);
        final EditState snap = st.copy();
        new Thread(() -> {
            Bitmap result = null;
            try {
                Bitmap src;
                try {
                    src = MediaSaver.load(this, media.uri, 4096);
                } catch (OutOfMemoryError oom) {
                    src = MediaSaver.load(this, media.uri, 2560);
                }
                result = Renderer.finish(src, snap);
                boolean png = "image/png".equals(media.mime);
                final android.net.Uri u = MediaSaver.savePicture(this, result, png);
                runOnUiThread(() -> {
                    Toast.makeText(this, R.string.saved_copy, Toast.LENGTH_LONG).show();
                    Library.reload(this);
                    finish();
                });
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    saving = false;
                    saveBtn.setAlpha(1f);
                    spinner.setVisibility(View.GONE);
                    Toast.makeText(this, R.string.error, Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }
}
