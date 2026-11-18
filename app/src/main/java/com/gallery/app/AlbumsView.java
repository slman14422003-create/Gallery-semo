package com.gallery.app;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * The Collections tab (iOS 26 style): a large title with a round Settings button, a "Pinned" section of
 * small rounded tiles (the smart albums: favourites, videos, screenshots) and an "Albums" section of big
 * rounded cover cards with the name drawn over a soft bottom gradient.
 */
public class AlbumsView extends LinearLayout {
    private static final int HEADER = 0, PINNED = 1, ALBUM = 2;
    private static final int SPANS = 6;

    private final BaseActivity act;
    private final TextView subtitle;
    private final RecyclerView rv;
    private final List<Object> rows = new ArrayList<>();   // String = section header, Album = card
    private final List<Album> albums = new ArrayList<>();
    private final Adapter ad = new Adapter();
    private final TextView empty;
    private final boolean wide;
    private ScrollHeader scroller;
    private View headerView, statusScrim;
    private int headerPadTop, topInset;

    public AlbumsView(BaseActivity a) {
        super(a);
        act = a;
        wide = a.getResources().getConfiguration().screenWidthDp >= 600;
        setOrientation(VERTICAL);
        setBackgroundColor(Ui.color(a, R.color.bg));
        final View header = LayoutInflater.from(a).inflate(R.layout.view_header, this, false);
        ((TextView) header.findViewById(R.id.title)).setText(R.string.nav_collections);
        subtitle = header.findViewById(R.id.subtitle);
        header.findViewById(R.id.btnBack).setVisibility(GONE);
        // the round button on the right opens Settings (Settings is not a tab any more)
        ImageButton settings = header.findViewById(R.id.btnRefresh);
        settings.setImageResource(R.drawable.ic_settings);
        settings.setContentDescription(a.getString(R.string.nav_settings));
        settings.setVisibility(VISIBLE);
        settings.setOnClickListener(v -> {
            act.startActivity(new Intent(act, SettingsActivity.class));
            Motion.legacy(act, R.anim.act_open_enter, R.anim.act_open_exit);
        });

        FrameLayout body = new FrameLayout(a);
        addView(body, new LayoutParams(-1, 0, 1f));
        rv = new RecyclerView(a);
        GridLayoutManager lm = new GridLayoutManager(a, SPANS);
        lm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                int t = ad.getItemViewType(position);
                if (t == HEADER) return SPANS;
                if (t == PINNED) return wide ? 1 : 2;     // 3 per row on phones, 6 on tablets
                return wide ? 2 : 3;                      // 2 per row on phones, 3 on tablets
            }
        });
        rv.setLayoutManager(lm);
        rv.setAdapter(ad);
        rv.setClipToPadding(false);
        rv.setPadding(Ui.dp(a, 12), Ui.dp(a, 0), Ui.dp(a, 12), Ui.dp(a, 112));   // room for the floating bar
        rv.setOverScrollMode(OVER_SCROLL_NEVER);
        final int gap = Ui.dp(a, 5);
        rv.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(Rect out, View v, RecyclerView p, RecyclerView.State s) {
                int pos = p.getChildAdapterPosition(v);
                if (pos >= 0 && ad.getItemViewType(pos) == HEADER) out.set(0, 0, 0, 0);
                else out.set(gap, gap, gap, gap);
            }
        });
        body.addView(rv, new FrameLayout.LayoutParams(-1, -1));
        body.addView(header, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));   // scrolls away with the cards
        headerView = header;
        headerPadTop = header.getPaddingTop();
        statusScrim = new View(a);
        int bgc = Ui.color(a, R.color.bg) & 0x00FFFFFF;
        statusScrim.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{bgc | 0xCC000000, bgc}));
        body.addView(statusScrim, new FrameLayout.LayoutParams(-1, 0, Gravity.TOP));
        scroller = new ScrollHeader(rv, header, Ui.dp(a, 112));
        empty = new TextView(a);
        empty.setText(R.string.empty_library);
        empty.setTextColor(Ui.color(a, R.color.text_secondary));
        empty.setGravity(Gravity.CENTER);
        empty.setTextSize(15);
        empty.setVisibility(GONE);
        body.addView(empty, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        refresh();
    }

    /** The cards scroll up under the status bar like on iPhone; the header sits below it. */
    public void setTopInset(int px) {
        if (px == topInset || headerView == null) return;
        topInset = px;
        headerView.setPadding(headerView.getPaddingLeft(), headerPadTop + px, headerView.getPaddingRight(), headerView.getPaddingBottom());
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) statusScrim.getLayoutParams();
        lp.height = px > 0 ? px + Ui.dp(act, 12) : 0;
        statusScrim.setLayoutParams(lp);
    }

    public void setBottomInset(int px) {
        if (scroller != null) scroller.setBottom(Ui.dp(act, 112) + px);
    }

    public void scrollToTop() {
        rv.smoothScrollToPosition(0);
    }

    public void refresh() {
        albums.clear();
        albums.addAll(Library.albums(act));
        rows.clear();
        List<Album> pinned = new ArrayList<>();
        List<Album> normal = new ArrayList<>();
        for (Album al : albums) (al.kind == Album.NORMAL ? normal : pinned).add(al);
        if (!pinned.isEmpty()) {
            rows.add(act.getString(R.string.sec_pinned));
            rows.addAll(pinned);
        }
        if (!normal.isEmpty()) {
            rows.add(act.getString(R.string.sec_albums));
            rows.addAll(normal);
        }
        ad.notifyDataSetChanged();
        subtitle.setText(act.getString(R.string.n_albums, albums.size()));
        empty.setVisibility(albums.isEmpty() && Library.loaded ? VISIBLE : GONE);
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public int getItemViewType(int p) {
            Object o = rows.get(p);
            if (o instanceof String) return HEADER;
            return ((Album) o).kind == Album.NORMAL ? ALBUM : PINNED;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
            Context c = parent.getContext();
            if (type == HEADER) {
                TextView h = new TextView(c);
                h.setId(R.id.album_name);
                h.setTextColor(Ui.color(c, R.color.text_primary));
                h.setTextSize(22);
                h.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
                h.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
                h.setPaddingRelative(Ui.dp(c, 6), Ui.dp(c, 16), Ui.dp(c, 6), Ui.dp(c, 6));
                h.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
                return new RecyclerView.ViewHolder(h) { };
            }
            final boolean small = type == PINNED;
            final int radius = Ui.dp(c, small ? 18 : 26);

            FrameLayout cover = new FrameLayout(c) {
                @Override
                protected void onMeasure(int w, int h) {
                    super.onMeasure(w, w);   // always square
                }
            };
            cover.setClipToOutline(true);
            cover.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                }
            });
            cover.setBackgroundColor(Ui.color(c, R.color.surface_high));

            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setId(R.id.album_cover);
            cover.addView(iv, new FrameLayout.LayoutParams(-1, -1));

            // soft gradient so the white name stays readable on any photo
            View scrim = new View(c);
            GradientDrawable sg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x00000000, 0x99000000});
            scrim.setBackground(sg);
            cover.addView(scrim, new FrameLayout.LayoutParams(-1, Ui.dp(c, small ? 56 : 96), Gravity.BOTTOM));

            ImageView kind = new ImageView(c);
            kind.setId(R.id.album_kind);
            kind.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
            int pad = Ui.dp(c, small ? 4 : 6);
            kind.setPadding(pad, pad, pad, pad);
            GradientDrawable kg = new GradientDrawable();
            kg.setShape(GradientDrawable.OVAL);
            kg.setColor(0x66000000);
            kind.setBackground(kg);
            int ks = Ui.dp(c, small ? 22 : 30);
            FrameLayout.LayoutParams kp = new FrameLayout.LayoutParams(ks, ks, Gravity.TOP | Gravity.START);
            kp.setMargins(Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8));
            cover.addView(kind, kp);

            LinearLayout label = new LinearLayout(c);
            label.setOrientation(LinearLayout.VERTICAL);
            label.setPaddingRelative(Ui.dp(c, small ? 9 : 14), 0, Ui.dp(c, 8), Ui.dp(c, small ? 8 : 12));
            TextView name = new TextView(c);
            name.setId(R.id.album_name);
            name.setTextColor(0xFFFFFFFF);
            name.setTextSize(small ? 12.5f : 16);
            name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            name.setShadowLayer(4f, 0f, 1f, 0x80000000);
            label.addView(name);
            if (!small) {
                TextView count = new TextView(c);
                count.setId(R.id.album_count);
                count.setTextColor(0xCCFFFFFF);
                count.setTextSize(12.5f);
                count.setSingleLine(true);
                count.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
                count.setShadowLayer(4f, 0f, 1f, 0x80000000);
                label.addView(count);
            }
            cover.addView(label, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

            cover.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            Ui.press(c, cover);
            return new RecyclerView.ViewHolder(cover) { };
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder h, int p) {
            Object o = rows.get(p);
            View v = h.itemView;
            if (o instanceof String) {
                ((TextView) v).setText((String) o);
                return;
            }
            final Album a = (Album) o;
            ImageView iv = v.findViewById(R.id.album_cover);
            ImageView kind = v.findViewById(R.id.album_kind);
            ((TextView) v.findViewById(R.id.album_name)).setText(a.name);
            TextView count = v.findViewById(R.id.album_count);
            if (count != null) {
                count.setText(a.videos > 0 && a.videos < a.count
                        ? act.getString(R.string.album_count_mixed, a.count - a.videos, a.videos)
                        : a.videos == a.count ? act.getString(R.string.album_count_videos, a.count)
                        : act.getString(R.string.album_count_photos, a.count));
            }
            if (a.cover != null) Thumbs.load(iv, a.cover, 480);
            else iv.setImageDrawable(new ColorDrawable(Ui.color(act, R.color.surface_high)));
            int icon = a.kind == Album.FAVORITES ? R.drawable.ic_heart_fill
                    : a.kind == Album.VIDEOS ? R.drawable.ic_video
                    : a.kind == Album.SHOTS ? R.drawable.ic_aspect : 0;
            kind.setVisibility(icon == 0 ? GONE : VISIBLE);
            if (icon != 0) kind.setImageResource(icon);
            v.setOnClickListener(x -> {
                Intent i = new Intent(act, AlbumActivity.class);
                i.putExtra("kind", a.kind);
                i.putExtra("bucket", a.bucketId);
                i.putExtra("name", a.name);
                act.startActivity(i);
            });
        }

        @Override
        public void onViewRecycled(RecyclerView.ViewHolder h) {
            ImageView iv = h.itemView.findViewById(R.id.album_cover);
            if (iv != null) Thumbs.clear(iv);
        }
    }
}
