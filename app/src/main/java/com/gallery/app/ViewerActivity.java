package com.gallery.app;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

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
    private ImageButton favBtn;
    private boolean chrome = true;
    private int current;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
            page.setAlpha(1f - 0.3f * d);
        });
        pager.setPageTransformer(pt);
        View pagerList = pager.getChildAt(0);
        if (pagerList != null) pagerList.setOverScrollMode(View.OVER_SCROLL_NEVER);
        root.addView(pager, new FrameLayout.LayoutParams(-1, -1));

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
        root.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // bottom action pill
        final ImageButton[] btns = new ImageButton[5];
        bottomBar = ViewerBar.build(this,
                new int[]{R.drawable.ic_share, R.drawable.ic_heart, R.drawable.ic_edit, R.drawable.ic_info, R.drawable.ic_delete},
                new int[]{R.string.share, R.string.favorite, R.string.edit, R.string.info, R.string.delete},
                new View.OnClickListener[]{
                        v -> Actions.share(this, java.util.Collections.singletonList(cur())),
                        v -> toggleFav(),
                        v -> Actions.edit(this, cur()),
                        v -> InfoSheet.show(this, cur()),
                        v -> deleteCurrent()}, btns);
        favBtn = btns[1];
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        blp.bottomMargin = Ui.dp(this, 16);
        root.addView(bottomBar, blp);

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
        favBtn.setImageResource(fav ? R.drawable.ic_heart_fill : R.drawable.ic_heart);
        favBtn.setImageTintList(ColorStateList.valueOf(fav ? 0xFFFF5C77 : 0xFFFFFFFF));
    }

    private void toggleFav() {
        boolean now = Library.toggleFav(this, cur());
        updateChrome();
        Toast.makeText(this, now ? R.string.fav_added : R.string.fav_removed, Toast.LENGTH_SHORT).show();
    }

    private void deleteCurrent() {
        final Media m = cur();
        trashOrDelete(java.util.Collections.singletonList(m), () -> {
            int idx = list.indexOf(m);
            if (idx < 0) return;
            list.remove(idx);
            if (list.isEmpty()) {
                finish();
                return;
            }
            adapter.notifyItemRemoved(idx);
            current = Math.max(0, Math.min(current, list.size() - 1));
            updateChrome();
        });
    }

    private void toggleChrome() {
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
                        .override(3072, 3072)
                        .downsample(DownsampleStrategy.AT_MOST)
                        .signature(new ObjectKey(m.modified + ":" + m.size))
                        .thumbnail(0.2f)
                        .into(h.photo);
            }
        }

        @Override
        public void onViewRecycled(VH h) {
            h.photo.reset();
            Glide.with(h.itemView).clear(h.photo);
            Glide.with(h.itemView).clear(h.poster);
        }
    }
}
