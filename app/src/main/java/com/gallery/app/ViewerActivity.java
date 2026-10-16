package com.gallery.app;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.CompositePageTransformer;
import androidx.viewpager2.widget.MarginPageTransformer;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy;
import com.bumptech.glide.signature.ObjectKey;

import java.util.ArrayList;
import java.util.List;

/** Full-screen viewer: swipe between photos and videos, pinch and double-tap zoom, quick actions. */
public class ViewerActivity extends BaseActivity {
    private final List<Media> list = new ArrayList<>();
    private ViewPager2 pager;
    private Adapter adapter;
    private LinearLayout topBar, bottomBar;
    private TextView titleTv, subTv;
    private ImageButton favBtn, liveBtn;
    private boolean chrome = true;
    private int current;
    private boolean live;
    private int liveGen;
    private OcrLayer layer;
    private ProgressBar busy;
    private LinearLayout menu;
    private FrameLayout rootView;
    private final List<Object[]> menuItems = new ArrayList<>();
    private int menuPage;
    private androidx.appcompat.app.AlertDialog dlDialog;
    private ProgressBar dlBar;
    private TextView dlText;
    private OnBackPressedCallback liveBack;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean slideshow;
    private final Runnable slideStep = new Runnable() {
        @Override
        public void run() {
            if (!slideshow) return;
            pager.setCurrentItem(current + 1 < list.size() ? current + 1 : 0, true);
            ui.postDelayed(this, 3200);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Motion.install(this, R.anim.viewer_open_enter, R.anim.hold, R.anim.hold_close, R.anim.viewer_close_exit);
        list.addAll(Library.session);
        if (list.isEmpty()) {
            finish();
            return;
        }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        root.setTag(R.id.tag_insets, Boolean.TRUE);
        View content = findViewById(android.R.id.content);
        if (content != null) content.setTag(R.id.tag_insets, Boolean.TRUE);

        pager = new ViewPager2(this);
        adapter = new Adapter();
        pager.setAdapter(adapter);
        pager.setOffscreenPageLimit(1);
        // gap between pages plus a soft depth effect while swiping (neighbours shrink and fade slightly)
        CompositePageTransformer pt = new CompositePageTransformer();
        pt.addTransformer(new MarginPageTransformer(Ui.dp(this, 14)));
        pt.addTransformer((page, position) -> {
            float d = Math.min(1f, Math.abs(position));
            float s = 1f - 0.05f * d;
            page.setScaleX(s);
            page.setScaleY(s);
        });
        pager.setPageTransformer(pt);
        View pagerList = pager.getChildAt(0);
        if (pagerList != null) pagerList.setOverScrollMode(View.OVER_SCROLL_NEVER);
        DismissLayout dismiss = new DismissLayout(this);
        dismiss.addView(pager, new FrameLayout.LayoutParams(-1, -1));
        dismiss.setListener(new DismissLayout.Listener() {
            @Override
            public boolean canDismiss() {
                if (live) return false;
                PhotoView p = currentPhoto();
                return p == null || !p.isZoomed();
            }

            @Override
            public void onProgress(float p) {
                if (slideshow) stopSlideshow();
                topBar.setAlpha(1f - p);
                bottomBar.setAlpha(1f - p);
            }

            @Override
            public void onCancel() {
                topBar.animate().alpha(chrome ? 1f : 0f).setDuration(200).start();
                bottomBar.animate().alpha(chrome ? 1f : 0f).setDuration(200).start();
            }

            @Override
            public void onDismiss() {
                finish();
            }
        });
        root.addView(dismiss, new FrameLayout.LayoutParams(-1, -1));

        // top: floating back button and a title pill (no full-width bar)
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = roundButton(R.drawable.ic_back, R.string.back);
        back.setOnClickListener(v -> finish());
        topBar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_VERTICAL);
        titles.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), 0);
        GradientDrawable tg = new GradientDrawable();
        tg.setCornerRadius(Ui.dp(this, 23));
        tg.setColor(0x66000000);
        titles.setBackground(tg);
        titleTv = new TextView(this);
        titleTv.setTextColor(Color.WHITE);
        titleTv.setTextSize(14.5f);
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        titleTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        subTv = new TextView(this);
        subTv.setTextColor(0xBBFFFFFF);
        subTv.setTextSize(11.5f);
        subTv.setSingleLine(true);
        subTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        titles.addView(titleTv);
        titles.addView(subTv);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1f);
        tlp.setMarginStart(Ui.dp(this, 8));
        topBar.addView(titles, tlp);
        ImageButton slides = roundButton(R.drawable.ic_play, R.string.slideshow);
        slides.setOnClickListener(v -> startSlideshow());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46));
        slp.setMarginStart(Ui.dp(this, 8));
        topBar.addView(slides, slp);
        root.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // bottom action pill
        final ImageButton[] btns = new ImageButton[6];
        bottomBar = ViewerBar.build(this,
                new int[]{R.drawable.ic_share, R.drawable.ic_heart, R.drawable.ic_live_text, R.drawable.ic_edit, R.drawable.ic_info, R.drawable.ic_delete},
                new int[]{R.string.share, R.string.favorite, R.string.live_text, R.string.edit, R.string.info, R.string.delete},
                new View.OnClickListener[]{
                        v -> Actions.share(this, java.util.Collections.singletonList(cur())),
                        v -> toggleFav(),
                        v -> toggleLive(),
                        v -> Actions.edit(this, cur()),
                        v -> InfoSheet.show(this, cur()),
                        v -> deleteCurrent()}, btns);
        favBtn = btns[1];
        liveBtn = btns[2];
        liveBtn.setOnLongClickListener(v -> {
            pickEngine();
            return true;
        });
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        blp.bottomMargin = Ui.dp(this, 16);
        root.addView(bottomBar, blp);

        rootView = root;
        menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.HORIZONTAL);
        menu.setGravity(Gravity.CENTER_VERTICAL);
        menu.setVisibility(View.GONE);
        GradientDrawable mg = new GradientDrawable();
        mg.setCornerRadius(Ui.dp(this, 16));
        mg.setColor(0xF51C1C1E);
        mg.setStroke(Math.max(1, Ui.dp(this, 1) / 2), 0x40FFFFFF);
        menu.setBackground(mg);
        menu.setElevation(Ui.dp(this, 12));
        menu.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        root.addView(menu, new FrameLayout.LayoutParams(-2, Ui.dp(this, 42), Gravity.TOP | Gravity.LEFT));

        busy = new ProgressBar(this);
        busy.setIndeterminateTintList(ColorStateList.valueOf(0xFFFFFFFF));
        busy.setVisibility(View.GONE);
        root.addView(busy, new FrameLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44), Gravity.CENTER));

        liveBack = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                exitLive();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, liveBack);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets b = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            topBar.setPadding(Ui.dp(this, 12) + b.left, b.top + Ui.dp(this, 10), Ui.dp(this, 12) + b.right, 0);
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) bottomBar.getLayoutParams();
            p.bottomMargin = Ui.dp(this, 16) + b.bottom;
            bottomBar.setLayoutParams(p);
            return insets;
        });

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                if (live && position != current) exitLive();
                current = position;
                updateChrome();
            }
        });
        current = Math.max(0, Math.min(Library.sessionIndex, list.size() - 1));
        pager.setCurrentItem(current, false);
        updateChrome();
    }

    @Override
    protected void onPostCreate(Bundle b) {
        super.onPostCreate(b);
        try {
            WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            c.setAppearanceLightStatusBars(false);
            c.setAppearanceLightNavigationBars(false);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null && !list.isEmpty()) updateChrome();
        resetInteraction();
    }

    /** After a system dialog (trash request) or the editor, make sure no half-finished animation leaves the screen unresponsive. */
    private void resetInteraction() {
        if (pager == null || topBar == null || bottomBar == null) return;
        pager.animate().cancel();
        pager.setTranslationX(0f);
        pager.setTranslationY(0f);
        pager.setScaleX(1f);
        pager.setScaleY(1f);
        topBar.animate().cancel();
        bottomBar.animate().cancel();
        topBar.setAlpha(chrome ? 1f : 0f);
        bottomBar.setAlpha(chrome ? 1f : 0f);
        topBar.setVisibility(chrome ? View.VISIBLE : View.INVISIBLE);
        bottomBar.setVisibility(chrome ? View.VISIBLE : View.INVISIBLE);
        for (int i = 0; i < bottomBar.getChildCount(); i++) bottomBar.getChildAt(i).setEnabled(chrome);
    }

    @Override
    protected void onDestroy() {
        stopSlideshow();
        super.onDestroy();
    }

    @Override
    public void finish() {
        super.finish();
        Motion.legacy(this, R.anim.hold_close, R.anim.viewer_close_exit);
    }

    private Media cur() {
        return list.get(Math.max(0, Math.min(current, list.size() - 1)));
    }

    private ImageButton roundButton(int icon, int desc) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        b.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0x66000000);
        b.setBackground(g);
        b.setContentDescription(getString(desc));
        Ui.press(this, b);
        return b;
    }

    private void updateChrome() {
        if (list.isEmpty()) return;
        Media m = cur();
        titleTv.setText(m.name);
        StringBuilder sb = new StringBuilder(Fmt.dateLong(m.date()));
        subTv.setText(sb);
        boolean fav = Library.isFav(this, m);
        liveBtn.setVisibility(m.video ? View.GONE : View.VISIBLE);
        favBtn.setImageResource(fav ? R.drawable.ic_heart_fill : R.drawable.ic_heart);
        favBtn.setImageTintList(ColorStateList.valueOf(fav ? 0xFFFF5C77 : 0xFFFFFFFF));
    }

    private void toggleFav() {
        boolean now = Library.toggleFav(this, cur());
        updateChrome();
        Toast.makeText(this, now ? R.string.fav_added : R.string.fav_removed, Toast.LENGTH_SHORT).show();
    }

    private PhotoView currentPhoto() {
        View rv = pager.getChildAt(0);
        if (!(rv instanceof RecyclerView)) return null;
        RecyclerView.ViewHolder vh = ((RecyclerView) rv).findViewHolderForAdapterPosition(current);
        return vh instanceof Adapter.VH ? ((Adapter.VH) vh).photo : null;
    }

    private void toggleLive() {
        if (cur().video) return;
        if (live) {
            exitLive();
            return;
        }
        if (Ocr.needsKey(this)) {
            askKey(this::toggleLive);
            return;
        }
        final PhotoView p = currentPhoto();
        if (p == null) return;
        live = true;
        liveBack.setEnabled(true);
        final int gen = ++liveGen;
        setLiveButton(true);
        busy.setVisibility(View.VISIBLE);
        Ocr.run(this, cur(), new Ocr.Callback() {
            @Override
            public void onDownload(int percent) {
                if (gen != liveGen) return;
                if (percent < 0) hideDownload();
                else showDownload(percent);
            }

            @Override
            public void onDone(OcrResult r) {
                if (gen != liveGen || !live) return;
                hideDownload();
                busy.setVisibility(View.GONE);
                if (r.words.isEmpty()) {
                    Toast.makeText(ViewerActivity.this, R.string.ocr_none, Toast.LENGTH_LONG).show();
                    exitLive();
                    return;
                }
                layer = new OcrLayer(p, r, settled -> onSelection(settled));
                p.setLayer(layer);
            }

            @Override
            public void onError(int msg) {
                if (gen != liveGen) return;
                hideDownload();
                Toast.makeText(ViewerActivity.this, msg, Toast.LENGTH_LONG).show();
                exitLive();
            }
        });
    }

    private void exitLive() {
        live = false;
        liveGen++;
        liveBack.setEnabled(false);
        busy.setVisibility(View.GONE);
        menu.setVisibility(View.GONE);
        hideDownload();
        if (layer != null) {
            layer.release();
            layer = null;
        }
        setLiveButton(false);
    }

    private void setLiveButton(boolean on) {
        liveBtn.setImageTintList(ColorStateList.valueOf(on ? 0xFFFFD60A : 0xFFFFFFFF));
    }

    private void showDownload(int percent) {
        if (dlDialog == null) {
            LinearLayout box = Ui.box(this);
            dlText = new TextView(this);
            dlText.setTextColor(Ui.color(this, R.color.text_secondary));
            dlText.setTextSize(14);
            box.addView(dlText);
            dlBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            dlBar.setMax(100);
            Ui.tint(this, dlBar);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 16));
            lp.topMargin = Ui.dp(this, 10);
            box.addView(dlBar, lp);
            dlDialog = new Dlg(this).setTitle(R.string.ocr_download).setView(box).setCancelable(false)
                    .setNegativeButton(R.string.cancel, (d, w) -> {
                        dlDialog = null;
                        exitLive();
                    }).create();
            dlDialog.show();
        }
        dlBar.setProgress(percent);
        dlText.setText(percent + "%");
    }

    private void hideDownload() {
        if (dlDialog != null) {
            androidx.appcompat.app.AlertDialog d = dlDialog;
            dlDialog = null;
            d.dismiss();
        }
    }

    private void onSelection(boolean settled) {
        if (layer == null || !layer.hasSelection() || !settled) {
            menu.setVisibility(View.GONE);
            return;
        }
        menuItems.clear();
        final String text = layer.selectedText();
        int n = 0;
        for (final OcrEntity e : layer.selectionEntities()) {
            if (n >= 2) break;
            n++;
            if (e.type == OcrEntity.PHONE) {
                menuItems.add(new Object[]{R.string.ocr_call, (Runnable) () -> LiveActions.call(this, e.value)});
                menuItems.add(new Object[]{R.string.ocr_message, (Runnable) () -> LiveActions.sms(this, e.value)});
            } else if (e.type == OcrEntity.URL) {
                menuItems.add(new Object[]{R.string.ocr_open_link, (Runnable) () -> LiveActions.open(this, e.value)});
            } else {
                menuItems.add(new Object[]{R.string.ocr_send_mail, (Runnable) () -> LiveActions.mail(this, e.value)});
            }
        }
        menuItems.add(new Object[]{R.string.ocr_copy, (Runnable) () -> {
            LiveActions.copy(this, text);
            layer.clearSelection();
        }});
        menuItems.add(new Object[]{R.string.ocr_select_all, (Runnable) () -> layer.selectAll()});
        menuItems.add(new Object[]{R.string.ocr_translate, (Runnable) () -> TranslateSheet.show(this, text)});
        menuItems.add(new Object[]{R.string.ocr_search, (Runnable) () -> LiveActions.search(this, text)});
        menuItems.add(new Object[]{R.string.share, (Runnable) () -> LiveActions.shareText(this, text)});
        if (Semo.hasNotes(this)) {
            menuItems.add(new Object[]{R.string.eco_save_note, (Runnable) () -> Semo.sendToNotes(this, "", text)});
        }
        menuPage = 0;
        renderMenu();
    }

    private void renderMenu() {
        final int per = 4;
        menu.removeAllViews();
        int from = menuPage * per;
        int to = Math.min(menuItems.size(), from + per);
        boolean rtl = getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        if (menuPage > 0) menuArrow(rtl ? "›" : "‹", -1);
        for (int i = from; i < to; i++) {
            if (i > from || menuPage > 0) menuDivider();
            final Object[] it = menuItems.get(i);
            TextView t = new TextView(this);
            t.setText((Integer) it[0]);
            t.setTextSize(14.5f);
            t.setSingleLine(true);
            t.setTextColor(i == from && menuPage == 0 ? 0xFF64B5FF : 0xFFFFFFFF);
            t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            t.setGravity(Gravity.CENTER);
            t.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
            GradientDrawable mask = new GradientDrawable();
            mask.setCornerRadius(Ui.dp(this, 12));
            mask.setColor(0xFFFFFFFF);
            t.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, mask));
            t.setOnClickListener(v -> ((Runnable) it[1]).run());
            menu.addView(t, new LinearLayout.LayoutParams(-2, -1));
        }
        if (to < menuItems.size()) {
            menuDivider();
            menuArrow(rtl ? "‹" : "›", 1);
        }
        menu.setVisibility(View.INVISIBLE);
        menu.post(this::placeMenu);
    }

    private void menuArrow(String glyph, final int dir) {
        TextView t = new TextView(this);
        t.setText(glyph);
        t.setTextSize(22f);
        t.setTextColor(0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 3));
        t.setOnClickListener(v -> {
            menuPage += dir;
            renderMenu();
        });
        menu.addView(t, new LinearLayout.LayoutParams(-2, -1));
    }

    private void menuDivider() {
        View d = new View(this);
        d.setBackgroundColor(0x33FFFFFF);
        menu.addView(d, new LinearLayout.LayoutParams(Math.max(1, Ui.dp(this, 1) / 2), Ui.dp(this, 22)));
    }

    private void placeMenu() {
        android.graphics.RectF b = new android.graphics.RectF();
        if (layer == null || !layer.selectionBounds(b)) {
            menu.setVisibility(View.GONE);
            return;
        }
        int mw = menu.getWidth();
        int mh = menu.getHeight();
        int rw = rootView.getWidth();
        int rh = rootView.getHeight();
        int gap = Ui.dp(this, 22);
        int side = Ui.dp(this, 8);
        float x = Math.max(side, Math.min(rw - mw - side, b.centerX() - mw / 2f));
        float y = b.top - mh - gap;
        if (y < Ui.dp(this, 90)) y = b.bottom + gap + Ui.dp(this, 12);
        y = Math.max(Ui.dp(this, 90), Math.min(rh - mh - Ui.dp(this, 100), y));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) menu.getLayoutParams();
        lp.leftMargin = Math.round(x);
        lp.topMargin = Math.round(y);
        menu.setLayoutParams(lp);
        menu.setPivotX(mw / 2f);
        menu.setPivotY(mh);
        menu.setAlpha(0f);
        menu.setScaleX(0.88f);
        menu.setScaleY(0.88f);
        menu.setVisibility(View.VISIBLE);
        menu.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f)).start();
    }

    private void pickEngine() {
        final String[] ids = {Ocr.AUTO, Ocr.MLKIT, Ocr.TESS, Ocr.VISION};
        final int[] names = {R.string.ocr_engine_auto, R.string.ocr_engine_mlkit, R.string.ocr_engine_tess, R.string.ocr_engine_vision};
        String curMode = Ocr.mode(this);
        CharSequence[] items = new CharSequence[ids.length];
        for (int i = 0; i < ids.length; i++) {
            items[i] = (ids[i].equals(curMode) ? "✓ " : "○ ") + getString(names[i]);
        }
        new Dlg(this).setTitle(R.string.ocr_engine).setItems(items, (d, w) -> {
            Ocr.setMode(this, ids[w]);
            if (live) exitLive();
            if (Ocr.VISION.equals(ids[w]) && VisionEngine.key(this).isEmpty()) askKey(null);
        }).show();
    }

    private void askKey(final Runnable after) {
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        et.setHint(R.string.ocr_vision_hint);
        et.setText(VisionEngine.key(this));
        et.setTextColor(Ui.color(this, R.color.text_primary));
        et.setHintTextColor(Ui.color(this, R.color.text_hint));
        et.setBackgroundResource(R.drawable.bg_input);
        et.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        LinearLayout box = Ui.box(this);
        box.addView(Ui.label(this, getString(R.string.ocr_vision_msg)));
        box.addView(et, new LinearLayout.LayoutParams(-1, Ui.dp(this, 50)));
        new Dlg(this).setTitle(R.string.ocr_vision_title).setView(box)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String k = et.getText().toString().trim();
                    Store.setStr(this, "vision_key", k);
                    if (!k.isEmpty() && after != null) after.run();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void deleteCurrent() {
        if (live) exitLive();
        final Media m = cur();
        trashOrDelete(java.util.Collections.singletonList(m), () -> {
            int idx = list.indexOf(m);
            if (idx < 0) return;
            list.remove(idx);
            if (list.isEmpty()) {
                finish();
                return;
            }
            current = Math.max(0, Math.min(idx, list.size() - 1));
            adapter.notifyDataSetChanged();
            pager.setCurrentItem(current, false);
            updateChrome();
            resetInteraction();
            pager.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (pager.getCurrentItem() != current) pager.setCurrentItem(current, false);
                resetInteraction();
            });
        });
    }

    private void startSlideshow() {
        if (slideshow || list.size() < 2) return;
        if (live) exitLive();
        if (chrome) toggleChrome();
        slideshow = true;
        ui.postDelayed(slideStep, 2200);
    }

    private void stopSlideshow() {
        slideshow = false;
        ui.removeCallbacks(slideStep);
    }

    private void toggleChrome() {
        if (slideshow) {
            stopSlideshow();
            if (!chrome) toggleChrome();
            return;
        }
        chrome = !chrome;
        // a hidden bar must stop receiving touches: before, the invisible back button still caught taps in the corner
        fadeChrome(topBar, chrome);
        fadeChrome(bottomBar, chrome);
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        if (chrome) c.show(WindowInsetsCompat.Type.systemBars());
        else c.hide(WindowInsetsCompat.Type.systemBars());
        for (int i = 0; i < bottomBar.getChildCount(); i++) bottomBar.getChildAt(i).setEnabled(chrome);
    }

    private void fadeChrome(final View v, final boolean show) {
        v.animate().cancel();
        if (show) {
            v.setVisibility(View.VISIBLE);
            v.animate().alpha(1f).setDuration(180).withLayer().start();
        } else {
            v.animate().alpha(0f).setDuration(160).withLayer()
                    .withEndAction(() -> {
                        if (!chrome) v.setVisibility(View.INVISIBLE);
                    }).start();
        }
    }

    // ------------------------------------------------------------------ pages

    private final class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        final class VH extends RecyclerView.ViewHolder {
            final PhotoView photo;
            final ImageView poster;
            final ImageView play;

            VH(FrameLayout f, PhotoView p, ImageView post, ImageView pl) {
                super(f);
                photo = p;
                poster = post;
                play = pl;
            }
        }

        @Override
        public int getItemCount() {
            return list.size();
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int type) {
            FrameLayout f = new FrameLayout(parent.getContext());
            f.setLayoutParams(new RecyclerView.LayoutParams(-1, -1));
            PhotoView p = new PhotoView(parent.getContext());
            f.addView(p, new FrameLayout.LayoutParams(-1, -1));
            ImageView post = new ImageView(parent.getContext());
            post.setScaleType(ImageView.ScaleType.FIT_CENTER);
            f.addView(post, new FrameLayout.LayoutParams(-1, -1));
            ImageView pl = new ImageView(parent.getContext());
            pl.setImageResource(R.drawable.ic_play_fill);
            pl.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
            int pad = Ui.dp(parent.getContext(), 22);
            pl.setPadding(pad + Ui.dp(parent.getContext(), 3), pad, pad, pad);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(0x99000000);
            g.setStroke(Ui.dp(parent.getContext(), 2), 0xAAFFFFFF);
            pl.setBackground(g);
            f.addView(pl, new FrameLayout.LayoutParams(Ui.dp(parent.getContext(), 84), Ui.dp(parent.getContext(), 84), Gravity.CENTER));
            return new VH(f, p, post, pl);
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            final Media m = list.get(pos);
            Glide.with(h.itemView).clear(h.photo);
            Glide.with(h.itemView).clear(h.poster);
            h.photo.setTapListener(ViewerActivity.this::toggleChrome);
            if (m.video) {
                h.photo.setVisibility(View.GONE);
                h.poster.setVisibility(View.VISIBLE);
                h.play.setVisibility(View.VISIBLE);
                Glide.with(h.itemView).asBitmap().load(m.uri).override(1280, 1280)
                        .signature(new ObjectKey(m.modified + ":" + m.size)).into(h.poster);
                h.poster.setOnClickListener(v -> toggleChrome());
                h.play.setOnClickListener(v -> Actions.play(ViewerActivity.this, list, list.indexOf(m)));
            } else {
                h.poster.setVisibility(View.GONE);
                h.play.setVisibility(View.GONE);
                h.photo.setVisibility(View.VISIBLE);
                Glide.with(h.itemView).load(m.uri)
                        .override(2560, 2560)
                        .downsample(DownsampleStrategy.AT_MOST)
                        .signature(new ObjectKey(m.modified + ":" + m.size))
                        .thumbnail(0.2f)
                        .into(h.photo);
            }
        }

        @Override
        public void onViewRecycled(VH h) {
            h.photo.setLayer(null);
            h.photo.reset();
            Glide.with(h.itemView).clear(h.photo);
            Glide.with(h.itemView).clear(h.poster);
        }
    }
}
