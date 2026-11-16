package com.gallery.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The photo grid: date sections, pinch to change the column count, long-press multi-select with
 * share / favourite / delete, filter chips and an optional search field. Used for the Photos tab,
 * for every album, and for search results.
 */
public class MediaGrid extends LinearLayout {
    public static final int PHOTOS = 0, ALBUM = 1, SEARCH = 2;
    /** Filtering + sorting + grouping thousands of items ran on the UI thread right as the viewer / editor
     *  closed, which is exactly when a dropped frame is most visible; one shared background thread now does
     *  that work for every grid (Photos, Search, every album) while the UI stays responsive. */
    private static final ExecutorService BG = Executors.newSingleThreadExecutor();

    private final BaseActivity act;
    private final int mode;
    private final View header;
    private final TextView title, subtitle;
    private final ImageButton btnBack, btnA1, btnA2, btnDel, btnAll;
    private final ImageView headerIcon;
    private final LinearLayout chipRow;
    private final HorizontalScrollView chipScroll;
    private EditText searchBox;
    private final RecyclerView rv;
    private final GridLayoutManager glm;
    private final Adapter ad = new Adapter();
    private final LinearLayout emptyView;
    private final TextView emptyText;
    private final SpinnerView spinner;

    private List<Media> source = new ArrayList<>();
    private final List<Media> shown = new ArrayList<>();
    private final List<Object> items = new ArrayList<>();
    private final Set<String> sel = new LinkedHashSet<>();
    private boolean selecting;
    private boolean dragSelect;          // finger is sliding over tiles after a long press (iOS swipe-to-select)
    private int lastDragPos = -1;
    private int filter;                 // 0 all, 1 photos, 2 videos, 3 favourites
    private String query = "";
    private int cols;
    private String baseTitle = "";
    private Runnable onBack;
    private Runnable onSelectChange;
    private final List<TextView> chips = new ArrayList<>();
    private boolean built;                       // the list has been filled at least once
    private int rebuildGen;                       // bumps on every rebuild(); a background pass that finishes
                                                    // after a newer one started is discarded instead of applied
    private final Runnable applyQuery = () -> rebuild(false);

    public MediaGrid(BaseActivity a, int mode) {
        super(a);
        this.act = a;
        this.mode = mode;
        setOrientation(VERTICAL);
        setBackgroundColor(Ui.color(a, R.color.bg));

        header = LayoutInflater.from(a).inflate(R.layout.view_header, this, false);
        addView(header);
        title = header.findViewById(R.id.title);
        subtitle = header.findViewById(R.id.subtitle);
        btnBack = header.findViewById(R.id.btnBack);
        btnA1 = header.findViewById(R.id.btnA1);
        btnA2 = header.findViewById(R.id.btnA2);
        btnDel = header.findViewById(R.id.btnRefresh);
        headerIcon = header.findViewById(R.id.headerIcon);
        btnAll = new ImageButton(a, null, 0, R.style.IconButton);
        btnAll.setImageResource(R.drawable.ic_check_circle);
        btnAll.setContentDescription(a.getString(R.string.select_all));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(Ui.dp(a, 40), Ui.dp(a, 40));
        ap.setMarginStart(Ui.dp(a, 8));
        ((LinearLayout) header).addView(btnAll, ((LinearLayout) header).indexOfChild(btnA1), ap);
        btnAll.setVisibility(GONE);
        btnA1.setVisibility(VISIBLE);
        btnA2.setVisibility(VISIBLE);

        // filter chips
        chipScroll = new HorizontalScrollView(a);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setOverScrollMode(OVER_SCROLL_NEVER);
        chipRow = new LinearLayout(a);
        chipRow.setOrientation(HORIZONTAL);
        chipRow.setPaddingRelative(Ui.dp(a, 16), Ui.dp(a, 6), Ui.dp(a, 16), Ui.dp(a, 10));
        chipScroll.addView(chipRow);
        addView(chipScroll, new LayoutParams(-1, -2));
        int[] names = {R.string.f_all, R.string.f_photos, R.string.f_videos, R.string.f_favorites};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView ch = Ui.chip(a, a.getString(names[i]), i == 0);
            ch.setOnClickListener(v -> setFilter(idx));
            chips.add(ch);
            chipRow.addView(ch);
        }

        if (mode == SEARCH) {
            searchBox = Ui.edit(a, a.getString(R.string.search_hint), null);
            searchBox.setSingleLine(true);
            searchBox.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
            sp.setMargins(Ui.dp(a, 16), Ui.dp(a, 2), Ui.dp(a, 16), Ui.dp(a, 8));
            addView(searchBox, indexOfChild(chipScroll), sp);
            searchBox.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }

                @Override
                public void afterTextChanged(Editable e) {
                    query = e.toString().trim().toLowerCase(Locale.getDefault());
                    // wait for a short pause in typing instead of re-sorting the whole library on every key
                    removeCallbacks(applyQuery);
                    postDelayed(applyQuery, query.isEmpty() ? 0 : 160);
                }
            });
        }

        FrameLayout body = new FrameLayout(a);
        addView(body, new LayoutParams(-1, 0, 1f));

        cols = Store.intPref(a, "cols", a.getResources().getConfiguration().screenWidthDp >= 600 ? 5 : 3);
        rv = new RecyclerView(a);
        glm = new GridLayoutManager(a, cols);
        glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return position < items.size() && items.get(position) instanceof String ? glm.getSpanCount() : 1;
            }
        });
        rv.setLayoutManager(glm);
        rv.setAdapter(ad);
                rv.setItemViewCacheSize(30);
        rv.setHasFixedSize(true);
        rv.setDrawingCacheEnabled(false);
        rv.setClipToPadding(false);
        rv.setPadding(Ui.dp(a, 1), 0, Ui.dp(a, 1), Ui.dp(a, 96));   // bottom room so the last row clears the floating tab bar
        rv.setOverScrollMode(OVER_SCROLL_NEVER);
        rv.getRecycledViewPool().setMaxRecycledViews(1, 60);
        RecyclerView.ItemAnimator ia = rv.getItemAnimator();
        if (ia != null) {
            ia.setAddDuration(120);
            ia.setRemoveDuration(100);
            ia.setMoveDuration(160);
            if (ia instanceof SimpleItemAnimator) ((SimpleItemAnimator) ia).setSupportsChangeAnimations(false);   // no flicker on updates
        }
        final int gap = Math.max(1, Math.round(a.getResources().getDisplayMetrics().density * 0.5f));   // 1dp between tiles
        rv.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(Rect out, View v, RecyclerView p, RecyclerView.State s) {
                out.set(gap, gap, gap, gap);
            }
        });
        body.addView(rv, new FrameLayout.LayoutParams(-1, -1));

        // pinch to change the column count
        final ScaleGestureDetector sgd = new ScaleGestureDetector(a, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private float acc = 1f;

            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                acc = 1f;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector d) {
                acc *= d.getScaleFactor();
                if (acc > 1.3f && cols > 2) {
                    setCols(cols - 1);
                    acc = 1f;
                } else if (acc < 0.77f && cols < 7) {
                    setCols(cols + 1);
                    acc = 1f;
                }
                return true;
            }
        });
        rv.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(RecyclerView r, MotionEvent e) {
                sgd.onTouchEvent(e);
                if (dragSelect) {
                    int act = e.getActionMasked();
                    if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                        dragSelect = false;
                        return false;
                    }
                    return act == MotionEvent.ACTION_MOVE;   // take over so the list does not scroll while selecting
                }
                return false;
            }

            @Override
            public void onTouchEvent(RecyclerView r, MotionEvent e) {
                if (!dragSelect) return;
                int act = e.getActionMasked();
                if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                    dragSelect = false;
                    return;
                }
                if (act != MotionEvent.ACTION_MOVE) return;
                View v = r.findChildViewUnder(e.getX(), e.getY());
                if (v == null) return;
                int pos = r.getChildAdapterPosition(v);
                if (pos < 0 || pos == lastDragPos || pos >= items.size() || !(items.get(pos) instanceof Media)) return;
                lastDragPos = pos;
                Media m = (Media) items.get(pos);
                if (sel.add(m.key())) {
                    ad.notifyItemChanged(pos, "sel");
                    updateHeader();
                }
            }
        });

        emptyView = new LinearLayout(a);
        emptyView.setOrientation(VERTICAL);
        emptyView.setGravity(Gravity.CENTER);
        ImageView ei = new ImageView(a);
        ei.setImageResource(R.drawable.ic_image);
        ei.setImageTintList(ColorStateList.valueOf(Ui.color(a, R.color.text_hint)));
        emptyView.addView(ei, new LinearLayout.LayoutParams(Ui.dp(a, 56), Ui.dp(a, 56)));
        emptyText = new TextView(a);
        emptyText.setTextColor(Ui.color(a, R.color.text_secondary));
        emptyText.setTextSize(15);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setPadding(Ui.dp(a, 32), Ui.dp(a, 12), Ui.dp(a, 32), 0);
        emptyView.addView(emptyText);
        emptyView.setVisibility(GONE);
        body.addView(emptyView, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        spinner = new SpinnerView(a);
        body.addView(spinner, new FrameLayout.LayoutParams(Ui.dp(a, 44), Ui.dp(a, 44), Gravity.CENTER));
        spinner.setVisibility(INVISIBLE);

        btnBack.setOnClickListener(v -> {
            if (selecting) exitSelect();
            else if (onBack != null) onBack.run();
        });
        btnA1.setOnClickListener(v -> {
            if (selecting) Actions.share(act, selected());
            else enterSelect();
        });
        btnA2.setOnClickListener(v -> {
            if (selecting) toggleFavSelected();
            else showOptions();
        });
        btnDel.setOnClickListener(v -> {
            if (selecting) act.trashOrDelete(selected(), this::exitSelect);
        });
        btnAll.setOnClickListener(v -> selectAll());

        applyMode();
        updateHeader();
    }

    // ------------------------------------------------------------------ configuration

    public void setTitle(String t) {
        baseTitle = t;
        updateHeader();
    }

    public void setOnSelectChange(Runnable r) {
        onSelectChange = r;
    }

    public boolean isSelecting() {
        return selecting;
    }

    private void selectChanged() {
        if (onSelectChange != null) onSelectChange.run();
    }

    public void setOnBack(Runnable r) {
        onBack = r;
        applyMode();
        updateHeader();
    }

    public void setSource(List<Media> list) {
        List<Media> next = list == null ? new ArrayList<Media>() : list;
        if (built && next == source) {
            // same list as before (coming back from the viewer / settings): nothing to re-sort, just refresh the favourite marks
            refreshMarks();
            if (filter == 3) rebuild(true);
            return;
        }
        source = next;
        // drop selections of items that are gone
        Set<String> keys = new LinkedHashSet<>();
        for (Media m : source) keys.add(m.key());
        sel.retainAll(keys);
        if (sel.isEmpty() && selecting) {
            selecting = false;
            selectChanged();
        }
        rebuild(built);
    }

    /** Re-reads favourite / selection state of the visible tiles without reloading any picture. */
    public void refreshMarks() {
        if (!items.isEmpty()) ad.notifyItemRangeChanged(0, items.size(), "sel");
    }

    /** Consumes the back press while selecting. */
    public boolean handleBack() {
        if (selecting) {
            exitSelect();
            return true;
        }
        return false;
    }

    public void focusSearch2() {
        if (searchBox != null) {
            searchBox.requestFocus();
            android.view.inputmethod.InputMethodManager im =
                    (android.view.inputmethod.InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (im != null) im.showSoftInput(searchBox, 0);
        }
    }

    public void scrollToTop() {
        if (glm.findFirstVisibleItemPosition() > 40) rv.scrollToPosition(12);   // long way up: jump close, then glide
        rv.smoothScrollToPosition(0);
    }

    private void applyMode() {
        btnBack.setVisibility(onBack != null ? VISIBLE : GONE);
        headerIcon.setVisibility(GONE);
        chipScroll.setVisibility(mode == SEARCH ? GONE : VISIBLE);
    }

    // ------------------------------------------------------------------ data

    private void setFilter(int f) {
        filter = f;
        for (int i = 0; i < chips.size(); i++) Ui.setChip(act, chips.get(i), i == f);
        rebuild();
    }

    private void setCols(int n) {
        cols = n;
        Store.setIntPref(act, "cols", n);
        glm.setSpanCount(n);
        ad.notifyDataSetChanged();
    }

    /** {@code f}/{@code q} are a snapshot taken before the (possibly background) rebuild pass started,
     *  so this never reads the live {@link #filter} / {@link #query} fields while they could be changing. */
    private boolean matches(Media m, int f, String q) {
        if (f == 1 && m.video) return false;
        if (f == 2 && !m.video) return false;
        if (f == 3 && !Library.isFav(act, m)) return false;
        if (!q.isEmpty()) {
            String hay = (m.name + " " + m.bucket + " " + m.mime + " "
                    + (m.video ? "video فيديو mp4" : "image photo صورة") + (m.isGif() ? " gif" : "") + " "
                    + new SimpleDateFormat("yyyy MMMM MM-dd", Locale.getDefault()).format(new Date(m.date())))
                    .toLowerCase(Locale.getDefault());
            for (String tok : q.toLowerCase(Locale.getDefault()).split("\\s+")) if (!hay.contains(tok)) return false;
        }
        return true;
    }

    private void rebuild() {
        rebuild(false);
    }

    /** {@code diff}: animate only what changed (library reloads) instead of redrawing the whole grid.
     *  The filter / sort / group pass (and the DiffUtil comparison for a library reload) run on a
     *  background thread; only applying the finished result to the adapter happens on the UI thread,
     *  so a large library no longer drops frames right when a screen closes back into the grid. */
    private void rebuild(boolean diff) {
        final int gen = ++rebuildGen;
        final boolean needQuery = mode == SEARCH && query.isEmpty();
        if (needQuery) {
            // Nothing to compute: empty "type to search" state, cheap enough to do inline.
            shown.clear();
            items.clear();
            built = true;
            ad.notifyDataSetChanged();
            applyEmptyState(true);
            return;
        }
        final List<Object> old = diff ? new ArrayList<Object>(items) : null;
        final List<Media> srcSnapshot = new ArrayList<>(source);
        final int f = filter;
        final String q = query;
        final boolean byAdded = Store.intPref(act, "sort", 0) == 1;
        final int gm = Store.intPref(act, "group", 1);

        BG.execute(() -> {
            List<Media> newShown = new ArrayList<>();
            List<Object> newItems = new ArrayList<>();
            DiffUtil.DiffResult diffResult = null;
            try {
                for (Media m : srcSnapshot) if (matches(m, f, q)) newShown.add(m);
                if (byAdded) {
                    Collections.sort(newShown, (a, b) -> Long.compare(Math.max(b.added, 0), Math.max(a.added, 0)));
                } else {
                    Collections.sort(newShown, (a, b) -> Long.compare(b.date(), a.date()));
                }
                String last = null;
                for (Media m : newShown) {
                    if (gm != 0) {
                        long t = byAdded && m.added > 0 ? m.added * 1000L : m.date();
                        String h = header(t, gm);
                        if (!h.equals(last)) {
                            newItems.add(h);
                            last = h;
                        }
                    }
                    newItems.add(m);
                }
                if (old != null && !old.isEmpty() && !newItems.isEmpty() && old.size() + newItems.size() < 30000) {
                    diffResult = DiffUtil.calculateDiff(new DiffCb(old, newItems), false);
                }
            } catch (Throwable t) {
                newShown = new ArrayList<>(srcSnapshot);
                newItems = new ArrayList<Object>(srcSnapshot);
                diffResult = null;
            }
            final List<Media> shownNow = newShown;
            final List<Object> itemsNow = newItems;
            final DiffUtil.DiffResult finalDiff = diffResult;
            act.runOnUiThread(() -> {
                if (gen != rebuildGen) return;   // superseded by a newer rebuild; drop this result
                shown.clear();
                shown.addAll(shownNow);
                items.clear();
                items.addAll(itemsNow);
                built = true;
                if (finalDiff != null) {
                    try {
                        finalDiff.dispatchUpdatesTo(ad);
                        refreshMarks();
                    } catch (RuntimeException e) {
                        ad.notifyDataSetChanged();
                    }
                } else {
                    ad.notifyDataSetChanged();
                }
                applyEmptyState(false);
            });
        });
    }

    private void applyEmptyState(boolean needQuery) {
        boolean empty = shown.isEmpty();
        boolean loading = !Library.loaded && mode != SEARCH;
        emptyView.setVisibility(empty && !loading ? VISIBLE : GONE);
        spinner.setVisibility(loading && empty ? VISIBLE : INVISIBLE);
        if (needQuery) emptyText.setText(R.string.search_start);
        else emptyText.setText(source.isEmpty() ? R.string.empty_library : R.string.empty_filter);
        updateHeader();
    }

    private static final class DiffCb extends DiffUtil.Callback {
        private final List<Object> old, next;

        DiffCb(List<Object> old, List<Object> next) {
            this.old = old;
            this.next = next;
        }

        @Override
        public int getOldListSize() {
            return old.size();
        }

        @Override
        public int getNewListSize() {
            return next.size();
        }

        @Override
        public boolean areItemsTheSame(int o, int n) {
            Object x = old.get(o), y = next.get(n);
            if (x instanceof Media) return y instanceof Media && ((Media) x).key().equals(((Media) y).key());
            return x instanceof String && x.equals(y);
        }

        @Override
        public boolean areContentsTheSame(int o, int n) {
            Object x = old.get(o), y = next.get(n);
            if (!(x instanceof Media) || !(y instanceof Media)) return true;
            Media a = (Media) x, b = (Media) y;
            return a.modified == b.modified && a.size == b.size && a.duration == b.duration && a.name.equals(b.name);
        }
    }

    private String header(long t, int gm) {
        Calendar now = Calendar.getInstance(), c = Calendar.getInstance();
        c.setTimeInMillis(t);
        if (gm == 2) {
            return new SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(new Date(t));
        }
        if (now.get(Calendar.YEAR) == c.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)) {
            return act.getString(R.string.today);
        }
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (now.get(Calendar.YEAR) == c.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)) {
            return act.getString(R.string.yesterday);
        }
        boolean thisYear = Calendar.getInstance().get(Calendar.YEAR) == c.get(Calendar.YEAR);
        return new SimpleDateFormat(thisYear ? "EEEE d MMMM" : "d MMMM yyyy", Locale.getDefault()).format(new Date(t));
    }

    // ------------------------------------------------------------------ header

    private void updateHeader() {
        if (selecting) {
            title.setText(act.getString(R.string.n_selected, sel.size()));
            subtitle.setText("");
            subtitle.setVisibility(GONE);
            btnBack.setVisibility(VISIBLE);
            btnBack.setImageResource(R.drawable.ic_cancel);
            headerIcon.setVisibility(GONE);
            btnAll.setVisibility(VISIBLE);
            btnA1.setImageResource(R.drawable.ic_share);
            btnA1.setContentDescription(act.getString(R.string.share));
            boolean allFav = Library.allFav(act, selected());
            btnA2.setImageResource(allFav ? R.drawable.ic_heart_fill : R.drawable.ic_heart);
            btnA2.setContentDescription(act.getString(R.string.favorite));
            btnDel.setVisibility(VISIBLE);
            btnDel.setImageResource(R.drawable.ic_delete);
            btnDel.setContentDescription(act.getString(R.string.delete));
            return;
        }
        btnAll.setVisibility(GONE);
        subtitle.setVisibility(VISIBLE);
        btnBack.setImageResource(R.drawable.ic_back);
        btnBack.setVisibility(onBack != null ? VISIBLE : GONE);
        headerIcon.setVisibility(GONE);
        title.setText(mode == SEARCH ? act.getString(R.string.nav_search) : baseTitle.isEmpty() ? act.getString(R.string.app_name) : baseTitle);
        int photos = 0, videos = 0;
        for (Media m : shown) {
            if (m.video) videos++;
            else photos++;
        }
        subtitle.setText(act.getString(R.string.counts, photos, videos));
        btnA1.setImageResource(R.drawable.ic_check_circle);
        btnA1.setContentDescription(act.getString(R.string.select));
        btnA2.setImageResource(R.drawable.ic_sort);
        btnA2.setContentDescription(act.getString(R.string.view_options));
        btnDel.setVisibility(GONE);
        btnA1.setVisibility(shown.isEmpty() ? INVISIBLE : VISIBLE);
    }

    // ------------------------------------------------------------------ selection

    private List<Media> selected() {
        List<Media> out = new ArrayList<>();
        for (Media m : shown) if (sel.contains(m.key())) out.add(m);
        return out;
    }

    private void enterSelect() {
        selecting = true;
        selectChanged();
        updateHeader();
        ad.notifyItemRangeChanged(0, items.size(), "sel");
    }

    private void exitSelect() {
        selecting = false;
        sel.clear();
        selectChanged();
        updateHeader();
        ad.notifyItemRangeChanged(0, items.size(), "sel");
    }

    private void toggle(Media m) {
        if (!sel.remove(m.key())) sel.add(m.key());
        if (sel.isEmpty()) {
            exitSelect();
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) == m) {
                ad.notifyItemChanged(i, "sel");
                break;
            }
        }
        updateHeader();
    }

    private void selectAll() {
        if (sel.size() == shown.size()) {
            exitSelect();
            return;
        }
        for (Media m : shown) sel.add(m.key());
        updateHeader();
        ad.notifyItemRangeChanged(0, items.size(), "sel");
    }

    private void toggleFavSelected() {
        List<Media> l = selected();
        boolean on = !Library.allFav(act, l);
        Library.setFavs(act, l, on);
        Toast.makeText(act, on ? R.string.fav_added : R.string.fav_removed, Toast.LENGTH_SHORT).show();
        exitSelect();
        rebuild();
    }

    // ------------------------------------------------------------------ view options dialog

    private void showOptions() {
        final LinearLayout box = Ui.box(act);
        addChoice(box, R.string.opt_group, new int[]{R.string.opt_group_none, R.string.opt_group_day, R.string.opt_group_month},
                Store.intPref(act, "group", 1), v -> {
                    Store.setIntPref(act, "group", v);
                    rebuild();
                });
        addChoice(box, R.string.opt_sort, new int[]{R.string.opt_sort_taken, R.string.opt_sort_added},
                Store.intPref(act, "sort", 0), v -> {
                    Store.setIntPref(act, "sort", v);
                    rebuild();
                });
        String[] cn = {"2", "3", "4", "5", "6"};
        box.addView(Ui.label(act, act.getString(R.string.opt_columns)));
        final LinearLayout row = new LinearLayout(act);
        row.setOrientation(HORIZONTAL);
        final List<TextView> cc = new ArrayList<>();
        for (int i = 0; i < cn.length; i++) {
            final int val = i + 2;
            final TextView t = Ui.chip(act, cn[i], cols == val);
            t.setOnClickListener(v -> {
                setCols(val);
                for (int k = 0; k < cc.size(); k++) Ui.setChip(act, cc.get(k), k + 2 == val);
            });
            cc.add(t);
            row.addView(t);
        }
        box.addView(row);
        new Dlg(act).setTitle(R.string.view_options).setView(box).setPositiveButton(R.string.done, null).show();
    }

    private interface IntCb {
        void on(int v);
    }

    private void addChoice(LinearLayout box, int label, int[] names, int current, final IntCb cb) {
        box.addView(Ui.label(act, act.getString(label)));
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(HORIZONTAL);
        final List<TextView> cs = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = Ui.chip(act, act.getString(names[i]), i == current);
            t.setOnClickListener(v -> {
                for (int k = 0; k < cs.size(); k++) Ui.setChip(act, cs.get(k), k == idx);
                cb.on(idx);
            });
            cs.add(t);
            row.addView(t);
        }
        HorizontalScrollView hs = new HorizontalScrollView(act);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(row);
        box.addView(hs);
    }

    // ------------------------------------------------------------------ adapter

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public int getItemCount() {
            return items.size();
        }

        @Override
        public int getItemViewType(int p) {
            return items.get(p) instanceof String ? 0 : 1;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
            Context c = parent.getContext();
            if (type == 0) {
                TextView t = new TextView(c);
                t.setTextColor(Ui.color(c, R.color.text_primary));
                t.setTextSize(19);
                t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
                t.setPaddingRelative(Ui.dp(c, 15), Ui.dp(c, 20), Ui.dp(c, 15), Ui.dp(c, 8));
                t.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
                return new RecyclerView.ViewHolder(t) { };
            }
            Tile tile = new Tile(c);
            tile.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            return new RecyclerView.ViewHolder(tile) { };
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder h, int p) {
            Object o = items.get(p);
            if (o instanceof String) {
                ((TextView) h.itemView).setText((String) o);
                return;
            }
            final Media m = (Media) o;
            final Tile t = (Tile) h.itemView;
            int px = Math.max(120, getResources().getDisplayMetrics().widthPixels / Math.max(1, cols));
            t.setScaleX(1f);
            t.setScaleY(1f);
            t.bind(m, px, Library.isFav(act, m));
            t.setSelection(selecting, sel.contains(m.key()), false);
            // Resolve the item from the holder's CURRENT position when tapped. A partial ("sel") rebind or a
            // DiffUtil move keeps this listener while the library reload replaced every Media instance, so a
            // captured `m` went stale and taps silently did nothing until the whole grid was rebound.
            final RecyclerView.ViewHolder holder = h;
            t.setOnClickListener(v -> {
                Media cur = mediaAt(holder);
                if (cur == null) return;
                if (selecting) toggle(cur);
                else open(cur);
            });
            t.setOnLongClickListener(v -> {
                Media cur = mediaAt(holder);
                if (cur == null) return true;
                if (!selecting) enterSelect();
                if (!sel.contains(cur.key())) toggle(cur);   // long press selects; sliding the finger keeps adding tiles
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                dragSelect = true;
                lastDragPos = items.indexOf(cur);
                return true;
            });
        }

        private Media mediaAt(RecyclerView.ViewHolder h) {
            int pos = h.getBindingAdapterPosition();
            if (pos < 0 || pos >= items.size()) return null;
            Object o = items.get(pos);
            return o instanceof Media ? (Media) o : null;
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder h, int p, List<Object> payloads) {
            if (!payloads.isEmpty() && h.itemView instanceof Tile && p < items.size() && items.get(p) instanceof Media) {
                Media m = (Media) items.get(p);
                Tile t = (Tile) h.itemView;
                t.setFav(Library.isFav(act, m));
                t.setSelection(selecting, sel.contains(m.key()), true);
                return;
            }
            super.onBindViewHolder(h, p, payloads);
        }

        @Override
        public void onViewRecycled(RecyclerView.ViewHolder h) {
            if (h.itemView instanceof Tile) Thumbs.clear(((Tile) h.itemView).thumb);
        }
    }

    private void open(Media m) {
        int idx = -1;
        for (int i = 0; i < shown.size(); i++) {
            if (shown.get(i).key().equals(m.key())) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return;
        Actions.openViewer(act, shown, idx);
    }
}
