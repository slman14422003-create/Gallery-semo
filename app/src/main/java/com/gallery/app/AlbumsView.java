package com.gallery.app;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.List;

/** The Albums tab: smart albums (favourites, videos, screenshots) followed by every folder, as cover cards. */
public class AlbumsView extends LinearLayout {
    private final BaseActivity act;
    private final TextView subtitle;
    private final RecyclerView rv;
    private final List<Album> albums = new ArrayList<>();
    private final Adapter ad = new Adapter();
    private final TextView empty;

    public AlbumsView(BaseActivity a) {
        super(a);
        act = a;
        setOrientation(VERTICAL);
        setBackgroundColor(Ui.color(a, R.color.bg));
        View header = LayoutInflater.from(a).inflate(R.layout.view_header, this, false);
        addView(header);
        ((TextView) header.findViewById(R.id.title)).setText(R.string.nav_albums);
        subtitle = header.findViewById(R.id.subtitle);
        header.findViewById(R.id.btnBack).setVisibility(GONE);
        header.findViewById(R.id.btnRefresh).setVisibility(GONE);

        FrameLayout body = new FrameLayout(a);
        addView(body, new LayoutParams(-1, 0, 1f));
        rv = new RecyclerView(a);
        int span = a.getResources().getConfiguration().screenWidthDp >= 600 ? 3 : 2;
        rv.setLayoutManager(new GridLayoutManager(a, span));
        rv.setAdapter(ad);
        rv.setClipToPadding(false);
        rv.setPadding(Ui.dp(a, 12), Ui.dp(a, 4), Ui.dp(a, 12), Ui.dp(a, 96));
        rv.setOverScrollMode(OVER_SCROLL_NEVER);
        final int gap = Ui.dp(a, 6);
        rv.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(Rect out, View v, RecyclerView p, RecyclerView.State s) {
                out.set(gap, gap, gap, gap);
            }
        });
        body.addView(rv, new FrameLayout.LayoutParams(-1, -1));
        empty = new TextView(a);
        empty.setText(R.string.empty_library);
        empty.setTextColor(Ui.color(a, R.color.text_secondary));
        empty.setGravity(Gravity.CENTER);
        empty.setTextSize(15);
        empty.setVisibility(GONE);
        body.addView(empty, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        refresh();
    }

    public void scrollToTop() {
        rv.smoothScrollToPosition(0);
    }

    public void refresh() {
        albums.clear();
        albums.addAll(Library.albums(act));
        ad.notifyDataSetChanged();
        subtitle.setText(act.getString(R.string.n_albums, albums.size()));
        empty.setVisibility(albums.isEmpty() && Library.loaded ? VISIBLE : GONE);
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public int getItemCount() {
            return albums.size();
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
            Context c = parent.getContext();
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(0, 0, 0, Ui.dp(c, 10));   // iOS albums: bare rounded cover with the name underneath, no card

            FrameLayout cover = new FrameLayout(c) {
                @Override
                protected void onMeasure(int w, int h) {
                    super.onMeasure(w, w);
                }
            };
            cover.setClipToOutline(true);
            cover.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.dp(v.getContext(), 10));
                }
            });
            cover.setBackgroundColor(Ui.color(c, R.color.surface_high));
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setId(R.id.album_cover);
            cover.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            ImageView kind = new ImageView(c);
            kind.setId(R.id.album_kind);
            kind.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
            int pad = Ui.dp(c, 6);
            kind.setPadding(pad, pad, pad, pad);
            android.graphics.drawable.GradientDrawable kg = new android.graphics.drawable.GradientDrawable();
            kg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            kg.setColor(0x88000000);
            kind.setBackground(kg);
            FrameLayout.LayoutParams kp = new FrameLayout.LayoutParams(Ui.dp(c, 30), Ui.dp(c, 30), Gravity.TOP | Gravity.START);
            kp.setMargins(Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8));
            cover.addView(kind, kp);
            card.addView(cover, new LinearLayout.LayoutParams(-1, -2));

            TextView name = new TextView(c);
            name.setId(R.id.album_name);
            name.setTextColor(Ui.color(c, R.color.text_primary));
            name.setTextSize(15);
            name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            name.setPadding(Ui.dp(c, 2), Ui.dp(c, 8), Ui.dp(c, 2), 0);
            card.addView(name);
            TextView count = new TextView(c);
            count.setId(R.id.album_count);
            count.setTextColor(Ui.color(c, R.color.text_secondary));
            count.setTextSize(13);
            count.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            count.setPadding(Ui.dp(c, 2), Ui.dp(c, 1), Ui.dp(c, 2), 0);
            card.addView(count);
            card.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            Ui.press(c, card);
            return new RecyclerView.ViewHolder(card) { };
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder h, int p) {
            final Album a = albums.get(p);
            View v = h.itemView;
            ImageView iv = v.findViewById(R.id.album_cover);
            ImageView kind = v.findViewById(R.id.album_kind);
            ((TextView) v.findViewById(R.id.album_name)).setText(a.name);
            ((TextView) v.findViewById(R.id.album_count)).setText(
                    a.videos > 0 && a.videos < a.count
                            ? act.getString(R.string.album_count_mixed, a.count - a.videos, a.videos)
                            : a.videos == a.count ? act.getString(R.string.album_count_videos, a.count)
                            : act.getString(R.string.album_count_photos, a.count));
            if (a.cover != null) Thumbs.load(iv, a.cover, 420);
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
