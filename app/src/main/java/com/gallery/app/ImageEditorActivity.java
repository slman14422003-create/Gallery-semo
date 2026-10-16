package com.gallery.app;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
 * Photo editor in the iOS Photos layout: Cancel / undo / redo / Done on top, the picture in the middle, a tool
 * panel and a round-icon toolbar at the bottom. Tools: crop with straighten ruler, adjust dials, filters,
 * markup and text. Saves a copy; the original is never touched.
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

    private static final int MODE_TOOL = 1, MODE_TEXT = 2;

    private Media media;
    private Bitmap preview;
    private Bitmap transformed;
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
    private float aspectVal = 0f;
    private int adjSel = 0;
    private final List<TextView> aspectChips = new ArrayList<>();
    private final LinearLayout[] tabViews = new LinearLayout[5];
    private SpinnerView spinner;

    private static final int DRAFT_SIDE = 720;

    private static final class Frame {
        final Bitmap shown, base;
        final ColorMatrix tone;
        final boolean draft;

        Frame(Bitmap shown, Bitmap base, ColorMatrix tone, boolean draft) {
            this.shown = shown;
            this.base = base;
            this.tone = tone;
            this.draft = draft;
        }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable finalPass = () -> submit(false);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
    private volatile int gen = 0;
    private Bitmap cropped, draftSrc, draftBmp;
    private float ccl = -1f, cct, ccr, ccb;

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

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        LinearLayout left = new LinearLayout(this);
        left.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        TextView cancel = Ios.navText(this, getString(R.string.cancel), false);
        cancel.setOnClickListener(v -> onBackPressedDispatcher());
        left.addView(cancel);
        head.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout mid = new LinearLayout(this);
        mid.setGravity(Gravity.CENTER);
        mid.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        undoBtn = Ios.navIcon(this, R.drawable.ic_undo, R.string.undo, v -> undo());
        undoBtn.setAlpha(0.35f);
        redoBtn = Ios.navIcon(this, R.drawable.ic_redo, R.string.redo, v -> redo());
        redoBtn.setAlpha(0.35f);
        mid.addView(undoBtn, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        mid.addView(redoBtn, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        head.addView(mid, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        saveBtn = Ios.navText(this, getString(R.string.done), true);
        saveBtn.setOnClickListener(v -> save());
        right.addView(saveBtn);
        head.addView(right, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(head, new LinearLayout.LayoutParams(-1, Ui.dp(this, 52)));

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
        panel.setMinimumHeight(Ui.dp(this, 168));
        root.addView(panel, new LinearLayout.LayoutParams(-1, -2));

        root.addView(Ios.hairline(this));
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 8), Ui.dp(this, 2), Ui.dp(this, 8), Ui.dp(this, 2));
        int[] icons = {R.drawable.ic_crop, R.drawable.ic_adjust, R.drawable.ic_palette, R.drawable.ic_brush, R.drawable.ic_text};
        int[] names = {R.string.tab_crop, R.string.tab_adjust, R.string.tab_filters, R.string.tab_draw, R.string.tab_text};
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            LinearLayout it = Ios.toolItem(this, icons[i], getString(names[i]));
            it.setOnClickListener(v -> selectTab(idx));
            tabViews[i] = it;
            bar.addView(it, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

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
                    if (isDestroyed()) return;
                    preview = b;
                    spinner.setVisibility(View.GONE);
                    selectTab(1);
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
        ui.removeCallbacksAndMessages(null);
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
        g.setStroke(Ios.hairPx(this), Ui.color(this, R.color.stroke));
        cmp.setBackground(g);
        cmp.setElevation(Ui.dp(this, 2));
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
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40), Gravity.BOTTOM | Gravity.START);
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

    // ------------------------------------------------------------------ rendering

    private Frame compute(EditState snap, int curTab, boolean draft) {
        lock.lock();
        try {
            int key = (snap.rot * 4 + (snap.flipH ? 1 : 0) + (snap.flipV ? 2 : 0)) + 16 * Math.round(snap.angle * 10f);
            if (transformed == null || key != tKey) {
                transformed = Renderer.transform(preview, snap);
                tKey = key;
                cropped = null;
                draftSrc = null;
                draftBmp = null;
            }
            Bitmap base;
            if (curTab == 0) {
                base = transformed;
            } else {
                if (cropped == null || ccl != snap.cl || cct != snap.ct || ccr != snap.cr || ccb != snap.cb) {
                    cropped = Renderer.crop(transformed, snap);
                    ccl = snap.cl;
                    cct = snap.ct;
                    ccr = snap.cr;
                    ccb = snap.cb;
                    draftSrc = null;
                    draftBmp = null;
                }
                base = cropped;
            }
            if (!snap.heavy()) return new Frame(base, base, snap.toneChanged() ? Renderer.matrix(snap) : null, false);
            Bitmap src = draft ? draftOf(base) : base;
            return new Frame(Renderer.color(src, snap), base, null, src != base);
        } finally {
            lock.unlock();
        }
    }

    private Bitmap draftOf(Bitmap base) {
        int side = Math.max(base.getWidth(), base.getHeight());
        if (side <= DRAFT_SIDE) return base;
        if (draftSrc != base || draftBmp == null) {
            float k = DRAFT_SIDE / (float) side;
            draftBmp = Bitmap.createScaledBitmap(base, Math.max(2, Math.round(base.getWidth() * k)), Math.max(2, Math.round(base.getHeight() * k)), true);
            draftSrc = base;
        }
        return draftBmp;
    }

    private void rebuild() {
        if (preview == null || ev == null) return;
        if (st.heavy() || !lock.tryLock()) {
            rebuildAsync();
            return;
        }
        try {
            gen++;
            ui.removeCallbacks(finalPass);
            Frame f = compute(st, tab, false);
            ev.set(f.shown, st, f.base, f.tone);
        } finally {
            lock.unlock();
        }
    }

    private void rebuildAsync() {
        if (preview == null || ev == null) return;
        ui.removeCallbacks(finalPass);
        submit(true);
    }

    private void submit(final boolean draft) {
        if (preview == null || ev == null) return;
        final int g = ++gen;
        final EditState snap = st.copy();
        final int curTab = tab;
        try {
            worker.execute(() -> {
                if (g != gen) return;
                try {
                    final Frame f = compute(snap, curTab, draft);
                    runOnUiThread(() -> {
                        if (g != gen || isDestroyed()) return;
                        ev.set(f.shown, st, f.base, f.tone);
                        if (f.draft) ui.postDelayed(finalPass, 240);
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
        if (tab >= 0 && idx != tab && tabViews[idx] != null) tabViews[idx].performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        tab = idx;
        for (int i = 0; i < 5; i++) Ios.setToolSelected(this, tabViews[i], i == idx);
        ev.setGrid(false);
        ev.setMode(idx == 0 ? EditorView.CROP : idx == 3 ? EditorView.DRAW : idx == 4 ? EditorView.TEXT : EditorView.NONE);
        ev.setBrush(brushColor, brushW);
        ev.setTool(drawTool);
        if (idx == 0) ev.setAspectLock(aspectVal);
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

    private HorizontalScrollView hscroll(LinearLayout content) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hs.addView(content);
        return hs;
    }

    // --- crop, rotate, straighten

    private void rotateCrop(boolean cw) {
        float l = st.cl, t = st.ct, r = st.cr, b = st.cb;
        if (cw) {
            st.cl = 1f - b;
            st.ct = l;
            st.cr = 1f - t;
            st.cb = r;
        } else {
            st.cl = t;
            st.ct = 1f - r;
            st.cr = b;
            st.cb = 1f - l;
        }
    }

    private void rotate(boolean cw) {
        push();
        st.rot = (st.rot + (cw ? 1 : 3)) % 4;
        rotateCrop(cw);
        if (aspectVal > 0f) aspectVal = 1f / aspectVal;
        ev.setAspectLock(aspectVal);
        rebuild();
        refreshAspectChips();
    }

    /** Flips what is shown on screen: with a quarter turn applied, the stored flip axis is the other one. */
    private void flip(boolean horizontal) {
        push();
        boolean odd = st.rot % 2 == 1;
        if (horizontal != odd) st.flipH = !st.flipH;
        else st.flipV = !st.flipV;
        st.angle = -st.angle;
        float l = st.cl, t = st.ct, r = st.cr, b = st.cb;
        if (horizontal) {
            st.cl = 1f - r;
            st.cr = 1f - l;
        } else {
            st.ct = 1f - b;
            st.cb = 1f - t;
        }
        selectTab(0);
    }

    private View cropPanel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 12), Ui.dp(this, 2), Ui.dp(this, 12), Ui.dp(this, 4));
        LinearLayout ops = row();
        ops.setGravity(Gravity.CENTER);
        ops.addView(cropIcon(R.drawable.ic_rotate_left, R.string.rot_left, v -> rotate(false), 0f));
        ops.addView(cropIcon(R.drawable.ic_rotate, R.string.rot_right, v -> rotate(true), 0f));
        ops.addView(cropIcon(R.drawable.ic_flip, R.string.flip_h, v -> flip(true), 0f));
        ops.addView(cropIcon(R.drawable.ic_flip, R.string.flip_v, v -> flip(false), 90f));
        TextView auto = Ui.chip(this, getString(R.string.ad_straight), false);
        auto.setOnClickListener(v -> autoStraighten());
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2);
        ap.setMarginStart(Ui.dp(this, 8));
        ops.addView(auto, ap);
        box.addView(ops);

        final RulerView ruler = new RulerView(this);
        ruler.configure(-45f, 45f, 0f, 90, 5);
        ruler.setFormat(v -> String.format(Locale.US, "%.1f\u00B0", v));
        ruler.setValue(st.angle);
        ruler.setListener(new RulerView.Listener() {
            @Override
            public void onStart() {
                push();
                ev.setGrid(true);
            }

            @Override
            public void onValue(float v) {
                st.angle = v;
                rebuildAsync();
            }

            @Override
            public void onEnd() {
                ev.setGrid(false);
            }
        });
        box.addView(ruler, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout chips = row();
        aspectChips.clear();
        for (int i = 0; i < ASPECTS.length; i++) {
            final int k = i;
            TextView c = Ui.chip(this, getString(ASPECT_NAMES[i]), false);
            c.setOnClickListener(v -> chooseAspect(k));
            aspectChips.add(c);
            chips.addView(c);
        }
        box.addView(hscroll(chips), new LinearLayout.LayoutParams(-1, -2));
        refreshAspectChips();
        return box;
    }

    private ImageButton cropIcon(int icon, int desc, View.OnClickListener c, float rotation) {
        ImageButton b = Ios.panelIcon(this, icon, desc, c);
        b.setRotation(rotation);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42));
        lp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void chooseAspect(int k) {
        if (transformed == null) return;
        push();
        float a = ASPECTS[k];
        if (a < 0f) a = (float) transformed.getWidth() / transformed.getHeight();
        aspectVal = a;
        st.resetCrop();
        if (a > 0f) {
            ev.setAspect(a);
        } else {
            ev.setAspectLock(0f);
            ev.setMode(EditorView.CROP);
        }
        refreshAspectChips();
    }

    private void refreshAspectChips() {
        if (aspectChips.isEmpty()) return;
        float orig = transformed != null ? (float) transformed.getWidth() / transformed.getHeight() : 0f;
        int match = -1;
        if (aspectVal <= 0f) {
            match = 0;
        } else {
            for (int i = 2; i < ASPECTS.length; i++) {
                if (Math.abs(ASPECTS[i] - aspectVal) < 0.005f) {
                    match = i;
                    break;
                }
            }
            if (match < 0 && Math.abs(orig - aspectVal) < 0.005f) match = 1;
        }
        for (int i = 0; i < aspectChips.size(); i++) Ui.setChip(this, aspectChips.get(i), i == match);
    }

    // --- adjust

    private View adjustPanel() {
        AdjustPanel p = new AdjustPanel(this, st, AdjustPanel.imageTools(), new AdjustPanel.Host() {
            @Override
            public void onBegin() {
                push();
            }

            @Override
            public void onChange() {
                rebuildAsync();
            }
        }, adjSel);
        p.addAction(R.drawable.ic_refresh, getString(R.string.ad_reset), () -> {
            if (!st.colorChanged() && st.filter == 0) return;
            push();
            st.resetAdjust();
            st.filter = 0;
            st.filterAmt = 1f;
            adjSel = p.selected();
            selectTab(1);
        });
        p.addAction(R.drawable.ic_wand, getString(R.string.ad_auto), () -> {
            adjSel = p.selected();
            push();
            autoEnhance();
        });
        p.setPadding(0, Ui.dp(this, 2), 0, 0);
        return p;
    }

    /**
     * One tap reads the picture's own pixels (light, colour cast, how soft or grainy it is) and sets the sliders
     * the way a person would. The analysis runs on the worker so a big photo never drops a frame; no network.
     */
    private void autoEnhance() {
        if (preview == null) return;
        final Bitmap snapshot = preview;
        spinner.setVisibility(View.VISIBLE);
        worker.execute(() -> {
            final AutoEnhance.Result r;
            final float[] ai;
            try {
                r = AutoEnhance.analyze(snapshot);
                ai = AiTools.suggest(snapshot);
            } catch (Throwable t) {
                runOnUiThread(() -> spinner.setVisibility(View.GONE));
                return;
            }
            runOnUiThread(() -> {
                if (isDestroyed() || preview != snapshot) {
                    spinner.setVisibility(View.GONE);
                    return;
                }
                st.exposure = 0f;
                st.brightness = r.brightness;
                st.contrast = r.contrast;
                st.saturation = Math.max(st.saturation, r.saturation);
                st.warmth = r.warmth;
                st.tint = r.tint;
                st.sharpen = Math.max(st.sharpen, r.sharpen);
                st.denoise = Math.max(st.denoise, r.denoise);
                st.dehaze = ai[0];
                st.clarity = ai[1];
                st.shadows = ai[2];
                spinner.setVisibility(View.GONE);
                selectTab(1);
            });
        });
    }

    /** Finds the dominant horizon / vertical lines and sets the straighten angle so they come out level. */
    private void autoStraighten() {
        if (preview == null) return;
        final Bitmap snapshot = preview;
        spinner.setVisibility(View.VISIBLE);
        worker.execute(() -> {
            float a = 0f;
            try {
                a = AiTools.straightenAngle(snapshot);
            } catch (Throwable ignored) {
            }
            final float ang = a;
            runOnUiThread(() -> {
                spinner.setVisibility(View.GONE);
                if (isDestroyed() || preview != snapshot) return;
                if (Math.abs(ang) < 0.3f) {
                    Toast.makeText(this, R.string.ad_straight_ok, Toast.LENGTH_SHORT).show();
                    return;
                }
                push();
                st.angle = Math.max(-45f, Math.min(45f, ang));
                selectTab(0);
            });
        });
    }

    // --- filters

    private View filterPanel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout list = row();
        list.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 2));
        Bitmap small = Bitmap.createScaledBitmap(preview, 144, Math.max(1, 144 * preview.getHeight() / preview.getWidth()), true);
        final List<LinearLayout> cells = new ArrayList<>();
        final RulerView ruler = new RulerView(this);
        ruler.configure(0f, 1f, 1f, 50, 10);
        ruler.setFormat(v -> String.valueOf(Math.round(v * 100f)));
        ruler.setValue(st.filterAmt);
        ruler.setListener(new RulerView.Listener() {
            @Override
            public void onStart() {
                push();
            }

            @Override
            public void onValue(float v) {
                st.filterAmt = v;
                rebuildAsync();
            }

            @Override
            public void onEnd() {
            }
        });
        for (int i = 0; i < Renderer.FILTERS; i++) {
            final int k = i;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(Ui.dp(this, 5), Ui.dp(this, 2), Ui.dp(this, 5), Ui.dp(this, 2));
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Bitmap t = Bitmap.createBitmap(small.getWidth(), small.getHeight(), Bitmap.Config.ARGB_8888);
            Paint p = new Paint();
            p.setColorFilter(new ColorMatrixColorFilter(Renderer.preset(i)));
            new Canvas(t).drawBitmap(small, 0, 0, p);
            iv.setImageBitmap(t);
            cell.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 72)));
            TextView n = new TextView(this);
            n.setText(FILTER_NAMES[i]);
            n.setTextSize(11.5f);
            n.setGravity(Gravity.CENTER);
            n.setSingleLine(true);
            n.setPadding(0, Ui.dp(this, 5), 0, 0);
            cell.addView(n);
            cells.add(cell);
            cell.setOnClickListener(v -> {
                push();
                st.filter = k;
                st.filterAmt = 1f;
                ruler.setValue(1f);
                markFilters(cells, ruler);
                rebuild();
            });
            list.addView(cell);
        }
        box.addView(hscroll(list));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = Ui.dp(this, 4);
        box.addView(ruler, rp);
        markFilters(cells, ruler);
        return box;
    }

    private void markFilters(List<LinearLayout> cells, View ruler) {
        for (int i = 0; i < cells.size(); i++) {
            boolean on = i == st.filter;
            Ios.thumbFrame(this, cells.get(i).getChildAt(0), on);
            TextView n = (TextView) cells.get(i).getChildAt(1);
            n.setTextColor(Ui.color(this, on ? R.color.accent_text : R.color.text_secondary));
            n.setTypeface(Typeface.create("sans-serif-medium", on ? Typeface.BOLD : Typeface.NORMAL));
        }
        ruler.setVisibility(st.filter != 0 ? View.VISIBLE : View.INVISIBLE);
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

    private interface FloatCb {
        void on(float v);
    }

    /** Thin iOS slider row used by the markup and text tabs. */
    private SeekBar slider(LinearLayout box, int label, float value, final float min, final float max, final float def,
                           final int mode, final FloatCb cb) {
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
        final int[] last = {sb.getProgress()};
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) return;
                float v = p == defP ? def : min + (max - min) * p / steps;
                if ((last[0] - defP) * (p - defP) < 0 || (p == defP && last[0] != defP)) {
                    s.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                }
                last[0] = p;
                cb.on(v);
                if (mode == MODE_TEXT) ev.invalidate();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                if (mode == MODE_TEXT && ev.active() != null) push();
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        l.setOnClickListener(v -> {
            if (mode == MODE_TEXT && ev.active() != null) push();
            sb.setProgress(defP);
            last[0] = defP;
            cb.on(def);
            if (mode == MODE_TEXT) ev.invalidate();
        });
        r.addView(sb, new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1f));
        box.addView(r);
        return sb;
    }

    // --- draw

    private View drawPanel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 4));
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
        hp.topMargin = Ui.dp(this, 6);
        box.addView(hscroll(colorRow(brushColor, c -> {
            brushColor = c;
            ev.setBrush(brushColor, brushW);
        })), hp);
        slider(box, R.string.brush_size, brushW, 0.003f, 0.04f, 0.012f, MODE_TOOL, v -> {
            brushW = v;
            ev.setBrush(brushColor, brushW);
        });
        return box;
    }

    // --- text

    private View textPanel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 4));
        LinearLayout top = row();
        TextView add = new TextView(this);
        add.setText("+  " + getString(R.string.add_text));
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
        top.addView(add, new LinearLayout.LayoutParams(-2, Ui.dp(this, 36)));
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        ImageButton del = Ios.panelIcon(this, R.drawable.ic_delete, R.string.delete, v -> {
            EditState.Label a = ev.active();
            if (a == null) return;
            push();
            st.labels.remove(a);
            ev.setActive(null);
            ev.invalidate();
        });
        top.addView(del, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        box.addView(top);

        LinearLayout stickers = row();
        for (final String em : VideoFx.EMOJI) {
            TextView c = Ui.chip(this, em, false);
            c.setTextSize(20);
            c.setOnClickListener(v -> {
                EditState.Label l = new EditState.Label();
                l.text = em;
                l.size = 0.18f;
                push();
                st.labels.add(l);
                ev.setActive(l);
            });
            stickers.addView(c);
        }
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(-1, -2);
        stp.topMargin = Ui.dp(this, 4);
        box.addView(hscroll(stickers), stp);

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
                    ev.invalidate();
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
                    ev.invalidate();
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
                ev.invalidate();
            }
        })), hp);
        slider(box, R.string.text_size, textSize, 0.03f, 0.2f, 0.07f, MODE_TEXT, v -> {
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
                    ev.invalidate();
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    // ------------------------------------------------------------------ save

    private static int saveFmt = -1, saveQ = 85, saveSide = 0;
    private static final int[] SIDES = {0, 2560, 1920, 1280, 1024};

    private void save() {
        if (saving || preview == null) return;
        if (!st.changed()) {
            Toast.makeText(this, R.string.nothing_changed, Toast.LENGTH_SHORT).show();
            return;
        }
        if (saveFmt < 0) saveFmt = "image/png".equals(media.mime) ? MediaSaver.PNG : MediaSaver.JPEG;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), Ui.dp(this, 4));
        box.addView(Ui.label(this, getString(R.string.sv_format)));
        final LinearLayout qRow = row();
        box.addView(optionRow(new String[]{"JPEG", "WebP", "PNG"}, saveFmt, i -> {
            saveFmt = i;
            qRow.setVisibility(i == MediaSaver.PNG ? View.GONE : View.VISIBLE);
        }));
        box.addView(Ui.label(this, getString(R.string.sv_size)));
        String[] sn = new String[SIDES.length];
        int sel = 0;
        for (int i = 0; i < SIDES.length; i++) {
            sn[i] = SIDES[i] == 0 ? getString(R.string.cr_original) : String.valueOf(SIDES[i]);
            if (SIDES[i] == saveSide) sel = i;
        }
        box.addView(optionRow(sn, sel, i -> saveSide = SIDES[i]));
        final TextView qv = new TextView(this);
        qv.setTextColor(Ui.color(this, R.color.text_secondary));
        qv.setTextSize(13.5f);
        qv.setText(getString(R.string.sv_quality) + "  " + saveQ + "%");
        SeekBar sb = new SeekBar(this);
        sb.setMax(60);
        sb.setProgress(saveQ - 40);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                saveQ = 40 + p;
                qv.setText(getString(R.string.sv_quality) + "  " + saveQ + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        qRow.setOrientation(LinearLayout.VERTICAL);
        qRow.addView(qv);
        qRow.addView(sb);
        qRow.setVisibility(saveFmt == MediaSaver.PNG ? View.GONE : View.VISIBLE);
        box.addView(qRow);
        new Dlg(this).setTitle(R.string.sv_title).setView(box)
                .setPositiveButton(R.string.save, (d, w) -> doSave())
                .setNegativeButton(R.string.cancel, null).show();
    }

    private HorizontalScrollView optionRow(String[] names, int sel, final java.util.function.IntConsumer cb) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout r = new LinearLayout(this);
        final List<TextView> cs = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            final int k = i;
            TextView c = Ui.chip(this, names[i], i == sel);
            c.setOnClickListener(v -> {
                for (int j = 0; j < cs.size(); j++) Ui.setChip(this, cs.get(j), j == k);
                cb.accept(k);
            });
            cs.add(c);
            r.addView(c);
        }
        hs.addView(r);
        return hs;
    }

    private void doSave() {
        if (saving || preview == null) return;
        saving = true;
        saveBtn.setAlpha(0.5f);
        spinner.setVisibility(View.VISIBLE);
        final EditState snap = st.copy();
        final int fmt = saveFmt, q = saveQ, side = saveSide;
        new Thread(() -> {
            Bitmap result = null;
            try {
                int longest = Math.max(media.w, media.h);
                int[] tries = {Math.min(longest > 0 ? longest : 4096, 6144), 4096, 3072, 2048};
                for (int i = 0; i < tries.length && result == null; i++) {
                    if (i > 0 && tries[i] >= tries[i - 1]) continue;
                    Bitmap src = null;
                    try {
                        src = MediaSaver.load(this, media.uri, tries[i]);
                        result = Renderer.finish(src, snap);
                    } catch (OutOfMemoryError oom) {
                        result = null;
                    } finally {
                        if (src != null) src.recycle();
                    }
                }
                if (result == null) throw new IllegalStateException("render");
                int big = Math.max(result.getWidth(), result.getHeight());
                if (side > 0 && big > side) {
                    float f = (float) side / big;
                    Bitmap small = Bitmap.createScaledBitmap(result, Math.max(1, Math.round(result.getWidth() * f)),
                            Math.max(1, Math.round(result.getHeight() * f)), true);
                    if (small != result) result.recycle();
                    result = small;
                }
                final MediaSaver.Saved sv = MediaSaver.savePicture(this, result, fmt, q);
                runOnUiThread(() -> {
                    Toast.makeText(this, getString(R.string.sv_done, Fmt.size(sv.bytes), Fmt.size(media.size)), Toast.LENGTH_LONG).show();
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
