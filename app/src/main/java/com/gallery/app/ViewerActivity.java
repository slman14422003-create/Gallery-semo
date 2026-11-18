package com.gallery.app;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.CompositePageTransformer;
import androidx.viewpager2.widget.MarginPageTransformer;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy;
import com.bumptech.glide.signature.ObjectKey;

import eightbitlab.com.blurview.BlurTarget;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Full-screen viewer in the iOS Photos style: zoom transition from the tapped tile, flat top bar (back, date and
 * time, Edit, more), film strip you can scrub, bottom toolbar, tap to hide everything and fade to black,
 * swipe down to shrink the picture back into its tile.
 */
public class ViewerActivity extends BaseActivity {
    private static final ArgbEvaluator ARGB = new ArgbEvaluator();
    private static final int TOP_BAR_DP = 60, STRIP_DP = 52, TOOLBAR_DP = 68, STRIP_H_DP = 42, STRIP_NARROW_DP = 16;

    private final List<Media> list = new ArrayList<>();
    private ViewPager2 pager;
    private Adapter adapter;
    private View bg;
    private FrameLayout topBar;
    private LinearLayout bottomPanel, toolbar;
    private RecyclerView strip;
    private LinearLayoutManager stripLm;
    private FrameLayout heroLayer;
    private ImageView hero;
    private FrameLayout.LayoutParams heroLp;
    private View menuGlass;
    private BlurTarget blurTarget;
    private TextView titleTv, subTv;
    private ImageButton favBtn, liveBtn, moreBtn;
    private PopupWindow popup;

    private boolean chrome = true;
    private float chromeP = 1f;     // 0 hidden, 1 shown (tap to toggle)
    private float openP = 1f;       // backdrop while opening / closing
    private float barsP = 1f;       // bars while opening / closing
    private float dismissP = 0f;    // swipe-down progress
    private ValueAnimator chromeAnim, dismissAnim;
    private int statusInset, navInset, sideLeft, sideRight, topH, bottomH;
    private int viewerBg, ink;
    private boolean stripOn;
    private boolean stripUser;
    private int stripSel = -1;
    private int stripPad;
    private boolean opening, closing, closedByHero;
    private int heroPx;

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
    private int scrubPos = -1;
    /** While the film strip is dragged only the page the finger settles on is loaded (not one 2K decode per tick). */
    private final Runnable scrubApply = () -> {
        int p = scrubPos;
        scrubPos = -1;
        if (p >= 0 && p != current && pager != null) pager.setCurrentItem(p, false);
    };
    private boolean slideshow;
    private boolean editing;   // this viewer opened an editor; close once an edited copy was saved
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
        list.addAll(Library.session);
        if (list.isEmpty()) {
            finish();
            return;
        }
        current = Math.max(0, Math.min(Library.sessionIndex, list.size() - 1));
        final Rect from = Hero.rect != null && Hero.key != null && Hero.key.equals(list.get(current).key()) ? new Rect(Hero.rect) : null;
        Hero.rect = null;
        heroPx = Hero.px;
        Motion.install(this, from != null ? R.anim.none : R.anim.viewer_open_enter, from != null ? R.anim.none : R.anim.hold,
                R.anim.hold_close, R.anim.viewer_close_exit);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);

        viewerBg = Ui.color(this, R.color.bg);
        ink = Appearance.isNight(this) ? 0xFFFFFFFF : Ui.color(this, R.color.accent);
        stripOn = list.size() > 1;

        final FrameLayout root = new FrameLayout(this);
        setContentView(root);
        root.setTag(R.id.tag_insets, Boolean.TRUE);
        View content = findViewById(android.R.id.content);
        if (content != null) content.setTag(R.id.tag_insets, Boolean.TRUE);
        rootView = root;

        bg = new View(this);
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));

        pager = new ViewPager2(this);
        adapter = new Adapter();
        pager.setAdapter(adapter);
        pager.setOffscreenPageLimit(1);
        CompositePageTransformer pt = new CompositePageTransformer();
        pt.addTransformer(new MarginPageTransformer(Ui.dp(this, 14)));
        pager.setPageTransformer(pt);
        View pagerList = pager.getChildAt(0);
        if (pagerList != null) pagerList.setOverScrollMode(View.OVER_SCROLL_NEVER);
        DismissLayout dismiss = new DismissLayout(this);
        dismiss.addView(pager, new FrameLayout.LayoutParams(-1, -1));
        dismiss.setListener(new DismissLayout.Listener() {
            @Override
            public boolean canDismiss() {
                if (live || opening || closing) return false;
                PhotoView p = currentPhoto();
                return p == null || !p.isZoomed();
            }

            @Override
            public void onProgress(float p) {
                if (slideshow) stopSlideshow();
                if (dismissAnim != null) dismissAnim.cancel();
                dismissP = p;
                applyChrome();
            }

            @Override
            public void onCancel() {
                animateDismissBack();
            }

            @Override
            public void onDismiss() {
                closeViewer();
            }
        });
        blurTarget = new BlurTarget(this);
        blurTarget.addView(dismiss, new FrameLayout.LayoutParams(-1, -1));
        root.addView(blurTarget, new FrameLayout.LayoutParams(-1, -1));

        heroLayer = new FrameLayout(this);
        heroLayer.setVisibility(View.GONE);
        hero = new ImageView(this);
        hero.setScaleType(ImageView.ScaleType.CENTER_CROP);
        heroLp = new FrameLayout.LayoutParams(0, 0, Gravity.TOP | Gravity.LEFT);
        heroLayer.addView(hero, heroLp);
        root.addView(heroLayer, new FrameLayout.LayoutParams(-1, -1));

        buildTopBar(root);
        buildBottom(root);

        menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.HORIZONTAL);
        menu.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable mg = new GradientDrawable();
        mg.setCornerRadius(Ui.dp(this, 16));
        mg.setColor(0xF51C1C1E);
        mg.setStroke(Math.max(1, Ui.dp(this, 1) / 2), 0x40FFFFFF);
        menu.setBackground(mg);
        menu.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        menuGlass = Glass.wrap(this, blurTarget, menu, Ui.dp(this, 16), Glass.OVER_MEDIA, new FrameLayout.LayoutParams(-2, -1));
        menuGlass.setElevation(Ui.dp(this, 12));
        menuGlass.setVisibility(View.GONE);
        root.addView(menuGlass, new FrameLayout.LayoutParams(-2, Ui.dp(this, 42), Gravity.TOP | Gravity.LEFT));

        busy = new ProgressBar(this);
        busy.setIndeterminateTintList(ColorStateList.valueOf(ink));
        busy.setVisibility(View.GONE);
        root.addView(busy, new FrameLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44), Gravity.CENTER));

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                closeViewer();
            }
        });
        liveBack = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                exitLive();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, liveBack);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sb = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars());
            Insets cu = insets.getInsets(WindowInsetsCompat.Type.displayCutout());
            statusInset = Math.max(sb.top, cu.top);
            navInset = Math.max(sb.bottom, cu.bottom);
            sideLeft = Math.max(sb.left, cu.left);
            sideRight = Math.max(sb.right, cu.right);
            layoutBars();
            return insets;
        });

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                if (live && position != current) exitLive();
                current = position;
                updateChrome();
                setStripSelected(position);
                if (!stripUser) centerStrip(position, true);
            }
        });
        pager.setCurrentItem(current, false);
        stripSel = current;
        updateChrome();

        if (from != null) {
            opening = true;
            openP = 0f;
            barsP = 0f;
            pager.setAlpha(0f);
            applyChrome();
            root.post(() -> startOpen(from));
        } else {
            applyChrome();
        }
    }

    // ------------------------------------------------------------------ building

    /** Translucent iOS-style disc / capsule. Drawn flat on purpose: live blur behind six controls re-rendered every frame and made paging stutter. */
    private View flat(View child, FrameLayout.LayoutParams clp) {
        FrameLayout f = new FrameLayout(this);
        boolean night = Appearance.isNight(this);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.dp(this, 100));
        g.setColor(night ? 0xE62C2C2E : 0xF0F2F2F7);
        g.setStroke(Math.max(1, Ui.dp(this, 1) / 2), night ? 0x26FFFFFF : 0x1A000000);
        f.setBackground(g);
        child.setBackground(null);
        f.addView(child, clp);
        return f;
    }

    private View glassCircle(ImageButton btn, int sizeDp) {
        View g = flat(btn, new FrameLayout.LayoutParams(-1, -1));
        g.setLayoutParams(new FrameLayout.LayoutParams(Ui.dp(this, sizeDp), Ui.dp(this, sizeDp)));
        return g;
    }

    private void buildTopBar(FrameLayout root) {
        topBar = new FrameLayout(this);
        topBar.setBackgroundColor((viewerBg & 0x00FFFFFF) | 0xF2000000);

        // back: glass circle on the left
        ImageButton back = circleButton(R.drawable.ic_chevron, R.string.back);
        back.setRotation(180f);
        back.setOnClickListener(v -> closeViewer());
        View backG = glassCircle(back, 44);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44), Gravity.START | Gravity.CENTER_VERTICAL);
        bp.setMarginStart(Ui.dp(this, 16));
        topBar.addView(backG, bp);

        // centre: glass capsule with the date and the time under it
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER);
        titles.setPadding(Ui.dp(this, 22), Ui.dp(this, 4), Ui.dp(this, 22), Ui.dp(this, 4));
        titleTv = new TextView(this);
        titleTv.setTextColor(Ui.color(this, R.color.text_primary));
        titleTv.setTextSize(15f);
        titleTv.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
        titleTv.setSingleLine(true);
        titleTv.setGravity(Gravity.CENTER);
        subTv = new TextView(this);
        subTv.setTextColor(Ui.color(this, R.color.text_secondary));
        subTv.setTextSize(11.5f);
        subTv.setSingleLine(true);
        subTv.setGravity(Gravity.CENTER);
        titles.addView(titleTv);
        titles.addView(subTv);
        View pillG = flat(titles, new FrameLayout.LayoutParams(-2, -1));
        topBar.addView(pillG, new FrameLayout.LayoutParams(-2, Ui.dp(this, 46), Gravity.CENTER));

        // right: one "..." circle (Edit, Live Text, Slideshow live inside it)
        final ImageButton more = circleButton(R.drawable.ic_more, R.string.view_options);
        moreBtn = more;
        more.setOnClickListener(v -> showMore());
        View moreG = glassCircle(more, 44);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44), Gravity.END | Gravity.CENTER_VERTICAL);
        rp.setMarginEnd(Ui.dp(this, 16));
        topBar.addView(moreG, rp);

        root.addView(topBar, new FrameLayout.LayoutParams(-1, Ui.dp(this, TOP_BAR_DP), Gravity.TOP));
    }

    private ImageButton circleButton(int icon, int desc) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(ink));
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setBackgroundResource(R.drawable.bg_icon_btn);
        b.setContentDescription(getString(desc));
        Ui.press(this, b);
        return b;
    }

    private void buildBottom(FrameLayout root) {
        bottomPanel = new LinearLayout(this);
        bottomPanel.setOrientation(LinearLayout.VERTICAL);
        bottomPanel.setBackgroundColor((viewerBg & 0x00FFFFFF) | 0xF2000000);

        strip = new RecyclerView(this);
        stripLm = new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false);
        strip.setLayoutManager(stripLm);
        strip.setAdapter(new StripAdapter());
        strip.setClipToPadding(false);
        strip.setOverScrollMode(View.OVER_SCROLL_NEVER);
        strip.setItemAnimator(null);
        strip.setHasFixedSize(true);
        strip.setItemViewCacheSize(10);
        strip.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = r - l;
            if (w != or - ol && w > 0) {
                stripPad = Math.max(0, w / 2 - Ui.dp(this, 18));
                strip.setPadding(stripPad, 0, stripPad, 0);
                centerStrip(current, false);
            }
        });
        strip.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView rv, int state) {
                if (state == RecyclerView.SCROLL_STATE_DRAGGING) {
                    stripUser = true;
                    if (slideshow) stopSlideshow();
                } else if (state == RecyclerView.SCROLL_STATE_IDLE && stripUser) {
                    stripUser = false;
                    ui.removeCallbacks(scrubApply);
                    scrubApply.run();
                    centerStrip(current, true);
                }
            }

            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                if (!stripUser) return;
                View c = rv.findChildViewUnder(rv.getWidth() / 2f, rv.getHeight() / 2f);
                int pos = c == null ? -1 : rv.getChildAdapterPosition(c);
                if (pos >= 0 && pos != current && pos != scrubPos) {
                    scrubPos = pos;
                    rv.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    ui.removeCallbacks(scrubApply);
                    ui.postDelayed(scrubApply, 60);
                }
            }
        });
        strip.setVisibility(stripOn ? View.VISIBLE : View.GONE);
        bottomPanel.addView(strip, new LinearLayout.LayoutParams(-1, Ui.dp(this, STRIP_DP)));

        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 6));
        toolbar.setClipChildren(false);
        // share: glass circle on the left
        View shareG = glassCircle(toolButton(R.drawable.ic_share, R.string.share,
                v -> Actions.share(this, java.util.Collections.singletonList(cur()))), 52);
        toolbar.addView(shareG, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        toolbar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        // middle: glass capsule with heart, info and edit (sliders)
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.HORIZONTAL);
        mid.setGravity(Gravity.CENTER_VERTICAL);
        mid.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        favBtn = toolButton(R.drawable.ic_heart, R.string.favorite, v -> toggleFav());
        mid.addView(favBtn, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        mid.addView(toolButton(R.drawable.ic_info, R.string.info, v -> InfoSheet.show(this, cur())),
                new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        mid.addView(toolButton(R.drawable.ic_sliders, R.string.edit, v -> openEditor()),
                new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        View midG = flat(mid, new FrameLayout.LayoutParams(-2, -1));
        toolbar.addView(midG, new LinearLayout.LayoutParams(-2, Ui.dp(this, 52)));
        toolbar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        // delete: glass circle on the right
        View delG = glassCircle(toolButton(R.drawable.ic_delete, R.string.delete, v -> deleteCurrent()), 52);
        toolbar.addView(delG, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        toolbar.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        bottomPanel.addView(toolbar, new LinearLayout.LayoutParams(-1, Ui.dp(this, TOOLBAR_DP)));
        root.addView(bottomPanel, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        liveBtn = new ImageButton(this);
        liveBtn.setImageResource(R.drawable.ic_live_text);
        liveBtn.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        liveBtn.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable lg = new GradientDrawable();
        lg.setShape(GradientDrawable.OVAL);
        lg.setColor(0x80000000);
        liveBtn.setBackground(lg);
        liveBtn.setContentDescription(getString(R.string.live_text));
        Ui.press(this, liveBtn);
        liveBtn.setOnClickListener(v -> toggleLive());
        liveBtn.setOnLongClickListener(v -> {
            pickEngine();
            return true;
        });
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40), Gravity.BOTTOM | Gravity.END);
        ll.setMarginEnd(Ui.dp(this, 14));
        root.addView(liveBtn, ll);
    }

    private ImageButton toolButton(int icon, int desc, View.OnClickListener click) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(ink));
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setBackground(null);
        b.setContentDescription(getString(desc));
        Ui.press(this, b);
        b.setOnClickListener(click);
        return b;
    }

    private void layoutBars() {
        topH = statusInset + Ui.dp(this, TOP_BAR_DP);
        FrameLayout.LayoutParams tl = (FrameLayout.LayoutParams) topBar.getLayoutParams();
        if (tl.height != topH) {
            tl.height = topH;
            topBar.setLayoutParams(tl);
        }
        topBar.setPadding(sideLeft, statusInset, sideRight, 0);
        bottomH = navInset + Ui.dp(this, TOOLBAR_DP) + (stripOn ? Ui.dp(this, STRIP_DP) : 0);
        bottomPanel.setPadding(sideLeft, 0, sideRight, navInset);
        FrameLayout.LayoutParams ll = (FrameLayout.LayoutParams) liveBtn.getLayoutParams();
        ll.bottomMargin = bottomH + Ui.dp(this, 12);
        liveBtn.setLayoutParams(ll);
        updatePageInsets(false);
    }

    private int insetTop() {
        return chrome ? topH : 0;
    }

    private int insetBottom() {
        return chrome ? bottomH : 0;
    }

    private void updatePageInsets(boolean animate) {
        View rv = pager.getChildAt(0);
        if (!(rv instanceof RecyclerView)) return;
        RecyclerView r = (RecyclerView) rv;
        for (int i = 0; i < r.getChildCount(); i++) {
            RecyclerView.ViewHolder vh = r.getChildViewHolder(r.getChildAt(i));
            if (vh instanceof Adapter.VH) ((Adapter.VH) vh).applyInsets(insetTop(), insetBottom(), animate);
        }
    }

    // ------------------------------------------------------------------ chrome state

    private void applyChrome() {
        float barA = chromeP * barsP * (1f - dismissP);
        topBar.setAlpha(barA);
        bottomPanel.setAlpha(barA);
        liveBtn.setAlpha(barA);
        float shift = (1f - chromeP) * Ui.dp(this, 10);
        topBar.setTranslationY(-shift);
        bottomPanel.setTranslationY(shift);
        bg.setBackgroundColor((Integer) ARGB.evaluate(chromeP, Color.BLACK, viewerBg));
        bg.setAlpha(openP * (1f - dismissP));
    }

    private void setBarsVisibility(boolean show) {
        int v = show ? View.VISIBLE : View.INVISIBLE;
        topBar.setVisibility(v);
        bottomPanel.setVisibility(v);
        liveBtn.setVisibility(list.isEmpty() || cur().video ? View.GONE : v);
    }

    private void animateDismissBack() {
        if (dismissAnim != null) dismissAnim.cancel();
        dismissAnim = ValueAnimator.ofFloat(dismissP, 0f);
        dismissAnim.setDuration(240);
        dismissAnim.setInterpolator(new DecelerateInterpolator(1.5f));
        dismissAnim.addUpdateListener(a -> {
            dismissP = (float) a.getAnimatedValue();
            applyChrome();
        });
        dismissAnim.start();
    }

    private void toggleChrome() {
        if (slideshow) {
            stopSlideshow();
            if (!chrome) toggleChrome();
            return;
        }
        setChrome(!chrome);
    }

    private void setChrome(final boolean show) {
        if (chromeAnim != null) chromeAnim.cancel();
        chrome = show;
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        if (show) c.show(WindowInsetsCompat.Type.systemBars());
        else c.hide(WindowInsetsCompat.Type.systemBars());
        updatePageInsets(true);
        setBarsVisibility(true);
        chromeAnim = ValueAnimator.ofFloat(chromeP, show ? 1f : 0f);
        chromeAnim.setDuration(show ? 240 : 220);
        chromeAnim.setInterpolator(new DecelerateInterpolator(1.6f));
        chromeAnim.addUpdateListener(a -> {
            chromeP = (float) a.getAnimatedValue();
            applyChrome();
        });
        chromeAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (!cancelled) setBarsVisibility(chrome);
            }
        });
        chromeAnim.start();
    }

    // ------------------------------------------------------------------ zoom transition

    private void positionHero(RectF r) {
        heroLp.width = Math.max(1, Math.round(r.width()));
        heroLp.height = Math.max(1, Math.round(r.height()));
        heroLp.leftMargin = Math.round(r.left);
        heroLp.topMargin = Math.round(r.top);
        hero.setLayoutParams(heroLp);
    }

    private static RectF lerp(RectF a, RectF b, float t) {
        return new RectF(a.left + (b.left - a.left) * t, a.top + (b.top - a.top) * t,
                a.right + (b.right - a.right) * t, a.bottom + (b.bottom - a.bottom) * t);
    }

    private RectF toRoot(Rect screen) {
        int[] o = new int[2];
        rootView.getLocationOnScreen(o);
        return new RectF(screen.left - o[0], screen.top - o[1], screen.right - o[0], screen.bottom - o[1]);
    }

    /** Where the current picture sits when fitted between the bars, in root coordinates. */
    private RectF fitRect(Media m) {
        float w = rootView.getWidth(), top = insetTop(), h = rootView.getHeight() - insetTop() - insetBottom();
        if (m.w <= 0 || m.h <= 0 || w <= 0 || h <= 0) return new RectF(0, top, w, top + h);
        float s = Math.min(w / m.w, h / m.h);
        float iw = m.w * s, ih = m.h * s;
        float l = (w - iw) / 2f, t = top + (h - ih) / 2f;
        return new RectF(l, t, l + iw, t + ih);
    }

    private void startOpen(Rect fromScreen) {
        if (isFinishing() || isDestroyed()) return;
        final RectF a = toRoot(fromScreen);
        final RectF b = fitRect(cur());
        Thumbs.load(hero, cur(), heroPx > 0 ? heroPx : 320);
        positionHero(a);
        heroLayer.setVisibility(View.VISIBLE);
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(380);
        va.setInterpolator(new PathInterpolator(0.22f, 1f, 0.36f, 1f));
        va.addUpdateListener(x -> {
            float t = (float) x.getAnimatedValue();
            positionHero(lerp(a, b, t));
            openP = Math.min(1f, t * 1.4f);
            barsP = Math.max(0f, Math.min(1f, (t - 0.4f) / 0.6f));
            applyChrome();
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                openP = 1f;
                barsP = 1f;
                opening = false;
                applyChrome();
                awaitPage(0);
            }
        });
        va.start();
    }

    /** Waits (briefly) until the pager page has a picture, then crossfades the transition picture away. */
    private void awaitPage(final int tries) {
        PhotoView p = currentPhoto();
        boolean ready = cur().video || (p != null && p.getDrawable() != null);
        if (!ready && tries < 40) {
            ui.postDelayed(() -> awaitPage(tries + 1), 16);
            return;
        }
        pager.setAlpha(1f);
        if (heroLayer.getVisibility() == View.VISIBLE) {
            hero.animate().alpha(0f).setDuration(140).withEndAction(() -> {
                heroLayer.setVisibility(View.GONE);
                hero.setAlpha(1f);
            }).start();
        }
    }

    private RectF screenImageRect(PhotoView p) {
        RectF r = new RectF();
        if (p == null || !p.displayRect(r)) return null;
        int[] o = new int[2];
        p.getLocationOnScreen(o);
        float s = pager.getScaleX();
        return new RectF(o[0] + r.left * s, o[1] + r.top * s, o[0] + r.right * s, o[1] + r.bottom * s);
    }

    private void closeViewer() {
        if (closing || opening) return;
        if (live) exitLive();
        stopSlideshow();
        if (popup != null) popup.dismiss();
        menuGlass.setVisibility(View.GONE);
        Media m = cur();
        Rect tile = Hero.locator != null ? Hero.locator.find(m.key()) : null;
        PhotoView p = currentPhoto();
        Drawable d = null;
        RectF start = null;
        if (tile != null && !m.video && p != null) {
            if (p.isZoomed()) p.reset();
            start = screenImageRect(p);
            d = p.getDrawable();
        } else if (tile != null && m.video) {
            RecyclerView rv = (RecyclerView) pager.getChildAt(0);
            RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(current);
            if (vh instanceof Adapter.VH) {
                d = ((Adapter.VH) vh).poster.getDrawable();
                if (d != null) {
                    int[] o = new int[2];
                    ((Adapter.VH) vh).itemView.getLocationOnScreen(o);
                    start = fitScreenRect(m, o);
                }
            }
        }
        if (tile == null || start == null || d == null) {
            closedByHero = false;
            finish();
            return;
        }
        closing = true;
        closedByHero = true;
        final RectF a = toRoot(new Rect(Math.round(start.left), Math.round(start.top), Math.round(start.right), Math.round(start.bottom)));
        final RectF b = toRoot(tile);
        Drawable.ConstantState cs = d.getConstantState();
        hero.setImageDrawable(cs != null ? cs.newDrawable(getResources()) : d);
        hero.setAlpha(1f);
        positionHero(a);
        heroLayer.setVisibility(View.VISIBLE);
        pager.setAlpha(0f);
        final float bgStart = openP * (1f - dismissP);
        final float barsStart = barsP * (1f - dismissP);
        dismissP = 0f;
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(340);
        va.setInterpolator(new PathInterpolator(0.3f, 0.9f, 0.3f, 1f));
        va.addUpdateListener(x -> {
            float t = (float) x.getAnimatedValue();
            positionHero(lerp(a, b, t));
            openP = bgStart * (1f - t);
            barsP = barsStart * Math.max(0f, 1f - t * 3f);
            applyChrome();
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                finish();
            }
        });
        va.start();
    }

    private RectF fitScreenRect(Media m, int[] pageOrigin) {
        RectF r = fitRect(m);
        float s = pager.getScaleX();
        return new RectF(pageOrigin[0] + r.left * s, pageOrigin[1] + r.top * s, pageOrigin[0] + r.right * s, pageOrigin[1] + r.bottom * s);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onPostCreate(Bundle b) {
        super.onPostCreate(b);
        try {
            WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            boolean light = !Appearance.isNight(this);
            c.setAppearanceLightStatusBars(light);
            c.setAppearanceLightNavigationBars(light);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (list.isEmpty()) return;
        if (editing) {
            editing = false;
            if (Library.savedEdit) {
                Library.savedEdit = false;
                finish();   // back to the grid so another photo can be opened right away
                return;
            }
        }
        if (adapter != null) updateChrome();
        resetInteraction();
    }

    /** After a system dialog (trash request) or the editor, make sure no half-finished animation leaves the screen unresponsive. */
    private void resetInteraction() {
        if (pager == null || topBar == null || opening || closing) return;
        pager.animate().cancel();
        pager.setTranslationX(0f);
        pager.setTranslationY(0f);
        pager.setScaleX(1f);
        pager.setScaleY(1f);
        pager.setAlpha(1f);
        if (dismissAnim != null) dismissAnim.cancel();
        if (chromeAnim != null) chromeAnim.cancel();
        dismissP = 0f;
        openP = 1f;
        barsP = 1f;
        chromeP = chrome ? 1f : 0f;
        applyChrome();
        setBarsVisibility(chrome);
    }

    @Override
    protected void onDestroy() {
        stopSlideshow();
        if (popup != null) popup.dismiss();
        Hero.clear();
        super.onDestroy();
    }

    @Override
    public void finish() {
        int cc = closedByHero ? R.anim.none : R.anim.hold_close;
        int ce = closedByHero ? R.anim.none : R.anim.viewer_close_exit;
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, cc, ce);
        super.finish();
        Motion.legacy(this, cc, ce);
    }

    private Media cur() {
        return list.get(Math.max(0, Math.min(current, list.size() - 1)));
    }

    private void updateChrome() {
        if (list.isEmpty()) return;
        Media m = cur();
        long t = m.date();
        titleTv.setText(Fmt.dateShort(t));
        subTv.setText(Fmt.time(t));
        boolean fav = Library.isFav(this, m);
        liveBtn.setVisibility(m.video ? View.GONE : (chrome ? View.VISIBLE : View.INVISIBLE));
        favBtn.setImageResource(fav ? R.drawable.ic_heart_fill : R.drawable.ic_heart);
    }

    private void toggleFav() {
        boolean now = Library.toggleFav(this, cur());
        updateChrome();
        favBtn.animate().cancel();
        favBtn.setScaleX(0.7f);
        favBtn.setScaleY(0.7f);
        favBtn.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(new OvershootInterpolator(3f)).start();
        favBtn.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        Toast.makeText(this, now ? R.string.fav_added : R.string.fav_removed, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ film strip

    private int selWidth(int pos) {
        Media m = list.get(pos);
        float aspect = m.w > 0 && m.h > 0 ? (float) m.w / m.h : 0.75f;
        int w = Math.round(Ui.dp(this, STRIP_H_DP) * aspect);
        return Math.max(Ui.dp(this, 26), Math.min(Ui.dp(this, 64), w));
    }

    private void setStripSelected(int pos) {
        if (!stripOn || stripSel == pos) return;
        int old = stripSel;
        stripSel = pos;
        resizeStripItem(old, false);
        resizeStripItem(pos, true);
    }

    private void resizeStripItem(final int pos, boolean grow) {
        if (pos < 0 || pos >= list.size()) return;
        RecyclerView.ViewHolder vh = strip.findViewHolderForAdapterPosition(pos);
        if (vh == null) return;
        final View v = vh.itemView;
        int from = v.getLayoutParams().width;
        int to = grow ? selWidth(pos) : Ui.dp(this, STRIP_NARROW_DP);
        if (from == to) return;
        ValueAnimator a = ValueAnimator.ofInt(from, to);
        a.setDuration(220);
        a.setInterpolator(new DecelerateInterpolator(1.4f));
        a.addUpdateListener(x -> {
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            lp.width = (int) x.getAnimatedValue();
            v.setLayoutParams(lp);
        });
        a.start();
    }

    private void centerStrip(int pos, boolean smooth) {
        if (!stripOn || strip == null || strip.getWidth() == 0 || pos < 0 || pos >= list.size()) return;
        int first = stripLm.findFirstVisibleItemPosition();
        int last = stripLm.findLastVisibleItemPosition();
        boolean far = first < 0 || pos < first - 10 || pos > last + 10;
        if (!smooth || far) {
            int off = strip.getWidth() / 2 - selWidth(pos) / 2 - stripPad;
            stripLm.scrollToPositionWithOffset(pos, off);
            return;
        }
        LinearSmoothScroller s = new LinearSmoothScroller(this) {
            @Override
            public int calculateDtToFit(int viewStart, int viewEnd, int boxStart, int boxEnd, int snapPreference) {
                return (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2);
            }

            @Override
            protected float calculateSpeedPerPixel(DisplayMetrics dm) {
                return 18f / dm.densityDpi;
            }

            @Override
            protected int calculateTimeForScrolling(int dx) {
                return Math.min(320, super.calculateTimeForScrolling(dx));
            }
        };
        s.setTargetPosition(pos);
        stripLm.startSmoothScroll(s);
    }

    private final class StripAdapter extends RecyclerView.Adapter<StripAdapter.VH> {
        final class VH extends RecyclerView.ViewHolder {
            final ImageView iv;

            VH(ImageView v) {
                super(v);
                iv = v;
            }
        }

        @Override
        public int getItemCount() {
            return list.size();
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int type) {
            ImageView iv = new ImageView(parent.getContext());
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(Ui.dp(parent.getContext(), STRIP_NARROW_DP), Ui.dp(parent.getContext(), STRIP_H_DP));
            lp.setMargins(Ui.dp(parent.getContext(), 1), Ui.dp(parent.getContext(), 5), Ui.dp(parent.getContext(), 1), Ui.dp(parent.getContext(), 5));
            iv.setLayoutParams(lp);
            return new VH(iv);
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            ViewGroup.LayoutParams lp = h.itemView.getLayoutParams();
            lp.width = pos == stripSel ? selWidth(pos) : Ui.dp(ViewerActivity.this, STRIP_NARROW_DP);
            h.itemView.setLayoutParams(lp);
            Thumbs.load(h.iv, list.get(pos), Ui.dp(ViewerActivity.this, 64));
            h.itemView.setOnClickListener(v -> {
                int p = h.getBindingAdapterPosition();
                if (p < 0 || p == current) return;
                if (slideshow) stopSlideshow();
                pager.setCurrentItem(p, false);
            });
        }

        @Override
        public void onViewRecycled(VH h) {
            Thumbs.clear(h.iv);
        }
    }

    // ------------------------------------------------------------------ more menu

    private void openEditor() {
        if (live) exitLive();
        editing = true;
        Actions.edit(this, cur());
    }

    private void showMore() {
        if (popup != null && popup.isShowing()) {
            popup.dismiss();
            return;
        }
        List<IosMenu.Item> items = new ArrayList<>();
        items.add(new IosMenu.Item(R.string.edit, R.drawable.ic_sliders, false, this::openEditor));
        items.add(new IosMenu.Item(R.string.info, R.drawable.ic_info, false, () -> InfoSheet.show(this, cur())));
        if (!cur().video) items.add(new IosMenu.Item(R.string.live_text, R.drawable.ic_live_text, false, this::toggleLive));
        if (list.size() > 1) items.add(new IosMenu.Item(R.string.slideshow, R.drawable.ic_play, false, this::startSlideshow));
        popup = IosMenu.show(this, moreBtn, items);
    }

    // ------------------------------------------------------------------ live text

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
        menuGlass.setVisibility(View.GONE);
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
            menuGlass.setVisibility(View.GONE);
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
        menuGlass.setVisibility(View.INVISIBLE);
        menuGlass.post(this::placeMenu);
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
            menuGlass.setVisibility(View.GONE);
            return;
        }
        int mw = menuGlass.getWidth();
        int mh = menuGlass.getHeight();
        int rw = rootView.getWidth();
        int rh = rootView.getHeight();
        int gap = Ui.dp(this, 22);
        int side = Ui.dp(this, 8);
        float x = Math.max(side, Math.min(rw - mw - side, b.centerX() - mw / 2f));
        float y = b.top - mh - gap;
        if (y < Ui.dp(this, 90)) y = b.bottom + gap + Ui.dp(this, 12);
        y = Math.max(Ui.dp(this, 90), Math.min(rh - mh - Ui.dp(this, 100), y));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) menuGlass.getLayoutParams();
        lp.leftMargin = Math.round(x);
        lp.topMargin = Math.round(y);
        menuGlass.setLayoutParams(lp);
        menuGlass.setPivotX(mw / 2f);
        menuGlass.setPivotY(mh);
        menuGlass.setAlpha(0f);
        menuGlass.setScaleX(0.88f);
        menuGlass.setScaleY(0.88f);
        menuGlass.setVisibility(View.VISIBLE);
        menuGlass.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150)
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
        stopSlideshow();
        final Media m = cur();
        trashOrDelete(java.util.Collections.singletonList(m), () -> {
            // the item is already gone from the library (BaseActivity); leave the viewer so the grid is usable at once
            closedByHero = false;
            if (!isFinishing() && !isDestroyed()) finish();
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

            void applyInsets(int top, int bottom, boolean animate) {
                photo.setInsets(top, bottom, animate);
                poster.setPadding(0, top, 0, bottom);
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
            h.applyInsets(insetTop(), insetBottom(), false);
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
                        .override(2048, 2048)
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
