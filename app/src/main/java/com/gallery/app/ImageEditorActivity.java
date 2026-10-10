package com.gallery.app;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
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

/** Photo editor (beta): crop and rotate, light and colour, filters, drawing and text. Saves a copy; the original is never touched. */
public class ImageEditorActivity extends BaseActivity implements EditorView.Listener {
    private static final int[] COLORS = {0xFFFFFFFF, 0xFF111111, 0xFFFF3B30, 0xFFFF9500, 0xFFFFD60A, 0xFF34C759, 0xFF0A84FF, 0xFFBF5AF2, 0xFFFF2D92};
    private static final int[] FILTER_NAMES = {R.string.fl_original, R.string.fl_vivid, R.string.fl_warm, R.string.fl_cool,
            R.string.fl_bw, R.string.fl_noir, R.string.fl_sepia, R.string.fl_fade, R.string.fl_dramatic};
    private static final float[] ASPECTS = {0f, -1f, 1f, 4f / 3f, 3f / 4f, 16f / 9f, 9f / 16f};
    private static final int[] ASPECT_NAMES = {R.string.cr_free, R.string.cr_original, R.string.cr_1_1, R.string.cr_4_3, R.string.cr_3_4, R.string.cr_16_9, R.string.cr_9_16};

    private Media media;
    private Bitmap preview;          // source at preview size
    private Bitmap transformed;      // preview after rotate / flip
    private int tKey = -1;
    private EditState st = new EditState();
    private final List<EditState> undo = new ArrayList<>();
    private EditorView ev;
    private FrameLayout panel;
    private int tab = -1;
    private ImageButton undoBtn;
    private TextView saveBtn;
    private boolean saving = false;
    private int brushColor = COLORS[0];
    private float brushW = 0.012f;
    private int textColor = COLORS[0];
    private float textSize = 0.07f;
    private final List<TextView> aspectChips = new ArrayList<>();
    private LinearLayout[] tabViews = new LinearLayout[5];
    private SpinnerView spinner;

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
        head.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 6));
        ImageButton close = new ImageButton(this, null, 0, R.style.IconButton);
        close.setImageResource(R.drawable.ic_cancel);
        close.setOnClickListener(v -> onBackPressedDispatcher());
        head.addView(close, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        LinearLayout tl = new LinearLayout(this);
        tl.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(R.string.edit_photo);
        title.setTextColor(Ui.color(this, R.color.text_primary));
        title.setTextSize(19);
        title.setTypeface(android.graphics.Typeface.SERIF);
        title.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 8), 0);
        tl.addView(title);
        tl.addView(betaBadge());
        head.addView(tl, new LinearLayout.LayoutParams(0, -2, 1f));
        undoBtn = new ImageButton(this, null, 0, R.style.IconButton);
        undoBtn.setImageResource(R.drawable.ic_undo);
        undoBtn.setOnClickListener(v -> undo());
        undoBtn.setAlpha(0.4f);
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44));
        up.setMarginEnd(Ui.dp(this, 8));
        head.addView(undoBtn, up);
        saveBtn = new TextView(this);
        saveBtn.setText(R.string.save_copy);
        saveBtn.setTextColor(Ui.color(this, R.color.on_accent));
        saveBtn.setTextSize(14.5f);
        saveBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        saveBtn.setBackgroundResource(R.drawable.btn_primary);
        saveBtn.setGravity(Gravity.CENTER);
        saveBtn.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), 0);
        saveBtn.setOnClickListener(v -> save());
        head.addView(saveBtn, new LinearLayout.LayoutParams(-2, Ui.dp(this, 42)));
        root.addView(head);

        FrameLayout canvasBox = new FrameLayout(this);
        ev = new EditorView(this);
        ev.setListener(this);
        canvasBox.addView(ev, new FrameLayout.LayoutParams(-1, -1));
        spinner = new SpinnerView(this);
        canvasBox.addView(spinner, new FrameLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44), Gravity.CENTER));
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
        b.setText(R.string.beta);
        b.setTextSize(10.5f);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setTextColor(Ui.color(this, R.color.accent_text));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.dp(this, 20));
        g.setColor((Ui.color(this, R.color.accent_text) & 0x00FFFFFF) | 0x26000000);
        b.setBackground(g);
        b.setPadding(Ui.dp(this, 8), Ui.dp(this, 2), Ui.dp(this, 8), Ui.dp(this, 2));
        return b;
    }

    // ------------------------------------------------------------------ rendering

    private void rebuild() {
        if (preview == null) return;
        int key = st.rot * 4 + (st.flipH ? 1 : 0) + (st.flipV ? 2 : 0);
        if (transformed == null || key != tKey) {
            transformed = Renderer.transform(preview, st);
            tKey = key;
        }
        Bitmap base = ev == null ? null : (tab == 0 ? transformed : Renderer.crop(transformed, st));
        Bitmap shown = Renderer.color(base, st);
        ev.set(shown, st);
    }

    private void push() {
        undo.add(st.copy());
        if (undo.size() > 40) undo.remove(0);
        undoBtn.setAlpha(1f);
    }

    private void undo() {
        if (undo.isEmpty()) return;
        st = undo.remove(undo.size() - 1);
        if (undo.isEmpty()) undoBtn.setAlpha(0.4f);
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

    // --- crop & rotate

    private View cropPanel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 4));
        LinearLayout ops = row();
        ops.setGravity(Gravity.CENTER);
        ops.addView(toolIcon(R.drawable.ic_rotate_left, R.string.rot_left, v -> {
            push();
            st.rot = (st.rot + 3) % 4;
            st.resetCrop();
            rebuild();
        }), new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        ops.addView(toolIcon(R.drawable.ic_rotate, R.string.rot_right, v -> {
            push();
            st.rot = (st.rot + 1) % 4;
            st.resetCrop();
            rebuild();
        }), new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        ops.addView(toolIcon(R.drawable.ic_flip, R.string.flip_h, v -> {
            push();
            st.flipH = !st.flipH;
            st.resetCrop();
            rebuild();
        }), new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        ImageButton fv = toolIcon(R.drawable.ic_flip, R.string.flip_v, v -> {
            push();
            st.flipV = !st.flipV;
            st.resetCrop();
            rebuild();
        });
        fv.setRotation(90f);
        ops.addView(fv, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        box.addView(ops);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
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
        hs.addView(chips);
        box.addView(hs);
        return box;
    }

    // --- adjust

    private View adjustPanel() {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 4), Ui.dp(this, 20), Ui.dp(this, 4));
        slider(box, R.string.ad_brightness, st.brightness, true, v -> st.brightness = v);
        slider(box, R.string.ad_contrast, st.contrast, true, v -> st.contrast = v);
        slider(box, R.string.ad_saturation, st.saturation, true, v -> st.saturation = v);
        slider(box, R.string.ad_warmth, st.warmth, true, v -> st.warmth = v);
        slider(box, R.string.ad_vignette, st.vignette, false, v -> st.vignette = v);
        sv.addView(box);
        sv.setLayoutParams(new FrameLayout.LayoutParams(-1, Ui.dp(this, 190)));
        return sv;
    }

    private interface FloatCb {
        void on(float v);
    }

    private void slider(LinearLayout box, int label, float value, final boolean signed, final FloatCb cb) {
        LinearLayout r = row();
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(Ui.color(this, R.color.text_secondary));
        l.setTextSize(13.5f);
        r.addView(l, new LinearLayout.LayoutParams(Ui.dp(this, 84), -2));
        SeekBar sb = new SeekBar(this);
        sb.setMax(signed ? 200 : 100);
        sb.setProgress(signed ? Math.round(value * 100 + 100) : Math.round(value * 100));
        sb.setProgressTintList(ColorStateList.valueOf(Ui.color(this, R.color.accent)));
        sb.setThumbTintList(ColorStateList.valueOf(Ui.color(this, R.color.accent)));
        final TextView num = new TextView(this);
        num.setTextColor(Ui.color(this, R.color.text_hint));
        num.setTextSize(12.5f);
        num.setGravity(Gravity.CENTER);
        num.setText(String.valueOf(signed ? sb.getProgress() - 100 : sb.getProgress()));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                float v = signed ? (p - 100) / 100f : p / 100f;
                num.setText(String.valueOf(signed ? p - 100 : p));
                if (!user) return;
                cb.on(v);
                rebuild();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                push();
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        r.addView(sb, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f));
        r.addView(num, new LinearLayout.LayoutParams(Ui.dp(this, 38), -2));
        box.addView(r);
    }

    // --- filters

    private View filterPanel() {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = row();
        row.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        Bitmap small = Bitmap.createScaledBitmap(preview, 128, Math.max(1, 128 * preview.getHeight() / preview.getWidth()), true);
        final List<LinearLayout> cells = new ArrayList<>();
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
            cell.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 74), Ui.dp(this, 74)));
            TextView n = new TextView(this);
            n.setText(FILTER_NAMES[i]);
            n.setTextSize(11.5f);
            n.setGravity(Gravity.CENTER);
            n.setPadding(0, Ui.dp(this, 4), 0, 0);
            cell.addView(n);
            cells.add(cell);
            cell.setOnClickListener(v -> {
                push();
                st.filter = k;
                markFilters(cells);
                rebuild();
            });
            row.addView(cell);
        }
        markFilters(cells);
        hs.addView(row);
        return hs;
    }

    private void markFilters(List<LinearLayout> cells) {
        for (int i = 0; i < cells.size(); i++) {
            boolean on = i == st.filter;
            TextView n = (TextView) cells.get(i).getChildAt(1);
            n.setTextColor(Ui.color(this, on ? R.color.accent_text : R.color.text_secondary));
            n.setTypeface(on ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
            cells.get(i).setAlpha(on ? 1f : 0.82f);
        }
    }

    // --- draw

    private LinearLayout colorRow(final int current, final java.util.function.IntConsumer on) {
        LinearLayout r = row();
        r.setGravity(Gravity.CENTER);
        final List<View> dots = new ArrayList<>();
        for (final int col : COLORS) {
            View d = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(col);
            g.setStroke(Ui.dp(this, col == current ? 3 : 1), col == current ? Ui.color(this, R.color.accent) : 0x55888888);
            d.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 30));
            lp.setMargins(Ui.dp(this, 5), 0, Ui.dp(this, 5), 0);
            d.setOnClickListener(v -> {
                on.accept(col);
                for (int i = 0; i < dots.size(); i++) {
                    GradientDrawable gg = new GradientDrawable();
                    gg.setShape(GradientDrawable.OVAL);
                    gg.setColor(COLORS[i]);
                    gg.setStroke(Ui.dp(this, COLORS[i] == col ? 3 : 1), COLORS[i] == col ? Ui.color(this, R.color.accent) : 0x55888888);
                    dots.get(i).setBackground(gg);
                }
            });
            dots.add(d);
            r.addView(d, lp);
        }
        return r;
    }

    private View drawPanel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 4));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(colorRow(brushColor, c -> {
            brushColor = c;
            ev.setBrush(brushColor, brushW);
        }));
        box.addView(hs);
        LinearLayout r = row();
        TextView l = new TextView(this);
        l.setText(R.string.brush_size);
        l.setTextColor(Ui.color(this, R.color.text_secondary));
        l.setTextSize(13.5f);
        r.addView(l, new LinearLayout.LayoutParams(Ui.dp(this, 84), -2));
        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        sb.setProgress(Math.round((brushW - 0.003f) / 0.037f * 100));
        sb.setProgressTintList(ColorStateList.valueOf(Ui.color(this, R.color.accent)));
        sb.setThumbTintList(ColorStateList.valueOf(Ui.color(this, R.color.accent)));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                brushW = 0.003f + 0.037f * p / 100f;
                ev.setBrush(brushColor, brushW);
            }

            @Override public void onStartTrackingTouch(SeekBar s) { }

            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        r.addView(sb, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f));
        box.addView(r);
        return box;
    }

    // --- text

    private View textPanel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 4));
        LinearLayout top = row();
        TextView add = new TextView(this);
        add.setText("＋  " + getString(R.string.add_text));
        add.setTextColor(Ui.color(this, R.color.on_accent));
        add.setTextSize(14);
        add.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        add.setBackgroundResource(R.drawable.btn_primary);
        add.setGravity(Gravity.CENTER);
        add.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), 0);
        add.setOnClickListener(v -> {
            EditState.Label l = new EditState.Label();
            l.color = textColor;
            l.size = textSize;
            editLabel(l);
        });
        top.addView(add, new LinearLayout.LayoutParams(-2, Ui.dp(this, 40)));
        View sp = new View(this);
        top.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        top.addView(toolIcon(R.drawable.ic_delete, R.string.delete, v -> {
            EditState.Label a = ev.active();
            if (a == null) return;
            push();
            st.labels.remove(a);
            ev.setActive(null);
            rebuild();
        }), new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        box.addView(top);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(colorRow(textColor, c -> {
            textColor = c;
            EditState.Label a = ev.active();
            if (a != null) {
                push();
                a.color = c;
                rebuild();
            }
        }));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = Ui.dp(this, 8);
        box.addView(hs, hp);
        LinearLayout r = row();
        TextView l = new TextView(this);
        l.setText(R.string.text_size);
        l.setTextColor(Ui.color(this, R.color.text_secondary));
        l.setTextSize(13.5f);
        r.addView(l, new LinearLayout.LayoutParams(Ui.dp(this, 84), -2));
        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        sb.setProgress(Math.round((textSize - 0.03f) / 0.17f * 100));
        sb.setProgressTintList(ColorStateList.valueOf(Ui.color(this, R.color.accent)));
        sb.setThumbTintList(ColorStateList.valueOf(Ui.color(this, R.color.accent)));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                textSize = 0.03f + 0.17f * p / 100f;
                EditState.Label a = ev.active();
                if (a != null && u) {
                    a.size = textSize;
                    ev.invalidate();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                if (ev.active() != null) push();
            }

            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        r.addView(sb, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f));
        box.addView(r);
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
